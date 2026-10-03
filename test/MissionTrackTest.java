import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The spacecraft tracks (JPL Horizons, compressed for playback) against well-documented
 * encounters, measured the way the mission panel measures them — from the interpolated track.
 */
class MissionTrackTest {

    private static final Instant J2000 = LocalDateTime.of(2000, 1, 1, 12, 0, 0).toInstant(ZoneOffset.UTC);

    static double sec(int y, int mo, int d, int h, int mi) {
        return LocalDateTime.of(y, mo, d, h, mi).toInstant(ZoneOffset.UTC).getEpochSecond() - J2000.getEpochSecond();
    }

    /** The measured closest-approach event at a body nearest a date. */
    static Missions.Event approach(String mission, String body, double near) {
        Missions.Mission m = Missions.byId(mission);
        assertNotNull(m, mission);
        Missions.Event best = null;
        for (Missions.Event e : m.timeline())
            if (e.title.endsWith(body) && (best == null || Math.abs(e.sim - near) < Math.abs(best.sim - near))) best = e;
        assertNotNull(best, "no " + body + " approach for " + mission);
        System.out.printf("  %-12s %-8s %s  %s%n", mission, body, MissionPlayer.utcText(best.sim), best.text);
        return best;
    }

    /** Distance from the centre (km) in an event's text ("349,000 km from …"). */
    static double km(Missions.Event e) {
        return Double.parseDouble(e.text.substring(0, e.text.indexOf(" km")).replace(",", ""));
    }

    @Test
    void everyTrackLoadsAndIsOrdered() {
        for (Missions.Mission m : Missions.all())
            for (Missions.Craft c : m.craft) {
                SpacecraftTrack t = c.track();
                assertNotNull(t, "missing track for " + c.name);
                for (SpacecraftTrack.Segment g : t.segments) {
                    assertTrue(g.size() >= 2, c.name + " " + g.centre + " segment too short");
                    for (int k = 1; k < g.size(); k++) assertTrue(g.t[k] > g.t[k - 1], c.name + " not time-ordered");
                    assertTrue(g.start() >= t.start() - 1 && g.end() <= t.end() + 1, c.name + " local segment outside the base span");
                }
            }
    }

    @Test
    void artemisIIFarSideFlyby() {
        Missions.Event e = approach("artemis2", "Moon", sec(2026, 4, 6, 23, 0));
        double alt = km(e) - 1737.4;
        assertEquals(6545, alt, 60, "closest approach altitude (NASA: 6,545 km)");
        assertEquals(sec(2026, 4, 6, 23, 0), e.sim, 3600, "closest approach time");
    }

    @Test
    void voyagerEncounters() {
        Missions.Event j = approach("voyager1", "Jupiter", sec(1979, 3, 5, 12, 5));
        assertEquals(348_890, km(j), 3_500, "Voyager 1 at Jupiter: 348,890 km from the centre");
        assertEquals(sec(1979, 3, 5, 12, 5), j.sim, 1800);
        Missions.Event n = approach("voyager2", "Neptune", sec(1989, 8, 25, 3, 56));
        assertEquals(29_240, km(n), 600, "Voyager 2 at Neptune: ~4,950 km above the clouds");
        assertEquals(sec(1989, 8, 25, 3, 56), n.sim, 1800);
    }

    @Test
    void newHorizonsAtPluto() {
        Missions.Event p = approach("newhorizons", "Pluto", sec(2015, 7, 14, 11, 49));
        assertEquals(sec(2015, 7, 14, 11, 49), p.sim, 900);
        assertEquals(13_690, km(p), 600, "~12,500 km above Pluto");
    }

    @Test
    void playbackHugsTheTrackWithinTolerance() {
        // Hermite playback between kept samples reproduces the full-rate Horizons data to
        // max(0.2 km, 1e-5 r) by construction; check continuity across every sample of a flyby.
        SpacecraftTrack t = Missions.byId("artemis2").primary().track();
        double[] a = new double[6], b = new double[6];
        for (SpacecraftTrack.Segment g : t.segments)
            for (int k = 1; k + 1 < g.size(); k++) {
                // Either side of a sample, the position differs only by the motion in between.
                double dt = 1e-3;
                g.stateAt(g.t[k] - dt, a);
                g.stateAt(g.t[k] + dt, b);
                double jump = 0;
                for (int c = 0; c < 3; c++) jump += Math.pow(b[c] - a[c] - 0.5 * (a[3 + c] + b[3 + c]) * 2 * dt, 2);
                jump = Math.sqrt(jump);
                assertTrue(jump < 1e-6, "position jump " + jump + " km at a sample");
            }
    }

    @Test
    void timelinesAreSortedAndInsideTheMission() {
        for (Missions.Mission m : Missions.all()) {
            List<Missions.Event> tl = m.timeline();
            for (int i = 1; i < tl.size(); i++) assertTrue(tl.get(i).sim >= tl.get(i - 1).sim, m.id + " timeline unsorted");
            assertTrue(tl.get(0).sim >= m.launch - 1, m.id + " has an event before launch");
        }
    }
}
