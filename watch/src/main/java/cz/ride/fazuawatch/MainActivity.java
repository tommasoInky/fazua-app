package cz.ride.fazuawatch;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Minimal standalone Wear OS scanner/display for FAZUA Ride 60.
 * No vendor protocol is invented; motor watts and assist mode require a confirmed decoder.
 * This program never writes motor commands or changes drive settings.
 */
public final class MainActivity extends Activity {
    private static final UUID SERVICE_POWER = uuid16(0x1818);
    private static final UUID CHAR_POWER = uuid16(0x2A63);
    private static final UUID SERVICE_BATTERY = uuid16(0x180F);
    private static final UUID CHAR_BATTERY = uuid16(0x2A19);
    private static final UUID SERVICE_CSC = uuid16(0x1816);
    private static final UUID CHAR_CSC = uuid16(0x2A5B);
    private static final UUID CCC = uuid16(0x2902);
    // These two vendor services are actually present on the user's PATH RIOT / RIDE 60.
    // Their payloads have not been decoded, so we log them without guessing values.
    private static final UUID SERVICE_SIGMA = uuid16(0xFD6D);
    private static final UUID SERVICE_VENDOR = UUID.fromString("f1480001-0cb6-4a08-9e5d-e53e946dd203");
    private static final int REQUEST_BLE = 79;
    private static UUID uuid16(int n) {
        return UUID.fromString(String.format(Locale.US,
                "0000%04x-0000-1000-8000-00805f9b34fb", n));
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable cadenceTimeout = new Runnable() {
        @Override public void run() {
            if (gatt != null && cadence != null && cadence > 0 &&
                    lastCrankMillis > 0 && System.currentTimeMillis() - lastCrankMillis > 5000) {
                cadence = 0;
                render();
            }
            main.postDelayed(this, 2000);
        }
    };
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private boolean scanning = false;
    private boolean debug = false;
    private String state = "Odpojeno";
    private Integer battery, riderWatts, cadence;
    private TelemetryParser.CrankEvent lastPowerCrank, lastCscCrank;
    private long lastCrankMillis;
    private final Map<String, BluetoothDevice> found = new LinkedHashMap<>();
    private final ArrayDeque<Runnable> ops = new ArrayDeque<>();
    private boolean busy;
    private final StringBuilder log = new StringBuilder();
    private TextView statusView, batteryView, wattsView, cadenceView, motorView, diagnosticView;
    private LinearLayout devicesView;
    private Button scanButton, debugButton;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
        createUi();
        append("Fazua Monitor / prototyp 0.3");
        append("Potvrzeny profil PATH RIOT: 180F/2A19, 1818/2A63, 1816/2A5B, FD6D, f148...");
        main.postDelayed(cadenceTimeout, 2000);
        render();
    }

    private TextView text(String content, int size, int color) {
        TextView v = new TextView(this);
        v.setText(content);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER);
        v.setPadding(4, 4, 4, 4);
        return v;
    }
    private Button button(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextAllCaps(false);
        b.setOnClickListener(listener);
        return b;
    }
    private void createUi() {
        final int white = Color.WHITE;
        final int soft = Color.rgb(172, 194, 202);
        final int mint = Color.rgb(93, 231, 182);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(8, 16, 20));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(19, 24, 19, 30);
        root.addView(text("FAZUA MONITOR", 17, mint));
        statusView = text("Odpojeno", 12, soft);
        root.addView(statusView);
        root.addView(text("BATERIE KOLA", 11, soft));
        batteryView = text("-- %", 35, white);
        root.addView(batteryView);
        root.addView(text("VÝKON JEZDCE", 11, soft));
        wattsView = text("-- W", 30, white);
        root.addView(wattsView);
        root.addView(text("KADENCE", 11, soft));
        cadenceView = text("-- ot/min", 23, white);
        root.addView(cadenceView);
        root.addView(text("MOTOR A DOPOMOC", 11, soft));
        motorView = text("Zatím neověřeno", 14, soft);
        root.addView(motorView);
        scanButton = button("Vyhledat kolo", v -> startScan());
        root.addView(scanButton);
        root.addView(button("Odpojit", v -> disconnect()));
        devicesView = new LinearLayout(this);
        devicesView.setOrientation(LinearLayout.VERTICAL);
        root.addView(devicesView);
        debugButton = button("Zobrazit diagnostiku", v -> {
            debug = !debug;
            debugButton.setText(debug ? "Skrýt diagnostiku" : "Zobrazit diagnostiku");
            diagnosticView.setVisibility(debug ? View.VISIBLE : View.GONE);
        });
        root.addView(debugButton);
        root.addView(button("Diagnostika Bluetooth", v -> startVendorProbe()));
        diagnosticView = text("", 10, soft);
        diagnosticView.setGravity(Gravity.START);
        diagnosticView.setVisibility(View.GONE);
        root.addView(diagnosticView);
        root.addView(text("Motor se neovládá. Údaje -- nejsou měřené.", 10, soft));
        scroll.addView(root);
        setContentView(scroll);
    }
    private void render() {
        statusView.setText(state);
        batteryView.setText(battery == null ? "-- %" : battery + " %");
        wattsView.setText(riderWatts == null ? "-- W" : riderWatts + " W");
        cadenceView.setText(cadence == null ? "-- ot/min" : cadence + " ot/min");
        motorView.setText("Nutný FAZUA dekodér");
        diagnosticView.setText(log.toString());
        scanButton.setText(scanning ? "Hledám..." : "Vyhledat kolo");
    }
    private void append(String line) {
        android.util.Log.d("FazuaMonitor", line);
        if (log.length() > 11000) log.delete(0, log.length() - 7000);
        log.append(line).append('\n');
        if (diagnosticView != null) diagnosticView.setText(log.toString());
    }
    private String deviceName(BluetoothDevice device, ScanResult result) {
        String name = null;
        if (result.getScanRecord() != null) name = result.getScanRecord().getDeviceName();
        if (name == null || name.isEmpty()) {
            try { name = device.getName(); } catch (SecurityException ignored) { }
        }
        return name == null || name.isEmpty() ? "Neznámé BLE zařízení" : name;
    }
    private boolean permissionsOk() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
    private void askPermissions() {
        if (Build.VERSION.SDK_INT >= 31) requestPermissions(new String[]{
                Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BLE);
        else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_BLE);
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == REQUEST_BLE && permissionsOk()) startScan();
        else if (requestCode == REQUEST_BLE) { state = "Povol oprávnění Bluetooth"; render(); }
    }
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                main.post(() -> handleScanResult(result));
            } else handleScanResult(result);
        }
        private void handleScanResult(ScanResult result) {
            if (!scanning) return;
            BluetoothDevice d = result.getDevice();
            String id;
            try { id = d.getAddress(); } catch (SecurityException ex) { return; }
            if (!found.containsKey(id)) {
                found.put(id, d);
                String label = deviceName(d, result);
                append("Nalezeno: " + label + " / " + id);
                // Show all named devices, so the FAZUA name is not incorrectly assumed.
                if (found.size() <= 25) {
                    Button b = button(label + " (" + id.substring(Math.max(0, id.length()-5)) + ")", v -> connect(d, label));
                    devicesView.addView(b);
                }
            }
        }
        @Override public void onScanFailed(int errorCode) {
            main.post(() -> { scanning = false; state = "Chyba hledání " + errorCode; render(); });
        }
    };
    private void startScan() {
        if (!permissionsOk()) { askPermissions(); return; }
        if (adapter == null || !adapter.isEnabled()) {
            state = "Zapni Bluetooth na hodinkách"; render(); return;
        }
        stopScan();
        found.clear();
        devicesView.removeAllViews();
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { state = "Bluetooth scanner není dostupný"; render(); return; }
        try {
            scanner.startScan(null, new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback);
            scanning = true;
            state = "Hledám Bluetooth kola (12 s)";
            render();
            main.postDelayed(() -> { if (scanning) { stopScan(); state = "Vyber kolo ze seznamu"; render(); }}, 12000);
        } catch (SecurityException | IllegalStateException ex) {
            state = "Nelze skenovat: " + ex.getMessage(); append(state); render();
        }
    }
    private void stopScan() {
        if (scanning && scanner != null) {
            try { scanner.stopScan(scanCallback); } catch (Exception ignored) { }
        }
        scanning = false;
    }
    private void connect(BluetoothDevice device, String name) {
        stopScan();
        if (!permissionsOk()) { askPermissions(); return; }
        closeGatt();
        devicesView.removeAllViews();
        battery = null; riderWatts = null; cadence = null; lastPowerCrank = null; lastCscCrank = null;
        state = "Připojuji: " + name;
        append(state);
        render();
        try {
            gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) { state = "Nelze zahájit připojení"; render(); }
        } catch (SecurityException | IllegalArgumentException ex) {
            state = "Chyba připojení: " + ex.getMessage(); append(state); render();
        }
    }
    private void disconnect() {
        stopScan(); closeGatt();
        battery = null; riderWatts = null; cadence = null; lastPowerCrank = null; lastCscCrank = null;
        state = "Odpojeno"; render();
    }
    private void closeGatt() {
        ops.clear(); busy = false;
        if (gatt != null) {
            try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) { }
            gatt = null;
        }
    }
    private void enqueue(Runnable next) {
        ops.addLast(next); kickQueue();
    }
    private void kickQueue() {
        if (busy) return;
        Runnable next = ops.pollFirst();
        if (next == null) return;
        busy = true;
        next.run();
    }
    private void done() {
        busy = false;
        main.postDelayed(this::kickQueue, 60);
    }
    /** Subscribe to proprietary NOTIFY characteristics for diagnostics only.
     * The only GATT writes are standard Client Characteristic Configuration descriptors.
     */
    private void startVendorProbe() {
        BluetoothGatt current = gatt;
        if (current == null) { state = "Nejdřív se připoj ke kolu"; render(); return; }
        debug = true;
        debugButton.setText("Skrýt diagnostiku");
        diagnosticView.setVisibility(View.VISIBLE);
        int reads = 0, subscriptions = 0;
        // Restrict probing to the two detected data services. Never touch firmware/DFU.
        for (BluetoothGattService service : current.getServices()) {
            UUID serviceId = service.getUuid();
            if (!SERVICE_SIGMA.equals(serviceId) && !SERVICE_VENDOR.equals(serviceId)) continue;
            append("Diagnostika služby: " + serviceId);
            for (BluetoothGattCharacteristic ch : service.getCharacteristics()) {
                int properties = ch.getProperties();
                if ((properties & BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                    scheduleRead(current, ch);
                    reads++;
                }
                if ((properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                        && ch.getDescriptor(CCC) != null) {
                    scheduleNotify(current, ch);
                    subscriptions++;
                }
            }
        }
        append("Diagnostika: " + reads + " čtení, " + subscriptions + " odběrů notifikací; žádné povely motoru");
        render();
    }
    private void scheduleRead(BluetoothGatt g, BluetoothGattCharacteristic ch) {
        enqueue(() -> {
            if (gatt != g || !permissionsOk()) { done(); return; }
            try { if (!g.readCharacteristic(ch)) done(); }
            catch (Exception e) { append("Čtení selhalo: " + e); done(); }
        });
    }
    private void scheduleNotify(BluetoothGatt g, BluetoothGattCharacteristic ch) {
        enqueue(() -> {
            if (gatt != g || !permissionsOk()) { done(); return; }
            BluetoothGattDescriptor ccc = ch.getDescriptor(CCC);
            if (ccc == null) { append("Chybí CCC: " + ch.getUuid()); done(); return; }
            try {
                if (!g.setCharacteristicNotification(ch, true)) { done(); return; }
                boolean started;
                if (Build.VERSION.SDK_INT >= 33)
                    started = g.writeDescriptor(ccc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        == android.bluetooth.BluetoothStatusCodes.SUCCESS;
                else {
                    // Deprecated API needed only on Wear OS up to API 32.
                    ccc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    started = g.writeDescriptor(ccc);
                }
                if (!started) done();
            } catch (Exception e) { append("Notifikace selhala: " + e); done(); }
        });
    }
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            main.post(() -> {
                if (gatt != g) return;
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    state = "Připojeno; načítám služby"; append(state); render();
                    try { if (!g.discoverServices()) { state = "Nelze načíst BLE služby"; render(); } }
                    catch (SecurityException e) { state = "Chybí Bluetooth oprávnění"; render(); }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    state = "Spojení ukončeno (" + status + ")";
                    append(state); render(); closeGatt();
                }
            });
        }
        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            main.post(() -> {
                if (gatt != g) return;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    state = "Chyba služeb: " + status; render(); return;
                }
                append("--- Dostupné BLE služby a charakteristiky ---");
                for (BluetoothGattService s : g.getServices()) {
                    append("SLUŽBA " + s.getUuid());
                    for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                        append("  CHAR " + c.getUuid() + " vlastnosti=0x" +
                            Integer.toHexString(c.getProperties()));
                    }
                }
                BluetoothGattService power = g.getService(SERVICE_POWER);
                BluetoothGattService batteryService = g.getService(SERVICE_BATTERY);
                BluetoothGattService cscService = g.getService(SERVICE_CSC);
                if (power != null) {
                    BluetoothGattCharacteristic ch = power.getCharacteristic(CHAR_POWER);
                    if (ch != null) {
                        if ((ch.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0)
                            scheduleNotify(g, ch);
                        else if ((ch.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) != 0)
                            scheduleRead(g, ch);
                    }
                }
                if (batteryService != null) {
                    BluetoothGattCharacteristic ch = batteryService.getCharacteristic(CHAR_BATTERY);
                    if (ch != null) {
                        if ((ch.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) != 0) scheduleRead(g, ch);
                        if ((ch.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) scheduleNotify(g, ch);
                    }
                }
                if (cscService != null) {
                    BluetoothGattCharacteristic ch = cscService.getCharacteristic(CHAR_CSC);
                    if (ch != null && (ch.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0)
                        scheduleNotify(g, ch);
                }
                boolean any = power != null || batteryService != null || cscService != null;
                state = any ? "Připojeno • čtu dostupná data" : "Připojeno • pouze diagnostika";
                append("Standardní služby: CPS=" + (power != null) +
                    ", Battery=" + (batteryService != null) + ", CSC=" + (cscService != null));
                render();
            });
        }
        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            byte[] bytes = c.getValue();
            main.post(() -> { if (gatt == g) { if (status == BluetoothGatt.GATT_SUCCESS)
                valueReceived(c.getUuid(), bytes); done(); } });
        }
        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c,
                                                   byte[] value, int status) {
            main.post(() -> { if (gatt == g) { if (status == BluetoothGatt.GATT_SUCCESS)
                valueReceived(c.getUuid(), value); done(); } });
        }
        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            main.post(() -> { if (gatt == g) { append("Odběr " + d.getCharacteristic().getUuid()
                + (status == BluetoothGatt.GATT_SUCCESS ? " OK" : " chyba " + status)); done(); } });
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] copy = c.getValue() == null ? null : c.getValue().clone();
            main.post(() -> { if (gatt == g) valueReceived(c.getUuid(), copy); });
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g,
                              BluetoothGattCharacteristic c, byte[] value) {
            byte[] copy = value == null ? null : value.clone();
            main.post(() -> { if (gatt == g) valueReceived(c.getUuid(), copy); });
        }
    };
    private void valueReceived(UUID id, byte[] value) {
        if (value == null) return;
        append("DATA " + id + ": " + TelemetryParser.hex(value));
        if (CHAR_BATTERY.equals(id)) {
            int level = TelemetryParser.batteryLevel(value);
            if (level >= 0) battery = level;
        }
        if (CHAR_POWER.equals(id)) {
            TelemetryParser.PowerResult p = TelemetryParser.cyclingPower(value);
            if (p != null) {
                riderWatts = p.riderWatts;
                updateCrank(p.crank, false);
            }
        }
        if (CHAR_CSC.equals(id)) updateCrank(TelemetryParser.cyclingCadence(value), true);
        render();
    }
    private void updateCrank(TelemetryParser.CrankEvent c, boolean fromCsc) {
        if (c == null) return;
        TelemetryParser.CrankEvent previous = fromCsc ? lastCscCrank : lastPowerCrank;
        if (previous != null) {
            int value = TelemetryParser.rpm(previous, c);
            if (value >= 0) cadence = value;
        }
        // Keep the two independent BLE characteristic counters separate.
        if (previous == null || c.revolutions != previous.revolutions ||
                c.eventTime1024 != previous.eventTime1024) {
            lastCrankMillis = System.currentTimeMillis();
        }
        if (fromCsc) lastCscCrank = c;
        else lastPowerCrank = c;
    }
    @Override protected void onDestroy() {
        main.removeCallbacks(cadenceTimeout);
        stopScan(); closeGatt(); super.onDestroy();
    }
}
