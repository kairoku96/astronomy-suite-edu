import java.util.*;

/**
 * Finds sky events by searching the sim's own ephemerides — the same N-body planets, lunar
 * theory and comet orbits that draw the scene — so every event is a consequence of the
 * modelled geometry, not a table:
 *
 * <ul>
 *   <li><b>Conjunctions and oppositions</b> — geocentric ecliptic longitudes equal (or 180°
 *       apart), light-time corrected; for Mercury and Venus inferior/superior conjunction.</li>
 *   <li><b>Greatest elongations</b> of Mercury and Venus — local maxima of the planet–Sun angle.</li>
 *   <li><b>Transits</b> of Mercury and Venus — inferior conjunctions where the planet's disc
 *       passes inside the Sun's (minimum separation &lt; the sum of the semi-diameters).</li>
 *   <li><b>Lunar occultations</b> of planets and the four bright ecliptic stars — conjunctions
 *       where the geocentric separation is below the Moon's semi-diameter plus its horizontal
 *       parallax, so the Moon covers the object from somewhere on Earth.</li>
 *   <li><b>Meteor-shower peaks</b> — the dates the Earth reaches the point of its orbit closest
 *       to a parent comet's orbit (the minimum orbit intersection), from the comet's current
 *       integrated orbit: 109P/Swift-Tuttle → Perseids, 55P/Tempel-Tuttle → Leonids,
 *       1P/Halley → η Aquariids and Orionids.</li>
 *   <li><b>Eclipses</b> — from {@link SolarSystemSimulation#nextEarthEclipse}.</li>
 * </ul>
 * Times are UTC sim seconds (since J2000.0, 2000-01-01 12:00).
 */
final class EventFinder {
    private EventFinder() {}

    enum Kind {
        CONJUNCTION("Conjunctions"), OPPOSITION("Oppositions"), ELONGATION("Greatest elongations"),
        TRANSIT("Transits of Mercury & Venus"), OCCULTATION("Lunar occultations"),
        METEOR("Meteor showers"), ECLIPSE("Eclipses");
        final String label;
        Kind(String label) { this.label = label; }
    }

    /** A found event; {@code target} names what to look at ("Mars", "Moon", "Sun", a star). */
    record Found(double sim, Kind kind, String title, String detail, String target) {}

    static final double DAY = 86400.0, DEG = Math.PI / 180;
    private static final double C_AUD = 299792.458 * DAY / 149597870.7;   // AU/day
    private static final double AU_KM = 149597870.7;
    private static final double R_SUN_KM = 695700, R_EARTH_KM = 6378.137, R_MOON_KM = 1737.4;
    static final String[] PLANETS = {"Mercury", "Venus", "Earth", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune"};

    // ------------------------------------------------------------------ ephemeris access

    private static final int CACHE_MAX = 4096;
    private static final Map<Double, double[]> CACHE = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Double, double[]> e) { return size() > CACHE_MAX; }
    });

    /** All heliocentric states (AU, AU/day) at a UTC sim time — planets at their own centres. */
    static double[] states(double t) {
        return CACHE.computeIfAbsent(t, SolarSystemSimulation::ephemerisStates);
    }

    /** Geocentric light-time-corrected vector (AU) of planet i at t. */
    static double[] geo(int i, double t) {
        double[] S = states(t);
        double[] E = {S[12], S[13], S[14]};
        double[] p = {S[6 * i], S[6 * i + 1], S[6 * i + 2]};
        double[] v = {S[6 * i + 3], S[6 * i + 4], S[6 * i + 5]};
        double tau = norm(sub(p, E)) / C_AUD;
        return new double[]{p[0] - v[0] * tau - E[0], p[1] - v[1] * tau - E[1], p[2] - v[2] * tau - E[2]};
    }

    /** Geocentric vector of the Sun (AU) at t. */
    static double[] sun(double t) {
        double[] S = states(t);
        return new double[]{-S[12], -S[13], -S[14]};
    }

    /** Geocentric vector of the Moon (AU) at t. */
    static double[] moon(double t) { return MoonEphemeris.geocentricEclipticAU(t); }

    static double lon(double[] g) { return Math.atan2(g[1], g[0]); }

    /** Angle between two directions (radians), accurate at small separations. */
    static double sep(double[] a, double[] b) {
        return Math.atan2(norm(cross(a, b)), dot(a, b));
    }

    static double wrap(double a) {
        a = a % (2 * Math.PI);
        if (a > Math.PI) a -= 2 * Math.PI;
        if (a < -Math.PI) a += 2 * Math.PI;
        return a;
    }

    // ------------------------------------------------------------------ root finding

    interface Fn { double at(double t); }

    /** Times in [from, to] where the angle f(t) crosses zero (f wrapped to ±π), scanned at {@code step}. */
    static List<Double> zeros(Fn f, double from, double to, double step) {
        List<Double> out = new ArrayList<>();
        double t0 = from, f0 = wrap(f.at(t0));
        for (double t1 = from + step; t1 <= to + step * 0.5; t1 += step) {
            double f1 = wrap(f.at(t1));
            if ((f0 <= 0 && f1 > 0 || f0 >= 0 && f1 < 0) && Math.abs(f1 - f0) < Math.PI) {
                double a = t0, b = t1, fa = f0;
                for (int it = 0; it < 60 && b - a > 1; it++) {
                    double m = 0.5 * (a + b), fm = wrap(f.at(m));
                    if ((fa <= 0 && fm >= 0) || (fa >= 0 && fm <= 0)) b = m; else { a = m; fa = fm; }
                }
                out.add(0.5 * (a + b));
            }
            t0 = t1; f0 = f1;
        }
        return out;
    }

    /** Golden-section minimum of g on [a, b]: {t, g(t)}. */
    static double[] minimise(Fn g, double a, double b) {
        double gr = 0.6180339887498949;
        double c = b - gr * (b - a), d = a + gr * (b - a), gc = g.at(c), gd = g.at(d);
        for (int it = 0; it < 80 && b - a > 1; it++) {
            if (gc < gd) { b = d; d = c; gd = gc; c = b - gr * (b - a); gc = g.at(c); }
            else { a = c; c = d; gc = gd; d = a + gr * (b - a); gd = g.at(d); }
        }
        double t = 0.5 * (a + b);
        return new double[]{t, g.at(t)};
    }

    // ------------------------------------------------------------------ searches

    /** Conjunctions with the Sun (inferior/superior for Mercury and Venus) and oppositions. */
    static List<Found> sunEvents(int i, double from, double to) {
        List<Found> out = new ArrayList<>();
        String name = PLANETS[i];
        for (double t : zeros(t -> lon(geo(i, t)) - lon(sun(t)), from, to, DAY)) {
            if (i < 2) {
                boolean inferior = norm(geo(i, t)) < norm(sun(t));
                out.add(new Found(t, Kind.CONJUNCTION, (inferior ? "Inferior" : "Superior") + " conjunction — " + name,
                        String.format("%s passes %s the Sun, %.2f° from it.", name, inferior ? "between Earth and" : "behind",
                                sep(geo(i, t), sun(t)) / DEG), name));
            } else {
                out.add(new Found(t, Kind.CONJUNCTION, "Solar conjunction — " + name,
                        name + " is behind the Sun, lost in its glare.", name));
            }
        }
        if (i >= 3) {
            for (double t : zeros(t -> lon(geo(i, t)) - lon(sun(t)) - Math.PI, from, to, DAY)) {
                double d = norm(geo(i, t));
                out.add(new Found(t, Kind.OPPOSITION, "Opposition — " + name,
                        String.format("%s opposite the Sun: up all night, %.3f AU (%,.0f million km) from Earth.",
                                name, d, d * AU_KM / 1e6), name));
            }
        }
        return out;
    }

    /** Greatest elongations of Mercury (0) or Venus (1). */
    static List<Found> elongations(int i, double from, double to) {
        List<Found> out = new ArrayList<>();
        Fn el = t -> sep(geo(i, t), sun(t));
        double prev2 = el.at(from - DAY), prev = el.at(from);
        for (double t = from + DAY; t <= to + DAY; t += DAY) {
            double cur = el.at(t);
            if (prev > prev2 && prev >= cur) {
                double[] m = minimise(x -> -el.at(x), t - 2 * DAY, t);
                if (m[0] >= from && m[0] <= to) {
                    boolean east = wrap(lon(geo(i, m[0])) - lon(sun(m[0]))) > 0;
                    out.add(new Found(m[0], Kind.ELONGATION,
                            String.format("Greatest %s elongation — %s", east ? "eastern" : "western", PLANETS[i]),
                            String.format("%s is %.1f° %s of the Sun — best seen in the %s sky.", PLANETS[i], -m[1] / DEG,
                                    east ? "east" : "west", east ? "evening" : "morning"), PLANETS[i]));
                }
            }
            prev2 = prev; prev = cur;
        }
        return out;
    }

    /** Geocentric conjunctions in longitude between two planets, with their separation. */
    static List<Found> pairConjunctions(int a, int b, double from, double to) {
        List<Found> out = new ArrayList<>();
        for (double t : zeros(t -> lon(geo(a, t)) - lon(geo(b, t)), from, to, DAY)) {
            double s = sep(geo(a, t), geo(b, t)) / DEG;
            double elong = sep(geo(a, t), sun(t)) / DEG;
            out.add(new Found(t, Kind.CONJUNCTION, "Conjunction — " + PLANETS[a] + " & " + PLANETS[b],
                    String.format("%.2f° apart%s, %.0f° from the Sun.", s, s < 0.5 ? " — a close pairing" : "", elong), PLANETS[a]));
        }
        return out;
    }

    /** Transits of Mercury (0) or Venus (1): {Found} at greatest transit. */
    static List<Found> transits(int i, double from, double to) {
        List<Found> out = new ArrayList<>();
        for (double t : zeros(t -> lon(geo(i, t)) - lon(sun(t)), from, to, DAY)) {
            if (norm(geo(i, t)) > norm(sun(t))) continue;               // superior conjunction
            double[] m = minimise(x -> sep(geo(i, x), sun(x)), t - 0.75 * DAY, t + 0.75 * DAY);
            double rs = Math.asin(R_SUN_KM / (norm(sun(m[0])) * AU_KM));
            double rp = Math.asin(SolarSystemSimulation.bodyRadiusM(i) / 1000 / (norm(geo(i, m[0])) * AU_KM));
            if (m[1] < rs + rp) {
                out.add(new Found(m[0], Kind.TRANSIT, "Transit of " + PLANETS[i],
                        String.format("%s crosses the Sun's disc; greatest transit %.0f″ from the Sun's centre (solar radius %.0f″).",
                                PLANETS[i], m[1] / DEG * 3600, rs / DEG * 3600), "Sun"));
            }
        }
        return out;
    }

    /** Bright stars near the ecliptic that the Moon can occult: name, J2000 RA (h), Dec (deg). */
    static final Object[][] STARS = {
        {"Aldebaran", 4 + 35 / 60.0 + 55.24 / 3600, 16 + 30 / 60.0 + 33.5 / 3600},
        {"Regulus", 10 + 8 / 60.0 + 22.31 / 3600, 11 + 58 / 60.0 + 1.9 / 3600},
        {"Spica", 13 + 25 / 60.0 + 11.58 / 3600, -(11 + 9 / 60.0 + 40.8 / 3600)},
        {"Antares", 16 + 29 / 60.0 + 24.46 / 3600, -(26 + 25 / 60.0 + 55.2 / 3600)},
    };

    /** Geocentric direction (ecliptic J2000, unit) of an occultation target at t: a planet index or a star. */
    private static double[] targetDir(Object target, double t) {
        if (target instanceof Integer i) return geo(i, t);
        Object[] s = (Object[]) target;
        double ra = (Double) s[1] * 15 * DEG, dec = (Double) s[2] * DEG;
        double[] q = Astro.radec(ra, dec);
        return Astro.eqToEcl(q[0], q[1], q[2]);
    }

    /** Lunar occultations of the planets Mercury–Neptune and the bright ecliptic stars. */
    static List<Found> occultations(double from, double to) {
        List<Found> out = new ArrayList<>();
        List<Object> targets = new ArrayList<>();
        for (int i = 0; i < 8; i++) if (i != 2) targets.add(i);
        targets.addAll(Arrays.asList(STARS));
        for (Object tg : targets) {
            String name = tg instanceof Integer i ? PLANETS[i] : (String) ((Object[]) tg)[0];
            for (double t : zeros(x -> lon(moon(x)) - lon(targetDir(tg, x)), from, to, 3 * 3600.0)) {
                double[] m = minimise(x -> sep(moon(x), targetDir(tg, x)), t - 4 * 3600, t + 4 * 3600);
                double d = norm(moon(m[0])) * AU_KM;
                double sMoon = Math.asin(R_MOON_KM / d), parallax = Math.asin(R_EARTH_KM / d);
                double sTarget = tg instanceof Integer i ? Math.asin(SolarSystemSimulation.bodyRadiusM(i) / 1000 / (norm(geo(i, m[0])) * AU_KM)) : 0;
                if (m[1] < sMoon + parallax + sTarget) {
                    double elong = sep(moon(m[0]), sun(m[0])) / DEG;
                    out.add(new Found(m[0], Kind.OCCULTATION, "Moon occults " + name,
                            String.format("Geocentric separation %.2f° (Moon's radius %.2f° + parallax %.2f°): the Moon covers %s "
                                    + "from part of the Earth. Moon %.0f° from the Sun.", m[1] / DEG, sMoon / DEG, parallax / DEG, name, elong),
                            "Moon"));
                }
            }
        }
        return out;
    }

    /** Parent comets in the catalogue and their showers, keyed by solar-longitude range of the encounter. */
    static final Object[][] SHOWERS = {
        {"109P/Swift-Tuttle", 100.0, 180.0, "Perseids"},
        {"55P/Tempel-Tuttle", 200.0, 270.0, "Leonids"},
        {"1P/Halley", 20.0, 80.0, "η Aquariids"},
        {"1P/Halley", 180.0, 240.0, "Orionids"},
    };

    /**
     * Meteor-shower peaks: for each parent comet, the points of the Earth's orbit closest to the
     * comet's current orbit (local minima of the distance), and the dates the Earth reaches them.
     */
    static List<Found> meteorShowers(double from, double to) {
        List<Found> out = new ArrayList<>();
        double mid = 0.5 * (from + to);
        double[] S = states(mid);
        double[] E = Arrays.copyOfRange(S, 12, 18);
        double[] eEl = NBodyEphemeris.elements(NBodyEphemeris.GM_SUN + NBodyEphemeris.GM[2], E[0], E[1], E[2], E[3], E[4], E[5]);
        Set<String> parents = new LinkedHashSet<>();
        for (Object[] sh : SHOWERS) parents.add((String) sh[0]);
        for (String parent : parents) {
            int k = NBodyEphemeris.indexOf(parent);
            double[] c = Arrays.copyOfRange(S, 6 * k, 6 * k + 6);
            double[] cEl = NBodyEphemeris.elements(NBodyEphemeris.GM_SUN, c[0], c[1], c[2], c[3], c[4], c[5]);
            int nC = 2000;
            double[][] cp = new double[nC][];
            for (int j = 0; j < nC; j++) cp[j] = orbitPoint(cEl, 2 * Math.PI * j / nC);
            int nE = 1440;
            double[] dmin = new double[nE];
            for (int j = 0; j < nE; j++) {
                double[] ep = orbitPoint(eEl, 2 * Math.PI * j / nE);
                double best = Double.MAX_VALUE;
                for (double[] q : cp) best = Math.min(best, dist2(ep, q));
                dmin[j] = Math.sqrt(best);
            }
            for (int j = 0; j < nE; j++) {
                double a = dmin[(j + nE - 1) % nE], b = dmin[j], d = dmin[(j + 1) % nE];
                if (!(b < a && b <= d) || b > 0.25) continue;
                double[] ep = orbitPoint(eEl, 2 * Math.PI * j / nE);
                double earthLon = Math.atan2(ep[1], ep[0]);
                double solarLon = (earthLon / DEG + 180 + 360) % 360;
                String shower = null;
                for (Object[] sh : SHOWERS)
                    if (sh[0].equals(parent) && solarLon >= (Double) sh[1] && solarLon < (Double) sh[2]) shower = (String) sh[3];
                if (shower == null) continue;
                final double target = earthLon;
                final String sName = shower;
                for (double t : zeros(x -> { double[] s = states(x); return Math.atan2(s[13], s[12]) - target; }, from - DAY, to, DAY)) {
                    if (t < from || t > to) continue;
                    out.add(new Found(t, Kind.METEOR, sName + " peak",
                            String.format("The Earth crosses the debris stream of %s: closest approach of the orbits %.4f AU "
                                    + "(%,.0f km) at solar longitude %.1f°.", parent, b, b * AU_KM, solarLon), "Earth"));
                }
            }
        }
        return out;
    }

    /** Heliocentric point at eccentric anomaly E on an orbit given as {@link NBodyEphemeris#elements}. */
    private static double[] orbitPoint(double[] el, double E) {
        double a = el[0], e = el[1], inc = el[2], node = el[3], om = el[4] - el[3];
        double b = a * Math.sqrt(Math.max(0, 1 - e * e));
        double xr = a * (Math.cos(E) - e), yr = b * Math.sin(E);
        double cO = Math.cos(node), sO = Math.sin(node), cw = Math.cos(om), sw = Math.sin(om), ci = Math.cos(inc), si = Math.sin(inc);
        return new double[]{(cO * cw - sO * sw * ci) * xr + (-cO * sw - sO * cw * ci) * yr,
                (sO * cw + cO * sw * ci) * xr + (-sO * sw + cO * cw * ci) * yr,
                (sw * si) * xr + (cw * si) * yr};
    }

    /** Solar and lunar eclipses in the window. */
    static List<Found> eclipses(double from, double to) {
        List<Found> out = new ArrayList<>();
        for (boolean solar : new boolean[]{true, false}) {
            double t = from;
            while (true) {
                double e = SolarSystemSimulation.nextEarthEclipse(solar, t, +1);
                if (Double.isNaN(e) || e > to) break;
                out.add(new Found(e, Kind.ECLIPSE, solar ? "Solar eclipse" : "Lunar eclipse",
                        solar ? "New Moon at a node: the Moon's shadow falls on the Earth." :
                                "Full Moon at a node: the Moon passes through the Earth's shadow.", solar ? "Sun" : "Moon"));
                t = e + 5 * DAY;
            }
        }
        return out;
    }

    /** Every event of the requested kinds in [from, to], sorted by time. */
    static List<Found> search(double from, double to, Set<Kind> kinds) {
        List<Found> out = new ArrayList<>();
        if (kinds.contains(Kind.CONJUNCTION) || kinds.contains(Kind.OPPOSITION))
            for (int i = 0; i < 8; i++) if (i != 2)
                for (Found f : sunEvents(i, from, to)) if (kinds.contains(f.kind())) out.add(f);
        if (kinds.contains(Kind.CONJUNCTION)) {
            int[] naked = {0, 1, 3, 4, 5};
            for (int a = 0; a < naked.length; a++)
                for (int b = a + 1; b < naked.length; b++) out.addAll(pairConjunctions(naked[a], naked[b], from, to));
        }
        if (kinds.contains(Kind.ELONGATION)) { out.addAll(elongations(0, from, to)); out.addAll(elongations(1, from, to)); }
        if (kinds.contains(Kind.TRANSIT)) { out.addAll(transits(0, from, to)); out.addAll(transits(1, from, to)); }
        if (kinds.contains(Kind.OCCULTATION)) out.addAll(occultations(from, to));
        if (kinds.contains(Kind.METEOR)) out.addAll(meteorShowers(from, to));
        if (kinds.contains(Kind.ECLIPSE)) out.addAll(eclipses(from, to));
        out.sort(Comparator.comparingDouble(Found::sim));
        return out;
    }

    // ------------------------------------------------------------------ vectors
    static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    static double norm(double[] a) { return Math.sqrt(dot(a, a)); }
    static double[] sub(double[] a, double[] b) { return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
    static double[] cross(double[] a, double[] b) { return Lambert.cross(a, b); }
    static double dist2(double[] a, double[] b) { double x = a[0] - b[0], y = a[1] - b[1], z = a[2] - b[2]; return x * x + y * y + z * z; }
}
