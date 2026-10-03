import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Accuracy of the integrated Solar System against JPL Horizons. Planet centres produced by
 * {@link SolarSystemSimulation#planetEclipticAU} (the symplectic N-body integration plus each
 * planet's moon reflex) and the minor bodies' integrated positions are compared with Horizons
 * heliocentric ecliptic-J2000 vectors from 1950 to 2100 — a century either side of the J2000
 * initial conditions — making the "positions match the real sky" claim machine-checked.
 *
 * <p>Run with {@code ./gradlew test}.
 */
class AccuracyTest {

    private static final double DAY = 86_400.0;

    /** Angular separation (arcseconds) between two position vectors. */
    static double sepArcsec(double[] a, double[] b) {
        double dot = a[0]*b[0] + a[1]*b[1] + a[2]*b[2];
        double na = Math.sqrt(a[0]*a[0] + a[1]*a[1] + a[2]*a[2]);
        double nb = Math.sqrt(b[0]*b[0] + b[1]*b[1] + b[2]*b[2]);
        double c = Math.max(-1, Math.min(1, dot / (na * nb)));
        return Math.toDegrees(Math.acos(c)) * 3600.0;
    }

    /** UTC sim seconds for a TDB Julian date (the sim clock is UTC; Horizons epochs are TDB). */
    static double simSecForTdbJd(double jd) {
        double tdb = (jd - 2451545.0) * DAY;
        return tdb - Astro.deltaTSeconds(2000.0 + tdb / (365.25 * DAY));
    }

    // {sim planet index, TDB JD, x, y, z (AU)} — JPL Horizons, body centres, ecliptic J2000.
    private static final double[][] PLANETS = {
        {0, 2433282.500000000, 3.208815126064326E-01, 9.919523755987102E-02, -2.139653050292696E-02},   // Mercury 1950-Jan-01
        {0, 2455198.000000000, 3.415469490697257E-02, 3.051433951495451E-01, 2.179698349645237E-02},   // Mercury 2010-Jan-01
        {0, 2469807.500000000, -1.795140424844850E-01, 2.678117696735194E-01, 3.834830324254487E-02},   // Mercury 2050-Jan-01
        {0, 2488069.500000000, 2.387416045804995E-01, -3.565573467721776E-01, -5.102537864026348E-02},   // Mercury 2100-Jan-01
        {2, 2433282.500000000, -1.827171265684942E-01, 9.661172312536040E-01, 1.080379451135291E-04},   // Earth 1950-Jan-01
        {2, 2455198.000000000, -1.846102661679914E-01, 9.658117302283563E-01, -2.146855277535136E-05},   // Earth 2010-Jan-01
        {2, 2469807.500000000, -1.716121680953062E-01, 9.682588196450419E-01, -1.099670485407470E-04},   // Earth 2050-Jan-01
        {2, 2488069.500000000, -1.574071329883954E-01, 9.706777444608841E-01, -2.182183279262971E-04},   // Earth 2100-Jan-01
        {3, 2433282.500000000, -1.395553774894103E+00, 9.043964307251104E-01, 5.337868315157205E-02},   // Mars 1950-Jan-01
        {3, 2455198.000000000, -7.356536474416738E-01, 1.451729858816603E+00, 4.848322311893991E-02},   // Mars 2010-Jan-01
        {3, 2469807.500000000, -1.543231687780398E+00, -5.035895204731804E-01, 2.720193305057832E-02},   // Mars 2050-Jan-01
        {3, 2488069.500000000, 6.096557738716314E-01, 1.380752260077868E+00, 1.412357993518850E-02},   // Mars 2100-Jan-01
        {4, 2433282.500000000, 3.406605247558555E+00, -3.760530033862945E+00, -6.089144483071644E-02},   // Jupiter 1950-Jan-01
        {4, 2455198.000000000, 4.510650741645971E+00, -2.162898370850126E+00, -9.195983560606384E-02},   // Jupiter 2010-Jan-01
        {4, 2469807.500000000, -2.391045955156108E+00, 4.664073539322320E+00, 3.396562774577581E-02},   // Jupiter 2050-Jan-01
        {4, 2488069.500000000, -5.373760304528921E+00, -9.084799102285207E-01, 1.238877644450175E-01},   // Jupiter 2100-Jan-01
        {5, 2433282.500000000, -9.007366891248770E+00, 2.500428606539161E+00, 3.139899154080162E-01},   // Saturn 1950-Jan-01
        {5, 2455198.000000000, -9.464480773496048E+00, 2.558930196763475E-01, 3.722678868539636E-01},   // Saturn 2010-Jan-01
        {5, 2469807.500000000, 4.766226141432818E+00, -8.773718298319354E+00, -3.787765602192074E-02},   // Saturn 2050-Jan-01
        {5, 2488069.500000000, -9.153087247698588E+00, -3.078992753104979E+00, 4.182399691810392E-01},   // Saturn 2100-Jan-01
        {7, 2433282.500000000, -2.909262556441645E+01, -8.409026045352901E+00, 8.433805220354059E-01},   // Neptune 1950-Jan-01
        {7, 2455198.000000000, 2.481802805596860E+01, -1.689626926139731E+01, -2.239882450293774E-01},   // Neptune 2010-Jan-01
        {7, 2469807.500000000, 1.739820467380735E+01, 2.419778367193252E+01, -8.992415526060462E-01},   // Neptune 2050-Jan-01
        {7, 2488069.500000000, -2.905922741901618E+01, 8.235187480545719E+00, 5.001933984409804E-01},   // Neptune 2100-Jan-01
        {8, 2433282.500000000, -2.653406673821501E+01, 2.428411973204163E+01, 5.074441299680692E+00},   // Pluto 1950-Jan-01
        {8, 2455198.000000000, 1.629367441861995E+00, -3.158768395386210E+01, 2.909546983911815E+00},   // Pluto 2010-Jan-01
        {8, 2469807.500000000, 3.745495460095810E+01, -1.513285736968986E+01, -9.214719989109588E+00},   // Pluto 2050-Jan-01
        {8, 2488069.500000000, 3.967530922819456E+01, 2.490476330864163E+01, -1.414321151643269E+01},   // Pluto 2100-Jan-01
    };
    /**
     * Allowed error (arcsec) per sim planet index. Mercury carries the Wisdom–Holman step error
     * (∝ step²; measured ≤ 15″). Earth includes the lunar theory's reflex placement of Earth's
     * centre about the Earth–Moon barycentre (measured ≤ 0.6″); the rest measure ≤ 0.25″.
     */
    private static final double[] TOL = {20, 1, 1.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5};

    @Test
    void planetCentresMatchHorizons1950to2100() {
        for (double[] r : PLANETS) {
            int i = (int) r[0];
            double[] got = SolarSystemSimulation.planetEclipticAU(i, simSecForTdbJd(r[1]));
            double sep = sepArcsec(got, new double[]{r[2], r[3], r[4]});
            System.out.printf("  body %d JD %.1f: %.2f arcsec%n", i, r[1], sep);
            assertTrue(sep < TOL[i], "body " + i + " off by " + sep + " arcsec at JD " + r[1]);
        }
    }

    // {name, TDB JD, x, y, z (AU)} — Horizons; comets from their apparition solution.
    private static final String[][] SMALL = {
        {"1P/Halley", "2446470.500000000", "3.423337950487022E-01", "-4.465925417210364E-01", "1.677969814871451E-01"},   // 1986-Feb-09
        {"Ceres", "2460676.500000000", "2.176976419651001E+00", "-1.976334607788391E+00", "-4.635983995150979E-01"},   // 2025-Jan-01
        {"433 Eros", "2460676.500000000", "-1.619866965564159E-02", "-1.688559955842637E+00", "-1.844316998702361E-01"},   // 2025-Jan-01
        {"101955 Bennu", "2458849.500000000", "1.002425056292472E+00", "-3.988422338280882E-01", "-4.584777860208500E-02"},   // 2020-Jan-01
    };

    @Test
    void minorBodiesMatchHorizons() {
        for (String[] r : SMALL) {
            int k = NBodyEphemeris.indexOf(r[0]);
            double jd = Double.parseDouble(r[1]);
            double[] got = NBodyEphemeris.shared().heliocentricAU(k, (jd - 2451545.0) * DAY);
            double[] ref = {Double.parseDouble(r[2]), Double.parseDouble(r[3]), Double.parseDouble(r[4])};
            double sep = sepArcsec(got, ref);
            System.out.printf("  %s: %.2f arcsec%n", r[0], sep);
            // Bennu also drifts from its thermal (Yarkovsky) push, which isn't modelled.
            assertTrue(sep < 5, r[0] + " off by " + sep + " arcsec");
        }
    }

    @Test
    void heliocentricDistancesAreRealistic() {
        // sanity: each planet's Sun distance sits in its true perihelion..aphelion band
        double[][] band = {   // {min AU, max AU}
            {0.30, 0.47},     // Mercury
            {0.71, 0.73},     // Venus
            {0.98, 1.02},     // Earth
            {1.38, 1.67},     // Mars
        };
        for (int i = 0; i < band.length; i++) {
            for (double yr = -50; yr <= 50; yr += 7.3) {
                double[] p = SolarSystemSimulation.planetEclipticAU(i, yr * 365.25 * DAY);
                double r = Math.sqrt(p[0]*p[0] + p[1]*p[1] + p[2]*p[2]);
                assertTrue(r >= band[i][0] && r <= band[i][1],
                        "body " + i + " Sun distance " + r + " AU outside " + band[i][0] + ".." + band[i][1]);
            }
        }
    }
}
