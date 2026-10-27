/**
 * A smooth pan-and-zoom between two views: van Wijk &amp; Nuij (2003), "Smooth and efficient zooming
 * and panning". Among all the ways to move a view across a distance while changing its width, this
 * path minimises the perceived travel: when the two views are far apart compared with their widths
 * it zooms out part-way, pans while both ends are in sight, and zooms back in, so the target never
 * slides in from off-screen. The move is timed, so it takes about a second at any scale (from the
 * Earth–Moon line to the whole Solar System), and eases in and out.
 */
final class ZoomPath {
    /** Their recommended balance of zooming against panning. */
    private static final double RHO = 1.4;

    private final double u1, w0, w1, r0, S, zoomSign;
    private final boolean pureZoom;
    /** Duration of the move (s). */
    final double seconds;
    private double t;

    /** A move across distance {@code dist} from a view {@code w0} wide to one {@code w1} wide (any one unit). */
    ZoomPath(double dist, double w0, double w1) {
        this.u1 = dist;
        this.w0 = w0;
        this.w1 = w1;
        pureZoom = dist < 1e-6 * Math.min(w0, w1);
        if (pureZoom) {
            r0 = 0;
            zoomSign = Math.signum(Math.log(w1 / w0));
            S = Math.abs(Math.log(w1 / w0)) / RHO;
        } else {
            double p2 = RHO * RHO, p4 = p2 * p2;
            double b0 = (w1 * w1 - w0 * w0 + p4 * dist * dist) / (2 * w0 * p2 * dist);
            double b1 = (w1 * w1 - w0 * w0 - p4 * dist * dist) / (2 * w1 * p2 * dist);
            r0 = -asinh(b0);                     // ln(√(b² + 1) − b), without the cancellation
            zoomSign = 0;
            S = (-asinh(b1) - r0) / RHO;
        }
        seconds = Math.max(0.6, Math.min(1.5, 0.45 + 0.13 * S));
    }

    /** Advance the clock; true once the move is complete. */
    boolean advance(double dt) { t = Math.min(seconds, t + dt); return t >= seconds; }

    /** Eased progress 0 → 1 (smootherstep: no jolt at either end), for the parts that just blend. */
    double ease() {
        double x = t / seconds;
        return x * x * x * (x * (6 * x - 15) + 10);
    }

    /** Fraction of the distance covered so far. */
    double pan() {
        double f = ease();
        if (pureZoom || f >= 1) return f;
        double s = f * S;
        return w0 / (RHO * RHO) * (Math.cosh(r0) * Math.tanh(RHO * s + r0) - Math.sinh(r0)) / u1;
    }

    /** View width now (the unit of the constructor). */
    double width() {
        double f = ease();
        if (f >= 1) return w1;
        double s = f * S;
        return pureZoom ? w0 * Math.exp(zoomSign * RHO * s) : w0 * Math.cosh(r0) / Math.cosh(RHO * s + r0);
    }

    private static double asinh(double x) { return Math.signum(x) * Math.log(Math.abs(x) + Math.sqrt(x * x + 1)); }
}
