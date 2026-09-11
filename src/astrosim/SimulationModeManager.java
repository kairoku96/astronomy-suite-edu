package astrosim;

import static astrosim.Bodies.*;

/**
 * Owns the simulation mode state machine and everything mode-specific:
 *
 *  SANDBOX — free orbit camera, exaggerated visual scale, full time-warp range,
 *            body spawning and vector editing.
 *  SHIP    — true-scale rendering, chase camera latched to the spacecraft,
 *            Newtonian thrust via {@link ShipController}, flight telemetry.
 *  LANDING — entered automatically when the ship descends near a planet inside
 *            its sphere of influence (or by hotkey while inside one): the
 *            camera up-vector aligns to the local vertical so the horizon
 *            matches planetary curvature, telemetry switches to the
 *            surface-relative frame (altitude AGL, climb/sink rate, horizontal
 *            surface speed including the planet's axial rotation), and
 *            touchdown is resolved against the {@link Spacecraft#CRASH_SPEED}
 *            structural limit — soft landings anchor the ship to the rotating
 *            surface grid, hard ones crash it.
 *
 * Called from the render loop: {@link #update} before integration (input,
 * camera, transitions) and {@link #postStep} after (collision/anchoring).
 */
public final class SimulationModeManager {

    public enum Mode { SANDBOX, SHIP, LANDING }

    /** LANDING auto-engages below this many planet radii of altitude (with hysteresis). */
    private static final double LANDING_ENTER_RADII = 1.5;
    private static final double LANDING_EXIT_RADII  = 2.5;
    /** Spawn orbit altitude in planet radii — above the LANDING threshold so SHIP mode greets the pilot. */
    private static final double SPAWN_ALT_RADII = 3.5;

    private final NBody sim;
    private final SimState state;
    private final OrbitCamera cam;
    private final ShipController controller;

    private volatile Mode mode = Mode.SANDBOX;
    private Spacecraft ship;

    // saved sandbox settings, restored on exit from flight modes
    private double savedTimeScale = -1;

    // current gravitational parent: -1 = Sun, otherwise planet index
    private volatile int primaryPlanet = -1;

    // predicted coast trajectory (m, barycentric frame re-anchored at the primary)
    private volatile Vec3[] predictedPath;
    private long lastPredictNanos = 0;

    public SimulationModeManager(NBody sim, SimState state, OrbitCamera cam, ShipController controller) {
        this.sim = sim; this.state = state; this.cam = cam; this.controller = controller;
    }

    public Mode mode() { return mode; }
    public Spacecraft ship() { return ship; }
    public int primaryPlanetIndex() { return primaryPlanet; }
    public Vec3[] predictedPath() { return predictedPath; }

    /* ----------------- mode switching ----------------- */

    public void requestMode(Mode m) {
        if (m == mode) {
            if (m == Mode.SHIP && ship != null && ship.crashed) respawn();
            return;
        }
        switch (m) {
            case SANDBOX -> enterSandbox();
            case SHIP -> enterShip();
            case LANDING -> {
                // Only meaningful inside a planet's SOI.
                if (ship == null) { enterShip(); }
                if (computePrimary() >= 0) mode = Mode.LANDING;
            }
        }
        controller.clearAll();
    }

    private void enterSandbox() {
        mode = Mode.SANDBOX;
        // Keep whatever scale the user has set (true scale is the default);
        // returning from flight no longer forces the exaggerated view back on.
        if (savedTimeScale > 0) { state.timeScale = savedTimeScale; savedTimeScale = -1; }
        cam.minDist = OrbitCamera.MIN_DIST;
        cam.upOverride = null;
        if (cam.distance < 0.5) cam.distance = 5;
        if (ship != null) ship.body.extAccel.set(0, 0, 0);
    }

    private void enterShip() {
        if (savedTimeScale < 0) savedTimeScale = state.timeScale;
        if (ship == null) {
            ship = new Spacecraft(sim);
            ship.respawnInOrbit(sim, 2, RADIUS_M[2] * SPAWN_ALT_RADII);   // high Earth orbit
        } else if (ship.crashed) {
            respawn();
        }
        mode = Mode.SHIP;
        Scale.trueScale = true;
        state.timeScale = 1.0;                       // real time; warp keys adjust from here
        cam.minDist = 40.0 / AU;
        cam.distance = 220.0 / AU;
        cam.upOverride = null;
        state.selectDynamic(ship.body);
        state.followSelected = false;
    }

    /** Respawn the ship in low orbit around the current primary (or Earth). */
    public void respawn() {
        if (ship == null) return;
        int k = primaryPlanet >= 0 ? primaryPlanet : 2;
        ship.respawnInOrbit(sim, k, RADIUS_M[k] * SPAWN_ALT_RADII);
        if (mode == Mode.SANDBOX) mode = Mode.SHIP;
    }

    /* ----------------- per-frame driving ----------------- */

    /** Pre-integration: input, camera latch, time-warp clamps, auto transitions. */
    public void update(double dtReal, double dtSim) {
        if (mode == Mode.SANDBOX) return;
        if (ship == null) { enterShip(); }

        primaryPlanet = computePrimary();
        NBody.Body primary = primaryBody();

        // time-warp guard rails: flight needs steps the pilot can react to
        double maxWarp = mode == Mode.LANDING ? 64 : 10_000;
        if (state.timeScale > maxWarp) state.timeScale = maxWarp;
        if (state.timeScale < 0.1) state.timeScale = 0.1;

        controller.update(ship, primary, dtReal, dtSim);

        // liftoff: anchored ship pushes off when thrust beats local gravity
        if (ship.landed && !ship.crashed && primaryPlanet >= 0) {
            Vec3 up = localUp();
            double g = mu(primaryPlanet) / Math.pow(distToPrimary(), 2);
            if (ship.thrustAccel.dot(up) > g) liftoff(up);
        }
        if (ship.body.anchored) ship.body.extAccel.set(0, 0, 0);   // anchored: integrator must not see thrust

        // auto SHIP <-> LANDING with hysteresis
        if (primaryPlanet >= 0) {
            double altRadii = altitudeAGL() / RADIUS_M[primaryPlanet];
            if (mode == Mode.SHIP && altRadii < LANDING_ENTER_RADII) mode = Mode.LANDING;
            else if (mode == Mode.LANDING && altRadii > LANDING_EXIT_RADII && !ship.landed) mode = Mode.SHIP;
        } else if (mode == Mode.LANDING) {
            mode = Mode.SHIP;
        }

        latchCamera(dtReal);
        maybePredict();
    }

    /** Post-integration: anchored-surface kinematics and touchdown resolution. */
    public void postStep() {
        if (mode == Mode.SANDBOX || ship == null) return;

        if (ship.body.anchored) {
            applyAnchorKinematics();
            return;
        }
        if (primaryPlanet < 0) return;

        NBody.Body planet = sim.planet(primaryPlanet);
        double R = RADIUS_M[primaryPlanet];
        Vec3 rel = ship.body.r.sub(planet.r);
        double d = rel.length();
        if (d > R + Spacecraft.HEIGHT_M * 0.5) return;

        /* ---- touchdown ---- */
        Vec3 up = rel.normalized();
        Vec3 surfVel = surfacePointVelocity(primaryPlanet, rel);
        Vec3 vRel = ship.body.v.sub(planet.v).sub(surfVel);
        double vVert = vRel.dot(up);
        ship.touchdownSpeed = vVert;

        ship.landed = true;
        ship.crashed = -vVert > Spacecraft.CRASH_SPEED;
        ship.landedPlanet = primaryPlanet;
        ship.throttle = 0;
        ship.thrustAccel.set(0, 0, 0);
        ship.body.extAccel.set(0, 0, 0);

        // Anchor to the rotating surface grid: store the contact point in the
        // planet-fixed frame, then drive position kinematically from the spin.
        double rAnchor = R + Spacecraft.HEIGHT_M * 0.5;
        Vec3 contact = up.scale(rAnchor);
        double theta = spinAngleRad(primaryPlanet, sim.simTime);
        Quat unspin = Quat.fromAxisAngle(spinAxis(primaryPlanet), -theta);
        ship.anchorLocal.set(unspin.rotate(contact));
        ship.anchorSimTime = sim.simTime;
        ship.body.anchored = true;
        applyAnchorKinematics();
        mode = Mode.LANDING;
    }

    private void applyAnchorKinematics() {
        int k = ship.landedPlanet;
        if (k < 0) return;
        NBody.Body planet = sim.planet(k);
        double theta = spinAngleRad(k, sim.simTime);
        Quat spin = Quat.fromAxisAngle(spinAxis(k), theta);
        Vec3 off = spin.rotate(ship.anchorLocal);
        ship.body.r.set(planet.r.x + off.x, planet.r.y + off.y, planet.r.z + off.z);
        Vec3 surfVel = surfacePointVelocity(k, off);
        ship.body.v.set(planet.v.x + surfVel.x, planet.v.y + surfVel.y, planet.v.z + surfVel.z);
    }

    private void liftoff(Vec3 up) {
        ship.body.anchored = false;
        ship.landed = false;
        ship.landedPlanet = -1;
        // small clearance so the very next step doesn't re-trigger contact
        ship.body.r.addScaled(up, 2.0);
    }

    /** Velocity (m/s) of the planet-fixed surface point at offset {@code rel} from center: omega x r. */
    private Vec3 surfacePointVelocity(int k, Vec3 rel) {
        Vec3 omega = spinAxis(k).scale(spinRate(k));
        return omega.cross(rel);
    }

    /* ----------------- camera ----------------- */

    private void latchCamera(double dtReal) {
        // Chase latch: smooth-pursue the ship; in LANDING align the camera's
        // vertical with the local up so the horizon tilts to the surface.
        Vec3 shipAU = ship.body.r.scale(1.0 / AU);
        double lerp = 1 - Math.exp(-14.0 * dtReal);
        cam.target.x += (shipAU.x - cam.target.x) * lerp;
        cam.target.y += (shipAU.y - cam.target.y) * lerp;
        cam.target.z += (shipAU.z - cam.target.z) * lerp;

        if (mode == Mode.LANDING && primaryPlanet >= 0) {
            Vec3 up = localUp();
            if (cam.upOverride == null) cam.upOverride = new Vec3(up);
            else {
                double s = 1 - Math.exp(-6.0 * dtReal);
                cam.upOverride.x += (up.x - cam.upOverride.x) * s;
                cam.upOverride.y += (up.y - cam.upOverride.y) * s;
                cam.upOverride.z += (up.z - cam.upOverride.z) * s;
            }
        } else {
            cam.upOverride = null;
        }
    }

    /* ----------------- gravitational frame queries ----------------- */

    /** SOI test: innermost planet whose sphere of influence contains the ship, else -1 (Sun). */
    private int computePrimary() {
        if (ship == null) return -1;
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int k = 0; k < COUNT; k++) {
            double d = ship.body.r.distance(sim.planetPos(k));
            if (d < soiRadius(k) && d < bestD) { best = k; bestD = d; }
        }
        return best;
    }

    public NBody.Body primaryBody() {
        return primaryPlanet >= 0 ? sim.planet(primaryPlanet) : sim.sun();
    }

    public String primaryName() {
        return primaryPlanet >= 0 ? NAMES[primaryPlanet] : "Sun";
    }

    public double distToPrimary() {
        return ship == null ? 0 : ship.body.r.distance(primaryBody().r);
    }

    /** Altitude above the primary's surface (m); distance-to-center minus radius. */
    public double altitudeAGL() {
        double R = primaryPlanet >= 0 ? RADIUS_M[primaryPlanet] : SUN_RADIUS_M;
        return distToPrimary() - R;
    }

    /** Local vertical at the ship (unit, away from the primary). */
    public Vec3 localUp() {
        return ship.body.r.sub(primaryBody().r).normalized();
    }

    /** |v| in the barycentric frame (m/s). */
    public double speedAbsolute() { return ship == null ? 0 : ship.body.v.length(); }

    /** Speed relative to the gravitational parent (m/s). */
    public double speedRelPrimary() {
        return ship == null ? 0 : ship.body.v.sub(primaryBody().v).length();
    }

    /** Escape velocity at the ship's current distance from its parent: sqrt(2 mu / r). */
    public double escapeVelocity() {
        if (ship == null) return 0;
        double muP = primaryPlanet >= 0 ? mu(primaryPlanet) : MU_SUN;
        return Math.sqrt(2 * muP / Math.max(1, distToPrimary()));
    }

    /** Climb (+) / sink (-) rate relative to the parent (m/s). */
    public double verticalSpeed() {
        if (ship == null) return 0;
        return ship.body.v.sub(primaryBody().v).dot(localUp());
    }

    /** Horizontal speed over the rotating surface (m/s) — subtracts the ground's own omega x r motion. */
    public double horizontalSurfaceSpeed() {
        if (ship == null || primaryPlanet < 0) {
            // around the Sun, "horizontal" is just the tangential component
            if (ship == null) return 0;
            Vec3 vRel = ship.body.v.sub(primaryBody().v);
            Vec3 up = localUp();
            return vRel.sub(up.scale(vRel.dot(up))).length();
        }
        Vec3 rel = ship.body.r.sub(primaryBody().r);
        Vec3 vRel = ship.body.v.sub(primaryBody().v).sub(surfacePointVelocity(primaryPlanet, rel));
        Vec3 up = localUp();
        return vRel.sub(up.scale(vRel.dot(up))).length();
    }

    /** Local gravitational acceleration at the ship (m/s^2). */
    public double localGravity() {
        if (ship == null) return 0;
        double muP = primaryPlanet >= 0 ? mu(primaryPlanet) : MU_SUN;
        double d = Math.max(1, distToPrimary());
        return muP / (d * d);
    }

    /* ----------------- trajectory prediction ----------------- */

    private void maybePredict() {
        if (!state.showTrajectory || ship == null || ship.landed || ship.crashed) {
            if (ship != null && (ship.landed || ship.crashed)) predictedPath = null;
            return;
        }
        long now = System.nanoTime();
        if (now - lastPredictNanos < 700_000_000L) return;   // ~0.7 s cadence
        lastPredictNanos = now;

        NBody.Body primary = primaryBody();
        double muP = primaryPlanet >= 0 ? mu(primaryPlanet) : MU_SUN;
        double d = Math.max(1, distToPrimary());
        double vRel = speedRelPrimary();

        // vis-viva: specific orbital energy fixes the period (bound) or a short look-ahead (escape)
        double eps = vRel * vRel / 2 - muP / d;
        double horizon;
        if (eps < 0) {
            double aOrb = -muP / (2 * eps);
            horizon = 2 * Math.PI * Math.sqrt(aOrb * aOrb * aOrb / muP) * 1.02;
        } else {
            horizon = Math.min(30 * DAY, 4 * d / Math.max(1, vRel));
        }
        horizon = Math.max(600, Math.min(horizon, 5 * YEAR));
        predictedPath = sim.predictPath(ship.body, primary, horizon, 280);
    }
}
