import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Moon's IAU orientation ({@link MoonEphemeris#bodyToEcliptic}) against its known behaviour:
 * it keeps the same face to the Earth, nodding by its optical librations (≤ 8° in longitude,
 * ≤ 7° in latitude), with the pole within ~1.5° of the ecliptic pole (Cassini's laws).
 */
class MoonOrientationTest {

    @Test
    void nearSideFacesTheEarthWithinLibration() {
        double maxLon = 0, maxLat = 0;
        for (double d = -3650; d <= 3650; d += 0.7) {
            double sim = d * 86400;
            double tt = Astro.ttSeconds(sim);
            double[] m = MoonEphemeris.geocentricEclipticAU(sim);
            double[] M = MoonEphemeris.bodyToEcliptic(tt);
            // Earth as seen from the Moon, in the Moon's body frame.
            double ex = -m[0], ey = -m[1], ez = -m[2];
            double bx = M[0] * ex + M[3] * ey + M[6] * ez, by = M[1] * ex + M[4] * ey + M[7] * ez, bz = M[2] * ex + M[5] * ey + M[8] * ez;
            double r = Math.sqrt(bx * bx + by * by + bz * bz);
            double lon = Math.toDegrees(Math.atan2(by, bx)), lat = Math.toDegrees(Math.asin(bz / r));
            maxLon = Math.max(maxLon, Math.abs(lon));
            maxLat = Math.max(maxLat, Math.abs(lat));
        }
        System.out.printf("  sub-Earth point: |lon| ≤ %.2f°, |lat| ≤ %.2f°%n", maxLon, maxLat);
        assertTrue(maxLon < 8.5 && maxLon > 6.0, "longitude libration " + maxLon);
        assertTrue(maxLat < 7.5 && maxLat > 5.5, "latitude libration " + maxLat);
    }

    @Test
    void poleIsNearTheEclipticPole() {
        for (double d = -3650; d <= 3650; d += 37) {
            double[] M = MoonEphemeris.bodyToEcliptic(Astro.ttSeconds(d * 86400));
            double tilt = Math.toDegrees(Math.acos(M[8]));     // body z vs ecliptic z
            assertTrue(tilt > 1.3 && tilt < 1.8, "pole tilt " + tilt);
        }
    }

    @Test
    void tranquilityBaseFacedTheEarthDuringApollo11() {
        double sim = Missions.utc(1969, 7, 20, 20, 17, 40);
        double tt = Astro.ttSeconds(sim);
        double[] site = MoonEphemeris.siteEcliptic(0.67408, 23.47297, 0, tt);
        double[] m = MoonEphemeris.geocentricEclipticAU(sim);
        double cos = -(site[0] * m[0] + site[1] * m[1] + site[2] * m[2])
                / (Math.sqrt(site[0] * site[0] + site[1] * site[1] + site[2] * site[2]) * Math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]));
        double angle = Math.toDegrees(Math.acos(cos));
        // The site is 23.5° east of the mean sub-Earth point; with libration it sits 15–32° from it.
        assertTrue(angle > 15 && angle < 32, "Tranquility Base " + angle + "° from the sub-Earth point");
    }
}
