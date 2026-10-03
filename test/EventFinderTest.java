import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The event finder against published almanac dates (US Naval Observatory / NASA / IMO): every
 * event comes out of the sim's own ephemerides, so this checks the planets, the Moon, the
 * light-time handling and the comet orbits all at once.
 */
class EventFinderTest {

    private static final Instant J2000 = LocalDateTime.of(2000, 1, 1, 12, 0, 0).toInstant(ZoneOffset.UTC);
    private static final double DAY = 86400;

    static double sec(int y, int mo, int d, int h, int mi) {
        return LocalDateTime.of(y, mo, d, h, mi).toInstant(ZoneOffset.UTC).getEpochSecond() - J2000.getEpochSecond();
    }

    static LocalDateTime utc(double s) {
        return LocalDateTime.ofInstant(J2000.plusSeconds(Math.round(s)), ZoneOffset.UTC);
    }

    /** The one event of the list nearest {@code want}, asserting it lies within {@code tolDays}. */
    static EventFinder.Found near(List<EventFinder.Found> list, double want, double tolDays, String what) {
        EventFinder.Found best = null;
        for (EventFinder.Found f : list) if (best == null || Math.abs(f.sim() - want) < Math.abs(best.sim() - want)) best = f;
        assertNotNull(best, "no " + what + " found");
        double d = (best.sim() - want) / DAY;
        System.out.printf("  %-44s got %s  (Δ %+.2f d)%n", what, utc(best.sim()), d);
        assertTrue(Math.abs(d) <= tolDays, what + ": off by " + d + " days");
        return best;
    }

    @Test
    void marsOppositions() {
        int[][] dates = {{2003, 8, 28}, {2005, 11, 7}, {2018, 7, 27}, {2020, 10, 13}, {2022, 12, 8}, {2025, 1, 16}, {2027, 2, 19}};
        for (int[] d : dates) {
            double w = sec(d[0], d[1], d[2], 12, 0);
            List<EventFinder.Found> f = EventFinder.sunEvents(3, w - 40 * DAY, w + 40 * DAY);
            f.removeIf(x -> x.kind() != EventFinder.Kind.OPPOSITION);
            near(f, w, 1.0, "Mars opposition " + d[0]);
        }
    }

    @Test
    void jupiterAndSaturnOppositions() {
        int[][] jup = {{2022, 9, 26}, {2023, 11, 3}, {2024, 12, 7}, {2026, 1, 10}};
        for (int[] d : jup) {
            double w = sec(d[0], d[1], d[2], 12, 0);
            List<EventFinder.Found> f = EventFinder.sunEvents(4, w - 40 * DAY, w + 40 * DAY);
            f.removeIf(x -> x.kind() != EventFinder.Kind.OPPOSITION);
            near(f, w, 1.0, "Jupiter opposition " + d[0]);
        }
        int[][] sat = {{2023, 8, 27}, {2024, 9, 8}, {2025, 9, 21}};
        for (int[] d : sat) {
            double w = sec(d[0], d[1], d[2], 12, 0);
            List<EventFinder.Found> f = EventFinder.sunEvents(5, w - 40 * DAY, w + 40 * DAY);
            f.removeIf(x -> x.kind() != EventFinder.Kind.OPPOSITION);
            near(f, w, 1.0, "Saturn opposition " + d[0]);
        }
    }

    @Test
    void venusConjunctionsAndElongations() {
        List<EventFinder.Found> c = EventFinder.sunEvents(1, sec(2023, 1, 1, 0, 0), sec(2025, 12, 31, 0, 0));
        c.removeIf(x -> !x.title().startsWith("Inferior"));
        near(c, sec(2023, 8, 13, 12, 0), 1.0, "Venus inferior conjunction 2023");
        near(c, sec(2025, 3, 23, 0, 0), 1.0, "Venus inferior conjunction 2025");
        List<EventFinder.Found> e = EventFinder.elongations(1, sec(2023, 1, 1, 0, 0), sec(2025, 12, 31, 0, 0));
        assertTrue(near(e, sec(2023, 6, 4, 12, 0), 1.5, "Venus GEE 2023-06-04").title().contains("eastern"));
        assertTrue(near(e, sec(2023, 10, 23, 12, 0), 1.5, "Venus GEW 2023-10-23").title().contains("western"));
        assertTrue(near(e, sec(2025, 1, 10, 12, 0), 1.5, "Venus GEE 2025-01-10").detail().contains("47."));
        assertTrue(near(e, sec(2025, 6, 1, 12, 0), 1.5, "Venus GEW 2025-06-01").title().contains("western"));
    }

    @Test
    void mercuryElongations2024() {
        List<EventFinder.Found> e = EventFinder.elongations(0, sec(2024, 1, 1, 0, 0), sec(2024, 12, 31, 23, 0));
        Object[][] want = {{3, 24, "eastern", "18."}, {5, 9, "western", "26."}, {7, 22, "eastern", "26."},
                {9, 5, "western", "18."}, {11, 16, "eastern", "22."}, {12, 25, "western", "22."}};
        for (Object[] w : want) {
            EventFinder.Found f = near(e, sec(2024, (Integer) w[0], (Integer) w[1], 12, 0), 1.5, "Mercury elongation 2024-" + w[0] + "-" + w[1]);
            assertTrue(f.title().contains((String) w[2]), f.title());
            assertTrue(f.detail().contains((String) w[3]), f.detail());
        }
    }

    @Test
    void greatConjunction2020() {
        List<EventFinder.Found> f = EventFinder.pairConjunctions(4, 5, sec(2020, 11, 1, 0, 0), sec(2021, 2, 1, 0, 0));
        EventFinder.Found g = near(f, sec(2020, 12, 21, 18, 0), 0.5, "Jupiter–Saturn great conjunction");
        assertTrue(g.detail().startsWith("0.10") || g.detail().startsWith("0.11"), g.detail());
    }

    @Test
    void transitsOfMercuryAndVenus() {
        List<EventFinder.Found> m = EventFinder.transits(0, sec(2003, 1, 1, 0, 0), sec(2020, 1, 1, 0, 0));
        assertEquals(4, m.size(), "Mercury transits 2003–2019: 2003, 2006, 2016, 2019");
        near(m, sec(2003, 5, 7, 7, 52), 0.02, "Mercury transit 2003 (greatest 07:52 UT)");
        near(m, sec(2006, 11, 8, 21, 41), 0.02, "Mercury transit 2006 (greatest 21:41 UT)");
        near(m, sec(2016, 5, 9, 14, 57), 0.02, "Mercury transit 2016 (greatest 14:57 UT)");
        near(m, sec(2019, 11, 11, 15, 20), 0.02, "Mercury transit 2019 (greatest 15:20 UT)");
        assertTrue(EventFinder.transits(0, sec(2020, 1, 1, 0, 0), sec(2032, 6, 1, 0, 0)).isEmpty(), "no Mercury transit 2020–mid 2032");
        List<EventFinder.Found> v = EventFinder.transits(1, sec(2000, 1, 1, 0, 0), sec(2030, 1, 1, 0, 0));
        assertEquals(2, v.size(), "Venus transits this century: 2004 and 2012");
        near(v, sec(2004, 6, 8, 8, 20), 0.02, "Venus transit 2004 (greatest 08:20 UT)");
        near(v, sec(2012, 6, 6, 1, 29), 0.02, "Venus transit 2012 (greatest 01:29 UT)");
    }

    @Test
    void lunarOccultations() {
        List<EventFinder.Found> o = EventFinder.occultations(sec(2022, 12, 1, 0, 0), sec(2022, 12, 15, 0, 0));
        o.removeIf(x -> !x.title().endsWith("Mars"));
        near(o, sec(2022, 12, 8, 5, 0), 0.3, "Moon occults Mars 2022-12-08");
        o = EventFinder.occultations(sec(2023, 11, 1, 0, 0), sec(2023, 11, 20, 0, 0));
        o.removeIf(x -> !x.title().endsWith("Venus"));
        near(o, sec(2023, 11, 9, 9, 0), 0.3, "Moon occults Venus 2023-11-09");
    }

    @Test
    void meteorShowerPeaks() {
        List<EventFinder.Found> s = EventFinder.meteorShowers(sec(2025, 1, 1, 0, 0), sec(2025, 12, 31, 0, 0));
        List<EventFinder.Found> per = s.stream().filter(f -> f.title().startsWith("Perseids")).toList();
        List<EventFinder.Found> leo = s.stream().filter(f -> f.title().startsWith("Leonids")).toList();
        // IMO: Perseids peak at solar longitude 140.0° (12–13 Aug), Leonids at 235.27° (17–18 Nov).
        near(per, sec(2025, 8, 12, 18, 0), 1.5, "Perseids 2025");
        near(leo, sec(2025, 11, 17, 12, 0), 1.5, "Leonids 2025");
    }

    @Test
    void eclipsesListed() {
        List<EventFinder.Found> e = EventFinder.eclipses(sec(2024, 1, 1, 0, 0), sec(2024, 12, 31, 0, 0));
        near(e, sec(2024, 4, 8, 18, 17), 0.05, "Total solar eclipse 2024-04-08");
    }
}
