import cz.ride.fazuawatch.TelemetryParser;
public final class TelemetryParserTest {
    static void eq(int expected, int actual) {
        if (expected != actual) throw new AssertionError(expected + " != " + actual);
    }
    public static void main(String[] args) {
        // REAL notification captured from a PATH RIOT / FAZUA RIDE60:
        // flags 0x0020 (crank revolution data present), power 0W,
        // 7 cumulative revolutions, last crank event 0x5B2F ticks at 1/1024s.
        byte[] realSample = {0x20, 0, 0, 0, 0x07, 0, 0x2f, 0x5b};
        TelemetryParser.PowerResult real = TelemetryParser.cyclingPower(realSample);
        eq(0, real.riderWatts);
        eq(7, real.crank.revolutions);
        eq(0x5B2F, real.crank.eventTime1024);
        // Real measured battery value from 0x2A19 was 98 percent.
        eq(98, TelemetryParser.batteryLevel(new byte[]{0x62}));
        eq(-1, TelemetryParser.batteryLevel(new byte[]{(byte)255}));
        // CPS with 180 W, crank 10 rev at 1 second
        byte[] a = {0x20, 0, (byte) 180, 0, 10, 0, 0, 4};
        TelemetryParser.PowerResult p = TelemetryParser.cyclingPower(a);
        eq(180, p.riderWatts); eq(10, p.crank.revolutions); eq(1024, p.crank.eventTime1024);
        // Later 11th crank revolution at 2 seconds -> 60 rpm
        eq(60, TelemetryParser.rpm(p.crank,
            new TelemetryParser.CrankEvent(11, 2048)));
        // CSC with wheel + crank data, 2nd segment contains crank
        byte[] csc = {3, 0, 0, 0, 0, 0, 0, 12, 0, 0, 8};
        eq(12, TelemetryParser.cyclingCadence(csc).revolutions);
        // Pedal balance and accumulated torque before crank
        byte[] cpsOptional = {0x25, 0, (byte) 200, 0, 50, 0, 0, 2, 0, 0, 4};
        eq(200, TelemetryParser.cyclingPower(cpsOptional).riderWatts);
        eq(2, TelemetryParser.cyclingPower(cpsOptional).crank.revolutions);
        System.out.println("All parsers OK");
    }
}
