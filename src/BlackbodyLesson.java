import java.awt.*;
import java.awt.geom.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Lesson: Hot Bodies Glow (AST221 Day 5). Planck's law and Wien's law for the real Sun, the
 * Stefan–Boltzmann law as a star beside the Sun, each planet's equilibrium temperature from its
 * live distance, how an eccentric orbit changes it, and where an Earth-like orbit would be around
 * a cooler, smaller star.
 */
final class BlackbodyLesson extends LessonKit {

    BlackbodyLesson(LessonStudio.Host host) { super(host); }

    static final String[] PLANETS = {"Mercury", "Venus", "Earth", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune"};
    /** Bond albedos (NASA planetary fact sheets). */
    static final double[] ALBEDO = {0.088, 0.76, 0.306, 0.250, 0.343, 0.342, 0.300, 0.290};
    /** Measured temperatures (K): mean surface for the rocky planets, effective (emitted) for the giants. */
    static final double[] T_MEAS = {440, 737, 288, 210, 124, 95, 59, 59};
    static final String[] MEAS_NOTE = {"mean surface", "surface: greenhouse", "mean surface", "mean surface",
            "effective: inner heat", "effective: inner heat", "effective", "effective: inner heat"};

    double temp = T_SUN;            // what-if temperature (K)
    double radius = 1;              // what-if radius (solar radii)
    int zone;                       // 0 = inner planets, 1 = outer planets

    /** Equilibrium temperature of a fast-rotating planet with Bond albedo A at distance d from a star (T, R). */
    static double tEq(double T, double R, double A, double d) { return T * Math.pow(1 - A, 0.25) * Math.sqrt(R / (2 * d)); }
    /** Distance where a planet with albedo A comes to temperature Te. */
    static double dFor(double T, double R, double A, double Te) { return R / 2 * sq(T * Math.pow(1 - A, 0.25) / Te); }

    static double[] nearSun(double radiusRs, double pitch, double yaw) { return new double[]{0, 0, radiusRs * R_SUN, pitch, yaw}; }

    /** Centre of the what-if star beside the Sun (solar radii): clear of the Sun at any size. */
    double starX() { return 1 + 0.9 + 1.25 * radius; }
    /** Frame the Sun and the what-if star together. */
    double[] pairView() {
        double x = starX(), left = -1.2, right = x + 1.15 * radius;
        return new double[]{(left + right) / 2 * R_SUN, 0, Math.max(2.4, 0.62 * (right - left)) * R_SUN, 8, 0};
    }

    void sunOnly() { host.showOnly(null); host.setOrbits(false, false); host.setTime(DAY); }

    /** Planck curves on log axes, with the visible band, the Sun's curve and a what-if curve. */
    void planckInset(Graphics2D g, Rectangle b, double T) {
        Plot p = new Plot(b, 100, 3000, false, 1e9, 1e17, true);
        // The visible band, as a rainbow behind the curves.
        for (int nm = 380; nm < 750; nm += 5) {
            g.setColor(alpha(Color.getHSBColor((float) (0.75 * (750 - nm) / 370.0), 0.9f, 0.9f), 55));
            g.fill(new Rectangle2D.Double(p.px(nm), p.plotTop(), p.px(nm + 5) - p.px(nm) + 0.5, p.plotBottom() - p.plotTop()));
        }
        p.axes(g, "λ (nm)", "Bλ (W m⁻² m⁻¹ sr⁻¹)", new double[]{500, 1000, 2000, 3000}, new String[]{"500", "1000", "2000", "3000"},
                new double[]{1e10, 1e12, 1e14, 1e16}, new String[]{"10¹⁰", "10¹²", "10¹⁴", "10¹⁶"});
        int n = 240;
        double[] xs = new double[n], sun = new double[n], wi = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = 100 + 2900.0 * i / (n - 1);
            sun[i] = planck(xs[i] * 1e-9, T_SUN);
            wi[i] = planck(xs[i] * 1e-9, T);
        }
        p.curve(g, xs, sun, alpha(SUN_C, 150), 1.4f);
        p.curve(g, xs, wi, starColor(T), 2.4f);
        double peak = WIEN / T * 1e9;
        if (peak > 100 && peak < 3000) {
            p.vline(g, peak, alpha(Color.WHITE, 140));
            p.label(g, peak, planck(peak * 1e-9, T), String.format("λmax = %.0f nm", peak), Color.WHITE, 6, -4);
        }
        p.label(g, 2200, planck(2200e-9, T_SUN), "the Sun, 5772 K", alpha(SUN_C, 220), -40, 16);
        g.setColor(starColor(T));
        g.fill(new Ellipse2D.Double(p.plotRight() - 30, p.plotTop() + 6, 22, 22));
    }

    /** Normalised blackbody curves of the Sun and of Earth (255 K) on a logarithmic wavelength axis. */
    static void sunEarthInset(Graphics2D g, Rectangle b) {
        Plot p = new Plot(b, 0.1, 100, true, 0, 1.1, false);
        p.axes(g, "λ (µm)", null, new double[]{0.1, 1, 10, 100}, new String[]{"0.1", "1", "10", "100"},
                new double[]{}, new String[]{});
        int n = 200;
        double[] xs = new double[n], s = new double[n], e = new double[n];
        double ps = planck(WIEN / T_SUN, T_SUN), pe = planck(WIEN / 255, 255);
        for (int i = 0; i < n; i++) {
            xs[i] = 0.1 * Math.pow(1000, i / (n - 1.0));
            // λ B_λ per log-wavelength interval peaks near the same place as B_λ; plot B_λ / its peak.
            s[i] = planck(xs[i] * 1e-6, T_SUN) / ps;
            e[i] = planck(xs[i] * 1e-6, 255) / pe;
        }
        p.curve(g, xs, s, SUN_C, 2f);
        p.curve(g, xs, e, ACCENT, 2f);
        p.label(g, 0.95, 0.9, "Sun, 5772 K", SUN_C, 4, 0);
        p.label(g, 20, 0.9, "Earth, 255 K", ACCENT, 4, 0);
        p.label(g, 0.1, 1.05, "each curve scaled to peak at 1", DIM, 4, 0);
    }

    LessonStudio.LessonDef build() {
        List<LessonStudio.Step> s = new ArrayList<>();

        s.add(step("The Sun is (nearly) a blackbody", """
                A {k:blackbody} absorbs all light that falls on it and, in thermal equilibrium,
                re-radiates the energy with a spectrum that depends **only on its temperature**:
                Planck's law,

                $ Bλ(T) = (2hc² / λ⁵) / (e^(hc/λkT) − 1)

                Stars are close to blackbodies. The inset shows the curve for the real Sun (5772 K) and
                for any temperature you choose. Hotter bodies are brighter at **every** wavelength, and
                their peak moves to shorter wavelengths, {k:Wien's law}:

                $ λmax = 2.898 × 10⁻³ m·K / T

                That is why cool stars look red and hot stars blue-white. A room-temperature object
                (300 K) peaks at 10 µm, far in the infrared, which is why a cold blackbody looks black.
                """)
                .unit(R_SUN)
                .cam(() -> nearSun(4.5, 55, 25))
                .enter(() -> { sunOnly(); temp = T_SUN; })
                .controls(c -> {
                    c.slider("Temperature T", 2000, 30000, () -> temp, x -> temp = x, x -> String.format("%,.0f K", x));
                    c.button("Reset to the Sun (5772 K)", () -> temp = T_SUN);
                })
                .scene((r, hud, t) -> {
                    r.labelBelow(v(0, 0, 0), 1, "the Sun: 5772 K", SUN_C, 12.5f);
                    double F = SIGMA * Math.pow(temp, 4), L = 4 * Math.PI * R_SUN * R_SUN * F;
                    hud.title("A blackbody at " + String.format("%,.0f K", temp), starColor(temp));
                    hud.row("peak wavelength λmax", String.format("%.0f nm", WIEN / temp * 1e9), ACCENT);
                    hud.row("surface flux σT⁴", sci(F, 2) + " W/m²");
                    hud.row("luminosity at the Sun's size", sci(L, 2) + " W  = " + String.format("%.3g L(Sun)", L / L_SUN));
                    hud.formula("λmax T = 2.898 × 10⁻³ m·K");
                    hud.inset("Planck curves", 290, 220, (g, b) -> planckInset(g, b, temp));
                }));

        s.add(step("Stefan–Boltzmann: hot stars are fierce", """
                Add up a blackbody's emission over all wavelengths and directions and you get its
                surface flux, the {k:Stefan–Boltzmann law}:

                $ F = σ T⁴,   σ = 5.67 × 10⁻⁸ W m⁻² K⁻⁴

                Double the temperature and each square metre shines 2⁴ = 16 times brighter. A star's
                luminosity is that flux times its surface area:

                $ L = 4π R² σ T⁴

                The star beside the Sun is yours to set. The slides' example: an M dwarf at 3000 K
                with 0.1 times the Sun's radius, against the Sun at about 6000 K.
                """)
                .unit(R_SUN)
                .cam(this::pairView)
                .enter(() -> { sunOnly(); temp = 3000; radius = 0.1; })
                .controls(c -> {
                    c.slider("Star's temperature", 2500, 12000, () -> temp, x -> temp = x, x -> String.format("%,.0f K", x));
                    c.slider("Star's radius", 0.1, 3, () -> radius, x -> radius = x, x -> String.format("%.2f solar radii", x));
                    c.button("The slides' M dwarf: 3000 K, 0.1 solar radii", () -> { temp = 3000; radius = 0.1; });
                    c.button("A Sun twin", () -> { temp = T_SUN; radius = 1; });
                })
                .scene((r, hud, t) -> {
                    double[] c0 = v(starX(), 0, 0);
                    Color col = starColor(temp);
                    r.glow(c0, radius * 1.8, alpha(col, 90), 0.5);
                    int n = radius > 0.8 ? 30 : 14;
                    r.sphere(c0, radius, n, 2 * n, (nx, ny, nz, mu) -> LessonStudio.scale(col, 0.55 + 0.45 * mu));
                    r.labelBelow(c0, radius, String.format("%,.0f K, %.2f solar radii", temp, radius), col, 12.5f);
                    r.labelBelow(v(0, 0, 0), 1, "the Sun: 5772 K", SUN_C, 12.5f);
                    double fr = Math.pow(temp / T_SUN, 4), lr = radius * radius * fr;
                    hud.title("The star vs the Sun", col);
                    hud.row("temperature ratio T / T(Sun)", String.format("%.3f", temp / T_SUN));
                    hud.row("surface flux ratio (T / T(Sun))⁴", fmtRatio(fr), ACCENT);
                    hud.row("area ratio (R / R(Sun))²", fmtRatio(radius * radius));
                    hud.row("luminosity ratio L / L(Sun)", fmtRatio(lr), ACCENT);
                    hud.formula("L = 4πR² σT⁴");
                }));

        s.add(step("How warm is each planet?", """
                A planet absorbs sunlight on its disc (area πR²) and re-radiates as a blackbody from its
                whole surface (4πR²). Balancing the two gives its {k:equilibrium temperature}:

                $ T(eq) = T(Sun) × (1 − A)^¼ × √(R(Sun) / 2d)

                where A is the fraction of sunlight it reflects (its albedo) and d its distance. The labels
                show T(eq) from each planet's real distance today.

                Compare the measured temperatures. Venus is far hotter (a runaway greenhouse); Earth is
                33 K warmer (a mild greenhouse); Jupiter, Saturn and Neptune give off extra heat from
                inside. Planets are so cool that they glow in the **infrared**, at about 10–50 µm.
                """)
                .beyondSlides()                 // planet temperatures, albedo and the greenhouse effect aren't in the slides
                .unit(AU)
                .cam(() -> zone == 0 ? new double[]{0, 0, 1.7 * AU, 35, 0} : new double[]{0, 0, 31 * AU, 40, 0})
                .enter(() -> { host.showOnly(null, PLANETS); host.setOrbits(true, true); host.setTime(5 * DAY); })
                .controls(c -> c.choices("Show", new String[]{"Inner planets", "Outer planets"}, () -> zone, k -> zone = k))
                .scene((r, hud, t) -> {
                    hud.title("Equilibrium vs measured", ACCENT);
                    for (int i = 0; i < 8; i++) {
                        double[] p = pos(host.state(PLANETS[i]));
                        double d = len(p), Te = tEq(T_SUN, R_SUN, ALBEDO[i], d);
                        if ((zone == 0) == (i < 4)) r.label(scaleV(p, 1 / AU), String.format("%.0f K", Te), ORANGE, 12f, 0, 18);
                        hud.row(PLANETS[i], String.format("%.2f AU   T(eq) %3.0f K   measured %3.0f K", d / AU, Te, T_MEAS[i]),
                                Math.abs(T_MEAS[i] - Te) > 40 ? ORANGE : INK);
                    }
                    hud.formula("T(eq) = T(Sun) (1 − A)^¼ √(R(Sun) / 2d)");
                    hud.inset("Sun vs Earth: where each one glows", 260, 170, BlackbodyLesson::sunEarthInset);
                }));

        s.add(step("An eccentric orbit: Mars's seasons of distance", """
                Mars's orbit is noticeably eccentric (e = 0.093), so its distance from the Sun swings
                from 1.38 AU to 1.67 AU. Since T(eq) ∝ 1 / √d, its equilibrium temperature swings
                too:

                $ T(perihelion) / T(aphelion) = √(Q / q) ≈ 1.10

                about 20 K. Sunlight at Mars is 45% stronger at perihelion, which makes southern summer
                (near perihelion) the season of global dust storms.
                """)
                .beyondSlides()                 // equilibrium temperature isn't in the slides
                .unit(AU)
                .cam(() -> new double[]{0, 0, 1.9 * AU, 30, 0})
                .enter(() -> { host.showOnly(null, "Earth", "Mars"); host.setOrbits(true, true); host.setTime(20 * DAY); })
                .controls(c -> timeSlider(c, "days", DAY, 40))
                .scene((r, hud, t) -> {
                    int i = host.planet("Mars");
                    double a = host.planetA(i), e = host.planetEcc(i);
                    double[] P = scaleV(host.planetOrbit(i, 0), 1 / AU), Q = scaleV(host.planetOrbit(i, Math.PI), 1 / AU);
                    double[] m = scaleV(pos(host.state("Mars")), 1 / AU);
                    double d = len(m) * AU, Tq = tEq(T_SUN, R_SUN, ALBEDO[3], a * (1 - e)), TQ = tEq(T_SUN, R_SUN, ALBEDO[3], a * (1 + e));
                    r.line(v(0, 0, 0), P, PERI_C, 2.4f, false);
                    r.line(v(0, 0, 0), Q, APO_C, 2.4f, false);
                    r.label(P, String.format("perihelion: %.0f K", Tq), PERI_C, 12.5f, 0, -18);
                    r.label(Q, String.format("aphelion: %.0f K", TQ), APO_C, 12.5f, 0, -18);
                    r.label(m, String.format("%.0f K", tEq(T_SUN, R_SUN, ALBEDO[3], d)), ORANGE, 12.5f, 0, 18);
                    hud.title("Mars", ORANGE);
                    hud.row("distance now", au(d / AU));
                    hud.row("T(eq) now", String.format("%.1f K", tEq(T_SUN, R_SUN, ALBEDO[3], d)), ORANGE);
                    hud.row("at perihelion  q = " + au(a * (1 - e) / AU), String.format("%.1f K", Tq), PERI_C);
                    hud.row("at aphelion  Q = " + au(a * (1 + e) / AU), String.format("%.1f K", TQ), APO_C);
                    hud.row("sunlight at q vs Q  (Q / q)²", String.format("%.2f×", sq((1 + e) / (1 - e))));
                    hud.formula("T(eq) ∝ 1 / √d");
                }));

        s.add(step("Around a different star", """
                Put a different star in the Sun's place. Its luminosity L = 4πR²σT⁴ sets where a planet
                is comfortable. The green ring is where an Earth-like planet (albedo 0.3) would sit at
                Earth's 255 K:

                $ d = (R / 2) × (T (1 − A)^¼ / 255 K)²

                For the Sun that is 1 AU. Around the slides' M dwarf (3000 K, 0.1 solar radii, about 1/1400 of
                the Sun's luminosity) it shrinks to **0.027 AU**, fourteen times closer than Mercury.
                Planets that close are locked with one face to their star, like the Moon to Earth.
                """)
                .beyondSlides()                 // equilibrium temperature and habitable zones aren't in the slides
                .unit(AU)
                .cam(() -> {
                    double d = dFor(temp, radius * R_SUN, ALBEDO[2], 255) / AU;
                    return new double[]{0, 0, clamp(1.5 * d, 0.06, 20) * AU, 30, 0};
                })
                .enter(() -> {
                    host.showOnly(null, "Mercury", "Venus", "Earth", "Mars"); host.setOrbits(true, true); host.setTime(5 * DAY);
                    temp = 3000; radius = 0.1;
                })
                .controls(c -> {
                    c.slider("Star's temperature", 2500, 10000, () -> temp, x -> temp = x, x -> String.format("%,.0f K", x));
                    c.slider("Star's radius", 0.1, 3, () -> radius, x -> radius = x, x -> String.format("%.2f solar radii", x));
                    c.button("The slides' M dwarf: 3000 K, 0.1 solar radii", () -> { temp = 3000; radius = 0.1; });
                    c.button("The Sun", () -> { temp = T_SUN; radius = 1; });
                })
                .scene((r, hud, t) -> {
                    double d = dFor(temp, radius * R_SUN, ALBEDO[2], 255) / AU;
                    int n = 140;
                    double[][] ring = new double[n][];
                    for (int i = 0; i < n; i++) ring[i] = v(d * Math.cos(2 * Math.PI * i / n), d * Math.sin(2 * Math.PI * i / n), 0);
                    r.polyline(ring, true, GREEN, 2.4f, true);
                    r.label(ring[n / 8], String.format("255 K at %.3g AU", d), GREEN, 12.5f, 0, -16);
                    r.labelBelow(v(0, 0, 0), R_SUN / AU, String.format("the real Sun; your star would be %.2f× its size", radius), DIM, 11.5f);
                    double L = radius * radius * Math.pow(temp / T_SUN, 4);
                    hud.title("A what-if star", starColor(temp));
                    hud.row("temperature / radius", String.format("%,.0f K  /  %.2f solar radii", temp, radius));
                    hud.row("luminosity", fmtRatio(L) + " the Sun's", ACCENT);
                    hud.row("Earth-like orbit (255 K)", String.format("%.3g AU", d), GREEN);
                    hud.gap();
                    for (int i = 0; i < 4; i++) {
                        double dp = len(pos(host.state(PLANETS[i])));
                        hud.row(PLANETS[i] + " would be", String.format("%.0f K", tEq(temp, radius * R_SUN, ALBEDO[i], dp)));
                    }
                    hud.formula("L = 4πR²σT⁴");
                }));

        s.add(step("Check: surface flux", """
                An M dwarf has a temperature of **3000 K**. A Sun-like G star has **6000 K**.
                """)
                .unit(R_SUN)
                .cam(() -> new double[]{1.9 * R_SUN, 0, 4.2 * R_SUN, 8, 0})
                .enter(() -> { sunOnly(); temp = 3000; radius = 0.1; })
                .quiz("How does the G star's surface flux compare with the M dwarf's?", 2,
                      "F = σT⁴, so F(G) / F(M) = (6000 / 3000)⁴ = 2⁴ = 16. Each square metre of the G star "
                    + "radiates 16 times more.",
                      "2 times", "4 times", "16 times", "1600 times")
                .scene((r, hud, t) -> {
                    double[] c0 = v(3.8, 0, 0);
                    Color col = starColor(3000);
                    r.sphere(c0, 0.1, 10, 20, (nx, ny, nz, mu) -> LessonStudio.scale(col, 0.55 + 0.45 * mu));
                    r.labelBelow(c0, 0.1, "M dwarf", col, 12.5f);
                    r.labelBelow(v(0, 0, 0), 1, "G star (like the Sun)", SUN_C, 12.5f);
                }));

        s.add(step("Check: luminosity", """
                Now include their sizes: the M dwarf (3000 K) has **0.1** times the Sun's radius; the G
                star (6000 K) has **1** solar radius.
                """)
                .unit(R_SUN)
                .cam(() -> new double[]{1.9 * R_SUN, 0, 4.2 * R_SUN, 8, 0})
                .enter(() -> { sunOnly(); temp = 3000; radius = 0.1; })
                .quiz("How much more luminous is the G star?", 3,
                      "L = 4πR²σT⁴, so L(G) / L(M) = (1 / 0.1)² × (6000 / 3000)⁴ = 100 × 16 = 1600.",
                      "16 times", "100 times", "160 times", "1600 times")
                .scene((r, hud, t) -> {
                    double[] c0 = v(3.8, 0, 0);
                    Color col = starColor(3000);
                    r.sphere(c0, 0.1, 10, 20, (nx, ny, nz, mu) -> LessonStudio.scale(col, 0.55 + 0.45 * mu));
                    r.labelBelow(c0, 0.1, "M dwarf: 3000 K, 0.1 solar radii", col, 12.5f);
                    r.labelBelow(v(0, 0, 0), 1, "G star: 6000 K, 1 solar radius", SUN_C, 12.5f);
                }));

        s.add(step("Check: the Sun's colour", """
                The Sun's surface is at about **5800 K**.
                """)
                .reviews("The Sun is (nearly) a blackbody")
                .unit(R_SUN)
                .cam(() -> nearSun(4.5, 55, 25))
                .enter(() -> { sunOnly(); temp = 5800; })
                .quiz("Where does its spectrum peak?", 1,
                      "λmax = 2.898 × 10⁻³ m·K / 5800 K = 5.0 × 10⁻⁷ m = 500 nm: blue-green, right in the "
                    + "middle of the visible band (380–750 nm). Our eyes evolved to use the light the Sun "
                    + "gives most of.",
                      "About 50 nm, ultraviolet", "About 500 nm, in the visible", "About 5 µm, infrared", "About 1 mm, radio")
                .scene((r, hud, t) -> hud.inset("Planck curve", 290, 220, (g, b) -> planckInset(g, b, 5800))));

        return new LessonStudio.LessonDef("Intermediate", "Hot Bodies Glow",
                "Blackbodies: Planck, Wien and Stefan–Boltzmann",
                LessonStudio.SHOW_BEYOND_SLIDES
                    ? "Read the Sun's temperature off its spectrum, put a red dwarf beside it, take the "
                    + "temperature of every planet from its real distance, and move the Earth-like zone "
                    + "around a cooler star."
                    : "Read the Sun's temperature off its spectrum with Planck's and Wien's laws, then put a "
                    + "red dwarf beside it and compare their surface fluxes and luminosities.",
                new Color(255, 120, 90), s, _ -> { });
    }

    static String fmtRatio(double x) {
        return x >= 100 ? String.format("%,.0f×", x) : x >= 0.01 ? String.format("%.3g×", x) : sci(x, 2) + "×";
    }
}
