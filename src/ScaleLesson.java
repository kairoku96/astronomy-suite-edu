import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Lesson: How Far Is Light? (AST101 Days 3–4). A pulse of light leaving the real Sun and crossing
 * the real planets' distances, timed; the Solar System shrunk to a grapefruit Sun; and what light
 * travel time means for seeing the Sun and talking to a spacecraft.
 */
final class ScaleLesson extends LessonKit {

    ScaleLesson(LessonStudio.Host host) { super(host); }

    static final String[] PLANETS = {"Mercury", "Venus", "Earth", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune"};
    /** Equatorial diameters (km). */
    static final double[] DIAM_KM = {4879, 12104, 12756, 6792, 142984, 120536, 51118, 49528};
    static final double SUN_DIAM_KM = 1.3914e6;
    /** Proxima Centauri's distance (m): 4.246 light-years. */
    static final double PROXIMA = 4.246 * LY;
    int target = 2;
    double pulseAge;                // seconds since the current light pulse left the Sun (real time)
    double[] camGoal = {0, 0, 1.6 * AU, 30, 0};

    double dist(String n) { return len(pos(host.state(n))); }

    void frame() {
        double d = dist(PLANETS[target]) / AU;
        camGoal = new double[]{0, 0, Math.max(0.6, d * 1.12) * AU, 25, 0};
    }

    /** The pulse's speed-up: it reaches the chosen planet in 4 real seconds. */
    double speedUp() { return dist(PLANETS[target]) / C / 4; }

    static String duration(double s) {
        if (s < 120) return String.format("%.0f seconds", s);
        if (s < 7200) return String.format("%.1f minutes", s / 60);
        return String.format("%.1f hours", s / 3600);
    }

    /** Size in a model where the Sun is a 10 cm grapefruit. */
    static String model(double metres) {
        double k = 0.10 / (SUN_DIAM_KM * 1e3), x = metres * k;
        if (x < 0.01) return String.format("%.1f mm", x * 1000);
        if (x < 1) return String.format("%.0f cm", x * 100);
        if (x < 1000) return String.format("%.0f m", x);
        return String.format("%,.0f km", x / 1000);
    }

    LessonStudio.LessonDef build() {
        List<LessonStudio.Step> s = new ArrayList<>();

        s.add(step("Light takes time", """
                Light is the fastest thing there is: **300,000 km every second**. Fast enough to go
                around the Earth seven times in a second. But the Solar System is so big that even
                light takes minutes or hours to cross it.

                The ring is a pulse of light leaving the Sun, sped up so you can watch it. It reaches
                Earth after **8.3 minutes**. So we always see the Sun as it was 8 minutes ago, and
                Neptune as it was 4 hours ago. Looking out into space is looking back in time.

                > Pick a planet to send the pulse to. Distances are the real ones today.
                """)
                .watch(() -> v(0, 0, 0))
                .unit(AU)
                .cam(() -> camGoal)
                .enter(() -> {
                    host.showOnly(null, PLANETS); host.setOrbits(true, true); host.setTime(0);
                    frame(); pulseAge = 0;
                })
                .controls(c -> c.choices("Send light to", PLANETS, () -> target, k -> { target = k; frame(); pulseAge = 0; }))
                .scene((r, hud, t) -> {
                    double k = speedUp(), travelled = C * k * pulseAge, rad = travelled / AU;
                    double[][] ring = new double[200][];
                    for (int i = 0; i < 200; i++) ring[i] = v(rad * Math.cos(2 * Math.PI * i / 200), rad * Math.sin(2 * Math.PI * i / 200), 0);
                    r.polyline(ring, true, new Color(255, 236, 160, 220), 2.4f, false);
                    for (int i = 0; i <= target; i++) {
                        double d = dist(PLANETS[i]);
                        double[] p = scaleV(pos(host.state(PLANETS[i])), 1 / AU);
                        boolean reached = travelled >= d;
                        r.ring(p, reached ? 10 : 7, reached ? SUN_C : alpha(INK, 120), reached ? 2.2f : 1.2f);
                        if (reached) r.label(p, duration(d / C), SUN_C, 12f, 0, 24);
                    }
                    double d = dist(PLANETS[target]);
                    hud.title("Light from the Sun to " + PLANETS[target], SUN_C);
                    hud.row("distance today", String.format("%,.0f million km  (%s)", d / 1e9, au(d / AU)));
                    hud.row("light travel time", duration(d / C), SUN_C);
                    hud.row("pulse sped up", String.format("%,.0f×", k), DIM);
                    hud.formula("time = distance / speed of light   (c = 300,000 km/s)");
                }));

        s.add(step("Shrink it to a grapefruit", """
                Pictures of the Solar System squeeze the planets close together and draw them far too
                big, because at true scale they would be invisible specks. Here is the real thing,
                shrunk so the Sun is a **10 cm grapefruit**.

                Earth is a grain of sand 1 mm across, 11 metres away. Jupiter is a marble 55 m away.
                Neptune is a 3.6 mm bead a third of a kilometre off. And the nearest star, Proxima
                Centauri, would be another grapefruit **2,900 km away**, about the distance from
                Toronto to Calgary. Space is mostly empty.

                > The table uses the planets' real sizes and today's real distances.
                """)
                .unit(AU)
                .cam(() -> new double[]{0, 0, 33 * AU, 35, 0})
                .enter(() -> { host.showOnly(null, PLANETS); host.setOrbits(true, true); host.setTime(0); })
                .scene((r, hud, t) -> {
                    hud.title("If the Sun were a 10 cm grapefruit", SUN_C);
                    hud.row("body", "size        distance from the Sun", DIM);
                    hud.row("Sun", "10 cm", SUN_C);
                    for (int i = 0; i < PLANETS.length; i++)
                        hud.row(PLANETS[i], String.format("%-11s %s", model(DIAM_KM[i] * 1e3), model(dist(PLANETS[i]))));
                    hud.row("Proxima Centauri", "another grapefruit,  " + model(PROXIMA), ACCENT);
                    hud.formula("1 AU = 150 million km = 8.3 light-minutes");
                }));

        s.add(step("Check: if the Sun went dark", """
                Imagine the Sun suddenly stopped shining.
                """)
                .reviews("Light takes time")
                .unit(AU)
                .cam(() -> new double[]{0, 0, 1.6 * AU, 30, 0})
                .enter(() -> { host.showOnly(null, "Mercury", "Venus", "Earth", "Mars"); host.setOrbits(true, true); host.setTime(0); })
                .quiz("How long until we'd notice on Earth?", 1,
                      "The last light the Sun gave off would still be on its way. It takes 150 million km / "
                    + "300,000 km/s = 500 seconds, about 8.3 minutes, to reach us, so the sky would stay bright for "
                    + "8 more minutes.",
                      "Instantly", "About 8 minutes", "About 1 second", "About a day")
                .scene((r, hud, t) -> { }));

        s.add(step("Check: talking to a spacecraft at Neptune", """
                Neptune is about **30 AU** from the Sun (4.5 billion km), and a spacecraft there is
                about as far from Earth. Radio waves travel at the speed of light.
                """)
                .reviews("Light takes time")
                .unit(AU)
                .cam(() -> new double[]{0, 0, 33 * AU, 35, 0})
                .enter(() -> { host.showOnly(null, PLANETS); host.setOrbits(true, true); host.setTime(0); })
                .quiz("How long does a command take to get there?", 2,
                      "4.5 × 10⁹ km / 300,000 km/s = 15,000 s, about 4 hours (30 × 8.3 minutes). An answer takes "
                    + "another 4 hours to come back, so a spacecraft out there has to look after itself.",
                      "About 8 minutes", "About 30 minutes", "About 4 hours", "About 4 days")
                .scene((r, hud, t) -> { }));

        return new LessonStudio.LessonDef("Beginner", "How Far Is Light?",
                "The scale of the Solar System and light travel time",
                "Send a pulse of light from the Sun across the real planets' distances, shrink the Solar "
              + "System to a grapefruit Sun, and find out why we see the Sun as it was 8 minutes ago.",
                new Color(255, 236, 160), s, dt -> pulseAge = (pulseAge + dt) % 5.5);      // a new pulse every 5.5 s
    }
}
