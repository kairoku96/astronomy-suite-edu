import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end check that the real lunar ephemeris ({@link MoonEphemeris}) plus the Sun
 * position reproduce actual eclipses on their real calendar dates. Solar eclipses are found
 * as new moons near a node and compared to the NASA Five Millennium Canon dates and
 * instants of greatest eclipse. This validates the Moon's longitude, latitude, node timing
 * and the ΔT/precession handling all at once — the "matches the live sky" claim, machine-checked.
 *
 * <p>The instant found is geocentric conjunction in ecliptic longitude (new moon); greatest
 * eclipse is defined slightly differently, so the times are checked to within 45 minutes.
 */
class EclipseAccuracyTest {

    private static final double DAY = 86_400.0;
    private static final Instant J2000 = LocalDateTime.of(2000, 1, 1, 12, 0, 0).toInstant(ZoneOffset.UTC);

    /** Seconds from the J2000 epoch instant for a UTC calendar moment. */
    private static double secOf(int y, int mo, int d, int h, int mi) {
        return (LocalDateTime.of(y, mo, d, h, mi).toInstant(ZoneOffset.UTC).getEpochSecond()
                - J2000.getEpochSecond());
    }

    private static LocalDateTime utc(double simSec) {
        return LocalDateTime.ofInstant(J2000.plusSeconds(Math.round(simSec)), ZoneOffset.UTC);
    }

    // NASA greatest-eclipse instants (UT) for every solar eclipse of 2023-2024.
    private static final int[][] SOLAR_2023_2024 = {
        {2023, 4, 20,  4, 17}, {2023, 10, 14, 17, 59},
        {2024, 4,  8, 18, 17}, {2024, 10,  2, 18, 45},
    };

    @Test
    void solarEclipsesLandOnRealDates() {
        double t = secOf(2023, 1, 1, 0, 0);
        double end = secOf(2024, 12, 31, 0, 0);
        int found = 0;
        for (int k = 0; k < SOLAR_2023_2024.length; k++) {
            double te = SolarSystemSimulation.nextEarthEclipse(true, t, +1);
            assertFalse(Double.isNaN(te), "no solar eclipse found after " + utc(t));
            if (te > end) break;
            int[] exp = SOLAR_2023_2024[k];
            double expSec = secOf(exp[0], exp[1], exp[2], exp[3], exp[4]);
            double diffMin = Math.abs(te - expSec) / 60.0;
            System.out.printf("  solar: got %s, expected %04d-%02d-%02d %02d:%02d UT, Δ=%.1f min%n",
                    utc(te), exp[0], exp[1], exp[2], exp[3], exp[4], diffMin);
            assertEquals(exp[0], utc(te).getYear());
            assertEquals(exp[1], utc(te).getMonthValue());
            assertEquals(exp[2], utc(te).getDayOfMonth(), "wrong day for eclipse #" + k);
            assertTrue(diffMin < 45.0, "solar eclipse #" + k + " off by " + diffMin + " min");
            t = te + 5 * DAY;   // step past this one before searching for the next
            found++;
        }
        assertEquals(SOLAR_2023_2024.length, found, "wrong number of solar eclipses in 2023-2024");
    }

    // A few well-known umbral lunar eclipses (greatest-eclipse UT).
    private static final int[][] LUNAR = {
        {2022, 5, 16,  4, 11}, {2022, 11,  8, 10, 59}, {2025, 3, 14,  6, 59}, {2025, 9,  7, 18, 11},
    };

    @Test
    void knownLunarEclipsesAreFound() {
        for (int[] exp : LUNAR) {
            double from = secOf(exp[0], exp[1], exp[2], exp[3], exp[4]) - 10 * DAY;
            double te = SolarSystemSimulation.nextEarthEclipse(false, from, +1);
            assertFalse(Double.isNaN(te), "no lunar eclipse found near " + exp[0] + "-" + exp[1]);
            double diffMin = Math.abs(te - secOf(exp[0], exp[1], exp[2], exp[3], exp[4])) / 60.0;
            System.out.printf("  lunar: got %s, expected %04d-%02d-%02d %02d:%02d UT, Δ=%.1f min%n",
                    utc(te), exp[0], exp[1], exp[2], exp[3], exp[4], diffMin);
            assertEquals(exp[2], utc(te).getDayOfMonth(), "wrong day for lunar eclipse " + exp[0]);
            assertTrue(diffMin < 45.0, "lunar eclipse " + exp[0] + " off by " + diffMin + " min");
        }
    }

    @Test
    void moonStaysInPhysicalRange() {
        for (double t = 0; t < 30 * 365.25 * DAY; t += 0.37 * DAY) {
            double[] s = MoonEphemeris.geocentric(t);
            double distKm = s[2] / 1000.0;
            double latDeg = Math.toDegrees(s[1]);
            assertTrue(distKm > 356000 && distKm < 407000, "Moon distance out of range: " + distKm + " km");
            assertTrue(Math.abs(latDeg) < 5.35, "Moon latitude out of range: " + latDeg + " deg");
        }
    }
}
