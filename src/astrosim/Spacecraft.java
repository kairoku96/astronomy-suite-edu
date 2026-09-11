package astrosim;

import java.awt.Color;

import static astrosim.Bodies.*;

/**
 * The controllable spacecraft: a finite-mass body registered in the
 * {@link NBody} integrator (so it experiences full n-body gravity), plus
 * attitude (quaternion), propellant bookkeeping and landed/crashed state.
 *
 * Thrust is communicated to the integrator through {@code body.extAccel}
 * (m/s^2), which the leapfrog applies inside both half-kicks:
 *   v(t+dt/2) = v(t) + (a_gravity + a_thrust) * dt/2
 *
 * Propulsion model: a single throttleable engine.
 *   a_thrust = throttle * MAX_THRUST / m_total
 *   mdot     = throttle * MAX_THRUST / (ISP * g0)
 *   dv_rem   = ISP * g0 * ln(m_total / m_dry)        (Tsiolkovsky)
 */
public final class Spacecraft {

    /* ---- vehicle constants ---- */
    public static final double DRY_MASS      = 12_000;    // kg
    public static final double FUEL_CAPACITY = 28_000;    // kg
    public static final double MAX_THRUST    = 800_000;   // N  (~2g at full mass)
    public static final double ISP           = 3000;      // s  (advanced torch engine; dv ~ 35 km/s full)
    public static final double G0            = 9.80665;   // m/s^2
    public static final double HEIGHT_M      = 18;        // visual / touchdown reference height
    /** Touchdown vertical-speed limit (m/s): faster than this is structural failure. */
    public static final double CRASH_SPEED   = 5.0;

    /** The integrator body carrying position/velocity/mass. */
    public final NBody.Body body;

    /** Attitude: +Z forward, +Y up in ship frame. */
    public final Quat orientation = Quat.identity();

    public double fuelMass = FUEL_CAPACITY;   // kg
    public double throttle = 0;               // 0..1
    /** World-frame thrust acceleration applied this frame (m/s^2), for telemetry. */
    public final Vec3 thrustAccel = new Vec3();

    public boolean landed = false;
    public boolean crashed = false;
    public int landedPlanet = -1;
    /** Touchdown vertical speed (m/s, negative = sink) recorded for the HUD. */
    public double touchdownSpeed = 0;

    /** Anchor: ship offset from planet center in the planet-fixed rotating frame at anchorTime. */
    final Vec3 anchorLocal = new Vec3();
    double anchorSimTime = 0;

    public Spacecraft(NBody sim) {
        body = sim.addBody("Spacecraft", DRY_MASS + FUEL_CAPACITY, HEIGHT_M,
                new Vec3(), new Vec3(), new Color(220, 235, 255));
    }

    public double totalMass() { return DRY_MASS + fuelMass; }

    /** Remaining delta-v (m/s) by the rocket equation. */
    public double deltaVRemaining() {
        return ISP * G0 * Math.log(totalMass() / DRY_MASS);
    }

    public double fuelFraction() { return fuelMass / FUEL_CAPACITY; }

    /** Burn propellant for thrust force F (N) over dtSim seconds; returns achievable F. */
    public double consumeFuel(double thrustN, double dtSim) {
        if (fuelMass <= 0) { fuelMass = 0; return 0; }
        double mdot = thrustN / (ISP * G0);
        double need = mdot * dtSim;
        if (need >= fuelMass) {
            double frac = fuelMass / need;
            fuelMass = 0;
            return thrustN * frac;
        }
        fuelMass -= need;
        return thrustN;
    }

    /** Sync integrator mass after fuel burn. */
    public void updateMass() { body.mass = totalMass(); }

    /**
     * Place the ship in a circular orbit around planet k at the given altitude,
     * prograde in the planet's orbital plane, facing prograde. Clears any
     * landed/crashed state.
     */
    public void respawnInOrbit(NBody sim, int k, double altitudeM) {
        NBody.Body planet = sim.planet(k);
        double rOrb = RADIUS_M[k] + altitudeM;
        double vCirc = Math.sqrt(mu(k) / rOrb);

        // Radial direction: outward from the Sun (well-lit side); prograde from planet velocity.
        Vec3 sunDir = planet.r.sub(sim.sunPos()).normalized();
        Vec3 progradeHint = planet.v.sub(sim.sun().v).normalized();
        Vec3 orbitNormal = sunDir.cross(progradeHint).normalized();
        if (orbitNormal.lengthSq() < 1e-12) orbitNormal = new Vec3(0, 1, 0);
        Vec3 tangent = orbitNormal.cross(sunDir).normalized();

        body.anchored = false;
        body.r.set(planet.r.x + sunDir.x * rOrb, planet.r.y + sunDir.y * rOrb, planet.r.z + sunDir.z * rOrb);
        body.v.set(planet.v.x + tangent.x * vCirc, planet.v.y + tangent.y * vCirc, planet.v.z + tangent.z * vCirc);
        orientation.set(Quat.fromLook(tangent, sunDir));

        landed = false; crashed = false; landedPlanet = -1;
        throttle = 0;
        body.extAccel.set(0, 0, 0);
        thrustAccel.set(0, 0, 0);
        if (fuelMass <= 0) fuelMass = FUEL_CAPACITY;   // crash/flame-out recovery refuels
        updateMass();
    }
}
