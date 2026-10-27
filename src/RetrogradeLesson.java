import java.awt.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lesson: Mars Goes Backwards (AST101 Day 9). The real retrograde loop of Mars around its 2027
 * opposition, plotted night by night against the stars; the same months seen from above, where
 * the backward drift is just Earth overtaking Mars on the inside lane; and the phases of Venus that
 * Galileo saw, which an Earth-centred sky can't make.
 */
final class RetrogradeLesson extends LessonKit {

    RetrogradeLesson(LessonStudio.Host host) { super(host); }

    /** The 2027 opposition (Feb 19), and the span of Mars's track around it. */
    static final double OPP = MoonLesson.secondsOf(2027, 2, 19), SPAN0 = OPP - 125 * DAY, SPAN1 = OPP + 115 * DAY;
    static final int NIGHTS = 241;
    double[][] track;              // Mars's {RA h, Dec °} each night
    int station1 = -1, station2 = -1;
    double night;                  // the play head: nights since the track starts
    boolean playing = true;
    double[] skyView = {180, 45, 40};
    double[] camGoal = {0, 0, 2.3 * AU, 8, 0};

    void sampleTrack() {
        if (track != null) return;
        track = new double[NIGHTS][];
        for (int i = 0; i < NIGHTS; i++) {
            double[] b = host.skyBodyAt("Mars", SPAN0 + (SPAN1 - SPAN0) * i / (NIGHTS - 1));
            track[i] = new double[]{b[2], b[3]};
        }
        // The stations: where the right ascension stops growing, and where it starts again.
        for (int i = 1; i < NIGHTS - 1; i++) {
            double d0 = dRa(i - 1, i), d1 = dRa(i, i + 1);
            if (station1 < 0 && d0 > 0 && d1 <= 0) station1 = i;
            else if (station1 >= 0 && station2 < 0 && d0 < 0 && d1 >= 0) station2 = i;
        }
    }
    double dRa(int a, int b) { return Math.IEEEremainder(track[b][0] - track[a][0], 24); }
    double tOf(double i) { return SPAN0 + (SPAN1 - SPAN0) * i / (NIGHTS - 1); }
    static String date(double t) {
        return LocalDateTime.of(2000, 1, 1, 12, 0).plusSeconds(Math.round(t)).format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH));
    }

    void tick(double dt) {
        if (playing && track != null) night = (night + dt * 12) % NIGHTS;     // 12 nights a second
    }

    /** Where the ray from p along u leaves a sphere of radius R about the Sun. */
    static double[] toBackdrop(double[] p, double[] u, double R) {
        double pu = dot(p, u), s = -pu + Math.sqrt(Math.max(0, pu * pu - dot(p, p) + R * R));
        return add(p, scaleV(u, s));
    }

    int venusView;                 // 0 = through a telescope, 1 = from above
    LessonStudio.Step venusStep;

    /**
     * The telescope: look at the real Venus from the Earth's side at a fixed magnification (an 80″
     * field), so the sim's rendering shows its real phase and its real change in apparent size.
     */
    void frameVenus() {
        if (venusView == 1) { camGoal = new double[]{0, 0, 1.25 * AU, 10, 0}; return; }
        double now = host.simTime();
        double[] toEarth = sub(pos(host.stateAt("Earth", now)), pos(host.stateAt("Venus", now)));
        double[] u = norm(toEarth);
        camGoal = new double[]{0, 0, len(toEarth) * Math.tan(40 * ARCSEC),
                Math.toDegrees(Math.acos(clamp(u[2], -1, 1))), Math.toDegrees(Math.atan2(u[0], u[1])), 0};
    }

    /** Venus's lit fraction, angular diameter (″) and whether it is east of the Sun (an evening star). */
    double[] venus(double t) {
        double[] e = pos(host.stateAt("Earth", t)), v = pos(host.stateAt("Venus", t));
        double[] toSun = scaleV(v, -1), toEarth = sub(e, v);
        double i = Math.toRadians(sep(toSun, toEarth)), d = len(toEarth);
        double ahead = ((eclLon(sub(v, e)) - eclLon(scaleV(e, -1))) % 360 + 360) % 360;
        return new double[]{(1 + Math.cos(i)) / 2, 2 * 6.0518e6 / d / ARCSEC, ahead < 180 ? 1 : 0, d};
    }

    LessonStudio.LessonDef build() {
        List<LessonStudio.Step> s = new ArrayList<>();

        s.add(step("Mars goes backwards", """
                Night after night, the planets creep slowly eastward against the stars. But every
                couple of years Mars stops, drifts **backward** (west) for about two months, stops
                again, and carries on east. This is {k:retrograde motion}, and it puzzled
                astronomers for 2,000 years.

                Here is the real Mars, plotted once a night from October 2026 to June 2027 against
                the stars of Cancer and Leo. The loop is centred on February 19, 2027, when Mars is
                at {k:opposition}: opposite the Sun in our sky, closest to Earth and brightest.

                > The marker plays the nights back at 12 a second. Pause it and drag the date to
                step through the loop yourself.
                """)
                .watch(() -> track == null ? null : host.skyEnuRaDec(track[(int) Math.round(night)][0], track[(int) Math.round(night)][1]))
                .sky(() -> skyView)
                .enter(() -> {
                    host.skyPlace(UTM, UTM_LAT, UTM_LON, TORONTO);
                    host.goToUTC(2027, 2, 19, 5, 0);                       // midnight in Mississauga
                    host.setTime(0);
                    host.skyLayers(false, false, true, true);
                    sampleTrack();
                    double[] mid = norm(add(host.skyEnuRaDec(track[0][0], track[0][1]), host.skyEnuRaDec(track[NIGHTS - 1][0], track[NIGHTS - 1][1])));
                    mid = norm(add(mid, host.skyEnuRaDec(track[NIGHTS / 2][0], track[NIGHTS / 2][1])));
                    skyView = new double[]{Math.round(azOf(mid)), Math.round(altOf(mid)), 42};
                    night = 0; playing = true;
                })
                .controls(c -> {
                    c.toggle("Play the nights", () -> playing, x -> playing = x);
                    c.slider("Date", 0, NIGHTS - 1, () -> night, x -> { night = x; playing = false; }, x -> date(tOf(x)));
                })
                .scene((r, hud, t) -> {
                    double[][] p = new double[NIGHTS][];
                    for (int i = 0; i < NIGHTS; i++) p[i] = host.skyEnuRaDec(track[i][0], track[i][1]);
                    r.polyline(p, false, alpha(PERI_C, 150), 1.6f, false);
                    for (int i = 0; i < NIGHTS; i += 30) r.dot(p[i], 2.5, alpha(INK, 170));
                    if (station1 > 0) r.label(p[station1], "stops, turns back: " + date(tOf(station1)), APO_C, 12f, 0, -16);
                    if (station2 > 0) r.label(p[station2], "turns east again: " + date(tOf(station2)), GREEN, 12f, 0, 22);
                    int k = (int) Math.round(night);
                    r.ring(p[k], 9, SUN_C, 2.2f);
                    r.label(p[k], "Mars on " + date(tOf(night)), SUN_C, 12f, 0, 20);
                    r.dot(p[k], 4.5, SUN_C);
                    boolean back = k > 0 && dRa(k - 1, k) < 0;
                    hud.title("Mars, one night at a time", ACCENT);
                    hud.row("date", date(tOf(night)));
                    hud.row("moving", back ? "west: backward (retrograde)" : "east: forward", back ? APO_C : GREEN);
                    hud.row("backward from", station1 > 0 ? date(tOf(station1)) + " to " + date(tOf(station2)) : "");
                    hud.row("opposition", "Feb 19, 2027");
                    hud.formula("dots every 30 nights;   east is to the left");
                }));

        s.add(step("Earth overtakes Mars", """
                From above, the mystery goes away. Earth is on the inside lane and moves faster, so
                every 26 months it catches up with Mars and passes it. The lines are our line of
                sight to Mars every 12 days, carried on to the distant stars.

                While Earth is far behind, the sight line swings forward. As Earth draws level and
                passes, around opposition, the sight line swings **backward** against the stars,
                like a slower car seeming to slide backward as you overtake it on the highway. Then
                forward again. Mars itself never reverses.

                This was a key argument for Copernicus (1543): a Sun-centred system explains the
                loops simply, where Ptolemy needed circles on circles.
                """)
                .watch(() -> scaleV(pos(host.state("Earth")), 1 / AU))
                .unit(AU)
                .cam(() -> camGoal)
                .enter(() -> {
                    host.showOnly(null, "Earth", "Mars"); host.setOrbits(true, true);
                    host.goToUTC(2026, 11, 1, 0, 0);
                    host.setTime(4 * DAY);
                    camGoal = new double[]{0, 0, 2.3 * AU, 8, 0};
                })
                .controls(c -> timeSlider(c, "days", DAY, 10))
                .scene((r, hud, t) -> {
                    double now = host.simTime(), R = 3.1;
                    double[] prevEnd = null;
                    for (int k = 14; k >= 0; k--) {
                        double tk = now - k * 12 * DAY;
                        double[] e = scaleV(pos(host.stateAt("Earth", tk)), 1 / AU), m = scaleV(pos(host.stateAt("Mars", tk)), 1 / AU);
                        double[] end = toBackdrop(e, norm(sub(m, e)), R);      // in 3-D, so the line passes through Mars from any angle
                        int a = 40 + 200 * (14 - k) / 14;
                        r.line(e, end, alpha(k == 0 ? SUN_C : INK, k == 0 ? 230 : a / 2), k == 0 ? 2f : 1f, false);
                        r.dot(end, k == 0 ? 5 : 3, alpha(PERI_C, a));
                        if (prevEnd != null) r.line(prevEnd, end, alpha(PERI_C, a), 1.4f, false);
                        prevEnd = end;
                    }
                    double[][] ring = new double[180][];
                    for (int i = 0; i < 180; i++) ring[i] = v(R * Math.cos(2 * Math.PI * i / 180), R * Math.sin(2 * Math.PI * i / 180), 0);
                    r.polyline(ring, true, alpha(INK, 40), 1f, true);
                    r.label(ring[25], "the distant stars", alpha(INK, 170), 11.5f, 0, -12);
                    double[] e0 = pos(host.stateAt("Earth", now)), m0 = pos(host.stateAt("Mars", now));
                    double[] e1 = pos(host.stateAt("Earth", now + DAY)), m1 = pos(host.stateAt("Mars", now + DAY));
                    double drift = Math.IEEEremainder(eclLon(sub(m1, e1)) - eclLon(sub(m0, e0)), 360);
                    hud.title("Mars as seen from Earth", ACCENT);
                    hud.row("date", host.dateString());
                    hud.row("Earth to Mars", au(len(sub(m0, e0)) / AU));
                    hud.row("drift against the stars", String.format("%+.2f° per day", drift).replace('-', '−'), drift < 0 ? APO_C : GREEN);
                    hud.row("so Mars looks to be moving", drift < 0 ? "backward (west)" : "forward (east)", drift < 0 ? APO_C : GREEN);
                    hud.formula("the faster inner planet overtakes the outer one");
                }));

        s.add(venusStep = step("Venus has phases", """
                In 1610 Galileo turned his new telescope on Venus and saw it go through **phases**
                like the Moon, from a thin crescent to nearly full. And it changed size: a big
                crescent when near us, a small, nearly full disc when far away.

                In Ptolemy's Earth-centred system Venus always stays between the Earth and the Sun,
                so we would only ever see it as a crescent. A nearly full Venus has to be on the
                **far side of the Sun**: Venus orbits the Sun. It was some of the first hard evidence
                against an Earth-centred universe.

                > This is the real Venus through a telescope pointed from Earth, at a fixed
                magnification, so you see its true change in size. Time runs at 6 days per second
                through its 19-month cycle. Switch to "From above" to see where it is.
                """)
                .watch(() -> scaleV(pos(host.state("Venus")), 1 / AU))
                .unit(AU)
                .cam("Venus", () -> camGoal)
                .enter(() -> {
                    host.showOnly(null, "Venus", "Earth"); host.setOrbits(true, true);
                    host.goToUTC(2026, 6, 1, 0, 0);
                    host.setTime(6 * DAY);
                    venusStep.follow = venusView == 0 ? "Venus" : null;
                })
                .live(this::frameVenus)
                .controls(c -> {
                    c.choices("View", new String[]{"Through a telescope", "From above"}, () -> venusView, k -> {
                        venusView = k;
                        venusStep.follow = k == 0 ? "Venus" : null;
                    });
                    timeSlider(c, "days", DAY, 15);
                })
                .scene((r, hud, t) -> {
                    double now = host.simTime();
                    double[] e = scaleV(pos(host.state("Earth")), 1 / AU), vv = scaleV(pos(host.state("Venus")), 1 / AU);
                    if (venusView == 1) r.line(e, vv, alpha(SUN_C, 200), 1.6f, false);
                    double[] vn = venus(now);
                    boolean evening = vn[2] > 0;
                    hud.title("Venus through a telescope", ACCENT);
                    hud.row("date", host.dateString());
                    hud.row("lit part of the face we see", String.format("%.0f%%", 100 * vn[0]), ACCENT);
                    hud.row("apparent size", String.format("%.0f″", vn[1]));
                    hud.row("distance from Earth", au(vn[3] / AU));
                    hud.row("in our sky", evening ? "evening star (after sunset)" : "morning star (before dawn)");
                    hud.row("compared with the Sun (1 AU)", vn[3] > AU ? "farther away" : "nearer");
                    hud.formula("near: big crescent;   far: small and nearly full");
                }));

        s.add(step("Check: when Mars goes backward", """
                Mars drifts backward against the stars for about two months every 26 months.
                """)
                .reviews("Earth overtakes Mars")
                .unit(AU)
                .cam(() -> camGoal)
                .enter(() -> {
                    host.showOnly(null, "Earth", "Mars"); host.setOrbits(true, true); host.setTime(0);
                    camGoal = new double[]{0, 0, 2.3 * AU, 8, 0};
                })
                .quiz("When does Mars look to move backward?", 0,
                      "Retrograde motion happens around opposition, when the faster Earth catches up with Mars and "
                    + "passes it on the inside. Our sight line then swings backward against the stars, though Mars "
                    + "itself keeps going forward the whole time.",
                      "Around opposition, when Earth overtakes it", "When Mars really stops and turns around",
                      "When Mars is behind the Sun", "Every night, around midnight")
                .scene((r, hud, t) -> { }));

        s.add(step("Check: a small, nearly full Venus", """
                Through a telescope, Venus looks small and almost fully lit.
                """)
                .reviews("Venus has phases")
                .unit(AU)
                .cam(() -> camGoal)
                .enter(() -> {
                    host.showOnly(null, "Venus", "Earth"); host.setOrbits(true, true); host.setTime(0);
                    camGoal = new double[]{0, 0, 1.25 * AU, 10, 0};
                })
                .quiz("Where is Venus, and what does that tell us?", 1,
                      "To show us almost all of its lit side, Venus has to be beyond the Sun, which is also why it "
                    + "looks small: it is far away. In an Earth-centred system Venus never gets beyond the Sun, so we "
                    + "would only see crescents. Venus goes around the Sun.",
                      "Between the Earth and the Sun: Earth is at the centre",
                      "On the far side of the Sun: Venus orbits the Sun",
                      "Behind the Earth, opposite the Sun",
                      "Close to Earth: the Moon goes around Venus")
                .scene((r, hud, t) -> { }));

        return new LessonStudio.LessonDef("Beginner", "Mars Goes Backwards",
                "Retrograde motion and the phases of Venus",
                "Watch the real Mars loop backward against the stars around its 2027 opposition, see from "
              + "above that it's just Earth overtaking it, and follow Venus through the phases Galileo saw.",
                new Color(255, 140, 110), s, this::tick);
    }
}
