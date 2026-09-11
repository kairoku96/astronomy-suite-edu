package astrosim;

import static astrosim.Bodies.*;

/**
 * Headless sanity checks for the physics engine (not part of the app):
 * leapfrog energy conservation, initial-state plausibility, thrust injection
 * into the KDK kicks, and the dynamic-body machinery.
 */
public final class PhysicsCheck {
    public static void main(String[] args) {
        boolean ok = true;

        /* ---- 1. initial conditions ---- */
        NBody sim = new NBody();
        System.out.printf("Initial distances (AU): ");
        for (int k = 0; k < COUNT; k++) System.out.printf("%s=%.2f ", NAMES[k], sim.planetDist(k) / AU);
        System.out.println();
        double vEarth = sim.planetSpeed(2) / 1000.0;
        double vMerc = sim.planetSpeed(0) / 1000.0;
        System.out.printf("Earth speed: %.2f km/s (expect ~29.8)%n", vEarth);
        System.out.printf("Mercury speed: %.2f km/s (38.9–59.0 over the orbit; near aphelion at J2000)%n", vMerc);
        ok &= check("Earth speed", Math.abs(vEarth - 29.8) < 1.5);
        ok &= check("Mercury speed in orbital range", vMerc > 37 && vMerc < 60);

        /* ---- 2. symplectic energy conservation over 10 years ---- */
        double e0 = sim.totalEnergy();
        double dtFrame = DAY * 5;
        int frames = (int) (10 * YEAR / dtFrame);
        for (int f = 0; f < frames; f++) sim.step(dtFrame);
        double e1 = sim.totalEnergy();
        double drift = Math.abs((e1 - e0) / e0);
        System.out.printf("Energy drift over 10 yr: %.3e relative%n", drift);
        System.out.printf("Earth dist after 10 yr: %.3f AU (expect ~1)%n", sim.planetDist(2) / AU);
        System.out.printf("Sun displacement from origin: %.3e m (barycentric wobble)%n", sim.sunPos().length());
        ok &= check("energy drift < 1e-6", drift < 1e-6);
        ok &= check("Earth stays near 1 AU", Math.abs(sim.planetDist(2) / AU - 1.0) < 0.05);

        /* ---- 3. thrust injection: v(t+dt/2) = v(t) + (a_g + a_thrust) dt/2 ---- */
        NBody sim2 = new NBody();
        // park a test craft 500 AU out where solar gravity is ~2e-8 m/s^2 (negligible vs thrust)
        NBody.Body craft = sim2.addBody("TestCraft", 40_000, 18,
                new Vec3(500 * AU, 0, 0), new Vec3(), null);
        craft.extAccel.set(0, 10.0, 0);                       // 10 m/s^2 cross-track burn
        double burn = 100;                                    // s
        sim2.step(burn);
        double dv = craft.v.y;
        double dy = craft.r.y;
        System.out.printf("Thrust test: dv=%.3f m/s (expect 1000), dy=%.1f m (expect ~50,000)%n", dv, dy);
        ok &= check("thrust dv = a*t", Math.abs(dv - 1000) < 1);
        ok &= check("thrust dy = a*t^2/2", Math.abs(dy - 50_000) < 100);

        /* ---- 4. anchored bodies are not integrated ---- */
        craft.anchored = true;
        Vec3 before = new Vec3(craft.r);
        sim2.step(1000);
        ok &= check("anchored body frozen", craft.r.distance(before) == 0);

        /* ---- 5. circular orbit survives in the leapfrog (LEO around Earth) ---- */
        NBody sim3 = new NBody();
        NBody.Body earth = sim3.planet(2);
        double rOrb = RADIUS_M[2] + 400_000;
        double vCirc = Math.sqrt(mu(2) / rOrb);
        Vec3 up = new Vec3(0, 1, 0);
        NBody.Body leo = sim3.addBody("LEO", 40_000, 18,
                new Vec3(earth.r.x + rOrb, earth.r.y, earth.r.z),
                new Vec3(earth.v.x, earth.v.y + 0, earth.v.z + vCirc), null);
        double period = 2 * Math.PI * Math.sqrt(rOrb * rOrb * rOrb / mu(2));
        for (int i = 0; i < 200; i++) sim3.step(period / 200);
        double altAfter = leo.r.distance(earth.r) - RADIUS_M[2];
        System.out.printf("LEO altitude after 1 orbit: %.1f km (expect ~400)%n", altAfter / 1000);
        ok &= check("LEO altitude held", Math.abs(altAfter - 400_000) < 30_000);
        ok &= check("up vector unused warning suppressor", up.y == 1);

        System.out.println(ok ? "ALL CHECKS PASSED" : "CHECKS FAILED");
        if (!ok) System.exit(1);
    }

    private static boolean check(String name, boolean pass) {
        System.out.printf("  [%s] %s%n", pass ? "PASS" : "FAIL", name);
        return pass;
    }
}
