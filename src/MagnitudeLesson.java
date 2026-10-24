import java.awt.*;
import java.awt.geom.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Lesson: How Bright? (AST221 Day 6). The magnitude scale on the real winter sky, Vega as the
 * zero point, Mars brightening toward its 2027 opposition, absolute magnitude, colour (B − V),
 * the spectral sequence OBAFGKM as a filter on the real star catalogue, and where absorption
 * lines come from.
 */
final class MagnitudeLesson extends LessonKit {

    MagnitudeLesson(LessonStudio.Host host) { super(host); }

    static final String[] WINTER = {"Jupiter", "Sirius", "Capella", "Rigel", "Procyon", "Betelgeuse", "Aldebaran"};
    static final String[] SUMMER = {"Vega", "Arcturus", "Altair", "Deneb"};
    /** Vega's bolometric flux, the zero point the slides use (W/m²). */
    static final double F0 = 2.518e-8;
    /** The Sun's absolute visual magnitude. */
    static final double M_SUN_V = 4.83;

    static final String[] CLASSES = {"All", "O and B", "A", "F", "G", "K", "M"};
    /** B − V boundaries between the classes (main-sequence colours). */
    static final double[] BV_EDGE = {-0.30, -0.02, 0.30, 0.58, 0.81, 1.40};
    static final Color[] CLASS_C = {INK, new Color(155, 176, 255), new Color(202, 215, 255), new Color(248, 247, 255),
            new Color(255, 244, 234), new Color(255, 210, 161), new Color(255, 204, 111)};

    double skyLimit = 6.0;
    double[] orionView = {185, 35, 55};

    /** Centre the view between Betelgeuse and Rigel. */
    void frameOrion() {
        double[] m = norm(add(host.skyEnu("Betelgeuse"), host.skyEnu("Rigel")));
        orionView = new double[]{Math.round(azOf(m)), Math.round(altOf(m)), 55};
    }
    int cls;
    double logD = 1;           // observer distance for the absolute-magnitude step, log10(pc)
    double[] marsT, marsM;     // Mars's magnitude over the coming year
    int[] classCount;

    /** Spectral class 1..6 (O/B, A, F, G, K, M) of a B − V colour. */
    static int classOf(double bv) {
        if (bv < BV_EDGE[1]) return 1;
        for (int k = 2; k < 6; k++) if (bv < BV_EDGE[k]) return k;
        return 6;
    }

    void utm() { host.skyPlace(UTM, UTM_LAT, UTM_LON, TORONTO); }

    void labelStars(LessonStudio.R3 r, String[] names) {
        for (String n : names) {
            double[] b = host.skyBody(n), e = host.skyEnu(n);
            if (b == null || b[0] < 0) continue;
            r.label(e, "m = " + signed("%.1f", b[4]), SUN_C, 12f, 0, 20);
        }
    }

    void countClasses() {
        StarCatalog cat = StarCatalog.get();
        classCount = new int[7];
        if (cat == null) return;
        for (int i = 0; i < cat.n; i++) if (cat.vmag[i] <= 6.5) classCount[classOf(cat.bv[i])]++;
    }

    void traceMars() {
        int n = 96;
        double t0 = host.simTime();
        marsT = new double[n]; marsM = new double[n];
        for (int i = 0; i < n; i++) {
            marsT[i] = 365.0 * i / (n - 1);
            marsM[i] = host.skyBodyAt("Mars", t0 + marsT[i] * DAY)[4];
        }
        marsT0 = t0;
    }
    double marsT0;

    /** The Sun as a star among familiar ones, each drawn as the planetarium draws a star of its magnitude. */
    void sunAsStarInset(Graphics2D g, Rectangle b, double mSun) {
        String[] names = {"Sirius", "Vega", "Polaris", "Mizar", "limit"};
        double[] mags = {-1.46, 0.03, 1.98, 2.23, 6.5};
        int n = names.length + 1;
        for (int i = 0; i < n; i++) {
            boolean sun = i == n - 1;
            double m = sun ? mSun : mags[i];
            double x = b.x + 22 + i * (b.width - 44) / (n - 1.0), y = b.y + b.height * 0.45;
            if (m < 7.5) {
                double[] sp = StarCatalog.pointSprite(Math.max(m, -3), 6.5);
                double r = Math.max(0.8, sp[0] * 1.4);
                Color c = sun ? new Color(255, 236, 200) : new Color(225, 235, 255);
                g.setPaint(new RadialGradientPaint(new Point2D.Double(x, y), (float) (r * 2.6), new float[]{0f, 0.35f, 1f},
                        new Color[]{alpha(c, (int) (255 * sp[1])), alpha(c, (int) (90 * sp[1])), alpha(c, 0)}));
                g.fill(new Ellipse2D.Double(x - r * 2.6, y - r * 2.6, r * 5.2, r * 5.2));
            }
            g.setFont(LessonStudio.uiFont(sun ? Font.BOLD : Font.PLAIN, 10.5f));
            g.setColor(sun ? SUN_C : DIM);
            String a = sun ? "Sun" : names[i], c2 = signed("%.1f", m);
            g.drawString(a, (float) (x - g.getFontMetrics().stringWidth(a) / 2.0), (float) (b.y + b.height * 0.45 + 34));
            g.drawString(c2, (float) (x - g.getFontMetrics().stringWidth(c2) / 2.0), (float) (b.y + b.height * 0.45 + 48));
        }
        g.setFont(LessonStudio.uiFont(Font.PLAIN, 10.5f));
        g.setColor(DIM);
        g.drawString(mSun > 6.5 ? "too faint to see with the naked eye" : "visible to the naked eye", b.x + 6, b.y + 14);
    }

    /** Two blackbody spectra with the B and V filters: their ratio is the colour. */
    static void colourInset(Graphics2D g, Rectangle b) {
        Plot p = new Plot(b, 300, 1000, false, 0, 1.15, false);
        double[][] bands = {{445, 94, 0}, {551, 88, 1}};
        for (double[] bd : bands) {
            g.setColor(bd[2] == 0 ? new Color(90, 120, 255, 60) : new Color(120, 220, 120, 60));
            g.fill(new Rectangle2D.Double(p.px(bd[0] - bd[1] / 2), p.plotTop(), p.px(bd[0] + bd[1] / 2) - p.px(bd[0] - bd[1] / 2), p.plotBottom() - p.plotTop()));
            p.label(g, bd[0] - 8, 1.1, bd[2] == 0 ? "B" : "V", INK, 0, 0);
        }
        p.axes(g, "λ (nm)", null, new double[]{400, 600, 800, 1000}, new String[]{"400", "600", "800", "1000"}, new double[]{}, new String[]{});
        double[] Ts = {tFromBv(1.85), tFromBv(-0.03)};
        String[] lab = {"Betelgeuse", "Rigel"};
        for (int k = 0; k < 2; k++) {
            int n = 160;
            double[] xs = new double[n], ys = new double[n];
            double norm = planck(551e-9, Ts[k]);
            for (int i = 0; i < n; i++) { xs[i] = 300 + 700.0 * i / (n - 1); ys[i] = 0.5 * planck(xs[i] * 1e-9, Ts[k]) / norm; }
            p.curve(g, xs, ys, starColor(Ts[k]), 2f);
            p.label(g, 880, ys[(int) ((880 - 300) / 700.0 * (n - 1))], lab[k], starColor(Ts[k]), 0, -6);
        }
        p.label(g, 300, 1.05, "both scaled to match at V", DIM, 4, 0);
    }

    /** Hydrogen's energy levels and the Balmer transitions, over a strip of absorption spectrum. */
    static void bohrInset(Graphics2D g, Rectangle b) {
        double top = b.y + 8, bot = b.y + b.height - 46, left = b.x + 40, right = b.x + b.width - 10;
        // Square-root stretched energy axis, so the crowded upper levels can be told apart.
        java.util.function.DoubleUnaryOperator yOf = e -> top + (bot - top) * Math.sqrt(-e / 13.6);
        g.setFont(LessonStudio.uiFont(Font.PLAIN, 10f));
        for (int n = 1; n <= 6; n++) {
            double E = -13.6 / (n * n), y = yOf.applyAsDouble(E);
            g.setColor(new Color(255, 255, 255, n == 2 ? 200 : 110));
            g.setStroke(new BasicStroke(n == 2 ? 1.6f : 1f));
            g.draw(new Line2D.Double(left, y, right, y));
            if (n <= 4) {
                g.setColor(DIM);
                g.drawString(String.format("n=%d  %.2f eV", n, E).replace('-', '−'), (float) (right - 92), (float) (y - 3));
            }
        }
        double y0 = yOf.applyAsDouble(0);
        g.setColor(DIM);
        g.drawString("ionized (E = 0)", (float) (left - 30), (float) (y0 + 11));
        g.drawString("energy axis stretched", (float) (left - 30), (float) (bot - 6));
        int[] up = {3, 4, 5, 6};
        double[] nm = {656.3, 486.1, 434.0, 410.2};
        Color[] col = {new Color(255, 70, 70), new Color(70, 220, 255), new Color(110, 120, 255), new Color(180, 100, 255)};
        for (int k = 0; k < 4; k++) {
            double x = left + 14 + k * 26;
            double ya = yOf.applyAsDouble(-13.6 / 4), yb = yOf.applyAsDouble(-13.6 / (up[k] * up[k]));
            g.setColor(col[k]);
            g.setStroke(new BasicStroke(2f));
            g.draw(new Line2D.Double(x, ya, x, yb + 5));
            g.fill(new Polygon(new int[]{(int) x - 4, (int) x + 4, (int) x}, new int[]{(int) yb + 7, (int) yb + 7, (int) yb}, 3));
            g.drawString(String.format("%.0f", nm[k]), (float) (x - 9), (float) (ya + 12));
        }
        // An absorption spectrum strip, 380–700 nm.
        double sy = b.y + b.height - 26, sh = 16, sx0 = b.x + 8, sx1 = b.x + b.width - 8;
        for (int i = 0; i < 160; i++) {
            double lam = 380 + 320.0 * i / 160;
            g.setColor(Color.getHSBColor((float) (0.78 * (700 - lam) / 320.0), 0.85f, 0.95f));
            g.fill(new Rectangle2D.Double(sx0 + (sx1 - sx0) * i / 160.0, sy, (sx1 - sx0) / 160.0 + 0.6, sh));
        }
        g.setColor(Color.BLACK);
        for (double lam : nm) {
            double x = sx0 + (sx1 - sx0) * (lam - 380) / 320.0;
            g.fill(new Rectangle2D.Double(x - 1.3, sy, 2.6, sh));
        }
        g.setColor(DIM);
        g.drawString("an A star's spectrum: the Balmer lines", (float) sx0, (float) (sy + sh + 11));
    }

    LessonStudio.LessonDef build() {
        List<LessonStudio.Step> s = new ArrayList<>();

        s.add(step("The magnitude scale", """
                Over 2000 years ago Hipparchus sorted the stars from **1st magnitude** (brightest) to
                **6th** (barely visible). The scale stuck, backwards: **bigger numbers are fainter**.

                The eye responds roughly logarithmically, so magnitudes are a logarithm of flux. A
                difference of 5 magnitudes is exactly a factor of 100, so 1 magnitude is a factor of
                100^(1/5) = 2.512:

                $ m₁ − m₂ = −2.5 log₁₀(F₁ / F₂)

                Here is the real midnight sky from Mississauga on January 10, 2026, with Jupiter at
                opposition (magnitude −2.7) beside Orion.

                > Slide the sky's limiting magnitude: city light pollution washes out everything
                fainter than about magnitude 3.
                """)
                .sky(() -> new double[]{165, 38, 110})
                .enter(() -> {
                    utm(); host.goToUTC(2026, 1, 10, 5, 0); host.setTime(0);
                    host.skyLayers(false, false, true, true);
                })
                .live(() -> host.skyOptics(skyLimit, Double.NaN, null))
                .controls(c -> c.slider("Faintest star visible", 1, 6.5, () -> skyLimit, x -> skyLimit = x,
                        x -> String.format("magnitude %.1f%s", x, x < 3.5 ? "  (city)" : x < 5.5 ? "  (suburbs)" : "  (dark site)")))
                .scene((r, hud, t) -> {
                    labelStars(r, WINTER);
                    hud.title("Brightness", SUN_C);
                    for (String n : WINTER) {
                        double[] b = host.skyBody(n);
                        hud.row(n, String.format("m %s   F / F(Vega) %.2f", signed("%5.2f", b[4]), Math.pow(10, -0.4 * b[4])));
                    }
                    hud.formula("m₁ − m₂ = −2.5 log₁₀(F₁ / F₂)");
                }));

        s.add(step("Vega is zero", """
                The modern scale is pinned to one star: **Vega** is defined to have magnitude 0 in
                every band. Its flux f₀ depends on the band. For the total (bolometric) flux the
                slides use f₀ = 2.518 × 10⁻⁸ W/m². Then any star's magnitude is

                $ m = −2.5 log₁₀(F / f₀)

                Vega sits almost overhead on September evenings. Here it is with the rest of the Summer
                Triangle (Altair and Deneb) and Arcturus, setting in the west.
                """)
                .sky(() -> new double[]{285, 55, 120})
                .enter(() -> {
                    utm(); host.goToUTC(2026, 9, 22, 1, 0); host.setTime(0);
                    host.skyLayers(false, false, true, true);
                })
                .scene((r, hud, t) -> {
                    labelStars(r, SUMMER);
                    hud.title("Measured against Vega", SUN_C);
                    for (String n : SUMMER) {
                        double[] b = host.skyBody(n);
                        double ratio = Math.pow(10, -0.4 * b[4]);
                        hud.row(n, String.format("m %s   F / F(Vega) %.2f", signed("%5.2f", b[4]), ratio));
                    }
                    hud.formula("m = −2.5 log₁₀(F / f₀)");
                }));

        s.add(step("Planets change brightness", """
                A star's brightness is fixed; a planet's is not. Sunlight falls off as 1 / r² on the
                way to the planet, and the reflected light falls off as 1 / Δ² on the way back to us,
                so a planet's magnitude contains 5 log₁₀(r Δ), plus a term for how much of its lit
                face we see.

                Mars is brightening now: it reaches **opposition on February 19, 2027**, when Earth
                passes between it and the Sun. The inset shows its real magnitude over the coming
                year: it climbs by more than two magnitudes, a factor of about 8 in brightness.
                """)
                .beyondSlides()                 // planet phases and 5 log(rΔ) aren't in the slides
                .unit(AU)
                .cam(() -> new double[]{0, 0, 1.9 * AU, 25, 0})
                .enter(() -> {
                    host.showOnly(null, "Earth", "Mars"); host.setOrbits(true, true); host.setTime(5 * DAY);
                    host.goToUTC(2026, 10, 19, 12, 0);
                    traceMars();
                })
                .controls(c -> timeSlider(c, "days", DAY, 30))
                .scene((r, hud, t) -> {
                    double[] e = scaleV(pos(host.state("Earth")), 1 / AU), m = scaleV(pos(host.state("Mars")), 1 / AU);
                    r.line(e, m, alpha(PINK, 200), 1.6f, true);
                    double[] mb = host.skyBody("Mars");
                    r.label(lerp(e, m, 0.5), String.format("Δ = %.2f AU", mb[6]), PINK, 12f, 0, -14);
                    r.label(m, "Mars  " + signed("%.1f", mb[4]), ORANGE, 12.5f, 0, 18);
                    hud.title("Mars from Earth", ORANGE);
                    hud.row("date", host.dateString());
                    hud.row("Mars–Sun distance r", au(len(m)));
                    hud.row("Mars–Earth distance Δ", au(mb[6]), PINK);
                    hud.row("magnitude", signed("%.2f", mb[4]), ORANGE);
                    hud.formula("m = H + 5 log₁₀(r Δ) + phase term");
                    double now = (host.simTime() - marsT0) / DAY;
                    hud.inset("Mars's magnitude, next 12 months", 260, 170, (g, b) -> {
                        Plot p = new Plot(b, 0, 365, false, 2, -2.5, false);
                        p.axes(g, "days from Oct 19, 2026", "m", new double[]{0, 120, 240, 360}, new String[]{"0", "120", "240", "360"},
                                new double[]{2, 1, 0, -1, -2}, new String[]{"2", "1", "0", "−1", "−2"});
                        p.curve(g, marsT, marsM, ORANGE, 2f);
                        if (now >= 0 && now <= 365) p.vline(g, now, alpha(Color.WHITE, 160));
                        p.label(g, 123, -1.4, "opposition", DIM, -26, -6);
                    });
                }));

        s.add(step("Absolute magnitude", """
                Apparent magnitude m mixes up how luminous a star is with how far away it is. To
                compare stars fairly, imagine moving each one to a standard distance of **10
                parsecs**. Its magnitude there is its {k:absolute magnitude} M:

                $ m − M = 5 log₁₀(d / 10 pc)

                The Sun is magnitude −26.7 from Earth, but its absolute magnitude is only **+4.8**:
                from 10 pc it is a faint naked-eye star. Beyond about 22 pc you could not see it at all.

                > Move the observer away from the Sun.
                """)
                .unit(AU)
                .cam(() -> new double[]{0, 0, 1.8 * AU, 30, 0})
                .enter(() -> { host.showOnly(null, "Earth", "Mars", "Jupiter"); host.setOrbits(true, true); host.setTime(0); })
                .controls(c -> {
                    c.slider("Distance from the Sun", Math.log10(AU / PC), 3, () -> logD, x -> logD = x, x -> fmtDist(Math.pow(10, x)));
                    c.choices("Jump to", new String[]{"Earth", "Neptune", "α Centauri", "10 pc", "Pleiades"},
                            () -> -1, k -> logD = Math.log10(new double[]{AU / PC, 30 * AU / PC, 1.34, 10, 136}[k]));
                })
                .scene((r, hud, t) -> {
                    double d = Math.pow(10, logD), m = M_SUN_V + 5 * Math.log10(d / 10);
                    double show = Math.min(d * PC / AU, 1.6);
                    double[] you = v(-show * 0.6, show * 0.8, 0);
                    r.line(v(0, 0, 0), you, alpha(INK, 150), 1.4f, true);
                    r.dot(you, 5, ACCENT);
                    r.label(you, d * PC / AU > 1.6 ? "you, " + fmtDist(d) + " away (not to scale)" : "you", ACCENT, 12f, 0, -16);
                    hud.title("The Sun from far away", SUN_C);
                    hud.row("distance d", fmtDist(d));
                    hud.row("5 log₁₀(d / 10 pc)", signed("%.2f", 5 * Math.log10(d / 10)));
                    hud.row("apparent magnitude m", signed("%.2f", m), SUN_C);
                    hud.row("absolute magnitude M", "+4.83 (always)");
                    hud.formula("m = M + 5 log₁₀(d / 10 pc)");
                    hud.inset("The Sun among familiar stars", 280, 130, (g, b) -> sunAsStarInset(g, b, m));
                }));

        s.add(step("Colour: B − V", """
                Measure a star through a blue filter (B, 445 nm) and a visual one (V, 551 nm). The
                difference of the two magnitudes is its {k:colour}:

                $ B − V = −2.5 log₁₀(F(B) / F(V)) + constant

                A colour is a magnitude difference, so it is a **ratio of fluxes**. Moving a star farther
                away dims both bands by the same factor, so its colour does not change with distance; it
                depends on temperature. Red Betelgeuse has B − V = +1.85; blue-white Rigel, −0.03.
                """)
                .sky(() -> orionView)
                .enter(() -> {
                    utm(); host.goToUTC(2026, 1, 10, 3, 0); host.setTime(0);
                    host.skyLayers(false, false, true, true);
                    frameOrion();
                })
                .scene((r, hud, t) -> {
                    hud.title("Orion's two brightest", ACCENT);
                    for (String n : new String[]{"Betelgeuse", "Rigel"}) {
                        double[] b = host.skyBody(n);
                        double T = tFromBv(b[7]);
                        r.label(host.skyEnu(n), String.format("%s  B − V = %s", n, signed("%+.2f", b[7])), starColor(T), 12.5f, 0, -18);
                        hud.row(n, String.format("V %s   B − V %s   ≈ %,.0f K", signed("%.2f", b[4]), signed("%+.2f", b[7]), Math.round(T / 100) * 100.0), starColor(T));
                    }
                    hud.formula("colour = −2.5 log₁₀(F(B) / F(V)) + const");
                    hud.inset("Two blackbodies through B and V", 260, 170, MagnitudeLesson::colourInset);
                }));

        s.add(step("Spectral types: O B A F G K M", """
                In the 1890s the Harvard "computers" sorted hundreds of thousands of stellar spectra.
                **Williamina Fleming** lettered them A, B, C... by the strength of their hydrogen
                lines. **Annie Jump Cannon** and **Antonia Maury** reordered them by temperature, which
                gives the sequence we still use, from hottest to coolest:

                $ O  B  A  F  G  K  M

                ("Oh Be A Fine Girl/Guy, Kiss Me"). The Sun is a G star. Brown dwarfs extend it with L, T
                and Y.

                > Show one class at a time. Each star is placed in a class from its B − V colour. The
                inset counts the naked-eye stars in each: bright B stars and orange K giants dominate,
                though dim red M dwarfs are the commonest stars of all.
                """)
                .sky(() -> new double[]{170, 40, 115})
                .enter(() -> {
                    utm(); host.goToUTC(2026, 1, 10, 5, 0); host.setTime(0);
                    host.skyLayers(false, false, true, true);
                    if (classCount == null) countClasses();
                })
                .live(() -> {
                    final int k0 = cls;
                    host.skyOptics(6.5, Double.NaN, k0 == 0 ? null : bv -> classOf(bv) == k0);
                })
                .controls(c -> c.choices("Show", CLASSES, () -> cls, k -> cls = k))
                .scene((r, hud, t) -> {
                    hud.title(cls == 0 ? "All stars" : CLASSES[cls] + " stars", CLASS_C[cls]);
                    String[] range = {"", "B − V < −0.02", "−0.02 to 0.30", "0.30 to 0.58", "0.58 to 0.81", "0.81 to 1.40", "> 1.40"};
                    String[] temp = {"", "> 10,000 K", "7,500–10,000 K", "6,000–7,500 K", "5,200–6,000 K", "3,700–5,200 K", "< 3,700 K"};
                    String[] eg = {"", "Rigel, Spica", "Sirius, Vega", "Procyon, Polaris", "the Sun, Capella", "Arcturus, Aldebaran", "Betelgeuse, Antares"};
                    if (cls > 0) {
                        hud.row("colour", range[cls]);
                        hud.row("temperature", temp[cls]);
                        hud.row("examples", eg[cls]);
                    } else hud.row("pick a class", "on the right");
                    hud.inset("Naked-eye stars by class", 240, 150, (g, b) -> {
                        int max = 1;
                        for (int k = 1; k < 7; k++) max = Math.max(max, classCount[k]);
                        double bw = (b.width - 20) / 6.0;
                        g.setFont(LessonStudio.uiFont(Font.BOLD, 11f));
                        for (int k = 1; k < 7; k++) {
                            double h = (b.height - 34) * classCount[k] / (double) max;
                            double x = b.x + 10 + (k - 1) * bw;
                            g.setColor(alpha(CLASS_C[k], k == cls ? 255 : 150));
                            g.fill(new Rectangle2D.Double(x + 4, b.y + b.height - 18 - h, bw - 8, h));
                            g.setColor(DIM);
                            String lab = k == 1 ? "OB" : CLASSES[k];
                            g.drawString(lab, (float) (x + bw / 2 - g.getFontMetrics().stringWidth(lab) / 2.0), (float) (b.y + b.height - 4));
                            String num = String.valueOf(classCount[k]);
                            g.drawString(num, (float) (x + bw / 2 - g.getFontMetrics().stringWidth(num) / 2.0), (float) (b.y + b.height - 22 - h));
                        }
                    });
                }));

        s.add(step("Where absorption lines come from", """
                In the Bohr model of hydrogen, the electron can only have certain energies,
                E(n) = −13.6 eV / n², like standing waves that must fit around the nucleus. An atom
                absorbs a photon only if its energy exactly matches a jump between two levels. Jumps
                up from n = 2 absorb visible light: the {k:Balmer lines}, Hα at 656 nm first.

                Temperature decides which jumps happen (the Boltzmann and Saha equations):

                **A stars** (about 10,000 K, like Vega) have the strongest Balmer lines: many atoms
                sit in n = 2. **Hotter O and B stars** ionize most of their hydrogen, so the lines
                weaken. **Cool K and M stars** have few atoms in n = 2, but show broad bands from
                molecules such as TiO that would break apart in hotter stars.
                """)
                .sky(() -> new double[]{285, 55, 120})
                .enter(() -> {
                    utm(); host.goToUTC(2026, 9, 22, 1, 0); host.setTime(0);
                    host.skyLayers(false, false, true, true);
                })
                .scene((r, hud, t) -> {
                    r.label(host.skyEnu("Vega"), "Vega: type A0, the strongest Balmer lines", SUN_C, 12.5f, 0, -18);
                    hud.title("Hydrogen", ACCENT);
                    hud.row("Hα  n = 3 → 2", "656.3 nm");
                    hud.row("Hβ  n = 4 → 2", "486.1 nm");
                    hud.row("ionization from n = 1", "13.6 eV  (91.2 nm)");
                    hud.formula("E(n) = −13.6 eV / n²;   λ = hc / ΔE");
                    hud.inset("Energy levels of hydrogen", 270, 300, MagnitudeLesson::bohrInset);
                }));

        // AST101 checks on the one step it keeps; the AST221 checks below need magnitudes.
        s.add(step("Check: why only certain colours?", """
                Starlight passing through hydrogen gas comes out with a few thin dark lines: a few
                exact colours are missing, and every other colour gets through.
                """)
                .only("AST101")
                .sky(() -> new double[]{285, 55, 120})
                .enter(() -> {
                    utm(); host.goToUTC(2026, 9, 22, 1, 0); host.setTime(0);
                    host.skyLayers(false, false, true, true);
                })
                .quiz("Why does hydrogen absorb only certain colours?", 2,
                      "Hydrogen's electron can only sit at certain energy levels. A photon is absorbed only if its "
                    + "energy exactly matches a jump between two levels, and a photon's energy sets its colour, so only "
                    + "those few colours are taken out.",
                      "Hydrogen gas is red, so it blocks the other colours",
                      "Dust between the stars blocks those colours",
                      "Its electron can only jump between fixed energy levels",
                      "The star doesn't make light at those colours")
                .scene((r, hud, t) -> hud.inset("Energy levels of hydrogen", 270, 300, MagnitudeLesson::bohrInset)));

        s.add(step("Check: a fingerprint", """
                In the lab, hot hydrogen gas glows at exactly **656 nm** (red) and **486 nm**
                (blue-green). A star's spectrum shows dark lines at exactly **656 nm** and
                **486 nm**.
                """)
                .only("AST101")
                .sky(() -> new double[]{285, 55, 120})
                .enter(() -> {
                    utm(); host.goToUTC(2026, 9, 22, 1, 0); host.setTime(0);
                    host.skyLayers(false, false, true, true);
                })
                .quiz("What does this tell you about the star?", 0,
                      "An element absorbs the same colours it gives off when hot, the jumps between its energy levels. "
                    + "Every element has its own set, like a fingerprint, so lines at hydrogen's wavelengths mean there "
                    + "is hydrogen in the star's outer layers. This is how we know what stars are made of without "
                    + "visiting them.",
                      "The star's outer layers contain hydrogen", "The star is moving toward us",
                      "The star is exactly as hot as the lab gas", "The star is surrounded by a cloud of dust")
                .scene((r, hud, t) -> {
                    hud.title("Two spectra", ACCENT);
                    hud.row("hot hydrogen in the lab", "bright lines at 656 nm and 486 nm");
                    hud.row("the star", "dark lines at 656 nm and 486 nm", SUN_C);
                }));

        s.add(step("Check: the strongest hydrogen lines", """
                Four stars, from cool to hot. Remember that a star's temperature decides which
                jumps its hydrogen atoms can make.
                """)
                .only("AST101")
                .sky(() -> new double[]{285, 55, 120})
                .enter(() -> {
                    utm(); host.goToUTC(2026, 9, 22, 1, 0); host.setTime(0);
                    host.skyLayers(false, false, true, true);
                })
                .quiz("Which star shows the strongest hydrogen lines?", 2,
                      "Visible hydrogen lines come from atoms whose electron sits in the second level. A cool star has "
                    + "few atoms lifted that high; a very hot star strips the electron off altogether. In between, near "
                    + "10,000 K (A stars like Vega), the most atoms sit in the second level, so the lines are strongest.",
                      "A cool red star, 3,000 K", "The Sun, 5,800 K", "Vega, about 10,000 K", "A blue O star, 40,000 K")
                .scene((r, hud, t) -> {
                    hud.title("Four stars", ACCENT);
                    hud.row("cool red M star", "3,000 K", new Color(255, 190, 120));
                    hud.row("the Sun (G)", "5,800 K", new Color(255, 236, 200));
                    hud.row("Vega (A)", "about 10,000 K", new Color(230, 236, 255));
                    hud.row("blue O star", "40,000 K", new Color(170, 190, 255));
                }));

        s.add(step("Check: a bolometric magnitude", """
                A star has a bolometric flux of **2.5 × 10⁻⁹ W/m²** at Earth. Vega's bolometric flux is
                f₀ = **2.518 × 10⁻⁸ W/m²**.
                """)
                .sky(() -> new double[]{285, 55, 120})
                .enter(() -> {
                    utm(); host.goToUTC(2026, 9, 22, 1, 0); host.setTime(0);
                    host.skyLayers(false, false, true, true);
                })
                .quiz("What is its bolometric apparent magnitude?", 2,
                      "m = −2.5 log₁₀(2.5 × 10⁻⁹ / 2.518 × 10⁻⁸) = −2.5 log₁₀(0.0993) = −2.5 × (−1.003) ≈ +2.5. "
                    + "It is 10 times fainter than Vega, and a factor of 10 is 2.5 magnitudes.",
                      "−2.5", "+0.1", "+2.5", "+10")
                .scene((r, hud, t) -> labelStars(r, SUMMER)));

        s.add(step("Check: the Sun's absolute magnitude", """
                The Sun's apparent magnitude is **−26.8**. Use 1 AU = 1.5 × 10¹¹ m and 1 pc = 3 × 10¹⁶ m.
                """)
                .unit(AU)
                .cam(() -> new double[]{0, 0, 1.8 * AU, 30, 0})
                .enter(() -> { host.showOnly(null, "Earth"); host.setOrbits(true, true); host.setTime(0); })
                .quiz("What is the Sun's absolute magnitude?", 0,
                      "d = 1.5 × 10¹¹ / 3 × 10¹⁶ = 5 × 10⁻⁶ pc. M = m − 5 log₁₀(d / 10 pc) = −26.8 − 5 log₁₀(5 × 10⁻⁷) "
                    + "= −26.8 + 31.5 = +4.7.",
                      "About +4.7", "About −26.8", "About +31.5", "About −31.5")
                .scene((r, hud, t) -> {
                    double[] e = scaleV(pos(host.state("Earth")), 1 / AU);
                    r.line(v(0, 0, 0), e, alpha(SUN_C, 170), 1.6f, true);
                    r.label(lerp(v(0, 0, 0), e, 0.5), "d = 1 AU", SUN_C, 12.5f, 0, -16);
                    r.ring(e, 9, ACCENT, 2f);
                    r.labelBelow(v(0, 0, 0), R_SUN / AU, "the Sun from Earth: m = −26.8", SUN_C, 12.5f);
                }));

        s.add(step("Check: colour and distance", """
                Rigel is about 260 pc away. Imagine moving it to 2600 pc, ten times farther.
                """)
                .sky(() -> orionView)
                .enter(() -> {
                    utm(); host.goToUTC(2026, 1, 10, 3, 0); host.setTime(0);
                    host.skyLayers(false, false, true, true);
                    frameOrion();
                })
                .quiz("What happens to its V magnitude and its B − V colour?", 1,
                      "Its flux drops by 10² = 100 in every band, so V grows by 5 magnitudes (fainter). B and V "
                    + "both grow by 5, so B − V, a ratio of fluxes, stays the same: colour does not depend on "
                    + "distance (ignoring dust, which reddens starlight).",
                      "V and B − V both increase by 5", "V increases by 5; B − V stays the same",
                      "V stays the same; B − V increases", "Both stay the same")
                .scene((r, hud, t) -> r.label(host.skyEnu("Rigel"), "Rigel", starColor(tFromBv(-0.03)), 12.5f, 0, -18)));

        return new LessonStudio.LessonDef("Intermediate", "How Bright?",
                "Magnitudes, colours and spectral types",
                LessonStudio.SHOW_BEYOND_SLIDES
                    ? "Read magnitudes off the real winter sky, pin the scale on Vega, watch Mars brighten toward "
                    + "opposition, move the Sun to 10 parsecs, and sort the naked-eye stars into OBAFGKM."
                    : "Read magnitudes off the real winter sky, pin the scale on Vega, move the Sun to 10 "
                    + "parsecs, and sort the naked-eye stars into OBAFGKM.",
                new Color(190, 160, 255), s, _ -> { });
    }

    static String fmtDist(double pc) {
        double au = pc * PC / AU;
        if (au < 1000) return String.format("%.3g AU", au);
        if (pc < 0.1) return String.format("%,.0f AU", au);
        return String.format("%.3g pc", pc);
    }
}
