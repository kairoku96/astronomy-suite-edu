import java.awt.*;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Lesson: Gaps in the Asteroid Belt (AST101 Day 28). The real main belt, 877,256 numbered asteroids
 * from the JPL Small-Body Database binned by semi-major axis ({@code assets/asteroids/main_belt_a.csv},
 * written by {@code tools.FetchAsteroids}), with the Kirkwood gaps where an orbit would keep a
 * whole-number rhythm with the real Jupiter; and a test asteroid whose meetings with Jupiter pile up
 * in one place at a resonance and spread all round off it.
 */
final class KirkwoodLesson extends LessonKit {

    KirkwoodLesson(LessonStudio.Host host) { super(host); }

    /** Resonances as {asteroid orbits, Jupiter orbits}: 3:1, 5:2, 7:3, 2:1. */
    static final int[][] RES = {{3, 1}, {5, 2}, {7, 3}, {2, 1}};
    static final String[] PICKS = {"2:1 (3.28 AU)", "3:1 (2.50 AU)", "Off rhythm (3.05 AU)"};
    double[] binA, binN;            // histogram: bin start (AU) and count
    int total;
    double testA = 3.05;
    double lam0, tStart, prevD = Double.NaN;
    final List<double[][]> meets = new ArrayList<>();          // each pass: {the test asteroid, Jupiter} (AU)
    double[] camGoal = {0, 0, 5.8 * AU, 10, 0};

    void load() {
        if (binA != null) return;
        try {
            List<double[]> rows = new ArrayList<>();
            for (String line : Files.readAllLines(Assets.path("asteroids", "main_belt_a.csv"), StandardCharsets.UTF_8)) {
                if (line.startsWith("#") || line.startsWith("a_")) continue;
                String[] f = line.split(",");
                rows.add(new double[]{Double.parseDouble(f[0]), Double.parseDouble(f[1])});
            }
            binA = new double[rows.size()]; binN = new double[rows.size()];
            for (int i = 0; i < rows.size(); i++) { binA[i] = rows.get(i)[0]; binN[i] = rows.get(i)[1]; total += (int) binN[i]; }
        } catch (IOException | RuntimeException ex) {
            binA = new double[0]; binN = new double[0];
            System.err.println("Kirkwood lesson: can't read assets/asteroids/main_belt_a.csv: " + ex);
        }
    }

    double aJ() { return host.planetA(host.planet("Jupiter")) / AU; }
    /** Where an asteroid would go round p times per q orbits of Jupiter (Kepler III). */
    double resA(int[] r) { return aJ() * Math.pow((double) r[1] / r[0], 2.0 / 3); }
    double jupLon(double t) { double[] j = pos(host.stateAt("Jupiter", t)); return Math.atan2(j[1], j[0]); }

    void resetTest() {
        tStart = host.simTime();
        lam0 = jupLon(tStart) + Math.PI;
        prevD = Double.NaN;
        meets.clear();
    }
    /** The test asteroid on a circular orbit of radius testA (AU, scene units), moving at its Kepler speed. */
    double[] testPos(double t) {
        double P = Math.pow(testA, 1.5) * YEAR, l = lam0 + 2 * Math.PI * (t - tStart) / P;
        return v(testA * Math.cos(l), testA * Math.sin(l), 0);
    }

    static double[][] circle(double rad) {
        double[][] p = new double[160][];
        for (int i = 0; i < 160; i++) p[i] = v(rad * Math.cos(2 * Math.PI * i / 160), rad * Math.sin(2 * Math.PI * i / 160), 0);
        return p;
    }

    /** The real belt as a histogram, with the resonances marked (and the test asteroid's orbit, if any). */
    void histInset(Graphics2D g, Rectangle b, double mark) {
        double max = 0;
        for (double n : binN) max = Math.max(max, n);
        Plot p = new Plot(b, 2.0, 3.6, false, 0, max * 1.1, false);
        p.axes(g, "a (AU)", "asteroids", new double[]{2.0, 2.4, 2.8, 3.2, 3.6}, new String[]{"2.0", "2.4", "2.8", "3.2", "3.6"},
                new double[]{0, max}, new String[]{"0", String.format("%,.0f", max)});
        g.setColor(new Color(220, 180, 130, 200));
        for (int i = 0; i < binA.length; i++) {
            if (binA[i] < 2.0 || binA[i] >= 3.6) continue;
            double x0 = p.px(binA[i]), x1 = p.px(binA[i] + 0.01), y = p.py(binN[i]);
            g.fill(new Rectangle2D.Double(x0, y, Math.max(1, x1 - x0 - 0.3), p.plotBottom() - y));
        }
        g.setFont(LessonStudio.uiFont(Font.PLAIN, 10f));
        for (int[] r : RES) {
            double x = p.px(resA(r));
            g.setColor(new Color(120, 200, 255, 200));
            g.draw(new Line2D.Double(x, p.plotTop() + 12, x, p.plotBottom()));
            g.drawString(r[0] + ":" + r[1], (float) (x - 8), (float) (p.plotTop() + 10));
        }
        if (!Double.isNaN(mark)) {
            g.setColor(SUN_C);
            double x = p.px(mark);
            g.setStroke(new BasicStroke(2f));
            g.draw(new Line2D.Double(x, p.plotTop(), x, p.plotBottom()));
        }
    }

    LessonStudio.LessonDef build() {
        List<LessonStudio.Step> s = new ArrayList<>();

        s.add(step("Gaps in the asteroid belt", """
                Between Mars and Jupiter orbit hundreds of thousands of rocky {k:asteroids}. The chart
                counts the **877,256** numbered ones, real data from NASA's small-body database, by
                the size of their orbits.

                The belt is not smooth. It has sharp **gaps**, found by Daniel Kirkwood in 1866 when
                fewer than a hundred asteroids were known. The blue lines show where they fall: at
                exactly the distances where an asteroid would go around the Sun 3 times for every 1
                orbit of Jupiter (3:1), 5 for every 2, 7 for every 3, and 2 for every 1.

                > The rings on the map are the same gaps, around the real Sun, Mars and Jupiter.
                """)
                .watch(() -> v(0, -resA(RES[0]), 0))
                .unit(AU)
                .cam(() -> camGoal)
                .enter(() -> {
                    host.showOnly(null, "Mars", "Jupiter"); host.setOrbits(true, true); host.setTime(0.3 * YEAR);
                    load(); camGoal = new double[]{0, 0, 5.8 * AU, 10, 0};
                })
                .controls(c -> timeSlider(c, "years", YEAR, 2))
                .scene((r, hud, t) -> {
                    for (int[] rs : RES) {
                        r.polyline(circle(resA(rs)), true, new Color(120, 200, 255, 120), 1.2f, true);
                    }
                    r.label(v(0, -resA(RES[0]), 0), "3:1", new Color(120, 200, 255), 11.5f, 0, 12);
                    r.label(v(0, -resA(RES[3]), 0), "2:1", new Color(120, 200, 255), 11.5f, 0, 12);
                    hud.title("The real main belt", ACCENT);
                    hud.row("numbered asteroids, 1.8–3.7 AU", String.format("%,d", total));
                    for (int[] rs : RES)
                        hud.row(String.format("gap %d:%d", rs[0], rs[1]), String.format("%.2f AU", resA(rs)), new Color(120, 200, 255));
                    hud.formula("gap where P(Jupiter) / P(asteroid) = 3, 5/2, 7/3, 2");
                    hud.inset("Asteroids by orbit size (0.01 AU bins)", 330, 220, (g, b) -> histInset(g, b, Double.NaN));
                }));

        s.add(step("Why the gaps? A rhythm with Jupiter", """
                Jupiter is nearly a million times heavier than all the asteroids together, and every time it
                passes one it gives it a small tug. Usually the passes happen at a different place
                each time, so the tugs point every which way and cancel out.

                But an asteroid at a gap keeps a whole-number rhythm with Jupiter. At **2:1** it goes
                around exactly twice per Jupiter orbit, so Jupiter passes it at **the same spot**
                every time, and the tugs add up, orbit after orbit, until the asteroid is thrown onto
                a new path. That is how the gaps were swept clear. Kepler's third law says where they
                are:

                $ a(gap) = a(Jupiter) × (1/2)^(2/3) = 5.20 AU × 0.63 = 3.28 AU

                > The yellow dot is a test asteroid. Each red dot marks a pass by Jupiter. Pick an orbit
                and watch where the passes land.
                """)
                .watch(() -> testPos(host.simTime()))
                .unit(AU)
                .cam(() -> camGoal)
                .enter(() -> {
                    host.showOnly(null, "Jupiter"); host.setOrbits(true, true); host.setTime(0.6 * YEAR);
                    load(); resetTest(); camGoal = new double[]{0, 0, 5.8 * AU, 6, 0};
                })
                .controls(c -> {
                    c.choices("Test asteroid", PICKS, () -> Math.abs(testA - resA(RES[3])) < 0.005 ? 0 : Math.abs(testA - resA(RES[0])) < 0.005 ? 1
                            : Math.abs(testA - 3.05) < 0.005 ? 2 : -1, k -> { testA = k == 0 ? resA(RES[3]) : k == 1 ? resA(RES[0]) : 3.05; resetTest(); });
                    c.slider("Its orbit a", 2.0, 3.6, () -> testA, x -> { testA = x; resetTest(); }, x -> String.format("%.2f AU", x));
                    timeSlider(c, "years", YEAR, 2);
                })
                .scene((r, hud, t) -> {
                    double now = host.simTime();
                    double[] ap = testPos(now), jp = scaleV(pos(host.state("Jupiter")), 1 / AU);
                    double d = Math.IEEEremainder(Math.atan2(ap[1], ap[0]) - Math.atan2(jp[1], jp[0]), 2 * Math.PI);
                    if (!Double.isNaN(prevD) && prevD < 0 && d >= 0 && d - prevD < Math.PI) {
                        meets.add(new double[][]{ap, jp});
                        if (meets.size() > 40) meets.remove(0);
                    }
                    prevD = d;
                    r.polyline(circle(testA), true, alpha(SUN_C, 90), 1.2f, true);
                    for (double[][] m : meets) {
                        r.dot(m[0], 4.5, alpha(PERI_C, 220));
                        r.line(m[0], m[1], alpha(PERI_C, 50), 1f, false);                // to where Jupiter really was
                    }
                    r.dot(ap, 6, SUN_C);
                    double P = Math.pow(testA, 1.5), PJ = Math.pow(aJ(), 1.5), ratio = PJ / P;
                    hud.title("A test asteroid at " + String.format("%.2f AU", testA), SUN_C);
                    hud.row("its year (Kepler III: P = a^1.5)", String.format("%.2f years", P));
                    hud.row("Jupiter's year", String.format("%.2f years", PJ));
                    hud.row("orbits per Jupiter orbit", String.format("%.3f", ratio), ACCENT);
                    hud.row("passes by Jupiter so far", String.valueOf(meets.size()), PERI_C);
                    hud.formula("same spot every pass → the tugs add up → the orbit is cleared");
                    hud.inset("The real belt, and this orbit", 330, 200, (g, b) -> histInset(g, b, testA));
                }));

        s.add(step("Check: where is the 2:1 gap?", """
                Jupiter orbits at **5.2 AU** and takes **11.9 years**. Kepler's third law: P² = a³,
                with P in years and a in AU.
                """)
                .reviews("Why the gaps? A rhythm with Jupiter")
                .unit(AU)
                .cam(() -> camGoal)
                .enter(() -> { host.showOnly(null, "Mars", "Jupiter"); host.setOrbits(true, true); host.setTime(0); load(); camGoal = new double[]{0, 0, 5.8 * AU, 10, 0}; })
                .quiz("Where would an asteroid go around exactly twice per Jupiter orbit?", 1,
                      "Its year must be 11.9 / 2 = 5.9 years. Then a = P^(2/3) = 5.9^(2/3) = 3.3 AU, the 2:1 gap. "
                    + "Halving the distance (2.6 AU) isn't right: the period goes as a^1.5, not as a.",
                      "2.6 AU, half Jupiter's distance", "About 3.3 AU", "About 4.1 AU", "About 1.5 AU")
                .scene((r, hud, t) -> hud.inset("Asteroids by orbit size", 330, 220, (g, b) -> histInset(g, b, Double.NaN))));

        s.add(step("Check: an empty orbit", """
                Hardly any asteroids orbit at **2.50 AU**, where an asteroid goes around 3 times for
                every orbit of Jupiter.
                """)
                .reviews("Why the gaps? A rhythm with Jupiter")
                .unit(AU)
                .cam(() -> camGoal)
                .enter(() -> { host.showOnly(null, "Mars", "Jupiter"); host.setOrbits(true, true); host.setTime(0); load(); camGoal = new double[]{0, 0, 5.8 * AU, 10, 0}; })
                .quiz("Why is that orbit nearly empty?", 2,
                      "At 3:1 Jupiter passes the asteroid at the same places every time, so its tugs build up instead of "
                    + "cancelling. Over time they stretch the orbit until it crosses Mars's or Earth's and the asteroid is "
                    + "thrown out. Some of the meteorites that land on Earth started there.",
                      "No asteroids ever formed there", "The Sun's heat evaporated them",
                      "Jupiter's repeated tugs at the same spot changed their orbits", "They crashed into Mars")
                .scene((r, hud, t) -> hud.inset("Asteroids by orbit size", 330, 220, (g, b) -> histInset(g, b, Double.NaN))));

        return new LessonStudio.LessonDef("Intermediate", "Gaps in the Asteroid Belt",
                "Kirkwood gaps and resonance with Jupiter",
                "Count 877,256 real asteroids by orbit size, find the gaps where an orbit would keep a "
              + "whole-number rhythm with Jupiter, and watch Jupiter's tugs pile up at a resonance.",
                new Color(220, 180, 130), s, _ -> { });
    }
}
