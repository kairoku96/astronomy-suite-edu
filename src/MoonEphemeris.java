import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/**
 * Geocentric lunar position from the full <b>ELP/MPP02</b> theory (J. Chapront &amp;
 * G. Francou, A&amp;A 404, 735, 2003): the ELP-2000/82 lunar theory with the planetary
 * perturbations recomputed from the MPP02 solution and its constants fitted to lunar laser
 * ranging. All 35 901 terms are read from {@code assets/moon/elpmpp02.bin.gz} (written by
 * {@code tools.FetchElpMpp02}). Each is A·t^k·sin(φ₀ + φ₁t + … + φ₄t⁴), t in Julian
 * centuries of TDB since J2000; the main problem gives longitude and latitude (″) and
 * distance (km) on the mean ecliptic and equinox of date, and Laskar's P, Q polynomials
 * rotate the result to the <b>mean ecliptic and equinox of J2000</b>, the frame of the
 * N-body planetary ephemeris ({@link NBodyEphemeris}). Against JPL Horizons this is a
 * fraction of an arcsecond over 1950–2100 ({@code MoonEphemerisTest}); the truncated
 * Meeus series it replaces was 10–22″ off, which put solar-eclipse paths 20–40 km off.
 *
 * <p>Full evaluation is ~36 000 sines (~0.3 ms). The everyday API interpolates the full theory
 * from Chebyshev series over cached 8-day segments (within 0.3 m of it, at nanoseconds a
 * call); {@link #geocentricEclipticAUatTT(double, double)} evaluates directly
 * with an amplitude cut-off, for a caller that needs the Moon at many scattered instants and
 * less precisely (the N-body integrator's Earth–Moon tide); terms are stored by increasing
 * amplitude, so a cut just skips the tail.
 *
 * <p>{@link #bodyToEcliptic} adds the Moon's IAU orientation (pole, prime meridian and
 * librations).
 */
final class MoonEphemeris {
    private MoonEphemeris() {}

    private static final double DEG = Math.PI / 180.0;
    private static final double ARCSEC = Math.PI / 648000.0;
    private static final double AU_KM = 149_597_870.7;

    // ------------------------------------------------------------------ the series

    /** Constants of the ICOR = 0 (lunar-laser-ranging) fit: the Moon's mean longitude W1 (rad, powers of t). */
    private static final double[] W1 = {
        3.81034392032190893929e+00, 8.39968473020743294910e+03, -3.31919929752746043178e-05,
        3.20170955004737532337e-08, -1.53637455543611967059e-10 };
    /** Laskar's precession quantities P and Q (powers of t, starting at t¹). */
    private static final double[] P = { 1.0180391e-05, 4.7020439e-07, -5.417367e-10, -2.507948e-12, 4.63486e-15 };
    private static final double[] Q = { -1.13469002e-04, 1.2372674e-07, 1.2654170e-09, -1.371808e-12, -3.20334e-15 };
    /** Distance scale: the semi-major axis of DE405 over the theory's own. */
    private static final double DIST_SCALE = 384747.9613701725 / 384747.980674318;

    /** One series: coordinate (0 λ, 1 β, 2 r), power of t (−1 main problem), terms (6 doubles each). */
    private record Series(int coord, int power, double[] terms) {}

    private static Series[] series;

    private static synchronized Series[] series() {
        if (series != null) return series;
        Path f = Path.of("assets/moon/elpmpp02.bin.gz");
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(Files.newInputStream(f)), 1 << 16))) {
            byte[] magic = new byte[8];
            in.readFully(magic);
            if (!new String(magic, java.nio.charset.StandardCharsets.US_ASCII).equals("ELPMPP02"))
                throw new IOException(f + " is not an ELP/MPP02 series file");
            Series[] s = new Series[in.readInt()];
            for (int k = 0; k < s.length; k++) {
                int coord = in.readByte(), power = in.readByte(), n = in.readInt();
                double[] d = new double[6 * n];
                for (int i = 0; i < d.length; i++) d[i] = in.readDouble();
                s[k] = new Series(coord, power, d);
            }
            return series = s;
        } catch (IOException e) {
            throw new UncheckedIOException("ELP/MPP02 lunar series missing: run tools.FetchElpMpp02", e);
        }
    }

    /** First term (index) of a series whose |amplitude| reaches {@code tol}: binary search, terms ascending. */
    private static int firstAbove(double[] d, double tol) {
        int lo = 0, hi = d.length / 6;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (Math.abs(d[6 * mid]) < tol) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    /**
     * Geocentric ecliptic-J2000 position (km) at {@code tdbSec} (TDB ≈ TT seconds since J2000),
     * summing only terms with |amplitude| ≥ {@code tol} (″ for λ, β; km for r; 0 = all).
     */
    private static double[] elp(double tdbSec, double tol) {
        double t = tdbSec / (86400.0 * 36525.0);
        double t2 = t * t, t3 = t2 * t, t4 = t3 * t;
        double[] sum = new double[3];
        for (Series s : series()) {
            double[] d = s.terms;
            double tk = s.power <= 0 ? 1 : s.power == 1 ? t : s.power == 2 ? t2 : t3;
            double acc = 0;
            for (int i = 6 * firstAbove(d, tol); i < d.length; i += 6)
                acc += d[i] * Math.sin(d[i + 1] + d[i + 2] * t + d[i + 3] * t2 + d[i + 4] * t3 + d[i + 5] * t4);
            sum[s.coord] += tk * acc;
        }
        // Spherical coordinates on the ecliptic and equinox of date.
        double lam = sum[0] * ARCSEC + W1[0] + W1[1] * t + W1[2] * t2 + W1[3] * t3 + W1[4] * t4;
        double bet = sum[1] * ARCSEC;
        double r = sum[2] * DIST_SCALE;
        double cb = Math.cos(bet);
        double x1 = r * cb * Math.cos(lam), x2 = r * cb * Math.sin(lam), x3 = r * Math.sin(bet);
        // Laskar's rotation to the fixed ecliptic and equinox of J2000.
        double pw = (P[0] + t * (P[1] + t * (P[2] + t * (P[3] + t * P[4])))) * t;
        double qw = (Q[0] + t * (Q[1] + t * (Q[2] + t * (Q[3] + t * Q[4])))) * t;
        double ra = 2 * Math.sqrt(1 - pw * pw - qw * qw);
        double pwqw = 2 * pw * qw, pw2 = 1 - 2 * pw * pw, qw2 = 1 - 2 * qw * qw;
        return new double[]{
            pw2 * x1 + pwqw * x2 + pw * ra * x3,
            pwqw * x1 + qw2 * x2 - qw * ra * x3,
            -pw * ra * x1 + qw * ra * x2 + (pw2 + qw2 - 1) * x3 };
    }

    // One-entry cache per precision: many callers ask about the same instant in a row.
    private static double cacheT = Double.NaN, cacheTol = Double.NaN;
    private static double[] cacheKm;

    /** {@link #elp} with the last result cached (thread-safe). */
    private static synchronized double[] positionKm(double ttSec, double tol) {
        if (ttSec != cacheT || tol != cacheTol) {
            cacheKm = elp(ttSec, tol);
            cacheT = ttSec; cacheTol = tol;
        }
        return cacheKm;
    }

    /*
     * The full theory costs ~0.3 ms, and some callers ask thousands of times (an eclipse path,
     * an event search). The Moon's geocentric motion is smooth over 8 days (≈ 0.9 rad of its
     * orbit), so the everyday API evaluates the full theory at 13 Chebyshev nodes per 8-day
     * segment and interpolates: within 0.3 m of direct evaluation (the series' short-period
     * terms set that), 1/300 of the theory's own error, at nanoseconds a call. Segments are
     * cached (least recently used dropped first).
     */
    private static final double SEG_SEC = 8 * 86400.0;
    private static final int CHEB_N = 13;
    private static final java.util.LinkedHashMap<Long, double[]> segments =
            new java.util.LinkedHashMap<>(64, 0.75f, true) {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<Long, double[]> e) { return size() > 512; }
            };

    /** Chebyshev coefficients (3 × CHEB_N) of segment k, [k·8 d, (k+1)·8 d) of TT. */
    private static double[] segment(long k) {
        double[] c = segments.get(k);
        if (c != null) return c;
        double[][] f = new double[CHEB_N][];
        for (int j = 0; j < CHEB_N; j++) {
            double x = Math.cos(Math.PI * (j + 0.5) / CHEB_N);          // node in [−1, 1]
            f[j] = elp((k + 0.5 + 0.5 * x) * SEG_SEC, 0);
        }
        c = new double[3 * CHEB_N];
        for (int n = 0; n < CHEB_N; n++)
            for (int j = 0; j < CHEB_N; j++) {
                double w = Math.cos(Math.PI * n * (j + 0.5) / CHEB_N) * (n == 0 ? 1.0 : 2.0) / CHEB_N;
                for (int a = 0; a < 3; a++) c[3 * n + a] += w * f[j][a];
            }
        segments.put(k, c);
        return c;
    }

    /** The full theory at {@code ttSec} (km), through the Chebyshev segment cache. */
    private static synchronized double[] interpolatedKm(double ttSec) {
        long k = (long) Math.floor(ttSec / SEG_SEC);
        double[] c = segment(k);
        double x = 2 * (ttSec / SEG_SEC - k) - 1;
        // Clenshaw recurrence for Σ c_n T_n(x), all three coordinates at once.
        double[] b1 = new double[3], b2 = new double[3], out = new double[3];
        for (int n = CHEB_N - 1; n >= 1; n--)
            for (int a = 0; a < 3; a++) {
                double b0 = 2 * x * b1[a] - b2[a] + c[3 * n + a];
                b2[a] = b1[a]; b1[a] = b0;
            }
        for (int a = 0; a < 3; a++) out[a] = x * b1[a] - b2[a] + c[a];
        return out;
    }

    // ------------------------------------------------------------------ public API

    /**
     * Geocentric position of the Moon at the given sim time (seconds of UTC since the
     * J2000.0 epoch instant, 2000-01-01 12:00). Returns {@code {lambda, beta, dist}}:
     * ecliptic longitude and latitude (radians, mean ecliptic and equinox of J2000) and the
     * Earth-centre to Moon-centre distance in metres.
     */
    static double[] geocentric(double simTimeSec) {
        return geocentricTT(Astro.ttSeconds(simTimeSec));
    }

    /** {@link #geocentric} for a time given directly as TT seconds since J2000.0. */
    static double[] geocentricTT(double ttSec) {
        double[] p = interpolatedKm(ttSec);
        double r = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
        double lam = Math.atan2(p[1], p[0]);
        if (lam < 0) lam += 2 * Math.PI;
        return new double[]{ lam, Math.asin(p[2] / r), r * 1000.0 };
    }

    /**
     * Geocentric ecliptic-J2000 rectangular position of the Moon in AU, as {@code {x, y, z}}
     * with x toward the vernal equinox and z toward ecliptic north — the frame JPL Horizons
     * reports geocentric vectors in.
     */
    static double[] geocentricEclipticAU(double simTimeSec) {
        return geocentricEclipticAUatTT(Astro.ttSeconds(simTimeSec));
    }

    /** {@link #geocentricEclipticAU} for a time given as TT seconds since J2000.0. */
    static double[] geocentricEclipticAUatTT(double ttSec) {
        double[] p = interpolatedKm(ttSec);
        return new double[]{ p[0] / AU_KM, p[1] / AU_KM, p[2] / AU_KM };
    }

    /**
     * {@link #geocentricEclipticAU} at TT seconds, evaluated directly (no segment cache) and
     * keeping only terms with amplitude ≥ {@code tol} (″ for the angles, km for the distance;
     * 0 = the full theory). Truncation error, 1900–2100: 0.01″ at 1e-4, 0.3″ at 0.01, 2.5″ at 0.1.
     */
    static double[] geocentricEclipticAUatTT(double ttSec, double tol) {
        double[] p = positionKm(ttSec, tol);
        return new double[]{ p[0] / AU_KM, p[1] / AU_KM, p[2] / AU_KM };
    }

    // ------------------------------------------------------------------ orientation

    /**
     * The Moon's orientation from the IAU WGCCRE model (Archinal et al. 2011/2018): pole
     * right ascension α₀, declination δ₀ and prime meridian W, with the periodic terms in the
     * arguments E1–E13 that carry the physical librations. Returns the row-major 3×3 rotation
     * taking a selenographic (body-fixed, mean-Earth/polar-axis) vector to ecliptic J2000:
     * {@code r_ecl = M · r_body}. {@code ttSec} = TT seconds since J2000.
     */
    static double[] bodyToEcliptic(double ttSec) {
        double d = ttSec / 86400.0, T = d / 36525.0;
        double[] E = new double[14];
        double[][] arg = {
            {0, 0}, {125.045, -0.0529921}, {250.089, -0.1059842}, {260.008, 13.0120009},
            {176.625, 13.3407154}, {357.529, 0.9856003}, {311.589, 26.4057084},
            {134.963, 13.0649930}, {276.617, 0.3287146}, {34.226, 1.7484877},
            {15.134, -0.1589763}, {119.743, 0.0036096}, {239.961, 0.1643573}, {25.053, 12.9590088}};
        for (int k = 1; k <= 13; k++) E[k] = (arg[k][0] + arg[k][1] * d) * DEG;
        double a0 = 269.9949 + 0.0031 * T - 3.8787 * Math.sin(E[1]) - 0.1204 * Math.sin(E[2])
                + 0.0700 * Math.sin(E[3]) - 0.0172 * Math.sin(E[4]) + 0.0072 * Math.sin(E[6])
                - 0.0052 * Math.sin(E[10]) + 0.0043 * Math.sin(E[13]);
        double d0 = 66.5392 + 0.0130 * T + 1.5419 * Math.cos(E[1]) + 0.0239 * Math.cos(E[2])
                - 0.0278 * Math.cos(E[3]) + 0.0068 * Math.cos(E[4]) - 0.0029 * Math.cos(E[6])
                + 0.0009 * Math.cos(E[7]) + 0.0008 * Math.cos(E[10]) - 0.0009 * Math.cos(E[13]);
        double W = 38.3213 + 13.17635815 * d - 1.4e-12 * d * d + 3.5610 * Math.sin(E[1])
                + 0.1208 * Math.sin(E[2]) - 0.0642 * Math.sin(E[3]) + 0.0158 * Math.sin(E[4])
                + 0.0252 * Math.sin(E[5]) - 0.0066 * Math.sin(E[6]) - 0.0047 * Math.sin(E[7])
                - 0.0046 * Math.sin(E[8]) + 0.0028 * Math.sin(E[9]) + 0.0052 * Math.sin(E[10])
                + 0.0040 * Math.sin(E[11]) + 0.0019 * Math.sin(E[12]) - 0.0044 * Math.sin(E[13]);
        // Body → ICRF: Rz(α₀ + 90°) · Rx(90° − δ₀) · Rz(W)  (active rotations).
        double[] m = mulA(rotA(2, (a0 + 90) * DEG), mulA(rotA(0, (90 - d0) * DEG), rotA(2, W * DEG)));
        // ICRF → ecliptic J2000: rotate by −ε about x.
        double e = Astro.EPS_J2000, c = Math.cos(e), s = Math.sin(e);
        double[] q = {1, 0, 0, 0, c, s, 0, -s, c};
        return mulA(q, m);
    }

    /** Selenographic (lat, east lon in degrees, height above the mean radius in km) → ecliptic-J2000 Moon-centred vector (km). */
    static double[] siteEcliptic(double latDeg, double lonDeg, double heightKm, double ttSec) {
        double r = 1737.4 + heightKm, la = latDeg * DEG, lo = lonDeg * DEG;
        double[] b = {r * Math.cos(la) * Math.cos(lo), r * Math.cos(la) * Math.sin(lo), r * Math.sin(la)};
        return Astro.apply(bodyToEcliptic(ttSec), b[0], b[1], b[2]);
    }

    /** Active rotation matrix about axis 0 (x) or 2 (z). */
    private static double[] rotA(int axis, double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return axis == 0 ? new double[]{1, 0, 0, 0, c, -s, 0, s, c} : new double[]{c, -s, 0, s, c, 0, 0, 0, 1};
    }

    private static double[] mulA(double[] A, double[] B) { return Astro.mul(A, B); }
}
