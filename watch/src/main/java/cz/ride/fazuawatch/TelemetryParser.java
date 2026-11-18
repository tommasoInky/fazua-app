package cz.ride.fazuawatch;

/**
 * Only official Bluetooth SIG Cycling Power / CSC layouts are interpreted.
 * The proprietary FAZUA motor-power, assist-mode and battery formats are deliberately
 * NOT guessed; displaying unverified bytes as real ride values would be misleading.
 */
public final class TelemetryParser {
    private TelemetryParser() { }

    public static final class CrankEvent {
        public final int revolutions, eventTime1024;
        public CrankEvent(int rev, int time) { revolutions = rev; eventTime1024 = time; }
    }
    public static final class PowerResult {
        public final int riderWatts;
        public final CrankEvent crank;
        PowerResult(int watts, CrankEvent c) { riderWatts = watts; crank = c; }
    }
    /** 0x2A19 Battery Level: one unsigned byte, 0-100; 255 is not a valid percentage. */
    public static int batteryLevel(byte[] data) {
        if (data == null || data.length != 1) return -1;
        int value = data[0] & 0xff;
        return value <= 100 ? value : -1;
    }
    public static int u16(byte[] bytes, int pos) {
        return (bytes[pos] & 0xff) | ((bytes[pos + 1] & 0xff) << 8);
    }
    public static int s16(byte[] bytes, int pos) {
        return (short) u16(bytes, pos);
    }
    public static long u32(byte[] b, int pos) {
        return ((long)b[pos] & 0xff) | (((long)b[pos+1] & 0xff)<<8)
            | (((long)b[pos+2] & 0xff)<<16) | (((long)b[pos+3] & 0xff)<<24);
    }
    /** Cycling Power Measurement 0x2A63. Optional fields before crank data obey the flags. */
    public static PowerResult cyclingPower(byte[] data) {
        if (data == null || data.length < 4) return null;
        int flags = u16(data, 0);
        int watts = s16(data, 2);
        int p = 4;
        if ((flags & 0x0001) != 0) p += 1; // pedal power balance
        if ((flags & 0x0004) != 0) p += 2; // accumulated torque
        if ((flags & 0x0010) != 0) p += 6; // wheel revs + wheel event time
        CrankEvent crank = null;
        if ((flags & 0x0020) != 0 && data.length >= p + 4) {
            crank = new CrankEvent(u16(data, p), u16(data, p + 2));
        }
        return new PowerResult(watts, crank);
    }
    /** Cycling Speed and Cadence Measurement 0x2A5B. */
    public static CrankEvent cyclingCadence(byte[] data) {
        if (data == null || data.length < 1) return null;
        int flags = data[0] & 0xff;
        int p = 1;
        if ((flags & 0x01) != 0) p += 6; // 4-byte wheel revs + 2-byte wheel time
        if ((flags & 0x02) != 0 && data.length >= p + 4)
            return new CrankEvent(u16(data, p), u16(data, p + 2));
        return null;
    }
    /** Returns -1 on the first sample, bogus timestamp or impossible high cadence. */
    public static int rpm(CrankEvent oldEvent, CrankEvent newEvent) {
        if (oldEvent == null || newEvent == null) return -1;
        int count = (newEvent.revolutions - oldEvent.revolutions) & 0xffff;
        int dt = (newEvent.eventTime1024 - oldEvent.eventTime1024) & 0xffff;
        if (dt == 0) return -1;
        double result = count * 60.0 * 1024.0 / dt;
        if (result < 0 || result > 250) return -1;
        return (int) Math.round(result);
    }
    public static String hex(byte[] data) {
        if (data == null) return "";
        StringBuilder b = new StringBuilder();
        for (byte v : data) b.append(String.format(java.util.Locale.US, "%02X ", v & 255));
        return b.toString().trim();
    }
}
