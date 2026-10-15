import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The lunar theory ({@link MoonEphemeris}, ELP/MPP02): it reproduces the authors' reference
 * positions, which checks the series file and the evaluation end to end; it agrees with
 * JPL's DE441 (via Horizons) over 1950–2100; and the truncated evaluation used by the
 * integrator stays within its stated accuracy.
 */
class MoonEphemerisTest {

    private static final double AU_KM = 149_597_870.7;

    private static double[] km(double tdbJd, double tol) {
        double[] p = MoonEphemeris.geocentricEclipticAUatTT((tdbJd - 2451545.0) * 86400.0, tol);
        return new double[]{p[0] * AU_KM, p[1] * AU_KM, p[2] * AU_KM};
    }

    @Test
    void reproducesTheAuthorsReferencePositions() {
        // Chapront & Francou's ELP/MPP02 test output (LLR fit): JD (TDB), X, Y, Z (km), mean
        // ecliptic and equinox of J2000.
        double[][] ref = {
            {2444239.5, 43890.2824005, 381188.7274523, -31633.3816524},
            {2446239.5, -313664.5964499, 212007.2667385, 33744.7512039},
            {2448239.5, -273220.0606714, -296859.7682229, -34604.3569962},
            {2450239.5, 171613.1427993, -318097.3375025, 31293.5482404},
            {2452239.5, 396530.0063512, 47487.9224886, -36085.3090343},
        };
        for (double[] r : ref) {
            double[] p = km(r[0], 0);
            for (int c = 0; c < 3; c++) assertEquals(r[1 + c], p[c], 1e-4, "JD " + r[0] + " component " + c);
        }
    }

    @Test
    void cachedInterpolationIsTheFullTheory() {
        // The everyday API interpolates the full theory over 8-day Chebyshev segments; it must
        // agree with direct evaluation everywhere, segment edges included.
        java.util.Random rnd = new java.util.Random(7);
        double worst = 0;
        for (int i = 0; i < 200; i++) {
            double tt = (rnd.nextDouble() * 2 - 1) * 100 * 365.25 * 86400.0;
            if (i % 10 == 0) tt = Math.round(tt / (8 * 86400.0)) * 8 * 86400.0;   // on a segment edge
            double[] a = MoonEphemeris.geocentricEclipticAUatTT(tt), b = MoonEphemeris.geocentricEclipticAUatTT(tt, 0);
            for (int c = 0; c < 3; c++) worst = Math.max(worst, Math.abs(a[c] - b[c]) * AU_KM);
        }
        System.out.printf("  interpolation vs direct: %.2e km%n", worst);
        assertTrue(worst < 1e-3, "interpolated Moon off by " + worst + " km");
    }

    // JPL Horizons (DE441) geocentric Moon, ecliptic J2000: JD (TDB), x, y, z (km).
    private static final double[][] HORIZONS = {
        {2433282.5, 1.865116707714126E+05, 3.524176788821661E+05, 2.639696093564296E+04},          // 1950
        {2440000.5, 3.590522352038171E+05, 1.856864740052315E+05, 5.632457058169311E+03},          // 1968
        {2444239.5, 4.389029660880000E+04, 3.811887212318406E+05, -3.163344485493895E+04},         // 1980
        {2451545.0, -2.916083841877129E+05, -2.749797416731504E+05, 3.627119662699287E+04},        // 2000
        {2455198.0, -1.263477247519830E+05, 3.358189864291663E+05, 1.762437160201371E+02},         // 2010
        {2457987.267361111, -3.175178652703518E+05, 1.939897594787129E+05, 2.748744201271140E+03}, // 2017 eclipse
        {2460409.261805556, 3.401697994169244E+05, 1.172024216553603E+05, 2.155312995021239E+03},  // 2024 eclipse
        {2462000.5, -4.610802017432747E+04, 3.696134862455964E+05, 9.005853588826314E+03},         // 2028
        {2469807.5, 3.595805947708946E+05, 1.165753803418467E+05, 2.238725525948481E+04},          // 2050
        {2480000.5, 2.807177395563371E+05, 2.302869577314195E+05, -1.055452256965835E+04},         // 2077
        {2488069.5, -3.395196032258248E+05, 1.511481601645553E+05, 7.061518665618823E+03},         // 2100
    };

    /** {angle (″), distance (km)} between a computed and a reference position. */
    private static double[] diff(double[] p, double[] r) {
        double dot = p[0] * r[0] + p[1] * r[1] + p[2] * r[2];
        double np = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]), nr = Math.sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2]);
        return new double[]{Math.toDegrees(Math.acos(Math.min(1, dot / (np * nr)))) * 3600, np - nr};
    }

    @Test
    void matchesHorizons1950to2100() {
        double worstA = 0, worstR = 0;
        for (double[] h : HORIZONS) {
            double[] d = diff(km(h[0], 0), new double[]{h[1], h[2], h[3]});
            System.out.printf("  JD %.1f: %.3f\"  %+.3f km%n", h[0], d[0], d[1]);
            worstA = Math.max(worstA, d[0]);
            worstR = Math.max(worstR, Math.abs(d[1]));
        }
        System.out.printf("  worst: %.3f\", %.3f km%n", worstA, worstR);
        assertTrue(worstA < 0.1, "Moon off by " + worstA + " arcsec");
        assertTrue(worstR < 0.01, "Moon distance off by " + worstR + " km");
    }
}
