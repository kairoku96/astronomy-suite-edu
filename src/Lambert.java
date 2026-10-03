/**
 * Lambert's problem: the two-body orbit that carries a body from position r1 to position r2 in
 * a given time of flight. Solved with universal variables (Bate, Mueller &amp; White §5.3;
 * Curtis, <i>Orbital Mechanics for Engineering Students</i>, Algorithm 5.2), single revolution,
 * with a bracketed Newton/bisection iteration on the universal anomaly parameter z so it
 * converges for elliptic, parabolic and hyperbolic transfers alike.
 *
 * <p>Units are whatever {@code mu} is given in (the planner uses AU and days, the rendezvous
 * code km and seconds). Returns {@code null} when no single-revolution solution exists (e.g. a
 * transfer angle of exactly 180°, where the plane is undefined).
 */
final class Lambert {
    private Lambert() {}

    /**
     * @param r1   departure position
     * @param r2   arrival position
     * @param tof  time of flight (&gt; 0)
     * @param mu   gravitational parameter of the central body
     * @param hRef reference angular-momentum direction: the transfer goes the "short" or "long"
     *             way round so that its angular momentum points along {@code hRef} (prograde
     *             transfers pass the ecliptic north pole here)
     * @return {v1x, v1y, v1z, v2x, v2y, v2z}, or null
     */
    static double[] solve(double[] r1, double[] r2, double tof, double mu, double[] hRef) {
        double R1 = norm(r1), R2 = norm(r2);
        double[] c = cross(r1, r2);
        double cosd = dot(r1, r2) / (R1 * R2);
        cosd = Math.max(-1, Math.min(1, cosd));
        double dth = Math.acos(cosd);
        if (dot(c, hRef) < 0) dth = 2 * Math.PI - dth;
        double sind = Math.sin(dth);
        if (Math.abs(1 - cosd) < 1e-14) return null;
        double A = sind * Math.sqrt(R1 * R2 / (1 - cosd));
        if (Math.abs(A) < 1e-12) return null;
        double sqmuT = Math.sqrt(mu) * tof;

        // F(z) increases monotonically with z wherever y(z) > 0; bracket the root.
        double zHi = 4 * Math.PI * Math.PI * (1 - 1e-9);
        double zLo = -4 * Math.PI * Math.PI;
        while (y(zLo, R1, R2, A) < 0 && zLo < zHi) zLo += 0.1;   // smallest z with y ≥ 0 (A > 0 case)
        // Hyperbolic side: extend downward until F < 0.
        int guard = 0;
        while (F(zLo, R1, R2, A, sqmuT) > 0 && guard++ < 200) {
            double cand = zLo * 2 - 1;
            if (y(cand, R1, R2, A) < 0) break;
            zLo = cand;
        }
        if (F(zLo, R1, R2, A, sqmuT) > 0 || F(zHi, R1, R2, A, sqmuT) < 0) return null;
        double z = 0.5 * (zLo + zHi);
        for (int it = 0; it < 200; it++) {
            double f = F(z, R1, R2, A, sqmuT);
            if (Math.abs(f) < 1e-12 * Math.max(1, sqmuT)) break;
            if (f > 0) zHi = z; else zLo = z;
            // Newton step on the numerical derivative, kept inside the bracket.
            double dz = 1e-6 * Math.max(1, Math.abs(z));
            double fp = (F(z + dz, R1, R2, A, sqmuT) - F(z - dz, R1, R2, A, sqmuT)) / (2 * dz);
            double zn = (fp > 0 && Double.isFinite(fp)) ? z - f / fp : Double.NaN;
            z = (zn > zLo && zn < zHi) ? zn : 0.5 * (zLo + zHi);
            if (zHi - zLo < 1e-14 * Math.max(1, Math.abs(z))) break;
        }
        double yy = y(z, R1, R2, A);
        double f = 1 - yy / R1, g = A * Math.sqrt(yy / mu), gd = 1 - yy / R2;
        double[] v = new double[6];
        for (int k = 0; k < 3; k++) {
            v[k] = (r2[k] - f * r1[k]) / g;
            v[3 + k] = (gd * r2[k] - r1[k]) / g;
        }
        return v;
    }

    private static double y(double z, double r1, double r2, double A) {
        return r1 + r2 + A * (z * S(z) - 1) / Math.sqrt(C(z));
    }

    private static double F(double z, double r1, double r2, double A, double sqmuT) {
        double yy = y(z, r1, r2, A);
        if (yy < 0) return -sqmuT;
        double cz = C(z);
        return Math.pow(yy / cz, 1.5) * S(z) + A * Math.sqrt(yy) - sqmuT;
    }

    /** Stumpff function C(z), with its series near zero. */
    static double C(double z) {
        if (Math.abs(z) < 1e-3) return 0.5 - z / 24 + z * z / 720 - z * z * z / 40320;
        if (z > 0) return (1 - Math.cos(Math.sqrt(z))) / z;
        return (Math.cosh(Math.sqrt(-z)) - 1) / (-z);
    }

    /** Stumpff function S(z), with its series near zero. */
    static double S(double z) {
        if (Math.abs(z) < 1e-3) return 1.0 / 6 - z / 120 + z * z / 5040 - z * z * z / 362880;
        if (z > 0) { double s = Math.sqrt(z); return (s - Math.sin(s)) / (s * s * s); }
        double s = Math.sqrt(-z);
        return (Math.sinh(s) - s) / (s * s * s);
    }

    static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    static double norm(double[] a) { return Math.sqrt(dot(a, a)); }
    static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }
}
