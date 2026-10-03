import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Saturn's rings sit in its real (IAU) equator plane, so the ring-plane crossings land on their
 * real dates: the Sun crossed (Saturn's equinox) on 2009-08-11 and 2025-05-06, and the Earth on
 * 2009-09-04 and 2025-03-23. Also the slab photometry's limits.
 */
class SaturnRingTest {

    private static final Instant J2000 = LocalDateTime.of(2000, 1, 1, 12, 0, 0).toInstant(ZoneOffset.UTC);

    static double sec(int y, int mo, int d) {
        return LocalDateTime.of(y, mo, d, 12, 0).toInstant(ZoneOffset.UTC).getEpochSecond() - J2000.getEpochSecond();
    }

    /** Zero crossing of B (k=0, Earth) or B′ (k=1, Sun) nearest a date, by bisection over ±60 days. */
    static double crossing(int k, double near) {
        double a = near - 60 * 86400, b = near + 60 * 86400;
        double fa = SolarSystemSimulation.saturnRingTilts(a)[k];
        double fb = SolarSystemSimulation.saturnRingTilts(b)[k];
        assertTrue(fa * fb < 0, "no crossing within ±60 d of the expected date");
        for (int it = 0; it < 50; it++) {
            double m = 0.5 * (a + b), fm = SolarSystemSimulation.saturnRingTilts(m)[k];
            if (fa * fm <= 0) { b = m; } else { a = m; fa = fm; }
        }
        return 0.5 * (a + b);
    }

    @Test
    void sunCrossesTheRingPlaneAtSaturnsEquinoxes() {
        for (int[] d : new int[][]{{2009, 8, 11}, {2025, 5, 6}}) {
            double want = sec(d[0], d[1], d[2]);
            double got = crossing(1, want);
            double days = (got - want) / 86400;
            System.out.printf("  Saturn equinox %d: Δ %+.1f d%n", d[0], days);
            assertTrue(Math.abs(days) < 4, "equinox " + d[0] + " off by " + days + " d");
        }
    }

    @Test
    void earthCrossesTheRingPlaneOnTheRealDates() {
        for (int[] d : new int[][]{{2009, 9, 4}, {2025, 3, 23}}) {
            double want = sec(d[0], d[1], d[2]);
            double got = crossing(0, want);
            double days = (got - want) / 86400;
            System.out.printf("  Earth ring-plane crossing %d: Δ %+.1f d%n", d[0], days);
            assertTrue(Math.abs(days) < 3, "crossing " + d[0] + " off by " + days + " d");
        }
    }

    @Test
    void ringsAreWideOpenNearSolstice() {
        // 2017 (Saturn's northern summer solstice): rings open by ~26–27° to Earth and Sun.
        double[] b = SolarSystemSimulation.saturnRingTilts(sec(2017, 10, 16));
        assertTrue(b[0] > 25 && b[0] < 28, "B = " + b[0]);
        assertTrue(b[1] > 25 && b[1] < 28, "B' = " + b[1]);
    }

    @Test
    void slabPhotometryLimits() {
        // Edge-on to the Sun: no light reaches the lit face.
        assertTrue(SolarSystemSimulation.ringReflect(0.5, 1e-4, 0.4, true) < 1e-3);
        // The unlit face is fainter than the lit face for the same geometry.
        assertTrue(SolarSystemSimulation.ringReflect(0.5, 0.3, 0.4, false) < SolarSystemSimulation.ringReflect(0.5, 0.3, 0.4, true));
        // Transmission vanishes for an opaque ring and for a transparent one.
        assertTrue(SolarSystemSimulation.ringReflect(20, 0.3, 0.4, false) < 1e-6);
        assertTrue(SolarSystemSimulation.ringReflect(1e-6, 0.3, 0.4, false) < 1e-5);
        // Continuous where μ0 = μ.
        double a = SolarSystemSimulation.ringReflect(0.7, 0.3, 0.3 + 1e-7, false);
        double b = SolarSystemSimulation.ringReflect(0.7, 0.3, 0.3, false);
        assertEquals(a, b, 1e-5);
    }
}
