import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Lesson: Titan Lab (AST221 final-project rehearsal, Days 3 and 7). Through a small telescope
 * in Mississauga, log Titan's real east–west offset from Saturn night after night (clouds
 * permitting), fit a sinusoid by least squares to find its orbital period, then weigh Saturn
 * with Kepler's third law, exactly as the final project asks.
 */
final class TitanLesson extends LessonKit {

    TitanLesson(LessonStudio.Host host) { super(host); }

    static final double P_TITAN = 15.945;                 // days (JPL), for comparison only
    final List<double[]> log = new ArrayList<>();          // {days since the first night, east offset ″, Saturn distance AU}
    double logT0 = Double.NaN;
    int every = 1;                                         // nights between observations
    boolean clouds;
    int autoLeft;
    double autoT;
    final Random weather = new Random(221);
    /** Best fit {period d, amplitude ″, phase rad, offset ″} or null with fewer than 5 points. */
    double[] fit;

    void setup() {
        host.skyPlace(UTM, UTM_LAT, UTM_LON, TORONTO);
        // No ground: the telescope keeps Saturn in its field even after it sets (it is then only
        // dimmed by daylight), so the field never fills with horizon.
        host.skyLayers(false, false, false, false);
        host.skyOptics(5.0, 100, null);                    // a 100 mm telescope under a suburban sky
        host.skyNorthUp(true);                             // on an equatorial mount: no field rotation
    }

    /** Titan's offset from Saturn {east ″, north ″}. */
    double[] offset() {
        double[] t = host.skyBody("Titan"), s = host.skyBody("Saturn");
        double dRa = t[2] - s[2];
        if (dRa > 12) dRa -= 24; else if (dRa < -12) dRa += 24;
        return new double[]{dRa * 15 * 3600 * Math.cos(Math.toRadians(s[3])), (t[3] - s[3]) * 3600};
    }

    /** Record tonight (unless cloudy) and move the clock on to the next observing night. */
    void observeAndAdvance() {
        boolean cloudy = clouds && weather.nextDouble() < 0.3;
        if (!cloudy) {
            if (Double.isNaN(logT0)) logT0 = host.simTime();
            log.add(new double[]{(host.simTime() - logT0) / DAY, offset()[0], host.skyBody("Saturn")[6]});
            refit();
        }
        lastCloudy = cloudy;
        jumpTo(host.simTime() + every * DAY);
    }
    boolean lastCloudy;

    void clearLog() { log.clear(); logT0 = Double.NaN; fit = null; autoLeft = 0; }

    /** Least-squares fit of x(t) = a sin ωt + b cos ωt + c, scanning the period. */
    void refit() {
        if (log.size() < 5) { fit = null; return; }
        double best = Double.MAX_VALUE, bestP = 0;
        double[] bestAbc = null;
        for (int pass = 0; pass < 2; pass++) {
            double lo = pass == 0 ? 2 : bestP - 0.05, hi = pass == 0 ? 40 : bestP + 0.05, step = pass == 0 ? 0.005 : 0.0002;
            for (double P = lo; P <= hi; P += step) {
                double[] abc = solve(P);
                if (abc == null) continue;
                double chi = abc[3];
                if (chi < best) { best = chi; bestP = P; bestAbc = abc; }
            }
        }
        if (bestAbc == null) { fit = null; return; }
        fit = new double[]{bestP, Math.hypot(bestAbc[0], bestAbc[1]), Math.atan2(bestAbc[1], bestAbc[0]), bestAbc[2], best};
    }

    /** Normal equations for a, b, c at one period; returns {a, b, c, χ²}. */
    double[] solve(double P) {
        double w = 2 * Math.PI / P;
        double[][] A = new double[3][4];
        for (double[] o : log) {
            double[] f = {Math.sin(w * o[0]), Math.cos(w * o[0]), 1};
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) A[i][j] += f[i] * f[j];
                A[i][3] += f[i] * o[1];
            }
        }
        for (int c = 0; c < 3; c++) {                       // Gauss–Jordan with partial pivoting
            int piv = c;
            for (int r = c + 1; r < 3; r++) if (Math.abs(A[r][c]) > Math.abs(A[piv][c])) piv = r;
            double[] tmp = A[c]; A[c] = A[piv]; A[piv] = tmp;
            if (Math.abs(A[c][c]) < 1e-12) return null;
            for (int r = 0; r < 3; r++) {
                if (r == c) continue;
                double k = A[r][c] / A[c][c];
                for (int j = c; j < 4; j++) A[r][j] -= k * A[c][j];
            }
        }
        double a = A[0][3] / A[0][0], b = A[1][3] / A[1][1], c = A[2][3] / A[2][2];
        double chi = 0;
        for (double[] o : log) chi += sq(o[1] - (a * Math.sin(w * o[0]) + b * Math.cos(w * o[0]) + c));
        return new double[]{a, b, c, chi};
    }

    void tick(double dt) {
        if (autoLeft <= 0) return;
        autoT += dt;
        if (autoT >= 0.35) { autoT = 0; observeAndAdvance(); autoLeft--; }
    }

    void logInset(Graphics2D g, Rectangle b) {
        double span = 40;
        for (double[] o : log) span = Math.max(span, o[0] + 2);
        Plot p = new Plot(b, 0, span, false, -260, 260, false);
        double[] xt = ticks(0, span, span > 80 ? 20 : 10);
        p.axes(g, "days", "east offset (″)", xt, labels(xt, "%.0f"), new double[]{-200, 0, 200}, new String[]{"−200", "0", "200"});
        p.hline(g, 0, alpha(Color.WHITE, 80));
        if (fit != null) {
            int n = 400;
            double[] xs = new double[n], ys = new double[n];
            double w = 2 * Math.PI / fit[0];
            for (int i = 0; i < n; i++) xs[i] = span * i / (n - 1);
            double a = fit[1] * Math.cos(fit[2]), bb = fit[1] * Math.sin(fit[2]);      // back to a sin ωt + b cos ωt
            for (int i = 0; i < n; i++) ys[i] = a * Math.sin(w * xs[i]) + bb * Math.cos(w * xs[i]) + fit[3];
            p.curve(g, xs, ys, alpha(GREEN, 200), 1.6f);
        }
        for (double[] o : log) p.dot(g, o[0], o[1], 3.2, SUN_C);
        if (log.isEmpty()) {
            g.setFont(LessonStudio.uiFont(Font.PLAIN, 11f));
            g.setColor(DIM);
            g.drawString("no observations yet: press Observe", b.x + 50, (float) b.getCenterY());
        }
    }

    void fitRows(LessonStudio.Hud hud) {
        hud.row("nights logged", String.valueOf(log.size()));
        if (fit == null) { hud.row("best-fit period", "needs 5 nights", DIM); return; }
        hud.row("best-fit period", String.format("%.2f days", fit[0]), GREEN);
        hud.row("amplitude (greatest elongation)", String.format("%.0f″", fit[1]), GREEN);
        hud.row("Titan's true period", String.format("%.3f days", P_TITAN), DIM);
    }

    LessonStudio.LessonDef build() {
        List<LessonStudio.Step> s = new ArrayList<>();

        s.add(step("Saturn and Titan in a small telescope", """
                One AST221 final project is to measure the orbital period of **Titan**, Saturn's
                largest moon, as Huygens did in 1655. This is the real view from Mississauga on
                September 21, 2026 at 10 pm, through a **100 mm telescope** that tracks Saturn. The
                field is 12′ wide, with north up and east to the left as on an equatorially mounted
                telescope. Saturn's rings are nearly edge-on this year.

                Titan (magnitude 8.4) is the brightest of the dots strung along the ring plane. Its
                distance east or west of Saturn changes from night to night as it orbits; the
                fainter ones are Rhea, Dione and Tethys.

                > Time runs at an hour per second: you can just see Titan creep.
                """)
                .sky("Saturn", () -> new double[]{0, 0, 0.2})
                .enter(() -> {
                    setup();
                    host.goToUTC(2026, 9, 22, 2, 0);
                    host.setTime(3600);
                })
                .controls(c -> timeSlider(c, "hours", 3600, 2))
                .scene((r, hud, t) -> {
                    double[] off = offset(), tb = host.skyBody("Titan"), sb = host.skyBody("Saturn");
                    r.ring(host.skyEnu("Titan"), 9, GREEN, 1.8f);
                    hud.title("Titan tonight", GREEN);
                    hud.row("local time", host.skyLocalTime().replaceAll("\\s+\\(.*\\)$", ""));
                    hud.row("Titan's offset", String.format("%s″ %s,  %s″ %s", fmt0(Math.abs(off[0])), off[0] >= 0 ? "E" : "W",
                            fmt0(Math.abs(off[1])), off[1] >= 0 ? "N" : "S"), GREEN);
                    hud.row("Titan's magnitude", String.format("%.1f", tb[4]));
                    hud.row("Saturn's distance", String.format("%.3f AU", sb[6]));
                    hud.formula("offset east = ΔRA × cos(Dec)");
                }));

        s.add(step("Logging night after night", """
                A single night tells you little: you need Titan's position on many nights. Press
                **Observe** to record Titan's east–west offset (east positive) and move on to the
                next observing night at 10 pm. The inset plots your log.

                Real skies are not always clear. Turn on the weather and about 3 nights in 10 are
                lost to cloud, leaving gaps, just as in the real project.

                > Log at least two weeks, or press "Observe 24 nights" to let it run.
                """)
                .sky("Saturn", () -> new double[]{0, 0, 0.2})
                .enter(() -> {
                    setup();
                    if (log.isEmpty()) host.goToUTC(2026, 9, 22, 2, 0);
                    host.setTime(0);
                })
                .controls(c -> {
                    c.button("Observe tonight, then wait one night", () -> { every = Math.max(1, every); observeAndAdvance(); });
                    c.button("Observe 24 nights", () -> autoLeft = 24);
                    c.toggle("Weather: about 30% of nights cloudy", () -> clouds, x -> clouds = x);
                    c.button("Clear the log", this::clearLog);
                })
                .scene((r, hud, t) -> {
                    double[] off = offset();
                    r.ring(host.skyEnu("Titan"), 9, GREEN, 1.8f);
                    hud.title("Observing log", SUN_C);
                    hud.row("tonight", host.skyLocalTime().replaceAll("\\s+\\d\\d:\\d\\d:\\d\\d.*$", ""));
                    hud.row("Titan's east offset now", signed("%+.0f″", off[0]), GREEN);
                    if (lastCloudy) hud.row("last night", "clouded out", PERI_C);
                    fitRows(hud);
                    hud.inset("Titan's offset, night by night", 280, 190, this::logInset);
                }));

        s.add(step("Fitting the period", """
                A moon on a circular orbit, seen edge-on, swings east and west of its planet like a
                pendulum: a sine curve,

                $ x(t) = A sin(2πt / P + φ) + c

                The green curve is the {k:least-squares fit}: for each trial period P the program finds
                the A, φ and c that make the sum of squared misses smallest, and keeps the P with the
                smallest sum of all. With a dozen good nights it lands within a few hundredths of a
                day of Titan's true 15.945 days.

                > Try observing less often, every few nights, and see how the fit copes.
                """)
                .sky("Saturn", () -> new double[]{0, 0, 0.2})
                .enter(() -> {
                    setup();
                    if (log.size() < 8) { clearLog(); host.goToUTC(2026, 9, 22, 2, 0); autoLeft = 16; }
                    host.setTime(0);
                })
                .controls(c -> {
                    c.slider("Nights between observations", 1, 12, () -> every, x -> every = (int) Math.round(x), x -> String.valueOf((int) Math.round(x)));
                    c.button("Observe 16 more", () -> autoLeft = 16);
                    c.button("Clear the log", this::clearLog);
                })
                .scene((r, hud, t) -> {
                    r.ring(host.skyEnu("Titan"), 9, GREEN, 1.8f);
                    hud.title("Least-squares fit", GREEN);
                    hud.row("observing every", every + (every == 1 ? " night" : " nights"));
                    fitRows(hud);
                    hud.formula("minimise Σ (x(observed) − x(model))²");
                    hud.inset("Titan's offset, night by night", 280, 190, this::logInset);
                }));

        s.add(step("Weigh Saturn", """
                Your fit gives two numbers: the period P and the amplitude A, Titan's greatest angular
                distance from Saturn. Multiply A (in radians) by Saturn's distance to get the size of
                Titan's orbit, a. Kepler's third law then weighs Saturn:

                $ M(Saturn) = 4π² a³ / G P²

                This year Titan's orbit is seen almost edge-on (like the rings), so its east–west swing
                is very nearly the full orbit. The answer should come out close to Saturn's real mass,
                5.68 × 10²⁶ kg: 95 Earths.
                """)
                .sky("Saturn", () -> new double[]{0, 0, 0.2})
                .enter(() -> {
                    setup();
                    if (fit == null) { clearLog(); every = 1; host.goToUTC(2026, 9, 22, 2, 0); autoLeft = 18; }
                    host.setTime(0);
                })
                .controls(c -> c.button("Clear and observe 18 nights", () -> { clearLog(); every = 1; autoLeft = 18; }))
                .scene((r, hud, t) -> {
                    r.ring(host.skyEnu("Titan"), 9, GREEN, 1.8f);
                    hud.title("Saturn's mass from your data", SUN_C);
                    fitRows(hud);
                    if (fit != null) {
                        double dist = 0;
                        for (double[] o : log) dist += o[2];
                        dist /= log.size();
                        double a = fit[1] * ARCSEC * dist * AU, P = fit[0] * DAY;
                        double M = 4 * Math.PI * Math.PI * a * a * a / (G * P * P);
                        hud.gap();
                        hud.row("mean Saturn distance", String.format("%.3f AU", dist));
                        hud.row("Titan's orbit a = A × d", String.format("%,.0f km", a / 1e3), GREEN);
                        hud.row("M = 4π²a³ / GP²", sci(M, 3) + " kg", SUN_C);
                        hud.row("JPL's Saturn system", sci(host.gm("Saturn") / G, 3) + " kg", DIM);
                    }
                    hud.formula("M = 4π² a³ / (G P²)");
                    hud.inset("Titan's offset, night by night", 280, 190, this::logInset);
                }));

        s.add(step("Check: one observation every 16 nights", """
                Suppose the only telescope time you can get is one night in every 16.
                """)
                .beyondSlides()                 // aliasing isn't in the slides
                .sky("Saturn", () -> new double[]{0, 0, 0.2})
                .enter(() -> { setup(); host.setTime(0); })
                .quiz("What would your log of Titan look like?", 2,
                      "16 nights is almost exactly one orbit (15.95 days), so Titan is back near the same place "
                    + "each time and its offset barely changes. You could not measure the period at all. You "
                    + "must sample faster than twice per orbit, so at least every 7 nights or so.",
                      "A clean sine curve with a 16-day period", "A sine curve with an 8-day period",
                      "Nearly the same offset every time, so no period at all", "Random scatter")
                .scene((r, hud, t) -> r.ring(host.skyEnu("Titan"), 9, GREEN, 1.8f)));

        return new LessonStudio.LessonDef("Intermediate", "Titan Lab",
                "Final-project rehearsal: Titan's period and Saturn's mass",
                "Point a small telescope at Saturn from Mississauga, log Titan's real position night "
              + "after night through the clouds, fit its period by least squares, and weigh Saturn.",
                new Color(120, 230, 170), s, this::tick);
    }

    static String fmt0(double x) { return String.format("%.0f", x); }
}
