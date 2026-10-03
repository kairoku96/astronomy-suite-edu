import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Data-integrity check for every moon: the tabulated semi-major axis and orbital period must
 * be mutually consistent with the parent's mass under Kepler's third law. The renderer uses
 * {@code MOON_A_M} for the orbit-ring radius and {@code MOON_PERIOD_DAYS} for the angular
 * rate independently, so a mismatch means a moon is drawn at the wrong radius or moving at
 * the wrong speed. This caught bad axes/periods for Dione, Umbriel, Ariel and Charon.
 */
class MoonDataTest {

    private static final double G = 6.674e-11;
    private static final double DAY = 86_400.0;
    // Companion masses that are a non-trivial fraction of the primary (two-body period uses the sum).
    private static final double CHARON_MASS = 1.586e21;

    /** Gravitational parameter of the system the moon orbits (kg → GM). */
    private static double parentMass(int planetIndex) {
        double m = SolarSystemSimulation.PLANET_MASS[planetIndex];
        if (planetIndex == 8) m += CHARON_MASS;   // Pluto–Charon is a near-binary
        return m;
    }

    @Test
    void everyMoonAxisMatchesItsPeriod() {
        double worst = 0.0;
        for (int p = 0; p < SolarSystemSimulation.MOON_A_M.length; p++) {
            double[] a = SolarSystemSimulation.MOON_A_M[p];
            double[] pd = SolarSystemSimulation.MOON_PERIOD_DAYS[p];
            for (int m = 0; m < a.length; m++) {
                double keplerDays = 2 * Math.PI * Math.sqrt(a[m] * a[m] * a[m] / (G * parentMass(p))) / DAY;
                double errPct = Math.abs(keplerDays - pd[m]) / pd[m] * 100.0;
                worst = Math.max(worst, errPct);
                String name = SolarSystemSimulation.MOON_NAMES[p][m];
                System.out.printf("  %-10s a=%.3e P=%.4fd  Kepler=%.4fd  %.1f%%%n",
                        name, a[m], pd[m], keplerDays, errPct);
                assertTrue(errPct < 3.0,
                        name + ": semi-major axis and period disagree by " + errPct + "% (Kepler's 3rd law)");
            }
        }
        System.out.printf("  worst moon a/P inconsistency: %.2f%%%n", worst);
    }
}
