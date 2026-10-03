import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Structural properties of the symplectic integrator ({@link NBodyEphemeris}) that the sim
 * relies on: it retraces its own path backwards, conserves energy to a bounded oscillation,
 * gives the same position for a given instant however the clock got there (so the scene is
 * identical at every time-warp), and is continuous between grid steps.
 */
class IntegratorTest {

    private static final double DAY = 86_400.0;

    private static double[] initial() {
        double[] s = new double[6 * NBodyEphemeris.N_BODIES];
        NBodyEphemeris.shared().heliocentric(0, s);
        return s;
    }

    @Test
    void stepIsTimeReversible() {
        // Start from a heliocentric snapshot interpreted as canonical coordinates (any valid
        // state will do); 4000 steps forward then 4000 back must return to it.
        double[] s = initial(), s0 = s.clone();
        double h = NBodyEphemeris.STEP_DAYS;
        for (int k = 0; k < 4000; k++) NBodyEphemeris.step(s, k * h, h);
        for (int k = 4000; k > 0; k--) NBodyEphemeris.step(s, k * h, -h);
        for (int i = 0; i < s.length; i++) assertEquals(s0[i], s[i], 1e-9, "component " + i);
    }

    /** Newtonian energy of the massive bodies (heliocentric states → barycentric). */
    private static double energy(double[] h) {
        int n = NBodyEphemeris.N_MASSIVE;
        double[] gm = NBodyEphemeris.GM;
        double mTot = NBodyEphemeris.GM_SUN;
        double[] vs = new double[3];
        for (int i = 0; i < n; i++) { mTot += gm[i]; for (int c = 0; c < 3; c++) vs[c] -= gm[i] * h[6 * i + 3 + c]; }
        for (int c = 0; c < 3; c++) vs[c] /= mTot;                 // Sun's barycentric velocity
        double ke = 0.5 * NBodyEphemeris.GM_SUN * (vs[0] * vs[0] + vs[1] * vs[1] + vs[2] * vs[2]), pe = 0;
        for (int i = 0; i < n; i++) {
            double vx = h[6 * i + 3] + vs[0], vy = h[6 * i + 4] + vs[1], vz = h[6 * i + 5] + vs[2];
            ke += 0.5 * gm[i] * (vx * vx + vy * vy + vz * vz);
            double r = Math.sqrt(h[6 * i] * h[6 * i] + h[6 * i + 1] * h[6 * i + 1] + h[6 * i + 2] * h[6 * i + 2]);
            pe -= NBodyEphemeris.GM_SUN * gm[i] / r;
            for (int j = i + 1; j < n; j++) {
                double dx = h[6 * i] - h[6 * j], dy = h[6 * i + 1] - h[6 * j + 1], dz = h[6 * i + 2] - h[6 * j + 2];
                pe -= gm[i] * gm[j] / Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
        }
        return ke + pe;
    }

    @Test
    void energyStaysBoundedOverCenturies() {
        NBodyEphemeris eph = NBodyEphemeris.shared();
        double[] s = new double[6 * NBodyEphemeris.N_BODIES];
        eph.heliocentric(0, s);
        double e0 = energy(s), worst = 0;
        for (int yr = -200; yr <= 200; yr += 10) {
            eph.heliocentric(yr * 365.25 * DAY, s);
            worst = Math.max(worst, Math.abs(energy(s) / e0 - 1));
        }
        System.out.printf("  max |dE/E| over 1800-2200: %.2e%n", worst);
        // GR terms and the between-step interpolation make this not exactly conserved, but it
        // must not drift: well under a part in a million over four centuries.
        assertTrue(worst < 1e-6, "energy drift " + worst);
    }

    @Test
    void positionIsAFunctionOfTimeAlone() {
        // Reach the same instant by different histories: a direct jump, many small hops, and
        // an approach from the other side. The answer must not depend on the path.
        NBodyEphemeris eph = NBodyEphemeris.shared();
        double t = 12_345.678 * DAY;
        eph.heliocentricAU(4, -30 * 365.25 * DAY);
        double[] a = eph.heliocentricAU(0, t);
        for (double h = t - 400 * DAY; h < t; h += 0.37 * DAY) eph.heliocentricAU(0, h);
        double[] b = eph.heliocentricAU(0, t);
        eph.heliocentricAU(0, t + 900 * DAY);
        double[] c = eph.heliocentricAU(0, t);
        for (int k = 0; k < 3; k++) {
            assertEquals(a[k], b[k], 1e-10);   // AU: 15 m of round-off
            assertEquals(a[k], c[k], 1e-10);
        }
    }

    @Test
    void positionsAreContinuousAcrossGridSteps() {
        // Mercury, the fastest body, straddling a grid boundary: the jump at the boundary must
        // be no larger than its smooth motion over the same tiny interval (≈ 5 km for 0.1 s).
        NBodyEphemeris eph = NBodyEphemeris.shared();
        double tb = 1000 * NBodyEphemeris.STEP_DAYS * DAY;
        double eps = 0.05;
        double[] l = eph.heliocentricAU(0, tb - eps), r = eph.heliocentricAU(0, tb + eps);
        double d = Math.sqrt(Math.pow(l[0] - r[0], 2) + Math.pow(l[1] - r[1], 2) + Math.pow(l[2] - r[2], 2));
        double km = d * 149_597_870.7;
        assertTrue(km < 10, "discontinuity at a grid step: " + km + " km");
    }
}
