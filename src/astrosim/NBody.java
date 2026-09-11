package astrosim;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import static astrosim.Bodies.*;

/**
 * Gravitational n-body engine. All state is SI (metres, kilograms, seconds) in
 * the J2000 ecliptic frame with the system barycenter at the origin — the
 * renderer converts to AU only at draw time, which keeps the integrator free
 * of unit-scale precision loss.
 *
 * Integration is a synchronized kick-drift-kick (KDK) leapfrog — 2nd order and
 * symplectic, so phase-space volume is preserved and total energy shows no
 * secular drift over long orbital timelines. Per substep:
 *
 *   1. kick  : v(t+dt/2) = v(t) + [a_grav(t) + a_ext(t)] * dt/2
 *   2. drift : x(t+dt)   = x(t) + v(t+dt/2) * dt
 *   3. force : recompute a_grav(t+dt) from F = G m1 m2 / r^2 over all pairs
 *   4. kick  : v(t+dt)   = v(t+dt/2) + [a_grav(t+dt) + a_ext(t+dt)] * dt/2
 *
 * a_ext is each body's external (non-gravitational) acceleration — spacecraft
 * thrust — injected directly into both kicks per the flight-dynamics spec.
 *
 * Body layout: index 0 is the Sun, indices 1..COUNT are the planets/dwarfs
 * ({@link Bodies}), indices >= FIXED are runtime bodies (spawned masses, the
 * spacecraft). Initial planetary conditions come from JPL J2000 Keplerian
 * elements; the Sun's velocity zeroes total momentum and everything is shifted
 * to the barycenter, so the Sun visibly wobbles under the planets' pull.
 */
public final class NBody {

    /** One gravitating body. Mutable fields are owned by the integrator. */
    public static final class Body {
        public String name;
        public double mass;          // kg
        public double radiusM;       // physical radius (m), 0 if point-like
        public Color color = new Color(200, 210, 230);

        public final Vec3 r = new Vec3();   // position (m)
        public final Vec3 v = new Vec3();   // velocity (m/s)
        public final Vec3 a = new Vec3();   // gravitational acceleration scratch (m/s^2)

        /** External acceleration (thrust), m/s^2 — applied in BOTH leapfrog kicks. */
        public final Vec3 extAccel = new Vec3();

        /** Anchored bodies (a landed spacecraft) are positioned kinematically, not integrated. */
        public boolean anchored = false;

        Body(String name, double mass, double radiusM) {
            this.name = name; this.mass = mass; this.radiusM = radiusM;
        }
    }

    /** Number of permanent bodies: Sun + planets. Runtime bodies start here. */
    public static final int FIXED = COUNT + 1;

    private final List<Body> bodies = new ArrayList<>();
    public double simTime = 0.0;        // seconds since J2000 epoch

    /** Plummer-style softening (m) — guards near-singular steps between spawned
     *  bodies. Must stay far below LEO scales (~1e6 m) so spacecraft skimming a
     *  surface feel full gravity; 1 km contributes < 1e-7 error there. */
    private static final double SOFTEN = 1.0e3;
    private static final int MAX_SUBSTEPS = 4000;

    public NBody() {
        initFromElements();
    }

    /** Build heliocentric state vectors from JPL J2000 Keplerian elements, recenter on barycenter. */
    private void initFromElements() {
        Body sun = new Body("Sun", M_SUN, SUN_RADIUS_M);
        sun.color = new Color(255, 220, 130);
        bodies.add(sun);

        for (int k = 0; k < COUNT; k++) {
            Body b = new Body(NAMES[k], PLANET_MASS[k], RADIUS_M[k]);
            b.color = COLORS[k];
            bodies.add(b);

            double a_m  = A_METERS[k];
            double e    = ECC[k];
            double inc  = Math.toRadians(INCLINATION_DEG[k]);
            double Omega = Math.toRadians(PLANET_OMEGA0[k]);                    // longitude of ascending node
            double omega = Math.toRadians(PLANET_VARPI0[k] - PLANET_OMEGA0[k]); // argument of periapsis
            double M     = Math.toRadians(PLANET_L0[k] - PLANET_VARPI0[k]);     // mean anomaly

            // Solve Kepler's equation M = E - e sin E.
            double E = M;
            for (int it = 0; it < 60; it++) {
                double f  = E - e * Math.sin(E) - M;
                double fp = 1 - e * Math.cos(E);
                double d  = f / fp;
                E -= d;
                if (Math.abs(d) < 1e-14) break;
            }

            double cosE = Math.cos(E), sinE = Math.sin(E);
            double b_m  = a_m * Math.sqrt(1 - e * e);
            double px = a_m * (cosE - e);
            double py = b_m * sinE;
            double rmag = a_m * (1 - e * cosE);

            double mu = G * (M_SUN + PLANET_MASS[k]);
            double fac = Math.sqrt(mu * a_m) / rmag;
            double vpx = -fac * sinE;
            double vpy =  fac * Math.sqrt(1 - e * e) * cosE;

            // Perifocal -> ecliptic: R = Rz(Omega) Rx(inc) Rz(omega).
            double co = Math.cos(Omega), so = Math.sin(Omega);
            double cw = Math.cos(omega), sw = Math.sin(omega);
            double ci = Math.cos(inc),   si = Math.sin(inc);
            double m11 = co * cw - so * sw * ci, m12 = -co * sw - so * cw * ci;
            double m21 = so * cw + co * sw * ci, m22 = -so * sw + co * cw * ci;
            double m31 = sw * si,                m32 = cw * si;

            // Ecliptic (normal +Z) -> world: rotate -90 deg about X so the
            // orbital plane lies horizontal (normal +Y, the camera's up axis):
            // (x, y, z) -> (x, z, -y).
            double rx = m11 * px + m12 * py, ry = m21 * px + m22 * py, rz = m31 * px + m32 * py;
            double vx = m11 * vpx + m12 * vpy, vy = m21 * vpx + m22 * vpy, vz = m31 * vpx + m32 * vpy;
            b.r.set(rx, rz, -ry);
            b.v.set(vx, vz, -vy);
        }

        // Sun velocity so total momentum is zero; shift all to the barycenter.
        Vec3 p = new Vec3();
        for (int i = 1; i < bodies.size(); i++) p.addScaled(bodies.get(i).v, bodies.get(i).mass);
        sun.v.set(-p.x / sun.mass, -p.y / sun.mass, -p.z / sun.mass);

        Vec3 com = new Vec3();
        double mtot = 0;
        for (Body b : bodies) { com.addScaled(b.r, b.mass); mtot += b.mass; }
        com.set(com.x / mtot, com.y / mtot, com.z / mtot);
        for (Body b : bodies) { b.r.x -= com.x; b.r.y -= com.y; b.r.z -= com.z; }
    }

    /* ----------------- body management ----------------- */

    /** Register a runtime body (spawned mass or spacecraft). Returns it for convenience. */
    public synchronized Body addBody(String name, double mass, double radiusM, Vec3 r, Vec3 v, Color color) {
        Body b = new Body(name, mass, radiusM);
        b.r.set(r); b.v.set(v);
        if (color != null) b.color = color;
        bodies.add(b);
        return b;
    }

    public synchronized void removeBody(Body b) {
        int idx = bodies.indexOf(b);
        if (idx >= FIXED) bodies.remove(idx);   // permanent bodies cannot be removed
    }

    public synchronized int size() { return bodies.size(); }
    public synchronized Body get(int i) { return bodies.get(i); }

    /** Snapshot of runtime bodies (index >= FIXED) for the renderer / pickers. */
    public synchronized List<Body> dynamicBodies() {
        return new ArrayList<>(bodies.subList(Math.min(FIXED, bodies.size()), bodies.size()));
    }

    public Body sun() { return bodies.get(0); }
    public Body planet(int k) { return bodies.get(k + 1); }

    /* ----------------- forces ----------------- */

    /** Pairwise Newtonian gravity, F = G m1 m2 / r^2, accumulated into each body's a. */
    private static void computeAccelerations(List<Body> bs) {
        int n = bs.size();
        for (int i = 0; i < n; i++) bs.get(i).a.set(0, 0, 0);
        double eps2 = SOFTEN * SOFTEN;
        for (int i = 0; i < n; i++) {
            Body bi = bs.get(i);
            for (int j = i + 1; j < n; j++) {
                Body bj = bs.get(j);
                double dx = bj.r.x - bi.r.x;
                double dy = bj.r.y - bi.r.y;
                double dz = bj.r.z - bi.r.z;
                double r2 = dx * dx + dy * dy + dz * dz + eps2;
                double invR = 1.0 / Math.sqrt(r2);
                double invR3 = invR * invR * invR;
                double s = G * invR3;
                double si = s * bj.mass;
                double sj = s * bi.mass;
                bi.a.x += si * dx; bi.a.y += si * dy; bi.a.z += si * dz;
                bj.a.x -= sj * dx; bj.a.y -= sj * dy; bj.a.z -= sj * dz;
            }
        }
    }

    /** Half-kick all unanchored bodies: v += (a_grav + a_ext) * h. */
    private static void kick(List<Body> bs, double h) {
        for (Body b : bs) {
            if (b.anchored) continue;
            b.v.x += (b.a.x + b.extAccel.x) * h;
            b.v.y += (b.a.y + b.extAccel.y) * h;
            b.v.z += (b.a.z + b.extAccel.z) * h;
        }
    }

    /** Full drift: x += v * dt. */
    private static void drift(List<Body> bs, double dt) {
        for (Body b : bs) {
            if (b.anchored) continue;
            b.r.addScaled(b.v, dt);
        }
    }

    /* ----------------- integration ----------------- */

    /**
     * Largest stable substep (s) for the current configuration: 1/200 of
     * Mercury's period for the planetary system, tightened near any massive
     * body for runtime bodies (a spacecraft skimming a surface needs steps
     * proportional to the local dynamical time sqrt(r^3/mu)).
     */
    private double maxStableDt() {
        double maxDt = (PERIOD_Y[0] * YEAR) / 200.0;
        for (int i = FIXED; i < bodies.size(); i++) {
            Body b = bodies.get(i);
            if (b.anchored) continue;
            for (int j = 0; j < FIXED; j++) {
                Body m = bodies.get(j);
                double d = b.r.distance(m.r);
                if (d <= 0) continue;
                double tDyn = 2 * Math.PI * Math.sqrt(d * d * d / (G * m.mass));
                maxDt = Math.min(maxDt, Math.max(1e-3, tDyn / 200.0));
            }
        }
        return maxDt;
    }

    /** Advance the system by dtSim seconds (KDK leapfrog, internally substepped). */
    public synchronized void step(double dtSim) {
        if (dtSim == 0) return;
        double maxDt = maxStableDt();
        int sub = (int) Math.ceil(Math.abs(dtSim) / maxDt);
        if (sub < 1) sub = 1;
        if (sub > MAX_SUBSTEPS) sub = MAX_SUBSTEPS;
        double dt = dtSim / sub;

        computeAccelerations(bodies);                 // a(t)
        for (int s = 0; s < sub; s++) {
            kick(bodies, dt * 0.5);                   // v(t+dt/2) = v(t) + [a(t)+a_ext]*dt/2
            drift(bodies, dt);                        // x(t+dt)   = x(t) + v(t+dt/2)*dt
            computeAccelerations(bodies);             // a(t+dt)
            kick(bodies, dt * 0.5);                   // v(t+dt)   = v(t+dt/2) + [a(t+dt)+a_ext]*dt/2
        }
        simTime += dtSim;
    }

    /* ----------------- trajectory prediction ----------------- */

    /**
     * Coast prediction: clone the full system, integrate forward with no
     * external thrust, and record {@code subject}'s position each sample.
     * Positions are returned relative to {@code reference}'s co-moving frame
     * (offset back to the reference's *current* position), so the path reads
     * as the orbit the pilot will fly around that body, not an inertial spiral.
     * Returns metres; null if subject/reference are not in the system.
     */
    public Vec3[] predictPath(Body subject, Body reference, double horizonSec, int samples) {
        final int n;
        final double[] mass;
        final Vec3[] r, v;
        final int subjIdx, refIdx;
        synchronized (this) {
            n = bodies.size();
            int si = bodies.indexOf(subject);
            int ri = reference == null ? 0 : bodies.indexOf(reference);
            if (si < 0 || ri < 0) return null;
            subjIdx = si; refIdx = ri;
            mass = new double[n];
            r = new Vec3[n]; v = new Vec3[n];
            for (int i = 0; i < n; i++) {
                Body b = bodies.get(i);
                mass[i] = b.mass;
                r[i] = new Vec3(b.r);
                v[i] = new Vec3(b.v);
            }
        }
        Vec3 refNow = new Vec3(r[refIdx]);
        Vec3[] a = new Vec3[n];
        for (int i = 0; i < n; i++) a[i] = new Vec3();
        double eps2 = SOFTEN * SOFTEN;

        Vec3[] path = new Vec3[samples];
        double dtSample = horizonSec / samples;

        java.util.function.Consumer<Void> accel = ignored -> {
            for (int i = 0; i < n; i++) a[i].set(0, 0, 0);
            for (int i = 0; i < n; i++)
                for (int j = i + 1; j < n; j++) {
                    double dx = r[j].x - r[i].x, dy = r[j].y - r[i].y, dz = r[j].z - r[i].z;
                    double r2 = dx * dx + dy * dy + dz * dz + eps2;
                    double invR = 1.0 / Math.sqrt(r2);
                    double s = G * invR * invR * invR;
                    double si2 = s * mass[j], sj2 = s * mass[i];
                    a[i].x += si2 * dx; a[i].y += si2 * dy; a[i].z += si2 * dz;
                    a[j].x -= sj2 * dx; a[j].y -= sj2 * dy; a[j].z -= sj2 * dz;
                }
        };

        accel.accept(null);
        for (int sIdx = 0; sIdx < samples; sIdx++) {
            // Substep each sample near the reference body for accuracy.
            double d = r[subjIdx].distance(r[refIdx]);
            double tDyn = 2 * Math.PI * Math.sqrt(Math.max(1, d * d * d) / (G * Math.max(1, mass[refIdx])));
            int sub = (int) Math.min(48, Math.max(1, Math.ceil(dtSample / Math.max(1e-3, tDyn / 120.0))));
            double dt = dtSample / sub;
            for (int q = 0; q < sub; q++) {
                for (int i = 0; i < n; i++) v[i].addScaled(a[i], dt * 0.5);
                for (int i = 0; i < n; i++) r[i].addScaled(v[i], dt);
                accel.accept(null);
                for (int i = 0; i < n; i++) v[i].addScaled(a[i], dt * 0.5);
            }
            // Reference-relative, re-anchored at the reference's current position.
            path[sIdx] = new Vec3(
                    r[subjIdx].x - r[refIdx].x + refNow.x,
                    r[subjIdx].y - r[refIdx].y + refNow.y,
                    r[subjIdx].z - r[refIdx].z + refNow.z);
        }
        return path;
    }

    /* ---- accessors preserved for telemetry/UI (planet k is 0-based; body index k+1) ---- */

    public Vec3 sunPos() { return bodies.get(0).r; }
    public Vec3 planetPos(int k) { return bodies.get(k + 1).r; }

    /** Speed of planet k relative to the Sun (m/s). */
    public double planetSpeed(int k) {
        Vec3 vp = bodies.get(k + 1).v, vs = bodies.get(0).v;
        double dx = vp.x - vs.x, dy = vp.y - vs.y, dz = vp.z - vs.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Distance of planet k from the Sun (m). */
    public double planetDist(int k) {
        return bodies.get(k + 1).r.distance(bodies.get(0).r);
    }

    /** Total mechanical energy (J) — symplectic-drift diagnostic. */
    public synchronized double totalEnergy() {
        double ke = 0, pe = 0;
        int n = bodies.size();
        for (Body b : bodies) ke += 0.5 * b.mass * b.v.lengthSq();
        for (int i = 0; i < n; i++)
            for (int j = i + 1; j < n; j++) {
                Body bi = bodies.get(i), bj = bodies.get(j);
                pe -= G * bi.mass * bj.mass / bi.r.distance(bj.r);
            }
        return ke + pe;
    }
}
