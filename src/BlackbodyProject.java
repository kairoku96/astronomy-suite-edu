import java.awt.*;
import java.awt.geom.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lesson: Project 1, Investigating Blackbody Radiation (the AST221 Python project by Gwen Irvine,
 * AST399 ROP), worked in the sim. Each notebook task is a step: Planck's law as a function (and the
 * notebook's misplaced-parenthesis bug), Wien's law measured with argmax on a wavelength grid,
 * B_λ against B_ν, eight real SDSS spectra against scaled blackbodies, and the observed peaks
 * against Wien's law. The scale factor between a spectrum and its blackbody, π (R/d)², gives the
 * stars' distance, which can be checked: all eight lie in the open cluster M67.
 */
final class BlackbodyProject extends LessonKit {

    BlackbodyProject(LessonStudio.Host host) { super(host); }

    /** Wien's law for B_ν: ν(max) = 5.879 × 10¹⁰ Hz/K × T. */
    static final double WIEN_NU = 5.878925757e10;
    /** M67's distance (pc) from its Gaia parallax (≈ 1.16 mas). */
    static final double M67_PC = 860;
    static final int[] GRID_N = {200, 1000, 5000};
    /** One colour per spectrum when all eight share a plot. */
    static final Color[] SERIES = {ACCENT, ORANGE, GREEN, PINK, TEAL, VIOLET, SUN_C, APO_C};
    static final Color OBS_C = new Color(176, 196, 232);
    /** Strong absorption features in the SDSS range: vacuum wavelengths (nm), as SDSS records them. */
    static final double[] LINE_NM = {395.2, 410.3, 430.8, 434.2, 486.3, 517.6, 589.5, 656.5, 854.4};
    static final String[] LINE_ID = {"Ca H,K", "Hδ", "G", "Hγ", "Hβ", "Mg b", "Na D", "Hα", "Ca II"};

    // Task 1
    double temp = 5000, temp2 = 3000;
    boolean bug;
    // Task 2
    double lamLo = 100, lamHi = 3000;
    int gridK = 1;
    final List<double[]> wienLog = new ArrayList<>();      // {T (K), argmax λ (nm)}
    // Tasks 4 and 5
    int star;
    boolean allStars, lines = true, smooth;
    double radius = 1, distPc = 500;

    // ------------------------------------------------------------------ the notebook's functions

    /** Planck's B_λ in the notebook's units, W m⁻² nm⁻¹ sr⁻¹. */
    static double bLam(double nm, double T) { return planck(nm * 1e-9, T) * 1e-9; }
    /** The notebook's first try, 1 / np.exp(hc/λkT − 1): the −1 slipped inside the exponential. */
    static double bLamBug(double nm, double T) {
        double l = nm * 1e-9, x = H_PL * C / (l * K_B * T);
        return x > 700 ? 0 : 2 * H_PL * C * C / Math.pow(l, 5) / Math.exp(x - 1) * 1e-9;
    }
    /** Planck's B_ν, W m⁻² Hz⁻¹ sr⁻¹. */
    static double bNu(double hz, double T) {
        double x = H_PL * hz / (K_B * T);
        return x > 700 ? 0 : 2 * H_PL * hz * hz * hz / (C * C) / Math.expm1(x);
    }
    /** np.linspace(a, b, n). */
    static double[] linspace(double a, double b, int n) {
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = a + (b - a) * i / (n - 1);
        return x;
    }
    /** np.argmax: the first index of the largest value. */
    static int argmax(double[] y) {
        int k = 0;
        for (int i = 1; i < y.length; i++) if (y[i] > y[k]) k = i;
        return k;
    }
    static double[] bLamOn(double[] nm, double T) {
        double[] b = new double[nm.length];
        for (int i = 0; i < nm.length; i++) b[i] = bLam(nm[i], T);
        return b;
    }
    /** Task 2: the grid wavelength (nm) where B_λ is largest, as argmax finds it. */
    static double peakOnGrid(double T, double lo, double hi, int n) {
        double[] nm = linspace(lo, hi, n);
        return nm[argmax(bLamOn(nm, T))];
    }
    /** Task 3: the same grid turned into frequencies ν = c/λ; returns c/ν (nm) where B_ν is largest. */
    static double nuPeakOnGrid(double T, double lo, double hi, int n) {
        double[] nm = linspace(lo, hi, n), bn = new double[n];
        for (int i = 0; i < n; i++) bn[i] = bNu(C / (nm[i] * 1e-9), T);
        return nm[argmax(bn)];
    }
    /** ∫ B_λ dλ (W m⁻² sr⁻¹) from 10 nm to 1 mm, trapezoids in ln λ; over all λ it is σT⁴/π. */
    static double area(double T) {
        int n = 4000;
        double a = Math.log(10), b = Math.log(1e6), s = 0, h = (b - a) / n;
        for (int i = 0; i <= n; i++) {
            double nm = Math.exp(a + i * h), f = bLam(nm, T) * nm;
            s += (i == 0 || i == n) ? f / 2 : f;
        }
        return s * h;
    }
    static double median(double[] a) {
        double[] s = a.clone();
        java.util.Arrays.sort(s);
        int n = s.length;
        return n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2;
    }

    // ------------------------------------------------------------------ the real spectra

    static List<SdssSpectra.Spectrum> spectra() { return SdssSpectra.all(); }
    SdssSpectra.Spectrum spec() {
        List<SdssSpectra.Spectrum> all = spectra();
        return all.isEmpty() ? null : all.get(Math.min(star, all.size() - 1));
    }
    String[] starLabels() {
        List<SdssSpectra.Spectrum> all = spectra();
        String[] s = new String[all.size()];
        for (int i = 0; i < s.length; i++) s[i] = all.get(i).name();
        return s;
    }

    /** The notebook's scaling, median(F) / median(B) (sr): π (R/d)², times any light the fibre missed. */
    static double medianScale(SdssSpectra.Spectrum s) { return median(s.flux()) / median(bLamOn(s.nm(), s.teff())); }

    /** The peak wavelength (nm): the brightest pixel, as argmax finds it, or the brightest 5-nm average. */
    static double peakOf(SdssSpectra.Spectrum s, boolean smooth) {
        double[] nm = s.nm(), f = s.flux();
        if (!smooth) return nm[argmax(f)];
        double best = -1, at = nm[0], sum = 0;
        int lo = 0;
        for (int hi = 0; hi < nm.length; hi++) {           // a running 5-nm boxcar, kept clear of the ends
            sum += f[hi];
            while (nm[hi] - nm[lo] > 5) sum -= f[lo++];
            if (nm[lo] - nm[0] < 2.5 || nm[nm.length - 1] - nm[hi] < 2.5) continue;
            double m = sum / (hi - lo + 1);
            if (m > best) { best = m; at = (nm[lo] + nm[hi]) / 2; }
        }
        return at;
    }

    private static final Map<String, Double> FIT_T = new HashMap<>();
    /** The blackbody that best fits the whole spectrum (free scale, weighted by ivar): its temperature (K). */
    static synchronized double fitT(SdssSpectra.Spectrum s) {
        return FIT_T.computeIfAbsent(s.file(), _ -> {
            double best = Double.MAX_VALUE, bestT = 0;
            for (int pass = 0; pass < 2; pass++) {
                double lo = pass == 0 ? 3000 : bestT - 50, hi = pass == 0 ? 10000 : bestT + 50, step = pass == 0 ? 25 : 1;
                for (double T = lo; T <= hi; T += step) {
                    double sfb = 0, sbb = 0, sff = 0;
                    for (int i = 0; i < s.nm().length; i++) {
                        double w = s.ivar()[i], b = bLam(s.nm()[i], T), f = s.flux()[i];
                        sfb += w * f * b; sbb += w * b * b; sff += w * f * f;
                    }
                    double chi = sff - sfb * sfb / sbb;           // Σw(F − aB)² at the best scale a
                    if (chi < best) { best = chi; bestT = T; }
                }
            }
            return bestT;
        });
    }

    // ------------------------------------------------------------------ the 3-D stage

    void sunOnly() { host.showOnly(null); host.setOrbits(false, false); host.setTime(DAY); }

    /** Centre of the star beside the Sun (solar radii), clear of the Sun at any size. */
    double starX(double rad) { return 1 + 0.9 + 1.25 * rad; }
    /** Frame the Sun and the star beside it. */
    double[] pairView(double rad) {
        double x = starX(rad), left = -1.2, right = x + 1.15 * rad;
        return new double[]{(left + right) / 2 * R_SUN, 0, Math.max(2.4, 0.62 * (right - left)) * R_SUN, 8, 0};
    }
    /** The real Sun and a star of temperature T and radius rad (solar radii) beside it. */
    void pair(LessonStudio.R3 r, double T, double rad, String label) {
        double[] c0 = v(starX(rad), 0, 0);
        Color col = starColor(T);
        r.glow(c0, rad * 1.8, alpha(col, 90), 0.5);
        int n = rad > 0.8 ? 30 : 16;
        r.sphere(c0, rad, n, 2 * n, (nx, ny, nz, mu) -> LessonStudio.scale(col, 0.55 + 0.45 * mu));
        r.labelBelow(c0, rad, label, col, 12.5f);
        r.labelBelow(v(0, 0, 0), 1, "the Sun: 5772 K", SUN_C, 12.5f);
    }

    // ------------------------------------------------------------------ chart helpers

    /** A round tick step that gives about n ticks over span. */
    static double niceStep(double span, int n) {
        double raw = span / n, p = Math.pow(10, Math.floor(Math.log10(raw))), m = raw / p;
        return (m < 1.5 ? 1 : m < 3.5 ? 2 : m < 7.5 ? 5 : 10) * p;
    }
    static double[] niceTicks(double lo, double hi, int n) {
        double st = niceStep(hi - lo, n);
        return ticks(Math.ceil(lo / st - 1e-9) * st, hi, st);
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

    /** The visible band, as a rainbow behind the curves. */
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
    /** Linear B_λ axes over [lo, hi] nm up to ymax, labelled in units of 10^e. */
    static Plot bLamAxes(Graphics2D g, Rectangle b, double lo, double hi, double ymax) {
        Plot p = new Plot(b, lo, hi, false, 0, ymax, false);
        visibleBand(g, p);
        int e = exp10(ymax);
        double[] xt = clear(niceTicks(lo, hi, 5), lo, hi), yt = niceTicks(0, ymax, 4);
        p.axes(g, "λ (nm)", "Bλ (10" + LessonStudio.sup(e) + " W m⁻² nm⁻¹ sr⁻¹)", xt, labels(xt, "%.0f"), yt, scaled(yt, e));
        return p;
    }
    /** Drop a tick at the right end, where its label would be clipped. */
    static double[] clear(double[] t, double lo, double hi) {
        return java.util.Arrays.stream(t).filter(x -> x < hi - 0.03 * (hi - lo)).toArray();
    }
    /** Room above a curve's peak for the axis title and labels. */
    static final double HEADROOM = 1.3;

    // ------------------------------------------------------------------ insets

    /** Task 1: what the student's function plots, the right line or the notebook's buggy one. */
    void functionInset(Graphics2D g, Rectangle b) {
        double peak = WIEN / temp * 1e9, hi = Math.max(3000, Math.ceil(5 * peak / 500) * 500);
        double[] nm = linspace(100, hi, 400), ok = bLamOn(nm, temp), bad = new double[nm.length];
        for (int i = 0; i < nm.length; i++) bad[i] = bLamBug(nm[i], temp);
        double ymax = HEADROOM * (bug ? bad[argmax(bad)] : ok[argmax(ok)]);
        Plot p = bLamAxes(g, b, 100, hi, ymax);
        if (bug) {
            p.curve(g, nm, ok, alpha(GREEN, 150), 1.4f);
            p.curve(g, nm, bad, BAD, 2.4f);
            int k = argmax(bad);
            p.label(g, nm[k], bad[k], "your line", BAD, 6, -4);
            p.label(g, peak, bLam(peak, temp), "Planck", GREEN, 6, -4);
        } else {
            p.curve(g, nm, ok, starColor(temp), 2.4f);
            p.vline(g, peak, alpha(Color.WHITE, 130));
            p.label(g, peak, bLam(peak, temp), String.format("peak ≈ %.0f nm", peak), Color.WHITE, 6, -4);
        }
    }

    /** Task 1 questions: two blackbodies on one plot. */
    void twoTempInset(Graphics2D g, Rectangle b) {
        double hot = Math.max(temp, temp2), cool = Math.min(temp, temp2);
        double hi = Math.max(3000, Math.ceil(4 * WIEN / cool * 1e9 / 500) * 500);
        double[] nm = linspace(100, hi, 400);
        double ymax = HEADROOM * bLam(WIEN / hot * 1e9, hot);
        Plot p = bLamAxes(g, b, 100, hi, ymax);
        double[][] ts = {{temp, 0}, {temp2, 1}};
        for (double[] t : ts) {
            Color c = t[1] == 0 ? ORANGE : ACCENT;
            p.curve(g, nm, bLamOn(nm, t[0]), c, 2.2f);
            double pk = WIEN / t[0] * 1e9;
            if (pk < hi) { p.vline(g, pk, alpha(c, 150)); p.label(g, pk, bLam(pk, t[0]), String.format("%,.0f K", t[0]), c, 6, -4); }
        }
    }

    /** Task 2: the grid, its argmax, and the log of measured peaks against Wien's law. */
    void wienInset(Graphics2D g, Rectangle b) {
        Rectangle[] r = split(b);
        int n = GRID_N[gridK];
        double[] nm = linspace(lamLo, lamHi, n), bl = bLamOn(nm, temp);
        int k = argmax(bl);
        Plot p = bLamAxes(g, r[0], lamLo, lamHi, HEADROOM * bl[k]);
        p.curve(g, nm, bl, starColor(temp), 1.8f);
        if (n <= 200) for (int i = 0; i < n; i++) p.dot(g, nm[i], bl[i], 1.4, alpha(INK, 160));
        double wien = WIEN / temp * 1e9;
        if (wien > lamLo && wien < lamHi) p.vline(g, wien, alpha(GREEN, 170));
        p.dot(g, nm[k], bl[k], 4, ORANGE);
        p.label(g, nm[k], bl[k], String.format("argmax: %.1f nm", nm[k]), ORANGE, 7, -5);

        Plot q = new Plot(r[1], 2000, 12000, false, 0, 1600, false);
        double[] xt = {2000, 4000, 6000, 8000, 10000}, yt = {0, 400, 800, 1200, 1600};
        q.axes(g, "T (K)", "peak λ (nm)", xt, labels(xt, "%.0f"), yt, labels(yt, "%.0f"));
        double[] ts = linspace(2000, 12000, 200), wl = new double[ts.length];
        for (int i = 0; i < ts.length; i++) wl[i] = WIEN / ts[i] * 1e9;
        q.curve(g, ts, wl, GREEN, 1.8f);
        q.label(g, 7000, WIEN / 7000 * 1e9, "Wien: b / T", GREEN, 4, -8);
        for (double[] o : wienLog) if (o[1] <= 1600) q.dot(g, o[0], o[1], 3.4, ACCENT);
        if (nm[k] <= 1600) q.dot(g, temp, nm[k], 4.2, ORANGE);
    }

    /** Task 3: B_λ against λ and B_ν against ν, each with its own peak. */
    void nuInset(Graphics2D g, Rectangle b) {
        Rectangle[] r = split(b);
        double[] nm = linspace(100, 3000, 1000), bl = bLamOn(nm, temp), nu = new double[nm.length], bn = new double[nm.length];
        for (int i = 0; i < nm.length; i++) { nu[i] = C / (nm[i] * 1e-9); bn[i] = bNu(nu[i], temp); }
        int kl = argmax(bl), kn = argmax(bn);
        double cOverNu = C / nu[kn] * 1e9;
        Plot p = bLamAxes(g, r[0], 100, 3000, HEADROOM * bl[kl]);
        p.curve(g, nm, bl, ACCENT, 2f);
        p.vline(g, nm[kl], alpha(ACCENT, 200));
        p.label(g, nm[kl], bl[kl], String.format("λpeak %.0f nm", nm[kl]), ACCENT, 6, -4);
        p.vline(g, cOverNu, alpha(PINK, 220));
        p.label(g, cOverNu, 0.55 * bl[kl], String.format("c / νpeak = %.0f nm", cOverNu), PINK, 6, 0);

        double[] nu14 = new double[nu.length];
        for (int i = 0; i < nu.length; i++) nu14[i] = nu[i] / 1e14;
        int e = exp10(HEADROOM * bn[kn]);
        Plot q = new Plot(r[1], 0, 30, false, 0, HEADROOM * bn[kn], false);
        double[] xt = {0, 5, 10, 15, 20, 25}, yt = niceTicks(0, HEADROOM * bn[kn], 4);
        q.axes(g, "ν (10¹⁴ Hz)", "Bν (10" + LessonStudio.sup(e) + " W m⁻² Hz⁻¹ sr⁻¹)", xt, labels(xt, "%.0f"), yt, scaled(yt, e));
        q.curve(g, nu14, bn, PINK, 2f);
        q.vline(g, nu14[kn], alpha(PINK, 200));
        q.label(g, nu14[kn], bn[kn], String.format("νpeak %.2f × 10¹⁴ Hz", nu14[kn]), PINK, 6, -4);
    }

    /**
     * Task 4: observed spectra with their blackbodies, scaled the notebook's way (median to median)
     * or, with {@code physical}, as π B (R/d)² for the chosen radius and distance.
     */
    void spectrumInset(Graphics2D g, Rectangle b, boolean physical) {
        List<SdssSpectra.Spectrum> all = spectra();
        if (all.isEmpty()) { insetText(g, b, BAD, "No spectra found in assets/spectra."); return; }
        List<Integer> show = new ArrayList<>();
        if (allStars && !physical) for (int i = 0; i < all.size(); i++) show.add(i); else show.add(Math.min(star, all.size() - 1));
        // The axes follow the observed spectra; a model that is far too bright runs off the top.
        double ymax = 0;
        double[][] models = new double[all.size()][];
        for (int i : show) {
            SdssSpectra.Spectrum s = all.get(i);
            double[] m = bLamOn(s.nm(), s.teff());
            double k = physical ? Math.PI * sq(radius * R_SUN / (distPc * PC)) : medianScale(s);
            for (int j = 0; j < m.length; j++) m[j] *= k;
            models[i] = m;
            for (int j = 0; j < m.length; j++) if (s.ivar()[j] > 0) ymax = Math.max(ymax, s.flux()[j]);
        }
        ymax *= HEADROOM;
        Plot p = new Plot(b, 380, 925, false, 0, ymax, false);
        visibleBand(g, p);
        int e = exp10(ymax);
        double[] xt = {400, 500, 600, 700, 800, 900}, yt = niceTicks(0, ymax, 4);
        p.axes(g, "λ (nm)", "Fλ (10" + LessonStudio.sup(e) + " W m⁻² nm⁻¹)", xt, labels(xt, "%.0f"), yt, scaled(yt, e));
        if (lines) {
            g.setFont(LessonStudio.uiFont(Font.PLAIN, 9.5f));
            for (int i = 0; i < LINE_NM.length; i++) {
                g.setColor(alpha(Color.WHITE, 55));
                g.setStroke(new BasicStroke(1f));
                g.draw(new Line2D.Double(p.px(LINE_NM[i]), p.plotTop() + 25 + 10 * (i % 3), p.px(LINE_NM[i]), p.plotBottom()));
                g.setColor(DIM);
                String id = LINE_ID[i];
                float x = (float) (p.px(LINE_NM[i]) - g.getFontMetrics().stringWidth(id) / 2.0);
                g.drawString(id, Math.max((float) p.plotLeft(), Math.min(x, (float) p.plotRight() - g.getFontMetrics().stringWidth(id))),
                        (float) (p.plotTop() + 22 + 10 * (i % 3)));
            }
        }
        for (int i : show) {
            SdssSpectra.Spectrum s = all.get(i);
            Color c = show.size() == 1 ? OBS_C : SERIES[i % SERIES.length];
            p.curve(g, s.nm(), s.flux(), alpha(c, show.size() == 1 ? 230 : 150), 0.9f);
        }
        for (int i : show) {
            SdssSpectra.Spectrum s = all.get(i);
            p.curve(g, s.nm(), models[i], show.size() == 1 ? ORANGE : SERIES[i % SERIES.length], 2.2f);
        }
    }

    /** Task 5: each spectrum's peak against its temperature, over Wien's law. */
    void observedWienInset(Graphics2D g, Rectangle b) {
        List<SdssSpectra.Spectrum> all = spectra();
        Plot p = new Plot(b, 4800, 6200, false, 380, 680, false);
        double[] xt = {4800, 5200, 5600, 6000}, yt = {400, 450, 500, 550, 600, 650};
        p.axes(g, "Teff (K)", "peak λ (nm)", xt, labels(xt, "%.0f"), yt, labels(yt, "%.0f"));
        double[] ts = linspace(4800, 6200, 100), wl = new double[ts.length];
        for (int i = 0; i < ts.length; i++) wl[i] = WIEN / ts[i] * 1e9;
        p.curve(g, ts, wl, GREEN, 2f);
        p.label(g, 6000, WIEN / 6000 * 1e9, "Wien's law", GREEN, -10, -10);
        for (int i = 0; i < all.size(); i++) {
            SdssSpectra.Spectrum s = all.get(i);
            double pk = peakOf(s, smooth), w = WIEN / s.teff() * 1e9;
            g.setColor(alpha(SERIES[i % SERIES.length], 150));
            g.setStroke(new BasicStroke(1f));
            g.draw(new Line2D.Double(p.px(s.teff()), p.py(pk), p.px(s.teff()), p.py(w)));
            p.dot(g, s.teff(), pk, 4, SERIES[i % SERIES.length]);
        }
    }

    // ------------------------------------------------------------------ the lesson

    LessonStudio.LessonDef build() {
        List<LessonStudio.Step> s = new ArrayList<>();

        s.add(step("Task 1: Planck's law as a function", """
                This lesson is **Python Project 1**, _Investigating Blackbody Radiation_ (developed by
                Gwen Irvine through the AST399 ROP), worked step by step in the sim. Where the notebook
                asks you to fill in code, the sim runs the same calculation live so you can check your
                answer against it.

                A {k:blackbody} absorbs all light that falls on it and glows with a spectrum set only by
                its temperature. Planck's law, per unit wavelength and per unit frequency:

                $ Bλ(T) = (2hc² / λ⁵) · 1 / (e^(hc/λkT) − 1)
                $ Bν(T) = (2hν³ / c²) · 1 / (e^(hν/kT) − 1)

                Task 1: write Bλ as a function of temperature and wavelength that returns
                W m⁻² nm⁻¹ sr⁻¹. The inset is what it should plot from 100 to 3000 nm.

                **Watch the parentheses.** The notebook's first try put the −1 inside the
                exponential. Switch that version on: it still draws a smooth, believable curve, but
                about e ≈ 2.7 times too high, with its peak a few nm off. A plot that looks right can
                still be wrong, so check a number as well.
                """)
                .unit(R_SUN)
                .cam(() -> pairView(0.8))
                .enter(() -> { sunOnly(); temp = 5000; bug = false; })
                .controls(c -> {
                    c.slider("Temperature T", 2000, 12000, () -> temp, x -> temp = x, x -> String.format("%,.0f K", x));
                    c.toggle("The notebook's first try (−1 inside np.exp)", () -> bug, x -> bug = x);
                    c.button("The notebook's 5000 K", () -> temp = 5000);
                })
                .scene((r, hud, t) -> {
                    pair(r, temp, 0.8, String.format("a %,.0f K blackbody", temp));
                    double pk = WIEN / temp * 1e9;
                    hud.title("Your function at " + String.format("%,.0f K", temp), starColor(temp));
                    hud.row("units it returns", "W / (nm sr m²)");
                    hud.row("Bλ at the peak (" + String.format("%.0f nm", pk) + ")", sci(bLam(pk, temp), 3) + " W m⁻² nm⁻¹ sr⁻¹", GREEN);
                    if (bug) {
                        double[] nm = linspace(100, 20000, 20000), bad = new double[nm.length];
                        for (int i = 0; i < nm.length; i++) bad[i] = bLamBug(nm[i], temp);
                        int k = argmax(bad);
                        hud.row("the buggy line's peak (" + String.format("%.0f nm", nm[k]) + ")", sci(bad[k], 3), BAD);
                        hud.row("too high by", String.format("%.2f×", bad[k] / bLam(pk, temp)), BAD);
                        hud.formula("1 / exp(hc/λkT − 1)  ≠  1 / (exp(hc/λkT) − 1)");
                    } else {
                        hud.formula("Bλ = (2hc² / λ⁵) / (exp(hc/λkT) − 1)");
                    }
                    hud.inset("Bλ, 100 nm onward", 330, 230, this::functionInset);
                }));

        s.add(step("Task 1: two temperatures", """
                Plot a second blackbody and compare. The notebook's example: 3000 K and 6000 K.

                The hotter blackbody is brighter at **every** wavelength, and its peak sits at a shorter
                wavelength, Wien's law, λmax = b / T with b = 2.898 × 10⁻³ m·K. That is why hot stars
                look blue-white and cool ones red.

                The total area under a curve, all the intensity it emits, grows much faster than T.
                The readouts add up each area numerically; compare the ratio with (T₁ / T₂)⁴.

                Our eyes see only 380–750 nm (the rainbow band). Slide the temperatures and look
                where the peaks fall: a 3000 K star peaks in the infrared and a 12,000 K star in the
                ultraviolet. We see only the visible slice of either curve.
                """)
                .unit(R_SUN)
                .cam(() -> pairView(0.8))
                .enter(() -> { sunOnly(); temp = 6000; temp2 = 3000; bug = false; })
                .controls(c -> {
                    c.slider("Temperature T₁", 2000, 12000, () -> temp, x -> temp = x, x -> String.format("%,.0f K", x));
                    c.slider("Temperature T₂", 2000, 12000, () -> temp2, x -> temp2 = x, x -> String.format("%,.0f K", x));
                    c.button("The notebook's pair: 6000 K and 3000 K", () -> { temp = 6000; temp2 = 3000; });
                })
                .scene((r, hud, t) -> {
                    pair(r, temp, 0.8, String.format("T₁ = %,.0f K", temp));
                    double a1 = area(temp), a2 = area(temp2);
                    hud.title("Two blackbodies", ORANGE);
                    hud.row("T₁: peak", String.format("%,.0f nm", WIEN / temp * 1e9), ORANGE);
                    hud.row("T₂: peak", String.format("%,.0f nm", WIEN / temp2 * 1e9), ACCENT);
                    hud.gap();
                    hud.row("area under T₁ (10 nm to 1 mm)", sci(a1, 3) + " W m⁻² sr⁻¹");
                    hud.row("σT₁⁴ / π", sci(SIGMA * Math.pow(temp, 4) / Math.PI, 3) + " W m⁻² sr⁻¹");
                    hud.row("area ratio T₁ : T₂", String.format("%.2f", a1 / a2), GREEN);
                    hud.row("(T₁ / T₂)⁴", String.format("%.2f", Math.pow(temp / temp2, 4)), GREEN);
                    hud.formula("∫ Bλ dλ = σT⁴ / π");
                    hud.inset("T₁ (orange) and T₂ (blue)", 330, 230, this::twoTempInset);
                }));

        s.add(step("Check: fill in the blank", """
                The notebook gives you the function's shell: units are attached to the temperature
                and wavelengths, and the result is converted to W m⁻² nm⁻¹ and divided by a
                steradian. You write the one line that computes Bλ. Below, x stands for
                hc / λkT, written with the function's own variables.
                """)
                .unit(R_SUN)
                .cam(() -> pairView(0.8))
                .enter(() -> { sunOnly(); temp = 5000; bug = false; })
                .quiz("Which line belongs in the blank?", 1,
                      "B is Planck's law. A is the notebook's first try: it runs, the units work, and the curve "
                    + "looks fine, but it is e ≈ 2.7 times too high. Units can't catch a misplaced −1. C and D "
                    + "fail at once with astropy units: 2hc² is W m², so dividing by λ⁴ leaves W m⁻² and "
                    + "multiplying by λ⁵ leaves W m⁷, and neither converts to W m⁻² nm⁻¹.",
                      "2*h*c**2 / wavelengths**5 / np.exp(x - 1)",
                      "2*h*c**2 / wavelengths**5 / (np.exp(x) - 1)",
                      "2*h*c**2 / wavelengths**4 / (np.exp(x) - 1)",
                      "2*h*c**2 * wavelengths**5 / (np.exp(x) - 1)")
                .scene((r, hud, t) -> {
                    pair(r, 5000, 0.8, "a 5000 K blackbody");
                    hud.inset("Bλ at 5000 K", 330, 230, this::functionInset);
                }));

        s.add(step("Check: the area under the curve", """
                Raise a blackbody's temperature and the whole curve rises, most of all near its peak.
                """)
                .unit(R_SUN)
                .cam(() -> pairView(0.8))
                .enter(() -> { sunOnly(); temp = 6000; temp2 = 3000; })
                .quiz("How does the area under Bλ, the total intensity, grow with T?", 2,
                      "The area is σT⁴ / π: the Stefan–Boltzmann law. Doubling T from 3000 K to 6000 K "
                    + "multiplies it by 2⁴ = 16.",
                      "In proportion to T", "As T²", "As T⁴: the Stefan–Boltzmann law", "It stays the same; only the peak moves")
                .scene((r, hud, t) -> {
                    pair(r, temp, 0.8, "6000 K");
                    hud.inset("6000 K and 3000 K", 330, 230, this::twoTempInset);
                }));

        s.add(step("Check: an M-dwarf sun", """
                Suppose Earth orbited an M dwarf at **3000 K** instead of the Sun.
                """)
                .unit(R_SUN)
                .cam(() -> pairView(0.8))
                .enter(() -> { sunOnly(); temp = T_SUN; temp2 = 3000; })
                .quiz("Would the wavelengths we call visible be different?", 1,
                      "Visible means what our eyes detect, 380–750 nm; that is set by our eyes, not by the "
                    + "star. The 3000 K star peaks near 970 nm, in the infrared, so less of its light would "
                    + "fall in our visible band and the star would look orange-red. (Eyes that evolved there "
                    + "might be tuned redder, but that is biology, not the star's spectrum.)",
                      "Yes: visible would move to the infrared, where the star peaks",
                      "No: visible is set by our eyes; the star would give less visible light and look redder",
                      "No: every star peaks in the visible",
                      "Yes: visible would move to the ultraviolet")
                .scene((r, hud, t) -> {
                    pair(r, 3000, 0.8, "an M dwarf, 3000 K");
                    hud.inset("The Sun (orange) and a 3000 K star (blue)", 330, 230, this::twoTempInset);
                }));

        s.add(step("Task 2: find the peak with argmax", """
                Now measure Wien's law instead of quoting it. Compute Bλ on a grid of wavelengths and
                let np.argmax find the index of the largest value; the same index in the wavelength
                array is the peak. The orange dot is what argmax picks; the green line is Wien's law,
                λmax = b / T.

                Record peaks at several temperatures (the notebook asks for at least three more) and
                they fall on Wien's curve in the lower plot. Two things to try:

                **The grid edge.** A hot or cool star may peak outside your grid. Then argmax just
                returns the end of the array, which is not a peak at all. Adjust the minimum and
                maximum wavelengths, as the notebook's hint says.

                **The spacing.** argmax can only return a grid point, so the answer is never better
                than the spacing between points. Try 200 points and compare.
                """)
                .unit(R_SUN)
                .cam(() -> pairView(0.8))
                .enter(() -> { sunOnly(); temp = 5000; lamLo = 100; lamHi = 3000; gridK = 1; })
                .controls(c -> {
                    c.slider("Temperature T", 2000, 12000, () -> temp, x -> temp = x, x -> String.format("%,.0f K", x));
                    c.slider("Grid starts at", 50, 1000, () -> lamLo, x -> lamLo = Math.min(Math.round(x / 10) * 10, lamHi - 50), x -> String.format("%.0f nm", x));
                    c.slider("Grid ends at", 1000, 10000, () -> lamHi, x -> lamHi = Math.max(Math.round(x / 50) * 50, lamLo + 50), x -> String.format("%,.0f nm", x));
                    c.choices("Points in the grid", new String[]{"200", "1000", "5000"}, () -> gridK, k -> gridK = k);
                    c.button("Record this peak", () -> wienLog.add(new double[]{temp, peakOnGrid(temp, lamLo, lamHi, GRID_N[gridK])}));
                    c.button("Record 3000, 4000, 5000 and 6000 K on this grid", () -> {
                        for (double T : new double[]{3000, 4000, 5000, 6000})
                            wienLog.add(new double[]{T, peakOnGrid(T, lamLo, lamHi, GRID_N[gridK])});
                    });
                    c.button("Clear the log", wienLog::clear);
                })
                .scene((r, hud, t) -> {
                    pair(r, temp, 0.8, String.format("%,.0f K", temp));
                    int n = GRID_N[gridK];
                    double[] nm = linspace(lamLo, lamHi, n);
                    int k = argmax(bLamOn(nm, temp));
                    double wien = WIEN / temp * 1e9;
                    hud.title("np.argmax on your grid", ORANGE);
                    hud.row("grid", String.format("%,.0f to %,.0f nm, %d points", lamLo, lamHi, n));
                    hud.row("spacing between points", String.format("%.2f nm", (lamHi - lamLo) / (n - 1)));
                    hud.row("argmax peak", String.format("%.2f nm", nm[k]), ORANGE);
                    hud.row("Wien's law  b / T", String.format("%.2f nm", wien), GREEN);
                    if (k == 0 || k == n - 1) hud.row("the peak is off your grid", k == 0 ? "start lower" : "end higher", BAD);
                    else hud.row("difference", signed("%+.2f nm", nm[k] - wien));
                    hud.row("peaks recorded", Integer.toString(wienLog.size()));
                    hud.formula("λmax = b / T,   b = 2.898 × 10⁻³ m·K");
                    hud.inset("Your grid (top) and your log (bottom)", 340, 360, this::wienInset);
                }));

        s.add(step("Check: argmax vs Wien", """
                With 1000 points from 100 to 3000 nm, argmax finds the 5000 K peak at **578.98 nm**.
                Wien's law gives **579.55 nm**.
                """)
                .unit(R_SUN)
                .cam(() -> pairView(0.8))
                .enter(() -> { sunOnly(); temp = 5000; lamLo = 100; lamHi = 3000; gridK = 1; })
                .quiz("Why do they differ by about half a nanometre?", 1,
                      "The grid points are (3000 − 100) / 999 = 2.90 nm apart, and argmax can only return one "
                    + "of them; 578.98 nm is the grid point nearest the true peak. A finer grid closes the gap.",
                      "Wien's constant is only an approximation",
                      "argmax can only return a grid point, and they are 2.9 nm apart",
                      "Astropy's units round the result",
                      "A 5000 K blackbody does not follow Wien's law")
                .scene((r, hud, t) -> {
                    pair(r, 5000, 0.8, "5000 K");
                    hud.inset("Your grid (top) and your log (bottom)", 340, 360, this::wienInset);
                }));

        s.add(step("Task 3: make a prediction", """
                Bλ is brightness per unit **wavelength**; Bν is per unit **frequency**. The two describe
                the same light, so they are linked by a change of variables. Since ν = c/λ,
                |dλ/dν| = λ² / c, and

                $ Bν = Bλ |dλ/dν| = Bλ λ² / c

                The notebook builds a frequency grid from the wavelength grid, ν = c/λ, and takes
                argmax of each.
                """)
                .unit(R_SUN)
                .cam(() -> pairView(0.8))
                .enter(() -> { sunOnly(); temp = 5000; })
                .quiz("Will the two peaks match, so that λpeak = c / νpeak?", 1,
                      "The factor λ² / c gives long wavelengths more weight, so Bν peaks at a lower frequency. "
                    + "For 5000 K, Bλ peaks at 579 nm but Bν at 2.94 × 10¹⁴ Hz, which is c / ν = 1020 nm: "
                    + "1.76 times longer. Where a spectrum \"peaks\" depends on how you plot it.",
                      "Yes: ν = c/λ, so the peaks must match",
                      "No: c / νpeak is a longer wavelength than λpeak",
                      "No: c / νpeak is a shorter wavelength than λpeak")
                .scene((r, hud, t) -> {
                    pair(r, 5000, 0.8, "5000 K");
                    hud.inset("Bλ at 5000 K", 330, 230, this::functionInset);
                }));

        s.add(step("Task 3: Bλ vs Bν", """
                Here are both functions for the same blackbody, Bλ against wavelength above and Bν
                against frequency below, with each peak found by argmax on the notebook's grid
                (1000 points, 100–3000 nm).

                The pink line on the top plot is c / νpeak: the wavelength of the Bν peak. It is
                1.76 times longer than λpeak at every temperature, since both peaks follow a Wien
                law of their own:

                $ λmax × T = 2.898 × 10⁻³ m·K
                $ (c / νmax) × T = 5.100 × 10⁻³ m·K

                Neither one is wrong. They answer different questions: in which nanometre-wide slice,
                or in which hertz-wide slice, does the star give the most power?
                """)
                .unit(R_SUN)
                .cam(() -> pairView(0.8))
                .enter(() -> { sunOnly(); temp = 5000; })
                .controls(c -> {
                    c.slider("Temperature T", 2500, 12000, () -> temp, x -> temp = x, x -> String.format("%,.0f K", x));
                    c.button("The notebook's 5000 K", () -> temp = 5000);
                })
                .scene((r, hud, t) -> {
                    pair(r, temp, 0.8, String.format("%,.0f K", temp));
                    double lp = peakOnGrid(temp, 100, 3000, 1000), np = nuPeakOnGrid(temp, 100, 3000, 1000);
                    hud.title("Two peaks of one blackbody", ACCENT);
                    hud.row("λpeak (argmax of Bλ)", String.format("%.1f nm", lp), ACCENT);
                    hud.row("νpeak (argmax of Bν)", sci(C / (np * 1e-9), 3) + " Hz", PINK);
                    hud.row("c / νpeak", String.format("%.1f nm", np), PINK);
                    hud.row("ratio", String.format("%.2f", np / lp));
                    hud.row("Wien for ν: 5.879 × 10¹⁰ Hz/K × T", sci(WIEN_NU * temp, 3) + " Hz");
                    hud.formula("Bν = Bλ λ² / c");
                    hud.inset("Bλ (top) and Bν (bottom)", 340, 360, this::nuInset);
                }));

        s.add(step("Task 4: real stars", """
                Now real data: eight spectra from the **Sloan Digital Sky Survey**, the same files the
                notebook reads. Each one is Fλ, the flux that reached the telescope, from 382 to
                922 nm. Over each spectrum the notebook draws a blackbody at the star's temperature,
                scaled so the two have the same median.

                The shapes agree well: these stars are close to blackbodies. The differences are
                where the physics is. The narrow notches are {k:absorption lines}: atoms and ions in
                the star's cooler outer layers absorb particular wavelengths (hydrogen's Balmer lines,
                sodium, magnesium, calcium). The lines crowd together in the blue, and below 420 nm
                the cooler stars fall 10–15% short of their blackbodies.

                A star isn't one temperature either. Each wavelength escapes from a different depth,
                and where the gas is more transparent we see deeper, hotter layers. Between 450 and
                520 nm every one of these spectra bulges 5–10% above its blackbody.

                A real detail behind the file names: SDSS compared each spectrum with a library of
                bright, well-studied stars (ELODIE). Each HD number is the library star that matched
                best; the star SDSS actually observed is a faint one with no name.
                """)
                .unit(R_SUN)
                .cam(() -> pairView(0.8))
                .enter(() -> { sunOnly(); allStars = false; lines = true; })
                .controls(c -> {
                    c.choices("Spectrum", starLabels(), () -> star, k -> star = k);
                    c.toggle("All eight at once (the notebook's for loop)", () -> allStars, x -> allStars = x);
                    c.toggle("Mark the absorption lines", () -> lines, x -> lines = x);
                })
                .scene((r, hud, t) -> {
                    SdssSpectra.Spectrum sp = spec();
                    if (sp == null) { hud.title("No spectra found in assets/spectra", BAD); return; }
                    pair(r, sp.teff(), 0.8, String.format("an SDSS star matched to %s (%s), %,.0f K", sp.name(), sp.spType(), sp.teff()));
                    if (allStars) {
                        hud.title("All eight spectra", ACCENT);
                        List<SdssSpectra.Spectrum> all = spectra();
                        for (int i = 0; i < all.size(); i++)
                            hud.row(all.get(i).name() + "  (" + all.get(i).spType() + ")", String.format("%,.0f K", all.get(i).teff()), SERIES[i % SERIES.length]);
                    } else {
                        hud.title("SDSS spectrum " + sp.plate() + "-" + sp.mjd() + "-" + sp.fiber(), ACCENT);
                        hud.row("best-matching library star", sp.name() + "  (" + sp.spType() + ")");
                        hud.row("its temperature", String.format("%,.0f K", sp.teff()), ORANGE);
                        hud.row("log g / [Fe/H]", String.format("%.2f / %s", sp.logg(), signed("%+.2f", sp.feh())));
                        hud.row("scale median(F) / median(B)", sci(medianScale(sp), 3) + " sr", ACCENT);
                    }
                    hud.formula("Fλ ≈ (scale) × Bλ(T)");
                    hud.inset(allStars ? "Eight spectra and their blackbodies" : "Observed (blue) and scaled blackbody (orange)",
                            440, 300, (g, b) -> spectrumInset(g, b, false));
                }));

        s.add(step("Intensity vs flux: how far away?", """
                Why does the blackbody need scaling at all? Bλ is an {k:intensity}: power per square
                metre per steradian **at the star's surface**. A telescope measures {k:flux}.
                Integrating the intensity over the outgoing directions gives the surface flux πBλ, and
                spreading it from radius R out to distance d dilutes it by (R/d)²:

                $ Fλ = π Bλ(T) (R / d)²

                Both factors are the same at every wavelength, so they change the height, not the
                shape. The notebook's scale factor is therefore π(R/d)², and it holds the star's
                distance. Pick a radius (these are Sun-like stars) and solve for d.

                Check your answer: all eight stars lie within 13′ of the centre of **M67**, an old
                open cluster whose Gaia parallax puts it about 860 pc away. Dust along the way dims
                the light a little, which makes the distance come out slightly too large.

                R/d is also the star's angular radius: a few micro-arcseconds, millions of times too
                small for any telescope to resolve. We can't measure these stars' intensity, only
                their flux.
                """)
                .unit(R_SUN)
                .cam(() -> pairView(radius))
                .enter(() -> { sunOnly(); allStars = false; radius = 1; distPc = 300; })
                .controls(c -> {
                    c.choices("Spectrum", starLabels(), () -> star, k -> star = k);
                    c.slider("Star's radius", 0.5, 2, () -> radius, x -> radius = x, x -> String.format("%.2f solar radii", x));
                    c.slider("Distance", 2, 3.6, () -> log10(distPc), x -> distPc = Math.pow(10, x),
                            x -> String.format("%,.0f pc", Math.pow(10, x)));
                    c.button("Solve: d = R / √(scale / π)", () -> {
                        SdssSpectra.Spectrum sp = spec();
                        if (sp != null) distPc = clamp(radius * R_SUN / Math.sqrt(medianScale(sp) / Math.PI) / PC, 100, 3981);
                    });
                })
                .scene((r, hud, t) -> {
                    SdssSpectra.Spectrum sp = spec();
                    if (sp == null) { hud.title("No spectra found in assets/spectra", BAD); return; }
                    pair(r, sp.teff(), radius, String.format("%,.0f K, %.2f solar radii", sp.teff(), radius));
                    double theta = Math.sqrt(medianScale(sp) / Math.PI), dFit = radius * R_SUN / theta / PC;
                    double model = Math.PI * sq(radius * R_SUN / (distPc * PC));
                    double lo = Double.MAX_VALUE, hi = 0;
                    for (SdssSpectra.Spectrum o : spectra()) {
                        double d = radius * R_SUN / Math.sqrt(medianScale(o) / Math.PI) / PC;
                        lo = Math.min(lo, d); hi = Math.max(hi, d);
                    }
                    hud.title("Matched to " + sp.name() + ", " + String.format("%,.0f K", sp.teff()), ACCENT);
                    hud.row("your model π(R/d)²", sci(model, 3) + " sr");
                    hud.row("observed median(F) / median(B)", sci(medianScale(sp), 3) + " sr");
                    hud.row("observed ÷ your model", String.format("%.2f", medianScale(sp) / model),
                            Math.abs(Math.log(medianScale(sp) / model)) < 0.05 ? GOOD : ORANGE);
                    hud.gap();
                    hud.row("angular radius R/d", String.format("%.1f µas", theta / ARCSEC * 1e6));
                    hud.row("distance for this radius", String.format("%,.0f pc", dFit), GREEN);
                    hud.row("all eight at this radius", String.format("%,.0f to %,.0f pc", lo, hi), GREEN);
                    hud.row("M67, from Gaia", "≈ " + String.format("%.0f", M67_PC) + " pc", ACCENT);
                    hud.formula("Fλ = π Bλ(T) (R / d)²");
                    hud.inset("Observed (blue) and π B (R/d)² (orange)", 440, 300, (g, b) -> spectrumInset(g, b, true));
                }));

        s.add(step("Check: twice as far", """
                Take one of the stars and move it to twice the distance, keeping its radius and
                temperature.
                """)
                .unit(R_SUN)
                .cam(() -> pairView(1))
                .enter(() -> { sunOnly(); allStars = false; radius = 1; })
                .quiz("What happens to its observed spectrum?", 2,
                      "Fλ = πBλ(T)(R/d)²: doubling d divides the flux by 4 at every wavelength. The shape and "
                    + "the peak stay put, because temperature alone sets them. This is why the notebook can "
                    + "compare shapes after scaling by the median.",
                      "It shifts to longer wavelengths", "It halves at every wavelength",
                      "It drops to a quarter at every wavelength, same shape",
                      "Its intensity Bλ drops to a quarter")
                .scene((r, hud, t) -> {
                    SdssSpectra.Spectrum sp = spec();
                    if (sp != null) pair(r, sp.teff(), 1, String.format("%,.0f K", sp.teff()));
                }));

        s.add(step("Task 5: Wien's law from real spectra", """
                Last, measure each star's peak with argmax, as in Task 2, and plot it against the
                library temperature, with Wien's law over a grid of temperatures.

                The points follow the trend (hotter stars peak bluer) but scatter, most of them to
                the **blue** side of Wien's curve. Two reasons. The top of a blackbody is very flat,
                so a few percent of noise, or a gap between absorption lines, moves argmax by tens of
                nanometres. And the real spectra bulge above their blackbodies between 450 and
                520 nm (Task 4), which pulls the peak blueward. Smoothing over 5 nm removes the noise
                but not the bulge.

                Fitting a blackbody to **all** of each spectrum, not just its peak, gives the
                temperatures in the last column. They are steadier, but up to about 400 K cooler
                than the library values: the lines take light unevenly, most of it in the blue.
                """)
                .unit(R_SUN)
                .cam(() -> pairView(0.8))
                .enter(() -> { sunOnly(); smooth = false; })
                .controls(c -> c.toggle("Smooth each spectrum over 5 nm before argmax", () -> smooth, x -> smooth = x))
                .scene((r, hud, t) -> {
                    List<SdssSpectra.Spectrum> all = spectra();
                    if (all.isEmpty()) { hud.title("No spectra found in assets/spectra", BAD); return; }
                    pair(r, 5500, 0.8, "eight Sun-like stars in M67");
                    hud.title("Peak (" + (smooth ? "5-nm smoothed" : "argmax") + ") · Wien · blackbody fit", ACCENT);
                    for (int i = 0; i < all.size(); i++) {
                        SdssSpectra.Spectrum sp = all.get(i);
                        double pk = peakOf(sp, smooth), w = WIEN / sp.teff() * 1e9;
                        hud.row(String.format("%s  %,.0f K", sp.name(), sp.teff()),
                                String.format("%.0f nm · %.0f nm · %,.0f K", pk, w, fitT(sp)), SERIES[i % SERIES.length]);
                    }
                    hud.formula("λmax = b / T");
                    hud.inset("Measured peaks and Wien's law", 340, 260, this::observedWienInset);
                }));

        s.add(step("Check: how close?", """
                Look at the measured peaks against Wien's curve.
                """)
                .unit(R_SUN)
                .cam(() -> pairView(0.8))
                .enter(() -> { sunOnly(); smooth = false; })
                .quiz("How well do the real peaks follow Wien's law?", 1,
                      "They follow the trend, within about 15%, mostly on the short-wavelength side. Noise, "
                    + "absorption lines and an atmosphere whose temperature changes with depth all move the "
                    + "peak of a real spectrum away from an ideal blackbody's.",
                      "Exactly on the curve",
                      "The trend is right, but they scatter by up to about 15%, mostly to shorter wavelengths",
                      "They are off by about a factor of two",
                      "There is no trend with temperature at all")
                .scene((r, hud, t) -> {
                    pair(r, 5500, 0.8, "eight Sun-like stars in M67");
                    hud.inset("Measured peaks and Wien's law", 340, 260, this::observedWienInset);
                }));

        s.add(step("Check: how SDSS measured temperature", """
                Each file gives a temperature, a spectral type, a surface gravity and a metallicity,
                all labelled ELODIE.
                """)
                .unit(R_SUN)
                .cam(() -> pairView(0.8))
                .enter(() -> { sunOnly(); })
                .quiz("Did SDSS find these temperatures with Wien's law?", 1,
                      "No. The pipeline compared each whole spectrum, lines and all, with the ELODIE library of "
                    + "real stars and kept the best match; the temperature is that library star's. Line "
                    + "strengths pin the temperature far better than the flat top of the curve. Project 2 "
                    + "tries the B − V colour, and the most precise method compares lines of the same atom "
                    + "from different energy levels.",
                      "Yes: from each spectrum's peak, with Wien's law",
                      "No: it matched each whole spectrum, lines included, to a library of known stars",
                      "No: it measured each star's radius and luminosity",
                      "Yes: after smoothing the spectrum first")
                .scene((r, hud, t) -> {
                    SdssSpectra.Spectrum sp = spec();
                    if (sp != null) {
                        pair(r, sp.teff(), 0.8, sp.name());
                        hud.inset("Observed (blue) and scaled blackbody (orange)", 440, 300, (g, b) -> spectrumInset(g, b, false));
                    }
                }));

        return new LessonStudio.LessonDef("Intermediate", "Project 1: Blackbody Radiation",
                "The Python project, live: Planck, Wien, Bλ vs Bν and real SDSS spectra",
                "Write Planck's law and catch the notebook's bug, measure Wien's law with argmax, compare "
                + "Bλ with Bν, then lay blackbodies over eight real SDSS spectra and find out how far away "
                + "the stars are.",
                new Color(255, 170, 70), s, _ -> { });
    }
}
