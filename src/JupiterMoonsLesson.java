import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Lesson: Jupiter's Moons in Step (AST101 Days 25–27). The four Galilean moons on their real orbits:
 * Kepler's third law around Jupiter, and the 1 : 2 : 4 resonance of Io, Europa and Ganymede, shown by
 * where their meetings fall and by the Laplace angle λ_Io − 3λ_Europa + 2λ_Ganymede, which the real
 * moons hold at 180°.
 */
final class JupiterMoonsLesson extends LessonKit {

    JupiterMoonsLesson(LessonStudio.Host host) { super(host); }

    static final double MMU = 1e6;                                       // scene unit: 1000 km
    static final String[] MOONS = WeighLesson.GALILEAN;
    static final double[] P = WeighLesson.P_GAL;
    static final Color[] MOON_C = WeighLesson.MOON_C;
    double[][][] paths = new double[4][][];                              // each moon's orbit about Jupiter (m)
    double t0;                                                           // when the step began (sim s)
    double prevIE = Double.NaN, prevEG = Double.NaN;                     // last frame's angle between the pairs
    final List<double[]> meetIE = new ArrayList<>(), meetEG = new ArrayList<>();   // where the pairs met (m, about Jupiter)
    double[] camGoal = {0, 0, 2.2e9, 12, 0};

    double[] rel(String moon, double t) { return sub(pos(host.stateAt(moon, t)), pos(host.stateAt("Jupiter", t))); }
    static double lon(double[] p) { return Math.atan2(p[1], p[0]); }

    void trace() {
        double t = host.simTime();
        for (int k = 0; k < 4; k++) {
            int n = 120;
            paths[k] = new double[n][];
            for (int i = 0; i < n; i++) paths[k][i] = rel(MOONS[k], t + P[k] * DAY * i / n);
        }
    }

    void start() {
        t0 = host.simTime();
        prevIE = prevEG = Double.NaN;
        meetIE.clear(); meetEG.clear();
    }

    void drawOrbits(LessonStudio.R3 r, double[] jk, int n) {
        for (int k = 0; k < n; k++) {
            double[][] p = new double[paths[k].length][];
            for (int i = 0; i < p.length; i++) p[i] = add(jk, scaleV(paths[k][i], 1 / MMU));
            r.polyline(p, true, alpha(MOON_C[k], 110), 1.3f, false);
        }
    }

    /** Record where a pair of moons meets (the inner one passing the outer), as the angle between them wraps. */
    double meet(double prev, double[] inner, double[] outer, List<double[]> out) {
        double d = Math.IEEEremainder(lon(inner) - lon(outer), 2 * Math.PI);
        if (!Double.isNaN(prev) && prev < 0 && d >= 0 && d - prev < Math.PI) {
            out.add(scaleV(outer, 1.0));
            if (out.size() > 24) out.remove(0);
        }
        return d;
    }

    LessonStudio.LessonDef build() {
        List<LessonStudio.Step> s = new ArrayList<>();

        s.add(step("Four moons, four clocks", """
                In 1610 Galileo found four moons circling Jupiter: **Io, Europa, Ganymede and
                Callisto**. It was a little solar system, proof that not everything goes around the
                Earth.

                The moons obey Kepler's third law just as the planets do, with Jupiter in place of the
                Sun: the farther out, the slower and the longer the orbit. Io goes round in 1.8 days,
                Callisto in 16.7. Look at the last column: a³ / P² comes out the same for all four.
                Its value depends only on Jupiter's mass.

                > These are the real moons, at 12 hours per second. The view follows Jupiter.
                """)
                .watch(() -> scaleV(pos(host.state("Io")), 1 / MMU))
                .unit(MMU)
                .cam("Jupiter", () -> camGoal)
                .enter(() -> {
                    host.showOnly(null, "Jupiter"); host.setOrbits(false, true); host.setTime(0.5 * DAY);
                    trace(); camGoal = new double[]{0, 0, 2.2e9, 12, 0};
                })
                .controls(c -> timeSlider(c, "days", DAY, 2))
                .scene((r, hud, t) -> {
                    double now = host.simTime();
                    double[] jk = scaleV(pos(host.state("Jupiter")), 1 / MMU);
                    drawOrbits(r, jk, 4);
                    hud.title("The Galilean moons", ACCENT);
                    hud.row("moon", "a (1000 km)   P (days)   a³ / P²", DIM);
                    for (int k = 0; k < 4; k++) {
                        double a = WeighLesson.semiMajor(paths[k]) / 1e6;     // thousand km
                        hud.row(MOONS[k], String.format("%,.0f       %.2f       %,.0f", a, P[k], a * a * a / (P[k] * P[k])), MOON_C[k]);
                    }
                    hud.formula("P² ∝ a³   (Kepler's third law, around Jupiter)");
                }));

        s.add(step("In step: 1 : 2 : 4", """
                Io, Europa and Ganymede keep time with each other. In the time Ganymede goes round
                once, Europa goes round twice and Io four times: a {k:resonance}, 1 : 2 : 4.

                So the moons keep meeting in the same places. Each time Io laps Europa (red dots), and
                each time Europa laps Ganymede (blue dots), it happens almost where it did last time,
                the spot creeping round by under a degree a day as the orbits slowly turn.
                The regular tugs at those meetings keep the rhythm locked and stretch the orbits
                slightly, and the flexing they give Io heats its interior: it is the most volcanic
                world in the Solar System.

                The rhythm also means all three can never line up on one side of Jupiter. The
                {k:Laplace angle} below, worked out from the moons' real positions, stays at 180°.

                > Run time and watch the dots pile up in the same places.
                """)
                .watch(() -> scaleV(pos(host.state("Io")), 1 / MMU))
                .unit(MMU)
                .cam("Jupiter", () -> camGoal)
                .enter(() -> {
                    host.showOnly(null, "Jupiter"); host.setOrbits(false, true); host.setTime(0.5 * DAY);
                    trace(); start(); camGoal = new double[]{0, 0, 1.25e9, 10, 0};
                })
                .controls(c -> timeSlider(c, "days", DAY, 2))
                .scene((r, hud, t) -> {
                    double now = host.simTime();
                    double[] jk = scaleV(pos(host.state("Jupiter")), 1 / MMU);
                    drawOrbits(r, jk, 3);
                    double[] io = rel("Io", now), eu = rel("Europa", now), ga = rel("Ganymede", now);
                    prevIE = meet(prevIE, io, eu, meetIE);
                    prevEG = meet(prevEG, eu, ga, meetEG);
                    for (double[] m : meetIE) r.dot(add(jk, scaleV(m, 1 / MMU)), 4, alpha(PERI_C, 210));
                    for (double[] m : meetEG) r.dot(add(jk, scaleV(m, 1 / MMU)), 4, alpha(TEAL, 210));
                    double[][] now3 = {io, eu, ga};
                    for (int k = 0; k < 3; k++) {
                        double[] p = add(jk, scaleV(now3[k], 1 / MMU));
                        r.line(jk, p, alpha(MOON_C[k], 150), 1.3f, false);
                    }
                    double laplace = Math.toDegrees(lon(io) - 3 * lon(eu) + 2 * lon(ga));
                    laplace = ((laplace % 360) + 360) % 360;
                    double days = (now - t0) / DAY;
                    hud.title("Io, Europa, Ganymede", ACCENT);
                    hud.row("days watched", String.format("%.1f", days));
                    hud.row("laps: Io / Europa / Ganymede", String.format("%.1f / %.1f / %.1f", days / P[0], days / P[1], days / P[2]));
                    hud.row("P(Europa) / P(Io)", String.format("%.3f", P[1] / P[0]), MOON_C[1]);
                    hud.row("P(Ganymede) / P(Europa)", String.format("%.3f", P[2] / P[1]), MOON_C[2]);
                    hud.row("Laplace angle λ(Io) − 3λ(Eu) + 2λ(Ga)", String.format("%.0f°", laplace), SUN_C);
                    hud.formula("1 : 2 : 4");
                }));

        s.add(step("Check: Ganymede's year", """
                Europa goes around Jupiter in **3.55 days**, and it is in a 1 : 2 rhythm with
                Ganymede.
                """)
                .reviews("In step: 1 : 2 : 4")
                .unit(MMU)
                .cam("Jupiter", () -> camGoal)
                .enter(() -> {
                    host.showOnly(null, "Jupiter"); host.setOrbits(false, true); host.setTime(0);
                    trace(); camGoal = new double[]{0, 0, 1.25e9, 10, 0};
                })
                .quiz("How long does Ganymede take to go around?", 2,
                      "Ganymede goes around once while Europa goes around twice, so its period is about 2 × 3.55 = "
                    + "7.1 days (really 7.15). Io, at 1.77 days, fits four times into it.",
                      "About 1.8 days", "About 3.6 days", "About 7.1 days", "About 14 days")
                .scene((r, hud, t) -> drawOrbits(r, scaleV(pos(host.state("Jupiter")), 1 / MMU), 3)));

        s.add(step("Check: the fastest moon", """
                The four moons, from the inside out: Io, Europa, Ganymede, Callisto.
                """)
                .reviews("Four moons, four clocks")
                .unit(MMU)
                .cam("Jupiter", () -> camGoal)
                .enter(() -> {
                    host.showOnly(null, "Jupiter"); host.setOrbits(false, true); host.setTime(0);
                    trace(); camGoal = new double[]{0, 0, 2.2e9, 12, 0};
                })
                .quiz("Which moon has the shortest orbit, and why?", 0,
                      "Io, the closest. Nearer in, Jupiter's pull is stronger, so a moon must move faster to stay in "
                    + "orbit, and it has a shorter way to go around: Kepler's third law, P² ∝ a³.",
                      "Io, because it is closest to Jupiter", "Callisto, because it is farthest out",
                      "Ganymede, because it is the biggest", "They all take the same time")
                .scene((r, hud, t) -> drawOrbits(r, scaleV(pos(host.state("Jupiter")), 1 / MMU), 4)));

        return new LessonStudio.LessonDef("Intermediate", "Jupiter's Moons in Step",
                "Kepler's third law again, and the 1 : 2 : 4 resonance",
                "Watch Galileo's four moons on their real orbits, find Kepler's third law around Jupiter, and "
              + "see Io, Europa and Ganymede keep a 1 : 2 : 4 rhythm that never lets them line up.",
                new Color(255, 200, 140), s, _ -> { });
    }
}
