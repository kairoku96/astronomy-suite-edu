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
import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;
import com.formdev.flatlaf.FlatDarkLaf;

/**
 * Solar System Simulation – a real-date, 3-D model of the Solar System with textures,
 * smooth camera, moon-following and auto-zoom.
 *
 * <p>Dynamics:
 * <ul>
 *   <li>Planets, dwarf planets, comets and asteroids move under mutual gravity, integrated by
 *       a symplectic Wisdom–Holman map from JPL Horizons J2000 states ({@link NBodyEphemeris}),
 *       including the Sun's general-relativistic term.</li>
 *   <li>Earth's Moon follows the ELP-2000/82 lunar theory ({@link MoonEphemeris}); every other
 *       moon a mean-element theory fitted to JPL's satellite ephemerides ({@link SatelliteEphemeris}).</li>
 *   <li>Each planet's centre sits off its system barycentre by its moons' reflex (Earth by
 *       ~4700 km, Pluto by ~2100 km), so planet and moons orbit their common centre of mass.</li>
 *   <li>Orbit rings are the osculating ellipses of the bodies' current states.</li>
 * </ul>
 *
 * @author Ethan Lin (original)
 * @version 4.0
 */

public class SolarSystemSimulation extends JPanel
        implements ActionListener, MouseWheelListener, MouseListener, MouseMotionListener {

    /* ==============================
       PHYSICAL CONSTANTS (SI UNITS)
       ============================== */
    /** 1 astronomical unit in metres (IAU 2012 definition, the unit the ephemerides use) */
    private static final double AU  = Astro.AU_M;
    /** Seconds in one Earth day */
    static final double DAY = 86_400.0;
    /** Seconds in one Earth year */
    static final double YEAR = 365.25 * DAY;
    /** Gravitational constant (m³ kg⁻¹ s⁻²) */
    private static final double G   = 6.67430e-11;
    /** Mass of the Sun (kg) */
    private static final double M_SUN = 1.98847e30;
    /** Mutable Sun mass used in simulation (can be changed at runtime) */
    private final double sunMass = M_SUN;

    /** Conversion factor: meters → screen pixels at base zoom (250 px per AU) */
    static final double METERS_TO_PIXELS = 250.0 / AU;

    /* ==============================
       CAMERA SYSTEM
       ============================== */
    /** Index of currently selected planet (-1 = none) */
    int selectedPlanet = -1;
    /** Whether camera is locked to a planet */
    boolean followingPlanet = false;
    /** Index of planet currently tracked by camera */
    int     cameraFocusIndex = -1;
    /** Current camera center in world coordinates (meters) */
    double  camX = 0, camY = 0;
    /** Camera-centre height above the ecliptic (m); tracks a followed body's Z so it stays centred. */
    private double  camZ = 0;

    /**
     * View pitch: how far the camera is tilted off straight-down (radians). 0 = top-down from
     * above the ecliptic (elevation +90°); 90° = edge-on (in the plane); 180° = straight-down
     * from below the plane (elevation −90°). Sweeping the full range lets you view the system
     * from 90° above to 90° below. Free-dragged with the mouse (or [ / ] keys).
     * {@link #viewCosP}/{@link #viewSinP} cache its cos/sin and are refreshed by {@link #setViewPitch}.
     */
    private double viewPitchRad = 0.0;
    private double viewCosP = 1.0, viewSinP = 0.0;
    static final double MAX_PITCH_RAD = Math.PI;   // top-down (0) → edge-on (π/2) → bottom-up (π)
    /**
     * View yaw (azimuth): rotation of the camera around the vertical (ecliptic-normal) axis
     * through the camera target, so you can swing fully around the system. 0 = the original
     * orientation. Free-dragged horizontally with the mouse; {@link #viewCosY}/{@link #viewSinY}
     * cache its cos/sin and are refreshed by {@link #setViewYaw}.
     */
    private double viewYawRad = 0.0;
    private double viewCosY = 1.0, viewSinY = 0.0;
    /** Interactive launches start slightly oblique so the scene reads as 3-D; press \ for top-down. */
    private static final double DEFAULT_VIEW_PITCH_DEG = 24.0;

    /**
     * One on-screen text label, collected during the paint pass and placed afterwards by
     * {@link #drawPendingLabels} so labels never overlap: higher-priority / nearer bodies claim
     * their spot first and lower-priority ones that can't find clear space are dropped.
     */
    private static final class LabelReq {
        final String text; final double ax, ay, bodyR, depth; final Color color;
        final float size; final boolean bold; final int priority;
        LabelReq(String text, double ax, double ay, double bodyR, Color color,
                 float size, boolean bold, int priority, double depth) {
            this.text = text; this.ax = ax; this.ay = ay; this.bodyR = bodyR; this.color = color;
            this.size = size; this.bold = bold; this.priority = priority; this.depth = depth;
        }
    }
    private final java.util.ArrayList<LabelReq> pendingLabels = new java.util.ArrayList<>();
    /** Reusable per-frame buffers for projecting an orbit ring (sized to the max adaptive segments). */
    private double[] ringSX = new double[16386], ringSY = new double[16386];

    /** True if camera is still moving toward target (smooth approach) */
    private boolean cameraIsApproaching = false;
    /** Distance threshold (m) to stop smoothing camera motion */
    private static final double ARRIVAL_THRESHOLD = 1e7;

    // ----- Moon selection -----
    /** Index of selected moon on current planet */
    int selectedMoon = -1;
    /** Index of moon being tracked by camera */
    int   cameraFocusMoon = -1;
    /** Camera center when following a moon */
    private double moonCamX = 0, moonCamY = 0;
    /** Whether camera is locked to a moon */
    boolean followingMoon = false;
    /** True if moon camera is approaching target */
    private boolean moonCameraIsApproaching = false;

    // ----- Visibility & UI toggles -----
    /** Whether to draw all planet orbits */
    boolean showPlanetOrbits = true;
    /** Whether to draw planetary rings (e.g., Saturn) */
    boolean showRings = true;
    /** Whether to draw text labels for bodies */
    boolean showLabels = true;
    /** If true, only the selected planet (and its moons) is rendered */
    boolean showOnlySelected = false;
    /** If true, show a small Sun info panel (toggled with U) */
    private boolean showSunInfo = false;

    // ----- Asteroid and Debris Belt Visibility -----
    /** Whether to draw the asteroid belt */
    boolean showAsteroidBelt = false;
    /** Whether to draw the Kuiper belt */
    boolean showKuiperBelt = false;
    /** Whether to draw ice lines (frost line) */
    boolean showIceLines = false;

    // ----- Visibility toggles for individual planets -----
    /** Array tracking whether each planet should be visible */
    final boolean[] planetVisible = new boolean[12];

    // ----- UI components -----
    /** Reference to the main menu bar for toggling visibility */
    JFrame parentFrame;
    /** Whether menu bar is currently visible */
    private boolean menuBarVisible = true;
    /** Reference to the search bar panel for toggling visibility */
    JPanel searchBarPanel;
    /** Reference to the sidebar/navigation panel for toggling visibility */
    JPanel sidebarPanel;
    /** Education: lessons docked on the right; while one runs it drives this simulation. */
    LessonStudio lessonStudio;

    boolean lessonActive() { return lessonStudio != null && lessonStudio.isLessonActive(); }

    // ----- Lesson camera: eases to each step's framing until the user takes the view -----
    /** {centreX m, centreY m, radius m, pitch deg, yaw deg}, or null. */
    private double[] lessonAimGoal;
    private boolean lessonSteer;
    /** When set, the only catalogued small body a lesson shows (e.g. Halley's Comet). */
    private String lessonOnlyBody;
    /** Everything a lesson changes, captured when it starts so leaving restores the sim exactly. */
    private Object[] lessonSaved;

    /**
     * A lesson started (true) or ended (false). The object sidebar (with the time controls) and
     * the search bar step aside during a lesson, and on the way out every setting the lesson
     * touched — visibility, clock, date, camera, overlays — is put back as it was.
     */
    void onLessonActiveChanged(boolean active) {
        if (active && missionPlayer.isActive()) missionPlayer.stop();
        if (active) {
            lessonSaved = new Object[]{
                planetVisible.clone(), showPlanetOrbits, showLabels, showComets, showAsteroids,
                showDistantBodies, showAsteroidBelt, showKuiperBelt, showIceLines, showPerihelionAphelion,
                showVelocityVectors, showVelocityComparison, showKeplersLaw, showOnlySelected, barycentricFrame,
                timeScale, simulationPaused, simTimeSec, camX, camY, camZ, currentZoom, targetZoom,
                autoZoomEnabled, zoomMode, followingPlanet, followingMoon, cameraFocusIndex, cameraFocusMoon,
                selectedPlanet, selectedMoon, viewPitchRad, viewYawRad, showSunInfo};
            showPerihelionAphelion = showVelocityVectors = showVelocityComparison = showKeplersLaw = false;
            showOnlySelected = false;
            showSunInfo = false;
            barycentricFrame = false;
            simulationPaused = false;
            computeBarycenter();
        } else if (lessonSaved != null) {
            Object[] s = lessonSaved;
            int k = 0;
            System.arraycopy((boolean[]) s[k++], 0, planetVisible, 0, planetVisible.length);
            showPlanetOrbits = (Boolean) s[k++]; showLabels = (Boolean) s[k++]; showComets = (Boolean) s[k++];
            showAsteroids = (Boolean) s[k++]; showDistantBodies = (Boolean) s[k++]; showAsteroidBelt = (Boolean) s[k++];
            showKuiperBelt = (Boolean) s[k++]; showIceLines = (Boolean) s[k++]; showPerihelionAphelion = (Boolean) s[k++];
            showVelocityVectors = (Boolean) s[k++]; showVelocityComparison = (Boolean) s[k++]; showKeplersLaw = (Boolean) s[k++];
            showOnlySelected = (Boolean) s[k++]; barycentricFrame = (Boolean) s[k++];
            double ts = (Double) s[k++];
            simulationPaused = (Boolean) s[k++];
            goToSimTime((Double) s[k++]);
            setTimeScaleAndSync(ts);
            camX = (Double) s[k++]; camY = (Double) s[k++]; camZ = (Double) s[k++];
            currentZoom = (Double) s[k++]; targetZoom = (Double) s[k++];
            autoZoomEnabled = (Boolean) s[k++]; zoomMode = (Integer) s[k++];
            followingPlanet = (Boolean) s[k++]; followingMoon = (Boolean) s[k++];
            cameraFocusIndex = (Integer) s[k++]; cameraFocusMoon = (Integer) s[k++];
            selectedPlanet = (Integer) s[k++]; selectedMoon = (Integer) s[k++];
            setViewPitch((Double) s[k++]); setViewYaw((Double) s[k++]);
            showSunInfo = (Boolean) s[k++];
            moonCamX = camX; moonCamY = camY;
            cameraIsApproaching = moonCameraIsApproaching = false;
            lessonSaved = null;
            lessonAimGoal = null;
            lessonOnlyBody = null;
            computeBarycenter();
            updateTimeScaleLabel();
        }
        boolean showPanels = !active && uiPanelsVisible;
        if (sidebarPanel != null) sidebarPanel.setVisible(showPanels);
        if (searchBarPanel != null) searchBarPanel.setVisible(showPanels);
        Container p = getParent();
        if (p != null) { p.revalidate(); p.repaint(); }
        requestFocusInWindow();
        repaint();
    }

    /** Ease the lesson camera toward its goal (called each frame while nothing is followed). */
    private void steerLessonCamera(double dtReal) {
        double[] g = lessonAimGoal;
        double k = 1 - Math.exp(-3.0 * dtReal);
        double zoomGoal = 0.40 * Math.max(1, Math.min(getWidth(), getHeight())) / (g[2] * METERS_TO_PIXELS);
        camX += (g[0] - camX) * k;
        camY += (g[1] - camY) * k;
        moonCamX = camX; moonCamY = camY;
        currentZoom = Math.exp(Math.log(currentZoom) + (Math.log(zoomGoal) - Math.log(currentZoom)) * k);
        targetZoom = currentZoom;
        setViewPitch(viewPitchRad + (Math.toRadians(g[3]) - viewPitchRad) * k);
        double dy = Math.IEEEremainder(Math.toRadians(g[4]) - viewYawRad, 2 * Math.PI);
        setViewYaw(viewYawRad + dy * k);
    }

    /** The simulation as seen by the lessons (see {@link LessonStudio.Host}). */
    final class LessonHost implements LessonStudio.Host {
        public int planet(String name) {
            for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(name)) return i;
            return -1;
        }
        public void showOnly(String smallBody, String... planets) {
            java.util.Arrays.fill(planetVisible, false);
            for (String n : planets) { int i = planet(n); if (i >= 0) planetVisible[i] = true; }
            lessonOnlyBody = smallBody;
            showAsteroidBelt = showKuiperBelt = showIceLines = false;
            showComets = showAsteroids = showDistantBodies = false;
        }
        public void setOrbits(boolean orbits, boolean labels) { showPlanetOrbits = orbits; showLabels = labels; }
        public void setTime(double s) {
            simulationPaused = s <= 0;
            if (s > 0) setTimeScaleAndSync(s);
            updateTimeScaleLabel();
        }
        public double timeScale() { return simulationPaused ? 0 : timeScale; }
        public void goToDate(int y, int m, int d) { SolarSystemSimulation.this.goToDate(java.time.LocalDateTime.of(y, m, d, 12, 0)); }
        public String dateString() { return simDateString(); }
        public double simTime() { return simTimeSec; }
        public void aim(double[] goal) {
            followingPlanet = followingMoon = false;
            cameraFocusIndex = cameraFocusMoon = -1;
            selectedPlanet = selectedMoon = -1;
            cameraIsApproaching = moonCameraIsApproaching = false;
            autoZoomEnabled = false;
            lessonAimGoal = goal.clone();
            lessonSteer = true;
        }
        public double sunRadiusM() { return SUN_RADIUS_M; }

        // The lessons draw the ellipse a body is on right now: its osculating orbit.
        public double planetA(int i) { return oscA[i]; }
        public double planetEcc(int i) { return oscE[i]; }
        public double planetEccAnomaly(int i) { return oscEanom[i]; }
        public double[] planetOrbit(int i, double E) { return planetOrbitPoint(i, E); }
        private SmallBody body(String name) {
            for (SmallBody sb : smallBodies) if (sb.name.equals(name)) return sb;
            throw new IllegalArgumentException("no small body " + name);
        }
        public double bodyA(String n) { return body(n).aM; }
        public double bodyEcc(String n) { return body(n).ecc; }
        public double bodyEccAnomaly(String n) { return body(n).eccAnom; }
        public double[] bodyOrbit(String n, double E) {
            SmallBody sb = body(n);
            return keplerXYZ(sb.aM, sb.ecc, E, sb.inclRad, sb.omegaRad, sb.varpiRad);
        }

        public boolean project(double x, double y, double z, double[] o) {
            double wx = x + sunBarycenterX, wy = y + sunBarycenterY;
            if (!inFront(wx, wy, z)) return false;
            o[0] = projX(wx, wy, z);
            o[1] = projY(wx, wy, z);
            o[2] = cameraDistanceMeters() - viewDepth(wx, wy, z);
            o[3] = perspScaleAt(wx, wy, z);
            return true;
        }
        public double[] eye() {
            double d = cameraDistanceMeters();
            return new double[]{camX - sunBarycenterX + viewSinY * viewSinP * d,
                                camY - sunBarycenterY + viewCosY * viewSinP * d,
                                camZ + viewCosP * d};
        }
        public double[] sunDisc() {
            double sx = sunBarycenterX, sy = sunBarycenterY;
            if (!inFront(sx, sy, 0)) return null;
            return new double[]{projX(sx, sy, 0), projY(sx, sy, 0), SUN_RADIUS_M * perspScaleAt(sx, sy, 0),
                                cameraDistanceMeters() - viewDepth(sx, sy, 0)};
        }
    }
    /* ==============================
       SPACECRAFT MISSIONS (played back inside this view)
       ============================== */
    /** Plays spacecraft missions: trails, markers, the mission panel, auto camera and pacing. */
    final MissionPlayer missionPlayer = new MissionPlayer(new MissionHost());

    /** Docked transfer planner and sky-events panels (created with the window; null head-less). */
    TransferPlanner transferPlanner;
    EventsPanel eventsPanel;

    /** Show one of the docked tool panels (or none), keeping the lesson panel as it is. */
    void showDockPanel(JComponent which) {
        if (transferPlanner != null) transferPlanner.setVisible(which == transferPlanner);
        if (eventsPanel != null) eventsPanel.setVisible(which == eventsPanel);
        if (which == transferPlanner && transferPlanner != null) transferPlanner.open();
        Container p = getParent();
        if (p != null) { p.revalidate(); p.repaint(); }
        repaint();
    }

    /** World position (m) of a body by name, as the scene draws it now. */
    double[] bodyWorld(String body) {
        if (body.equals("Sun")) return new double[]{sunBarycenterX, sunBarycenterY, 0};
        if (body.equals("Moon")) {
            double[] rel = moonRelPos(EARTH_INDEX, 0);
            return new double[]{planetX[EARTH_INDEX] + sunBarycenterX + rel[0],
                    planetY[EARTH_INDEX] + sunBarycenterY + rel[1], planetZ[EARTH_INDEX] + rel[2]};
        }
        for (int i = 0; i < NAMES.length; i++)
            if (NAMES[i].equals(body)) return new double[]{planetX[i] + sunBarycenterX, planetY[i] + sunBarycenterY, planetZ[i]};
        return null;
    }

    /** The simulation as seen by the mission player. */
    final class MissionHost implements MissionPlayer.Host {
        public double simTime() { return simTimeSec; }
        public void jumpTo(double s) { goToSimTime(s); }
        public double timeScale() { return timeScale; }
        public void setTimeScale(double s) { setTimeScaleAndSync(s); }
        public boolean paused() { return simulationPaused; }
        public void setPaused(boolean p) { simulationPaused = p; updateTimeScaleLabel(); }
        public double[] centreWorld(String body) { return bodyWorld(body); }
        public boolean project(double wx, double wy, double wz, double[] out) {
            if (!inFront(wx, wy, wz)) return false;
            out[0] = projX(wx, wy, wz);
            out[1] = projY(wx, wy, wz);
            return true;
        }
        public int width() { return getWidth(); }
        public int height() { return getHeight(); }
        public void aimCamera(double wx, double wy, double wz, double radius, double dtReal) {
            followingPlanet = followingMoon = false;
            cameraFocusIndex = cameraFocusMoon = -1;
            cameraIsApproaching = moonCameraIsApproaching = false;
            autoZoomEnabled = false;
            camX = moonCamX = wx; camY = moonCamY = wy; camZ = wz;
            if (radius > 0) {
                currentZoom = 0.40 * Math.max(1, Math.min(getWidth(), getHeight())) / (radius * METERS_TO_PIXELS);
                targetZoom = currentZoom;
            }
        }
        public void ensureVisible(String... bodies) {
            for (String b : bodies) for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(b)) planetVisible[i] = true;
            showOnlySelected = false;
            updateTreeVisibility();
        }
        public void missionEnded() { repaint(); }
    }

    /** Whether search bar and sidebar are currently visible */
    private boolean uiPanelsVisible = true;
    /** Text field used by the top search bar to jump to bodies */
    JTextField searchField;
    /** Popup list of name suggestions for the search field */
    JPopupMenu searchSuggestionsPopup;
    /** List component that shows filtered planet/moon names */
    JList<String> searchSuggestionsList;
    /** Tree navigation of planets and moons */
    JTree bodyTree;
    /** Tree model for navigator */
    DefaultTreeModel bodyTreeModel;
    /** Root node of the tree */
    DefaultMutableTreeNode treeRootNode;
    /** Array of planet tree nodes */
    DefaultMutableTreeNode[] planetTreeNodes;
    /** Cached paths to planet nodes */
    TreePath[] planetTreePaths;
    /** Cached paths to moon nodes */
    TreePath[][] moonTreePaths;
    /** Guard to prevent recursive selection syncing */
    private boolean suppressTreeSelection;

    // ----- Time controls -----
    static final double TIME_SCALE_MIN = 1.0; // minimum 1.0 s/s
    private static final double TIME_SCALE_MAX = 100 * YEAR;
    static final int TIME_SLIDER_MIN = 0;
    static final int TIME_SLIDER_MAX = 1000;
    /** True when the simulation clock is paused */
    boolean simulationPaused = false;
    /** Slider used to adjust time scale linearly */
    JSlider timeScaleSlider;
    /** Label reflecting the current time scale and pause state */
    JLabel timeScaleLabel;
    /** Prevent slider event loops when syncing from code */
    boolean suppressTimeSliderEvents = false;
    /** Editable numeric field for the time-scale magnitude (paired with the unit combo). */
    JTextField timeScaleValueField;
    /** Dropdown selecting the unit the value field is expressed in (s/s, hr/s, day/s, ...). */
    JComboBox<String> timeScaleUnitCombo;
    /** Guards against feedback loops while syncing the value field / unit combo from code. */
    boolean suppressTimeFieldEvents = false;
    /** Selectable time-scale units and their length in real seconds; index-aligned. */
    static final String[] TS_UNIT_NAMES   = {"s/s", "min/s", "hr/s", "day/s", "week/s", "month/s", "yr/s"};
    private static final double[] TS_UNIT_SECONDS = {1.0, 60.0, 3600.0, DAY, 7 * DAY, 30 * DAY, YEAR};


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
    /**
     * When true the whole system is drawn in the barycentric frame, so the Sun (and
     * with it every Sun-centred orbit ring) performs its real reflex wobble around the
     * system barycentre. Off by default: in a barycentric frame that wobble — ~1.2 M km,
     * dominated by Jupiter+Saturn — makes the Sun and all orbit rings visibly swing at
     * high time-acceleration, which reads as the planets "oscillating off their orbits".
     * With it off the Sun is fixed at the origin (heliocentric frame) and planets glide
     * on perfectly stationary rings.
     */
    boolean barycentricFrame = false;

    /** Toggle: auto-adjust zoom when following bodies */
    private boolean autoZoomEnabled = false;
    /** Target zoom level (auto-computed) */
    private double targetZoom = 1.0;
    /** Current zoom level (smoothly interpolated) */
    double currentZoom = 1.0;
    /** Speed of zoom interpolation (higher = faster) */
    private static final double ZOOM_SPEED = 10.0;
    private int zoomMode = 0;                   // 0=Planet, 1=Orbit, 2=Manual

    /* ==============================
       EARTH EXPLORER  ("Google Earth" mode)
       ==============================
       A self-contained, offline pan/zoom globe you drop into when you zoom into
       Earth. It orthographically ray-casts the full-resolution Earth map centred
       on a geographic point (geLat/geLon) at an arbitrary zoom (geZoom): far out
       it reads as a globe hanging in space, and as you zoom in the visible cap
       flattens into a smooth, keep-zooming-forever ground view — like Google
       Earth, but sampled from the local textures with no network. */
    /** Active while zoomed into Earth's surface. */
    boolean earthExplorerActive = false;

    /* ==============================
       SKY FROM EARTH (planetarium)
       ============================== */
    /** The planetarium view of the real sky from a place on Earth (see {@link SkyView}). */
    SkyView skyView;
    boolean skyViewActive = false;

    SkyView skyView() {
        if (skyView == null) {
            skyView = new SkyView(new SkyView.Host() {
                public double simTimeSec() { return simTimeSec; }
                public void repaint() { SolarSystemSimulation.this.repaint(); }
                public Component component() { return SolarSystemSimulation.this; }
                public void goToNow() { goToDate(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)); }
            });
        }
        return skyView;
    }

    /** Switch the canvas to the sky as seen from the observer's location. */
    void enterSkyView() {
        if (earthExplorerActive) exitEarthExplorer();
        skyView();
        skyViewActive = true;
        draggingView = false;
        requestFocusInWindow();
        repaint();
    }

    /** Look at the sky from a given place (e.g. the Earth Explorer's map centre). */
    void enterSkyViewAt(String name, double lat, double lon) {
        skyView().setLocation(name, lat, lon);
        enterSkyView();
    }

    void exitSkyView() {
        skyViewActive = false;
        repaint();
    }
    /** Geographic point (deg) under the centre of the explorer view. */
    private double geLat = 20.0, geLon = 0.0;
    /** Zoom: 1.0 ≈ whole globe fits the frame; grows without bound as you zoom in. */
    private double geZoom = 1.0, geTargetZoom = 1.0;
    /** Entry/exit zoom: zooming back out past this leaves the explorer for the solar view. */
    private static final double GE_MIN_ZOOM = 0.80;
    /** Deep enough for street level once satellite tiles kick in (bounded only by over-zoom blur). */
    private static final double GE_MAX_ZOOM = 5.0e5;
    /** Cross-fade band (in geZoom) from the local-texture globe to the streamed satellite tiles.
     *  geZoom ≈ 2·R_earth / (view-width); 3.3 ≈ 3900 km across, 5.8 ≈ 2200 km — so satellite
     *  imagery starts fading in around a ~3000 km-wide view and is fully in a bit closer. */
    private static final double GE_GLOBE_MAX = 3.3;    // below: pure globe
    private static final double GE_TILE_MIN  = 5.8;    // above: pure tiles (globe ray-cast skipped)
    /** Smooth fly-in transition when entering the explorer (0→1). */
    private double geTransition = 0.0;
    /** Screen-roll (rad) of Earth's axial tilt at the moment of entry, straightened to north-up
     *  as you zoom in — so the hand-off keeps the tilted globe and only levels out on the way down. */
    private double geRoll0 = 0.0;
    /** Index of the body the explorer shows (Earth). */
    private static final int EARTH_INDEX = 2;
    /** Full-resolution Earth maps for the explorer (loaded once, uncapped by the 2K sim cap). */
    private int[] geDiffuse, geNight, geClouds;
    private int geDiffW, geDiffH, geNightW, geNightH, geCloudW, geCloudH;
    private boolean geTexTried = false;
    /** Reused offscreen raster the globe is ray-cast into (scaled to the panel on blit). */
    private BufferedImage geBuf;
    private int geBufW = -1, geBufH = -1;
    /** Cached on-screen radius (internal-buffer px) of the globe last frame, for pan scaling. */
    private double geLastRadiusPx = 1.0;
    /** Ray-cast cache: reuse the last raster when the view + lighting are unchanged (idle frames). */
    private boolean geBufValid = false;
    private double geRLat, geRLon, geRZoom, geRSunX, geRSunY, geRSunZ;
    private boolean geRShade, geRNight, geRClouds;

    // ---- Satellite tile streaming (Esri World Imagery, Web-Mercator XYZ) for street-level zoom ----
    // Three transparent-overlay layers, each {z}/{y}/{x}, 256 px tiles: satellite base, place +
    // boundary labels, and road/transport labels — the "Google Maps hybrid" stack.
    private static final int LYR_IMAGERY = 0, LYR_PLACES = 1, LYR_ROADS = 2;
    private static final String[] GE_LAYER_URL = {
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/",
        "https://services.arcgisonline.com/arcgis/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/",
        "https://services.arcgisonline.com/arcgis/rest/services/Reference/World_Transportation/MapServer/tile/",
    };
    private static final String GE_TILE_ATTRIB = "Imagery © Esri, Maxar, Earthstar Geographics";
    /** Overlay place/road labels on the satellite map (toggle with L in the explorer). */
    private boolean geLabels = true;
    /** Reused low-res buffer for the day/night terminator drawn over the tiles. */
    private BufferedImage geLightBuf;
    private static final int GE_TILE_PX = 256;
    private static final int GE_MAX_TILE_Z = 19;                 // Esri World Imagery max detail
    private static final double GE_EARTH_CIRC = 2 * Math.PI * 6_378_137.0;   // WGS84 equator (m)
    /** In-memory LRU tile cache (bounded) shared with the fetch workers. */
    private final Map<Long, BufferedImage> tileCache =
            Collections.synchronizedMap(new LinkedHashMap<>(1024, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<Long, BufferedImage> e) {
                    return size() > 768;
                }
            });
    private final Set<Long> tileLoading = ConcurrentHashMap.newKeySet();
    private final Set<Long> tileFailed = ConcurrentHashMap.newKeySet();
    private ExecutorService tilePool;      // parallel fetch workers (LIFO: newest view first)
    private ExecutorService tileDiskPool;  // off-thread disk-cache writes (kept off the fetch path)
    private HttpClient tileHttp;           // shared HTTP/2 client: connection reuse + multiplexing
    private File tileCacheDir;
    private volatile boolean tilesReachable = true;   // flips false after repeated network failures
    private int tileFailStreak = 0;

    // ----- Educational overlays (Education > Overlays) -----
    boolean showKeplersLaw = false;     // Show Kepler's 3rd Law calculations
    boolean showPerihelionAphelion = false; // Highlight perihelion/aphelion points
    boolean showVelocityVectors = false;    // Show velocity vectors on planets
    boolean showVelocityComparison = false; // Show velocity comparison at perihelion vs aphelion

    // ----- UI Size Settings -----
    enum UISize { SMALL, NORMAL, LARGE }
    UISize uiSize = UISize.NORMAL;  // Default to normal (previously small)

    // ----- Orbit visibility thresholds -----
    /** Zoom level at which orbits become visible */
    private static final double ORBIT_VISIBLE_ZOOM = 5.0;

    /* ==============================
       SIMULATION STATE
       ============================== */
    /** Time acceleration factor (1 = real-time, 1000 = 1000× faster) */
    double timeScale  = DAY;
    /** Total simulated time elapsed (seconds) */
    double simTimeSec = 0;
    /** Timestamp of last frame (nanoseconds) */
    private long   lastNanos;

    /* ==============================
       PLANET DATA (physical; orbits are integrated)
       ============================== */
    /** Names of celestial bodies */
    static final String[] NAMES = {
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

    /**
     * Nominal mean distance from the Sun (m) — used only to size the UI (orbit tessellation,
     * zoom framing). The real orbit is integrated (see {@link NBodyEphemeris}); its current
     * osculating elements are in {@link #oscA} etc.
     */
    private static final double[] A_METERS = {
            5.7909e10, 1.0821e11, 1.495978707e11, 2.2794e11,
            7.7854e11, 1.4298e12, 2.8725e12, 4.5045e12, 5.9064e12,
            2.766496 * AU, 42.909258 * AU, 68.139869 * AU
    };

    /** Physical radius (m) */
    static final double[] RADIUS_M = {
            2.439e6, 6.052e6, 6.371e6, 3.390e6,
            6.9911e7, 5.8232e7, 2.5362e7, 2.4622e7, 1.1883e6,
            4.697e5, 8.16e5, 1.163e6
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

    static final double[] PLANET_MASS = {
            3.3011e23, 4.8675e24, 5.97237e24, 6.4171e23,
            1.8982e27, 5.6834e26, 8.6810e25, 1.02413e26, 1.303e22,
            9.3835e20, 4.006e21, 1.6466e22
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

    // ============================================================================
    // IAU / WGCCRE (2015) rotation model for the eight planets (indices 0..7).
    //
    // The report fixes each body's orientation in inertial space with three angles:
    //   pole right ascension  a0 = A0 + A0_T * T        (deg, ICRF/J2000 equatorial)
    //   pole declination      d0 = D0 + D0_T * T        (deg)
    //   prime meridian        W  = W0 + WDOT * d         (deg, measured east from the
    //                                                     node of the ICRF equator)
    // with d = days of TT since J2000 and T = d / 36525 Julian centuries. Ẇ is the
    // true sidereal spin rate (negative = retrograde: Venus, Uranus). Small periodic
    // libration terms (Mercury, Neptune's sinN, Jupiter's magnetosphere wobble) are
    // dropped — well below the sim's other approximations. Values: Archinal et al.,
    // "Report of the IAU WGCCRE 2015", Celest. Mech. Dyn. Astron. 130:22 (2018).
    //
    // These anchor the on-screen spin to a REAL orientation datum: at any date the
    // sub-solar meridian rendered on a planet is the meridian the Sun truly stands
    // over, instead of an arbitrary phase. See iauSubSolarEastLon / bodySpinAngle.
    // ============================================================================
    /** Pole right ascension a0 at J2000 (deg) and its per-century rate. */
    private static final double[] IAU_RA0   = {281.0103, 272.76, 0.00,     317.269,  268.056595, 40.589, 257.311, 299.36};
    private static final double[] IAU_RA0_T = {  0.0,      0.0,  -0.641,    -0.106,   -0.006499,  -0.036,  0.0,     0.0 };
    /** Pole declination d0 at J2000 (deg) and its per-century rate. */
    private static final double[] IAU_DEC0   = {61.4155, 67.16, 90.00,   54.432,  64.495303, 83.537, -15.175, 43.46};
    private static final double[] IAU_DEC0_T = { 0.0,     0.0,  -0.557,  -0.061,   0.002413, -0.004,   0.0,    0.0 };
    /** Prime-meridian angle W at J2000 (deg) and its rate (deg/day); negative = retrograde. */
    private static final double[] IAU_W0   = {329.5988, 160.20, 190.147, 176.049863, 284.95,   38.90,   203.81,   253.18};
    private static final double[] IAU_WDOT = {  6.1385108, -1.4813688, 360.9856235, 350.891982443297, 870.5360000, 810.7939024, -501.1600928, 536.3128492};
    /** Obliquity of the ecliptic (deg) used to rotate the sim's ecliptic vectors into the ICRF equatorial frame the IAU pole is given in. */
    private static final double ECLIPTIC_OBLIQUITY_DEG = 23.43929111;

    /** Sidereal rotation of the Sun at the equator (hours). */
    private static final double SUN_ROTATION_H = 609.12;
    /** Moon (Luna) sidereal rotation (tidally locked) and obliquity to ecliptic. */
    private static final double MOON_ROTATION_H = 655.72;
    private static final double MOON_OBLIQUITY_DEG = 6.68;

    /* ==============================
       MOON DATA
       ============================== */
    /**
     * Mean semi-major axis of each moon about its planet (m). Nominal values for UI sizing and
     * the info panel; positions come from {@link MoonEphemeris} (Earth's Moon) and
     * {@link SatelliteEphemeris} (all others).
     */
    static final double[][] MOON_A_M = {
            {}, {}, {3.844e8},
            {9.377e6, 2.356e7},
            {4.217e8, 6.71e8, 1.07e9, 1.883e9},
            {1.222e9, 2.38e8, 5.27e8, 1.861e8, 2.946e8, 3.774e8, 3.561e9},
            {4.363e8, 5.835e8, 2.660e8, 1.913e8, 1.299e8},
            {3.547e8}, {1.9596e7}, {}, {}, {}
    };

    /** Moon sidereal orbital period (Earth days). */
    static final double[][] MOON_PERIOD_DAYS = {
            {}, {}, {27.321661},
            {0.318910, 1.26244},
            {1.769278, 3.551181, 7.154552, 16.689018},
            {15.94542, 1.370218, 4.518212, 0.942422, 1.887802, 2.736915, 79.3215},
            {8.706234, 13.46339, 4.144176, 2.520379, 1.413479},
            {5.876854}, {6.38723}, {}, {}, {}
    };

    /**
     * Mean radius of each moon (m), JPL / IAU values. (An earlier table mixed ratios to
     * Earth's radius with made-up ones and scaled them by the parent, drawing e.g. Io at
     * 20 000 km instead of 1 822 km; these are the real sizes.)
     */
    private static final double[][] MOON_RADIUS_M = {
            {}, {}, {1737.4e3},
            {11.08e3, 6.2e3},
            {1821.6e3, 1560.8e3, 2634.1e3, 2410.3e3},
            {2574.7e3, 252.1e3, 763.8e3, 198.2e3, 531.1e3, 561.4e3, 734.5e3},
            {788.9e3, 761.4e3, 584.7e3, 578.9e3, 235.8e3},
            {1353.4e3}, {606.0e3},
            {}, {}, {}
    };

    /** Names of moons per planet */
    static final String[][] MOON_NAMES = {
            {}, {}, {"Moon"},
            {"Phobos", "Deimos"},
            {"Io", "Europa", "Ganymede", "Callisto"},
            {"Titan", "Enceladus", "Rhea", "Mimas", "Tethys", "Dione", "Iapetus"},
            {"Titania", "Oberon", "Umbriel", "Ariel", "Miranda"},
            {"Triton"}, {"Charon"}, {}, {}, {}
    };

    /** Moon radius as a ratio of its parent planet's radius (the eclipse-shadow geometry's unit). */
    private static final double[][] MOON_SIZE_RATIO = new double[NAMES.length][];
    static {
        for (int i = 0; i < NAMES.length; i++) {
            MOON_SIZE_RATIO[i] = new double[MOON_RADIUS_M[i].length];
            for (int m = 0; m < MOON_RADIUS_M[i].length; m++) MOON_SIZE_RATIO[i][m] = MOON_RADIUS_M[i][m] / RADIUS_M[i];
        }
    }

    // ---- Read-only body data for the planetarium (SkyView) ----
    static int bodyCount() { return NAMES.length; }
    static String bodyName(int i) { return NAMES[i]; }
    static double bodyRadiusM(int i) { return RADIUS_M[i]; }
    static double moonRadiusM(int i, int m) { return MOON_RADIUS_M[i][m]; }

    /* ==============================
       ORBITAL STATE
       ============================== */
    /**
     * Osculating heliocentric elements of each planet's centre at the current sim time
     * (from {@link #updateEphemeris}): semi-major axis (m), eccentricity, inclination, node Ω,
     * longitude of perihelion ϖ, mean and eccentric anomaly (radians), and period (years).
     * These are the ellipse the planet is on right now — the orbit ring, the perihelion /
     * aphelion markers, Kepler's-law overlay and the lessons all use them.
     */
    private final double[] oscA = new double[NAMES.length], oscE = new double[NAMES.length],
            oscI = new double[NAMES.length], oscNode = new double[NAMES.length],
            oscVarpi = new double[NAMES.length], oscM = new double[NAMES.length],
            oscEanom = new double[NAMES.length], oscPeriodY = new double[NAMES.length];

    /** Built-in catalog of real minor bodies (comets, asteroids, distant objects). */
    private final java.util.List<SmallBody> smallBodies = new java.util.ArrayList<>();
    boolean showComets = false;     // comets off by default (toggle in the Objects menu)
    boolean showAsteroids = false;  // asteroids / near-Earth objects off by default
    boolean showDistantBodies = false;  // far centaurs/TNOs off by default (huge orbits)
    boolean showSmallBodyLabels = true;

    /** Number of points to use when drawing orbits */
    private static final int ORBIT_RESOLUTION = 10000;

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
    boolean shadingEnabled = true;
    /** Atmospheric limb glow (thin scattering shell on bodies with an atmosphere). */
    boolean showAtmosphere = true;
    /** Mutual eclipse shadows between a planet and its moons (shadow transits + lunar eclipses). */
    boolean showEclipses = true;
    /** Emissive city lights on the night side of bodies with a night map (Earth). */
    boolean showNightLights = true;
    /** Rotating cloud layer on bodies with a cloud map (Earth). */
    boolean showClouds = true;
    /**
     * Tier-2 "GLSL pipeline": shade textured spheres on the GPU via {@link GLSphereRenderer}
     * instead of the per-pixel CPU loop in {@link #drawTexturedSphere}. Off by default; the
     * CPU path is always kept as a fallback (and used automatically if the GPU is unavailable).
     * Enabled by key <b>G</b> / the View menu, or eagerly via {@code -Dsss.gpu}.
     */
    boolean gpuSpheres = System.getProperty("sss.gpu") != null;
    /** Lazily-created off-screen GPU sphere shader; null until first needed, then cached. */
    private GLSphereRenderer glSpheres;
    private boolean glSpheresTried = false;

    /**
     * The GPU sphere renderer, created on first use. Returns null (and leaves
     * {@link #gpuSpheres} logically inert) if an off-screen GL context can't be made,
     * so the caller silently keeps the CPU path.
     */
    GLSphereRenderer gpuRenderer() {
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

        // All planets and dwarf planets (Pluto, Ceres, Haumea, Eris) visible by default.
        Arrays.fill(planetVisible, true);

        // Load the built-in catalog of real comets, asteroids and distant objects, then place
        // everything at its integrated state for the starting sim time (J2000.0).
        smallBodies.addAll(buildBodyCatalog());
        updateEphemeris();

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
            if (pitchProp != null) {
                setViewPitch(Math.toRadians(Double.parseDouble(pitchProp)));
            } else if (System.getProperty("sss.shot") == null
                    && System.getProperty("sss.lessonshot") == null) {
                // Interactive launch: start slightly oblique for an immediate sense of depth.
                // (Head-less showcase/lesson renders stay top-down so their framing is unchanged.)
                setViewPitch(Math.toRadians(DEFAULT_VIEW_PITCH_DEG));
            }
            String yawProp = System.getProperty("sss.yaw");   // optional initial azimuth (deg)
            if (yawProp != null) setViewYaw(Math.toRadians(Double.parseDouble(yawProp)));
        } catch (NumberFormatException ignored) { }
        lastNanos = System.nanoTime();
        if (System.getProperty("sss.shot") == null && System.getProperty("sss.lessonshot") == null
                && System.getProperty("sss.missionshot") == null) {
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
        if (lessonActive() && lessonStudio.handleKey(code)) return true;
        if (skyViewActive) {
            if (code == KeyEvent.VK_ESCAPE || code == KeyEvent.VK_BACK_SPACE || code == KeyEvent.VK_K) { exitSkyView(); return true; }
            if (skyView().handleKey(code)) return true;
            if (code != KeyEvent.VK_SPACE && code != KeyEvent.VK_P && code != KeyEvent.VK_H) return false;
        } else if (code == KeyEvent.VK_K && !lessonActive()) {
            enterSkyView();
            return true;
        }
        if (earthExplorerActive) {
            double step = 6.0 / Math.max(0.5, geZoom);   // pan step shrinks as you zoom in
            switch (code) {
                case KeyEvent.VK_ESCAPE, KeyEvent.VK_BACK_SPACE -> { exitEarthExplorer(); return true; }
                case KeyEvent.VK_LEFT  -> { geLon = wrapLonDeg(geLon - step); repaint(); return true; }
                case KeyEvent.VK_RIGHT -> { geLon = wrapLonDeg(geLon + step); repaint(); return true; }
                case KeyEvent.VK_UP    -> { geLat = Math.min(89.9, geLat + step); repaint(); return true; }
                case KeyEvent.VK_DOWN  -> { geLat = Math.max(-89.9, geLat - step); repaint(); return true; }
                case KeyEvent.VK_EQUALS, KeyEvent.VK_PLUS, KeyEvent.VK_ADD ->
                        { geTargetZoom = Math.min(GE_MAX_ZOOM, geTargetZoom * 1.3); return true; }
                case KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT -> {
                    geTargetZoom /= 1.3;
                    if (geTargetZoom < GE_MIN_ZOOM) exitEarthExplorer();
                    return true;
                }
                case KeyEvent.VK_L -> { geLabels = !geLabels; repaint(); return true; }   // map labels
                case KeyEvent.VK_V -> {                                                     // look up from here
                    enterSkyViewAt(String.format("Map centre (%.2f°, %.2f°)", geLat, geLon), geLat, geLon);
                    return true;
                }
                default -> { /* fall through to the shared S/C/I/pause toggles below */ }
            }
        }
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
            case KeyEvent.VK_COMMA         -> setViewYaw(viewYawRad - Math.toRadians(5));  // orbit left
            case KeyEvent.VK_PERIOD        -> setViewYaw(viewYawRad + Math.toRadians(5));  // orbit right
            case KeyEvent.VK_BACK_SLASH    -> { setViewPitch(0.0); setViewYaw(0.0); }      // reset view
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
            case KeyEvent.VK_T -> { apparentPositions = !apparentPositions; updateEphemeris(); }
            case KeyEvent.VK_H -> toggleMenuBarVisibility();
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
        lessonSteer = false;                     // the user is framing the view now
        if (e.isControlDown()) {
            // Ctrl+Scroll: adjust time scale
            double proposed = e.getPreciseWheelRotation() < 0 ? timeScale * 2 : timeScale / 2;
            proposed = Math.max(1.0, proposed); // clamp to 1.0 s/s minimum
            setTimeScaleAndSync(proposed);
        } else if (skyViewActive) {
            skyView().zoom(e.getPreciseWheelRotation());
        } else if (earthExplorerActive) {
            // Earth Explorer: scroll dives toward / pulls back from the surface.
            if (e.getPreciseWheelRotation() < 0) {
                geTargetZoom = Math.min(geTargetZoom * 1.30, GE_MAX_ZOOM);
            } else {
                geTargetZoom /= 1.30;
                if (geTargetZoom < GE_MIN_ZOOM) { exitEarthExplorer(); return; }
            }
            repaint();
        } else if (e.getPreciseWheelRotation() < 0 && followingPlanet && !followingMoon
                && cameraFocusIndex == EARTH_INDEX && hasTexture("earth")
                && earthScreenRadiusPx() >= 0.26 * Math.min(getWidth(), getHeight())) {
            // Zooming in on a well-framed Earth hands off to the Google-Earth-style explorer.
            enterEarthExplorer();
        } else if (missionPlayer.wheel(e.getX(), e.getY(), e.getWheelRotation())) {
            repaint();                               // scrolled the mission timeline
        } else {
            // Normal scroll: zoom. (Spacecraft close-ups need far more than a planet's zoom.)
            missionPlayer.userTookCamera();
            double factor = e.getPreciseWheelRotation() < 0 ? 1.1 : 1/1.1;
            currentZoom *= factor;
            currentZoom = Math.max(0.02, Math.min(currentZoom, missionPlayer.controlsCamera() ? 2e8 : 50000));
            targetZoom = currentZoom;
        }
    }

    /**
     * Handles click logic: select planet → moon → unfollow
     */
    private void handleClick(int mx, int my) {
        exitSearchModeIfActive();
        if (skyViewActive) { skyView().click(mx, my); return; }
        if (earthExplorerActive) return;   // clicks don't select bodies while exploring Earth
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
                if (!inFront(moonWorldX, moonWorldY, moonWorldZ)) continue;

                double mpx = projX(moonWorldX, moonWorldY, moonWorldZ);
                double mpy = projY(moonWorldX, moonWorldY, moonWorldZ);
                double mr  = Math.max(MOON_RADIUS_M[p][m] * perspScaleAt(moonWorldX, moonWorldY, moonWorldZ), 2.0);

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
            if (!inFront(planetX[i] + sunBarycenterX, planetY[i] + sunBarycenterY, planetZ[i])) continue;
            double px = projX(planetX[i] + sunBarycenterX, planetY[i] + sunBarycenterY, planetZ[i]);
            double py = projY(planetX[i] + sunBarycenterX, planetY[i] + sunBarycenterY, planetZ[i]);
            double pr = Math.max(RADIUS_M[i] * perspScaleAt(planetX[i] + sunBarycenterX, planetY[i] + sunBarycenterY, planetZ[i]), 4);

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
                    missionPlayer.releaseCamera();
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

        // CLICKED EMPTY SPACE → return to Sun (unless a mission's camera is on its spacecraft)
        if (missionPlayer.controlsCamera()) return;
        followingPlanet = followingMoon = false;
        cameraFocusIndex = cameraFocusMoon = -1;
        selectedPlanet = selectedMoon = -1;
        cameraIsApproaching = moonCameraIsApproaching = false;
        currentZoom = 1.0;
    }

    @Override public void mouseClicked(MouseEvent e) {
        if (lessonActive() && e.getClickCount() == 2) { lessonStudio.reaim(); return; }   // back to the step's view
        if (!skyViewActive && !earthExplorerActive && !lessonActive() && missionPlayer.click(e.getX(), e.getY())) { repaint(); return; }
        if (!handleScaleRulerClick(e.getX(), e.getY(), getHeight())) {
            handleClick(e.getX(), e.getY());
        }
    }
    @Override public void mousePressed(MouseEvent e) {
        boolean onRuler = handleScaleRulerClick(e.getX(), e.getY(), getHeight());
        draggingView = !onRuler;                 // otherwise a bare drag orbits the view
        lastDragX = e.getX();
        lastDragY = e.getY();
    }
    @Override public void mouseReleased(MouseEvent e) {
        stopScaleRulerDrag();
        draggingView = false;
    }
    @Override public void mouseEntered(MouseEvent e) {}
    @Override public void mouseExited(MouseEvent e) {}

    @Override public void mouseDragged(MouseEvent e) {
        if (skyViewActive) {
            skyView().drag(e.getX() - lastDragX, e.getY() - lastDragY);
            lastDragX = e.getX();
            lastDragY = e.getY();
            return;
        }
        if (earthExplorerActive) {
            // Grab-and-drag the surface: convert panel-pixel motion to a rotation of the
            // globe about the point under the cursor (scaled by the current zoom).
            double sx = (double) geBufW / Math.max(1, getWidth());
            double dxn = (e.getX() - lastDragX) * sx / Math.max(1.0, geLastRadiusPx);
            double dyn = (e.getY() - lastDragY) * sx / Math.max(1.0, geLastRadiusPx);
            double cphi = Math.max(0.15, Math.cos(Math.toRadians(geLat)));
            geLon -= Math.toDegrees(dxn / cphi);
            geLat += Math.toDegrees(dyn);
            geLat = Math.max(-89.9, Math.min(89.9, geLat));
            geLon = ((geLon + 180) % 360 + 360) % 360 - 180;
            lastDragX = e.getX();
            lastDragY = e.getY();
            repaint();
            return;
        }
        if (draggingView) {
            lessonSteer = false;                 // the user is framing the view now
            // Free-orbit the camera: horizontal drag swings the azimuth (yaw) around the system;
            // vertical drag pitches from top-down toward edge-on (reveals orbital inclination and
            // the out-of-plane oscillation).
            int dx = e.getX() - lastDragX;
            int dy = e.getY() - lastDragY;
            setViewYaw(viewYawRad + dx * 0.01);
            setViewPitch(viewPitchRad + dy * 0.005);
            lastDragX = e.getX();
            lastDragY = e.getY();
        } else {
            updateScaleRulerDrag(e.getX(), e.getY());
        }
        repaint();
    }

    /** Mouse-drag view-orbit state (see {@link #mouseDragged}). */
    private boolean draggingView = false;
    private int lastDragY = 0;
    private int lastDragX = 0;

    @Override public void mouseMoved(MouseEvent e) {}

    /* ==============================
       RENDERING
       ============================== */
    // ----- The real sky: Yale Bright Star Catalogue + Milky Way, fixed at infinity -----
    /** Draw the real star field (and Milky Way) behind the system; off = plain deep-space gradient. */
    boolean showRealStars = true;
    /** Constellation stick figures on the background sky (View menu). */
    boolean showConstellations = false;
    /** Star directions in world axes for {@link #starEpochYears} (proper motion applied). */
    private double[] starWX, starWY, starWZ;
    private double starEpochYears = Double.NaN;
    /** Low-resolution Milky Way backdrop, re-rendered only when the view orientation/size changes. */
    private BufferedImage mwBackdrop;
    private String mwBackdropKey = "";

    /** Faintest magnitude drawn in the 3-D view's background sky. */
    private static final double SKY_LIMIT_MAG = 6.2;

    /** Refresh the cached star directions when the date has moved by more than a year. */
    private void ensureStarDirections(StarCatalog cat) {
        double years = simTimeSec / (365.25 * DAY);
        if (starWX != null && Math.abs(years - starEpochYears) < 1.0) return;
        starEpochYears = years;
        starWX = new double[cat.n]; starWY = new double[cat.n]; starWZ = new double[cat.n];
        for (int i = 0; i < cat.n; i++) {
            double[] d = cat.directionAt(i, years);
            double[] w = eclToWorld(d[0], d[1], d[2]);
            starWX[i] = w[0]; starWY[i] = w[1]; starWZ[i] = w[2];
        }
    }

    /**
     * Focal length (px) for points at infinity: the same perspective the bodies get, so stars
     * stay fixed relative to the scene's directions as the camera turns.
     */
    private double skyFocalPx() { return METERS_TO_PIXELS * currentZoom * cameraDistanceMeters(); }

    /**
     * Deep-space background with the real sky: the Milky Way and the Yale Bright Star Catalogue
     * (every naked-eye star, at its true direction, brightness and colour), seen through the
     * current camera. Stars are infinitely far away, so they turn with the view but never
     * parallax as the camera moves through the system.
     */
    private void drawStarfield(Graphics2D g2, int w, int h) {
        g2.setPaint(new GradientPaint(0, 0, new Color(6, 8, 16), 0, h, new Color(3, 4, 10)));
        g2.fillRect(0, 0, w, h);
        StarCatalog cat = showRealStars ? StarCatalog.get() : null;
        if (cat == null) return;
        ensureStarDirections(cat);
        double f = skyFocalPx(), cx = w / 2.0, cy = h / 2.0;
        drawMilkyWayBackdrop(g2, w, h, f, cat);

        Object oldAA = g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (showConstellations) {
            g2.setStroke(new BasicStroke(1f));
            g2.setColor(new Color(90, 130, 200, 70));
            for (StarCatalog.Figure fig : cat.figures) {
                Path2D.Double path = new Path2D.Double();
                boolean pen = false;
                for (double[] d : fig.points()) {
                    double[] wv = eclToWorld(d[0], d[1], d[2]);
                    double[] s = skyToScreen(wv[0], wv[1], wv[2], f, cx, cy);
                    if (s == null) { pen = false; continue; }
                    if (!pen) { path.moveTo(s[0], s[1]); pen = true; } else path.lineTo(s[0], s[1]);
                }
                g2.draw(path);
            }
        }
        for (int i = 0; i < cat.n; i++) {
            if (cat.vmag[i] > SKY_LIMIT_MAG + 0.5) continue;
            double[] s = skyToScreen(starWX[i], starWY[i], starWZ[i], f, cx, cy);
            if (s == null || s[0] < -4 || s[1] < -4 || s[0] > w + 4 || s[1] > h + 4) continue;
            double[] sp = StarCatalog.pointSprite(cat.vmag[i], SKY_LIMIT_MAG);
            if (sp[1] < 0.03) continue;
            int rgb = cat.rgb[i];
            g2.setColor(new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, (int) (255 * sp[1])));
            double r = sp[0];
            if (r < 0.9) g2.fill(new Rectangle2D.Double(s[0] - 0.6, s[1] - 0.6, 1.2, 1.2));
            else g2.fill(new Ellipse2D.Double(s[0] - r, s[1] - r, 2 * r, 2 * r));
        }
        if (oldAA != null) g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA);
    }

    /** Screen position of a direction at infinity (world axes), or null if it's behind the camera. */
    private double[] skyToScreen(double wx, double wy, double wz, double f, double cx, double cy) {
        double xv = -(wx * viewCosY - wy * viewSinY);       // screen right (see projX)
        double yr = wx * viewSinY + wy * viewCosY;
        double zv = yr * viewSinP + wz * viewCosP;          // toward the viewer
        if (zv > -1e-3) return null;
        double sd = yr * viewCosP - wz * viewSinP;
        return new double[]{ cx + f * xv / -zv, cy + f * sd / -zv };
    }

    /**
     * The Milky Way, rendered per pixel from the brightness map at quarter resolution and
     * smoothed up — cached, so it costs nothing until the view turns or the window resizes.
     */
    private void drawMilkyWayBackdrop(Graphics2D g2, int w, int h, double f, StarCatalog cat) {
        int bw = Math.max(1, w / 4), bh = Math.max(1, h / 4);
        String key = bw + "x" + bh + ":" + (float) viewYawRad + ":" + (float) viewPitchRad + ":" + (float) f;
        if (!key.equals(mwBackdropKey) || mwBackdrop == null) {
            mwBackdropKey = key;
            mwBackdrop = new BufferedImage(bw, bh, BufferedImage.TYPE_INT_ARGB);
            double fs = f / 4.0, cx = bw / 2.0, cy = bh / 2.0;
            int[] px = new int[bw * bh];
            for (int y = 0; y < bh; y++) {
                for (int x = 0; x < bw; x++) {
                    // View ray (xv, sd, zv = −focal) → world direction (inverse of skyToScreen's rotation).
                    double xv = -(x + 0.5 - cx), sd = y + 0.5 - cy, zv = -fs;   // un-flip screen x (see skyToScreen)
                    double yr = sd * viewCosP + zv * viewSinP;
                    double wz = -sd * viewSinP + zv * viewCosP;
                    double wx = xv * viewCosY + yr * viewSinY;
                    double wy = -xv * viewSinY + yr * viewCosY;
                    double n = Math.sqrt(wx * wx + wy * wy + wz * wz);
                    double[] q = Astro.eclToEq(-wy / n, wx / n, wz / n);     // world {ye,−xe,ze} → ecliptic → ICRF
                    float b = cat.milkyWay(q[0], q[1], q[2]);
                    int a = (int) (95 * Math.pow(b, 0.85));
                    px[y * bw + x] = (a << 24) | (178 << 16) | (192 << 8) | 228;
                }
            }
            mwBackdrop.setRGB(0, 0, bw, bh, px, 0, bw);
        }
        Object oldI = g2.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.drawImage(mwBackdrop, 0, 0, w, h, null);
        if (oldI != null) g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldI);
    }

    private static int clamp255(int v) { return v < 0 ? 0 : v > 255 ? 255 : v; }

    /**
     * Makes the Sun read as a glowing body rather than a disc pasted on a glow. Two layers go on
     * top of the drawn disc: (1) limb darkening — the real photosphere's edge is seen through
     * cooler, higher gas, so it is dimmer and redder (about 40% of the centre brightness in visible
     * light); (2) a bloom that starts inside the limb and fades out past it, the way a camera or
     * eye spreads a very bright source, so the disc edge dissolves into the surrounding corona.
     */
    private void drawSunLimbAndBloom(Graphics2D g2, double cx, double cy, double r) {
        Paint old = g2.getPaint();
        // Limb darkening: transparent over the middle of the disc, warm shadow toward the edge.
        g2.setPaint(new RadialGradientPaint(new Point2D.Double(cx, cy), (float) r,
                new float[]{0f, 0.55f, 0.8f, 0.93f, 1f},
                new Color[]{new Color(120, 40, 0, 0), new Color(120, 40, 0, 0), new Color(130, 45, 0, 45),
                            new Color(140, 50, 5, 95), new Color(150, 60, 10, 130)}));
        g2.fill(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
        // Bloom across the limb.
        double br = Math.max(r * 1.9, r + 22);
        float in = (float) (0.86 * r / br), edge = (float) (r / br);
        float out1 = edge + (1 - edge) * 0.18f, out2 = edge + (1 - edge) * 0.5f;
        g2.setPaint(new RadialGradientPaint(new Point2D.Double(cx, cy), (float) br,
                new float[]{0f, in, edge, out1, out2, 1f},
                new Color[]{new Color(255, 236, 190, 0), new Color(255, 236, 190, 0), new Color(255, 226, 170, 150),
                            new Color(255, 205, 130, 85), new Color(255, 170, 80, 25), new Color(255, 150, 60, 0)}));
        g2.fill(new Ellipse2D.Double(cx - br, cy - br, 2 * br, 2 * br));
        g2.setPaint(old);
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
        pendingLabels.clear();   // collected during this paint, placed non-overlapping at the end

        // Google-Earth-style surface explorer takes over the whole canvas when active.
        if (earthExplorerActive) { drawEarthExplorer(g2); return; }
        // The planetarium likewise takes over the canvas.
        if (skyViewActive) { skyView().paint(g2, getWidth(), getHeight()); return; }

        // Deep-space background + starfield
        drawStarfield(g2, getWidth(), getHeight());

        // Draw Sun at origin in world coordinates, offset by barycenter
        double sunWorldX = sunBarycenterX;  // Apply barycenter offset for Sun motion
        double sunWorldY = sunBarycenterY;  // Apply barycenter offset for Sun motion
        double sunR = SUN_RADIUS_M * perspScaleAt(sunWorldX, sunWorldY, 0);
        boolean sunVisible = inFront(sunWorldX, sunWorldY, 0);   // behind the camera when zoomed onto a day-side
        double sunCX = projX(sunWorldX, sunWorldY, 0);
        double sunCY = projY(sunWorldX, sunWorldY, 0);
        double sunXpix = sunCX - sunR;
        double sunYpix = sunCY - sunR;

        // Broad outer halo behind the Sun: a smooth, roughly 1/r-like falloff (many stops, so no
        // visible ring) that reaches well past the disc.
        double glowR = Math.max(sunR * 5.0, sunR + 60);
        if (sunVisible) {
            float f1 = (float) (sunR / glowR);
            RadialGradientPaint sunGlow = new RadialGradientPaint(
                    new Point2D.Double(sunCX, sunCY), (float) glowR,
                    new float[]{0f, f1, f1 + (1 - f1) * 0.12f, f1 + (1 - f1) * 0.35f, f1 + (1 - f1) * 0.65f, 1f},
                    new Color[]{new Color(255, 214, 140, 200), new Color(255, 200, 120, 170),
                                new Color(255, 170, 80, 95), new Color(255, 150, 60, 38),
                                new Color(255, 140, 50, 12), new Color(255, 140, 40, 0)});
            Paint oldGlow = g2.getPaint();
            g2.setPaint(sunGlow);
            g2.fill(new Ellipse2D.Double(sunCX - glowR, sunCY - glowR, 2 * glowR, 2 * glowR));
            g2.setPaint(oldGlow);
        }

        // Sun body: textured (emissive, slow equatorial spin) when a map exists,
        // otherwise a hot-core radial gradient.
        Paint oldPaint = g2.getPaint();
        boolean sunTextured = sunVisible && sunR > 2 && drawTexturedSphere(g2, "sun", sunCX, sunCY, sunR,
                obliquityPole(0.0), spinAngle(SUN_ROTATION_H), true, 0, 0, 1);   // emissive: light direction unused
        if (sunVisible && !sunTextured) {
            g2.setPaint(new RadialGradientPaint(
                    new Point2D.Double(sunCX - sunR * 0.25, sunCY - sunR * 0.25), (float) Math.max(1.0, sunR),
                    new float[]{0f, 0.6f, 1f},
                    new Color[]{new Color(255, 248, 220), new Color(255, 190, 70), new Color(240, 140, 30)}));
            g2.fill(new Ellipse2D.Double(sunXpix, sunYpix, 2*sunR, 2*sunR));
            g2.setPaint(oldPaint);
        }
        if (sunVisible && sunR > 1.5) drawSunLimbAndBloom(g2, sunCX, sunCY, sunR);

        // Draw asteroid belt and Kuiper belt as rings
        drawAsteroidBeltRing(g2, cx, cy);
        drawKuiperBeltRing(g2, cx, cy);

        // Draw ice lines
        drawIceLines(g2, cx, cy);

        // Draw planets and moons, far side first so nearer bodies occlude farther ones
        // (painter's algorithm) — the depth cue that makes the tilted view read as 3-D.
        Integer[] drawOrder = new Integer[NAMES.length];
        for (int k = 0; k < drawOrder.length; k++) drawOrder[k] = k;
        Arrays.sort(drawOrder, (p, q) -> Double.compare(
                viewDepth(planetX[p] + sunBarycenterX, planetY[p] + sunBarycenterY, planetZ[p]),
                viewDepth(planetX[q] + sunBarycenterX, planetY[q] + sunBarycenterY, planetZ[q])));
        for (int oi = 0; oi < drawOrder.length; oi++) {
            int i = drawOrder[oi];
            if (isPlanetVisible(i)) continue;

            // Planet positions are heliocentric, so add Sun's barycentric offset
            double worldX = planetX[i] + sunBarycenterX;
            double worldY = planetY[i] + sunBarycenterY;
            double worldZ = planetZ[i];
            if (!inFront(worldX, worldY, worldZ)) continue;   // behind the camera (zoomed in elsewhere)
            double px = projX(worldX, worldY, worldZ);
            double py = projY(worldX, worldY, worldZ);
            double pr = RADIUS_M[i] * perspScaleAt(worldX, worldY, worldZ);   // true physical scale under perspective
            // In a lesson the planets are the demonstration, so never let one shrink below a few
            // pixels (at orbit scale a true-size planet is far smaller than a pixel).
            if (lessonActive()) pr = Math.max(pr, i == EARTH_INDEX ? 6.0 : 5.0);
            // Sun→planet light: exact view-frame direction for the sphere/eclipse geometry,
            // and a screen-space direction for the flat atmosphere-limb glow.
            double[] pL = viewLight(worldX, worldY, worldZ);
            double[] pLightDir = worldDirToScreen(sunBarycenterX - worldX, sunBarycenterY - worldY, -worldZ);
            double pLightDx = pLightDir[0], pLightDy = pLightDir[1];
            // Sun's angular radius (radians) as seen from this planet, from its true heliocentric
            // distance. This is what sizes an eclipse: the Moon's umbra shrinks to a thin track
            // because the Moon and Sun subtend almost the same angle from Earth.
            double distToSun = Math.sqrt(planetX[i] * planetX[i]
                    + planetY[i] * planetY[i] + planetZ[i] * planetZ[i]);
            double sunAngR = distToSun > 1e-3 ? SUN_RADIUS_M / distToSun : 0;

            // Draw orbit (respect visibility toggle). drawOrbitRing culls by the ring's true
            // projected bounding box and returns whether it is on screen, so a tilted/zoomed-in
            // orbit stays visible instead of being wrongly hidden.
            if (showPlanetOrbits && drawOrbitRing(g2, i)) {
                // Perihelion (E=0) and aphelion (E=π) of the current inclined ellipse,
                // projected through the same 3-D transform as the orbit ring.
                if (showPerihelionAphelion) {
                    {
                        double[] periW = planetOrbitPoint(i, 0.0);
                        double[] apoW = planetOrbitPoint(i, Math.PI);
                        double periX = projX(periW[0] + sunBarycenterX, periW[1] + sunBarycenterY, periW[2]);
                        double periY = projY(periW[0] + sunBarycenterX, periW[1] + sunBarycenterY, periW[2]);
                        double apoX = projX(apoW[0] + sunBarycenterX, apoW[1] + sunBarycenterY, apoW[2]);
                        double apoY = projY(apoW[0] + sunBarycenterX, apoW[1] + sunBarycenterY, apoW[2]);

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
                double lpx = pL[0], lpy = pL[1], lpz = pL[2];   // view-frame Sun direction
                // worldToView is a proper rotation, so the occluder is used exactly where the
                // ephemeris puts it: the umbra is a pure ray-trace of the real lunar ephemeris.
                for (int m = 0; m < nm; m++) {
                    double[] rel = moonRelPos(i, m);
                    double[] v = worldToView(rel[0], rel[1], rel[2]);   // moon offset in the view frame
                    double[] occM = { v[0] * invR, v[1] * invR, v[2] * invR, 0 };
                    double mx = occM[0], my = occM[1], mz = occM[2];
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
                    textured = drawSaturn(g2, px, py, pr, planetSpin(i),
                            !shadingEnabled, showRings, pL[0], pL[1], pL[2]);
                } else {
                    // Spin anchored to the body's real orientation: Earth is phase-locked to the
                    // wall clock, the other planets to their IAU prime-meridian model, so the
                    // meridian facing the Sun is the one truly at local noon for the current date.
                    double spin = planetSpin(i);
                    textured = drawTexturedSphere(g2, texKey, px, py, pr,
                            planetPole(i), spin,
                            !shadingEnabled, pL[0], pL[1], pL[2], pOcc, pOccN, sunAngR);
                }
            }
            if (!textured) {
                // Untextured bodies (dwarf/minor planets) still get a true 3-D ray-traced
                // terminator + eclipse shadows by pushing a flat-colour sphere through the SAME
                // shader the mapped planets and moons use (see the moon path above). The shadow
                // is derived from the view-frame Sun direction and per-pixel surface normals, so
                // it stays correct under any camera yaw/pitch, spin rate or orbital motion —
                // unlike the old screen-space gradient, which skewed when the camera rotated.
                boolean flatShaded = false;
                if (shadingEnabled && pr > 2) {
                    String flatKey = ensureFlatTexture(COLORS[i].getRGB());
                    flatShaded = drawTexturedSphere(g2, flatKey, px, py, pr,
                            planetPole(i), planetSpin(i),
                            false, pL[0], pL[1], pL[2], pOcc, pOccN, sunAngR);
                }
                if (!flatShaded) {
                    g2.setColor(COLORS[i]);
                    g2.fill(planetEllipse);
                }
            }

            // Atmospheric limb glow, layered on top of the body so it haloes the rim.
            if (showAtmosphere && shadingEnabled && pr > 2
                    && i < ATMO_RGB.length && ATMO_RGB[i] != null) {
                drawAtmosphere(g2, px, py, pr, pLightDx, pLightDy,
                        ATMO_RGB[i], ATMO_STRENGTH[i], ATMO_HEIGHT[i]);
            }

            // Light-time view: a hollow marker where the body really is now, joined to where it
            // is seen — the gap is how far it moved while its light was on the way to Earth.
            if (apparentPositions && i != EARTH_INDEX) {
                double tx = trueX[i] + sunBarycenterX, ty = trueY[i] + sunBarycenterY, tz = trueZ[i];
                if (inFront(tx, ty, tz)) {
                    double gx = projX(tx, ty, tz), gy = projY(tx, ty, tz);
                    double gr = Math.max(pr, 3.5);
                    g2.setColor(new Color(150, 220, 255, 150));
                    g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{3f, 3f}, 0f));
                    if (Math.hypot(gx - px, gy - py) > gr + pr) g2.draw(new java.awt.geom.Line2D.Double(px, py, gx, gy));
                    g2.setStroke(new BasicStroke(1f));
                    g2.draw(new Ellipse2D.Double(gx - gr, gy - gr, 2 * gr, 2 * gr));
                }
            }

            // Queue the name for the non-overlapping label pass. Major planets outrank dwarf
            // planets, and the selected/followed body outranks everything, so crowded views drop
            // the least important labels rather than letting them collide.
            if (showLabels) {
                int pri = (i < 8) ? 80 : 62;
                if (i == selectedPlanet || i == cameraFocusIndex) pri += 40;
                float labelSize = (float)(11f + Math.min(3f, currentZoom * 0.5f));
                String label = NAMES[i];
                if (apparentPositions && i != EARTH_INDEX) label += "  · seen " + formatDuration(lightTimeSec[i]) + " ago";
                pendingLabels.add(new LabelReq(label, px, py, pr, Color.WHITE,
                        labelSize, i < 8, pri, viewDepth(worldX, worldY, worldZ)));
            }


            // Draw moons (same as before)
            if (MOON_NAMES[i].length > 0) {
                // Moon orbit rings: trace the SAME 3-D inclined orbit the moon body follows, so the
                // moon sits exactly on its ring and the ring shows its real orbital tilt.
                if (currentZoom > ORBIT_VISIBLE_ZOOM) for (int m = 0; m < MOON_A_M[i].length; m++) {
                    int seg = orbitSegments(MOON_A_M[i][m]);
                    Path2D.Double mpath = new Path2D.Double();
                    boolean pen = false;
                    double periodSec = MOON_PERIOD_DAYS[i][m] * DAY;
                    for (int j = 0; j <= seg; j++) {
                        // Trace the moon's real path over one orbit centred on now, so the ring
                        // and the ephemeris-driven body coincide (and show any precession).
                        double[] off = moonOffsetWorld(i, m, simTimeSec + (j / (double) seg - 0.5) * periodSec);
                        double wx = worldX + off[0], wy = worldY + off[1], wz = worldZ + off[2];
                        if (!inFront(wx, wy, wz)) { pen = false; continue; }   // clip behind-camera arc
                        double x = projX(wx, wy, wz), y = projY(wx, wy, wz);
                        if (!pen) { mpath.moveTo(x, y); pen = true; } else mpath.lineTo(x, y);
                    }
                    g2.setColor(new Color(100, 150, 100, 80));  // Green-tinted for moon orbits
                    g2.setStroke(new BasicStroke(0.8f));
                    g2.draw(mpath);
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
                        if (!inFront(moonWorldX, moonWorldY, moonWorldZ)) continue;   // moon behind the camera

                        double mpx = projX(moonWorldX, moonWorldY, moonWorldZ);
                        double mpy = projY(moonWorldX, moonWorldY, moonWorldZ);
                        double mr = MOON_RADIUS_M[i][m] * perspScaleAt(moonWorldX, moonWorldY, moonWorldZ);   // true physical scale under perspective
                        double[] mL = viewLight(moonWorldX, moonWorldY, moonWorldZ);   // exact view-frame Sun direction

                        // Depth cue: a moon on the far side of the planet, projected inside its
                        // disc, is hidden behind it — skip it rather than drawing it on top.
                        double moonDepth = viewDepth(moonWorldX, moonWorldY, moonWorldZ);
                        double planetDepth = viewDepth(worldX, worldY, worldZ);
                        if (moonDepth < planetDepth && Math.hypot(mpx - px, mpy - py) < pr - mr) {
                            continue;
                        }

                        // Ray-traced lit sphere for every moon: a real surface map when one exists
                        // (Luna), otherwise a flat-colour sphere pushed through the SAME shader, so
                        // untextured moons still get a correct 3-D terminator and true ray-traced
                        // eclipse shadows (fully black in the parent planet's umbra) rather than the
                        // old flat half-black gradient.
                        String moonKey = MOON_NAMES[i][m].toLowerCase();
                        boolean moonDrawn = false;
                        if (mr > 2) {
                            String key = hasTexture(moonKey) ? moonKey : ensureFlatTexture(MOON_FLAT_RGB);
                            // Parent planet occludes the Sun -> shadow falls on the moon
                            // (a lunar eclipse when the moon crosses the planet's shadow).
                            // Occluder = the planet, at offset -rel from the moon, in the view frame,
                            // scaled to moon-radius units.
                            double ratio = MOON_SIZE_RATIO[i][m];
                            double invMr = 1.0 / (RADIUS_M[i] * ratio);
                            double[] mOcc = null;
                            if (showEclipses && shadingEnabled) {
                                double[] pv = worldToView(-rx, -ry, -rz);   // planet relative to the moon, view frame
                                double orad = 1.0 / ratio;
                                double[] occP = { pv[0] * invMr, pv[1] * invMr, pv[2] * invMr, orad };
                                double ocx = occP[0], ocy = occP[1], ocz = occP[2];
                                double al = ocx * mL[0] + ocy * mL[1] + ocz * mL[2];
                                double pp = ocx * ocx + ocy * ocy + ocz * ocz - al * al;
                                if (al > 0 && pp <= (orad + 1) * (orad + 1)) {   // Moon actually in the umbra
                                    mOcc = new double[]{ocx, ocy, ocz, orad};
                                }
                            }
                            boolean luna = i == EARTH_INDEX && m == 0;
                            moonDrawn = drawTexturedSphere(g2, key, mpx, mpy, mr,
                                    luna ? lunaPole() : obliquityPole(MOON_OBLIQUITY_DEG),
                                    luna ? lunaSpin(moonWorldX, moonWorldY, moonWorldZ) : spinAngle(MOON_ROTATION_H),
                                    !shadingEnabled, mL[0], mL[1], mL[2],
                                    mOcc, mOcc != null ? 1 : 0, sunAngR);
                        }
                        if (!moonDrawn) {   // sub-pixel moon: a simple dot
                            g2.setColor(Color.LIGHT_GRAY);
                            g2.fill(new Ellipse2D.Double(mpx - mr, mpy - mr, 2*mr, 2*mr));
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

        // Draw user-added comets / asteroids (with anti-sunward tails for comets)
        if (!smallBodies.isEmpty()) {
            drawSmallBodies(g2);
        }

        // Draw velocity vectors for planets (educational feature)
        if (showVelocityVectors) {
            drawVelocityVectors(g2, cx, cy);
        }

        // Spacecraft: trails and markers of the playing mission (and of any craft in flight),
        // and the planner's transfer arc.
        if (!lessonActive()) {
            missionPlayer.paintScene(g2);
            if (transferPlanner != null && transferPlanner.isVisible()) transferPlanner.paintScene(g2);
        }

        // Place all collected body labels in one non-overlapping pass (over every body).
        drawPendingLabels(g2);

        // During a lesson its annotations and readouts take the place of the sim's own HUD panels.
        if (lessonActive()) {
            lessonStudio.paintOverlay(g2, getWidth(), getHeight());
            drawDynamicScale(g2, getHeight());
            return;
        }

        // Draw dynamic scale ruler at bottom-left
        drawDynamicScale(g2, getHeight());

        // Modern HUD (top-left status panel)
        drawHud(g2);

        // Optional Sun info panel (toggled with U)
        if (showSunInfo) drawSunInfo(g2);

        // A playing mission's panel takes the right-hand side.
        if (missionPlayer.isActive()) { missionPlayer.paintOverlay(g2); return; }

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
            int baseLines = 12;  // Header + Name + Radius + Distance + Speed + a/e + Peri/Apo + Period + Orbit progress + Central mass + Moons count
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
            if (i != EARTH_INDEX) {
                g2.drawString("Light-time to Earth: " + formatDuration(lightTimeSec[i]), colX, lineY); lineY += lineStep;
            }

            // Osculating orbit: the ellipse the planet is on right now (it slowly changes as
            // the other planets pull on it).
            double a_m = oscA[i];
            double e = oscE[i];
            double periodYears = oscPeriodY[i];
            double periodDays = periodYears * 365.25;
            double peri_m = a_m * (1.0 - e);
            double apo_m  = a_m * (1.0 + e);

            double M = oscM[i];
            double frac = (M % (2 * Math.PI)) / (2 * Math.PI);
            if (frac < 0) frac += 1.0;
            double pct = frac * 100.0;

            g2.drawString(String.format("a: %.3f AU  e: %.3f", a_m / AU, e), colX, lineY); lineY += lineStep;
            g2.drawString(String.format("Peri: %.3f AU  Apo: %.3f AU", peri_m / AU, apo_m / AU), colX, lineY); lineY += lineStep;
            g2.drawString(String.format("Period: %.1f d (%.2f y)", periodDays, periodYears), colX, lineY); lineY += lineStep;

            // Kepler's 3rd Law overlay (Education > Overlays) with the worked calculation
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

                // Step 3: Show the ratio calculation with highlight
                int boxHeight = (int)(lineStep * 1.3);
                {
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
                double eMoon = moonEcc(i, m);
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
        String local = simLocalDateString();
        rows.add(new String[]{"Date", local != null ? local : simDateString()});
        if (local != null) rows.add(new String[]{"UTC", simDateString()});
        rows.add(new String[]{"Elapsed", String.format("%.2f yr", simTimeSec / (365.25 * DAY))});
        rows.add(new String[]{"Positions", apparentPositions ? "as seen from Earth  (T)" : "true  (T)"});
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

        double sunScreenX = projX(sunBarycenterX, sunBarycenterY, 0);
        double sunScreenY = projY(sunBarycenterX, sunBarycenterY, 0);

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
        double sunScreenX = projX(sunBarycenterX, sunBarycenterY, 0);
        double sunScreenY = projY(sunBarycenterX, sunBarycenterY, 0);

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

    /** Build planet {@code i}'s orbit as a projected screen path from its current osculating
     *  3-D ellipse (the orbit it is on right now), so the ring passes exactly through the
     *  planet, shows the real inclination, and tilts with the view. */
    private boolean drawOrbitRing(Graphics2D g2, int i) {
        double a = oscA[i];
        int seg = orbitSegments(a);
        double minX = 1e18, minY = 1e18, maxX = -1e18, maxY = -1e18;
        // Build the ring as a projected polyline, lifting the pen across any arc that falls
        // behind the camera (which happens once you dolly inside the orbit) so it never wraps
        // to a garbage line. Bounding box is taken over the visible arc only.
        Path2D.Double path = new Path2D.Double();
        boolean pen = false, any = false;
        for (int j = 0; j <= seg; j++) {
            double E = 2 * Math.PI * j / seg;
            double[] w = planetOrbitPoint(i, E);                 // {wx, wy, wz} heliocentric
            double wx = w[0] + sunBarycenterX, wy = w[1] + sunBarycenterY, wz = w[2];
            if (!inFront(wx, wy, wz)) { pen = false; continue; }
            double sx = projX(wx, wy, wz), sy = projY(wx, wy, wz);
            if (sx < minX) minX = sx; if (sx > maxX) maxX = sx;
            if (sy < minY) minY = sy; if (sy > maxY) maxY = sy;
            if (!pen) { path.moveTo(sx, sy); pen = true; } else path.lineTo(sx, sy);
            any = true;
        }
        if (!any) return false;
        // Cull by the ring's TRUE projected bounding box (tilt/yaw-aware), so a foreshortened,
        // zoomed-in orbit is not wrongly hidden — the bug the old Sun-radius test had under tilt.
        double m = 48;
        if (maxX < -m || minX > getWidth() + m || maxY < -m || minY > getHeight() + m) return false;

        g2.setStroke(new BasicStroke(1f));
        g2.setColor(getOrbitColor(i));
        g2.draw(path);
        return true;
    }

    /**
     * Number of straight segments to approximate an orbit of semi-major axis {@code aMeters}
     * so the polyline stays visually smooth <em>at the current zoom</em>. A fixed segment count
     * (the old 512) is fine zoomed out, but when zoomed in on a body the chords of a coarse
     * polygon bow visibly inside the true ellipse — the chord sagitta grows with on-screen
     * radius — so the planet, which is drawn at its exact position, appears to sit off its own
     * ring and to "oscillate" toward and away from it as it crosses successive chords. Scaling
     * the count with the on-screen size keeps the chord error sub-pixel: sagitta ≈ R·(π/seg)²/2,
     * so seg ≈ π·√(R_px / targetSagitta). Clamped so it is cheap zoomed out and bounded when
     * zoomed extremely far in (only on-screen orbits are ever tessellated, see the cull in paint).
     */
    private int orbitSegments(double aMeters) {
        double rPx = aMeters * METERS_TO_PIXELS * currentZoom;   // on-screen semi-major axis (px)
        int seg = (int) Math.ceil(Math.PI * Math.sqrt(Math.max(1.0, rPx) / 0.3));
        return Math.max(256, Math.min(seg, 16384));
    }



    // ------- Real-date planetarium mapping (simTimeSec = 0 is the J2000.0 epoch) -------

    /** J2000.0 = 2000-01-01 12:00 TT, treated as UTC here (the ~64 s offset is irrelevant visually). */
    private static final java.time.Instant J2000_INSTANT =
            java.time.LocalDateTime.of(2000, 1, 1, 12, 0, 0).toInstant(java.time.ZoneOffset.UTC);
    private static final java.time.format.DateTimeFormatter SIM_DATE_FMT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd  HH:mm");

    /** The simulated calendar instant for the current elapsed sim time (UTC), or null if out of range. */
    private java.time.LocalDateTime simDateTime() {
        try {
            return java.time.LocalDateTime.ofInstant(
                    J2000_INSTANT.plusSeconds((long) simTimeSec), java.time.ZoneOffset.UTC);
        } catch (Exception e) {
            return null;   // beyond the representable range at extreme time offsets
        }
    }

    /** Human-readable simulated date (UTC), or an elapsed-years fallback at extreme offsets. */
    private String simDateString() {
        java.time.LocalDateTime dt = simDateTime();
        if (dt == null) return String.format("J2000 %+.0f yr", simTimeSec / (365.25 * DAY));
        return dt.format(SIM_DATE_FMT);
    }

    /**
     * Time zone that dates are typed and shown in: the computer's own zone, like a desk clock
     * (and like Stellarium). The sim clock itself is UTC; this is only the human-facing label.
     */
    private final java.time.ZoneId displayZone = java.time.ZoneId.systemDefault();

    /** The sim instant in {@link #displayZone}, e.g. "2026-10-02  20:01 EDT", or null if out of range. */
    private String simLocalDateString() {
        try {
            java.time.ZonedDateTime z = J2000_INSTANT.plusSeconds((long) simTimeSec).atZone(displayZone);
            return z.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd  HH:mm zzz"));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * A plain-text snapshot of the live simulation state, injected into the AI
     * assistant's prompt so it can answer questions like "what planet am I
     * looking at?" or "how fast is time running?" accurately.
     */
    String aiContextSnapshot() {
        StringBuilder b = new StringBuilder(512);
        b.append("Simulated date/time (UTC): ").append(simDateString()).append('\n');
        String local = simLocalDateString();
        if (local != null) b.append("Same instant in the user's time zone (").append(displayZone).append("): ").append(local).append('\n');
        b.append("Elapsed since J2000 epoch: ")
         .append(String.format("%.2f years", simTimeSec / (365.25 * DAY))).append('\n');
        b.append("Time speed: ").append(formatTimeScale(timeScale))
         .append(simulationPaused ? " (PAUSED)" : " (running)").append('\n');

        String selName = (selectedPlanet >= 0 && selectedPlanet < NAMES.length)
                ? NAMES[selectedPlanet] : "none";
        b.append("Selected body: ").append(selName).append('\n');

        // Which planet's moon system is active (the one being followed, else selected).
        int moonParent = (cameraFocusIndex >= 0) ? cameraFocusIndex : selectedPlanet;
        if (selectedMoon >= 0 && moonParent >= 0 && moonParent < MOON_NAMES.length
                && selectedMoon < MOON_NAMES[moonParent].length) {
            b.append("Selected moon: ").append(MOON_NAMES[moonParent][selectedMoon])
             .append(" (of ").append(NAMES[moonParent]).append(")\n");
        }

        if (followingMoon && moonParent >= 0 && cameraFocusMoon >= 0
                && cameraFocusMoon < MOON_NAMES[moonParent].length) {
            b.append("Camera is following: moon ")
             .append(MOON_NAMES[moonParent][cameraFocusMoon])
             .append(" of ").append(NAMES[moonParent]).append('\n');
        } else if (followingPlanet && cameraFocusIndex >= 0 && cameraFocusIndex < NAMES.length) {
            b.append("Camera is following: ").append(NAMES[cameraFocusIndex]).append('\n');
        } else {
            b.append("Camera is following: nothing (free / whole-system view)\n");
        }

        b.append("Zoom mode: ")
         .append(switch (zoomMode) { case 0 -> "Planet"; case 1 -> "Orbit"; default -> "Manual"; })
         .append("; auto-zoom ").append(autoZoomEnabled ? "on" : "off").append('\n');
        b.append("Planet orbits shown: ").append(showPlanetOrbits ? "yes" : "no")
         .append("; labels shown: ").append(showLabels ? "yes" : "no")
         .append("; barycentric (Sun-wobble) frame: ").append(barycentricFrame ? "on" : "off")
         .append('\n');
        return b.toString();
    }

    /**
     * Jump the whole simulation to a real calendar date/time: sets sim time and places every
     * body at the state it has then — planets, dwarf planets and minor bodies from the N-body
     * integration, Earth's Moon from {@link MoonEphemeris}, other moons from
     * {@link SatelliteEphemeris}. Works while paused, so it acts as a date scrubber.
     */
    void goToDate(java.time.LocalDateTime dt) {
        long secs = java.time.Duration.between(J2000_INSTANT,
                dt.toInstant(java.time.ZoneOffset.UTC)).getSeconds();
        goToSimTime(secs);
    }

    /** Place every body at its state {@code secs} seconds after J2000 (see {@link #goToDate}). */
    void goToSimTime(double secs) {
        simTimeSec = secs;
        // A jump of centuries means integrating that far from the nearest cached checkpoint
        // (~0.4 s per century the first time); show a busy cursor meanwhile.
        Cursor old = getCursor();
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        try { updateEphemeris(); } finally { setCursor(old); }
        updateTimeScaleLabel();
        repaint();
    }

    // ------- Ephemeris: integrated heliocentric states (see NBodyEphemeris) -------

    /** GM(Moon) / GM(Earth + Moon), DE440: how far Earth's centre sits from the Earth–Moon barycentre. */
    private static final double MOON_MASS_FRACTION = 4902.800118 / 403503.235502;
    private static final double AU_KM = AU / 1000.0;

    /** Ecliptic-J2000 vector (x → equinox, z → ecliptic north) to the sim's world axes {ye, −xe, ze}. */
    static double[] eclToWorld(double x, double y, double z) { return new double[]{ y, -x, z }; }

    /**
     * Offset (AU, ecliptic J2000) of planet {@code i}'s centre from its system barycentre — the
     * reflex of its moons (Earth: the Moon from the lunar theory) — or null if it has none.
     */
    private static double[] centreOffsetAU(int i, double simSec) {
        if (i == EARTH_INDEX) {
            double[] m = MoonEphemeris.geocentricEclipticAU(simSec);
            return new double[]{ -MOON_MASS_FRACTION * m[0], -MOON_MASS_FRACTION * m[1], -MOON_MASS_FRACTION * m[2] };
        }
        if (SatelliteEphemeris.count(i) == 0) return null;
        double[] o = SatelliteEphemeris.centreOffsetKm(i, Astro.ttSeconds(simSec));
        return new double[]{ o[0] / AU_KM, o[1] / AU_KM, o[2] / AU_KM };
    }

    /** Move body {@code i}'s state in {@code s} from its system barycentre to the planet's centre. */
    private static void toPlanetCentre(double[] s, int i, double simSec) {
        double[] o = centreOffsetAU(i, simSec);
        if (o == null) return;
        double[] a = centreOffsetAU(i, simSec - 60), b = centreOffsetAU(i, simSec + 60);
        for (int c = 0; c < 3; c++) {
            s[6 * i + c] += o[c];
            s[6 * i + 3 + c] += (b[c] - a[c]) / (120.0 / DAY);   // AU/day
        }
    }

    /**
     * Heliocentric ecliptic-J2000 states {x,y,z,vx,vy,vz} (AU, AU/day) of every integrated body
     * at UTC sim time {@code simSec} — planets at their own centres, minor bodies as integrated —
     * followed by the Sun's position relative to the Solar-System barycentre (3 more doubles, AU).
     */
    static double[] ephemerisStates(double simSec) {
        int n = NBodyEphemeris.N_BODIES;
        double[] s = new double[6 * n + 3];
        NBodyEphemeris.shared().heliocentric(Astro.ttSeconds(simSec), s);
        double[] sun = NBodyEphemeris.sunBarycentric(s);   // from the barycentres, before re-centring
        System.arraycopy(sun, 0, s, 6 * n, 3);
        for (int i = 0; i < NAMES.length; i++) toPlanetCentre(s, i, simSec);
        return s;
    }

    /** Heliocentric ecliptic-J2000 state (AU, AU/day) of planet {@code i}'s centre. */
    static double[] planetStateAU(int i, double simSec) {
        double[] s = new double[6 * NBodyEphemeris.N_BODIES];
        NBodyEphemeris.shared().heliocentric(Astro.ttSeconds(simSec), s);
        toPlanetCentre(s, i, simSec);
        return java.util.Arrays.copyOfRange(s, 6 * i, 6 * i + 6);
    }

    /**
     * Heliocentric ecliptic-J2000 position (AU) of planet {@code i}'s centre at elapsed (UTC) sim
     * time, as {x, y, z} with x toward the vernal equinox and z toward ecliptic north — the
     * frame JPL Horizons reports, so the accuracy tests compare it directly.
     */
    static double[] planetEclipticAU(int i, double simTimeSec) {
        double[] s = planetStateAU(i, simTimeSec);
        return new double[]{ s[0], s[1], s[2] };
    }

    /** Integrated heliocentric states at the current sim time (layout of {@link #ephemerisStates}). */
    private double[] ephem;

    /**
     * Light-time view: draw every body where it <em>appears</em> from Earth rather than where it
     * is. Light leaving Jupiter now reaches us ~35–52 minutes later, so what we see is Jupiter
     * (its moons, its rotation) as it was then. Off = true (geometric) positions.
     */
    boolean apparentPositions = false;
    /** One-way light-time (s) from each body to the Earth, refreshed every frame. */
    private final double[] lightTimeSec = new double[NAMES.length];
    /** True (geometric) world positions, kept so the apparent view can mark where bodies really are. */
    private final double[] trueX = new double[NAMES.length], trueY = new double[NAMES.length], trueZ = new double[NAMES.length];
    private static final double C_LIGHT = 299_792_458.0;

    /** The instant a body is seen at: now, or now minus its light-time in the apparent view. */
    private double bodyTime(int i) {
        return apparentPositions && i >= 0 && i < NAMES.length ? simTimeSec - lightTimeSec[i] : simTimeSec;
    }

    /**
     * Heliocentric position (AU) a body had {@code tau} days ago, from its state now: a
     * second-order Taylor step under the Sun's gravity. For light-times up to Pluto's ~5.5 h the
     * neglected jerk term is well under a kilometre.
     */
    private static double[] retarded(double[] s, int o, double tau) {
        double x = s[o], y = s[o + 1], z = s[o + 2];
        double r3 = Math.pow(x * x + y * y + z * z, 1.5);
        double k = 0.5 * tau * tau * NBodyEphemeris.GM_SUN / r3;
        return new double[]{x - s[o + 3] * tau - k * x, y - s[o + 4] * tau - k * y, z - s[o + 5] * tau - k * z};
    }

    /** Light-time (days) from a heliocentric point to the Earth's position now, iterated to convergence. */
    private static double lightDays(double[] s, int o, double ex, double ey, double ez) {
        double cAud = C_LIGHT * DAY / AU;
        double tau = 0;
        for (int it = 0; it < 3; it++) {
            double[] p = retarded(s, o, tau);
            tau = Math.sqrt((p[0] - ex) * (p[0] - ex) + (p[1] - ey) * (p[1] - ey) + (p[2] - ez) * (p[2] - ez)) / cAud;
        }
        return tau;
    }
    /** The Sun's offset from the Solar-System barycentre, world axes (m). */
    private double sunBaryWX, sunBaryWY;

    /**
     * Pull every body's state for the current sim time from the ephemerides: planet positions,
     * speeds and osculating elements, the minor bodies, and the Sun's barycentric offset.
     */
    void updateEphemeris() {
        double[] s = ephemerisStates(simTimeSec);
        ephem = s;
        double ex = s[12], ey = s[13], ez = s[14];
        for (int i = 0; i < NAMES.length; i++) {
            int o = 6 * i;
            double[] w = eclToWorld(s[o] * AU, s[o + 1] * AU, s[o + 2] * AU);
            trueX[i] = w[0]; trueY[i] = w[1]; trueZ[i] = w[2];
            double tau = i == EARTH_INDEX ? 0 : lightDays(s, o, ex, ey, ez);
            lightTimeSec[i] = tau * DAY;
            if (apparentPositions && tau > 0) {
                double[] r = retarded(s, o, tau);
                w = eclToWorld(r[0] * AU, r[1] * AU, r[2] * AU);
            }
            planetX[i] = w[0]; planetY[i] = w[1]; planetZ[i] = w[2];
            planetDistM[i] = Math.sqrt(s[o] * s[o] + s[o + 1] * s[o + 1] + s[o + 2] * s[o + 2]) * AU;
            double v = Math.sqrt(s[o + 3] * s[o + 3] + s[o + 4] * s[o + 4] + s[o + 5] * s[o + 5]);
            planetSpeedKmS[i] = v * AU / DAY / 1000.0;
            double mu = NBodyEphemeris.GM_SUN + NBodyEphemeris.GM[i];
            double[] el = NBodyEphemeris.elements(mu, s[o], s[o + 1], s[o + 2], s[o + 3], s[o + 4], s[o + 5]);
            oscA[i] = el[0] * AU; oscE[i] = el[1]; oscI[i] = el[2]; oscNode[i] = el[3];
            oscVarpi[i] = el[4]; oscM[i] = el[5]; oscEanom[i] = el[6];
            oscPeriodY[i] = 2 * Math.PI * Math.sqrt(el[0] * el[0] * el[0] / mu) / 365.25;
        }
        for (SmallBody sb : smallBodies) {
            sb.update(s);
            if (apparentPositions) {
                double[] r = retarded(s, 6 * sb.index, lightDays(s, 6 * sb.index, ex, ey, ez));
                double[] w = eclToWorld(r[0] * AU, r[1] * AU, r[2] * AU);
                sb.x = w[0]; sb.y = w[1]; sb.z = w[2];
            }
        }
        int n = NBodyEphemeris.N_BODIES;
        double[] sun = eclToWorld(s[6 * n] * AU, s[6 * n + 1] * AU, s[6 * n + 2] * AU);
        sunBaryWX = sun[0]; sunBaryWY = sun[1];
        computeBarycenter();
    }

    /** Point at eccentric anomaly {@code E} on planet {@code i}'s current osculating orbit (world, m, heliocentric). */
    private double[] planetOrbitPoint(int i, double E) {
        return keplerXYZ(oscA[i], oscE[i], E, oscI[i], oscNode[i], oscVarpi[i]);
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
     * plane and wz is height above it. The perifocal point is rotated by the argument of
     * perihelion, inclined by {@code incl} about the node line, and swung by the node
     * {@code Omega}, then mapped to world axes by {@link #eclToWorld}.
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
        return eclToWorld(xe, ye, ze);
    }

    // ------- IAU rotation model: real prime-meridian orientation of a planet -------

    /** Prime-meridian angle W = W0 + Ẇ·d (deg, unwrapped) for planet i at elapsed sim time. */
    static double iauPrimeMeridianDeg(int i, double simTimeSec) {
        return IAU_W0[i] + IAU_WDOT[i] * (simTimeSec / DAY);
    }

    /**
     * The planet's north-pole unit vector at time {@code simTimeSec}, expressed in the
     * ecliptic-J2000 frame the sim's positions live in (x → vernal equinox, z → ecliptic
     * north). Built from the IAU pole (a0, d0) given in ICRF equatorial coords, then
     * rotated by the obliquity of the ecliptic into the ecliptic frame.
     */
    static double[] iauPoleEcliptic(int i, double simTimeSec) {
        double T = simTimeSec / (36525.0 * DAY);
        double a0 = Math.toRadians(IAU_RA0[i]  + IAU_RA0_T[i]  * T);
        double d0 = Math.toRadians(IAU_DEC0[i] + IAU_DEC0_T[i] * T);
        // Pole in equatorial J2000.
        double ex = Math.cos(d0) * Math.cos(a0);
        double ey = Math.cos(d0) * Math.sin(a0);
        double ez = Math.sin(d0);
        return equatorialToEcliptic(ex, ey, ez);
    }

    /** Rotate an equatorial-J2000 vector into the ecliptic-J2000 frame (−ε about x). */
    private static double[] equatorialToEcliptic(double x, double y, double z) {
        double e = Math.toRadians(ECLIPTIC_OBLIQUITY_DEG), c = Math.cos(e), s = Math.sin(e);
        return new double[]{ x, c * y + s * z, -s * y + c * z };
    }

    /** Rotate an ecliptic-J2000 vector into the equatorial-J2000 frame (+ε about x). */
    private static double[] eclipticToEquatorial(double x, double y, double z) {
        double e = Math.toRadians(ECLIPTIC_OBLIQUITY_DEG), c = Math.cos(e), s = Math.sin(e);
        return new double[]{ x, c * y - s * z, s * y + c * z };
    }

    /**
     * Real sub-solar planetographic <em>east</em> longitude (radians, prime meridian = 0)
     * for planet i at elapsed sim time — the geographic longitude the Sun stands directly
     * over. This is the physical datum the on-screen texture is anchored to, so the meridian
     * lit at local noon on the rendered globe is the one truly in noon at that instant.
     *
     * <p>Computed from the full IAU body-to-inertial rotation R = Rz(a0+90°)·Rx(90°−d0)·Rz(W):
     * the Sun direction is transformed into the body-fixed frame with Rᵀ and its longitude
     * read off. Frame- and camera-independent, and a pure function of {@code simTimeSec}, so
     * it holds at any time-warp speed.
     */
    static double iauSubSolarEastLon(int i, double simTimeSec) {
        // Planet → Sun direction in ecliptic J2000 (Sun at heliocentric origin).
        double[] p = planetEclipticAU(i, simTimeSec);
        double[] sEq = eclipticToEquatorial(-p[0], -p[1], -p[2]);
        double n = Math.sqrt(sEq[0]*sEq[0] + sEq[1]*sEq[1] + sEq[2]*sEq[2]);
        if (n < 1e-12) return 0;
        double sx = sEq[0]/n, sy = sEq[1]/n, sz = sEq[2]/n;

        double T = simTimeSec / (36525.0 * DAY);
        double a0 = Math.toRadians(IAU_RA0[i]  + IAU_RA0_T[i]  * T) + Math.PI / 2;   // node RA + 90°
        double d0 = Math.toRadians(IAU_DEC0[i] + IAU_DEC0_T[i] * T);
        double W  = Math.toRadians(iauPrimeMeridianDeg(i, simTimeSec));

        // Body-fixed Sun vector = Rᵀ·s = Rz(−W)·Rx(−(90°−d0))·Rz(−a0)·s.
        double[] v = rotZ(sx, sy, sz, -a0);
        v = rotX(v[0], v[1], v[2], -(Math.PI / 2 - d0));
        v = rotZ(v[0], v[1], v[2], -W);
        return Math.atan2(v[1], v[0]);   // east longitude from the prime meridian
    }

    private static double[] rotZ(double x, double y, double z, double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[]{ c * x - s * y, s * x + c * y, z };
    }
    private static double[] rotX(double x, double y, double z, double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[]{ x, c * y - s * z, s * y + c * z };
    }

    /** Which built-in catalog a minor body belongs to (drives the View toggles). */
    enum BodyClass { COMET, ASTEROID, DISTANT }

    /**
     * A real minor body — comet, asteroid, or distant object — integrated as a test particle
     * of the N-body system ({@link NBodyEphemeris}) from its JPL Horizons J2000 state, so it
     * feels Jupiter and the other planets. Comets are flagged so they get an anti-sunward tail.
     * Its osculating elements (refreshed every frame) give the orbit ring.
     */
    static final class SmallBody {
        final String name;
        final boolean comet;
        final BodyClass cls;
        final Color color;
        /** Index of this body in {@link NBodyEphemeris}. */
        final int index;
        double x, y, z;                  // current heliocentric position (world axes, m)
        // Current osculating heliocentric orbit (m, radians).
        double aM, ecc, inclRad, omegaRad, varpiRad, meanAnom, eccAnom;

        SmallBody(String name, BodyClass cls, Color color) {
            this.name = name;
            this.cls = cls;
            this.comet = (cls == BodyClass.COMET);
            this.color = color;
            this.index = NBodyEphemeris.indexOf(name);
            if (index < NBodyEphemeris.N_MASSIVE) throw new IllegalArgumentException("not an integrated minor body: " + name);
        }

        /** Take this body's state from a set of states laid out as {@link #ephemerisStates}. */
        void update(double[] s) {
            int o = 6 * index;
            double[] w = eclToWorld(s[o] * AU, s[o + 1] * AU, s[o + 2] * AU);
            x = w[0]; y = w[1]; z = w[2];
            double[] el = NBodyEphemeris.elements(NBodyEphemeris.GM_SUN, s[o], s[o + 1], s[o + 2], s[o + 3], s[o + 4], s[o + 5]);
            aM = el[0] * AU; ecc = el[1]; inclRad = el[2]; omegaRad = el[3]; varpiRad = el[4];
            meanAnom = el[5]; eccAnom = el[6];
        }

        /** Position (world, m) {@code days} from now along the current osculating orbit. */
        double[] positionAfter(double days) {
            double aAU = aM / AU;
            double n = Math.sqrt(NBodyEphemeris.GM_SUN / (aAU * aAU * aAU));   // rad/day
            double E = solveEccentricAnomaly(meanAnom + n * days, ecc);
            return keplerXYZ(aM, ecc, E, inclRad, omegaRad, varpiRad);
        }

        /** Heliocentric distance (m) — drives comet-tail length/brightness. */
        double sunDistM() { return Math.sqrt(x * x + y * y + z * z); }
    }

    private static final Color COMET_COLOR = new Color(180, 230, 255);
    private static final Color ASTEROID_COLOR = new Color(205, 200, 175);
    private static final Color DISTANT_COLOR = new Color(200, 190, 225);

    /**
     * The catalog of real Solar-System minor bodies. Their states come from the N-body
     * integration (JPL Horizons initial conditions, see {@link NBodyEphemeris#TEST}).
     */
    private static java.util.List<SmallBody> buildBodyCatalog() {
        java.util.List<SmallBody> c = new java.util.ArrayList<>();
        // ---- Comets (get tails near the Sun) ----
        for (String n : new String[]{"1P/Halley", "2P/Encke", "55P/Tempel-Tuttle", "109P/Swift-Tuttle", "C/1995 O1 Hale-Bopp"})
            c.add(new SmallBody(n, BodyClass.COMET, COMET_COLOR));
        // ---- Asteroids / near-Earth objects ----
        for (String n : new String[]{"4 Vesta", "2 Pallas", "3 Juno", "10 Hygiea", "433 Eros", "101955 Bennu"})
            c.add(new SmallBody(n, BodyClass.ASTEROID, ASTEROID_COLOR));
        // ---- Distant objects (centaurs / TNOs; off by default — very large orbits) ----
        for (String n : new String[]{"2060 Chiron", "50000 Quaoar", "90377 Sedna"})
            c.add(new SmallBody(n, BodyClass.DISTANT, DISTANT_COLOR));
        return c;
    }

    /** True when body {@code sb} should be shown/updated under the current category toggles. */
    private boolean bodyVisible(SmallBody sb) {
        if (lessonActive() && lessonOnlyBody != null) return sb.name.equals(lessonOnlyBody);
        return switch (sb.cls) {
            case COMET -> showComets;
            case ASTEROID -> showAsteroids;
            case DISTANT -> showDistantBodies;
        };
    }

    /** Draw the enabled real minor bodies: orbit ring, anti-sunward tail (comets), nucleus + label. */
    private void drawSmallBodies(Graphics2D g2) {
        double sunSX = projX(sunBarycenterX, sunBarycenterY, 0), sunSY = projY(sunBarycenterX, sunBarycenterY, 0);
        Object oldAA = g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Font oldFont = g2.getFont();
        for (SmallBody sb : smallBodies) {
            if (!bodyVisible(sb)) continue;

            // Orbit ring — skipped for very large orbits (a > 90 AU) whose ellipse would be absurd.
            if (showPlanetOrbits && sb.aM / AU <= 90.0) {
                Path2D.Double path = new Path2D.Double();
                int seg = orbitSegments(sb.aM);   // zoom-adaptive so the ring stays smooth up close
                boolean pen = false;
                for (int j = 0; j <= seg; j++) {
                    double E = 2 * Math.PI * j / seg;
                    double[] w = keplerXYZ(sb.aM, sb.ecc, E, sb.inclRad, sb.omegaRad, sb.varpiRad);
                    double wx = w[0] + sunBarycenterX, wy = w[1] + sunBarycenterY, wz = w[2];
                    if (!inFront(wx, wy, wz)) { pen = false; continue; }
                    double sx = projX(wx, wy, wz), sy = projY(wx, wy, wz);
                    if (!pen) { path.moveTo(sx, sy); pen = true; } else path.lineTo(sx, sy);
                }
                g2.setColor(new Color(sb.color.getRed(), sb.color.getGreen(), sb.color.getBlue(), 55));
                g2.setStroke(new BasicStroke(1f));
                g2.draw(path);
            }

            if (!inFront(sb.x + sunBarycenterX, sb.y + sunBarycenterY, sb.z)) continue;   // nucleus behind camera
            double px = projX(sb.x + sunBarycenterX, sb.y + sunBarycenterY, sb.z), py = projY(sb.x + sunBarycenterX, sb.y + sunBarycenterY, sb.z);

            if (sb.comet) drawCometTail(g2, sb, px, py, sunSX, sunSY);

            double r = sb.comet ? 2.6 : 2.4;
            g2.setColor(sb.color);
            g2.fill(new Ellipse2D.Double(px - r, py - r, 2 * r, 2 * r));
            if (showSmallBodyLabels) {
                // Lowest priority: minor-body labels yield to planets/moons and are dropped first.
                int pri = (sb.cls == BodyClass.DISTANT) ? 12 : 18;
                pendingLabels.add(new LabelReq(sb.name, px, py, r, new Color(225, 232, 245, 220),
                        10.5f, false, pri, viewDepth(sb.x + sunBarycenterX, sb.y + sunBarycenterY, sb.z)));
            }
        }
        g2.setFont(oldFont);
        if (oldAA != null) g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA);
    }

    /**
     * A physically-styled comet head + twin tails, built from many soft translucent
     * specks so it reads as a diffuse gas/dust stream rather than a solid shape:
     *   • a straight, narrow, bluish ION tail pointing almost exactly anti-sunward,
     *   • a broader, warm DUST tail that curves back toward the comet's orbital wake,
     *   • a fuzzy green-white COMA glow around the nucleus.
     * Length and density grow as the comet nears the Sun (sublimation).
     */
    private void drawCometTail(Graphics2D g2, SmallBody sb, double px, double py, double sunSX, double sunSY) {
        double dx = px - sunSX, dy = py - sunSY, dlen = Math.hypot(dx, dy);
        if (dlen < 1e-6) return;
        double ux = dx / dlen, uy = dy / dlen;                        // anti-sunward
        double rAU = sb.sunDistM() / AU;
        double activity = Math.max(0.0, Math.min(1.0, (3.5 - rAU) / 3.5));
        if (activity <= 0.02) return;
        // TRUE physical tail lengths (AU -> px at the current zoom), so the tail is drawn at
        // actual size and scales with everything else — no fixed on-screen enlargement.
        double pxPerAU = METERS_TO_PIXELS * currentZoom * AU;
        double ionLenPx  = 0.30 * activity * pxPerAU;   // ion tail up to ~0.30 AU near perihelion
        double dustLenPx = 0.16 * activity * pxPerAU;   // dust tail up to ~0.16 AU
        if (ionLenPx < 3 && dustLenPx < 3) return;      // too far away to be worth drawing
        double visLen = Math.max(ionLenPx, dustLenPx);
        double densf = Math.max(0.25, Math.min(1.0, visLen / 200.0));  // fewer specks when short

        // Comet's screen-space motion direction (dust lags toward the orbital wake).
        double dtv = 5.0 * 86400.0;
        double[] pFut = sb.positionAfter(dtv / DAY);
        double fx = projX(pFut[0] + sunBarycenterX, pFut[1] + sunBarycenterY, pFut[2]), fy = projY(pFut[0] + sunBarycenterX, pFut[1] + sunBarycenterY, pFut[2]);
        double mvx = fx - px, mvy = fy - py, mvl = Math.hypot(mvx, mvy);
        double trailx = mvl > 1e-6 ? -mvx / mvl : ux, traily = mvl > 1e-6 ? -mvy / mvl : uy;

        Graphics2D gg = (Graphics2D) g2.create();
        gg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // Stable per-comet speckle pattern so the tail flows smoothly instead of flickering.
        java.util.Random rnd = new java.util.Random(sb.name.hashCode() * 0x9E3779B97F4A7C15L + 17);

        // ---- Dust tail: curved fan of warm specks (widths are a fraction of its length) ----
        int nDust = (int) ((40 + 260 * activity) * densf);
        for (int i = 0; i < nDust; i++) {
            double s = Math.pow(rnd.nextDouble(), 1.8);               // dense near the head
            double curve = 0.60 * s;                                  // older dust curves toward the wake
            double dirx = ux * (1 - curve) + trailx * curve;
            double diry = uy * (1 - curve) + traily * curve;
            double dl = Math.hypot(dirx, diry); dirx /= dl; diry /= dl;
            double perpx = -diry, perpy = dirx;
            double spread = (0.02 + 0.12 * s) * dustLenPx;            // widens downstream, physical
            double j = gauss(rnd) * spread;
            double along = s * dustLenPx;
            double x = px + dirx * along + perpx * j;
            double y = py + diry * along + perpy * j;
            double fall = (1 - s) * (1 - s);
            int alpha = (int) (60 * fall * activity);
            if (alpha < 3) continue;
            double rad = 1.0 + 2.0 * fall;
            gg.setColor(new Color(255, 226, 168, Math.min(90, alpha)));
            gg.fill(new Ellipse2D.Double(x - rad, y - rad, 2 * rad, 2 * rad));
        }

        // ---- Ion tail: straight, narrow, bluish ----
        int nIon = (int) ((30 + 200 * activity) * densf);
        double perpIx = -uy, perpIy = ux;
        for (int i = 0; i < nIon; i++) {
            double s = Math.pow(rnd.nextDouble(), 1.3);
            double along = s * ionLenPx;
            double spread = (0.006 + 0.03 * s) * ionLenPx;           // stays close to the axis
            double j = gauss(rnd) * spread;
            double x = px + ux * along + perpIx * j;
            double y = py + uy * along + perpIy * j;
            double fall = (1 - s);
            int alpha = (int) (55 * fall * fall * activity);
            if (alpha < 3) continue;
            double rad = 0.8 + 1.3 * fall;
            gg.setColor(new Color(150, 195, 255, Math.min(85, alpha)));
            gg.fill(new Ellipse2D.Double(x - rad, y - rad, 2 * rad, 2 * rad));
        }

        // ---- Coma: soft radial glow around the head (greenish, like real comets) ----
        float comaR = (float) Math.max(2.5, Math.min(60.0, 0.006 * activity * pxPerAU));
        gg.setPaint(new RadialGradientPaint(new Point2D.Double(px, py), comaR,
                new float[]{0f, 0.45f, 1f},
                new Color[]{new Color(205, 255, 215, (int) (170 * activity)),
                            new Color(150, 235, 205, (int) (70 * activity)),
                            new Color(150, 235, 205, 0)}));
        gg.fill(new Ellipse2D.Double(px - comaR, py - comaR, 2 * comaR, 2 * comaR));
        gg.dispose();
    }

    /** Approximately-normal random in ~[-1,1] (sum of two uniforms), for tail speckle spread. */
    private static double gauss(java.util.Random r) {
        return (r.nextDouble() + r.nextDouble() - 1.0);
    }

    /* ---------- Real-date scrubbing + adding minor bodies (UI) ---------- */

    /** Parse "YYYY-MM-DD" or "YYYY-MM-DD HH:MM" (also accepting 'T') to a LocalDateTime, or null. */
    /**
     * Read "YYYY-MM-DD[ HH:MM]" optionally followed by a zone — "UTC"/"Z", an offset like
     * "+09:00" / "-5", a region like "Asia/Tokyo", or the zone abbreviation the dialog
     * prefills — interpreting a bare time in {@code defaultZone}. Returns the instant as a UTC
     * date-time (what {@link #goToDate} takes), or null if unreadable.
     */
    static java.time.LocalDateTime parseDateTimeInZone(String s, java.time.ZoneId defaultZone) {
        if (s == null) return null;
        // ISO "2026-10-03T00:01" → a space; only a T between digits, so "UTC" etc. survive.
        String[] parts = s.trim().replaceFirst("(\\d)T(\\d)", "$1 $2").split("\\s+");
        java.time.ZoneId zone = defaultZone;
        int n = parts.length;
        if (n >= 2) {
            java.time.ZoneId z = parseZone(parts[n - 1], defaultZone);
            if (z != null) { zone = z; n--; }
        }
        java.time.LocalDateTime local = parseDateTime(String.join(" ", java.util.Arrays.copyOf(parts, n)));
        if (local == null) return null;
        return local.atZone(zone).withZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime();
    }

    /** A zone token: UTC/GMT/Z, ±H[H][:MM], a region ID, or the default zone's own abbreviation; else null. */
    private static java.time.ZoneId parseZone(String t, java.time.ZoneId defaultZone) {
        String u = t.toUpperCase(java.util.Locale.ROOT);
        if (u.equals("UTC") || u.equals("GMT") || u.equals("Z") || u.equals("UT")) return java.time.ZoneOffset.UTC;
        try {
            if (u.matches("(UTC|GMT)?[+-]\\d{1,2}(:?\\d{2})?")) {
                String o = u.replaceFirst("^(UTC|GMT)", "");
                if (o.matches("[+-]\\d")) o = o.charAt(0) + "0" + o.charAt(1);
                return java.time.ZoneOffset.of(o);
            }
            if (t.contains("/")) return java.time.ZoneId.of(t);
        } catch (Exception ignored) { }
        for (java.time.format.TextStyle st : new java.time.format.TextStyle[]{java.time.format.TextStyle.SHORT, java.time.format.TextStyle.FULL})
            if (defaultZone.getDisplayName(st, java.util.Locale.getDefault()).equalsIgnoreCase(t)) return defaultZone;
        // Daylight-time abbreviations (e.g. "EDT") aren't the zone's generic name; accept any that
        // the default zone actually uses at some point in the year.
        java.time.format.DateTimeFormatter abbr = java.time.format.DateTimeFormatter.ofPattern("zzz");
        java.time.ZonedDateTime probe = java.time.ZonedDateTime.now(defaultZone);
        for (int m = 0; m < 12; m++)
            if (probe.plusMonths(m).format(abbr).equalsIgnoreCase(t)) return defaultZone;
        return null;
    }

    private static java.time.LocalDateTime parseDateTime(String s) {
        if (s == null) return null;
        // Collapse any run of whitespace to one space: the dialog prefills the date with the
        // double-spaced SIM_DATE_FMT, and the patterns below expect a single date/time separator.
        s = s.trim().replaceFirst("(\\d)T(\\d)", "$1 $2").replaceAll("\\s+", " ");
        String[] dt = {"yyyy-M-d H:m", "yyyy-M-d HH:mm", "yyyy-MM-dd HH:mm", "yyyy-MM-dd H:m"};
        for (String p : dt) {
            try { return java.time.LocalDateTime.parse(s, java.time.format.DateTimeFormatter.ofPattern(p)); }
            catch (Exception ignored) { }
        }
        try { return java.time.LocalDate.parse(s.split("\\s+")[0]).atStartOfDay(); }
        catch (Exception ignored) { }
        return null;
    }

    /** Ask for a real date and scrub the whole system to it (pauses so it holds on that date). */
    void promptGoToDate(Window owner) {
        String zoneName = displayZone.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault());
        String prefill = simLocalDateString();
        String input = (String) JOptionPane.showInputDialog(owner,
                "Jump to a real date — the whole system moves to its configuration then.\n"
                + "Format:  YYYY-MM-DD   or   YYYY-MM-DD HH:MM\n"
                + "Times are in your time zone (" + zoneName + ", " + displayZone + ").\n"
                + "Add UTC, an offset (+09:00) or a zone (Asia/Tokyo) to use another.",
                "Go to Date", JOptionPane.QUESTION_MESSAGE, null, null,
                (prefill != null ? prefill : simDateString()).trim().replaceAll("\\s+", " "));
        if (input == null || input.isBlank()) return;
        java.time.LocalDateTime dt = parseDateTimeInZone(input, displayZone);
        if (dt == null) {
            JOptionPane.showMessageDialog(owner,
                    "Couldn't read \"" + input + "\".\nUse YYYY-MM-DD or YYYY-MM-DD HH:MM, optionally followed by a zone.",
                    "Invalid date", JOptionPane.WARNING_MESSAGE);
            return;
        }
        simulationPaused = true;
        goToDate(dt);
    }

    // ------- View projection (world -> screen), pitch-aware -------

    /** Set the view pitch (radians, clamped) and refresh cached cos/sin + the sphere-frame cache. */
    private void setViewPitch(double rad) {
        double p = Math.max(0.0, Math.min(MAX_PITCH_RAD, rad));
        if (p == viewPitchRad) return;
        viewPitchRad = p;
        viewCosP = Math.cos(p);
        viewSinP = Math.sin(p);
        invalidateSphereGeometry();   // the sphere body frame depends on the view orientation
    }

    /** Set the view yaw/azimuth (radians, wrapped) and refresh cached cos/sin. */
    private void setViewYaw(double rad) {
        double y = rad % (2 * Math.PI);
        if (y == viewYawRad) return;
        viewYawRad = y;
        viewCosY = Math.cos(y);
        viewSinY = Math.sin(y);
        invalidateSphereGeometry();   // yaw turns the globe to a new face, so the baked geometry is stale
    }

    /** Drop the cached sphere/ring geometry so it is rebuilt for the new view orientation. */
    private void invalidateSphereGeometry() {
        geomCache.clear();
        ringGeom = null;
    }

    // ===================== Earth Explorer ("Google Earth" mode) =====================
    // A self-contained surface explorer: when you zoom into Earth we ray-cast the
    // full-resolution equirectangular map as an orthographic globe centred on a
    // geographic point (geLat/geLon) at an arbitrary zoom (geZoom). Zoomed out it is
    // a globe hanging in the solar system; as geZoom climbs the visible cap flattens
    // into a smooth, keep-zooming ground view. Pan by dragging, zoom with the wheel,
    // Esc to pop back to the solar-system view. No network — detail is bounded only by
    // the texture you drop in (a higher-res earth_diffuse.jpg is used at full size).

    private static double wrapLonDeg(double lon) {
        return ((lon + 180) % 360 + 360) % 360 - 180;
    }

    /** On-screen radius (px) of Earth right now under the current follow/zoom. */
    private double earthScreenRadiusPx() {
        double wx = planetX[EARTH_INDEX] + sunBarycenterX;
        double wy = planetY[EARTH_INDEX] + sunBarycenterY;
        double wz = planetZ[EARTH_INDEX];
        return RADIUS_M[EARTH_INDEX] * perspScaleAt(wx, wy, wz);
    }

    /**
     * Geographic (lat, lon) in degrees of the point on Earth currently facing the camera,
     * derived with exactly the sphere renderer's orientation math so the hand-off into the
     * explorer lands on the same face the user was looking at.
     */
    private double[] earthSubViewerLatLon() {
        double[] fr = bodyFrame(planetPole(EARTH_INDEX));   // pole,a,b in view space
        double lat = Math.asin(Math.max(-1, Math.min(1, fr[2])));           // N·pole with N=(0,0,1)
        double lon0 = Math.atan2(fr[8], fr[5]);                             // atan2(N·b, N·a)
        double lonGeo = renderedEastLon(lon0, planetSpin(EARTH_INDEX));
        return new double[]{ Math.toDegrees(lat), Math.toDegrees(lonGeo) };
    }

    /** Sub-solar direction in the Earth-fixed frame (Z=north), for a date-accurate terminator. */
    private double[] earthSunDirGeo() {
        java.time.LocalDateTime dt = simDateTime();
        double utcH = (dt != null) ? dt.getHour() + dt.getMinute() / 60.0 + dt.getSecond() / 3600.0 : 12.0;
        double lamSun = -(utcH - 12.0) * Math.PI / 12.0;                     // sub-solar east longitude
        int doy = (dt != null) ? dt.getDayOfYear() : 172;
        double decl = Math.toRadians(23.44) * Math.sin(2 * Math.PI * (doy - 80) / 365.25);
        double cD = Math.cos(decl);
        return new double[]{ cD * Math.cos(lamSun), cD * Math.sin(lamSun), Math.sin(decl) };
    }

    private void enterEarthExplorer() {
        if (!ensureEarthExplorerTextures()) return;   // no Earth map on disk: stay in the solar view
        double[] c = earthSubViewerLatLon();
        geLat = c[0];
        geLon = c[1];
        // Freeze the solar view's on-screen tilt (roll of local north at the sub-viewer point) so
        // the globe starts leaning exactly like it did in the solar view, then straightens on descent.
        double[] fr = bodyFrame(planetPole(EARTH_INDEX));   // pole in view space (x right, y up)
        geRoll0 = Math.atan2(fr[0], fr[1]);
        // Seed the globe at exactly Earth's current on-screen size so the hand-off has no size
        // pop, then glide in to a comfortable globe framing while the UI fades up.
        double minHalf = Math.min(getWidth(), getHeight()) * 0.5;
        double entryZoom = minHalf > 0 ? earthScreenRadiusPx() / minHalf : 1.0;
        geZoom = Math.max(GE_MIN_ZOOM, Math.min(entryZoom, 1.6));
        geTargetZoom = 1.15;
        geTransition = 0.0;
        earthExplorerActive = true;
        draggingView = false;
        repaint();
    }

    void exitEarthExplorer() {
        if (!earthExplorerActive) return;
        earthExplorerActive = false;
        // Match Earth's solar-view size to the globe we were showing, then let auto-zoom glide it
        // back to the normal framing — a smooth zoom-out rather than a jump.
        double minHalf = Math.min(getWidth(), getHeight()) * 0.5;
        double matched = geZoom * minHalf / (RADIUS_M[EARTH_INDEX] * METERS_TO_PIXELS);
        currentZoom = Math.max(0.02, Math.min(matched, 50000));
        autoZoomEnabled = true;         // resume the ordinary followed-Earth framing
        if (cameraFocusIndex == EARTH_INDEX) updateAutoZoomTarget();
        repaint();
    }

    /** Load the full-resolution Earth maps once (uncapped, so higher-res drop-ins add detail). */
    private boolean ensureEarthExplorerTextures() {
        if (geDiffuse != null) return true;
        if (geTexTried) return false;
        geTexTried = true;
        final int MAX_W = 8192;
        int[][] px = new int[1][]; int[] wh = new int[2];
        if (loadRawTexture("earth_diffuse.jpg", MAX_W, px, wh)) {
            geDiffuse = px[0]; geDiffW = wh[0]; geDiffH = wh[1];
        } else if (loadTexture("earth")) {                       // fall back to the sim's 2K copy
            geDiffuse = texPixels.get("earth");
            geDiffW = texSize.get("earth")[0]; geDiffH = texSize.get("earth")[1];
        }
        if (geDiffuse == null) return false;
        int[][] n = new int[1][]; int[] nwh = new int[2];
        if (loadRawTexture("earth_night.jpg", MAX_W, n, nwh)) { geNight = n[0]; geNightW = nwh[0]; geNightH = nwh[1]; }
        int[][] cl = new int[1][]; int[] cwh = new int[2];
        if (loadRawTexture("earth_clouds.jpg", MAX_W, cl, cwh)) { geClouds = cl[0]; geCloudW = cwh[0]; geCloudH = cwh[1]; }
        return true;
    }

    /** Read an equirectangular map at up to {@code maxW} wide into {@code outPix}/{@code outWH}. */
    private boolean loadRawTexture(String fileName, int maxW, int[][] outPix, int[] outWH) {
        File f = new File("assets/textures/" + fileName);
        if (!f.isFile()) return false;
        try {
            BufferedImage img = ImageIO.read(f);
            if (img == null) return false;
            if (img.getWidth() > maxW) {
                int nw = maxW, nh = Math.max(1, img.getHeight() * maxW / img.getWidth());
                BufferedImage small = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
                Graphics2D sg = small.createGraphics();
                sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                sg.drawImage(img, 0, 0, nw, nh, null);
                sg.dispose();
                img = small;
            }
            int w = img.getWidth(), h = img.getHeight();
            outPix[0] = img.getRGB(0, 0, w, h, null, 0, w);
            outWH[0] = w; outWH[1] = h;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Bilinear equirectangular sample (u wraps, v clamps); returns packed RGB. */
    private static int sampleBilin(int[] tex, int tw, int th, double u, double v) {
        u -= Math.floor(u);
        if (v < 0) v = 0; else if (v > 1) v = 1;
        double fx = u * tw - 0.5, fy = v * (th - 1);
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        double tx = fx - x0, ty = fy - y0;
        int x1 = x0 + 1;
        x0 = ((x0 % tw) + tw) % tw; x1 = ((x1 % tw) + tw) % tw;
        int y1 = Math.min(th - 1, Math.max(0, y0 + 1));
        y0 = Math.min(th - 1, Math.max(0, y0));
        int c00 = tex[y0 * tw + x0], c10 = tex[y0 * tw + x1];
        int c01 = tex[y1 * tw + x0], c11 = tex[y1 * tw + x1];
        double r = (((c00 >> 16) & 0xFF) * (1 - tx) + ((c10 >> 16) & 0xFF) * tx) * (1 - ty)
                 + (((c01 >> 16) & 0xFF) * (1 - tx) + ((c11 >> 16) & 0xFF) * tx) * ty;
        double g = (((c00 >> 8) & 0xFF) * (1 - tx) + ((c10 >> 8) & 0xFF) * tx) * (1 - ty)
                 + (((c01 >> 8) & 0xFF) * (1 - tx) + ((c11 >> 8) & 0xFF) * tx) * ty;
        double b = (((c00) & 0xFF) * (1 - tx) + ((c10) & 0xFF) * tx) * (1 - ty)
                 + (((c01) & 0xFF) * (1 - tx) + ((c11) & 0xFF) * tx) * ty;
        return ((int) r << 16) | ((int) g << 8) | (int) b;
    }

    /** Nearest equirectangular sample (cheap; used for low-frequency night/cloud layers). */
    private static int sampleNearest(int[] tex, int tw, int th, double u, double v) {
        u -= Math.floor(u);
        if (v < 0) v = 0; else if (v > 1) v = 1;
        int x = (int) (u * tw); if (x >= tw) x = tw - 1;
        int y = (int) (v * (th - 1));
        return tex[y * tw + x];
    }

    /** Render the whole canvas as the Earth explorer globe. */
    private void drawEarthExplorer(Graphics2D g2) {
        int W = getWidth(), H = getHeight();
        if (W <= 0 || H <= 0) return;
        if (geDiffuse == null && !ensureEarthExplorerTextures()) { exitEarthExplorer(); return; }

        if (geZoom < GE_TILE_MIN) drawStarfield(g2, W, H);   // space behind the globe (hidden once tiles fill)

        // Internal raster: cap the long side so the per-pixel ray-cast stays real-time,
        // then bilinear-upscale to the panel.
        final int CAP = 820;
        int bw, bh;
        if (W >= H) { bw = Math.min(W, CAP); bh = Math.max(1, (int) Math.round(bw * (double) H / W)); }
        else        { bh = Math.min(H, CAP); bw = Math.max(1, (int) Math.round(bh * (double) W / H)); }
        if (geBuf == null || geBufW != bw || geBufH != bh) {
            geBuf = new BufferedImage(bw, bh, BufferedImage.TYPE_INT_ARGB);
            geBufW = bw; geBufH = bh; geBufValid = false;
        }
        int[] out = ((DataBufferInt) geBuf.getRaster().getDataBuffer()).getData();

        double half = Math.min(bw, bh) * 0.5;
        double Rpx = half * geZoom;                 // globe radius in internal-buffer px
        geLastRadiusPx = Rpx;
        double bcx = bw * 0.5, bcy = bh * 0.5, invR = 1.0 / Rpx;

        double[] sun = earthSunDirGeo();
        double sunX = sun[0], sunY = sun[1], sunZ = sun[2];
        boolean lit = shadingEnabled;

        boolean drawGlobe = geZoom < GE_TILE_MIN;                        // above the band: pure tiles
        double tileAlpha = smoothstep(GE_GLOBE_MAX, GE_TILE_MIN, geZoom); // globe → satellite cross-fade

        if (drawGlobe) {
        // Reuse the last raster on idle frames (view + lighting unchanged) — the timer
        // repaints at 60 FPS, but a static globe need not be ray-cast every time.
        boolean reuse = geBufValid
                && geRLat == geLat && geRLon == geLon && geRZoom == geZoom
                && geRShade == lit && geRNight == showNightLights && geRClouds == showClouds
                && Math.abs(geRSunX - sunX) + Math.abs(geRSunY - sunY) + Math.abs(geRSunZ - sunZ) < 3e-3;
        if (!reuse) {
        // Orthonormal view basis in the Earth-fixed frame (Z=north pole, X=Greenwich equator).
        double phi0 = Math.toRadians(geLat), lam0 = Math.toRadians(geLon);
        double cP = Math.cos(phi0), sP = Math.sin(phi0), cL = Math.cos(lam0), sL = Math.sin(lam0);
        double ctrX = cP * cL, ctrY = cP * sL, ctrZ = sP;         // toward camera
        double norX = -sP * cL, norY = -sP * sL, norZ = cP;       // screen up (north)
        double easX = -sL, easY = cL, easZ = 0;                   // screen right (east)

        // Roll the globe to the frozen axial tilt near entry, straightening to north-up by the
        // time the flat tile map takes over — so both hand-offs (solar↔globe, globe↔tiles) line up.
        double roll = geRoll0 * (1.0 - smoothstep(1.0, GE_GLOBE_MAX, geZoom));
        double cR = Math.cos(roll), sR = Math.sin(roll);

        final double AMB = 0.13;

        int[] dif = geDiffuse; int dW = geDiffW, dH = geDiffH;
        int[] night = showNightLights ? geNight : null; int nW = geNightW, nH = geNightH;
        int[] cloud = showClouds ? geClouds : null; int cW = geCloudW, cH = geCloudH;
        final double TWO_PI = 2 * Math.PI;

        for (int py = 0; py < bh; py++) {
            double y0 = -(py - bcy) * invR;          // north-up (pre-roll)
            int rowoff = py * bw;
            for (int px = 0; px < bw; px++) {
                double x0 = (px - bcx) * invR;        // east (pre-roll)
                double x = x0 * cR - y0 * sR;         // apply screen roll
                double y = x0 * sR + y0 * cR;
                double rr = x * x + y * y;
                if (rr > 1.0) { out[rowoff + px] = 0x00000000; continue; }
                double nz = Math.sqrt(1.0 - rr);
                // Earth-fixed unit surface point.
                double Px = x * easX + y * norX + nz * ctrX;
                double Py = x * easY + y * norY + nz * ctrY;
                double Pz = x * easZ + y * norZ + nz * ctrZ;
                double lat = Math.asin(Pz > 1 ? 1 : (Pz < -1 ? -1 : Pz));
                double lon = Math.atan2(Py, Px);
                double u = lon / TWO_PI + 0.5;
                double v = 0.5 - lat / Math.PI;

                int base = sampleBilin(dif, dW, dH, u, v);
                double cr = (base >> 16) & 0xFF, cg = (base >> 8) & 0xFF, cb = base & 0xFF;

                // Clouds over the surface.
                if (cloud != null) {
                    int cp = sampleNearest(cloud, cW, cH, u, v);
                    double ca = (((cp >> 16) & 0xFF) + ((cp >> 8) & 0xFF) + (cp & 0xFF)) / 765.0;
                    ca = Math.max(0.0, (ca - 0.12) / 0.88);
                    cr += (255 - cr) * ca; cg += (255 - cg) * ca; cb += (255 - cb) * ca;
                }

                double dot = lit ? (Px * sunX + Py * sunY + Pz * sunZ) : 1.0;
                double shade = lit ? (AMB + (1 - AMB) * Math.max(0.0, dot)) : 1.0;
                cr *= shade; cg *= shade; cb *= shade;

                // City lights on the night side.
                if (night != null && lit && dot < 0.18) {
                    double nw = Math.min(1.0, (0.18 - dot) / 0.30);
                    int np = sampleNearest(night, nW, nH, u, v);
                    cr += ((np >> 16) & 0xFF) * 1.6 * nw;
                    cg += ((np >> 8) & 0xFF) * 1.6 * nw;
                    cb += (np & 0xFF) * 1.6 * nw;
                }

                int r = cr > 255 ? 255 : (int) cr;
                int g = cg > 255 ? 255 : (int) cg;
                int b = cb > 255 ? 255 : (int) cb;
                out[rowoff + px] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        geBufValid = true;
        geRLat = geLat; geRLon = geLon; geRZoom = geZoom;
        geRShade = lit; geRNight = showNightLights; geRClouds = showClouds;
        geRSunX = sunX; geRSunY = sunY; geRSunZ = sunZ;
        }  // end if (!reuse)

        // Soft atmosphere rim while the globe is small enough to see space around it
        // (faded out as the satellite tiles take over, and up during the fly-in).
        double panelR = Rpx * (double) W / bw;
        double pcx = W * 0.5, pcy = H * 0.5;
        int atmA = (int) Math.round(90 * (1 - tileAlpha) * Math.min(1.0, geTransition));
        if (panelR < Math.hypot(W, H) && atmA > 1) {
            Paint old = g2.getPaint();
            float inner = (float) Math.max(0.0, (panelR - 2) / (panelR * 1.09));
            g2.setPaint(new RadialGradientPaint(
                    new Point2D.Double(pcx, pcy), (float) (panelR * 1.09),
                    new float[]{ Math.min(0.999f, inner), 0.955f, 1f },
                    new Color[]{ new Color(120, 170, 255, 0),
                                 new Color(120, 170, 255, atmA),
                                 new Color(120, 170, 255, 0) }));
            g2.fill(new Ellipse2D.Double(pcx - panelR * 1.09, pcy - panelR * 1.09,
                    panelR * 2.18, panelR * 2.18));
            g2.setPaint(old);
        }

        // Blit the globe over space + atmosphere.
        Object oldInterp = g2.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.drawImage(geBuf, 0, 0, W, H, null);
        if (oldInterp != null) g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterp);
        }  // end if (drawGlobe)

        // Streamed satellite tiles take over for street-level detail (drawn over the globe,
        // cross-fading in through the band and fully opaque once we're close to the ground).
        if (tileAlpha > 0.004) {
            double zf = mercatorZoomFor(geZoom, geLat, Math.min(W, H) * 0.5);
            drawTileMap(g2, W, H, zf, (float) tileAlpha);
        }

        drawDynamicScale(g2, H);              // surface scale + coordinates on the existing ruler
        drawEarthExplorerHud(g2, W, H, tileAlpha);
    }

    /** Fractional Web-Mercator zoom that matches the globe's on-screen ground scale (see notes). */
    private double mercatorZoomFor(double gZoom, double lat, double minHalfPx) {
        double cosLat = Math.max(0.02, Math.cos(Math.toRadians(lat)));
        double val = gZoom * minHalfPx * cosLat * 2 * Math.PI / GE_TILE_PX;
        return Math.max(0.0, Math.log(val) / Math.log(2));
    }

    // ---- Web-Mercator slippy-map of streamed satellite tiles ----

    private static long tileKey(int layer, int z, int x, int y) {
        return ((long) layer << 47) | ((long) z << 42) | ((long) x << 21) | y;
    }

    /** Draw the satellite map centred on geLat/geLon at fractional zoom {@code zf}: imagery,
     *  then the day/night terminator, then the place + road label overlays. */
    private void drawTileMap(Graphics2D g2, int W, int H, double zf, float alpha) {
        // Imagery at the matched zoom level.
        int z = Math.max(0, Math.min(GE_MAX_TILE_Z, (int) Math.floor(zf)));
        double s = Math.pow(2, zf - z);                 // fractional over-zoom (>=1)
        int n = 1 << z;
        double mercY = mercatorY(geLat);
        double cxw = (geLon + 180.0) / 360.0 * n * GE_TILE_PX;
        double cyw = mercY * n * GE_TILE_PX;

        drawTileLayer(g2, W, H, z, s, cxw, cyw, n, LYR_IMAGERY, alpha, true);
        if (shadingEnabled) drawTileDayNight(g2, W, H, z, s, cxw, cyw, n, alpha);

        if (geLabels) {
            // Labels one level finer (drawn at half size) so place/road text stays map-sized
            // rather than doubling with the imagery's over-zoom.
            int lz = Math.max(0, Math.min(GE_MAX_TILE_Z, (int) Math.floor(zf) + 1));
            double ls = Math.pow(2, zf - lz);
            int ln = 1 << lz;
            double lcxw = (geLon + 180.0) / 360.0 * ln * GE_TILE_PX;
            double lcyw = mercY * ln * GE_TILE_PX;
            // Roads (lines + street names) UNDER place/boundary labels, so area names — cities,
            // airports, parks — sit on top of every road line and road label, never buried by them.
            drawTileLayer(g2, W, H, lz, ls, lcxw, lcyw, ln, LYR_ROADS, alpha, false);
            drawTileLayer(g2, W, H, lz, ls, lcxw, lcyw, ln, LYR_PLACES, alpha, false);
        }
    }

    /** Normalised Web-Mercator Y (0 at +85°, 1 at −85°) for a latitude in degrees. */
    private static double mercatorY(double latDeg) {
        double latR = Math.toRadians(Math.max(-85.05, Math.min(85.05, latDeg)));
        return (1 - Math.log(Math.tan(latR) + 1.0 / Math.cos(latR)) / Math.PI) / 2.0;
    }

    /** Blit one tile layer across the viewport. {@code base} enables the ancestor/neutral fallback
     *  (imagery); overlay label layers just skip a tile that has not arrived. */
    private void drawTileLayer(Graphics2D g2, int W, int H, int z, double s,
                               double cxw, double cyw, int n, int layer, float alpha, boolean base) {
        double scx = W / 2.0, scy = H / 2.0;
        int minTx = (int) Math.floor((cxw - scx / s) / GE_TILE_PX), maxTx = (int) Math.floor((cxw + (W - scx) / s) / GE_TILE_PX);
        int minTy = (int) Math.floor((cyw - scy / s) / GE_TILE_PX), maxTy = (int) Math.floor((cyw + (H - scy) / s) / GE_TILE_PX);
        int dw = (int) Math.ceil(GE_TILE_PX * s) + 1;

        Composite oldC = g2.getComposite();
        if (alpha < 0.999f) g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        Object oldI = g2.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        for (int ty = minTy; ty <= maxTy; ty++) {
            if (ty < 0 || ty >= n) continue;                 // no vertical wrap (poles)
            int dsty = (int) Math.floor((ty * GE_TILE_PX - cyw) * s + scy);
            for (int tx = minTx; tx <= maxTx; tx++) {
                int wx = ((tx % n) + n) % n;                 // longitude wraps
                int dstx = (int) Math.floor((tx * GE_TILE_PX - cxw) * s + scx);
                BufferedImage img = getTile(layer, z, wx, ty);
                if (img != null) {
                    g2.drawImage(img, dstx, dsty, dw, dw, null);
                } else if (base) {
                    drawTileFallback(g2, z, wx, ty, dstx, dsty, dw);
                }
            }
        }
        g2.setComposite(oldC);
        if (oldI != null) g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldI);
    }

    /**
     * Day/night terminator over the satellite map: build a small buffer of dark-blue night with
     * per-pixel alpha = (1 − shade) from the sub-solar geometry (the same Sun the globe uses),
     * then upscale it as a darkening overlay so the map fades into night exactly where the globe does.
     */
    private void drawTileDayNight(Graphics2D g2, int W, int H, int z, double s,
                                  double cxw, double cyw, int n, float alpha) {
        final int LW = 256, LH = 144;
        if (geLightBuf == null) geLightBuf = new BufferedImage(LW, LH, BufferedImage.TYPE_INT_ARGB);
        int[] lm = ((DataBufferInt) geLightBuf.getRaster().getDataBuffer()).getData();
        double[] sun = earthSunDirGeo();
        double sunX = sun[0], sunY = sun[1], sunZ = sun[2];
        double scx = W / 2.0, scy = H / 2.0, span = n * GE_TILE_PX;
        boolean anyNight = false;
        for (int j = 0; j < LH; j++) {
            double sy = (j + 0.5) / LH * H;
            double myn = (cyw + (sy - scy) / s) / span;                 // mercator y in [0,1]
            double lat = Math.atan(Math.sinh(Math.PI * (1 - 2 * myn)));
            double cLat = Math.cos(lat), sLat = Math.sin(lat);
            for (int i = 0; i < LW; i++) {
                double sx = (i + 0.5) / LW * W;
                double lon = ((cxw + (sx - scx) / s) / span) * 2 * Math.PI - Math.PI;
                double dot = (cLat * Math.cos(lon)) * sunX + (cLat * Math.sin(lon)) * sunY + sLat * sunZ;
                double day = smoothstep(-0.12, 0.12, dot);
                int a = (int) ((1 - (0.30 + 0.70 * day)) * 255);        // night floor 0.30
                if (a > 0) anyNight = true;
                lm[j * LW + i] = (a << 24) | 0x0A1226;                  // dark night-blue tint
            }
        }
        if (!anyNight) return;                                          // fully daylit: no overlay
        Composite oldC = g2.getComposite();
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        Object oldI = g2.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.drawImage(geLightBuf, 0, 0, W, H, null);
        g2.setComposite(oldC);
        if (oldI != null) g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldI);
    }

    /** While an imagery tile loads, upsample the nearest cached ancestor; else a neutral fill. */
    private void drawTileFallback(Graphics2D g2, int z, int x, int y, int dx, int dy, int dw) {
        for (int k = 1; k <= 6; k++) {
            int pz = z - k;
            if (pz < 0) break;
            int mask = (1 << k) - 1, sub = GE_TILE_PX >> k;
            if (sub < 1) break;
            BufferedImage p = tileCache.get(tileKey(LYR_IMAGERY, pz, x >> k, y >> k));
            if (p != null) {
                int ox = (x & mask) * sub, oy = (y & mask) * sub;
                g2.drawImage(p, dx, dy, dx + dw, dy + dw, ox, oy, ox + sub, oy + sub, null);
                return;
            }
        }
        g2.setColor(new Color(26, 34, 48));
        g2.fillRect(dx, dy, dw, dw);
    }

    /** Cached tile, or null (kicking off an async fetch on first miss). */
    private BufferedImage getTile(int layer, int z, int x, int y) {
        long k = tileKey(layer, z, x, y);
        BufferedImage im = tileCache.get(k);
        if (im != null) return im;
        requestTile(layer, z, x, y, k);
        return null;
    }

    private void requestTile(int layer, int z, int x, int y, long k) {
        Long ft = tileFailAt.get(k);
        if (ft != null && System.currentTimeMillis() - ft < 5000) return;   // brief back-off after a failure
        if (tileLoading.size() > 200 || !tileLoading.add(k)) return;        // already loading / flood guard
        ensureTilePool();
        Runnable body = () -> {
            try {
                BufferedImage im = loadTileBlocking(layer, z, x, y);
                if (im != null) {
                    tileCache.put(k, im);
                    tileFailAt.remove(k);
                    tileFailStreak = 0;
                    tilesReachable = true;
                } else {
                    tileFailAt.put(k, System.currentTimeMillis());
                }
            } catch (Throwable t) {
                tileFailAt.put(k, System.currentTimeMillis());
                if (layer == LYR_IMAGERY && ++tileFailStreak > 8) tilesReachable = false;
            } finally {
                tileLoading.remove(k);
                if (earthExplorerActive) repaint();
            }
        };
        // Priority: satellite base first, then place labels, then roads; newest view first within a layer.
        ((ThreadPoolExecutor) tilePool).execute(new TileTask(layer, tileSeq.incrementAndGet(), body));
    }

    private final AtomicLong tileSeq = new AtomicLong();

    /** A prioritised fetch job: lower {@code layer} runs first; ties break to the most recent request. */
    private static final class TileTask implements Runnable, Comparable<TileTask> {
        final int layer; final long seq; final Runnable body;
        TileTask(int layer, long seq, Runnable body) { this.layer = layer; this.seq = seq; this.body = body; }
        @Override public void run() { body.run(); }
        @Override public int compareTo(TileTask o) {
            if (layer != o.layer) return Integer.compare(layer, o.layer);   // imagery(0) < places(1) < roads(2)
            return Long.compare(o.seq, seq);                                // newer request first
        }
    }

    /** Lazily build the fetch pool (imagery-first, newest-view-first), disk-writer and shared
     *  HTTP/2 client. Called only from the EDT (paint), so no init race. */
    private void ensureTilePool() {
        if (tilePool != null) return;
        ThreadPoolExecutor ex = new ThreadPoolExecutor(16, 16, 30, TimeUnit.SECONDS,
                new PriorityBlockingQueue<>(256), r -> {
            Thread t = new Thread(r, "earth-tile-fetch");
            t.setDaemon(true);
            return t;
        });
        ex.allowCoreThreadTimeOut(true);
        tilePool = ex;
        tileDiskPool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "earth-tile-disk");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        tileHttp = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)          // multiplex many tiles over few connections
                .connectTimeout(Duration.ofSeconds(6))
                .build();
        tileCacheDir = new File(System.getProperty("java.io.tmpdir", "."), "astro-earth-tiles");
    }

    private final Map<Long, Long> tileFailAt = new ConcurrentHashMap<>();

    /** Fetch one layer's tile from disk cache or the network (blocking; runs on a worker thread).
     *  Uses the shared HTTP/2 client for connection reuse, and writes the disk cache off-thread so
     *  the decoded tile reaches the screen without waiting on disk I/O. */
    private BufferedImage loadTileBlocking(int layer, int z, int x, int y) throws Exception {
        String ext = layer == LYR_IMAGERY ? ".jpg" : ".png";
        File cf = (tileCacheDir != null) ? new File(tileCacheDir, layer + "/" + z + "/" + x + "_" + y + ext) : null;
        if (cf != null && cf.isFile()) {
            try { BufferedImage im = ImageIO.read(cf); if (im != null) return im; } catch (Exception ignore) { }
        }
        String url = GE_LAYER_URL[layer] + z + "/" + y + "/" + x;   // ArcGIS order is {z}/{y}/{x}
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "AstronomySuite/1.0 (Earth Explorer)")
                .timeout(Duration.ofSeconds(12))
                .GET().build();
        HttpResponse<byte[]> resp = tileHttp.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) throw new java.io.IOException("HTTP " + resp.statusCode());
        byte[] bytes = resp.body();
        BufferedImage im = ImageIO.read(new ByteArrayInputStream(bytes));
        if (im != null && cf != null) {
            final File f = cf;
            tileDiskPool.submit(() -> {
                try { f.getParentFile().mkdirs(); Files.write(f.toPath(), bytes); } catch (Exception ignore) { }
            });
        }
        return im;
    }

    /** Explorer overlays: just the controls hint and the imagery attribution — the position and
     *  scale live on the existing scale ruler, not in a separate panel. */
    private void drawEarthExplorerHud(Graphics2D g2, int W, int H, double tileAlpha) {
        float fade = (float) Math.max(0.0, Math.min(1.0, geTransition));   // fly-in fade of the UI
        if (fade <= 0.02f) return;
        float rowSize = switch (uiSize) { case SMALL -> 10f; case LARGE -> 13f; default -> 11f; };

        Font oldFont = g2.getFont();

        // Bottom hint bar.
        String hint = "Drag to pan  •  Scroll to zoom  •  L labels  •  Esc to exit";
        g2.setFont(oldFont.deriveFont(Font.PLAIN, rowSize));
        FontMetrics hfm = g2.getFontMetrics();
        int hw = hfm.stringWidth(hint) + 24;
        int hx = (W - hw) / 2, hy = H - hfm.getHeight() - 16;
        g2.setColor(new Color(10, 16, 30, (int) (190 * fade)));
        g2.fillRoundRect(hx, hy, hw, hfm.getHeight() + 10, 12, 12);
        g2.setColor(new Color(210, 225, 245, (int) (255 * fade)));
        g2.drawString(hint, hx + 12, hy + hfm.getAscent() + 5);

        // Imagery attribution (required when satellite tiles are on screen).
        if (tileAlpha > 0.05) {
            String attrib = GE_TILE_ATTRIB;
            int aw = hfm.stringWidth(attrib) + 12;
            int ax = W - aw - 8, ay = hy - hfm.getHeight() - 6;
            g2.setColor(new Color(0, 0, 0, (int) (150 * tileAlpha)));
            g2.fillRoundRect(ax, ay, aw, hfm.getHeight() + 6, 8, 8);
            g2.setColor(new Color(220, 228, 240, (int) (210 * tileAlpha)));
            g2.drawString(attrib, ax + 6, ay + hfm.getAscent() + 3);
        }
        g2.setFont(oldFont);
    }

    /** Camera elevation above the ecliptic used by the sphere lighting frame (deg); 90 = top-down. */
    private double viewElevDeg() { return 90.0 - Math.toDegrees(viewPitchRad); }

    // ---- Perspective camera: yaw (about the vertical axis) then pitch (about the screen X axis),
    // with a real perspective divide so zooming reads as the camera dollying forward through 3-D
    // rather than a flat magnification. The camera sits `cameraDistanceMeters()` behind the target
    // along the view axis; `currentZoom` still sets the on-screen scale AT the target plane, so all
    // the framing / focus / ruler logic is unchanged, but off-plane depth now foreshortens and
    // parallaxes. A very large focal length would reduce this back to the old orthographic look. ----

    /** Focal length in pixels; ties the field of view to the viewport height (FOV ≈ 34°).
     *  Larger = gentler perspective and the camera stays farther back for the same framing,
     *  so distant companions (a planet's moon) stay in front of the camera as you zoom in. */
    private static final double FOCAL_K = 1.6;
    private double focalPx() { int h = getHeight(); return FOCAL_K * (h > 0 ? h : 1000); }
    /**
     * Distance (m) from the camera to the target plane. Normally {@code focalPx / (m2px * zoom)},
     * so the target-plane scale equals the zoom. But when following a body, we never let the eye
     * dolly closer than the whole followed system's radius — otherwise, zooming in on a planet
     * puts the eye <em>inside</em> its moons' orbits, and a moon on the near side of its orbit
     * ends up in front of (or behind) the near plane and gets culled, so it flickers out as it
     * orbits and vanishes entirely once zoomed past ~4000×. Holding the eye back by the system
     * radius keeps every moon in front of the camera at any zoom; the planet still fills the frame
     * because the on-screen scale is set independently (see {@link #perspScale}). The only cost is
     * a gentler perspective once you are zoomed in tight, which is unnoticeable at that scale. */
    private double cameraDistanceMeters() {
        double base = focalPx() / (METERS_TO_PIXELS * currentZoom);
        return Math.max(base, followSystemRadiusMeters());
    }
    /**
     * Radius (m) of the system currently being followed — the outermost moon apoapsis of a
     * followed planet, or the parent-planet distance of a followed moon — with a margin, so the
     * camera stays outside it. Zero when nothing is followed (then the camera behaves exactly as
     * the plain {@code focalPx / (m2px * zoom)} dolly, unchanged from before).
     */
    private double followSystemRadiusMeters() {
        int i = cameraFocusIndex;
        if (i < 0) return 0.0;
        if (followingMoon && cameraFocusMoon >= 0 && cameraFocusMoon < MOON_A_M[i].length) {
            int m = cameraFocusMoon;
            double e = moonEcc(i, m);
            return MOON_A_M[i][m] * (1.0 + e) * 1.3;
        }
        if (followingPlanet) {
            double maxApo = 0.0;
            for (int m = 0; m < MOON_A_M[i].length; m++) {
                double e = moonEcc(i, m);
                maxApo = Math.max(maxApo, MOON_A_M[i][m] * (1.0 + e));
            }
            return maxApo * 1.3;
        }
        return 0.0;
    }
    /** Pixels per metre for a point at view-depth {@code zv} (m toward the viewer from the target). */
    private double perspScale(double zv) {
        double camDist = cameraDistanceMeters();
        double s = METERS_TO_PIXELS * currentZoom;      // target-plane scale (framing invariant)
        double d = camDist - zv;                        // distance from the camera along the view axis
        double near = camDist * 1e-3;
        if (d < near) d = near;                         // near clamp: never divide by ~0 behind the eye
        return s * camDist / d;                          // == s at the target plane (zv = 0)
    }
    /** True if a world point is in front of the near plane (safe to project / draw). */
    private boolean inFront(double worldX, double worldY, double worldZ) {
        return viewDepth(worldX, worldY, worldZ) < cameraDistanceMeters() * (1.0 - 1e-3);
    }

    /** Pixels per metre at a body's centre — its on-screen size scale under perspective. */
    private double perspScaleAt(double worldX, double worldY, double worldZ) {
        return perspScale(viewDepth(worldX, worldY, worldZ));
    }

    /**
     * Screen-space direction (dx, dy; y down) of a WORLD direction, rotated by the
     * view yaw+pitch but WITHOUT the perspective divide. Used for lighting: the Sun
     * can sit behind the camera when zoomed in on a body, which would garble a
     * direction taken from projected positions, but a pure rotation of the true
     * world Sun→body vector is always valid. Magnitude is irrelevant (the shader
     * normalises), so only the orientation matters.
     */
    private double[] worldDirToScreen(double wx, double wy, double wz) {
        double xv = -(wx * viewCosY - wy * viewSinY);
        double yr = wx * viewSinY + wy * viewCosY;
        double screenDown = yr * viewCosP - wz * viewSinP;
        return new double[]{xv, screenDown};
    }

    /**
     * Project a world point to screen x (px). Needs Z because the perspective divide uses depth.
     *
     * <p>Screen right is {@code −(dx·cos yaw − dy·sin yaw)}: with screen up along −world-y at zero
     * yaw and the eye on the +z side, a right-handed view frame (right × up = toward the eye)
     * needs right = −world-x. (The sign was once +, which drew the whole scene mirror-imaged —
     * the planets went round clockwise seen from ecliptic north and the constellations were
     * reversed.)
     */
    private double projX(double worldX, double worldY, double worldZ) {
        double dx = worldX - camX, dy = worldY - camY, dz = worldZ - camZ;
        double xv = -(dx * viewCosY - dy * viewSinY);
        double yr = dx * viewSinY + dy * viewCosY;
        double zv = yr * viewSinP + dz * viewCosP;
        return getWidth() / 2.0 + xv * perspScale(zv);
    }
    /** Project a world point to screen y (px): yaw rotates in-plane, pitch tilts, then perspective. */
    private double projY(double worldX, double worldY, double worldZ) {
        double dx = worldX - camX, dy = worldY - camY, dz = worldZ - camZ;
        double yr = dx * viewSinY + dy * viewCosY;
        double zv = yr * viewSinP + dz * viewCosP;
        double screenDown = yr * viewCosP - dz * viewSinP;
        return getHeight() / 2.0 + screenDown * perspScale(zv);
    }
    /**
     * Camera-forward depth of a world point (metres along the view direction); larger = nearer the
     * viewer. Drives the painter's-algorithm draw order so nearer bodies occlude farther ones —
     * the main depth cue once the view is tilted off top-down.
     */
    private double viewDepth(double worldX, double worldY, double worldZ) {
        double dx = worldX - camX, dy = worldY - camY, dz = worldZ - camZ;
        double yr = dx * viewSinY + dy * viewCosY;
        return yr * viewSinP + dz * viewCosP;
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
        double[] pole;            // world spin pole the geometry was built for
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

    /** Flat surface colour for moons that have no texture map (a neutral rock grey). */
    private static final int MOON_FLAT_RGB = 0xFFB5B0A8;

    /**
     * Register (once) a tiny solid-colour texture so a body without a surface map can still be
     * drawn by {@link #drawTexturedSphere} — the same lit sphere with a real terminator and true
     * ray-traced eclipse shadows — instead of a flat gradient. Returns the cache key to pass as
     * the texture key. Keyed by colour, so all moons sharing a colour reuse one entry.
     */
    private String ensureFlatTexture(int argb) {
        String key = "flat_" + Integer.toHexString(argb);
        if (!texPixels.containsKey(key)) {
            texPixels.put(key, new int[]{argb, argb, argb, argb});   // 2x2 solid
            texSize.put(key, new int[]{2, 2});
        }
        return key;
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
     * Spin angle for Earth, phase-locked to real time so the geography lines up with the clock:
     * the sub-solar point (local solar noon) sits at longitude 0° at 12:00 UTC and moves 15°/hour
     * west after that. We take the true Sun direction (Earth's real orbital position) to find which
     * body-longitude currently faces the Sun, then choose the spin that places the correct
     * geographic longitude there — so "jump to today" shows the hemisphere that is actually in
     * daylight now. The map is equirectangular with 0° (Greenwich) at its centre (u = 0.5), and the
     * surface samples u = frac(-(lon + spin)/2π); solving u for the wanted longitude gives the spin
     * below. Rotation rate still comes out right because the Sun direction drifts with the orbit.
     */
    private double earthSpinAngle() {
        // Wanted sub-solar geographic longitude (mean sun): 0 at 12:00 UTC, -15°/h afterwards.
        java.time.LocalDateTime dt = simDateTime();
        double utcH = (dt != null)
                ? dt.getHour() + dt.getMinute() / 60.0 + dt.getSecond() / 3600.0
                : 12.0;
        double lambda = -(utcH - 12.0) * Math.PI / 12.0;   // radians, east positive
        return subSolarSpin(planetPole(EARTH_INDEX), -planetX[2], -planetY[2], -planetZ[2], lambda);
    }

    // ---- Pure texture-orientation mapping (static, so the render datum is testable) ----

    /**
     * Longitude (rad) in a body's tilt frame that currently faces the Sun; {@code (sx,sy,sz)}
     * is the body→Sun direction in world coords. This is the basis {@code buildSphereGeom}
     * uses (A = worldX leaned by obliquity, B = worldY); the view rotation cancels here.
     */
    static double frameSubSolarLon(double obliquityDeg, double sx, double sy, double sz) {
        return frameSubSolarLon(obliquityPole(obliquityDeg), sx, sy, sz);
    }

    /**
     * Longitude (rad) in the body frame of spin pole {@code pole} (world axes) that faces the
     * direction (sx, sy, sz): the frame's reference A is world +x with its pole component
     * removed, B = pole × A — the same basis {@link #bodyFrame(double[])} builds.
     */
    static double frameSubSolarLon(double[] pole, double sx, double sy, double sz) {
        double[] ab = poleBasis(pole);
        return Math.atan2(sx * ab[3] + sy * ab[4] + sz * ab[5], sx * ab[0] + sy * ab[1] + sz * ab[2]);
    }

    /** {A, B} (6 doubles) of a pole's equatorial basis in world axes; see {@link #frameSubSolarLon(double[], double, double, double)}. */
    static double[] poleBasis(double[] p) {
        double dot = p[0];                                         // worldX · pole
        double ax = 1 - dot * p[0], ay = -dot * p[1], az = -dot * p[2];
        double al = Math.sqrt(ax * ax + ay * ay + az * az);
        if (al < 1e-6) { ax = 0; ay = 1; az = 0; al = 1; }       // pole along world x: use world y
        ax /= al; ay /= al; az /= al;
        return new double[]{ax, ay, az, p[1] * az - p[2] * ay, p[2] * ax - p[0] * az, p[0] * ay - p[1] * ax};
    }

    /** The older tilt convention: the pole leaned off the ecliptic normal toward world +x by the obliquity. */
    static double[] obliquityPole(double obliquityDeg) {
        double e = Math.toRadians(obliquityDeg);
        return new double[]{Math.sin(e), 0, Math.cos(e)};
    }

    /**
     * Spin pole (unit, world axes) of body {@code i}: the real IAU pole for the eight planets — so
     * Saturn's rings open and close on the right dates and every planet's equator is where it
     * really is — and the obliquity convention for the bodies with no IAU model here.
     */
    double[] planetPole(int i) {
        if (i >= 0 && i < IAU_RA0.length) {
            double[] q = iauPoleEcliptic(i, bodyTime(i));
            return eclToWorld(q[0], q[1], q[2]);
        }
        return obliquityPole(OBLIQUITY_DEG[i]);
    }

    /** The Moon's spin pole (world axes) from its IAU orientation model. */
    private double[] lunaPole() {
        double[] M = MoonEphemeris.bodyToEcliptic(Astro.ttSeconds(bodyTime(EARTH_INDEX)));
        return eclToWorld(M[2], M[5], M[8]);
    }

    /**
     * Spin for the Moon that puts its real sub-solar selenographic longitude (from the IAU
     * orientation model, librations included) at the Sun-facing point — so the near side faces
     * the Earth and Tranquility Base is where Apollo 11 left it.
     */
    private double lunaSpin(double moonWX, double moonWY, double moonWZ) {
        double t = Astro.ttSeconds(bodyTime(EARTH_INDEX));
        double[] M = MoonEphemeris.bodyToEcliptic(t);
        // Moon→Sun direction, world axes (Sun at the heliocentric origin), then into the body frame.
        double sx = sunBarycenterX - moonWX, sy = sunBarycenterY - moonWY, sz = -moonWZ;
        double ex = -sy, ey = sx, ez = sz;                         // world {ye,−xe,ze} → ecliptic
        double bx = M[0] * ex + M[3] * ey + M[6] * ez, by = M[1] * ex + M[4] * ey + M[7] * ez;
        double lambda = Math.atan2(by, bx);
        return subSolarSpin(lunaPole(), sx, sy, sz, lambda);
    }

    /**
     * Spin angle (rad) that makes the renderer place geographic east longitude {@code lambda}
     * at the sub-solar point, given the body→Sun direction. Inverse of {@link #renderedEastLon}.
     */
    static double subSolarSpin(double obliquityDeg, double sx, double sy, double sz, double lambda) {
        return frameSubSolarLon(obliquityDeg, sx, sy, sz) - lambda;
    }

    /** {@link #subSolarSpin(double, double, double, double, double)} for a body with spin pole {@code pole}. */
    static double subSolarSpin(double[] pole, double sx, double sy, double sz, double lambda) {
        return frameSubSolarLon(pole, sx, sy, sz) - lambda;
    }

    /**
     * Geographic east longitude (rad, wrapped to ±π) the shader samples at tilt-frame longitude
     * {@code lon0} under {@code spin}: the prime meridian sits at frame longitude {@code spin}, so
     * λ = lon0 − spin, and the map lookup is {@code u = ½ + (lon0 − spin)/2π} (Greenwich at the
     * map centre). Used to verify the sub-solar meridian lands where it should.
     */
    static double renderedEastLon(double lon0, double spin) {
        double v = (lon0 - spin) % (2 * Math.PI);
        if (v < -Math.PI) v += 2 * Math.PI;
        else if (v > Math.PI) v -= 2 * Math.PI;
        return v;
    }

    /**
     * Spin angle for planet {@code i} (0..7), anchored to its real IAU orientation so the
     * meridian rendered facing the Sun is the one truly at local noon. Generalises
     * {@link #earthSpinAngle}: the wanted sub-solar geographic longitude comes from the IAU
     * prime-meridian model ({@link #iauSubSolarEastLon}) instead of the wall clock, and is
     * placed at the Sun-facing point of the sim's tilt frame with the same construction. As
     * with Earth, the rotation <em>rate</em> falls out correct because the Sun direction
     * drifts with the orbit — and the whole thing is a pure function of {@code simTimeSec},
     * so it stays right at any time-warp speed and under any camera rotation.
     */
    private double bodySpinAngle(int i) {
        double lambda = iauSubSolarEastLon(i, bodyTime(i));           // real sub-solar east longitude (as seen, in the light-time view)
        return subSolarSpin(planetPole(i), -planetX[i], -planetY[i], -planetZ[i], lambda);
    }

    /**
     * Spin angle used by the renderer for body {@code i}: Earth stays phase-locked to the
     * civil clock, the other seven planets use their IAU-anchored real orientation, and any
     * further body (dwarf/minor planets with no rotation datum) keeps a steady sidereal spin.
     */
    private double planetSpin(int i) {
        if (i == 2) return earthSpinAngle();
        if (i >= 0 && i < IAU_W0.length) return bodySpinAngle(i);
        double p = ROTATION_PERIOD_H[i];
        return p == 0 ? 0 : 2 * Math.PI * bodyTime(i) / (p * 3600.0);
    }

    /**
     * Orthonormal body frame in view space (x right, y up, z toward viewer):
     * returns {poleX,poleY,poleZ, ax,ay,az, bx,by,bz}. The spin axis is the true
     * obliquity off the ecliptic normal, and both it and the longitude reference
     * (world +x) are carried into view space through the SAME yaw+pitch rotation
     * the projection uses — so orbiting the camera (yaw) turns the globe to show a
     * new face, instead of leaving the texture pinned to the screen while only the
     * shadow moves.
     */
    private double[] bodyFrame(double obliquityDeg) { return bodyFrame(obliquityPole(obliquityDeg)); }

    /** {@link #bodyFrame(double)} for a spin pole given directly (unit vector, world axes). */
    private double[] bodyFrame(double[] poleW) {
        double[] ab = poleBasis(poleW);
        double[] pole = worldToView(poleW[0], poleW[1], poleW[2]);
        double[] a = worldToView(ab[0], ab[1], ab[2]);
        double[] b = worldToView(ab[3], ab[4], ab[5]);
        return new double[]{pole[0], pole[1], pole[2], a[0], a[1], a[2], b[0], b[1], b[2]};
    }

    /**
     * Rotate a world direction (x,y in the ecliptic plane, z up) into the view
     * frame (x right, y up, z toward viewer) using the current yaw + pitch — the
     * exact rotation behind {@link #projX}/{@link #projY} (screen y is down there,
     * so the up axis is negated here).
     */
    private double[] worldToView(double wx, double wy, double wz) {
        double inPlane = wx * viewSinY + wy * viewCosY;       // component that pitch tilts
        double xv = -(wx * viewCosY - wy * viewSinY);         // proper rotation (see projX)
        double yv = -(inPlane * viewCosP - wz * viewSinP);    // view up = -(screen down)
        double zv = inPlane * viewSinP + wz * viewCosP;       // toward the viewer
        return new double[]{xv, yv, zv};
    }

    /**
     * Unit Sun→body light direction in the view frame (x right, y up, z toward
     * viewer). Computed from the true world Sun-to-body vector, so day/night
     * terminators and eclipse-shadow geometry are exact from any camera angle —
     * not an elevation-only screen reconstruction.
     */
    private double[] viewLight(double bodyX, double bodyY, double bodyZ) {
        double[] v = worldToView(sunBarycenterX - bodyX, sunBarycenterY - bodyY, -bodyZ);
        double n = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (n < 1e-9) return new double[]{0, 0, 1};
        return new double[]{v[0] / n, v[1] / n, v[2] / n};
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
                                       double pr, double[] pole, double spin,
                                       boolean emissive, double lx, double ly, double lz) {
        return drawTexturedSphere(g2, key, px, py, pr, pole, spin, emissive,
                lx, ly, lz, null, 0, 0);
    }

    /**
     * As {@link #drawTexturedSphere}, plus optional eclipse shadows: {@code occ} is a
     * flat {@code [cx,cy,radius, ...]} list of occluding bodies in screen pixels
     * ({@code occN} entries). Any surface pixel whose ray toward the Sun passes behind
     * an occluder's disc is darkened, giving moon shadow-transits on a planet and a
     * planet's shadow falling across a moon (lunar eclipse).
     */
    private boolean drawTexturedSphere(Graphics2D g2, String key, double px, double py,
                                       double pr, double[] pole, double spin,
                                       boolean emissive, double lx0, double ly0, double lz0,
                                       double[] occ, int occN, double sunAlpha) {
        if (!loadTexture(key)) return false;
        int R = (int) Math.ceil(pr);
        if (R < 2) return false;

        int n = Math.min(2 * R + 1, SPHERE_RASTER_CAP);
        int[] tex = texPixels.get(key);
        int tw = texSize.get(key)[0], th = texSize.get(key)[1];

        SphereGeom geom = geomCache.get(key);
        if (geom == null || geom.n != n || !samePole(geom.pole, pole)) {
            geom = buildSphereGeom(n, pole, th);
            geomCache.put(key, geom);
        }

        double ln = Math.sqrt(lx0 * lx0 + ly0 * ly0 + lz0 * lz0);
        if (ln < 1e-9) ln = 1;
        double[] L = { lx0 / ln, ly0 / ln, lz0 / ln };   // view-frame Sun direction (exact)
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
        double cloudSpin = spin - (2 * Math.PI * simTimeSec / (25.0 * 24.0 * 3600.0));
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
                double[] fr = bodyFrame(pole);
                gpuFilled = r.shade(out, n, key, tex, tw, th,
                        new double[]{fr[0], fr[1], fr[2]},
                        new double[]{fr[3], fr[4], fr[5]},
                        new double[]{fr[6], fr[7], fr[8]}, L,
                        spin, cloudSpin, ambient, emissive, earthLike,
                        nightTex, nTw, nTh, cloudTex, cTw, cTh, oceanGlint,
                        occ, doShadows ? occN : 0, sunAlpha);
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
            // Frame longitude increases eastward (right-handed about the pole); the prime meridian
            // sits at frame longitude `spin`, and Greenwich is the map's centre column.
            double u = 0.5 + (lon0[p] - spin) * INV_2PI;
            u -= Math.floor(u);
            int tx = (int) (u * tw);
            if (tx >= tw) tx = tw - 1;
            int rgb = tex[ty * tw + tx];
            if (emissive) { out[p] = 0xFF000000 | rgb; continue; }

            float d = gnx[p] * lx + gny[p] * ly + gnz[p] * lz;
            if (d < 0) d = 0;
            double shade = ambient + (1 - ambient) * d;
            double ecl = 1.0;                              // fraction of the Sun still visible (1 = full sun)
            if (doShadows) {
                // True ray-traced cast shadow (1 = full sun, 0 = deep umbra), applied to the WHOLE
                // surface — direct sun and ambient alike — so the shadow reads as one complete disc
                // that carries on across the day/night terminator instead of being clipped at it.
                // City lights are added afterwards, so they still shine through the shadowed ground.
                ecl = eclipseFactor(gnx[p], gny[p], gnz[p], lxd, lyd, lzd, occ, occN, sunAlpha);
                ecl = perceptualEclipse(ecl);
                shade *= ecl;
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
                double cu = 0.5 + (lon0[p] - cloudSpin) * INV_2PI; cu -= Math.floor(cu);   // same mapping as the surface
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
            // Night-side city lights (emissive), dimmed under cloud cover. Driven only by the
            // day/night terminator — an eclipse umbra darkens the ground but does not switch city
            // lights on, so a day-side umbra reads as a plain dark shadow disc.
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
     * Sun-visibility multiplier (≈0 in the umbra, 1 in full sun) for a lit surface point,
     * modelling the Sun as a real <em>extended disc</em> rather than a point source — the
     * physics that sizes an eclipse correctly. The point sits on the unit sphere at
     * ({@code nx,ny,nz}) in the receiver's view frame; {@code (lx,ly,lz)} is the unit Sun
     * direction; {@code occ} lists occluders as {x, y, z, radius} in receiver-radius units;
     * {@code sunAlpha} is the Sun's angular radius (radians) as seen from the receiver.
     *
     * <p>As seen from the surface point, the occluder is a disc of angular radius
     * {@code α_o = radius / distance} whose centre sits {@code θ = miss / distance} from the
     * Sun's centre. The factor returned is {@code 1 − (fraction of the Sun's disc the
     * occluder covers)}. This makes the geometry emergent and real:
     * <ul>
     *   <li><b>Total</b> (α_o ≥ sunAlpha, θ ≤ α_o − sunAlpha): Sun fully hidden → 0. The
     *       full-shadow footprint is a near-point when α_o ≈ sunAlpha, so the Moon's umbra
     *       on Earth is a thin track — not a disc a third of the planet wide.</li>
     *   <li><b>Annular</b> (α_o &lt; sunAlpha, on-axis): a bright ring of Sun remains, so the
     *       factor bottoms out at {@code 1 − (α_o/sunAlpha)²} instead of reaching 0.</li>
     *   <li><b>Partial/penumbra</b>: the wide, soft region of fractional coverage.</li>
     * </ul>
     * The result depends only on relative geometry, so it is invariant under camera rotation.
     */
    static double eclipseFactor(double nx, double ny, double nz,
                                double lx, double ly, double lz,
                                double[] occ, int occN, double sunAlpha) {
        double factor = 1.0;
        double rs = sunAlpha > 1e-9 ? sunAlpha : 1e-9;   // Sun angular radius (guard div-by-zero)
        double rs2 = rs * rs;
        for (int k = 0; k < occN; k++) {
            double ox = occ[4 * k], oy = occ[4 * k + 1], oz = occ[4 * k + 2], orad = occ[4 * k + 3];
            // Vector from the surface point to the occluder centre.
            double wx = ox - nx, wy = oy - ny, wz = oz - nz;
            double along = wx * lx + wy * ly + wz * lz;   // distance toward the Sun
            if (along <= 0) continue;                     // occluder is not sunward -> no shadow
            double perp2 = wx * wx + wy * wy + wz * wz - along * along;
            if (perp2 < 0) perp2 = 0;
            // Angular geometry as seen from this surface point.
            double ro = orad / along;                     // occluder angular radius
            double d  = Math.sqrt(perp2) / along;          // angular separation of the two disc centres
            double blocked;                                // fraction of the Sun's disc hidden
            if (d >= rs + ro) {
                continue;                                  // discs disjoint -> no shadow
            } else if (d <= Math.abs(rs - ro)) {
                double rmin = Math.min(rs, ro);
                blocked = (rmin * rmin) / rs2;             // one disc fully inside the other (total/annular core)
            } else {
                double ro2 = ro * ro, d2 = d * d;          // partial overlap: area of the circular lens
                double a1 = Math.acos(Math.max(-1, Math.min(1, (d2 + rs2 - ro2) / (2 * d * rs))));
                double a2 = Math.acos(Math.max(-1, Math.min(1, (d2 + ro2 - rs2) / (2 * d * ro))));
                double tri = 0.5 * Math.sqrt(Math.max(0,
                        (-d + rs + ro) * (d + rs - ro) * (d - rs + ro) * (d + rs + ro)));
                double lens = rs2 * a1 + ro2 * a2 - tri;
                blocked = lens / (Math.PI * rs2);
            }
            if (blocked < 0) blocked = 0; else if (blocked > 1) blocked = 1;   // guard FP cancellation
            factor *= 1.0 - blocked;
        }
        return factor;
    }

    /**
     * Perceptual darkening curve for a cast eclipse shadow. {@link #eclipseFactor} returns the
     * true fraction of the Sun still visible (photometrically exact), but a partly-eclipsed
     * patch of ground is still brightly lit — a 60%-covered Sun looks essentially normal from
     * space, and only as totality approaches does the ground go dark. Rendering the raw fraction
     * paints the whole penumbra (which spans roughly half of Earth) as a big grey blob. This
     * curve keeps the penumbra bright and concentrates the visible shadow into the umbra and its
     * immediate deep-partial surround — so the shadow reads as the thin track people recognise,
     * not a continent-sized smudge. {@code visible} is 1 = full Sun, 0 = totality.
     */
    static double perceptualEclipse(double visible) {
        double o = 1.0 - visible;                            // 0 = full sun, 1 = total
        // Only near-total obscuration darkens appreciably; the broad partial penumbra stays bright,
        // so the visible shadow hugs the umbra track instead of spreading across the disc.
        double o2 = o * o, o6 = o2 * o2 * o2;                // ^6
        return 1.0 - o6;
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

    /** Poles equal to ~0.2″ (the IAU poles precess slowly, so baked geometry stays valid for years). */
    private static boolean samePole(double[] a, double[] b) {
        return a != null && Math.abs(a[0] - b[0]) < 1e-6 && Math.abs(a[1] - b[1]) < 1e-6 && Math.abs(a[2] - b[2]) < 1e-6;
    }

    private SphereGeom buildSphereGeom(int n, double[] pole, int th) {
        SphereGeom geom = new SphereGeom(n);
        geom.pole = pole.clone();
        double[] fr = bodyFrame(pole);
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
        double[] pole;                     // Saturn's world spin pole the geometry was built for
        RingGeom(int n, double half) {
            this.n = n; this.half = half; int s = n * n;
            sTexY = new int[s]; sLon0 = new double[s];
            snx = new float[s]; sny = new float[s]; snz = new float[s];
            ringARGB = new int[s]; rpx = new float[s]; rpy = new float[s]; rpz = new float[s];
            rMag2 = new float[s];
        }
    }

    private RingGeom buildRingGeom(int n, double half, int th, int[] lut, int lutN, double[] poleW) {
        RingGeom G = new RingGeom(n, half);
        G.pole = poleW.clone();
        double[] fr = bodyFrame(poleW);
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

    /** Normal optical depth of the ring from the ring map's opacity (alpha = 1 − e^(−τ) face-on). */
    static double ringTau(int alpha255) {
        double a = Math.min(0.995, alpha255 / 255.0);
        return -Math.log(1 - a);
    }

    /**
     * Brightness (I/F up to a constant) of a ring slab with normal optical depth τ, lit at
     * μ0 = sin(Sun elevation) and seen at μ = sin(viewer elevation): Lommel–Seeliger
     * single scattering, reflected on the lit face and diffusely transmitted on the unlit face.
     */
    static double ringReflect(double tau, double mu0, double mu, boolean litSide) {
        if (litSide) return mu0 / (mu0 + mu) * (1 - Math.exp(-tau * (1 / mu0 + 1 / mu)));
        if (Math.abs(mu0 - mu) < 1e-6) return tau / mu * Math.exp(-tau / mu);
        return mu0 / (mu0 - mu) * (Math.exp(-tau / mu0) - Math.exp(-tau / mu));
    }

    /**
     * Saturn's ring-opening angles (degrees) at a UTC sim time, from its IAU pole: {B, B′} —
     * the elevation of the Earth and of the Sun above the ring plane. B′ = 0 is Saturn's
     * equinox (rings edge-on to the Sun, dark); B = 0 a ring-plane crossing seen from Earth.
     */
    static double[] saturnRingTilts(double simSec) {
        double[] pole = iauPoleEcliptic(5, simSec);
        double[] sat = planetEclipticAU(5, simSec), earth = planetEclipticAU(EARTH_INDEX, simSec);
        double[] toE = {earth[0] - sat[0], earth[1] - sat[1], earth[2] - sat[2]};
        double[] toS = {-sat[0], -sat[1], -sat[2]};
        return new double[]{elevationDeg(pole, toE), elevationDeg(pole, toS)};
    }

    private static double elevationDeg(double[] pole, double[] v) {
        double n = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return Math.toDegrees(Math.asin((pole[0] * v[0] + pole[1] * v[1] + pole[2] * v[2]) / n));
    }

    private boolean drawSaturn(Graphics2D g2, double px, double py, double pr,
                               double spin, boolean emissive, boolean drawRing,
                               double lx0, double ly0, double lz0) {
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
        double[] poleW = planetPole(5);
        if (ringGeom == null || ringGeom.n != n || ringGeom.half != half || !samePole(ringGeom.pole, poleW)) {
            ringGeom = buildRingGeom(n, half, th, lut, lutN, poleW);
        }
        RingGeom G = ringGeom;

        double[] fr = bodyFrame(poleW);
        double pX = fr[0], pY = fr[1], pZ = fr[2];
        double ln = Math.sqrt(lx0 * lx0 + ly0 * ly0 + lz0 * lz0);
        if (ln < 1e-9) ln = 1;
        double lx = lx0 / ln, ly = ly0 / ln, lz = lz0 / ln;   // view-frame Sun direction (exact)
        double lDotPole = lx * pX + ly * pY + lz * pZ;
        final double amb = 0.10;
        // Ring photometry: a thin slab of single-scattering particles (Chandrasekhar's
        // Lommel–Seeliger slab). μ0 = sine of the Sun's elevation above the ring plane,
        // μ = the viewer's; τ (normal optical depth) comes from the ring map's opacity. The lit
        // face reflects μ0/(μ0+μ)·(1−e^(−τ(1/μ0+1/μ))); the unlit face is seen only by light that
        // diffused through, μ0/(μ0−μ)·(e^(−τ/μ0)−e^(−τ/μ)). So the rings go dark as the Sun reaches
        // the ring plane (Saturn's equinox) and glow faintly when seen from their unlit side.
        double mu0 = Math.max(1e-4, Math.abs(lDotPole));
        double mu = Math.max(1e-3, Math.abs(pZ));
        boolean litSide = lDotPole * pZ > 0;
        // Normalise so a mid-opacity ring lit and seen at 30° keeps the old look (shade 0.9).
        final double refShade = 0.9 / ringReflect(0.5, 0.5, 0.5, true);

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
                double u = 0.5 + (G.sLon0[p] - spin) * INV_2PI; u -= Math.floor(u);
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
                                // Sunlight crossing the ring slab obliquely: transmitted e^(−τ/μ0).
                                shade *= Math.exp(-ringTau(a) / mu0);
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
                rHit = true; zR = G.rpz[p];
                int a0 = (rbase >>> 24) & 0xFF;
                double tau = ringTau(a0);
                // Opacity along the line of sight grows as the rings turn edge-on.
                rA = (int) Math.round(255 * (1 - Math.exp(-tau / mu)));
                double rshade = emissive ? 1.0 : refShade * ringReflect(tau, mu0, mu, litSide);
                if (!emissive) {                                     // planet shadow on ring
                    double bb = G.rpx[p] * lx + G.rpy[p] * ly + G.rpz[p] * lz;
                    double cc = G.rMag2[p] - 1.0;
                    double disc = bb * bb - cc;
                    if (disc > 0 && bb < 0) rshade *= 0.04;
                }
                rColor = shadeRGB(rbase & 0xFFFFFF, Math.min(1.6, rshade));
                if (rA <= 0) { rHit = false; }
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

        // Determine the scale distance in pixels, choosing a nice round number in a unit
        // appropriate to the current zoom: AU out at planetary scales, kilometres closer in,
        // metres when zoomed onto a surface. A second line always shows the light-travel time
        // across the bar (light-hours down to light-nanoseconds), so "ls" is covered too.
        // In the Earth Explorer the on-screen scale is set by geZoom (globe and tile views share the
        // same ground scale by construction), so the same ruler reads out km/m across the surface.
        double pixelsPerMeter = earthExplorerActive
                ? (Math.min(getWidth(), getHeight()) * 0.5 * geZoom) / RADIUS_M[EARTH_INDEX]
                : METERS_TO_PIXELS * currentZoom;
        double minPixels = 80.0; // Minimum pixels for the scale bar
        double maxPixels = 150.0; // Maximum pixels for the scale bar

        double minMeters = minPixels / pixelsPerMeter;   // shortest physical length the bar may show

        double unitMeters; String unitName;
        if (minMeters >= 0.001 * AU)  { unitMeters = AU;     unitName = "AU"; }
        else if (minMeters >= 1000.0) { unitMeters = 1000.0; unitName = "km"; }
        else                          { unitMeters = 1.0;    unitName = "m";  }

        // Smallest nice 1-2-5 count in that unit whose bar is at least minPixels, then halved
        // as needed so it never exceeds maxPixels.
        double niceCount = findNiceRoundNumber(minMeters / unitMeters);
        double scaleMeters = niceCount * unitMeters;
        double scalePixels = scaleMeters * pixelsPerMeter;
        while (scalePixels > maxPixels && niceCount > 1e-12) {
            niceCount /= 2.0;
            scaleMeters = niceCount * unitMeters;
            scalePixels = scaleMeters * pixelsPerMeter;
        }

        String distanceLabel = formatScaleCount(niceCount, unitName);
        // Second line: light-travel time normally, or the surface coordinates in the explorer.
        String timeLightLabel;
        if (earthExplorerActive) {
            timeLightLabel = String.format("%.4f°%s  %.4f°%s",
                    Math.abs(geLat), geLat >= 0 ? "N" : "S",
                    Math.abs(geLon), geLon >= 0 ? "E" : "W");
        } else {
            timeLightLabel = formatLightTime(scaleMeters);
        }
        String caption = earthExplorerActive
                ? (geZoom > GE_GLOBE_MAX ? (tilesReachable ? "EARTH · ESRI" : "EARTH · OFFLINE") : "EARTH")
                : "SCALE";

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
        g2.drawString(caption, barStartX, scaleY + 4);

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

        // Second line below the bar: light-time (centred) or coordinates (left-aligned) in the explorer.
        g2.setFont(oldFont.deriveFont(Font.PLAIN, 10f));
        g2.setColor(new Color(160, 180, 215));
        FontMetrics fmP = g2.getFontMetrics();
        int lightLabelWidth = fmP.stringWidth(timeLightLabel);
        int secondX = earthExplorerActive ? barStartX
                : barStartX + (int) (scalePixels / 2) - lightLabelWidth / 2;
        g2.drawString(timeLightLabel, secondX, barY + 18);

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

    /** Format a scale-bar count with its unit, trimming trailing zeros (e.g. "2 km", "0.5 AU"). */
    private String formatScaleCount(double v, String unit) {
        String num = (v >= 1000.0) ? String.format("%,.0f", v) : trimZeros(String.format("%.4f", v));
        return num + " " + unit;
    }

    private static String trimZeros(String s) {
        if (s.indexOf('.') >= 0) {
            s = s.replaceAll("0+$", "");
            if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /**
     * Light-travel time across a physical length, in the most readable unit — light-hours out
     * at planetary distances, down through light-seconds to light-nanoseconds when zoomed in
     * onto a surface (1 m of light-travel ≈ 3.3 light-ns).
     */
    /** "43.2 min", "5.3 h", "12 s" for a duration in seconds. */
    static String formatDuration(double s) {
        if (s < 90) return String.format("%.0f s", s);
        if (s < 5400) return String.format("%.1f min", s / 60);
        return String.format("%.2f h", s / 3600);
    }

    private String formatLightTime(double meters) {
        double ls = meters / 299_792_458.0;                     // light-seconds
        if (ls >= 3600.0) return String.format("%.2f light-hours",   ls / 3600.0);
        if (ls >= 60.0)   return String.format("%.2f light-minutes", ls / 60.0);
        if (ls >= 1.0)    return String.format("%.2f light-seconds", ls);
        if (ls >= 1e-3)   return String.format("%.2f light-ms", ls * 1e3);
        if (ls >= 1e-6)   return String.format("%.2f light-µs", ls * 1e6);
        return String.format("%.2f light-ns", ls * 1e9);
    }

    /**
     * Place and draw every label collected during the paint pass so none overlap. Labels are
     * honoured in priority order (Sun/planets &gt; dwarf planets &gt; moons &gt; minor bodies, with the
     * selected body boosted, ties broken by nearer depth): each claims the first of several
     * candidate slots around its body that is on-screen and clear of already-placed labels, and
     * any label that can't find room is dropped. This declutters crowded views instead of letting
     * names pile on top of each other.
     */
    private void drawPendingLabels(Graphics2D g2) {
        if (pendingLabels.isEmpty()) return;
        pendingLabels.sort((a, b) -> {
            if (a.priority != b.priority) return Integer.compare(b.priority, a.priority);
            if (a.depth != b.depth)       return Double.compare(b.depth, a.depth);   // nearer first
            return Double.compare(b.bodyR, a.bodyR);
        });
        // Candidate directions around a body, tried in order (right, left, up, down, diagonals).
        final double[][] dirs = {{1, 0}, {-1, 0}, {0, -1}, {0, 1}, {1, -1}, {-1, -1}, {1, 1}, {-1, 1}};
        final int W = getWidth(), H = getHeight();
        java.util.ArrayList<Rectangle> placed = new java.util.ArrayList<>();
        Font baseFont = g2.getFont();
        for (LabelReq L : pendingLabels) {
            g2.setFont(baseFont.deriveFont(L.bold ? Font.BOLD : Font.PLAIN, L.size));
            FontMetrics fm = g2.getFontMetrics();
            int tw = fm.stringWidth(L.text), th = fm.getHeight();
            double gap = L.bodyR + 6;
            Rectangle chosen = null;
            search:
            for (int ring = 0; ring < 3 && chosen == null; ring++) {
                double off = gap + ring * (th + 5);
                for (double[] d : dirs) {
                    double ax = L.ax + d[0] * off, ay = L.ay + d[1] * off;
                    int lx = (d[0] > 0) ? (int) ax : (d[0] < 0) ? (int) (ax - tw) : (int) (ax - tw / 2.0);
                    int top = (int) (ay - th / 2.0);
                    Rectangle box = new Rectangle(lx, top, tw, th);
                    if (box.x < 3 || box.y < 3 || box.x + box.width > W - 3 || box.y + box.height > H - 3)
                        continue;
                    boolean clear = true;
                    for (Rectangle r : placed) if (r.intersects(box)) { clear = false; break; }
                    if (clear) { chosen = box; break search; }
                }
            }
            if (chosen == null) continue;   // no room — drop this label
            placed.add(chosen);
            int baseline = chosen.y + fm.getAscent();
            g2.setColor(new Color(0, 0, 0, 170));
            g2.drawString(L.text, chosen.x + 1, baseline + 1);
            g2.setColor(L.color);
            g2.drawString(L.text, chosen.x, baseline);
        }
        g2.setFont(baseFont);
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
    double sliderValueToTimeScale(int sliderVal) {
        int clamped = Math.max(TIME_SLIDER_MIN, Math.min(sliderVal, TIME_SLIDER_MAX));
        double fraction = (clamped - TIME_SLIDER_MIN) / (double)(TIME_SLIDER_MAX - TIME_SLIDER_MIN);
        double ts = TIME_SCALE_MIN + fraction * (TIME_SCALE_MAX - TIME_SCALE_MIN);
        return Math.max(TIME_SCALE_MIN, Math.min(ts, TIME_SCALE_MAX));
    }

    int timeScaleToSliderValue(double ts) {
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

    void updateTimeScaleLabel() {
        if (timeScaleLabel != null) {
            String prefix = simulationPaused ? "Paused" : "Time";
            timeScaleLabel.setText(prefix + " " + formatTimeScale(timeScale));
        }
        updateTimeFieldsFromScale();
    }

    /** Index of the largest unit in {@link #TS_UNIT_SECONDS} whose value is still >= 1 (matches formatTimeScale). */
    static int bestTimeUnitIndex(double ts) {
        if (!Double.isFinite(ts) || ts <= 0) return 0;
        int idx = 0;
        for (int i = 0; i < TS_UNIT_SECONDS.length; i++) {
            if (ts >= TS_UNIT_SECONDS[i]) idx = i;
        }
        return idx;
    }

    /** Reflect the current timeScale into the editable number field + unit combo, without firing their listeners. */
    private void updateTimeFieldsFromScale() {
        if (timeScaleValueField == null || timeScaleUnitCombo == null) return;
        suppressTimeFieldEvents = true;
        try {
            int idx = timeScaleUnitCombo.getSelectedIndex();
            if (idx < 0) idx = bestTimeUnitIndex(timeScale);   // no user choice yet: pick a readable unit
            double value = timeScale / TS_UNIT_SECONDS[idx];
            double abs = Math.abs(value);
            String fmt = abs < 1 ? "%.4f" : abs < 10 ? "%.3f" : abs < 1000 ? "%.2f" : "%.1f";
            timeScaleUnitCombo.setSelectedIndex(idx);
            timeScaleValueField.setText(String.format(fmt, value));
        } finally {
            suppressTimeFieldEvents = false;
        }
    }

    /** Apply the user-typed number against the selected unit, clamping to the allowed range. */
    void applyTimeFieldInput() {
        if (timeScaleValueField == null || timeScaleUnitCombo == null) return;
        int idx = timeScaleUnitCombo.getSelectedIndex();
        if (idx < 0) idx = 0;
        double value;
        try {
            value = Double.parseDouble(timeScaleValueField.getText().trim());
        } catch (NumberFormatException ex) {
            updateTimeFieldsFromScale();   // unreadable entry: snap back to the current scale
            return;
        }
        setTimeScaleAndSync(value * TS_UNIT_SECONDS[idx]);
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

    void setTimeScaleAndSync(double newScale) {
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
        advanceFrame(dtReal);
    }

    /** One simulation frame of {@code dtReal} real seconds (also driven directly for head-less renders). */
    void advanceFrame(double dtReal) {

        // When paused, sim time (and thus orbits + spin) freezes; the camera and
        // UI animations still use real time below so they stay responsive.
        double dtSim = simulationPaused ? 0.0 : dtReal * timeScale;
        simTimeSec += dtSim;

        // Every body's state for the new sim time: integrated planets / minor bodies, the
        // theory-driven moons, and the Sun's barycentric offset (see updateEphemeris).
        updateEphemeris();

        /* ----- CAMERA & AUTO-ZOOM ----- */
        // A playing mission steers the camera onto its spacecraft (and paces the clock).
        missionPlayer.tick(dtReal);
        boolean missionCam = missionPlayer.controlsCamera();
        if (missionCam) {
            cameraIsApproaching = moonCameraIsApproaching = false;
        } else if (followingMoon && cameraFocusIndex >= 0 && cameraFocusMoon >= 0) {
            /* ---------- MOON FOLLOWING ---------- */
            int p = cameraFocusIndex, m = cameraFocusMoon;
            double[] rel = moonRelPos(p, m);   // lunar theory for Earth's Moon, satellite theory otherwise
            // Use barycentric coordinates for camera target
            double targetX = planetX[p] + sunBarycenterX + rel[0];
            double targetY = planetY[p] + sunBarycenterY + rel[1];

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

        } else if (lessonActive() && lessonAimGoal != null) {
            /* ---------- LESSON FRAMING ---------- */
            if (lessonSteer) steerLessonCamera(dtReal);
            cameraIsApproaching = moonCameraIsApproaching = false;
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
        if (missionCam) {
            // camZ was set on the spacecraft by the mission player.
        } else if (followingMoon && cameraFocusIndex >= 0 && cameraFocusMoon >= 0) {
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
        if (earthExplorerActive) {
            double lerp = 1 - Math.exp(-ZOOM_SPEED * dtReal);
            geZoom += (geTargetZoom - geZoom) * lerp;
            if (Math.abs(geTargetZoom - geZoom) < geTargetZoom * 1e-4) geZoom = geTargetZoom;  // settle → cache hits
            if (geTransition < 1.0) {                       // fly-in fade of the atmosphere + HUD
                geTransition = Math.min(1.0, geTransition + dtReal / 0.45);
            }
        }

        if (lessonStudio != null) lessonStudio.tick(dtReal);

        repaint();
    }

    /**
     * Position of moon {@code m} of planet {@code i} relative to the planet's centre, in world
     * axes (metres) — the real 3-D position, including the moon's tilt to the ecliptic (so
     * shadows land at real latitudes and eclipses happen only near a node). Earth's Moon comes
     * from the ELP-2000/82 lunar theory, every other moon from {@link SatelliteEphemeris}.
     */
    private double[] moonRelPos(int i, int m) { return moonOffsetWorld(i, m, bodyTime(i)); }

    /** {@link #moonRelPos} at an arbitrary sim time (used to trace the orbit ring). */
    private double[] moonOffsetWorld(int i, int m, double timeSec) {
        if (i == EARTH_INDEX && m == 0) return earthMoonOffsetWorld(timeSec);
        double[] k = SatelliteEphemeris.positionKm(i, m, Astro.ttSeconds(timeSec));
        return eclToWorld(k[0] * 1000.0, k[1] * 1000.0, k[2] * 1000.0);
    }

    /** Mean orbital eccentricity of moon {@code m} of planet {@code i}. */
    private static double moonEcc(int i, int m) {
        return (i == EARTH_INDEX && m == 0) ? 0.0549 : SatelliteEphemeris.eccentricity(i, m);
    }

    /**
     * Earth-Moon offset in the sim's world convention ({@code {ye, -xe, ze}}, metres) at the
     * given sim time, from the geocentric ELP ephemeris. Added to Earth's world position to
     * place the Moon body, its orbit ring and its eclipse shadow.
     */
    private double[] earthMoonOffsetWorld(double timeSec) {
        double[] s = MoonEphemeris.geocentric(timeSec);
        double lam = s[0], bet = s[1], dm = s[2];
        double cb = Math.cos(bet);
        double xe = dm * cb * Math.cos(lam);   // ecliptic-J2000: x -> vernal equinox
        double ye = dm * cb * Math.sin(lam);
        double ze = dm * Math.sin(bet);
        return new double[]{ ye, -xe, ze };    // world = {ye, -xe, ze}, matching keplerXYZ
    }

    /**
     * Geocentric ecliptic-J2000 position of the Moon (AU) at elapsed sim time, as
     * {@code {x, y, z}} toward the vernal equinox / ecliptic north — the frame JPL Horizons
     * reports geocentric vectors in. Static and pure, for the accuracy tests.
     */
    static double[] moonGeocentricEclipticAU(double simTimeSec) {
        return MoonEphemeris.geocentricEclipticAU(simTimeSec);
    }

    /** Compute system barycenter and set sunBarycenterX/Y accordingly. */
    void computeBarycenter() {
        // Heliocentric frame (default): Sun fixed at the origin, no reflex wobble, so
        // planets sit rock-steady on their orbit rings at any time-acceleration.
        if (!barycentricFrame) {
            sunBarycenterX = 0.0;
            sunBarycenterY = 0.0;
            return;
        }
        // The Sun's real offset from the Solar-System barycentre, from the integrated system.
        sunBarycenterX = sunBaryWX;
        sunBarycenterY = sunBaryWY;
    }

    /**
     * Stage and jump to a real Earth eclipse: pauses the sim, positions the Moon, and
     * frames Earth (solar) or the Moon (lunar). Live-app counterpart of the headless shots.
     */
    /**
     * Advance the sim clock to the next real Earth eclipse of the requested kind (the Moon is
     * on its true ephemeris, so eclipses land on their actual calendar dates — no faking).
     */
    private void jumpToEclipse(boolean solar) {
        double t = nextEarthEclipse(solar, simTimeSec, +1);
        if (!Double.isNaN(t)) goToDate(java.time.LocalDateTime.ofInstant(
                J2000_INSTANT.plusSeconds((long) t), java.time.ZoneOffset.UTC));
    }

    void showEclipse(boolean solar) {
        simulationPaused = true;
        jumpToEclipse(solar);
        updateTimeScaleLabel();
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
            currentZoom = (0.30 * screen) / (MOON_RADIUS_M[2][0] * METERS_TO_PIXELS);
        }
        targetZoom = currentZoom;
        repaint();
    }

    /** Sun's geocentric ecliptic-J2000 longitude (radians) at sim time {@code t}. */
    private static double sunGeoLon(double t) {
        double[] e = planetEclipticAU(EARTH_INDEX, t);        // Earth heliocentric ecliptic
        return wrapAngle(Math.atan2(e[1], e[0]) + Math.PI);   // Sun is 180° opposite
    }

    /** Signed Moon-Sun ecliptic longitude difference in [-π,π]; 0 at new moon, ±π at full. */
    private static double syzygyPhase(double t, boolean solar) {
        double d = MoonEphemeris.geocentric(t)[0] - sunGeoLon(t) - (solar ? 0.0 : Math.PI);
        return wrapAngle(d);
    }

    /**
     * Sim time (s from J2000) of the next real Earth eclipse of the requested kind, scanning
     * from {@code fromSec} in direction {@code dir} (+1 forward). A solar eclipse is a new moon
     * and a lunar eclipse a full moon occurring while the Moon is close enough to a node for
     * its shadow cone to reach (|latitude| under the ecliptic-limit thresholds below). Returns
     * {@code NaN} if none is found within a few years.
     */
    static double nextEarthEclipse(boolean solar, double fromSec, int dir) {
        // A solar eclipse (somewhere on Earth) is possible for |β| up to ~1.58° at conjunction;
        // any lunar eclipse (incl. penumbral) up to ~1.55° at opposition. Use limits just inside
        // those so every real event is caught without spurious near-misses.
        double latLimit = Math.toRadians(solar ? 1.50 : 1.45);
        double step = 6.0 * 3600.0 * Math.signum(dir == 0 ? 1 : dir);
        double t0 = fromSec + step;                 // start just off the current instant
        double p0 = syzygyPhase(t0, solar);
        long maxSteps = (long) Math.ceil((5.0 * 365.25 * DAY) / Math.abs(step));
        for (long k = 1; k <= maxSteps; k++) {
            double t1 = fromSec + (k + 1) * step;
            double p1 = syzygyPhase(t1, solar);
            // Syzygy = phase crosses zero; ignore the ±π wrap discontinuity.
            boolean cross = (p0 <= 0 && p1 > 0) || (p0 >= 0 && p1 < 0);
            if (cross && Math.abs(p1 - p0) < Math.PI) {
                double tS = refineSyzygy(Math.min(t0, t1), Math.max(t0, t1), solar);
                if (Math.abs(MoonEphemeris.geocentric(tS)[1]) < latLimit) return tS;
            }
            t0 = t1; p0 = p1;
        }
        return Double.NaN;
    }

    /** Bisection root of {@link #syzygyPhase} between two straddling times. */
    private static double refineSyzygy(double ta, double tb, boolean solar) {
        double pa = syzygyPhase(ta, solar);
        for (int it = 0; it < 40; it++) {
            double tm = 0.5 * (ta + tb);
            double pm = syzygyPhase(tm, solar);
            if (pa <= 0 && pm >= 0 || pa >= 0 && pm <= 0) { tb = tm; } else { ta = tm; pa = pm; }
            if (tb - ta < 1.0) break;
        }
        return 0.5 * (ta + tb);
    }

    /** Wrap an angle (radians) to [-π, π]. */
    private static double wrapAngle(double a) {
        a %= 2 * Math.PI;
        if (a > Math.PI) a -= 2 * Math.PI;
        if (a < -Math.PI) a += 2 * Math.PI;
        return a;
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

        // B) Solar eclipse: jump to the next real solar eclipse; Moon's umbra falls on Earth.
        jumpToEclipse(true);
        focusPlanetHeadless(2, W, H, 0.85);
        writeShot(dir + "/shot_solar_eclipse.png", W, H);


        // C) Lunar eclipse: jump to the next real lunar eclipse; Moon in Earth's shadow cone.
        jumpToEclipse(false);
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
        goToSimTime(9.0 * 3600.0);
        focusPlanetHeadless(2, W, H, 0.70);
        writeShot(dir + "/shot_earth_night.png", W, H);
        goToSimTime(0.0);

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

        // J–N) Sky from Earth: the planetarium on real dates.
        renderSkyShot(dir + "/shot_sky_eclipse_dallas.png", W, H, "Dallas, USA", 32.7767, -96.7970,
                java.time.LocalDateTime.of(2024, 4, 8, 18, 41), "Sun", 2.0);            // total solar eclipse
        renderSkyShot(dir + "/shot_sky_orion_greenwich.png", W, H, "Greenwich, UK", 51.4779, -0.0015,
                java.time.LocalDateTime.of(2025, 1, 20, 21, 0), null, 110);             // winter evening, looking south
        renderSkyShot(dir + "/shot_sky_jupiter_moons.png", W, H, "Greenwich, UK", 51.4779, -0.0015,
                java.time.LocalDateTime.of(2025, 1, 20, 21, 0), "Jupiter", 0.18);       // Galilean moons
        renderSkyShot(dir + "/shot_sky_moon.png", W, H, "Greenwich, UK", 51.4779, -0.0015,
                java.time.LocalDateTime.of(2025, 3, 3, 19, 0), "Moon", 1.2);            // waxing crescent
        renderSkyShot(dir + "/shot_sky_twilight.png", W, H, "Greenwich, UK", 51.4779, -0.0015,
                java.time.LocalDateTime.of(2025, 3, 3, 18, 20), null, 120);             // just after sunset, looking west
        skyViewActive = false;
    }

    /**
     * Head-less mission frames ({@code -Dsss.missionshot=<dir>[,id,id…]}): each mission at each
     * timeline event, after letting the auto camera and pacing settle for two seconds.
     */
    private void renderMissionShots(String spec) {
        final int W = 1600, H = 1000;
        String[] parts = spec.split(",");
        String dir = parts[0];
        new File(dir).mkdirs();
        setSize(W, H);
        shadingEnabled = true; showLabels = true;
        setViewPitch(Math.toRadians(DEFAULT_VIEW_PITCH_DEG));
        for (Missions.Mission m : Missions.all()) {
            if (parts.length > 1 && !java.util.Arrays.asList(parts).contains(m.id)) continue;
            missionPlayer.start(m);
            int k = 0;
            for (Missions.Event e : m.timeline()) {
                double lead = m.kind == Missions.Kind.LUNAR ? 90 : 1.5 * DAY;
                goToSimTime(Math.max(e.sim - lead, m.span()[0] + 1));
                simulationPaused = false;
                for (int f = 0; f < 150; f++) advanceFrame(1.0 / 60);
                writeShot(String.format("%s/%s_%02d.png", dir, m.id, k++), W, H);
            }
            missionPlayer.stop();
        }
    }

    /** One planetarium frame: place, UTC instant, target to centre on (null = due south/west), field of view. */
    private void renderSkyShot(String path, int w, int h, String place, double lat, double lon,
                               java.time.LocalDateTime utc, String target, double fovDeg) {
        goToDate(utc);
        SkyView sv = skyView();
        sv.setLocation(place, lat, lon, false);
        sv.setFov(fovDeg);
        if (target == null || !sv.lookAt(target)) sv.look(path.contains("twilight") ? 270 : 180, 28);
        skyViewActive = true;
        writeShot(path, w, h);
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
        camZ = planetZ[i];                       // centre on the body's height above the ecliptic too
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
        camZ = planetZ[i] + rel[2];
        double moonR = MOON_RADIUS_M[i][m];
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
        String missionDir = System.getProperty("sss.missionshot");
        if (missionDir != null) {
            SolarSystemSimulation sim = new SolarSystemSimulation();
            sim.computeBarycenter();
            sim.renderMissionShots(missionDir);
            System.out.println("Mission shots complete: " + missionDir);
            System.exit(0);
        }
        String lessonDir = System.getProperty("sss.lessonshot");
        if (lessonDir != null) {
            try { FlatDarkLaf.setup(); } catch (Throwable ignored) { }   // match the real UI
            SolarSystemSimulation sim = new SolarSystemSimulation();
            sim.computeBarycenter();
            JPanel root = new JPanel(new BorderLayout());
            sim.lessonStudio = new LessonStudio(sim, sim.new LessonHost(), sim::onLessonActiveChanged);
            root.add(sim, BorderLayout.CENTER);
            root.add(sim.lessonStudio, BorderLayout.EAST);
            JFrame f = new JFrame();
            f.setUndecorated(true);
            f.setContentPane(root);
            f.setSize(1600, 960);
            f.addNotify();                                     // realise (never shown) so layout is complete
            sim.lessonStudio.renderShots(lessonDir, root, sim::advanceFrame);
            f.dispose();
            System.out.println("Lesson shots complete: " + lessonDir);
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

        AppWindow.open();
    }

    // Simple search function: focus camera on a planet or moon by name
    void searchAndFocus() {
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

    boolean isSearchUIComponent(Component c) {
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
        double[] rel = moonRelPos(planetIndex, moonIndex);   // lunar theory for Earth's Moon, satellite theory otherwise
        // Use barycentric coordinates
        double targetX = planetX[planetIndex] + sunBarycenterX + rel[0];
        double targetY = planetY[planetIndex] + sunBarycenterY + rel[1];
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

    void handleTreeSelection(TreePath path) {
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
    void updateTreeVisibility() {
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
            if (!inFront(worldX, worldY, planetZ[i])) continue;
            double px = projX(worldX, worldY, planetZ[i]);
            double py = projY(worldX, worldY, planetZ[i]);

            // Calculate velocity direction (perpendicular to radius vector)
            double dx = worldX - sunBarycenterX;
            double dy = worldY - sunBarycenterY;
            double dist = Math.sqrt(dx * dx + dy * dy);

            // Velocity is perpendicular to position vector (for circular approximation)
            // For elliptical orbits, we use the actual speed from vis-viva equation
            double vx = -dy / dist;  // perpendicular
            double vy = dx / dist;

            // Scale velocity vector by actual speed. Build the tip as a world-space point (in the
            // ecliptic plane) and project it, so the arrow rotates correctly with camera yaw/pitch
            // rather than being pinned to the screen axes. The world length is chosen so the
            // on-screen size matches the previous behaviour.
            double speed = planetSpeedKmS[i];
            double vLengthWorld = speed * 0.00015 / METERS_TO_PIXELS;
            double tipWX = worldX + vx * vLengthWorld;
            double tipWY = worldY + vy * vLengthWorld;
            double endX = projX(tipWX, tipWY, planetZ[i]);
            double endY = projY(tipWX, tipWY, planetZ[i]);

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

    static final class TreeBody {
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

    static final class BodyTreeRenderer extends DefaultTreeCellRenderer {
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
