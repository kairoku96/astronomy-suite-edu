import java.awt.*;
import java.awt.geom.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Lesson: Measuring Distance (AST221 Day 7). Stellar parallax with Earth's real orbit as the
 * baseline: the yearly parallactic ellipse of real nearby stars (from their catalogued positions,
 * parallaxes and proper motions), the parsec, the looping track proper motion and parallax make
 * together, and how far Hipparcos and Gaia can reach.
 */
final class ParallaxLesson extends LessonKit {

    ParallaxLesson(LessonStudio.Host host) { super(host); }

    static final String[] STARS = {"α Centauri A", "Barnard's Star", "61 Cygni A", "Sirius", "Vega"};
    /** Every star the lesson draws: the five to choose from, plus Rigel for the checks. */
    static final String[] NAME = {"α Centauri A", "Barnard's Star", "61 Cygni A", "Sirius", "Vega", "Rigel"};
    static final int RIGEL = 5;
    /** J2000 RA (°), Dec (°), parallax (mas), proper motion μα·cosδ and μδ (mas/yr): Hipparcos / Gaia DR3. */
    static final double[][] DATA = {
            {219.9021, -60.8340, 747.1, -3679.25, 473.67},
            {269.4521, 4.6934, 546.98, -802.80, 10362.39},
            {316.7248, 38.7494, 285.99, 4164.21, 3249.99},
            {101.2872, -16.7161, 379.21, -546.01, -1223.07},
            {279.2347, 38.7837, 130.23, 200.94, 286.23},
            {78.6345, -8.2016, 3.235, 1.31, 0.50}};          // Rigel, with the slides' parallax
    static final double EPS = Math.toRadians(23.4392911);
    /** Shown distance of the star in the 3-D diagram (AU) and of the far "background" plane. */
    static final double D_VIS = 2.6, SCREEN = 8;

    int star;
    double[][] yearE;          // Earth's equatorial position (AU) at 73 times over the next year
    double[][] trackE;         // ... and at 160 times over 3 years
    double tYear0;
    double[] camGoal = {0, 0, 7 * AU, 60, 0};

    // ------------------------------------------------------------------ geometry

    /** Earth's heliocentric position at sim time t, equatorial J2000, in AU. */
    double[] earthEq(double t) {
        double[] w = pos(host.stateAt("Earth", t));
        double xe = -w[1] / AU, ye = w[0] / AU, ze = w[2] / AU;           // world {ye, −xe, ze} -> ecliptic
        return v(xe, ye * Math.cos(EPS) - ze * Math.sin(EPS), ye * Math.sin(EPS) + ze * Math.cos(EPS));
    }
    static double[] unitEq(int k) {
        double a = Math.toRadians(DATA[k][0]), d = Math.toRadians(DATA[k][1]);
        return v(Math.cos(d) * Math.cos(a), Math.cos(d) * Math.sin(a), Math.sin(d));
    }
    /** The star's direction in the sim's world frame. */
    static double[] unitWorld(int k) {
        double[] q = unitEq(k);
        double ye = q[1] * Math.cos(EPS) + q[2] * Math.sin(EPS), ze = -q[1] * Math.sin(EPS) + q[2] * Math.cos(EPS);
        return v(ye, -q[0], ze);
    }
    /** Parallactic shift {east, north} in arcsec for an observer at R (AU, equatorial): −p × R projected on the sky. */
    static double[] shift(int k, double[] R) {
        double a = Math.toRadians(DATA[k][0]), d = Math.toRadians(DATA[k][1]), p = DATA[k][2] / 1000;
        double[] e = v(-Math.sin(a), Math.cos(a), 0), n = v(-Math.sin(d) * Math.cos(a), -Math.sin(d) * Math.sin(a), Math.cos(d));
        return new double[]{-p * dot(R, e), -p * dot(R, n)};
    }

    void sample() {
        tYear0 = host.simTime();
        yearE = new double[73][];
        for (int i = 0; i < 73; i++) yearE[i] = earthEq(tYear0 + YEAR * i / 72);
        trackE = new double[161][];
        for (int i = 0; i <= 160; i++) trackE[i] = earthEq(tYear0 + 3 * YEAR * i / 160);
    }

    void frame() {
        double[] u = unitWorld(star);
        double h = Math.hypot(u[0], u[1]);
        double[] hu = h < 1e-6 ? v(1, 0, 0) : v(u[0] / h, u[1] / h, 0);
        double[] perp = v(-hu[1], hu[0], 0);
        // Centre on the sight line itself (nearby stars can lie far off the ecliptic), seen side-on.
        camGoal = new double[]{u[0] * 4 * AU, u[1] * 4 * AU, 6.2 * AU, 62, Math.toDegrees(Math.atan2(perp[0], perp[1])), u[2] * 4 * AU};
    }

    void pick(int k) { star = k; frame(); }

    /** Earth now (AU, world), the shown star, and where the sight line meets the far plane. */
    void draw3d(LessonStudio.R3 r, boolean ellipse) {
        double[] u = unitWorld(star), S = scaleV(u, D_VIS);
        double[] E = scaleV(pos(host.state("Earth")), 1 / AU);
        double t = (SCREEN - dot(E, u)) / (D_VIS - dot(E, u));
        double[] P = add(E, scaleV(sub(S, E), t));
        r.line(E, P, alpha(SUN_C, 200), 1.6f, false);
        if (ellipse) {
            double[][] pts = new double[73][];
            for (int i = 0; i < 73; i++) {
                double[] Ei = worldOfEq(yearE[i]);
                double ti = (SCREEN - dot(Ei, u)) / (D_VIS - dot(Ei, u));
                pts[i] = add(Ei, scaleV(sub(S, Ei), ti));
            }
            r.polyline(pts, true, alpha(PINK, 200), 1.8f, true);
            r.label(pts[18], "where the star appears against the background", PINK, 11.5f, 0, -16);
        }
        r.dot(P, 5, PINK);
        Color c = new Color(255, 236, 200);
        r.dot(S, 6, c);
        double tooClose = 1 / (DATA[star][2] / 1000) * 206265 / D_VIS;
        r.label(S, NAME[star] + String.format("  (shown %,d× too close)", Math.round(tooClose / 1000) * 1000), c, 12f, 0, -18);
    }
    static double[] worldOfEq(double[] q) {
        double ye = q[1] * Math.cos(EPS) + q[2] * Math.sin(EPS), ze = -q[1] * Math.sin(EPS) + q[2] * Math.cos(EPS);
        return v(ye, -q[0], ze);
    }

    /** The true-scale yearly ellipse on the sky (east to the left, as on a sky chart), with the star now. */
    void ellipseInset(Graphics2D g, Rectangle b, double halfArcsec) { ellipseInset(g, b, halfArcsec, false); }
    /** ... with the scale bar in plain arcseconds when {@code plain} (AST101). */
    void ellipseInset(Graphics2D g, Rectangle b, double halfArcsec, boolean plain) {
        double cx = b.getCenterX(), cy = b.getCenterY() + 6, s = (Math.min(b.width, b.height) / 2.0 - 16) / halfArcsec;
        g.setColor(new Color(255, 255, 255, 40));
        g.draw(new Line2D.Double(b.x + 4, cy, b.getMaxX() - 4, cy));
        g.draw(new Line2D.Double(cx, b.y + 14, cx, b.getMaxY() - 4));
        Path2D.Double path = new Path2D.Double();
        for (int i = 0; i < 73; i++) {
            double[] d = shift(star, yearE[i]);
            double x = cx - d[0] * s, y = cy - d[1] * s;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        g.setColor(PINK);
        g.setStroke(new BasicStroke(1.8f));
        g.draw(path);
        double[] now = shift(star, earthEq(host.simTime()));
        g.setColor(new Color(255, 236, 200));
        g.fill(new Ellipse2D.Double(cx - now[0] * s - 4, cy - now[1] * s - 4, 8, 8));
        g.setFont(LessonStudio.uiFont(Font.PLAIN, 10.5f));
        g.setColor(DIM);
        g.drawString("N", (float) cx + 4, b.y + 22);
        g.drawString("E", b.x + 6, (float) cy - 4);
        double bar = TelescopeLesson.niceBar(halfArcsec / 2);
        g.draw(new Line2D.Double(b.getMaxX() - 10 - bar * s, b.getMaxY() - 8, b.getMaxX() - 10, b.getMaxY() - 8));
        String lab = plain ? arcsec(bar) : angle(bar);
        g.drawString(lab, (float) (b.getMaxX() - 12 - bar * s - g.getFontMetrics().stringWidth(lab) - 4), (float) b.getMaxY() - 4);
    }

    /** Three years of the star's apparent track: steady proper motion plus the yearly parallax loop. */
    void trackInset(Graphics2D g, Rectangle b) {
        int n = trackE.length;
        double[] xs = new double[n], ys = new double[n];
        double minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9;
        for (int i = 0; i < n; i++) {
            double yr = 3.0 * i / (n - 1);
            double[] d = shift(star, trackE[i]);
            xs[i] = d[0] + DATA[star][3] / 1000 * yr;
            ys[i] = d[1] + DATA[star][4] / 1000 * yr;
            minX = Math.min(minX, xs[i]); maxX = Math.max(maxX, xs[i]); minY = Math.min(minY, ys[i]); maxY = Math.max(maxY, ys[i]);
        }
        double span = Math.max(maxX - minX, maxY - minY) * 1.15 + 1e-6;
        double s = (Math.min(b.width, b.height) - 30) / span, mx = (minX + maxX) / 2, my = (minY + maxY) / 2;
        double cx = b.getCenterX(), cy = b.getCenterY() + 4;
        Path2D.Double path = new Path2D.Double(), straight = new Path2D.Double();
        for (int i = 0; i < n; i++) {
            double x = cx - (xs[i] - mx) * s, y = cy - (ys[i] - my) * s;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            double yr = 3.0 * i / (n - 1);
            double sx = cx - (DATA[star][3] / 1000 * yr - mx) * s, sy = cy - (DATA[star][4] / 1000 * yr - my) * s;
            if (i == 0) straight.moveTo(sx, sy); else straight.lineTo(sx, sy);
        }
        g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[]{4f, 4f}, 0f));
        g.setColor(alpha(ACCENT, 170));
        g.draw(straight);
        g.setStroke(new BasicStroke(1.8f));
        g.setColor(PINK);
        g.draw(path);
        double yrNow = clamp((host.simTime() - tYear0) / YEAR, 0, 3);
        int k = (int) Math.round(yrNow / 3 * (n - 1));
        g.setColor(new Color(255, 236, 200));
        g.fill(new Ellipse2D.Double(cx - (xs[k] - mx) * s - 4, cy - (ys[k] - my) * s - 4, 8, 8));
        g.setFont(LessonStudio.uiFont(Font.PLAIN, 10.5f));
        g.setColor(DIM);
        double bar = TelescopeLesson.niceBar(span / 3);
        g.draw(new Line2D.Double(b.getMaxX() - 10 - bar * s, b.getMaxY() - 8, b.getMaxX() - 10, b.getMaxY() - 8));
        String lab = angle(bar);
        g.drawString(lab, (float) (b.getMaxX() - 12 - bar * s - g.getFontMetrics().stringWidth(lab) - 4), (float) b.getMaxY() - 4);
        g.drawString("dashed: proper motion alone", b.x + 6, b.y + 14);
    }

    /** Distances on a log scale with the reach of Hipparcos and Gaia. */
    static void reachInset(Graphics2D g, Rectangle b) {
        double x0 = b.x + 12, x1 = b.getMaxX() - 12;
        java.util.function.DoubleUnaryOperator X = pc -> x0 + (x1 - x0) * (Math.log10(pc) + 0.5) / 5.0;    // 0.3 pc .. 30 kpc
        double y = b.y + 92;
        g.setColor(new Color(255, 255, 255, 120));
        g.setStroke(new BasicStroke(1.2f));
        g.draw(new Line2D.Double(x0, y, x1, y));
        g.setFont(LessonStudio.uiFont(Font.PLAIN, 10f));
        for (int e = 0; e <= 4; e++) {
            double x = X.applyAsDouble(Math.pow(10, e));
            g.draw(new Line2D.Double(x, y - 3, x, y + 3));
            String s = new String[]{"1 pc", "10", "100", "1 kpc", "10 kpc"}[e];
            g.setColor(DIM);
            g.drawString(s, (float) (x - g.getFontMetrics().stringWidth(s) / 2.0), (float) (y + 15));
            g.setColor(new Color(255, 255, 255, 120));
        }
        double[][] bars = {{100, 1000, 0}, {1000, 10000, 1}};
        String[] names = {"Hipparcos: 10% at 100 pc, detects to 1 kpc", "Gaia: 10% at 1 kpc, detects to 10 kpc"};
        Color[] cols = {ORANGE, GREEN};
        for (int k = 0; k < 2; k++) {
            double yy = b.y + 18 + k * 30;
            double xa = X.applyAsDouble(0.3), xb = X.applyAsDouble(bars[k][0]), xc = X.applyAsDouble(bars[k][1]);
            g.setColor(cols[k]);
            g.fill(new Rectangle2D.Double(xa, yy, xb - xa, 7));
            g.setColor(alpha(cols[k], 90));
            g.fill(new Rectangle2D.Double(xb, yy, xc - xb, 7));
            g.setColor(cols[k]);
            g.drawString(names[k], (float) xa, (float) (yy + 19));
        }
        String[] lab = {"α Cen", "Vega", "Pleiades", "Rigel", "Galactic centre"};
        double[] d = {1.34, 7.68, 136, 264, 8200};
        for (int i = 0; i < lab.length; i++) {
            double x = X.applyAsDouble(d[i]);
            g.setColor(SUN_C);
            g.fill(new Ellipse2D.Double(x - 3, y - 3, 6, 6));
            g.drawString(lab[i], (float) (x - g.getFontMetrics().stringWidth(lab[i]) / 2.0), (float) (y + 30 + (i % 2) * 12));
        }
    }

    LessonStudio.LessonDef build() {
        List<LessonStudio.Step> s = new ArrayList<>();

        s.add(step("Parallax: Earth's orbit as a baseline", """
                Hold up a finger and blink one eye, then the other: the finger jumps against the
                background. That shift is {k:parallax}, and the nearer the finger, the bigger it
                is.

                Astronomers blink across Earth's orbit. Over a year Earth carries us around a circle
                2 AU wide, so a nearby star traces a small ellipse against the far more distant
                stars. The half-width of that ellipse is the star's {k:parallax angle} p.

                The diagram shows **α Centauri A**, the nearest Sun-like star, 100,000 times too close
                so you can see the effect. The inset is the real thing, at true scale: an ellipse
                only **1.5″** across, p = 0.75″ either side of the star's average position.
                """)
                .only("AST221")
                .unit(AU)
                .cam(() -> camGoal)
                .enter(() -> {
                    host.showOnly(null, "Earth"); host.setOrbits(true, true); host.setTime(15 * DAY);
                    star = 0; frame(); sample();
                })
                .controls(c -> timeSlider(c, "days", DAY, 40))
                .scene((r, hud, t) -> {
                    draw3d(r, true);
                    double p = DATA[star][2] / 1000;
                    hud.title("Parallax of " + NAME[star], PINK);
                    hud.row("parallax p", String.format("%.3f″", p), PINK);
                    hud.row("the ellipse across", String.format("%.2f″", 2 * p));
                    hud.row("distance d = 1 / p", String.format("%.2f pc = %.2f light-years", 1 / p, 1 / p * PC / LY));
                    hud.formula("d (parsecs) = 1 / p (arcseconds)");
                    hud.inset("True scale, one year", 230, 230, (g, b) -> ellipseInset(g, b, 0.9));
                }));

        // AST101 leaves out the parsec: the same parallax, told as the shift Tycho looked for.
        s.add(step("Parallax: Earth's orbit as a baseline", """
                Hold up a finger and blink one eye, then the other: the finger jumps against the
                background. That shift is {k:parallax}, and the nearer the finger, the bigger it
                is.

                If Earth goes around the Sun, the stars should do the same. Over a year Earth
                carries us around a circle 300 million km wide, so a nearby star should trace a small
                loop against the far more distant stars. Half the distance, twice the shift.

                In the 1580s Tycho Brahe looked hard for this shift and saw none. His instruments,
                the best of his time, could measure about 1 arcminute (1/60 of a degree). Even the
                nearest star's shift is **0.75 arcseconds**, 80 times smaller. The stars are far
                more distant than anyone imagined; the shift wasn't seen until 1838.

                > The diagram shows α Centauri A 100,000 times too close, so you can see the
                effect. The inset is the real loop at true scale. Pick farther stars: their loops
                shrink.
                """)
                .only("AST101")
                .unit(AU)
                .cam(() -> camGoal)
                .enter(() -> {
                    host.showOnly(null, "Earth"); host.setOrbits(true, true); host.setTime(15 * DAY);
                    star = 0; frame(); sample();
                })
                .controls(c -> {
                    c.choices("Star", STARS, () -> star, this::pick);
                    timeSlider(c, "days", DAY, 40);
                })
                .scene((r, hud, t) -> {
                    draw3d(r, true);
                    double p = DATA[star][2] / 1000, ly = 1 / p * PC / LY;
                    hud.title("Parallax of " + NAME[star], PINK);
                    hud.row("distance", String.format("%.1f light-years", ly));
                    hud.row("shift p (half the loop)", arcsec(p), PINK);
                    hud.row("Tycho could measure", "about 60″ (1 arcminute)", DIM);
                    hud.row("the shift is smaller by", String.format("%,.0f×", 60 / p), PINK);
                    hud.formula("twice as far → half the shift");
                    hud.inset("True scale, one year", 230, 230, (g, b) -> ellipseInset(g, b, 0.9, true));
                }));

        s.add(step("Check: a star twice as far", """
                α Centauri A, 4.4 light-years away, shifts **0.75″** either way over a year. Picture a
                second star exactly **twice as far** away, 8.7 light-years.
                """)
                .only("AST101")
                .unit(AU)
                .cam(() -> camGoal)
                .enter(() -> { host.showOnly(null, "Earth"); host.setOrbits(true, true); host.setTime(15 * DAY); star = 0; frame(); sample(); })
                .quiz("How big is the second star's shift?", 2,
                      "Twice as far, half the shift: 0.75″ / 2 ≈ 0.37″. The same 300-million-km baseline looks half "
                    + "as wide from twice as far away. (Sirius, at 8.6 light-years, really does shift 0.38″.)",
                      "1.5″", "0.75″", "About 0.37″", "About 0.19″")
                .scene((r, hud, t) -> {
                    draw3d(r, true);
                    hud.inset("α Centauri A, true scale", 230, 230, (g, b) -> ellipseInset(g, b, 0.9, true));
                }));

        s.add(step("Check: why Tycho saw no shift", """
                Tycho Brahe measured star positions to about 1 arcminute (60″) and found no yearly
                shift at all.
                """)
                .only("AST101")
                .unit(AU)
                .cam(() -> camGoal)
                .enter(() -> { host.showOnly(null, "Earth"); host.setOrbits(true, true); host.setTime(15 * DAY); star = 0; frame(); sample(); })
                .quiz("What does the missing shift really tell us?", 1,
                      "Tycho took it to mean that Earth stands still. But Earth does move: even the nearest star's shift is "
                    + "0.75″, 80 times smaller than he could measure, because the stars are far more distant than anyone "
                    + "then imagined. The shift was finally measured in 1838, by telescope.",
                      "Earth doesn't move around the Sun", "The stars are much farther away than Tycho thought",
                      "The stars move along with Earth", "Parallax only works for planets")
                .scene((r, hud, t) -> {
                    draw3d(r, true);
                    hud.title("The numbers", PINK);
                    hud.row("Tycho could measure", "about 60″");
                    hud.row("the nearest star's shift", "0.75″", PINK);
                }));

        s.add(step("The parsec", """
                Parallax defines astronomy's unit of distance. A star whose parallax is **1
                arcsecond** is 1 {k:parsec} (parallax-second) away: the distance at which 1 AU
                spans 1″, which is 206,265 AU or 3.26 light-years. So

                $ d = 1 / p   (d in parsecs, p in arcseconds)

                No star is that close: α Centauri, the nearest, has p = 0.75″. In 1838 Friedrich Bessel
                measured the first stellar parallax, of **61 Cygni**: 0.29″, a coin seen from
                15 km away.

                > Compare stars. The inset keeps the same scale, so farther stars draw smaller
                ellipses.
                """)
                .unit(AU)
                .cam(() -> camGoal)
                .enter(() -> {
                    host.showOnly(null, "Earth"); host.setOrbits(true, true); host.setTime(15 * DAY);
                    frame(); sample();
                })
                .controls(c -> {
                    c.choices("Star", STARS, () -> star, this::pick);
                    timeSlider(c, "days", DAY, 40);
                })
                .scene((r, hud, t) -> {
                    draw3d(r, true);
                    double p = DATA[star][2] / 1000, d = 1 / p;
                    hud.title(NAME[star], PINK);
                    hud.row("parallax p", String.format("%.1f mas", p * 1000), PINK);
                    hud.row("distance 1 / p", String.format("%.2f pc", d));
                    hud.row("in AU  (× 206,265)", String.format("%,.0f AU", d * 206265));
                    hud.row("in light-years  (× 3.26)", String.format("%.2f ly", d * PC / LY));
                    hud.formula("1 pc = 206,265 AU = 3.26 ly");
                    hud.inset("True scale, one year (same scale for every star)", 230, 230, (g, b) -> ellipseInset(g, b, 0.9));
                }));

        s.add(step("Proper motion: stars wander too", """
                Stars also move through the Galaxy, so each drifts slowly across the sky in a straight
                line: its {k:proper motion} μ, measured in arcseconds per year. Add the yearly parallax
                loop on top and a nearby star's track becomes a wiggle, or for a slow mover a
                series of loops.

                The inset shows three years of each star's real track: the dashed line is the proper
                motion alone. Barnard's Star moves fastest of all, 10.4″ a year, so its parallax is
                just a ripple on a long line. To measure a parallax you must fit both motions at once.
                """)
                .unit(AU)
                .cam(() -> camGoal)
                .enter(() -> {
                    host.showOnly(null, "Earth"); host.setOrbits(true, true); host.setTime(30 * DAY);
                    frame(); sample();
                })
                .controls(c -> {
                    c.choices("Star", STARS, () -> star, this::pick);
                    timeSlider(c, "days", DAY, 60);
                })
                .scene((r, hud, t) -> {
                    draw3d(r, false);
                    double mu = Math.hypot(DATA[star][3], DATA[star][4]) / 1000;
                    hud.title(NAME[star], PINK);
                    hud.row("proper motion μ", String.format("%.2f″ per year", mu), ACCENT);
                    hud.row("parallax p", String.format("%.3f″", DATA[star][2] / 1000), PINK);
                    hud.row("μ / p", String.format("%.1f", mu / (DATA[star][2] / 1000)));
                    hud.formula("track = proper motion + parallax loop");
                    hud.inset("Three years on the sky", 240, 240, this::trackInset);
                }));

        s.add(step("How far can parallax reach?", """
                A parallax can only be measured if it is bigger than the measuring error σ. The
                **Hipparcos** satellite (1989–93) measured 100,000 stars to about **1 mas**; **Gaia**
                (2013–) measures nearly two billion, down to about **0.1 mas** or better for bright
                stars.

                Since d = 1 / p, a precision σ can just detect stars out to 1 / σ, and gives a 10%
                distance out to 1 / (10σ): about 100 pc for Hipparcos and 1 kpc for Gaia. The centre
                of our Galaxy, 8 kpc away, is still beyond a good parallax.
                """)
                .unit(AU)
                .cam(() -> camGoal)
                .enter(() -> {
                    host.showOnly(null, "Earth"); host.setOrbits(true, true); host.setTime(15 * DAY);
                    frame(); sample();
                })
                .scene((r, hud, t) -> {
                    draw3d(r, true);
                    hud.title("The reach of parallax", ACCENT);
                    hud.row("Hipparcos  σ ≈ 1 mas", "detect to 1 kpc;  10% at 100 pc", ORANGE);
                    hud.row("Gaia  σ ≈ 0.1 mas", "detect to 10 kpc;  10% at 1 kpc", GREEN);
                    hud.formula("d(max) ≈ 1 / σ");
                    hud.inset("Distances (log scale)", 330, 140, ParallaxLesson::reachInset);
                }));

        s.add(step("Check: a star at 25 pc", """
                A star lies **25 parsecs** away.
                """)
                .unit(AU)
                .cam(() -> camGoal)
                .enter(() -> { host.showOnly(null, "Earth"); host.setOrbits(true, true); host.setTime(15 * DAY); star = 0; frame(); sample(); })
                .quiz("What is its parallax?", 1,
                      "p = 1 / d = 1 / 25 = 0.04″ = 40 mas: about the width of a human hair seen from 300 m.",
                      "25″", "0.04″", "0.4″", "4 mas")
                .scene((r, hud, t) -> {
                    draw3d(r, true);
                    hud.title("For comparison", PINK);
                    hud.row("α Centauri A", "p = 0.747″,  d = 1.34 pc");
                }));

        s.add(step("Check: Rigel's temperature", """
                From the slides: Rigel, in Orion, has parallax **3.235 mas**, radius **74.1 solar radii**
                and luminosity **120,000 times the Sun's**. The Sun's surface is at 5772 K.
                """)
                .unit(AU)
                .cam(() -> camGoal)
                .enter(() -> { host.showOnly(null, "Earth"); host.setOrbits(true, true); host.setTime(15 * DAY); star = RIGEL; frame(); sample(); })
                .quiz("What is Rigel's surface temperature?", 0,
                      "L = 4πR²σT⁴, so T / T(Sun) = (L / R²)^¼ in solar units = (120,000 / 74.1²)^¼ = 21.9^¼ = 2.16. "
                    + "T ≈ 2.16 × 5772 K ≈ 12,500 K: a hot, blue-white supergiant. (The parallax isn't needed "
                    + "here; it gives the distance, 1 / 0.003235 = 309 pc.)",
                      "About 12,500 K", "About 5,800 K", "About 3,500 K", "About 25,000 K")
                .scene((r, hud, t) -> draw3d(r, true)));

        s.add(step("Check: Rigel's absolute magnitude", """
                Same star: L = **120,000 L(Sun)**. The Sun's absolute bolometric magnitude is **+4.74**.
                """)
                .unit(AU)
                .cam(() -> camGoal)
                .enter(() -> { host.showOnly(null, "Earth"); host.setOrbits(true, true); host.setTime(15 * DAY); star = RIGEL; frame(); sample(); })
                .quiz("What is Rigel's absolute bolometric magnitude?", 1,
                      "M = M(Sun) − 2.5 log₁₀(L / L(Sun)) = 4.74 − 2.5 × log₁₀(120,000) = 4.74 − 2.5 × 5.08 = −8.0. "
                    + "Its flux at Earth is L / 4πd² = 4.6 × 10³¹ W / 4π(9.5 × 10¹⁸ m)² ≈ 4 × 10⁻⁸ W/m².",
                      "About +4.7", "About −8.0", "About −12.7", "About −2.5")
                .scene((r, hud, t) -> draw3d(r, true)));

        return new LessonStudio.LessonDef("Intermediate", "Measuring Distance",
                "Parallax, the parsec and proper motion",
                "Ride Earth's real orbit and watch the nearest stars trace their yearly parallax "
              + "ellipses, define the parsec, untangle parallax from proper motion, and see how far "
              + "Hipparcos and Gaia can reach.",
                new Color(255, 140, 200), s, _ -> { });
    }
}
