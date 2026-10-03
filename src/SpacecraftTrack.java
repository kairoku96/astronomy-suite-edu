import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * A spacecraft trajectory: ecliptic-J2000 state vectors (TDB seconds since J2000, km, km/s),
 * stored as one or more <em>segments</em>, each relative to a centre body.
 *
 * <p>The base segment spans the whole flight (the Sun for a deep-space probe, the Earth for a
 * lunar mission); local segments cover the stretches near a planet or the Moon. Positions
 * between samples come from cubic Hermite interpolation of the neighbouring states — the
 * same interpolant the track builders ({@code tools.FetchMissions}, {@link ApolloReconstruction})
 * validated every dropped sample against, so playback reproduces the source trajectory to
 * within max(0.2 km, 10⁻⁵ r).
 *
 * <p>File format ({@code .trk.gz}, gzip text): a {@code mission <id>} line, then for each
 * segment a {@code seg <Centre> <n>} line followed by {@code n} lines {@code t x y z vx vy vz}.
 */
final class SpacecraftTrack {

    /** One run of samples relative to a single centre body. */
    static final class Segment {
        final String centre;
        final double[] t;          // TDB s since J2000, ascending
        final double[] s;          // 6 per sample: x, y, z (km), vx, vy, vz (km/s)

        Segment(String centre, double[] t, double[] s) {
            this.centre = centre; this.t = t; this.s = s;
        }

        int size() { return t.length; }
        double start() { return t[0]; }
        double end() { return t[t.length - 1]; }
        boolean covers(double tdb) { return tdb >= t[0] && tdb <= t[t.length - 1]; }

        /** Index k with t[k] ≤ tdb < t[k+1] (clamped to the valid interval range). */
        int interval(double tdb) {
            int k = Arrays.binarySearch(t, tdb);
            if (k < 0) k = -k - 2;
            return Math.max(0, Math.min(t.length - 2, k));
        }

        /** State {x,y,z,vx,vy,vz} (km, km/s) at {@code tdb}, by Hermite interpolation. */
        void stateAt(double tdb, double[] out) {
            int k = interval(tdb);
            hermite(t[k], s, 6 * k, t[k + 1], s, 6 * (k + 1), tdb, out);
        }
    }

    final String id;
    final List<Segment> segments;     // [0] is the base segment

    SpacecraftTrack(String id, List<Segment> segments) {
        this.id = id;
        this.segments = segments;
    }

    Segment base() { return segments.get(0); }
    double start() { return base().start(); }
    double end() { return base().end(); }
    boolean covers(double tdb) { return tdb >= start() && tdb <= end(); }

    /** The segment that should place the craft at {@code tdb}: a local one if any covers it, else the base. */
    Segment segmentAt(double tdb) {
        for (int i = 1; i < segments.size(); i++) if (segments.get(i).covers(tdb)) return segments.get(i);
        return base().covers(tdb) ? base() : null;
    }

    // ------------------------------------------------------------------ interpolation

    /**
     * Cubic Hermite interpolation between state a (at ta) and state b (at tb): position, and its
     * time derivative as the velocity, written to {@code out[0..5]}.
     */
    static void hermite(double ta, double[] A, int ao, double tb, double[] B, int bo, double t, double[] out) {
        double h = tb - ta, u = (t - ta) / h;
        double u2 = u * u, u3 = u2 * u;
        double h00 = 2 * u3 - 3 * u2 + 1, h10 = u3 - 2 * u2 + u, h01 = -2 * u3 + 3 * u2, h11 = u3 - u2;
        double d00 = (6 * u2 - 6 * u) / h, d10 = 3 * u2 - 4 * u + 1, d01 = (-6 * u2 + 6 * u) / h, d11 = 3 * u2 - 2 * u;
        for (int c = 0; c < 3; c++) {
            double pa = A[ao + c], va = A[ao + 3 + c], pb = B[bo + c], vb = B[bo + 3 + c];
            out[c] = h00 * pa + h10 * h * va + h01 * pb + h11 * h * vb;
            out[3 + c] = d00 * pa + d10 * va + d01 * pb + d11 * vb;
        }
    }

    // ------------------------------------------------------------------ I/O

    static SpacecraftTrack load(Path file) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.US_ASCII))) {
            return read(r);
        }
    }

    static SpacecraftTrack read(BufferedReader r) throws IOException {
        String id = "?";
        List<Segment> segs = new ArrayList<>();
        String line;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("mission ")) { id = line.substring(8).trim(); continue; }
            if (line.startsWith("seg ")) {
                String[] h = line.split("\\s+");
                int n = Integer.parseInt(h[2]);
                double[] t = new double[n], s = new double[6 * n];
                for (int k = 0; k < n; k++) {
                    String[] f = r.readLine().trim().split("\\s+");
                    t[k] = Double.parseDouble(f[0]);
                    for (int c = 0; c < 6; c++) s[6 * k + c] = Double.parseDouble(f[1 + c]);
                }
                segs.add(new Segment(h[1], t, s));
            }
        }
        if (segs.isEmpty()) throw new IOException("empty track " + id);
        return new SpacecraftTrack(id, segs);
    }

    void write(Path file) throws IOException {
        try (Writer w = new OutputStreamWriter(new java.util.zip.GZIPOutputStream(Files.newOutputStream(file)),
                StandardCharsets.US_ASCII)) {
            w.write("mission " + id + "\n");
            for (Segment g : segments) {
                w.write("seg " + g.centre + " " + g.size() + "\n");
                for (int k = 0; k < g.size(); k++) {
                    w.write(String.format(Locale.ROOT, "%.1f %.3f %.3f %.3f %.7f %.7f %.7f%n", g.t[k],
                            g.s[6 * k], g.s[6 * k + 1], g.s[6 * k + 2], g.s[6 * k + 3], g.s[6 * k + 4], g.s[6 * k + 5]));
                }
            }
        }
    }

    /**
     * Greedy thinning (the same rule the Horizons fetcher uses): from dense states
     * {t, x, y, z, vx, vy, vz}, keep the fewest whose Hermite interpolant reproduces every
     * dropped one within max(0.2 km, 10⁻⁵ r). Returns a segment.
     */
    static Segment thin(String centre, List<double[]> d) {
        List<double[]> kept = new ArrayList<>();
        int i = 0;
        kept.add(d.get(0));
        double[] p = new double[6];
        while (i < d.size() - 1) {
            int j = i + 1;
            outer:
            while (j + 1 < d.size()) {
                double[] a = d.get(i), b = d.get(j + 1);
                for (int k = i + 1; k <= j; k++) {
                    double[] s = d.get(k);
                    hermite(a[0], a, 1, b[0], b, 1, s[0], p);
                    double err = Math.sqrt(sq(p[0] - s[1]) + sq(p[1] - s[2]) + sq(p[2] - s[3]));
                    double r = Math.sqrt(sq(s[1]) + sq(s[2]) + sq(s[3]));
                    if (err > Math.max(0.2, 1e-5 * r)) break outer;
                }
                j++;
            }
            kept.add(d.get(j));
            i = j;
        }
        double[] t = new double[kept.size()], s = new double[6 * kept.size()];
        for (int k = 0; k < kept.size(); k++) {
            t[k] = kept.get(k)[0];
            System.arraycopy(kept.get(k), 1, s, 6 * k, 6);
        }
        return new Segment(centre, t, s);
    }

    private static double sq(double x) { return x * x; }
}
