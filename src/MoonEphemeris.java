/**
 * Geocentric lunar position from the ELP-2000/82 theory, truncated to the terms in
 * Meeus, <i>Astronomical Algorithms</i> (2nd ed.) chapter 47. This replaces the pure
 * two-body Kepler Moon used elsewhere: it carries the real periodic inequalities
 * (equation of the centre, evection, variation, annual equation, …) plus the correctly
 * regressing node and precessing perigee that live in the mean arguments, so new/full
 * moons and node crossings — and therefore eclipse dates and geometry — line up with the
 * real sky. Accuracy is ~10&Prime; in longitude and ~4&Prime; in latitude over 2000&ndash;2050,
 * i.e. Moon positions good to well under an arc-minute and eclipse instants good to a
 * minute or two.
 *
 * <p>Coordinates are returned in the sim's frame: geocentric ecliptic, referred to the
 * <b>mean equinox of J2000.0</b> (the accumulated general precession in longitude is
 * removed), matching the J2000 mean elements the planets use so Sun&ndash;Moon syzygy
 * comparisons are self-consistent.
 */
final class MoonEphemeris {
    private MoonEphemeris() {}

    private static final double DEG = Math.PI / 180.0;

    /** Table 47.A — periodic terms for longitude (Σl) and distance (Σr). Columns: D, M, M', F, Σl(1e-6°), Σr(1e-3 km). */
    private static final int[][] TA = {
        {0, 0, 1, 0,  6288774, -20905355}, {2, 0,-1, 0,  1274027, -3699111},
        {2, 0, 0, 0,   658314, -2955968},  {0, 0, 2, 0,   213618,  -569925},
        {0, 1, 0, 0,  -185116,    48888},  {0, 0, 0, 2,  -114332,    -3149},
        {2, 0,-2, 0,    58793,   246158},  {2,-1,-1, 0,    57066,  -152138},
        {2, 0, 1, 0,    53322,  -170733},  {2,-1, 0, 0,    45758,  -204586},
        {0, 1,-1, 0,   -40923,  -129620},  {1, 0, 0, 0,   -34720,   108743},
        {0, 1, 1, 0,   -30383,   104755},  {2, 0, 0,-2,    15327,    10321},
        {0, 0, 1, 2,   -12528,        0},  {0, 0, 1,-2,    10980,    79661},
        {4, 0,-1, 0,    10675,   -34782},  {0, 0, 3, 0,    10034,   -23210},
        {4, 0,-2, 0,     8548,   -21636},  {2, 1,-1, 0,    -7888,    24208},
        {2, 1, 0, 0,    -6766,    30824},  {1, 0,-1, 0,    -5163,    -8379},
        {2,-1, 1, 0,     4987,   -16675},  {2, 0, 2, 0,     4036,   -12831},
        {2, 0, 0, 2,     3994,   -10445},  {4, 0, 0, 0,     3861,   -11650},
        {2, 0,-3, 0,     3665,    14403},  {0, 1,-2, 0,    -2689,    -7003},
        {2, 0,-1, 2,    -2602,        0},  {2,-1,-2, 0,     2390,    10056},
        {1, 0, 1, 0,    -2348,     6322},  {2,-2, 0, 0,     2236,    -9884},
        {0, 1, 2, 0,    -2120,     5751},  {0, 2, 0, 0,    -2069,        0},
        {2,-2,-1, 0,     2048,    -4950},  {2, 0, 1,-2,    -1773,     4130},
        {2, 0, 0,-4,    -1595,        0},  {4,-1,-1, 0,     1215,    -3958},
        {0, 0, 2, 2,    -1110,        0},  {3, 0,-1, 0,     -892,     3258},
        {2, 1, 1, 0,     -810,     2616},  {4,-1,-2, 0,      759,    -1897},
        {0, 2,-1, 0,     -713,    -2117},  {2, 2,-1, 0,     -700,     2354},
        {2, 1,-2, 0,      691,        0},  {2,-1, 0,-2,      596,        0},
        {4, 0, 1, 0,      549,    -1423},  {0, 0, 4, 0,      537,    -1117},
        {4,-1, 0, 0,      520,    -1571},  {1, 0,-2, 0,     -487,    -1739},
        {2, 1, 0,-2,     -399,        0},  {0, 0, 2,-2,     -381,    -4421},
        {1, 1, 0, 0,      351,        0},  {3, 0,-2, 0,     -340,        0},
        {4, 0,-3, 0,      330,        0},  {2,-1, 2, 0,      327,        0},
        {0, 2, 1, 0,     -323,     1165},  {1, 1,-1, 0,      299,        0},
        {2, 0, 3, 0,      294,        0},  {2, 0,-1,-2,        0,     8752},
    };

    /** Table 47.B — periodic terms for latitude (Σb). Columns: D, M, M', F, Σb(1e-6°). */
    private static final int[][] TB = {
        {0, 0, 0, 1, 5128122}, {0, 0, 1, 1,  280602}, {0, 0, 1,-1,  277693},
        {2, 0, 0,-1,  173237}, {2, 0,-1, 1,   55413}, {2, 0,-1,-1,   46271},
        {2, 0, 0, 1,   32573}, {0, 0, 2, 1,   17198}, {2, 0, 1,-1,    9266},
        {0, 0, 2,-1,    8822}, {2,-1, 0,-1,    8216}, {2, 0,-2,-1,    4324},
        {2, 0, 1, 1,    4200}, {2, 1, 0,-1,   -3359}, {2,-1,-1, 1,    2463},
        {2,-1, 0, 1,    2211}, {2,-1,-1,-1,    2065}, {0, 1,-1,-1,   -1870},
        {4, 0,-1,-1,    1828}, {0, 1, 0, 1,   -1794}, {0, 0, 0, 3,   -1749},
        {0, 1,-1, 1,   -1565}, {1, 0, 0, 1,   -1491}, {0, 1, 1, 1,   -1475},
        {0, 1, 1,-1,   -1410}, {0, 1, 0,-1,   -1344}, {1, 0, 0,-1,   -1335},
        {0, 0, 3, 1,    1107}, {4, 0, 0,-1,    1021}, {4, 0,-1, 1,     833},
        {0, 0, 1,-3,     777}, {4, 0,-2, 1,     671}, {2, 0, 0,-3,     607},
        {2, 0, 2,-1,     596}, {2,-1, 1,-1,     491}, {2, 0,-2, 1,    -451},
        {0, 0, 3,-1,     439}, {2, 0, 2, 1,     422}, {2, 0,-3,-1,     421},
        {2, 1,-1, 1,    -366}, {2, 1, 0, 1,    -351}, {4, 0, 0, 1,     331},
        {2,-1, 1, 1,     315}, {2,-2, 0,-1,     302}, {0, 0, 1, 3,    -283},
        {2, 1, 1,-1,    -229}, {1, 1, 0,-1,     223}, {1, 1, 0, 1,     223},
        {0, 1,-2,-1,    -220}, {2, 1,-1,-1,    -220}, {1, 0, 1, 1,    -185},
        {2,-1,-2,-1,     181}, {0, 1, 2, 1,    -177}, {4, 0,-2,-1,     176},
        {4,-1,-1,-1,     166}, {1, 0, 1,-1,    -164}, {4, 0, 1,-1,     132},
        {1, 0,-1,-1,    -119}, {4,-1, 0,-1,     115}, {2,-2, 0, 1,     107},
    };

    /**
     * Geocentric position of the Moon at the given sim time (seconds of UTC since the
     * J2000.0 epoch instant, 2000-01-01 12:00). Returns {@code {lambda, beta, dist}}:
     * ecliptic longitude and latitude (radians, mean equinox of J2000) and the Earth-centre
     * to Moon-centre distance in metres.
     */
    static double[] geocentric(double simTimeSec) {
        return geocentricTT(Astro.ttSeconds(simTimeSec));   // TT = UTC + ΔT
    }

    /** {@link #geocentric} for a time given directly as TT seconds since J2000.0. */
    static double[] geocentricTT(double ttSec) {
        // Mean arguments use Julian centuries of TT since J2000.
        double T = ttSec / (86400.0 * 36525.0);

        // Mean arguments (degrees), reduced to [0,360).
        double Lp = poly(T, 218.3164477, 481267.88123421, -0.0015786, 1.0/538841.0, -1.0/65194000.0);
        double D  = poly(T, 297.8501921, 445267.1114034, -0.0018819, 1.0/545868.0, -1.0/113065000.0);
        double M  = poly(T, 357.5291092,  35999.0502909, -0.0001536, 1.0/24490000.0, 0.0);
        double Mp = poly(T, 134.9633964, 477198.8675055,  0.0087414, 1.0/69699.0, -1.0/14712000.0);
        double F  = poly(T,  93.2720950, 483202.0175233, -0.0036539, -1.0/3526000.0, 1.0/863310000.0);
        double A1 = wrap(119.75 + 131.849 * T);
        double A2 = wrap(53.09 + 479264.290 * T);
        double A3 = wrap(313.45 + 481266.484 * T);
        double E  = 1.0 - 0.002516 * T - 0.0000074 * T * T;   // eccentricity correction of Earth's orbit

        double Dr = D * DEG, Mr = M * DEG, Mpr = Mp * DEG, Fr = F * DEG;

        double sumL = 0.0, sumR = 0.0, sumB = 0.0;
        for (int[] t : TA) {
            double arg = t[0] * Dr + t[1] * Mr + t[2] * Mpr + t[3] * Fr;
            double ecc = eFactor(t[1], E);
            sumL += t[4] * ecc * Math.sin(arg);
            sumR += t[5] * ecc * Math.cos(arg);
        }
        for (int[] t : TB) {
            double arg = t[0] * Dr + t[1] * Mr + t[2] * Mpr + t[3] * Fr;
            sumB += t[4] * eFactor(t[1], E) * Math.sin(arg);
        }

        // Additive terms (planetary perturbations of Venus/Jupiter and Earth's flattening).
        double LpR = Lp * DEG;
        sumL += 3958.0 * Math.sin(A1 * DEG) + 1962.0 * Math.sin(LpR - Fr) + 318.0 * Math.sin(A2 * DEG);
        sumB += -2235.0 * Math.sin(LpR) + 382.0 * Math.sin(A3 * DEG)
              + 175.0 * Math.sin((A1 - F) * DEG) + 175.0 * Math.sin((A1 + F) * DEG)
              + 127.0 * Math.sin(LpR - Mpr) - 115.0 * Math.sin(LpR + Mpr);

        double lambdaDate = Lp + sumL / 1_000_000.0;   // longitude, mean equinox of date (deg)
        double beta = sumB / 1_000_000.0;              // latitude (deg)
        double distKm = 385000.56 + sumR / 1000.0;     // Earth-Moon centre distance (km)

        // Remove accumulated general precession in longitude to reach the J2000 equinox.
        double precDeg = (5029.0966 * T + 1.11161 * T * T) / 3600.0;
        double lambdaJ2000 = wrap(lambdaDate - precDeg);

        return new double[]{ lambdaJ2000 * DEG, beta * DEG, distKm * 1000.0 };
    }

    /**
     * Geocentric ecliptic-J2000 rectangular position of the Moon in AU, as {@code {x, y, z}}
     * with x toward the vernal equinox and z toward ecliptic north — the frame JPL Horizons
     * reports geocentric vectors in. Used by the accuracy tests.
     */
    static double[] geocentricEclipticAU(double simTimeSec) {
        return geocentricEclipticAUatTT(Astro.ttSeconds(simTimeSec));
    }

    /** {@link #geocentricEclipticAU} for a time given as TT seconds since J2000.0. */
    static double[] geocentricEclipticAUatTT(double ttSec) {
        double[] s = geocentricTT(ttSec);
        double lam = s[0], bet = s[1], dm = s[2];
        double cb = Math.cos(bet);
        double AU = 1.495978707e11;
        return new double[]{ dm * cb * Math.cos(lam) / AU, dm * cb * Math.sin(lam) / AU, dm * Math.sin(bet) / AU };
    }

    /** Term multiplier: E for a term linear in the Sun's anomaly M, E² for a quadratic one. */
    private static double eFactor(int mCoeff, double E) {
        int a = Math.abs(mCoeff);
        return a == 1 ? E : a == 2 ? E * E : 1.0;
    }

    private static double poly(double T, double a0, double a1, double a2, double a3, double a4) {
        return wrap(a0 + T * (a1 + T * (a2 + T * (a3 + T * a4))));
    }

    private static double wrap(double deg) {
        double r = deg % 360.0;
        return r < 0 ? r + 360.0 : r;
    }

    /** TT − UT1 in seconds (see {@link Astro#deltaTSeconds}). */
    static double deltaTSeconds(double year) { return Astro.deltaTSeconds(year); }
}
