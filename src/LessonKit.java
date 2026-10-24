import java.awt.*;
import java.awt.geom.*;

/**
 * Shared toolkit for the Lesson Studio's lessons that live in their own files: physical
 * constants, the vector and formatting helpers of {@link LessonStudio}, great-circle drawing on
 * the sky, the Planck and Airy functions, blackbody colours and a small chart plotter for HUD
 * insets. A lesson extends this class, so all of it is in scope unqualified.
 */
abstract class LessonKit {

    // ------------------------------------------------------------------ palette (the studio's)
    static final Color INK = LessonStudio.INK, DIM = LessonStudio.DIM, SUN_C = LessonStudio.SUN_C;
    static final Color PERI_C = LessonStudio.PERI_C, APO_C = LessonStudio.APO_C, ACCENT = LessonStudio.ACCENT;
    static final Color GOOD = LessonStudio.GOOD, BAD = LessonStudio.BAD, VIOLET = LessonStudio.VIOLET;
    static final Color TEAL = LessonStudio.TEAL, GREEN = LessonStudio.GREEN, PINK = LessonStudio.PINK;
    static final Color ORANGE = LessonStudio.ORANGE;

    // ------------------------------------------------------------------ constants (SI, CODATA 2018 / IAU 2015)
    static final double AU = 1.495978707e11, PC = AU * 648000 / Math.PI, LY = 9.4607304725808e15;
    static final double DAY = 86400, YEAR = 365.25 * DAY;
    static final double G = 6.67430e-11, C = 299_792_458.0, H_PL = 6.62607015e-34, K_B = 1.380649e-23;
    static final double SIGMA = 5.670374419e-8, WIEN = 2.897771955e-3;
    static final double R_SUN = 6.957e8, L_SUN = 3.828e26, T_SUN = 5772;
    /** One arcsecond in radians; 206 265″ make a radian. */
    static final double ARCSEC = Math.PI / 648000;

    /** The AST221 observing site: the University of Toronto Mississauga. */
    static final String UTM = "UTM, Mississauga";
    static final double UTM_LAT = 43.5483, UTM_LON = -79.6627;
    static final String TORONTO = "America/Toronto";

    final LessonStudio.Host host;
    LessonKit(LessonStudio.Host host) { this.host = host; }

    static LessonStudio.Step step(String title, String html) { return new LessonStudio.Step(title, html); }

    /** Jump the sim clock to a sim instant (seconds from J2000.0, UTC), to the minute. */
    void jumpTo(double simSec) {
        java.time.LocalDateTime d = java.time.LocalDateTime.of(2000, 1, 1, 12, 0).plusSeconds(Math.round(simSec));
        host.goToUTC(d.getYear(), d.getMonthValue(), d.getDayOfMonth(), d.getHour(), d.getMinute());
    }

    /** A time-speed slider in {@code unit}s of sim time per real second (the bottom 2% pauses). */
    void timeSlider(LessonStudio.Controls c, String unit, double unitSec, double max) {
        c.slider("Time speed", 0, max, () -> host.timeScale() / unitSec,
                x -> host.setTime(x < 0.02 * max ? 0 : x * unitSec),
                x -> x < 0.02 * max ? "paused" : String.format(x < 10 ? "%.1f %s per second" : "%.0f %s per second", x, unit));
    }

    // ------------------------------------------------------------------ vectors & formatting
    static double sq(double x) { return x * x; }
    static double[] v(double x, double y, double z) { return new double[]{x, y, z}; }
    static double[] add(double[] a, double[] b) { return LessonStudio.add(a, b); }
    static double[] sub(double[] a, double[] b) { return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
    static double[] scaleV(double[] a, double k) { return LessonStudio.scaleV(a, k); }
    static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    static double len(double[] a) { return Math.sqrt(dot(a, a)); }
    static double[] norm(double[] a) { return LessonStudio.norm(a); }
    static double[] cross(double[] a, double[] b) { return LessonStudio.cross(a, b); }
    static double[] lerp(double[] a, double[] b, double t) { return LessonStudio.lerp(a, b, t); }
    static double[] pos(double[] state) { return new double[]{state[0], state[1], state[2]}; }
    static double[] vel(double[] state) { return new double[]{state[3], state[4], state[5]}; }
    static Color alpha(Color c, int a) { return LessonStudio.alpha(c, a); }
    static Color mix(Color a, Color b, double t) { return LessonStudio.mix(a, b, t); }
    static double ease(double cur, double goal, double dt, double rate) { return LessonStudio.ease(cur, goal, dt, rate); }
    static String sci(double x, int digits) { return LessonStudio.sci(x, digits); }
    static String au(double x) { return LessonStudio.au(x); }
    static double clamp(double x, double lo, double hi) { return Math.max(lo, Math.min(hi, x)); }
    static double log10(double x) { return Math.log10(x); }

    /** Hours -> "14h39m36s". */
    static String hms(double hours) {
        hours = ((hours % 24) + 24) % 24;
        int h = (int) hours, m = (int) ((hours - h) * 60);
        int s = (int) Math.round(((hours - h) * 60 - m) * 60);
        if (s == 60) { s = 0; m++; }
        if (m == 60) { m = 0; h = (h + 1) % 24; }
        return String.format("%02dh%02dm%02ds", h, m, s);
    }
    /** Degrees -> "+43°32′54″". */
    static String dms(double deg) {
        double d = Math.abs(deg);
        int dd = (int) d, mm = (int) ((d - dd) * 60);
        int ss = (int) Math.round(((d - dd) * 60 - mm) * 60);
        if (ss == 60) { ss = 0; mm++; }
        if (mm == 60) { mm = 0; dd++; }
        return String.format("%s%d°%02d′%02d″", deg < 0 ? "−" : "+", dd, mm, ss);
    }
    /** An angle in arcseconds, in the unit that reads best. */
    static String angle(double arcsec) {
        double a = Math.abs(arcsec);
        if (a >= 3600) return String.format("%.2f°", arcsec / 3600);
        if (a >= 60) return String.format("%.1f′", arcsec / 60);
        if (a >= 1) return String.format("%.2f″", arcsec);
        if (a >= 1e-3) return String.format("%.1f mas", arcsec * 1e3);
        return String.format("%.1f µas", arcsec * 1e6);
    }
    /** An angle in arcseconds only (no milli- or micro-arcseconds), for AST101: 1.96″, 0.138″, 0.005″. */
    static String arcsec(double arcsec) {
        double a = Math.abs(arcsec);
        if (a >= 60) return angle(arcsec);
        return String.format(a >= 1 ? "%.2f″" : a >= 0.01 ? "%.3f″" : "%.4f″", arcsec);
    }
    /**
     * A sphere lit from one side, seen from the observer: a disc of radius {@code r} px with a fraction
     * {@code lit} of its face lit, the lit limb on the right when {@code litRight}. The terminator is an
     * ellipse of half-width |2·lit − 1|·r, bulging into the dark side past half (gibbous) and into the
     * lit side before (crescent).
     */
    static void phaseDisc(Graphics2D g, double cx, double cy, double r, double lit, boolean litRight, Color bright) {
        g.setColor(new Color(40, 42, 50));
        g.fill(new java.awt.geom.Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
        double w = Math.abs(2 * lit - 1) * r;
        java.awt.geom.Area a = new java.awt.geom.Area(new java.awt.geom.Rectangle2D.Double(litRight ? cx : cx - r, cy - r, r, 2 * r));
        a.intersect(new java.awt.geom.Area(new java.awt.geom.Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r)));
        java.awt.geom.Area t = new java.awt.geom.Area(new java.awt.geom.Ellipse2D.Double(cx - w, cy - r, 2 * w, 2 * r));
        if (lit >= 0.5) a.add(t); else a.subtract(t);
        g.setColor(bright);
        g.fill(a);
        g.setColor(new Color(255, 255, 255, 50));
        g.setStroke(new BasicStroke(1f));
        g.draw(new java.awt.geom.Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
    }

    /** Ecliptic longitude (°, 0–360) of a world-frame vector (world = {ye, −xe, ze}). */
    static double eclLon(double[] w) { double l = Math.toDegrees(Math.atan2(w[0], -w[1])); return l < 0 ? l + 360 : l; }
    /** Ecliptic latitude (°) of a world-frame vector. */
    static double eclLat(double[] w) { return Math.toDegrees(Math.asin(clamp(w[2] / len(w), -1, 1))); }

    /** Signed number with a real minus sign. */
    static String signed(String fmt, double x) { return String.format(fmt, x).replace('-', '−'); }
    /**
     * Four significant figures, with thousands separators: 0.05801, 1.000, 140.7, 27,205. The digits
     * are chosen after rounding, so a value hovering at a power of ten (Earth's a = 0.99998 … 1.00002)
     * always reads 1.000 rather than flipping between 1.000 and 1.0000.
     */
    static String sig(double x) { return sig(x, x); }
    /** {@code x} with the decimals four significant figures of {@code ref} would get: a live value stays put. */
    static String sig(double x, double ref) {
        double m = Math.abs(ref);
        if (m == 0) return "0.000";
        int d = Math.max(0, 3 - (int) Math.floor(Math.log10(m)));
        if (d > 0 && Math.round(m * Math.pow(10, d)) >= 10000) d--;      // rounds up into the next decade
        return d == 0 ? String.format("%,.0f", x) : String.format("%." + d + "f", x);
    }

    // ------------------------------------------------------------------ the sky (east-north-up directions)

    /** ENU unit vector for an azimuth (from north through east) and altitude, in degrees. */
    static double[] enu(double azDeg, double altDeg) {
        double az = Math.toRadians(azDeg), alt = Math.toRadians(altDeg), ca = Math.cos(alt);
        return new double[]{Math.sin(az) * ca, Math.cos(az) * ca, Math.sin(alt)};
    }
    static double altOf(double[] e) { return Math.toDegrees(Math.asin(clamp(e[2] / len(e), -1, 1))); }
    static double azOf(double[] e) { double a = Math.toDegrees(Math.atan2(e[0], e[1])); return a < 0 ? a + 360 : a; }
    /** Angle (degrees) between two directions. */
    static double sep(double[] a, double[] b) { return Math.toDegrees(Math.acos(clamp(dot(norm(a), norm(b)), -1, 1))); }

    /** The great-circle arc from direction a to direction b (the short way). */
    static void arc(LessonStudio.R3 r, double[] a, double[] b, Color c, float w, boolean dashed) {
        double ang = Math.toRadians(sep(a, b));
        int n = Math.max(4, (int) Math.ceil(ang / Math.toRadians(1.5)));
        double[][] p = new double[n + 1][];
        for (int i = 0; i <= n; i++) p[i] = norm(lerp(norm(a), norm(b), (double) i / n));
        r.polyline(p, false, c, w, dashed);
    }
    /** Circle of points {@code radiusDeg} from {@code axis} on the sky. */
    static void skyCircle(LessonStudio.R3 r, double[] axis, double radiusDeg, Color c, float w, boolean dashed) {
        double[] z = norm(axis), x = norm(cross(Math.abs(z[2]) < 0.9 ? v(0, 0, 1) : v(1, 0, 0), z)), y = cross(z, x);
        int n = 180;
        double rr = Math.toRadians(radiusDeg), cr = Math.cos(rr), sr = Math.sin(rr);
        double[][] p = new double[n][];
        for (int i = 0; i < n; i++) {
            double t = 2 * Math.PI * i / n;
            p[i] = add(scaleV(z, cr), add(scaleV(x, sr * Math.cos(t)), scaleV(y, sr * Math.sin(t))));
        }
        r.polyline(p, true, c, w, dashed);
    }

    // ------------------------------------------------------------------ radiation

    /** Planck spectral radiance B_λ(T) in W m⁻² m⁻¹ sr⁻¹. */
    static double planck(double lambdaM, double T) {
        double x = H_PL * C / (lambdaM * K_B * T);
        if (x > 700) return 0;
        return 2 * H_PL * C * C / Math.pow(lambdaM, 5) / Math.expm1(x);
    }
    /** Ballesteros (2012): a blackbody's temperature from its B−V colour. */
    static double tFromBv(double bv) { return 4600 * (1 / (0.92 * bv + 1.7) + 1 / (0.92 * bv + 0.62)); }
    /** The inverse of {@link #tFromBv} (bisection; T falls as B−V rises). */
    static double bvFromT(double T) {
        double lo = -0.4, hi = 2.4;
        for (int i = 0; i < 50; i++) {
            double mid = (lo + hi) / 2;
            if (tFromBv(mid) > T) lo = mid; else hi = mid;
        }
        return (lo + hi) / 2;
    }
    /** The colour a star of temperature T looks (the planetarium's B−V tint). */
    static Color starColor(double T) { return new Color(StarCatalog.bvToRgb(bvFromT(T))); }

    /** Bessel J₁ (Abramowitz & Stegun 9.4.4 / 9.4.6 polynomial fits, |error| < 1e-7). */
    static double besselJ1(double x) {
        double ax = Math.abs(x);
        if (ax < 3) {
            double y = sq(x / 3);
            return x * (0.5 + y * (-0.56249985 + y * (0.21093573 + y * (-0.03954289
                    + y * (0.00443319 + y * (-0.00031761 + y * 0.00001109))))));
        }
        double y = 3 / ax;
        double f = 0.79788456 + y * (0.00000156 + y * (0.01659667 + y * (0.00017105
                + y * (-0.00249511 + y * (0.00113653 - y * 0.00020033)))));
        double th = ax - 2.35619449 + y * (0.12499612 + y * (0.00005650 + y * (-0.00637879
                + y * (0.00074348 + y * (0.00079824 - y * 0.00029166)))));
        double r = f * Math.cos(th) / Math.sqrt(ax);
        return x < 0 ? -r : r;
    }
    /** The Airy pattern [2 J₁(x) / x]², x = π D θ / λ: the image of a point through a round aperture. */
    static double airy(double x) {
        if (Math.abs(x) < 1e-6) return 1;
        double j = 2 * besselJ1(x) / x;
        return j * j;
    }

    // ------------------------------------------------------------------ insets

    /** Linear or logarithmic axes in an inset box, with a few helpers to draw on them. */
    static final class Plot {
        final Rectangle box;
        final double x0, x1, y0, y1;
        final boolean logX, logY;
        final int left = 38, bottom = 22, top = 6, right = 8;
        Plot(Rectangle box, double x0, double x1, boolean logX, double y0, double y1, boolean logY) {
            this.box = box; this.x0 = x0; this.x1 = x1; this.logX = logX; this.y0 = y0; this.y1 = y1; this.logY = logY;
        }
        private double fx(double x) { return logX ? Math.log10(x) : x; }
        private double fy(double y) { return logY ? Math.log10(y) : y; }
        double px(double x) { return box.x + left + (box.width - left - right) * (fx(x) - fx(x0)) / (fx(x1) - fx(x0)); }
        double py(double y) { return box.y + box.height - bottom - (box.height - bottom - top) * (fy(y) - fy(y0)) / (fy(y1) - fy(y0)); }
        double plotLeft() { return box.x + left; }
        double plotRight() { return box.x + box.width - right; }
        double plotTop() { return box.y + top; }
        double plotBottom() { return box.y + box.height - bottom; }

        /** Frame, tick labels and axis titles. */
        void axes(Graphics2D g, String xTitle, String yTitle, double[] xTicks, String[] xLabels, double[] yTicks, String[] yLabels) {
            g.setStroke(new BasicStroke(1f));
            g.setColor(new Color(255, 255, 255, 40));
            for (double t : xTicks) g.draw(new Line2D.Double(px(t), plotTop(), px(t), plotBottom()));
            for (double t : yTicks) g.draw(new Line2D.Double(plotLeft(), py(t), plotRight(), py(t)));
            g.setColor(new Color(255, 255, 255, 120));
            g.draw(new Line2D.Double(plotLeft(), plotBottom(), plotRight(), plotBottom()));
            g.draw(new Line2D.Double(plotLeft(), plotTop(), plotLeft(), plotBottom()));
            g.setFont(LessonStudio.uiFont(Font.PLAIN, 10f));
            FontMetrics fm = g.getFontMetrics();
            g.setColor(DIM);
            for (int i = 0; i < xTicks.length; i++) {
                String s = xLabels[i];
                g.drawString(s, (float) (px(xTicks[i]) - fm.stringWidth(s) / 2.0), (float) (plotBottom() + 12));
            }
            for (int i = 0; i < yTicks.length; i++) {
                String s = yLabels[i];
                g.drawString(s, (float) (plotLeft() - 4 - fm.stringWidth(s)), (float) (py(yTicks[i]) + 4));
            }
            if (xTitle != null) g.drawString(xTitle, (float) (plotRight() - fm.stringWidth(xTitle)), (float) (plotBottom() + 21));
            if (yTitle != null) g.drawString(yTitle, (float) (plotLeft() + 4), (float) (plotTop() + 10));
        }

        /** A curve through (xs, ys), clipped to the plot. */
        void curve(Graphics2D g, double[] xs, double[] ys, Color c, float w) {
            Path2D.Double p = new Path2D.Double();
            boolean pen = false;
            for (int i = 0; i < xs.length; i++) {
                if (Double.isNaN(ys[i]) || (logY && ys[i] <= 0)) { pen = false; continue; }
                double X = px(xs[i]), Y = clamp(py(ys[i]), plotTop() - 2, plotBottom() + 2);
                if (!pen) { p.moveTo(X, Y); pen = true; } else p.lineTo(X, Y);
            }
            Shape old = g.getClip();
            g.clip(new Rectangle2D.Double(plotLeft(), plotTop() - 2, plotRight() - plotLeft(), plotBottom() - plotTop() + 4));
            g.setColor(c);
            g.setStroke(new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(p);
            g.setClip(old);
        }

        void dot(Graphics2D g, double x, double y, double r, Color c) {
            g.setColor(c);
            g.fill(new Ellipse2D.Double(px(x) - r, py(y) - r, 2 * r, 2 * r));
        }

        void label(Graphics2D g, double x, double y, String s, Color c, int dx, int dy) {
            g.setFont(LessonStudio.uiFont(Font.BOLD, 10.5f));
            g.setColor(c);
            g.drawString(s, (float) (px(x) + dx), (float) (py(y) + dy));
        }

        void vline(Graphics2D g, double x, Color c) {
            g.setColor(c);
            g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[]{4f, 3f}, 0f));
            g.draw(new Line2D.Double(px(x), plotTop(), px(x), plotBottom()));
        }
        void hline(Graphics2D g, double y, Color c) {
            g.setColor(c);
            g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[]{4f, 3f}, 0f));
            g.draw(new Line2D.Double(plotLeft(), py(y), plotRight(), py(y)));
        }
    }

    /** Evenly spaced ticks with labels. */
    static double[] ticks(double a, double b, double step) {
        int n = (int) Math.floor((b - a) / step + 1e-9) + 1;
        double[] t = new double[n];
        for (int i = 0; i < n; i++) t[i] = a + i * step;
        return t;
    }
    static String[] labels(double[] t, String fmt) {
        String[] s = new String[t.length];
        for (int i = 0; i < t.length; i++) s[i] = String.format(fmt, t[i]).replace('-', '−');
        return s;
    }

    /** Text lines in an inset, top-left. */
    static void insetText(Graphics2D g, Rectangle b, Color c, String... lines) {
        g.setFont(LessonStudio.uiFont(Font.PLAIN, 11f));
        g.setColor(c);
        int y = b.y + 14;
        for (String s : lines) { g.drawString(s, b.x + 4, y); y += 15; }
    }
}
