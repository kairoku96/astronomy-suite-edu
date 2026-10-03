import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

/**
 * Reconstructs the Apollo 8, 11, 13 and 17 trajectories by integrating the spacecraft through
 * the sim's own Earth–Moon–Sun field and targeting the mission events NASA documented
 * (Orloff, <i>Apollo by the Numbers</i>, NASA SP-2000-4029). JPL has no ephemerides for the
 * Apollo command modules, so the flights are rebuilt the way mission planners designed them:
 *
 * <ol>
 *   <li><b>Translunar coast.</b> The craft's state at pericynthion is parameterised (speed,
 *       and the direction of the closest point) and integrated <em>backwards</em> to the
 *       documented translunar-injection instant; Newton iteration adjusts it until the state
 *       there has the documented TLI altitude, inertial speed and flight-path angle. For the
 *       landing missions the arrival plane is the one that contains the landing site at the
 *       landing time; all Apollo lunar orbits are retrograde.</li>
 *   <li><b>Lunar orbit.</b> Each documented burn (LOI, circularisation, descent-orbit
 *       insertion…) is an impulse that sets the documented apolune × perilune. One burn's
 *       apsides are trimmed (within tens of km) so the lunar module arrives over the
 *       powered-descent point at the documented time.</li>
 *   <li><b>Landing and ascent</b> follow polynomial guidance profiles (the form of Apollo's
 *       descent and ascent guidance) between the real orbital states and the real site;
 *       <b>rendezvous</b> is a two-impulse Lambert transfer from the documented TPI time.</li>
 *   <li><b>Transearth injection</b> is the impulse (timed within a revolution of the documented
 *       burn for least Δv) that reaches the entry interface (121.9 km) at the documented
 *       instant with Apollo's −6.5° entry flight-path angle.</li>
 * </ol>
 *
 * Forces: Earth point mass + J2, the Moon (from {@link MoonEphemeris}) and the Sun (from the
 * N-body ephemeris) as third bodies; integrated with an adaptive Dormand–Prince 5(4). Output is
 * written as {@link SpacecraftTrack} files in {@code assets/missions/}; run
 * {@code java ApolloReconstruction} from the project root.
 */
final class ApolloReconstruction {

    // ------------------------------------------------------------------ constants
    static final double MU_E = 398600.4418, MU_M = 4902.800066, MU_S = 132712440041.279419;
    static final double R_E = 6378.137, R_M = 1737.4, J2 = 1.08262668e-3;
    static final double AU_KM = 149597870.7;
    static final double NMI = 1.852, FPS = 0.0003048, DEG = Math.PI / 180;
    /** Entry interface: 400,000 ft. */
    static final double H_EI = 121.92;
    static final double GAMMA_EI = -6.5 * DEG;
    /** Earth's pole in ecliptic J2000. */
    static final double[] POLE_E = {0, Math.sin(Astro.EPS_J2000), Math.cos(Astro.EPS_J2000)};
    static final double MOON_FRACTION = 4902.800118 / 403503.235502;

    // ------------------------------------------------------------------ ephemerides (km, TDB s)

    static double[] moon(double t) {
        double[] m = MoonEphemeris.geocentricEclipticAUatTT(t);
        return new double[]{m[0] * AU_KM, m[1] * AU_KM, m[2] * AU_KM};
    }

    static double[] moonState(double t) {
        double[] a = moon(t - 30), b = moon(t + 30), m = moon(t);
        return new double[]{m[0], m[1], m[2], (b[0] - a[0]) / 60, (b[1] - a[1]) / 60, (b[2] - a[2]) / 60};
    }

    private static final Map<Long, double[]> SUN_CACHE = new HashMap<>();

    /** Geocentric Sun (km), linearly interpolated on an hourly grid of the N-body ephemeris. */
    static double[] sun(double t) {
        long k = (long) Math.floor(t / 3600.0);
        double[] a = sunAt(k), b = sunAt(k + 1);
        double w = t / 3600.0 - k;
        return new double[]{a[0] + w * (b[0] - a[0]), a[1] + w * (b[1] - a[1]), a[2] + w * (b[2] - a[2])};
    }

    private static synchronized double[] sunAt(long k) {
        return SUN_CACHE.computeIfAbsent(k, kk -> {
            double t = kk * 3600.0;
            double[] s = new double[6 * NBodyEphemeris.N_BODIES];
            NBodyEphemeris.shared().heliocentric(t, s);
            double[] m = MoonEphemeris.geocentricEclipticAUatTT(t);
            double ex = s[12] - MOON_FRACTION * m[0], ey = s[13] - MOON_FRACTION * m[1], ez = s[14] - MOON_FRACTION * m[2];
            return new double[]{-ex * AU_KM, -ey * AU_KM, -ez * AU_KM};
        });
    }

    // ------------------------------------------------------------------ dynamics

    static void accel(double t, double[] y, double[] out) {
        double x = y[0], yy = y[1], z = y[2];
        double r2 = x * x + yy * yy + z * z, r = Math.sqrt(r2), r3 = r2 * r;
        double ax = -MU_E * x / r3, ay = -MU_E * yy / r3, az = -MU_E * z / r3;
        // Earth's oblateness.
        double zk = x * POLE_E[0] + yy * POLE_E[1] + z * POLE_E[2];
        double f = -1.5 * J2 * MU_E * R_E * R_E / (r2 * r3);
        double q = 1 - 5 * zk * zk / r2;
        ax += f * (q * x + 2 * zk * POLE_E[0]);
        ay += f * (q * yy + 2 * zk * POLE_E[1]);
        az += f * (q * z + 2 * zk * POLE_E[2]);
        thirdBody(MU_M, moon(t), x, yy, z, out);
        ax += out[0]; ay += out[1]; az += out[2];
        thirdBody(MU_S, sun(t), x, yy, z, out);
        ax += out[0]; ay += out[1]; az += out[2];
        out[0] = y[3]; out[1] = y[4]; out[2] = y[5];
        out[3] = ax; out[4] = ay; out[5] = az;
    }

    private static void thirdBody(double mu, double[] b, double x, double y, double z, double[] out) {
        double dx = b[0] - x, dy = b[1] - y, dz = b[2] - z;
        double d3 = Math.pow(dx * dx + dy * dy + dz * dz, 1.5);
        double b3 = Math.pow(b[0] * b[0] + b[1] * b[1] + b[2] * b[2], 1.5);
        out[0] = mu * (dx / d3 - b[0] / b3);
        out[1] = mu * (dy / d3 - b[1] / b3);
        out[2] = mu * (dz / d3 - b[2] / b3);
    }

    /** Longest step allowed: a small fraction of the local dynamical time, so records are dense. */
    static double maxStep(double t, double[] y) {
        double rE = Math.sqrt(y[0] * y[0] + y[1] * y[1] + y[2] * y[2]);
        double[] m = moon(t);
        double dx = y[0] - m[0], dy = y[1] - m[1], dz = y[2] - m[2];
        double rM = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double td = Math.min(Math.sqrt(rE * rE * rE / MU_E), Math.sqrt(rM * rM * rM / MU_M));
        return Math.max(5, Math.min(900, 0.03 * td));
    }

    // Dormand–Prince 5(4) tableau.
    private static final double[] C = {0, 1.0 / 5, 3.0 / 10, 4.0 / 5, 8.0 / 9, 1, 1};
    private static final double[][] A = {
        {},
        {1.0 / 5},
        {3.0 / 40, 9.0 / 40},
        {44.0 / 45, -56.0 / 15, 32.0 / 9},
        {19372.0 / 6561, -25360.0 / 2187, 64448.0 / 6561, -212.0 / 729},
        {9017.0 / 3168, -355.0 / 33, 46732.0 / 5247, 49.0 / 176, -5103.0 / 18656},
        {35.0 / 384, 0, 500.0 / 1113, 125.0 / 192, -2187.0 / 6784, 11.0 / 84}};
    private static final double[] B5 = {35.0 / 384, 0, 500.0 / 1113, 125.0 / 192, -2187.0 / 6784, 11.0 / 84, 0};
    private static final double[] B4 = {5179.0 / 57600, 0, 7571.0 / 16695, 393.0 / 640, -92097.0 / 339200, 187.0 / 2100, 1.0 / 40};

    /** Tolerance used while targeting (looser) and for the final tracks (tight). */
    static double rtol = 1e-11;

    /**
     * Integrate from (t0, y0) to t1 (either direction). Every accepted step is appended to
     * {@code rec} as {t, x, y, z, vx, vy, vz} when rec is non-null. Returns the final state.
     */
    static double[] propagate(double t0, double[] y0, double t1, List<double[]> rec) {
        return integrate(t0, y0, t1, rec, 0, null);
    }

    /**
     * Integrate from (t0, y0) toward tMax, stopping early the first time the geocentric radius
     * drops below {@code rStop}. Returns {t, x, y, z, vx, vy, vz} at the stop (or at tMax).
     */
    static double[] propagateToRadius(double t0, double[] y0, double tMax, double rStop) {
        double[] tOut = new double[1];
        double[] y = integrate(t0, y0, tMax, null, rStop, tOut);
        return row(tOut[0], y);
    }

    private static double[] integrate(double t0, double[] y0, double t1, List<double[]> rec, double rStop, double[] tOut) {
        double[] y = y0.clone();
        double t = t0, dir = Math.signum(t1 - t0);
        if (dir == 0) return y;
        double h = dir * 10;
        double[][] k = new double[7][6];
        double[] tmp = new double[6], y5 = new double[6];
        if (rec != null) rec.add(row(t, y));
        int steps = 0;
        while (dir * (t1 - t) > 1e-9) {
            // A trial trajectory that dives into the Earth or the Moon has no physical meaning
            // (and would grind the step size to nothing): report it as NaN.
            if (++steps > 200_000 || insideBody(t, y)) { Arrays.fill(y, Double.NaN); return y; }
            double hmax = maxStep(t, y);
            if (Math.abs(h) > hmax) h = dir * hmax;
            if (dir * (t + h - t1) > 0) h = t1 - t;
            accel(t, y, k[0]);
            for (int s = 1; s < 7; s++) {
                for (int i = 0; i < 6; i++) {
                    double acc = y[i];
                    for (int j = 0; j < s; j++) acc += h * A[s][j] * k[j][i];
                    tmp[i] = acc;
                }
                accel(t + C[s] * h, tmp, k[s]);
            }
            double err = 0;
            for (int i = 0; i < 6; i++) {
                double s5 = y[i], e = 0;
                for (int j = 0; j < 7; j++) { s5 += h * B5[j] * k[j][i]; e += h * (B5[j] - B4[j]) * k[j][i]; }
                y5[i] = s5;
                double sc = (i < 3 ? 1e-7 : 1e-10) + rtol * Math.max(Math.abs(y[i]), Math.abs(s5));
                err = Math.max(err, Math.abs(e) / sc);
            }
            if (err <= 1) {
                t += h;
                System.arraycopy(y5, 0, y, 0, 6);
                if (rec != null) rec.add(row(t, y));
                if (rStop > 0 && y[0] * y[0] + y[1] * y[1] + y[2] * y[2] < rStop * rStop) break;
            }
            double fac = err == 0 ? 5 : Math.min(5, Math.max(0.2, 0.9 * Math.pow(err, -0.2)));
            h *= fac;
        }
        if (tOut != null) tOut[0] = t;
        return y;
    }

    /**
     * Two-body (Earth) conic through a geocentric state: {perigee radius, C3 (twice the
     * specific energy, km²/s²), time of perigee passage (TDB), eccentricity}. Defined for ellipses and hyperbolas alike,
     * so targeting residuals built on it stay smooth even when a trial orbit misses badly.
     */
    static double[] conic(double t, double[] y) {
        double[] r = pos(y), v = vel(y);
        double rr = norm(r), vv = norm(v);
        double h = norm(cross(r, v));
        double en = vv * vv / 2 - MU_E / rr;
        double e = Math.sqrt(Math.max(0, 1 + 2 * en * h * h / (MU_E * MU_E)));
        double rp = h * h / (MU_E * (1 + e));
        double cosNu = (h * h / (MU_E * rr) - 1) / e;
        double nu = Math.acos(Math.max(-1, Math.min(1, cosNu)));
        if (dot(r, v) < 0) nu = -nu;
        return new double[]{rp, 2 * en, t - timeFromPerigee(e, rp, nu), e};
    }

    /** Time from perigee to true anomaly nu on a conic with eccentricity e and perigee radius rp. */
    static double timeFromPerigee(double e, double rp, double nu) {
        if (e < 1) {
            double a = rp / (1 - e), n = Math.sqrt(MU_E / (a * a * a));
            double E = 2 * Math.atan(Math.sqrt((1 - e) / (1 + e)) * Math.tan(nu / 2));
            return (E - e * Math.sin(E)) / n;
        }
        double a = rp / (e - 1), n = Math.sqrt(MU_E / (a * a * a));
        double x = Math.sqrt((e - 1) / (e + 1)) * Math.tan(nu / 2);
        x = Math.max(-0.999999, Math.min(0.999999, x));
        double F = Math.log((1 + x) / (1 - x));
        return (e * Math.sinh(F) - F) / n;
    }

    static boolean insideBody(double t, double[] y) {
        double r2 = y[0] * y[0] + y[1] * y[1] + y[2] * y[2];
        if (r2 < (R_E - 50) * (R_E - 50)) return true;
        double[] m = moon(t);
        double dx = y[0] - m[0], dy = y[1] - m[1], dz = y[2] - m[2];
        return dx * dx + dy * dy + dz * dz < (R_M - 20) * (R_M - 20);
    }

    static double[] row(double t, double[] y) {
        return new double[]{t, y[0], y[1], y[2], y[3], y[4], y[5]};
    }

    // ------------------------------------------------------------------ vector helpers

    static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    static double norm(double[] a) { return Math.sqrt(dot(a, a)); }
    static double[] cross(double[] a, double[] b) { return Lambert.cross(a, b); }
    static double[] unit(double[] a) { double n = norm(a); return new double[]{a[0] / n, a[1] / n, a[2] / n}; }
    static double[] add(double[] a, double[] b, double s) { return new double[]{a[0] + s * b[0], a[1] + s * b[1], a[2] + s * b[2]}; }
    static double[] sub3(double[] a, double[] b) { return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
    static double[] pos(double[] y) { return new double[]{y[0], y[1], y[2]}; }
    static double[] vel(double[] y) { return new double[]{y[3], y[4], y[5]}; }
    static double[] state(double[] p, double[] v) { return new double[]{p[0], p[1], p[2], v[0], v[1], v[2]}; }

    /** Rotate v about unit axis k by angle a (Rodrigues). */
    static double[] rotate(double[] v, double[] k, double a) {
        double c = Math.cos(a), s = Math.sin(a), kd = dot(k, v);
        double[] kx = cross(k, v);
        return new double[]{v[0] * c + kx[0] * s + k[0] * kd * (1 - c),
                v[1] * c + kx[1] * s + k[1] * kd * (1 - c),
                v[2] * c + kx[2] * s + k[2] * kd * (1 - c)};
    }

    /** Selenocentric state of a geocentric state at time t. */
    static double[] seleno(double t, double[] y) {
        double[] m = moonState(t);
        double[] r = new double[6];
        for (int i = 0; i < 6; i++) r[i] = y[i] - m[i];
        return r;
    }

    static double[] geo(double t, double[] s) {
        double[] m = moonState(t);
        double[] r = new double[6];
        for (int i = 0; i < 6; i++) r[i] = s[i] + m[i];
        return r;
    }

    /**
     * Impulsive burn at time t that puts the craft (geocentric state y) on a Moon-centred orbit
     * with the given apolune/perilune altitudes (km), keeping the current position, orbit
     * plane and the sign of the radial velocity. Returns the new geocentric state.
     */
    static double[] setApsides(double t, double[] y, double hApo, double hPeri) {
        double[] s = seleno(t, y);
        double[] r = pos(s), v = vel(s);
        double rr = norm(r);
        double ra = Math.max(R_M + hApo, rr), rp = Math.min(R_M + hPeri, rr);
        double a = 0.5 * (ra + rp);
        double sp = Math.sqrt(MU_M * (2 / rr - 1 / a));
        double hmom = Math.sqrt(2 * MU_M * rp * ra / (rp + ra));
        double vt = hmom / rr, vr2 = sp * sp - vt * vt;
        double[] rh = unit(r);
        double vrNow = dot(v, rh);
        double vr = vr2 > 0 ? Math.copySign(Math.sqrt(vr2), vrNow == 0 ? 1 : vrNow) : 0;
        double[] th = unit(add(v, rh, -vrNow));
        double[] nv = add(new double[]{rh[0] * vr, rh[1] * vr, rh[2] * vr}, th, vt);
        return geo(t, state(r, nv));
    }

    /** Selenocentric apolune/perilune altitudes (km) of a geocentric state, two-body. */
    static double[] apsides(double t, double[] y) {
        double[] s = seleno(t, y);
        double[] el = NBodyEphemeris.elements(MU_M, s[0], s[1], s[2], s[3], s[4], s[5]);
        return new double[]{el[0] * (1 + el[1]) - R_M, el[0] * (1 - el[1]) - R_M};
    }

    // ------------------------------------------------------------------ Newton solver

    /**
     * Damped Gauss–Newton with a finite-difference Jacobian: drives the residual vector F(x)
     * to zero (least squares when over/under-determined, minimum-norm steps when
     * under-determined). Returns the solution.
     */
    static double[] newton(Function<double[], double[]> F, double[] x0, double[] dx, int maxIt, double tol) {
        double[] x = x0.clone();
        double[] f = F.apply(x);
        int n = x.length, m = f.length;
        // Levenberg–Marquardt: Gauss–Newton steps, damped toward gradient descent (λ) whenever a
        // step fails to reduce |F|, and undamped again as it succeeds — robust in the long curved
        // valleys these targeting problems have, where plain Newton stalls.
        double lambda = 1e-3;
        for (int it = 0; it < maxIt; it++) {
            double fn = norm2(f);
            if (fn < tol) break;
            double[][] J = new double[m][n];
            for (int j = 0; j < n; j++) {
                double[] xp = x.clone(), xm = x.clone();
                xp[j] += dx[j];
                xm[j] -= dx[j];
                double[] fp = F.apply(xp), fm = F.apply(xm);
                for (int i = 0; i < m; i++) J[i][j] = (fp[i] - fm[i]) / (2 * dx[j]);
            }
            double[][] A = new double[n][n];
            double[] g = new double[n];
            for (int a = 0; a < n; a++) {
                for (int c = 0; c < n; c++) for (int i = 0; i < m; i++) A[a][c] += J[i][a] * J[i][c];
                for (int i = 0; i < m; i++) g[a] += J[i][a] * f[i];
            }
            boolean improved = false;
            for (int tries = 0; tries < 14; tries++) {
                double[][] D = new double[n][];
                for (int a = 0; a < n; a++) { D[a] = A[a].clone(); D[a][a] += lambda * Math.max(A[a][a], 1e-12); }
                double[] step = m >= n ? gauss(D, g) : minNormStep(J, f);
                double[] xn = x.clone();
                for (int j = 0; j < n; j++) xn[j] -= step[j];
                double[] fnw = F.apply(xn);
                if (Double.isFinite(norm2(fnw)) && norm2(fnw) < fn) {
                    x = xn; f = fnw; improved = true;
                    lambda = Math.max(1e-9, lambda / 4);
                    break;
                }
                lambda *= 6;
            }
            if (!improved) break;
        }
        return x;
    }

    static double norm2(double[] f) { double s = 0; for (double v : f) s += v * v; return Math.sqrt(s); }

    /** Solve J·s ≈ f: (JᵀJ)⁻¹Jᵀf when m ≥ n, Jᵀ(JJᵀ)⁻¹f when m < n (with light regularisation). */
    static double[] minNormStep(double[][] J, double[] f) {
        int m = J.length, n = J[0].length;
        if (m >= n) {
            double[][] M = new double[n][n];
            double[] b = new double[n];
            for (int a = 0; a < n; a++) {
                for (int c = 0; c < n; c++) for (int i = 0; i < m; i++) M[a][c] += J[i][a] * J[i][c];
                for (int i = 0; i < m; i++) b[a] += J[i][a] * f[i];
                M[a][a] *= 1 + 1e-12;
            }
            return gauss(M, b);
        }
        double[][] M = new double[m][m];
        for (int a = 0; a < m; a++) {
            for (int c = 0; c < m; c++) for (int j = 0; j < n; j++) M[a][c] += J[a][j] * J[c][j];
            M[a][a] *= 1 + 1e-12;
        }
        double[] w = gauss(M, f.clone());
        double[] s = new double[n];
        for (int j = 0; j < n; j++) for (int a = 0; a < m; a++) s[j] += J[a][j] * w[a];
        return s;
    }

    static double[] gauss(double[][] M, double[] b) {
        int n = b.length;
        double[][] a = new double[n][];
        for (int i = 0; i < n; i++) a[i] = M[i].clone();
        double[] x = b.clone();
        for (int c = 0; c < n; c++) {
            int p = c;
            for (int r = c + 1; r < n; r++) if (Math.abs(a[r][c]) > Math.abs(a[p][c])) p = r;
            double[] tr = a[c]; a[c] = a[p]; a[p] = tr;
            double tb = x[c]; x[c] = x[p]; x[p] = tb;
            if (Math.abs(a[c][c]) < 1e-300) continue;
            for (int r = c + 1; r < n; r++) {
                double f = a[r][c] / a[c][c];
                for (int k = c; k < n; k++) a[r][k] -= f * a[c][k];
                x[r] -= f * x[c];
            }
        }
        for (int c = n - 1; c >= 0; c--) {
            double s = x[c];
            for (int k = c + 1; k < n; k++) s -= a[c][k] * x[k];
            x[c] = Math.abs(a[c][c]) < 1e-300 ? 0 : s / a[c][c];
        }
        return x;
    }

    // ------------------------------------------------------------------ mission specification

    /** Documented flight data for one mission (times as ground-elapsed seconds). */
    static final class Spec {
        String id, lmId;
        double launch;                       // UTC sim seconds of liftoff
        double eoi, parkAlt;                 // Earth-orbit insertion GET, parking-orbit altitude (km)
        double tli, tliDur, tliAlt, tliV, tliGamma;   // TLI (10 s after S-IVB cutoff) GET, burn s, km, km/s, rad
        double tliIncl;                      // inclination of the TLI orbit to the Earth's equator (rad)
        double peri, periAlt;                // pericynthion GET and altitude (km)
        double loiApo = -1, loiPeri;         // orbit after LOI (km), or -1 for a flyby
        List<double[]> csmBurns = new ArrayList<>();   // {GET, apo km, peri km}
        int phaseBurn = -1;                  // index into csmBurns trimmed for landing phasing
        boolean phaseBothApsides;
        double undock = -1, lmDoi, lmDoiPeri, pdi, land, siteLat, siteLon, descentArcKm;
        double liftoff, insertion, insApo, insPeri, tpi, tpf, docked;
        double tei, ei;                      // TEI (mid-burn) and entry-interface GETs
        boolean optimiseTei = true;
        double get(double sim) { return sim - launch; }
        double tdb(double getSec) { return Astro.ttSeconds(launch + getSec); }
    }

    static double get(int h, int m, double s) { return h * 3600 + m * 60 + s; }

    static double utc(int y, int mo, int d, int h, int mi, int s) {
        return java.time.Duration.between(java.time.LocalDateTime.of(2000, 1, 1, 12, 0, 0),
                java.time.LocalDateTime.of(y, mo, d, h, mi, s)).getSeconds();
    }

    static Spec spec(String id) {
        Spec s = new Spec();
        s.id = id;
        switch (id) {
            case "apollo8" -> {
                s.tliIncl = 30.639 * DEG;
                s.launch = utc(1968, 12, 21, 12, 51, 0);
                s.eoi = get(0, 11, 35); s.parkAlt = 185.0;
                s.tli = get(2, 56, 5.51); s.tliDur = 317.72;
                s.tliAlt = 187.221 * NMI; s.tliV = 35505.41 * FPS; s.tliGamma = 7.897 * DEG;
                s.peri = get(69, 8, 20.4) + 246.9 / 2; s.periAlt = 60.0 * NMI;
                s.loiApo = 168.5 * NMI; s.loiPeri = 60.0 * NMI;
                s.csmBurns.add(new double[]{get(73, 35, 6.6) + 4.8, 60.7 * NMI, 59.7 * NMI});
                s.tei = get(89, 19, 16.6) + 203.7 / 2; s.ei = get(146, 46, 12.8);
            }
            case "apollo11" -> {
                s.tliIncl = 31.386 * DEG;
                s.lmId = "apollo11_lm";
                s.launch = utc(1969, 7, 16, 13, 32, 0);
                s.eoi = get(0, 11, 49.33); s.parkAlt = 185.0;
                s.tli = get(2, 50, 13.03); s.tliDur = 346.83;
                s.tliAlt = 180.581 * NMI; s.tliV = 35545.6 * FPS; s.tliGamma = 7.367 * DEG;
                s.peri = get(75, 49, 50.37) + 357.53 / 2; s.periAlt = 60.0 * NMI;
                s.loiApo = 169.7 * NMI; s.loiPeri = 60.0 * NMI;
                s.csmBurns.add(new double[]{get(80, 11, 36.75) + 8.4, 66.1 * NMI, 54.5 * NMI});
                s.phaseBurn = 0; s.phaseBothApsides = true;
                s.undock = get(100, 12, 0); s.lmDoi = get(101, 36, 14) + 15; s.lmDoiPeri = 8.5 * NMI;
                s.pdi = get(102, 33, 5.01); s.land = get(102, 45, 39.9);
                s.siteLat = 0.67408; s.siteLon = 23.47297; s.descentArcKm = 480;
                s.liftoff = get(124, 22, 0.79); s.insertion = get(124, 29, 15.67);
                s.insApo = 48.0 * NMI; s.insPeri = 9.4 * NMI;
                s.tpi = get(127, 3, 51.8); s.tpf = get(127, 46, 9.8); s.docked = get(128, 3, 0);
                s.tei = get(135, 23, 42.28) + 151.41 / 2; s.ei = get(195, 3, 5.7);
            }
            case "apollo13" -> {
                s.tliIncl = 31.818 * DEG;
                s.launch = utc(1970, 4, 11, 19, 13, 0);
                s.eoi = get(0, 12, 39.83); s.parkAlt = 185.0;
                s.tli = get(2, 41, 47.15); s.tliDur = 350.85;
                // The scanned table reads 35,938.4 ft/s, an OCR slip: cutoff was 35,562.6 ft/s ten seconds
                // earlier, and every Saturn V TLI lost ~24.2 ft/s in those 10 s (Apollo 17: 35,579.5 →
                // 35,555.3), so the injection speed is 35,538.4 ft/s.
                s.tliAlt = 182.445 * NMI; s.tliV = 35538.4 * FPS; s.tliGamma = 7.635 * DEG;
                // Pericynthion: placed so the computed Earth occultation brackets the documented
                // 077:08:35–077:33:10 (refined in build()); altitude 254 km.
                s.peri = get(77, 20, 52); s.periAlt = 254.0;
                s.tei = get(79, 27, 38.95) + 263.82 / 2; s.ei = get(142, 40, 45.7);
                s.optimiseTei = false;   // PC+2 is a documented-time burn on a flyby, not a lunar-orbit TEI
            }
            case "apollo17" -> {
                s.tliIncl = 28.466 * DEG;
                s.lmId = "apollo17_lm";
                s.launch = utc(1972, 12, 7, 5, 33, 0);
                s.eoi = get(0, 11, 52.6); s.parkAlt = 168.0;
                s.tli = get(3, 18, 37.64); s.tliDur = 351.04;
                s.tliAlt = 169.401 * NMI; s.tliV = 35555.3 * FPS; s.tliGamma = 7.379 * DEG;
                s.peri = get(86, 14, 22.6) + 393.16 / 2; s.periAlt = 52.6 * NMI;
                s.loiApo = 170 * NMI; s.loiPeri = 52.6 * NMI;
                s.csmBurns.add(new double[]{get(90, 31, 37.43) + 11.1, 59 * NMI, 14.5 * NMI});   // DOI-1 (CSM+LM)
                s.phaseBurn = 0; s.phaseBothApsides = false;
                s.undock = get(107, 47, 56);
                s.csmBurns.add(new double[]{get(109, 17, 28.92) + 1.9, 70 * NMI, 54 * NMI});      // CSM circularisation
                s.lmDoi = get(109, 22, 42) + 10.75; s.lmDoiPeri = 6.2 * NMI;
                s.pdi = get(110, 9, 53); s.land = get(110, 21, 58);
                s.siteLat = 20.19080; s.siteLon = 30.77168; s.descentArcKm = 450;
                s.liftoff = get(185, 21, 37); s.insertion = get(185, 28, 58);
                s.insApo = 48.5 * NMI; s.insPeri = 9.1 * NMI;
                s.tpi = get(186, 15, 58); s.tpf = get(187, 20, 0); s.docked = get(187, 37, 15);
                s.tei = get(234, 2, 9.18) + 143.69 / 2; s.ei = get(301, 38, 38);
            }
            default -> throw new IllegalArgumentException(id);
        }
        return s;
    }

    // ------------------------------------------------------------------ translunar arrival

    /**
     * Pericynthion state (geocentric) from the arrival parameters.
     *
     * <p>Flyby and orbit-only missions: {v_p (km/s), φ, θ} — speed at pericynthion and the
     * direction of the closest point (longitude from the anti-Earth direction in the Moon's
     * orbit plane, and latitude out of it); the arrival plane is the retrograde one nearest the
     * Moon's orbit plane, turned about the periapsis line by an optional fourth parameter κ —
     * fixed by the documented inclination of the TLI orbit.
     *
     * <p>Landing missions: {v_p, ω, ψ} — the arrival plane is made to pass over the landing site
     * at the landing instant: ψ turns it about the site's direction (its heading over the site;
     * ψ = 0 is the plane nearest the Moon's orbit plane), and ω places the pericynthion within
     * it, measured from the site. (Defining the plane by the pericynthion and the site instead
     * is ill-conditioned: the far-side pericynthion is nearly opposite a near-side site.)
     */
    static double[] periState(Spec sp, double[] x) {
        double tp = sp.tdb(sp.peri);
        double[] m = moonState(tp);
        double[] mr = pos(m), mv = vel(m);
        double[] xh = unit(mr), zh = unit(cross(mr, mv)), yh = cross(zh, xh);
        double[] p, n;
        if (sp.undock > 0) {
            double[] site = unit(MoonEphemeris.siteEcliptic(sp.siteLat, sp.siteLon, 0, sp.tdb(sp.land)));
            double[] u1 = unit(add(zh, site, -dot(zh, site)));      // toward the orbit pole, ⟂ site
            double[] u2 = cross(site, u1);
            double c = Math.cos(x[2]), sn = Math.sin(x[2]);
            n = new double[]{-(c * u1[0] + sn * u2[0]), -(c * u1[1] + sn * u2[1]), -(c * u1[2] + sn * u2[2])};   // retrograde
            double[] eb = cross(n, site);
            p = new double[3];
            for (int i = 0; i < 3; i++) p[i] = Math.cos(x[1]) * site[i] + Math.sin(x[1]) * eb[i];
        } else {
            double phi = x[1], th = x[2];
            p = new double[3];
            for (int i = 0; i < 3; i++)
                p[i] = Math.cos(th) * Math.cos(phi) * xh[i] + Math.cos(th) * Math.sin(phi) * yh[i] + Math.sin(th) * zh[i];
            n = unit(add(zh, p, -dot(zh, p)));
            n = new double[]{-n[0], -n[1], -n[2]};                    // retrograde
            if (x.length > 3) n = rotate(n, p, x[3]);                  // plane turned about the periapsis line
        }
        double[] vh = cross(n, p);
        double r = R_M + sp.periAlt;
        return state(add(mr, p, r), add(mv, vh, x[0]));
    }

    /** TLI residuals for a pericynthion parameter set: (Δr/10 km, Δv/10 m/s, Δγ/0.1°). */
    static double[] tliResidual(Spec sp, double[] x) {
        double[] y = propagate(sp.tdb(sp.peri), periState(sp, x), sp.tdb(sp.tli), null);
        double r = norm(pos(y)), v = norm(vel(y));
        double g = Math.asin(dot(pos(y), vel(y)) / (r * v));
        double[] f = {(r - R_E - sp.tliAlt) / 10, (v - sp.tliV) / 0.01, (g - sp.tliGamma) / (0.1 * DEG)};
        if (x.length < 4) return f;
        return new double[]{f[0], f[1], f[2], (inclination(y) - sp.tliIncl) / (0.1 * DEG)};
    }

    /** Inclination (rad) of a geocentric state's orbit to the Earth's equator. */
    static double inclination(double[] y) {
        return Math.acos(Math.max(-1, Math.min(1, dot(unit(cross(pos(y), vel(y))), POLE_E))));
    }

    /**
     * Smooth arrival residual: integrate back from pericynthion until 20 000 km from the Earth
     * and compare that leg's conic perigee (radius, speed, passage time) with the perigee of the
     * documented TLI state's conic.
     */
    static double[] tliConicResidual(Spec sp, double[] x, double[] target) {
        double tp = sp.tdb(sp.peri);
        double[] s = propagateToRadius(tp, periState(sp, x), sp.tdb(sp.tli) - 86400, 20000);
        if (Double.isNaN(s[1])) return new double[]{1e4, 1e4, 1e4};
        double[] ys = {s[1], s[2], s[3], s[4], s[5], s[6]};
        double[] c = conic(s[0], ys);
        double[] f = {(c[0] - target[0]) / 10, (c[1] - target[1]) / 0.01, (c[2] - target[2]) / 600};
        if (x.length < 4) return f;
        return new double[]{f[0], f[1], f[2], (inclination(ys) - sp.tliIncl) / (0.2 * DEG)};
    }

    /** Conic perigee {rp, vp, t_perigee} of the documented TLI state (r, v, gamma at the TLI instant). */
    static double[] tliTarget(Spec sp) {
        double r = R_E + sp.tliAlt, v = sp.tliV, g = sp.tliGamma;
        double[] y = {r, 0, 0, v * Math.sin(g), v * Math.cos(g), 0};
        return conic(sp.tdb(sp.tli), y);
    }

    static double[] solveArrival(Spec sp) {
        double[] target = tliTarget(sp);
        double saved0 = rtol;
        rtol = 1e-9;
        boolean landing = sp.undock > 0;
        // Coarse grid on the smooth conic residual, then Levenberg–Marquardt on it, then on the
        // exact TLI residual. Several of the best grid cells are tried, since the residual has
        // more than one valley.
        List<double[]> cells = new ArrayList<>();
        double a0 = landing ? 90 : -120, a1 = landing ? 270 : 120, b0 = landing ? -60 : -45, b1 = -b0;
        for (double vp = 2.30; vp <= 2.751; vp += 0.025)
            for (double a = a0; a <= a1; a += 7.5)
                for (double b = b0; b <= b1; b += 7.5) {
                    double[] x = {vp, a * DEG, b * DEG};
                    double n = norm2(tliConicResidual(sp, x, target));
                    if (Double.isFinite(n)) cells.add(new double[]{n, x[0], x[1], x[2]});
                }
        cells.sort(Comparator.comparingDouble(c -> c[0]));
        double[] best = null;
        double bestN = Double.MAX_VALUE;
        for (int k = 0; k < Math.min(6, cells.size()); k++) {
            double[] x = {cells.get(k)[1], cells.get(k)[2], cells.get(k)[3]};
            x = newton(q -> tliConicResidual(sp, q, target), x, new double[]{1e-5, 1e-5, 1e-5}, 60, 1e-3);
            double n = norm2(tliConicResidual(sp, x, target));
            System.out.printf("   start %d (|F| %.1f): conic |F| -> %.3f at vp=%.4f %.2f° %.2f°%n",
                    k, cells.get(k)[0], n, x[0], x[1] / DEG, x[2] / DEG);
            if (n < bestN) { bestN = n; best = x; }
            if (n < 10) break;
        }
        if (!landing) {
            // Turn the arrival plane until the outbound orbit has the documented inclination.
            double[] x4 = {best[0], best[1], best[2], 0};
            x4 = newton(q -> tliConicResidual(sp, q, target), x4, new double[]{1e-5, 1e-5, 1e-5, 1e-5}, 60, 1e-3);
            System.out.printf("   with TLI inclination: conic |F| %.3f, plane turned %.2f°%n",
                    norm2(tliConicResidual(sp, x4, target)), x4[3] / DEG);
            best = x4;
        }
        rtol = saved0;
        return polishArrival(sp, best);
    }

    /** Exact TLI match (radius, speed, flight-path angle at the TLI instant). */
    static double[] polishArrival(Spec sp, double[] start) {
        double[] dx = start.length > 3 ? new double[]{1e-6, 1e-7, 1e-7, 1e-7} : new double[]{1e-6, 1e-7, 1e-7};
        double[] x = newton(q -> tliResidual(sp, q), start, dx, 30, 1e-4);
        double[] f = tliResidual(sp, x);
        System.out.printf("   arrival solved: vp=%.4f %.3f° %.3f°  residual %s%n",
                x[0], x[1] / DEG, x[2] / DEG, Arrays.toString(f));
        return x;
    }

    // ------------------------------------------------------------------ return to Earth

    /** Residual at the entry-interface time: (Δr/1 km, Δγ/0.01°). */
    static double[] entryResidual(double t0, double[] y0, double[] dv, double tEI) {
        double[] y = y0.clone();
        for (int i = 0; i < 3; i++) y[3 + i] += dv[i];
        double[] e = propagate(t0, y, tEI, null);
        double r = norm(pos(e)), v = norm(vel(e));
        double g = Math.asin(dot(pos(e), vel(e)) / (r * v));
        return new double[]{(r - R_E - H_EI), (g - GAMMA_EI) / (0.01 * DEG)};
    }

    /** Local orbit-frame basis (selenocentric): {radial, along-track, normal}. */
    static double[][] lvlh(double t, double[] y) {
        double[] s = seleno(t, y);
        double[] r = unit(pos(s)), n = unit(cross(pos(s), vel(s))), a = cross(n, r);
        return new double[][]{r, a, n};
    }

    /**
     * Smooth return residual: fly in to 30 000 km and compare the conic with the one that would
     * meet the entry interface at -6.5 deg (same energy) at exactly tEI: (perigee radius error in
     * km, entry-time error in minutes).
     */
    static double[] returnConicResidual(double t0, double[] y0, double[] dv, double tEI) {
        double[] y = y0.clone();
        for (int i = 0; i < 3; i++) y[3 + i] += dv[i];
        // A burn too weak to leave the Moon is no return at all: check after six hours rather
        // than integrating days of lunar orbit.
        double[] y6 = propagate(t0, y, t0 + 6 * 3600, null);
        if (Double.isNaN(y6[0]) || norm(pos(seleno(t0 + 6 * 3600, y6))) < 20000) return new double[]{1e5, 1e5};
        double[] s = propagateToRadius(t0 + 6 * 3600, y6, tEI + 86400, 30000);
        if (Double.isNaN(s[1])) return new double[]{1e5, 1e5};
        double[] ys = {s[1], s[2], s[3], s[4], s[5], s[6]};
        double[] c = conic(s[0], ys);
        double rr = norm(pos(ys)), vv = norm(vel(ys));
        double en = vv * vv / 2 - MU_E / rr;
        double rEI = R_E + H_EI;
        double vEI = Math.sqrt(2 * (en + MU_E / rEI));
        double h = rEI * vEI * Math.cos(GAMMA_EI);
        double e = Math.sqrt(Math.max(0, 1 + 2 * en * h * h / (MU_E * MU_E)));
        double rpReq = h * h / (MU_E * (1 + e));
        double nuEI = -Math.acos(Math.max(-1, Math.min(1, (h * h / (MU_E * rEI) - 1) / e)));
        double tauReq = -timeFromPerigee(e, rpReq, nuEI);      // EI -> perigee
        return new double[]{(c[0] - rpReq), (c[2] - tauReq - tEI) / 60};
    }

    /** Δv (geocentric vector) at (t0, y0) that reaches the entry interface at tEI. */
    static double[] solveReturn(double t0, double[] y0, double tEI) { return solveReturn(t0, y0, tEI, true); }

    /** As above; {@code polish} = false stops after the (fast, smooth) conic solve, for searches. */
    static double[] solveReturn(double t0, double[] y0, double tEI, boolean polish) {
        double saved = rtol;
        rtol = 1e-9;
        double[][] B = lvlh(t0, y0);
        // Seed: prograde only, scanning for the magnitude whose arrival time is closest.
        double bestDv = 1.0, bestErr = Double.MAX_VALUE;
        for (double dv = 0.4; dv <= 1.61; dv += 0.1) {
            double[] f = returnConicResidual(t0, y0, comb(B, new double[]{0, dv, 0}), tEI);
            if (Math.abs(f[1]) < bestErr) { bestErr = Math.abs(f[1]); bestDv = dv; }
        }
        for (double dv = bestDv - 0.09; dv <= bestDv + 0.091; dv += 0.015) {
            double[] f = returnConicResidual(t0, y0, comb(B, new double[]{0, dv, 0}), tEI);
            if (Math.abs(f[1]) < bestErr) { bestErr = Math.abs(f[1]); bestDv = dv; }
        }
        final double[][] Bf = B;
        double[] x = newton(q -> returnConicResidual(t0, y0, comb(Bf, q), tEI),
                new double[]{0, bestDv, 0}, new double[]{1e-6, 1e-6, 1e-6}, 25, 1e-2);
        rtol = saved;
        if (!polish) return comb(B, x);
        Function<double[], double[]> F = q -> entryResidual(t0, y0, comb(Bf, q), tEI);
        x = newton(F, x, new double[]{1e-7, 1e-7, 1e-7}, 20, 1e-4);
        return comb(B, x);
    }

    static double[] comb(double[][] B, double[] q) {
        double[] v = new double[3];
        for (int k = 0; k < 3; k++) for (int i = 0; i < 3; i++) v[i] += q[k] * B[k][i];
        return v;
    }

    // ------------------------------------------------------------------ a whole flight

    /** The reconstructed flight: CSM samples, optional LM samples, and derived facts. */
    static final class Flight {
        final List<double[]> csm = new ArrayList<>(), lm = new ArrayList<>();
        final Map<String, Double> facts = new LinkedHashMap<>();
    }

    /** Integrate one leg, appending records (dropping the duplicate first row). */
    static double[] leg(List<double[]> rec, double t0, double[] y0, double t1) {
        List<double[]> r = new ArrayList<>();
        double[] y = propagate(t0, y0, t1, r);
        if (!rec.isEmpty() && !r.isEmpty() && Math.abs(rec.get(rec.size() - 1)[0] - r.get(0)[0]) < 1e-6) r.remove(0);
        rec.addAll(r);
        return y;
    }

    /** Record an impulse: the post-burn state is stamped 1 s after the pre-burn one. */
    static double[] impulse(List<double[]> rec, double t, double[] post) {
        double[] p = post.clone();
        for (int i = 0; i < 3; i++) p[i] += p[3 + i] * 1.0;
        rec.add(row(t + 1.0, p));
        return p;
    }

    static double stateAt(List<double[]> rec, double t, double[] out) {
        int lo = 0, hi = rec.size() - 1;
        while (hi - lo > 1) { int mid = (lo + hi) >>> 1; if (rec.get(mid)[0] <= t) lo = mid; else hi = mid; }
        double[] a = rec.get(lo), b = rec.get(hi);
        if (b[0] - a[0] < 1e-9) { System.arraycopy(a, 1, out, 0, 6); return t; }
        SpacecraftTrack.hermite(a[0], a, 1, b[0], b, 1, t, out);
        return t;
    }

    static Flight build(Spec sp) {
        Flight F = new Flight();
        System.out.println("== " + sp.id);
        double[] x = solveArrival(sp);
        if (sp.id.equals("apollo13")) {
            // Shift the pericynthion until the computed Earth occultation is centred on the documented one.
            for (int it = 0; it < 3; it++) {
                double[] occ = occultation(sp, periState(sp, x));
                double want = 0.5 * (sp.tdb(get(77, 8, 35)) + sp.tdb(get(77, 33, 10)));
                double shift = want - 0.5 * (occ[0] + occ[1]);
                System.out.printf("   occultation %s..%s, shifting pericynthion %.0f s%n",
                        fmt(sp, occ[0]), fmt(sp, occ[1]), shift);
                if (Math.abs(shift) < 5) break;
                sp.peri += shift;
                x = solveArrival(sp);
            }
        }
        double tp = sp.tdb(sp.peri);
        double[] yp = periState(sp, x);

        // --- Earth parking orbit and TLI burn (backwards from the TLI state) ---
        List<double[]> outbound = new ArrayList<>();
        double tTli = sp.tdb(sp.tli);
        double[] yTli = propagate(tp, yp, tTli, outbound);
        Collections.reverse(outbound);
        double[] nT = unit(cross(pos(yTli), vel(yTli)));
        double rPark = R_E + sp.parkAlt;
        double vPark = Math.sqrt(MU_E / rPark);
        double rT = norm(pos(yTli));
        double sweep = 0.5 * (vPark / rPark + norm(vel(yTli)) * Math.cos(sp.tliGamma) / rT) * (sp.tliDur + 10);
        double[] pIgn = rotate(unit(pos(yTli)), nT, -sweep);
        double[] vIgn = cross(nT, pIgn);
        double[] yIgn = state(new double[]{pIgn[0] * rPark, pIgn[1] * rPark, pIgn[2] * rPark},
                new double[]{vIgn[0] * vPark, vIgn[1] * vPark, vIgn[2] * vPark});
        double tIgn = tTli - sp.tliDur - 10;
        List<double[]> park = new ArrayList<>();
        propagate(tIgn, yIgn, sp.tdb(sp.eoi), park);
        Collections.reverse(park);
        F.csm.addAll(park);
        F.csm.addAll(outbound);
        F.facts.put("tliInclinationDeg", Math.acos(dot(nT, POLE_E)) / DEG);

        // --- pericynthion onwards ---
        double[] y = yp;
        double t = tp;
        F.facts.put("periAltKm", sp.periAlt);
        if (sp.loiApo > 0) {
            y = setApsides(t, y, sp.loiApo, sp.loiPeri);
            y = impulse(F.csm, t, y);
            t += 1;
        }
        if (sp.undock > 0) buildLunarOrbitWithLanding(sp, F, t, y);
        else {
            // Lunar orbit (Apollo 8) or flyby (Apollo 13) through to the return burn.
            for (double[] b : sp.csmBurns) {
                y = leg(F.csm, t, y, sp.tdb(b[0]));
                t = sp.tdb(b[0]);
                y = impulse(F.csm, t, setApsides(t, y, b[1], b[2]));
                t += 1;
            }
            returnHome(sp, F, t, y);
        }
        return F;
    }

    /** CSM lunar orbit with the LM's undocking, phasing trim, landing, ascent and rendezvous. */
    static void buildLunarOrbitWithLanding(Spec sp, Flight F, double t0, double[] y0) {
        // Trim the phasing burn so the LM reaches the powered-descent point on time.
        double[] site0 = unit(MoonEphemeris.siteEcliptic(sp.siteLat, sp.siteLon, 0, sp.tdb(sp.land)));
        Function<Double, Double> phaseErr = d -> {
            double[] lm = lmAtPdi(sp, t0, y0, d, null, null);
            double tP = sp.tdb(sp.pdi);
            double[] s = seleno(tP, lm);
            double[] n = unit(cross(pos(s), vel(s)));
            double[] target = rotate(site0, n, -sp.descentArcKm / R_M);
            double[] p = unit(add(pos(s), n, -dot(pos(s), n)));
            double ang = Math.atan2(dot(cross(target, p), n), dot(target, p));
            return ang;   // >0: LM is ahead of the PDI point
        };
        double saved = rtol;
        rtol = 1e-9;
        double d0 = 0, e0 = phaseErr.apply(d0);
        double d1 = 5, e1 = phaseErr.apply(d1);
        for (int it = 0; it < 20 && Math.abs(e1) > 1e-5; it++) {
            double d2 = d1 - e1 * (d1 - d0) / (e1 - e0);
            d2 = Math.max(-45, Math.min(45, d2));
            d0 = d1; e0 = e1; d1 = d2; e1 = phaseErr.apply(d1);
        }
        rtol = saved;
        double trim = d1;
        System.out.printf("   phasing trim on burn %d: %+.2f km  (residual %.3f°, untrimmed %.1f°)%n",
                sp.phaseBurn, trim, Math.toDegrees(e1), Math.toDegrees(phaseErr.apply(0.0)));
        F.facts.put("phasingTrimKm", trim);

        List<double[]> lmRec = new ArrayList<>();
        double[] csmAtUndock = new double[6];
        double[] lmPdi = lmAtPdi(sp, t0, y0, trim, F.csm, lmRec);
        // F.csm now runs to undocking; continue the CSM alone.
        double tU = sp.tdb(sp.undock);
        stateAt(F.csm, tU, csmAtUndock);
        double t = tU;
        double[] y = csmAtUndock;
        for (int b = 0; b < sp.csmBurns.size(); b++) {
            double tb = sp.tdb(sp.csmBurns.get(b)[0]);
            if (tb <= tU) continue;
            y = leg(F.csm, t, y, tb);
            t = tb;
            y = impulse(F.csm, t, setApsides(t, y, sp.csmBurns.get(b)[1], sp.csmBurns.get(b)[2]));
            t += 1;
        }
        // CSM coasts through the surface stay and the rendezvous.
        double tDock = sp.tdb(sp.docked);
        y = leg(F.csm, t, y, tDock);
        t = tDock;

        // --- LM: powered descent, surface stay, ascent, rendezvous ---
        double tP = sp.tdb(sp.pdi), tL = sp.tdb(sp.land);
        descent(sp, lmRec, tP, lmPdi, tL);
        double tLift = sp.tdb(sp.liftoff), tIns = sp.tdb(sp.insertion);
        for (double ts = tL + 600; ts < tLift; ts += 600) lmRec.add(surfaceRow(sp, ts));
        lmRec.add(surfaceRow(sp, tLift));
        double[] csmIns = new double[6];
        stateAt(F.csm, tIns, csmIns);
        double[] lmIns = ascent(sp, lmRec, tLift, tIns, csmIns);
        double tTpi = sp.tdb(sp.tpi), tTpf = sp.tdb(sp.tpf);
        double[] lmTpi = leg(lmRec, tIns, lmIns, tTpi);
        // Two-impulse Lambert rendezvous, corrected for the perturbations the two-body solution ignores.
        double[] csmTpf = new double[6];
        stateAt(F.csm, tTpf, csmTpf);
        double[] target = pos(seleno(tTpf, csmTpf));
        double[] lmS = seleno(tTpi, lmTpi);
        double[] csmTpi = new double[6];
        stateAt(F.csm, tTpi, csmTpi);
        double[] hRef = cross(pos(seleno(tTpi, csmTpi)), vel(seleno(tTpi, csmTpi)));
        double[] post = null;
        for (int it = 0; it < 4; it++) {
            double[] v = Lambert.solve(pos(lmS), target, tTpf - tTpi, MU_M, hRef);
            post = geo(tTpi, state(pos(lmS), new double[]{v[0], v[1], v[2]}));
            double[] arr = propagate(tTpi, post, tTpf, null);
            double[] miss = sub3(pos(seleno(tTpf, arr)), pos(seleno(tTpf, csmTpf)));
            target = sub3(target, miss);
        }
        double dvTpi = norm(sub3(vel(post), vel(lmTpi)));
        F.facts.put("tpiDvMps", dvTpi * 1000);
        double[] postI = impulse(lmRec, tTpi, post);
        double[] arr = leg(lmRec, tTpi + 1, postI, tTpf);
        F.facts.put("rendezvousMissKm", norm(sub3(pos(arr), pos(csmTpf))));
        // Station-keeping with the CSM until docking.
        for (double ts = tTpf + 1; ts <= tDock; ts += 60) {
            double[] c = new double[6];
            stateAt(F.csm, ts, c);
            lmRec.add(row(ts, c));
        }
        F.lm.addAll(lmRec);
        returnHome(sp, F, t, y);
    }

    /**
     * From the post-LOI state, fly the CSM/LM stack with the documented burns (the phasing burn
     * trimmed by {@code trim} km) to undocking, then the LM alone through its descent-orbit
     * insertion to PDI. Records into csmRec/lmRec when non-null. Returns the LM state at PDI.
     */
    static double[] lmAtPdi(Spec sp, double t0, double[] y0, double trim, List<double[]> csmRec, List<double[]> lmRec) {
        double t = t0;
        double[] y = y0;
        double tU = sp.tdb(sp.undock);
        for (int b = 0; b < sp.csmBurns.size(); b++) {
            double tb = sp.tdb(sp.csmBurns.get(b)[0]);
            if (tb > tU) break;
            y = csmRec != null ? leg(csmRec, t, y, tb) : propagate(t, y, tb, null);
            t = tb;
            double apo = sp.csmBurns.get(b)[1], peri = sp.csmBurns.get(b)[2];
            if (b == sp.phaseBurn) { apo += trim; if (sp.phaseBothApsides) peri += trim; }
            double[] post = setApsides(t, y, apo, peri);
            y = csmRec != null ? impulse(csmRec, t, post) : post;
            t += csmRec != null ? 1 : 0;
        }
        y = csmRec != null ? leg(csmRec, t, y, tU) : propagate(t, y, tU, null);
        t = tU;
        if (lmRec != null) lmRec.add(row(t, y));
        double tD = sp.tdb(sp.lmDoi);
        y = lmRec != null ? leg(lmRec, t, y, tD) : propagate(t, y, tD, null);
        double[] ap = apsides(tD, y);
        double[] post = setApsides(tD, y, ap[0], sp.lmDoiPeri);
        y = lmRec != null ? impulse(lmRec, tD, post) : post;
        double tStart = lmRec != null ? tD + 1 : tD;
        double tP = sp.tdb(sp.pdi);
        return lmRec != null ? leg(lmRec, tStart, y, tP) : propagate(tStart, y, tP, null);
    }

    // ------------------------------------------------------------------ surface operations

    /** Body-fixed → selenocentric ecliptic rotation at TDB t. */
    static double[] bodyM(double t) { return MoonEphemeris.bodyToEcliptic(t); }

    static double[] toBody(double t, double[] v) {
        double[] M = bodyM(t);   // transpose apply
        return new double[]{M[0] * v[0] + M[3] * v[1] + M[6] * v[2],
                M[1] * v[0] + M[4] * v[1] + M[7] * v[2],
                M[2] * v[0] + M[5] * v[1] + M[8] * v[2]};
    }

    static double[] fromBody(double t, double[] v) { return Astro.apply(bodyM(t), v[0], v[1], v[2]); }

    /** Geocentric state of a body-fixed path point, velocity by central difference. */
    static double[] fromBodyPath(Function<Double, double[]> bodyPos, double t) {
        double dt = 0.5;
        double[] a = add(moon(t - dt), fromBody(t - dt, bodyPos.apply(t - dt)), 1);
        double[] b = add(moon(t + dt), fromBody(t + dt, bodyPos.apply(t + dt)), 1);
        double[] c = add(moon(t), fromBody(t, bodyPos.apply(t)), 1);
        return state(c, new double[]{(b[0] - a[0]) / (2 * dt), (b[1] - a[1]) / (2 * dt), (b[2] - a[2]) / (2 * dt)});
    }

    static double[] siteBody(Spec sp, double h) {
        double la = sp.siteLat * DEG, lo = sp.siteLon * DEG, r = R_M + h;
        return new double[]{r * Math.cos(la) * Math.cos(lo), r * Math.cos(la) * Math.sin(lo), r * Math.sin(la)};
    }

    static double[] surfaceRow(Spec sp, double t) {
        return row(t, fromBodyPath(q -> siteBody(sp, 0), t));
    }

    /**
     * A polynomial guidance path in the Moon-fixed frame between two points on a great circle:
     * along-track angle and altitude are cubics in time matching the end positions and rates
     * (Apollo's descent and ascent guidance both commanded polynomial trajectories in time).
     * {@code e0}/{@code e1} are unit body-fixed directions, {@code h0}/{@code h1} altitudes,
     * {@code w0}/{@code w1} along-track angular rates, {@code hd0}/{@code hd1} vertical rates.
     */
    static Function<Double, double[]> guidancePath(double tA, double tB, double[] e0, double[] e1,
                                                   double h0, double h1, double w0, double w1, double hd0, double hd1) {
        double T = tB - tA;
        double[] n = unit(cross(e0, e1));
        double Psi = Math.acos(Math.max(-1, Math.min(1, dot(e0, e1))));
        return t -> {
            double u = Math.max(0, Math.min(1, (t - tA) / T));
            double u2 = u * u, u3 = u2 * u;
            double h00 = 2 * u3 - 3 * u2 + 1, h10 = u3 - 2 * u2 + u, h01 = -2 * u3 + 3 * u2, h11 = u3 - u2;
            double psi = h10 * T * w0 + h01 * Psi + h11 * T * w1;
            double h = h00 * h0 + h10 * T * hd0 + h01 * h1 + h11 * T * hd1;
            double[] d = rotate(e0, n, psi);
            double r = R_M + h;
            return new double[]{d[0] * r, d[1] * r, d[2] * r};
        };
    }

    /** Powered descent from the LM's PDI state to the site (documented times). */
    static void descent(Spec sp, List<double[]> rec, double tP, double[] lmPdi, double tL) {
        double[] s = seleno(tP, lmPdi);
        double[] pb = toBody(tP, pos(s));
        // Body-fixed velocity: inertial velocity minus the Moon's rotation (finite difference of the frame).
        double[] pbNext = toBody(tP + 1, add(pos(s), vel(s), 1));
        double[] vb = sub3(pbNext, pb);
        double[] e0 = unit(pb), e1 = unit(siteBody(sp, 0));
        double r0 = norm(pb);
        double[] nrm = unit(cross(e0, e1));
        double[] along = cross(nrm, e0);
        double w0 = dot(vb, along) / r0, hd0 = dot(vb, e0);
        Function<Double, double[]> path = guidancePath(tP, tL, e0, e1, r0 - R_M, 0, w0, 0, hd0, 0);
        for (double ts = tP; ts < tL; ts += 10) rec.add(row(ts, fromBodyPath(path, ts)));
        rec.add(surfaceRow(sp, tL));
    }

    /** Ascent from the site into the documented insertion orbit, heading along the CSM's orbit. Returns the LM state at insertion. */
    static double[] ascent(Spec sp, List<double[]> rec, double tLift, double tIns, double[] csmIns) {
        double[] siteB = siteBody(sp, 0);
        double[] e0 = unit(siteB);
        double[] cs = seleno(tIns, csmIns);
        double[] nB = unit(toBody(tIns, cross(pos(cs), vel(cs))));    // CSM orbit normal, body frame
        double[] dir = unit(cross(nB, e0));                          // downrange along the CSM's motion
        double rIns = R_M + sp.insPeri;
        double vIns = Math.sqrt(MU_M * (2 / rIns - 2 / (2 * R_M + sp.insApo + sp.insPeri)));
        double T = tIns - tLift;
        double wEnd = (vIns - 0.0046) / rIns;                        // body-fixed rate (less the ~4.6 m/s surface speed)
        double Psi = 0.45 * wEnd * T;
        double[] axis = unit(cross(e0, dir));
        double[] e1 = rotate(e0, axis, Psi);
        Function<Double, double[]> path = guidancePath(tLift, tIns, e0, e1, 0, sp.insPeri, 0, wEnd, 0, 0);
        for (double ts = tLift + 10; ts < tIns; ts += 10) rec.add(row(ts, fromBodyPath(path, ts)));
        double[] ins = fromBodyPath(path, tIns);
        rec.add(row(tIns, ins));
        return ins;
    }

    // ------------------------------------------------------------------ transearth

    static void returnHome(Spec sp, Flight F, double t, double[] y) {
        double tEI = sp.tdb(sp.ei);
        double tTei = sp.tdb(sp.tei);
        if (sp.optimiseTei) {
            // TEI happens behind the Moon at the point of the orbit that needs least Δv; search a
            // revolution around the documented time for it.
            double bestT = tTei, bestDv = Double.MAX_VALUE;
            double saved = rtol;
            rtol = 1e-9;
            double[] yb = propagate(t, y, tTei - 3600, null);
            double tb = tTei - 3600;
            for (double tc = tTei - 3600; tc <= tTei + 3600; tc += 300) {
                yb = propagate(tb, yb, tc, null);
                tb = tc;
                double[] dv = solveReturn(tc, yb, tEI, false);
                double[] chk = returnConicResidual(tc, yb, dv, tEI);
                double m = norm(dv);
                if (Math.abs(chk[0]) < 5 && Math.abs(chk[1]) < 1 && m < bestDv) { bestDv = m; bestT = tc; }
            }
            // Refine around the best coarse time.
            for (double step = 100; step >= 10; step /= 3) {
                double c = bestT;
                for (double tc = c - 2 * step; tc <= c + 2 * step; tc += step) {
                    double[] yc = propagate(t, y, tc, null);
                    double[] dv = solveReturn(tc, yc, tEI, false);
                    double[] chk = returnConicResidual(tc, yc, dv, tEI);
                    double m = norm(dv);
                    if (Math.abs(chk[0]) < 5 && Math.abs(chk[1]) < 1 && m < bestDv) { bestDv = m; bestT = tc; }
                }
            }
            rtol = saved;
            System.out.printf("   TEI at %s (documented %s), Δv %.0f m/s%n",
                    fmt(sp, bestT), fmt(sp, tTei), bestDv * 1000);
            F.facts.put("teiShiftMin", (bestT - tTei) / 60);
            tTei = bestT;
        }
        y = leg(F.csm, t, y, tTei);
        double[] dv = solveReturn(tTei, y, tEI);
        F.facts.put("returnDvMps", norm(dv) * 1000);
        double[] post = y.clone();
        for (int i = 0; i < 3; i++) post[3 + i] += dv[i];
        post = impulse(F.csm, tTei, post);
        double[] e = leg(F.csm, tTei + 1, post, tEI);
        double r = norm(pos(e)), v = norm(vel(e));
        F.facts.put("entryAltKm", r - R_E);
        F.facts.put("entryGammaDeg", Math.asin(dot(pos(e), vel(e)) / (r * v)) / DEG);
        F.facts.put("entrySpeedKms", v);
    }

    // ------------------------------------------------------------------ diagnostics

    /** Earth-occultation interval (TDB) around pericynthion: when the Moon's limb hides the Earth's centre. */
    static double[] occultation(Spec sp, double[] yp) {
        double tp = sp.tdb(sp.peri);
        List<double[]> rec = new ArrayList<>();
        propagate(tp, yp, tp - 3600, rec);
        Collections.reverse(rec);
        propagate(tp, yp, tp + 3600, rec);
        double t0 = Double.NaN, t1 = Double.NaN;
        boolean prev = false;
        for (double[] s : rec) {
            boolean hid = earthHidden(s[0], new double[]{s[1], s[2], s[3]});
            if (hid && !prev && Double.isNaN(t0)) t0 = s[0];
            if (!hid && prev) t1 = s[0];
            prev = hid;
        }
        return new double[]{t0, t1};
    }

    /** True when the line from the craft to the Earth's centre passes through the Moon. */
    static boolean earthHidden(double t, double[] p) {
        double[] m = moon(t);
        double[] d = sub3(new double[]{0, 0, 0}, p);   // craft → Earth
        double[] w = sub3(m, p);                       // craft → Moon
        double L = norm(d);
        double proj = dot(w, d) / L;
        if (proj <= 0 || proj >= L) return false;
        double perp2 = dot(w, w) - proj * proj;
        return perp2 < R_M * R_M;
    }

    static String fmt(Spec sp, double tdb) {
        double getS = tdb - sp.tdb(0);
        int h = (int) (getS / 3600), m = (int) ((getS - h * 3600) / 60);
        double s = getS - h * 3600 - m * 60;
        return String.format("%03d:%02d:%04.1f", h, m, s);
    }

    // ------------------------------------------------------------------ output

    /** Split a geocentric record into an Earth base segment and Moon-local segments, thinned. */
    static SpacecraftTrack toTrack(String id, List<double[]> rec) {
        rec.sort(Comparator.comparingDouble(a -> a[0]));
        // Drop exact duplicate times.
        List<double[]> clean = new ArrayList<>();
        for (double[] r : rec) if (clean.isEmpty() || r[0] > clean.get(clean.size() - 1)[0] + 1e-6) clean.add(r);
        List<SpacecraftTrack.Segment> segs = new ArrayList<>();
        segs.add(SpacecraftTrack.thin("Earth", clean));
        List<double[]> local = new ArrayList<>();
        for (double[] r : clean) {
            double[] s = seleno(r[0], new double[]{r[1], r[2], r[3], r[4], r[5], r[6]});
            if (norm(pos(s)) < 1.0e5) {
                local.add(new double[]{r[0], s[0], s[1], s[2], s[3], s[4], s[5]});
            } else {
                if (local.size() >= 2) segs.add(SpacecraftTrack.thin("Moon", local));
                local = new ArrayList<>();
            }
        }
        if (local.size() >= 2) segs.add(SpacecraftTrack.thin("Moon", local));
        return new SpacecraftTrack(id, segs);
    }

    public static void main(String[] args) throws Exception {
        List<String> ids = args.length > 0 ? Arrays.asList(args) : List.of("apollo8", "apollo11", "apollo13", "apollo17");
        Path dir = Paths.get("assets", "missions");
        Files.createDirectories(dir);
        for (String id : ids) {
            long t0 = System.currentTimeMillis();
            Spec sp = spec(id);
            Flight f = build(sp);
            toTrack(id, f.csm).write(dir.resolve(id + ".trk.gz"));
            if (!f.lm.isEmpty()) toTrack(sp.lmId, f.lm).write(dir.resolve(sp.lmId + ".trk.gz"));
            System.out.println("   facts: " + f.facts);
            System.out.printf("   built in %.1f s%n", (System.currentTimeMillis() - t0) / 1000.0);
        }
    }
}
