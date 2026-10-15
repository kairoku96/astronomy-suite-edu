import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The paths of total solar eclipses on the ground ({@link SolarEclipse}) against NASA's
 * published path tables (F. Espenak, eclipse.gsfc.nasa.gov, SE2017Aug21T and SE2024Apr08T):
 * the central line and the northern and southern limits at given instants, the duration of
 * totality on the central line, and the greatest eclipse.
 */
class EclipsePathTest {

    private static final double J2000 = LocalDateTime.of(2000, 1, 1, 12, 0).toEpochSecond(ZoneOffset.UTC);

    private static double sim(int y, int mo, int d, int h, int mi, double s) {
        return LocalDateTime.of(y, mo, d, h, mi).toEpochSecond(ZoneOffset.UTC) + s - J2000;
    }

    /** "25 17.4N" → degrees. */
    private static double dm(String v) {
        String[] p = v.trim().split("\\s+");
        double deg = Double.parseDouble(p[0]) + Double.parseDouble(p[1].substring(0, p[1].length() - 1)) / 60;
        char h = p[1].charAt(p[1].length() - 1);
        return h == 'S' || h == 'W' ? -deg : deg;
    }

    /** Great-circle distance (km) between two {lat, lon} points (deg), on a 6371-km sphere. */
    private static double km(double[] a, double lat, double lon) {
        double p1 = Math.toRadians(a[0]), p2 = Math.toRadians(lat), dl = Math.toRadians(lon - a[1]);
        double c = Math.sin(p1) * Math.sin(p2) + Math.cos(p1) * Math.cos(p2) * Math.cos(dl);
        return 6371 * Math.acos(Math.max(-1, Math.min(1, c)));
    }

    // {date y,m,d, "hh:mm", north lat, north lon, south lat, south lon, centre lat, centre lon, duration "m s"}
    private static final String[][] ROWS = {
        {"2017-08-21", "17:00", "44 45.1N", "142 02.8W", "44 02.9N", "140 57.8W", "44 24.1N", "141 29.7W", "1 30.9"},
        {"2017-08-21", "17:24", "45 00.4N", "118 49.2W", "44 03.2N", "118 30.4W", "44 31.8N", "118 39.5W", "2 07.4"},
        {"2017-08-21", "17:48", "42 51.3N", "104 04.2W", "41 51.4N", "104 12.8W", "42 21.3N", "104 08.4W", "2 29.2"},
        {"2017-08-21", "18:24", "37 41.1N", "087 58.8W", "36 45.2N", "088 32.5W", "37 13.2N", "088 15.7W", "2 40.2"},
        {"2017-08-21", "18:48", "33 23.1N", "078 59.5W", "32 32.4N", "079 42.6W", "32 57.8N", "079 21.2W", "2 33.9"},
        {"2017-08-21", "19:24", "25 39.0N", "065 00.2W", "24 59.0N", "065 51.7W", "25 19.1N", "065 26.1W", "2 05.2"},
        {"2024-04-08", "16:44", "04 20.5S", "145 29.6W", "05 08.4S", "143 00.6W", "04 44.0S", "144 13.0W", "2 36.8"},
        {"2024-04-08", "17:32", "12 34.9N", "117 21.7W", "11 30.8N", "115 49.0W", "12 02.9N", "116 35.1W", "4 04.8"},
        {"2024-04-08", "18:04", "22 03.5N", "108 26.8W", "20 53.5N", "106 58.4W", "21 28.5N", "107 42.4W", "4 25.7"},
        {"2024-04-08", "18:36", "31 15.0N", "099 19.9W", "29 56.0N", "098 00.1W", "30 35.4N", "098 39.9W", "4 25.3"},
        {"2024-04-08", "19:08", "40 18.2N", "086 26.5W", "38 47.4N", "085 31.1W", "39 32.6N", "085 58.9W", "4 01.7"},
        {"2024-04-08", "19:40", "48 29.1N", "060 26.8W", "46 51.5N", "060 59.6W", "47 40.1N", "060 44.7W", "3 07.7"},
    };

    /** Distance (km) from a point to a polyline of {lat, lon} points, on a 6371-km sphere. */
    private static double toLine(java.util.List<double[]> line, double lat, double lon) {
        double[] q = xyz(lat, lon), best = null;
        double min = Double.MAX_VALUE;
        for (int i = 0; i + 1 < line.size(); i++) {
            double[] a = xyz(line.get(i)[0], line.get(i)[1]), b = xyz(line.get(i + 1)[0], line.get(i + 1)[1]);
            double[] ab = {b[0] - a[0], b[1] - a[1], b[2] - a[2]};
            double l2 = ab[0] * ab[0] + ab[1] * ab[1] + ab[2] * ab[2];
            double u = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((q[0] - a[0]) * ab[0] + (q[1] - a[1]) * ab[1] + (q[2] - a[2]) * ab[2]) / l2));
            double dx = a[0] + u * ab[0] - q[0], dy = a[1] + u * ab[1] - q[1], dz = a[2] + u * ab[2] - q[2];
            min = Math.min(min, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        return min;
    }

    private static double[] xyz(double lat, double lon) {
        double p = Math.toRadians(lat), l = Math.toRadians(lon);
        return new double[]{6371 * Math.cos(p) * Math.cos(l), 6371 * Math.cos(p) * Math.sin(l), 6371 * Math.sin(p)};
    }

    /**
     * Where the path lies on the ground (independent of timing): every NASA central-line and
     * limit point within a few km of our central line and limits; and the duration of
     * totality on the central line. Also the timing: our central line at NASA's instant.
     *
     * <p>What is left is mostly NASA's, not ours. The tables assume ΔT = 68.4 s (2017) and
     * 70.6 s (2024); the true TT − UTC on both dates was 69.184 s, so at a given UT our shadow
     * is 0.8 s and 1.4 s along the track from theirs (about 1 km). The 2017 tables come from
     * JPL DE405 and agree with our path to 0.3 km; the 2024 ones from VSOP87/ELP2000-85, to
     * about 1 km. (With the Meeus-truncated Moon this was 25 km and 45 s.)
     */
    @Test
    void pathMatchesNasaTables() {
        double worstX = 0, worstT = 0, worstD = 0;
        java.util.Map<String, SolarEclipse.Path> paths = new java.util.HashMap<>();
        for (String[] r : ROWS) {
            String[] d = r[0].split("-"), hm = r[1].split(":");
            double t = sim(Integer.parseInt(d[0]), Integer.parseInt(d[1]), Integer.parseInt(d[2]),
                    Integer.parseInt(hm[0]), Integer.parseInt(hm[1]), 0);
            SolarEclipse.Path p = paths.computeIfAbsent(r[0], _ -> SolarEclipse.compute(t));
            java.util.List<double[]> cl = new java.util.ArrayList<>(), nl = new java.util.ArrayList<>(), sl = new java.util.ArrayList<>();
            for (SolarEclipse.Sample s : p.central()) {
                cl.add(s.centre());
                if (s.north() != null) nl.add(s.north());
                if (s.south() != null) sl.add(s.south());
            }
            double xC = toLine(cl, dm(r[6]), dm(r[7])), xN = toLine(nl, dm(r[2]), dm(r[3])), xS = toLine(sl, dm(r[4]), dm(r[5]));
            SolarEclipse.Geometry g = SolarEclipse.at(t);
            double[] c = SolarEclipse.intersect(g.moon(), g.axis());
            assertNotNull(c, r[0] + " " + r[1] + ": shadow axis misses the Earth");
            double eT = km(SolarEclipse.geodetic(c), dm(r[6]), dm(r[7]));
            String[] ms = r[8].split(" ");
            double nasaDur = Integer.parseInt(ms[0]) * 60 + Double.parseDouble(ms[1]);
            double dur = SolarEclipse.centralDuration(c, t);
            System.out.printf("  %s %s  path offset: centre %4.1f  N %4.1f  S %4.1f km | same instant %5.1f km | duration %5.1f s (NASA %5.1f)%n",
                    r[0], r[1], xC, xN, xS, eT, dur, nasaDur);
            worstX = Math.max(worstX, Math.max(xC, Math.max(xN, xS)));
            if (!r[1].equals("16:44")) worstT = Math.max(worstT, eT);   // 16:44: sunrise, shadow smeared
            worstD = Math.max(worstD, Math.abs(dur - nasaDur));
        }
        System.out.printf("  worst: path %.1f km, same-instant %.1f km, duration %.1f s%n", worstX, worstT, worstD);
        assertTrue(worstX < 2, "path off by " + worstX + " km");
        assertTrue(worstT < 5, "shadow off its timing by " + worstT + " km");
        assertTrue(worstD < 1, "duration off by " + worstD + " s");
    }

    @Test
    void greatestEclipse() {
        // {date, NASA greatest eclipse UT, lat, lon, duration s, gamma}
        Object[][] ge = {
            {sim(2017, 8, 21, 18, 25, 32), "36 58.0N", "087 40.3W", 160.2, 0.4367},
            {sim(2024, 4, 8, 18, 17, 16), "25 17.4N", "104 08.3W", 268.1, 0.3431},
        };
        for (Object[] e : ge) {
            double tN = (double) e[0];
            SolarEclipse.Path p = SolarEclipse.compute(tN);
            assertNotNull(p);
            assertEquals(SolarEclipse.Type.TOTAL, p.type());
            double eKm = km(p.greatest(), dm((String) e[1]), dm((String) e[2]));
            double dur = SolarEclipse.centralDuration(SolarEclipse.surface(p.greatest()[0], p.greatest()[1]), p.tGreatest());
            System.out.printf("  greatest: %+.1f s, %.1f km, duration %.1f s (NASA %.1f), gamma %.4f (NASA %.4f), %d path samples%n",
                    p.tGreatest() - tN, eKm, dur, (double) e[3], p.gamma(), (double) e[4], p.central().size());
            assertEquals(tN, p.tGreatest(), 6);
            assertTrue(eKm < 2, "greatest eclipse " + eKm + " km off");
            assertEquals((double) e[3], dur, 0.5);
            assertEquals((double) e[4], p.gamma(), 0.0003);
        }
    }
}
