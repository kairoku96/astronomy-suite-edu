import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live-check tests for planet <em>rotation</em> and <em>texture orientation</em> — the
 * companion to {@link AccuracyTest}, which covers position. These assert that the on-screen
 * spin is anchored to each planet's real IAU / WGCCRE orientation, so the meridian rendered
 * facing the Sun is the one the Sun is truly over, and that this holds at any time-warp speed
 * and under any camera rotation.
 *
 * <p>The datum is {@link SolarSystemSimulation#iauSubSolarEastLon} (real sub-solar east
 * longitude) fed through the render mapping {@link SolarSystemSimulation#subSolarSpin} /
 * {@link SolarSystemSimulation#renderedEastLon}. Ground truth here is independent of the sim:
 * the civil clock for Earth, and NASA sidereal periods cross-checked against the IAU spin rate.
 */
class RotationTest {

    private static final double DAY = 86_400.0, HOUR = 3_600.0;
    private static final int MERCURY = 0, VENUS = 1, EARTH = 2, MARS = 3,
                             JUPITER = 4, SATURN = 5, URANUS = 6, NEPTUNE = 7;
    private static final Instant J2000 = Instant.parse("2000-01-01T12:00:00Z");

    /** NASA sidereal rotation periods (hours); sign = spin sense (+ prograde, − retrograde). */
    private static final double[] SIDEREAL_H = {
            1407.6, -5832.6, 23.9345, 24.6229, 9.9250, 10.656, -17.24, 16.11
    };

    private static double deg(double rad) { return Math.toDegrees(rad); }

    /** Wrap an angle in degrees to (−180, 180]. */
    private static double wrapDeg(double d) {
        d = ((d + 180.0) % 360.0 + 360.0) % 360.0;
        return d - 180.0;
    }

    /** simTimeSec for a UTC calendar moment (simTimeSec = 0 is the J2000.0 epoch). */
    private static double simTimeOf(LocalDateTime utc) {
        return Duration.between(J2000, utc.toInstant(ZoneOffset.UTC)).toMillis() / 1000.0;
    }

    // ------------------------------------------------------------------------------------
    // A. Earth's sub-solar longitude tracks the civil clock — real, external ground truth.
    //    The Sun stands over longitude 0 at 12:00 UTC and moves 15°/hour west, the only
    //    departure being the equation of time (≲ ±4°). This checks rate, direction and datum
    //    together for the one body with a hard civil reference.
    // ------------------------------------------------------------------------------------
    @Test
    void earthSubSolarPointTracksTheCivilClock() {
        int[][] dates = {{2000, 1, 1}, {2000, 4, 1}, {2000, 7, 1}, {2000, 10, 1}, {2001, 6, 15}};
        for (int[] d : dates) {
            for (int hour : new int[]{0, 6, 12, 18}) {
                double t = simTimeOf(LocalDateTime.of(d[0], d[1], d[2], hour, 0));
                double got = deg(SolarSystemSimulation.iauSubSolarEastLon(EARTH, t));
                double meanSun = wrapDeg(-(hour - 12.0) * 15.0);   // mean sub-solar longitude
                double err = wrapDeg(got - meanSun);
                assertTrue(Math.abs(err) < 6.0,
                        String.format("Earth sub-solar lon %.2f° vs civil %.2f° on %d-%02d-%02d %02dh (err %.2f°)",
                                got, meanSun, d[0], d[1], d[2], hour, err));
            }
        }
    }

    // ------------------------------------------------------------------------------------
    // B. The IAU spin rate (Ẇ, deg/day) must agree with the independent NASA sidereal period
    //    for every planet — a cross-check between two separate data sources, and a guard on
    //    the new IAU_WDOT table (including the retrograde sign for Venus and Uranus).
    // ------------------------------------------------------------------------------------
    @Test
    void iauSpinRateMatchesSiderealPeriod() {
        for (int i = 0; i < 8; i++) {
            double simWdot = SolarSystemSimulation.iauPrimeMeridianDeg(i, DAY)
                           - SolarSystemSimulation.iauPrimeMeridianDeg(i, 0.0);       // deg / day
            double expected = 360.0 / (SIDEREAL_H[i] / 24.0);                          // signed deg / day
            assertEquals(Math.signum(expected), Math.signum(simWdot),
                    "spin direction wrong for planet " + i);
            double relErr = Math.abs(simWdot - expected) / Math.abs(expected);
            assertTrue(relErr < 0.005,
                    String.format("planet %d Ẇ=%.6f°/day vs NASA %.6f°/day (%.3f%%)",
                            i, simWdot, expected, relErr * 100));
        }
    }

    // ------------------------------------------------------------------------------------
    // C. Speed independence: the orientation datum is a pure function of elapsed sim time, so
    //    reaching a time by many tiny steps (fast-forward) gives the same result as one jump
    //    (the "at all speeds" requirement). Also asserts strict purity of repeated calls.
    // ------------------------------------------------------------------------------------
    @Test
    void subSolarLongitudeIsSpeedIndependent() {
        double target = 37.0 * 365.25 * DAY + 5.0 * HOUR;   // arbitrary far epoch
        for (int i = 0; i < 8; i++) {
            double a = SolarSystemSimulation.iauSubSolarEastLon(i, target);
            double b = SolarSystemSimulation.iauSubSolarEastLon(i, target);
            assertEquals(a, b, 0.0, "iauSubSolarEastLon not pure for planet " + i);

            double t = 0.0;
            int steps = 200_000;
            double dt = target / steps;
            for (int k = 0; k < steps; k++) t += dt;      // accumulate as the sim loop would
            double stepped = SolarSystemSimulation.iauSubSolarEastLon(i, t);
            double errDeg = Math.abs(wrapDeg(deg(stepped) - deg(a)));
            assertTrue(errDeg < 1e-3,
                    String.format("planet %d differs by %.2e° between stepped and direct time", i, errDeg));
        }
    }

    // ------------------------------------------------------------------------------------
    // D. Render mapping round-trip: the shader must actually paint the intended geographic
    //    meridian at the sub-solar point. subSolarSpin(λ) chooses the spin; renderedEastLon
    //    reads back what longitude the shader samples where the Sun faces — it must equal λ.
    //    This is the texture-orientation guarantee, holding for any obliquity, Sun direction
    //    and target longitude (independent of the IAU model).
    // ------------------------------------------------------------------------------------
    @Test
    void renderPlacesIntendedMeridianAtSubSolarPoint() {
        Random r = new Random(20260916L);
        for (int trial = 0; trial < 5000; trial++) {
            double obliquity = r.nextDouble() * 180.0;                 // 0..180° incl. retrograde tilts
            // random unit body→Sun direction
            double sx = r.nextGaussian(), sy = r.nextGaussian(), sz = r.nextGaussian();
            double n = Math.sqrt(sx*sx + sy*sy + sz*sz);
            if (n < 1e-6) continue;
            sx /= n; sy /= n; sz /= n;
            double lambda = (r.nextDouble() * 2 - 1) * Math.PI;         // wanted sub-solar east longitude

            double spin = SolarSystemSimulation.subSolarSpin(obliquity, sx, sy, sz, lambda);
            double lonSS = SolarSystemSimulation.frameSubSolarLon(obliquity, sx, sy, sz);
            double got = SolarSystemSimulation.renderedEastLon(lonSS, spin);

            double errDeg = Math.abs(wrapDeg(deg(got) - deg(lambda)));
            assertTrue(errDeg < 1e-9,
                    String.format("mapping off by %.2e° (obliq %.1f, λ %.3f)", errDeg, obliquity, lambda));
        }
    }

    // ------------------------------------------------------------------------------------
    // E. Robustness: over ±100 years, for every planet, the sub-solar longitude stays finite
    //    and within [−180°, 180°] — no blow-ups from the wrapping or the rotation matrices.
    // ------------------------------------------------------------------------------------
    @Test
    void subSolarLongitudeStaysBoundedOverACentury() {
        for (int i = 0; i < 8; i++) {
            for (double yr = -100; yr <= 100; yr += 0.37) {
                double lon = deg(SolarSystemSimulation.iauSubSolarEastLon(i, yr * 365.25 * DAY));
                assertTrue(Double.isFinite(lon) && lon >= -180.0001 && lon <= 180.0001,
                        String.format("planet %d lon %.3f° out of range at %.2f yr", i, lon, yr));
            }
        }
    }
}
