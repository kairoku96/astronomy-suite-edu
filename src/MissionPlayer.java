import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.*;
import java.util.List;

/**
 * Plays spacecraft missions inside the solar-system view: trajectory trails, craft markers, an
 * in-canvas mission panel with a clickable timeline, an auto camera that frames each phase,
 * and automatic pacing of the clock.
 *
 * <p><b>Where a craft is drawn.</b> Its current position is the sim's own position of the
 * track's centre body (Sun, Earth, Moon, a planet) plus the interpolated offset, so a flyby
 * hugs the rendered planet and a lunar orbit hugs the rendered Moon. Trails are drawn in one
 * consistent frame: heliocentric for deep-space probes (or planet-centred while the camera is
 * close to the planet the craft is visiting), and for lunar flights either the inertial
 * Earth-centred frame or the Earth–Moon <em>rotating</em> frame — the one the classic
 * figure-eight free-return diagrams are drawn in (each past point is turned and scaled with
 * the Moon's motion since, so the trail stays attached to the Moon as it is now).
 *
 * <p><b>Pacing.</b> In auto pace the time rate is set so the followed craft crosses the view in
 * about twelve seconds of real time — slow for a landing, fast for the coast — from its
 * speed relative to the nearest body and the camera's view radius.
 */
final class MissionPlayer {

    /** What the player needs from the simulation. */
    interface Host {
        double simTime();
        void jumpTo(double simSec);
        double timeScale();
        void setTimeScale(double secondsPerSecond);
        boolean paused();
        void setPaused(boolean p);
        /** Current world position (m) of a body: "Sun", "Earth", "Moon" or a planet name. */
        double[] centreWorld(String body);
        /** World point → screen {x, y}; false when behind the camera. */
        boolean project(double wx, double wy, double wz, double[] out);
        int width();
        int height();
        /** Put the camera on a world point with the given view radius (m). */
        void aimCamera(double wx, double wy, double wz, double viewRadiusM, double dtReal);
        /** Make sure the bodies a mission needs are drawn. */
        void ensureVisible(String... bodies);
        void missionEnded();
    }

    private final Host host;
    private Missions.Mission active;
    private int focusCraft = -1;                 // -1 = automatic
    private boolean autoCamera = true, autoPace = true, rotatingFrame = true;
    private double paceMul = 1.0;
    private double lastSetScale = Double.NaN;
    private boolean showAllInFlight = true;
    private double viewRadiusM = 4e8;
    private final Map<Missions.Craft, Prepared> prepared = new HashMap<>();
    private final List<Object[]> hits = new ArrayList<>();    // {Rectangle, Runnable}
    private int timelineScroll = 0;

    MissionPlayer(Host host) { this.host = host; }

    boolean isActive() { return active != null; }
    Missions.Mission active() { return active; }
    void setShowAllInFlight(boolean b) { showAllInFlight = b; }
    boolean showAllInFlight() { return showAllInFlight; }

    // ------------------------------------------------------------------ start / stop

    void start(Missions.Mission m) {
        active = m;
        focusCraft = -1;
        autoCamera = autoPace = true;
        camReleased = false;
        paceMul = 1;
        rotatingFrame = m.kind == Missions.Kind.LUNAR;
        timelineScroll = 0;
        for (Missions.Craft c : m.craft) prepare(c);
        if (m.kind == Missions.Kind.LUNAR) host.ensureVisible("Earth");
        double[] span = m.span();
        double t0 = Math.max(m.launch, span[0]);
        if (!Double.isFinite(t0)) t0 = m.launch;
        host.jumpTo(t0 + 1);
        host.setPaused(false);
        lastSetScale = Double.NaN;
        viewRadiusM = m.kind == Missions.Kind.LUNAR ? 3.0e7 : 3.0e11;
    }

    void stop() {
        active = null;
        hits.clear();
        host.missionEnded();
    }

    /** The user took the camera (drag or scroll): keep following, stop auto-framing. */
    void userTookCamera() { if (active != null) autoCamera = false; }

    /** The user picked something else to look at (a planet): stop steering until asked again. */
    void releaseCamera() { camReleased = true; }

    private boolean camReleased;

    /** True while the player is steering the camera this frame. */
    boolean controlsCamera() { return active != null && !camReleased && currentCraftPos() != null; }

    // ------------------------------------------------------------------ per frame

    /** Advance pacing and the camera; called once per frame before painting. */
    void tick(double dtReal) {
        if (active == null) return;
        double now = host.simTime();
        double[] span = active.span();
        if (now > span[1] && !host.paused()) host.setPaused(true);     // mission complete
        Missions.Craft c = followed();
        double[] p = c == null ? null : craftWorld(c, now);
        if (p == null) return;
        double[] rel = nearestRelative(c, now);       // {distance m, speed m/s, bodyRadius m}
        double want = autoRadius(c, now, rel);
        if (autoCamera) {
            double k = 1 - Math.exp(-2.5 * dtReal);
            viewRadiusM = Math.exp(Math.log(viewRadiusM) + (Math.log(want) - Math.log(viewRadiusM)) * k);
        }
        if (!camReleased) host.aimCamera(p[0], p[1], p[2], autoCamera ? viewRadiusM : -1, dtReal);
        if (autoPace) {
            if (Double.isFinite(lastSetScale) && Math.abs(host.timeScale() - lastSetScale) > 1e-6 * lastSetScale) {
                autoPace = false;          // the user set the speed themselves
            } else {
                boolean lunar = active.kind == Missions.Kind.LUNAR;
                double lo = lunar ? 15 : 3600, hi = lunar ? 6 * 3600 : 120 * 86400.0;
                double v = Math.max(rel[1], 1.0);
                double ts = 2 * viewRadiusM / (v * 12.0) * paceMul;
                ts = Math.max(lo * paceMul, Math.min(hi * paceMul, ts));
                if (!Double.isFinite(lastSetScale) || Math.abs(ts / lastSetScale - 1) > 0.03) {
                    host.setTimeScale(ts);
                    lastSetScale = host.timeScale();
                }
            }
        }
    }

    /** The craft the camera follows: the user's pick, else the lander while it is flying alone. */
    Missions.Craft followed() {
        if (active == null) return null;
        double now = host.simTime();
        if (focusCraft >= 0 && focusCraft < active.craft.size() && craftWorld(active.craft.get(focusCraft), now) != null)
            return active.craft.get(focusCraft);
        for (int i = active.craft.size() - 1; i >= 0; i--)
            if (craftWorld(active.craft.get(i), now) != null) return active.craft.get(i);
        return null;
    }

    private double[] currentCraftPos() {
        Missions.Craft c = followed();
        return c == null ? null : craftWorld(c, host.simTime());
    }

    /** View radius the auto camera wants: a phase hint from the timeline, else a distance-based framing. */
    private double autoRadius(Missions.Craft c, double now, double[] rel) {
        for (Missions.Event e : active.timeline()) {
            if (e.viewKm <= 0 || e.sim > now) continue;
            double hold = e.viewKm < 2000 ? 40 * 60 : (active.kind == Missions.Kind.LUNAR ? 0 : 5 * 86400);
            if (now - e.sim < hold) return e.viewKm * 1000;
        }
        double d = rel[0], R = rel[2];
        if (active.kind == Missions.Kind.LUNAR) return Math.max(1.35 * d, 3.2 * R);
        // Deep space: near a planet frame the encounter, otherwise the craft's place in the Solar System.
        if (rel[3] > 0) return Math.max(2.5 * d, 8 * R);
        double[] sun = host.centreWorld("Sun"), p = craftWorld(c, now);
        double rs = Math.sqrt(sq(p[0] - sun[0]) + sq(p[1] - sun[1]) + sq(p[2] - sun[2]));
        return Math.max(0.65 * rs, 0.6 * Astro.AU_M);
    }

    /**
     * {distance (m), speed (m/s), radius (m), isPlanet} of the craft relative to its nearest
     * relevant body: the Earth or the Moon on a lunar flight; on a deep-space flight the centre
     * of the local segment it is in (or the Sun).
     */
    private double[] nearestRelative(Missions.Craft c, double now) {
        SpacecraftTrack t = c.track();
        double tdb = Astro.ttSeconds(now);
        double[] st = new double[6];
        if (active.kind == Missions.Kind.LUNAR) {
            SpacecraftTrack.Segment g = t.segmentAt(tdb);
            g.stateAt(tdb, st);
            double[] geo = st.clone();
            double[] m = ApolloReconstruction.moonState(tdb);
            if (g.centre.equals("Moon")) for (int i = 0; i < 6; i++) geo[i] += m[i];
            double rE = Math.sqrt(sq(geo[0]) + sq(geo[1]) + sq(geo[2]));
            double rM = Math.sqrt(sq(geo[0] - m[0]) + sq(geo[1] - m[1]) + sq(geo[2] - m[2]));
            if (rM < rE * 0.35) {
                double v = Math.sqrt(sq(geo[3] - m[3]) + sq(geo[4] - m[4]) + sq(geo[5] - m[5]));
                return new double[]{rM * 1000, v * 1000, 1737.4e3, 1};
            }
            double v = Math.sqrt(sq(geo[3]) + sq(geo[4]) + sq(geo[5]));
            return new double[]{rE * 1000, v * 1000, 6378.137e3, 1};
        }
        SpacecraftTrack.Segment g = t.segmentAt(tdb);
        g.stateAt(tdb, st);
        double r = Math.sqrt(sq(st[0]) + sq(st[1]) + sq(st[2])), v = Math.sqrt(sq(st[3]) + sq(st[4]) + sq(st[5]));
        boolean planet = !g.centre.equals("Sun");
        return new double[]{r * 1000, v * 1000, Missions.centreRadiusKm(g.centre) * 1000, planet ? 1 : 0};
    }

    // ------------------------------------------------------------------ positions

    /** Current world position (m) of a craft at UTC sim time {@code now}, or null outside its data. */
    double[] craftWorld(Missions.Craft c, double now) {
        SpacecraftTrack t = c.track();
        if (t == null) return null;
        double tdb = Astro.ttSeconds(now);
        SpacecraftTrack.Segment g = t.segmentAt(tdb);
        if (g == null) return null;
        double[] st = new double[6];
        g.stateAt(tdb, st);
        double[] base = host.centreWorld(g.centre);
        if (base == null) return null;
        double[] w = SolarSystemSimulation.eclToWorld(st[0] * 1000, st[1] * 1000, st[2] * 1000);
        return new double[]{base[0] + w[0], base[1] + w[1], base[2] + w[2]};
    }

    // ------------------------------------------------------------------ prepared trails

    /** A craft's trail, densified once: base-frame points and (lunar) rotating-frame coordinates. */
    private static final class Prepared {
        double[] t;          // TDB
        double[] p;          // base frame (heliocentric or geocentric), km
        double[] rot;        // Earth–Moon rotating coordinates (lunar only), units of the Earth–Moon distance
        final List<LocalPath> local = new ArrayList<>();
    }

    /** A planet-centred stretch (deep space), drawn around the planet when the camera is there. */
    private static final class LocalPath {
        String centre; double t0, t1; double[] t, p;
    }

    private static double gmOf(String centre) {
        return switch (centre) {
            case "Sun" -> 1.32712440041e11; case "Earth" -> 398600.4418; case "Moon" -> 4902.8;
            case "Venus" -> 324858.6; case "Mars" -> 42828.4; case "Jupiter" -> 1.26686534e8;
            case "Saturn" -> 3.7931187e7; case "Uranus" -> 5.793939e6; case "Neptune" -> 6.836529e6;
            case "Pluto" -> 869.6; default -> 1;
        };
    }

    private static int planetIndex(String name) {
        return switch (name) {
            case "Mercury" -> 0; case "Venus" -> 1; case "Earth" -> 2; case "Mars" -> 3; case "Jupiter" -> 4;
            case "Saturn" -> 5; case "Uranus" -> 6; case "Neptune" -> 7; case "Pluto" -> 8; default -> -1;
        };
    }

    /** State (km, km/s) of a local centre relative to the base frame's origin at TDB t. */
    private static double[] centreInBase(String centre, boolean lunar, double tdb) {
        if (lunar) return centre.equals("Moon") ? ApolloReconstruction.moonState(tdb) : new double[6];
        if (centre.equals("Sun")) return new double[6];
        int i = planetIndex(centre);
        double[] s = SolarSystemSimulation.planetStateAU(i, Missions.utcOf(tdb));
        double au = Astro.AU_M / 1000;
        return new double[]{s[0] * au, s[1] * au, s[2] * au, s[3] * au / 86400, s[4] * au / 86400, s[5] * au / 86400};
    }

    private Prepared prepare(Missions.Craft c) {
        Prepared P = prepared.get(c);
        if (P != null) return P;
        SpacecraftTrack tr = c.track();
        P = new Prepared();
        prepared.put(c, P);
        if (tr == null) { P.t = new double[0]; P.p = new double[0]; return P; }
        boolean lunar = tr.base().centre.equals("Earth");
        TreeMap<Double, double[]> pts = new TreeMap<>();
        double[] st = new double[6];
        for (int si = 0; si < tr.segments.size(); si++) {
            SpacecraftTrack.Segment g = tr.segments.get(si);
            double gm = gmOf(g.centre);
            LocalPath lp = null;
            List<Double> lt = new ArrayList<>();
            List<double[]> lpPts = new ArrayList<>();
            if (si > 0 && !lunar) { lp = new LocalPath(); lp.centre = g.centre; lp.t0 = g.start(); lp.t1 = g.end(); }
            // Centre states at the sample times, Hermite-interpolated between them for dense points.
            double[] cs = new double[6 * g.size()];
            for (int k = 0; k < g.size(); k++) System.arraycopy(centreInBase(g.centre, lunar, g.t[k]), 0, cs, 6 * k, 6);
            for (int k = 0; k + 1 < g.size(); k++) {
                double ta = g.t[k], tb = g.t[k + 1];
                if (si == 0 && coveredByLocal(tr, 0.5 * (ta + tb))) continue;
                double r = Math.sqrt(sq(g.s[6 * k]) + sq(g.s[6 * k + 1]) + sq(g.s[6 * k + 2]));
                double tdyn = Math.sqrt(r * r * r / gm);
                double maxDt = Math.max(20, Math.min(20 * 86400, 0.08 * tdyn));
                int n = Math.max(1, Math.min(400, (int) Math.ceil((tb - ta) / maxDt)));
                double[] cst = new double[6];
                for (int j = 0; j < n; j++) {
                    double t = ta + (tb - ta) * j / n;
                    g.stateAt(t, st);
                    SpacecraftTrack.hermite(ta, cs, 6 * k, tb, cs, 6 * (k + 1), t, cst);
                    pts.put(t, new double[]{st[0] + cst[0], st[1] + cst[1], st[2] + cst[2]});
                    if (lp != null) { lt.add(t); lpPts.add(new double[]{st[0], st[1], st[2]}); }
                }
            }
            int last = g.size() - 1;
            double[] cl = centreInBase(g.centre, lunar, g.t[last]);
            if (si == 0 || !coveredLater(tr, si))
                pts.put(g.t[last], new double[]{g.s[6 * last] + cl[0], g.s[6 * last + 1] + cl[1], g.s[6 * last + 2] + cl[2]});
            if (lp != null) {
                lt.add(g.t[last]);
                lpPts.add(new double[]{g.s[6 * last], g.s[6 * last + 1], g.s[6 * last + 2]});
                lp.t = new double[lt.size()];
                lp.p = new double[3 * lt.size()];
                for (int k = 0; k < lt.size(); k++) {
                    lp.t[k] = lt.get(k);
                    System.arraycopy(lpPts.get(k), 0, lp.p, 3 * k, 3);
                }
                P.local.add(lp);
            }
        }
        int n = pts.size();
        P.t = new double[n];
        P.p = new double[3 * n];
        int k = 0;
        for (Map.Entry<Double, double[]> e : pts.entrySet()) {
            P.t[k] = e.getKey();
            System.arraycopy(e.getValue(), 0, P.p, 3 * k, 3);
            k++;
        }
        if (lunar) {
            P.rot = new double[3 * n];
            for (int i = 0; i < n; i++) {
                double[][] B = moonFrame(P.t[i]);
                double[] q = {P.p[3 * i], P.p[3 * i + 1], P.p[3 * i + 2]};
                for (int a = 0; a < 3; a++) P.rot[3 * i + a] = (q[0] * B[a][0] + q[1] * B[a][1] + q[2] * B[a][2]) / B[3][0];
            }
        }
        return P;
    }

    private static boolean coveredByLocal(SpacecraftTrack tr, double t) {
        for (int i = 1; i < tr.segments.size(); i++) if (tr.segments.get(i).covers(t)) return true;
        return false;
    }

    private static boolean coveredLater(SpacecraftTrack tr, int si) { return false; }

    /** Earth–Moon rotating basis at TDB t: rows x̂ (to the Moon), ŷ, ẑ (orbit normal), and {|m|}. */
    private static double[][] moonFrame(double t) {
        double[] m = ApolloReconstruction.moonState(t);
        double[] r = {m[0], m[1], m[2]}, v = {m[3], m[4], m[5]};
        double rn = Lambert.norm(r);
        double[] x = {r[0] / rn, r[1] / rn, r[2] / rn};
        double[] z = Lambert.cross(r, v);
        double zn = Lambert.norm(z);
        z = new double[]{z[0] / zn, z[1] / zn, z[2] / zn};
        double[] y = Lambert.cross(z, x);
        return new double[][]{x, y, z, {rn}};
    }

    // ------------------------------------------------------------------ painting

    /** Trails and markers for the active mission (and markers for any other craft in flight). */
    void paintScene(Graphics2D g2) {
        double now = host.simTime();
        Object aa = g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (active != null) {
            for (Missions.Craft c : active.craft) drawTrail(g2, c, now);
            for (Missions.Craft c : active.craft) drawMarker(g2, c, now, c == followed());
        }
        if (showAllInFlight) {
            for (Missions.Mission m : Missions.all()) {
                if (m == active) continue;
                double[] sp = m.span();
                if (now < sp[0] || now > sp[1]) continue;
                for (Missions.Craft c : m.craft) drawMarker(g2, c, now, false);
            }
        }
        if (aa != null) g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
    }

    private void drawTrail(Graphics2D g2, Missions.Craft c, double now) {
        Prepared P = prepare(c);
        if (P.t.length < 2) return;
        double tdb = Astro.ttSeconds(now);
        boolean lunar = P.rot != null;
        double[] out = new double[2];

        // Deep space, zoomed in on the planet the craft is visiting: draw that stretch planet-centred.
        if (!lunar) {
            for (LocalPath lp : P.local) {
                if (tdb < lp.t0 || tdb > lp.t1 || viewRadiusEffective() > 2.0e10) continue;
                double[] cw = host.centreWorld(lp.centre);
                if (cw == null) continue;
                double win0 = tdb - 45 * 86400, win1 = tdb + 20 * 86400;
                strokeSeries(g2, c.color, lp.t, tdb, win0, win1, i -> {
                    double[] w = SolarSystemSimulation.eclToWorld(lp.p[3 * i] * 1000, lp.p[3 * i + 1] * 1000, lp.p[3 * i + 2] * 1000);
                    return new double[]{cw[0] + w[0], cw[1] + w[1], cw[2] + w[2]};
                }, out);
                return;
            }
        }
        double[] origin = host.centreWorld(lunar ? "Earth" : "Sun");
        if (origin == null) return;
        double[][] Bn = lunar && rotatingFrame ? moonFrame(tdb) : null;
        // Orbit-tour stretches are limited to a window around now so 290 orbits don't knot up.
        double w0 = -Double.MAX_VALUE, w1 = Double.MAX_VALUE;
        if (!lunar && P.t.length > 4000) {
            for (LocalPath lp : P.local)
                if (lp.t.length > 2000 && tdb >= lp.t0 && tdb <= lp.t1) { w0 = tdb - 120 * 86400; w1 = tdb + 60 * 86400; }
        }
        final double[][] B = Bn;
        strokeSeries(g2, c.color, P.t, tdb, w0, w1, i -> {
            double x, y, z;
            if (B != null) {
                double s = B[3][0];
                double a = P.rot[3 * i] * s, b = P.rot[3 * i + 1] * s, cc = P.rot[3 * i + 2] * s;
                x = a * B[0][0] + b * B[1][0] + cc * B[2][0];
                y = a * B[0][1] + b * B[1][1] + cc * B[2][1];
                z = a * B[0][2] + b * B[1][2] + cc * B[2][2];
            } else { x = P.p[3 * i]; y = P.p[3 * i + 1]; z = P.p[3 * i + 2]; }
            double[] w = SolarSystemSimulation.eclToWorld(x * 1000, y * 1000, z * 1000);
            return new double[]{origin[0] + w[0], origin[1] + w[1], origin[2] + w[2]};
        }, out);
    }

    private double viewRadiusEffective() { return viewRadiusM; }

    /** Stroke a time series of world points: the flown part bright, the part still to come faint. */
    private void strokeSeries(Graphics2D g2, Color col, double[] t, double tNow, double w0, double w1,
                              java.util.function.IntFunction<double[]> world, double[] out) {
        Path2D.Double past = new Path2D.Double(), future = new Path2D.Double();
        boolean penP = false, penF = false;
        double lx = Double.NaN, ly = Double.NaN;
        double lim = 20.0 * Math.max(host.width(), host.height());
        for (int i = 0; i < t.length; i++) {
            if (t[i] < w0 || t[i] > w1) { penP = penF = false; continue; }
            double[] w = world.apply(i);
            // Lift the pen behind the camera, and for points so close to the near plane that the
            // perspective divide flings them far off screen (they'd draw as long false chords).
            if (!host.project(w[0], w[1], w[2], out) || Math.abs(out[0]) > lim || Math.abs(out[1]) > lim) {
                penP = penF = false;
                lx = Double.NaN;
                continue;
            }
            boolean isPast = t[i] <= tNow;
            boolean far = Double.isNaN(lx) || Math.abs(out[0] - lx) + Math.abs(out[1] - ly) > 0.8
                    || i == t.length - 1 || (i + 1 < t.length && (t[i] <= tNow) != (t[i + 1] <= tNow));
            if (isPast) {
                if (!penP) { past.moveTo(out[0], out[1]); penP = true; far = true; }
                else if (far) past.lineTo(out[0], out[1]);
            } else {
                if (!penF) {
                    future.moveTo(out[0], out[1]);
                    if (penP) past.lineTo(out[0], out[1]);   // join the two halves
                    penF = true;
                    far = true;
                } else if (far) future.lineTo(out[0], out[1]);
            }
            if (far) { lx = out[0]; ly = out[1]; }
        }
        g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 210));
        g2.draw(past);
        g2.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{5f, 4f}, 0f));
        g2.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 95));
        g2.draw(future);
    }

    private final Map<Missions.Craft, double[]> markerPx = new HashMap<>();

    private void drawMarker(Graphics2D g2, Missions.Craft c, double now, boolean focused) {
        double[] p = craftWorld(c, now);
        markerPx.remove(c);
        if (p == null) return;
        double[] o = new double[2];
        if (!host.project(p[0], p[1], p[2], o)) return;
        markerPx.put(c, o.clone());
        float r = focused ? 5.5f : 4f;
        g2.setColor(new Color(c.color.getRed(), c.color.getGreen(), c.color.getBlue(), 70));
        g2.fill(new Ellipse2D.Double(o[0] - r * 2.2, o[1] - r * 2.2, r * 4.4, r * 4.4));
        Path2D.Double d = new Path2D.Double();
        d.moveTo(o[0], o[1] - r); d.lineTo(o[0] + r, o[1]); d.lineTo(o[0], o[1] + r); d.lineTo(o[0] - r, o[1]); d.closePath();
        g2.setColor(c.color);
        g2.fill(d);
        g2.setColor(Color.WHITE);
        g2.setStroke(new BasicStroke(1f));
        g2.draw(d);
        Font f = g2.getFont();
        g2.setFont(f.deriveFont(focused ? Font.BOLD : Font.PLAIN, 11.5f));
        g2.setColor(new Color(235, 240, 255));
        g2.drawString(c.name, (float) (o[0] + r + 5), (float) (o[1] - r - 2));
        g2.setFont(f);
    }

    /** A click on a craft marker focuses the camera on it. */
    boolean clickCraft(int mx, int my) {
        for (Map.Entry<Missions.Craft, double[]> e : markerPx.entrySet()) {
            double[] o = e.getValue();
            if (Math.hypot(mx - o[0], my - o[1]) < 10) {
                Missions.Mission owner = null;
                for (Missions.Mission m : Missions.all()) if (m.craft.contains(e.getKey())) owner = m;
                if (owner != active) { start(owner); return true; }
                focusCraft = active.craft.indexOf(e.getKey());
                autoCamera = true;
                camReleased = false;
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ panel

    private static final Color PANEL_TOP = new Color(20, 25, 40, 238), PANEL_BOT = new Color(14, 18, 32, 238),
            ACCENT = new Color(120, 180, 255), TEXT = new Color(230, 238, 252), DIM = new Color(150, 165, 195);

    /** The mission panel on the right of the canvas. */
    void paintOverlay(Graphics2D g2) {
        hits.clear();
        if (active == null) return;
        double now = host.simTime();
        int W = host.width(), H = host.height();
        int w = Math.min(350, W / 3 + 40), x0 = W - w - 14, y0 = 14;
        Font base = g2.getFont();
        Font fTitle = base.deriveFont(Font.BOLD, 15f), fSub = base.deriveFont(Font.PLAIN, 11f),
                fBody = base.deriveFont(Font.PLAIN, 11.5f), fBold = base.deriveFont(Font.BOLD, 11.5f),
                fSmall = base.deriveFont(Font.PLAIN, 10.5f);
        List<Missions.Event> tl = active.timeline();
        int cur = -1;
        for (int i = 0; i < tl.size(); i++) if (tl.get(i).sim <= now) cur = i;

        // Layout pass: measure height.
        int pad = 13, lh = 16;
        List<String> phase = new ArrayList<>();
        String phaseTitle;
        if (cur >= 0) {
            phaseTitle = tl.get(cur).title;
            phase.addAll(wrap(g2.getFontMetrics(fBody), tl.get(cur).text, w - 2 * pad));
        } else {
            phaseTitle = "Before launch";
            phase.addAll(wrap(g2.getFontMetrics(fBody), active.summary, w - 2 * pad));
        }
        double[] span = active.span();
        if (now > span[1]) { phaseTitle = "Mission complete"; phase.clear(); phase.addAll(wrap(g2.getFontMetrics(fBody), "End of the trajectory data. Pick an event to replay it.", w - 2 * pad)); }
        if (followed() == null && now < span[0])
            phase.addAll(wrap(g2.getFontMetrics(fBody), "(No trajectory before " + utcText(span[0]) + ".)", w - 2 * pad));
        String[] read = readouts(now);
        int rows = Math.min(tl.size(), Math.max(4, (H - 340) / lh));
        int h = pad + 20 + 16 + 20 + 18 + phase.size() * lh + 8 + read.length * lh + 14 + rows * lh + 12 + 26 + pad;
        h = Math.min(h, H - 28);

        g2.setColor(new Color(0, 0, 0, 70));
        g2.fillRoundRect(x0 + 2, y0 + 3, w, h, 16, 16);
        g2.setPaint(new GradientPaint(x0, y0, PANEL_TOP, x0, y0 + h, PANEL_BOT));
        g2.fillRoundRect(x0, y0, w, h, 16, 16);
        g2.setColor(new Color(100, 150, 255, 120));
        g2.setStroke(new BasicStroke(1.4f));
        g2.drawRoundRect(x0, y0, w, h, 16, 16);

        int x = x0 + pad, y = y0 + pad + 13;
        g2.setFont(fTitle); g2.setColor(ACCENT);
        g2.drawString(active.name, x, y);
        addButton(g2, "✕", x0 + w - pad - 18, y - 13, 18, 18, this::stop);
        y += 16;
        g2.setFont(fSub); g2.setColor(DIM);
        g2.drawString(clip(g2.getFontMetrics(fSub), active.subtitle, w - 2 * pad), x, y);
        y += 20;
        g2.setFont(fBold); g2.setColor(TEXT);
        g2.drawString(metText(now), x, y);
        g2.setFont(fSmall); g2.setColor(DIM);
        String date = utcText(now);
        g2.drawString(date, x0 + w - pad - g2.getFontMetrics().stringWidth(date), y);
        y += 18;
        g2.setFont(fBold); g2.setColor(new Color(255, 214, 140));
        g2.drawString(clip(g2.getFontMetrics(fBold), phaseTitle, w - 2 * pad), x, y);
        y += lh;
        g2.setFont(fBody); g2.setColor(TEXT);
        for (String s : phase) { g2.drawString(s, x, y); y += lh; }
        y += 4;
        g2.setFont(fSmall); g2.setColor(new Color(180, 200, 235));
        for (String s : read) { g2.drawString(s, x, y); y += lh; }
        y += 2;
        g2.setColor(new Color(100, 150, 255, 70));
        g2.drawLine(x, y - 8, x0 + w - pad, y - 8);

        // Timeline rows, scrolled so the current event is visible.
        int first = Math.max(0, Math.min(tl.size() - rows, (cur < 0 ? 0 : cur) - rows / 3 + timelineScroll));
        g2.setFont(fSmall);
        for (int i = first; i < Math.min(tl.size(), first + rows); i++) {
            Missions.Event e = tl.get(i);
            boolean isCur = i == cur;
            Rectangle r = new Rectangle(x - 4, y - 12, w - 2 * pad + 8, lh);
            if (isCur) { g2.setColor(new Color(100, 150, 255, 55)); g2.fill(r); }
            g2.setColor(e.sim <= now ? new Color(170, 185, 210) : TEXT);
            String when = active.kind == Missions.Kind.LUNAR ? shortMet(e.sim - active.launch) : shortDate(e.sim);
            g2.drawString(when, x, y);
            g2.setColor(isCur ? new Color(255, 225, 160) : (e.sim <= now ? new Color(170, 185, 210) : TEXT));
            g2.drawString(clip(g2.getFontMetrics(), e.title, w - 2 * pad - 70), x + 68, y);
            final Missions.Event ev = e;
            hits.add(new Object[]{r, (Runnable) () -> jumpToEvent(ev)});
            y += lh;
        }
        y += 6;
        // Controls.
        int bx = x, bh = 22;
        bx += addButton(g2, "◀ Prev", bx, y, 58, bh, () -> step(-1)) + 5;
        bx += addButton(g2, "Next ▶", bx, y, 58, bh, () -> step(+1)) + 5;
        String pace = autoPace ? ("Pace ×" + fmtMul(paceMul)) : "Pace: manual";
        bx += addButton(g2, pace, bx, y, 82, bh, this::cyclePace) + 5;
        bx += addButton(g2, camReleased ? "Cam: off" : (autoCamera ? "Cam: auto" : "Cam: free"), bx, y, 70, bh, () -> {
            if (camReleased) { camReleased = false; autoCamera = true; } else autoCamera = !autoCamera;
        }) + 5;
        if (active.kind == Missions.Kind.LUNAR && x0 + w - pad - bx > 40)
            addButton(g2, rotatingFrame ? "Rot." : "Inert.", bx, y, x0 + w - pad - bx, bh, () -> rotatingFrame = !rotatingFrame);
        g2.setFont(base);
    }

    private String[] readouts(double now) {
        Missions.Craft c = followed();
        if (c == null) return new String[0];
        double tdb = Astro.ttSeconds(now);
        SpacecraftTrack t = c.track();
        SpacecraftTrack.Segment g = t.segmentAt(tdb);
        double[] st = new double[6];
        g.stateAt(tdb, st);
        List<String> L = new ArrayList<>();
        L.add("Following: " + c.name + (focusCraft < 0 ? "" : " (picked)"));
        if (active.kind == Missions.Kind.LUNAR) {
            double[] m = ApolloReconstruction.moonState(tdb);
            double[] geo = st.clone();
            if (g.centre.equals("Moon")) for (int i = 0; i < 6; i++) geo[i] += m[i];
            double rE = Math.sqrt(sq(geo[0]) + sq(geo[1]) + sq(geo[2]));
            double rM = Math.sqrt(sq(geo[0] - m[0]) + sq(geo[1] - m[1]) + sq(geo[2] - m[2]));
            double vE = Math.sqrt(sq(geo[3]) + sq(geo[4]) + sq(geo[5]));
            double vM = Math.sqrt(sq(geo[3] - m[3]) + sq(geo[4] - m[4]) + sq(geo[5] - m[5]));
            L.add(String.format("Earth  %,.0f km  (alt %,.0f km)   %.3f km/s", rE, rE - 6378.137, vE));
            L.add(String.format("Moon  %,.0f km  (alt %,.0f km)   %.3f km/s", rM, rM - 1737.4, vM));
        } else {
            double[] h = st.clone();
            if (!g.centre.equals("Sun")) {
                double[] cs = centreInBase(g.centre, false, tdb);
                for (int i = 0; i < 6; i++) h[i] += cs[i];
                double r = Math.sqrt(sq(st[0]) + sq(st[1]) + sq(st[2]));
                L.add(String.format("%s  %,.0f km  (alt %,.0f km)   %.2f km/s", g.centre, r,
                        r - Missions.centreRadiusKm(g.centre), Math.sqrt(sq(st[3]) + sq(st[4]) + sq(st[5]))));
            }
            double rs = Math.sqrt(sq(h[0]) + sq(h[1]) + sq(h[2])), vs = Math.sqrt(sq(h[3]) + sq(h[4]) + sq(h[5]));
            double[] e = centreInBase("Earth", false, tdb);
            double re = Math.sqrt(sq(h[0] - e[0]) + sq(h[1] - e[1]) + sq(h[2] - e[2]));
            L.add(String.format("Sun  %.3f AU   %.2f km/s", rs / 149597870.7, vs));
            L.add(String.format("Earth  %.3f AU   one-way light time %s", re / 149597870.7, lightTime(re / 299792.458)));
        }
        return L.toArray(new String[0]);
    }

    private static String lightTime(double s) {
        if (s < 60) return String.format("%.1f s", s);
        if (s < 3600) return String.format("%.1f min", s / 60);
        return String.format("%d h %02d min", (int) (s / 3600), (int) (s % 3600 / 60));
    }

    private int addButton(Graphics2D g2, String label, int x, int y, int w, int h, Runnable action) {
        RoundRectangle2D.Double r = new RoundRectangle2D.Double(x, y, w, h, 9, 9);
        g2.setColor(new Color(60, 80, 130, 150));
        g2.fill(r);
        g2.setColor(new Color(120, 160, 240, 160));
        g2.setStroke(new BasicStroke(1f));
        g2.draw(r);
        Font f = g2.getFont();
        g2.setFont(f.deriveFont(Font.PLAIN, 11f));
        FontMetrics fm = g2.getFontMetrics();
        g2.setColor(TEXT);
        g2.drawString(label, x + (w - fm.stringWidth(label)) / 2, y + (h + fm.getAscent() - fm.getDescent()) / 2);
        g2.setFont(f);
        hits.add(new Object[]{new Rectangle(x, y, w, h), action});
        return w;
    }

    /** Handle a click on the panel; true if consumed. */
    boolean click(int mx, int my) {
        for (int i = hits.size() - 1; i >= 0; i--) {
            Object[] h = hits.get(i);
            if (((Rectangle) h[0]).contains(mx, my)) { ((Runnable) h[1]).run(); return true; }
        }
        return clickCraft(mx, my);
    }

    /** Mouse wheel over the panel scrolls the timeline; true if consumed. */
    boolean wheel(int mx, int my, int notches) {
        if (active == null || hits.isEmpty()) return false;
        int W = host.width(), w = Math.min(350, W / 3 + 40);
        if (mx < W - w - 14) return false;
        timelineScroll += notches;
        return true;
    }

    private void jumpToEvent(Missions.Event e) {
        double lead = active.kind == Missions.Kind.LUNAR ? 90 : (e.viewKm > 0 ? 1.5 * 86400 : 0);
        double[] span = active.span();
        double t = e.sim - lead;
        if (t < span[0] && e.sim >= span[0]) t = span[0] + 1;
        host.jumpTo(t);
        host.setPaused(false);
        timelineScroll = 0;
        autoCamera = true;
        camReleased = false;
        if (!autoPace) { autoPace = true; lastSetScale = Double.NaN; }
    }

    private void step(int dir) {
        List<Missions.Event> tl = active.timeline();
        double now = host.simTime();
        Missions.Event pick = null;
        if (dir > 0) { for (Missions.Event e : tl) if (e.sim > now + 120) { pick = e; break; } }
        else { for (Missions.Event e : tl) if (e.sim < now - 600) pick = e; }
        if (pick != null) jumpToEvent(pick);
    }

    private void cyclePace() {
        if (!autoPace) { autoPace = true; paceMul = 1; lastSetScale = Double.NaN; return; }
        double[] steps = {0.25, 0.5, 1, 2, 4, 8};
        int i = 0;
        while (i < steps.length && steps[i] <= paceMul + 1e-9) i++;
        paceMul = i < steps.length ? steps[i] : steps[0];
        lastSetScale = Double.NaN;
    }

    // ------------------------------------------------------------------ text helpers

    private String metText(double now) {
        double d = now - active.launch;
        String sign = d < 0 ? "T−" : "T+";
        d = Math.abs(d);
        int days = (int) (d / 86400);
        int hh = (int) (d % 86400 / 3600), mm = (int) (d % 3600 / 60), ss = (int) (d % 60);
        if (active.kind == Missions.Kind.DEEP_SPACE && days > 400)
            return String.format("%s %.2f years", sign, d / (365.25 * 86400));
        return String.format("%s %dd %02d:%02d:%02d", sign, days, hh, mm, ss);
    }

    private static String shortMet(double d) {
        if (d < 0) return "T−";
        int h = (int) (d / 3600), m = (int) (d % 3600 / 60);
        return String.format("%03d:%02d", h, m);
    }

    private static String shortDate(double sim) {
        java.time.LocalDateTime t = java.time.LocalDateTime.of(2000, 1, 1, 12, 0).plusSeconds((long) sim);
        return String.format("%04d-%02d-%02d", t.getYear(), t.getMonthValue(), t.getDayOfMonth());
    }

    static String utcText(double sim) {
        java.time.LocalDateTime t = java.time.LocalDateTime.of(2000, 1, 1, 12, 0).plusSeconds((long) sim);
        return String.format("%04d-%02d-%02d %02d:%02d UTC", t.getYear(), t.getMonthValue(), t.getDayOfMonth(), t.getHour(), t.getMinute());
    }

    private static String fmtMul(double m) { return m < 1 ? (m == 0.25 ? "¼" : "½") : String.valueOf((int) m); }

    private static List<String> wrap(FontMetrics fm, String text, int width) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String cand = line.length() == 0 ? word : line + " " + word;
            if (fm.stringWidth(cand) > width && line.length() > 0) { out.add(line.toString()); line = new StringBuilder(word); }
            else line = new StringBuilder(cand);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    private static String clip(FontMetrics fm, String s, int w) {
        if (fm.stringWidth(s) <= w) return s;
        while (s.length() > 1 && fm.stringWidth(s + "…") > w) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    private static double sq(double x) { return x * x; }
}
