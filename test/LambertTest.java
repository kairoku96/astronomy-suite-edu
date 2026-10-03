import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/** Lambert's problem and the transfer planner built on it. */
class LambertTest {

    @Test
    void curtisExample5_2() {
        // Curtis, Orbital Mechanics for Engineering Students, Example 5.2 (geocentric, km, s).
        double[] r1 = {5000, 10000, 2100}, r2 = {-14600, 2500, 7000};
        double[] v = Lambert.solve(r1, r2, 3600, 398600, new double[]{0, 0, 1});
        assertNotNull(v);
        double[] v1 = {-5.9925, 1.9254, 3.2456}, v2 = {-3.3125, -4.1966, -0.38529};
        for (int i = 0; i < 3; i++) {
            assertEquals(v1[i], v[i], 2e-3, "v1[" + i + "]");
            assertEquals(v2[i], v[3 + i], 2e-3, "v2[" + i + "]");
        }
    }

    @Test
    void reproducesAHohmannTransfer() {
        // Circular coplanar orbits at 1 and 1.524 AU; a 179.9° transfer in the Hohmann time must
        // leave at the Hohmann perihelion speed.
        double mu = NBodyEphemeris.GM_SUN, r1 = 1.0, r2 = 1.524;
        double a = 0.5 * (r1 + r2), tof = Math.PI * Math.sqrt(a * a * a / mu);
        double th = Math.toRadians(179.9);
        double[] v = Lambert.solve(new double[]{r1, 0, 0}, new double[]{r2 * Math.cos(th), r2 * Math.sin(th), 0},
                tof, mu, new double[]{0, 0, 1});
        assertNotNull(v);
        double vHoh = Math.sqrt(mu * (2 / r1 - 1 / a));
        assertEquals(vHoh, Math.hypot(v[0], v[1]), 2e-3 * vHoh);
    }

    @Test
    void solutionPropagatesToTheTarget() {
        // The solved conic, propagated exactly for the time of flight, must arrive at r2.
        double mu = NBodyEphemeris.GM_SUN;
        double[] r1 = {0.9, -0.4, 0.02}, r2 = {-1.1, 1.2, -0.05};
        for (double tof : new double[]{120, 250, 400}) {
            double[] v = Lambert.solve(r1, r2, tof, mu, new double[]{0, 0, 1});
            double[] s = {r1[0], r1[1], r1[2], v[0], v[1], v[2]};
            NBodyEphemeris.drift(mu, s, 0, tof);
            double miss = Math.sqrt(Math.pow(s[0] - r2[0], 2) + Math.pow(s[1] - r2[1], 2) + Math.pow(s[2] - r2[2], 2));
            assertTrue(miss < 1e-9, "missed by " + miss + " AU for tof " + tof);
        }
    }

    static double sec(int y, int m, int d) {
        return LocalDateTime.of(y, m, d, 0, 0).toEpochSecond(ZoneOffset.UTC)
                - LocalDateTime.of(2000, 1, 1, 12, 0).toEpochSecond(ZoneOffset.UTC);
    }

    @Test
    void marsWindow2020PorkchopMinimum() {
        // The 2020 Earth→Mars window (Perseverance, Tianwen-1, Hope launched 19–30 July 2020):
        // the cheapest C3 should fall in July–August 2020 at roughly 13–16 km²/s².
        double[][][] pc = TransferPlanner.porkchop(2, 3, sec(2020, 5, 1), sec(2020, 10, 31), 60,
                sec(2020, 11, 1), sec(2021, 9, 30), 60);
        double best = Double.MAX_VALUE;
        int bi = -1;
        for (int i = 0; i < 60; i++)
            for (int j = 0; j < 60; j++)
                if (pc[0][i][j] < best) { best = pc[0][i][j]; bi = i; }
        double dep = sec(2020, 5, 1) + (sec(2020, 10, 31) - sec(2020, 5, 1)) * bi / 59.0;
        System.out.printf("  min C3 %.2f km²/s² departing %s%n", best, TransferPlanner.dateOf(dep));
        assertTrue(best > 8 && best < 17, "min C3 " + best);
        assertTrue(dep > sec(2020, 6, 25) && dep < sec(2020, 9, 5), "departure " + TransferPlanner.dateOf(dep));
    }

    @Test
    void perseveranceTransfer() {
        // Mars 2020 launched 2020-07-30 and arrived 2021-02-18; the planner's Lambert transfer for
        // those dates needs a launch C3 near the mission's ~14 km²/s² and a Mars arrival v∞ of a
        // few km/s.
        double dep = sec(2020, 7, 30), arr = sec(2021, 2, 18);
        TransferPlanner.Transfer T = TransferPlanner.solve(2, 3, dep, (arr - dep) / 86400);
        assertNotNull(T);
        System.out.printf("  Mars 2020: C3 %.2f km²/s², arrival v∞ %.2f km/s%n", T.c3, T.vinfArr);
        assertTrue(T.c3 > 11 && T.c3 < 18, "C3 " + T.c3);
        assertTrue(T.vinfArr > 2 && T.vinfArr < 4, "arrival v∞ " + T.vinfArr);
    }
}
