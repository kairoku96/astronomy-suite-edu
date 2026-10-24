import java.awt.*;
import java.awt.geom.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Lesson: How Big Does It Look? (AST221 Days 2 and 4). Angular size and the small-angle
 * approximation on the real 2024 total eclipse, the thin wedge a radian-sized idea makes at the
 * Moon, the Sun's solid angle as seen from each planet, and how tiny a planet's disc is.
 */
final class AngularLesson extends LessonKit {

    AngularLesson(LessonStudio.Host host) { super(host); }

    static final String[] WORLDS = {"Mercury", "Venus", "Earth", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune", "Pluto"};
    static final double R_MOON = 1737.4e3, R_EARTH = 6371.0e3;
    /** Greatest eclipse (UTC, to the minute) of three real central eclipses: two total, one annular. */
    static final int[][] ECLIPSES = {{2024, 4, 8, 18, 17}, {2027, 8, 2, 10, 7}, {2024, 10, 2, 18, 45}};
    static final String[] ECLIPSE_NAMES = {"Total: Apr 8, 2024", "Total: Aug 2, 2027", "Annular: Oct 2, 2024"};
    /** AST101 fields of view for Jupiter, from the naked eye to a big telescope (degrees). */
    static final double[] FIELDS = {60, 7, 1, 0.1};
    static final String[] FIELD_NAMES = {"Naked eye (60°)", "Binoculars (7°)", "Small telescope (1°)", "Big telescope (0.1°)"};
    int world = 2;
    int moonView;                 // 0 = the whole Earth–Moon line, 1 = at the Moon
    int eclipse, coneView, field; // AST101: which eclipse, side view / down the line, which field of view
    boolean showHand = true, eclipseHand;
    double[] view = {0, 0, 1};
    double[] camGoal = {0, 0, 1, 50, 0};

    /** Where the Sun–Moon line meets the ground (m, heliocentric): the middle of the shadow's path. */
    double[] shadowPoint() {
        double[] e = pos(host.state("Earth")), m = pos(host.state("Moon")), u = norm(m);
        double[] w = sub(m, e);                                  // the axis is m + s·u, onward from the Sun
        double b = dot(w, u), disc = b * b - (dot(w, w) - R_EARTH * R_EARTH);
        if (disc < 0) return add(e, scaleV(norm(sub(add(m, scaleV(u, -b)), e)), R_EARTH));   // a near miss: the nearest ground
        return add(m, scaleV(u, -b - Math.sqrt(disc)));
    }

    void frameCone() {
        double[] e = pos(host.state("Earth")), m = pos(host.state("Moon")), p = shadowPoint(), u = norm(m);
        double yaw = Math.toDegrees(Math.atan2(u[0], u[1])), pitch = Math.toDegrees(Math.acos(u[2]));
        double[] mid = sub(lerp(p, m, 0.5), e), mm = sub(m, e);
        // Side view: across the line. Down the line: from the ground side, along the axis, tipped a little.
        camGoal = coneView == 0
                ? new double[]{mid[0], mid[1], 0.6 * len(sub(m, p)), 72, yaw + 90, mid[2]}
                : new double[]{mm[0], mm[1], 4.5e6, pitch - 10, yaw + 12, mm[2]};
    }

    /** A direction {@code x}° right and {@code y}° up of {@code t} on the sky, in the frame where {@code up} is up. */
    static double[] skyAt(double[] t, double[] up, double x, double y) {
        double[] right = norm(cross(t, up)), u = cross(right, t);
        return norm(add(t, add(scaleV(right, Math.tan(Math.toRadians(x))), scaleV(u, Math.tan(Math.toRadians(y))))));
    }

    /** A finger (or thumb) on the sky: a rounded bar of width w from (x0, y0) to (x1, y1), degrees in the hand's frame. */
    static double[][] finger(double[] t, double[] up, double x0, double y0, double x1, double y1, double w) {
        double dx = x1 - x0, dy = y1 - y0, l = Math.hypot(dx, dy), ux = dx / l, uy = dy / l, nx = uy, ny = -ux, h = w / 2;
        double cx = x1 - ux * h, cy = y1 - uy * h;      // centre of the round tip, so the tip ends at (x1, y1)
        List<double[]> p = new ArrayList<>();
        p.add(skyAt(t, up, x0 + nx * h, y0 + ny * h));
        for (int i = 0; i <= 10; i++) {
            double a = Math.PI * i / 10, ca = Math.cos(a), sa = Math.sin(a);
            p.add(skyAt(t, up, cx + (nx * ca + ux * sa) * h, cy + (ny * ca + uy * sa) * h));
        }
        p.add(skyAt(t, up, x0 - nx * h, y0 - ny * h));
        return p.toArray(new double[0][]);
    }

    /**
     * Your right hand held up at arm's length, back toward you, at its true angular size: the
     * little finger is about 1° wide, three fingers about 5°, a closed fist about 10°. The tip of
     * the little finger sits {@code below}° under {@code t}.
     */
    static void hand(LessonStudio.R3 r, double[] t, double[] up, double below) {
        Color fill = new Color(240, 196, 160, 46), edge = new Color(255, 214, 180, 175);
        double y0 = -below;
        // little, ring, middle, index: centre x, width, tip height above the little finger's tip
        double[][] f = {{0, 1.0, 0}, {-1.38, 1.6, 1.8}, {-3.11, 1.7, 2.6}, {-4.89, 1.7, 1.8}};
        for (double[] k : f) {
            double base = y0 - 5.0 + 0.25 * -k[0];       // the knuckles rise a little toward the index finger
            r.poly(finger(t, up, k[0], base, k[0], y0 + k[2], k[1]), fill, edge, 1.2f);
        }
        double top = y0 - 5.0, bottom = y0 - 14.0;
        r.poly(new double[][]{skyAt(t, up, 0.5, top), skyAt(t, up, -5.74, top + 1.4), skyAt(t, up, -6.0, bottom + 1.5),
                skyAt(t, up, -4.6, bottom), skyAt(t, up, 0.2, bottom), skyAt(t, up, 0.55, bottom + 2)}, fill, edge, 1.2f);
        r.poly(finger(t, up, -5.2, bottom + 3.2, -9.6, bottom + 7.6, 2.0), fill, edge, 1.2f);      // the thumb
        Color mark = new Color(255, 225, 150, 230);
        double ym = y0 - 1.2;
        r.line(skyAt(t, up, -0.5, ym), skyAt(t, up, 0.5, ym), mark, 1.6f);
        r.label(skyAt(t, up, 0.5, ym), "1°", mark, 12f, 16, 4);
        double y5 = y0 - 3.4;
        r.line(skyAt(t, up, -5.74, y5), skyAt(t, up, -0.58, y5), mark, 1.6f);
        r.label(skyAt(t, up, -3.16, y5), "three fingers ≈ 5°", mark, 11.5f, 0, 16);
        r.label(skyAt(t, up, -2.7, bottom + 4), "your hand at arm's length", new Color(255, 225, 200, 210), 11.5f, 0, 0);
    }

    /** The sky's "up" for the hand: celestial north on an equatorial telescope, otherwise the zenith. */
    double[] skyUp(boolean northUp) { return northUp ? host.skyEnuRaDec(0, 90) : v(0, 0, 1); }

    /** Angular radius (rad) of a sphere of radius R at distance d. */
    static double angR(double R, double d) { return Math.asin(Math.min(1, R / d)); }

    void frameWorld() {
        double[] p = pos(host.state(WORLDS[world]));
        double d = len(p);
        camGoal = new double[]{p[0] / 2, p[1] / 2, 0.62 * d, 48, 0, p[2] / 2};
    }

    void frameMoon() {
        double[] e = pos(host.state("Earth")), m = pos(host.state("Moon"));
        double[] em = sub(m, e);
        // The Moon is up to ~35,000 km above or below the ecliptic as seen from Earth: centre in 3-D.
        camGoal = moonView == 0
                ? new double[]{em[0] / 2, em[1] / 2, 0.62 * len(em), 55, 20, em[2] / 2}
                : new double[]{em[0], em[1], 7.0e6, 62, 20, em[2]};
    }

    LessonStudio.LessonDef build() {
        List<LessonStudio.Step> s = new ArrayList<>();
        final double KM = 1e6;          // scene unit for the Earth–Moon step: 1000 km

        s.add(step("Angular size", """
                April 8, 2024, 3:18 pm, Niagara Falls. The Moon slides exactly over the Sun: a
                {k:total solar eclipse}. The Sun is 400 times wider than the Moon, but also about
                400 times farther away, so the two look the same size.

                What we measure in the sky is an {k:angular size} θ, not a real size. For a body of
                diameter D at distance d, as long as the angle is small,

                $ θ ≈ D / d   (in radians)

                This is the {k:small-angle approximation}: for small θ, sin θ ≈ tan θ ≈ θ.

                > The field is 2.5° wide. Time runs at 3 seconds per second; slide it to replay the
                eclipse.
                """)
                .watch(() -> host.skyEnu("Sun"))
                .only("AST221")
                .sky(() -> view)
                .enter(() -> {
                    host.skyPlace("Niagara Falls, NY", 43.0962, -79.0377, TORONTO);
                    host.goToUTC(2024, 4, 8, 19, 18);
                    host.setTime(3);
                    host.skyLayers(false, false, false, true);
                    double[] b = host.skyBody("Sun");
                    view = new double[]{Math.round(b[1] * 10) / 10.0, Math.round(b[0] * 10) / 10.0, 2.5};
                })
                .controls(c -> c.slider("Time speed", 0, 60, () -> host.timeScale(), x -> host.setTime(x < 0.5 ? 0 : x),
                        x -> x < 0.5 ? "paused" : String.format("%.0f s per second", x)))
                .scene((r, hud, t) -> {
                    double[] sun = host.skyBody("Sun"), moon = host.skyBody("Moon");
                    double[] se = host.skyEnu("Sun"), me = host.skyEnu("Moon");
                    r.label(se, String.format("Sun  %.1f′", sun[5] / 60), SUN_C, 12.5f, 0, -150);
                    r.label(me, String.format("Moon  %.1f′", moon[5] / 60), INK, 12.5f, 0, 150);
                    double dSunKm = sun[6] * AU / 1e3, dMoonKm = moon[6] * AU / 1e3;
                    hud.title("Angular sizes right now", SUN_C);
                    hud.row("local time", host.skyLocalTime().replaceAll("\\s+\\(.*\\)$", ""));
                    hud.row("Sun hidden by the Moon", String.format("%.1f%%", 100 * host.skyEclipsed()));
                    hud.gap();
                    hud.row("Sun: D / d", String.format("1,391,400 km / %s km", fmtKm(dSunKm)), SUN_C);
                    hud.row("Sun's angular diameter", String.format("%.4f rad = %.3f°", 1.3914e6 / dSunKm, sun[5] / 3600), SUN_C);
                    hud.row("Moon: D / d", String.format("3,474.8 km / %s km", fmtKm(dMoonKm)));
                    hud.row("Moon's angular diameter", String.format("%.4f rad = %.3f°", 3474.8 / dMoonKm, moon[5] / 3600));
                    hud.formula("θ ≈ D / d");
                }));

        // AST101 doesn't use radians: the same eclipse in degrees, with your hand for scale.
        s.add(step("Angular size", """
                April 8, 2024, 3:18 pm, Niagara Falls. The Moon slides exactly over the Sun: a
                {k:total solar eclipse}. The Sun is 400 times wider than the Moon, but it is also
                about 400 times farther away, so the two look the same size.

                How big something looks is its {k:angular size}: the angle it covers in the sky,
                in degrees. It depends on the object's real diameter D and on its distance d:

                $ angular size ≈ 57.3° × D / d

                (57.3 is just the number that turns the ratio D / d into degrees.) Twice as far
                away looks half as big. The Sun and the Moon both come out at about **half a
                degree**.

                > The field is 2.5° wide. Tick "Compare with my hand" to zoom out and hold your hand
                up beside the Sun at arm's length: your little finger is about 1° wide, twice the
                Sun. Slide the time to replay the eclipse.
                """)
                .watch(() -> host.skyEnu("Sun"))
                .only("AST101")
                .sky(() -> eclipseHand ? new double[]{view[0], view[1] - 7, 26} : view)
                .enter(() -> {
                    host.skyPlace("Niagara Falls, NY", 43.0962, -79.0377, TORONTO);
                    host.goToUTC(2024, 4, 8, 19, 18);
                    host.setTime(3);
                    host.skyNorthUp(false);
                    host.skyLayers(false, false, false, true);
                    double[] b = host.skyBody("Sun");
                    view = new double[]{Math.round(b[1] * 10) / 10.0, Math.round(b[0] * 10) / 10.0, 2.5};
                    eclipseHand = false;
                })
                .controls(c -> {
                    c.slider("Time speed", 0, 60, () -> host.timeScale(), x -> host.setTime(x < 0.5 ? 0 : x),
                            x -> x < 0.5 ? "paused" : String.format("%.0f s per second", x));
                    c.toggle("Compare with my hand (zooms out to 26°)", () -> eclipseHand, x -> eclipseHand = x);
                })
                .scene((r, hud, t) -> {
                    double[] sun = host.skyBody("Sun"), moon = host.skyBody("Moon");
                    double[] se = host.skyEnu("Sun"), me = host.skyEnu("Moon");
                    int off = eclipseHand ? 70 : 190;
                    r.label(se, String.format("Sun  %.2f°", sun[5] / 3600), SUN_C, 12.5f, -off, -off / 3);
                    r.label(me, String.format("Moon  %.2f°", moon[5] / 3600), INK, 12.5f, off, -off / 3);
                    if (eclipseHand) hand(r, se, skyUp(false), 0.45);
                    double dSunKm = sun[6] * AU / 1e3, dMoonKm = moon[6] * AU / 1e3;
                    hud.title("Angular sizes right now", SUN_C);
                    hud.row("local time", host.skyLocalTime().replaceAll("\\s+\\(.*\\)$", ""));
                    hud.row("Sun hidden by the Moon", String.format("%.1f%%", 100 * host.skyEclipsed()));
                    hud.gap();
                    hud.row("Sun: D / d", String.format("1,391,400 km / %s km", fmtKm(dSunKm)), SUN_C);
                    hud.row("Sun: 57.3° × D / d", String.format("%.3f°  (%.1f′)", 57.296 * 1.3914e6 / dSunKm, sun[5] / 60), SUN_C);
                    hud.row("Moon: D / d", String.format("3,475 km / %s km", fmtKm(dMoonKm)));
                    hud.row("Moon: 57.3° × D / d", String.format("%.3f°  (%.1f′)", 57.296 * 3474.8 / dMoonKm, moon[5] / 60));
                    hud.gap();
                    hud.row("1° = 60′ (arcminutes)", "your little finger ≈ 1°", DIM);
                    hud.formula("angular size ≈ 57.3° × D / d");
                }));

        s.add(step("Same angle, different sizes", """
                The same line-up in space, at true scale. The gold lines are the {k:cone of
                sunlight}: every direction from the middle of the Moon's shadow on the ground that
                lands on the Sun. The cone's angle is the Sun's half degree, so it widens with
                distance: at the Moon it is about as wide as the Moon.

                The Moon's orbit is a stretched circle, so its distance changes, from 357,000 km at
                its closest (perigee) to 407,000 km at its farthest (apogee).

                **Near**: the Moon is wider than the cone and hides the whole Sun: a
                {k:total eclipse}.

                **Far**: the Moon is narrower than the cone and a ring of Sun shows around it: an
                {k:annular eclipse}.

                > Pick an eclipse. Switch to "Down the line" to look along the cone from the
                ground. The inset is what you'd see there.
                """)
                .watch(() -> scaleV(pos(host.state("Moon")), 1 / KM))
                .only("AST101")
                .unit(KM)
                .cam("Earth", () -> camGoal)
                .enter(() -> {
                    host.showOnly(null, "Earth"); host.setOrbits(false, true); host.setTime(0);
                    int[] d = ECLIPSES[eclipse];
                    host.goToUTC(d[0], d[1], d[2], d[3], d[4]);
                    frameCone();
                })
                .controls(c -> {
                    c.choices("Eclipse", ECLIPSE_NAMES, () -> eclipse, k -> {
                        eclipse = k;
                        int[] d = ECLIPSES[k];
                        host.goToUTC(d[0], d[1], d[2], d[3], d[4]);
                        frameCone();
                    });
                    c.choices("View", new String[]{"Side view", "Down the line"}, () -> coneView, k -> { coneView = k; frameCone(); });
                })
                .scene((r, hud, t) -> {
                    double[] p = shadowPoint(), m = pos(host.state("Moon")), u = norm(m);
                    double dS = len(p), dM = len(sub(m, p));
                    double aS = Math.asin(R_SUN / dS), aM = Math.asin(R_MOON / dM);   // angular radii from the ground
                    double[] side = norm(cross(u, v(0, 0, 1))), up = cross(side, u);
                    double[] pk = scaleV(p, 1 / KM), mk = scaleV(m, 1 / KM);
                    double reach = dM * 1.12 / KM, coneR = Math.tan(aS) * dM / KM;
                    // The cone: a few lines along it from the ground, and its cross-section at the Moon.
                    for (int i = 0; i < 8; i++) {
                        double th = 2 * Math.PI * i / 8;
                        double[] dir = norm(add(scaleV(u, -1), scaleV(add(scaleV(side, Math.cos(th)), scaleV(up, Math.sin(th))), Math.tan(aS))));
                        r.seg(pk, add(pk, scaleV(dir, reach)), alpha(SUN_C, i % 4 == 0 ? 220 : 90), i % 4 == 0 ? 1.6f : 1f);
                    }
                    double[][] ring = new double[120][];
                    for (int i = 0; i < 120; i++) {
                        double th = 2 * Math.PI * i / 120;
                        ring[i] = add(mk, scaleV(add(scaleV(side, Math.cos(th)), scaleV(up, Math.sin(th))), coneR));
                    }
                    r.polyline(ring, true, SUN_C, 2f, false);
                    r.dot(pk, 4, ACCENT);
                    r.label(pk, "middle of the shadow", ACCENT, 12f, 0, 18);
                    boolean total = aM > aS;
                    double coneKm = 2 * Math.tan(aS) * dM / 1e3, moonKm = 2 * R_MOON / 1e3;
                    if (coneView == 1) {
                        r.label(add(mk, scaleV(up, coneR)), String.format("cone: %,.0f km wide here", coneKm), SUN_C, 12.5f, 0, -14);
                        r.label(add(mk, scaleV(up, -R_MOON / KM)), "Moon: 3,475 km", INK, 12.5f, 0, 22);
                    } else {
                        r.label(mk, String.format("Moon, %,.0f km up", dM / 1e3), INK, 12.5f, 0, -18);
                    }
                    hud.title(total ? "Total: the Moon covers the cone" : "Annular: the Moon is too small", total ? SUN_C : ORANGE);
                    hud.row("Moon's distance (from the ground)", fmtKm(dM / 1e3) + " km");
                    hud.row("Moon: 57.3° × 3,475 km / d", String.format("%.3f°", 57.296 * moonKm * 1e3 / dM));
                    hud.row("Sun: 57.3° × 1,391,400 km / d", String.format("%.3f°", 57.296 * 2 * R_SUN / dS), SUN_C);
                    hud.row("Moon's width / cone's width", String.format("%,.0f km / %,.0f km = %.3f", moonKm, coneKm, moonKm / coneKm),
                            total ? SUN_C : ORANGE);
                    hud.formula("angular size ≈ 57.3° × D / d");
                    hud.inset("From the shadow's middle (1.2°)", 220, 220, (g, b) -> eclipseInset(g, b, aS, aM));
                }));

        s.add(step("A radian is huge; sky angles are tiny", """
                The white lines run from the centre of the Earth to the two edges of the Moon. They
                enclose the Moon's angular diameter: 3,475 km across at its average distance of 384,400 km gives
                **0.0090 rad = 0.52°**.

                A full radian is 57.3° and, since 1° = 60′ (arcminutes) and 1′ = 60″ (arcseconds),

                $ 1 rad = 206,265″

                That number gives the {k:parsec}: the distance at which 1 AU spans 1″, which is
                206,265 AU.

                > Switch to the view at the Moon: there the two lines are 3,475 km apart. Back at
                Earth they meet.
                """)
                .unit(KM)
                .cam("Earth", () -> camGoal)
                .enter(() -> {
                    host.showOnly(null, "Earth"); host.setOrbits(false, true); host.setTime(0);
                    host.goToUTC(2026, 9, 22, 1, 0);
                    frameMoon();
                })
                .controls(c -> c.choices("View", new String[]{"Earth to Moon", "At the Moon"}, () -> moonView,
                        k -> { moonView = k; frameMoon(); }))
                .scene((r, hud, t) -> {
                    double[] e = scaleV(pos(host.state("Earth")), 1 / KM), m = scaleV(pos(host.state("Moon")), 1 / KM);
                    double[] los = norm(sub(m, e)), side = norm(cross(los, v(0, 0, 1)));
                    double rm = R_MOON / KM;
                    double[] l1 = add(m, scaleV(side, rm)), l2 = add(m, scaleV(side, -rm));
                    r.seg(e, l1, Color.WHITE, 1.4f);
                    r.seg(e, l2, Color.WHITE, 1.4f);
                    r.ring(m, moonView == 0 ? 7 : 4, alpha(INK, 160), 1.4f);
                    double d = len(sub(m, e)) * KM, th = 2 * R_MOON / d;
                    if (moonView == 1) {
                        r.line(l1, l2, SUN_C, 2.2f, false);
                        r.label(m, "D = 3,475 km", SUN_C, 13f, 0, -40);
                        r.label(lerp(e, m, 0.97), "to Earth: 384,400 km", INK, 12f, 0, 18);
                    } else {
                            r.label(lerp(e, m, 0.5), String.format("θ = %.2f°", Math.toDegrees(th)), SUN_C, 13f, 0, -16);
                    }
                    hud.title("The Moon's angular diameter", SUN_C);
                    hud.row("diameter D", "3,474.8 km");
                    hud.row("distance d (centre to centre)", fmtKm(d / 1e3) + " km");
                    hud.row("θ = D / d", String.format("%.5f rad", th), SUN_C);
                    hud.row("in degrees  (× 57.296)", String.format("%.3f°", Math.toDegrees(th)));
                    hud.row("in arcseconds  (× 206,265)", String.format("%,.0f″", th / ARCSEC));
                    hud.row("tan θ − θ", sci(Math.tan(th) - th, 1) + " rad", DIM);
                    hud.formula("1 rad = 57.296° = 206,265″");
                }));

        s.add(step("Solid angle: how much sky the Sun covers", """
                Angular size is one-dimensional. The two-dimensional version is {k:solid angle} Ω,
                an "angular area": the area an object covers on a sphere of radius 1 around you.
                It is measured in {k:steradians} (sr). The whole sky is the unit sphere's whole
                area,

                $ Ω(whole sky) = 4π sr

                A disc of angular radius α (small) covers Ω ≈ πα². From Earth the Sun covers
                6.8 × 10⁻⁵ sr: about 5 millionths of the sky.

                > Pick a world to see how big the Sun looks from there. The inset shows the Sun at true
                scale in a 2° field; the dashed ring is how it looks from Earth.
                """)
                .unit(AU)
                .cam(() -> camGoal)
                .enter(() -> {
                    host.showOnly(null, WORLDS); host.setOrbits(true, true); host.setTime(0);
                    frameWorld();
                })
                .controls(c -> c.choices("Seen from", WORLDS, () -> world, k -> {
                    world = k; frameWorld();
                }))
                .scene((r, hud, t) -> {
                    double[] p = scaleV(pos(host.state(WORLDS[world])), 1 / AU);
                    double d = len(p) * AU, a = angR(R_SUN, d), om = 2 * Math.PI * (1 - Math.cos(a));
                    r.line(v(0, 0, 0), p, alpha(SUN_C, 170), 1.6f, true);
                    r.ring(p, 9, SUN_C, 2f);
                    r.label(p, WORLDS[world], SUN_C, 12.5f, 0, -22);
                    hud.title("The Sun from " + WORLDS[world], SUN_C);
                    hud.row("distance", au(d / AU));
                    hud.row("angular diameter 2α", angle(2 * a / ARCSEC), SUN_C);
                    hud.row("solid angle Ω ≈ πα²", sci(om, 2) + " sr", SUN_C);
                    hud.row("fraction of the sky Ω / 4π", sci(om / (4 * Math.PI), 1));
                    hud.row("compared with Earth's view", String.format("%.3g×", om / 6.80e-5));
                    hud.formula("Ω ≈ π α²   (α = R / d)");
                    hud.inset("The Sun from " + WORLDS[world] + " (2° field)", 220, 220, (g, b) -> sunInset(g, b, a));
                }));

        s.add(step("A planet's tiny disc", """
                Jupiter at opposition, January 10, 2026, seen from Mississauga at midnight through a
                telescope. The field here is only **0.1° wide** (6′).

                Jupiter is 143,000 km across, eleven times wider than Earth, but it is 4.2 AU away.
                Its disc spans

                $ θ = D / d ≈ 47″

                about 1/40 of the Moon's width. The four dots in a line are the Galilean moons, at
                their real positions that night.

                > Zoom out with the scroll wheel to see how small Jupiter is to the naked eye.
                """)
                .watch(() -> host.skyEnu("Jupiter"))
                .only("AST221")
                .sky("Jupiter", () -> new double[]{0, 0, 0.1})
                .enter(() -> {
                    host.skyPlace(UTM, UTM_LAT, UTM_LON, TORONTO);
                    host.goToUTC(2026, 1, 10, 5, 0);
                    host.setTime(60);
                    host.skyNorthUp(true);
                    host.skyLayers(false, false, false, true);
                })
                .controls(c -> c.slider("Time speed", 0, 30, () -> host.timeScale() / 60, x -> host.setTime(x < 0.3 ? 0 : x * 60),
                        x -> x < 0.3 ? "paused" : String.format("%.0f min per second", x)))
                .scene((r, hud, t) -> {
                    double[] j = host.skyBody("Jupiter");
                    double dKm = j[6] * AU / 1e3;
                    hud.title("Jupiter tonight", SUN_C);
                    hud.row("distance d", String.format("%.3f AU = %s km", j[6], fmtKm(dKm)));
                    hud.row("equatorial diameter D", "142,984 km");
                    hud.row("θ = D / d", String.format("%.2e rad = %.1f″", 142984 / dKm, 142984 / dKm / ARCSEC), SUN_C);
                    hud.row("the Moon, for comparison", "≈ 1,870″ (31′)");
                    hud.row("magnitude", String.format("%.1f", j[4]));
                    hud.formula("θ (arcsec) = 206,265 × D / d");
                }));

        s.add(step("A planet's tiny disc", """
                Jupiter at opposition, January 10, 2026, seen from Mississauga at midnight. Jupiter
                is 143,000 km across, eleven times wider than Earth, but it is 630 million km away,
                so its angular size is

                $ 57.3° × 143,000 km / 630,000,000 km ≈ 0.013°

                That is 47 {k:arcseconds} (1° = 60 arcminutes = 3,600 arcseconds), about 1/40 of the
                Moon's width. To your eye Jupiter is a point of light. It takes a telescope to see
                its disc.

                > Step through the fields of view, from your naked eye (with your hand at arm's length
                for scale) to a big telescope. The dashed
                circle is how big the full Moon would look. The four dots in a line are Jupiter's
                Galilean moons, at their real places that night.
                """)
                .watch(() -> host.skyEnu("Jupiter"))
                .only("AST101")
                .sky("Jupiter", () -> new double[]{0, 0, FIELDS[field]})
                .enter(() -> {
                    host.skyPlace(UTM, UTM_LAT, UTM_LON, TORONTO);
                    host.goToUTC(2026, 1, 10, 5, 0);
                    host.setTime(60);
                    host.skyNorthUp(true);
                    host.skyLayers(false, false, false, true);
                    field = 0;
                })
                .controls(c -> {
                    c.choices("Field of view", FIELD_NAMES, () -> field, k -> field = k);
                    c.toggle("Show my hand at arm's length", () -> showHand, x -> showHand = x);
                    c.slider("Time speed", 0, 30, () -> host.timeScale() / 60, x -> host.setTime(x < 0.3 ? 0 : x * 60),
                            x -> x < 0.3 ? "paused" : String.format("%.0f min per second", x));
                })
                .scene((r, hud, t) -> {
                    double[] j = host.skyBody("Jupiter"), je = host.skyEnu("Jupiter"), up = skyUp(true);
                    double dKm = j[6] * AU / 1e3, deg = 57.296 * 142984 / dKm;
                    double moonR = 0.259;                                  // the full Moon's angular radius (°)
                    if (FIELDS[field] < 10) {                              // at 60° it is a speck
                        LessonKit.skyCircle(r, je, moonR, alpha(INK, 170), 1.3f, true);
                        if (moonR < 0.45 * FIELDS[field])
                            r.label(skyAt(je, up, 0, moonR), "the full Moon, this big", alpha(INK, 210), 11.5f, 0, -12);
                    }
                    if (showHand && FIELDS[field] >= 20) hand(r, je, up, 0.6);   // a hand is a naked-eye ruler
                    hud.title("Jupiter tonight", SUN_C);
                    hud.row("distance d", String.format("%s km", fmtKm(dKm)));
                    hud.row("diameter D", "142,984 km");
                    hud.row("57.3° × D / d", String.format("%.4f°  =  %.0f″", deg, deg * 3600), SUN_C);
                    hud.row("the full Moon, for comparison", "0.52°  =  1,870″");
                    hud.row("field of view", FIELD_NAMES[field]);
                    hud.row("Jupiter's width / the field", String.format("1 / %,.0f", FIELDS[field] / deg), DIM);
                    if (FIELDS[field] < 0.52) hud.row("the full Moon would span", String.format("%.0f× this field", 0.52 / FIELDS[field]), DIM);
                    hud.formula("angular size ≈ 57.3° × D / d");
                }));

        s.add(step("Check: the Moon's angular size", """
                From the slides: the Moon has diameter **3.5 × 10³ km** and distance
                **3.8 × 10⁵ km**.
                """)
                .reviews("A radian is huge; sky angles are tiny")
                .only("AST221")
                .unit(KM)
                .cam("Earth", () -> camGoal)
                .enter(() -> {
                    host.showOnly(null, "Earth"); host.setOrbits(false, true); host.setTime(0);
                    moonView = 1; frameMoon();
                })
                .quiz("What is the Moon's angular diameter?", 1,
                      "θ = D / d = 3.5 × 10³ / 3.8 × 10⁵ = 0.0092 rad, and 0.0092 × 57.3° = 0.53°: about half a "
                    + "degree, the same as the Sun (1.4 × 10⁶ km / 1.5 × 10⁸ km = 0.0093 rad).",
                      "About 5°", "About 0.5°", "About 0.05°", "About 30°")
                .scene((r, hud, t) -> {
                    double[] m = scaleV(pos(host.state("Moon")), 1 / KM);
                    r.ring(m, 4, alpha(INK, 160), 1.4f);
                }));

        s.add(step("Check: the Moon's angular size", """
                From the slides: the Moon has diameter **3.5 × 10³ km** and distance
                **3.8 × 10⁵ km**.
                """)
                .reviews("Angular size")
                .only("AST101")
                .unit(KM)
                .cam("Earth", () -> camGoal)
                .enter(() -> {
                    host.showOnly(null, "Earth"); host.setOrbits(false, true); host.setTime(0);
                    moonView = 1; frameMoon();
                })
                .quiz("What is the Moon's angular diameter?", 1,
                      "Angular size ≈ 57.3° × D / d = 57.3° × 3.5 × 10³ / 3.8 × 10⁵ = 0.53°: about half a degree, "
                    + "half your little finger at arm's length. The Sun comes out the same: "
                    + "57.3° × 1.4 × 10⁶ km / 1.5 × 10⁸ km = 0.53°.",
                      "About 5°", "About 0.5°", "About 0.05°", "About 30°")
                .scene((r, hud, t) -> {
                    double[] m = scaleV(pos(host.state("Moon")), 1 / KM);
                    r.ring(m, 4, alpha(INK, 160), 1.4f);
                }));

        s.add(step("Check: the Sun's solid angle", """
                From the slides: the Sun has radius **7 × 10⁵ km** and is **1.5 × 10⁸ km** away.
                """)
                .reviews("Solid angle: how much sky the Sun covers")
                .unit(AU)
                .cam(() -> camGoal)
                .enter(() -> {
                    host.showOnly(null, "Earth"); host.setOrbits(true, true); host.setTime(0);
                    world = 2; frameWorld();
                })
                .quiz("What solid angle does the Sun cover in Earth's sky?", 2,
                      "α = R / d = 7 × 10⁵ / 1.5 × 10⁸ = 4.7 × 10⁻³ rad, so Ω ≈ πα² = π × (4.7 × 10⁻³)² ≈ "
                    + "6.8 × 10⁻⁵ sr. Divide by 4π sr: the Sun fills about 5 millionths of the sky.",
                      "4π sr", "4.7 × 10⁻³ sr", "6.8 × 10⁻⁵ sr", "0.53 sr")
                .scene((r, hud, t) -> {
                    double[] p = scaleV(pos(host.state("Earth")), 1 / AU);
                    r.line(v(0, 0, 0), p, alpha(SUN_C, 170), 1.6f, true);
                    r.label(lerp(v(0, 0, 0), p, 0.5), "d = 1.5 × 10⁸ km", SUN_C, 12.5f, 0, -16);
                    r.ring(p, 9, ACCENT, 2f);
                    r.labelBelow(v(0, 0, 0), R_SUN / AU, "Sun: R = 7 × 10⁵ km", SUN_C, 12.5f);
                    hud.inset("The Sun from Earth (2° field)", 220, 220, (g, b) -> sunInset(g, b, angR(R_SUN, len(p) * AU)));
                }));

        return new LessonStudio.LessonDef("Beginner", "How Big Does It Look?",
                "Angular size, the small-angle approximation and solid angle",
                "Watch the Moon exactly cover the Sun over Niagara Falls, measure the thin wedge of a "
              + "half-degree angle, see how big the Sun looks from every planet, and how tiny Jupiter's disc is.",
                new Color(255, 200, 110), s, _ -> { });
    }

    static String fmtKm(double km) { return String.format("%,.0f", km); }

    /** The Sun and the Moon at true angular size (radii in radians) in a 1.2°-wide field, centred on each other. */
    static void eclipseInset(Graphics2D g, Rectangle b, double sunR, double moonR) {
        double cx = b.getCenterX(), cy = b.getCenterY(), pxPerDeg = b.width / 1.2;
        double rs = Math.toDegrees(sunR) * pxPerDeg, rm = Math.toDegrees(moonR) * pxPerDeg;
        g.setColor(new Color(6, 8, 16));
        g.fill(b);
        if (rm >= rs) {                                   // totality: only the corona shows around the Moon
            g.setPaint(new RadialGradientPaint(new Point2D.Double(cx, cy), (float) (rs * 2.2),
                    new float[]{0f, 0.45f, 1f}, new Color[]{new Color(235, 240, 255, 200), new Color(200, 215, 255, 70), new Color(200, 215, 255, 0)}));
            g.fill(new Ellipse2D.Double(cx - rs * 2.2, cy - rs * 2.2, rs * 4.4, rs * 4.4));
        } else {
            g.setPaint(new RadialGradientPaint(new Point2D.Double(cx, cy), (float) rs,
                    new float[]{0f, 0.8f, 1f}, new Color[]{new Color(255, 248, 225), new Color(255, 222, 150), new Color(240, 170, 80)}));
            g.fill(new Ellipse2D.Double(cx - rs, cy - rs, 2 * rs, 2 * rs));
        }
        g.setColor(new Color(4, 4, 6));
        g.fill(new Ellipse2D.Double(cx - rm, cy - rm, 2 * rm, 2 * rm));
        g.setFont(LessonStudio.uiFont(Font.PLAIN, 11f));
        g.setColor(rm >= rs ? new Color(220, 228, 255) : new Color(255, 200, 120));
        String s = rm >= rs ? "Total: the Moon hides the whole Sun" : "Annular: a ring of Sun shows";
        g.drawString(s, (float) (b.x + 8), (float) (b.getMaxY() - 8));
    }

    /** The Sun's disc at true angular scale in a 2°-wide field, with Earth's view dashed for scale. */
    static void sunInset(Graphics2D g, Rectangle b, double angRad) {
        double cx = b.getCenterX(), cy = b.getCenterY(), pxPerDeg = b.width / 2.0;
        double r = Math.toDegrees(angRad) * pxPerDeg, rE = 0.2666 * pxPerDeg;
        if (r < 1.2) {
            g.setColor(new Color(255, 240, 210));
            g.fill(new Ellipse2D.Double(cx - 1.4, cy - 1.4, 2.8, 2.8));
        } else {
            g.setPaint(new RadialGradientPaint(new Point2D.Double(cx, cy), (float) r,
                    new float[]{0f, 0.8f, 1f}, new Color[]{new Color(255, 248, 225), new Color(255, 222, 150), new Color(240, 170, 80)}));
            g.fill(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
        }
        g.setColor(new Color(150, 200, 255, 200));
        g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[]{4f, 4f}, 0f));
        g.draw(new Ellipse2D.Double(cx - rE, cy - rE, 2 * rE, 2 * rE));
        g.setStroke(new BasicStroke(1.5f));
        g.setColor(DIM);
        double y = b.getMaxY() - 8;
        g.draw(new Line2D.Double(b.x + 8, y, b.x + 8 + pxPerDeg / 2, y));
        g.setFont(LessonStudio.uiFont(Font.PLAIN, 10.5f));
        g.drawString("0.5°", (float) (b.x + 8), (float) (y - 5));
    }
}
