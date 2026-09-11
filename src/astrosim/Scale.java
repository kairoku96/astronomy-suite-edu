package astrosim;

import static astrosim.Bodies.*;

/**
 * Visual scaling between physical metres and scene units (AU).
 *
 * The gravitational math is always true-scale (SI, in {@link NBody}); this
 * class only decides how large bodies are DRAWN. Two regimes:
 *
 *  - Exaggerated (sandbox default): radii are multiplied by a user-adjustable
 *    factor and clamped so every body is visible at AU zoom levels. Moon
 *    systems are laid out schematically (true orbit shape/eccentricity/
 *    inclination, compressed distance) so they sit outside their exaggerated
 *    parent.
 *
 *  - True scale ({@code trueScale}): radii and moon distances are rendered at
 *    their real physical size. Required by SHIP/LANDING modes, where the
 *    camera operates metres above a surface; the mode manager forces it on.
 */
public final class Scale {
    private Scale() {}

    public static final double AU = Bodies.AU;

    /** User-adjustable radius exaggeration for the sandbox view (toolbar slider). */
    public static volatile double exaggeration = 1800.0;
    /** Render bodies at true physical scale (forced on in SHIP/LANDING modes).
     *  Default ON so every body is drawn at its actual size. */
    public static volatile boolean trueScale = true;

    public static final double EXAGG_MIN = 100.0;
    public static final double EXAGG_MAX = 3600.0;

    private static final double MIN_R = 0.020;        // AU, exaggerated-mode clamp
    private static final double MAX_R = 0.105;        // AU

    private static final double[] maxMoonA = new double[COUNT];
    static {
        for (int k = 0; k < COUNT; k++) {
            double mx = 0;
            for (double a : MOON_A_M[k]) mx = Math.max(mx, a);
            maxMoonA[k] = mx;
        }
    }

    public static double au(double meters) { return meters / AU; }

    /** Visual radius of the Sun (AU). */
    public static double sunVisualR() {
        return trueScale ? SUN_RADIUS_M / AU : 0.16;
    }

    /** Visual radius of planet k (AU). */
    public static double planetVisualR(int k) {
        double realAu = RADIUS_M[k] / AU;
        if (trueScale) return realAu;
        double v = realAu * exaggeration;
        return Math.max(MIN_R, Math.min(MAX_R, v));
    }

    /** Visual radius for an arbitrary runtime body (AU). */
    public static double bodyVisualR(double radiusM) {
        double realAu = Math.max(radiusM, 1.0) / AU;
        if (trueScale) return realAu;
        double v = realAu * exaggeration;
        return Math.max(MIN_R * 0.6, Math.min(MAX_R, v));
    }

    /** Schematic visual orbit radius of a moon (AU): innermost ~1.8x, outermost ~4.0x parent radius. */
    public static double moonOrbitVisualR(int k, int m) {
        double pr = planetVisualR(k);
        double frac = maxMoonA[k] > 0 ? MOON_A_M[k][m] / maxMoonA[k] : 0.5;
        return pr * (1.8 + 2.2 * frac);
    }

    /** Uniform scale applied to a moon's real 3-D offset. 1.0 (true distances) in true-scale mode. */
    public static double moonScale(int k, int m) {
        if (trueScale) return 1.0;
        double realOrbitAu = MOON_A_M[k][m] / AU;
        return realOrbitAu > 0 ? moonOrbitVisualR(k, m) / realOrbitAu : 1.0;
    }

    /** Visual radius of a moon (AU). */
    public static double moonVisualR(int k, int m) {
        if (trueScale) return MOON_RADIUS_M[k][m] / AU;
        double pr = planetVisualR(k);
        double sr = MOON_SIZE_RATIO[k][m];
        double v = pr * 0.16 * (0.4 + sr);
        return Math.max(0.004, Math.min(pr * 0.5, v));
    }
}
