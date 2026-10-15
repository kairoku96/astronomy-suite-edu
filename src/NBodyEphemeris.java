import java.util.Map;
import java.util.TreeMap;

/**
 * The Solar System's heliocentric motion, integrated with a symplectic Wisdom–Holman map.
 *
 * <p>Every body pulls on every other: the Sun, the eight planets (the Earth–Moon barycentre
 * and the planet-<em>system</em> barycentres, exactly as JPL's DE ephemerides carry them),
 * Pluto–Charon, Ceres, Haumea and Eris are massive; the catalogued comets and asteroids are
 * test particles that feel all of them. Initial conditions are the JPL Horizons heliocentric
 * ecliptic-J2000 state vectors at J2000.0 (TDB), so the integration reproduces real
 * positions — including the mutual perturbations a Kepler ellipse can't (the Jupiter–Saturn
 * great inequality, Neptune–Pluto, Jupiter's pull on comets and asteroids).
 *
 * <h3>Integrator</h3>
 * Democratic-heliocentric Wisdom–Holman (Duncan, Levison &amp; Lee 1998): heliocentric
 * positions, barycentric velocities, and the Hamiltonian split
 * {@code H = H_Kepler + H_interaction + H_Sun-momentum}. One step is the symmetric
 * composition <i>kick/2 · jump/2 · drift · jump/2 · kick/2</i>: kicks apply the planet–planet
 * forces (plus the general-relativistic Sun term), jumps the Sun's reflex momentum, and the
 * drift is an exact two-body Kepler solve about the Sun (universal variables, so any conic).
 * The map is symplectic and time-reversible, so energy errors stay bounded over any span
 * and integrating backwards retraces the same states — jumping to 1066 or 3000 is just a
 * longer integration from the nearest cached checkpoint.
 *
 * <p>General relativity enters through the Sun's 1PN (Schwarzschild, harmonic-coordinate)
 * acceleration — the same term the JPL DE ephemerides carry — applied in the kick at the
 * mid-kick velocity so the step stays time-symmetric. It gives Mercury its 43″/century
 * perihelion advance <em>and</em> the right mean motion; the simpler Nobili–Roxburgh potential
 * gets the advance but shifts the mean motion (it put Mercury ~100″ off Horizons by 2050).
 *
 * <p>States are read out through a <em>symplectic corrector</em> (Wisdom, Holman &amp; Toussaint
 * 1996; see {@link #correct}), which removes the map's O(εh²) step error — Mercury's was 15″
 * by 2100, ∝ step². Minor bodies feel the Earth and the Moon separately (it matters at
 * Earth flybys) and, where measured, the Yarkovsky push.
 *
 * <p>Accuracy vs JPL Horizons, 1950–2100: Mercury–Pluto ≲ 0.03″ (Neptune 0.14″),
 * Ceres/Eros/Bennu ≲ 0.25″, Halley 0.6″ (its outgassing isn't modelled).
 *
 * <h3>Between steps</h3>
 * States live on a fixed grid of {@link #STEP_DAYS}. A query between grid points k and k+1
 * propagates both neighbours to the requested instant along their two-body orbits and blends
 * them linearly in time — continuous, smooth at any time-warp, and within metres of the
 * integrated trajectory.
 *
 * <p>Units internally: AU, days, GM in AU³/day². All times are TDB seconds since J2000.0.
 * The singleton is thread-safe (synchronized); the sim queries it once per frame.
 */
final class NBodyEphemeris {

    /** Integration step (days). Mercury's 88-day orbit is resolved in ~176 steps. */
    static final double STEP_DAYS = 0.5;
    /** Keep a state checkpoint every this many steps (~64 days). */
    private static final int CHECKPOINT_EVERY = 128;
    private static final int MAX_CHECKPOINTS = 60_000;

    private static final double AU_KM = 149_597_870.7;
    private static final double KM3S2 = 86400.0 * 86400.0 / (AU_KM * AU_KM * AU_KM);   // km³/s² → AU³/day²
    /** Speed of light, AU/day. */
    private static final double C_AUD = 299_792.458 * 86400.0 / AU_KM;

    /** GM of the Sun (DE440), AU³/day². */
    static final double GM_SUN = 132_712_440_041.279419 * KM3S2;

    /**
     * Massive bodies, in the sim's planet order. GM (km³/s², DE440 system values; Haumea and
     * Eris from their measured masses) and Horizons J2000.0 heliocentric ecliptic states
     * (AU, AU/day). Index 2 is the Earth–Moon barycentre; 3–8 are planet-system barycentres.
     */
    static final String[] MASSIVE = {
            "Mercury", "Venus", "Earth-Moon barycenter", "Mars", "Jupiter", "Saturn",
            "Uranus", "Neptune", "Pluto", "Ceres", "Haumea", "Eris" };
    private static final double[] MASSIVE_GM_KM3S2 = {
            22_031.868551, 324_858.592, 403_503.235502, 42_828.375816, 126_712_764.1, 37_940_584.8418,
            5_794_556.4, 6_836_527.10058, 975.5, 62.6284, 267.4, 1_099.0 };
    private static final double[][] MASSIVE_STATE0 = {
        {-1.300936053754522E-01, -4.472876181353563E-01, -2.459830695805179E-02,  2.136639566801616E-02, -6.447989743836899E-03, -2.487863414837162E-03},
        {-7.183022963453890E-01, -3.265430819980606E-02,  4.101418202684621E-02,  7.981175157753219E-04, -2.029521870223381E-02, -3.234515157278357E-04},
        {-1.771587841839055E-01,  9.672193524609504E-01, -1.139275508446145E-06, -1.720310905522688E-02, -3.163911860749114E-03,  2.424167134816753E-08},
        { 1.390715921746287E+00, -1.341631815233992E-02, -3.446766277585426E-02,  6.714995210335850E-04,  1.518724809115030E-02,  3.016517626039734E-04},
        { 4.001177161126058E+00,  2.938576106966103E+00, -1.017852766337440E-01, -4.568313526752718E-03,  6.443206010221308E-03,  7.557952025774449E-05},
        { 6.406408859532809E+00,  6.569989616912557E+00, -3.690764262448534E-01, -4.292351873843250E-03,  3.890315698014864E-03,  1.029478493989804E-04},
        { 1.443185661438178E+01, -1.373432146877096E+01, -2.381416877992503E-01,  2.678105591420150E-03,  2.672695895712686E-03, -2.477206355505103E-05},
        { 1.681204696805288E+01, -2.499176288928469E+01,  1.272228799202476E-01,  2.579274259047737E-03,  1.776900820016126E-03, -9.590961498591593E-05},
        {-9.875352229923580E+00, -2.795879662619036E+01,  5.850445916486538E+00,  3.028750846014266E-03, -1.537786167300815E-03, -7.122026507444645E-04},
        {-2.379327705915647E+00,  7.954860388931395E-01,  4.630055715902157E-01, -3.584228273182221E-03, -1.058594861536097E-02,  3.338536192580540E-04},
        {-4.598975416258292E+01, -5.120482285486933E+00,  2.238562723851461E+01,  4.769510293459651E-04, -2.062523577388229E-03,  3.680107428292245E-04},
        { 8.839334192774233E+01,  3.076524538911426E+01, -2.609439027515380E+01, -2.035772890454625E-04,  9.755904158751690E-04,  8.689419965069767E-04},
    };

    /**
     * Test particles: the minor-body catalog, by the names the sim uses, with Horizons J2000.0
     * heliocentric ecliptic states. Comets use the JPL orbit solution of the apparition
     * nearest 2000 (non-gravitational outgassing forces are not modelled).
     */
    static final String[] TEST = {
            "1P/Halley", "2P/Encke", "55P/Tempel-Tuttle", "109P/Swift-Tuttle", "C/1995 O1 Hale-Bopp",
            "4 Vesta", "2 Pallas", "3 Juno", "10 Hygiea", "433 Eros", "101955 Bennu",
            "2060 Chiron", "50000 Quaoar", "90377 Sedna" };
    private static final double[][] TEST_STATE0 = {
        {-1.738599346385816E+01,  1.697917611082230E+01, -7.577986613535686E+00, -1.207566068391474E-03,  2.191260405496591E-03, -6.912393771945848E-04},
        { 3.089110858497331E+00,  1.161315679196392E-02,  2.778184047623123E-01, -6.236297873650006E-03,  4.291490694009908E-03,  2.511424466758724E-04},
        { 9.195576465402951E-01, -6.625697102909885E+00, -1.429500284480203E+00, -2.380935134080231E-03, -7.193282127679244E-03, -6.757640937114219E-04},
        {-1.257514687248681E+01,  3.687234330253122E+00, -1.242229064076265E+01, -3.625303487950810E-03,  1.811051511973337E-03, -2.262751344811015E-03},
        { 1.314558701051669E-01, -1.071331357602078E+00, -1.008071613547486E+01,  5.823538034011996E-04, -2.983321480014320E-03, -6.891735972818090E-03},
        {-1.353580437607153E+00, -1.673136657862151E+00,  2.149018113721361E-01,  9.624804330114381E-03, -7.402735946443642E-03, -9.457048767913505E-04},
        {-8.411384433388419E-01,  1.653739426955205E+00, -1.073889494800965E+00, -1.173753499559210E-02, -4.121974546925030E-03,  3.817460329256512E-03},
        { 3.841773243423457E-01, -3.022428257712655E+00,  6.706612056325405E-01,  8.411460625023161E-03,  2.676537867092838E-03, -9.378734798920789E-04},
        {-2.374062486038638E+00, -1.463570769967126E+00, -1.781685951459966E-01,  6.109725584714315E-03, -8.949322941029890E-03,  2.567731873697293E-04},
        {-1.197012153973655E+00, -5.612812404290187E-01, -2.495595283241241E-01,  3.594899375829884E-03, -1.493154495450738E-02, -1.046952125992474E-03},
        {-4.824800176051853E-01,  8.266609041452084E-01,  8.913097995205260E-02, -1.744071645014861E-02, -6.993292605803212E-03, -6.676613128767211E-04},
        {-3.529597323721606E+00, -9.127360725510940E+00,  7.572364835926668E-01,  4.971227226758336E-03, -3.655657077733303E-03,  6.848535581491020E-04},
        {-1.676652235545512E+01, -3.983170726097171E+01,  5.159288095529033E+00,  2.431315772504504E-03, -8.913204987669561E-04,  1.777363312804227E-04},
        { 6.195077806808445E+01,  6.387632758113101E+01, -1.858921559765201E+01, -2.267794159920438E-03,  9.021230448037089E-04,  1.246151421265307E-04},
    };

    /**
     * Transverse non-gravitational parameter A2 (AU/day²) per test particle, for the thermal
     * Yarkovsky drift (see {@link #yarkovsky}). Bennu's is set from its measured semimajor-axis
     * drift, da/dt = −284.6 ± 0.2 m/yr (Farnocchia et al. 2021, OSIRIS-REx tracking), through
     * the orbit-averaged Gauss equation on its orbit (a = 1.126 AU, e = 0.204):
     * A2 = −4.557×10⁻¹⁴, matching JPL's fitted −4.5…−4.6×10⁻¹⁴. Comets' outgassing forces are
     * not modelled.
     */
    private static final double[] A2 = new double[TEST.length];
    static { A2[java.util.Arrays.asList(TEST).indexOf("101955 Bennu")] = -4.557e-14; }

    static final int N_MASSIVE = MASSIVE.length;
    static final int N_BODIES = MASSIVE.length + TEST.length;
    /** GM of each body (AU³/day²); zero for test particles. */
    static final double[] GM = new double[N_BODIES];
    private static final double GM_TOTAL;
    static {
        double s = GM_SUN;
        for (int i = 0; i < N_MASSIVE; i++) { GM[i] = MASSIVE_GM_KM3S2[i] * KM3S2; s += GM[i]; }
        GM_TOTAL = s;
    }

    // Holder idiom: built on first use, after every static of this class (the corrector's
    // coefficients, the Moon cache) is initialised.
    private static final class Shared { static final NBodyEphemeris INSTANCE = new NBodyEphemeris(); }
    static NBodyEphemeris shared() { return Shared.INSTANCE; }

    /** Index of a body by name (planet names map to their system barycentre), or −1. */
    static int indexOf(String name) {
        for (int i = 0; i < N_MASSIVE; i++) if (MASSIVE[i].equals(name)) return i;
        if (name.equals("Earth")) return 2;
        for (int i = 0; i < TEST.length; i++) if (TEST[i].equals(name)) return N_MASSIVE + i;
        return -1;
    }

    // ------------------------------------------------------------------ state

    /**
     * Canonical integrator state: per body {Q (heliocentric position), V (barycentric
     * velocity)}, 6 doubles each, AU and AU/day.
     */
    private double[] cur;
    private long curStep;
    private final TreeMap<Long, double[]> checkpoints = new TreeMap<>();
    // Cached bracketing pair for interpolation (heliocentric position + velocity per body).
    private long pairStep = Long.MIN_VALUE;
    private final double[] pairA = new double[6 * N_BODIES], pairB = new double[6 * N_BODIES];

    private NBodyEphemeris() {
        cur = new double[6 * N_BODIES];
        // Heliocentric velocities → barycentric: subtract the Sun's barycentric velocity,
        // which is −Σ m_j v_j / M_total over the massive bodies.
        double[] sunV = new double[3];
        for (int i = 0; i < N_MASSIVE; i++)
            for (int c = 0; c < 3; c++) sunV[c] -= GM[i] * MASSIVE_STATE0[i][3 + c] / GM_TOTAL;
        for (int i = 0; i < N_BODIES; i++) {
            double[] s = i < N_MASSIVE ? MASSIVE_STATE0[i] : TEST_STATE0[i - N_MASSIVE];
            for (int c = 0; c < 3; c++) {
                cur[6 * i + c] = s[c];
                cur[6 * i + 3 + c] = s[3 + c] + sunV[c];
            }
        }
        // The map integrates "mapped" coordinates; start from the ones whose corrected image
        // is the real J2000 state.
        correct(cur, 0, true);
        curStep = 0;
        checkpoints.put(0L, cur.clone());
    }

    // ------------------------------------------------------------------ public queries

    /**
     * Heliocentric ecliptic-J2000 positions (AU) and velocities (AU/day) of every body at
     * {@code tdbSec}, written as {x,y,z,vx,vy,vz} per body into {@code out}
     * (length ≥ 6·{@link #N_BODIES}).
     */
    synchronized void heliocentric(double tdbSec, double[] out) {
        double tDays = tdbSec / 86400.0;
        double kf = Math.floor(tDays / STEP_DAYS);
        long k = (long) kf;
        double tau = tDays - kf * STEP_DAYS;           // 0 ≤ tau < STEP_DAYS
        loadPair(k);
        double w = tau / STEP_DAYS;
        double[] a = new double[6], b = new double[6];
        for (int i = 0; i < N_BODIES; i++) {
            double mu = GM_SUN + GM[i];
            System.arraycopy(pairA, 6 * i, a, 0, 6);
            System.arraycopy(pairB, 6 * i, b, 0, 6);
            drift(mu, a, 0, tau);
            drift(mu, b, 0, tau - STEP_DAYS);
            for (int c = 0; c < 6; c++) out[6 * i + c] = (1 - w) * a[c] + w * b[c];
        }
    }

    /** Heliocentric ecliptic-J2000 position (AU) of one body at {@code tdbSec}. */
    double[] heliocentricAU(int body, double tdbSec) {
        double[] all = new double[6 * N_BODIES];
        heliocentric(tdbSec, all);
        return new double[]{ all[6 * body], all[6 * body + 1], all[6 * body + 2] };
    }

    /**
     * The Sun's position relative to the Solar-System barycentre (AU), from a set of
     * heliocentric states as returned by {@link #heliocentric}: −Σ m_i r_i / M_total.
     */
    static double[] sunBarycentric(double[] helio) {
        double[] s = new double[3];
        for (int i = 0; i < N_MASSIVE; i++)
            for (int c = 0; c < 3; c++) s[c] -= GM[i] * helio[6 * i + c] / GM_TOTAL;
        return s;
    }

    // ------------------------------------------------------------------ grid management

    /** Fill {@link #pairA}/{@link #pairB} with heliocentric states at grid steps k and k+1. */
    private void loadPair(long k) {
        if (k == pairStep) return;
        double[] s = stateAt(k);
        double[] c = s.clone();
        correct(c, k * STEP_DAYS, false);
        toHeliocentric(c, pairA);
        step(s, k * STEP_DAYS, STEP_DAYS);  // s is our own copy, safe to advance
        correct(s, (k + 1) * STEP_DAYS, false);
        toHeliocentric(s, pairB);
        pairStep = k;
    }

    /** A copy of the canonical state at grid step k, integrating from the nearest cache. */
    private double[] stateAt(long k) {
        long base = curStep;
        for (Map.Entry<Long, double[]> c : java.util.Arrays.asList(checkpoints.floorEntry(k), checkpoints.ceilingEntry(k))) {
            if (c != null && Math.abs(k - c.getKey()) < Math.abs(k - base)) base = c.getKey();
        }
        double[] s = (base == curStep ? cur : checkpoints.get(base)).clone();
        long n = base;
        double h = k > n ? STEP_DAYS : -STEP_DAYS;
        while (n != k) {
            step(s, n * STEP_DAYS, h);
            n += k > n ? 1 : -1;
            if (n % CHECKPOINT_EVERY == 0 && !checkpoints.containsKey(n)) {
                if (checkpoints.size() >= MAX_CHECKPOINTS) trimCheckpoints(n);
                checkpoints.put(n, s.clone());
            }
        }
        cur = s.clone();
        curStep = k;
        return s;
    }

    /** Drop the checkpoints farthest from {@code keepNear} (always keeping the J2000 seed). */
    private void trimCheckpoints(long keepNear) {
        long radius = (long) MAX_CHECKPOINTS * CHECKPOINT_EVERY / 4;
        checkpoints.keySet().removeIf(c -> c != 0 && Math.abs(c - keepNear) > radius);
    }

    /** Canonical (Q, V) → heliocentric (r, v): v = V + P/M☉, with P = Σ m_j V_j. */
    private static void toHeliocentric(double[] s, double[] out) {
        double px = 0, py = 0, pz = 0;
        for (int j = 0; j < N_MASSIVE; j++) {
            px += GM[j] * s[6 * j + 3]; py += GM[j] * s[6 * j + 4]; pz += GM[j] * s[6 * j + 5];
        }
        px /= GM_SUN; py /= GM_SUN; pz /= GM_SUN;
        for (int i = 0; i < N_BODIES; i++) {
            int o = 6 * i;
            out[o] = s[o]; out[o + 1] = s[o + 1]; out[o + 2] = s[o + 2];
            out[o + 3] = s[o + 3] + px; out[o + 4] = s[o + 4] + py; out[o + 5] = s[o + 5] + pz;
        }
    }

    // ------------------------------------------------------------------ the map

    /**
     * One Wisdom–Holman step of {@code h} days (negative = backwards) starting at time
     * {@code t} (TDB days since J2000), in place.
     */
    static void step(double[] s, double t, double h) {
        kick(s, 0.5 * h, t);
        jump(s, 0.5 * h);
        for (int i = 0; i < N_BODIES; i++) drift(GM_SUN, s, 6 * i, h);
        jump(s, 0.5 * h);
        kick(s, 0.5 * h, t + h);
    }

    // ------------------------------------------------------------------ symplectic corrector

    /*
     * The map above is the Strang splitting e^{hB/2} e^{hA} e^{hB/2} of the Kepler flow A and
     * the perturbation B (interaction kick + Sun-momentum jump, ~ε ≈ planet/Sun mass ratio).
     * To first order in ε its logarithm is hA + h·φ(h·ad_A)B with φ(x) = (x/2)coth(x/2)
     * = 1 + x²/12 − x⁴/720 + …, so a step follows the true flow only to O(εh²): Mercury
     * drifts ~15″ per century at h = ½ day (measured ∝ h²). Wisdom, Holman & Toussaint (1996):
     * the error is removed by a near-identity change of variables C = e^{G},
     * G = h·(φ(x) − 1)/x · B, because C·map·C⁻¹ is then the true flow to O(ε²). The series
     * (φ(x) − 1)/x = x/12 − x³/720 + x⁵/30240 − x⁷/1209600 + … is odd, so it is built from
     * pairs of conjugated kicks X(±αh, ±βh) = drift(a)·kick(b)·drift(−a), whose generator is
     * b·e^{a·ad_A}B: a pair gives 2βh·sinh(αx)B. With α = 1…4 the four β below match the
     * series through x⁷ (residual O(εh⁹)). The integration itself is untouched: the grid
     * carries mapped coordinates, and C is applied only to read a state out.
     */
    private static final double[] CORR_A = {1, 2, 3, 4};
    private static final double[] CORR_B = new double[CORR_A.length];
    static {
        double[] series = {1.0 / 12, -1.0 / 720, 1.0 / 30240, -1.0 / 1209600};
        int n = CORR_A.length;
        double[][] m = new double[n][n + 1];
        for (int k = 0; k < n; k++) {
            double fact = 1;
            for (int f = 2; f <= 2 * k + 1; f++) fact *= f;
            for (int i = 0; i < n; i++) m[k][i] = 2 * Math.pow(CORR_A[i], 2 * k + 1) / fact;
            m[k][n] = series[k];
        }
        for (int p = 0; p < n; p++)                        // Gauss–Jordan (well conditioned)
            for (int r = 0; r < n; r++) {
                if (r == p) continue;
                double f = m[r][p] / m[p][p];
                for (int c = p; c <= n; c++) m[r][c] -= f * m[p][c];
            }
        for (int i = 0; i < n; i++) CORR_B[i] = m[i][n] / m[i][i];
    }

    /**
     * Apply the corrector C (mapped → real coordinates), or its inverse, to the canonical
     * state {@code s} at TDB day {@code t}, in place.
     */
    static void correct(double[] s, double t, boolean inverse) {
        double h = STEP_DAYS;
        int n = CORR_A.length;
        // C = Π X(α_i, β_i)·X(−α_i, −β_i); C⁻¹ runs the factors in reverse with β negated.
        for (int q = 0; q < 2 * n; q++) {
            int idx = inverse ? 2 * n - 1 - q : q;
            int i = idx / 2;
            double sign = (idx % 2 == 0) ? 1 : -1;
            double a = sign * CORR_A[i] * h, b = sign * CORR_B[i] * h * (inverse ? 1 : -1);
            for (int j = 0; j < N_BODIES; j++) drift(GM_SUN, s, 6 * j, a);
            jump(s, 0.5 * b);
            kick(s, b, t);
            jump(s, 0.5 * b);
            for (int j = 0; j < N_BODIES; j++) drift(GM_SUN, s, 6 * j, -a);
        }
    }

    /** Index of the Earth–Moon barycentre among the massive bodies. */
    private static final int EMB = 2;
    /** GM(Moon) / GM(Earth + Moon), DE440. */
    private static final double MOON_FRACTION = 4902.800118 / 403503.235502;

    /**
     * Interaction kick at time {@code t} (TDB days): mutual attraction of the massive bodies,
     * the GR term from the Sun, and the Sun's tide on the Earth–Moon pair.
     */
    private static void kick(double[] s, double h, double t) {
        // Sun's reflex: heliocentric velocity v = V + P/M☉ (needed by the velocity-dependent GR term).
        double px = 0, py = 0, pz = 0;
        for (int j = 0; j < N_MASSIVE; j++) {
            px += GM[j] * s[6 * j + 3]; py += GM[j] * s[6 * j + 4]; pz += GM[j] * s[6 * j + 5];
        }
        px /= GM_SUN; py /= GM_SUN; pz /= GM_SUN;
        double[] g = new double[3];
        for (int i = 0; i < N_BODIES; i++) {
            int oi = 6 * i;
            double xi = s[oi], yi = s[oi + 1], zi = s[oi + 2];
            double ax = 0, ay = 0, az = 0;
            for (int j = 0; j < N_MASSIVE; j++) {
                if (j == i) continue;
                int oj = 6 * j;
                if (j == EMB && i >= N_MASSIVE) {
                    // A minor body passing the Earth feels the Earth and the Moon separately
                    // (Bennu's 2005 and 2011 flybys pass within ~15 lunar distances).
                    earthMoonPull(s[oj] - xi, s[oj + 1] - yi, s[oj + 2] - zi, t, g);
                    ax += g[0]; ay += g[1]; az += g[2];
                    continue;
                }
                double dx = s[oj] - xi, dy = s[oj + 1] - yi, dz = s[oj + 2] - zi;
                double r2 = dx * dx + dy * dy + dz * dz;
                double f = GM[j] / (r2 * Math.sqrt(r2));
                ax += f * dx; ay += f * dy; az += f * dz;
            }
            // 1PN Sun term, evaluated at the mid-kick velocity (two fixed-point passes) so the
            // kick stays time-symmetric even though the force depends on velocity.
            double vx = s[oi + 3] + px, vy = s[oi + 4] + py, vz = s[oi + 5] + pz;
            grAccel(xi, yi, zi, vx, vy, vz, g);
            for (int it = 0; it < 2; it++) {
                double mx = vx + 0.5 * h * (ax + g[0]), my = vy + 0.5 * h * (ay + g[1]), mz = vz + 0.5 * h * (az + g[2]);
                grAccel(xi, yi, zi, mx, my, mz, g);
            }
            ax += g[0]; ay += g[1]; az += g[2];
            if (i == EMB) {
                emBTide(xi, yi, zi, t, g);
                ax += g[0]; ay += g[1]; az += g[2];
            }
            if (i >= N_MASSIVE && A2[i - N_MASSIVE] != 0) {
                // Yarkovsky: along the transverse direction ĥ × r̂ of the mid-kick velocity.
                double mx = vx + 0.5 * h * ax, my = vy + 0.5 * h * ay, mz = vz + 0.5 * h * az;
                yarkovsky(A2[i - N_MASSIVE], xi, yi, zi, mx, my, mz, g);
                ax += g[0]; ay += g[1]; az += g[2];
            }
            s[oi + 3] += h * ax; s[oi + 4] += h * ay; s[oi + 5] += h * az;
        }
    }

    /**
     * The Earth–Moon barycentre is not a point mass: the Sun pulls the Earth and the Moon
     * separately, and the difference (a quadrupole tide, ~10⁻⁷ of the Sun's pull) changes the
     * barycentre's mean motion — left out, it puts Earth ~5000 km (8″) off after 50 years.
     * This adds the exact two-body sum minus the point-mass term the Kepler drift already
     * applies, with the Earth→Moon vector from the lunar theory ({@link MoonEphemeris}).
     */
    private static void emBTide(double x, double y, double z, double t, double[] out) {
        double[] r = moonVector(t);
        double fM = MOON_FRACTION, fE = 1 - fM;
        double ex = x - fM * r[0], ey = y - fM * r[1], ez = z - fM * r[2];   // Earth
        double mx = x + fE * r[0], my = y + fE * r[1], mz = z + fE * r[2];   // Moon
        double re = Math.sqrt(ex * ex + ey * ey + ez * ez), rm = Math.sqrt(mx * mx + my * my + mz * mz);
        double rb = Math.sqrt(x * x + y * y + z * z);
        double ke = fE / (re * re * re), km = fM / (rm * rm * rm), kb = 1 / (rb * rb * rb);
        out[0] = -GM_SUN * (ke * ex + km * mx - kb * x);
        out[1] = -GM_SUN * (ke * ey + km * my - kb * y);
        out[2] = -GM_SUN * (ke * ez + km * mz - kb * z);
    }

    /**
     * Pull of the Earth and the Moon, as two point masses, on a body whose offset to the
     * Earth–Moon barycentre is (dx, dy, dz), at TDB day {@code t}.
     */
    private static void earthMoonPull(double dx, double dy, double dz, double t, double[] out) {
        double[] r = moonVector(t);
        double fM = MOON_FRACTION, fE = 1 - fM;
        double ex = dx - fM * r[0], ey = dy - fM * r[1], ez = dz - fM * r[2];   // to Earth
        double mx = dx + fE * r[0], my = dy + fE * r[1], mz = dz + fE * r[2];   // to Moon
        double re2 = ex * ex + ey * ey + ez * ez, rm2 = mx * mx + my * my + mz * mz;
        double ke = fE * GM[EMB] / (re2 * Math.sqrt(re2)), km = fM * GM[EMB] / (rm2 * Math.sqrt(rm2));
        out[0] = ke * ex + km * mx; out[1] = ke * ey + km * my; out[2] = ke * ez + km * mz;
    }

    // One-entry cache: a step's closing kick and the next step's opening kick share a time.
    private static double moonT = Double.NaN;
    private static double[] moonR;

    /**
     * Geocentric Moon (AU, ecliptic J2000) at TDB day {@code t}. The tide and the Earth–Moon
     * split are ~10⁻⁷ effects, so the lunar series is cut at 0.1″-amplitude terms (≈ 2″, a few
     * km at the Moon; 4 µs instead of the full theory's 0.3 ms per integration step).
     */
    private static synchronized double[] moonVector(double t) {
        if (t != moonT) { moonR = MoonEphemeris.geocentricEclipticAUatTT(t * 86400.0, 0.1); moonT = t; }
        return moonR;
    }

    /**
     * Post-Newtonian acceleration of a body about the Sun in harmonic coordinates (the
     * Schwarzschild 1PN term of the Einstein–Infeld–Hoffmann equations, as in the JPL DE
     * ephemerides): a = μ/(c²r³) [(4μ/r − v²) r + 4 (r·v) v]. Gives Mercury its 43″/century.
     */
    private static void grAccel(double x, double y, double z, double vx, double vy, double vz, double[] out) {
        double r2 = x * x + y * y + z * z, r = Math.sqrt(r2);
        double k = GM_SUN / (C_AUD * C_AUD * r2 * r);
        double a = 4 * GM_SUN / r - (vx * vx + vy * vy + vz * vz);
        double b = 4 * (x * vx + y * vy + z * vz);
        out[0] = k * (a * x + b * vx); out[1] = k * (a * y + b * vy); out[2] = k * (a * z + b * vz);
    }

    /**
     * Marsden-style transverse non-gravitational acceleration A2·(1 AU/r)² along ĥ × r̂ (the
     * in-plane direction perpendicular to the Sun line, prograde for A2 &gt; 0) — the form
     * JPL fits for the Yarkovsky effect.
     */
    private static void yarkovsky(double a2, double x, double y, double z, double vx, double vy, double vz, double[] out) {
        double hx = y * vz - z * vy, hy = z * vx - x * vz, hz = x * vy - y * vx;
        double tx = hy * z - hz * y, ty = hz * x - hx * z, tz = hx * y - hy * x;   // h × r
        double r2 = x * x + y * y + z * z;
        double k = a2 / (r2 * Math.sqrt(tx * tx + ty * ty + tz * tz));
        out[0] = k * tx; out[1] = k * ty; out[2] = k * tz;
    }

    /** Sun-momentum "jump": every position shifts by h·P/M☉. */
    private static void jump(double[] s, double h) {
        double px = 0, py = 0, pz = 0;
        for (int j = 0; j < N_MASSIVE; j++) {
            px += GM[j] * s[6 * j + 3]; py += GM[j] * s[6 * j + 4]; pz += GM[j] * s[6 * j + 5];
        }
        double k = h / GM_SUN;
        for (int i = 0; i < N_BODIES; i++) {
            s[6 * i] += k * px; s[6 * i + 1] += k * py; s[6 * i + 2] += k * pz;
        }
    }

    /**
     * Exact two-body propagation of the state {@code s[o..o+5]} by {@code dt} under
     * gravitational parameter {@code mu}, in universal variables (Stumpff functions) so it
     * is valid for ellipses, parabolas and hyperbolas alike.
     */
    static void drift(double mu, double[] s, int o, double dt) {
        if (dt == 0) return;
        double x = s[o], y = s[o + 1], z = s[o + 2], vx = s[o + 3], vy = s[o + 4], vz = s[o + 5];
        double r0 = Math.sqrt(x * x + y * y + z * z);
        double v2 = vx * vx + vy * vy + vz * vz;
        double rv = x * vx + y * vy + z * vz;
        double sqmu = Math.sqrt(mu);
        double alpha = 2.0 / r0 - v2 / mu;          // 1/a
        if (alpha > 0) {                            // ellipse: fold dt into one period
            double period = 2 * Math.PI / (sqmu * alpha * Math.sqrt(alpha));
            dt = Math.IEEEremainder(dt, period);
        }
        double chi = alpha > 0 ? sqmu * dt * alpha : sqmu * dt / r0;
        double c2 = 0.5, c3 = 1.0 / 6.0, r = r0;
        double last = Double.MAX_VALUE;
        for (int it = 0; it < 60; it++) {
            double zz = alpha * chi * chi;
            c2 = stumpC(zz); c3 = stumpS(zz);
            double chi2 = chi * chi;
            r = chi2 * c2 + rv / sqmu * chi * (1 - zz * c3) + r0 * (1 - zz * c2);
            double F = rv / sqmu * chi2 * c2 + (1 - alpha * r0) * chi2 * chi * c3 + r0 * chi - sqmu * dt;
            double d = F / r;
            chi -= d;
            double ad = Math.abs(d);
            // Converged once the correction is at round-off level or stops shrinking.
            if (ad < 1e-15 * (1 + Math.abs(chi)) || (ad >= 0.5 * last && ad < 1e-11 * (1 + Math.abs(chi)))) {
                zz = alpha * chi * chi; c2 = stumpC(zz); c3 = stumpS(zz);
                chi2 = chi * chi;
                r = chi2 * c2 + rv / sqmu * chi * (1 - zz * c3) + r0 * (1 - zz * c2);
                break;
            }
            last = ad;
        }
        double chi2 = chi * chi, zz = alpha * chi2;
        double f = 1 - chi2 / r0 * c2;
        double g = dt - chi2 * chi * c3 / sqmu;
        double fd = sqmu / (r * r0) * (zz * c3 - 1) * chi;
        double gd = 1 - chi2 / r * c2;
        s[o] = f * x + g * vx; s[o + 1] = f * y + g * vy; s[o + 2] = f * z + g * vz;
        s[o + 3] = fd * x + gd * vx; s[o + 4] = fd * y + gd * vy; s[o + 5] = fd * z + gd * vz;
    }

    /** Stumpff C(z) = (1 − cos√z)/z. */
    private static double stumpC(double z) {
        if (Math.abs(z) < 1e-3) return 0.5 - z / 24.0 + z * z / 720.0 - z * z * z / 40320.0;
        if (z > 0) return (1 - Math.cos(Math.sqrt(z))) / z;
        return (Math.cosh(Math.sqrt(-z)) - 1) / -z;
    }

    /** Stumpff S(z) = (√z − sin√z)/√z³. */
    private static double stumpS(double z) {
        if (Math.abs(z) < 1e-3) return 1.0 / 6.0 - z / 120.0 + z * z / 5040.0 - z * z * z / 362880.0;
        if (z > 0) { double q = Math.sqrt(z); return (q - Math.sin(q)) / (q * q * q); }
        double q = Math.sqrt(-z);
        return (Math.sinh(q) - q) / (q * q * q);
    }

    // ------------------------------------------------------------------ orbital elements

    /**
     * Osculating heliocentric elements of a state (AU, AU/day; ecliptic J2000) under
     * {@code mu}: {a (AU), e, i, Ω, ϖ, M, E} (angles in radians). For the sim's orbit rings,
     * Kepler's-law overlays and the lessons — the ellipse the body is on <em>right now</em>.
     */
    static double[] elements(double mu, double x, double y, double z, double vx, double vy, double vz) {
        double r = Math.sqrt(x * x + y * y + z * z);
        double v2 = vx * vx + vy * vy + vz * vz;
        double hx = y * vz - z * vy, hy = z * vx - x * vz, hz = x * vy - y * vx;
        double h = Math.sqrt(hx * hx + hy * hy + hz * hz);
        double a = 1.0 / (2.0 / r - v2 / mu);
        double rv = x * vx + y * vy + z * vz;
        // Eccentricity vector.
        double ex = (v2 / mu - 1 / r) * x - rv / mu * vx;
        double ey = (v2 / mu - 1 / r) * y - rv / mu * vy;
        double ez = (v2 / mu - 1 / r) * z - rv / mu * vz;
        double e = Math.sqrt(ex * ex + ey * ey + ez * ez);
        double inc = Math.acos(Math.max(-1, Math.min(1, hz / h)));
        double node = Math.atan2(hx, -hy);
        // Argument of periapsis and true anomaly measured in the orbit plane from the node.
        double nx = Math.cos(node), ny = Math.sin(node);
        double qx = -hz / h * ny, qy = hz / h * nx, qz = (hx * ny - hy * nx) / h;   // ĥ × n̂
        double om = e > 1e-12 ? Math.atan2(ex * qx + ey * qy + ez * qz, ex * nx + ey * ny) : 0;
        double u = Math.atan2(x * qx + y * qy + z * qz, x * nx + y * ny);
        double nu = u - om;
        double E = 2 * Math.atan2(Math.sqrt(Math.max(0, 1 - e)) * Math.sin(nu / 2), Math.sqrt(1 + e) * Math.cos(nu / 2));
        double M = E - e * Math.sin(E);
        return new double[]{ a, e, inc, node, node + om, M, E };
    }
}
