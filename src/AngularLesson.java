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
    static final double R_MOON = 1737.4e3;
    int world = 2;
    int moonView;                 // 0 = the whole Earth–Moon line, 1 = at the Moon
    double[] view = {0, 0, 1};
    double[] camGoal = {0, 0, 1, 50, 0};

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

        s.add(step("Check: the Moon's angular size", """
                From the slides: the Moon has diameter **3.5 × 10³ km** and distance
                **3.8 × 10⁵ km**.
                """)
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

        s.add(step("Check: the Sun's solid angle", """
                From the slides: the Sun has radius **7 × 10⁵ km** and is **1.5 × 10⁸ km** away.
                """)
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
