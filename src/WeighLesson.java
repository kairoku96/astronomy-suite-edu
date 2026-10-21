import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Lesson: Weighing the Solar System (AST221 Day 7). Kepler's third law on the real planets with
 * their measured periods, Newton's form of it, and masses weighed from real orbits: the Sun from
 * Earth's, Jupiter from its four large moons, and Pluto + Charon from Charon's orbit about their
 * shared barycentre.
 */
final class WeighLesson extends LessonKit {

    WeighLesson(LessonStudio.Host host) { super(host); }

    static final String[] PLANETS = {"Mercury", "Venus", "Earth", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune"};
    /** Measured sidereal orbital periods (years; NASA planetary fact sheets). */
    static final double[] P_YR = {0.2408467, 0.61519726, 1.0000174, 1.8808476, 11.862615, 29.447498, 84.016846, 164.79132};
    static final String[] GALILEAN = {"Io", "Europa", "Ganymede", "Callisto"};
    /** Sidereal periods of the Galilean moons and Charon (days; JPL). */
    static final double[] P_GAL = {1.769138, 3.551181, 7.154553, 16.689017};
    static final double P_CHARON = 6.387230;
    static final Color[] MOON_C = {new Color(255, 214, 120), new Color(200, 225, 255), new Color(180, 255, 190), new Color(255, 160, 200)};

    /** Each Galilean moon's path relative to Jupiter over one orbit (m), traced from the ephemeris. */
    double[][][] galPath = new double[4][][];
    /** Charon's and Pluto's paths about their barycentre (m), and the orbit pole. */
    double[][] charonPath, plutoPath;
    double[] camGoal = {0, 0, 1, 30, 0};
    double massRatio = 2, t0, phase0;

    /** Semi-major axis of a traced closed orbit: half the sum of its nearest and farthest points. */
    static double semiMajor(double[][] path) {
        double lo = Double.MAX_VALUE, hi = 0;
        for (double[] p : path) { double r = len(p); lo = Math.min(lo, r); hi = Math.max(hi, r); }
        return (lo + hi) / 2;
    }

    void traceGalilean() {
        double t = host.simTime();
        for (int k = 0; k < 4; k++) {
            int n = 90;
            galPath[k] = new double[n][];
            for (int i = 0; i < n; i++) {
                double ti = t + P_GAL[k] * DAY * i / n;
                galPath[k][i] = sub(pos(host.stateAt(GALILEAN[k], ti)), pos(host.stateAt("Jupiter", ti)));
            }
        }
    }

    void traceCharon() {
        double t = host.simTime();
        int n = 90;
        charonPath = new double[n][];
        plutoPath = new double[n][];
        for (int i = 0; i < n; i++) {
            double ti = t + P_CHARON * DAY * i / n;
            double[] b = pos(host.systemBaryAt("Pluto", ti));
            charonPath[i] = sub(pos(host.stateAt("Charon", ti)), b);
            plutoPath[i] = sub(pos(host.stateAt("Pluto", ti)), b);
        }
        // Look straight down the orbit's pole.
        double[] nrm = norm(cross(charonPath[0], charonPath[n / 4]));
        if (nrm[2] < 0) nrm = scaleV(nrm, -1);
        double sp = Math.sqrt(Math.max(1e-9, 1 - nrm[2] * nrm[2]));
        camGoal = new double[]{0, 0, 2.6e7, Math.toDegrees(Math.acos(nrm[2])), Math.toDegrees(Math.atan2(nrm[0] / sp, nrm[1] / sp))};
    }

    /** {M_total, a, r_Pluto, r_Charon} (kg, m) from the traced orbits. */
    double[] plutoCharon() {
        double rC = semiMajor(charonPath), rP = semiMajor(plutoPath), a = rC + rP;
        double P = P_CHARON * DAY;
        return new double[]{4 * Math.PI * Math.PI * a * a * a / (G * P * P), a, rP, rC};
    }

    LessonStudio.LessonDef build() {
        List<LessonStudio.Step> s = new ArrayList<>();
        final double KM = 1e6;      // 1000 km

        s.add(step("Kepler's third law", """
                Kepler found that a planet's orbital period P and its orbit's size (semi-major axis
                a) are tied together. In years and AU,

                $ P² = a³

                The inset plots the measured periods of all eight planets against their distances,
                on logarithmic axes. They fall on one straight line of slope 3/2. Far planets are not
                only farther; they also move more slowly, so a year on Neptune lasts 165 Earth years.

                Look closely at a³ / P²: the outer planets miss 1 by up to about 1%. Those differences are
                real. The planets tug on each other, so each orbit's size wobbles a little around its
                average, and the exact law also contains the planet's own mass (next step).
                """)
                .unit(AU)
                .cam(() -> new double[]{0, 0, 31 * AU, 42, 0})
                .enter(() -> { host.showOnly(null, PLANETS); host.setOrbits(true, true); host.setTime(0.5 * YEAR); })
                .controls(c -> timeSlider(c, "years", YEAR, 4))
                .scene((r, hud, t) -> {
                    hud.title("Measured orbits", ACCENT);
                    for (int i = 0; i < 8; i++) {
                        double a = host.planetA(host.planet(PLANETS[i])) / AU;
                        hud.row(PLANETS[i], String.format("a %6.3f AU   P %7.3f yr   a³/P² %.4f", a, P_YR[i], a * a * a / (P_YR[i] * P_YR[i])));
                    }
                    hud.formula("P² = a³   (P in years, a in AU)");
                    hud.inset("Period vs distance (log-log)", 250, 210, (g, b) -> {
                        Plot p = new Plot(b, 0.3, 40, true, 0.15, 250, true);
                        p.axes(g, "a (AU)", "P (yr)", new double[]{1, 10}, new String[]{"1", "10"},
                                new double[]{1, 10, 100}, new String[]{"1", "10", "100"});
                        int n = 60;
                        double[] xs = new double[n], ys = new double[n];
                        for (int i = 0; i < n; i++) { xs[i] = 0.3 * Math.pow(40 / 0.3, i / (n - 1.0)); ys[i] = Math.pow(xs[i], 1.5); }
                        p.curve(g, xs, ys, alpha(ACCENT, 160), 1.4f);
                        for (int i = 0; i < 8; i++) {
                            double a = host.planetA(host.planet(PLANETS[i])) / AU;
                            p.dot(g, a, P_YR[i], 3.5, SUN_C);
                        }
                        p.label(g, 2, Math.pow(2, 1.5), "P = a^1.5", ACCENT, 6, 14);
                    });
                }));

        s.add(step("Newton weighs the Sun", """
                Newton showed why Kepler's law holds. For a circular orbit, gravity supplies exactly
                the centripetal force:

                $ G M m / r² = m v² / r,   with   v = 2πr / P

                Rearranging gives Kepler's third law with the mass in it:

                $ P² = 4π² a³ / G(M + m)

                For Earth, m is tiny compared with M. Measure Earth's year and its distance, and you
                have **weighed the Sun**: about 2 × 10³⁰ kg. (The exact form has M + m. Even for
                Jupiter, m is only a thousandth of M.)

                > The green arrow is Earth's real velocity; the orange arrow is the Sun's pull.
                """)
                .unit(AU)
                .cam(() -> new double[]{0, 0, 1.3 * AU, 30, 0})
                .enter(() -> { host.showOnly(null, "Earth"); host.setOrbits(true, true); host.setTime(10 * DAY); })
                .controls(c -> timeSlider(c, "days", DAY, 30))
                .scene((r, hud, t) -> {
                    double[] st = host.state("Earth"), p = scaleV(pos(st), 1 / AU), vv = vel(st);
                    double rr = len(pos(st)), sp = len(vv);
                    r.arrow(p, add(p, scaleV(norm(vv), 0.35)), GREEN, 3f);
                    r.label(add(p, scaleV(norm(vv), 0.35)), String.format("v = %.2f km/s", sp / 1e3), GREEN, 12f, 0, -14);
                    r.arrow(p, scaleV(p, 0.72), ORANGE, 3f);
                    r.label(scaleV(p, 0.7), "gravity", ORANGE, 12f, 0, 16);
                    double a = host.planetA(host.planet("Earth")), P = P_YR[2] * YEAR;
                    double M = 4 * Math.PI * Math.PI * a * a * a / (G * P * P);
                    hud.title("Weighing the Sun", SUN_C);
                    hud.row("Earth's semi-major axis a", sci(a, 4) + " m");
                    hud.row("Earth's sidereal year P", sci(P, 4) + " s");
                    hud.row("G", "6.674 × 10⁻¹¹ m³ kg⁻¹ s⁻²");
                    hud.row("M = 4π² a³ / G P²", sci(M, 3) + " kg", SUN_C);
                    hud.gap();
                    hud.row("from today's speed: v² r / G", sci(sp * sp * rr / G, 3) + " kg", DIM);
                    hud.formula("M = 4π² a³ / (G P²)");
                }));

        s.add(step("Weigh Jupiter with its moons", """
                Any orbit weighs the body at its centre. Here are Jupiter's four large moons, which
                Galileo found in 1610, on their real orbits. For each one, measure its orbit's size a
                (traced here from its real motion) and its period P, then

                $ M_Jupiter = 4π² a³ / G P²

                All four give the same answer: **1.90 × 10²⁷ kg**, 318 Earths. The inset shows P²
                against a³: a straight line through the origin, whose slope is 4π² / GM.

                > Time runs at half a day per second: Io laps the others.
                """.replace("M_Jupiter", "M(Jupiter)"))
                .unit(KM)
                .cam("Jupiter", () -> new double[]{0, 0, 2.15e9, 14, 0})
                .enter(() -> {
                    host.showOnly(null, "Jupiter"); host.setOrbits(false, true); host.setTime(0.5 * DAY);
                    traceGalilean();
                })
                .controls(c -> timeSlider(c, "days", DAY, 3))
                .scene((r, hud, t) -> {
                    double[] J = scaleV(pos(host.state("Jupiter")), 1 / KM);
                    hud.title("Jupiter's moons weigh Jupiter", SUN_C);
                    double[] a3 = new double[4], p2 = new double[4];
                    for (int k = 0; k < 4; k++) {
                        double[][] path = galPath[k];
                        double[][] pts = new double[path.length][];
                        for (int i = 0; i < path.length; i++) pts[i] = add(J, scaleV(path[i], 1 / KM));
                        r.polyline(pts, true, alpha(MOON_C[k], 150), 1.4f, false);
                        double[] m = scaleV(pos(host.state(GALILEAN[k])), 1 / KM);
                        r.dot(m, 4, MOON_C[k]);
                        double a = semiMajor(path), P = P_GAL[k] * DAY;
                        a3[k] = a * a * a; p2[k] = P * P;
                        hud.row(GALILEAN[k], String.format("a %,9.0f km   P %6.3f d   M %s kg", a / 1e3, P_GAL[k],
                                sci(4 * Math.PI * Math.PI * a3[k] / (G * p2[k]), 3)), MOON_C[k]);
                    }
                    hud.gap();
                    hud.row("Jupiter system mass (JPL GM / G)", sci(host.gm("Jupiter") / G, 3) + " kg", DIM);
                    hud.formula("M = 4π² a³ / (G P²)");
                    hud.inset("P² vs a³ for Jupiter's moons", 240, 200, (g, b) -> {
                        double xm = a3[3] * 1.1, ym = p2[3] * 1.1;
                        Plot p = new Plot(b, 0, xm, false, 0, ym, false);
                        p.axes(g, "a³", "P²", new double[]{}, new String[]{}, new double[]{}, new String[]{});
                        p.curve(g, new double[]{0, xm}, new double[]{0, xm * 4 * Math.PI * Math.PI / host.gm("Jupiter")}, alpha(ACCENT, 170), 1.4f);
                        for (int k = 0; k < 4; k++) {
                            p.dot(g, a3[k], p2[k], 4, MOON_C[k]);
                            p.label(g, a3[k], p2[k], GALILEAN[k], MOON_C[k], -50, -6);
                        }
                    });
                }));

        s.add(step("Pluto and Charon weigh each other", """
                Charon is so big, half Pluto's width, that Pluto visibly orbits too. Both circle their
                {k:centre of mass} (the barycentre, the white cross), which lies in the empty space
                between them, outside Pluto. These are the real motions, traced from the ephemeris
                and viewed straight down the orbit's axis.

                Kepler III with the **sum** of their distances from the barycentre gives the **total**
                mass:

                $ M_P + M_C = 4π² (r_P + r_C)³ / G P²

                and the balance point splits it: the heavier body sits closer,

                $ M_P r_P = M_C r_C

                > This is exactly how astronomers weigh binary stars (Day 8).
                """.replace("M_P", "M(Pluto)").replace("M_C", "M(Charon)").replace("r_P", "r(Pluto)").replace("r_C", "r(Charon)"))
                .unit(KM)
                .cam("bary:Pluto", () -> camGoal)
                .enter(() -> {
                    host.showOnly(null, "Pluto"); host.setOrbits(false, true); host.setTime(0.5 * DAY);
                    traceCharon();
                })
                .controls(c -> timeSlider(c, "days", DAY, 2))
                .scene((r, hud, t) -> {
                    double[] B = scaleV(pos(host.systemBaryAt("Pluto", host.simTime())), 1 / KM);
                    double[][] cp = new double[charonPath.length][], pp = new double[plutoPath.length][];
                    for (int i = 0; i < cp.length; i++) { cp[i] = add(B, scaleV(charonPath[i], 1 / KM)); pp[i] = add(B, scaleV(plutoPath[i], 1 / KM)); }
                    r.polyline(cp, true, alpha(VIOLET, 170), 1.6f, false);
                    r.polyline(pp, true, alpha(SUN_C, 200), 1.6f, false);
                    double[] C = scaleV(pos(host.state("Charon")), 1 / KM), P = scaleV(pos(host.state("Pluto")), 1 / KM);
                    r.line(B, C, alpha(VIOLET, 200), 1.4f, true);
                    r.line(B, P, alpha(SUN_C, 220), 1.4f, true);
                    double c = 600 / 1e3;
                    r.line(add(B, v(-c, 0, 0)), add(B, v(c, 0, 0)), Color.WHITE, 2f);
                    r.line(add(B, v(0, -c, 0)), add(B, v(0, c, 0)), Color.WHITE, 2f);
                    double[] pc = plutoCharon();
                    double ratio = pc[2] / pc[3], mP = pc[0] / (1 + ratio), mC = pc[0] - mP;
                    hud.title("Pluto + Charon", VIOLET);
                    hud.row("Charon's period P", String.format("%.3f days", P_CHARON));
                    hud.row("Pluto to barycentre", String.format("%,.0f km  (Pluto's radius: 1,188 km)", pc[2] / 1e3), SUN_C);
                    hud.row("Charon to barycentre", String.format("%,.0f km", pc[3] / 1e3), VIOLET);
                    hud.row("total mass 4π²a³ / GP²", sci(pc[0], 3) + " kg");
                    hud.row("mass ratio  M(Charon) / M(Pluto)", String.format("%.3f", ratio));
                    hud.row("Pluto  /  Charon", sci(mP, 2) + "  /  " + sci(mC, 2) + " kg");
                    hud.formula("M₁ r₁ = M₂ r₂");
                }));

        s.add(step("What if the Sun were heavier?", """
                The violet planet orbits 1 AU from a what-if star of mass M, starting beside Earth.
                With a = 1 AU and M in solar masses,

                $ P = √(a³ / M)  years

                A heavier star pulls harder, so the planet must move faster to stay in orbit and the
                year is shorter: around a star of 2 solar masses, a planet at 1 AU orbits in 0.71 years.

                > Change the star's mass and watch the what-if planet pull ahead of, or fall behind,
                Earth.
                """)
                .unit(AU)
                .cam(() -> new double[]{0, 0, 1.35 * AU, 25, 0})
                .enter(() -> {
                    host.showOnly(null, "Earth"); host.setOrbits(true, true); host.setTime(20 * DAY);
                    double[] e = pos(host.state("Earth"));
                    phase0 = Math.atan2(e[1], e[0]); t0 = host.simTime();
                })
                .controls(c -> {
                    c.slider("Star's mass M", 0.25, 4, () -> massRatio, x -> {
                        double[] e = pos(host.state("Earth"));
                        massRatio = x; phase0 = Math.atan2(e[1], e[0]); t0 = host.simTime();
                    }, x -> String.format("%.2f solar masses", x));
                    timeSlider(c, "days", DAY, 60);
                })
                .scene((r, hud, t) -> {
                    double P = Math.sqrt(1 / massRatio) * YEAR;
                    double th = phase0 + 2 * Math.PI * (host.simTime() - t0) / P;
                    int n = 120;
                    double[][] ring = new double[n][];
                    for (int i = 0; i < n; i++) ring[i] = v(Math.cos(2 * Math.PI * i / n), Math.sin(2 * Math.PI * i / n), 0);
                    r.polyline(ring, true, alpha(VIOLET, 140), 1.4f, true);
                    double[] g = v(Math.cos(th), Math.sin(th), 0);
                    r.dot(g, 7, new Color(200, 170, 255));
                    r.ring(g, 9, new Color(235, 220, 255, 200), 1.5f);
                    r.label(g, "what-if planet", VIOLET, 12f, 0, -18);
                    hud.title("Orbit at 1 AU", VIOLET);
                    hud.row("star's mass M", String.format("%.2f solar masses", massRatio));
                    hud.row("what-if year  √(1 / M)", String.format("%.3f yr = %.0f days", P / YEAR, P / DAY), VIOLET);
                    hud.row("Earth's year", "1.000 yr = 365 days", ACCENT);
                    hud.formula("P² = a³ / M   (years, AU, solar masses)");
                }));

        s.add(step("Check: weigh 51 Pegasi", """
                The first planet found around a Sun-like star, 51 Pegasi b, has an orbital period of
                **4.23 days** and a semi-major axis of **0.05 AU**.
                """)
                .unit(AU)
                .cam(() -> new double[]{0, 0, 1.35 * AU, 25, 0})
                .enter(() -> { host.showOnly(null, "Mercury", "Venus", "Earth"); host.setOrbits(true, true); host.setTime(5 * DAY); })
                .quiz("What is the total mass of the star + planet system?", 1,
                      "In years, AU and solar masses, M = a³ / P². P = 4.23 / 365.25 = 0.01158 yr, so "
                    + "M = 0.05³ / 0.01158² = 1.25 × 10⁻⁴ / 1.34 × 10⁻⁴ ≈ 0.93 solar masses. The planet is about "
                    + "0.46 Jupiter masses, 0.04% of that, so M(total) ≈ M(star) is an excellent approximation.",
                      "About 0.05 solar masses", "About 0.93 solar masses", "About 1.9 solar masses", "About 11 solar masses")
                .scene((r, hud, t) -> {
                    int n = 90;
                    double[][] ring = new double[n][];
                    for (int i = 0; i < n; i++) ring[i] = v(0.05 * Math.cos(2 * Math.PI * i / n), 0.05 * Math.sin(2 * Math.PI * i / n), 0);
                    r.polyline(ring, true, PINK, 2f, false);
                    r.label(v(0.05, 0, 0), "51 Peg b's orbit, to scale", PINK, 12f, 0, -16);
                }));

        s.add(step("Check: a heavier host star", """
                An Earth-mass planet orbits its star at **1 AU**, but the star is **twice as massive**
                as the Sun.
                """)
                .unit(AU)
                .cam(() -> new double[]{0, 0, 1.35 * AU, 25, 0})
                .enter(() -> {
                    host.showOnly(null, "Earth"); host.setOrbits(true, true); host.setTime(0);
                })
                .quiz("Is its year longer or shorter than Earth's?", 0,
                      "P² = a³ / M = 1 / 2, so P = 0.71 years. A heavier star pulls harder, so the planet "
                    + "has to move faster to stay in a 1 AU orbit, and it gets round sooner.",
                      "Shorter: about 0.71 years", "Longer: about 1.41 years", "Shorter: 0.5 years", "The same: 1 year")
                .scene((r, hud, t) -> {
                    int n = 120;
                    double[][] ring = new double[n][];
                    for (int i = 0; i < n; i++) ring[i] = v(Math.cos(2 * Math.PI * i / n), Math.sin(2 * Math.PI * i / n), 0);
                    r.polyline(ring, true, alpha(VIOLET, 140), 1.4f, true);
                    double[] e = norm(pos(host.state("Earth")));
                    double[] g = v(-e[0], -e[1], 0);
                    r.dot(g, 7, new Color(200, 170, 255));
                    r.label(g, "Earth-mass planet, 1 AU", VIOLET, 12f, 0, -18);
                    r.labelBelow(v(0, 0, 0), R_SUN / AU, "a star of 2 solar masses in the Sun's place", SUN_C, 12f);
                }));

        return new LessonStudio.LessonDef("Intermediate", "Weighing the Solar System",
                "Kepler's third law and Newton's version of it",
                "Check P² = a³ on the eight planets, weigh the Sun from Earth's year, Jupiter from its "
              + "moons, and Pluto and Charon from their dance about a point in empty space.",
                new Color(255, 150, 120), s, _ -> { });
    }
}
