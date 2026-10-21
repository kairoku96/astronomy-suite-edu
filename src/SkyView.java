import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import javax.imageio.ImageIO;
import javax.swing.*;

/**
 * "Sky from Earth": a planetarium view of the real sky from a place on Earth at the sim's
 * date and time, driven by the same ephemerides as the 3-D view.
 *
 * <p>Every position goes through the full reduction an observatory would use:
 * <ol>
 *   <li>geometric positions — planets, dwarf planets, comets and asteroids from the N-body
 *       integration, the Moon from ELP/MPP02, planetary moons from their fitted theories,
 *       stars from the Yale Bright Star Catalogue with proper motion;</li>
 *   <li>light-time (we see Jupiter where it was ~40 minutes ago) and annual aberration
 *       (Earth's 30 km/s orbital velocity tilts incoming light by up to 20″);</li>
 *   <li>topocentric parallax — from the observer's place on the Earth's surface, not its
 *       centre (it shifts the Moon by up to a degree, which is what makes eclipses local);</li>
 *   <li>precession and nutation to the true equator of date, then Earth rotation (Greenwich
 *       apparent sidereal time) to the local horizon; and</li>
 *   <li>atmospheric refraction, which lifts objects near the horizon by ~½°.</li>
 * </ol>
 * The Sun, Moon and planets are drawn as lit spheres at their true angular size and
 * orientation (IAU pole and prime meridian), so phases, the Moon's libration and the tilt
 * of Saturn's rings come out of the geometry. The sky is coloured by the Sun's altitude
 * (day, civil / nautical / astronomical twilight, night), stars dim by atmospheric extinction
 * toward the horizon, and the faintest visible star tracks the sky brightness.
 */
final class SkyView {

    /** What the planetarium needs from the simulation. */
    interface Host {
        double simTimeSec();
        void repaint();
        Component component();
        /** Set the sim clock to the current real time. */
        void goToNow();
    }

    private final Host host;

    // ---------------------------------------------------------------- observer & view state
    double latDeg = 51.4779, lonDeg = -0.0015;          // east-positive longitude
    String placeName = "Greenwich, UK";
    /** The observer's civil time zone (for showing local time); the computer's zone until a place sets one. */
    java.time.ZoneId zone = java.time.ZoneId.systemDefault();
    private double viewAz = Math.toRadians(180), viewAlt = Math.toRadians(25);   // looking south
    private double fovDeg = 100;
    private static final double FOV_MIN = 0.02, FOV_MAX = 200;

    boolean showConstLines = true, showConstNames = true, showStarNames = true, showAltAzGrid = false,
            showEqGrid = false, showEcliptic = true, showGround = true, showAtmosphere = true;

    // ---- Lesson hooks (see LessonStudio): a lesson draws its own readouts over the sky ----
    /** While a lesson runs the sky's own HUD and info panel step aside for the lesson's. */
    boolean lessonMode;
    /**
     * Naked-eye limiting magnitude set by a lesson (light pollution), or NaN for the sky's own
     * value from the Sun's altitude and the Moon.
     */
    double limitOverride = Double.NaN;
    /**
     * Telescope aperture (mm) a lesson looks through, or NaN to let a narrow field stand in for
     * optical aid. A telescope of aperture D gathers (D / 7 mm)² times the light of a dark-adapted
     * pupil, so it reaches 5 log₁₀(D / 7 mm) magnitudes fainter.
     */
    double apertureMm = Double.NaN;
    /** Only stars whose B−V passes this test are drawn (a lesson's spectral-class filter); null = all. */
    java.util.function.DoublePredicate starFilter;
    /**
     * Keep celestial north up, as an equatorially mounted telescope does, instead of the zenith.
     * With the zenith up (an alt-az view) the field rotates as a target crosses the sky: a
     * tracked Saturn's rings would turn through tens of degrees in a night.
     */
    boolean northUp;

    /** Selected object (by name) for the info panel, or null. */
    private String selectedName;
    private double[] riseSet;           // {rise, transit, set} sim seconds (NaN = none) for the selection
    private String riseSetKey = "";
    private long riseSetNanos;

    private static final double DAY = 86_400.0;
    private static final double C_AUD = 299_792.458 * DAY / 149_597_870.7;   // light speed, AU/day
    private static final double AU_KM = 149_597_870.7;

    SkyView(Host host) {
        this.host = host;
        loadLocation();
    }

    // ---------------------------------------------------------------- per-frame geometry

    /** One solar-system object as seen from the observer this frame. */
    static final class Obj {
        String name, kind;
        int planet = -1, moonOf = -1, moon = -1;  // indices into the sim's tables
        double[] dir;          // apparent topocentric direction, ecliptic J2000 unit vector
        double distAU;         // observer distance
        double radiusKm;       // physical radius (0 = point)
        double mag;            // visual magnitude
        double phaseAngle;     // Sun–object–observer angle (rad)
        double[] sunDir;       // object → Sun unit vector (ecliptic J2000)
        double alt, az;        // apparent (refracted) altitude, azimuth (rad)
        int rgb;
        double sx, sy, rPx;    // screen placement this frame (rPx = disc radius, 0 if a point)
        boolean onScreen;
    }

    /** Frame state shared by the drawing passes. */
    private double[] H;                 // ecliptic-J2000 → local ENU (east, north, up), 3×3 row-major
    private double[] PC;                // ecliptic-J2000 → true equator of date
    private double[] beta;              // Earth velocity / c (aberration)
    private double[] obsEcl;            // observer's geocentric position (AU, ecliptic J2000)
    private double lst;                 // local apparent sidereal time (rad)
    private double sunAltDeg, sunAzRad, moonAltDeg, moonIllum;
    /** Fraction of the Sun's disc not covered by the Moon (1 = no eclipse, 0 = total). */
    private double sunVisible = 1;
    private double limitMag;
    private final List<Obj> objs = new ArrayList<>();
    private int W, Hh;
    private double cx, cy, scale;       // stereographic: screen = c + scale·k·(x, −y)
    private double[] camR, camU, camF;  // camera basis in ENU

    /** Sim time the frame state below was computed for. */
    private double frameT = Double.NaN;

    private void computeFrame(double t) {
        frameT = t;
        double tt = Astro.ttSeconds(t);
        double[] P = Astro.j2000ToTrueOfDate(tt);
        double ce = Math.cos(Astro.EPS_J2000), se = Math.sin(Astro.EPS_J2000);
        double[] C = {1, 0, 0, 0, ce, -se, 0, se, ce};          // ecliptic → equatorial J2000
        PC = Astro.mul(P, C);
        lst = Astro.gast(t) + Math.toRadians(lonDeg);
        double cl = Math.cos(lst), sl = Math.sin(lst);
        double phi = Math.toRadians(latDeg), cp = Math.cos(phi), sp = Math.sin(phi);
        double[] enu = {-sl, cl, 0, -sp * cl, -sp * sl, cp, cp * cl, cp * sl, sp};
        H = Astro.mul(enu, PC);

        // Observer's geocentric position on the WGS-84 ellipsoid (sea level), of date → J2000 ecliptic.
        double f = 1 / 298.257223563, u = Math.atan((1 - f) * Math.tan(phi));
        double rs = (1 - f) * Math.sin(u), rc = Math.cos(u), a = 6378.137 / AU_KM;
        double[] od = {a * rc * cl, a * rc * sl, a * rs};
        obsEcl = applyT(PC, od);

        double[] S = SolarSystemSimulation.ephemerisStates(t);
        double[] E = {S[12], S[13], S[14]};
        beta = new double[]{S[15] / C_AUD, S[16] / C_AUD, S[17] / C_AUD};

        objs.clear();
        // The Sun.
        Obj sun = new Obj();
        sun.name = "Sun"; sun.kind = "Star (G2V)"; sun.radiusKm = 695_700; sun.rgb = 0xFFF4E0;
        double[] gs = {-E[0] - obsEcl[0], -E[1] - obsEcl[1], -E[2] - obsEcl[2]};
        sun.distAU = norm(gs);
        sun.dir = aberrate(gs);
        sun.mag = -26.74 + 5 * Math.log10(sun.distAU);
        objs.add(sun);
        // The Moon (geocentric ELP/MPP02 → topocentric).
        double[] mg = MoonEphemeris.geocentricEclipticAU(t);
        Obj moon = new Obj();
        moon.name = "Moon"; moon.kind = "Moon"; moon.radiusKm = 1737.4; moon.rgb = 0xD8D4CC;
        moon.planet = 2; moon.moonOf = 2; moon.moon = 0;
        double[] gm = {mg[0] - obsEcl[0], mg[1] - obsEcl[1], mg[2] - obsEcl[2]};
        moon.distAU = norm(gm);
        moon.dir = unit(gm);
        double[] moonHelio = {E[0] + mg[0], E[1] + mg[1], E[2] + mg[2]};
        setPhase(moon, moonHelio);
        double ip = Math.toDegrees(moon.phaseAngle);
        moon.mag = -12.73 + 0.026 * ip + 4e-9 * Math.pow(ip, 4) + 5 * Math.log10(moon.distAU * AU_KM / 384_400.0);
        objs.add(moon);

        // Planets, dwarf planets and their moons.
        int nb = NBodyEphemeris.N_BODIES;
        for (int i = 0; i < SolarSystemSimulation.bodyCount(); i++) {
            if (i == 2) continue;
            double[] p = {S[6 * i], S[6 * i + 1], S[6 * i + 2]};
            double[] v = {S[6 * i + 3], S[6 * i + 4], S[6 * i + 5]};
            double tau = norm(sub(p, E)) / C_AUD;                       // light-time (days)
            double[] pr = {p[0] - v[0] * tau, p[1] - v[1] * tau, p[2] - v[2] * tau};
            Obj o = new Obj();
            o.name = SolarSystemSimulation.bodyName(i);
            o.kind = i < 8 ? "Planet" : "Dwarf planet";
            o.planet = i;
            o.radiusKm = SolarSystemSimulation.bodyRadiusM(i) / 1000.0;
            o.rgb = PLANET_RGB[i];
            double[] g = {pr[0] - E[0] - obsEcl[0], pr[1] - E[1] - obsEcl[1], pr[2] - E[2] - obsEcl[2]};
            o.distAU = norm(g);
            o.dir = aberrate(g);
            setPhase(o, pr);
            o.mag = planetMagnitude(i, norm(pr), o.distAU, o.phaseAngle, o.dir);
            objs.add(o);
            // This planet's moons, at the same light-time.
            String[] mn = SolarSystemSimulation.MOON_NAMES[i];
            for (int m = 0; m < SatelliteEphemeris.count(i); m++) {
                double[] k = SatelliteEphemeris.positionKm(i, m, Astro.ttSeconds(t - tau * DAY));
                double[] q = {pr[0] + k[0] / AU_KM, pr[1] + k[1] / AU_KM, pr[2] + k[2] / AU_KM};
                Obj s = new Obj();
                s.name = mn[m]; s.kind = "Moon of " + o.name;
                s.planet = i; s.moonOf = i; s.moon = m;
                s.radiusKm = SolarSystemSimulation.moonRadiusM(i, m) / 1000.0;
                s.rgb = 0xCFC8BC;
                double[] gq = {q[0] - E[0] - obsEcl[0], q[1] - E[1] - obsEcl[1], q[2] - E[2] - obsEcl[2]};
                s.distAU = norm(gq);
                s.dir = aberrate(gq);
                setPhase(s, q);
                Double hMag = MOON_H.get(mn[m]);
                s.mag = (hMag == null ? 8 : hMag) + 5 * Math.log10(norm(q) * s.distAU);
                objs.add(s);
            }
        }
        // Comets and asteroids (integrated test particles).
        for (int k = NBodyEphemeris.N_MASSIVE; k < nb; k++) {
            String name = NBodyEphemeris.TEST[k - NBodyEphemeris.N_MASSIVE];
            double[] p = {S[6 * k], S[6 * k + 1], S[6 * k + 2]};
            double[] v = {S[6 * k + 3], S[6 * k + 4], S[6 * k + 5]};
            double tau = norm(sub(p, E)) / C_AUD;
            double[] pr = {p[0] - v[0] * tau, p[1] - v[1] * tau, p[2] - v[2] * tau};
            Obj o = new Obj();
            o.name = name;
            boolean comet = name.contains("/");
            o.kind = comet ? "Comet" : "Minor body";
            o.rgb = comet ? 0xBFEFFF : 0xD8D0B8;
            double[] g = {pr[0] - E[0] - obsEcl[0], pr[1] - E[1] - obsEcl[1], pr[2] - E[2] - obsEcl[2]};
            o.distAU = norm(g);
            o.dir = aberrate(g);
            setPhase(o, pr);
            double r = norm(pr);
            double[] ph = MINOR_PHOT.get(name);
            o.mag = comet ? ph[0] + 5 * Math.log10(o.distAU) + 2.5 * ph[1] * Math.log10(r)
                          : ph[0] + 5 * Math.log10(r * o.distAU) + 0.03 * Math.toDegrees(o.phaseAngle);
            objs.add(o);
        }
        for (Obj o : objs) {
            double[] h = Astro.apply(H, o.dir[0], o.dir[1], o.dir[2]);
            double altTrue = Math.asin(clamp(h[2]));
            o.alt = altTrue + refraction(altTrue);
            o.az = Math.atan2(h[0], h[1]);
        }
        sunAltDeg = Math.toDegrees(objs.get(0).alt);
        sunAzRad = objs.get(0).az;
        moonAltDeg = Math.toDegrees(objs.get(1).alt);
        moonIllum = (1 + Math.cos(objs.get(1).phaseAngle)) / 2;
        sunVisible = 1 - discOverlap(objs.get(0), objs.get(1));
        limitMag = limitingMagnitude();
    }

    /**
     * Fraction of body {@code a}'s disc hidden behind body {@code b} (circle–circle overlap
     * of their true angular discs) — the obscuration of a solar eclipse as seen from here.
     */
    private static double discOverlap(Obj a, Obj b) {
        double r1 = Math.asin(Math.min(1, a.radiusKm / (a.distAU * AU_KM)));
        double r2 = Math.asin(Math.min(1, b.radiusKm / (b.distAU * AU_KM)));
        double d = Math.acos(clamp(dot(a.dir, b.dir)));
        if (d >= r1 + r2) return 0;
        if (d <= Math.abs(r1 - r2)) return Math.min(1, (r2 * r2) / (r1 * r1));
        double c1 = Math.acos(clamp((d * d + r1 * r1 - r2 * r2) / (2 * d * r1)));
        double c2 = Math.acos(clamp((d * d + r2 * r2 - r1 * r1) / (2 * d * r2)));
        double k = Math.sqrt(Math.max(0, (-d + r1 + r2) * (d + r1 - r2) * (d - r1 + r2) * (d + r1 + r2)));
        return (r1 * r1 * c1 + r2 * r2 * c2 - 0.5 * k) / (Math.PI * r1 * r1);
    }

    /** Phase geometry: object → Sun direction and the Sun–object–observer angle. */
    private void setPhase(Obj o, double[] helio) {
        double r = norm(helio);
        o.sunDir = new double[]{-helio[0] / r, -helio[1] / r, -helio[2] / r};
        double c = -(o.dir[0] * o.sunDir[0] + o.dir[1] * o.sunDir[1] + o.dir[2] * o.sunDir[2]);   // (obj→obs)·(obj→sun)
        o.phaseAngle = Math.acos(clamp(c));
    }

    /** Annual aberration: apparent direction = normalize(u + v⊕/c). */
    private double[] aberrate(double[] g) {
        double n = norm(g);
        return unit(new double[]{g[0] / n + beta[0], g[1] / n + beta[1], g[2] / n + beta[2]});
    }

    /** Refraction (rad) to add to a true altitude — Saemundsson's formula, standard atmosphere. */
    static double refraction(double altTrue) {
        double h = Math.toDegrees(altTrue);
        if (h < -2) return 0;
        double rArcmin = 1.02 / Math.tan(Math.toRadians(h + 10.3 / (h + 5.11)));
        return Math.toRadians(Math.max(0, rArcmin) / 60.0);
    }

    /**
     * Faintest magnitude visible against the current sky: ~6.3 on a dark night, brightening
     * through twilight to about −4.5 in daylight (only Venus, the Moon and the Sun survive),
     * and a little worse under a bright Moon. Narrow fields act like optical aid (+~2.5 mag
     * per 10× in field width), which lets the planets' fainter moons appear when zoomed in.
     */
    private double limitingMagnitude() {
        double s = sunAltDeg, L;
        if (s <= -18) L = 6.3;
        else if (s <= -12) L = 6.3 - (s + 18) / 6 * 0.8;
        else if (s <= -6) L = 5.5 - (s + 12) / 6 * 2.5;
        else if (s <= 0) L = 3.0 - (s + 6) / 6 * 4.0;
        else L = -1.0 - Math.min(1, s / 8) * 3.5;
        // During a solar eclipse the daylight sky dims with the visible part of the Sun:
        // at totality the brighter stars and planets come out (it's like deep twilight).
        if (s > -6 && sunVisible < 1) L = Math.min(6.3, L - 2.5 * Math.log10(Math.max(sunVisible, 2e-3)));
        if (moonAltDeg > 0 && s < -6) L -= 1.2 * moonIllum * Math.sin(Math.toRadians(moonAltDeg));
        if (!Double.isNaN(limitOverride)) L = limitOverride;
        if (!Double.isNaN(apertureMm)) return L + 5 * Math.log10(Math.max(1.0, apertureMm / 7.0));
        double optical = 2.5 * Math.log10(Math.max(1.0, 60.0 / fovDeg));
        return L + Math.min(optical, 9);
    }

    /**
     * The limiting magnitude that point sources are sized against. Through a lesson's telescope the
     * real limit can reach magnitude 20, which would blow every catalogue star up into a maximal glare
     * disc; the telescope shows fainter things, but bright stars should not balloon on screen. So
     * sprites are sized against at most magnitude 7.5, while visibility still uses the real limit.
     */
    private double spriteLimit() { return Double.isNaN(apertureMm) ? limitMag : Math.min(limitMag, 7.5); }

    /** Extinction (mag) at apparent altitude {@code alt}: k·(X − 1) with Rozenberg's airmass. */
    private static double extinction(double alt) {
        double s = Math.sin(Math.max(alt, -0.02));
        double X = 1.0 / (s + 0.025 * Math.exp(-11 * s));
        return 0.25 * (X - 1);
    }

    // ---------------------------------------------------------------- projection

    private void setupProjection(int w, int h) {
        W = w; Hh = h; cx = w / 2.0; cy = h / 2.0;
        scale = (w / 2.0) / (2 * Math.tan(Math.toRadians(fovDeg) / 4));
        camF = enuDir(viewAz, viewAlt);
        camR = new double[]{Math.cos(viewAz), -Math.sin(viewAz), 0};
        if (northUp && PC != null) {
            // Celestial north (the true pole of date) in ENU; right = forward × up.
            double[] q = applyT(PC, new double[]{0, 0, 1});
            double[] pole = Astro.apply(H, q[0], q[1], q[2]);
            double[] r = cross(camF, pole);
            if (norm(r) > 1e-6) camR = unit(r);
        }
        camU = cross(camR, camF);
    }

    /** ENU unit vector for azimuth (from north through east) and altitude. */
    private static double[] enuDir(double az, double alt) {
        double ca = Math.cos(alt);
        return new double[]{Math.sin(az) * ca, Math.cos(az) * ca, Math.sin(alt)};
    }

    /** Stereographic screen position {x, y, k} of an ENU direction, or null if behind the viewer. */
    private double[] project(double[] v) {
        double x = dot(v, camR), y = dot(v, camU), z = dot(v, camF);
        if (z < -0.9) return null;
        double k = 2 / (1 + z);
        return new double[]{cx + scale * k * x, cy - scale * k * y, k};
    }

    /** ENU direction under a screen pixel (inverse stereographic). */
    private double[] unproject(double sx, double sy) {
        double X = (sx - cx) / scale, Y = (cy - sy) / scale, r2 = X * X + Y * Y, d = 4 + r2;
        double x = 4 * X / d, y = 4 * Y / d, z = (4 - r2) / d;
        return new double[]{x * camR[0] + y * camU[0] + z * camF[0],
                            x * camR[1] + y * camU[1] + z * camF[1],
                            x * camR[2] + y * camU[2] + z * camF[2]};
    }

    /** Apparent ENU direction (with refraction) of an ecliptic-J2000 direction. */
    private double[] eclToEnuApparent(double[] d) {
        double[] h = Astro.apply(H, d[0], d[1], d[2]);
        double alt = Math.asin(clamp(h[2]));
        double lift = refraction(alt);
        if (lift == 0) return h;
        double az = Math.atan2(h[0], h[1]);
        return enuDir(az, alt + lift);
    }

    // ---------------------------------------------------------------- painting

    void paint(Graphics2D g2, int w, int h) {
        double t = host.simTimeSec();
        computeFrame(t);
        setupProjection(w, h);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        drawSkyBackground(g2);
        StarCatalog cat = StarCatalog.get();
        double darkness = clamp01((-sunAltDeg - 4) / 10);             // 0 by day, 1 once properly dark
        if (showAltAzGrid) drawAltAzGrid(g2);
        if (showEqGrid) drawEquatorialGrid(g2);
        if (showEcliptic) drawEcliptic(g2);
        if (cat != null && showConstLines) drawConstellationLines(g2, cat, darkness);
        if (cat != null) drawStars(g2, cat, t);
        if (cat != null && showConstNames) drawConstellationNames(g2, cat, darkness);
        drawSolarSystem(g2);
        if (showGround) {
            Object oldI = g2.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(ground, 0, 0, W, Hh, null);
            if (oldI != null) g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldI);
            drawHorizonDecor(g2);
        }
        if (lessonMode) return;
        drawHud(g2, t);
        drawInfoPanel(g2, t);
    }

    // Cached low-resolution sky / ground / Milky Way background.
    private BufferedImage bg, ground;
    private String bgKey = "";

    /**
     * Sky colour per pixel (at quarter resolution, smoothed up): a Rayleigh-blue day sky
     * brightening toward the horizon and the Sun, orange-to-indigo twilight centred on the
     * Sun's azimuth, a dark night sky with the Milky Way, moonlight, and the ground below the
     * horizon lit by the same light.
     */
    private void drawSkyBackground(Graphics2D g2) {
        int bw = Math.max(1, W / 4), bh = Math.max(1, Hh / 4);
        Obj moon = objs.get(1);
        String key = bw + "x" + bh + ":" + (float) viewAz + ":" + (float) viewAlt + ":" + (float) fovDeg
                + ":" + Math.round(sunAltDeg * 20) + ":" + Math.round(Math.toDegrees(sunAzRad) * 5)
                + ":" + Math.round(Math.toDegrees(lst) * 20) + ":" + Math.round(moonAltDeg * 5)
                + ":" + showGround + ":" + showAtmosphere + ":" + (float) latDeg + ":" + (float) lonDeg
                + ":" + Math.round(sunVisible * 200);
        if (!key.equals(bgKey) || bg == null) {
            bgKey = key;
            bg = new BufferedImage(bw, bh, BufferedImage.TYPE_INT_RGB);
            ground = new BufferedImage(bw, bh, BufferedImage.TYPE_INT_ARGB);
            int[] gpx = new int[bw * bh];
            StarCatalog cat = StarCatalog.get();
            double[] sun = enuDir(sunAzRad, Math.toRadians(sunAltDeg));
            double[] mn = enuDir(moon.az, moon.alt);
            double day = showAtmosphere ? Math.pow(clamp01((sunAltDeg + 6) / 16), 1.2) : 0;
            double dayLit = day;                                   // ground light, before eclipse dimming
            day *= Math.pow(Math.max(sunVisible, 1e-4), 0.35);     // an eclipse darkens the sky
            double twi = showAtmosphere ? clamp01((sunAltDeg + 18) / 18) : 0;
            double twiK = twi * twi * (1 - day) * clamp01(1 - sunAltDeg / 10);
            double totality = dayLit * clamp01(1 - sunVisible / 0.05);
            double mwVis = clamp01((-sunAltDeg - 12) / 6) * (moonAltDeg > 0 ? 1 - 0.6 * moonIllum : 1);
            double moonGlow = showAtmosphere && moonAltDeg > 0 ? moonIllum * Math.sin(Math.toRadians(moonAltDeg)) * (1 - day) : 0;
            double[] Ht = transpose(H);
            int[] px = new int[bw * bh];
            for (int y = 0; y < bh; y++) {
                for (int x = 0; x < bw; x++) {
                    double[] v = unproject((x + 0.5) * 4, (y + 0.5) * 4);
                    double up = v[2];
                    double r, g, b;
                    if (showGround) {
                        // The ground is an overlay drawn over the sky layers (so constellation
                        // figures, grids and set bodies vanish behind it); opaque below the horizon.
                        double lit = 0.06 + 0.94 * dayLit * Math.pow(Math.max(sunVisible, 1e-4), 0.35);
                        int gr = clamp8(10 + 46 * lit + 10 * twi), gg = clamp8(13 + 52 * lit + 8 * twi), gb = clamp8(10 + 34 * lit + 10 * twi);
                        gpx[y * bw + x] = up < 0 ? (0xFF << 24) | (gr << 16) | (gg << 8) | gb : 0;
                    }
                    {
                        double h = Math.max(0, up);
                        double cosG = dot(v, sun), gamma = Math.acos(clamp(cosG));
                        double horiz = Math.pow(1 - h, 3);
                        // Night base + airglow near the horizon.
                        r = 3 + 5 * horiz; g = 4 + 6 * horiz; b = 10 + 10 * horiz;
                        // Twilight: warm band at the horizon under the Sun, deep blue overhead
                        // (only while the Sun is near or below the horizon).
                        if (twiK > 0) {
                            double w = Math.exp(-gamma / 0.9) * Math.pow(1 - h, 2);
                            r += twiK * (30 + 200 * w); g += twiK * (45 + 95 * w); b += twiK * (95 + 25 * w);
                        }
                        // Totality: deep-blue sky ringed by a "360° sunset" — sunlight from beyond the
                        // Moon's shadow scattering in at the horizon all round.
                        if (totality > 0) {
                            double w = Math.pow(1 - h, 5);
                            r += totality * (14 + 150 * w); g += totality * (22 + 95 * w); b += totality * (52 + 55 * w);
                        }
                        // Daylight: Rayleigh blue, paler at the horizon, bright around the Sun.
                        if (day > 0) {
                            double sunGlow = 0.75 * Math.exp(-gamma / 0.12) + 0.25 * Math.exp(-gamma / 0.6);
                            double dr = 40 + 135 * horiz, dg = 95 + 110 * horiz, db = 195 + 45 * horiz;
                            dr += (255 - dr) * sunGlow; dg += (248 - dg) * sunGlow; db += (232 - db) * sunGlow;
                            r += day * dr; g += day * dg; b += day * db;
                        }
                        if (moonGlow > 0) {
                            double gm = Math.acos(clamp(dot(v, mn)));
                            double w = moonGlow * (0.35 + Math.exp(-gm / 0.35));
                            r += 10 * w; g += 15 * w; b += 26 * w;
                        }
                        if (mwVis > 0 && cat != null) {
                            double[] e = Astro.apply(Ht, v[0], v[1], v[2]);   // ENU → ecliptic J2000 (approx. of date)
                            double[] q = Astro.eclToEq(e[0], e[1], e[2]);
                            double m = cat.milkyWay(q[0], q[1], q[2]) * mwVis * (0.35 + 0.65 * Math.min(1, h * 4));
                            r += 46 * m; g += 50 * m; b += 62 * m;
                        }
                    }
                    px[y * bw + x] = (clamp8(r) << 16) | (clamp8(g) << 8) | clamp8(b);
                }
            }
            bg.setRGB(0, 0, bw, bh, px, 0, bw);
            ground.setRGB(0, 0, bw, bh, gpx, 0, bw);
        }
        Object oldI = g2.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.drawImage(bg, 0, 0, W, Hh, null);
        if (oldI != null) g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldI);
    }

    private void drawStars(Graphics2D g2, StarCatalog cat, double t) {
        double years = t / (365.25 * DAY);
        boolean labels = showStarNames;
        Font f = g2.getFont().deriveFont(Font.PLAIN, 11f);
        g2.setFont(f);
        double labelLimit = Math.min(limitMag - 1.0, 1.6 + 2.5 * Math.log10(Math.max(1, 90 / fovDeg)));
        for (int i = 0; i < cat.n; i++) {
            if (cat.vmag[i] > limitMag + 1) continue;
            if (starFilter != null && !starFilter.test(cat.bv[i])) continue;
            double[] d = aberrate(cat.directionAt(i, years));
            double[] v = eclToEnuApparent(d);
            if (showGround && v[2] < 0) continue;
            double[] s = project(v);
            if (s == null || s[0] < -10 || s[1] < -10 || s[0] > W + 10 || s[1] > Hh + 10) continue;
            double m = cat.vmag[i] + extinction(Math.asin(clamp(v[2])));
            double[] sp = StarCatalog.pointSprite(m, spriteLimit());
            if (sp[1] < 0.04) continue;
            int rgb = cat.rgb[i];
            double r = drawPointSource(g2, s[0], s[1], 0, sp, rgb);
            if (labels && m < labelLimit && !cat.displayName(i).isEmpty()
                    && (!cat.properName[i].isEmpty() || fovDeg < 25)) {
                g2.setColor(new Color(200, 215, 240, (int) (190 * Math.min(1, sp[1]))));
                g2.drawString(cat.displayName(i), (float) (s[0] + r + 3), (float) (s[1] - r - 1));
            }
        }
    }

    private void drawConstellationLines(Graphics2D g2, StarCatalog cat, double darkness) {
        g2.setStroke(new BasicStroke(1f));
        g2.setColor(new Color(110, 150, 220, (int) (12 + 88 * darkness)));
        for (StarCatalog.Figure fig : cat.figures) {
            Path2D.Double p = new Path2D.Double();
            boolean pen = false;
            for (double[] d : fig.points()) {
                double[] v = eclToEnuApparent(d);
                double[] s = project(v);
                if (s == null) { pen = false; continue; }
                if (!pen) { p.moveTo(s[0], s[1]); pen = true; } else p.lineTo(s[0], s[1]);
            }
            g2.draw(p);
        }
    }

    private void drawConstellationNames(Graphics2D g2, StarCatalog cat, double darkness) {
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, fovDeg < 30 ? 13f : 11f));
        FontMetrics fm = g2.getFontMetrics();
        g2.setColor(new Color(150, 175, 225, (int) (18 + 132 * darkness)));
        for (StarCatalog.Label l : cat.labels) {
            double[] v = eclToEnuApparent(l.dir());
            if (showGround && v[2] < 0) continue;
            double[] s = project(v);
            if (s == null || s[0] < 0 || s[1] < 0 || s[0] > W || s[1] > Hh) continue;
            String txt = l.name().toUpperCase();
            g2.drawString(txt, (float) (s[0] - fm.stringWidth(txt) / 2.0), (float) s[1]);
        }
    }

    /** A great circle given as ecliptic-J2000 points, projected and stroked. */
    private void strokeCircle(Graphics2D g2, java.util.function.DoubleFunction<double[]> eclAt, int n) {
        Path2D.Double p = new Path2D.Double();
        boolean pen = false;
        double[] last = null;
        for (int k = 0; k <= n; k++) {
            double[] v = eclToEnuApparent(eclAt.apply(2 * Math.PI * k / n));
            double[] s = project(v);
            if (s == null || (last != null && Math.hypot(s[0] - last[0], s[1] - last[1]) > W)) { pen = false; last = s; continue; }
            if (!pen) { p.moveTo(s[0], s[1]); pen = true; } else p.lineTo(s[0], s[1]);
            last = s;
        }
        g2.draw(p);
    }

    private void drawEcliptic(Graphics2D g2) {
        g2.setStroke(new BasicStroke(1.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{6f, 5f}, 0f));
        g2.setColor(new Color(235, 200, 90, 120));
        strokeCircle(g2, a -> new double[]{Math.cos(a), Math.sin(a), 0}, 360);
    }

    private void drawEquatorialGrid(Graphics2D g2) {
        g2.setStroke(new BasicStroke(0.8f));
        g2.setColor(new Color(120, 200, 170, 70));
        double[] Pt = transpose(PC);                       // of-date equatorial → ecliptic J2000
        for (int decDeg = -75; decDeg <= 75; decDeg += 15) {
            double dc = Math.toRadians(decDeg);
            strokeCircle(g2, a -> Astro.apply(Pt, Math.cos(dc) * Math.cos(a), Math.cos(dc) * Math.sin(a), Math.sin(dc)), 180);
        }
        for (int h = 0; h < 24; h += 2) {
            double ra = Math.toRadians(h * 15);
            strokeCircle(g2, a -> Astro.apply(Pt, Math.cos(a) * Math.cos(ra), Math.cos(a) * Math.sin(ra), Math.sin(a)), 180);
        }
    }

    private void drawAltAzGrid(Graphics2D g2) {
        g2.setStroke(new BasicStroke(0.8f));
        g2.setColor(new Color(200, 150, 110, 70));
        for (int alt = 0; alt < 90; alt += 15) {
            Path2D.Double p = new Path2D.Double();
            boolean pen = false;
            for (int k = 0; k <= 360; k += 2) {
                double[] s = project(enuDir(Math.toRadians(k), Math.toRadians(alt)));
                if (s == null) { pen = false; continue; }
                if (!pen) { p.moveTo(s[0], s[1]); pen = true; } else p.lineTo(s[0], s[1]);
            }
            g2.draw(p);
        }
        for (int az = 0; az < 360; az += 30) {
            Path2D.Double p = new Path2D.Double();
            boolean pen = false;
            for (int k = 0; k <= 90; k += 2) {
                double[] s = project(enuDir(Math.toRadians(az), Math.toRadians(k)));
                if (s == null) { pen = false; continue; }
                if (!pen) { p.moveTo(s[0], s[1]); pen = true; } else p.lineTo(s[0], s[1]);
            }
            g2.draw(p);
        }
    }

    /** Horizon line and the cardinal / intercardinal points. */
    private void drawHorizonDecor(Graphics2D g2) {
        g2.setStroke(new BasicStroke(1.2f));
        g2.setColor(new Color(150, 170, 150, 140));
        Path2D.Double p = new Path2D.Double();
        boolean pen = false;
        for (int k = 0; k <= 720; k++) {
            double[] s = project(enuDir(Math.toRadians(k / 2.0), 0));
            if (s == null) { pen = false; continue; }
            if (!pen) { p.moveTo(s[0], s[1]); pen = true; } else p.lineTo(s[0], s[1]);
        }
        g2.draw(p);
        String[] names = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 15f));
        FontMetrics fm = g2.getFontMetrics();
        for (int k = 0; k < 8; k++) {
            double[] s = project(enuDir(Math.toRadians(45 * k), 0));
            if (s == null || s[0] < 0 || s[0] > W || s[1] < 0 || s[1] > Hh) continue;
            g2.setColor(k % 2 == 0 ? new Color(255, 210, 120, 230) : new Color(220, 200, 160, 160));
            g2.drawString(names[k], (float) (s[0] - fm.stringWidth(names[k]) / 2.0), (float) (s[1] + fm.getAscent() + 4));
        }
    }

    // ---------------------------------------------------------------- the Sun, Moon and planets

    private void drawSolarSystem(Graphics2D g2) {
        // Farthest first, so a nearer body covers a farther one (the Moon over the Sun = an eclipse).
        List<Obj> order = new ArrayList<>(objs);
        order.sort(Comparator.comparingDouble((Obj o) -> o.distAU).reversed());
        double[] Ht = transpose(H);
        Font lf = g2.getFont().deriveFont(Font.BOLD, 12f);
        for (Obj o : order) {
            o.onScreen = false;
            double[] v = enuDir(o.az, o.alt);
            if (showGround && o.alt < -Math.asin(Math.min(1, o.radiusKm / (o.distAU * AU_KM)))) continue;
            double[] s = project(v);
            if (s == null) continue;
            double pxPerRad = scale * s[2];
            double angR = o.radiusKm > 0 ? Math.asin(Math.min(1, o.radiusKm / (o.distAU * AU_KM))) : 0;
            double rPx = angR * pxPerRad;
            o.sx = s[0]; o.sy = s[1]; o.rPx = rPx;
            if (s[0] < -rPx - 40 || s[1] < -rPx - 40 || s[0] > W + rPx + 40 || s[1] > Hh + rPx + 40) continue;
            double m = o.mag + extinction(o.alt);
            boolean isSun = o.name.equals("Sun");
            if (!isSun && m > limitMag + 0.3 && rPx < 1.5) continue;
            if (o.moonOf >= 0 && !o.name.equals("Moon") && moonHiddenByParent(o)) continue;
            o.onScreen = true;
            // Local screen axes at the object (stereographic is conformal: one rotation + scale).
            double[][] axes = screenAxes(o.dir, Ht);
            if (isSun) drawSun(g2, s[0], s[1], Math.max(rPx, 2.5));
            else if (rPx >= 4 || (o.planet == 5 && o.moonOf < 0 && rPx >= 1.6)) {
                // Resolved disc (phases show from a few pixels up); glare from a bright small disc
                // lies over it, starting at the limb.
                drawSphere(g2, o, s[0], s[1], rPx, axes);
                if (rPx < 10) drawGlare(g2, s[0], s[1], rPx, StarCatalog.pointSprite(m, spriteLimit()), o.rgb, true);
            }
            else {
                double[] sp = StarCatalog.pointSprite(m, spriteLimit());
                drawPointSource(g2, s[0], s[1], rPx, new double[]{Math.max(sp[0], 1.0), Math.max(0.35, sp[1])}, o.rgb);
                if (o.kind.equals("Comet")) drawCometHint(g2, o, s, axes);
            }
            boolean label = o.moonOf < 0 || o.name.equals("Moon") || fovDeg < 3;
            if (label && (m < limitMag || isSun || rPx > 2)) {
                g2.setFont(lf);
                boolean sel = o.name.equals(selectedName);
                g2.setColor(sel ? new Color(255, 230, 140) : new Color(255, 220, 170, 220));
                double off = Math.max(rPx, 3) + 4;
                g2.drawString(o.name, (float) (s[0] + off), (float) (s[1] - off));
            }
            if (o.name.equals(selectedName)) {
                double rr = Math.max(rPx, 4) + 7;
                g2.setStroke(new BasicStroke(1.4f));
                g2.setColor(new Color(255, 225, 120, 200));
                g2.draw(new Ellipse2D.Double(s[0] - rr, s[1] - rr, 2 * rr, 2 * rr));
            }
        }
    }

    /** Largest solid core (px) a point source is drawn with; brighter light goes into glare. */
    private static final double MAX_CORE_PX = 2.2;

    /**
     * A source too small to resolve: a compact core — never bigger than {@link #MAX_CORE_PX},
     * or than the body's true disc {@code truePx} plus a pixel — with the rest of its
     * brightness spread as glare that fades out, as an eye or camera spreads a bright point.
     * (A flat disc sized by brightness made a 1-px Moon look like an 18-px coin.) Returns the
     * drawn radius for label placement.
     */
    private double drawPointSource(Graphics2D g2, double x, double y, double truePx, double[] sp, int rgb) {
        double r = sp[0] * (fovDeg < 20 ? 1.15 : 1.0);
        int cr = (rgb >> 16) & 0xFF, cg = (rgb >> 8) & 0xFF, cb = rgb & 0xFF;
        if (r < 0.9) {
            g2.setColor(new Color(cr, cg, cb, (int) (255 * sp[1])));
            g2.fill(new Rectangle2D.Double(x - 0.6, y - 0.6, 1.2, 1.2));
            return r;
        }
        double core = Math.min(r, Math.max(MAX_CORE_PX, truePx + 1));
        if (r > core) drawGlare(g2, x, y, core, sp, rgb);
        g2.setColor(new Color(cr, cg, cb, (int) (255 * sp[1])));
        g2.fill(new Ellipse2D.Double(x - core, y - core, 2 * core, 2 * core));
        return core;
    }

    /** Glare around a bright point or small disc: brightest at the core, falling off smoothly. */
    private void drawGlare(Graphics2D g2, double x, double y, double core, double[] sp, int rgb) {
        drawGlare(g2, x, y, core, sp, rgb, false);
    }

    /** {@code outsideOnly}: leave the disc itself clear (glare drawn over a resolved sphere). */
    private void drawGlare(Graphics2D g2, double x, double y, double core, double[] sp, int rgb, boolean outsideOnly) {
        double gr = Math.max(core * 2.5, sp[0] * 2.4);
        float in = (float) Math.min(0.9, core / gr);
        int c = rgb & 0xFFFFFF;
        int a0 = (int) ((outsideOnly ? 110 : 170) * sp[1]);
        Color centre = outsideOnly ? new Color(c, true) : new Color(c | a0 << 24, true);
        g2.setPaint(new RadialGradientPaint((float) x, (float) y, (float) gr, new float[]{0f, in, Math.min(0.99f, in + 0.25f), 1f},
                new Color[]{centre, new Color(c | (a0 * 3 / 4) << 24, true),
                            new Color(c | (a0 / 5) << 24, true), new Color(c, true)}));
        g2.fill(new Ellipse2D.Double(x - gr, y - gr, 2 * gr, 2 * gr));
    }

    /** A planetary moon behind its planet's disc (occultation) is not visible. */
    private boolean moonHiddenByParent(Obj o) {
        for (Obj p : objs) {
            if (p.planet != o.moonOf || p.moonOf >= 0) continue;
            double sep = Math.acos(clamp(dot(p.dir, o.dir)));
            double pr = Math.asin(Math.min(1, p.radiusKm / (p.distAU * AU_KM)));
            return o.distAU > p.distAU && sep < pr;
        }
        return false;
    }

    /**
     * Ecliptic-J2000 tangent vectors that map to screen-right and screen-up at the direction
     * {@code d}, found by projecting a small step — exact for the conformal stereographic map.
     */
    private double[][] screenAxes(double[] d, double[] Ht) {
        double[] a = unit(cross(Math.abs(d[2]) < 0.9 ? new double[]{0, 0, 1} : new double[]{1, 0, 0}, d));
        double[] b = cross(d, a);
        double eps = 1e-6;
        double[] s0 = project(eclToEnuApparent(d));
        double[] sa = project(eclToEnuApparent(unit(new double[]{d[0] + eps * a[0], d[1] + eps * a[1], d[2] + eps * a[2]})));
        double[] sb = project(eclToEnuApparent(unit(new double[]{d[0] + eps * b[0], d[1] + eps * b[1], d[2] + eps * b[2]})));
        if (s0 == null || sa == null || sb == null) return new double[][]{a, b};
        // Screen displacements (y up) of a step along a and along b. The sky seen from inside the
        // celestial sphere is mirrored, so b need not lie 90° anticlockwise of a on screen: solve
        // the 2×2 map exactly for the tangent vectors that land on screen-right and screen-up.
        double ax = sa[0] - s0[0], ay = -(sa[1] - s0[1]), bx = sb[0] - s0[0], by = -(sb[1] - s0[1]);
        double det = ax * by - bx * ay;
        if (Math.abs(det) < 1e-30) return new double[][]{a, b};
        // [ax bx; ay by]·(α, β) = (1, 0) for right and (0, 1) for up.
        double ra = by / det, rb = -ay / det, ua = -bx / det, ub = ax / det;
        double[] right = unit(new double[]{ra * a[0] + rb * b[0], ra * a[1] + rb * b[1], ra * a[2] + rb * b[2]});
        double[] up = unit(new double[]{ua * a[0] + ub * b[0], ua * a[1] + ub * b[1], ua * a[2] + ub * b[2]});
        return new double[][]{right, up};
    }

    private void drawSun(Graphics2D g2, double x, double y, double r) {
        double vis = Math.max(0, sunVisible);
        if (vis < 0.01) {
            // Totality: the corona — normally drowned by the photosphere — becomes visible,
            // pearly and falling off steeply with distance from the limb.
            double cr = r * 4.2, k = 1 - vis / 0.01;
            float in = (float) (r / cr);
            g2.setPaint(new RadialGradientPaint((float) x, (float) y, (float) cr,
                    new float[]{0f, in * 0.98f, in, in + (1 - in) * 0.15f, in + (1 - in) * 0.45f, 1f},
                    new Color[]{new Color(240, 240, 255, 0), new Color(240, 240, 255, 0), new Color(245, 245, 255, (int) (235 * k)),
                                new Color(225, 230, 250, (int) (120 * k)), new Color(200, 210, 240, (int) (35 * k)), new Color(190, 200, 235, 0)}));
            g2.fill(new Ellipse2D.Double(x - cr, y - cr, 2 * cr, 2 * cr));
        }
        double glow = Math.max(r * 6, 60) * (showAtmosphere && sunAltDeg > -1 ? 1.6 : 1.0);
        int ga = (int) (210 * Math.sqrt(vis));
        g2.setPaint(new RadialGradientPaint((float) x, (float) y, (float) glow,
                new float[]{0f, 0.15f, 0.45f, 1f},
                new Color[]{new Color(255, 250, 230, ga), new Color(255, 240, 200, ga * 120 / 210),
                            new Color(255, 220, 160, ga * 35 / 210), new Color(255, 210, 150, 0)}));
        g2.fill(new Ellipse2D.Double(x - glow, y - glow, 2 * glow, 2 * glow));
        // Photosphere with limb darkening.
        g2.setPaint(new RadialGradientPaint((float) x, (float) y, (float) r,
                new float[]{0f, 0.7f, 1f},
                new Color[]{new Color(255, 255, 245), new Color(255, 244, 215), new Color(240, 190, 120)}));
        g2.fill(new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r));
    }

    /** Short anti-sunward tail hint for a comet drawn as a point. */
    private void drawCometHint(Graphics2D g2, Obj o, double[] s, double[][] axes) {
        double len = 14;
        double dx = -dot(o.sunDir, axes[0]), dy = dot(o.sunDir, axes[1]);
        double n = Math.hypot(dx, dy);
        if (n < 1e-6) return;
        dx /= n; dy /= n;
        g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.setPaint(new GradientPaint((float) s[0], (float) s[1], new Color(190, 235, 255, 160),
                (float) (s[0] + dx * len), (float) (s[1] - dy * len), new Color(190, 235, 255, 0)));
        g2.draw(new java.awt.geom.Line2D.Double(s[0], s[1], s[0] + dx * len, s[1] - dy * len));
    }

    /**
     * A lit sphere at its true orientation: each disc pixel's surface normal is lit by the
     * real Sun direction (so phases are exact) and, where a texture exists, coloured by the
     * map at that point's body-fixed longitude/latitude from the IAU rotation model.
     *
     * <p>Saturn's rings are ray-traced in the same pass, exactly as the 3-D view does it: the
     * ring plane is normal to Saturn's real IAU pole, its radial colour and opacity come from
     * the same {@code saturn_ring.png} profile ({@link #RING_INNER}–{@link #RING_OUTER} Saturn
     * radii, the C ring through the A ring with the Cassini Division), and each pixel is
     * depth-composited against the globe — so the near side of the rings crosses in front of
     * the planet and the far side passes behind it. Saturn's shadow falls across the rings,
     * the rings' shadow falls on the globe, and when the Sun lights the face of the rings
     * turned away from us we see them dimly, by light filtering through.
     */
    private void drawSphere(Graphics2D g2, Obj o, double x, double y, double r, double[][] axes) {
        double[] right = axes[0], up = axes[1];
        double[] L = o.dir;                                   // observer → body
        double[] S = o.sunDir;
        double[] R = bodyRotation(o);                         // body-fixed → equatorial J2000, or null
        Tex tex = texture(o);
        int[] lut = (o.planet == 5 && o.moonOf < 0) ? ringLut() : null;
        double[] pole = lut != null ? SolarSystemSimulation.iauPoleEcliptic(5, host.simTimeSec()) : null;
        // Ring-plane geometry in the screen basis (planet radii): a view ray through (u, v) meets
        // the plane P·pole = 0 at depth zR (toward the observer) = (u R·p + v U·p) / (L·p).
        double rP = 0, uP = 0, lP = 0, sP = 0;
        boolean ringVisible = false, litFace = false;
        if (lut != null) {
            rP = dot(right, pole); uP = dot(up, pole); lP = dot(L, pole); sP = dot(S, pole);
            ringVisible = Math.abs(lP) > 1e-6;                // edge-on rings vanish
            litFace = Math.signum(sP) == Math.signum(-lP);    // Sun and observer on the same side
        }
        double half = lut != null ? RING_OUTER : 1.0;        // raster half-extent in planet radii
        int n = (int) Math.ceil(r * half) + 1;
        int size = Math.min(2 * n + 1, 2400);
        double scale = (size - 1) / 2.0 / (r * half) * r;    // px per planet radius (≈ r, capped)
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        int[] px = new int[size * size];
        double ambient = o.name.equals("Moon") ? 0.025 : 0.0;   // earthshine on the Moon's night side
        // Exposure: the albedo map is dim grey, but the sunlit Moon against a night sky is about
        // the brightest thing a display can show — expose it up (channels clamp at white).
        double gain = o.name.equals("Moon") ? 1.8 : 1.0;
        double c0 = (size - 1) / 2.0;
        // Ring brightness: the optically thick A/B rings stay bright on their sunlit face until
        // the Sun is within a few degrees of the ring plane (then fade to the crossing); the face
        // turned from the Sun shows only a faint glow of light filtering through.
        double sunElev = Math.min(1, Math.abs(sP) * 10);
        double ringLight = litFace ? 0.92 * Math.min(1, 0.1 + sunElev) : 0.25 * sunElev;
        for (int j = 0; j < size; j++) {
            double v = (c0 - j) / scale;
            for (int i = 0; i < size; i++) {
                double u = (i - c0) / scale;
                double q = u * u + v * v;
                // --- globe ---
                boolean sHit = q <= 1.0;
                int sColor = 0, sAlpha = 0;
                double w = 0;
                if (sHit) {
                    w = Math.sqrt(1 - q);
                    // Outward normal (ecliptic J2000): screen right/up + toward the observer (−L).
                    double nx = u * right[0] + v * up[0] - w * L[0];
                    double ny = u * right[1] + v * up[1] - w * L[1];
                    double nz = u * right[2] + v * up[2] - w * L[2];
                    double lit = Math.max(0, nx * S[0] + ny * S[1] + nz * S[2]);
                    double shade = Math.min(1, ambient + lit * (0.6 + 0.4 * Math.pow(w, 0.3))) * gain;
                    if (lut != null && lit > 0 && Math.abs(sP) > 1e-6) {
                        // Ring shadow: follow the ray toward the Sun to the ring plane.
                        double t = -(nx * pole[0] + ny * pole[1] + nz * pole[2]) / sP;
                        if (t > 0) {
                            double hx = nx + t * S[0], hy = ny + t * S[1], hz = nz + t * S[2];
                            int a = ringAlpha(lut, Math.sqrt(hx * hx + hy * hy + hz * hz));
                            shade *= 1.0 - (a / 255.0) * 0.8;
                        }
                    }
                    int base = o.rgb;
                    if (tex != null && R != null) {
                        double[] e = Astro.eclToEq(nx, ny, nz);
                        double bx = R[0] * e[0] + R[3] * e[1] + R[6] * e[2];   // Rᵀ·e
                        double by = R[1] * e[0] + R[4] * e[1] + R[7] * e[2];
                        double bz = R[2] * e[0] + R[5] * e[1] + R[8] * e[2];
                        double lon = Math.atan2(by, bx), lat = Math.asin(clamp(bz));
                        base = tex.sample(0.5 + lon / (2 * Math.PI), 0.5 - lat / Math.PI);
                    }
                    double edge = Math.min(1, (1 - Math.sqrt(q)) * scale * 1.5 + 0.2);   // anti-aliased limb
                    sAlpha = (int) (255 * Math.min(1, edge));
                    sColor = (clamp8(((base >> 16) & 0xFF) * shade) << 16) | (clamp8(((base >> 8) & 0xFF) * shade) << 8)
                            | clamp8((base & 0xFF) * shade);
                }
                // --- rings ---
                int rColor = 0, rAlpha = 0;
                double zR = 0;
                if (ringVisible) {
                    zR = (u * rP + v * uP) / lP;
                    double hx = u * right[0] + v * up[0] - zR * L[0];
                    double hy = u * right[1] + v * up[1] - zR * L[1];
                    double hz = u * right[2] + v * up[2] - zR * L[2];
                    double rad2 = hx * hx + hy * hy + hz * hz, rad = Math.sqrt(rad2);
                    if (rad >= RING_INNER && rad <= RING_OUTER) {
                        int lc = lut[(int) ((rad - RING_INNER) / (RING_OUTER - RING_INNER) * (lut.length - 1))];
                        rAlpha = (lc >>> 24) & 0xFF;
                        if (rAlpha > 0) {
                            double rs = ringLight;
                            double b = hx * S[0] + hy * S[1] + hz * S[2];      // Saturn's shadow on the rings
                            if (b < 0 && b * b - (rad2 - 1) > 0) rs *= 0.12;
                            rColor = (clamp8(((lc >> 16) & 0xFF) * rs) << 16) | (clamp8(((lc >> 8) & 0xFF) * rs) << 8)
                                    | clamp8((lc & 0xFF) * rs);
                        }
                    }
                }
                // --- depth composite (zR and w both measure toward the observer) ---
                int out;
                if (sHit && (rAlpha == 0 || w >= zR)) out = (sAlpha << 24) | sColor;
                else if (sHit) {
                    int a = rAlpha;
                    int cr = (((rColor >> 16) & 0xFF) * a + ((sColor >> 16) & 0xFF) * (255 - a)) / 255;
                    int cg = (((rColor >> 8) & 0xFF) * a + ((sColor >> 8) & 0xFF) * (255 - a)) / 255;
                    int cb = ((rColor & 0xFF) * a + (sColor & 0xFF) * (255 - a)) / 255;
                    out = (sAlpha << 24) | (cr << 16) | (cg << 8) | cb;
                } else if (rAlpha > 0) out = (rAlpha << 24) | rColor;
                else continue;
                px[j * size + i] = out;
            }
        }
        img.setRGB(0, 0, size, size, px, 0, size);
        g2.drawImage(img, (int) Math.round(x - c0), (int) Math.round(y - c0), null);
    }

    /** Saturn's ring extent in planet radii — the span of {@code saturn_ring.png} (same as the 3-D view). */
    private static final double RING_INNER = 1.11, RING_OUTER = 2.27;
    private int[] ringLut;
    private boolean ringLutTried;

    /** Radial ARGB profile of the rings (inner → outer; alpha = optical depth), as the 3-D view loads it. */
    private int[] ringLut() {
        if (ringLut != null || ringLutTried) return ringLut;
        ringLutTried = true;
        try {
            BufferedImage im = ImageIO.read(new File("assets/textures/saturn_ring.png"));
            if (im != null) ringLut = im.getRGB(0, im.getHeight() / 2, im.getWidth(), 1, null, 0, im.getWidth());
        } catch (Exception ignored) { }
        return ringLut;
    }

    private static int ringAlpha(int[] lut, double rad) {
        if (rad < RING_INNER || rad > RING_OUTER) return 0;
        return (lut[(int) ((rad - RING_INNER) / (RING_OUTER - RING_INNER) * (lut.length - 1))] >>> 24) & 0xFF;
    }

    /**
     * Body-fixed → equatorial-J2000 rotation (IAU WGCCRE), for texturing: the planets from the
     * sim's IAU model, the Moon from the IAU lunar model including its main nutation-like terms.
     */
    private double[] bodyRotation(Obj o) {
        double t = host.simTimeSec();
        double a0, d0, W;
        if (o.name.equals("Moon")) {
            double d = Astro.ttSeconds(t) / DAY, T = d / 36525.0;
            double E1 = Math.toRadians(125.045 - 0.0529921 * d), E2 = Math.toRadians(250.089 - 0.1059842 * d);
            double E3 = Math.toRadians(260.008 + 13.0120009 * d), E4 = Math.toRadians(176.625 + 13.3407154 * d);
            a0 = 269.9949 + 0.0031 * T - 3.8787 * Math.sin(E1) - 0.1204 * Math.sin(E2) + 0.0700 * Math.sin(E3) - 0.0172 * Math.sin(E4);
            d0 = 66.5392 + 0.0130 * T + 1.5419 * Math.cos(E1) + 0.0239 * Math.cos(E2) - 0.0278 * Math.cos(E3) + 0.0068 * Math.cos(E4);
            W = 38.3213 + 13.17635815 * d - 1.4e-12 * d * d + 3.5610 * Math.sin(E1) + 0.1208 * Math.sin(E2)
                    - 0.0642 * Math.sin(E3) + 0.0158 * Math.sin(E4);
        } else if (o.moonOf < 0 && o.planet >= 0 && o.planet < 8) {
            double[] pole = SolarSystemSimulation.iauPoleEcliptic(o.planet, t);
            double[] pe = Astro.eclToEq(pole[0], pole[1], pole[2]);
            a0 = Math.toDegrees(Math.atan2(pe[1], pe[0]));
            d0 = Math.toDegrees(Math.asin(clamp(pe[2])));
            W = SolarSystemSimulation.iauPrimeMeridianDeg(o.planet, t);
        } else return null;
        // R = Rz(a0 + 90°) · Rx(90° − d0) · Rz(W), as active rotations.
        double[] rz1 = activeZ(Math.toRadians(a0 + 90)), rx = activeX(Math.toRadians(90 - d0)), rz2 = activeZ(Math.toRadians(W));
        return Astro.mul(rz1, Astro.mul(rx, rz2));
    }

    private static double[] activeZ(double a) { double c = Math.cos(a), s = Math.sin(a); return new double[]{c, -s, 0, s, c, 0, 0, 0, 1}; }
    private static double[] activeX(double a) { double c = Math.cos(a), s = Math.sin(a); return new double[]{1, 0, 0, 0, c, -s, 0, s, c}; }

    /** A small equirectangular texture (0° longitude at the centre, east to the right). */
    private static final class Tex {
        final int w, h; final int[] rgb;
        Tex(int w, int h, int[] rgb) { this.w = w; this.h = h; this.rgb = rgb; }
        int sample(double u, double v) {
            u -= Math.floor(u);
            int x = Math.min(w - 1, (int) (u * w)), y = Math.max(0, Math.min(h - 1, (int) (v * h)));
            return rgb[y * w + x];
        }
    }
    private final java.util.Map<String, Tex> textures = new java.util.HashMap<>();

    private Tex texture(Obj o) {
        String key = o.name.equals("Moon") ? "moon" : (o.moonOf < 0 && o.planet >= 0 && o.planet < 8 && o.planet != 2)
                ? o.name.toLowerCase() : null;
        if (key == null) return null;
        if (textures.containsKey(key)) return textures.get(key);
        Tex t = null;
        try {
            File f = new File("assets/textures/" + key + "_diffuse.jpg");
            if (f.isFile()) {
                BufferedImage src = ImageIO.read(f);
                int w = Math.min(1024, src.getWidth()), h = Math.max(1, w / 2);
                BufferedImage s = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = s.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(src, 0, 0, w, h, null);
                g.dispose();
                t = new Tex(w, h, s.getRGB(0, 0, w, h, null, 0, w));
            }
        } catch (Exception ignored) { }
        textures.put(key, t);
        return t;
    }

    // ---------------------------------------------------------------- HUD & info

    private void drawHud(Graphics2D g2, double t) {
        Font f = g2.getFont().deriveFont(Font.PLAIN, 12f);
        g2.setFont(f);
        FontMetrics fm = g2.getFontMetrics();
        java.time.LocalDateTime dt = utc(t);
        double lstH = Math.toDegrees(wrap(lst)) / 15;
        String[] rows = {
                "SKY FROM EARTH",
                placeName,
                String.format("%.4f°%s  %.4f°%s", Math.abs(latDeg), latDeg >= 0 ? "N" : "S", Math.abs(lonDeg), lonDeg >= 0 ? "E" : "W"),
                localTime(t),
                dt == null ? "" : dt.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd  HH:mm:ss")) + " UTC",
                String.format("Local sidereal time %02d:%02d", (int) lstH, (int) ((lstH % 1) * 60)),
                String.format("Sun %+.1f°  •  %s", sunAltDeg, skyState()),
                String.format("Field %s  •  stars to mag %.1f", fovDeg >= 1 ? String.format("%.0f°", fovDeg) : String.format("%.1f′", fovDeg * 60), Math.min(limitMag, 6.5)),
        };
        int w = 0;
        for (String r : rows) w = Math.max(w, fm.stringWidth(r));
        int x = 14, y = 14, lh = fm.getHeight();
        g2.setColor(new Color(8, 14, 28, 190));
        g2.fillRoundRect(x, y, w + 24, lh * rows.length + 14, 12, 12);
        for (int k = 0; k < rows.length; k++) {
            g2.setColor(k == 0 ? new Color(255, 205, 110) : new Color(215, 228, 248));
            g2.setFont(k == 0 ? f.deriveFont(Font.BOLD) : f);
            g2.drawString(rows[k], x + 12, y + 7 + fm.getAscent() + k * lh);
        }
        String hint = "Drag to look around  •  Scroll to zoom  •  Click an object  •  L location  •  T now  •  C lines  •  N names  •  G/Q grids  •  Esc exit";
        g2.setFont(f);
        int hw = fm.stringWidth(hint) + 24;
        g2.setColor(new Color(8, 14, 28, 170));
        g2.fillRoundRect((W - hw) / 2, Hh - lh - 22, hw, lh + 10, 12, 12);
        g2.setColor(new Color(205, 220, 245));
        g2.drawString(hint, (W - hw) / 2 + 12, Hh - 17 - fm.getDescent());
    }

    /** The instant in the observer's own time zone, e.g. "2026-10-03  00:01:57 EDT (UTC−04:00)". */
    private String localTime(double t) {
        try {
            java.time.ZonedDateTime z = java.time.LocalDateTime.of(2000, 1, 1, 12, 0).toInstant(java.time.ZoneOffset.UTC)
                    .plusSeconds((long) Math.floor(t)).atZone(zone);
            String off = z.getOffset().getId().equals("Z") ? "UTC" : "UTC" + z.getOffset().getId().replace('-', '−');
            return z.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd  HH:mm:ss zzz")) + "  (" + off + ")";
        } catch (Exception e) { return ""; }
    }

    private String skyState() {
        double s = sunAltDeg;
        if (s > -0.833) return "day";
        if (s > -6) return "civil twilight";
        if (s > -12) return "nautical twilight";
        if (s > -18) return "astronomical twilight";
        return "night";
    }

    private void drawInfoPanel(Graphics2D g2, double t) {
        if (selectedName == null) return;
        Obj o = find(selectedName);
        if (o == null) { selectedName = null; return; }
        double[] tod = Astro.apply(PC, o.dir[0], o.dir[1], o.dir[2]);
        double ra = wrap(Math.atan2(tod[1], tod[0])), dec = Math.asin(clamp(tod[2]));
        String key = o.name + ":" + Math.floor(t / 1800) + ":" + latDeg + ":" + lonDeg;
        long now = System.nanoTime();
        // Recompute when the half-hour or the object changes — at most twice a second while
        // the clock races, since each search re-evaluates the sky ~170 times.
        if (!key.equals(riseSetKey) && (riseSet == null || !riseSetKey.startsWith(o.name + ":") || now - riseSetNanos > 500_000_000L)) {
            riseSetKey = key; riseSetNanos = now; riseSet = riseTransitSet(o.name, t);
        }
        List<String> rows = new ArrayList<>();
        rows.add(o.name);
        rows.add(o.kind);
        rows.add(String.format("Magnitude %.1f", o.mag));
        rows.add(String.format("Altitude %+.2f°   Azimuth %.2f°", Math.toDegrees(o.alt), Math.toDegrees(wrap(o.az))));
        rows.add(String.format("RA %s   Dec %s  (of date)", hms(ra), dms(dec)));
        rows.add(o.name.equals("Moon") || o.name.equals("Sun")
                ? String.format("Distance %,.0f km", o.distAU * AU_KM)
                : String.format("Distance %.4f AU  (light %s)", o.distAU, lightTime(o.distAU)));
        if (o.radiusKm > 0) {
            double ang = 2 * Math.toDegrees(Math.asin(Math.min(1, o.radiusKm / (o.distAU * AU_KM)))) * 3600;
            rows.add(ang >= 120 ? String.format("Apparent diameter %.1f′", ang / 60) : String.format("Apparent diameter %.1f″", ang));
        }
        if (!o.name.equals("Sun"))
            rows.add(String.format("Illuminated %.0f%%  (phase angle %.1f°)", 50 * (1 + Math.cos(o.phaseAngle)), Math.toDegrees(o.phaseAngle)));
        if (riseSet != null) {
            rows.add("Rise " + clock(riseSet[0]) + "   Transit " + clock(riseSet[1]) + "   Set " + clock(riseSet[2])
                    + "  " + zone.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()));
        }
        Font f = g2.getFont().deriveFont(Font.PLAIN, 12f);
        g2.setFont(f);
        FontMetrics fm = g2.getFontMetrics();
        int w = 0;
        for (String r : rows) w = Math.max(w, fm.stringWidth(r));
        int lh = fm.getHeight(), x = W - w - 38, y = 14;
        g2.setColor(new Color(8, 14, 28, 200));
        g2.fillRoundRect(x, y, w + 24, lh * rows.size() + 14, 12, 12);
        for (int k = 0; k < rows.size(); k++) {
            g2.setColor(k == 0 ? new Color(255, 215, 130) : new Color(215, 228, 248));
            g2.setFont(k == 0 ? f.deriveFont(Font.BOLD, 13f) : f);
            g2.drawString(rows.get(k), x + 12, y + 7 + fm.getAscent() + k * lh);
        }
    }

    /**
     * Rise, transit and set (sim seconds, NaN if none) of a named object within ±12 h of
     * {@code t}: altitude scanned every 10 minutes, then the horizon crossings (at −0.833° for
     * the Sun and Moon's upper limb with refraction, −0.567° for points) refined by bisection.
     */
    private double[] riseTransitSet(String name, double t) {
        double h0 = Math.toRadians(name.equals("Sun") ? -0.833 : name.equals("Moon") ? 0.125 : -0.567);
        double step = 600, t0 = t - 12 * 3600;
        int n = (int) (24 * 3600 / step);
        double[] alts = new double[n + 1];
        for (int k = 0; k <= n; k++) alts[k] = trueAltitude(name, t0 + k * step);
        double rise = Double.NaN, set = Double.NaN, transit = Double.NaN, best = -9;
        for (int k = 0; k < n; k++) {
            if (alts[k] > best) { best = alts[k]; transit = t0 + k * step; }
            boolean a = alts[k] >= h0, b = alts[k + 1] >= h0;
            if (a != b) {
                double lo = t0 + k * step, hi = lo + step;
                for (int it = 0; it < 12; it++) {
                    double mid = (lo + hi) / 2;
                    boolean m = trueAltitude(name, mid) >= h0;
                    if (m == a) lo = mid; else hi = mid;
                }
                if (!a && Double.isNaN(rise)) rise = lo;
                if (a && Double.isNaN(set)) set = lo;
            }
        }
        if (alts[n] > best) transit = t0 + n * step;
        return new double[]{rise, transit, set};
    }

    /** True (unrefracted) geocentric-ish altitude of a named object at time {@code t}. */
    private double trueAltitude(String name, double t) {
        // Reuse the full pipeline for one object without disturbing this frame's state.
        double[] sH = H, sPC = PC, sB = beta, sO = obsEcl;
        double sL = lst, sSun = sunAltDeg, sSunAz = sunAzRad, sMoon = moonAltDeg, sIll = moonIllum, sLim = limitMag;
        double sT = frameT, sVis = sunVisible;
        List<Obj> saved = new ArrayList<>(objs);
        computeFrame(t);
        Obj o = find(name);
        double alt = o == null ? -1 : o.alt - refraction(o.alt);
        H = sH; PC = sPC; beta = sB; obsEcl = sO; lst = sL; sunAltDeg = sSun; sunAzRad = sSunAz;
        moonAltDeg = sMoon; moonIllum = sIll; limitMag = sLim; frameT = sT; sunVisible = sVis;
        objs.clear(); objs.addAll(saved);
        return alt;
    }

    private Obj find(String name) {
        for (Obj o : objs) if (o.name.equals(name)) return o;
        return null;
    }

    // ---------------------------------------------------------------- input

    void drag(int dx, int dy) {
        double radPerPx = 1.0 / Math.max(1, scale);
        viewAz = wrap(viewAz - dx * radPerPx);
        viewAlt = Math.max(Math.toRadians(-89.9), Math.min(Math.toRadians(89.9), viewAlt + dy * radPerPx));
        host.repaint();
    }

    void zoom(double wheel) {
        fovDeg = Math.max(FOV_MIN, Math.min(FOV_MAX, fovDeg * (wheel < 0 ? 1 / 1.15 : 1.15)));
        host.repaint();
    }

    /** Select the solar-system object or bright star nearest a click. */
    void click(int x, int y) {
        Obj best = null;
        double bestD = 14;
        for (Obj o : objs) {
            if (!o.onScreen) continue;
            double d = Math.hypot(o.sx - x, o.sy - y) - o.rPx;
            if (d < bestD) { bestD = d; best = o; }
        }
        selectedName = best == null ? null : best.name;
        riseSetKey = "";
        // Clicking an object you can see re-centres on it.
        if (best != null) {
            viewAz = best.az;
            viewAlt = best.alt;
        }
        host.repaint();
    }

    /**
     * Apparent (refracted) {altitude, azimuth} in degrees of a named solar-system object at
     * sim time {@code t} from the current location, or null if unknown. For tests / tooling.
     */
    double[] altAzDeg(String name, double t) {
        computeFrame(t);
        Obj o = find(name);
        return o == null ? null : new double[]{Math.toDegrees(o.alt), Math.toDegrees(wrap(o.az))};
    }

    /** Apparent (refracted) {altitude, azimuth} in degrees of BSC star {@code hr} at sim time {@code t}. */
    double[] starAltAzDeg(int hr, double t) {
        computeFrame(t);
        StarCatalog cat = StarCatalog.get();
        for (int i = 0; i < cat.n; i++) {
            if (cat.hr[i] != hr) continue;
            double[] v = eclToEnuApparent(aberrate(cat.directionAt(i, t / (365.25 * DAY))));
            return new double[]{Math.toDegrees(Math.asin(clamp(v[2]))), Math.toDegrees(wrap(Math.atan2(v[0], v[1])))};
        }
        return null;
    }

    void setFov(double deg) { fovDeg = Math.max(FOV_MIN, Math.min(FOV_MAX, deg)); }

    /** Point the view at an azimuth / altitude (degrees). */
    void look(double azDeg, double altDeg) { viewAz = Math.toRadians(azDeg); viewAlt = Math.toRadians(altDeg); }

    /** Point the view at a named object (Sun, Moon, a planet or moon). Returns false if unknown. */
    boolean lookAt(String name) {
        computeFrame(host.simTimeSec());
        for (Obj o : objs) {
            if (o.name.equalsIgnoreCase(name)) {
                viewAz = o.az; viewAlt = o.alt; selectedName = o.name; riseSetKey = "";
                host.repaint();
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- lesson API

    /** The view {azimuth°, altitude°, field°}. */
    double[] view() { return new double[]{Math.toDegrees(viewAz), Math.toDegrees(viewAlt), fovDeg}; }

    private void ensureFrame(double t) { if (t != frameT || H == null) computeFrame(t); }

    /**
     * What the observer sees of a named Sun / Moon / planet / moon, or a bright star by its
     * proper name, at sim time {@code t}: {apparent altitude°, azimuth°, RA (h, of date),
     * Dec (°, of date), V magnitude, apparent diameter (″), distance (AU; NaN for a star),
     * B−V (NaN unless a star)}; null if unknown.
     */
    double[] bodyInfo(String name, double t) {
        ensureFrame(t);
        Obj o = find(name);
        if (o != null) {
            double[] tod = Astro.apply(PC, o.dir[0], o.dir[1], o.dir[2]);
            double ang = o.radiusKm > 0 ? 2 * Math.toDegrees(Math.asin(Math.min(1, o.radiusKm / (o.distAU * AU_KM)))) * 3600 : 0;
            return new double[]{Math.toDegrees(o.alt), Math.toDegrees(wrap(o.az)),
                    Math.toDegrees(wrap(Math.atan2(tod[1], tod[0]))) / 15, Math.toDegrees(Math.asin(clamp(tod[2]))),
                    o.mag, ang, o.distAU, Double.NaN};
        }
        StarCatalog cat = StarCatalog.get();
        if (cat == null) return null;
        for (int i = 0; i < cat.n; i++) {
            if (!cat.properName[i].equalsIgnoreCase(name)) continue;
            double[] d = aberrate(cat.directionAt(i, t / (365.25 * DAY)));
            double[] v = eclToEnuApparent(d);
            double[] tod = Astro.apply(PC, d[0], d[1], d[2]);
            return new double[]{Math.toDegrees(Math.asin(clamp(v[2]))), Math.toDegrees(wrap(Math.atan2(v[0], v[1]))),
                    Math.toDegrees(wrap(Math.atan2(tod[1], tod[0]))) / 15, Math.toDegrees(Math.asin(clamp(tod[2]))),
                    cat.vmag[i], 0, Double.NaN, cat.bv[i]};
        }
        return null;
    }

    /** Apparent ENU unit vector (east, north, up; refraction included) of a body or star, or null. */
    double[] enuOf(String name, double t) {
        double[] b = bodyInfo(name, t);
        return b == null ? null : enuDir(Math.toRadians(b[1]), Math.toRadians(b[0]));
    }

    /** Apparent ENU unit vector of an equatorial (of date) direction, refraction included. */
    double[] enuOfRaDec(double raHours, double decDeg, double t) {
        ensureFrame(t);
        double ra = Math.toRadians(raHours * 15), dec = Math.toRadians(decDeg);
        double[] e = {Math.cos(dec) * Math.cos(ra), Math.cos(dec) * Math.sin(ra), Math.sin(dec)};
        return eclToEnuApparent(applyT(PC, e));
    }

    /**
     * Screen position of an ENU direction (any length) as last painted:
     * {x, y, 1, pixels per radian there}; false when it is behind the viewer.
     */
    boolean projectEnu(double[] v, double[] out) {
        if (camF == null) return false;
        double[] s = project(unit(v));
        if (s == null) return false;
        out[0] = s[0]; out[1] = s[1]; out[2] = 1; out[3] = scale * s[2];
        return true;
    }

    /** Local apparent sidereal time (hours) at sim time {@code t}. */
    double lstHours(double t) { ensureFrame(t); return Math.toDegrees(wrap(lst)) / 15; }
    double limitMag(double t) { ensureFrame(t); limitMag = limitingMagnitude(); return limitMag; }
    double sunAltDeg(double t) { ensureFrame(t); return sunAltDeg; }
    /** Fraction of the Sun's disc hidden by the Moon (0 = none, 1 = total). */
    double eclipsed(double t) { ensureFrame(t); return 1 - sunVisible; }
    /** {rise, transit, set} (sim seconds, NaN = none) of a solar-system object within ±12 h of {@code t}. */
    double[] riseTransitSetOf(String name, double t) { ensureFrame(t); return riseTransitSet(name, t); }
    String localTimeOf(double t) { return localTime(t); }
    String clockOf(double t) { return clock(t); }

    // ---------------------------------------------------------------- AI assistant

    private static final String[] COMPASS = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
    private static String compass(double azDeg) { return COMPASS[(int) Math.round(((azDeg % 360) + 360) % 360 / 22.5) % 16]; }

    /**
     * A plain-text description of the sky from the observer's place at sim time {@code t}, for the
     * AI assistant: place and time, the Sun and the state of the sky, the Moon, every planet, the
     * brightest stars above the horizon and, when {@code onScreen}, where the view points and
     * what is in it.
     */
    String aiDescribe(double t, boolean onScreen) {
        computeFrame(t);
        StringBuilder b = new StringBuilder(2048);
        b.append("Observer: ").append(placeName).append(String.format(" (latitude %.4f° %s, longitude %.4f° %s)",
                Math.abs(latDeg), latDeg >= 0 ? "N" : "S", Math.abs(lonDeg), lonDeg >= 0 ? "E" : "W")).append('\n');
        b.append("Local time: ").append(localTime(t)).append('\n');
        double lstH = Math.toDegrees(wrap(lst)) / 15;
        b.append(String.format("Local sidereal time: %02dh%02dm (RA on the meridian)%n", (int) lstH, (int) ((lstH % 1) * 60)));
        Obj sun = objs.get(0), moon = objs.get(1);
        b.append(String.format("Sun: altitude %+.1f°, azimuth %.0f° (%s); sky: %s; faintest stars visible about magnitude %.1f%n",
                sunAltDeg, Math.toDegrees(wrap(sun.az)), compass(Math.toDegrees(sun.az)), skyState(), Math.min(limitMag, 6.5)));
        if (sunVisible < 0.999) b.append(String.format("SOLAR ECLIPSE in progress: %.1f%% of the Sun is covered%n", 100 * (1 - sunVisible)));
        // Moon: illuminated fraction, and waxing / waning from its elongation east or west of the Sun.
        double ill = (1 + Math.cos(moon.phaseAngle)) / 2;
        double lamM = Math.atan2(moon.dir[1], moon.dir[0]), lamS = Math.atan2(sun.dir[1], sun.dir[0]);
        double el = Math.toDegrees(Math.IEEEremainder(lamM - lamS, 2 * Math.PI));
        String phase = ill < 0.03 ? "new" : ill > 0.97 ? "full" : (el > 0 ? "waxing " : "waning ")
                + (ill < 0.47 ? "crescent" : ill > 0.53 ? "gibbous" : "half (quarter)");
        b.append(String.format("Moon: %s, %.0f%% lit; altitude %+.1f°, azimuth %.0f° (%s)%s%n", phase, 100 * ill,
                Math.toDegrees(moon.alt), Math.toDegrees(wrap(moon.az)), compass(Math.toDegrees(moon.az)),
                moon.alt > 0 ? "" : " - below the horizon"));
        b.append("Planets (apparent position from here):\n");
        for (Obj o : objs) {
            if (o.planet < 0 || o.moonOf >= 0 || o.name.equals("Sun") || o.name.equals("Moon")) continue;
            if (o.planet > 8) continue;      // Ceres, Haumea, Eris: skip in the summary
            double[] tod = Astro.apply(PC, o.dir[0], o.dir[1], o.dir[2]);
            double ra = wrap(Math.atan2(tod[1], tod[0])), dec = Math.asin(clamp(tod[2]));
            double alt = Math.toDegrees(o.alt);
            b.append(String.format("  %-8s mag %+.1f  altitude %+5.1f°  azimuth %3.0f° (%-3s)  RA %s  Dec %s  %.2f AU%s%n",
                    o.name, o.mag, alt, Math.toDegrees(wrap(o.az)), compass(Math.toDegrees(o.az)), hms(ra), dms(dec), o.distAU,
                    alt < 0 ? "  [below horizon]" : o.mag + extinction(o.alt) > limitMag ? "  [up, but too faint to see by eye now]" : "  [visible]"));
        }
        StarCatalog cat = StarCatalog.get();
        if (cat != null) {
            double years = t / (365.25 * DAY);
            List<Object[]> up = new ArrayList<>();
            for (int i = 0; i < cat.n; i++) {
                if (cat.vmag[i] > 2.0 || cat.properName[i].isEmpty()) continue;
                double[] v = eclToEnuApparent(aberrate(cat.directionAt(i, years)));
                double alt = Math.toDegrees(Math.asin(clamp(v[2])));
                if (alt < 0) continue;
                up.add(new Object[]{cat.vmag[i], cat.properName[i], alt, Math.toDegrees(wrap(Math.atan2(v[0], v[1])))});
            }
            up.sort(Comparator.comparingDouble(o -> (Double) o[0]));
            b.append("Brightest named stars above the horizon:");
            for (int k = 0; k < Math.min(14, up.size()); k++) {
                Object[] o = up.get(k);
                b.append(String.format(" %s (mag %.1f, alt %.0f°, %s);", o[1], (Double) o[0], (Double) o[2], compass((Double) o[3])));
            }
            b.append('\n');
        }
        if (onScreen && camF != null) {
            b.append(String.format("View: looking toward azimuth %.0f° (%s) at altitude %.0f°, field %s wide, %s up%n",
                    Math.toDegrees(wrap(viewAz)), compass(Math.toDegrees(viewAz)), Math.toDegrees(viewAlt),
                    fovDeg >= 1 ? String.format("%.0f°", fovDeg) : String.format("%.1f′", fovDeg * 60),
                    northUp ? "celestial north" : "the zenith"));
            b.append("Shown: ").append(showAltAzGrid ? "alt-az grid, " : "").append(showEqGrid ? "RA/Dec grid, " : "")
             .append(showConstLines ? "constellation lines, " : "").append(showGround ? "ground" : "no ground").append('\n');
            // Everything bright enough to see that projects inside the screen, brightest first.
            List<Object[]> seen = new ArrayList<>();          // {magnitude, label}
            for (Obj o : objs) {
                if (showGround && o.alt < 0) continue;
                if (!o.name.equals("Sun") && o.mag + extinction(o.alt) > limitMag) continue;
                double[] sp = project(enuDir(o.az, o.alt));
                if (sp == null || sp[0] < 0 || sp[1] < 0 || sp[0] > W || sp[1] > Hh) continue;
                seen.add(new Object[]{o.mag, o.name + String.format(" (%s, mag %.1f)", o.kind, o.mag)});
            }
            if (cat != null) {
                double years = t / (365.25 * DAY);
                java.util.Set<String> named = new java.util.HashSet<>();
                for (int i = 0; i < cat.n; i++) {
                    if (cat.properName[i].isEmpty() || cat.vmag[i] > limitMag || !named.add(cat.properName[i])) continue;
                    double[] v = eclToEnuApparent(aberrate(cat.directionAt(i, years)));
                    if (showGround && v[2] < 0) continue;
                    double[] sp = project(v);
                    if (sp == null || sp[0] < 0 || sp[1] < 0 || sp[0] > W || sp[1] > Hh) continue;
                    seen.add(new Object[]{cat.vmag[i], cat.properName[i] + String.format(" (star, mag %.1f)", cat.vmag[i])});
                }
            }
            seen.sort(Comparator.comparingDouble(o -> (Double) o[0]));
            List<String> inView = new ArrayList<>();
            for (int k = 0; k < Math.min(20, seen.size()); k++) inView.add((String) seen.get(k)[1]);
            b.append("In the field of view now (brightest first): ").append(inView.isEmpty() ? "nothing bright" : String.join(", ", inView)).append('\n');
            if (selectedName != null) b.append("Selected (clicked) object: ").append(selectedName).append('\n');
        }
        return b.toString();
    }

    /** Everything a lesson may change, to put back afterwards. */
    Object[] snapshot() {
        return new Object[]{latDeg, lonDeg, placeName, zone, viewAz, viewAlt, fovDeg, showConstLines, showConstNames,
                showStarNames, showAltAzGrid, showEqGrid, showEcliptic, showGround, showAtmosphere, selectedName,
                lessonMode, limitOverride, apertureMm, starFilter, northUp};
    }
    void restore(Object[] s) {
        int k = 0;
        latDeg = (Double) s[k++]; lonDeg = (Double) s[k++]; placeName = (String) s[k++]; zone = (java.time.ZoneId) s[k++];
        viewAz = (Double) s[k++]; viewAlt = (Double) s[k++]; fovDeg = (Double) s[k++];
        showConstLines = (Boolean) s[k++]; showConstNames = (Boolean) s[k++]; showStarNames = (Boolean) s[k++];
        showAltAzGrid = (Boolean) s[k++]; showEqGrid = (Boolean) s[k++]; showEcliptic = (Boolean) s[k++];
        showGround = (Boolean) s[k++]; showAtmosphere = (Boolean) s[k++]; selectedName = (String) s[k++];
        lessonMode = (Boolean) s[k++]; limitOverride = (Double) s[k++]; apertureMm = (Double) s[k++];
        starFilter = (java.util.function.DoublePredicate) s[k++];
        northUp = (Boolean) s[k++];
        bgKey = ""; riseSetKey = ""; frameT = Double.NaN;
    }

    /** Sky-mode keys; returns true if consumed. Esc is handled by the host (it leaves the mode). */
    boolean handleKey(int code) {
        double step = Math.toRadians(fovDeg / 12);
        switch (code) {
            case KeyEvent.VK_LEFT -> viewAz = wrap(viewAz - step);
            case KeyEvent.VK_RIGHT -> viewAz = wrap(viewAz + step);
            case KeyEvent.VK_UP -> viewAlt = Math.min(Math.toRadians(89.9), viewAlt + step);
            case KeyEvent.VK_DOWN -> viewAlt = Math.max(Math.toRadians(-89.9), viewAlt - step);
            case KeyEvent.VK_EQUALS, KeyEvent.VK_PLUS, KeyEvent.VK_ADD -> zoom(-1);
            case KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT -> zoom(1);
            case KeyEvent.VK_C -> showConstLines = !showConstLines;
            case KeyEvent.VK_N -> { showConstNames = !showConstNames; showStarNames = showConstNames; }
            case KeyEvent.VK_G -> showAltAzGrid = !showAltAzGrid;
            case KeyEvent.VK_Q -> showEqGrid = !showEqGrid;
            case KeyEvent.VK_E -> showEcliptic = !showEcliptic;
            case KeyEvent.VK_F -> showGround = !showGround;
            case KeyEvent.VK_A -> showAtmosphere = !showAtmosphere;
            case KeyEvent.VK_L -> showLocationDialog();
            case KeyEvent.VK_T -> host.goToNow();
            default -> { return false; }
        }
        host.repaint();
        return true;
    }

    // ---------------------------------------------------------------- location

    private static final Object[][] PLACES = {
            {"Greenwich, UK", 51.4779, -0.0015, "Europe/London"}, {"New York, USA", 40.7128, -74.0060, "America/New_York"},
            {"Los Angeles, USA", 34.0522, -118.2437, "America/Los_Angeles"}, {"Toronto, Canada", 43.6532, -79.3832, "America/Toronto"},
            {"Mexico City, Mexico", 19.4326, -99.1332, "America/Mexico_City"}, {"São Paulo, Brazil", -23.5505, -46.6333, "America/Sao_Paulo"},
            {"Buenos Aires, Argentina", -34.6037, -58.3816, "America/Argentina/Buenos_Aires"}, {"Reykjavík, Iceland", 64.1466, -21.9426, "Atlantic/Reykjavik"},
            {"Paris, France", 48.8566, 2.3522, "Europe/Paris"}, {"Berlin, Germany", 52.5200, 13.4050, "Europe/Berlin"},
            {"Cairo, Egypt", 30.0444, 31.2357, "Africa/Cairo"}, {"Nairobi, Kenya", -1.2921, 36.8219, "Africa/Nairobi"},
            {"Cape Town, South Africa", -33.9249, 18.4241, "Africa/Johannesburg"}, {"Moscow, Russia", 55.7558, 37.6173, "Europe/Moscow"},
            {"Mumbai, India", 19.0760, 72.8777, "Asia/Kolkata"}, {"Beijing, China", 39.9042, 116.4074, "Asia/Shanghai"},
            {"Tokyo, Japan", 35.6762, 139.6503, "Asia/Tokyo"}, {"Singapore", 1.3521, 103.8198, "Asia/Singapore"},
            {"Sydney, Australia", -33.8688, 151.2093, "Australia/Sydney"}, {"Auckland, New Zealand", -36.8485, 174.7633, "Pacific/Auckland"},
            {"Mauna Kea Observatories, Hawaiʻi", 19.8207, -155.4681, "Pacific/Honolulu"}, {"Paranal Observatory, Chile", -24.6272, -70.4042, "America/Santiago"},
            {"South Pole", -90.0, 0.0, "Antarctica/McMurdo"}, {"North Pole", 90.0, 0.0, "UTC"},
    };

    /** Set the observer's place (e.g. from the Earth Explorer's map centre). */
    void setLocation(String name, double lat, double lon) { setLocation(name, lat, lon, true); }

    void setLocation(String name, double lat, double lon, boolean remember) {
        setLocation(name, lat, lon, zoneFor(name, lon), remember);
    }

    /** A known place's zone; otherwise the computer's zone if the longitude fits it, else the nautical zone. */
    private static java.time.ZoneId zoneFor(String name, double lon) {
        for (Object[] p : PLACES) if (p[0].equals(name)) return java.time.ZoneId.of((String) p[3]);
        java.time.ZoneId sys = java.time.ZoneId.systemDefault();
        int sysHours = sys.getRules().getStandardOffset(java.time.Instant.now()).getTotalSeconds() / 3600;
        int solar = (int) Math.round(lon / 15.0);
        return Math.abs(sysHours - solar) <= 1 ? sys : java.time.ZoneOffset.ofHours(Math.max(-12, Math.min(14, solar)));
    }

    void setLocation(String name, double lat, double lon, java.time.ZoneId tz, boolean remember) {
        zone = tz;
        placeName = name;
        latDeg = Math.max(-90, Math.min(90, lat));
        lonDeg = ((lon + 180) % 360 + 360) % 360 - 180;
        bgKey = ""; riseSetKey = "";
        if (remember) saveLocation();
        host.repaint();
    }

    void showLocationDialog() {
        JComboBox<String> preset = new JComboBox<>();
        preset.addItem("(custom)");
        for (Object[] p : PLACES) preset.addItem((String) p[0]);
        JTextField name = new JTextField(placeName, 18);
        JTextField lat = new JTextField(String.format("%.4f", latDeg), 9);
        JTextField lon = new JTextField(String.format("%.4f", lonDeg), 9);
        JTextField tz = new JTextField(zone.getId(), 18);
        preset.addActionListener(_ -> {
            int k = preset.getSelectedIndex() - 1;
            if (k < 0) return;
            name.setText((String) PLACES[k][0]);
            lat.setText(String.valueOf(PLACES[k][1]));
            lon.setText(String.valueOf(PLACES[k][2]));
            tz.setText((String) PLACES[k][3]);
        });
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4); c.anchor = GridBagConstraints.WEST;
        Object[][] rows = {{"Preset", preset}, {"Name", name}, {"Latitude (° north +)", lat}, {"Longitude (° east +)", lon},
                {"Time zone (e.g. America/Toronto, UTC+9)", tz}};
        for (int r = 0; r < rows.length; r++) {
            c.gridx = 0; c.gridy = r; p.add(new JLabel((String) rows[r][0]), c);
            c.gridx = 1; p.add((Component) rows[r][1], c);
        }
        int ok = JOptionPane.showConfirmDialog(host.component(), p, "Observer location", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;
        try {
            double la = Double.parseDouble(lat.getText().trim()), lo = Double.parseDouble(lon.getText().trim());
            java.time.ZoneId z;
            try { z = java.time.ZoneId.of(tz.getText().trim()); }
            catch (Exception e) { z = zoneFor("", lo); }
            setLocation(name.getText().trim().isEmpty() ? "Custom location" : name.getText().trim(), la, lo, z, true);
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(host.component(), "Latitude and longitude must be numbers in degrees.",
                    "Observer location", JOptionPane.WARNING_MESSAGE);
        }
    }

    private static Path settingsFile() {
        return Path.of(System.getProperty("user.home", "."), ".astronomy-suite", "sky.properties");
    }

    private void loadLocation() {
        try {
            Path f = settingsFile();
            if (!Files.isRegularFile(f)) return;
            Properties p = new Properties();
            try (var in = Files.newBufferedReader(f)) { p.load(in); }
            latDeg = Double.parseDouble(p.getProperty("lat", String.valueOf(latDeg)));
            lonDeg = Double.parseDouble(p.getProperty("lon", String.valueOf(lonDeg)));
            placeName = p.getProperty("name", placeName);
            try { zone = java.time.ZoneId.of(p.getProperty("zone", zone.getId())); } catch (Exception ignored) { }
        } catch (Exception ignored) { }
    }

    private void saveLocation() {
        try {
            Path f = settingsFile();
            Files.createDirectories(f.getParent());
            Properties p = new Properties();
            p.setProperty("lat", String.valueOf(latDeg));
            p.setProperty("lon", String.valueOf(lonDeg));
            p.setProperty("name", placeName);
            p.setProperty("zone", zone.getId());
            try (var out = Files.newBufferedWriter(f)) { p.store(out, "Astronomy Suite - Sky from Earth observer"); }
        } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- photometry tables

    /** Visual magnitude of planet / dwarf planet {@code i} (Astronomical Almanac formulae; H for the dwarfs). */
    private double planetMagnitude(int i, double r, double delta, double phase, double[] los) {
        double d5 = 5 * Math.log10(r * delta), p = Math.toDegrees(phase);
        switch (i) {
            case 0: return -0.42 + d5 + 0.0380 * p - 0.000273 * p * p + 0.000002 * p * p * p;
            case 1: return -4.40 + d5 + 0.0009 * p + 0.000239 * p * p - 0.00000065 * p * p * p;
            case 3: return -1.52 + d5 + 0.016 * p;
            case 4: return -9.40 + d5 + 0.005 * p;
            case 5: {
                // Rings: brightness depends on their opening angle B as seen from Earth.
                double[] pole = SolarSystemSimulation.iauPoleEcliptic(5, host.simTimeSec());
                double sinB = Math.abs(dot(pole, los));
                return -8.88 + d5 + 0.044 * p - 2.60 * sinB + 1.25 * sinB * sinB;
            }
            case 6: return -7.19 + d5;
            case 7: return -6.87 + d5;
            case 8: return -1.00 + d5;
            case 9: return 3.34 + d5 + 0.03 * p;     // Ceres
            case 10: return 0.2 + d5;                // Haumea
            case 11: return -1.2 + d5;               // Eris
            default: return 10;
        }
    }

    /** Absolute magnitude H of the planets' moons (JPL / IAU values, rounded). */
    private static final java.util.Map<String, Double> MOON_H = java.util.Map.ofEntries(
            java.util.Map.entry("Phobos", 11.8), java.util.Map.entry("Deimos", 12.9),
            java.util.Map.entry("Io", -1.68), java.util.Map.entry("Europa", -1.41),
            java.util.Map.entry("Ganymede", -2.09), java.util.Map.entry("Callisto", -1.05),
            java.util.Map.entry("Titan", -1.28), java.util.Map.entry("Enceladus", 2.1),
            java.util.Map.entry("Rhea", 0.1), java.util.Map.entry("Mimas", 3.3),
            java.util.Map.entry("Tethys", 0.7), java.util.Map.entry("Dione", 0.8),
            java.util.Map.entry("Iapetus", 1.5), java.util.Map.entry("Titania", 1.02),
            java.util.Map.entry("Oberon", 1.23), java.util.Map.entry("Umbriel", 2.10),
            java.util.Map.entry("Ariel", 1.45), java.util.Map.entry("Miranda", 3.49),
            java.util.Map.entry("Triton", -1.24), java.util.Map.entry("Charon", 1.0));

    /**
     * Minor-body photometry: asteroids {H}; comets {M1, K1} for the total magnitude
     * M1 + 5 log Δ + 2.5 K1 log r (typical published values; comet brightness is erratic).
     */
    private static final java.util.Map<String, double[]> MINOR_PHOT = java.util.Map.ofEntries(
            java.util.Map.entry("1P/Halley", new double[]{5.5, 3.2}), java.util.Map.entry("2P/Encke", new double[]{11.5, 2.4}),
            java.util.Map.entry("55P/Tempel-Tuttle", new double[]{9.0, 4.0}), java.util.Map.entry("109P/Swift-Tuttle", new double[]{4.0, 4.0}),
            java.util.Map.entry("C/1995 O1 Hale-Bopp", new double[]{-0.8, 3.0}),
            java.util.Map.entry("4 Vesta", new double[]{3.20}), java.util.Map.entry("2 Pallas", new double[]{4.13}),
            java.util.Map.entry("3 Juno", new double[]{5.33}), java.util.Map.entry("10 Hygiea", new double[]{5.43}),
            java.util.Map.entry("433 Eros", new double[]{10.4}), java.util.Map.entry("101955 Bennu", new double[]{20.2}),
            java.util.Map.entry("2060 Chiron", new double[]{5.9}), java.util.Map.entry("50000 Quaoar", new double[]{2.4}),
            java.util.Map.entry("90377 Sedna", new double[]{1.8}));

    /** Display tints for the planets and dwarf planets (sim order). */
    private static final int[] PLANET_RGB = {
            0xC9C4BC, 0xFFF4DA, 0x9CC4FF, 0xFFB08A, 0xFFE6C0, 0xF5E2B0, 0xC8F0F5, 0xA8C4FF, 0xE8DCCF,
            0xD0D4D8, 0xE8F0FF, 0xE0E6F0 };

    // ---------------------------------------------------------------- small helpers

    private static java.time.LocalDateTime utc(double t) {
        try {
            return java.time.LocalDateTime.ofInstant(java.time.LocalDateTime.of(2000, 1, 1, 12, 0)
                    .toInstant(java.time.ZoneOffset.UTC).plusSeconds((long) Math.floor(t)), java.time.ZoneOffset.UTC);
        } catch (Exception e) { return null; }
    }
    /** Clock time of sim instant {@code t} in the observer's zone. */
    private String clock(double t) {
        if (Double.isNaN(t)) return "—";
        try {
            return java.time.LocalDateTime.of(2000, 1, 1, 12, 0).toInstant(java.time.ZoneOffset.UTC)
                    .plusSeconds((long) Math.floor(t)).atZone(zone).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        } catch (Exception e) { return "—"; }
    }
    private static String lightTime(double au) {
        double s = au * 499.004784;
        return s < 120 ? String.format("%.1f s", s) : s < 7200 ? String.format("%.1f min", s / 60) : String.format("%.2f h", s / 3600);
    }
    private static String hms(double ra) {
        double h = Math.toDegrees(ra) / 15;
        int hh = (int) h, mm = (int) ((h - hh) * 60);
        double ss = ((h - hh) * 60 - mm) * 60;
        return String.format("%02dh%02dm%04.1fs", hh, mm, ss);
    }
    private static String dms(double dec) {
        double d = Math.abs(Math.toDegrees(dec));
        int dd = (int) d, mm = (int) ((d - dd) * 60);
        double ss = ((d - dd) * 60 - mm) * 60;
        return String.format("%s%02d°%02d′%02.0f″", dec < 0 ? "−" : "+", dd, mm, ss);
    }
    private static double wrap(double a) { a %= 2 * Math.PI; return a < 0 ? a + 2 * Math.PI : a; }
    private static double clamp(double x) { return Math.max(-1, Math.min(1, x)); }
    private static double clamp01(double x) { return Math.max(0, Math.min(1, x)); }
    private static int clamp8(double v) { return v < 0 ? 0 : v > 255 ? 255 : (int) v; }
    private static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    private static double norm(double[] a) { return Math.sqrt(dot(a, a)); }
    private static double[] sub(double[] a, double[] b) { return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
    private static double[] unit(double[] a) { double n = norm(a); return new double[]{a[0] / n, a[1] / n, a[2] / n}; }
    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }
    private static double[] transpose(double[] M) { return new double[]{M[0], M[3], M[6], M[1], M[4], M[7], M[2], M[5], M[8]}; }
    /** Mᵀ·v. */
    private static double[] applyT(double[] M, double[] v) {
        return new double[]{M[0] * v[0] + M[3] * v[1] + M[6] * v[2], M[1] * v[0] + M[4] * v[1] + M[7] * v[2], M[2] * v[0] + M[5] * v[1] + M[8] * v[2]};
    }
}
