/**
 * Shared astronomical time scales and reference-frame rotations.
 *
 * <p>The sim clock ({@code simTimeSec}) counts seconds of <b>UTC</b> since the J2000.0
 * instant (2000-01-01 12:00). Dynamics (the N-body integrator, the satellite theory, the
 * lunar theory) run on <b>TT/TDB</b>, so they take {@link #ttSeconds(double)} (exact from
 * leap seconds since 1972). Earth rotation (sidereal time) runs on UT, taken as UTC (they
 * differ by under 0.9 s). The frames are:
 * <ul>
 *   <li>ecliptic J2000 (x → vernal equinox, z → ecliptic north) — the frame every ephemeris
 *       here reports, and that JPL Horizons uses for its ecliptic vectors;</li>
 *   <li>equatorial J2000 (ICRF), reached by the J2000 obliquity rotation;</li>
 *   <li>true equator and equinox of date (precession IAU 1976 + the dominant nutation terms),
 *       which is what a horizon-based observer actually sees.</li>
 * </ul>
 */
final class Astro {
    private Astro() {}

    static final double DAY = 86_400.0;
    static final double AU_M = 1.495978707e11;
    static final double DEG = Math.PI / 180.0;
    static final double ARCSEC = DEG / 3600.0;
    /** Obliquity of the ecliptic at J2000 (IAU 1976), radians. */
    static final double EPS_J2000 = 23.43929111 * DEG;

    // ------------------------------------------------------------------ time scales

    /** Decimal year of a UTC sim time (only used to pick a ΔT segment). */
    static double decimalYear(double simTimeSec) {
        return 2000.0 + simTimeSec / (365.25 * DAY);
    }

    /**
     * ΔT = TT − UT1 (seconds), the NASA / Espenak &amp; Meeus piecewise polynomials
     * (Five Millennium Canon), valid −1999..+3000 with the long-term parabola outside that.
     */
    static double deltaTSeconds(double y) {
        double t, u;
        if (y < -500) { u = (y - 1820) / 100; return -20 + 32 * u * u; }
        if (y < 500) {
            u = y / 100;
            return 10583.6 + u * (-1014.41 + u * (33.78311 + u * (-5.952053
                    + u * (-0.1798452 + u * (0.022174192 + u * 0.0090316521)))));
        }
        if (y < 1600) {
            u = (y - 1000) / 100;
            return 1574.2 + u * (-556.01 + u * (71.23472 + u * (0.319781
                    + u * (-0.8503463 + u * (-0.005050998 + u * 0.0083572073)))));
        }
        if (y < 1700) { t = y - 1600; return 120 + t * (-0.9808 + t * (-0.01532 + t / 7129)); }
        if (y < 1800) { t = y - 1700; return 8.83 + t * (0.1603 + t * (-0.0059285 + t * (0.00013336 - t / 1174000))); }
        if (y < 1860) {
            t = y - 1800;
            return 13.72 + t * (-0.332447 + t * (0.0068612 + t * (0.0041116 + t * (-0.00037436
                    + t * (0.0000121272 + t * (-0.0000001699 + t * 0.000000000875))))));
        }
        if (y < 1900) { t = y - 1860; return 7.62 + t * (0.5737 + t * (-0.251754 + t * (0.01680668 + t * (-0.0004473624 + t / 233174)))); }
        if (y < 1920) { t = y - 1900; return -2.79 + t * (1.494119 + t * (-0.0598939 + t * (0.0061966 - t * 0.000197))); }
        if (y < 1941) { t = y - 1920; return 21.20 + t * (0.84493 + t * (-0.076100 + t * 0.0020936)); }
        if (y < 1961) { t = y - 1950; return 29.07 + t * (0.407 + t * (-1.0 / 233 + t / 2547)); }
        if (y < 1986) { t = y - 1975; return 45.45 + t * (1.067 + t * (-1.0 / 260 - t / 718)); }
        if (y < 2005) {
            t = y - 2000;
            return 63.86 + t * (0.3345 + t * (-0.060374 + t * (0.0017275 + t * (0.000651814 + t * 0.00002373599))));
        }
        if (y < 2050) { t = y - 2000; return 62.92 + t * (0.32217 + t * 0.005589); }
        u = (y - 1820) / 100;
        if (y < 2150) return -20 + 32 * u * u - 0.5628 * (2150 - y);
        return -20 + 32 * u * u;
    }

    /*
     * UTC → TT. Since 1972 this is exact: TT − UTC = 32.184 s + TAI − UTC, the accumulated leap
     * seconds (IERS Bulletin C). Before 1972 UTC did not tick in SI seconds; the clock is read
     * as UT and the ΔT polynomials above apply (they meet the table within 0.07 s). After the
     * last instant the table is known to hold, ΔT follows the polynomials' predicted rise from
     * there on, joined continuously, rather than the polynomial's own value: it was fitted in
     * 2006 and now overshoots by ~5 s, which moves the Moon ~5 km and an eclipse shadow with it.
     */
    private static final double TT_TAI = 32.184;
    /** UTC dates from which TAI − UTC took the next whole second: 10 s on 1972-01-01, …, 37 s on 2017-01-01. */
    private static final int[][] LEAP_DATES = {
        {1972, 1}, {1972, 7}, {1973, 1}, {1974, 1}, {1975, 1}, {1976, 1}, {1977, 1}, {1978, 1},
        {1979, 1}, {1980, 1}, {1981, 7}, {1982, 7}, {1983, 7}, {1985, 7}, {1988, 1}, {1990, 1},
        {1991, 1}, {1992, 7}, {1993, 7}, {1994, 7}, {1996, 1}, {1997, 7}, {1999, 1}, {2006, 1},
        {2009, 1}, {2012, 7}, {2015, 7}, {2017, 1} };
    /** Sim seconds (UTC since J2000) of each leap date. */
    private static final double[] LEAP_AT = new double[LEAP_DATES.length];
    /** No leap second announced through this date (IERS Bulletin C 72); extrapolate after. */
    private static final double KNOWN_UNTIL_YEAR = 2027.0;
    static {
        double j2000 = java.time.LocalDateTime.of(2000, 1, 1, 12, 0).toEpochSecond(java.time.ZoneOffset.UTC);
        for (int i = 0; i < LEAP_DATES.length; i++)
            LEAP_AT[i] = java.time.LocalDate.of(LEAP_DATES[i][0], LEAP_DATES[i][1], 1)
                    .atStartOfDay().toEpochSecond(java.time.ZoneOffset.UTC) - j2000;
    }

    /** TT − UTC (seconds) at a UTC sim time: leap seconds since 1972, ΔT outside the table. */
    static double ttMinusUtc(double simTimeSec) {
        if (simTimeSec < LEAP_AT[0]) return deltaTSeconds(decimalYear(simTimeSec));
        double y = decimalYear(simTimeSec);
        if (y > KNOWN_UNTIL_YEAR)
            return TT_TAI + 9 + LEAP_AT.length + deltaTSeconds(y) - deltaTSeconds(KNOWN_UNTIL_YEAR);
        int n = 0;
        while (n < LEAP_AT.length && simTimeSec >= LEAP_AT[n]) n++;
        return TT_TAI + 9 + n;                          // TAI − UTC = 10 s from 1972-01-01
    }

    /** TT (≈ TDB, to 2 ms) seconds since J2000.0 for a UTC sim time. */
    static double ttSeconds(double simTimeSec) {
        return simTimeSec + ttMinusUtc(simTimeSec);
    }

    /** UTC sim seconds for a TT (or TDB) instant: the inverse of {@link #ttSeconds}. */
    static double utcSeconds(double ttSec) {
        double u = ttSec - ttMinusUtc(ttSec);
        for (int i = 0; i < 3; i++) u = ttSec - ttMinusUtc(u);
        return u;
    }

    // ------------------------------------------------------------------ frames

    /** Ecliptic-J2000 vector → equatorial J2000 (ICRF). */
    static double[] eclToEq(double x, double y, double z) {
        double c = Math.cos(EPS_J2000), s = Math.sin(EPS_J2000);
        return new double[]{ x, c * y - s * z, s * y + c * z };
    }

    /** Equatorial J2000 (ICRF) vector → ecliptic J2000. */
    static double[] eqToEcl(double x, double y, double z) {
        double c = Math.cos(EPS_J2000), s = Math.sin(EPS_J2000);
        return new double[]{ x, c * y + s * z, -s * y + c * z };
    }

    /** Unit vector of right ascension / declination (radians). */
    static double[] radec(double ra, double dec) {
        double cd = Math.cos(dec);
        return new double[]{ cd * Math.cos(ra), cd * Math.sin(ra), Math.sin(dec) };
    }

    /**
     * Rotation matrix (row-major 3×3) taking an equatorial-J2000 vector to the true
     * equator and equinox of date: P (IAU 1976, Lieske) followed by N (the 1980 nutation
     * series truncated to its largest terms, ≲ 0.5″). {@code tt} = TT seconds since J2000.
     */
    static double[] j2000ToTrueOfDate(double tt) {
        double T = tt / (36525.0 * DAY);
        // Precession angles (arcsec), Lieske 1977.
        double zeta  = (2306.2181 + (0.30188 + 0.017998 * T) * T) * T * ARCSEC;
        double z     = (2306.2181 + (1.09468 + 0.018203 * T) * T) * T * ARCSEC;
        double theta = (2004.3109 + (-0.42665 - 0.041833 * T) * T) * T * ARCSEC;
        double[] P = mul(rotZ(-z), mul(rotY(theta), rotZ(-zeta)));
        double[] nut = nutation(T);                    // {Δψ, Δε, ε0}
        double eps0 = nut[2], eps = eps0 + nut[1];
        double[] N = mul(rotX(-eps), mul(rotZ(-nut[0]), rotX(eps0)));
        return mul(N, P);
    }

    /** {Δψ, Δε, mean obliquity ε0} (radians) — the leading IAU 1980 nutation terms. */
    static double[] nutation(double T) {
        double om = (125.04452 - 1934.136261 * T) * DEG;            // Moon's node
        double L  = (280.4665 + 36000.7698 * T) * DEG;              // Sun mean longitude
        double Lp = (218.3165 + 481267.8813 * T) * DEG;             // Moon mean longitude
        double dpsi = (-17.20 * Math.sin(om) - 1.32 * Math.sin(2 * L) - 0.23 * Math.sin(2 * Lp)
                + 0.21 * Math.sin(2 * om)) * ARCSEC;
        double deps = (9.20 * Math.cos(om) + 0.57 * Math.cos(2 * L) + 0.10 * Math.cos(2 * Lp)
                - 0.09 * Math.cos(2 * om)) * ARCSEC;
        double eps0 = (23.43929111 - (46.8150 + (0.00059 - 0.001813 * T) * T) * T / 3600.0) * DEG;
        return new double[]{ dpsi, deps, eps0 };
    }

    /**
     * Greenwich apparent sidereal time (radians) for a UTC sim time: GMST (IAU 1982,
     * Meeus 12.4, on UT ≈ UTC) plus the equation of the equinoxes.
     */
    static double gast(double simTimeSec) {
        double d = simTimeSec / DAY;                        // UT days since J2000.0
        double T = d / 36525.0;
        double gmstDeg = 280.46061837 + 360.98564736629 * d + T * T * (0.000387933 - T / 38710000.0);
        double Ttt = ttSeconds(simTimeSec) / (36525.0 * DAY);
        double[] nut = nutation(Ttt);
        double g = gmstDeg * DEG + nut[0] * Math.cos(nut[2] + nut[1]);
        g %= 2 * Math.PI;
        return g < 0 ? g + 2 * Math.PI : g;
    }

    // ------------------------------------------------------------------ 3×3 helpers

    static double[] rotX(double a) { double c = Math.cos(a), s = Math.sin(a); return new double[]{1,0,0, 0,c,s, 0,-s,c}; }
    static double[] rotY(double a) { double c = Math.cos(a), s = Math.sin(a); return new double[]{c,0,-s, 0,1,0, s,0,c}; }
    static double[] rotZ(double a) { double c = Math.cos(a), s = Math.sin(a); return new double[]{c,s,0, -s,c,0, 0,0,1}; }

    static double[] mul(double[] A, double[] B) {
        double[] C = new double[9];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                C[3 * i + j] = A[3 * i] * B[j] + A[3 * i + 1] * B[3 + j] + A[3 * i + 2] * B[6 + j];
        return C;
    }

    static double[] apply(double[] M, double x, double y, double z) {
        return new double[]{ M[0] * x + M[1] * y + M[2] * z, M[3] * x + M[4] * y + M[5] * z, M[6] * x + M[7] * y + M[8] * z };
    }
}
