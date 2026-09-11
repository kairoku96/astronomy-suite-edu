import javax.swing.*;
import javax.swing.Timer;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.util.*;
import javax.imageio.ImageIO;
import com.formdev.flatlaf.FlatDarkLaf;

/**
 * Solar System Simulation – real-time 2-D orbital mechanics with textures,
 * smooth camera, moon-following and auto-zoom.
 *
 * <p>This program simulates the Sun and 9 major celestial bodies (including Pluto)
 * using Keplerian orbital elements. It supports:
 * <ul>
 *   <li>Real-time Newtonian physics via Kepler's equation</li>
 *   <li>Interactive camera: click to follow planets/moons</li>
 *   <li>Smooth panning and exponential zoom interpolation</li>
 *   <li>Auto-zoom when following bodies</li>
 *   <li>Moon systems with independent orbits</li>
 *   <li>Orbit path rendering and dynamic HUD</li>
 * </ul>
 *
 * N-body mode (toggle with 'N') replaces the Kepler update with a velocity-Verlet
 * integrator that accounts for mutual gravitational attraction between planets
 * and the central Sun (Sun is fixed at origin).
 *
 * @author Ethan Lin (original)
 * @version 3.0
 */

//TODO: Sympletic Integration - leapfrog, rebound, saturn ring shadow, UI polish, API for planet data

public class SolarSystemSimulation extends JPanel
        implements ActionListener, MouseWheelListener, MouseListener, MouseMotionListener {

    /* ==============================
       PHYSICAL CONSTANTS (SI UNITS)
       ============================== */
    /** 1 Astronomical Unit in meters */
    private static final double AU  = 1.496e11;
    /** Seconds in one Earth day */
    private static final double DAY = 86_400.0;
    /** Seconds in one Earth year */
    private static final double YEAR = 365.25 * DAY;
    /** Gravitational constant (m³ kg⁻¹ s⁻²) */
    private static final double G   = 6.67430e-11;
    /** Mass of the Sun (kg) */
    private static final double M_SUN = 1.98847e30;
    /** Mutable Sun mass used in simulation (can be changed at runtime) */
    private final double sunMass = M_SUN;

    /** Conversion factor: meters → screen pixels at base zoom (250 px per AU) */
    private static final double METERS_TO_PIXELS = 250.0 / AU;

    /* ==============================
       CAMERA SYSTEM
       ============================== */
    /** Index of currently selected planet (-1 = none) */
    private int selectedPlanet = -1;
    /** Whether camera is locked to a planet */
    private boolean followingPlanet = false;
    /** Index of planet currently tracked by camera */
    private int     cameraFocusIndex = -1;
    /** Current camera center in world coordinates (meters) */
    private double  camX = 0, camY = 0;
    /** Camera-centre height above the ecliptic (m); tracks a followed body's Z so it stays centred. */
    private double  camZ = 0;

    /**
     * View pitch: how far the camera is tilted off straight-down (radians). 0 = the original
     * top-down view (looking along the ecliptic normal); larger tilts toward edge-on, which is
     * what reveals orbital inclination and the out-of-plane oscillation. Free-dragged with the
     * mouse (or [ / ] keys); clamped to [0, ~88°]. {@link #viewCosP}/{@link #viewSinP} cache
     * its cos/sin and are refreshed by {@link #setViewPitch}.
     */
    private double viewPitchRad = 0.0;
    private double viewCosP = 1.0, viewSinP = 0.0;
    static final double MAX_PITCH_RAD = Math.toRadians(88);

    /** True if camera is still moving toward target (smooth approach) */
    private boolean cameraIsApproaching = false;
    /** Distance threshold (m) to stop smoothing camera motion */
    private static final double ARRIVAL_THRESHOLD = 1e7;

    // ----- Moon selection -----
    /** Index of selected moon on current planet */
    private int selectedMoon = -1;
    /** Index of moon being tracked by camera */
    private int   cameraFocusMoon = -1;
    /** Camera center when following a moon */
    private double moonCamX = 0, moonCamY = 0;
    /** Whether camera is locked to a moon */
    private boolean followingMoon = false;
    /** True if moon camera is approaching target */
    private boolean moonCameraIsApproaching = false;

    // ----- Visibility & UI toggles -----
    /** Whether to draw all planet orbits */
    private boolean showPlanetOrbits = true;
    /** Whether to draw planetary rings (e.g., Saturn) */
    private boolean showRings = true;
    /** Whether to draw text labels for bodies */
    private boolean showLabels = true;
    /** If true, only the selected planet (and its moons) is rendered */
    private boolean showOnlySelected = false;
    /** If true, show a small Sun info panel (toggled with U) */
    private boolean showSunInfo = false;

    // ----- Asteroid and Debris Belt Visibility -----
    /** Whether to draw the asteroid belt */
    private boolean showAsteroidBelt = false;
    /** Whether to draw the Kuiper belt */
    private boolean showKuiperBelt = false;
    /** Whether to draw ice lines (frost line) */
    private boolean showIceLines = false;

    // ----- Visibility toggles for individual planets -----
    /** Array tracking whether each planet should be visible */
    private final boolean[] planetVisible = new boolean[12];

    // ----- UI components -----
    /** Reference to the main menu bar for toggling visibility */
    private JFrame parentFrame;
    /** Whether menu bar is currently visible */
    private boolean menuBarVisible = true;
    /** Reference to the search bar panel for toggling visibility */
    private JPanel searchBarPanel;
    /** Reference to the sidebar/navigation panel for toggling visibility */
    private JPanel sidebarPanel;
    /** Whether search bar and sidebar are currently visible */
    private boolean uiPanelsVisible = true;
    /** Text field used by the top search bar to jump to bodies */
    private JTextField searchField;
    /** Popup list of name suggestions for the search field */
    private JPopupMenu searchSuggestionsPopup;
    /** List component that shows filtered planet/moon names */
    private JList<String> searchSuggestionsList;
    /** Tree navigation of planets and moons */
    private JTree bodyTree;
    /** Tree model for navigator */
    private DefaultTreeModel bodyTreeModel;
    /** Root node of the tree */
    private DefaultMutableTreeNode treeRootNode;
    /** Array of planet tree nodes */
    private DefaultMutableTreeNode[] planetTreeNodes;
    /** Cached paths to planet nodes */
    private TreePath[] planetTreePaths;
    /** Cached paths to moon nodes */
    private TreePath[][] moonTreePaths;
    /** Guard to prevent recursive selection syncing */
    private boolean suppressTreeSelection;

    // ----- Time controls -----
    private static final double TIME_SCALE_MIN = 1.0; // minimum 1.0 s/s
    private static final double TIME_SCALE_MAX = 100 * YEAR;
    private static final int TIME_SLIDER_MIN = 0;
    private static final int TIME_SLIDER_MAX = 1000;
    /** True when the simulation clock is paused */
    private boolean simulationPaused = false;
    /** Slider used to adjust time scale linearly */
    private JSlider timeScaleSlider;
    /** Label reflecting the current time scale and pause state */
    private JLabel timeScaleLabel;
    /** Prevent slider event loops when syncing from code */
    private boolean suppressTimeSliderEvents = false;


    // ----- Scale ruler dragging -----
    /** Current X position of scale ruler (default bottom-left) */
    private int scaleRulerX = 20;
    /** Current Y position of scale ruler (default bottom-left) */
    private int scaleRulerY = -60; // Relative to bottom (negative)
    /** Whether scale ruler is being dragged */
    private boolean scaleDragging = false;
    /** Mouse offset when dragging scale ruler */
    private int scaleDragOffsetX = 0, scaleDragOffsetY = 0;

    // ----- Barycenter motion variables -----
    /** Sun's X position offset due to barycentric motion */
    private double sunBarycenterX = 0.0;
    /** Sun's Y position offset due to barycentric motion */
    private double sunBarycenterY = 0.0;

    /** Toggle: auto-adjust zoom when following bodies */
    private boolean autoZoomEnabled = false;
    /** Target zoom level (auto-computed) */
    private double targetZoom = 1.0;
    /** Current zoom level (smoothly interpolated) */
    private double currentZoom = 1.0;
    /** Speed of zoom interpolation (higher = faster) */
    private static final double ZOOM_SPEED = 10.0;
    private int zoomMode = 0;                   // 0=Planet, 1=Orbit, 2=Manual

    // ----- Educational features for lessons -----
    private boolean lessonMode = false;
    private int currentLesson = 1;              // Default to Lesson 1
    private boolean showKeplersLaw = false;     // Show Kepler's 3rd Law calculations
    private boolean showPerihelionAphelion = false; // Highlight perihelion/aphelion points
    private boolean showVelocityVectors = false;    // Show velocity vectors on planets
    private boolean showVelocityComparison = false; // Show velocity comparison at perihelion vs aphelion

    // ----- Guided Tour System -----
    private boolean guidedTourActive = false;
    private int tourStep = 0;
    private double tourStepStartTime = 0;
    private String tourMessage = "";
    private double tourMessageAlpha = 0.0;
    private static final double TOUR_MESSAGE_FADE_SPEED = 2.0;
    private boolean tourStepReady = false;  // True when user can advance to next step
    private boolean[] savedPlanetVisibility = null;  // Save visibility state before tour

    // ----- UI Size Settings -----
    enum UISize { SMALL, NORMAL, LARGE }
    private UISize uiSize = UISize.NORMAL;  // Default to normal (previously small)

    // ----- Orbit visibility thresholds -----
    /** Zoom level at which orbits become visible */
    private static final double ORBIT_VISIBLE_ZOOM = 5.0;

    /* ==============================
       SIMULATION STATE
       ============================== */
    /** Time acceleration factor (1 = real-time, 1000 = 1000× faster) */
    private double timeScale  = DAY;
    /** Total simulated time elapsed (seconds) */
    private double simTimeSec = 0;
    /** Timestamp of last frame (nanoseconds) */
    private long   lastNanos;

    /* ==============================
       PLANET DATA (Keplerian Elements)
       ============================== */
    /** Names of celestial bodies */
    private static final String[] NAMES = {
            "Mercury", "Venus", "Earth", "Mars",
            "Jupiter", "Saturn", "Uranus", "Neptune", "Pluto",
            "Ceres", "Haumea", "Eris"
    };

    /** Body type classification for orbit coloring (planet, gas_giant, dwarf_planet) */
    private static final String[] BODY_TYPE = {
            "planet", "planet", "planet", "planet",
            "gas_giant", "gas_giant", "gas_giant", "gas_giant", "dwarf_planet",
            "dwarf_planet", "dwarf_planet", "dwarf_planet"
    };

    /** Semi-major axis (m) */
    private static final double[] A_METERS = {
            5.7909e10, 1.0821e11, 1.495978707e11, 2.2794e11,
            7.7854e11, 1.4298e12, 2.8725e12, 4.5045e12, 5.9064e12,
            2.767 * AU, 43.335 * AU, 67.781 * AU
    };

    /** Orbital eccentricity */
    private static final double[] ECC = {
            0.205630, 0.006772, 0.016709, 0.093405,
            0.048498, 0.055546, 0.046381, 0.008956, 0.248827,
            0.0758, 0.1887, 0.4407
    };

    /** Orbital period (Earth years) */
    private static final double[] PERIOD_Y = {
            0.2408467, 0.61519726, 1.0000174, 1.8808476,
            11.862615, 29.447498, 84.016846, 164.79132, 247.92065,
            4.60, 284.0, 557.0
    };

    /** Physical radius (m) */
    private static final double[] RADIUS_M = {
            2.439e6, 6.052e6, 6.371e6, 3.390e6,
            6.9911e7, 5.8232e7, 2.5362e7, 2.4622e7, 1.1883e6,
            4.73e5, 8.16e5, 1.163e6
    };

    /**
     * Atmospheric limb-glow tint per body (RGB). {@code null} = airless (no glow).
     * The shell is drawn as a thin, day-side-brightened scattering halo around the
     * limb; {@link #ATMO_STRENGTH} sets peak opacity and {@link #ATMO_HEIGHT} the
     * scale height (in planet radii) that controls how far it bleeds past the edge.
     */
    private static final int[][] ATMO_RGB = {
            null,                    // Mercury  – airless
            {255, 244, 210},         // Venus    – thick, pale sulphuric haze
            {150, 205, 255},         // Earth    – blue Rayleigh sky
            {235, 150, 110},         // Mars     – thin, dusty pink
            {255, 226, 180},         // Jupiter  – warm ammonia bands
            {240, 224, 178},         // Saturn   – pale gold
            {180, 236, 240},         // Uranus   – cyan methane
            {120, 165, 255},         // Neptune  – deep blue methane
            {220, 212, 205},         // Pluto    – very thin N2 haze
            null, null, null         // Ceres, Haumea, Eris – airless/none
    };
    /** Peak opacity of each body's atmospheric halo (0 = none). */
    private static final double[] ATMO_STRENGTH = {
            0.0, 0.85, 0.80, 0.38, 0.48, 0.42, 0.55, 0.62, 0.16, 0.0, 0.0, 0.0
    };
    /** Atmospheric scale height as a fraction of planet radius (halo bleed distance). */
    private static final double[] ATMO_HEIGHT = {
            0.0, 0.11, 0.060, 0.045, 0.050, 0.048, 0.052, 0.052, 0.030, 0.0, 0.0, 0.0
    };

    /** Mean longitude at J2000 (degrees) */
    private static final double[] PLANET_L0 = {
            252.25084, 181.97973, 100.46435, 355.45332, 34.40438,
            49.94432, 313.23218, 304.88003, 238.92881,
            181.0, 210.0, 35.0
    };

    /** Longitude of perihelion at J2000 (degrees) */
    private static final double[] PLANET_VARPI0 = {
            77.45736, 131.60261, 102.93735, 336.04084, 14.75385,
            92.43194, 170.96424, 44.97135, 113.76329,
            73.0, 122.0, 151.0
    };

    /** Longitude of ascending node at J2000 (degrees) */
    private static final double[] PLANET_OMEGA0 = {
            48.33167, 76.67992, 0.0, 49.57854, 100.55615,
            113.71504, 74.22950, 131.72169, 110.30347,
            80.0, 122.0, 36.0
    };

    /**
     * Orbital inclination to the ecliptic at J2000 (degrees). This is what tilts each
     * orbit out of the reference plane, so a body rises above the ecliptic near its
     * ascending node and dips below near the descending node — the out-of-plane
     * "orbital oscillation" of one period. Real values (true scale): most planets are
     * within a few degrees, Pluto/dwarf bodies far steeper.
     */
    private static final double[] PLANET_INCL_DEG = {
            7.00487, 3.39471, 0.00005, 1.85061, 1.30530,
            2.48446, 0.76986, 1.76917, 17.14175,
            10.593, 28.19, 44.04
    };

    // --- Secular precession: linear rates of the mean elements per Julian century
    //     (Standish/JPL for the 8 planets + Pluto; 0 for the other dwarf bodies).
    //     Applied over sim time so the ascending node slowly regresses and the
    //     perihelion precesses — only perceptible at high time-acceleration.

    /** d(longitude of ascending node)/dt (deg per Julian century). */
    private static final double[] PLANET_OMEGA_DOT = {
            -0.12534081, -0.27769418, 0.0, -0.29257343, 0.20469106,
            -0.28867794, 0.04240589, -0.00508664, -0.01183482,
            0.0, 0.0, 0.0
    };
    /** d(longitude of perihelion)/dt (deg per Julian century). */
    private static final double[] PLANET_VARPI_DOT = {
            0.16047689, 0.00268329, 0.32327364, 0.44441088, 0.21252668,
            -0.41897216, 0.40805281, -0.32241464, -0.04062942,
            0.0, 0.0, 0.0
    };
    /** d(inclination)/dt (deg per Julian century). */
    private static final double[] PLANET_INCL_DOT = {
            -0.00594749, -0.00078890, -0.01294668, -0.00813131, -0.00183714,
            0.00193609, -0.00242939, 0.00035372, 0.00039054,
            0.0, 0.0, 0.0
    };

    /** Sun's radius (m) */
    private static final double SUN_RADIUS_M = 6.9634e8;

    /** Visual color for each planet */
    private static final Color[] COLORS = {
            new Color(169, 169, 169),   // Mercury – dark gray, rocky
            new Color(210, 180, 140),   // Venus – pale tan/yellowish beige
            new Color(81, 150, 208),    // Earth – bluish with some green tones
            new Color(188, 39, 50),     // Mars – reddish ochre
            new Color(255, 204, 102),   // Jupiter – light orange-brown (cloud bands)
            new Color(194, 178, 128),   // Saturn – muted golden tan
            new Color(173, 216, 230),   // Uranus – light cyan with a hint of green
            new Color(72, 118, 255),    // Neptune – deep azure blue
            new Color(233, 226, 214),   // Pluto – pale off-white/icy beige
            new Color(180, 200, 210),   // Ceres – muted light gray/blue
            new Color(210, 230, 255),   // Haumea – icy bluish-white
            new Color(190, 205, 220)    // Eris – cold pale blue-gray
    };

    /** Orbit colors based on body type */
    private static final java.util.Map<String, Color> ORBIT_COLORS_BY_TYPE = java.util.Collections.unmodifiableMap(
            new java.util.HashMap<>() {{
                put("planet", new Color(200, 100, 150, 100));          // Purple/magenta for terrestrial planets
                put("gas_giant", new Color(210, 170, 80, 100));        // Golden/tan for gas giants
                put("dwarf_planet", new Color(180, 140, 180, 100));    // Purple-gray for dwarf planets
            }}
    );

    private static final double[] PLANET_MASS = {
            3.3011e23, 4.8675e24, 5.97237e24, 6.4171e23,
            1.8982e27, 5.6834e26, 8.6810e25, 1.02413e26, 1.303e22,
            9.39e20, 4.01e21, 1.66e22
    };

    /** Sidereal rotation period (hours); negative = retrograde spin. NASA fact sheets. */
    private static final double[] ROTATION_PERIOD_H = {
            1407.6, -5832.6, 23.9345, 24.6229,
            9.9250, 10.656, -17.24, 16.11, -153.29,
            9.07, 3.92, 25.9
    };

    /** Axial tilt / obliquity to orbit (deg). NASA fact sheets. */
    private static final double[] OBLIQUITY_DEG = {
            0.034, 177.36, 23.44, 25.19,
            3.13, 26.73, 97.77, 28.32, 122.53,
            4.0, 0.0, 0.0
    };

    /** Sidereal rotation of the Sun at the equator (hours). */
    private static final double SUN_ROTATION_H = 609.12;
    /** Moon (Luna) sidereal rotation (tidally locked) and obliquity to ecliptic. */
    private static final double MOON_ROTATION_H = 655.72;
    private static final double MOON_OBLIQUITY_DEG = 6.68;

    /* ==============================
       MOON DATA
       ============================== */
    /** Semi-major axis of moons relative to parent planet (m) */
    private static final double[][] MOON_A_M = {
            {}, {}, {3.844e8},
            {9.377e6, 2.356e7},
            {4.217e8, 6.71e8, 1.07e9, 1.883e9},
            {1.222e9, 2.38e8, 5.27e8, 1.861e8, 2.946e8, 3.561e8, 3.561e9},
            {4.363e8, 5.835e8, 4.989e8, 1.913e8, 1.299e8},
            {3.547e8}, {1.195e7}, {}, {}, {}
    };

    /** Moon orbital period (Earth days) */
    private static final double[][] MOON_PERIOD_DAYS = {
            {}, {}, {27.321661},
            {0.318910, 1.26244},
            {1.769278, 3.551181, 7.154552, 16.689018},
            {15.94542, 1.370218, 4.518212, 0.942422, 1.887802, 2.736915, 79.3215},
            {8.706234, 13.46339, 4.144176, 1.376491, 1.413479},
            {5.876854}, {6.38723}, {}, {}, {}
    };

    /** Moon radius as ratio of parent planet radius */
    private static final double[][] MOON_SIZE_RATIO = {
            {}, {}, {0.273},
            {0.11, 0.08},
            {0.286, 0.245, 0.413, 0.378},
            {0.404, 0.036, 0.152, 0.034, 0.024, 0.025, 0.009},
            {0.196, 0.182, 0.173, 0.096, 0.063},
            {0.212}, {0.501},
            {}, {}, {}
    };

    /** Names of moons per planet */
    private static final String[][] MOON_NAMES = {
            {}, {}, {"Moon"},
            {"Phobos", "Deimos"},
            {"Io", "Europa", "Ganymede", "Callisto"},
            {"Titan", "Enceladus", "Rhea", "Mimas", "Tethys", "Dione", "Iapetus"},
            {"Titania", "Oberon", "Umbriel", "Ariel", "Miranda"},
            {"Triton"}, {"Charon"}, {}, {}, {}
    };

    /** Moon orbital eccentricity */
    private static final double[][] MOON_ECC = {
            {}, {}, {0.05490},
            {0.01550, 0.00021},
            {0.00412, 0.00899, 0.00126, 0.00716},
            {0.02880, 0.00470, 0.00100, 0.00200, 0.00000, 0.00020, 0.02800},
            {0.00110, 0.00090, 0.00390, 0.00120, 0.00080},
            {0.000016}, {0.00259}, {}, {}, {}
    };

    /** Argument of periapsis for moons (radians) */
    private static final double[][] MOON_ARG_PERI = {
            {}, {}, {2.034},
            {Math.PI/2, Math.PI},
            {1.88, 0.85, 1.02, 5.92},
            {0.20, 2.29, 5.80, 4.40, 0.0, 2.10, 1.20},
            {3.76, 0.44, 4.72, 2.50, 1.80},
            {Math.PI}, {0.0}, {}, {}, {}
    };

    /**
     * Moon orbital inclination to the parent's equator/ecliptic (degrees). This tilts the
     * moon out of the orbital plane, which is what makes eclipse shadows fall at real
     * latitudes (and makes eclipses rare) rather than always crossing the sub-solar point.
     * The Moon's 5.145° to the ecliptic is the physically important one.
     */
    private static final double[][] MOON_INCL_DEG = {
            {}, {}, {5.145},
            {1.08, 1.79},
            {0.036, 0.467, 0.177, 0.192},
            {0.33, 0.02, 0.35, 1.57, 1.09, 0.02, 7.57},
            {0.34, 0.10, 0.36, 0.26, 4.34},
            {156.885}, {0.08}, {}, {}, {}
    };

    /**
     * Longitude of the ascending node for each moon (degrees); sets where its orbit crosses
     * the reference plane. The Moon's node regresses over 18.6 yr, so any value is valid at
     * some epoch — {@code stageEclipse} nudges Earth's node to line an eclipse up on demand.
     */
    private static double[][] MOON_NODE_DEG = {
            {}, {}, {125.08},
            {0.0, 0.0},
            {0.0, 0.0, 0.0, 0.0},
            {0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0},
            {0.0, 0.0, 0.0, 0.0, 0.0},
            {0.0}, {0.0}, {}, {}, {}
    };

    /** Computed moon radii (planet radius × ratio) */
    private static final double[][] MOON_RADIUS_M = new double[NAMES.length][];

    /* Static initializer: compute actual moon radii */
    static {
        for (int i = 0; i < NAMES.length; i++) {
            int n = MOON_SIZE_RATIO[i].length;
            MOON_RADIUS_M[i] = new double[n];
            for (int m = 0; m < n; m++) {
                MOON_RADIUS_M[i][m] = RADIUS_M[i] * MOON_SIZE_RATIO[i][m];
            }
        }
    }

    /* ==============================
       ORBITAL STATE
       ============================== */
    /** Current mean anomaly for each planet (radians) */
    private final double[] meanAnomaly = new double[NAMES.length];
    /** Mean anomaly for each moon (per planet) */
    private final double[][] moonMean = new double[NAMES.length][];

    /** Number of points to use when drawing orbits */
    private static final int ORBIT_RESOLUTION = 10000;

    /** Precomputed moon orbit paths (relative to parent planet) */
    private final ArrayList<ArrayList<ArrayList<Point2D.Double>>> moonOrbitPaths = new ArrayList<>();

    /** Current world position of planets (m) */
    private final double[] planetX = new double[NAMES.length];
    private final double[] planetY = new double[NAMES.length];
    /** Height above the ecliptic reference plane (m); non-zero once orbits are inclined. */
    private final double[] planetZ = new double[NAMES.length];
    /** Distance from Sun (m) */
    private final double[] planetDistM = new double[NAMES.length];
    /** Orbital speed (km/s) */
    private final double[] planetSpeedKmS = new double[NAMES.length];

    /* ==============================
   SHADING SYSTEM
   ============================== */
    private boolean shadingEnabled = true;
    /** Atmospheric limb glow (thin scattering shell on bodies with an atmosphere). */
    private boolean showAtmosphere = true;
    /** Mutual eclipse shadows between a planet and its moons (shadow transits + lunar eclipses). */
    private boolean showEclipses = true;
    /** Emissive city lights on the night side of bodies with a night map (Earth). */
    private boolean showNightLights = true;
    /** Rotating cloud layer on bodies with a cloud map (Earth). */
    private boolean showClouds = true;
    /**
     * Tier-2 "GLSL pipeline": shade textured spheres on the GPU via {@link GLSphereRenderer}
     * instead of the per-pixel CPU loop in {@link #drawTexturedSphere}. Off by default; the
     * CPU path is always kept as a fallback (and used automatically if the GPU is unavailable).
     * Enabled by key <b>G</b> / the View menu, or eagerly via {@code -Dsss.gpu}.
     */
    private boolean gpuSpheres = System.getProperty("sss.gpu") != null;
    /** Lazily-created off-screen GPU sphere shader; null until first needed, then cached. */
    private GLSphereRenderer glSpheres;
    private boolean glSpheresTried = false;

    /**
     * The GPU sphere renderer, created on first use. Returns null (and leaves
     * {@link #gpuSpheres} logically inert) if an off-screen GL context can't be made,
     * so the caller silently keeps the CPU path.
     */
    private GLSphereRenderer gpuRenderer() {
        if (glSpheres == null && !glSpheresTried) {
            glSpheresTried = true;
            try {
                glSpheres = new GLSphereRenderer();
                if (!glSpheres.isAvailable()) {
                    System.err.println("GPU spheres unavailable, using CPU path: " + glSpheres.lastError());
                }
            } catch (Throwable t) {
                System.err.println("GPU sphere renderer init failed, using CPU path: " + t);
                glSpheres = null;
            }
        }
        return glSpheres;
    }

    // No collision effects when strictly Keplerian.

    /* ==============================
       CONSTRUCTOR
       ============================== */
    /**
     * Initializes the simulation panel, sets up UI, precomputes orbits,
     * and starts the animation timer.
     */
    public SolarSystemSimulation() {
        setBackground(Color.BLACK);
        setPreferredSize(new Dimension(1200, 800));
        addMouseWheelListener(this);
        addMouseListener(this);
        addMouseMotionListener(this);

        // Initialize all planets as visible by default
        Arrays.fill(planetVisible, true);
        // Hide dwarf planets except Pluto (Pluto=8, Ceres=9, Haumea=10, Eris=11)
        planetVisible[9] = false;  // Ceres
        planetVisible[10] = false; // Haumea
        planetVisible[11] = false; // Eris

        // Initialize planet positions at J2000 epoch
        for (int i = 0; i < NAMES.length; i++) {
            double L_deg = PLANET_L0[i];
            double varpi_deg = PLANET_VARPI0[i];
            double M_deg = L_deg - varpi_deg;  // Mean anomaly at epoch
            meanAnomaly[i] = Math.toRadians(M_deg % 360.0);

            double[] pos = planetXYZ(i);   // 3-D from precessed elements (at epoch, precession = 0)
            planetX[i] = pos[0];
            planetY[i] = pos[1];
            planetZ[i] = pos[2];
            planetDistM[i] = Math.sqrt(pos[0] * pos[0] + pos[1] * pos[1] + pos[2] * pos[2]);
        }

        // Initialize moon mean anomaly arrays
        for (int i = 0; i < NAMES.length; i++) {
            int num = MOON_NAMES[i].length;
            moonMean[i] = new double[num];
        }
        // Planet orbit rings are built on the fly (getADouble) from current precessed 3-D
        // elements, so there is no static orbit-path table to precompute here anymore.

        // Pre-compute moon orbit paths (rotated by argument of periapsis)
        for (int i = 0; i < NAMES.length; i++) {
            ArrayList<ArrayList<Point2D.Double>> moonOrbits = new ArrayList<>();
            for (int m = 0; m < MOON_A_M[i].length; m++) {
                ArrayList<Point2D.Double> orbit = new ArrayList<>();
                double a = MOON_A_M[i][m], e = MOON_ECC[i][m], b = a * Math.sqrt(1 - e * e);
                double arg = MOON_ARG_PERI[i][m];
                for (int j = 0; j <= ORBIT_RESOLUTION; j++) {
                    double E = 2 * Math.PI * j / ORBIT_RESOLUTION;
                    double xTemp = a * (Math.cos(E) - e);
                    double yTemp = b * Math.sin(E);
                    double rxTemp = xTemp * Math.cos(arg) - yTemp * Math.sin(arg);
                    // Apply 90° clockwise rotation to fix coordinate system
                    double rx = xTemp * Math.sin(arg) + yTemp * Math.cos(arg);
                    double ry = -rxTemp;
                    orbit.add(new Point2D.Double(rx, ry));
                }
                moonOrbits.add(orbit);
            }
            moonOrbitPaths.add(moonOrbits);
        }

        // Key listener: Z toggles auto-zoom, S toggles shading.
        setFocusable(true);
        // Direct listener so the canvas responds when it holds focus.
        addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (handleGlobalKey(e.getKeyCode())) e.consume();
            }
        });
        // App-wide dispatcher so the toggle keys work no matter which widget
        // (tree, sliders, buttons, canvas) currently holds focus. Typing into a
        // text field is left untouched so the search box still works.
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
            if (e.getID() != KeyEvent.KEY_PRESSED) return false;
            Component focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            if (focusOwner instanceof javax.swing.text.JTextComponent) return false;
            // Only act while this simulation's window is the active one.
            Window w = SwingUtilities.getWindowAncestor(this);
            if (w == null || !w.isActive()) return false;
            return handleGlobalKey(e.getKeyCode());
        });
        // Start 60 FPS timer (skipped in off-screen screenshot mode, where the sim
        // is stepped explicitly and a background tick would fight the staged camera).
        // Optional initial view tilt (degrees off top-down), e.g. -Dsss.pitch=25 or for shots.
        try {
            String pitchProp = System.getProperty("sss.pitch");
            if (pitchProp != null) setViewPitch(Math.toRadians(Double.parseDouble(pitchProp)));
        } catch (NumberFormatException ignored) { }
        lastNanos = System.nanoTime();
        if (System.getProperty("sss.shot") == null) {
            Timer timer = new Timer(16, this);
            timer.start();
        }
    }

    /**
     * Central handler for the global toggle keybinds. Returns true when the key
     * was one we handle (so callers can consume the event). Used by both the
     * canvas KeyListener and the app-wide KeyEventDispatcher, so the same keys
     * behave identically regardless of which component holds focus.
     */
    boolean handleGlobalKey(int code) {
        switch (code) {
            case KeyEvent.VK_Z -> {
                autoZoomEnabled = !autoZoomEnabled;
                if (!autoZoomEnabled) targetZoom = currentZoom;
            }
            case KeyEvent.VK_X -> {
                // Cycle zoom mode: Planet -> Orbit -> Manual
                if (followingPlanet && cameraFocusIndex >= 0) {
                    zoomMode = (zoomMode + 1) % 3;
                    updateAutoZoomTarget();
                }
            }
            case KeyEvent.VK_S -> shadingEnabled = !shadingEnabled;
            case KeyEvent.VK_A -> showAtmosphere = !showAtmosphere;
            case KeyEvent.VK_E -> showEclipses = !showEclipses;
            case KeyEvent.VK_C -> showClouds = !showClouds;
            case KeyEvent.VK_I -> showNightLights = !showNightLights;
            case KeyEvent.VK_G -> {
                gpuSpheres = !gpuSpheres;
                if (gpuSpheres) {
                    GLSphereRenderer r = gpuRenderer();
                    if (r == null || !r.isAvailable()) gpuSpheres = false;  // no GPU: stay on CPU
                }
            }
            // View pitch: [ tilts back toward top-down, ] tilts toward edge-on, \ resets.
            case KeyEvent.VK_OPEN_BRACKET  -> setViewPitch(viewPitchRad - Math.toRadians(5));
            case KeyEvent.VK_CLOSE_BRACKET -> setViewPitch(viewPitchRad + Math.toRadians(5));
            case KeyEvent.VK_BACK_SLASH    -> setViewPitch(0.0);
            case KeyEvent.VK_O -> showPlanetOrbits = !showPlanetOrbits;
            case KeyEvent.VK_R -> showRings = !showRings;
            case KeyEvent.VK_L -> showLabels = !showLabels;
            case KeyEvent.VK_B -> showOnlySelected = !showOnlySelected;
            case KeyEvent.VK_SPACE, KeyEvent.VK_P -> {
                simulationPaused = !simulationPaused;
                updateTimeScaleLabel();
            }
            case KeyEvent.VK_M -> {
                // Cycle the selected planet (its mass/stats show in the info panel)
                for (int i = 0; i < NAMES.length; i++) {
                    if (selectedPlanet == i) { selectedPlanet = -1; break; }
                }
                selectedPlanet = (selectedPlanet + 1) % NAMES.length;
            }
            case KeyEvent.VK_U -> showSunInfo = !showSunInfo;
            case KeyEvent.VK_H -> toggleMenuBarVisibility();
            case KeyEvent.VK_N -> {
                if (guidedTourActive && tourStepReady) advanceTourStep();
            }
            default -> { return false; }
        }
        repaint();
        return true;
    }

    // Helper: whether a given planet should be drawn under current visibility settings
    private boolean isPlanetVisible(int planetIndex) {
        // Check individual planet visibility toggle
        if (planetIndex >= 0 && planetIndex < planetVisible.length && !planetVisible[planetIndex]) {
            return true;
        }
        // Check showOnlySelected mode
        if (!showOnlySelected) return false;
        if (selectedPlanet == -1) return false;
        return planetIndex != selectedPlanet;
    }

    /* ==============================
       INPUT HANDLING
       ============================== */
    @Override
    public void mouseWheelMoved(MouseWheelEvent e) {
        if (e.isControlDown()) {
            // Ctrl+Scroll: adjust time scale
            double proposed = e.getPreciseWheelRotation() < 0 ? timeScale * 2 : timeScale / 2;
            proposed = Math.max(1.0, proposed); // clamp to 1.0 s/s minimum
            setTimeScaleAndSync(proposed);
        } else {
            // Normal scroll: zoom
            double factor = e.getPreciseWheelRotation() < 0 ? 1.1 : 1/1.1;
            currentZoom *= factor;
            currentZoom = Math.max(0.02, Math.min(currentZoom, 50000));
            targetZoom = currentZoom;
        }
    }

    /**
     * Handles click logic: select planet → moon → unfollow
     */
    private void handleClick(int mx, int my) {
        exitSearchModeIfActive();
        int cx = getWidth() / 2, cy = getHeight() / 2;

        // MOON CLICK (only when zoomed in and following planet)
        if (followingPlanet && cameraFocusIndex >= 0 && currentZoom > 10) {
            int p = cameraFocusIndex;
            for (int m = 0; m < MOON_A_M[p].length; m++) {
                // Compute moon world position (3-D), matching the draw projection
                double[] rel = moonRelPos(p, m);
                double moonWorldX = planetX[p] + sunBarycenterX + rel[0];
                double moonWorldY = planetY[p] + sunBarycenterY + rel[1];
                double moonWorldZ = planetZ[p] + rel[2];

                double mpx = projX(moonWorldX);
                double mpy = projY(moonWorldY, moonWorldZ);
                double mr  = Math.max(RADIUS_M[p] * MOON_SIZE_RATIO[p][m] * METERS_TO_PIXELS * currentZoom, 2.0);

                double dx = mx - mpx, dy = my - mpy;
                if (dx * dx + dy * dy < mr * mr * 4) {
                    if (cameraFocusMoon == m && followingMoon) {
                        // Unfollow moon → back to planet
                        followingMoon = false;
                        cameraFocusMoon = -1;
                        selectedMoon = -1;
                        moonCameraIsApproaching = false;
                        // Use barycentric coordinates
                        camX = planetX[p] + sunBarycenterX;
                        camY = planetY[p] + sunBarycenterY;
                        // Snap immediately back to planet view
                        focusPlanetImmediate(p);
                    } else {
                        // Follow moon
                        followingMoon = true;
                        moonCameraIsApproaching = false; // we'll snap immediately
                        cameraFocusMoon = m;
                        selectedMoon = m;
                        cameraIsApproaching = false;
                        // Snap immediately to moon position and zoom
                        focusMoonImmediate(p, m);
                    }
                    return;
                }
            }
        }

        // PLANET CLICK
        for (int i = 0; i < NAMES.length; i++) {
            // Use barycentric coordinates for click detection
            double px = projX(planetX[i] + sunBarycenterX);
            double py = projY(planetY[i] + sunBarycenterY, planetZ[i]);
            double pr = Math.max(RADIUS_M[i] * METERS_TO_PIXELS * currentZoom, 4);

            double dx = mx - px, dy = my - py;
            if (dx * dx + dy * dy < pr * pr * 4) {
                if (cameraFocusIndex == i && followingPlanet && !followingMoon) {
                    // Unfollow everything
                    followingPlanet = false;
                    cameraFocusIndex = -1;
                    cameraFocusMoon = -1;
                    selectedPlanet = -1;
                    selectedMoon = -1;
                    cameraIsApproaching = false;
                    moonCameraIsApproaching = false;
                    autoZoomEnabled = false;
                    currentZoom = 1.0;
                } else {
                    // Follow planet
                    followingPlanet = true;
                    followingMoon = false;
                    cameraFocusIndex = i;
                    selectedPlanet = i;
                    selectedMoon = -1;
                    cameraFocusMoon = -1;
                    autoZoomEnabled = true;
                    // Snap immediately to planet position and zoom
                    focusPlanetImmediate(i);
                }
                return;
            }
        }

        // CLICKED EMPTY SPACE → return to Sun
        followingPlanet = followingMoon = false;
        cameraFocusIndex = cameraFocusMoon = -1;
        selectedPlanet = selectedMoon = -1;
        cameraIsApproaching = moonCameraIsApproaching = false;
        currentZoom = 1.0;
    }

    @Override public void mouseClicked(MouseEvent e) {
        if (!handleScaleRulerClick(e.getX(), e.getY(), getHeight())) {
            handleClick(e.getX(), e.getY());
        }
    }
    @Override public void mousePressed(MouseEvent e) {
        boolean onRuler = handleScaleRulerClick(e.getX(), e.getY(), getHeight());
        draggingView = !onRuler;                 // otherwise a bare drag tilts the view
        lastDragY = e.getY();
    }
    @Override public void mouseReleased(MouseEvent e) {
        stopScaleRulerDrag();
        draggingView = false;
    }
    @Override public void mouseEntered(MouseEvent e) {}
    @Override public void mouseExited(MouseEvent e) {}

    @Override public void mouseDragged(MouseEvent e) {
        if (draggingView) {
            // Vertical drag freely pitches the camera: down tilts toward edge-on (reveals
            // orbital inclination + the out-of-plane oscillation), up returns to top-down.
            int dy = e.getY() - lastDragY;
            setViewPitch(viewPitchRad + dy * 0.005);
            lastDragY = e.getY();
        } else {
            updateScaleRulerDrag(e.getX(), e.getY());
        }
        repaint();
    }

    /** Mouse-drag view-pitch state (see {@link #mouseDragged}). */
    private boolean draggingView = false;
    private int lastDragY = 0;

    @Override public void mouseMoved(MouseEvent e) {}

    /* ==============================
       RENDERING
       ============================== */
    // ----- Starfield background (regenerated on resize, stable via fixed seed) -----
    private int[] starX, starY;
    private float[] starMag;   // 0..1 brightness
    private int starFieldW = -1, starFieldH = -1;

    private void ensureStarfield(int w, int h) {
        if (w == starFieldW && h == starFieldH && starX != null) return;
        starFieldW = w; starFieldH = h;
        int count = Math.max(120, (w * h) / 6000);
        starX = new int[count];
        starY = new int[count];
        starMag = new float[count];
        Random rng = new Random(0xC0FFEE);
        for (int i = 0; i < count; i++) {
            starX[i] = rng.nextInt(Math.max(1, w));
            starY[i] = rng.nextInt(Math.max(1, h));
            // bias toward faint stars, a few bright ones
            float m = rng.nextFloat();
            starMag[i] = m * m;
        }
    }

    /** Deep-space gradient plus a subtle, parallaxed starfield. */
    private void drawStarfield(Graphics2D g2, int w, int h) {
        // Vertical space gradient for depth
        g2.setPaint(new GradientPaint(0, 0, new Color(10, 12, 22),
                0, h, new Color(4, 5, 12)));
        g2.fillRect(0, 0, w, h);

        ensureStarfield(w, h);
        // Gentle parallax tied to camera so panning feels spatial
        int ox = (int) Math.floor(-camX * METERS_TO_PIXELS * 0.00004) ;
        int oy = (int) Math.floor(-camY * METERS_TO_PIXELS * 0.00004);
        for (int i = 0; i < starX.length; i++) {
            int sx = Math.floorMod(starX[i] + ox, w);
            int sy = Math.floorMod(starY[i] + oy, h);
            float m = starMag[i];
            int bright = (int) (110 + 145 * m);
            int size = m > 0.85f ? 2 : 1;
            // faint bluish-white
            g2.setColor(new Color(bright, bright, Math.min(255, bright + 20), (int) (120 + 135 * m)));
            g2.fillRect(sx, sy, size, size);
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setComposite(AlphaComposite.SrcOver);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        int cx = getWidth() / 2, cy = getHeight() / 2;
        java.util.List<Rectangle> labelBoxes = new ArrayList<>();

        // Deep-space background + starfield
        drawStarfield(g2, getWidth(), getHeight());

        // Draw Sun at origin in world coordinates, offset by barycenter
        double sunR = SUN_RADIUS_M * METERS_TO_PIXELS * currentZoom;
        double sunWorldX = sunBarycenterX;  // Apply barycenter offset for Sun motion
        double sunWorldY = sunBarycenterY;  // Apply barycenter offset for Sun motion
        double sunCX = projX(sunWorldX);
        double sunCY = projY(sunWorldY, 0);
        double sunXpix = sunCX - sunR;
        double sunYpix = sunCY - sunR;

        // Soft radial corona/glow around the Sun
        double glowR = Math.max(sunR * 3.0, sunR + 26);
        RadialGradientPaint sunGlow = new RadialGradientPaint(
                new Point2D.Double(sunCX, sunCY), (float) glowR,
                new float[]{0f, 0.28f, 1f},
                new Color[]{new Color(255, 200, 110, 180),
                            new Color(255, 150, 60, 70),
                            new Color(255, 140, 40, 0)});
        Paint oldPaint = g2.getPaint();
        g2.setPaint(sunGlow);
        g2.fill(new Ellipse2D.Double(sunCX - glowR, sunCY - glowR, 2 * glowR, 2 * glowR));
        g2.setPaint(oldPaint);

        // Sun body: textured (emissive, slow equatorial spin) when a map exists,
        // otherwise a hot-core radial gradient.
        boolean sunTextured = sunR > 2 && drawTexturedSphere(g2, "sun", sunCX, sunCY, sunR,
                0.0, spinAngle(SUN_ROTATION_H), true, 1, 0);
        if (!sunTextured) {
            g2.setPaint(new RadialGradientPaint(
                    new Point2D.Double(sunCX - sunR * 0.25, sunCY - sunR * 0.25), (float) Math.max(1.0, sunR),
                    new float[]{0f, 0.6f, 1f},
                    new Color[]{new Color(255, 248, 220), new Color(255, 190, 70), new Color(240, 140, 30)}));
            g2.fill(new Ellipse2D.Double(sunXpix, sunYpix, 2*sunR, 2*sunR));
            g2.setPaint(oldPaint);
        }

        // Draw Sun label during guided tour
        if (guidedTourActive && showLabels) {
            double sunCenterX = sunXpix + sunR;
            double sunCenterY = sunYpix + sunR;

            // Dynamic font size based on uiSize
            float labelFontSize = switch (uiSize) {
                case SMALL -> 9f;
                case NORMAL -> 11f;
                default -> 13f;
            };

            Font oldFont = g2.getFont();
            g2.setFont(oldFont.deriveFont(Font.BOLD, labelFontSize));
            String sunLabel = "Sun";
            FontMetrics fm = g2.getFontMetrics();
            int labelWidth = fm.stringWidth(sunLabel);
            int labelHeight = fm.getHeight();

            // Position label below the Sun
            int labelX = (int)(sunCenterX - labelWidth / 2.0);
            int labelY = (int)(sunCenterY + sunR + labelHeight + 5);

            // Draw label background box
            int padding = 4;
            g2.setColor(new Color(0, 0, 0, 180));
            g2.fillRoundRect(labelX - padding, labelY - labelHeight + 3,
                           labelWidth + padding * 2, labelHeight + 2, 4, 4);

            // Draw label text
            g2.setColor(new Color(255, 200, 100));
            g2.drawString(sunLabel, labelX, labelY);
            g2.setFont(oldFont);
        }

        // Draw asteroid belt and Kuiper belt as rings
        drawAsteroidBeltRing(g2, cx, cy);
        drawKuiperBeltRing(g2, cx, cy);

        // Draw ice lines
        drawIceLines(g2, cx, cy);

        // Draw planets and moons
        for (int i = 0; i < NAMES.length; i++) {
            if (isPlanetVisible(i)) continue;

            // Planet positions are heliocentric, so add Sun's barycentric offset
            double worldX = planetX[i] + sunBarycenterX;
            double worldY = planetY[i] + sunBarycenterY;
            double worldZ = planetZ[i];
            double px = projX(worldX);
            double py = projY(worldY, worldZ);
            double pr = RADIUS_M[i] * METERS_TO_PIXELS * currentZoom;
            if (pr < 0.5) pr = 0.5;

            // Draw orbit (respect visibility toggle). Skip building/stroking an orbit whose
            // ring can't reach the viewport — when zoomed onto one body, the other planets'
            // orbits are huge off-screen ellipses that are pure cost to draw.
            boolean orbitOnScreen = true;
            {
                double sunSXo = projX(sunBarycenterX);
                double sunSYo = projY(sunBarycenterY, 0);
                double periPx = A_METERS[i] * (1 - ECC[i]) * METERS_TO_PIXELS * currentZoom;
                double apoPx  = A_METERS[i] * (1 + ECC[i]) * METERS_TO_PIXELS * currentZoom;
                double dxL = Math.max(0, Math.max(-sunSXo, sunSXo - getWidth()));
                double dyL = Math.max(0, Math.max(-sunSYo, sunSYo - getHeight()));
                double dNear = Math.hypot(dxL, dyL);            // nearest viewport dist from Sun
                double cxV = Math.min(Math.max(sunSXo, 0), getWidth());
                double cyV = Math.min(Math.max(sunSYo, 0), getHeight());
                double dFar = Math.max(Math.hypot(sunSXo, sunSYo),
                        Math.max(Math.hypot(sunSXo - getWidth(), sunSYo),
                        Math.max(Math.hypot(sunSXo, sunSYo - getHeight()),
                                 Math.hypot(sunSXo - getWidth(), sunSYo - getHeight()))));
                if (cxV == sunSXo && cyV == sunSYo) dNear = 0;   // Sun inside viewport
                orbitOnScreen = (periPx <= dFar + 4) && (apoPx >= dNear - 4);
            }
            if (showPlanetOrbits && orbitOnScreen) {
                Path2D.Double path = getADouble(i, cx, cy);
                g2.setColor(getOrbitColor(i));
                g2.setStroke(new BasicStroke(1f));
                g2.draw(path);

                // Perihelion (E=0) and aphelion (E=π) of the current inclined ellipse,
                // projected through the same 3-D transform as the orbit ring.
                if (showPerihelionAphelion) {
                    {
                        double[] periW = keplerXYZ(A_METERS[i], ECC[i], 0.0,
                                planetIncl(i), planetOmega(i), planetVarpi(i));
                        double[] apoW = keplerXYZ(A_METERS[i], ECC[i], Math.PI,
                                planetIncl(i), planetOmega(i), planetVarpi(i));
                        double periX = projX(periW[0] + sunBarycenterX);
                        double periY = projY(periW[1] + sunBarycenterY, periW[2]);
                        double apoX = projX(apoW[0] + sunBarycenterX);
                        double apoY = projY(apoW[1] + sunBarycenterY, apoW[2]);

                        // Smart marker sizing: scale with zoom but maintain minimum visibility
                        // At low zoom (zoomed out), markers are smaller but still visible
                        double baseMarkerSize = 6.0;
                        double zoomFactor = Math.max(0.3, Math.min(1.5, currentZoom / 5.0));
                        double markerSize = baseMarkerSize * zoomFactor;

                        // Calculate opacity based on screen distance from center (fade markers that are far off-screen)
                        double periDistFromCenter = Math.hypot(periX - cx, periY - cy);
                        double apoDistFromCenter = Math.hypot(apoX - cx, apoY - cy);
                        double maxScreenDist = Math.hypot(getWidth(), getHeight()) / 2.0;

                        Font oldFont = g2.getFont();
                        float fontSize = (float)(9f + 4f * zoomFactor);

                        // Draw perihelion marker (red, closest to Sun)
                        if (periDistFromCenter < maxScreenDist * 1.5) {
                            int alpha = (int)(220 * Math.max(0.4, 1.0 - periDistFromCenter / (maxScreenDist * 1.5)));
                            g2.setColor(new Color(255, 80, 80, alpha));
                            g2.setStroke(new BasicStroke((float)(2.0f * zoomFactor)));
                            g2.draw(new Ellipse2D.Double(periX - markerSize, periY - markerSize,
                                                          2*markerSize, 2*markerSize));
                            g2.setFont(oldFont.deriveFont(Font.BOLD, fontSize));
                            g2.drawString("P", (int)(periX + markerSize + 3), (int)(periY - markerSize + 1));
                        }

                        // Draw aphelion marker (blue, farthest from Sun)
                        if (apoDistFromCenter < maxScreenDist * 1.5) {
                            int alpha = (int)(220 * Math.max(0.4, 1.0 - apoDistFromCenter / (maxScreenDist * 1.5)));
                            g2.setColor(new Color(80, 150, 255, alpha));
                            g2.setStroke(new BasicStroke((float)(2.0f * zoomFactor)));
                            g2.draw(new Ellipse2D.Double(apoX - markerSize, apoY - markerSize,
                                                          2*markerSize, 2*markerSize));
                            g2.setFont(oldFont.deriveFont(Font.BOLD, fontSize));
                            g2.drawString("A", (int)(apoX + markerSize + 3), (int)(apoY - markerSize + 1));
                        }

                        g2.setFont(oldFont);
                    }
                }
            }

            // (Saturn's rings are rendered together with its body by drawSaturn.)

            // Selection ring (drawn AFTER rings, BEFORE planet body so it doesn't get too thick from rotation)
            if (i == selectedPlanet) {
                g2.setColor(Color.YELLOW);
                g2.setStroke(new BasicStroke(2f));
                g2.draw(new Ellipse2D.Double(px - pr - 3, py - pr - 3, 2*pr + 6, 2*pr + 6));
            }

            // Draw planet body
            Ellipse2D.Double planetEllipse = new Ellipse2D.Double(px - pr, py - pr, 2*pr, 2*pr);

            // Prefer a textured 3-D sphere (correct tilt + sidereal spin, Sun-lit);
            // fall back to flat shading / color when no map exists for the body.
            // Sun position on screen: drives lighting, eclipse-shadow direction and glow.
            double sunSX = projX(sunBarycenterX);
            double sunSY = projY(sunBarycenterY, 0);

            // Eclipse occluders: this planet's moons cast shadow transits onto its disc
            // (e.g. Io's shadow on Jupiter, the Moon's shadow on Earth during a solar eclipse).
            // Positions are in planet-radius units in the view frame (x right, y up, z toward
            // viewer); the orbital plane is the screen plane, so z = 0. Radius = true ratio.
            // A moon is only included when its shadow can actually reach the disc, so the
            // per-pixel shadow test is skipped entirely in the (near-always) no-eclipse case.
            double[] pOcc = null; int pOccN = 0;
            if (showEclipses && shadingEnabled && MOON_A_M[i].length > 0 && currentZoom > 10) {
                int nm = MOON_A_M[i].length;
                double invR = 1.0 / RADIUS_M[i];
                double[] Lp = lightDirView(sunSX - px, sunSY - py);
                double lpx = Lp[0], lpy = Lp[1], lpz = Lp[2];
                for (int m = 0; m < nm; m++) {
                    double[] rel = moonRelPos(i, m);
                    double mx = rel[0] * invR, my = -rel[1] * invR, mz = rel[2] * invR;
                    double along = mx * lpx + my * lpy + mz * lpz;
                    if (along <= 0) continue;                 // moon not between disc and Sun
                    double rm = MOON_SIZE_RATIO[i][m];
                    double perp2 = mx * mx + my * my + mz * mz - along * along;
                    if (perp2 > (1 + rm) * (1 + rm)) continue; // shadow axis misses the disc
                    if (pOcc == null) pOcc = new double[nm * 4];
                    pOcc[4 * pOccN]     = mx;
                    pOcc[4 * pOccN + 1] = my;
                    pOcc[4 * pOccN + 2] = mz;
                    pOcc[4 * pOccN + 3] = rm;
                    pOccN++;
                }
                if (pOccN == 0) pOcc = null;
            }

            String texKey = NAMES[i].toLowerCase();
            boolean textured = false;
            if (pr > 2 && hasTexture(texKey)) {
                if (i == 5) {   // Saturn: raytraced sphere + textured ring + mutual shadows
                    textured = drawSaturn(g2, px, py, pr, spinAngle(ROTATION_PERIOD_H[i]),
                            !shadingEnabled, showRings, sunSX - px, sunSY - py);
                } else {
                    textured = drawTexturedSphere(g2, texKey, px, py, pr,
                            OBLIQUITY_DEG[i], spinAngle(ROTATION_PERIOD_H[i]),
                            !shadingEnabled, sunSX - px, sunSY - py, pOcc, pOccN);
                }
            }
            if (!textured) {
                g2.setColor(COLORS[i]);
                if (shadingEnabled && pr > 3) {
                    drawRealisticShading(g2, planetX[i], planetY[i], pr, px, py, i);
                } else {
                    g2.fill(planetEllipse);
                }
            }

            // Atmospheric limb glow, layered on top of the body so it haloes the rim.
            if (showAtmosphere && shadingEnabled && pr > 2
                    && i < ATMO_RGB.length && ATMO_RGB[i] != null) {
                drawAtmosphere(g2, px, py, pr, sunSX - px, sunSY - py,
                        ATMO_RGB[i], ATMO_STRENGTH[i], ATMO_HEIGHT[i]);
            }

            // Draw label with overlap avoidance and zoom-aware sizing
            if (showLabels) {
                // Scale labels based on zoom level
                float labelSize = (float)(11f + Math.min(3f, currentZoom * 0.5f));
                Font labelFont = g2.getFont().deriveFont(Font.PLAIN, labelSize);
                g2.setFont(labelFont);

                g2.setColor(Color.WHITE);
                String name = NAMES[i];
                FontMetrics labelFm = g2.getFontMetrics();
                int w = labelFm.stringWidth(name), h = labelFm.getHeight();

                // Position label away from planet, scaling offset inversely with zoom
                // When zoomed out, labels should be closer to planets
                double angle = Math.atan2(py - cy, px - cx);
                double baseOffset = 8.0; // Minimum offset from planet edge
                double zoomScaledOffset = Math.min(15.0, baseOffset * Math.max(1.0, currentZoom));
                double labelOffset = pr + zoomScaledOffset;
                int lx = (int)(px + Math.cos(angle) * labelOffset);
                int ly = (int)(py + Math.sin(angle) * labelOffset);
                Rectangle box = new Rectangle(lx, ly - h, w, h);

                // Smart overlap avoidance with limited retries
                int maxTries = (currentZoom < 1.0) ? 2 : 4; // Fewer retries when zoomed out
                int tries = 0;
                boolean foundSpot = false;
                while (tries < maxTries) {
                    boolean overlap = false;
                    for (Rectangle r : labelBoxes) {
                        if (r.intersects(box)) {
                            // When zoomed out, try moving horizontally as well as vertically
                            if (currentZoom < 1.0 && tries % 2 == 1) {
                                box.x += w / 2;
                            } else {
                                box.y += h;
                            }
                            overlap = true;
                            tries++;
                            break;
                        }
                    }
                    if (!overlap) {
                        foundSpot = true;
                        break;
                    }
                }

                // Only draw if we found a non-overlapping spot or if zoomed in enough
                if (foundSpot || currentZoom > 3.0) {
                    labelBoxes.add(box);
                    // Add subtle shadow for better readability when zoomed out
                    if (currentZoom < 2.0) {
                        g2.setColor(new Color(0, 0, 0, 150));
                        g2.drawString(name, box.x + 1, box.y + h - 2);
                        g2.setColor(Color.WHITE);
                    }
                    g2.drawString(name, box.x, box.y + h - 3);
                }

                g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 12f)); // Reset font
            }


            // Draw moons (same as before)
            if (MOON_NAMES[i].length > 0) {
                ArrayList<ArrayList<Point2D.Double>> moonOrbits = moonOrbitPaths.get(i);
                for (ArrayList<Point2D.Double> morb : moonOrbits) {
                    Path2D.Double mpath = new Path2D.Double();
                    Point2D.Double first = morb.getFirst();
                    double msx = projX(worldX + first.x);
                    double msy = projY(worldY + first.y, worldZ);
                    mpath.moveTo(msx, msy);
                    for (int j = 1; j < morb.size(); j++) {
                        Point2D.Double p = morb.get(j);
                        double x = projX(worldX + p.x);
                        double y = projY(worldY + p.y, worldZ);
                        mpath.lineTo(x, y);
                    }
                    mpath.closePath();
                    if (currentZoom > ORBIT_VISIBLE_ZOOM) {
                        g2.setColor(new Color(100, 150, 100, 80));  // Green-tinted for moon orbits
                        g2.setStroke(new BasicStroke(0.8f));
                        g2.draw(mpath);
                    }
                }

                // Draw moon bodies (only when zoomed in)
                if (currentZoom > 10) {
                    for (int m = 0; m < MOON_A_M[i].length; m++) {
                        // Solve moon position (3-D, including its own orbital inclination)
                        double[] rel = moonRelPos(i, m);
                        double rx = rel[0], ry = rel[1], rz = rel[2];
                        // Moon position relative to planet's barycentric position
                        double moonWorldX = planetX[i] + sunBarycenterX + rx;
                        double moonWorldY = planetY[i] + sunBarycenterY + ry;
                        double moonWorldZ = planetZ[i] + rz;   // planet height + moon out-of-plane

                        double mpx = projX(moonWorldX);
                        double mpy = projY(moonWorldY, moonWorldZ);
                        double mr = Math.max(RADIUS_M[i] * MOON_SIZE_RATIO[i][m] * METERS_TO_PIXELS * currentZoom, 0.6);

                        // Textured sphere when a map exists (Luna), else shading/flat
                        String moonKey = MOON_NAMES[i][m].toLowerCase();
                        boolean moonTextured = false;
                        if (mr > 2 && hasTexture(moonKey)) {
                            // Parent planet occludes the Sun -> shadow falls on the moon
                            // (a lunar eclipse when the moon crosses the planet's shadow).
                            // Occluder in moon-radius units, view frame (z = 0, in-plane).
                            double ratio = MOON_SIZE_RATIO[i][m];
                            double invMr = 1.0 / (RADIUS_M[i] * ratio);
                            double[] mOcc = null;
                            if (showEclipses && shadingEnabled) {
                                double ocx = -rx * invMr, ocy = ry * invMr, ocz = -rz * invMr, orad = 1.0 / ratio;
                                double[] Lmn = lightDirView(sunSX - mpx, sunSY - mpy);
                                double al = ocx * Lmn[0] + ocy * Lmn[1] + ocz * Lmn[2];
                                double pp = ocx * ocx + ocy * ocy + ocz * ocz - al * al;
                                if (al > 0 && pp <= (orad + 1) * (orad + 1)) {   // Moon actually in the umbra
                                    mOcc = new double[]{ocx, ocy, ocz, orad};
                                }
                            }
                            moonTextured = drawTexturedSphere(g2, moonKey, mpx, mpy, mr,
                                    MOON_OBLIQUITY_DEG, spinAngle(MOON_ROTATION_H),
                                    !shadingEnabled, sunSX - mpx, sunSY - mpy,
                                    mOcc, mOcc != null ? 1 : 0);
                        }
                        if (!moonTextured) {
                            if (shadingEnabled && mr > 2) {
                                drawRealisticShading(g2, moonWorldX, moonWorldY, mr, mpx, mpy, -1);
                            } else {
                                g2.setColor(Color.LIGHT_GRAY);
                                g2.fill(new Ellipse2D.Double(mpx - mr, mpy - mr, 2*mr, 2*mr));
                            }
                        }

                        g2.setColor(Color.WHITE);
                        g2.drawString(MOON_NAMES[i][m], (int)(mpx + mr + 4), (int)(mpy - mr - 2));

                        if (followingPlanet && selectedMoon == m) {
                            g2.setColor(Color.YELLOW);
                            g2.draw(new Ellipse2D.Double(mpx - mr - 3, mpy - mr - 3, 2*mr + 6, 2*mr + 6));
                        }
                    }
                }
            }
        }

        // Draw velocity vectors for planets (educational feature)
        if (showVelocityVectors) {
            drawVelocityVectors(g2, cx, cy);
        }

        // Draw dynamic scale ruler at bottom-left
        drawDynamicScale(g2, getHeight());

        // Modern HUD (top-left status panel)
        drawHud(g2);

        // Optional Sun info panel (toggled with U)
        if (showSunInfo) drawSunInfo(g2);

        // Info panel
        if (selectedPlanet != -1) {
            int i = selectedPlanet;

            // Dynamic sizing based on uiSize setting
            int panelWidth, marginRight, marginTop;
            float headerFontSize, normalFontSize;
            int lineSpacing, topPadding, sidePadding;

            switch (uiSize) {
                case SMALL:
                    panelWidth = 240;
                    marginRight = 12;
                    marginTop = 12;
                    headerFontSize = 9f;
                    normalFontSize = 8f;
                    lineSpacing = 12;
                    topPadding = 18;
                    sidePadding = 10;
                    break;
                case NORMAL:
                    panelWidth = 280;
                    marginRight = 15;
                    marginTop = 15;
                    headerFontSize = 11f;
                    normalFontSize = 10f;
                    lineSpacing = 14;
                    topPadding = 22;
                    sidePadding = 12;
                    break;
                case LARGE:
                default:
                    panelWidth = 340;
                    marginRight = 18;
                    marginTop = 18;
                    headerFontSize = 12f;
                    normalFontSize = 11f;
                    lineSpacing = 16;
                    topPadding = 25;
                    sidePadding = 15;
                    break;
            }

            // Calculate dynamic height based on actual content
            int baseLines = 11;  // Header + Name + Radius + Distance + Speed + a/e + Peri/Apo + Period + Orbit progress + Central mass + Moons count
            int extraKeplerLines = showKeplersLaw ? 6 : 0;  // Header + divider + 3 calculation steps + result with error
            int extraMoonLines = 0;
            if (selectedMoon >= 0 && selectedMoon < MOON_NAMES[i].length) {
                extraMoonLines = 7;  // Moon header, spacing, name, a/e, peri/apo, sidereal, synodic
            }

            int totalLines = baseLines + extraKeplerLines + extraMoonLines;
            int w = panelWidth;
            int h = (topPadding + 10) + (totalLines * lineSpacing) + 10;  // Top padding + lines + bottom padding
            int x0 = getWidth() - w - marginRight, y0 = marginTop;

            // Modern gradient panel background
            GradientPaint gradient = new GradientPaint(
                x0, y0, new Color(20, 25, 40, 240),
                x0, y0 + h, new Color(15, 20, 35, 240)
            );
            g2.setPaint(gradient);
            g2.fillRoundRect(x0, y0, w, h, 15, 15);

            // Accent border with glow effect
            g2.setColor(new Color(100, 150, 255, 180));
            g2.setStroke(new BasicStroke(2f));
            g2.drawRoundRect(x0, y0, w, h, 15, 15);
            g2.setColor(new Color(100, 150, 255, 60));
            g2.setStroke(new BasicStroke(4f));
            g2.drawRoundRect(x0 - 1, y0 - 1, w + 2, h + 2, 16, 16);

            // Use a modern, clean font
            Font oldFont = g2.getFont();
            g2.setFont(oldFont.deriveFont(Font.PLAIN, normalFontSize));

            int lineY = y0 + topPadding;
            int lineStep = lineSpacing;
            int colX = x0 + sidePadding;

            // Header with accent color
            g2.setFont(oldFont.deriveFont(Font.BOLD, headerFontSize));
            g2.setColor(new Color(120, 180, 255));
            g2.drawString("PLANET", colX, lineY);
            lineY += lineStep + 4;

            g2.setFont(oldFont.deriveFont(Font.PLAIN, normalFontSize));
            g2.setColor(new Color(255, 255, 255));
            g2.drawString("Name: " + NAMES[i], colX, lineY); lineY += lineStep;

            // Planet core properties with improved colors
            g2.setColor(new Color(220, 230, 250));
            g2.drawString(String.format("Radius: %.0f km", RADIUS_M[i]/1_000), colX, lineY); lineY += lineStep;
            if (Double.isFinite(planetDistM[i])) {
                g2.drawString(String.format("Distance: %.3f AU (%.0f km)",
                        planetDistM[i]/AU, planetDistM[i]/1_000.0), colX, lineY);
                lineY += lineStep;
            }
            g2.drawString(String.format("Speed: %.2f km/s", planetSpeedKmS[i]), colX, lineY); lineY += lineStep;

            double a_m = A_METERS[i];
            double e = ECC[i];
            double periodYears = PERIOD_Y[i];
            double periodDays = periodYears * 365.25;
            double peri_m = a_m * (1.0 - e);
            double apo_m  = a_m * (1.0 + e);

            double M = meanAnomaly[i];
            double frac = (M % (2 * Math.PI)) / (2 * Math.PI);
            if (frac < 0) frac += 1.0;
            double pct = frac * 100.0;

            g2.drawString(String.format("a: %.3f AU  e: %.3f", a_m / AU, e), colX, lineY); lineY += lineStep;
            g2.drawString(String.format("Peri: %.3f AU  Apo: %.3f AU", peri_m / AU, apo_m / AU), colX, lineY); lineY += lineStep;
            g2.drawString(String.format("Period: %.1f d (%.2f y)", periodDays, periodYears), colX, lineY); lineY += lineStep;

            // Kepler's 3rd Law display for Lesson 1 - enhanced with detailed calculations
            if (showKeplersLaw) {
                double aAU = a_m / AU;
                double pSquared = periodYears * periodYears;
                double aCubed = aAU * aAU * aAU;
                double ratio = pSquared / aCubed;

                // Add spacing before Kepler's Law section
                lineY += (int)(lineStep * 0.3);

                // Draw prominent header with divider line
                g2.setFont(oldFont.deriveFont(Font.BOLD, headerFontSize));
                g2.setColor(new Color(255, 200, 80));
                g2.drawString("KEPLER'S 3RD LAW", colX, lineY);
                lineY += lineStep;

                // Draw divider line under header
                g2.setColor(new Color(255, 200, 80, 150));
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawLine(colX, lineY - (int)(lineStep * 0.7), colX + w - (sidePadding * 2) - 8, lineY - (int)(lineStep * 0.7));

                // Show calculation steps with emphasis
                g2.setFont(oldFont.deriveFont(Font.PLAIN, normalFontSize * 0.95f));
                g2.setColor(new Color(230, 230, 250));

                // Step 1: Show P² calculation
                g2.drawString(String.format("P² = (%.2f y)² = %.4f y²", periodYears, pSquared), colX, lineY);
                lineY += lineStep;

                // Step 2: Show a³ calculation
                g2.drawString(String.format("a³ = (%.3f AU)³ = %.4f AU³", aAU, aCubed), colX, lineY);
                lineY += lineStep;

                // Step 3: Show the ratio calculation with highlight (hidden during guided tour)
                int boxHeight = (int)(lineStep * 1.3);
                if (!guidedTourActive) {
                    g2.setColor(new Color(255, 200, 50, 60));
                    g2.fillRoundRect(colX - 6, lineY - (int)(lineStep * 0.85), w - (sidePadding * 2), boxHeight, 8, 8);

                    // Draw border around result
                    g2.setColor(new Color(255, 220, 80, 200));
                    g2.setStroke(new BasicStroke(2.0f));
                    g2.drawRoundRect(colX - 6, lineY - (int)(lineStep * 0.85), w - (sidePadding * 2), boxHeight, 8, 8);
                }

                // Final ratio with larger, bold text
                g2.setFont(oldFont.deriveFont(Font.BOLD, normalFontSize * 1.15f));
                g2.setColor(new Color(255, 230, 100));
                g2.drawString(String.format("P² / a³ = %.4f", ratio), colX, lineY);

                // Show comparison to ideal value
                g2.setFont(oldFont.deriveFont(Font.PLAIN, normalFontSize * 0.9f));
                g2.setColor(new Color(180, 220, 255));
                double percentError = Math.abs(ratio - 1.0) * 100.0;
                g2.drawString(String.format("(%.2f%% from 1.000)", percentError), colX + (int)(w * 0.55), lineY);

                g2.setFont(oldFont.deriveFont(Font.PLAIN, normalFontSize));
                g2.setColor(new Color(220, 230, 250));
                lineY += lineStep + (int)(lineStep * 0.3);
            }

            g2.drawString(String.format("Orbit progress: %.1f%%", pct), colX, lineY); lineY += lineStep;

            g2.setColor(new Color(180, 200, 230));
            g2.drawString(String.format("Central mass: %.3e kg", sunMass), colX, lineY); lineY += lineStep;
            if (MOON_NAMES[i].length > 0) {
                g2.drawString(String.format("Moons: %d", MOON_NAMES[i].length), colX, lineY); lineY += lineStep;
            }

            // Moon metadata block when a moon is selected
            if (selectedMoon >= 0 && selectedMoon < MOON_NAMES[i].length) {
                int m = selectedMoon;
                lineY += lineStep + 2; // spacing before moon section

                g2.setFont(oldFont.deriveFont(Font.BOLD, headerFontSize));
                g2.setColor(new Color(150, 200, 255));
                g2.drawString("MOON", colX, lineY);
                lineY += lineStep + 2;

                g2.setFont(oldFont.deriveFont(Font.PLAIN, normalFontSize));
                g2.setColor(new Color(255, 255, 255));
                g2.drawString("Name: " + MOON_NAMES[i][m], colX, lineY); lineY += lineStep;

                double aMoon = MOON_A_M[i][m];
                double eMoon = MOON_ECC[i][m];
                double periodSidDays = MOON_PERIOD_DAYS[i][m];
                double periMoon = aMoon * (1.0 - eMoon);
                double apoMoon  = aMoon * (1.0 + eMoon);

                // Approx synodic period relative to parent: 1 / (1/P_moon - 1/P_planet)
                double synodicDays;
                if (periodSidDays > 0 && periodDays > 0 && Math.abs(1.0/periodSidDays - 1.0/periodDays) > 1e-9) {
                    synodicDays = 1.0 / (1.0/periodSidDays - 1.0/periodDays);
                } else {
                    synodicDays = Double.NaN;
                }

                g2.setColor(new Color(220, 230, 250));
                g2.drawString(String.format("a: %.0f km  e: %.3f", aMoon/1_000.0, eMoon), colX, lineY); lineY += lineStep;
                g2.drawString(String.format("Peri: %.0f km  Apo: %.0f km", periMoon/1_000.0, apoMoon/1_000.0), colX, lineY); lineY += lineStep;
                g2.drawString(String.format("Sidereal period: %.2f d", periodSidDays), colX, lineY); lineY += lineStep;
                if (!Double.isNaN(synodicDays)) {
                    g2.drawString(String.format("Synodic vs %s: %.2f d", NAMES[i], synodicDays), colX, lineY);
                }
            }

            // Restore font
            g2.setFont(oldFont);
        }

        // Modern lesson mode indicator banner
        if (lessonMode) {
            // Dynamic sizing based on uiSize setting
            int bannerWidth, bannerHeight;
            float titleFontSize, subtitleFontSize;

            switch (uiSize) {
                case SMALL:
                    bannerWidth = 240;
                    bannerHeight = 42;
                    titleFontSize = 10f;
                    subtitleFontSize = 8f;
                    break;
                case NORMAL:
                    bannerWidth = 280;
                    bannerHeight = 50;
                    titleFontSize = 12f;
                    subtitleFontSize = 10f;
                    break;
                case LARGE:
                default:
                    bannerWidth = 330;
                    bannerHeight = 60;
                    titleFontSize = 14f;
                    subtitleFontSize = 11f;
                    break;
            }

            int bannerX = (getWidth() - bannerWidth) / 2;
            int bannerY = 15;

            // Shadow effect
            g2.setColor(new Color(0, 0, 0, 80));
            g2.fillRoundRect(bannerX + 3, bannerY + 3, bannerWidth, bannerHeight, 15, 15);

            // Gradient background
            GradientPaint bannerGradient = new GradientPaint(
                bannerX, bannerY, new Color(40, 80, 160, 240),
                bannerX, bannerY + bannerHeight, new Color(60, 120, 200, 240)
            );
            g2.setPaint(bannerGradient);
            g2.fillRoundRect(bannerX, bannerY, bannerWidth, bannerHeight, 15, 15);

            // Accent border with glow
            g2.setColor(new Color(120, 180, 255, 200));
            g2.setStroke(new BasicStroke(2.5f));
            g2.drawRoundRect(bannerX, bannerY, bannerWidth, bannerHeight, 15, 15);
            g2.setColor(new Color(150, 200, 255, 80));
            g2.setStroke(new BasicStroke(5f));
            g2.drawRoundRect(bannerX - 1, bannerY - 1, bannerWidth + 2, bannerHeight + 2, 16, 16);

            // Title text with icon
            g2.setColor(new Color(255, 255, 255, 250));
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, titleFontSize));
            String title = "🎓 Lesson " + currentLesson;
            int titleWidth = g2.getFontMetrics().stringWidth(title);
            int titleY = bannerY + (int)(bannerHeight * 0.4);
            g2.drawString(title, bannerX + (bannerWidth - titleWidth) / 2, titleY);

            // Lesson name
            String[] lessonNames = {
                "Introduction to Orbital Motion",
                "Velocity & Kepler's 2nd Law",
                "Scale of the Solar System",
                "Moon Systems",
                "Time Control"
            };
            String subtitle = lessonNames[currentLesson - 1];
            g2.setFont(g2.getFont().deriveFont(Font.PLAIN, subtitleFontSize));
            g2.setColor(new Color(220, 235, 255, 250));
            int subtitleWidth = g2.getFontMetrics().stringWidth(subtitle);
            int subtitleY = bannerY + (int)(bannerHeight * 0.72);
            g2.drawString(subtitle, bannerX + (bannerWidth - subtitleWidth) / 2, subtitleY);

            // Draw guided tour message if active
            if (guidedTourActive && !tourMessage.isEmpty()) {
                drawGuidedTourMessage(g2);
            }
        }
    }

    /**
     * Modern top-left HUD: a translucent rounded status panel with an accent
     * title bar and clean key/value rows. Replaces the old plain white text.
     */
    private void drawHud(Graphics2D g2) {
        // Dynamic sizing
        float titleSize, rowSize;
        int pad, rowStep;
        switch (uiSize) {
            case SMALL -> { titleSize = 10f; rowSize = 10f; pad = 10; rowStep = 15; }
            case LARGE -> { titleSize = 14f; rowSize = 13f; pad = 16; rowStep = 21; }
            default    -> { titleSize = 12f; rowSize = 11.5f; pad = 13; rowStep = 18; }
        }

        // Build rows
        java.util.List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Zoom", String.format("%.2f×  %s", currentZoom,
                followingPlanet && autoZoomEnabled ? "Auto" : "Manual")});
        rows.add(new String[]{"Time", formatTimeScale(timeScale)});
        rows.add(new String[]{"Elapsed", String.format("%.2f yr", simTimeSec / (365.25 * DAY))});
        if (viewPitchRad > 1e-4) {
            rows.add(new String[]{"View tilt", String.format("%.0f°  ( [ ]  \\ )", Math.toDegrees(viewPitchRad))});
        }
        if (followingMoon && cameraFocusIndex >= 0 && cameraFocusMoon >= 0) {
            rows.add(new String[]{"Tracking", NAMES[cameraFocusIndex] + " → " + MOON_NAMES[cameraFocusIndex][cameraFocusMoon]});
            rows.add(new String[]{"Moon r", String.format("%.0f km", MOON_RADIUS_M[cameraFocusIndex][cameraFocusMoon] / 1_000)});
        } else if (followingPlanet && cameraFocusIndex >= 0) {
            rows.add(new String[]{"Tracking", NAMES[cameraFocusIndex]});
            rows.add(new String[]{"Moons", String.valueOf(MOON_NAMES[cameraFocusIndex].length)});
        }

        Font oldFont = g2.getFont();
        Font titleFont = oldFont.deriveFont(Font.BOLD, titleSize);
        Font labelFont = oldFont.deriveFont(Font.PLAIN, rowSize);
        Font valueFont = oldFont.deriveFont(Font.BOLD, rowSize);

        // Measure width
        FontMetrics fmL = g2.getFontMetrics(labelFont);
        FontMetrics fmV = g2.getFontMetrics(valueFont);
        int labelCol = 0;
        for (String[] r : rows) labelCol = Math.max(labelCol, fmL.stringWidth(r[0]));
        int valueCol = 0;
        for (String[] r : rows) valueCol = Math.max(valueCol, fmV.stringWidth(r[1]));
        int gap = 12;
        String title = "SOLAR SYSTEM";
        int titleW = g2.getFontMetrics(titleFont).stringWidth(title);
        int contentW = Math.max(titleW, labelCol + gap + valueCol);
        int w = pad + contentW + pad;
        int titleBarH = (int) titleSize + pad;
        int h = titleBarH + rows.size() * rowStep + pad;
        int x0 = 14, y0 = 14;

        // Panel background (soft shadow + gradient + accent border)
        g2.setColor(new Color(0, 0, 0, 70));
        g2.fillRoundRect(x0 + 2, y0 + 3, w, h, 16, 16);
        g2.setPaint(new GradientPaint(x0, y0, new Color(20, 25, 40, 235),
                x0, y0 + h, new Color(14, 18, 32, 235)));
        g2.fillRoundRect(x0, y0, w, h, 16, 16);
        g2.setColor(new Color(100, 150, 255, 120));
        g2.setStroke(new BasicStroke(1.4f));
        g2.drawRoundRect(x0, y0, w, h, 16, 16);

        // Accent title
        int tx = x0 + pad;
        int ty = y0 + pad + (int) titleSize - 2;
        g2.setFont(titleFont);
        g2.setColor(new Color(120, 180, 255));
        g2.drawString(title, tx, ty);
        // Divider under title
        g2.setColor(new Color(100, 150, 255, 70));
        g2.setStroke(new BasicStroke(1f));
        g2.drawLine(x0 + pad, y0 + titleBarH - 2, x0 + w - pad, y0 + titleBarH - 2);

        // Rows
        int ry = y0 + titleBarH + (int) rowSize + 2;
        for (String[] r : rows) {
            g2.setFont(labelFont);
            g2.setColor(new Color(150, 165, 195));
            g2.drawString(r[0], x0 + pad, ry);
            g2.setFont(valueFont);
            g2.setColor(new Color(230, 238, 252));
            g2.drawString(r[1], x0 + pad + labelCol + gap, ry);
            ry += rowStep;
        }
        g2.setFont(oldFont);
    }

    /**
     * Small Sun information panel (toggled with U), styled to match the HUD.
     * Centered along the top edge.
     */
    private void drawSunInfo(Graphics2D g2) {
        float titleSize, rowSize;
        int pad, rowStep;
        switch (uiSize) {
            case SMALL -> { titleSize = 10f; rowSize = 10f; pad = 10; rowStep = 15; }
            case LARGE -> { titleSize = 14f; rowSize = 13f; pad = 16; rowStep = 21; }
            default    -> { titleSize = 12f; rowSize = 11.5f; pad = 13; rowStep = 18; }
        }

        String[][] rows = {
            {"Mass", String.format("%.4e kg", sunMass)},
            {"Radius", String.format("%,.0f km", SUN_RADIUS_M / 1_000.0)},
            {"Type", "G2V main-sequence star"},
        };
        String title = "SUN";

        Font oldFont = g2.getFont();
        Font titleFont = oldFont.deriveFont(Font.BOLD, titleSize);
        Font labelFont = oldFont.deriveFont(Font.PLAIN, rowSize);
        Font valueFont = oldFont.deriveFont(Font.BOLD, rowSize);

        FontMetrics fmL = g2.getFontMetrics(labelFont);
        FontMetrics fmV = g2.getFontMetrics(valueFont);
        int labelCol = 0, valueCol = 0;
        for (String[] r : rows) {
            labelCol = Math.max(labelCol, fmL.stringWidth(r[0]));
            valueCol = Math.max(valueCol, fmV.stringWidth(r[1]));
        }
        int gap = 12;
        int contentW = Math.max(g2.getFontMetrics(titleFont).stringWidth(title), labelCol + gap + valueCol);
        int w = pad + contentW + pad;
        int titleBarH = (int) titleSize + pad;
        int h = titleBarH + rows.length * rowStep + pad;
        int x0 = (getWidth() - w) / 2, y0 = 14;

        g2.setColor(new Color(0, 0, 0, 70));
        g2.fillRoundRect(x0 + 2, y0 + 3, w, h, 16, 16);
        g2.setPaint(new GradientPaint(x0, y0, new Color(40, 30, 16, 235),
                x0, y0 + h, new Color(28, 20, 10, 235)));
        g2.fillRoundRect(x0, y0, w, h, 16, 16);
        g2.setColor(new Color(255, 190, 90, 150));
        g2.setStroke(new BasicStroke(1.4f));
        g2.drawRoundRect(x0, y0, w, h, 16, 16);

        g2.setFont(titleFont);
        g2.setColor(new Color(255, 200, 110));
        g2.drawString(title, x0 + pad, y0 + pad + (int) titleSize - 2);
        g2.setColor(new Color(255, 190, 90, 70));
        g2.setStroke(new BasicStroke(1f));
        g2.drawLine(x0 + pad, y0 + titleBarH - 2, x0 + w - pad, y0 + titleBarH - 2);

        int ry = y0 + titleBarH + (int) rowSize + 2;
        for (String[] r : rows) {
            g2.setFont(labelFont);
            g2.setColor(new Color(200, 175, 140));
            g2.drawString(r[0], x0 + pad, ry);
            g2.setFont(valueFont);
            g2.setColor(new Color(252, 240, 220));
            g2.drawString(r[1], x0 + pad + labelCol + gap, ry);
            ry += rowStep;
        }
        g2.setFont(oldFont);
    }

    /**
     * Draw the guided tour message box with multi-line text
     * Box size dynamically adjusts to fit text content
     */
    private void drawGuidedTourMessage(Graphics2D g2) {
        int alpha = (int)(tourMessageAlpha * 255);
        if (alpha <= 0) return;

        // Dynamic sizing based on uiSize setting
        float fontSize;
        int minWidth, paddingH, paddingV, bottomMargin;

        switch (uiSize) {
            case SMALL:
                fontSize = 10f;
                minWidth = 180;
                paddingH = 32;
                paddingV = 28;
                bottomMargin = 50;
                break;
            case NORMAL:
                fontSize = 12f;
                minWidth = 220;
                paddingH = 40;
                paddingV = 35;
                bottomMargin = 60;
                break;
            case LARGE:
            default:
                fontSize = 14f;
                minWidth = 260;
                paddingH = 50;
                paddingV = 42;
                bottomMargin = 70;
                break;
        }

        // Split message into lines
        String[] lines = tourMessage.split("\n");

        // Calculate box dimensions based on text
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, fontSize));
        FontMetrics fm = g2.getFontMetrics();
        int maxWidth = 0;
        for (String line : lines) {
            int lineWidth = fm.stringWidth(line);
            maxWidth = Math.max(maxWidth, lineWidth);
        }

        // Add padding and ensure minimum size
        int boxWidth = Math.max(maxWidth + paddingH, minWidth);
        int lineHeight = fm.getHeight();
        int textHeight = lineHeight * lines.length;
        int boxHeight = textHeight + paddingV;

        // Center horizontally, place near bottom
        int boxX = (getWidth() - boxWidth) / 2;
        int boxY = getHeight() - boxHeight - bottomMargin;

        // Shadow effect
        g2.setColor(new Color(0, 0, 0, Math.min(alpha, 100)));
        g2.fillRoundRect(boxX + 4, boxY + 4, boxWidth, boxHeight, 12, 12);

        // Box background with gradient
        GradientPaint boxGradient = new GradientPaint(
            boxX, boxY, new Color(30, 30, 60, Math.min(alpha, 230)),
            boxX, boxY + boxHeight, new Color(50, 50, 90, Math.min(alpha, 230))
        );
        g2.setPaint(boxGradient);
        g2.fillRoundRect(boxX, boxY, boxWidth, boxHeight, 12, 12);

        // Border with glow effect
        g2.setColor(new Color(100, 150, 255, Math.min(alpha, 200)));
        g2.setStroke(new BasicStroke(2.5f));
        g2.drawRoundRect(boxX, boxY, boxWidth, boxHeight, 12, 12);

        // Optional: pulsing glow when ready to advance
        if (tourStepReady) {
            double pulse = Math.sin(System.currentTimeMillis() / 300.0) * 0.3 + 0.7;
            int glowAlpha = (int)(pulse * Math.min(alpha, 150));
            g2.setColor(new Color(150, 200, 255, glowAlpha));
            g2.setStroke(new BasicStroke(4.5f));
            g2.drawRoundRect(boxX - 2, boxY - 2, boxWidth + 4, boxHeight + 4, 14, 14);
        }

        // Draw text lines
        g2.setColor(new Color(255, 255, 255, alpha));
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, fontSize));
        int textY = boxY + (int)(paddingV * 0.6);
        for (String line : lines) {
            int textWidth = fm.stringWidth(line);
            int textX = boxX + (boxWidth - textWidth) / 2;
            g2.drawString(line, textX, textY);
            textY += lineHeight;
        }
    }

    /**
     * Draw Kuiper belt (beyond Neptune)
     * Draw as a ring using concentric circles
     */
    // Stable, world-space belt particle distributions (angle + radius in AU).
    private double[] astAng, astRad, kuiAng, kuiRad;

    private void ensureBeltParticles() {
        if (astAng == null) {
            Random r = new Random(0xA57E401D);
            int n = 1100;
            astAng = new double[n]; astRad = new double[n];
            for (int i = 0; i < n; i++) {
                astAng[i] = r.nextDouble() * 2 * Math.PI;
                // concentrate mass toward the middle of the band
                double t = (r.nextDouble() + r.nextDouble()) / 2.0;
                astRad[i] = 2.15 + t * (3.25 - 2.15);
            }
        }
        if (kuiAng == null) {
            Random r = new Random(0x0CE1CE);
            int n = 1600;
            kuiAng = new double[n]; kuiRad = new double[n];
            for (int i = 0; i < n; i++) {
                kuiAng[i] = r.nextDouble() * 2 * Math.PI;
                double t = (r.nextDouble() + r.nextDouble()) / 2.0;
                kuiRad[i] = 30.0 + t * (55.0 - 30.0);
            }
        }
    }

    /**
     * Render a debris belt as a soft radial-gradient annulus overlaid with a
     * stable scatter of tiny particles that sit in world space (so they scale
     * and pan with the rest of the scene).
     */
    private void drawBelt(Graphics2D g2, int cx, int cy,
                          double innerAU, double outerAU,
                          Color band, Color dot,
                          double[] ang, double[] rad) {
        double pxPerAU = AU * METERS_TO_PIXELS * currentZoom;
        double innerPx = innerAU * pxPerAU;
        double outerPx = outerAU * pxPerAU;
        if (outerPx < 1.0) return;

        double sunScreenX = projX(sunBarycenterX);
        double sunScreenY = projY(sunBarycenterY, 0);

        // Soft annulus glow (transparent core -> band color -> transparent edge)
        if (outerPx > 4) {
            float innerFrac = (float) Math.max(0.001, Math.min(0.98, innerPx / outerPx));
            float midFrac = Math.min(0.999f, (innerFrac + 1f) / 2f);
            Paint old = g2.getPaint();
            g2.setPaint(new RadialGradientPaint(
                    new Point2D.Double(sunScreenX, sunScreenY), (float) outerPx,
                    new float[]{0f, innerFrac, midFrac, 1f},
                    new Color[]{new Color(band.getRed(), band.getGreen(), band.getBlue(), 0),
                                new Color(band.getRed(), band.getGreen(), band.getBlue(), 0),
                                band,
                                new Color(band.getRed(), band.getGreen(), band.getBlue(), 0)}));
            g2.fill(new Ellipse2D.Double(sunScreenX - outerPx, sunScreenY - outerPx, 2 * outerPx, 2 * outerPx));
            g2.setPaint(old);
        }

        // Particle scatter
        int w = getWidth(), h = getHeight();
        for (int i = 0; i < ang.length; i++) {
            double rPx = rad[i] * pxPerAU;
            double sx = sunScreenX + Math.cos(ang[i]) * rPx;
            double sy = sunScreenY + Math.sin(ang[i]) * rPx;
            if (sx < -4 || sy < -4 || sx > w + 4 || sy > h + 4) continue;
            int alpha = 120 + ((i * 37) % 110);
            g2.setColor(new Color(dot.getRed(), dot.getGreen(), dot.getBlue(), alpha));
            int size = (i % 11 == 0) ? 2 : 1;
            g2.fillRect((int) sx, (int) sy, size, size);
        }
    }

    private void drawKuiperBeltRing(Graphics2D g2, int cx, int cy) {
        if (!showKuiperBelt) return;
        ensureBeltParticles();
        drawBelt(g2, cx, cy, 30.0, 55.0,
                new Color(150, 180, 215, 55),
                new Color(200, 220, 240),
                kuiAng, kuiRad);
    }

    /**
     * Draw asteroid belt (between Mars and Jupiter) as a soft particle band.
     */
    private void drawAsteroidBeltRing(Graphics2D g2, int cx, int cy) {
        if (!showAsteroidBelt) return;
        ensureBeltParticles();
        drawBelt(g2, cx, cy, 2.2, 3.2,
                new Color(200, 150, 90, 55),
                new Color(220, 180, 130),
                astAng, astRad);
    }

    /**
     * Draw frost lines for H2O and CO2
     * Shows regions where different volatile compounds can condense
     */
    private void drawIceLines(Graphics2D g2, int cx, int cy) {
        if (!showIceLines) return;

        // Center on Sun's barycentric position
        double sunScreenX = projX(sunBarycenterX);
        double sunScreenY = projY(sunBarycenterY, 0);

        // H2O frost line (water ice): ~4.85 AU
        double h2oFrostLineAU = 4.85;
        double h2oFrostLinePx = h2oFrostLineAU * AU * METERS_TO_PIXELS * currentZoom;

        // CO2 frost line (dry ice): ~30.0 AU
        double co2FrostLineAU = 30.0;
        double co2FrostLinePx = co2FrostLineAU * AU * METERS_TO_PIXELS * currentZoom;

        // Only draw if visible
        if (h2oFrostLinePx > 1.0) {
            // H2O frost line: bright cyan with cyan label
            drawFrostLine(g2, sunScreenX, sunScreenY, h2oFrostLinePx, new Color(100, 200, 255, 120), "H2O Frost Line");
        }

        if (co2FrostLinePx > 1.0) {
            // CO2 frost line: pale blue with blue label
            drawFrostLine(g2, sunScreenX, sunScreenY, co2FrostLinePx, new Color(150, 180, 220, 100), "CO2 Frost Line");
        }
    }

    /**
     * Helper: Draw a single frost line with label and fade effect
     */
    private void drawFrostLine(Graphics2D g2, double cx, double cy, double radiusPx, Color color, String label) {
        // Draw main line
        g2.setColor(color);
        g2.setStroke(new BasicStroke(2f));
        Ellipse2D.Double circle = new Ellipse2D.Double(cx - radiusPx, cy - radiusPx, 2*radiusPx, 2*radiusPx);
        g2.draw(circle);

        // Draw fade/glow effect (3 circles with decreasing opacity)
        for (int i = 1; i <= 3; i++) {
            float alpha = color.getAlpha() / 255f * (1.0f - i / 4.0f);
            g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), (int)(alpha * 255)));
            g2.setStroke(new BasicStroke(0.5f));
            double fadeRadius = radiusPx + i * 2;
            Ellipse2D.Double fadeCircle = new Ellipse2D.Double(cx - fadeRadius, cy - fadeRadius, 2*fadeRadius, 2*fadeRadius);
            g2.draw(fadeCircle);
        }

        // Draw label at top of frost line circle
        if (label != null) {
            g2.setColor(color);  // Use same color as line for label
            Font oldFont = g2.getFont();
            g2.setFont(oldFont.deriveFont(Font.BOLD, 11f));
            FontMetrics fm = g2.getFontMetrics();
            int labelWidth = fm.stringWidth(label);
            int labelX = (int)(cx - (double) labelWidth / 2);
            int labelY = (int)(cy - radiusPx - 5);  // Slightly above the line
            g2.drawString(label, labelX, labelY);
            g2.setFont(oldFont);
        }
    }

    /** Helper: builds orbit path from precomputed points */
    /** Build planet {@code i}'s orbit as a projected screen path from its current (precessed)
     *  3-D Keplerian ellipse, so the ring shows the real inclination and tilts with the view. */
    private Path2D.Double getADouble(int i, int cx, int cy) {
        Path2D.Double path = new Path2D.Double();
        double a = A_METERS[i], e = ECC[i];
        double incl = planetIncl(i), Om = planetOmega(i), vp = planetVarpi(i);
        final int seg = 512;   // smooth enough for an on-screen ellipse; rebuilt every frame
        for (int j = 0; j <= seg; j++) {
            double E = 2 * Math.PI * j / seg;
            double[] w = keplerXYZ(a, e, E, incl, Om, vp);       // {wx, wy, wz} heliocentric
            double wx = w[0] + sunBarycenterX, wy = w[1] + sunBarycenterY, wz = w[2];
            double sx = projX(wx), sy = projY(wy, wz);
            if (j == 0) path.moveTo(sx, sy); else path.lineTo(sx, sy);
        }
        path.closePath();
        return path;
    }

    // ------- 3-D Keplerian state + secular precession -------

    /** Julian centuries of sim time elapsed since the J2000 epoch (simTimeSec = 0). */
    private double centuriesSinceEpoch() { return simTimeSec / (36525.0 * 86400.0); }

    /** Current (precessed) longitude of the ascending node for planet i (radians). */
    private double planetOmega(int i) {
        return Math.toRadians(PLANET_OMEGA0[i] + PLANET_OMEGA_DOT[i] * centuriesSinceEpoch());
    }
    /** Current (precessed) longitude of perihelion for planet i (radians). */
    private double planetVarpi(int i) {
        return Math.toRadians(PLANET_VARPI0[i] + PLANET_VARPI_DOT[i] * centuriesSinceEpoch());
    }
    /** Current (precessed) inclination to the ecliptic for planet i (radians). */
    private double planetIncl(int i) {
        return Math.toRadians(PLANET_INCL_DEG[i] + PLANET_INCL_DOT[i] * centuriesSinceEpoch());
    }

    /** Newton solve of Kepler's equation M = E - e sinE for the eccentric anomaly E. */
    private static double solveEccentricAnomaly(double M, double e) {
        double E = M;
        for (int it = 0; it < 40; it++) {
            double f = E - e * Math.sin(E) - M;
            double fp = 1 - e * Math.cos(E);
            E -= f / fp;
            if (Math.abs(f) < 1e-12) break;
        }
        return E;
    }

    /**
     * Heliocentric position for a point on an inclined Keplerian ellipse, returned in the
     * sim's world convention {@code {wx, wy, wz}} where wx,wy lie in the ecliptic reference
     * plane (as previously drawn) and wz is height above it. The perifocal point is rotated
     * by the argument of perihelion, inclined by {@code incl} about the node line, and swung
     * by the node {@code Omega}; the final {@code {wy, -wx-in-plane}} swap reproduces the
     * original screen orientation exactly when incl = 0.
     */
    private static double[] keplerXYZ(double a, double e, double E, double incl, double Omega, double varpi) {
        double b = a * Math.sqrt(1 - e * e);
        double xr = a * (Math.cos(E) - e), yr = b * Math.sin(E);   // perifocal (z = 0)
        double omega = varpi - Omega;                              // argument of perihelion
        double cO = Math.cos(Omega), sO = Math.sin(Omega);
        double cw = Math.cos(omega), sw = Math.sin(omega);
        double ci = Math.cos(incl), si = Math.sin(incl);
        double xe = (cO * cw - sO * sw * ci) * xr + (-cO * sw - sO * cw * ci) * yr;
        double ye = (sO * cw + cO * sw * ci) * xr + (-sO * sw + cO * cw * ci) * yr;
        double ze = (sw * si) * xr + (cw * si) * yr;
        return new double[]{ ye, -xe, ze };   // matches the legacy (x = ye, y = -xe) mapping at incl = 0
    }

    /** Current heliocentric 3-D position of planet i from its mean anomaly + precessed elements. */
    private double[] planetXYZ(int i) {
        double E = solveEccentricAnomaly(meanAnomaly[i], ECC[i]);
        return keplerXYZ(A_METERS[i], ECC[i], E, planetIncl(i), planetOmega(i), planetVarpi(i));
    }

    // ------- View projection (world -> screen), pitch-aware -------

    /** Set the view pitch (radians, clamped) and refresh cached cos/sin + the sphere-frame cache. */
    private void setViewPitch(double rad) {
        double p = Math.max(0.0, Math.min(MAX_PITCH_RAD, rad));
        if (p == viewPitchRad) return;
        viewPitchRad = p;
        viewCosP = Math.cos(p);
        viewSinP = Math.sin(p);
        geomCache.clear();   // sphere lighting frame depends on the view elevation (= 90° - pitch)
    }

    /** Camera elevation above the ecliptic used by the sphere lighting frame (deg); 90 = top-down. */
    private double viewElevDeg() { return 90.0 - Math.toDegrees(viewPitchRad); }

    /** Project a world X (m) to a screen x (px). X is unaffected by pitch (tilt is about this axis). */
    private double projX(double worldX) {
        return getWidth() / 2.0 + (worldX - camX) * METERS_TO_PIXELS * currentZoom;
    }
    /** Project a world (Y, Z) (m) to a screen y (px): tilt rotates the ecliptic about the screen X axis. */
    private double projY(double worldY, double worldZ) {
        double s = METERS_TO_PIXELS * currentZoom;
        return getHeight() / 2.0 + ((worldY - camY) * viewCosP - (worldZ - camZ) * viewSinP) * s;
    }

    // ===================== Textured 3-D sphere rendering =====================
    // Planets are drawn as software-rendered lit spheres. The camera looks down
    // on the orbital plane but from a fixed elevation (VIEW_ELEV_DEG) above it, so
    // each body reads as a 3-D globe and its axial tilt shows as a real lean of the
    // spin axis rather than the pole pointing straight at the viewer. An
    // equirectangular NASA-derived map is orthographically projected onto the
    // visible hemisphere, spun at the body's sidereal rate, and lit by the Sun in
    // 3-D (the Sun sits in the orbital plane, giving a correct day/night
    // terminator). Saturn is rendered by a small raytracer that adds a textured
    // ring with mutual planet/ring shadows. Bodies with no map fall back to flat
    // shading.
    private final java.util.Map<String, int[]> texPixels = new HashMap<>();
    private final java.util.Map<String, int[]> texSize = new HashMap<>();   // {w, h}
    private final java.util.Set<String> texMissing = new HashSet<>();
    private final java.util.Map<String, SphereGeom> geomCache = new HashMap<>();
    // Raster resolution scales with on-screen size (n = min(2R+1, cap)); the cap
    // bounds per-frame cost. Geometry + the raster buffer are cached/reused, so a
    // high cap renders a zoomed planet ~1:1 with screen pixels (crisp at 1080p+)
    // without per-frame reallocation.
    private static final int SPHERE_RASTER_CAP = 820;
    private static final int RING_RASTER_CAP = 1100;
    private static final double INV_2PI = 1.0 / (2 * Math.PI);
    /**
     * Camera elevation above each body's orbital plane (deg). 90 = straight
     * top-down (perpendicular to the orbital plane), looking from near the
     * north pole; each body's spin axis then tilts by its true obliquity off
     * this vertical (e.g. Earth's pole sits ~23° off-centre, seen from above).
     *
     * <p>This is no longer a fixed constant: it tracks the view pitch so the sphere
     * lighting/orientation stays consistent as the camera tilts toward edge-on
     * ({@code elevation = 90° - pitch}); {@link #viewElevDeg()} is the live value.
     * Top-down (pitch 0) reproduces the original 90°.</p>
     */
    private static final double VIEW_ELEV_DEG = 90.0;
    /** Saturn ring inner/outer radius in planet radii (D-ring inner to A-ring outer). */
    private static final double RING_INNER = 1.11, RING_OUTER = 2.27;
    private int[] ringLut;              // radial ARGB profile of the ring, or null
    private boolean ringLutTried = false;

    /** Per-pixel sphere geometry for a raster size + obliquity (rebuilt on size change). */
    private static final class SphereGeom {
        final int n;              // raster is n x n
        final int[] texY;         // texture row per pixel, or -1 when outside the disc
        final double[] lon0;      // base longitude (rad) before spin is added
        final float[] nx, ny, nz; // view-space unit normal, for 3-D Sun lighting
        final BufferedImage buf;  // reused output raster (no per-frame allocation)
        SphereGeom(int n) {
            this.n = n;
            texY = new int[n * n];
            lon0 = new double[n * n];
            nx = new float[n * n];
            ny = new float[n * n];
            nz = new float[n * n];
            buf = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        }
    }

    private boolean loadTexture(String key) {
        return loadTextureFile(key, key + "_diffuse.jpg");
    }

    /**
     * Load an equirectangular texture from {@code assets/textures/<fileName>} into the
     * pixel cache under {@code cacheKey}. Used for both the {@code *_diffuse} surface
     * maps and Earth's extra {@code _night} / {@code _clouds} layers. Missing files are
     * remembered so they are not re-probed each frame.
     */
    private boolean loadTextureFile(String cacheKey, String fileName) {
        if (texPixels.containsKey(cacheKey)) return true;
        if (texMissing.contains(cacheKey)) return false;
        File f = new File("assets/textures/" + fileName);
        if (!f.isFile()) { texMissing.add(cacheKey); return false; }
        try {
            BufferedImage img = ImageIO.read(f);
            if (img == null) { texMissing.add(cacheKey); return false; }
            // Cap texture width: the sphere raster is <= SPHERE_RASTER_CAP px across a whole
            // hemisphere, so a map wider than ~2K adds nothing but cache-thrashing memory
            // traffic (the biggest per-pixel cost when a planet fills the screen).
            final int MAX_TEX_W = 2048;
            if (img.getWidth() > MAX_TEX_W) {
                int nw = MAX_TEX_W, nh = Math.max(1, img.getHeight() * MAX_TEX_W / img.getWidth());
                BufferedImage small = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
                Graphics2D sg = small.createGraphics();
                sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                sg.drawImage(img, 0, 0, nw, nh, null);
                sg.dispose();
                img = small;
            }
            int w = img.getWidth(), h = img.getHeight();
            texPixels.put(cacheKey, img.getRGB(0, 0, w, h, null, 0, w));
            texSize.put(cacheKey, new int[]{w, h});
            return true;
        } catch (Exception e) {
            texMissing.add(cacheKey);
            return false;
        }
    }

    private boolean hasTexture(String key) { return loadTexture(key); }

    /** Radial ARGB profile of Saturn's ring (x = inner..outer, alpha = gaps), or null. */
    private int[] loadRingLut() {
        if (ringLut != null || ringLutTried) return ringLut;
        ringLutTried = true;
        File f = new File("assets/textures/saturn_ring.png");
        if (!f.isFile()) return null;
        try {
            BufferedImage im = ImageIO.read(f);
            if (im == null) return null;
            int w = im.getWidth(), h = im.getHeight();
            ringLut = im.getRGB(0, h / 2, w, 1, null, 0, w);  // middle scanline (ARGB)
        } catch (Exception ignored) { }
        return ringLut;
    }

    /** Accumulated spin angle (rad) for a body given its sidereal period in hours. */
    private double spinAngle(double rotationPeriodHours) {
        if (rotationPeriodHours == 0) return 0;
        return 2 * Math.PI * simTimeSec / (rotationPeriodHours * 3600.0);
    }

    /**
     * Orthonormal body frame in view space (x right, y up, z toward viewer):
     * returns {poleX,poleY,poleZ, ax,ay,az, bx,by,bz}. The spin axis is the true
     * obliquity off the (elevated) ecliptic normal; A,B span the equator.
     */
    private double[] bodyFrame(double obliquityDeg) {
        double e = Math.toRadians(obliquityDeg), phi = Math.toRadians(viewElevDeg());
        double cE = Math.cos(e), sE = Math.sin(e), cP = Math.cos(phi), sP = Math.sin(phi);
        // ecliptic normal N=(0,cP,sP); pole = cosE*N + sinE*u, u=(1,0,0)
        double poleX = sE, poleY = cE * cP, poleZ = cE * sP;
        // A = normalize(u - (u·pole)pole); B = pole x A
        double ud = poleX;
        double axr = 1 - ud * poleX, ayr = -ud * poleY, azr = -ud * poleZ;
        double al = Math.sqrt(axr * axr + ayr * ayr + azr * azr);
        if (al < 1e-9) { axr = 0; ayr = 1; azr = 0; al = 1; }
        double ax = axr / al, ay = ayr / al, az = azr / al;
        double bx = poleY * az - poleZ * ay;
        double by = poleZ * ax - poleX * az;
        double bz = poleX * ay - poleY * ax;
        return new double[]{poleX, poleY, poleZ, ax, ay, az, bx, by, bz};
    }

    /** Screen-space Sun vector (dx,dy; y down) -> unit 3-D light direction in the view frame. */
    private double[] lightDirView(double lightDx, double lightDy) {
        double phi = Math.toRadians(viewElevDeg()), sP = Math.sin(phi), cP = Math.cos(phi);
        double ox = lightDx, oy = -lightDy;               // orbital-plane components (y up)
        double ol = Math.hypot(ox, oy);
        if (ol < 1e-9) { ox = 1; oy = 0; ol = 1; }
        ox /= ol; oy /= ol;
        // L = ox*u + oy*v, u=(1,0,0), v=(0,sP,-cP)
        return new double[]{ox, oy * sP, -oy * cP};
    }

    private static int shadeRGB(int rgb, double shade) {
        int r = (int) (((rgb >> 16) & 0xFF) * shade);
        int g = (int) (((rgb >> 8) & 0xFF) * shade);
        int b = (int) ((rgb & 0xFF) * shade);
        return (r << 16) | (g << 8) | b;
    }

    /**
     * Draw a body as a lit, textured 3-D sphere. Returns false (drawing nothing)
     * when no diffuse map exists for {@code key}, so the caller can fall back.
     */
    private boolean drawTexturedSphere(Graphics2D g2, String key, double px, double py,
                                       double pr, double obliquityDeg, double spin,
                                       boolean emissive, double lightDx, double lightDy) {
        return drawTexturedSphere(g2, key, px, py, pr, obliquityDeg, spin, emissive,
                lightDx, lightDy, null, 0);
    }

    /**
     * As {@link #drawTexturedSphere}, plus optional eclipse shadows: {@code occ} is a
     * flat {@code [cx,cy,radius, ...]} list of occluding bodies in screen pixels
     * ({@code occN} entries). Any surface pixel whose ray toward the Sun passes behind
     * an occluder's disc is darkened, giving moon shadow-transits on a planet and a
     * planet's shadow falling across a moon (lunar eclipse).
     */
    private boolean drawTexturedSphere(Graphics2D g2, String key, double px, double py,
                                       double pr, double obliquityDeg, double spin,
                                       boolean emissive, double lightDx, double lightDy,
                                       double[] occ, int occN) {
        if (!loadTexture(key)) return false;
        int R = (int) Math.ceil(pr);
        if (R < 2) return false;

        int n = Math.min(2 * R + 1, SPHERE_RASTER_CAP);
        int[] tex = texPixels.get(key);
        int tw = texSize.get(key)[0], th = texSize.get(key)[1];

        SphereGeom geom = geomCache.get(key);
        if (geom == null || geom.n != n) {
            geom = buildSphereGeom(n, obliquityDeg, th);
            geomCache.put(key, geom);
        }

        double[] L = lightDirView(lightDx, lightDy);
        float lx = (float) L[0], ly = (float) L[1], lz = (float) L[2];
        final float ambient = 0.10f;

        // Eclipse shadows use a true 3-D ray-vs-sphere test (see eclipseFactor); occ holds
        // each occluder as {x, y, z, radius} in receiver-radius units in the view frame.
        boolean doShadows = !emissive && occ != null && occN > 0;
        double lxd = L[0], lyd = L[1], lzd = L[2];

        // Earth-style extra layers (night city lights, drifting clouds, ocean sun-glint):
        // active only for a shaded body that actually has the maps on disk.
        boolean earthLike = !emissive && key.equals("earth");
        int[] nightTex = null, cloudTex = null;
        int nTw = 0, nTh = 0, cTw = 0, cTh = 0;
        if (earthLike && showNightLights && loadTextureFile("earth_night", "earth_night.jpg")) {
            nightTex = texPixels.get("earth_night");
            nTw = texSize.get("earth_night")[0]; nTh = texSize.get("earth_night")[1];
        }
        if (earthLike && showClouds && loadTextureFile("earth_clouds", "earth_clouds.jpg")) {
            cloudTex = texPixels.get("earth_clouds");
            cTw = texSize.get("earth_clouds")[0]; cTh = texSize.get("earth_clouds")[1];
        }
        boolean oceanGlint = earthLike;
        // Clouds drift slowly eastward relative to the surface (a full lap ~25 days).
        double cloudSpin = spin + (2 * Math.PI * simTimeSec / (25.0 * 24.0 * 3600.0));
        // Blinn half-vector (light + view), view dir = +z toward the camera.
        double hlx = L[0], hly = L[1], hlz = L[2] + 1.0;
        double hln = Math.sqrt(hlx * hlx + hly * hly + hlz * hlz);
        if (hln > 1e-9) { hlx /= hln; hly /= hln; hlz /= hln; }

        BufferedImage img = geom.buf;                   // reused raster
        int[] out = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        int[] texY = geom.texY;
        double[] lon0 = geom.lon0;
        float[] gnx = geom.nx, gny = geom.ny, gnz = geom.nz;

        // --- Tier-2 GPU path: shade the whole n×n disc in GLSL and read it back into the
        // same raster `out` the CPU loop fills, then fall through to the identical blit.
        // Uses the same body frame, light dir, spin, Earth layers and eclipse occluders,
        // so the result matches the CPU path (bodies without a *_normal/_specular map look
        // identical). Falls back to CPU if the GPU is off or unavailable. ---
        boolean gpuFilled = false;
        if (gpuSpheres) {
            GLSphereRenderer r = gpuRenderer();
            if (r != null && r.isAvailable()) {
                double[] fr = bodyFrame(obliquityDeg);
                gpuFilled = r.shade(out, n, key, tex, tw, th,
                        new double[]{fr[0], fr[1], fr[2]},
                        new double[]{fr[3], fr[4], fr[5]},
                        new double[]{fr[6], fr[7], fr[8]}, L,
                        spin, cloudSpin, ambient, emissive, earthLike,
                        nightTex, nTw, nTh, cloudTex, cTw, cTh, oceanGlint,
                        occ, doShadows ? occN : 0);
            }
        }
        if (!gpuFilled) {

        // Only shade the raster rows/cols that land on screen — when a body is zoomed
        // larger than the viewport, most of the disc is off-screen and clipped anyway,
        // so computing those pixels is pure waste. Off-screen raster pixels keep stale
        // values but are never blitted (g2 clips them). A 2px margin covers bilinear edges.
        double scale = (2.0 * pr) / n, dxf = px - pr, dyf = py - pr;
        int vw = getWidth(), vh = getHeight();
        int ix0 = 0, ix1 = n, iy0 = 0, iy1 = n;
        if (vw > 0 && vh > 0 && scale > 0) {
            ix0 = Math.max(0, (int) ((-dxf) / scale) - 2);
            ix1 = Math.min(n, (int) ((vw - dxf) / scale) + 3);
            iy0 = Math.max(0, (int) ((-dyf) / scale) - 2);
            iy1 = Math.min(n, (int) ((vh - dyf) / scale) + 3);
            if (ix0 >= ix1 || iy0 >= iy1) return true;   // fully off-screen: nothing to draw
        }

        for (int iy = iy0; iy < iy1; iy++) {
          final int rowBase = iy * n;
          for (int ix = ix0; ix < ix1; ix++) {
            int p = rowBase + ix;
            int ty = texY[p];
            if (ty < 0) { out[p] = 0; continue; }       // outside disc -> transparent
            double u = (lon0[p] + spin) * INV_2PI;
            u -= Math.floor(u);
            int tx = (int) (u * tw);
            if (tx >= tw) tx = tw - 1;
            int rgb = tex[ty * tw + tx];
            if (emissive) { out[p] = 0xFF000000 | rgb; continue; }

            float d = gnx[p] * lx + gny[p] * ly + gnz[p] * lz;
            if (d < 0) d = 0;
            double shade = ambient + (1 - ambient) * d;
            if (doShadows && d > 0) {
                shade *= eclipseFactor(gnx[p], gny[p], gnz[p], lxd, lyd, lzd, occ, occN);
            }

            if (!earthLike) {
                out[p] = 0xFF000000 | shadeRGB(rgb, shade);
                continue;
            }

            // --- Earth: clouds over surface, then shade, then glint + night lights ---
            int sr = (rgb >> 16) & 0xFF, sg = (rgb >> 8) & 0xFF, sb = rgb & 0xFF;
            double cloud = 0;
            if (cloudTex != null) {
                int cty = (int) ((long) ty * cTh / th); if (cty >= cTh) cty = cTh - 1;
                double cu = (lon0[p] + cloudSpin) * INV_2PI; cu -= Math.floor(cu);
                int ctx = (int) (cu * cTw); if (ctx >= cTw) ctx = cTw - 1;
                int cp = cloudTex[cty * cTw + ctx];
                cloud = (((cp >> 16) & 0xFF) + ((cp >> 8) & 0xFF) + (cp & 0xFF)) / 765.0;
                cloud = clamp01((cloud - 0.12) / 0.88);   // drop the faint haze floor
                sr += (int) ((255 - sr) * cloud);
                sg += (int) ((255 - sg) * cloud);
                sb += (int) ((255 - sb) * cloud);
            }
            int lr = (int) (sr * shade), lg = (int) (sg * shade), lb = (int) (sb * shade);

            // Ocean sun-glint: sharp Blinn highlight on blue (water) pixels, day side only.
            // Only the highlight core matters (ndoth near 1); a fast x^64 (six squarings)
            // replaces Math.pow so the hot loop stays cheap.
            if (oceanGlint && d > 0 && cloud < 0.45 && sb > sr + 8 && sb > sg + 4) {
                double ndoth = gnx[p] * hlx + gny[p] * hly + gnz[p] * hlz;
                if (ndoth > 0.5) {
                    double x = ndoth * ndoth; x *= x; x *= x; x *= x; x *= x; x *= x;  // ndoth^64
                    double spec = x * 0.9 * (1 - cloud);
                    int add = (int) (spec * 255);
                    lr += add; lg += add; lb += (int) (add * 0.85);
                }
            }
            // Night-side city lights (emissive), dimmed under cloud cover.
            if (nightTex != null) {
                double nightW = 1.0 - smoothstep(0.02, 0.22, d);
                if (nightW > 0.001) {
                    nightW *= (1 - cloud * 0.7);
                    int nty = (int) ((long) ty * nTh / th); if (nty >= nTh) nty = nTh - 1;
                    int ntx = (int) (u * nTw); if (ntx >= nTw) ntx = nTw - 1;   // same longitude as surface
                    int np = nightTex[nty * nTw + ntx];
                    double nb = 1.7 * nightW;
                    lr += (int) (((np >> 16) & 0xFF) * nb);
                    lg += (int) (((np >> 8) & 0xFF) * nb);
                    lb += (int) ((np & 0xFF) * nb);
                }
            }
            if (lr > 255) lr = 255; if (lg > 255) lg = 255; if (lb > 255) lb = 255;
            out[p] = 0xFF000000 | (lr << 16) | (lg << 8) | lb;
          }
        }
        } // end CPU shading fallback (!gpuFilled)

        Object oldInterp = g2.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.drawImage(img, (int) Math.round(px - pr), (int) Math.round(py - pr),
                (int) Math.round(2 * pr), (int) Math.round(2 * pr), null);
        if (oldInterp != null) g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterp);
        return true;
    }

    /**
     * Shadow multiplier (≈0 in the umbra, 1 in full sun) for a lit surface point, using a
     * true 3-D ray-vs-sphere test. The point sits on the unit sphere at ({@code nx,ny,nz})
     * in the receiver's view frame; {@code (lx,ly,lz)} is the unit Sun direction; {@code occ}
     * lists occluders as {x, y, z, radius} in receiver-radius units. A point is shadowed
     * when the ray it casts toward the Sun passes within an occluder's true sphere, so the
     * umbra is a proper circle sized by the occluder's real radius ratio (e.g. the Moon is
     * ~0.27 Earth radii), with a soft penumbra around it — not a screen-space band.
     */
    private static double eclipseFactor(double nx, double ny, double nz,
                                        double lx, double ly, double lz,
                                        double[] occ, int occN) {
        double factor = 1.0;
        for (int k = 0; k < occN; k++) {
            double ox = occ[4 * k], oy = occ[4 * k + 1], oz = occ[4 * k + 2], orad = occ[4 * k + 3];
            // Vector from the surface point to the occluder centre.
            double wx = ox - nx, wy = oy - ny, wz = oz - nz;
            double along = wx * lx + wy * ly + wz * lz;   // distance toward the Sun
            if (along <= 0) continue;                     // occluder is not sunward -> no shadow
            double perp2 = wx * wx + wy * wy + wz * wz - along * along;
            if (perp2 < 0) perp2 = 0;
            double perp = Math.sqrt(perp2);               // ray miss distance from occluder centre
            double edge0 = orad * 0.80;                   // full umbra inside this radius
            double edge1 = orad * 1.25 + 0.01;            // penumbra fades to 0 out here
            if (perp >= edge1) continue;
            double s = perp <= edge0 ? 1.0 : (edge1 - perp) / (edge1 - edge0);
            s = s * s * (3 - 2 * s);                       // smoothstep penumbra
            factor *= 1.0 - 0.90 * s;                      // ~90% darkening in the umbra
        }
        return factor;
    }

    /** Reusable raster for the atmospheric limb glow (sized to the largest halo drawn). */
    private BufferedImage atmoBuf;

    /**
     * Draw a thin atmospheric limb glow around a body: a scattering shell that
     * peaks at the limb, bleeds a little past the disc, and is brightest on the
     * Sun-facing edge (with faint airglow the rest of the way round). Blended
     * <em>additively</em> so it brightens the lit limb of the disc and reads as
     * one glow that hugs the edge, rather than a ring floating in the black.
     *
     * @param tint    RGB of the haze
     * @param strength peak opacity (0..1)
     * @param heightR  scale height as a fraction of planet radius
     */
    private void drawAtmosphere(Graphics2D g2, double px, double py, double pr,
                                double lightDx, double lightDy,
                                int[] tint, double strength, double heightR) {
        if (tint == null || strength <= 0 || pr < 2) return;
        // Glow profile centered ON the limb: a rim that reaches a little onto the disc
        // (innerW) and only a thin bleed into space (H), so it hugs the surface instead
        // of standing off as a separate arc.
        double H = Math.max(0.008, heightR * 0.42);     // outer scale height (space side) - tight
        double innerW = Math.max(0.030, heightR);       // how far the rim reaches onto the disc
        double ext = 1.0 + 3.0 * H;                     // outer extent (~3 thin scale heights)
        double halfPx = pr * ext;
        // The glow is low-frequency, so a modest raster upscaled by the blit looks identical
        // while costing far fewer per-pixel exp() evaluations.
        int n = Math.min((int) Math.ceil(2 * halfPx) + 1, 384);
        if (n < 8) return;
        if (atmoBuf == null || atmoBuf.getWidth() != n) {
            atmoBuf = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        }
        BufferedImage img = atmoBuf;
        int[] out = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();

        double slen = Math.hypot(lightDx, lightDy);
        double lsx = slen > 1e-9 ? lightDx / slen : 1.0;
        double lsy = slen > 1e-9 ? lightDy / slen : 0.0;

        double c = (n - 1) / 2.0;
        double sPx = (2 * halfPx) / (n - 1);            // screen px per raster px
        int tr = tint[0], tg = tint[1], tb = tint[2];

        for (int iy = 0; iy < n; iy++) {
            double dyPx = (iy - c) * sPx;
            int row = iy * n;
            for (int ix = 0; ix < n; ix++) {
                double dxPx = (ix - c) * sPx;
                double rPx = Math.sqrt(dxPx * dxPx + dyPx * dyPx);
                double rUnit = rPx / pr;
                if (rUnit > ext) { out[row + ix] = 0; continue; }
                double dr = rUnit - 1.0;                 // signed distance from the limb
                double density;
                if (dr <= 0) {
                    double t = -dr / innerW;             // 0 at limb -> 1 at inner edge
                    density = t >= 1 ? 0 : (1 - t * t);  // brightest at the limb, onto the disc
                } else {
                    density = Math.exp(-dr / H);         // thin bleed just past the limb
                }
                if (density < 0.004) { out[row + ix] = 0; continue; }
                double dot = rPx > 1e-6 ? (dxPx * lsx + dyPx * lsy) / rPx : 0.0;
                double lit = 0.05 + 0.95 * clamp01(dot * 0.5 + 0.5);
                lit *= lit;                              // concentrate toward the day limb
                double a = clamp01(density * lit * strength);
                if (a < 0.003) { out[row + ix] = 0; continue; }
                double hot = Math.min(1.0, density) * 0.25;  // whiter at the very edge
                int rr = (int) (tr + (255 - tr) * hot);
                int gg = (int) (tg + (255 - tg) * hot);
                int bb = (int) (tb + (255 - tb) * hot);
                int ai = (int) (a * 255);
                out[row + ix] = (ai << 24) | (rr << 16) | (gg << 8) | bb;
            }
        }

        // Blit with the default (hardware-accelerated SrcOver) composite. A custom
        // additive Composite forces a full read-back of the accelerated back-buffer
        // every frame (the main cause of the ~1 fps stall); a bright, white-hot rim
        // over SrcOver reads almost identically without that cost.
        Object oldInterp = g2.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        // Draw the n x n halo centered on the body (x, y, width, height overload).
        g2.drawImage(img, (int) Math.round(px - halfPx), (int) Math.round(py - halfPx),
                (int) Math.round(2 * halfPx), (int) Math.round(2 * halfPx), null);
        if (oldInterp != null) g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterp);
    }

    private static double clamp01(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    /** Hermite smoothstep: 0 below {@code e0}, 1 above {@code e1}, eased in between. */
    private static double smoothstep(double e0, double e1, double x) {
        if (e1 <= e0) return x < e0 ? 0 : 1;
        double t = clamp01((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    private SphereGeom buildSphereGeom(int n, double obliquityDeg, int th) {
        SphereGeom geom = new SphereGeom(n);
        double[] fr = bodyFrame(obliquityDeg);
        double pX = fr[0], pY = fr[1], pZ = fr[2];
        double aX = fr[3], aY = fr[4], aZ = fr[5];
        double bX = fr[6], bY = fr[7], bZ = fr[8];
        double c = (n - 1) / 2.0, Rr = (n - 1) / 2.0;
        for (int iy = 0; iy < n; iy++) {
            for (int ix = 0; ix < n; ix++) {
                int idx = iy * n + ix;
                double nX = (ix - c) / Rr, nY = -(iy - c) / Rr;   // view y is up
                double rr = nX * nX + nY * nY;
                if (rr > 1.0) { geom.texY[idx] = -1; continue; }
                double nZ = Math.sqrt(1.0 - rr);
                double lat = Math.asin(nX * pX + nY * pY + nZ * pZ);
                double lon = Math.atan2(nX * bX + nY * bY + nZ * bZ, nX * aX + nY * aY + nZ * aZ);
                int ty = (int) ((0.5 - lat / Math.PI) * th);
                if (ty < 0) ty = 0; else if (ty >= th) ty = th - 1;
                geom.texY[idx] = ty;
                geom.lon0[idx] = lon;
                geom.nx[idx] = (float) nX;
                geom.ny[idx] = (float) nY;
                geom.nz[idx] = (float) nZ;
            }
        }
        return geom;
    }

    /**
     * Raytraced ringed planet (Saturn). One orthographic pass over the ring's
     * bounding box renders the lit textured sphere and the textured ring together,
     * resolving front/back occlusion by depth and casting real shadow rays:
     * the planet shadows the ring (ring point -> Sun ray blocked by the sphere)
     * and the ring shadows the planet (surface point -> Sun ray crossing the ring).
     */
    private RingGeom ringGeom;             // cached Saturn geometry (rebuilt on size change)
    private BufferedImage ringBuf;         // reused raster for Saturn

    /** Cached per-pixel geometry of the Saturn sphere+ring for a given raster size. */
    private static final class RingGeom {
        final int n; final double half;
        final int[] sTexY;                 // sphere texture row, or -1 (no sphere at pixel)
        final double[] sLon0;              // sphere base longitude
        final float[] snx, sny, snz;       // sphere unit normal (= surface point)
        final int[] ringARGB;              // ring base colour+alpha, or 0 (no ring at pixel)
        final float[] rpx, rpy, rpz;       // ring point (planet-radius units)
        final float[] rMag2;               // |ring point|^2
        RingGeom(int n, double half) {
            this.n = n; this.half = half; int s = n * n;
            sTexY = new int[s]; sLon0 = new double[s];
            snx = new float[s]; sny = new float[s]; snz = new float[s];
            ringARGB = new int[s]; rpx = new float[s]; rpy = new float[s]; rpz = new float[s];
            rMag2 = new float[s];
        }
    }

    private RingGeom buildRingGeom(int n, double half, int th, int[] lut, int lutN) {
        RingGeom G = new RingGeom(n, half);
        double[] fr = bodyFrame(OBLIQUITY_DEG[5]);
        double pX = fr[0], pY = fr[1], pZ = fr[2];
        double aX = fr[3], aY = fr[4], aZ = fr[5];
        double bX = fr[6], bY = fr[7], bZ = fr[8];
        double c = (n - 1) / 2.0;
        for (int iy = 0; iy < n; iy++) {
            double y = (-(iy - c) / c) * half;
            for (int ix = 0; ix < n; ix++) {
                double x = ((ix - c) / c) * half;
                int idx = iy * n + ix;
                double r2 = x * x + y * y;
                if (r2 <= 1.0) {
                    double zS = Math.sqrt(1.0 - r2);
                    double lat = Math.asin(x * pX + y * pY + zS * pZ);
                    double lon = Math.atan2(x * bX + y * bY + zS * bZ, x * aX + y * aY + zS * aZ);
                    int ty = (int) ((0.5 - lat / Math.PI) * th); if (ty < 0) ty = 0; else if (ty >= th) ty = th - 1;
                    G.sTexY[idx] = ty; G.sLon0[idx] = lon;
                    G.snx[idx] = (float) x; G.sny[idx] = (float) y; G.snz[idx] = (float) zS;
                } else {
                    G.sTexY[idx] = -1;
                }
                if (lut != null && Math.abs(pZ) > 1e-6) {
                    double zR = -(x * pX + y * pY) / pZ;
                    double mag2 = x * x + y * y + zR * zR;
                    double rad = Math.sqrt(mag2);
                    if (rad >= RING_INNER && rad <= RING_OUTER) {
                        int lc = lut[(int) (((rad - RING_INNER) / (RING_OUTER - RING_INNER)) * (lutN - 1))];
                        if (((lc >>> 24) & 0xFF) > 0) {
                            G.ringARGB[idx] = lc;
                            G.rpx[idx] = (float) x; G.rpy[idx] = (float) y; G.rpz[idx] = (float) zR;
                            G.rMag2[idx] = (float) mag2;
                        }
                    }
                }
            }
        }
        return G;
    }

    private boolean drawSaturn(Graphics2D g2, double px, double py, double pr,
                               double spin, boolean emissive, boolean drawRing,
                               double lightDx, double lightDy) {
        if (!loadTexture("saturn")) return false;
        int[] tex = texPixels.get("saturn");
        int tw = texSize.get("saturn")[0], th = texSize.get("saturn")[1];
        int[] lut = drawRing ? loadRingLut() : null;
        int lutN = (lut != null) ? lut.length : 0;

        double half = (lut != null) ? RING_OUTER : 1.05;      // half-extent in planet radii
        int n = Math.min((int) Math.ceil(2 * half * pr) + 1, RING_RASTER_CAP);
        if (n < 4) return false;

        // Geometry (sphere/ring hits, depths, normals) depends only on the raster
        // size + fixed pole, so cache it; per frame only texture/lighting/shadows vary.
        if (ringGeom == null || ringGeom.n != n || ringGeom.half != half) {
            ringGeom = buildRingGeom(n, half, th, lut, lutN);
        }
        RingGeom G = ringGeom;

        double[] fr = bodyFrame(OBLIQUITY_DEG[5]);
        double pX = fr[0], pY = fr[1], pZ = fr[2];
        double[] L = lightDirView(lightDx, lightDy);
        double lx = L[0], ly = L[1], lz = L[2];
        double lDotPole = lx * pX + ly * pY + lz * pZ;
        final double amb = 0.10;

        if (ringBuf == null || ringBuf.getWidth() != n) {
            ringBuf = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        }
        int[] out = ((DataBufferInt) ringBuf.getRaster().getDataBuffer()).getData();

        int len = n * n;
        for (int p = 0; p < len; p++) {
            // --- Sphere ---
            int sColor = 0; float zS = 0; boolean sHit = false;
            int sTexY = G.sTexY[p];
            if (sTexY >= 0) {
                sHit = true; zS = G.snz[p];
                double u = (G.sLon0[p] + spin) * INV_2PI; u -= Math.floor(u);
                int tx = (int) (u * tw); if (tx >= tw) tx = tw - 1;
                int rgb = tex[sTexY * tw + tx];
                if (emissive) {
                    sColor = rgb & 0xFFFFFF;
                } else {
                    float nx = G.snx[p], ny = G.sny[p], nz = zS;
                    double d = nx * lx + ny * ly + nz * lz; if (d < 0) d = 0;
                    double shade = amb + (1 - amb) * d;
                    if (lut != null && Math.abs(lDotPole) > 1e-6) {   // ring shadow on planet
                        double t = -(nx * pX + ny * pY + nz * pZ) / lDotPole;
                        if (t > 0) {
                            double hx = nx + t * lx, hy = ny + t * ly, hz = nz + t * lz;
                            double rad = Math.sqrt(hx * hx + hy * hy + hz * hz);
                            if (rad >= RING_INNER && rad <= RING_OUTER) {
                                int a = (lut[(int) (((rad - RING_INNER) / (RING_OUTER - RING_INNER)) * (lutN - 1))] >>> 24) & 0xFF;
                                shade *= 1.0 - (a / 255.0) * 0.8;
                            }
                        }
                    }
                    sColor = shadeRGB(rgb, shade);
                }
            }

            // --- Ring ---
            int rColor = 0, rA = 0; float zR = 0; boolean rHit = false;
            int rbase = G.ringARGB[p];
            if (rbase != 0) {
                rHit = true; rA = (rbase >>> 24) & 0xFF; zR = G.rpz[p];
                double rshade = emissive ? 1.0 : 0.9;
                if (!emissive) {                                     // planet shadow on ring
                    double bb = G.rpx[p] * lx + G.rpy[p] * ly + G.rpz[p] * lz;
                    double cc = G.rMag2[p] - 1.0;
                    double disc = bb * bb - cc;
                    if (disc > 0 && bb < 0) rshade = 0.16;
                }
                rColor = shadeRGB(rbase & 0xFFFFFF, rshade);
            }

            // --- Composite by depth ---
            int outc;
            if (sHit && (!rHit || zS >= zR)) {
                outc = 0xFF000000 | sColor;
            } else if (sHit && rHit) {
                int a = rA;
                int sr = (sColor >> 16) & 0xFF, sg = (sColor >> 8) & 0xFF, sb = sColor & 0xFF;
                int rr = (rColor >> 16) & 0xFF, rg = (rColor >> 8) & 0xFF, rb = rColor & 0xFF;
                int or = (rr * a + sr * (255 - a)) / 255;
                int og = (rg * a + sg * (255 - a)) / 255;
                int ob = (rb * a + sb * (255 - a)) / 255;
                outc = 0xFF000000 | (or << 16) | (og << 8) | ob;
            } else if (rHit) {
                outc = (rA << 24) | rColor;
            } else {
                outc = 0;
            }
            out[p] = outc;
        }

        Object oldInterp = g2.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        double drawHalf = half * pr;
        g2.drawImage(ringBuf, (int) Math.round(px - drawHalf), (int) Math.round(py - drawHalf),
                (int) Math.round(2 * drawHalf), (int) Math.round(2 * drawHalf), null);
        if (oldInterp != null) g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterp);
        return true;
    }

    /**
     * Get the bounding rectangle of the scale ruler for hit detection.
     */
    private Rectangle getScaleRulerBounds(int canvasHeight) {
        int scaleY = (scaleRulerY < 0) ? (canvasHeight + scaleRulerY) : scaleRulerY;
        int panelWidth = 200;
        int panelHeight = 65;
        return new Rectangle(scaleRulerX - 5, scaleY - 5, panelWidth, panelHeight);
    }

    /**
     * Check if a click is within the scale ruler bounds and start dragging if so.
     */
    private boolean handleScaleRulerClick(int mx, int my, int canvasHeight) {
        Rectangle bounds = getScaleRulerBounds(canvasHeight);
        if (bounds.contains(mx, my)) {
            scaleDragging = true;
            scaleDragOffsetX = mx - scaleRulerX;
            scaleDragOffsetY = my - (scaleRulerY < 0 ? (canvasHeight + scaleRulerY) : scaleRulerY);
            return true;
        }
        return false;
    }

    /**
     * Update scale ruler position during dragging.
     */
    private void updateScaleRulerDrag(int mx, int my) {
        if (!scaleDragging) return;
        scaleRulerX = mx - scaleDragOffsetX;

        // Convert back to relative coordinates if near bottom, absolute if moved away
        scaleRulerY = my - scaleDragOffsetY;
    }

    /**
     * Stop dragging the scale ruler.
     */
    private void stopScaleRulerDrag() {
        scaleDragging = false;
    }

    /**
     * Draw a dynamic scale ruler at the bottom-left, showing distances in AU and light-seconds/minutes/hours.
     * Similar to Google Maps scale. Position is draggable.
     */
    private void drawDynamicScale(Graphics2D g2, int canvasHeight) {
        // Position: calculate Y based on bottom offset or fixed position
        int scaleX = scaleRulerX;
        int scaleY = (scaleRulerY < 0) ? (canvasHeight + scaleRulerY) : scaleRulerY;

        // Determine the scale distance in pixels
        // We want a nice round number: 50, 100, 200, 500, etc.
        double pixelsPerAU = METERS_TO_PIXELS * currentZoom * AU;
        double minPixels = 80.0; // Minimum pixels for the scale bar
        double maxPixels = 150.0; // Maximum pixels for the scale bar

        // Calculate what 1 AU represents in pixels at current zoom
        double distanceInAUForMinPixels = minPixels / pixelsPerAU;

        // Find a nice round number (1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, etc.)
        double scaleAU = findNiceRoundNumber(distanceInAUForMinPixels);
        double scalePixels = scaleAU * pixelsPerAU;

        // Limit scale bar size
        while (scalePixels > maxPixels && scaleAU >= 0.01) {
            scaleAU = scaleAU / 2.0;
            scalePixels = scaleAU * pixelsPerAU;
        }
        while (scalePixels < minPixels && scaleAU < 100000) {
            scaleAU = scaleAU * 2.0;
            scalePixels = scaleAU * pixelsPerAU;
        }

        // Convert AU to other units
        double scaleMeters = scaleAU * AU;
        double lightSecond = 299792458.0; // meters per light-second
        double scaleLightSeconds = scaleMeters / lightSecond;

        String distanceLabel;
        if (scaleAU >= 1.0) {
            distanceLabel = String.format("%.2f AU", scaleAU);
        } else {
            distanceLabel = String.format("%.4f AU", scaleAU);
        }

        // Format light-time distance
        String timeLightLabel;
        if (scaleLightSeconds >= 3600.0) {
            double hours = scaleLightSeconds / 3600.0;
            timeLightLabel = String.format("%.2f light-hours", hours);
        } else if (scaleLightSeconds >= 60.0) {
            double minutes = scaleLightSeconds / 60.0;
            timeLightLabel = String.format("%.2f light-minutes", minutes);
        } else {
            timeLightLabel = String.format("%.2f light-seconds", scaleLightSeconds);
        }

        // Modern panel: soft shadow, gradient fill, subtle accent border
        int panelWidth = 200;
        int panelHeight = 65;
        int px = scaleX - 5, py = scaleY - 5;
        g2.setColor(new Color(0, 0, 0, 60));
        g2.fillRoundRect(px + 2, py + 3, panelWidth, panelHeight, 14, 14);
        g2.setPaint(new GradientPaint(px, py, new Color(20, 25, 40, 225),
                px, py + panelHeight, new Color(14, 18, 32, 225)));
        g2.fillRoundRect(px, py, panelWidth, panelHeight, 14, 14);
        g2.setColor(new Color(100, 150, 255, 110));
        g2.setStroke(new BasicStroke(1.2f));
        g2.drawRoundRect(px, py, panelWidth, panelHeight, 14, 14);

        int barY = scaleY + 20;
        int barStartX = scaleX + 12;
        int barEndX = (int) (barStartX + scalePixels);

        Font oldFont = g2.getFont();

        // Small caption
        g2.setFont(oldFont.deriveFont(Font.BOLD, 9f));
        g2.setColor(new Color(120, 150, 200));
        g2.drawString("SCALE", barStartX, scaleY + 4);

        // Accent scale bar with rounded caps and end ticks
        g2.setColor(new Color(120, 180, 255));
        g2.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.drawLine(barStartX, barY, barEndX, barY);
        int tickHeight = 5;
        g2.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.drawLine(barStartX, barY - tickHeight, barStartX, barY + tickHeight);
        g2.drawLine(barEndX, barY - tickHeight, barEndX, barY + tickHeight);

        // Distance label above the bar
        g2.setFont(oldFont.deriveFont(Font.BOLD, 11.5f));
        g2.setColor(new Color(232, 240, 255));
        FontMetrics fmB = g2.getFontMetrics();
        int auLabelWidth = fmB.stringWidth(distanceLabel);
        g2.drawString(distanceLabel, barStartX + (int) (scalePixels / 2) - auLabelWidth / 2, barY - 7);

        // Light-time label below the bar
        g2.setFont(oldFont.deriveFont(Font.PLAIN, 10f));
        g2.setColor(new Color(160, 180, 215));
        FontMetrics fmP = g2.getFontMetrics();
        int lightLabelWidth = fmP.stringWidth(timeLightLabel);
        g2.drawString(timeLightLabel, barStartX + (int) (scalePixels / 2) - lightLabelWidth / 2, barY + 18);

        g2.setFont(oldFont);
    }

    /**
     * Find a nice round number >= target for scale display.
     * Examples: 1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, etc.
     */
    private double findNiceRoundNumber(double target) {
        if (target <= 0) return 1.0;

        double magnitude = Math.pow(10, Math.floor(Math.log10(target)));
        double normalized = target / magnitude;

        double[] roundNumbers = {1, 2, 5, 10};
        for (double num : roundNumbers) {
            if (num >= normalized) {
                return num * magnitude;
            }
        }
        return 10 * magnitude;
    }

    /**
     * Realistic directional shading for top-down view: linear gradient with half circle in shadow.
     * Light source: Sun at barycenter.
     * @param px, py - screen coordinates of planet center
     */
    private void drawRealisticShading(Graphics2D g2, double worldX, double worldY,
                                      double radiusPx, double px, double py, int index) {
        if (radiusPx < 2) return;

        // Light direction: from body to Sun (Sun is at barycentric position)
        double lightX = sunBarycenterX - worldX;
        double lightY = sunBarycenterY - worldY;
        double distToSun = Math.hypot(lightX, lightY);
        if (distToSun == 0) return;
        lightX /= distToSun;
        lightY /= distToSun;

        // Create ellipse matching the planet's position
        Ellipse2D.Double fillEllipse =
                new Ellipse2D.Double(px - radiusPx, py - radiusPx, 2*radiusPx, 2*radiusPx);

        // Set clip to body shape BEFORE drawing gradient
        Shape oldClip = g2.getClip();
        g2.setClip(fillEllipse);

        // Gradient endpoints: very close together for abrupt transition
        // Lit point: just 0.3x radius toward sun (creates sharp edge)
        // Dark point: just 0.3x radius away from sun
        float litX = (float)(px + lightX * radiusPx * 1);
        float litY = (float)(py + lightY * radiusPx * 1);
        float darkX = (float)(px - lightX * radiusPx * 0.35);
        float darkY = (float)(py - lightY * radiusPx * 0.35);

        // Colors: normal planet color on lit side, black on shadow side
        Color litColor;
        if (index == -1) {
            // For moons - light gray
            litColor = Color.LIGHT_GRAY;
        } else {
            // For planets - use their normal color
            litColor = COLORS[index];
        }
        Color shadowColor = Color.BLACK;

        // Create linear gradient from lit to dark side
        GradientPaint gp = new GradientPaint(
                litX, litY, litColor,
                darkX, darkY, shadowColor
        );

        g2.setPaint(gp);
        g2.fill(fillEllipse);

        // Restore clip
        g2.setClip(oldClip);

        // Reset paint to solid color for subsequent drawing
        g2.setPaint(null);
    }

    /** Re-calculates targetZoom according to the current zoomMode */
    private void updateAutoZoomTarget() {
        if (cameraFocusIndex < 0 || followingMoon) return;

        int i = cameraFocusIndex;
        int screen = Math.min(getWidth(), getHeight());
        // Zoom cap high enough that even the smallest bodies (dwarf planets,
        // Mercury) fill the frame; the divisor scales with the body's true size.
        final double MAX_ZOOM = 3.0e6;

        double maxRadius;

        if (zoomMode == 0) {                         // 0 = Planet fills the view
            double desired = screen * 0.55;          // planet diameter ~55 % of the smaller side
            maxRadius = RADIUS_M[i];
            targetZoom = desired / (maxRadius * METERS_TO_PIXELS * 2);
            targetZoom = Math.max(5.0, Math.min(targetZoom, MAX_ZOOM));

        } else if (zoomMode == 1) {                  // 1 = Planet + its moons
            double desired = screen * 0.8;           // fit the moon system in the frame
            maxRadius = RADIUS_M[i] * 3.0;           // fall back to a few planet radii if no moons
            for (int m = 0; m < MOON_A_M[i].length; m++) {
                double moonDist = MOON_A_M[i][m] * 1.2;
                if (moonDist > maxRadius) maxRadius = moonDist;
            }
            targetZoom = desired / (maxRadius * METERS_TO_PIXELS * 2);
            targetZoom = Math.max(5.0, Math.min(targetZoom, MAX_ZOOM));

        } else {                                     // 2 = Manual – keep current
            targetZoom = currentZoom;
            autoZoomEnabled = false;
        }
    }

    /**
     * Toggles the visibility of the menu bar, search bar, and sidebar.
     * Called when user presses the 'H' key.
     */
    private void toggleMenuBarVisibility() {
        if (parentFrame == null) return;

        menuBarVisible = !menuBarVisible;
        uiPanelsVisible = !uiPanelsVisible;

        // Toggle menu bar
        JMenuBar menuBar = parentFrame.getJMenuBar();
        if (menuBar != null) {
            menuBar.setVisible(menuBarVisible);
        }

        // Toggle search bar
        if (searchBarPanel != null) {
            searchBarPanel.setVisible(uiPanelsVisible);
        }

        // Toggle sidebar
        if (sidebarPanel != null) {
            sidebarPanel.setVisible(uiPanelsVisible);
        }

        // Revalidate to update layout
        parentFrame.revalidate();
        parentFrame.repaint();
    }

    /* ==============================
       Time slider ↔ timescale helpers & UI sync
       ============================== */
    /** Slider -> timeScale mapping: slider value is exponent*100, timeScale = 10^(val/100) */
    private double sliderValueToTimeScale(int sliderVal) {
        int clamped = Math.max(TIME_SLIDER_MIN, Math.min(sliderVal, TIME_SLIDER_MAX));
        double fraction = (clamped - TIME_SLIDER_MIN) / (double)(TIME_SLIDER_MAX - TIME_SLIDER_MIN);
        double ts = TIME_SCALE_MIN + fraction * (TIME_SCALE_MAX - TIME_SCALE_MIN);
        return Math.max(TIME_SCALE_MIN, Math.min(ts, TIME_SCALE_MAX));
    }

    private int timeScaleToSliderValue(double ts) {
        double clamped = Math.max(TIME_SCALE_MIN, Math.min(ts, TIME_SCALE_MAX));
        double fraction = (clamped - TIME_SCALE_MIN) / (TIME_SCALE_MAX - TIME_SCALE_MIN);
        return (int)Math.round(TIME_SLIDER_MIN + fraction * (TIME_SLIDER_MAX - TIME_SLIDER_MIN));
    }

    private void updateSliderFromTimeScale() {
        if (timeScaleSlider == null) return;
        suppressTimeSliderEvents = true;
        try {
            int val = timeScaleToSliderValue(timeScale);
            timeScaleSlider.setValue(val);
        } finally {
            suppressTimeSliderEvents = false;
        }
    }

    private void updateTimeScaleLabel() {
        if (timeScaleLabel == null) return;
        String prefix = simulationPaused ? "Paused" : "Time";
        timeScaleLabel.setText(prefix + " " + formatTimeScale(timeScale));
    }

    /**
     * Formats the timeScale (in seconds per real second) into a human-friendly
     * unit: s/s, min/s, hr/s, day/s, week/s, month/s, yr/s depending on magnitude.
     */
    private String formatTimeScale(double ts) {
        if (!Double.isFinite(ts)) ts = 0;
        final double MINUTE = 60.0;
        final double HOUR = 3600.0;
        final double WEEK = 7 * DAY;
        final double MONTH = 30 * DAY;
        // Choose the most suitable unit
        String unit;
        double value;
        if (ts < MINUTE) { // seconds per second
            unit = "s/s";
            value = ts;
        } else if (ts < HOUR) { // minutes per second
            unit = "min/s";
            value = ts / MINUTE;
        } else if (ts < DAY) { // hours per second
            unit = "hr/s";
            value = ts / HOUR;
        } else if (ts < WEEK) { // days per second
            unit = "day/s";
            value = ts / DAY;
        } else if (ts < MONTH) { // weeks per second
            unit = "week/s";
            value = ts / WEEK;
        } else if (ts < YEAR) { // months per second (approx 30 days)
            unit = "month/s";
            value = ts / MONTH;
        } else { // years per second
            unit = "yr/s";
            value = ts / YEAR;
        }
        // Use 3 decimals for small values, 2 for medium, 1 for large
        String fmt;
        double abs = Math.abs(value);
        if (abs < 1) fmt = "%.3f"; else if (abs < 10) fmt = "%.2f"; else fmt = "%.1f";
        return String.format(fmt + " %s", value, unit);
    }

    private void setTimeScaleAndSync(double newScale) {
        timeScale = Math.max(TIME_SCALE_MIN, Math.min(newScale, TIME_SCALE_MAX));
        updateSliderFromTimeScale();
        updateTimeScaleLabel();
    }

    /**
     * Gets the orbit color for a given planet based on its body type.
     */
    private Color getOrbitColor(int planetIndex) {
        if (planetIndex < 0 || planetIndex >= BODY_TYPE.length) {
            return new Color(100, 100, 100, 100);
        }
        String type = BODY_TYPE[planetIndex];
        return ORBIT_COLORS_BY_TYPE.getOrDefault(type, new Color(100, 100, 100, 100));
    }

    @Override
    public void actionPerformed(ActionEvent ev) {
        long now = System.nanoTime();
        double dtReal = (now - lastNanos) / 1e9;  // Real delta time (seconds)
        lastNanos = now;

        // When paused, sim time (and thus orbits + spin) freezes; the camera and
        // guided-tour animations still use real time below so they stay responsive.
        double dtSim = simulationPaused ? 0.0 : dtReal * timeScale;
        simTimeSec += dtSim;

        // Update planet mean anomalies (Keplerian motion)
        for (int i = 0; i < NAMES.length; i++) {
            double periodSec = PERIOD_Y[i] * 365.25 * DAY;
            double n = 2 * Math.PI / periodSec;   // Mean motion
            meanAnomaly[i] = (meanAnomaly[i] + n * dtSim) % (2 * Math.PI);
        }

        // Update moon mean anomalies
        for (int i = 0; i < NAMES.length; i++) {
            for (int m = 0; m < moonMean[i].length; m++) {
                double periodSec = MOON_PERIOD_DAYS[i][m] * DAY;
                double n = 2 * Math.PI / periodSec;
                moonMean[i][m] = (moonMean[i][m] + n * dtSim) % (2 * Math.PI);
            }
        }

        // Solve Kepler's equation for planet positions (now full 3-D from precessed elements)
        for (int i = 0; i < NAMES.length; i++) {
            double a = A_METERS[i];
            double[] pos = planetXYZ(i);
            planetX[i] = pos[0];
            planetY[i] = pos[1];
            planetZ[i] = pos[2];

            // Compute distance and approximate speed (vis-viva)
            planetDistM[i] = Math.sqrt(pos[0] * pos[0] + pos[1] * pos[1] + pos[2] * pos[2]);
            double r = planetDistM[i];
            double mu = G * (sunMass + PLANET_MASS[i]);
            double v = Math.sqrt(Math.max(0.0, mu * (2.0 / r - 1.0 / a)));
            planetSpeedKmS[i] = v / 1000.0;
        }

        // Compute barycenter of the system (Sun + planets) from current Kepler positions
        computeBarycenter();

        /* ----- CAMERA & AUTO-ZOOM ----- */
        if (followingMoon && cameraFocusIndex >= 0 && cameraFocusMoon >= 0) {
            /* ---------- MOON FOLLOWING ---------- */
            int p = cameraFocusIndex, m = cameraFocusMoon;
            double a = MOON_A_M[p][m], e = MOON_ECC[p][m], b = a * Math.sqrt(1 - e * e);
            double M = moonMean[p][m];
            double E = M;
            for (int it = 0; it < 40; it++) {
                double f = E - e * Math.sin(E) - M;
                double fp = 1 - e * Math.cos(E);
                E -= f / fp;
                if (Math.abs(f) < 1e-12) break;
            }
            double mx = a * (Math.cos(E) - e);
            double my = b * Math.sin(E);
            double arg = MOON_ARG_PERI[p][m];
            double rxTemp = mx * Math.cos(arg) - my * Math.sin(arg);
            // Apply 90° clockwise rotation to fix coordinate system
            double rx = mx * Math.sin(arg) + my * Math.cos(arg);
            double ry = -rxTemp;
            // Use barycentric coordinates for camera target
            double targetX = planetX[p] + sunBarycenterX + rx;
            double targetY = planetY[p] + sunBarycenterY + ry;

            double dx = targetX - moonCamX;
            double dy = targetY - moonCamY;
            double dist = Math.hypot(dx, dy);

            if (moonCameraIsApproaching) {
                if (dist <= ARRIVAL_THRESHOLD) {
                    moonCamX = targetX; moonCamY = targetY;
                    moonCameraIsApproaching = false;
                } else {
                    double speed = 12.0;
                    double lerp = 1 - Math.exp(-speed * dtReal);
                    moonCamX += dx * lerp;
                    moonCamY += dy * lerp;
                }
            } else {
                moonCamX = targetX; moonCamY = targetY;
            }
            camX = moonCamX; camY = moonCamY;

            /* moon auto-zoom (unchanged) */
            if (autoZoomEnabled) {
                double moonRadius = MOON_RADIUS_M[p][m];
                double maxRadius = moonRadius * 1.5;
                int screen = Math.min(getWidth(), getHeight());
                double desired = screen * 0.6;
                targetZoom = desired / (maxRadius * METERS_TO_PIXELS * 2);
                targetZoom = Math.max(50.0, Math.min(targetZoom, 50000.0));
            }

        } else if (followingPlanet && cameraFocusIndex >= 0) {
            /* ---------- PLANET FOLLOWING ---------- */
            // Use barycentric coordinates for camera target
            double targetX = planetX[cameraFocusIndex] + sunBarycenterX;
            double targetY = planetY[cameraFocusIndex] + sunBarycenterY;
            double dx = targetX - camX;
            double dy = targetY - camY;
            double dist = Math.hypot(dx, dy);

            if (cameraIsApproaching) {
                if (dist <= ARRIVAL_THRESHOLD) {
                    camX = targetX; camY = targetY;
                    cameraIsApproaching = false;
                } else {
                    double speed = 12.0;
                    double lerp = 1 - Math.exp(-speed * dtReal);
                    camX += dx * lerp;
                    camY += dy * lerp;
                }
            } else {
                camX = targetX; camY = targetY;
            }

            /* ---------- AUTO-ZOOM (Planet / Orbit / Manual) ---------- */
            if (autoZoomEnabled) {
                updateAutoZoomTarget();               // <-- NEW CALL
            }

        } else {
            /* ---------- NO FOLLOW – return to Sun ---------- */
            double speed = 8.0;
            double lerp = 1 - Math.exp(-speed * dtReal);
            camX *= (1 - lerp);
            camY *= (1 - lerp);
            moonCamX = camX; moonCamY = camY;

            targetZoom = 1.0;
            cameraIsApproaching = moonCameraIsApproaching = false;
            autoZoomEnabled = false;                 // auto-zoom off when not following
        }

        // Keep the followed body vertically centred under an oblique (tilted) view, so its
        // out-of-plane height doesn't push it off-screen when zoomed in.
        if (followingMoon && cameraFocusIndex >= 0 && cameraFocusMoon >= 0) {
            camZ = planetZ[cameraFocusIndex] + moonRelPos(cameraFocusIndex, cameraFocusMoon)[2];
        } else if (followingPlanet && cameraFocusIndex >= 0) {
            camZ = planetZ[cameraFocusIndex];
        } else {
            camZ = 0;
        }

        /* ----- SMOOTH ZOOM INTERPOLATION ----- */
        if (autoZoomEnabled) {
            double lerp = 1 - Math.exp(-ZOOM_SPEED * dtReal);
            currentZoom += (targetZoom - currentZoom) * lerp;
        }

        /* ----- GUIDED TOUR UPDATE ----- */
        if (lessonMode && guidedTourActive) {
            updateGuidedTour(dtReal);
        }

        repaint();
    }

    /**
     * Position of moon {@code m} of planet {@code i} relative to the planet centre
     * (metres) as {@code {rx, ry, rz}}: {@code rx,ry} are the ecliptic-plane position
     * (unchanged, so screen placement and orbit paths are identical), and {@code rz} is
     * the out-of-plane component from the moon's orbital inclination — the piece that
     * makes eclipse geometry correct (shadows at real latitudes, eclipses only near a
     * node). Matches the inline solve used when drawing moons.
     */
    private double[] moonRelPos(int i, int m) {
        double a = MOON_A_M[i][m], e = MOON_ECC[i][m], b = a * Math.sqrt(1 - e * e);
        double M = moonMean[i][m];
        double E = M;
        for (int it = 0; it < 40; it++) {
            double f = E - e * Math.sin(E) - M;
            double fp = 1 - e * Math.cos(E);
            E -= f / fp;
            if (Math.abs(f) < 1e-12) break;
        }
        double mx = a * (Math.cos(E) - e);
        double my = b * Math.sin(E);
        double arg = MOON_ARG_PERI[i][m];
        double rxTemp = mx * Math.cos(arg) - my * Math.sin(arg);
        double rx = mx * Math.sin(arg) + my * Math.cos(arg);   // 90° fix (matches draw loop)
        double ry = -rxTemp;
        double rz = 0.0;
        double inclDeg = (i < MOON_INCL_DEG.length && m < MOON_INCL_DEG[i].length) ? MOON_INCL_DEG[i][m] : 0.0;
        if (inclDeg != 0.0) {
            double incl = Math.toRadians(inclDeg);
            double node = Math.toRadians((i < MOON_NODE_DEG.length && m < MOON_NODE_DEG[i].length)
                    ? MOON_NODE_DEG[i][m] : 0.0);
            double rmag = Math.hypot(rx, ry);
            double phi = Math.atan2(ry, rx);            // ecliptic longitude of the moon
            rz = rmag * Math.sin(incl) * Math.sin(phi - node);   // height above the ecliptic
        }
        return new double[]{rx, ry, rz};
    }

    /** Compute system barycenter and set sunBarycenterX/Y accordingly. */
    private void computeBarycenter() {
        double totalPx = 0.0, totalPy = 0.0;
        for (int i = 0; i < NAMES.length; i++) {
            double m = PLANET_MASS[i];
            totalPx += m * planetX[i];
            totalPy += m * planetY[i];
        }
        // Sun offset so that center of mass is at origin: M_sun * r_sun + sum m_i r_i = 0
        sunBarycenterX = -totalPx / M_SUN;
        sunBarycenterY = -totalPy / M_SUN;
    }

    /**
     * Updates the guided tour for different lessons
     * This method manages tour steps with manual progression (press N to continue)
     */
    private void updateGuidedTour(double dtReal) {
        // Update tour timer
        tourStepStartTime += dtReal;

        // Fade in tour message
        if (tourMessageAlpha < 1.0) {
            tourMessageAlpha += TOUR_MESSAGE_FADE_SPEED * dtReal;
            if (tourMessageAlpha > 1.0) tourMessageAlpha = 1.0;
        }

        // After message is fully faded in and camera settled, allow advancing
        if (tourMessageAlpha >= 1.0 && tourStepStartTime > 1.5) {
            tourStepReady = true;
        }

        // Route to appropriate lesson tour
        if (currentLesson == 1) {
            updateLesson1Tour(dtReal);
        } else if (currentLesson == 2) {
            updateLesson2Tour(dtReal);
        }
    }

    /**
     * Lesson 1 Tour: Introduction to Orbital Motion
     */
    private void updateLesson1Tour(double dtReal) {
        // Lesson 1 Tour Steps - execute setup on first frame only
        if (tourStepStartTime < dtReal * 2) { // First frame of this step
            switch (tourStep) {
                case 0: // Welcome & Setup
                    showPlanetOrbits = true;
                    showPerihelionAphelion = true;
                    showKeplersLaw = true;
                    hideAllPlanetsExcept(-1); // Show all for overview
                    returnToSun();
                    currentZoom = 2.0;
                    targetZoom = 2.0;
                    tourMessage = """
                            Welcome to Lesson 1: Introduction to Orbital Motion
                            We'll explore how planets orbit the Sun.
                            
                            Press 'N' to continue to the next step.""";
                    break;

                case 1: // Focus on Venus (circular orbit)
                    hideAllPlanetsExcept(findPlanetIndex("Venus"));
                    focusPlanetByNameWithZoom("Venus", 1.0);  // Show full orbit comfortably
                    tourMessage = """
                            Venus - Nearly Perfect Circle
                            Eccentricity ≈ 0.007
                            Notice how circular the orbit looks!
                            
                            Press 'N' to continue.""";
                    break;

                case 2: // Focus on Earth
                    hideAllPlanetsExcept(findPlanetIndex("Earth"));
                    focusPlanetByNameWithZoom("Earth", 0.79);  // Show full orbit comfortably
                    tourMessage = """
                            Earth - Also Quite Circular
                            Eccentricity ≈ 0.017
                            Our orbit is nearly circular too.
                            
                            Press 'N' to continue.""";
                    break;

                case 3: // Focus on Mars
                    hideAllPlanetsExcept(findPlanetIndex("Mars"));
                    focusPlanetByNameWithZoom("Mars", 0.5);  // Show full orbit
                    tourMessage = """
                            Mars - More Elliptical
                            Eccentricity ≈ 0.093
                            The orbit starts to look stretched.
                            
                            Press 'N' to continue.""";
                    break;

                case 4: // Focus on Mercury
                    hideAllPlanetsExcept(findPlanetIndex("Mercury"));
                    focusPlanetByNameWithZoom("Mercury", 1.75);  // Smaller orbit, can zoom more
                    tourMessage = """
                            Mercury - Quite Elliptical
                            Eccentricity ≈ 0.206
                            Notice the red (P) and blue (A) markers
                            showing perihelion and aphelion points.
                            
                            Press 'N' to continue.""";
                    break;

                case 5: // Focus on Pluto
                    hideAllPlanetsExcept(findPlanetIndex("Pluto"));
                    focusPlanetByNameWithZoom("Pluto", 0.02);  // Large orbit, zoom out (slightly more than 0.04)
                    tourMessage = """
                            Pluto - Highly Elliptical
                            Eccentricity ≈ 0.250
                            The most eccentric orbit in our tour!
                            Distance varies dramatically.
                            
                            Press 'N' to continue.""";
                    break;

                case 6: // Kepler's Third Law - Mercury
                    hideAllPlanetsExcept(findPlanetIndex("Mercury"));
                    focusPlanetByNameWithZoom("Mercury", 1.75);
                    tourMessage = """
                            Kepler's Third Law Verification
                            Mercury: P² / a³ ≈ 1.0
                            Check the yellow line in the info panel!
                            
                            Press 'N' to continue.""";
                    break;

                case 7: // Kepler's Third Law - Earth
                    hideAllPlanetsExcept(findPlanetIndex("Earth"));
                    focusPlanetByNameWithZoom("Earth", 0.79);
                    tourMessage = """
                            Kepler's Third Law Verification
                            Earth: P² / a³ = 1.0000 (exactly!)
                            By definition of AU and year.
                            
                            Press 'N' to continue.""";
                    break;

                case 8: // Kepler's Third Law - Jupiter
                    hideAllPlanetsExcept(findPlanetIndex("Jupiter"));
                    focusPlanetByNameWithZoom("Jupiter", 0.1);  // Large orbit, zoom out more
                    tourMessage = """
                            Kepler's Third Law Verification
                            Jupiter: P² / a³ ≈ 1.0
                            The ratio is constant for all planets!
                            
                            Press 'N' to continue.""";
                    break;

                case 9: // Return to overview
                    restorePlanetVisibility();
                    returnToSun();
                    currentZoom = 1.5;
                    targetZoom = 1.5;
                    tourMessage = """
                            Tour Complete!
                            All planet visibility has been restored.
                            Feel free to explore on your own.
                            
                            Press 'N' to end the tour.""";
                    break;

                case 10: // End tour
                    guidedTourActive = false;
                    tourStep = 0;
                    tourMessage = "";
                    tourMessageAlpha = 0.0;
                    tourStepReady = false;
                    restorePlanetVisibility();
                    break;
            }
        }
    }

    /**
     * Lesson 2 Tour: Orbital Velocity and Kepler's 2nd Law
     */
    private void updateLesson2Tour(double dtReal) {
        // Lesson 2 Tour Steps - execute setup on first frame only
        if (tourStepStartTime < dtReal * 2) { // First frame of this step
            switch (tourStep) {
                case 0: // Welcome & Setup
                    showPlanetOrbits = true;
                    showPerihelionAphelion = true;
                    showVelocityVectors = true;
                    showVelocityComparison = true;
                    hideAllPlanetsExcept(-1); // Show all for overview
                    returnToSun();
                    currentZoom = 2.0;
                    targetZoom = 2.0;
                    tourMessage = """
                            Welcome to Lesson 2: Orbital Velocity
                            We'll explore how velocity changes around an orbit.
                            Velocity vectors show speed and direction.
                            
                            Press 'N' to continue to the next step.""";
                    break;

                case 1: // Explain velocity vectors
                    returnToSun();
                    currentZoom = 1.5;
                    targetZoom = 1.5;
                    tourMessage = """
                            Velocity Vectors
                            Arrows show each planet's velocity.
                            Blue = slower, Red = faster.
                            Notice how colors change around the orbit!
                            
                            Press 'N' to continue.""";
                    break;

                case 2: // Mercury at perihelion
                    hideAllPlanetsExcept(findPlanetIndex("Mercury"));
                    focusPlanetByNameWithZoom("Mercury", 1.75);  // Same as Lesson 1
                    tourMessage = """
                            Mercury - High Eccentricity
                            Watch the velocity arrow change color
                            as Mercury moves around its orbit.
                            Red = fast (perihelion), Blue = slow (aphelion).
                            
                            Press 'N' to continue.""";
                    break;

                case 3: // Mars velocity demonstration
                    hideAllPlanetsExcept(findPlanetIndex("Mars"));
                    focusPlanetByNameWithZoom("Mars", 0.5);  // Same as Lesson 1
                    tourMessage = """
                            Mars - Velocity Variation
                            Current speed shown next to arrow.
                            Mars moves faster when closer to the Sun.
                            This is Kepler's 2nd Law in action!
                            
                            Press 'N' to continue.""";
                    break;

                case 4: // Earth circular orbit comparison
                    hideAllPlanetsExcept(findPlanetIndex("Earth"));
                    focusPlanetByNameWithZoom("Earth", 0.79);  // Same as Lesson 1
                    tourMessage = """
                            Earth - Nearly Constant Velocity
                            Earth's nearly circular orbit means
                            velocity stays almost constant.
                            Speed: ~30 km/s year-round.
                            
                            Press 'N' to continue.""";
                    break;

                case 5: // Pluto extreme case
                    hideAllPlanetsExcept(findPlanetIndex("Pluto"));
                    focusPlanetByNameWithZoom("Pluto", 0.02);  // Same as Lesson 1
                    tourMessage = """
                            Pluto - Extreme Velocity Change
                            Highly elliptical orbit = huge velocity range!
                            Perihelion: ~6.1 km/s (fast)
                            Aphelion: ~3.7 km/s (slow)
                            
                            Press 'N' to continue.""";
                    break;

                case 6: // Kepler's 2nd Law explanation
                    returnToSun();
                    currentZoom = 1.5;
                    targetZoom = 1.5;
                    hideAllPlanetsExcept(-1); // Show all
                    tourMessage = """
                            Kepler's Second Law
                            "Equal areas in equal times"
                            Planets move faster when closer to the Sun
                            to sweep equal orbital areas.
                            
                            Press 'N' to continue.""";
                    break;

                case 7: // Return to overview
                    restorePlanetVisibility();
                    returnToSun();
                    currentZoom = 1.5;
                    targetZoom = 1.5;
                    tourMessage = """
                            Velocity Tour Complete!
                            You can toggle velocity vectors anytime:
                            Education → Lesson 2 Features
                            
                            Press 'N' to end the tour.""";
                    break;

                case 8: // End tour
                    guidedTourActive = false;
                    tourStep = 0;
                    tourMessage = "";
                    tourMessageAlpha = 0.0;
                    tourStepReady = false;
                    showVelocityVectors = false;
                    showVelocityComparison = false;
                    restorePlanetVisibility();
                    break;
            }
        }
    }

    /**
     * Advances to the next tour step
     */
    private void advanceTourStep() {
        tourStep++;
        tourStepStartTime = 0;
        tourMessageAlpha = 0.0;
        tourStepReady = false;
    }

    /**
     * Find the index of a planet by name
     */
    private int findPlanetIndex(String name) {
        for (int i = 0; i < NAMES.length; i++) {
            if (NAMES[i].equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Focuses camera on a planet by name with custom zoom level
     */
    private void focusPlanetByNameWithZoom(String name, double zoom) {
        for (int i = 0; i < NAMES.length; i++) {
            if (NAMES[i].equals(name)) {
                selectedPlanet = i;
                selectedMoon = -1;
                cameraFocusMoon = -1;
                autoZoomEnabled = false; // Disable auto-zoom to use our custom zoom
                focusPlanetImmediate(i);
                targetZoom = zoom;
                currentZoom = zoom;
                return;
            }
        }
    }

    /**
     * Hide all planets except the specified one (or show all if index is -1)
     */
    private void hideAllPlanetsExcept(int planetIndex) {
        // Save current visibility state on first call
        if (savedPlanetVisibility == null) {
            savedPlanetVisibility = new boolean[planetVisible.length];
            System.arraycopy(planetVisible, 0, savedPlanetVisibility, 0, planetVisible.length);
        }

        if (planetIndex == -1) {
            // Show all planets
            Arrays.fill(planetVisible, true);
        } else {
            // Hide all except the specified planet
            for (int i = 0; i < planetVisible.length; i++) {
                planetVisible[i] = (i == planetIndex);
            }
        }
    }

    /**
     * Restore planet visibility to saved state
     */
    private void restorePlanetVisibility() {
        if (savedPlanetVisibility != null) {
            System.arraycopy(savedPlanetVisibility, 0, planetVisible, 0, planetVisible.length);
            savedPlanetVisibility = null;
        }
    }

    /**
     * Stage a geometrically real eclipse for moon {@code m} of planet {@code i}. Because
     * the moon's orbit is inclined, an eclipse only happens when the moon is near a node at
     * syzygy; this nudges the node ~a few degrees off the Sun's current longitude (a valid
     * node orientation) and then finds the orbital phase where the moon lines up, so the
     * umbra lands on the visible disc (solar) or the moon sits in the planet's shadow (lunar).
     * Returns true if a configuration was found. Uses simulated positions as they are now.
     */
    private boolean stageEclipse(int i, int m, boolean solar) {
        if (m >= MOON_A_M[i].length) return false;
        // Sun direction from the planet, in the view frame (ecliptic is the screen plane).
        double phiSun = Math.atan2(-planetY[i], -planetX[i]);
        double sxw = -planetX[i], syw = -planetY[i];
        double sl = Math.hypot(sxw, syw);
        if (sl < 1e-9) return false;
        double lx = sxw / sl, ly = -syw / sl, lz = 0.0;          // world -> view (y negated)
        // Orient the node a few degrees off the Sun so the aligned moon sits slightly out of
        // plane -> the umbra lands at a mid latitude (solar) / deep in the umbra (lunar).
        if (i < MOON_NODE_DEG.length && m < MOON_NODE_DEG[i].length && MOON_INCL_DEG[i][m] != 0.0) {
            double off = solar ? 8.0 : 3.0;
            MOON_NODE_DEG[i][m] = Math.toDegrees(phiSun) - off;
        }
        double invR = 1.0 / RADIUS_M[i];
        double sign = solar ? 1.0 : -1.0;                        // sunward vs anti-sunward
        double saved = moonMean[i][m];
        double best = -1e9, bestMean = saved;
        for (int s = 0; s < 5760; s++) {
            double ang = 2 * Math.PI * s / 5760.0;
            moonMean[i][m] = ang;
            double[] rel = moonRelPos(i, m);
            double mx = rel[0] * invR, my = -rel[1] * invR, mz = rel[2] * invR;
            double along = mx * lx + my * ly + mz * lz;          // + = sunward
            if (sign * along <= 0) continue;                      // wrong side for this eclipse
            double perp2 = mx * mx + my * my + mz * mz - along * along;
            if (perp2 > 1.0) continue;                            // shadow axis misses the receiver
            // Solar: prefer the umbra as face-on as possible (high |mz| = higher latitude, more
            // circular). Lunar: prefer the moon deepest in the umbra (smallest miss distance).
            double score = solar ? Math.abs(mz) : -perp2;
            if (score > best) { best = score; bestMean = ang; }
        }
        moonMean[i][m] = bestMean;
        return best > -1e8;
    }

    /**
     * Stage and jump to a real Earth eclipse: pauses the sim, positions the Moon, and
     * frames Earth (solar) or the Moon (lunar). Live-app counterpart of the headless shots.
     */
    void showEclipse(boolean solar) {
        simulationPaused = true;
        updateTimeScaleLabel();
        if (!stageEclipse(2, 0, solar)) return;
        autoZoomEnabled = false;
        cameraIsApproaching = moonCameraIsApproaching = false;
        int screen = Math.max(1, Math.min(getWidth(), getHeight()));
        if (solar) {
            followingPlanet = true; followingMoon = false;
            cameraFocusIndex = 2; cameraFocusMoon = -1;
            selectedPlanet = 2; selectedMoon = -1;
            camX = planetX[2] + sunBarycenterX;
            camY = planetY[2] + sunBarycenterY;
            currentZoom = (0.42 * screen) / (RADIUS_M[2] * METERS_TO_PIXELS);
        } else {
            followingPlanet = true; followingMoon = true;
            cameraFocusIndex = 2; cameraFocusMoon = 0;
            selectedPlanet = 2; selectedMoon = 0;
            double[] rel = moonRelPos(2, 0);
            moonCamX = planetX[2] + sunBarycenterX + rel[0];
            moonCamY = planetY[2] + sunBarycenterY + rel[1];
            camX = moonCamX; camY = moonCamY;
            currentZoom = (0.30 * screen) / (RADIUS_M[2] * MOON_SIZE_RATIO[2][0] * METERS_TO_PIXELS);
        }
        targetZoom = currentZoom;
        repaint();
    }

    /**
     * Returns camera to Sun overview
     */
    private void returnToSun() {
        followingPlanet = followingMoon = false;
        cameraFocusIndex = cameraFocusMoon = -1;
        selectedPlanet = selectedMoon = -1;
        cameraIsApproaching = moonCameraIsApproaching = false;
        currentZoom = 1.0;
        targetZoom = 1.0;
        camX = 0;
        camY = 0;
    }

    /**
     * Custom checkbox menu item that doesn't close the menu
     */
    static class StayOpenCheckBoxMenuItem extends JCheckBoxMenuItem {

        public StayOpenCheckBoxMenuItem(String text, boolean selected) {
            super(text, selected);
        }

        @Override
        protected void processMouseEvent(MouseEvent evt) {
            if (evt.getID() == MouseEvent.MOUSE_RELEASED) {
                // Capture current menu path BEFORE the default click closes menus.
                final MenuSelectionManager msm = MenuSelectionManager.defaultManager();
                final MenuElement[] oldPath = msm.getSelectedPath();

                // Let Swing do the normal toggle + ActionListener firing order.
                super.doClick(0);

                // Re-apply the previous selection path on the next tick to keep the menu open.
                SwingUtilities.invokeLater(() -> {
                    if (oldPath != null && oldPath.length > 0) {
                        msm.setSelectedPath(oldPath);
                    }
                });

                evt.consume();
            } else {
                super.processMouseEvent(evt);
            }
        }
    }

    /**
     * Custom radio button menu item that doesn't close the menu
     */
    static class StayOpenRadioButtonMenuItem extends JRadioButtonMenuItem {
        public StayOpenRadioButtonMenuItem(String text, boolean selected) {
            super(text, selected);
        }

        @Override
        protected void processMouseEvent(MouseEvent evt) {
            if (evt.getID() == MouseEvent.MOUSE_RELEASED) {
                // Capture current menu path BEFORE the default click closes menus.
                final MenuSelectionManager msm = MenuSelectionManager.defaultManager();
                final MenuElement[] oldPath = msm.getSelectedPath();

                // Let Swing do the normal toggle + ActionListener firing order.
                super.doClick(0);

                // Re-apply the previous selection path on the next tick to keep the menu open.
                SwingUtilities.invokeLater(() -> {
                    if (oldPath != null && oldPath.length > 0) {
                        msm.setSelectedPath(oldPath);
                    }
                });

                evt.consume();
            } else {
                super.processMouseEvent(evt);
            }
        }
    }

    /* ==============================
       MAIN
       ============================== */
    /* ==============================
       HEADLESS SCREENSHOTS  (-Dsss.shot=<dir>)
       ============================== */
    /**
     * Renders a fixed set of showcase frames to PNG files off-screen (no window),
     * so rendering changes can be verified without a live display. Triggered by the
     * {@code sss.shot} system property. Run from the project root so
     * {@code assets/textures/*} resolve.
     */
    private void renderHeadlessShots(String dir) {
        final int W = 1600, H = 1000;
        try { new File(dir).mkdirs(); } catch (Exception ignored) { }
        setSize(W, H);
        shadingEnabled = true; showAtmosphere = true; showEclipses = true;
        showRings = true; showLabels = false;
        computeBarycenter();

        // A) Earth: blue limb glow + day/night terminator.
        focusPlanetHeadless(2, W, H, 0.60);
        writeShot(dir + "/shot_earth_atmo.png", W, H);

        // B) Solar eclipse: Moon's umbra falls on Earth's day side (circular shadow).
        stageEclipse(2, 0, true);
        focusPlanetHeadless(2, W, H, 0.85);
        writeShot(dir + "/shot_solar_eclipse.png", W, H);

        // C) Lunar eclipse: Moon sits in Earth's shadow cone.
        stageEclipse(2, 0, false);
        focusMoonHeadless(2, 0, W, H, 0.45);
        writeShot(dir + "/shot_lunar_eclipse.png", W, H);

        // D) Venus: thick, high sulphuric haze (strong halo).
        focusPlanetHeadless(1, W, H, 0.55);
        writeShot(dir + "/shot_venus_atmo.png", W, H);

        // E) Saturn: verify the halo coexists with the ring raytrace.
        focusPlanetHeadless(5, W, H, 0.45);
        writeShot(dir + "/shot_saturn.png", W, H);

        // F) Earth night side: spin so city-rich longitudes fall into shadow, to
        //    showcase the emissive night lights + clouds over the terminator.
        simTimeSec = 9.0 * 3600.0;
        focusPlanetHeadless(2, W, H, 0.70);
        writeShot(dir + "/shot_earth_night.png", W, H);
        simTimeSec = 0.0;

        // G) Mars: shows off the GPU normal-map hook (real surface relief near the
        //    terminator). Only visibly different when the GPU path is on AND a
        //    mars_normal map exists; identical to the CPU look otherwise.
        focusPlanetHeadless(3, W, H, 0.70);
        writeShot(dir + "/shot_mars_relief.png", W, H);

        // H) Inner system, tilted ~32° off top-down: orbit planes fan apart by their real
        //    inclinations and each planet sits above/below the ecliptic by its current phase.
        showLabels = true; showPlanetOrbits = true;
        focusSystemHeadless(3.3 * AU, W, H, 0.45);
        setViewPitch(Math.toRadians(32));
        writeShot(dir + "/shot_tilt_inner.png", W, H);

        // I) Wide view to Pluto (incl 17.1°, ecc 0.25), tilted ~35°: its orbit rides well out
        //    of the ecliptic and crosses Neptune's — the clearest showcase of genuine tilt.
        focusSystemHeadless(42.0 * AU, W, H, 0.45);
        setViewPitch(Math.toRadians(35));
        writeShot(dir + "/shot_tilt_pluto.png", W, H);
        setViewPitch(0.0);
        showLabels = false;
    }

    /** Centre on the Sun and zoom so a half-extent of {@code spanMeters} spans {@code frac} of the view. */
    private void focusSystemHeadless(double spanMeters, int w, int h, double frac) {
        followingPlanet = followingMoon = false;
        selectedPlanet = -1; selectedMoon = -1; showOnlySelected = false;
        camX = sunBarycenterX; camY = sunBarycenterY; camZ = 0;
        currentZoom = (frac * Math.min(w, h)) / (spanMeters * METERS_TO_PIXELS);
    }

    /** Centre the camera on a planet and zoom so its disc spans {@code frac} of the view. */
    private void focusPlanetHeadless(int i, int w, int h, double frac) {
        followingPlanet = followingMoon = false;
        selectedPlanet = -1; selectedMoon = -1; showOnlySelected = false;
        camX = planetX[i] + sunBarycenterX;
        camY = planetY[i] + sunBarycenterY;
        double targetPr = frac * Math.min(w, h) / 2.0;
        currentZoom = targetPr / (RADIUS_M[i] * METERS_TO_PIXELS);
    }

    /** Centre the camera on a moon and zoom so its disc spans {@code frac} of the view. */
    private void focusMoonHeadless(int i, int m, int w, int h, double frac) {
        followingPlanet = followingMoon = false;
        selectedPlanet = -1; selectedMoon = -1; showOnlySelected = false;
        double[] rel = moonRelPos(i, m);
        camX = planetX[i] + sunBarycenterX + rel[0];
        camY = planetY[i] + sunBarycenterY + rel[1];
        double moonR = RADIUS_M[i] * MOON_SIZE_RATIO[i][m];
        double targetPr = frac * Math.min(w, h) / 2.0;
        currentZoom = targetPr / (moonR * METERS_TO_PIXELS);
    }

    /** Render the current scene to an off-screen PNG. */
    private void writeShot(String path, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, w, h);
        paintComponent(g);
        g.dispose();
        try {
            ImageIO.write(img, "png", new File(path));
            System.out.println("  wrote " + path);
        } catch (Exception e) {
            System.err.println("  failed " + path + ": " + e);
        }
    }

    public static void main(String[] args) {
        // Off-screen screenshot mode: render showcase frames and exit (no UI).
        String shotDir = System.getProperty("sss.shot");
        if (shotDir != null) {
            new SolarSystemSimulation().renderHeadlessShots(shotDir);
            System.out.println("Headless shots complete: " + shotDir);
            System.exit(0);
        }
        if (System.getProperty("sss.bench") != null) {
            SolarSystemSimulation p = new SolarSystemSimulation();
            p.setSize(1600, 1000);
            p.computeBarycenter();
            p.followingPlanet = true; p.cameraFocusIndex = 2;
            p.camX = p.planetX[2] + p.sunBarycenterX; p.camY = p.planetY[2] + p.sunBarycenterY;
            p.currentZoom = (0.85 * 1000) / (RADIUS_M[2] * METERS_TO_PIXELS);
            BufferedImage bi = new BufferedImage(1600, 1000, BufferedImage.TYPE_INT_RGB);
            Graphics2D bg = bi.createGraphics();
            double[] fracs = {0.55, 0.85, 1.6};   // Earth fits / fills / overfills the viewport
            String[] labels = {"Earth fits", "Earth fills", "Earth overfills"};
            for (int c = 0; c < fracs.length; c++) {
                p.currentZoom = (fracs[c] * 1000) / (RADIUS_M[2] * METERS_TO_PIXELS);
                for (int i = 0; i < 40; i++) p.paintComponent(bg);   // warm up
                int frames = 60;
                long t0 = System.nanoTime();
                for (int i = 0; i < frames; i++) p.paintComponent(bg);
                double ms = (System.nanoTime() - t0) / 1e6 / frames;
                System.out.printf("%-16s %6.1f ms/frame  (~%.0f fps)%n", labels[c], ms, 1000.0 / ms);
            }
            System.exit(0);
        }

        // Modern flat dark look-and-feel for all Swing chrome (menus, dialogs,
        // tree, split panes, sliders). Falls back to the platform default if
        // FlatLaf is unavailable at runtime.
        try {
            UIManager.put("Component.arc", 10);
            UIManager.put("Button.arc", 10);
            UIManager.put("TextComponent.arc", 8);
            UIManager.put("ScrollBar.thumbArc", 10);
            UIManager.put("ScrollBar.width", 12);
            UIManager.put("Component.focusWidth", 1);
            UIManager.put("MenuItem.selectionArc", 8);
            FlatDarkLaf.setup();
            // Accent tuned to match the in-canvas HUD/info panels.
            UIManager.put("Component.accentColor", new Color(100, 150, 255));
        } catch (Throwable ignored) {
            // FlatLaf not on the classpath (or failed) -> keep default L&F
        }

        JFrame frame = new JFrame("Solar System Simulation");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        SolarSystemSimulation panel = new SolarSystemSimulation();
        panel.parentFrame = frame; // Set frame reference for menu toggling
        // ----- Build professional-style UI frame -----
         // Top-level menu bar
         JMenuBar menuBar = new JMenuBar();

        // Simulation menu
        JMenu simMenu = new JMenu("Simulation");
        JMenuItem resetViewItem = new JMenuItem("Reset View to Sun");
        resetViewItem.addActionListener(_ -> {
            panel.followingPlanet = false;
            panel.followingMoon = false;
            panel.cameraFocusIndex = -1;
            panel.cameraFocusMoon = -1;
            panel.selectedPlanet = -1;
            panel.selectedMoon = -1;
            panel.camX = 0;
            panel.camY = 0;
            panel.currentZoom = 1.0;
        });
        JMenuItem solarEclipseItem = new JMenuItem("Show solar eclipse (Earth)");
        solarEclipseItem.addActionListener(_ -> panel.showEclipse(true));
        JMenuItem lunarEclipseItem = new JMenuItem("Show lunar eclipse (Earth)");
        lunarEclipseItem.addActionListener(_ -> panel.showEclipse(false));
        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.addActionListener(_ -> System.exit(0));
        simMenu.add(resetViewItem);
        simMenu.addSeparator();
        simMenu.add(solarEclipseItem);
        simMenu.add(lunarEclipseItem);
        simMenu.addSeparator();
        simMenu.add(exitItem);

        // View menu – mirrors keyboard toggles
        JMenu viewMenu = new JMenu("View");
        StayOpenCheckBoxMenuItem orbitsItem = new StayOpenCheckBoxMenuItem("Show planet orbits", true);
        orbitsItem.addActionListener(_ -> {
            panel.showPlanetOrbits = orbitsItem.isSelected();
            panel.repaint();
        });
        StayOpenCheckBoxMenuItem ringsItem = new StayOpenCheckBoxMenuItem("Show Saturn rings", true);
        ringsItem.addActionListener(_ -> {
            panel.showRings = ringsItem.isSelected();
            panel.repaint();
        });
        StayOpenCheckBoxMenuItem labelsItem = new StayOpenCheckBoxMenuItem("Show labels", true);
        labelsItem.addActionListener(_ -> {
            panel.showLabels = labelsItem.isSelected();
            panel.repaint();
        });
        StayOpenCheckBoxMenuItem shadingItem = new StayOpenCheckBoxMenuItem("Enable shading", true);
        shadingItem.addActionListener(_ -> {
            panel.shadingEnabled = shadingItem.isSelected();
            panel.repaint();
        });
        StayOpenCheckBoxMenuItem atmosphereItem = new StayOpenCheckBoxMenuItem("Atmospheric glow  (A)", true);
        atmosphereItem.addActionListener(_ -> {
            panel.showAtmosphere = atmosphereItem.isSelected();
            panel.repaint();
        });
        StayOpenCheckBoxMenuItem eclipsesItem = new StayOpenCheckBoxMenuItem("Eclipse shadows  (E)", true);
        eclipsesItem.addActionListener(_ -> {
            panel.showEclipses = eclipsesItem.isSelected();
            panel.repaint();
        });
        StayOpenCheckBoxMenuItem cloudsItem = new StayOpenCheckBoxMenuItem("Earth clouds  (C)", true);
        cloudsItem.addActionListener(_ -> {
            panel.showClouds = cloudsItem.isSelected();
            panel.repaint();
        });
        StayOpenCheckBoxMenuItem nightLightsItem = new StayOpenCheckBoxMenuItem("Earth night lights  (I)", true);
        nightLightsItem.addActionListener(_ -> {
            panel.showNightLights = nightLightsItem.isSelected();
            panel.repaint();
        });
        StayOpenCheckBoxMenuItem gpuItem = new StayOpenCheckBoxMenuItem("GPU sphere shader  (G)", panel.gpuSpheres);
        gpuItem.addActionListener(_ -> {
            boolean want = gpuItem.isSelected();
            if (want) {
                GLSphereRenderer r = panel.gpuRenderer();
                want = r != null && r.isAvailable();
                if (!want) gpuItem.setSelected(false);   // no GPU available: keep CPU path
            }
            panel.gpuSpheres = want;
            panel.repaint();
        });
        StayOpenCheckBoxMenuItem selectedOnlyItem = new StayOpenCheckBoxMenuItem("Show only selected planet", false);
        selectedOnlyItem.addActionListener(_ -> {
            panel.showOnlySelected = selectedOnlyItem.isSelected();
            panel.repaint();
        });
        viewMenu.add(orbitsItem);
        viewMenu.add(ringsItem);
        viewMenu.add(labelsItem);
        viewMenu.add(shadingItem);
        viewMenu.add(atmosphereItem);
        viewMenu.add(eclipsesItem);
        viewMenu.add(cloudsItem);
        viewMenu.add(nightLightsItem);
        viewMenu.add(gpuItem);
        viewMenu.addSeparator();
        viewMenu.add(selectedOnlyItem);

        // Add planet visibility submenu
        viewMenu.addSeparator();
        JMenu planetsMenu = new JMenu("Planets");
        for (int i = 0; i < NAMES.length; i++) {
            final int planetIndex = i;
            // Dwarf planets (except Pluto) start unchecked
            boolean initialState = panel.planetVisible[i];
            StayOpenCheckBoxMenuItem checkbox = new StayOpenCheckBoxMenuItem(NAMES[i], initialState);
            checkbox.addActionListener(_ -> {
                panel.planetVisible[planetIndex] = checkbox.isSelected();
                panel.updateTreeVisibility();
                panel.repaint();
            });
            planetsMenu.add(checkbox);
        }
        viewMenu.add(planetsMenu);

        // Add asteroid/debris belts submenu
        viewMenu.addSeparator();
        JMenu beltsMenu = new JMenu("Belts & Lines");
        StayOpenCheckBoxMenuItem asteroidBeltItem = new StayOpenCheckBoxMenuItem("Asteroid Belt", false);
        asteroidBeltItem.addActionListener(_ -> {
            panel.showAsteroidBelt = asteroidBeltItem.isSelected();
            panel.repaint();
        });
        StayOpenCheckBoxMenuItem kuiperBeltItem = new StayOpenCheckBoxMenuItem("Kuiper Belt", false);
        kuiperBeltItem.addActionListener(_ -> {
            panel.showKuiperBelt = kuiperBeltItem.isSelected();
            panel.repaint();
        });
        StayOpenCheckBoxMenuItem iceLinesItem = new StayOpenCheckBoxMenuItem("Frost Lines", false);
        iceLinesItem.addActionListener(_ -> {
            panel.showIceLines = iceLinesItem.isSelected();
            panel.repaint();
        });
        beltsMenu.add(asteroidBeltItem);
        beltsMenu.add(kuiperBeltItem);
        beltsMenu.add(iceLinesItem);
        viewMenu.add(beltsMenu);

        // UI Size submenu
        viewMenu.addSeparator();
        JMenu uiSizeMenu = new JMenu("UI Size");
        ButtonGroup uiSizeGroup = new ButtonGroup();

        StayOpenRadioButtonMenuItem smallUIItem = new StayOpenRadioButtonMenuItem("Small", false);
        smallUIItem.addActionListener(_ -> {
            panel.uiSize = UISize.SMALL;
            panel.repaint();
        });
        uiSizeGroup.add(smallUIItem);
        uiSizeMenu.add(smallUIItem);

        StayOpenRadioButtonMenuItem normalUIItem = new StayOpenRadioButtonMenuItem("Normal", true);
        normalUIItem.addActionListener(_ -> {
            panel.uiSize = UISize.NORMAL;
            panel.repaint();
        });
        uiSizeGroup.add(normalUIItem);
        uiSizeMenu.add(normalUIItem);

        StayOpenRadioButtonMenuItem largeUIItem = new StayOpenRadioButtonMenuItem("Large", false);
        largeUIItem.addActionListener(_ -> {
            panel.uiSize = UISize.LARGE;
            panel.repaint();
        });
        uiSizeGroup.add(largeUIItem);
        uiSizeMenu.add(largeUIItem);

        viewMenu.add(uiSizeMenu);

        JMenu helpMenu = new JMenu("Help");
        JMenuItem controlsItem = new JMenuItem("Controls...");
        controlsItem.addActionListener(_ -> JOptionPane.showMessageDialog(
                frame,
                """
                        Controls:
                        - Mouse wheel: Zoom in/out
                        - Ctrl + wheel: Change time speed
                        - Click planet: Follow/unfollow
                        - Click moon (while following planet): Follow/unfollow moon
                        - Z: Toggle auto-zoom
                        - X: Cycle zoom mode (Planet/Orbit/Manual)
                        - O: Toggle planet orbits
                        - R: Toggle Saturn rings
                        - L: Toggle labels
                        - B: Show only selected planet
                        - S: Toggle shading
                        - A: Toggle atmospheric glow
                        - E: Toggle eclipse shadows
                        - C: Toggle Earth clouds
                        - I: Toggle Earth night lights
                        - Space / P: Pause / resume
                        - M: Cycle selected planet (shows its stats)
                        - U: Toggle Sun info panel
                        - H: Hide/show all UI (menu, search, sidebar)
                        - N: Next step in guided tour (when active)

                        Shortcuts work from anywhere in the window,
                        except while typing in the search box.

                        Use View menu for:
                        - Asteroid Belt
                        - Kuiper Belt
                        - Frost Lines (H2O and CO2)""",
                "Controls",
                JOptionPane.INFORMATION_MESSAGE));
        JMenuItem aboutItem = new JMenuItem("About");
        aboutItem.addActionListener(_ -> JOptionPane.showMessageDialog(
                frame,
                "Solar System Simulation\n" +
                        "Interactive 2-D Keplerian model with moons and auto-zoom.",
                "About",
                JOptionPane.INFORMATION_MESSAGE));
        helpMenu.add(controlsItem);
        helpMenu.addSeparator();
        helpMenu.add(aboutItem);

        menuBar.add(simMenu);
        menuBar.add(viewMenu);

        // Education menu for lessons
        JMenu educationMenu = new JMenu("Education");
        StayOpenCheckBoxMenuItem lessonModeItem = new StayOpenCheckBoxMenuItem("Enable Lesson Mode", false);
        lessonModeItem.addActionListener(_ -> {
            panel.lessonMode = lessonModeItem.isSelected();
            // Stop tour if lesson mode is disabled
            if (!panel.lessonMode) {
                panel.guidedTourActive = false;
                panel.tourStep = 0;
                panel.tourMessage = "";
                panel.restorePlanetVisibility(); // Restore visibility
            }
            panel.repaint();
        });
        educationMenu.add(lessonModeItem);

        // Guided Tour toggle button
        JMenuItem guidedTourItem = new JMenuItem("Start Guided Tour");
        guidedTourItem.addActionListener(_ -> {
            if (!panel.lessonMode) {
                JOptionPane.showMessageDialog(frame,
                    "Please enable Lesson Mode first!",
                    "Lesson Mode Required",
                    JOptionPane.INFORMATION_MESSAGE);
                return;
            }

            // Always check current state and toggle opposite
            if (panel.guidedTourActive) {
                // Currently active - STOP the tour
                panel.guidedTourActive = false;
                panel.tourStep = 0;
                panel.tourMessage = "";
                panel.tourMessageAlpha = 0.0;
                panel.tourStepReady = false;
                panel.restorePlanetVisibility(); // Restore visibility when stopping
                guidedTourItem.setText("Start Guided Tour");
            } else {
                // Currently inactive - START the tour
                panel.guidedTourActive = true;
                panel.tourStep = 0;
                panel.tourStepStartTime = 0;
                panel.tourMessageAlpha = 0.0;
                panel.tourMessage = "";
                panel.tourStepReady = false;
                panel.savedPlanetVisibility = null; // Reset saved state
                guidedTourItem.setText("Stop Guided Tour");

                // Show info about controls
                JOptionPane.showMessageDialog(frame,
                        """
                                Guided Tour Started!
                                
                                Press 'N' to advance to the next step.
                                The tour will automatically adjust zoom and visibility.""",
                    "Guided Tour Controls",
                    JOptionPane.INFORMATION_MESSAGE);
            }
            panel.repaint();
        });
        educationMenu.add(guidedTourItem);
        educationMenu.addSeparator();

        // Lesson selector submenu
        JMenu lessonMenu = new JMenu("Select Lesson");
        ButtonGroup lessonGroup = new ButtonGroup();
        String[] lessonNames = {
            "Lesson 1: Orbital Motion",
            "Lesson 2: Velocity & Kepler's 2nd Law",
            "Lesson 3: Scale of Solar System",
            "Lesson 4: Moon Systems",
            "Lesson 5: Time Control"
        };
        for (int i = 0; i < lessonNames.length; i++) {
            final int lessonNum = i + 1;
            JRadioButtonMenuItem lessonItem = new JRadioButtonMenuItem(lessonNames[i], i == 0);
            lessonGroup.add(lessonItem);
            lessonItem.addActionListener(_ -> {
                panel.currentLesson = lessonNum;
                panel.repaint();
            });
            lessonMenu.add(lessonItem);
        }
        educationMenu.add(lessonMenu);
        educationMenu.addSeparator();

        // Lesson 1 specific features
        JMenu lesson1Menu = new JMenu("Lesson 1 Features");
        StayOpenCheckBoxMenuItem keplersLawItem = new StayOpenCheckBoxMenuItem("Show Kepler's 3rd Law", false);
        keplersLawItem.addActionListener(_ -> {
            panel.showKeplersLaw = keplersLawItem.isSelected();
            panel.repaint();
        });
        StayOpenCheckBoxMenuItem periAphelionItem = new StayOpenCheckBoxMenuItem("Show Perihelion/Aphelion", false);
        periAphelionItem.addActionListener(_ -> {
            panel.showPerihelionAphelion = periAphelionItem.isSelected();
            panel.repaint();
        });
        lesson1Menu.add(keplersLawItem);
        lesson1Menu.add(periAphelionItem);
        educationMenu.add(lesson1Menu);

        // Lesson 2 specific features (Velocity & Kepler's 2nd Law)
        JMenu lesson2Menu = new JMenu("Lesson 2 Features");
        StayOpenCheckBoxMenuItem velocityVectorsItem = new StayOpenCheckBoxMenuItem("Show Velocity Vectors", false);
        velocityVectorsItem.addActionListener(_ -> {
            panel.showVelocityVectors = velocityVectorsItem.isSelected();
            panel.repaint();
        });
        StayOpenCheckBoxMenuItem velocityComparisonItem = new StayOpenCheckBoxMenuItem("Show Velocity Labels", false);
        velocityComparisonItem.addActionListener(_ -> {
            panel.showVelocityComparison = velocityComparisonItem.isSelected();
            panel.repaint();
        });
        lesson2Menu.add(velocityVectorsItem);
        lesson2Menu.add(velocityComparisonItem);
        educationMenu.add(lesson2Menu);

        JMenuItem lessonGuideItem = new JMenuItem("Lesson Guide...");
        lessonGuideItem.addActionListener(_ -> panel.showLessonGuide(frame));
        educationMenu.addSeparator();
        educationMenu.add(lessonGuideItem);

        menuBar.add(educationMenu);
        menuBar.add(helpMenu);
        frame.setJMenuBar(menuBar);

        // ----- Top search bar -----
        JPanel topBar = new JPanel(new BorderLayout(8, 0));
        topBar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        panel.searchBarPanel = topBar; // Store reference for toggling visibility
        JLabel searchLabel = new JLabel("Search body:");
        panel.searchField = new JTextField();
        panel.searchField.setToolTipText("Type a planet or major moon name (e.g., Earth, Titan)");

        // Ensure panel can still receive key events when search is not focused
        panel.setFocusTraversalKeysEnabled(false);

        // Build suggestion popup for search
        panel.searchSuggestionsPopup = new JPopupMenu();
        panel.searchSuggestionsPopup.setFocusable(false);
        panel.searchSuggestionsList = new JList<>();
        panel.searchSuggestionsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        panel.searchSuggestionsList.setFocusable(false);
        JScrollPane suggScroll = new JScrollPane(panel.searchSuggestionsList);
        suggScroll.setFocusable(false);
        suggScroll.setPreferredSize(new Dimension(220, 140));
        panel.searchSuggestionsPopup.add(suggScroll);

        // Precompute all searchable names (planets + moons)
        java.util.List<String> allNames = new java.util.ArrayList<>();
        Collections.addAll(allNames, NAMES);
        for (int i = 0; i < NAMES.length; i++) {
            Collections.addAll(allNames, MOON_NAMES[i]);
        }

        // Update suggestions as the user types
        panel.searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void updateSuggestions() {
                String text = panel.searchField.getText();
                if (text == null) text = "";
                String q = text.trim().toLowerCase();
                if (q.isEmpty()) {
                    panel.searchSuggestionsPopup.setVisible(false);
                    return;
                }
                java.util.List<String> matches = new java.util.ArrayList<>();
                for (String name : allNames) {
                    if (name.toLowerCase().startsWith(q)) {
                        matches.add(name);
                    }
                }
                if (matches.isEmpty()) {
                    panel.searchSuggestionsPopup.setVisible(false);
                    return;
                }
                panel.searchSuggestionsList.setListData(matches.toArray(new String[0]));
                try {
                    Rectangle r = panel.searchField.getBounds();
                    panel.searchSuggestionsPopup.show(panel.searchField, 0, r.height);
                } catch (Exception ignored) {}
            }
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { updateSuggestions(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { updateSuggestions(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { updateSuggestions(); }
        });

        // Clicking a suggestion uses that body
        panel.searchSuggestionsList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    String sel = panel.searchSuggestionsList.getSelectedValue();
                    if (sel != null) {
                        panel.searchField.setText(sel);
                        panel.searchSuggestionsPopup.setVisible(false);
                        panel.searchAndFocus();
                    }
                }
            }
        });


        topBar.add(searchLabel, BorderLayout.WEST);
        topBar.add(panel.searchField, BorderLayout.CENTER);

        JButton searchButton = new JButton("Go");
        searchButton.addActionListener(_ -> panel.searchAndFocus());
        panel.searchField.addActionListener(_ -> panel.searchAndFocus());

        // Add key listener to search field to handle Escape and forward global keys
        panel.searchField.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                int code = e.getKeyCode();

                // Handle search-specific keys
                if (!panel.searchSuggestionsPopup.isVisible()) {
                    // Popup not visible - handle Escape to clear and return focus
                    if (code == KeyEvent.VK_ESCAPE) {
                        panel.searchField.setText("");
                        panel.searchSuggestionsPopup.setVisible(false);
                        panel.requestFocusInWindow();
                        e.consume();
                        return;
                    }
                } else {
                    // Popup is visible - handle navigation
                    if (code == KeyEvent.VK_DOWN) {
                        int idx = panel.searchSuggestionsList.getSelectedIndex();
                        if (idx < panel.searchSuggestionsList.getModel().getSize() - 1) {
                            panel.searchSuggestionsList.setSelectedIndex(idx + 1);
                            panel.searchSuggestionsList.ensureIndexIsVisible(idx + 1);
                        }
                        e.consume();
                        return;
                    } else if (code == KeyEvent.VK_UP) {
                        int idx = panel.searchSuggestionsList.getSelectedIndex();
                        if (idx > 0) {
                            panel.searchSuggestionsList.setSelectedIndex(idx - 1);
                            panel.searchSuggestionsList.ensureIndexIsVisible(idx - 1);
                        }
                        e.consume();
                        return;
                    } else if (code == KeyEvent.VK_ENTER) {
                        String sel = panel.searchSuggestionsList.getSelectedValue();
                        if (sel != null) {
                            panel.searchField.setText(sel);
                        }
                        panel.searchSuggestionsPopup.setVisible(false);
                        panel.searchAndFocus();
                        e.consume();
                        return;
                    } else if (code == KeyEvent.VK_ESCAPE) {
                        panel.searchSuggestionsPopup.setVisible(false);
                        e.consume();
                        return;
                    }
                }
                // Global toggle keys are handled by the app-wide dispatcher; while
                // the search field has focus we let all other keys type normally.
            }
        });

        topBar.add(searchButton, BorderLayout.EAST);

        // Add mouse listener to top bar to close search when clicking outside search field
        topBar.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                // If click is not on search field, close the popup
                if (e.getSource() != panel.searchField && !panel.isSearchUIComponent(e.getComponent())) {
                    if (panel.searchSuggestionsPopup != null) {
                        panel.searchSuggestionsPopup.setVisible(false);
                    }
                }
            }
        });

        // Main content with top bar + simulation panel
        JPanel content = new JPanel(new BorderLayout());
        content.add(topBar, BorderLayout.NORTH);

        // Left navigation: hierarchical planet→moon tree
        JPanel leftNav = new JPanel(new BorderLayout());
        leftNav.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 4));
        panel.sidebarPanel = leftNav; // Store reference for toggling visibility
        JLabel navLabel = new JLabel("Objects");
        navLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        JPanel treePanel = new JPanel(new BorderLayout());
        treePanel.add(navLabel, BorderLayout.NORTH);

        // Build tree nodes
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Solar System");
        panel.treeRootNode = root;
        panel.planetTreePaths = new TreePath[NAMES.length];
        panel.planetTreeNodes = new DefaultMutableTreeNode[NAMES.length];
        panel.moonTreePaths = new TreePath[NAMES.length][];
        for (int i = 0; i < NAMES.length; i++) {
            DefaultMutableTreeNode planetNode = new DefaultMutableTreeNode(new TreeBody("planet", i, -1, NAMES[i]));
            panel.planetTreeNodes[i] = planetNode;
            // Only add to tree if planet is visible
            if (panel.planetVisible[i]) {
                root.add(planetNode);
            }
            panel.planetTreePaths[i] = new TreePath(new Object[]{root, planetNode});
            panel.moonTreePaths[i] = new TreePath[MOON_NAMES[i].length];
            for (int m = 0; m < MOON_NAMES[i].length; m++) {
                DefaultMutableTreeNode moonNode = new DefaultMutableTreeNode(new TreeBody("moon", i, m, MOON_NAMES[i][m]));
                planetNode.add(moonNode);
                panel.moonTreePaths[i][m] = new TreePath(new Object[]{root, planetNode, moonNode});
            }
        }
        panel.bodyTreeModel = new DefaultTreeModel(root);
        panel.bodyTree = new JTree(panel.bodyTreeModel);
        panel.bodyTree.setRootVisible(true);
        panel.bodyTree.setShowsRootHandles(true);
        panel.bodyTree.setCellRenderer(new BodyTreeRenderer());
        panel.bodyTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        for (int i = 0; i < panel.bodyTree.getRowCount(); i++) {
            panel.bodyTree.expandRow(i);
        }
        panel.bodyTree.addTreeSelectionListener(e -> panel.handleTreeSelection(e.getPath()));
        JScrollPane treeScroll = new JScrollPane(panel.bodyTree);
        treeScroll.setPreferredSize(new Dimension(220, 0));
        treePanel.add(treeScroll, BorderLayout.CENTER);

        leftNav.add(treePanel, BorderLayout.CENTER);

        content.add(leftNav, BorderLayout.WEST);
        content.add(panel, BorderLayout.CENTER);
        //
        // Bottom: time controls (play/pause, fine slider, presets, label)
        JPanel timeControlsPanel = new JPanel();
        timeControlsPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        timeControlsPanel.setLayout(new GridBagLayout());
        GridBagConstraints timeGbc = new GridBagConstraints();
        timeGbc.fill = GridBagConstraints.HORIZONTAL;

        JButton playPause = new JButton(panel.simulationPaused ? "Play" : "Pause");
        playPause.addActionListener(_ -> {
            panel.simulationPaused = !panel.simulationPaused;
            playPause.setText(panel.simulationPaused ? "Play" : "Pause");
            panel.updateTimeScaleLabel();
        });

        panel.timeScaleSlider = new JSlider(TIME_SLIDER_MIN, TIME_SLIDER_MAX,
                panel.timeScaleToSliderValue(panel.timeScale));
        panel.timeScaleSlider.setMajorTickSpacing((TIME_SLIDER_MAX - TIME_SLIDER_MIN) / 5);
        panel.timeScaleSlider.setMinorTickSpacing((TIME_SLIDER_MAX - TIME_SLIDER_MIN) / 20);
        panel.timeScaleSlider.setPaintTicks(false);
        panel.timeScaleSlider.setPreferredSize(new Dimension(220, 32));
        panel.timeScaleSlider.addChangeListener(_ -> {
            if (panel.suppressTimeSliderEvents) return;
            panel.timeScale = panel.sliderValueToTimeScale(panel.timeScaleSlider.getValue());
            panel.updateTimeScaleLabel();
        });

        timeGbc.gridx = 0; timeGbc.gridy = 0;
        timeGbc.weightx = 0.33;
        timeGbc.insets = new Insets(0, 0, 0, 6);
        timeControlsPanel.add(playPause, timeGbc);
        timeGbc = new GridBagConstraints();
        timeGbc.gridx = 1; timeGbc.gridy = 0;
        timeGbc.weightx = 0.67;
        timeGbc.fill = GridBagConstraints.HORIZONTAL;
        timeControlsPanel.add(panel.timeScaleSlider, timeGbc);

        JPanel presetGrid = new JPanel(new GridLayout(2, 3, 4, 4));
        presetGrid.setOpaque(false);
        JButton oneSecondBtn = new JButton("1 s/s");
        oneSecondBtn.addActionListener(_ -> panel.setTimeScaleAndSync(TIME_SCALE_MIN));
        JButton oneMinuteBtn = new JButton("1 min/s");
        oneMinuteBtn.addActionListener(_ -> panel.setTimeScaleAndSync(60.0));
        JButton oneHourBtn = new JButton("1 hr/s");
        oneHourBtn.addActionListener(_ -> panel.setTimeScaleAndSync(3600.0));
        JButton dayBtn = new JButton("1 day/s");
        dayBtn.addActionListener(_ -> panel.setTimeScaleAndSync(DAY));
        JButton weekBtn = new JButton("1 week/s");
        weekBtn.addActionListener(_ -> panel.setTimeScaleAndSync(7 * DAY));
        JButton monthBtn = new JButton("1 month/s");
        monthBtn.addActionListener(_ -> panel.setTimeScaleAndSync(30 * DAY));
        JButton threeMonthBtn = new JButton("3 months/s");
        threeMonthBtn.addActionListener(_ -> panel.setTimeScaleAndSync(90 * DAY));
        JButton yearBtn = new JButton("1 year/s");
        yearBtn.addActionListener(_ -> panel.setTimeScaleAndSync(YEAR));
        JButton decadeBtn = new JButton("10 years/s");
        decadeBtn.addActionListener(_ -> panel.setTimeScaleAndSync(10 * YEAR));
        presetGrid.add(oneSecondBtn);
        presetGrid.add(oneMinuteBtn);
        presetGrid.add(oneHourBtn);
        presetGrid.add(dayBtn);
        presetGrid.add(weekBtn);
        presetGrid.add(monthBtn);
        presetGrid.add(threeMonthBtn);
        presetGrid.add(yearBtn);
        presetGrid.add(decadeBtn);
        timeGbc = new GridBagConstraints();
        timeGbc.gridx = 0; timeGbc.gridy = 1;
        timeGbc.gridwidth = 2;
        timeGbc.insets = new Insets(4, 0, 0, 0);
        timeControlsPanel.add(presetGrid, timeGbc);

        panel.timeScaleLabel = new JLabel();
        panel.updateTimeScaleLabel();
        timeGbc = new GridBagConstraints();
        timeGbc.gridx = 0; timeGbc.gridy = 2;
        timeGbc.gridwidth = 2;
        timeGbc.insets = new Insets(4, 0, 0, 0);
        timeGbc.anchor = GridBagConstraints.WEST;
        timeControlsPanel.add(panel.timeScaleLabel, timeGbc);
        timeGbc = new GridBagConstraints();
        timeGbc.gridx = 0; timeGbc.gridy = 3;
        timeGbc.gridwidth = 2;
        timeGbc.weighty = 1.0;
        timeControlsPanel.add(Box.createVerticalGlue(), timeGbc);

        JSplitPane leftSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, treePanel, timeControlsPanel);
        leftSplit.setResizeWeight(0.9);
        leftSplit.setDividerLocation(0.9);
         leftSplit.setContinuousLayout(true);
         leftSplit.setDividerSize(6);
         leftSplit.setBorder(null);
         leftNav.add(leftSplit, BorderLayout.CENTER);

        content.add(leftNav, BorderLayout.WEST);
        content.add(panel, BorderLayout.CENTER);

        frame.setContentPane(content);

        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        frame.setTitle("Solar System Simulation");
        // Give the canvas keyboard focus so shortcuts work immediately.
        SwingUtilities.invokeLater(panel::requestFocusInWindow);
     }

    // Simple search function: focus camera on a planet or moon by name
    private void searchAndFocus() {
        if (searchField == null) return;
        String query = searchField.getText();
        if (query == null) return;
        query = query.trim();
        if (query.isEmpty()) return;

        String qLower = query.toLowerCase();

        // 1) Try planets
        for (int i = 0; i < NAMES.length; i++) {
            if (NAMES[i].toLowerCase().equals(qLower)) {
                // Check if planet is visible
                if (!planetVisible[i]) {
                    JOptionPane.showMessageDialog(this,
                            NAMES[i] + " is currently hidden.\n\n" +
                            "Enable it in View > Planets > " + NAMES[i] + " to view it.",
                            "Object Hidden",
                            JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                followingPlanet = true;
                followingMoon = false;
                cameraFocusIndex = i;
                selectedPlanet = i;
                selectedMoon = -1;
                cameraFocusMoon = -1;
                autoZoomEnabled = true;
                focusPlanetImmediate(i);
                return;
            }
        }

        // 2) Try moons (check parent planet visibility)
        for (int i = 0; i < NAMES.length; i++) {
            for (int m = 0; m < MOON_NAMES[i].length; m++) {
                if (MOON_NAMES[i][m].toLowerCase().equals(qLower)) {
                    // Check if parent planet is visible
                    if (!planetVisible[i]) {
                        JOptionPane.showMessageDialog(this,
                                MOON_NAMES[i][m] + " (moon of " + NAMES[i] + ") is currently hidden.\n\n" +
                                "Enable " + NAMES[i] + " in View > Planets > " + NAMES[i] + " to view it.",
                                "Object Hidden",
                                JOptionPane.INFORMATION_MESSAGE);
                        return;
                    }
                    followingPlanet = true;
                    followingMoon = true;
                    cameraFocusIndex = i;
                    selectedPlanet = i;
                    cameraFocusMoon = m;
                    selectedMoon = m;
                    autoZoomEnabled = true;
                    focusMoonImmediate(i, m);
                    return;
                }
            }
        }

        // Nothing found – optional feedback: briefly flash background or use dialog
        JOptionPane.showMessageDialog(this,
                "'" + query + "' not found.\n\n" +
                "Try searching for a planet or moon name\n" +
                "(e.g., Earth, Mars, Titan, Europa)",
                "Object Not Found",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private void exitSearchModeIfActive() {
        if (searchField == null) return;
        if (searchSuggestionsPopup != null) {
            searchSuggestionsPopup.setVisible(false);
        }
    }

    private boolean isSearchUIComponent(Component c) {
        if (c == null) return false;
        if (c == searchField) return true;
        if (searchSuggestionsPopup != null) {
            if (c == searchSuggestionsPopup) return true;
            return SwingUtilities.isDescendingFrom(c, searchSuggestionsPopup);
        }
        return false;
    }

    private void focusPlanetImmediate(int planetIndex) {
        if (planetIndex < 0 || planetIndex >= NAMES.length) return;
        // Use barycentric coordinates
        camX = planetX[planetIndex] + sunBarycenterX;
        camY = planetY[planetIndex] + sunBarycenterY;
        moonCamX = camX;
        moonCamY = camY;
        cameraIsApproaching = false;
        moonCameraIsApproaching = false;
        if (autoZoomEnabled) {
            updateAutoZoomTarget();
            currentZoom = targetZoom;
        }
        if (bodyTree != null) syncTreeSelection();
    }

    private void focusMoonImmediate(int planetIndex, int moonIndex) {
        if (planetIndex < 0 || planetIndex >= NAMES.length) return;
        if (moonIndex < 0 || moonIndex >= MOON_A_M[planetIndex].length) return;
        double a = MOON_A_M[planetIndex][moonIndex];
        double e = MOON_ECC[planetIndex][moonIndex];
        double b = a * Math.sqrt(1 - e * e);
        double M = moonMean[planetIndex][moonIndex];
        double E = M;
        for (int it = 0; it < 40; it++) {
            double f = E - e * Math.sin(E) - M;
            double fp = 1 - e * Math.cos(E);
            E -= f / fp;
            if (Math.abs(f) < 1e-12) break;
        }
        double mx = a * (Math.cos(E) - e);
        double my = b * Math.sin(E);
        double arg = MOON_ARG_PERI[planetIndex][moonIndex];
        double rx = mx * Math.cos(arg) - my * Math.sin(arg);
        double ry = mx * Math.sin(arg) + my * Math.cos(arg);
        // Use barycentric coordinates
        double targetX = planetX[planetIndex] + sunBarycenterX + rx;
        double targetY = planetY[planetIndex] + sunBarycenterY + ry;
        moonCamX = targetX;
        moonCamY = targetY;
        camX = moonCamX;
        camY = moonCamY;
        moonCameraIsApproaching = false;
        cameraIsApproaching = false;
        autoZoomEnabled = true;
        double moonRadius = MOON_RADIUS_M[planetIndex][moonIndex];
        double maxRadius = moonRadius * 1.5;
        int screen = Math.min(getWidth(), getHeight());
        double desired = screen * 0.6;
        targetZoom = desired / (maxRadius * METERS_TO_PIXELS * 2);
        targetZoom = Math.max(50.0, Math.min(targetZoom, 50000.0));
        currentZoom = targetZoom;
        if (bodyTree != null) syncTreeSelection();
    }

    private void handleTreeSelection(TreePath path) {
        if (suppressTreeSelection || path == null) return;
        Object last = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        if (!(last instanceof TreeBody body)) return;
        if ("planet".equals(body.type)) {
            followingPlanet = true;
            followingMoon = false;
            cameraFocusIndex = body.planetIndex;
            selectedPlanet = body.planetIndex;
            selectedMoon = -1;
            cameraFocusMoon = -1;
            autoZoomEnabled = true;
            focusPlanetImmediate(body.planetIndex);
        } else if ("moon".equals(body.type)) {
            followingPlanet = true;
            followingMoon = true;
            cameraFocusIndex = body.planetIndex;
            selectedPlanet = body.planetIndex;
            cameraFocusMoon = body.moonIndex;
            selectedMoon = body.moonIndex;
            autoZoomEnabled = true;
            focusMoonImmediate(body.planetIndex, body.moonIndex);
        }
        repaint();
    }

    private void syncTreeSelection() {
        if (bodyTree == null) return;
        suppressTreeSelection = true;
        try {
            if (followingMoon && cameraFocusIndex >= 0 && cameraFocusMoon >= 0) {
                bodyTree.setSelectionPath(moonTreePaths[cameraFocusIndex][cameraFocusMoon]);
                bodyTree.scrollPathToVisible(moonTreePaths[cameraFocusIndex][cameraFocusMoon]);
            } else if (followingPlanet && cameraFocusIndex >= 0) {
                bodyTree.setSelectionPath(planetTreePaths[cameraFocusIndex]);
                bodyTree.scrollPathToVisible(planetTreePaths[cameraFocusIndex]);
            } else {
                bodyTree.clearSelection();
            }
        } finally {
            suppressTreeSelection = false;
        }
    }

    /**
     * Updates the tree to show/hide planets based on planetVisible array
     */
    private void updateTreeVisibility() {
        if (bodyTreeModel == null || treeRootNode == null || planetTreeNodes == null) return;

        for (int i = 0; i < NAMES.length; i++) {
            DefaultMutableTreeNode planetNode = planetTreeNodes[i];
            boolean isCurrentlyInTree = treeRootNode.isNodeChild(planetNode);
            boolean shouldBeVisible = planetVisible[i];

            if (shouldBeVisible && !isCurrentlyInTree) {
                // Add planet to tree
                // Find correct position (maintain original order)
                int insertIndex = 0;
                for (int j = 0; j < i; j++) {
                    if (planetVisible[j]) {
                        insertIndex++;
                    }
                }
                bodyTreeModel.insertNodeInto(planetNode, treeRootNode, insertIndex);
            } else if (!shouldBeVisible && isCurrentlyInTree) {
                // Remove planet from tree
                bodyTreeModel.removeNodeFromParent(planetNode);
            }
        }

        // Expand all rows to show moons
        for (int i = 0; i < bodyTree.getRowCount(); i++) {
            bodyTree.expandRow(i);
        }
    }

    /**
     * Draw velocity vectors for planets to show Kepler's 2nd Law
     */
    private void drawVelocityVectors(Graphics2D g2, int cx, int cy) {
        for (int i = 0; i < NAMES.length; i++) {
            if (isPlanetVisible(i)) continue;

            // Planet position
            double worldX = planetX[i] + sunBarycenterX;
            double worldY = planetY[i] + sunBarycenterY;
            double px = projX(worldX);
            double py = projY(worldY, planetZ[i]);

            // Calculate velocity direction (perpendicular to radius vector)
            double dx = worldX - sunBarycenterX;
            double dy = worldY - sunBarycenterY;
            double dist = Math.sqrt(dx * dx + dy * dy);

            // Velocity is perpendicular to position vector (for circular approximation)
            // For elliptical orbits, we use the actual speed from vis-viva equation
            double vx = -dy / dist;  // perpendicular
            double vy = dx / dist;

            // Scale velocity vector by actual speed
            double speed = planetSpeedKmS[i];
            double vectorScale = 0.00015 * currentZoom;  // Adjust this for visibility
            double vLength = speed * vectorScale;

            // Draw velocity vector as an arrow
            double endX = px + vx * vLength;
            double endY = py + vy * vLength;

            // Dynamic arrow sizing based on UI size
            float arrowWidth = switch (uiSize) {
                case SMALL -> 2.0f;
                case NORMAL -> 2.5f;
                default -> 3.0f;
            };

            // Color based on speed (blue for slow, red for fast)
            double mu = G * (M_SUN + PLANET_MASS[i]);
            double circularSpeed = Math.sqrt(mu / dist) / 1000.0;  // km/s
            double speedRatio = speed / circularSpeed;

            // Color gradient: blue (slow) to green (circular) to red (fast)
            Color vectorColor;
            if (speedRatio < 1.0) {
                // Slower than circular: blue-ish
                int blue = 255;
                int green = (int)(speedRatio * 150);
                vectorColor = new Color(50, green, blue, 220);
            } else {
                // Faster than circular: yellow to red
                int red = Math.min(255, (int)((speedRatio - 1.0) * 400 + 200));
                int green = Math.max(100, 255 - (int)((speedRatio - 1.0) * 300));
                vectorColor = new Color(red, green, 50, 220);
            }

            g2.setColor(vectorColor);
            g2.setStroke(new BasicStroke(arrowWidth));
            g2.drawLine((int)px, (int)py, (int)endX, (int)endY);

            // Draw arrowhead
            double arrowHeadSize = 8.0 * (arrowWidth / 2.5);
            double angle = Math.atan2(endY - py, endX - px);
            int x1 = (int)(endX - arrowHeadSize * Math.cos(angle - Math.PI / 6));
            int y1 = (int)(endY - arrowHeadSize * Math.sin(angle - Math.PI / 6));
            int x2 = (int)(endX - arrowHeadSize * Math.cos(angle + Math.PI / 6));
            int y2 = (int)(endY - arrowHeadSize * Math.sin(angle + Math.PI / 6));

            g2.fillPolygon(new int[]{(int)endX, x1, x2}, new int[]{(int)endY, y1, y2}, 3);

            // Draw speed label if showing velocity comparison
            if (showVelocityComparison) {
                float labelFontSize = switch (uiSize) {
                    case SMALL -> 9f;
                    case NORMAL -> 11f;
                    default -> 13f;
                };

                Font oldFont = g2.getFont();
                g2.setFont(oldFont.deriveFont(Font.BOLD, labelFontSize));
                String speedLabel = String.format("%.1f km/s", speed);
                FontMetrics fm = g2.getFontMetrics();
                int labelWidth = fm.stringWidth(speedLabel);
                int labelHeight = fm.getHeight();

                // Position label near arrow tip
                int labelX = (int)endX + 10;
                int labelY = (int)endY - 5;

                // Background box
                g2.setColor(new Color(0, 0, 0, 180));
                g2.fillRoundRect(labelX - 3, labelY - labelHeight + 3, labelWidth + 6, labelHeight, 4, 4);

                // Text
                g2.setColor(vectorColor);
                g2.drawString(speedLabel, labelX, labelY);
                g2.setFont(oldFont);
            }
        }
    }

    /**
     * Display a lesson guide dialog for the current lesson.
     */
    private void showLessonGuide(Window owner) {
        String[] lessonContent = {
            // Lesson 1: Introduction to Orbital Motion
            """
            Lesson 1: Introduction to Orbital Motion
            ========================================
            
            Learning Objectives:
            • Understand elliptical vs. circular orbits
            • Identify key orbital elements (a, e, perihelion, aphelion)
            • Relate orbital period to distance from Sun
            
            Activities:
            
            1. Orbit Comparison
               - Press 'O' to show all planet orbits
               - Click Venus (nearly circular orbit)
               - Click Pluto (highly elliptical orbit)
               - Compare eccentricities in the info panel
            
            2. Kepler's Third Law Verification
               - Enable: Education → Lesson 1 Features → Show Kepler's 3rd Law
               - Click Mercury: note P² / a³ ratio
               - Click Earth: note P² / a³ ratio (should be ~1.0)
               - Click Jupiter: verify the ratio is constant
               - The ratio P² / a³ should be the same for all planets!
            
            3. Speed Variations
               - Click Mercury to follow it
               - Press Ctrl+Scroll to speed up time
               - Watch the speed value change in the info panel
               - Observe: faster near the Sun (perihelion), slower far away (aphelion)
               - Enable: Education → Lesson 1 Features → Show Perihelion/Aphelion
            
            Assessment Questions:
            - Why is Mercury's orbit more elliptical than Earth's?
            - How does orbital speed relate to distance from the Sun?
            - Calculate: If a planet orbits at 4 AU, what would its period be?
              (Hint: Use P² = a³ with a = 4, so P² = 64, P = 8 years)
            """,

            // Lesson 2: Scale of the Solar System
            """
            Lesson 2: Scale of the Solar System
            ===================================
            
            Learning Objectives:
            • Appreciate the vast distances in the Solar System
            • Understand the AU (Astronomical Unit) as a measurement
            • Compare planet sizes and distances
            
            Activities:
            
            1. Distance Exploration
               - Zoom out to see the full system
               - Use the dynamic scale ruler (bottom-left) to measure distances
               - Drag the ruler to different positions
               - Compare inner planet spacing vs. outer planets
            
            2. Zoom Challenge
               - Click Earth → auto-zoom to planet view
               - Press 'X' to cycle zoom modes (Planet → Orbit → Manual)
               - Observe how small Earth is compared to its orbit
            
            3. Belt Visualization
               - View → Belts & Lines → Asteroid Belt
               - View → Belts & Lines → Kuiper Belt
               - View → Belts & Lines → Frost Lines
               - Discuss where different materials can exist
            """,

            // Lesson 3: Moon Systems
            """
            Lesson 3: Moon Systems and Hierarchical Orbits
            ==============================================
            
            Learning Objectives:
            • Understand moons as satellites orbiting planets
            • Compare moon systems of different planets
            • Observe orbital relationships
            
            Activities:
            
            1. Earth-Moon System
               - Click Earth, then zoom in
               - Click the Moon to see its orbit details
               - Note the Moon's orbital period (27.3 days)
            
            2. Jupiter's Galilean Moons
               - Click Jupiter and zoom in
               - Observe Io, Europa, Ganymede, Callisto
               - Speed up time to see their orbital motions
            
            3. Saturn's Moons
               - Click Saturn
               - Toggle rings with 'R'
               - Observe Titan and other moons
            """,

            // Lesson 4: Time Control
            """
            Lesson 4: Time and Simulation Control
            =====================================
            
            Learning Objectives:
            • Understand different time scales in astronomy
            • Use simulation controls for observation
            
            Activities:
            
            1. Time Scale Manipulation
               - Use Ctrl+Scroll to speed up time
               - Watch Mercury complete one orbit (~88 days)
               - Press Space or 'P' to pause
            
            2. Study Configurations
               - Pause simulation at interesting moments
               - Record data from info panels
               - Resume and observe changes
            """,

            // Lesson 5: Planetary Characteristics
            """
            Lesson 5: Planetary Characteristics
            ==================================
            
            Learning Objectives:
            • Compare physical properties of planets
            • Understand planet classification
            
            Activities:
            
            1. Size Comparison
               - Click different planets to compare radii
               - Earth: 6,371 km
               - Jupiter: 69,911 km (11× Earth)
               - Mercury: 2,439 km (0.38× Earth)
            
            2. Orbit Color Coding
               - Purple/magenta: terrestrial planets
               - Golden/tan: gas giants
               - Purple-gray: dwarf planets
            
            3. Features
               - Toggle shading with 'S'
               - Toggle Saturn's rings with 'R'
            """
        };

        int lessonIndex = currentLesson - 1;
        if (lessonIndex < 0 || lessonIndex >= lessonContent.length) {
            lessonIndex = 0;
        }

        JTextArea textArea = new JTextArea(lessonContent[lessonIndex]);
        textArea.setEditable(false);
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        textArea.setCaretPosition(0);

        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setPreferredSize(new Dimension(650, 500));

        JOptionPane.showMessageDialog(
            owner,
            scrollPane,
            "Lesson " + currentLesson + " Guide",
            JOptionPane.INFORMATION_MESSAGE
        );
    }

    private static final class TreeBody {
        final String type;
        final int planetIndex;
        final int moonIndex;
        final String name;
        TreeBody(String type, int planetIndex, int moonIndex, String name) {
            this.type = type;
            this.planetIndex = planetIndex;
            this.moonIndex = moonIndex;
            this.name = name;
        }
        @Override public String toString() { return name; }
    }

    private static final class BodyTreeRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel,
                                                      boolean expanded, boolean leaf, int row, boolean hasFocus) {
            Component c = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            if (value instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof TreeBody body) {
                if ("moon".equals(body.type)) {
                    setIcon(UIManager.getIcon("FileView.fileIcon"));
                } else if ("planet".equals(body.type)) {
                    setIcon(UIManager.getIcon("FileView.directoryIcon"));
                }
            } else {
                setIcon(UIManager.getIcon("FileView.hardDriveIcon"));
            }
            return c;
        }
    }
}
