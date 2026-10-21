import java.awt.*;
import java.awt.geom.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Lesson: The Sun Wobbles Too (AST221 Days 7 and 8). The Sun's real path about the
 * Solar-System barycentre from the N-body integration, as an astrometrist at 10 pc would see it;
 * its radial-velocity curve for any viewing inclination, and Jupiter's minimum mass from it;
 * real transits of Jupiter and Earth with their light curves; and the binary-star mass relations.
 */
final class WobbleLesson extends LessonKit {

    WobbleLesson(LessonStudio.Host host) { super(host); }

    static final String[] GIANTS = {"Jupiter", "Saturn", "Uranus", "Neptune"};
    static final double SPAN_YR = 30;
    double[][] sunPath;        // the Sun's position about the barycentre (m, world) over ±15 years
    double[][] sunVel;         // ... and velocity (m/s)
    double[] pathT;            // sample times (years from now)
    double t0;
    double incl = 90, azim;    // viewing direction: inclination from the ecliptic pole (°), azimuth (°)
    int transitBody;           // 0 = Jupiter, 1 = Earth
    double[] tn;               // transit observer direction (unit, world)
    double[] lcT, lcF;         // light curve: hours from mid-transit, relative flux
    double tMid;

    void sampleSun() {
        int n = 361;
        t0 = host.simTime();
        sunPath = new double[n][]; sunVel = new double[n][]; pathT = new double[n];
        for (int i = 0; i < n; i++) {
            pathT[i] = -SPAN_YR / 2 + SPAN_YR * i / (n - 1);
            double[] b = host.sunBaryAt(t0 + pathT[i] * YEAR);
            sunPath[i] = pos(b); sunVel[i] = vel(b);
        }
    }

    /** Unit vector toward a distant observer at inclination i from the ecliptic pole. */
    double[] observer() {
        double i = Math.toRadians(incl), a = Math.toRadians(azim);
        return v(Math.sin(i) * Math.cos(a), Math.sin(i) * Math.sin(a), Math.cos(i));
    }
    /** Radial velocity (m/s, + = receding) of a velocity v seen from direction n. */
    static double rv(double[] v, double[] n) { return -dot(v, n); }

    /** Area of overlap of two discs (radii R, r, centres d apart) as a fraction of the first one's area. */
    static double overlap(double R, double r, double d) {
        if (d >= R + r) return 0;
        if (d <= Math.abs(R - r)) return Math.min(R, r) * Math.min(R, r) / (R * R);
        double c1 = Math.acos(clamp((d * d + R * R - r * r) / (2 * d * R), -1, 1));
        double c2 = Math.acos(clamp((d * d + r * r - R * R) / (2 * d * r), -1, 1));
        double k = Math.sqrt(Math.max(0, (-d + R + r) * (d + R - r) * (d - R + r) * (d + R + r)));
        return (R * R * c1 + r * r * c2 - 0.5 * k) / (Math.PI * R * R);
    }

    String tBody() { return transitBody == 0 ? "Jupiter" : "Earth"; }
    double tRadius() { return transitBody == 0 ? 6.9911e7 : 6.371e6; }

    /** Set up a transit seen by an observer along the planet's direction a little later, and its light curve. */
    void setupTransit() {
        // The transit is centred a few seconds of playback ahead: 0.6 d at 2 h/s for Jupiter, 0.2 d at 1 h/s for Earth.
        double lead = transitBody == 0 ? 0.6 * DAY : 0.2 * DAY, half = transitBody == 0 ? 1.5 * DAY : 0.6 * DAY;
        tMid = host.simTime() + lead;
        tn = norm(pos(host.stateAt(tBody(), tMid)));
        int n = 241;
        lcT = new double[n]; lcF = new double[n];
        for (int i = 0; i < n; i++) {
            double t = tMid - half + 2 * half * i / (n - 1);
            lcT[i] = (t - tMid) / 3600;
            lcF[i] = 1 - transitDepthAt(t);
        }
    }
    /** Fraction of the Sun's disc the planet covers at time t, for the transit observer. */
    double transitDepthAt(double t) {
        double[] p = pos(host.stateAt(tBody(), t));
        if (dot(p, tn) <= 0) return 0;                       // behind the Sun
        double[] perp = sub(p, scaleV(tn, dot(p, tn)));
        return overlap(R_SUN, tRadius(), len(perp));
    }

    /** The binary checks are drawn in empty space well away from the Sun: centre (AU) and camera. */
    static final double[] BIN_C = {0, 4, 2};
    // Seen from the side away from the Sun (yaw 180°), so the Sun's glow never sits behind the pair.
    static final double[] BIN_CAM = {BIN_C[0] * AU, BIN_C[1] * AU, 0.75 * AU, 62, 180, BIN_C[2] * AU};

    /**
     * A schematic binary for the checks, orbiting its centre of mass (the cross) once every 6 s of
     * playback. Each star's orbit radius is in proportion to its velocity semi-amplitude K, as for a
     * circular orbit (r = K P / 2π). With {@code darkB} the companion is a faint unseen body and only
     * star A's orbit is drawn (the astrometric binary). Edge-on observers look along −y ("to Earth").
     */
    static void binaryScene(LessonStudio.R3 r, double t, double kA, double kB, boolean darkB) {
        double[] c = BIN_C;
        double scale = 0.42 / Math.max(kA + kB, 1e-9);
        double rA = kA * scale, rB = kB * scale, ph = 2 * Math.PI * t / 6;
        int n = 120;
        double[][] oa = new double[n][], ob = new double[n][];
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n;
            oa[i] = add(c, v(rA * Math.cos(a), rA * Math.sin(a), 0));
            ob[i] = add(c, v(rB * Math.cos(a), rB * Math.sin(a), 0));
        }
        r.polyline(oa, true, alpha(ACCENT, 170), 1.6f, true);
        if (!darkB) r.polyline(ob, true, alpha(ORANGE, 170), 1.6f, true);
        double[] A = add(c, v(rA * Math.cos(ph), rA * Math.sin(ph), 0));
        double[] B = add(c, v(-rB * Math.cos(ph), -rB * Math.sin(ph), 0));
        Color ca = new Color(170, 200, 255), cb = new Color(255, 190, 120);
        r.sphere(A, 0.035, 10, 20, (nx, ny, nz, mu) -> LessonStudio.scale(ca, 0.55 + 0.45 * mu));
        r.label(A, darkB ? "visible star" : "star A", ACCENT, 12f, 0, -18);
        if (darkB) {
            r.dot(B, 3, new Color(90, 90, 110));
            r.label(B, "dark companion", DIM, 11.5f, 70, -12);
        } else {
            r.sphere(B, 0.035, 10, 20, (nx, ny, nz, mu) -> LessonStudio.scale(cb, 0.55 + 0.45 * mu));
            r.label(B, "star B", ORANGE, 12f, 0, -18);
        }
        double k = 0.03;
        r.line(add(c, v(-k, 0, 0)), add(c, v(k, 0, 0)), Color.WHITE, 2f);
        r.line(add(c, v(0, -k, 0)), add(c, v(0, k, 0)), Color.WHITE, 2f);
        if (!darkB) {
            r.arrow(add(c, v(0, -0.32, 0)), add(c, v(0, -0.5, 0)), alpha(INK, 200), 2f);
            r.label(add(c, v(0, -0.5, 0)), "to Earth (edge-on)", INK, 11.5f, 0, 14);
        } else r.label(add(c, v(0, 0, 0)), "centre of mass", INK, 11.5f, -60, 14);
    }

    /** Synthetic double-lined binary for the checks: K_A, K_B (km/s), period (days), systemic velocity. */
    static void binaryInset(Graphics2D g, Rectangle b, double kA, double kB, double gamma) {
        Plot p = new Plot(b, 0, 2, false, -90, 90, false);
        p.axes(g, "phase (orbits)", "v (km/s)", new double[]{0, 1, 2}, new String[]{"0", "1", "2"},
                new double[]{-80, -40, 0, 40, 80}, new String[]{"−80", "−40", "0", "40", "80"});
        int n = 200;
        double[] x = new double[n], ya = new double[n], yb = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = 2.0 * i / (n - 1);
            ya[i] = gamma + kA * Math.sin(2 * Math.PI * x[i]);
            yb[i] = gamma - kB * Math.sin(2 * Math.PI * x[i]);
        }
        p.hline(g, gamma, alpha(Color.WHITE, 120));
        p.curve(g, x, ya, ACCENT, 2f);
        p.curve(g, x, yb, ORANGE, 2f);
        p.label(g, 0.25, gamma + kA, "star A", ACCENT, -10, -6);
        p.label(g, 0.25, gamma - kB, "star B", ORANGE, -10, 14);
    }

    LessonStudio.LessonDef build() {
        List<LessonStudio.Step> s = new ArrayList<>();

        s.add(step("The Sun orbits too", """
                Newton's third law: if the Sun pulls Jupiter, Jupiter pulls the Sun. Both orbit their
                common {k:centre of mass}, and with all the planets, the Sun circles the
                {k:Solar-System barycentre} (the white cross).

                This is the Sun's real path over 30 years, from the simulation's N-body integration.
                Jupiter dominates: it has a thousandth of the Sun's mass at 5.2 AU, which puts the
                barycentre about 1.07 solar radii from the Sun's centre, **just outside its
                surface**. Saturn's pull bends the 12-year loop into a pattern that repeats every
                20 years.

                > The view is drawn around the barycentre, so the Sun itself moves.
                """)
                .unit(R_SUN)
                .cam(() -> new double[]{0, 0, 3.2 * R_SUN, 8, 0})
                .enter(() -> {
                    host.showOnly(null); host.setOrbits(false, false); host.setTime(YEAR);
                    host.setBarycentric(true);
                    sampleSun();
                })
                .controls(c -> timeSlider(c, "years", YEAR, 3))
                .scene((r, hud, t) -> {
                    host.setBarycentric(true);
                    double[] now = pos(host.sunBaryAt(host.simTime()));
                    double[][] pts = new double[sunPath.length][];
                    for (int i = 0; i < pts.length; i++) pts[i] = scaleV(sub(sunPath[i], now), 1 / R_SUN);
                    r.polyline(pts, false, alpha(SUN_C, 170), 1.6f, false);
                    double[] ssb = scaleV(now, -1 / R_SUN);
                    double c = 0.12;
                    r.line(add(ssb, v(-c, 0, 0)), add(ssb, v(c, 0, 0)), Color.WHITE, 2.2f);
                    r.line(add(ssb, v(0, -c, 0)), add(ssb, v(0, c, 0)), Color.WHITE, 2.2f);
                    r.label(ssb, "barycentre", INK, 12f, 0, 18);
                    double d = len(now);
                    hud.title("The Sun's reflex orbit", SUN_C);
                    hud.row("date", host.dateString());
                    hud.row("Sun's centre to barycentre", String.format("%.2f solar radii  (%,.0f km)", d / R_SUN, d / 1e3), SUN_C);
                    hud.row("Sun's speed about it", String.format("%.1f m/s", len(vel(host.sunBaryAt(host.simTime())))));
                    hud.gap();
                    double gmS = host.gm("Sun");
                    for (String gnt : GIANTS) {
                        double lever = host.gm(gnt) / gmS * len(pos(host.state(gnt)));
                        hud.row(gnt + "'s lever  (m / M) r", String.format("%.2f solar radii", lever / R_SUN));
                    }
                    hud.formula("M(Sun) r(Sun) = Σ m r");
                }));

        s.add(step("Astrometry: the wobble from 10 parsecs", """
                Could astronomers on another star see the Sun wobble? From 10 pc, looking down on the
                Solar System, the Sun's path would span about **1 milliarcsecond**: one solar radius
                at 10 pc is 0.47 mas.

                A star that wobbles on the sky with no visible partner is an {k:astrometric binary}.
                Its period and the size of its orbit (the angle times the distance) give the mass
                through Kepler's third law. Gaia, with precisions of tens of µas for bright stars,
                is finding thousands, including giant planets around nearby stars.

                > The inset is the Sun's track as seen face-on from 10 pc over the 30 years.
                """)
                .unit(R_SUN)
                .cam(() -> new double[]{0, 0, 3.2 * R_SUN, 8, 0})
                .enter(() -> {
                    host.showOnly(null); host.setOrbits(false, false); host.setTime(YEAR);
                    host.setBarycentric(true);
                    if (sunPath == null) sampleSun();
                })
                .controls(c -> timeSlider(c, "years", YEAR, 3))
                .scene((r, hud, t) -> {
                    host.setBarycentric(true);
                    double[] now = pos(host.sunBaryAt(host.simTime()));
                    double[][] pts = new double[sunPath.length][];
                    for (int i = 0; i < pts.length; i++) pts[i] = scaleV(sub(sunPath[i], now), 1 / R_SUN);
                    r.polyline(pts, false, alpha(SUN_C, 170), 1.6f, false);
                    double masPerM = 1 / (10 * PC) / ARCSEC * 1e3;
                    hud.title("Seen from 10 pc", SUN_C);
                    hud.row("Sun's offset now", String.format("%.2f mas", len(now) * masPerM), SUN_C);
                    hud.row("one solar radius at 10 pc", String.format("%.2f mas", R_SUN * masPerM));
                    hud.row("Gaia's precision (bright stars)", "≈ 0.01–0.1 mas", GREEN);
                    hud.formula("angle = size / distance");
                    hud.inset("The Sun from 10 pc (mas)", 230, 230, (g, b) -> {
                        double cx = b.getCenterX(), cy = b.getCenterY(), sc = (b.width / 2.0 - 14) / 1.2;
                        g.setColor(new Color(255, 255, 255, 40));
                        g.draw(new Line2D.Double(b.x + 4, cy, b.getMaxX() - 4, cy));
                        g.draw(new Line2D.Double(cx, b.y + 4, cx, b.getMaxY() - 4));
                        Path2D.Double p = new Path2D.Double();
                        for (int i = 0; i < sunPath.length; i++) {
                            double x = cx + sunPath[i][0] * masPerM * sc, y = cy - sunPath[i][1] * masPerM * sc;
                            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
                        }
                        g.setColor(SUN_C);
                        g.setStroke(new BasicStroke(1.5f));
                        g.draw(p);
                        g.setColor(Color.WHITE);
                        g.fill(new Ellipse2D.Double(cx + now[0] * masPerM * sc - 4, cy - now[1] * masPerM * sc - 4, 8, 8));
                        g.setColor(DIM);
                        g.setFont(LessonStudio.uiFont(Font.PLAIN, 10.5f));
                        g.draw(new Line2D.Double(b.x + 8, b.getMaxY() - 8, b.x + 8 + 0.5 * sc, b.getMaxY() - 8));
                        g.drawString("0.5 mas", b.x + 10 + (float) (0.5 * sc), (float) b.getMaxY() - 4);
                    });
                }));

        s.add(step("Radial velocity: the Doppler wobble", """
                Far more planets have been found another way. As the Sun swings around the
                barycentre, it moves toward and away from a distant observer. Its light is
                {k:Doppler shifted}:

                $ Δλ / λ = v(r) / c

                where v(r) is the velocity along the line of sight. The inset is the Sun's real
                radial-velocity curve. Jupiter makes a 12-year swing of **±12.5 m/s** (a shift of
                0.00003 nm in Hα!). Saturn adds a smaller 30-year one.

                Only motion along the line of sight shows. Tilt the observer toward the pole of the
                orbits and the swing shrinks by sin i. Seen pole-on (i = 0°) there is no Doppler
                signal at all.

                > The green arrow is the Sun's velocity (greatly enlarged); the dashed line points to
                the observer.
                """)
                .unit(R_SUN)
                .cam(() -> new double[]{0, 0, 3.6 * R_SUN, 55, 0})
                .enter(() -> {
                    host.showOnly(null); host.setOrbits(false, false); host.setTime(0.5 * YEAR);
                    host.setBarycentric(true);
                    if (sunPath == null) sampleSun();
                })
                .controls(c -> {
                    c.slider("Inclination i", 0, 90, () -> incl, x -> incl = x, x -> String.format("%.0f°%s", x, x > 89.5 ? "  (edge-on)" : x < 0.5 ? "  (pole-on)" : ""));
                    c.slider("Observer's direction", 0, 360, () -> azim, x -> azim = x, x -> String.format("%.0f°", x));
                    timeSlider(c, "years", YEAR, 2);
                })
                .scene((r, hud, t) -> {
                    host.setBarycentric(true);
                    double[] b = host.sunBaryAt(host.simTime()), n = observer(), vv = vel(b);
                    double[] tip = scaleV(norm(vv), 1.2 + 0.1 * len(vv));      // starts at the limb so it is never hidden
                    r.arrow(scaleV(norm(vv), 1.0), tip, GREEN, 3f);
                    r.label(tip, String.format("%.1f m/s", len(vv)), GREEN, 12f, 0, -14);
                    r.line(v(0, 0, 0), scaleV(n, 3), alpha(ACCENT, 200), 1.6f, true);
                    r.label(scaleV(n, 3), "to the observer", ACCENT, 12f, 0, -14);
                    double[] jv = vel(host.state("Jupiter")), sv = vel(host.state("Saturn"));
                    double gs = host.gm("Sun");
                    double kJ = host.gm("Jupiter") / gs * len(jv), kS = host.gm("Saturn") / gs * len(sv);
                    double si = Math.sin(Math.toRadians(incl));
                    hud.title("Radial velocity of the Sun", ACCENT);
                    hud.row("radial velocity now", signed("%+.2f m/s", rv(vv, n)), ACCENT);
                    hud.row("Jupiter's share  K(J)", String.format("%.2f m/s × sin i = %.2f m/s", kJ, kJ * si));
                    hud.row("Saturn's share  K(S)", String.format("%.2f m/s × sin i = %.2f m/s", kS, kS * si));
                    hud.row("Hα shift for Jupiter's K", String.format("%.1e nm", 656.28 * kJ * si / C));
                    hud.formula("Δλ / λ = v(r) / c");
                    double now = (host.simTime() - t0) / YEAR;
                    hud.inset("The Sun's radial velocity (m/s)", 260, 180, (g, bx) -> {
                        Plot p = new Plot(bx, -SPAN_YR / 2, SPAN_YR / 2, false, -18, 18, false);
                        p.axes(g, "years from now", null, new double[]{-15, -10, -5, 0, 5, 10, 15}, new String[]{"−15", "−10", "−5", "0", "5", "10", "15"},
                                new double[]{-15, -10, -5, 0, 5, 10, 15}, new String[]{"−15", "−10", "−5", "0", "5", "10", "15"});
                        double[] ys = new double[sunVel.length];
                        for (int i = 0; i < ys.length; i++) ys[i] = rv(sunVel[i], n);
                        p.curve(g, pathT, ys, ACCENT, 2f);
                        if (now >= -SPAN_YR / 2 && now <= SPAN_YR / 2) p.vline(g, now, alpha(Color.WHITE, 150));
                    });
                }));

        s.add(step("Weighing a planet from the wobble", """
                For a planet of mass m on a circular orbit of period P around a star of mass M,
                momentum balance (M v(star) = m v(planet)) and Kepler's third law give

                $ m sin i = K × (P M² / 2πG)^⅓

                where K is the star's velocity semi-amplitude. Put in Jupiter's K = 12.5 m/s and
                P = 11.86 years and out comes 1.9 × 10²⁷ kg: **Jupiter's mass**. That is how the first
                planet around a Sun-like star, 51 Pegasi b, was weighed in 1995 (K = 56 m/s, P = 4.2
                days, m sin i = 0.46 Jupiter masses).

                Because only sin i is known, the result is a **minimum mass** unless the orbit's
                tilt is known, for example from a transit (next).
                """)
                .unit(R_SUN)
                .cam(() -> new double[]{0, 0, 3.6 * R_SUN, 55, 0})
                .enter(() -> {
                    host.showOnly(null); host.setOrbits(false, false); host.setTime(0.5 * YEAR);
                    host.setBarycentric(true);
                    if (sunPath == null) sampleSun();
                })
                .controls(c -> c.slider("Inclination i", 1, 90, () -> incl, x -> incl = x, x -> String.format("%.0f°", x)))
                .scene((r, hud, t) -> {
                    host.setBarycentric(true);
                    double gs = host.gm("Sun"), Msun = gs / G;
                    double kJ = host.gm("Jupiter") / gs * len(vel(host.state("Jupiter")));
                    double si = Math.sin(Math.toRadians(incl)), K = kJ * si, P = 11.862615 * YEAR;
                    double msini = K * Math.cbrt(P * Msun * Msun / (2 * Math.PI * G));
                    double mJ = host.gm("Jupiter") / G;
                    hud.title("Minimum mass from K and P", ACCENT);
                    hud.row("measured K (= 12.5 m/s × sin i)", String.format("%.2f m/s", K), ACCENT);
                    hud.row("period P", "11.86 years");
                    hud.row("m sin i", sci(msini, 2) + " kg", SUN_C);
                    hud.row("inferred if you assume i = 90°", String.format("%.2f Jupiter masses", msini / mJ));
                    hud.row("true mass", String.format("1.00 Jupiter mass  (i = %.0f°)", incl));
                    hud.formula("m sin i = K (P M² / 2πG)^⅓");
                }));

        s.add(step("Transits and eclipses", """
                If the orbit is seen almost exactly edge-on, the planet passes in front of its star
                once per orbit: a {k:transit}. The star dims by the fraction of its disc that is
                covered,

                $ depth = (R(planet) / R(star))²

                The observer here sits far away, exactly in line with the planet's real orbit at that
                moment. Jupiter blocks **1%** of the Sun for about 30 hours; Earth only 0.008% for 13
                hours, which is why Earth-size planets need space telescopes like Kepler and TESS.

                For two stars ({k:eclipsing binaries}) there are two dips per orbit, and the deeper one
                is when the **hotter** star is hidden.

                > Time runs at a couple of hours per second. Watch the inset.
                """)
                .unit(AU)
                .cam(() -> transitBody == 0 ? new double[]{tn[0] * 2.6 * AU, tn[1] * 2.6 * AU, 4 * AU, 50, 0, tn[2] * 2.6 * AU}
                                            : new double[]{tn[0] * 0.5 * AU, tn[1] * 0.5 * AU, 1.0 * AU, 50, 0, tn[2] * 0.5 * AU})
                .enter(() -> {
                    host.showOnly(null, "Jupiter", "Earth"); host.setOrbits(true, true);
                    setupTransit();
                    host.setTime(transitBody == 0 ? 2 * 3600 : 3600);
                })
                .controls(c -> c.choices("Transiting planet", new String[]{"Jupiter", "Earth"}, () -> transitBody, k -> {
                    transitBody = k; setupTransit(); host.setTime(k == 0 ? 2 * 3600 : 3600);
                }))
                .scene((r, hud, t) -> {
                    double far = transitBody == 0 ? 7 : 1.6;
                    r.line(v(0, 0, 0), scaleV(tn, far), alpha(ACCENT, 200), 1.6f, true);
                    r.label(scaleV(tn, far), "to the distant observer", ACCENT, 12f, 0, -14);
                    double depth = transitDepthAt(host.simTime());
                    double hrs = (host.simTime() - tMid) / 3600;
                    hud.title("Transit of " + tBody(), SUN_C);
                    hud.row("hours from mid-transit", signed("%+.1f", hrs));
                    hud.row("Sun's light blocked now", String.format("%.4f%%", 100 * depth), SUN_C);
                    hud.row("expected depth (R / R(Sun))²", String.format("%.4f%%", 100 * sq(tRadius() / R_SUN)));
                    hud.formula("depth = (R(planet) / R(star))²");
                    hud.inset("What the observer sees", 270, 250, (g, b) -> {
                        // The Sun's disc with the planet's silhouette, to scale.
                        double cx = b.getCenterX(), cy = b.y + 62, R = 54;
                        g.setPaint(new RadialGradientPaint(new Point2D.Double(cx, cy), (float) R, new float[]{0f, 0.85f, 1f},
                                new Color[]{new Color(255, 245, 220), new Color(255, 215, 140), new Color(225, 150, 70)}));
                        g.fill(new Ellipse2D.Double(cx - R, cy - R, 2 * R, 2 * R));
                        double[] pp = pos(host.state(tBody()));
                        if (dot(pp, tn) > 0) {
                            double[] perp = sub(pp, scaleV(tn, dot(pp, tn)));
                            double[] up = v(0, 0, 1), ax = norm(cross(up, tn)), ay = cross(tn, ax);
                            double px = cx + dot(perp, ax) / R_SUN * R, py = cy - dot(perp, ay) / R_SUN * R;
                            double pr = Math.max(0.7, tRadius() / R_SUN * R);
                            g.setColor(new Color(10, 10, 16));
                            g.fill(new Ellipse2D.Double(px - pr, py - pr, 2 * pr, 2 * pr));
                        }
                        Rectangle lc = new Rectangle(b.x, b.y + 124, b.width, b.height - 124);
                        double dep = sq(tRadius() / R_SUN);
                        Plot p = new Plot(lc, lcT[0], lcT[lcT.length - 1], false, 1 - 1.4 * dep, 1 + 0.3 * dep, false);
                        p.axes(g, "hours", null, new double[]{lcT[0] / 2, 0, -lcT[0] / 2}, new String[]{signed("%.0f", lcT[0] / 2), "0", String.format("%.0f", -lcT[0] / 2)},
                                new double[]{1, 1 - dep}, new String[]{"100%", String.format("%.3g%%", 100 * (1 - dep))});
                        p.curve(g, lcT, lcF, SUN_C, 2f);
                        if (hrs >= lcT[0] && hrs <= lcT[lcT.length - 1]) p.vline(g, hrs, alpha(Color.WHITE, 160));
                    });
                }));

        s.add(step("Check: which star is heavier?", """
                A double-lined spectroscopic binary: both stars' lines are seen, and their radial
                velocities trace the two curves in the inset (an edge-on orbit). Star A swings by
                **±30 km/s**, star B by **±60 km/s**.
                """)
                .unit(AU)
                .cam(() -> BIN_CAM)
                .enter(() -> { host.showOnly(null); host.setOrbits(false, false); host.setTime(0); })
                .quiz("Which star is more massive, and by how much?", 0,
                      "Both stars orbit the centre of mass in the same period, so M(A) v(A) = M(B) v(B): the "
                    + "heavier star moves slower. M(A) / M(B) = K(B) / K(A) = 60 / 30 = 2. Inclination does not "
                    + "matter for the ratio, since sin i cancels.",
                      "Star A, twice as massive", "Star B, twice as massive", "Star A, four times as massive", "They are equal")
                .scene((r, hud, t) -> {
                    binaryScene(r, t, 30, 60, false);
                    hud.inset("Radial velocities", 260, 180, (g, b) -> binaryInset(g, b, 30, 60, 0));
                }));

        s.add(step("Check: an offset curve", """
                Another binary. Its two velocity curves swing about **+20 km/s** instead of zero.
                """)
                .unit(AU)
                .cam(() -> BIN_CAM)
                .enter(() -> { host.showOnly(null); host.setOrbits(false, false); host.setTime(0); })
                .quiz("Why is the zero point not zero?", 2,
                      "The pair as a whole is moving away from us at 20 km/s: that is the radial velocity of its "
                    + "centre of mass (the systemic velocity), added to both orbital curves.",
                      "One star is more massive", "The orbit is eccentric", "The whole system is moving away from us",
                      "The orbit is tilted to our line of sight")
                .scene((r, hud, t) -> {
                    binaryScene(r, t, 30, 45, false);
                    hud.inset("Radial velocities", 260, 180, (g, b) -> binaryInset(g, b, 30, 45, 20));
                }));

        s.add(step("Check: an astrometric binary", """
                A face-on, circular astrometric binary **10 pc** away. The visible star moves in a circle
                of radius **0.3″** about the centre of mass of the pair (assume the companion is dark and
                far less massive, so this is the size of the orbit), with a period of **3 years**.
                """)
                .unit(AU)
                .cam(() -> BIN_CAM)
                .enter(() -> { host.showOnly(null); host.setOrbits(false, false); host.setTime(0); })
                .quiz("What is the total mass of the system?", 1,
                      "The orbit's size is a = 0.3″ × 10 pc = 3 AU (angle in arcseconds times distance in parsecs "
                    + "gives AU). Kepler III in solar units: M = a³ / P² = 27 / 9 = 3 solar masses.",
                      "1 solar mass", "3 solar masses", "9 solar masses", "27 solar masses")
                .scene((r, hud, t) -> binaryScene(r, t, 1, 0.08, true)));

        return new LessonStudio.LessonDef("Advanced", "The Sun Wobbles Too",
                "Centre of mass, astrometric and spectroscopic binaries, transits",
                "Follow the Sun's real orbit about the Solar-System barycentre, measure its Doppler "
              + "wobble from any angle and weigh Jupiter with it, then watch Jupiter and Earth transit "
              + "the Sun for a distant observer.",
                new Color(255, 110, 110), s, _ -> { });
    }
}
