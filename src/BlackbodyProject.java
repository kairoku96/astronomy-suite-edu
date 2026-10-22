import java.awt.*;
import java.awt.geom.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lesson: Project 1, Blackbody Radiation, as a coding lesson about the Sun. It follows the AST221
 * Python project (Gwen Irvine, AST399 ROP), but every task is about the solar system and is written
 * by the student in real Python ({@link CodeCell}, {@link PythonKernel}): light time to the Sun
 * from the sim's live distance, Planck's law for the Sun, a sunspot, the Sun's peak by argmax and
 * Wien's law, Wien across the planets, B_ν against B_λ, and the sunlight reaching Earth and every
 * planet today. Checks grade each cell, and the scene draws the student's own numbers.
 */
final class BlackbodyProject extends LessonKit {

    BlackbodyProject(LessonStudio.Host host) { super(host); }

    static final String[] PLANETS = BlackbodyLesson.PLANETS;
    static final double T_SPOT = 3900;
    /** The total solar irradiance measured at 1 AU (W/m², Kopp & Lean 2011). */
    static final double TSI = 1361;

    int zone;                                   // 0 = inner planets, 1 = outer

    // ------------------------------------------------------------------ the live values handed to Python as `sim`

    Map<String, Object> sim() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", host.dateString());
        m.put("earth_sun_au", len(pos(host.state("Earth"))) / AU);
        Map<String, Object> d = new LinkedHashMap<>(), t = new LinkedHashMap<>();
        t.put("Sun", T_SUN);
        for (int i = 0; i < PLANETS.length; i++) {
            d.put(PLANETS[i], len(pos(host.state(PLANETS[i]))) / AU);
            t.put(PLANETS[i], BlackbodyLesson.T_MEAS[i]);
        }
        m.put("planet_au", d);
        m.put("temperatures", t);
        m.put("T_sun", T_SUN);
        return m;
    }

    /** The starter with each blank, in order, filled by an answer. */
    static String solve(String starter, String... answers) {
        String s = starter;
        for (String a : answers) {
            int i = s.indexOf(CodeCell.BLANK);
            if (i < 0) throw new IllegalArgumentException("more answers than blanks");
            s = s.substring(0, i) + a + s.substring(i + CodeCell.BLANK.length());
        }
        if (s.contains(CodeCell.BLANK)) throw new IllegalArgumentException("a blank was left unanswered");
        return s;
    }

    CodeCell cell(String id, String starter, String check, String... answers) {
        return new CodeCell(id, starter, solve(starter, answers), check, this::sim);
    }

    // ------------------------------------------------------------------ the cells

    static final String LIGHT = """
            # Already imported for you, as in the notebook:
            #   import numpy as np
            #   from astropy import units as u
            #   from astropy.constants import c, h, k_B, b_wien
            #   from astropy.constants import sigma_sb, R_sun
            # sim: a dictionary of live values from the simulation.

            d = sim["earth_sun_au"] * u.AU    # Earth-Sun distance now
            print("On", sim["date"], "Earth is", d, "from the Sun")
            print("That is", d.to(u.km))

            # How long does sunlight take to reach Earth today?
            light_time = ____
            print("Light time:", light_time.to(u.min))
            """;
    static final String LIGHT_CHECK = """
            d = sim["earth_sun_au"] * u.AU
            lt = quantity(need("light_time", "fill in its blank"), u.s, "light_time")
            want = (d / const.c).to(u.s)
            report.data["minutes"] = lt.to(u.min).value
            report.data["au"] = sim["earth_sun_au"]
            if abs((lt / want).decompose().value - 1) < 1e-6:
                report.ok(f"Sunlight takes {lt.to(u.min).value:.3f} minutes to reach Earth today ({d.value:.5f} AU).")
            else:
                report.bad(f"light_time is {lt.to(u.min).value:.3f} minutes, but light crosses {d.value:.5f} AU in "
                           f"{want.to(u.min).value:.3f} minutes. Time = distance / speed.")
            """;

    static final String PLANCK = """
            def planck_lambda(temperature, wavelengths):
                \"\"\"Planck's law: intensity B_lambda in W / (m2 nm sr).
                temperature: Quantity in K; wavelengths: Quantity in nm\"\"\"
                # Fill in Planck's law, using h, c, k_B and np.exp:
                B_lambda = ____
                B_lambda = B_lambda.to(u.W / u.m**2 / u.nm)
                return B_lambda / u.sr     # intensity is per steradian

            T_sun = 5772 * u.K
            print("At 500 nm:", planck_lambda(T_sun, 500 * u.nm))
            """;
    static final String PLANCK_CHECK = """
            f = need("planck_lambda", "run the cell that defines it")
            waves = np.linspace(100, 3000, 400) * u.nm
            B = quantity(f(5772 * u.K, waves), "W / (m2 nm sr)", "planck_lambda(5772 * u.K, waves)")
            want = planck_ref(5772 * u.K, waves)
            r = (B / want).decompose().value
            report.data["nm"] = waves.value
            report.data["B"] = B.value
            report.data["ref"] = want.value
            x = (const.h * const.c / (waves * const.k_B * 5772 * u.K)).decompose().value
            if np.allclose(r, 1, rtol=1e-6):
                report.ok(f"planck_lambda matches Planck's law. The Sun's surface gives {B.value.max():.4g} W m-2 nm-1 sr-1 at the peak.")
            elif np.allclose(r, np.e * (1 - np.exp(-x)), rtol=1e-4):
                report.bad("Close! Your curve is e = 2.7 times too high: the - 1 slipped inside np.exp(...). "
                           "It belongs outside: 1 / (np.exp(...) - 1).")
            elif np.allclose(r, r[0], rtol=1e-4):
                report.bad(f"Right shape, wrong height: your values are {r[0]:.4g} times Planck's. Check the 2 h c**2 in front.")
            else:
                report.bad("Your curve doesn't have Planck's shape. Compare with the formula: wavelength to the 5th power "
                           "underneath, and h c / (wavelength k_B T) in the exponent.")
            """;

    static final String SPOT = """
            waves = np.linspace(100, 3000, 500) * u.nm
            B_photosphere = planck_lambda(5772 * u.K, waves)

            # A sunspot's dark centre is about 3900 K:
            B_sunspot = ____

            # At 500 nm (green), the spot's brightness as a fraction
            # of the photosphere's:
            ratio_500 = ____
            print("At 500 nm:", ratio_500)

            # Over all wavelengths together (the total goes as T**4):
            ratio_total = ____
            print("In total:", ratio_total)
            """;
    static final String SPOT_CHECK = """
            waves = quantity(need("waves"), u.nm, "waves")
            spot = quantity(need("B_sunspot", "fill in its blank"), "W / (m2 nm sr)", "B_sunspot")
            want = planck_ref(3900 * u.K, waves)
            report.data["nm"] = waves.value
            report.data["phot"] = planck_ref(5772 * u.K, waves).value
            report.data["spot"] = spot.value
            if spot.shape == want.shape and np.allclose((spot / want).decompose().value, 1, rtol=1e-6):
                report.ok("B_sunspot is a 3900 K blackbody.")
            else:
                report.bad("B_sunspot should be planck_lambda at 3900 K, on the same wavelengths.")
            r500 = number(need("ratio_500", "fill in its blank"), "ratio_500")
            want500 = (planck_ref(3900 * u.K, 500 * u.nm) / planck_ref(5772 * u.K, 500 * u.nm)).decompose().value
            report.data["r500"] = r500
            if abs(r500 / want500 - 1) < 0.03:
                report.ok(f"At 500 nm the spot gives {r500:.3f} of the photosphere's light, about 1/{1 / r500:.0f}.")
            else:
                report.bad(f"ratio_500 is {r500:.4g}. Divide the spot's B at 500 nm by the photosphere's B at 500 nm.")
            rt = number(need("ratio_total", "fill in its blank"), "ratio_total")
            report.data["rt"] = rt
            if abs(rt / (3900 / 5772) ** 4 - 1) < 0.01:
                report.ok(f"In total it gives {rt:.3f}: (3900 / 5772)**4, the Stefan-Boltzmann law.")
            else:
                report.bad(f"ratio_total is {rt:.4g}. The total over all wavelengths goes as T**4.")
            """;

    static final String PEAK = """
            waves = np.linspace(100, 3000, 1000) * u.nm
            B_sun = planck_lambda(5772 * u.K, waves)

            # The index of the largest value in B_sun:
            i_peak = ____
            peak_wavelength = waves[i_peak]
            print("argmax peak:", peak_wavelength)

            # Wien's law, b_wien / T:
            wien_peak = ____
            print("Wien's law: ", wien_peak.to(u.nm))
            """;
    static final String PEAK_CHECK = """
            waves = quantity(need("waves"), u.nm, "waves")
            B = quantity(need("B_sun"), "W / (m2 nm sr)", "B_sun")
            k = int(np.argmax(B.value))
            report.data["nm"] = waves.value
            report.data["B"] = B.value
            report.data["peak"] = waves[k].value
            i = need("i_peak", "fill in its blank")
            if np.ndim(i) != 0:
                raise Fail("i_peak should be one index (a whole number), not an array.")
            if k == 0 or k == len(waves) - 1:
                report.bad("The largest value is at the very end of your wavelength grid, so it isn't a peak: widen the grid.")
            elif int(i) == k:
                report.ok(f"argmax finds the Sun's peak at {waves[k].value:.2f} nm.")
            else:
                report.bad(f"i_peak is {i}, but the largest value of B_sun is at index {k}. np.argmax(B_sun) finds it.")
            w = quantity(need("wien_peak", "fill in its blank"), u.nm, "wien_peak")
            want = (const.b_wien / (5772 * u.K)).to(u.nm)
            report.data["wien"] = w.value
            if abs((w / want).decompose().value - 1) < 1e-6:
                step = (waves[1] - waves[0]).value
                report.ok(f"Wien's law gives {w.value:.2f} nm. They differ by {abs(waves[k].value - w.value):.2f} nm: "
                          f"argmax can only land on a grid point, and yours are {step:.2f} nm apart.")
            else:
                report.bad(f"wien_peak is {w:.2f}. Wien's law is b_wien / T, with T = 5772 K.")
            """;

    static final String WIEN_CELL = """
            # sim["temperatures"]: measured temperatures (K)
            peaks = {}
            for name, T in sim["temperatures"].items():
                # Wien's law, in micrometres (u.um):
                peaks[name] = ____
                print(f"{name:8s} {T:5.0f} K  peak {peaks[name]:.2f}")
            """;
    static final String WIEN_CHECK = """
            peaks = need("peaks")
            if not isinstance(peaks, dict):
                raise Fail("peaks should be a dictionary: name -> wavelength.")
            names, temps, um, wrong = [], [], [], []
            for name, T in sim["temperatures"].items():
                if name not in peaks:
                    raise Fail(f"peaks has no entry for {name}: fill in the blank inside the loop.")
                lam = quantity(peaks[name], u.um, f"peaks['{name}']")
                if abs((lam / (const.b_wien / (T * u.K))).decompose().value - 1) > 1e-4:
                    wrong.append(name)
                names.append(name)
                temps.append(T)
                um.append(lam.value)
            report.data.update(names=names, temps=temps, um=um)
            if wrong:
                report.bad("These don't follow Wien's law, b_wien / T: " + ", ".join(wrong))
            else:
                report.ok(f"The Sun peaks at {um[0]:.2f} um, in visible light. The planets peak at "
                          f"{min(um[1:]):.1f} to {max(um[1:]):.0f} um, all in the infrared.")
            """;

    static final String NU = """
            def planck_nu(temperature, frequencies):
                \"\"\"Planck's law per unit frequency, in W / (m2 Hz sr).\"\"\"
                # Fill in, using h, c, k_B and np.exp:
                B_nu = ____
                B_nu = B_nu.to(u.W / u.m**2 / u.Hz)
                return B_nu / u.sr

            waves = np.linspace(100, 3000, 1000) * u.nm
            freqs = (c / waves).to(u.Hz)   # the same grid, as frequencies
            B_lam = planck_lambda(5772 * u.K, waves)
            B_nu = planck_nu(5772 * u.K, freqs)
            lam_peak = waves[np.argmax(B_lam)]

            # The frequency where B_nu is largest:
            nu_peak = ____
            print("B_lambda peaks at", lam_peak)
            print("B_nu peaks at", nu_peak)
            print("  as a wavelength:", (c / nu_peak).to(u.nm))
            """;
    static final String NU_CHECK = """
            f = need("planck_nu", "run the cell that defines it")
            freqs = quantity(need("freqs"), u.Hz, "freqs")
            Bn = quantity(f(5772 * u.K, freqs), "W / (m2 Hz sr)", "planck_nu(5772 * u.K, freqs)")
            want = planck_nu_ref(5772 * u.K, freqs)
            nm = (const.c / freqs).to(u.nm)
            Bl = planck_ref(5772 * u.K, nm)
            lam_peak = nm[int(np.argmax(Bl.value))]
            report.data.update(nm=nm.value, Blam=Bl.value, nu=freqs.value, Bnu=Bn.value, lam_peak=lam_peak.value)
            if np.allclose((Bn / want).decompose().value, 1, rtol=1e-6):
                report.ok("planck_nu matches Planck's law per unit frequency.")
            else:
                report.bad("planck_nu doesn't match: B_nu = (2 h nu**3 / c**2) / (np.exp(h nu / (k_B T)) - 1).")
            nu = quantity(need("nu_peak", "fill in its blank"), u.Hz, "nu_peak")
            if np.ndim(nu) != 0:
                raise Fail("nu_peak should be one frequency, not an array.")
            want_nu = freqs[int(np.argmax(want.value))]
            report.data.update(nu_peak=nu.value, c_over_nu=(const.c / nu).to(u.nm).value)
            if abs((nu / want_nu).decompose().value - 1) < 1e-9:
                report.ok(f"B_nu peaks at {nu.value:.4g} Hz. As a wavelength that is c / nu = "
                          f"{(const.c / nu).to(u.nm).value:.0f} nm, not {lam_peak.value:.0f} nm.")
            else:
                report.bad(f"nu_peak is {nu:.4g}. It should be the frequency where B_nu is largest: freqs[np.argmax(B_nu)].")
            """;

    static final String FLUX = """
            waves = np.linspace(100, 10000, 5000) * u.nm
            d = sim["earth_sun_au"] * u.AU     # live from the sim

            # Intensity at the Sun's surface -> flux at Earth:
            #  integrate over outgoing directions: a factor of pi sr,
            #  then spread from R_sun out to d: (R_sun / d)**2
            F_earth = ____
            # A flux has no steradians; this fails if any are left:
            F_earth = F_earth.to(u.W / u.m**2 / u.nm)

            solar_constant = np.trapezoid(F_earth, waves)
            solar_constant = solar_constant.to(u.W / u.m**2)
            print("Sunlight at Earth on", sim["date"], ":", solar_constant)
            """;
    static final String FLUX_CHECK = """
            waves = quantity(need("waves"), u.nm, "waves")
            F = need("F_earth", "fill in its blank")
            if isinstance(F, u.Quantity) and any(b == u.sr for b in F.unit.bases):
                raise Fail(f"F_earth is still per steradian ({F.unit.to_string()}). Turning intensity into flux means "
                           "integrating over the outgoing directions, a factor of pi sr: multiply by np.pi * u.sr.")
            F = quantity(F, "W / (m2 nm)", "F_earth")
            d = sim["earth_sun_au"] * u.AU
            want = (np.pi * u.sr * planck_ref(5772 * u.K, waves) * (const.R_sun / d) ** 2).to(u.W / u.m ** 2 / u.nm)
            r = (F / want).decompose().value if F.shape == want.shape else None
            step = max(1, len(waves) // 600)
            report.data.update(nm=waves.value[::step], F=F.value[::step] if F.shape == want.shape else [], au=sim["earth_sun_au"])
            if r is not None and np.allclose(r, 1, rtol=1e-6):
                report.ok("F_earth is the Sun's spectrum as it arrives at Earth.")
            elif r is not None and np.allclose(r, 1 / np.pi, rtol=1e-6):
                report.bad("F_earth is pi times too small: integrating the intensity over directions gives a factor of pi sr.")
            elif r is not None and np.allclose(r, r[0], rtol=1e-6):
                report.bad(f"Right shape, but {r[0]:.4g} times too big. Check (R_sun / d)**2: the light spreads over a sphere.")
            else:
                report.bad("F_earth should be planck_lambda(5772 K, waves) * pi sr * (R_sun / d)**2, on the same wavelengths.")
            S = quantity(need("solar_constant"), u.W / u.m ** 2, "solar_constant")
            total = (const.sigma_sb * (5772 * u.K) ** 4 * (const.R_sun / d) ** 2).to(u.W / u.m ** 2)
            report.data.update(S=S.value, total=total.value)
            if abs(S.value / np.trapezoid(want, waves).to(u.W / u.m ** 2).value - 1) < 1e-6:
                report.ok(f"Today Earth gets {S.value:,.0f} W/m2 between {waves[0].value:.0f} and {waves[-1].value:,.0f} nm; "
                          f"all wavelengths together, sigma T**4 (R/d)**2 = {total.value:,.0f} W/m2.")
            else:
                report.bad(f"solar_constant is {S:.1f}; it should be the integral of F_earth over waves.")
            """;

    static final String PLANETS_FLUX = """
            # sim["planet_au"]: each planet's distance from the Sun now
            sunlight = {}
            for name, d_au in sim["planet_au"].items():
                d = d_au * u.AU
                # sigma_sb T**4 (R_sun / d)**2, in W / m2:
                sunlight[name] = ____
                print(f"{name:8s} {d_au:6.3f} AU  {sunlight[name]:9.2f}")
            """;
    static final String PLANETS_CHECK = """
            sun = need("sunlight")
            if not isinstance(sun, dict):
                raise Fail("sunlight should be a dictionary: planet -> flux.")
            names, dist, vals, wrong = [], [], [], []
            for name, d_au in sim["planet_au"].items():
                if name not in sun:
                    raise Fail(f"sunlight has no entry for {name}: fill in the blank inside the loop.")
                S = quantity(sun[name], u.W / u.m ** 2, f"sunlight['{name}']")
                want = (const.sigma_sb * (5772 * u.K) ** 4 * (const.R_sun / (d_au * u.AU)) ** 2).to(u.W / u.m ** 2)
                if abs((S / want).decompose().value - 1) > 1e-3:
                    wrong.append(name)
                names.append(name)
                dist.append(d_au)
                vals.append(S.value)
            report.data.update(names=names, au=dist, W=vals)
            if wrong:
                report.bad("These don't match sigma_sb T**4 (R_sun / d)**2: " + ", ".join(wrong))
            else:
                report.ok(f"From {vals[0]:,.0f} W/m2 at Mercury to {vals[-1]:.2f} W/m2 at Neptune, "
                          f"{vals[0] / vals[-1]:,.0f} times less: the inverse-square law.")
            """;

    final CodeCell light = cell("task 0", LIGHT, LIGHT_CHECK, "d / c");
    final CodeCell planck = cell("task 1", PLANCK, PLANCK_CHECK,
            "(2 * h * c**2 / wavelengths**5) / (np.exp(h * c / (wavelengths * k_B * temperature)) - 1)");
    final CodeCell spot = cell("task 1b", SPOT, SPOT_CHECK,
            "planck_lambda(3900 * u.K, waves)",
            "planck_lambda(3900 * u.K, 500 * u.nm) / planck_lambda(5772 * u.K, 500 * u.nm)",
            "(3900 / 5772)**4");
    final CodeCell peak = cell("task 2", PEAK, PEAK_CHECK, "np.argmax(B_sun)", "b_wien / (5772 * u.K)");
    final CodeCell wien = cell("task 2b", WIEN_CELL, WIEN_CHECK, "(b_wien / (T * u.K)).to(u.um)");
    final CodeCell nu = cell("task 3", NU, NU_CHECK,
            "(2 * h * frequencies**3 / c**2) / (np.exp(h * frequencies / (k_B * temperature)) - 1)",
            "freqs[np.argmax(B_nu)]");
    final CodeCell flux = cell("task 4", FLUX, FLUX_CHECK, "planck_lambda(5772 * u.K, waves) * np.pi * u.sr * (R_sun / d)**2");
    final CodeCell planets = cell("task 4b", PLANETS_FLUX, PLANETS_CHECK,
            "(sigma_sb * (5772 * u.K)**4 * (R_sun / d)**2).to(u.W / u.m**2)");

    /** Every cell, in lesson order (for tests). */
    List<CodeCell> cells() { return List.of(light, planck, spot, peak, wien, nu, flux, planets); }

    // ------------------------------------------------------------------ chart helpers

    static double niceStep(double span, int n) {
        double raw = span / n, p = Math.pow(10, Math.floor(Math.log10(raw))), m = raw / p;
        return (m < 1.5 ? 1 : m < 3.5 ? 2 : m < 7.5 ? 5 : 10) * p;
    }
    static double[] niceTicks(double lo, double hi, int n) {
        double st = niceStep(hi - lo, n);
        return ticks(Math.ceil(lo / st - 1e-9) * st, hi, st);
    }
    /** Drop a tick at the right end, where its label would be clipped. */
    static double[] clear(double[] t, double lo, double hi) {
        return java.util.Arrays.stream(t).filter(x -> x < hi - 0.03 * (hi - lo)).toArray();
    }
    /** Tick labels for values in units of 10^e. */
    static String[] scaled(double[] t, int e) {
        String[] s = new String[t.length];
        double step = t.length > 1 ? (t[1] - t[0]) / Math.pow(10, e) : 1;
        String fmt = step < 0.1 ? "%.2f" : step < 1 ? "%.1f" : "%.0f";
        for (int i = 0; i < t.length; i++) s[i] = String.format(fmt, t[i] / Math.pow(10, e));
        return s;
    }
    static int exp10(double x) { return (int) Math.floor(Math.log10(x)); }
    static double max(double[] a) { double m = 0; for (double x : a) if (x > m) m = x; return m; }
    /** Room above a curve's peak for the axis title and labels. */
    static final double HEADROOM = 1.3;

    static void visibleBand(Graphics2D g, Plot p) {
        for (int nm = 380; nm < 750; nm += 5) {
            if (nm + 5 < p.x0 || nm > p.x1) continue;
            g.setColor(alpha(Color.getHSBColor((float) (0.75 * (750 - nm) / 370.0), 0.9f, 0.9f), 50));
            double x0 = Math.max(p.plotLeft(), p.px(nm)), x1 = Math.min(p.plotRight(), p.px(nm + 5) + 0.5);
            if (x1 > x0) g.fill(new Rectangle2D.Double(x0, p.plotTop(), x1 - x0, p.plotBottom() - p.plotTop()));
        }
    }
    static Rectangle[] split(Rectangle b) {
        int h = (b.height - 10) / 2;
        return new Rectangle[]{new Rectangle(b.x, b.y, b.width, h), new Rectangle(b.x, b.y + h + 10, b.width, h)};
    }
    /** Linear axes over [lo, hi] nm up to ymax, the y axis labelled in units of 10^e. */
    static Plot nmAxes(Graphics2D g, Rectangle b, double lo, double hi, double ymax, String yName) {
        Plot p = new Plot(b, lo, hi, false, 0, ymax, false);
        visibleBand(g, p);
        int e = exp10(ymax);
        double[] xt = clear(niceTicks(lo, hi, 5), lo, hi), yt = niceTicks(0, ymax, 4);
        p.axes(g, "λ (nm)", yName.replace("#", "10" + LessonStudio.sup(e)), xt, labels(xt, "%.0f"), yt, scaled(yt, e));
        return p;
    }
    /** "Run your code" in an inset that has nothing of the student's to draw yet. */
    static void waiting(Graphics2D g, Rectangle b, CodeCell c) {
        String msg = c.running ? "Running your code…"
                : c.result == null ? "Run your code: its result is drawn here."
                : c.result.unavailable() ? "Python isn't available (see the panel)."
                : c.result.error() != null ? "Your code stopped with an error (see the panel)."
                : "Not quite yet: see the checks in the panel.";
        insetText(g, b, DIM, msg);
    }

    // ------------------------------------------------------------------ insets (drawn from the student's results)

    void planckInset(Graphics2D g, Rectangle b) {
        PythonKernel.Result r = planck.result;
        double[] nm = r == null ? null : r.arr("nm"), mine = r == null ? null : r.arr("B"), ref = r == null ? null : r.arr("ref");
        if (nm == null || mine == null || ref == null) { waiting(g, b, planck); return; }
        double ymax = HEADROOM * Math.max(max(ref), Math.min(max(mine), 4 * max(ref)));
        Plot p = nmAxes(g, b, 100, 3000, ymax, "Bλ (# W m⁻² nm⁻¹ sr⁻¹)");
        p.curve(g, nm, ref, alpha(GREEN, 170), 1.4f);
        p.curve(g, nm, mine, planck.passed() ? SUN_C : BAD, 2.4f);
        double pk = WIEN / T_SUN * 1e9;
        p.label(g, 1600, 0.75 * max(ref) * HEADROOM / 1.3, planck.passed() ? "your planck_lambda = Planck's law" : "yours (red), Planck's (green)",
                planck.passed() ? SUN_C : BAD, 0, 0);
        if (planck.passed()) p.vline(g, pk, alpha(Color.WHITE, 120));
    }

    void spotInset(Graphics2D g, Rectangle b) {
        PythonKernel.Result r = spot.result;
        double[] nm = r == null ? null : r.arr("nm"), ph = r == null ? null : r.arr("phot"), sp = r == null ? null : r.arr("spot");
        if (nm == null || ph == null || sp == null || sp.length != nm.length) { waiting(g, b, spot); return; }
        Plot p = nmAxes(g, b, nm[0], nm[nm.length - 1], HEADROOM * max(ph), "Bλ (# W m⁻² nm⁻¹ sr⁻¹)");
        p.curve(g, nm, ph, SUN_C, 2.2f);
        p.curve(g, nm, sp, ORANGE, 2.2f);
        p.vline(g, 500, alpha(GREEN, 170));
        p.label(g, 1300, 0.6 * max(ph), "photosphere, 5772 K", SUN_C, 0, 0);
        p.label(g, 1300, 0.6 * max(ph), "sunspot, 3900 K", ORANGE, 0, 14);
    }

    void peakInset(Graphics2D g, Rectangle b) {
        PythonKernel.Result r = peak.result;
        double[] nm = r == null ? null : r.arr("nm"), B = r == null ? null : r.arr("B");
        if (nm == null || B == null || nm.length < 2) { waiting(g, b, peak); return; }
        Plot p = nmAxes(g, b, nm[0], nm[nm.length - 1], HEADROOM * max(B), "Bλ (# W m⁻² nm⁻¹ sr⁻¹)");
        p.curve(g, nm, B, SUN_C, 2f);
        double pk = r.num("peak"), w = r.num("wien");
        if (!Double.isNaN(w) && w > nm[0] && w < nm[nm.length - 1]) {
            p.vline(g, w, alpha(GREEN, 200));
            p.label(g, w, 0.35 * max(B), String.format("Wien: %.1f nm", w), GREEN, 6, 0);
        }
        if (!Double.isNaN(pk)) {
            int k = 0;
            for (int i = 1; i < nm.length; i++) if (Math.abs(nm[i] - pk) < Math.abs(nm[k] - pk)) k = i;
            p.dot(g, nm[k], B[k], 4, ORANGE);
            p.label(g, nm[k], B[k], String.format("argmax: %.1f nm", pk), ORANGE, 7, -5);
        }
    }

    void wienInset(Graphics2D g, Rectangle b) {
        PythonKernel.Result r = wien.result;
        double[] T = r == null ? null : r.arr("temps"), um = r == null ? null : r.arr("um");
        if (T == null || um == null || T.length != um.length || T.length == 0) { waiting(g, b, wien); return; }
        Plot p = new Plot(b, 30, 10000, true, 0.2, 200, true);
        p.axes(g, "T (K)", "λmax (µm)", new double[]{30, 100, 1000}, new String[]{"30", "100", "1000"},
                new double[]{1, 10, 100}, new String[]{"1", "10", "100"});
        double[] ts = new double[60], wl = new double[60];
        for (int i = 0; i < 60; i++) { ts[i] = 30 * Math.pow(10000 / 30.0, i / 59.0); wl[i] = WIEN / ts[i] * 1e6; }
        p.curve(g, ts, wl, alpha(GREEN, 170), 1.4f);
        // The visible band, for scale.
        g.setColor(alpha(Color.WHITE, 30));
        g.fill(new Rectangle2D.Double(p.plotLeft(), p.py(0.75), p.plotRight() - p.plotLeft(), p.py(0.38) - p.py(0.75)));
        p.label(g, 30, 0.5, "visible", DIM, 4, 4);
        List<?> names = r.data().get("names") instanceof List<?> l ? l : List.of();
        for (int i = 0; i < T.length; i++) {
            p.dot(g, T[i], um[i], 3.6, i == 0 ? SUN_C : ACCENT);
            if (i < names.size() && (i == 0 || i == 3 || i == 5 || i == 8))
                p.label(g, T[i], um[i], String.valueOf(names.get(i)), i == 0 ? SUN_C : ACCENT, 6, -4);
        }
    }

    void nuInset(Graphics2D g, Rectangle b) {
        PythonKernel.Result r = nu.result;
        double[] nm = r == null ? null : r.arr("nm"), bl = r == null ? null : r.arr("Blam"),
                 hz = r == null ? null : r.arr("nu"), bn = r == null ? null : r.arr("Bnu");
        if (nm == null || bl == null || hz == null || bn == null || nm.length < 2) { waiting(g, b, nu); return; }
        Rectangle[] rr = split(b);
        double lo = Math.min(nm[0], nm[nm.length - 1]), hi = Math.max(nm[0], nm[nm.length - 1]);
        Plot p = nmAxes(g, rr[0], lo, hi, HEADROOM * max(bl), "Bλ (# W m⁻² nm⁻¹ sr⁻¹)");
        p.curve(g, nm, bl, ACCENT, 2f);
        double lp = r.num("lam_peak"), cn = r.num("c_over_nu");
        if (!Double.isNaN(lp)) { p.vline(g, lp, alpha(ACCENT, 200)); p.label(g, lp, 0.9 * max(bl), String.format("λpeak %.0f nm", lp), ACCENT, 6, 0); }
        if (!Double.isNaN(cn) && cn > lo && cn < hi) {
            p.vline(g, cn, alpha(PINK, 220));
            p.label(g, cn, 0.55 * max(bl), String.format("c / νpeak = %.0f nm", cn), PINK, 6, 0);
        }
        double[] h14 = new double[hz.length];
        for (int i = 0; i < hz.length; i++) h14[i] = hz[i] / 1e14;
        double xmax = Math.ceil(max(h14) / 5) * 5, ymax = HEADROOM * max(bn);
        int e = exp10(ymax);
        Plot q = new Plot(rr[1], 0, xmax, false, 0, ymax, false);
        double[] xt = clear(ticks(0, xmax, 5), 0, xmax), yt = niceTicks(0, ymax, 4);
        q.axes(g, "ν (10¹⁴ Hz)", "Bν (10" + LessonStudio.sup(e) + " W m⁻² Hz⁻¹ sr⁻¹)", xt, labels(xt, "%.0f"), yt, scaled(yt, e));
        q.curve(g, h14, bn, PINK, 2f);
        double np = r.num("nu_peak");
        if (!Double.isNaN(np)) {
            q.vline(g, np / 1e14, alpha(PINK, 200));
            q.label(g, np / 1e14, 0.9 * max(bn), String.format("νpeak %.2f × 10¹⁴ Hz", np / 1e14), PINK, 6, 0);
        }
    }

    void fluxInset(Graphics2D g, Rectangle b) {
        PythonKernel.Result r = flux.result;
        double[] nm = r == null ? null : r.arr("nm"), F = r == null ? null : r.arr("F");
        if (nm == null || F == null || F.length != nm.length || nm.length < 2) { waiting(g, b, flux); return; }
        double hi = Math.min(3000, nm[nm.length - 1]);
        Plot p = nmAxes(g, b, nm[0], hi, HEADROOM * max(F), "Fλ (# W m⁻² nm⁻¹)");
        p.curve(g, nm, F, SUN_C, 2.2f);
        double S = r.num("S");
        if (!Double.isNaN(S)) p.label(g, 0.55 * hi, 0.75 * max(F), String.format("area = %,.0f W/m²", S), SUN_C, 0, 0);
    }

    // ------------------------------------------------------------------ the scene

    void sunOnly() { host.showOnly(null); host.setOrbits(false, false); host.setTime(DAY); }
    static double[] nearSun() { return new double[]{0, 0, 5 * R_SUN, 50, 25}; }

    /** The Sun, Earth and the line between them, labelled with the student's number. */
    void sunEarth(LessonStudio.R3 r, String label) {
        double[] e = scaleV(pos(host.state("Earth")), 1 / AU);
        r.line(v(0, 0, 0), e, alpha(SUN_C, 200), 2f, true);
        if (label != null) r.label(scaleV(e, 0.5), label, SUN_C, 13f, 0, -14);
    }

    /** Each planet labelled from a result's (names, values) lists. */
    void planetLabels(LessonStudio.R3 r, PythonKernel.Result res, String key, String fmt) {
        if (res == null || !res.passed()) return;
        double[] vals = res.arr(key);
        List<?> names = res.data().get("names") instanceof List<?> l ? l : List.of();
        if (vals == null) return;
        for (int i = 0; i < names.size() && i < vals.length; i++) {
            int k = java.util.Arrays.asList(PLANETS).indexOf(String.valueOf(names.get(i)));
            if (k < 0 || (zone == 0) != (k < 4)) continue;
            r.label(scaleV(pos(host.state(PLANETS[k])), 1 / AU), String.format(fmt, vals[i]), ORANGE, 12.5f, 0, 18);
        }
    }

    void dateButtons(LessonStudio.Controls c) {
        c.button("Go to 3 January 2027 (Earth closest to the Sun)", () -> host.goToDate(2027, 1, 3));
        c.button("Go to 5 July 2027 (Earth farthest)", () -> host.goToDate(2027, 7, 5));
    }

    // ------------------------------------------------------------------ the lesson

    LessonStudio.LessonDef build() {
        List<LessonStudio.Step> s = new ArrayList<>();

        s.add(step("Coding in the sim", """
                This lesson is **Python Project 1**, _Investigating Blackbody Radiation_ (developed by
                Gwen Irvine through the AST399 ROP), rewritten around the Sun and played out in the
                sim. You write the code: each step has a Python cell with blanks (highlighted in
                amber) to fill in. Press **Run** (or Ctrl+Enter) and the sim runs it with real
                Python, checks it, and draws your results into the scene.

                As in the notebook, numpy (np) and astropy's units (u) and constants (c, h, k_B,
                b_wien, sigma_sb, R_sun) are already imported. One thing is new: a dictionary called
                **sim**, which holds live values from the simulation, such as Earth's distance from
                the Sun right now.

                Warm up with units. Light from the Sun's surface takes time to reach us: time =
                distance / speed. Fill in the blank, run it, then use the buttons to move the clock
                to January or July and run it again. Earth's orbit isn't a circle, so the answer
                changes.
                """)
                .unit(AU)
                .cam(() -> new double[]{0, 0, 1.3 * AU, 35, 0})
                .enter(() -> {
                    host.showOnly(null, "Earth"); host.setOrbits(true, true); host.setTime(0);
                    PythonKernel.shared().warmUp();
                })
                .controls(c -> { c.code(light); dateButtons(c); })
                .scene((r, hud, t) -> {
                    PythonKernel.Result res = light.result;
                    boolean ok = light.passed();
                    sunEarth(r, ok ? String.format("light takes %.2f min (your code)", res.num("minutes")) : null);
                    double d = len(pos(host.state("Earth"))) / AU;
                    hud.title("Sunlight's trip to Earth", SUN_C);
                    hud.row("date in the sim", host.dateString());
                    hud.row("Earth–Sun distance (sim)", String.format("%.5f AU", d));
                    hud.row("your light_time", ok ? String.format("%.3f min", res.num("minutes")) : "run your code", ok ? GREEN : DIM);
                    if (ok && Math.abs(res.num("au") - d) > 1e-6) hud.row("(the clock has moved: run it again)", "", ORANGE);
                    hud.formula("time = distance / speed");
                }));

        s.add(step("Task 1: Planck's law for the Sun", """
                The Sun's surface (the {k:photosphere}) glows nearly like a {k:blackbody} at 5772 K.
                Planck's law gives its intensity at each wavelength:

                $ Bλ(T) = (2hc² / λ⁵) · 1 / (e^(hc/λkT) − 1)

                Write it as a Python function of a temperature and an array of wavelengths. The
                rest of the function is given: it converts to W m⁻² nm⁻¹ and divides by a
                steradian, since intensity is per unit solid angle.

                The check calls your function for the Sun from 100 to 3000 nm and draws your curve
                over the true one. Watch the parentheses: if the − 1 slips inside np.exp(...), the
                curve still looks smooth and believable, but it is e ≈ 2.7 times too high. A plot
                that looks right can still be wrong.
                """)
                .unit(R_SUN)
                .cam(BlackbodyProject::nearSun)
                .enter(this::sunOnly)
                .controls(c -> c.code(planck))
                .scene((r, hud, t) -> {
                    r.labelBelow(v(0, 0, 0), 1, "the Sun: 5772 K", SUN_C, 12.5f);
                    hud.title("Planck's law for the Sun", SUN_C);
                    hud.row("your planck_lambda", planck.passed() ? "matches Planck's law" : planck.result == null ? "run your code" : "not yet",
                            planck.passed() ? GREEN : DIM);
                    hud.formula("Bλ = (2hc² / λ⁵) / (exp(hc / λkT) − 1)");
                    hud.inset("Your planck_lambda at 5772 K", 360, 250, this::planckInset);
                }));

        s.add(step("Task 1b: a sunspot", """
                Use your function. A {k:sunspot} is a patch where the Sun's magnetic field holds back
                the heat from below; its dark centre is about 3900 K, against 5772 K around it.

                Compute the spot's spectrum, then compare it with the photosphere in two ways: at
                one wavelength (500 nm, green light) and over all wavelengths together, where the
                Stefan–Boltzmann law says the total goes as T⁴.

                The two answers are different. Look at the plot to see why: the spot's curve is
                not just a smaller copy of the photosphere's. It has also slid toward the red.
                """)
                .unit(R_SUN)
                .cam(BlackbodyProject::nearSun)
                .enter(this::sunOnly)
                .controls(c -> c.code(spot))
                .scene((r, hud, t) -> {
                    r.labelBelow(v(0, 0, 0), 1, "the Sun", SUN_C, 12.5f);
                    PythonKernel.Result res = spot.result;
                    hud.title("A sunspot vs the photosphere", ORANGE);
                    hud.row("at 500 nm (your ratio_500)", res != null && !Double.isNaN(res.num("r500")) ? String.format("%.3f", res.num("r500")) : "—", ORANGE);
                    hud.row("in total (your ratio_total)", res != null && !Double.isNaN(res.num("rt")) ? String.format("%.3f", res.num("rt")) : "—", ORANGE);
                    hud.formula("total ∝ T⁴");
                    hud.inset("Photosphere and sunspot", 360, 250, this::spotInset);
                }));

        s.add(step("Check: are sunspots black?", """
                In photographs of the Sun, sunspots look black.
                """)
                .unit(R_SUN)
                .cam(BlackbodyProject::nearSun)
                .enter(this::sunOnly)
                .quiz("Are they?", 1,
                      "No. At 3900 K a sunspot glows brightly; on its own it would outshine the full Moon many times "
                    + "over. But in green light it gives only about 9% as much as the 5772 K surface around it, "
                    + "so a picture exposed for the photosphere shows it as black.",
                      "Yes: sunspots are holes where no light comes out",
                      "No: they glow, but only about a tenth as brightly as the hotter surface around them",
                      "Yes: they are cooler than 0 °C",
                      "No: they are hotter than the photosphere")
                .scene((r, hud, t) -> {
                    r.labelBelow(v(0, 0, 0), 1, "the Sun", SUN_C, 12.5f);
                    hud.inset("Photosphere and sunspot", 360, 250, this::spotInset);
                }));

        s.add(step("Task 2: find the Sun's peak", """
                Where is the Sun brightest? Compute Bλ on a grid of wavelengths and let np.argmax
                find the index of the largest value; the same index in the wavelength array is the
                peak. Then compare with {k:Wien's law}:

                $ λmax = b / T,   b = 2.898 × 10⁻³ m·K

                b is b_wien in astropy. Try changing the grid: fewer points (argmax can only return
                a grid point, so it gets less precise), or a grid that stops before the peak (then
                argmax returns the end of the array, which isn't a peak at all).
                """)
                .unit(R_SUN)
                .cam(BlackbodyProject::nearSun)
                .enter(this::sunOnly)
                .controls(c -> c.code(peak))
                .scene((r, hud, t) -> {
                    r.labelBelow(v(0, 0, 0), 1, "the Sun: 5772 K", SUN_C, 12.5f);
                    PythonKernel.Result res = peak.result;
                    hud.title("The Sun's peak", SUN_C);
                    hud.row("argmax (your grid)", res != null && !Double.isNaN(res.num("peak")) ? String.format("%.2f nm", res.num("peak")) : "—", ORANGE);
                    hud.row("Wien's law (your wien_peak)", res != null && !Double.isNaN(res.num("wien")) ? String.format("%.2f nm", res.num("wien")) : "—", GREEN);
                    hud.formula("λmax = b / T");
                    hud.inset("The Sun's spectrum and its peak", 360, 250, this::peakInset);
                }));

        s.add(step("Task 2b: Wien's law across the solar system", """
                Everything warm glows, planets included. Use a **for loop** over the measured
                temperatures in sim["temperatures"] (the Sun's surface, the rocky planets' surfaces,
                and the giant planets' effective temperatures), and store each one's Wien peak in a
                dictionary, in micrometres.

                Your numbers appear beside the planets. The Sun peaks in visible light; every planet
                peaks far in the infrared, at 4 to 50 µm. Venus's 737 K surface would glow at 4 µm,
                but its thick clouds hide it: what escapes to space comes from the cloud tops, at
                about 230 K.
                """)
                .unit(AU)
                .cam(() -> zone == 0 ? new double[]{0, 0, 1.7 * AU, 35, 0} : new double[]{0, 0, 31 * AU, 40, 0})
                .enter(() -> { host.showOnly(null, PLANETS); host.setOrbits(true, true); host.setTime(5 * DAY); })
                .controls(c -> {
                    c.code(wien);
                    c.choices("Show", new String[]{"Inner planets", "Outer planets"}, () -> zone, k -> zone = k);
                })
                .scene((r, hud, t) -> {
                    planetLabels(r, wien.result, "um", "%.1f µm");
                    hud.title("Where each body glows brightest", ACCENT);
                    PythonKernel.Result res = wien.result;
                    double[] um = res == null || !res.passed() ? null : res.arr("um");
                    List<?> names = res == null ? List.of() : res.data().get("names") instanceof List<?> l ? l : List.of();
                    if (um == null) hud.row("your peaks", "run your code", DIM);
                    else for (int i = 0; i < um.length && i < names.size(); i++) hud.row(String.valueOf(names.get(i)), String.format("%.2f µm", um[i]), i == 0 ? SUN_C : INK);
                    hud.formula("λmax = b / T");
                    hud.inset("Wien's law: your points", 300, 230, this::wienInset);
                }));

        s.add(step("Check: seeing Jupiter", """
                Your loop says Jupiter's own glow peaks near 23 µm, far beyond what eyes can see.
                """)
                .unit(AU)
                .cam(() -> new double[]{0, 0, 6 * AU, 35, 0})
                .enter(() -> { host.showOnly(null, "Earth", "Jupiter"); host.setOrbits(true, true); host.setTime(5 * DAY); })
                .quiz("Then why can we see Jupiter in the night sky?", 2,
                      "Jupiter reflects sunlight: about a third of the visible light that hits it bounces back. "
                    + "Its own thermal glow is real (infrared telescopes see it), but it is invisible to us.",
                      "Its glow peaks in the infrared but is bright enough in visible light too",
                      "Our eyes adapt to the infrared at night",
                      "It reflects sunlight, which peaks in the visible",
                      "Its moons light it up")
                .scene((r, hud, t) -> hud.inset("Wien's law: your points", 300, 230, this::wienInset)));

        s.add(step("Check: make a prediction", """
                Bλ is the Sun's brightness per unit **wavelength**. Bν is per unit **frequency**. They
                describe the same light, linked by ν = c/λ, so |dλ/dν| = λ²/c and

                $ Bν = Bλ λ² / c

                Next you'll write Bν and find its peak with argmax, on frequencies made from the
                same wavelength grid.
                """)
                .unit(R_SUN)
                .cam(BlackbodyProject::nearSun)
                .enter(this::sunOnly)
                .quiz("Will Bν peak at the same place, so that c / νpeak = λpeak (502 nm)?", 1,
                      "The factor λ²/c gives long wavelengths more weight, so Bν peaks at a lower frequency: "
                    + "for the Sun, 3.39 × 10¹⁴ Hz, which is c / ν = 884 nm, in the near infrared. Where a "
                    + "spectrum \"peaks\" depends on how you plot it.",
                      "Yes: ν = c/λ, so the peaks must match",
                      "No: c / νpeak is a longer wavelength than 502 nm",
                      "No: c / νpeak is a shorter wavelength than 502 nm")
                .scene((r, hud, t) -> r.labelBelow(v(0, 0, 0), 1, "the Sun: 5772 K", SUN_C, 12.5f)));

        s.add(step("Task 3: Bλ vs Bν for the Sun", """
                Write planck_nu, Planck's law per unit frequency:

                $ Bν(T) = (2hν³ / c²) · 1 / (e^(hν/kT) − 1)

                then find where it peaks. Was your prediction right? The upper plot is Bλ against
                wavelength; the lower one is your Bν against frequency. The pink line on the upper
                plot is c / νpeak, the Bν peak expressed as a wavelength.

                Neither peak is wrong. They answer different questions: in which nanometre-wide
                slice, or in which hertz-wide slice, does the Sun give the most power?
                """)
                .unit(R_SUN)
                .cam(BlackbodyProject::nearSun)
                .enter(this::sunOnly)
                .controls(c -> c.code(nu))
                .scene((r, hud, t) -> {
                    r.labelBelow(v(0, 0, 0), 1, "the Sun: 5772 K", SUN_C, 12.5f);
                    PythonKernel.Result res = nu.result;
                    hud.title("Two peaks of one Sun", ACCENT);
                    hud.row("λpeak (argmax of Bλ)", res != null && !Double.isNaN(res.num("lam_peak")) ? String.format("%.1f nm", res.num("lam_peak")) : "—", ACCENT);
                    hud.row("your νpeak", res != null && !Double.isNaN(res.num("nu_peak")) ? sci(res.num("nu_peak"), 3) + " Hz" : "—", PINK);
                    hud.row("c / νpeak", res != null && !Double.isNaN(res.num("c_over_nu")) ? String.format("%.1f nm", res.num("c_over_nu")) : "—", PINK);
                    hud.formula("Bν = Bλ λ² / c");
                    hud.inset("Bλ (top) and your Bν (bottom)", 360, 380, this::nuInset);
                }));

        s.add(step("Task 4: sunlight at Earth", """
                Bλ is an {k:intensity}: power per square metre per steradian **at the Sun's surface**.
                What reaches Earth is a {k:flux}. Two steps get you there:

                $ Fλ = π Bλ(T) (R(Sun) / d)²

                Integrating the intensity over all the directions it leaves the surface gives a
                factor of π sr. Spreading that light from the Sun's radius out to Earth's distance
                dilutes it by (R(Sun)/d)². d comes live from the sim.

                Then add up Fλ over wavelength with np.trapezoid to get the {k:solar constant}, the
                sunlight on each square metre facing the Sun. Satellites measure 1361 W/m² at 1 AU.
                Try January and July: Earth is 3% closer in January.
                """)
                .unit(AU)
                .cam(() -> new double[]{0, 0, 1.3 * AU, 35, 0})
                .enter(() -> { host.showOnly(null, "Earth"); host.setOrbits(true, true); host.setTime(0); })
                .controls(c -> { c.code(flux); dateButtons(c); })
                .scene((r, hud, t) -> {
                    PythonKernel.Result res = flux.result;
                    boolean ok = flux.passed();
                    sunEarth(r, ok ? String.format("%,.0f W/m² (your code)", res.num("S")) : null);
                    double d = len(pos(host.state("Earth"))) / AU;
                    hud.title("Sunlight at Earth", SUN_C);
                    hud.row("date / distance (sim)", host.dateString() + "   " + String.format("%.4f AU", d));
                    hud.row("your solar_constant", ok ? String.format("%,.1f W/m²", res.num("S")) : "run your code", ok ? GREEN : DIM);
                    if (ok) hud.row("all wavelengths: σT⁴ (R(Sun)/d)²", String.format("%,.1f W/m²", res.num("total")));
                    hud.row("measured, at 1 AU", String.format("%,.0f W/m²", TSI), ACCENT);
                    if (ok && Math.abs(res.num("au") - d) > 1e-6) hud.row("(the clock has moved: run it again)", "", ORANGE);
                    hud.formula("Fλ = π Bλ (R(Sun) / d)²");
                    hud.inset("Your Fλ at Earth", 360, 250, this::fluxInset);
                }));

        s.add(step("Check: January vs July", """
                Earth is 0.983 AU from the Sun in early January and 1.017 AU in early July.
                """)
                .unit(AU)
                .cam(() -> new double[]{0, 0, 1.3 * AU, 35, 0})
                .enter(() -> { host.showOnly(null, "Earth"); host.setOrbits(true, true); host.setTime(0); })
                .quiz("How much stronger is sunlight at Earth in January?", 1,
                      "Flux goes as 1/d², so the ratio is (1.017 / 0.983)² = 1.07: about 7% more, roughly "
                    + "1408 against 1317 W/m². It isn't why we have seasons: the northern winter comes in "
                    + "January anyway, because of Earth's tilt.",
                      "About 3.5%, the same as the change in distance",
                      "About 7%, since flux goes as 1/d²",
                      "About 14%, since flux goes as 1/d⁴",
                      "No difference: the solar constant is constant")
                .scene((r, hud, t) -> sunEarth(r, null)));

        s.add(step("Task 4b: sunlight at every planet", """
                Last, a loop over the planets. For each one, take its distance from the Sun right now
                (sim["planet_au"]) and compute the sunlight it receives, using the Stefan–Boltzmann
                law for the whole spectrum at once:

                $ F = σT⁴ (R(Sun) / d)²

                Your values appear beside the planets. Neptune, 30 times farther than Earth, gets
                900 times less: 1.5 W/m², about what a 100-watt incandescent bulb gives at 2.3
                metres.
                """)
                .unit(AU)
                .cam(() -> zone == 0 ? new double[]{0, 0, 1.7 * AU, 35, 0} : new double[]{0, 0, 31 * AU, 40, 0})
                .enter(() -> { host.showOnly(null, PLANETS); host.setOrbits(true, true); host.setTime(5 * DAY); })
                .controls(c -> {
                    c.code(planets);
                    c.choices("Show", new String[]{"Inner planets", "Outer planets"}, () -> zone, k -> zone = k);
                })
                .scene((r, hud, t) -> {
                    planetLabels(r, planets.result, "W", "%,.1f W/m²");
                    PythonKernel.Result res = planets.result;
                    double[] W = res == null || !res.passed() ? null : res.arr("W"), au = res == null ? null : res.arr("au");
                    hud.title("Sunlight at each planet", SUN_C);
                    if (W == null || au == null) hud.row("your sunlight", "run your code", DIM);
                    else for (int i = 0; i < W.length && i < PLANETS.length; i++)
                        hud.row(PLANETS[i] + String.format("  (%.2f AU)", au[i]), String.format("%,.1f W/m²", W[i]), INK);
                    hud.formula("F = σT⁴ (R(Sun) / d)²");
                }));

        return new LessonStudio.LessonDef("Intermediate", "Project 1: Blackbody Radiation",
                "Code it in Python: Planck, Wien and sunlight, from the Sun to Neptune",
                "Write real Python inside the sim: time sunlight's trip to Earth, code Planck's law for the "
                + "Sun, see why sunspots look dark, find the Sun's peak with argmax, run Wien's law across the "
                + "planets, compare Bλ with Bν, and compute the sunlight reaching every planet today.",
                new Color(255, 170, 70), s, _ -> { });
    }
}
