import java.awt.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lesson: Moon Phases and Eclipses (AST101 Day 7). The real Moon (ELP/MPP02) going round the real
 * Earth: its phases from the Sun–Earth–Moon angle, its uniform spin that keeps one face toward us
 * (with the libration an eccentric orbit gives), and why the 5° tilt of its orbit lets only a
 * couple of new moons a year cast a shadow on Earth, shown on every new moon of 2026.
 */
final class MoonLesson extends LessonKit {

    MoonLesson(LessonStudio.Host host) { super(host); }

    static final double KMU = 1e6;                                  // scene unit: 1000 km
    static final double SYNODIC = 29.530589 * DAY, SIDEREAL = 27.321662 * DAY;
    /** A solar eclipse needs the new Moon within about this far (°) of the Sun line, seen from Earth. */
    static final double ECLIPSE_LIMIT = 1.5;
    static final String[] NEW_MOONS = {"Jan 18, 2026: no eclipse", "Feb 17, 2026: annular eclipse", "Aug 12, 2026: total eclipse"};
    static final int[][] NEW_MOON_DATES = {{2026, 1, 18}, {2026, 2, 17}, {2026, 8, 12}};

    double[][] orbitRel;            // the Moon's path relative to Earth over one month around now (m)
    double tSpin0;                  // when the "same face" step started
    double[] markNoSpin;            // the near side's direction then, held fixed: the Moon if it didn't spin
    int pick;
    int phasesView, faceView;       // phases: 0 = from Earth, 1 = from above; same face: 0 = from above, 1 = from Earth
    LessonStudio.Step phasesStep, faceStep;
    double[] newMoonT, newMoonLat;  // every new moon of 2026 and the Moon's ecliptic latitude at each
    double[] camGoal = {0, 0, 5.2e8, 25, 0};

    // ------------------------------------------------------------------ geometry

    /** The Moon now, in scene units. */
    double[] moonK() { double t = host.simTime(); return scaleV(add(earth(t), moonRel(t)), 1 / KMU); }

    double[] earth(double t) { return pos(host.stateAt("Earth", t)); }
    double[] moonRel(double t) { return sub(pos(host.stateAt("Moon", t)), earth(t)); }

    /** Angle Sun–Earth–Moon (°): 0 at new moon, 180 at full. */
    double elongation(double t) {
        double[] e = earth(t);
        return sep(scaleV(e, -1), moonRel(t));
    }
    /** How far the Moon is ahead of the Sun in ecliptic longitude (°, 0–360): under 180 while waxing. */
    double ahead(double t) {
        double d = eclLon(moonRel(t)) - eclLon(scaleV(earth(t), -1));
        return ((d % 360) + 360) % 360;
    }
    static String phaseName(double ahead) {
        String[] n = {"New moon", "Waxing crescent", "First quarter", "Waxing gibbous", "Full moon", "Waning gibbous", "Last quarter", "Waning crescent"};
        return n[(int) Math.floor(((ahead + 22.5) % 360) / 45)];
    }

    /** The new moon (least elongation) within 16 days of {@code t}. */
    double newMoonNear(double t) {
        double best = 1e9, bt = t;
        for (double x = t - 16 * DAY; x <= t + 16 * DAY; x += 3 * 3600) {
            double el = elongation(x);
            if (el < best) { best = el; bt = x; }
        }
        for (double x = bt - 3 * 3600; x <= bt + 3 * 3600; x += 120) {
            double el = elongation(x);
            if (el < best) { best = el; bt = x; }
        }
        return bt;
    }

    void findNewMoons() {
        if (newMoonT != null) return;
        newMoonT = new double[12];
        newMoonLat = new double[12];
        double t = secondsOf(2026, 1, 18);
        for (int k = 0; k < 12; k++) {
            t = newMoonNear(t);
            newMoonT[k] = t;
            newMoonLat[k] = eclLat(moonRel(t));
            t += SYNODIC;
        }
    }

    static double secondsOf(int y, int m, int d) {
        return java.time.Duration.between(LocalDateTime.of(2000, 1, 1, 12, 0), LocalDateTime.of(y, m, d, 12, 0)).getSeconds();
    }
    static String dateOf(double t) {
        return LocalDateTime.of(2000, 1, 1, 12, 0).plusSeconds(Math.round(t)).format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH));
    }

    double orbitT0;

    void sampleOrbit() {
        orbitT0 = host.simTime();
        int n = 120;
        orbitRel = new double[n][];
        for (int i = 0; i < n; i++) orbitRel[i] = moonRel(orbitT0 + SIDEREAL * (i / (double) n - 0.5));
    }

    /**
     * Look at the real Moon from the Earth's side, the sim rendering its real surface and sunlit half:
     * a 1.5° field, so the disc keeps its true apparent size as the Moon's distance changes.
     */
    void frameFromEarth() {
        double[] u = norm(scaleV(moonRel(host.simTime()), -1));
        camGoal = new double[]{0, 0, len(moonRel(host.simTime())) * Math.tan(Math.toRadians(0.75)),
                Math.toDegrees(Math.acos(clamp(u[2], -1, 1))), Math.toDegrees(Math.atan2(u[0], u[1])), 0};
    }

    /**
     * The direction of the Moon's near side from uniform spin: it turns once per sidereal month,
     * keeping pace with its mean motion, so it faces where the Earth would be on a circular orbit.
     * Mean longitude (Meeus 47.1), brought back from the equinox of date to J2000.
     */
    double[] nearSide(double t) {
        double T = t / (36525 * DAY);
        double L = 218.3164477 + 481267.88123421 * T - 1.3969713 * T + 180;
        double r = Math.toRadians(L);
        return v(Math.sin(r), -Math.cos(r), 0);                       // ecliptic (cos L, sin L, 0) in world axes
    }

    /** Sunlight arrows, coming in from the Sun's side of the Earth. */
    static void sunlight(LessonStudio.R3 r, double[] e, double[] sunDir) {
        double[] p = norm(v(-sunDir[1], sunDir[0], 0));
        for (int i = -1; i <= 1; i++) {
            double[] from = add(e, add(scaleV(sunDir, 560), scaleV(p, 160 * i)));
            r.arrow(from, add(from, scaleV(sunDir, -110)), new Color(255, 214, 120, 200), 2.2f);
        }
        r.label(add(e, scaleV(sunDir, 600)), "sunlight", SUN_C, 12.5f, 0, -16);
    }

    void frameEarth(double pitch) { camGoal = new double[]{0, 0, 5.4e8, pitch, 0}; }

    /** Side-on to the Sun line, so the Moon's tilt above and below it shows. */
    void frameSide() {
        double[] u = norm(scaleV(earth(host.simTime()), -1));
        double[] half = scaleV(moonRel(host.simTime()), 0.5);                        // centred between the Earth and the Moon
        camGoal = new double[]{half[0], half[1], 2.9e8, 88, Math.toDegrees(Math.atan2(u[0], u[1])) + 90, half[2]};
    }

    void drawOrbit(LessonStudio.R3 r, double[] ek) {
        if (orbitRel == null) return;
        if (Math.abs(host.simTime() - orbitT0) > 5 * DAY) sampleOrbit();     // the orbit drifts: keep the month around now
        double[][] p = new double[orbitRel.length][];
        for (int i = 0; i < p.length; i++) p[i] = add(ek, scaleV(orbitRel[i], 1 / KMU));
        r.polyline(p, true, new Color(200, 210, 230, 110), 1.3f, true);
    }

    LessonStudio.LessonDef build() {
        List<LessonStudio.Step> s = new ArrayList<>();

        s.add(phasesStep = step("Phases: the Moon is always half lit", """
                The Sun always lights half of the Moon, the half facing it, just as it lights half
                of the Earth. What changes through the month is how much of that lit half faces
                **us**.

                At **new moon** the Moon sits between us and the Sun, and its lit half faces away. A
                week later, a quarter of the way round, we see half the disc lit: **first quarter**.
                At **full moon** it is on the far side of the Earth and we see its whole lit face.
                Then it wanes back to new, after 29.5 days.

                The Moon makes no light of its own; a phase is just the Sun–Earth–Moon angle.

                > This is the real Moon, lit by the real Sun, seen from the Earth, at 1 day per
                second. Switch to "From above" to see where it is in its orbit at each phase.
                """)
                .watch(this::moonK)
                .unit(KMU)
                .cam("Moon", () -> camGoal)
                .enter(() -> {
                    host.showOnly(null, "Earth"); host.setOrbits(false, true); host.setTime(DAY);
                    host.goToUTC(2026, 10, 10, 12, 0);                    // a new moon: watch a whole month of phases
                    sampleOrbit();
                    phasesStep.follow = phasesView == 0 ? "Moon" : "Earth";
                })
                .live(() -> { if (phasesView == 0) frameFromEarth(); else frameEarth(25); })
                .controls(c -> {
                    c.choices("View", new String[]{"From Earth", "From above"}, () -> phasesView, k -> {
                        phasesView = k;
                        phasesStep.follow = k == 0 ? "Moon" : "Earth";
                    });
                    timeSlider(c, "days", DAY, 3);
                })
                .scene((r, hud, t) -> {
                    double now = host.simTime();
                    double[] e = earth(now), ek = scaleV(e, 1 / KMU), mk = moonK();
                    double[] sun = norm(scaleV(e, -1));
                    double el = elongation(now), ah = ahead(now), lit = (1 - Math.cos(Math.toRadians(el))) / 2;
                    if (phasesView == 0) {
                        double[] from = add(mk, scaleV(sun, 6)), to = add(mk, scaleV(sun, 2.4));
                        r.arrow(from, to, new Color(255, 214, 120, 210), 2.4f);
                        r.label(from, "sunlight", SUN_C, 12.5f, 0, -14);
                    } else {
                        drawOrbit(r, ek);
                        sunlight(r, ek, sun);
                        r.line(ek, mk, alpha(INK, 90), 1f, true);
                        r.ring(mk, 9, alpha(INK, 200), 1.6f);
                        r.ring(ek, 11, new Color(130, 180, 240, 200), 1.6f);
                        r.label(mk, phaseName(ah), INK, 12.5f, 0, -24);
                    }
                    hud.title("The Moon tonight", ACCENT);
                    hud.row("date", host.dateString());
                    hud.row("phase", phaseName(ah), ACCENT);
                    hud.row("lit part of the face we see", String.format("%.0f%%", 100 * lit));
                    hud.row("angle Sun–Earth–Moon", String.format("%.0f°", el));
                    hud.row("days since new moon", String.format("about %.0f", ah / 360 * 29.53));
                    hud.formula("phase = how much of the lit half faces us");
                }));

        s.add(faceStep = step("The same face, always", """
                From Earth we always see the same face of the Moon: the "man in the Moon". That
                does not mean it doesn't spin. It spins **exactly once per orbit**, 27.3 days for
                each, so the same side keeps turning toward us. This is {k:synchronous rotation},
                and Earth's tides locked the Moon into it long ago.

                The red line points from the middle of the Moon's near side. The dashed line shows
                where that face would point if the Moon didn't spin at all: within two weeks we'd be
                looking at its far side.

                The spin is perfectly steady but the orbit is not a circle, so the Moon runs a
                little ahead and behind. The near side rocks a few degrees either way, the
                {k:libration}, and over a month we see 59% of the Moon's surface.

                > Switch to "From Earth" and watch the real Moon's craters stay put, rocking gently,
                while the phases sweep across them.
                """)
                .watch(() -> add(moonK(), scaleV(nearSide(host.simTime()), 30)))
                .unit(KMU)
                .cam("Earth", () -> camGoal)
                .enter(() -> {
                    host.showOnly(null, "Earth"); host.setOrbits(false, true); host.setTime(DAY);
                    host.goToUTC(2026, 10, 10, 12, 0);
                    sampleOrbit();
                    faceStep.follow = faceView == 1 ? "Moon" : "Earth";
                    tSpin0 = host.simTime(); markNoSpin = nearSide(tSpin0);
                })
                .live(() -> { if (faceView == 1) frameFromEarth(); else frameEarth(18); })
                .controls(c -> {
                    c.choices("View", new String[]{"From above", "From Earth"}, () -> faceView, k -> {
                        faceView = k;
                        faceStep.follow = k == 1 ? "Moon" : "Earth";
                    });
                    timeSlider(c, "days", DAY, 3);
                })
                .scene((r, hud, t) -> {
                    double now = host.simTime();
                    double[] e = earth(now), ek = scaleV(e, 1 / KMU), rel = moonRel(now), mk = moonK();
                    double[] near = nearSide(now);
                    if (faceView == 0) {
                        drawOrbit(r, ek);
                        r.ring(mk, 9, alpha(INK, 200), 1.6f);
                        r.ring(ek, 11, new Color(130, 180, 240, 200), 1.6f);
                        r.line(mk, add(mk, scaleV(near, 120)), PERI_C, 3f, false);
                        r.label(add(mk, scaleV(near, 132)), "near side", PERI_C, 11.5f, 0, 0);
                        r.line(mk, add(mk, scaleV(markNoSpin, 120)), alpha(INK, 170), 1.6f, true);
                        r.label(add(mk, scaleV(markNoSpin, 136)), "if it didn't spin", alpha(INK, 200), 11.5f, 0, 0);
                    }
                    double spun = (now - tSpin0) / SIDEREAL * 360;
                    double toEarth = eclLon(scaleV(rel, -1)), lib = Math.IEEEremainder(eclLon(near) - toEarth, 360);
                    hud.title("Spin and orbit", ACCENT);
                    hud.row("days since this step began", String.format("%.1f", (now - tSpin0) / DAY));
                    hud.row("turned on its axis", String.format("%.0f°", spun), PERI_C);
                    hud.row("one spin = one orbit", "27.3 days each");
                    hud.row("libration now (near side vs Earth)", signed("%+.1f°", lib));
                    hud.formula("spin period = orbit period: one face toward Earth");
                }));

        s.add(step("Why not an eclipse every month?", """
                At every new moon the Moon passes between the Earth and the Sun, so why isn't every
                new moon a solar eclipse? Because the Moon's orbit is tilted **5°** to the plane of
                Earth's orbit. Here you look at it edge-on: the line through the Earth is the
                direction of the Sun.

                At most new moons the Moon passes a little above or below the Sun line, and its
                shadow (dashed) misses the Earth. Only when new moon falls near one of the two
                places where the tilted orbit crosses the Sun line does the shadow land: about twice
                a year, in {k:eclipse seasons}. Full moons work the same way for lunar eclipses.

                > Pick a new moon. The inset lists every new moon of 2026, with how far the Moon was
                from the Sun line.
                """)
                .watch(this::moonK)
                .unit(KMU)
                .cam("Earth", () -> camGoal)
                .enter(() -> {
                    host.showOnly(null, "Earth"); host.setOrbits(false, true); host.setTime(0);
                    findNewMoons();
                    jumpTo(newMoonNear(secondsOf(NEW_MOON_DATES[pick][0], NEW_MOON_DATES[pick][1], NEW_MOON_DATES[pick][2])));
                    sampleOrbit(); frameSide();
                })
                .controls(c -> c.choices("New moon", NEW_MOONS, () -> pick, k -> {
                    pick = k;
                    jumpTo(newMoonNear(secondsOf(NEW_MOON_DATES[k][0], NEW_MOON_DATES[k][1], NEW_MOON_DATES[k][2])));
                    sampleOrbit(); frameSide();
                }))
                .scene((r, hud, t) -> {
                    double now = host.simTime();
                    double[] e = earth(now), ek = scaleV(e, 1 / KMU), rel = moonRel(now), mk = add(ek, scaleV(rel, 1 / KMU));
                    double[] sun = norm(scaleV(e, -1));
                    drawOrbit(r, ek);
                    r.line(add(ek, scaleV(sun, 470)), add(ek, scaleV(sun, -170)), alpha(SUN_C, 170), 1.4f, false);
                    r.label(add(ek, scaleV(sun, 470)), "toward the Sun", SUN_C, 12f, 0, -14);
                    r.ring(mk, 7, alpha(INK, 200), 1.4f);                                         // the sim draws both at true size
                    // The shadow runs straight back from the Moon, away from the Sun.
                    double[] q = add(mk, scaleV(sun, -dot(sub(ek, mk), scaleV(sun, -1)) - 60));
                    r.line(mk, q, alpha(INK, 190), 1.6f, true);
                    double beta = eclLat(rel);
                    boolean hits = Math.abs(beta) < ECLIPSE_LIMIT;
                    r.label(mk, String.format("Moon %.1f° %s the Sun line", Math.abs(beta), beta >= 0 ? "above" : "below"), INK, 12.5f, 0, beta >= 0 ? -20 : 24);
                    hud.title(hits ? "The shadow lands on Earth: an eclipse" : "The shadow misses Earth", hits ? SUN_C : DIM);
                    hud.row("date", host.dateString());
                    hud.row("Moon above / below the Sun line", signed("%+.2f°", beta), hits ? SUN_C : INK);
                    hud.row("needed for a solar eclipse", String.format("within about %.1f°", ECLIPSE_LIMIT));
                    hud.row("tilt of the Moon's orbit", "5.1°");
                    hud.inset("New moons of 2026", 230, 300, (g, b) -> {
                        g.setFont(LessonStudio.uiFont(Font.PLAIN, 11.5f));
                        for (int k = 0; k < 12; k++) {
                            boolean ecl = Math.abs(newMoonLat[k]) < ECLIPSE_LIMIT;
                            g.setColor(ecl ? SUN_C : DIM);
                            float y = (float) (b.y + 18 + k * 22);
                            g.drawString(dateOf(newMoonT[k]), b.x + 10, y);
                            g.drawString(signed("%+.1f°", newMoonLat[k]), b.x + 80, y);
                            if (ecl) g.drawString("eclipse", b.x + 140, y);
                        }
                    });
                }));

        s.add(step("Check: a first-quarter Moon", """
                Tonight the Moon is at **first quarter**: exactly half of its face is lit.
                """)
                .reviews("Phases: the Moon is always half lit")
                .unit(KMU)
                .cam("Earth", () -> camGoal)
                .enter(() -> { host.showOnly(null, "Earth"); host.setOrbits(false, true); host.setTime(0); sampleOrbit(); frameEarth(25); })
                .quiz("Seen from Earth, what angle separates the Moon from the Sun?", 2,
                      "At first quarter the Moon is a quarter of the way round its orbit from new moon, so the Sun and "
                    + "the Moon are 90° apart in our sky. We see half of the lit half: half the disc. It is high in the "
                    + "south at sunset.",
                      "0°: it's next to the Sun", "45°", "90°", "180°: it's opposite the Sun")
                .scene((r, hud, t) -> drawOrbit(r, scaleV(earth(host.simTime()), 1 / KMU))));

        s.add(step("Check: eclipses and phases", """
                A solar eclipse needs the Moon's shadow to fall on Earth, and a lunar eclipse needs
                Earth's shadow to fall on the Moon.
                """)
                .reviews("Why not an eclipse every month?")
                .unit(KMU)
                .cam("Earth", () -> camGoal)
                .enter(() -> { host.showOnly(null, "Earth"); host.setOrbits(false, true); host.setTime(0); sampleOrbit(); frameEarth(25); })
                .quiz("At which phases can solar and lunar eclipses happen?", 0,
                      "A solar eclipse needs the Moon between the Sun and the Earth: new moon. A lunar eclipse needs the "
                    + "Earth between the Sun and the Moon: full moon. And only when that new or full moon falls near "
                    + "the Sun line, about twice a year.",
                      "Solar at new moon, lunar at full moon", "Solar at full moon, lunar at new moon",
                      "Both at any phase", "Both only at first quarter")
                .scene((r, hud, t) -> drawOrbit(r, scaleV(earth(host.simTime()), 1 / KMU))));

        return new LessonStudio.LessonDef("Beginner", "Moon Phases and Eclipses",
                "Phases, the Moon's one face, and why eclipses are rare",
                "Follow the real Moon around the real Earth: see where its phases come from, why it always "
              + "shows us one face, and why only a couple of 2026's twelve new moons put a shadow on Earth.",
                new Color(220, 220, 235), s, _ -> { });
    }
}
