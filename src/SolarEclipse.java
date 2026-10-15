import java.util.ArrayList;
import java.util.List;

/**
 * Solar eclipses on the ground: where the Moon's shadow falls, when, and how deep.
 *
 * <p>Everything here is the geometry an observer actually sees, not the classical
 * Besselian-element shortcut. At an instant the apparent Sun and Moon are put in the
 * Earth-fixed frame (ecliptic J2000 → precession + nutation → Greenwich apparent sidereal
 * time). For a point P on the WGS-84 ellipsoid, the Sun's and Moon's topocentric angular
 * radii r☉, r☾ and the separation σ of their centres decide everything:
 * <ul>
 *   <li>partial eclipse where σ &lt; r☉ + r☾ (the penumbra);</li>
 *   <li>total (r☾ &gt; r☉) or annular (r☉ &gt; r☾) where σ &lt; |r☾ − r☉| (the umbra or
 *       antumbra) — so the "central margin" g = |r☾ − r☉| − σ is positive inside the path.</li>
 * </ul>
 * The <b>central line</b> is where the Sun–Moon line meets the ellipsoid. The
 * <b>limits</b> of the path are the envelope of the moving shadow: a point is on a limit when
 * the shadow's edge reaches it and then recedes, i.e. on the footprint outline g = 0 where
 * ∂g/∂t = 0 — the same condition the published path tables use. Durations and contact
 * times are roots of g(t) and of the partial margin r☉ + r☾ − σ at a fixed place.
 *
 * <p>Reductions: the Sun is the apparent one (annual aberration of the geometric
 * direction), the Moon is the ELP/MPP02 position at the light-time-retarded instant
 * (in the geocentric frame its aberration and light-time cancel to first order). The
 * Moon's radius is k·a⊕ with NASA's eclipse values k = 0.2722810 for the umbra (a mean
 * limb, valleys included) and 0.2725076 for the penumbra; the Sun's is 959.63″ at 1 AU.
 * {@code EclipsePathTest} checks paths and durations against NASA's published tables.
 */
final class SolarEclipse {

    static final double AU_KM = 149_597_870.7;
    private static final double C_KMS = 299_792.458;
    private static final double C_AUD = C_KMS * 86400.0 / AU_KM;
    /** WGS-84 equatorial radius (km) and flattening. */
    static final double A_KM = 6378.137, FLAT = 1 / 298.257223563, E2 = FLAT * (2 - FLAT);
    static final double K_UMBRA = 0.2722810, K_PENUMBRA = 0.2725076;
    static final double SUN_KM = Math.toRadians(959.63 / 3600.0) * AU_KM;
    private static final double DAY = 86_400.0;

    private SolarEclipse() {}

    // ------------------------------------------------------------------ the instant

    /** Apparent Sun and Moon at one instant, Earth-fixed (km; x to Greenwich, z north). */
    record Geometry(double t, double[] sun, double[] moon) {
        /** Unit vector along the shadow axis (Sun → Moon, onward toward the Earth). */
        double[] axis() { return unit(sub(moon, sun)); }
    }

    /** Ecliptic-J2000 → Earth-fixed rotation at UTC sim time {@code t}. */
    private static double[] eclToEarthFixed(double t) {
        double[] P = Astro.j2000ToTrueOfDate(Astro.ttSeconds(t));
        double ce = Math.cos(Astro.EPS_J2000), se = Math.sin(Astro.EPS_J2000);
        double[] C = {1, 0, 0, 0, ce, -se, 0, se, ce};
        return Astro.mul(Astro.rotZ(Astro.gast(t)), Astro.mul(P, C));
    }

    /** Apparent geocentric Sun (AU, ecliptic J2000): −Earth, aberrated by Earth's velocity. */
    private static double[] apparentSunEcl(double t) {
        double[] e = SolarSystemSimulation.planetStateAU(2, t);
        double d = Math.sqrt(e[0] * e[0] + e[1] * e[1] + e[2] * e[2]);
        double[] u = unit(new double[]{-e[0] / d + e[3] / C_AUD, -e[1] / d + e[4] / C_AUD, -e[2] / d + e[5] / C_AUD});
        return new double[]{u[0] * d, u[1] * d, u[2] * d};
    }

    static Geometry at(double t) {
        double[] R = eclToEarthFixed(t);
        double[] s = apparentSunEcl(t);
        double[] m0 = MoonEphemeris.geocentricEclipticAU(t);
        double tau = Math.sqrt(m0[0] * m0[0] + m0[1] * m0[1] + m0[2] * m0[2]) * AU_KM / C_KMS;
        double[] m = MoonEphemeris.geocentricEclipticAU(t - tau);
        double[] sf = Astro.apply(R, s[0], s[1], s[2]), mf = Astro.apply(R, m[0], m[1], m[2]);
        for (int c = 0; c < 3; c++) { sf[c] *= AU_KM; mf[c] *= AU_KM; }
        return new Geometry(t, sf, mf);
    }

    /** Unit vector toward the apparent Sun in the Earth-fixed frame — the true sub-solar point. */
    static double[] sunDirEarthFixed(double t) {
        double[] s = apparentSunEcl(t);
        return unit(Astro.apply(eclToEarthFixed(t), s[0], s[1], s[2]));
    }

    // ------------------------------------------------------------------ the ellipsoid

    /** Point (km, Earth-fixed) on the WGS-84 ellipsoid at geodetic latitude/longitude (deg). */
    static double[] surface(double latDeg, double lonDeg) {
        double p = Math.toRadians(latDeg), l = Math.toRadians(lonDeg);
        double sp = Math.sin(p), cp = Math.cos(p);
        double n = A_KM / Math.sqrt(1 - E2 * sp * sp);
        return new double[]{n * cp * Math.cos(l), n * cp * Math.sin(l), n * (1 - E2) * sp};
    }

    /** Geodetic {latitude, longitude} (deg) of a point on the ellipsoid. */
    static double[] geodetic(double[] p) {
        double lat = Math.atan2(p[2], (1 - E2) * Math.hypot(p[0], p[1]));
        return new double[]{Math.toDegrees(lat), Math.toDegrees(Math.atan2(p[1], p[0]))};
    }

    /** First intersection of the ray o + s·d (s ≥ 0) with the ellipsoid, or null. */
    static double[] intersect(double[] o, double[] d) {
        double q = 1 / (1 - FLAT);                       // stretch z: ellipsoid → sphere of radius a
        double ox = o[0], oy = o[1], oz = o[2] * q, dx = d[0], dy = d[1], dz = d[2] * q;
        double a = dx * dx + dy * dy + dz * dz, b = ox * dx + oy * dy + oz * dz;
        double c = ox * ox + oy * oy + oz * oz - A_KM * A_KM;
        double disc = b * b - a * c;
        if (disc < 0) return null;
        double s = (-b - Math.sqrt(disc)) / a;
        if (s < 0) return null;
        return new double[]{o[0] + s * d[0], o[1] + s * d[1], o[2] + s * d[2]};
    }

    // ------------------------------------------------------------------ what P sees

    /** {σ, r☉, r☾} (rad) seen from P (km), with the Moon's radius k·a⊕. */
    static double[] seen(Geometry g, double[] p, double k) {
        double[] s = sub(g.sun, p), m = sub(g.moon, p);
        double ds = norm(s), dm = norm(m);
        double sep = Math.atan2(norm(cross(s, m)), dot(s, m));
        return new double[]{sep, Math.asin(SUN_KM / ds), Math.asin(k * A_KM / dm)};
    }

    /** Central margin |r☾ − r☉| − σ (rad): positive inside the umbra or antumbra. */
    static double centralMargin(Geometry g, double[] p) {
        double[] v = seen(g, p, K_UMBRA);
        return Math.abs(v[2] - v[1]) - v[0];
    }

    /** Partial margin r☉ + r☾ − σ (rad): positive inside the penumbra. */
    static double partialMargin(Geometry g, double[] p) {
        double[] v = seen(g, p, K_PENUMBRA);
        return v[1] + v[2] - v[0];
    }

    /** Fraction of the Sun's disc <em>area</em> hidden (the published "obscuration"). */
    static double obscuration(double sep, double rs, double rm) {
        if (sep >= rs + rm) return 0;
        if (sep <= Math.abs(rs - rm)) return Math.min(1, rm * rm / (rs * rs));
        double c1 = Math.acos(clamp((sep * sep + rs * rs - rm * rm) / (2 * sep * rs)));
        double c2 = Math.acos(clamp((sep * sep + rm * rm - rs * rs) / (2 * sep * rm)));
        double k = Math.sqrt(Math.max(0, (-sep + rs + rm) * (sep + rs - rm) * (sep - rs + rm) * (sep + rs + rm)));
        return (rs * rs * c1 + rm * rm * c2 - 0.5 * k) / (Math.PI * rs * rs);
    }

    /*
     * Sunlight, not area: the Sun is limb-darkened, I(μ) = 1 − u(1 − μ) with u = 0.6 (visual),
     * so covering its bright centre takes more light than covering an equal area of limb.
     * LIGHT[p][s] tabulates the fraction of the disc's light still arriving for a Moon of
     * radius p and centre offset s (both in solar radii), integrated over thin annuli of the
     * solar disc, each covered along an arc of half-angle acos((r² + s² − p²)/(2rs)).
     */
    private static final double LIMB_U = 0.6;
    private static final double P_MIN = 0.85, P_MAX = 1.15, S_MAX = 2.2;
    private static final int NP = 31, NS = 241;
    private static float[] light;

    private static synchronized float[] lightTable() {
        if (light != null) return light;
        float[] t = new float[NP * NS];
        int nr = 400;
        double norm = 1 - LIMB_U / 3;
        for (int ip = 0; ip < NP; ip++) {
            double p = P_MIN + (P_MAX - P_MIN) * ip / (NP - 1);
            for (int is = 0; is < NS; is++) {
                double s = S_MAX * is / (NS - 1), blocked = 0;
                for (int k = 0; k < nr; k++) {
                    double r = (k + 0.5) / nr, mu = Math.sqrt(1 - r * r);
                    double phi;                                        // covered half-angle of the annulus
                    if (r <= p - s) phi = Math.PI;
                    else if (r >= s + p || r <= s - p) phi = 0;
                    else phi = Math.acos(clamp((r * r + s * s - p * p) / (2 * r * s)));
                    blocked += (1 - LIMB_U * (1 - mu)) * 2 * r * (phi / Math.PI) / nr;
                }
                t[ip * NS + is] = (float) Math.max(0, 1 - blocked / norm);
            }
        }
        return light = t;
    }

    /** Fraction of the (limb-darkened) Sun's light reaching P: 1 outside the penumbra. */
    static double sunlight(Geometry g, double[] p) {
        double[] v = seen(g, p, K_PENUMBRA);
        return light(v[0], v[1], v[2]);
    }

    /**
     * {@link #sunlight} for a per-pixel renderer: P on the ellipsoid at geodetic latitude and
     * longitude (radians), no allocation, and a cheap rejection by distance from the shadow
     * axis first. {@code axis} is {@link Geometry#axis()} and {@code sunMoonKm} = |S − M|,
     * both computed once per frame.
     */
    static double sunlightAt(Geometry g, double[] axis, double sunMoonKm, double lat, double lon) {
        double sp = Math.sin(lat), cp = Math.cos(lat);
        double n = A_KM / Math.sqrt(1 - E2 * sp * sp);
        double px = n * cp * Math.cos(lon), py = n * cp * Math.sin(lon), pz = n * (1 - E2) * sp;
        double[] m = g.moon, s = g.sun;
        double dx = px - m[0], dy = py - m[1], dz = pz - m[2];
        double along = dx * axis[0] + dy * axis[1] + dz * axis[2];
        if (along <= 0) return 1;
        double rp = K_PENUMBRA * A_KM;
        double r = (rp + along * (SUN_KM + rp) / sunMoonKm) * 1.02;
        if (dx * dx + dy * dy + dz * dz - along * along > r * r) return 1;
        double sx = s[0] - px, sy = s[1] - py, sz = s[2] - pz;     // P → Sun
        double mx = -dx, my = -dy, mz = -dz;                       // P → Moon
        double cx = sy * mz - sz * my, cy = sz * mx - sx * mz, cz = sx * my - sy * mx;
        double sep = Math.atan2(Math.sqrt(cx * cx + cy * cy + cz * cz), sx * mx + sy * my + sz * mz);
        double rs = Math.asin(SUN_KM / Math.sqrt(sx * sx + sy * sy + sz * sz));
        double rm = Math.asin(rp / Math.sqrt(mx * mx + my * my + mz * mz));
        return light(sep, rs, rm);
    }

    /** Light fraction for separation σ and radii r☉, r☾ (rad), from the limb-darkened table. */
    private static double light(double sep, double rs, double rm) {
        if (sep >= rs + rm) return 1;
        double pr = (rm / rs - P_MIN) / (P_MAX - P_MIN) * (NP - 1), sr = sep / rs / S_MAX * (NS - 1);
        pr = Math.max(0, Math.min(NP - 1.001, pr));
        sr = Math.max(0, Math.min(NS - 1.001, sr));
        int i = (int) pr, j = (int) sr;
        double fi = pr - i, fj = sr - j;
        float[] t = lightTable();
        return (t[i * NS + j] * (1 - fj) + t[i * NS + j + 1] * fj) * (1 - fi)
             + (t[(i + 1) * NS + j] * (1 - fj) + t[(i + 1) * NS + j + 1] * fj) * fi;
    }

    /** True if P (km) can lie anywhere in the penumbra: cheap distance-to-axis rejection. */
    static boolean nearShadow(Geometry g, double[] axis, double[] p) {
        double[] d = sub(p, g.moon);
        double along = dot(d, axis);
        if (along <= 0) return false;
        double[] c = cross(d, axis);
        // The penumbra widens by (R☉ + R☾)/|S−M| per km beyond the Moon; add a 2% margin.
        double r = (K_PENUMBRA * A_KM + along * (SUN_KM + K_PENUMBRA * A_KM) / norm(sub(g.moon, g.sun))) * 1.02;
        return dot(c, c) < r * r;
    }

    // ------------------------------------------------------------------ the whole eclipse

    enum Type { TOTAL, ANNULAR, HYBRID, PARTIAL }

    /** One instant of the path: central-line point and the two limits (any may be null). */
    record Sample(double t, double[] centre, double[] north, double[] south, double durationSec) {}

    /**
     * A solar eclipse as a whole: its greatest instant and point, its type, the central path
     * (every {@code step}) with its limits, and the northern/southern limits of the partial
     * phase. Points are geodetic {lat, lon} (deg).
     */
    record Path(double tGreatest, double[] greatest, double gamma, Type type,
                double tFirst, double tLast, List<Sample> central,
                List<double[]> partialNorth, List<double[]> partialSouth) {
        /** Earliest and latest instants the penumbra touches the Earth. */
        double start() { return tFirst; }
        double end() { return tLast; }
    }

    /** Distance (Earth radii) of the shadow axis from the Earth's centre: Besselian γ. */
    static double gamma(Geometry g) {
        double[] a = g.axis();
        return norm(cross(g.moon, a)) / A_KM;
    }

    /** The eclipse around a new moon at {@code tNear} (e.g. from the event finder), or null. */
    static Path compute(double tNear) {
        // Greatest eclipse: the instant the shadow axis passes closest to the Earth's centre.
        double lo = tNear - 0.25 * DAY, hi = tNear + 0.25 * DAY;
        double gr = (Math.sqrt(5) - 1) / 2;
        double x1 = hi - gr * (hi - lo), x2 = lo + gr * (hi - lo);
        double f1 = gamma(at(x1)), f2 = gamma(at(x2));
        while (hi - lo > 0.5) {
            if (f1 < f2) { hi = x2; x2 = x1; f2 = f1; x1 = hi - gr * (hi - lo); f1 = gamma(at(x1)); }
            else         { lo = x1; x1 = x2; f1 = f2; x2 = lo + gr * (hi - lo); f2 = gamma(at(x2)); }
        }
        double tg = 0.5 * (lo + hi);
        Geometry gg = at(tg);
        double gam = gamma(gg);
        // Penumbral reach: no eclipse if the penumbra misses the Earth.
        double[] a = gg.axis();
        double distSM = norm(sub(gg.moon, gg.sun));
        double depth = dot(sub(new double[3], gg.moon), a);                 // Moon → Earth's centre along the axis
        double penR = K_PENUMBRA * A_KM + depth * (SUN_KM + K_PENUMBRA * A_KM) / distSM;
        if (gam * A_KM > penR + A_KM) return null;

        double[] gp = intersect(gg.moon, a);
        double[] greatest = gp != null ? geodetic(gp) : geodetic(closestSurfacePoint(gg));

        // The span of the partial phase on Earth: penumbra touching the globe.
        double tFirst = edgeTime(tg, -1), tLast = edgeTime(tg, +1);

        // The central path: sample while the axis meets the Earth.
        List<Sample> central = new ArrayList<>();
        boolean sawTotal = false, sawAnnular = false;
        double step = 60;
        for (double t = tg - Math.floor((tg - tFirst) / step) * step; t <= tLast; t += step) {
            Geometry g = at(t);
            double[] c = intersect(g.moon, g.axis());
            if (c == null) continue;
            double[] v = seen(g, c, K_UMBRA);
            if (v[2] > v[1]) sawTotal = true; else sawAnnular = true;
            double[][] lim = limits(t, g, true);
            central.add(new Sample(t, geodetic(c), lim[0] == null ? null : geodetic(lim[0]),
                    lim[1] == null ? null : geodetic(lim[1]), centralDuration(c, t)));
        }
        Type type = central.isEmpty() ? Type.PARTIAL : sawTotal && sawAnnular ? Type.HYBRID : sawTotal ? Type.TOTAL : Type.ANNULAR;

        List<double[]> pn = new ArrayList<>(), ps = new ArrayList<>();
        for (double t = tFirst; t <= tLast; t += 120) {
            double[][] lim = limits(t, at(t), false);
            if (lim[0] != null) pn.add(geodetic(lim[0]));
            if (lim[1] != null) ps.add(geodetic(lim[1]));
        }
        return new Path(tg, greatest, gam, type, tFirst, tLast, central, pn, ps);
    }

    /** Surface point nearest the shadow axis (for a non-central eclipse's greatest point). */
    private static double[] closestSurfacePoint(Geometry g) {
        double[] a = g.axis();
        double s = -dot(g.moon, a);
        double[] q = {g.moon[0] + s * a[0], g.moon[1] + s * a[1], g.moon[2] + s * a[2]};   // axis point nearest the centre
        double[] u = unit(q);
        return intersect(new double[]{u[0] * 2 * A_KM, u[1] * 2 * A_KM, u[2] * 2 * A_KM}, new double[]{-u[0], -u[1], -u[2]});
    }

    /** First (dir −1) or last (+1) instant the penumbra touches the Earth, from greatest eclipse. */
    private static double edgeTime(double tg, int dir) {
        double t0 = tg, t1 = tg;
        for (int k = 1; k <= 60; k++) {                  // ≤ 5 h either way, in 5-min steps
            t1 = tg + dir * k * 300;
            if (!penumbraTouches(at(t1))) break;
            t0 = t1;
        }
        for (int it = 0; it < 30; it++) {
            double tm = 0.5 * (t0 + t1);
            if (penumbraTouches(at(tm))) t0 = tm; else t1 = tm;
        }
        return t0;
    }

    private static boolean penumbraTouches(Geometry g) {
        double[] a = g.axis();
        double depth = -dot(g.moon, a);
        double penR = K_PENUMBRA * A_KM + depth * (SUN_KM + K_PENUMBRA * A_KM) / norm(sub(g.moon, g.sun));
        return gamma(g) * A_KM < penR + A_KM * (1 - FLAT / 2);
    }

    // ------------------------------------------------------------------ limits (the envelope)

    /**
     * The two limit points of the umbral ({@code central}) or penumbral path at instant t:
     * points on the shadow's footprint outline where it has stopped advancing, ∂g/∂t = 0.
     * Returns {north, south}, by side of the track (either may be null where the outline
     * runs off the Earth's limb).
     */
    static double[][] limits(double t, Geometry g, boolean central) {
        double dt = 5;
        Geometry gm = at(t - dt), gp = at(t + dt);
        double[] a = g.axis();
        double[] e1 = unit(cross(a, Math.abs(a[2]) < 0.9 ? new double[]{0, 0, 1} : new double[]{1, 0, 0}));
        double[] e2 = cross(a, e1);
        int n = 72;
        double[][] pts = new double[n][];
        double[] rate = new double[n];
        for (int i = 0; i < n; i++) {
            pts[i] = outline(g, a, e1, e2, 2 * Math.PI * i / n, central);
            if (pts[i] != null) rate[i] = margin(gp, pts[i], central) - margin(gm, pts[i], central);
        }
        List<double[]> found = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            if (pts[i] == null || pts[j] == null || (rate[i] > 0) == (rate[j] > 0)) continue;
            // Refine the root of the rate in θ by bisection along the outline.
            double ta = 2 * Math.PI * i / n, tb = 2 * Math.PI * (i + 1) / n, ra = rate[i];
            double[] best = pts[i];
            for (int it = 0; it < 20; it++) {
                double tm = 0.5 * (ta + tb);
                double[] pm = outline(g, a, e1, e2, tm, central);
                if (pm == null) break;
                best = pm;
                double rm = margin(gp, pm, central) - margin(gm, pm, central);
                if ((rm > 0) == (ra > 0)) { ta = tm; ra = rm; } else tb = tm;
            }
            found.add(best);
        }
        // Which side of the track each limit is on: across the shadow's motion (relative to the
        // ground) in the fundamental plane, with "north" the side toward the Earth's north pole.
        double[] v = sub(gp.moon, gm.moon);
        double[] side = unit(cross(a, sub(v, scale(a, dot(v, a)))));
        if (side[2] < 0) side = scale(side, -1);
        double[] north = null, south = null;
        double bestN = 0, bestS = 0;
        for (double[] p : found) {
            double off = dot(sub(p, g.moon), side);
            if (off > bestN) { bestN = off; north = p; }
            if (off < bestS) { bestS = off; south = p; }
        }
        return new double[][]{north, south};
    }

    /**
     * The umbra's (or antumbra's) footprint on the ground at this instant, as geodetic
     * {lat, lon} points around its outline; empty when the shadow axis misses the Earth.
     */
    static List<double[]> footprint(Geometry g) {
        List<double[]> out = new ArrayList<>();
        double[] a = g.axis();
        if (intersect(g.moon, a) == null) return out;
        double[] e1 = unit(cross(a, Math.abs(a[2]) < 0.9 ? new double[]{0, 0, 1} : new double[]{1, 0, 0}));
        double[] e2 = cross(a, e1);
        for (int i = 0; i < 48; i++) {
            double[] p = outline(g, a, e1, e2, 2 * Math.PI * i / 48, true);
            if (p != null) out.add(geodetic(p));
        }
        return out;
    }

    private static double margin(Geometry g, double[] p, boolean central) {
        return central ? centralMargin(g, p) : partialMargin(g, p);
    }

    /**
     * Where the footprint outline crosses the half-plane at angle θ about the axis: march the
     * ray (parallel to the axis, offset ρ in direction θ) outward until the margin turns
     * negative, then bisect in ρ. Null if the ray leaves the Earth first (the limb).
     */
    private static double[] outline(Geometry g, double[] a, double[] e1, double[] e2, double th, boolean central) {
        double cx = Math.cos(th), sx = Math.sin(th);
        double[] dir = {cx * e1[0] + sx * e2[0], cx * e1[1] + sx * e2[1], cx * e1[2] + sx * e2[2]};
        double stepKm = central ? 25 : 200, maxKm = central ? 3000 : 2 * A_KM + 8000;
        // Rays start on the shadow axis, well sunward of the Earth (base lies on the axis).
        double s0 = -dot(g.moon, a) - 3 * A_KM;
        double[] base = {g.moon[0] + s0 * a[0], g.moon[1] + s0 * a[1], g.moon[2] + s0 * a[2]};
        // March outward: (possibly) off the Earth, then inside the shadow, then the edge.
        double r0 = Double.NaN, r1 = Double.NaN;
        for (double r = 0; r <= maxKm; r += stepKm) {
            double[] h = ray(base, dir, r, a);
            if (h == null) {
                if (!Double.isNaN(r0)) return null;      // ran off the limb while still inside
                continue;                                // not on the Earth yet (non-central)
            }
            if (margin(g, h, central) > 0) { r0 = r; continue; }
            if (Double.isNaN(r0)) return null;           // met the Earth outside the shadow
            r1 = r;
            break;
        }
        if (Double.isNaN(r1)) return null;
        for (int it = 0; it < 40; it++) {
            double rm = 0.5 * (r0 + r1);
            double[] h = ray(base, dir, rm, a);
            if (h != null && margin(g, h, central) > 0) r0 = rm; else r1 = rm;
        }
        return ray(base, dir, r0, a);
    }

    private static double[] ray(double[] base, double[] dir, double rho, double[] a) {
        return intersect(new double[]{base[0] + rho * dir[0], base[1] + rho * dir[1], base[2] + rho * dir[2]}, a);
    }

    // ------------------------------------------------------------------ at one place

    /** Seconds the central phase lasts at P (km) around instant {@code tNear}; 0 if none. */
    static double centralDuration(double[] p, double tNear) {
        if (centralMargin(at(tNear), p) <= 0) return 0;
        return root(p, tNear, +1, true) - root(p, tNear, -1, true);
    }

    /** Edge of the central (or partial) phase at P, searching from {@code tIn} (inside) in {@code dir}. */
    private static double root(double[] p, double tIn, int dir, boolean central) {
        double step = central ? 30 : 600, t0 = tIn, t1 = tIn;
        for (int k = 1; k < 100; k++) {
            t1 = tIn + dir * k * step;
            if (margin(at(t1), p, central) <= 0) break;
            t0 = t1;
        }
        for (int it = 0; it < 18; it++) {               // to ~0.1 ms (central) / 2 ms (partial)
            double tm = 0.5 * (t0 + t1);
            if (margin(at(tm), p, central) > 0) t0 = tm; else t1 = tm;
        }
        return 0.5 * (t0 + t1);
    }

    /**
     * Local circumstances at a place: {C1, C2, tMax, C3, C4, max obscuration, Sun altitude at
     * max (deg)}; C2/C3 are NaN without a central phase, and everything but the altitude NaN
     * if the place sees no eclipse. Times are UTC sim seconds.
     */
    static double[] local(Path path, double latDeg, double lonDeg) {
        double[] p = surface(latDeg, lonDeg);
        // Maximum: minimise σ over the eclipse span (coarse scan, then golden section).
        double best = Double.NaN, bestSep = Double.MAX_VALUE;
        for (double t = path.tFirst(); t <= path.tLast(); t += 120) {
            double s = seen(at(t), p, K_PENUMBRA)[0];
            if (s < bestSep) { bestSep = s; best = t; }
        }
        double lo = best - 120, hi = best + 120, gr = (Math.sqrt(5) - 1) / 2;
        for (int it = 0; it < 30; it++) {
            double x1 = hi - gr * (hi - lo), x2 = lo + gr * (hi - lo);
            if (seen(at(x1), p, K_PENUMBRA)[0] < seen(at(x2), p, K_PENUMBRA)[0]) hi = x2; else lo = x1;
        }
        double tm = 0.5 * (lo + hi);
        Geometry g = at(tm);
        double[] v = seen(g, p, K_PENUMBRA);
        double[] up = unit(new double[]{p[0], p[1], p[2] / (1 - E2)});   // ellipsoid normal
        double alt = Math.toDegrees(Math.asin(clamp(dot(unit(sub(g.sun, p)), up))));
        double nan = Double.NaN;
        if (partialMargin(g, p) <= 0) return new double[]{nan, nan, nan, nan, nan, nan, alt};
        double c1 = root(p, tm, -1, false), c4 = root(p, tm, +1, false);
        double c2 = nan, c3 = nan;
        if (centralMargin(g, p) > 0) { c2 = root(p, tm, -1, true); c3 = root(p, tm, +1, true); }
        return new double[]{c1, c2, tm, c3, c4, obscuration(v[0], v[1], v[2]), alt};
    }

    // ------------------------------------------------------------------ vectors

    static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    static double norm(double[] a) { return Math.sqrt(dot(a, a)); }
    static double[] sub(double[] a, double[] b) { return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
    static double[] unit(double[] a) { double n = norm(a); return new double[]{a[0] / n, a[1] / n, a[2] / n}; }
    static double[] scale(double[] a, double k) { return new double[]{a[0] * k, a[1] * k, a[2] * k}; }
    static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }
    private static double clamp(double x) { return Math.max(-1, Math.min(1, x)); }
}
