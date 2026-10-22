import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Lesson: Telescope Power (AST221 Day 2). Collecting area and the limiting magnitude on the
 * real sky; the diffraction limit and seeing, as the image a telescope actually delivers of the
 * real Pluto–Charon pair (or the slides' binary at 10 pc); and the resolution of an
 * interferometer as big as the Earth.
 */
final class TelescopeLesson extends LessonKit {

    TelescopeLesson(LessonStudio.Host host) { super(host); }

    /** Dark-adapted pupil (mm) and the naked-eye limit under Mississauga's suburban sky. */
    static final double EYE_MM = 7, CITY_LIMIT = 5.0;
    double logD = Math.log10(7);           // aperture, log10(mm)
    int field;                             // 0 = wide field, 1 = telescope on Neptune
    int target;                            // 0 = Pluto & Charon, 1 = the slides' binary
    int band;                              // 0 = V 550 nm, 1 = K 2.2 µm
    int site = 1;                          // 0 = space, 1 = Mississauga, 2 = Mauna Kea, 3 = Mauna Kea + AO
    static final String[] SITES = {"In space", "Mississauga", "Mauna Kea", "Mauna Kea + AO"};
    static final double[] SEEING = {0, 2.0, 0.55, 0.55};    // median seeing at 500 nm (″) in each site's own air, from the slides
    /** Each site's air density relative to sea level (the standard atmosphere at 150 m and 4,205 m). */
    static final double[] SITE_RHO = {0, 0.985, 0.654, 0.654};
    double rho = SITE_RHO[1];              // air density, × sea level
    /** Keck-like AO: deformable-mirror actuator spacing (m) and its other wavefront errors (m rms: lag, noise, calibration). */
    static final double AO_PITCH = 0.5, AO_OTHER = 200e-9;
    double logB = Math.log10(25);          // interferometer baseline, log10(m)
    int wave;                              // 0 = 1 mm, 1 = 21 cm, 2 = 550 nm
    static final String[] WAVES = {"1 mm", "21 cm", "550 nm"};
    static final double[] WAVE_M = {1e-3, 0.21, 550e-9};
    double[] camGoal = {0, 0, 2e7, 60, 0};

    double apertureMm() { return Math.pow(10, logD); }
    double lambda() { return band == 0 ? 550e-9 : 2.2e-6; }

    String apertureText(double mm) {
        return mm < 1000 ? String.format("%.0f mm", mm) : String.format("%.2f m", mm / 1000);
    }

    void apertureSlider(LessonStudio.Controls c) {
        c.slider("Aperture D", Math.log10(7), 4, () -> logD, x -> logD = x,
                x -> apertureText(Math.pow(10, x)) + (Math.pow(10, x) < 8 ? "  (your eye)" : ""));
    }

    // ------------------------------------------------------------------ the telescope's image

    /** Separation (″) and position angle of the pair, and the companion's brightness relative to the primary. */
    double[] pair() {
        if (target == 1) return new double[]{0.1, 90, 1.0};
        double[] p = host.skyBody("Pluto"), c = host.skyBody("Charon");
        double dE = (c[2] - p[2]) * 15 * Math.cos(Math.toRadians(p[3])) * 3600, dN = (c[3] - p[3]) * 3600;
        return new double[]{Math.hypot(dE, dN), Math.toDegrees(Math.atan2(dE, dN)), Math.pow(10, -0.4 * (c[4] - p[4]))};
    }

    /**
     * FWHM (″) of the seeing disc at this wavelength, 0 with no air. Turbulence mixes warmer and cooler
     * air, and the refractive index goes as n − 1 ∝ density, so Cn² ∝ ρ² and the Fried length
     * r₀ ∝ (∫Cn² dh)^−3/5 ∝ ρ^−6/5 λ^6/5. The seeing ≈ 0.98 λ / r₀ then goes as ρ^6/5 λ^−1/5.
     */
    double seeing() {
        if (site == 0 || rho <= 0) return 0;
        return SEEING[site] * Math.pow(rho / SITE_RHO[site], 1.2) * Math.pow(lambda() / 500e-9, -0.2);
    }
    /** Fried length r₀ (m) at this wavelength: the size of a patch of steady air (infinite with none). */
    double r0() { return 0.98 * lambda() / (seeing() * ARCSEC); }
    /**
     * Strehl ratio, the share of the light left in the diffraction-limited core: exp(−σ²) for a wavefront
     * error σ² (Maréchal). Uncorrected σ² = 1.03 (D/r₀)^5/3 (Noll); AO leaves its fitting error
     * 0.28 (d/r₀)^5/3 for actuators d apart, plus its other errors. Maréchal holds only while S ≳ 10%
     * (σ² ≲ 2.3); past that no coherent core survives and the image is all halo, so S is 0.
     */
    double strehl() {
        if (seeing() == 0) return 1;
        double D = apertureMm() / 1000, r0 = r0();
        double var = 1.03 * Math.pow(D / r0, 5.0 / 3);
        if (site == 3)
            var = Math.min(var, 0.28 * Math.pow(Math.min(D, AO_PITCH) / r0, 5.0 / 3) + sq(2 * Math.PI * AO_OTHER / lambda()));
        return var > 2.3 ? 0 : Math.exp(-var);
    }
    String strehlText() { double S = strehl(); return S == 0 ? "under 10%" : String.format("%.0f%%", 100 * S); }
    /** λ / D in arcseconds. */
    double lambdaOverD() { return lambda() / (apertureMm() / 1000) / ARCSEC; }
    /** Rayleigh limit 1.22 λ / D in arcseconds. */
    double rayleigh() { return 1.22 * lambdaOverD(); }
    /** FWHM (″) of the seeing halo: diffraction (1.03 λ/D) and seeing added in quadrature. */
    double fwhm() { return Math.hypot(1.03 * lambdaOverD(), seeing()); }
    /** FWHM (″) of the whole image, core and halo, measured from the PSF. */
    double imageFwhm() {
        double half = psf(0) / 2, step = Math.min(lambdaOverD(), fwhm()) / 100;
        for (double th = step; th < 3 * fwhm(); th += step) if (psf(th) < half) return 2 * th;
        return fwhm();
    }
    /**
     * Resolved when the brightness between the stars dips to at most 75% of the fainter one's: the
     * Rayleigh criterion's 26% dip for two equal Airy patterns, applied to whatever image the air leaves.
     */
    boolean resolved(double sep, double ratio) {
        double ax = -0.5 * sep, bx = 0.5 * sep, lo = Double.MAX_VALUE;
        double fa = psf(0) + ratio * psf(sep), fb = psf(sep) + ratio * psf(0);
        for (int i = 1; i < 100; i++) {
            double u = ax + sep * i / 100;
            lo = Math.min(lo, psf(u - ax) + ratio * psf(bx - u));
        }
        return lo <= 0.75 * Math.min(fa, fb);
    }

    /** One point source's image at angle θ (″) from it, per unit flux: an Airy core holding the Strehl ratio, the rest in a seeing halo. */
    double psf(double th) {
        double ld = lambdaOverD(), S = strehl(), out = 0;
        if (S > 0) out += S * airy(Math.PI * th / ld) / (4 / Math.PI * ld * ld);
        if (S < 1) {
            double s = fwhm() / 2.3548;
            out += (1 - S) * Math.exp(-0.5 * th * th / (s * s)) / (2 * Math.PI * s * s);
        }
        return out;
    }

    /** Light lost to the air toward Pluto (fraction): V extinction k·X with k ∝ ρ (0.25 mag per air mass in Mississauga). */
    double airLoss() {
        double s = Math.sin(Math.toRadians(Math.max(host.skyBody("Pluto")[0], 1)));
        double X = 1.0 / (s + 0.025 * Math.exp(-11 * s));
        return 1 - Math.pow(10, -0.4 * 0.25 * rho / SITE_RHO[1] * X);
    }

    String densityText(double x) {
        if (x < 0.005) return "0  (no air)";
        String t = String.format("%.2f× sea level", x);
        // Height in the standard atmosphere with this density: ρ/ρ₀ = (1 − 2.2558×10⁻⁵ h)^4.2559.
        if (x < 0.995) return t + String.format("  (%.1f km up)", (1 - Math.pow(x, 1 / 4.2559)) / 2.2558e-5 / 1000);
        return t;
    }

    private BufferedImage img;

    /** The pair as the telescope images it, square-root stretched, with the profile through both stars. */
    void imageInset(Graphics2D g, Rectangle b) {
        double[] pr = pair();
        double sep = pr[0], pa = Math.toRadians(pr[1]), ratio = pr[2];
        double fieldArc = Math.max(4 * sep, 3 * imageFwhm());
        int N = 120;
        if (img == null) img = new BufferedImage(N, N, BufferedImage.TYPE_INT_RGB);
        double ex = Math.sin(pa), ny = Math.cos(pa);     // companion offset direction (east = left, north = up)
        double ax = -0.5 * sep, bx = 0.5 * sep;           // primary and companion along the pair axis
        double[] vals = new double[N * N];
        double max = 0;
        for (int j = 0; j < N; j++)
            for (int i = 0; i < N; i++) {
                double x = (i + 0.5 - N / 2.0) / N * fieldArc, y = (N / 2.0 - j - 0.5) / N * fieldArc;
                double u = -x * ex + y * ny, w = x * ny + y * ex;     // along / across the pair axis (east is left)
                double val = psf(Math.hypot(u - ax, w)) + ratio * psf(Math.hypot(u - bx, w));
                vals[j * N + i] = val;
                max = Math.max(max, val);
            }
        for (int k = 0; k < N * N; k++) {
            double q = Math.sqrt(vals[k] / max);
            int r = (int) (255 * Math.min(1, q * 1.05)), gg = (int) (240 * q), bb = (int) (200 * q * q);
            img.setRGB(k % N, k / N, (r << 16) | (gg << 8) | bb);
        }
        int side = Math.min(b.width, b.height - 46);
        int x0 = b.x + (b.width - side) / 2, y0 = b.y + 2;
        Object oldI = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, x0, y0, side, side, null);
        if (oldI != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldI);
        // Profile through both stars (linear intensity), drawn over the bottom of the image.
        Path2D.Double prof = new Path2D.Double();
        for (int i = 0; i <= 200; i++) {
            double u = (i / 200.0 - 0.5) * fieldArc;
            double val = (psf(Math.abs(u - ax)) + ratio * psf(Math.abs(u - bx))) / Math.max(1e-12, max);
            double X = x0 + side * i / 200.0, Y = y0 + side - 6 - val * side * 0.32;
            if (i == 0) prof.moveTo(X, Y); else prof.lineTo(X, Y);
        }
        g.setColor(new Color(120, 220, 255, 230));
        g.setStroke(new BasicStroke(1.6f));
        g.draw(prof);
        g.setFont(LessonStudio.uiFont(Font.PLAIN, 10.5f));
        g.setColor(DIM);
        double bar = niceBar(fieldArc / 3);
        double barPx = side * bar / fieldArc;
        g.setStroke(new BasicStroke(1.5f));
        g.draw(new Line2D.Double(x0 + 6, y0 + side + 12, x0 + 6 + barPx, y0 + side + 12));
        g.drawString(angle(bar), (float) (x0 + 10 + barPx), (float) (y0 + side + 16));
        g.setFont(LessonStudio.uiFont(Font.BOLD, 11.5f));
        boolean res = resolved(sep, ratio);
        g.setColor(res ? GREEN : PERI_C);
        g.drawString(res ? "resolved: two stars" : "not resolved: one blob", x0 + 6, y0 + side + 34);
        g.setColor(DIM);
        g.setFont(LessonStudio.uiFont(Font.PLAIN, 10.5f));
        String lab = "blue curve: brightness along the pair";
        g.drawString(lab, x0 + side - g.getFontMetrics().stringWidth(lab) - 4, y0 + side + 34);
    }

    static double niceBar(double x) {
        double p = Math.pow(10, Math.floor(Math.log10(x))), m = x / p;
        return (m >= 5 ? 5 : m >= 2 ? 2 : 1) * p;
    }

    // ------------------------------------------------------------------ the interferometer

    void frameEarth() { camGoal = new double[]{0, 0, 1.15e7, 35, 30}; }

    /** Two dishes a baseline B apart on the face of the real Earth turned toward the viewer. */
    void drawBaseline(LessonStudio.R3 r, double B, String label) {
        final double MM = 1e6, RE = 6.371e6;
        double[] e = scaleV(pos(host.state("Earth")), 1 / MM), eye = host.eye();
        double[] toEye = norm(sub(scaleV(eye, 1 / MM), e)), side = norm(cross(toEye, v(0, 0, 1)));
        double sb = Math.min(1, B / (2 * RE)), cb = Math.sqrt(1 - sb * sb);
        double[] a = add(e, add(scaleV(side, sb * RE / MM), scaleV(toEye, cb * RE / MM)));
        double[] b = add(e, add(scaleV(side, -sb * RE / MM), scaleV(toEye, cb * RE / MM)));
        r.line(a, b, SUN_C, 3f, false);
        r.dot(a, 6, PINK);
        r.dot(b, 6, PINK);
        r.label(lerp(a, b, 0.5), label, SUN_C, 13f, 0, -20);
    }

    /** A 1 cm pupil beside a 1 m mirror, to scale. */
    static void aperturesInset(Graphics2D g, Rectangle b) {
        double R = Math.min(b.width, b.height) / 2.0 - 22, cx = b.getCenterX(), cy = b.getCenterY() - 6;
        g.setColor(new Color(150, 200, 255, 70));
        g.fill(new Ellipse2D.Double(cx - R, cy - R, 2 * R, 2 * R));
        g.setColor(new Color(150, 200, 255));
        g.setStroke(new BasicStroke(1.5f));
        g.draw(new Ellipse2D.Double(cx - R, cy - R, 2 * R, 2 * R));
        double r = Math.max(0.8, R / 100);
        g.setColor(SUN_C);
        g.fill(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
        g.setFont(LessonStudio.uiFont(Font.PLAIN, 10.5f));
        g.setColor(DIM);
        g.drawString("1 m mirror", (float) (cx - R + 4), (float) (cy + R + 14));
        g.drawString("1 cm pupil (the dot)", (float) (cx + 6), (float) (cy - 6));
    }

    LessonStudio.LessonDef build() {
        List<LessonStudio.Step> s = new ArrayList<>();
        final double MM = 1e6;      // scene unit for the Earth step: 1000 km

        s.add(step("A bigger bucket for light", """
                A telescope's most important number is the diameter D of its main mirror (its
                {k:aperture}). The mirror collects light over its area, so it gathers

                $ (D / 7 mm)²

                times more light than your dark-adapted pupil, about 7 mm across. Each factor of 100 in light is 5
                magnitudes, so you reach

                $ Δm = 5 log₁₀(D / 7 mm)

                fainter. Here is the real sky over Mississauga, late on September 21, 2026, with the
                suburban sky limiting the naked eye to magnitude 5. **Saturn** is easy. **Neptune**
                (magnitude 7.8) is not.

                > Open up the aperture and watch Neptune appear, then switch to the telescope view to
                hunt for its moon **Triton** (magnitude 13.5). The star catalogue here stops near
                magnitude 7, so only the planets and moons show the gain.
                """)
                .sky("Neptune", () -> field == 0 ? new double[]{-6, 3, 30} : new double[]{0, 0, 0.04})
                .enter(() -> {
                    host.skyPlace(UTM, UTM_LAT, UTM_LON, TORONTO);
                    host.goToUTC(2026, 9, 22, 3, 0);
                    host.setTime(0);
                    host.skyLayers(false, false, true, true);
                    host.skyOptics(CITY_LIMIT, apertureMm(), null);
                })
                .live(() -> host.skyOptics(CITY_LIMIT, apertureMm(), null))
                .controls(c -> {
                    apertureSlider(c);
                    c.choices("View", new String[]{"Wide (30°)", "Telescope"}, () -> field, k -> field = k);
                })
                .scene((r, hud, t) -> {
                    double lim = host.skyLimit();
                    double[] np = host.skyBody("Neptune"), tr = host.skyBody("Triton"), sa = host.skyBody("Saturn"), ti = host.skyBody("Titan");
                    r.ring(host.skyEnu("Neptune"), field == 0 ? 12 : 26, np[4] < lim ? GREEN : alpha(DIM, 160), 1.8f);
                    if (field == 0) r.label(host.skyEnu("Neptune"), "Neptune", np[4] < lim ? GREEN : DIM, 12f, 0, 24);
                    else r.label(host.skyEnu("Triton"), "Triton", tr[4] < lim ? GREEN : DIM, 12f, 0, 22);
                    double gain = sq(apertureMm() / EYE_MM);
                    hud.title("Light grasp", ACCENT);
                    hud.row("aperture D", apertureText(apertureMm()));
                    hud.row("light gathered vs your eye", gain < 1e4 ? String.format("%,.0f×", gain) : sci(gain, 1) + "×", ACCENT);
                    hud.row("faintest magnitude you reach", String.format("%.1f", lim), ACCENT);
                    hud.gap();
                    hud.row(vis(sa[4], lim) + "  Saturn", String.format("mag %.1f", sa[4]), sa[4] < lim ? GREEN : DIM);
                    hud.row(vis(ti[4], lim) + "  Titan", String.format("mag %.1f", ti[4]), ti[4] < lim ? GREEN : DIM);
                    hud.row(vis(np[4], lim) + "  Neptune", String.format("mag %.1f", np[4]), np[4] < lim ? GREEN : DIM);
                    hud.row(vis(tr[4], lim) + "  Triton", String.format("mag %.1f", tr[4]), tr[4] < lim ? GREEN : DIM);
                    hud.formula("faintest magnitude = 5.0 + 5 log₁₀(D / 7 mm)");
                }));

        s.add(step("Check: a 1-second exposure", """
                From the slides. Your eye has an aperture of about **1 cm** and your brain adds up
                photons for about **1/30 s**. A camera on a **1-m telescope** takes a **1-second**
                exposure.
                """)
                .sky("Neptune", () -> new double[]{-6, 3, 30})
                .enter(() -> {
                    host.skyPlace(UTM, UTM_LAT, UTM_LON, TORONTO);
                    host.goToUTC(2026, 9, 22, 3, 0);
                    host.setTime(0);
                    host.skyLayers(false, false, true, true);
                    host.skyOptics(CITY_LIMIT, EYE_MM, null);
                })
                .quiz("How many times more light does the 1-second exposure collect?", 2,
                      "Collecting area goes as D²: (100 cm / 1 cm)² = 10⁴. The exposure is 30 times longer than "
                    + "the eye's 1/30 s, so the telescope collects 10⁴ × 30 = 3 × 10⁵ times more photons.",
                      "100×", "10⁴×", "3 × 10⁵×", "3 × 10⁶×")
                .scene((r, hud, t) -> {
                    hud.title("The two light buckets", ACCENT);
                    hud.row("your eye", "1 cm aperture,  1/30 s");
                    hud.row("the telescope", "1 m aperture,  1 s");
                    hud.inset("Apertures to scale", 220, 220, TelescopeLesson::aperturesInset);
                }));

        s.add(step("Diffraction and seeing", """
                Light is a wave, so a telescope cannot focus a star to a point. A round aperture makes
                an {k:Airy pattern}: a central blob with faint rings. Its size sets the
                {k:diffraction limit}, the closest two stars can be and still look like two
                (the Rayleigh criterion):

                $ θ ≈ 1.22 λ / D

                On the ground, turbulence in the air smears the image further. {k:Seeing} is the
                size of that smear: about 2″ in Mississauga and 0.55″ on Mauna Kea. {k:Adaptive optics}
                (AO) bends a deformable mirror to undo it, restoring the diffraction limit. It works best in
                the infrared.

                The smear comes from pockets of warmer and cooler air, which bend light by slightly
                different amounts. Air bends light in proportion to its density ρ, so the same turbulence
                in denser air blurs more. The {k:Fried length} r₀, the size of a patch of steady air,
                shrinks as ρ^(−6/5), and the seeing grows as

                $ seeing ≈ 0.98 λ / r₀  ∝  ρ^(6/5)

                A telescope smaller than r₀ hardly notices the air. One much bigger is limited by it,
                and only a small share of its light, the {k:Strehl ratio}, stays in the sharp core.

                The inset is the image of the real **Pluto and Charon** tonight (never more than 0.9″
                apart), or of the
                slides' binary star: two stars 1 AU apart at 10 pc, 0.1″ apart.

                > Change the aperture, wavelength and site. When does the blob split into two? Then thin
                the air toward 0 (space), or thicken it past sea level, and watch the image and the
                Strehl ratio.
                """)
                .sky("Pluto", () -> new double[]{0, 0, 0.03})
                .enter(() -> {
                    host.skyPlace(UTM, UTM_LAT, UTM_LON, TORONTO);
                    host.goToUTC(2026, 9, 22, 1, 0);
                    host.setTime(0);
                    host.skyLayers(false, false, false, true);
                    host.skyNorthUp(true);
                    if (logD < 2) logD = 3;
                })
                .live(() -> {
                    host.skyOptics(6.5, Math.max(apertureMm(), 1500), null);
                    host.skyAir(rho / SITE_RHO[1]);
                })
                .controls(c -> {
                    c.choices("Target", new String[]{"Pluto & Charon", "Binary at 10 pc"}, () -> target, k -> target = k);
                    apertureSlider(c);
                    c.choices("Wavelength", new String[]{"V  550 nm", "K  2.2 µm"}, () -> band, k -> band = k);
                    c.choices("Site", SITES, () -> site, k -> { site = k; rho = SITE_RHO[k]; });
                    // Space has no air: thickening it from 0 puts the telescope under Mississauga's.
                    c.slider("Air density", 0, 2, () -> site == 0 ? 0 : rho, x -> { rho = x; if (site == 0 && x > 0.005) site = 1; },
                            this::densityText);
                })
                .scene((r, hud, t) -> {
                    double[] pr = pair();
                    r.label(host.skyEnu("Pluto"), "Pluto", SUN_C, 12f, 0, 26);
                    hud.title(target == 0 ? "Pluto and Charon tonight" : "Binary: 1 AU apart at 10 pc", ACCENT);
                    hud.row("separation", angle(pr[0]), SUN_C);
                    hud.row("aperture D  /  wavelength λ", apertureText(apertureMm()) + "  /  " + (band == 0 ? "550 nm" : "2.2 µm"));
                    hud.row("diffraction limit 1.22 λ/D", angle(rayleigh()), ACCENT);
                    hud.row("air density", densityText(site == 0 ? 0 : rho));
                    hud.row("seeing at " + SITES[site], seeing() == 0 ? "none (no air)" : angle(seeing()), ACCENT);
                    if (seeing() > 0) hud.row("Fried length r₀", r0() < 1 ? String.format("%.0f cm", 100 * r0()) : String.format("%.2f m", r0()));
                    hud.row("light in the sharp core (Strehl)" + (site == 3 ? ", with AO" : ""), strehlText(), ACCENT);
                    hud.row("image size (FWHM)", angle(imageFwhm()));
                    hud.row("light lost to the air", String.format("%.0f%%", 100 * (site == 0 ? 0 : airLoss())));
                    boolean res = resolved(pr[0], pr[2]);
                    hud.row("verdict", res ? "resolved" : "not resolved", res ? GREEN : PERI_C);
                    hud.formula("θ = 1.22 λ / D");
                    hud.formula("seeing ∝ ρ^(6/5) λ^(−1/5)");
                    hud.inset("What the telescope delivers", 300, 340, this::imageInset);
                }));

        s.add(step("Check: resolving the binary", """
                The slides' question: a binary at **10 pc** with the two stars **1 AU** apart, so
                0.1″ apart. Use the image on the right to test each telescope.
                """)
                .sky("Pluto", () -> new double[]{0, 0, 0.03})
                .enter(() -> {
                    host.skyPlace(UTM, UTM_LAT, UTM_LON, TORONTO);
                    host.goToUTC(2026, 9, 22, 1, 0);
                    host.setTime(0);
                    host.skyLayers(false, false, false, true);
                    host.skyNorthUp(true);
                    target = 1;
                    rho = SITE_RHO[site];
                })
                .live(() -> host.skyOptics(6.5, Math.max(apertureMm(), 1500), null))
                .controls(c -> {
                    apertureSlider(c);
                    c.choices("Wavelength", new String[]{"V  550 nm", "K  2.2 µm"}, () -> band, k -> band = k);
                    c.choices("Site", SITES, () -> site, k -> site = k);
                })
                .quiz("Which of these can resolve the binary?", 3,
                      "In space a 1-m telescope in V reaches 1.22 × 550 nm / 1 m = 0.14″: just too coarse. On the "
                    + "ground in Mississauga seeing blurs everything to about 2″. With AO a 1-m in K gets "
                    + "1.22 × 2.2 µm / 1 m = 0.55″. Only the 10-m in K with AO reaches 0.055″ < 0.1″.",
                      "Your eye", "A 1-m telescope in space, V band (550 nm)", "A 1-m telescope in K band with AO",
                      "A 10-m telescope in K band with AO")
                .scene((r, hud, t) -> {
                    hud.title("Binary: 0.1″", ACCENT);
                    hud.row("diffraction limit 1.22 λ/D", angle(rayleigh()), ACCENT);
                    hud.row("seeing", seeing() == 0 ? "none" : angle(seeing()));
                    if (site == 3) hud.row("light in the sharp core (Strehl), with AO", strehlText());
                    hud.inset("What the telescope delivers", 300, 340, this::imageInset);
                }));

        s.add(step("Interferometry: a telescope as big as the Earth", """
                Radio waves are long, so a single radio dish has poor resolution. But two or more
                dishes can work together: light reaches each one with a slightly different delay,
                and combining the signals gives the resolution of one telescope as wide as the
                distance between them, the {k:baseline} B:

                $ θ ≈ λ / B

                Very-long-baseline interferometry (VLBI) links dishes on different continents. The
                **Event Horizon Telescope** used a baseline almost as long as Earth's diameter at
                λ = 1 mm to image the shadow of the black hole in M87 (about 42 µas across).

                > Slide the dishes apart. Try 21 cm, the radio line of hydrogen.
                """)
                .unit(MM)
                .cam("Earth", () -> camGoal)
                .enter(() -> {
                    host.showOnly(null, "Earth"); host.setOrbits(false, false); host.setTime(0);
                    host.goToUTC(2026, 9, 22, 1, 0);
                    frameEarth();
                })
                .controls(c -> {
                    c.slider("Baseline B", Math.log10(25), Math.log10(1.2742e7), () -> logB, x -> logB = x,
                            x -> { double b = Math.pow(10, x); return b < 1e3 ? String.format("%.0f m", b) : String.format("%,.0f km", b / 1e3); });
                    c.choices("Wavelength", WAVES, () -> wave, k -> wave = k);
                })
                .scene((r, hud, t) -> {
                    double B = Math.pow(10, logB);
                    drawBaseline(r, B, "B = " + (B < 1e3 ? String.format("%.0f m", B) : String.format("%,.0f km", B / 1e3)));
                    double th = WAVE_M[wave] / B / ARCSEC;
                    hud.title("Interferometer", ACCENT);
                    hud.row("wavelength λ", WAVES[wave]);
                    hud.row("baseline B", B < 1e3 ? String.format("%.0f m", B) : String.format("%,.0f km", B / 1e3));
                    hud.row("resolution λ / B", angle(th), SUN_C);
                    hud.gap();
                    hud.row("Hubble (2.4 m, visible light)", "0.05″", DIM);
                    hud.row("M87* black-hole shadow", "42 µas", DIM);
                    hud.formula("θ ≈ λ / B");
                }));

        s.add(step("Check: an Earth-sized radio telescope", """
                From the slides: a radio interferometer the size of the Earth, at **λ = 1 mm**, with
                Earth's radius taken as **6000 km**.
                """)
                .unit(MM)
                .cam("Earth", () -> camGoal)
                .enter(() -> {
                    host.showOnly(null, "Earth"); host.setOrbits(false, false); host.setTime(0);
                    frameEarth();
                    logB = Math.log10(1.2e7); wave = 0;
                })
                .quiz("What is its resolution?", 1,
                      "The baseline is Earth's diameter, B = 2 × 6000 km = 1.2 × 10⁷ m, so θ = λ / B = "
                    + "10⁻³ / 1.2 × 10⁷ = 8 × 10⁻¹¹ rad. Times 206,265: 1.7 × 10⁻⁵″, about 17 µas. That "
                    + "is like reading a newspaper in New York from Paris.",
                      "About 1″", "About 17 µas (1.7 × 10⁻⁵″)", "About 0.05″", "About 17 mas")
                .scene((r, hud, t) -> {
                    drawBaseline(r, 1.2e7, "B = 2 × 6000 km");
                    hud.title("Earth-sized interferometer", ACCENT);
                    hud.row("wavelength λ", "1 mm");
                    hud.row("baseline B", "2 × 6000 km");
                }));

        return new LessonStudio.LessonDef("Intermediate", "Telescope Power",
                "Collecting area, diffraction, seeing and interferometry",
                "Open up a telescope's mirror until Neptune and Triton appear, see the image a telescope "
              + "really delivers of Pluto and Charon from space, Mississauga or Mauna Kea, and build a "
              + "radio telescope as big as the Earth.",
                new Color(140, 210, 140), s, _ -> { });
    }

    static String vis(double mag, double lim) { return mag < lim ? "seen:" : "too faint:"; }
}
