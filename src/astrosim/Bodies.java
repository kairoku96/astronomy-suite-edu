package astrosim;

import java.awt.Color;

/**
 * Static physical/orbital data for the Sun, planets, dwarf planets and moons.
 * Migrated from the original 2-D SolarSystemSimulation, plus orbital
 * inclinations (J2000) needed for the 3-D model.
 *
 * Index convention everywhere in this package:
 *   planet index i in [0, COUNT)        -> arrays below
 *   the Sun is handled separately (M_SUN / SUN_RADIUS_M)
 */
public final class Bodies {
    private Bodies() {}

    /* ---- physical constants (SI) ---- */
    public static final double AU    = 1.496e11;
    public static final double DAY   = 86_400.0;
    public static final double YEAR  = 365.25 * DAY;
    public static final double G     = 6.67430e-11;
    public static final double M_SUN = 1.98847e30;
    public static final double SUN_RADIUS_M = 6.9634e8;

    public static final String[] NAMES = {
            "Mercury", "Venus", "Earth", "Mars",
            "Jupiter", "Saturn", "Uranus", "Neptune", "Pluto",
            "Ceres", "Haumea", "Eris"
    };
    public static final int COUNT = NAMES.length;

    public static final String[] BODY_TYPE = {
            "planet", "planet", "planet", "planet",
            "gas_giant", "gas_giant", "gas_giant", "gas_giant", "dwarf_planet",
            "dwarf_planet", "dwarf_planet", "dwarf_planet"
    };

    /** Semi-major axis (m). */
    public static final double[] A_METERS = {
            5.7909e10, 1.0821e11, 1.495978707e11, 2.2794e11,
            7.7854e11, 1.4298e12, 2.8725e12, 4.5045e12, 5.9064e12,
            2.767 * AU, 43.335 * AU, 67.781 * AU
    };

    /** Orbital eccentricity. */
    public static final double[] ECC = {
            0.205630, 0.006772, 0.016709, 0.093405,
            0.048498, 0.055546, 0.046381, 0.008956, 0.248827,
            0.0758, 0.1887, 0.4407
    };

    /** Orbital period (Earth years). */
    public static final double[] PERIOD_Y = {
            0.2408467, 0.61519726, 1.0000174, 1.8808476,
            11.862615, 29.447498, 84.016846, 164.79132, 247.92065,
            4.60, 284.0, 557.0
    };

    /** Physical radius (m). */
    public static final double[] RADIUS_M = {
            2.439e6, 6.052e6, 6.371e6, 3.390e6,
            6.9911e7, 5.8232e7, 2.5362e7, 2.4622e7, 1.1883e6,
            4.73e5, 8.16e5, 1.163e6
    };

    /** Mean longitude at J2000 (deg). */
    public static final double[] PLANET_L0 = {
            252.25084, 181.97973, 100.46435, 355.45332, 34.40438,
            49.94432, 313.23218, 304.88003, 238.92881,
            181.0, 210.0, 35.0
    };

    /** Longitude of perihelion at J2000 (deg). */
    public static final double[] PLANET_VARPI0 = {
            77.45736, 131.60261, 102.93735, 336.04084, 14.75385,
            92.43194, 170.96424, 44.97135, 113.76329,
            73.0, 122.0, 151.0
    };

    /** Longitude of ascending node at J2000 (deg). */
    public static final double[] PLANET_OMEGA0 = {
            48.33167, 76.67992, 0.0, 49.57854, 100.55615,
            113.71504, 74.22950, 131.72169, 110.30347,
            80.0, 122.0, 36.0
    };

    /** Orbital inclination to the ecliptic at J2000 (deg) — NEW for 3-D. */
    public static final double[] INCLINATION_DEG = {
            7.005, 3.395, 0.0, 1.850, 1.303,
            2.485, 0.773, 1.770, 17.16,
            10.59, 28.19, 44.04
    };

    public static final double[] PLANET_MASS = {
            3.3011e23, 4.8675e24, 5.97237e24, 6.4171e23,
            1.8982e27, 5.6834e26, 8.6810e25, 1.02413e26, 1.303e22,
            9.39e20, 4.01e21, 1.66e22
    };

    public static final Color[] COLORS = {
            new Color(169, 169, 169), new Color(210, 180, 140), new Color(81, 150, 208),
            new Color(188, 39, 50),   new Color(255, 204, 102), new Color(194, 178, 128),
            new Color(173, 216, 230), new Color(72, 118, 255),  new Color(233, 226, 214),
            new Color(180, 200, 210), new Color(210, 230, 255),  new Color(190, 205, 220)
    };

    /* ---- moon data (per planet) ---- */
    public static final double[][] MOON_A_M = {
            {}, {}, {3.844e8},
            {9.377e6, 2.356e7},
            {4.217e8, 6.71e8, 1.07e9, 1.883e9},
            {1.222e9, 2.38e8, 5.27e8, 1.861e8, 2.946e8, 3.561e8, 3.561e9},
            {4.363e8, 5.835e8, 4.989e8, 1.913e8, 1.299e8},
            {3.547e8}, {1.195e7}, {}, {}, {}
    };
    public static final double[][] MOON_PERIOD_DAYS = {
            {}, {}, {27.321661},
            {0.318910, 1.26244},
            {1.769278, 3.551181, 7.154552, 16.689018},
            {15.94542, 1.370218, 4.518212, 0.942422, 1.887802, 2.736915, 79.3215},
            {8.706234, 13.46339, 4.144176, 1.376491, 1.413479},
            {5.876854}, {6.38723}, {}, {}, {}
    };
    public static final double[][] MOON_SIZE_RATIO = {
            {}, {}, {0.273},
            {0.11, 0.08},
            {0.286, 0.245, 0.413, 0.378},
            {0.404, 0.036, 0.152, 0.034, 0.024, 0.025, 0.009},
            {0.196, 0.182, 0.173, 0.096, 0.063},
            {0.212}, {0.501}, {}, {}, {}
    };
    public static final String[][] MOON_NAMES = {
            {}, {}, {"Moon"},
            {"Phobos", "Deimos"},
            {"Io", "Europa", "Ganymede", "Callisto"},
            {"Titan", "Enceladus", "Rhea", "Mimas", "Tethys", "Dione", "Iapetus"},
            {"Titania", "Oberon", "Umbriel", "Ariel", "Miranda"},
            {"Triton"}, {"Charon"}, {}, {}, {}
    };
    public static final double[][] MOON_ECC = {
            {}, {}, {0.05490},
            {0.01550, 0.00021},
            {0.00412, 0.00899, 0.00126, 0.00716},
            {0.02880, 0.00470, 0.00100, 0.00200, 0.00000, 0.00020, 0.02800},
            {0.00110, 0.00090, 0.00390, 0.00120, 0.00080},
            {0.000016}, {0.00259}, {}, {}, {}
    };
    public static final double[][] MOON_ARG_PERI = {
            {}, {}, {2.034},
            {Math.PI / 2, Math.PI},
            {1.88, 0.85, 1.02, 5.92},
            {0.20, 2.29, 5.80, 4.40, 0.0, 2.10, 1.20},
            {3.76, 0.44, 4.72, 2.50, 1.80},
            {Math.PI}, {0.0}, {}, {}, {}
    };
    /** Moon orbital-plane inclination relative to the parent's orbital plane (deg).
     *  Approximate; gives the moon systems a little 3-D spread. */
    public static final double[][] MOON_INCL_DEG = {
            {}, {}, {5.14},
            {1.08, 1.79},
            {0.04, 0.47, 0.20, 0.19},
            {0.33, 0.02, 0.35, 1.57, 1.09, 0.02, 7.57},
            {0.34, 0.10, 0.36, 0.04, 4.34},
            {157.0}, {0.0}, {}, {}, {}
    };

    public static final double[][] MOON_RADIUS_M = new double[COUNT][];
    static {
        for (int i = 0; i < COUNT; i++) {
            int n = MOON_SIZE_RATIO[i].length;
            MOON_RADIUS_M[i] = new double[n];
            for (int m = 0; m < n; m++) MOON_RADIUS_M[i][m] = RADIUS_M[i] * MOON_SIZE_RATIO[i][m];
        }
    }

    /* ---- rotation & surface-frame data (NASA planetary fact sheets) ---- */

    /** Sidereal rotation period (hours); negative = retrograde spin. */
    public static final double[] ROTATION_PERIOD_H = {
            1407.6, -5832.6, 23.9345, 24.6229,
            9.9250, 10.656, -17.24, 16.11, -153.29,
            9.07, 3.92, 25.9
    };

    /** Axial tilt / obliquity to orbit (deg). */
    public static final double[] OBLIQUITY_DEG = {
            0.034, 177.36, 23.44, 25.19,
            3.13, 26.73, 97.77, 28.32, 122.53,
            4.0, 0.0, 0.0
    };

    /** Sidereal rotation of the Sun at the equator (hours). */
    public static final double SUN_ROTATION_H = 609.12;

    /** Gravitational parameter mu = G*M of planet k (m^3/s^2). */
    public static double mu(int k) { return G * PLANET_MASS[k]; }
    public static final double MU_SUN = G * M_SUN;

    /**
     * Sphere-of-influence radius of planet k (m), r_SOI = a (m/M_sun)^(2/5).
     * Inside this radius the planet, not the Sun, is treated as the
     * dominant gravitational parent for flight telemetry and mode switching.
     */
    public static double soiRadius(int k) {
        return A_METERS[k] * Math.pow(PLANET_MASS[k] / M_SUN, 0.4);
    }

    /** Spin axis of planet k in the ecliptic frame (unit vector, tilted about +X). */
    public static Vec3 spinAxis(int k) {
        double eps = Math.toRadians(OBLIQUITY_DEG[k]);
        return new Vec3(0, Math.cos(eps), Math.sin(eps));
    }

    /** Signed spin rate of planet k (rad/s); sign encodes retrograde rotation. */
    public static double spinRate(int k) {
        return 2 * Math.PI / (ROTATION_PERIOD_H[k] * 3600.0);
    }

    /** Accumulated spin angle (rad) of planet k at simulation time t (s past J2000). */
    public static double spinAngleRad(int k, double simTime) {
        return (spinRate(k) * simTime) % (2 * Math.PI);
    }

    /**
     * Texture lookup key for planet k. The renderer searches
     * assets/textures/&lt;key&gt;_diffuse.{png,jpg}; companion
     * &lt;key&gt;_normal.* and &lt;key&gt;_specular.* paths are reserved for the
     * programmable-pipeline upgrade (see assets/textures/README.md).
     */
    public static String textureKey(int k) { return NAMES[k].toLowerCase(); }
}
