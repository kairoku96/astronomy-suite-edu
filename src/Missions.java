import java.awt.Color;
import java.nio.file.*;
import java.util.*;

/**
 * The catalogue of spacecraft the sim can fly: deep-space probes played back from JPL Horizons
 * vectors, and lunar missions — Artemis I and II and Chandrayaan-3 from Horizons, Apollo 8, 11,
 * 13 and 17 reconstructed by {@link ApolloReconstruction}. Each mission has its craft (with
 * their track files), a timeline of events, and the documented facts behind them.
 *
 * <p>Event times come from two places: documented ones (launches, landings, burns — Apollo from
 * NASA SP-2000-4029 <i>Apollo by the Numbers</i>) and ones <em>measured from the trajectory</em>
 * (closest approaches, farthest distance from Earth), which are found by scanning the track
 * itself so they always agree with what is drawn.
 */
final class Missions {
    private Missions() {}

    enum Kind { LUNAR, DEEP_SPACE }

    /** A timeline entry. {@code viewKm} &gt; 0 asks the auto camera for that view radius around the event. */
    static final class Event {
        final double sim;          // UTC sim seconds since J2000
        final String title, text;
        final double viewKm;
        Event(double sim, String title, String text, double viewKm) {
            this.sim = sim; this.title = title; this.text = text; this.viewKm = viewKm;
        }
    }

    /** One vehicle with its own track (a CSM, a lander, a probe). */
    static final class Craft {
        final String name, file;
        final Color color;
        private SpacecraftTrack track;
        private boolean tried;
        Craft(String name, String file, Color color) { this.name = name; this.file = file; this.color = color; }

        /** The track, loaded on first use (null if the asset is missing). */
        synchronized SpacecraftTrack track() {
            if (!tried) {
                tried = true;
                try { track = SpacecraftTrack.load(Paths.get("assets", "missions", file + ".trk.gz")); }
                catch (Exception e) { System.err.println("No track for " + name + ": " + e.getMessage()); }
            }
            return track;
        }
    }

    static final class Mission {
        final String id, name, subtitle, summary;
        final Kind kind;
        final double launch;                       // UTC sim seconds
        final List<Craft> craft = new ArrayList<>();
        final List<Event> events = new ArrayList<>();
        private boolean measured;
        Mission(String id, String name, String subtitle, Kind kind, double launch, String summary) {
            this.id = id; this.name = name; this.subtitle = subtitle; this.kind = kind;
            this.launch = launch; this.summary = summary;
        }

        Craft primary() { return craft.get(0); }

        /** First and last instants (UTC sim s) any of this mission's tracks cover. */
        double[] span() {
            double a = Double.MAX_VALUE, b = -Double.MAX_VALUE;
            for (Craft c : craft) {
                SpacecraftTrack t = c.track();
                if (t == null) continue;
                a = Math.min(a, utcOf(t.start()));
                b = Math.max(b, utcOf(t.end()));
            }
            return new double[]{a, b};
        }

        /** Events sorted by time, with the trajectory-measured ones added on first call. */
        synchronized List<Event> timeline() {
            if (!measured) {
                measured = true;
                measure(this);
                events.sort(Comparator.comparingDouble(e -> e.sim));
            }
            return events;
        }

        void at(double sim, String title, String text, double viewKm) { events.add(new Event(sim, title, text, viewKm)); }
        void get(double getSec, String title, String text, double viewKm) { at(launch + getSec, title, text, viewKm); }
    }

    // ------------------------------------------------------------------ time helpers

    static double utc(int y, int mo, int d, int h, int mi, double s) {
        return java.time.Duration.between(java.time.LocalDateTime.of(2000, 1, 1, 12, 0, 0),
                java.time.LocalDateTime.of(y, mo, d, h, mi, 0)).getSeconds() + s;
    }

    static double get(int h, int m, double s) { return h * 3600 + m * 60 + s; }

    /** UTC sim seconds for a TDB instant (inverts {@link Astro#ttSeconds}). */
    static double utcOf(double tdb) {
        double u = tdb - Astro.deltaTSeconds(Astro.decimalYear(tdb));
        return tdb - Astro.deltaTSeconds(Astro.decimalYear(u));
    }

    // ------------------------------------------------------------------ the catalogue

    private static List<Mission> ALL;

    static synchronized List<Mission> all() {
        if (ALL == null) ALL = build();
        return ALL;
    }

    static Mission byId(String id) {
        for (Mission m : all()) if (m.id.equals(id)) return m;
        return null;
    }

    private static final Color CSM = new Color(255, 214, 120), LM = new Color(255, 150, 90),
            ORION = new Color(130, 220, 255), PROBE = new Color(140, 255, 190), ISRO = new Color(255, 170, 210);

    private static List<Mission> build() {
        List<Mission> L = new ArrayList<>();
        Mission m;

        // ---------------- Apollo (reconstructed) ----------------
        m = new Mission("apollo8", "Apollo 8", "Borman · Lovell · Anders — first crew to the Moon", Kind.LUNAR,
                utc(1968, 12, 21, 12, 51, 0),
                "The first crewed flight around the Moon: ten lunar orbits over Christmas Eve 1968.");
        m.craft.add(new Craft("Apollo 8", "apollo8", CSM));
        m.get(0, "Launch", "Saturn V lifts off from Kennedy Space Center, 12:51 UTC.", 0);
        m.get(get(2, 50, 37), "Translunar injection", "The S-IVB burns for 5 min 18 s and sends the first crew away from the Earth at 10.8 km/s.", 0);
        m.get(get(69, 8, 20.4), "Lunar orbit insertion", "Behind the Moon, out of contact, the SPS burns 4 min 7 s into a 312 × 111 km orbit.", 8000);
        m.get(get(73, 35, 6.6), "Circularisation", "A 9.6 s burn trims the orbit to 112 × 110 km.", 8000);
        m.get(get(75, 47, 0), "Earthrise", "On the fourth orbit Anders photographs the Earth rising over the lunar horizon.", 8000);
        m.get(get(85, 43, 0), "Christmas Eve broadcast", "Reading from Genesis on the ninth orbit, the most-watched broadcast of its time.", 8000);
        m.get(get(89, 19, 16.6), "Transearth injection", "The burn behind the Moon that brings the crew home — Lovell: “there is a Santa Claus”.", 8000);
        m.get(get(146, 46, 12.8), "Entry interface", "The command module meets the atmosphere at 11 km/s, 122 km up.", 0);
        m.get(get(147, 0, 42), "Splashdown", "Pacific Ocean, 27 December 1968.", 0);
        L.add(m);

        m = new Mission("apollo11", "Apollo 11", "Armstrong · Collins · Aldrin — first landing", Kind.LUNAR,
                utc(1969, 7, 16, 13, 32, 0),
                "The first crewed landing: Eagle sets down in the Sea of Tranquility while Columbia orbits overhead.");
        m.craft.add(new Craft("Columbia", "apollo11", CSM));
        m.craft.add(new Craft("Eagle", "apollo11_lm", LM));
        m.get(0, "Launch", "Saturn V liftoff, 13:32 UTC, 16 July 1969.", 0);
        m.get(get(2, 44, 16.2), "Translunar injection", "A 5 min 47 s S-IVB burn over the Pacific.", 0);
        m.get(get(75, 49, 50.37), "Lunar orbit insertion", "Columbia and Eagle brake into a 314 × 111 km orbit.", 8000);
        m.get(get(80, 11, 36.75), "Circularisation", "LOI-2 trims the orbit to 122 × 101 km.", 8000);
        m.get(get(100, 12, 0), "Undocking", "Eagle separates from Columbia: “The Eagle has wings.”", 400);
        m.get(get(101, 36, 14), "Descent orbit insertion", "Eagle lowers its perilune to 15 km, half an orbit before the landing site.", 3000);
        m.get(get(102, 33, 5), "Powered descent", "The descent engine fires 480 km up-range of Tranquility; 1202 and 1201 alarms follow.", 300);
        m.get(get(102, 45, 39.9), "Landing — Tranquility Base", "“The Eagle has landed.” 0.674° N, 23.473° E, 20:17:40 UTC.", 150);
        m.get(get(109, 24, 15), "First step", "Armstrong steps onto the Moon at 02:56 UTC, 21 July.", 150);
        m.get(get(124, 22, 0.79), "Lunar liftoff", "Eagle's ascent stage lifts off after 21½ hours on the surface.", 300);
        m.get(get(127, 3, 51.8), "Terminal phase initiation", "Eagle's rendezvous burn toward Columbia.", 600);
        m.get(get(128, 3, 0), "Docking", "Eagle and Columbia dock again.", 400);
        m.get(get(135, 23, 42.28), "Transearth injection", "Columbia's engine fires behind the Moon for the trip home.", 8000);
        m.get(get(195, 3, 5.7), "Entry interface", "11 km/s at 122 km altitude.", 0);
        m.get(get(195, 18, 35), "Splashdown", "Pacific Ocean, 24 July 1969.", 0);
        L.add(m);

        m = new Mission("apollo13", "Apollo 13", "Lovell · Swigert · Haise — “a successful failure”", Kind.LUNAR,
                utc(1970, 4, 11, 19, 13, 0),
                "An oxygen tank exploded 56 hours out. The crew looped around the Moon in the lunar module and came home.");
        m.craft.add(new Craft("Odyssey / Aquarius", "apollo13", CSM));
        m.get(0, "Launch", "Liftoff 19:13 UTC, 11 April 1970.", 0);
        m.get(get(2, 35, 46.3), "Translunar injection", "", 0);
        m.get(get(30, 40, 49.65), "Hybrid transfer", "A 3.5 s burn takes Apollo 13 off its free-return path, toward Fra Mauro.", 0);
        m.get(get(55, 54, 53), "“Houston, we've had a problem”", "Oxygen tank 2 explodes; the service module loses its oxygen and power.", 0);
        m.get(get(61, 29, 43.49), "Back to free return", "Aquarius' descent engine burns 34 s to put the stack back on a free-return path.", 0);
        m.get(get(77, 8, 35), "Behind the Moon", "Loss of signal: the crew passes 254 km above the far side, 400 171 km from the Earth — then the farthest humans had been.", 12000);
        m.get(get(79, 27, 38.95), "PC+2 burn", "Two hours after pericynthion Aquarius' engine fires 4.4 min to speed the return.", 0);
        m.get(get(138, 1, 48), "Service module jettisoned", "The crew sees the damage for the first time.", 0);
        m.get(get(141, 30, 0.2), "Aquarius jettisoned", "The lifeboat is released an hour before entry.", 0);
        m.get(get(142, 40, 45.7), "Entry interface", "", 0);
        m.get(get(142, 54, 41), "Splashdown", "South Pacific, 17 April 1970.", 0);
        L.add(m);

        m = new Mission("apollo17", "Apollo 17", "Cernan · Evans · Schmitt — the last landing", Kind.LUNAR,
                utc(1972, 12, 7, 5, 33, 0),
                "The last Apollo landing: three days in the Taurus–Littrow valley with the lunar rover.");
        m.craft.add(new Craft("America", "apollo17", CSM));
        m.craft.add(new Craft("Challenger", "apollo17_lm", LM));
        m.get(0, "Launch", "The only night launch of a Saturn V, 05:33 UTC.", 0);
        m.get(get(3, 12, 36.6), "Translunar injection", "", 0);
        m.get(get(86, 14, 22.6), "Lunar orbit insertion", "A 393 s burn into a 315 × 97 km orbit.", 8000);
        m.get(get(90, 31, 37.43), "Descent orbit insertion 1", "The docked pair drops to 109 × 27 km.", 8000);
        m.get(get(107, 47, 56), "Separation", "Challenger undocks from America.", 400);
        m.get(get(109, 22, 42), "Descent orbit insertion 2", "Challenger lowers its perilune to 11.5 km.", 3000);
        m.get(get(110, 9, 53), "Powered descent", "", 300);
        m.get(get(110, 21, 58), "Landing — Taurus–Littrow", "20.191° N, 30.772° E, 19:54:57 UTC, 11 December.", 150);
        m.get(get(185, 21, 37), "Lunar liftoff", "After 75 hours on the surface — the last humans to leave the Moon.", 300);
        m.get(get(187, 37, 15), "Docking", "", 400);
        m.get(get(234, 2, 9.18), "Transearth injection", "", 8000);
        m.get(get(301, 38, 38), "Entry interface", "", 0);
        m.get(get(301, 51, 59), "Splashdown", "Pacific Ocean, 19 December 1972.", 0);
        L.add(m);

        // ---------------- Lunar (JPL Horizons) ----------------
        m = new Mission("chandrayaan3", "Chandrayaan-3", "ISRO — first landing near the lunar south pole", Kind.LUNAR,
                utc(2023, 7, 14, 9, 5, 0),
                "A month of orbit-raising around the Earth, a slow capture into lunar orbit, and a soft landing at 69° S.");
        m.craft.add(new Craft("Vikram", "chandrayaan3", ISRO));
        m.at(m.launch, "Launch", "LVM3 from Sriharikota, 09:05 UTC.", 0);
        m.at(utc(2023, 8, 23, 12, 33, 0), "Landing", "Vikram touches down at 69.37° S, 32.32° E, 12:33 UTC, 23 August 2023.", 300);
        L.add(m);

        m = new Mission("artemis1", "Artemis I", "Orion (uncrewed) — distant retrograde orbit", Kind.LUNAR,
                utc(2022, 11, 16, 6, 47, 44),
                "Orion's 25-day test flight: two powered lunar flybys and half an orbit in a distant retrograde orbit.");
        m.craft.add(new Craft("Orion", "artemis1", ORION));
        m.at(m.launch, "Launch", "The first Space Launch System lifts off, 06:47 UTC.", 0);
        m.at(utc(2022, 12, 11, 17, 40, 0), "Splashdown", "Pacific Ocean off Baja California.", 0);
        L.add(m);

        m = new Mission("artemis2", "Artemis II", "Wiseman · Glover · Koch · Hansen — crewed lunar flyby", Kind.LUNAR,
                utc(2026, 4, 1, 22, 35, 0),
                "The first crew beyond low Earth orbit since 1972: a day in high Earth orbit, then a free-return loop around the far side of the Moon.");
        m.craft.add(new Craft("Orion “Integrity”", "artemis2", ORION));
        m.at(m.launch, "Launch", "SLS lifts off from Kennedy Space Center, 22:35 UTC, 1 April 2026.", 0);
        m.at(utc(2026, 4, 11, 0, 7, 0), "Splashdown", "Pacific Ocean off San Diego, 00:07 UTC, 11 April.", 0);
        L.add(m);

        // ---------------- Deep space (JPL Horizons) ----------------
        m = new Mission("voyager1", "Voyager 1", "NASA/JPL — Jupiter, Saturn and interstellar space", Kind.DEEP_SPACE,
                utc(1977, 9, 5, 12, 56, 0), "The fastest Voyager: Jupiter in 1979, Saturn and Titan in 1980, then out of the Solar System.");
        m.craft.add(new Craft("Voyager 1", "voyager1", PROBE));
        m.at(m.launch, "Launch", "Titan IIIE–Centaur from Cape Canaveral.", 0);
        m.at(utc(1990, 2, 14, 4, 48, 0), "Pale Blue Dot", "From 40 AU Voyager 1 turns and photographs the planets — Earth a pixel in a sunbeam.", 0);
        m.at(utc(2004, 12, 16, 0, 0, 0), "Termination shock", "The solar wind slows to subsonic speed at 94 AU.", 0);
        m.at(utc(2012, 8, 25, 0, 0, 0), "Heliopause", "Voyager 1 enters interstellar space at 121 AU.", 0);
        L.add(m);

        m = new Mission("voyager2", "Voyager 2", "NASA/JPL — the Grand Tour of the giant planets", Kind.DEEP_SPACE,
                utc(1977, 8, 20, 14, 29, 0), "The only spacecraft to visit Uranus and Neptune, using each giant's gravity to reach the next.");
        m.craft.add(new Craft("Voyager 2", "voyager2", PROBE));
        m.at(m.launch, "Launch", "Titan IIIE–Centaur, sixteen days before Voyager 1.", 0);
        m.at(utc(2018, 11, 5, 0, 0, 0), "Heliopause", "Voyager 2 crosses into interstellar space at 119 AU.", 0);
        L.add(m);

        m = new Mission("cassini", "Cassini–Huygens", "NASA/ESA/ASI — 13 years at Saturn", Kind.DEEP_SPACE,
                utc(1997, 10, 15, 8, 43, 0), "Two Venus flybys, one of Earth and one of Jupiter to reach Saturn; 294 orbits, Huygens on Titan, and the Grand Finale.");
        m.craft.add(new Craft("Cassini", "cassini", PROBE));
        m.at(m.launch, "Launch", "Titan IVB–Centaur from Cape Canaveral.", 0);
        m.at(utc(2004, 7, 1, 1, 12, 0), "Saturn orbit insertion", "A 96-minute main-engine burn through the ring-plane gap captures Cassini.", 4.0e6);
        m.at(utc(2005, 1, 14, 10, 13, 0), "Huygens lands on Titan", "ESA's probe parachutes to the surface of Titan.", 4.0e6);
        m.at(utc(2017, 4, 26, 9, 0, 0), "Grand Finale", "The first of 22 dives between the rings and the planet.", 4.0e5);
        m.at(utc(2017, 9, 15, 10, 31, 0), "End of mission", "Cassini plunges into Saturn's atmosphere.", 2.0e5);
        L.add(m);

        m = new Mission("newhorizons", "New Horizons", "NASA — Pluto and the Kuiper belt", Kind.DEEP_SPACE,
                utc(2006, 1, 19, 19, 0, 0), "The fastest launch ever, a Jupiter gravity assist, and the first close look at Pluto and Charon.");
        m.craft.add(new Craft("New Horizons", "newhorizons", PROBE));
        m.at(m.launch, "Launch", "Atlas V 551 — 16.26 km/s, the fastest departure from Earth.", 0);
        m.at(utc(2019, 1, 1, 5, 33, 0), "Arrokoth flyby", "The most distant object ever explored, 43 AU from the Sun.", 0);
        L.add(m);

        m = new Mission("juno", "Juno", "NASA — polar orbiter of Jupiter", Kind.DEEP_SPACE,
                utc(2011, 8, 5, 16, 25, 0), "An Earth flyby for speed, then a polar orbit of Jupiter skimming 4000 km over the clouds every perijove.");
        m.craft.add(new Craft("Juno", "juno", PROBE));
        m.at(m.launch, "Launch", "Atlas V 551 from Cape Canaveral.", 0);
        m.at(utc(2021, 6, 7, 17, 35, 0), "Ganymede flyby", "1038 km above Jupiter's largest moon.", 5.0e5);
        m.at(utc(2022, 9, 29, 9, 36, 0), "Europa flyby", "352 km above the ice shell.", 5.0e5);
        m.at(utc(2023, 12, 30, 8, 35, 0), "Io flyby", "1500 km above the most volcanic world in the Solar System.", 5.0e5);
        L.add(m);
        return L;
    }

    // ------------------------------------------------------------------ measured events

    /** Physical radii (km) of the track centres, for altitudes. */
    static double centreRadiusKm(String centre) {
        return switch (centre) {
            case "Sun" -> 695700; case "Earth" -> 6378.137; case "Moon" -> 1737.4; case "Venus" -> 6051.8;
            case "Jupiter" -> 71492; case "Saturn" -> 60268; case "Uranus" -> 25559; case "Neptune" -> 24764;
            case "Pluto" -> 1188.3; default -> 0;
        };
    }

    /**
     * Events measured from the trajectory: closest approaches in each planet/Moon segment
     * (only the first and the closest periapsis for an orbiter), the farthest distance from
     * the Earth on a lunar flight, and the end of the data.
     */
    static void measure(Mission m) {
        Craft c = m.primary();
        SpacecraftTrack t = c.track();
        if (t == null) return;
        double[] st = new double[6];
        for (int si = 1; si < t.segments.size(); si++) {
            SpacecraftTrack.Segment g = t.segments.get(si);
            if (g.centre.equals("Earth") && g.start() - t.start() < 2 * 86400) continue;   // launch departure
            List<double[]> minima = new ArrayList<>();                                      // {tdb, r}
            double prev = Double.MAX_VALUE, prev2 = Double.MAX_VALUE;
            for (int k = 0; k < g.size(); k++) {
                double r = Math.sqrt(g.s[6 * k] * g.s[6 * k] + g.s[6 * k + 1] * g.s[6 * k + 1] + g.s[6 * k + 2] * g.s[6 * k + 2]);
                if (k >= 2 && prev < prev2 && prev <= r) minima.add(refineMin(g, g.t[k - 2], g.t[k], st));
                prev2 = prev; prev = r;
            }
            if (minima.isEmpty()) continue;
            String who = g.centre;
            double R = centreRadiusKm(who);
            boolean orbiter = minima.size() > 3;
            double[] first = minima.get(0);
            double[] best = minima.stream().min(Comparator.comparingDouble(a -> a[1])).get();
            if (!orbiter) {
                for (double[] mn : minima)
                    m.events.add(new Event(utcOf(mn[0]), "Closest approach — " + who,
                            String.format("%,.0f km from %s's centre (%,.0f km above the surface)%s.", mn[1], who, mn[1] - R,
                                    m.kind == Kind.LUNAR ? "" : ", measured from the JPL trajectory"),
                            Math.max(3 * mn[1], 4 * R)));
            } else {
                m.events.add(new Event(utcOf(first[0]), "First periapsis — " + who,
                        String.format("Arrival: %,.0f km above %s. %d periapses follow in this data.", first[1] - R, who, minima.size() - 1),
                        Math.max(4 * first[1], 6 * R)));
                if (best != first)
                    m.events.add(new Event(utcOf(best[0]), "Lowest periapsis — " + who,
                            String.format("%,.0f km above %s's cloud tops.", best[1] - R, who), Math.max(3 * best[1], 3 * R)));
            }
        }
        if (m.kind == Kind.LUNAR) {
            // Farthest from the Earth, from the base (geocentric) segment.
            SpacecraftTrack.Segment b = t.base();
            double bestT = 0, bestR = 0;
            for (int k = 0; k < b.size(); k++) {
                double r = Math.sqrt(b.s[6 * k] * b.s[6 * k] + b.s[6 * k + 1] * b.s[6 * k + 1] + b.s[6 * k + 2] * b.s[6 * k + 2]);
                if (r > bestR) { bestR = r; bestT = b.t[k]; }
            }
            if (bestR > 3.0e5) {
                // The thinned samples straddle the true maximum: golden-section it on the interpolant.
                int k = b.interval(bestT);
                double lo = b.t[Math.max(0, k - 1)], hi = b.t[Math.min(b.size() - 1, k + 2)];
                double gr = 0.6180339887498949;
                for (int it = 0; it < 80 && hi - lo > 0.5; it++) {
                    double c1 = hi - gr * (hi - lo), c2 = lo + gr * (hi - lo);
                    if (dist(b, c1, st) > dist(b, c2, st)) hi = c2; else lo = c1;
                }
                bestT = 0.5 * (lo + hi);
                bestR = dist(b, bestT, st);
                m.events.add(new Event(utcOf(bestT), "Farthest from Earth",
                        String.format("%,.0f km from the Earth's centre — %,.0f km above the surface.", bestR, bestR - 6378.137), 0));
            }
        }
    }

    /** Golden-section minimum of the centre distance between ta and tb: {tdb, r}. */
    private static double[] refineMin(SpacecraftTrack.Segment g, double ta, double tb, double[] st) {
        double gr = 0.6180339887498949;
        double a = ta, b = tb;
        for (int it = 0; it < 60 && b - a > 0.5; it++) {
            double c1 = b - gr * (b - a), c2 = a + gr * (b - a);
            if (dist(g, c1, st) < dist(g, c2, st)) b = c2; else a = c1;
        }
        double tm = 0.5 * (a + b);
        return new double[]{tm, dist(g, tm, st)};
    }

    private static double dist(SpacecraftTrack.Segment g, double t, double[] st) {
        g.stateAt(t, st);
        return Math.sqrt(st[0] * st[0] + st[1] * st[1] + st[2] * st[2]);
    }
}
