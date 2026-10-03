import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The 3-D view must be a proper (unmirrored) picture of the Solar System: seen from above the
 * ecliptic the planets go round counter-clockwise, and the background sky shows the
 * constellations the right way round (east to the left of north, as seen from inside the
 * celestial sphere). Both were once mirrored by a sign in the projection.
 */
class HandednessTest {

    static {
        System.setProperty("java.awt.headless", "true");
        System.setProperty("sss.shot", "none");     // no animation timer
    }

    private static double call(Object o, String name, double... a) throws Exception {
        Class<?>[] t = new Class<?>[a.length];
        Object[] v = new Object[a.length];
        for (int i = 0; i < a.length; i++) { t[i] = double.class; v[i] = a[i]; }
        Method m = SolarSystemSimulation.class.getDeclaredMethod(name, t);
        m.setAccessible(true);
        return (double) m.invoke(o, v);
    }

    private static void view(Object sim, double pitchRad, double yawRad) throws Exception {
        Method p = SolarSystemSimulation.class.getDeclaredMethod("setViewPitch", double.class);
        Method y = SolarSystemSimulation.class.getDeclaredMethod("setViewYaw", double.class);
        p.setAccessible(true); y.setAccessible(true);
        p.invoke(sim, pitchRad);
        y.invoke(sim, yawRad);
    }

    @Test
    void planetsOrbitCounterClockwiseSeenFromEclipticNorth() throws Exception {
        SolarSystemSimulation s = new SolarSystemSimulation();
        s.setSize(1600, 1000);
        double AU = Astro.AU_M;
        for (double yaw : new double[]{0, 1.0, 2.5, -2.0}) {
            view(s, 0.0, yaw);                                  // looking straight down from +z
            double[] e1 = SolarSystemSimulation.planetEclipticAU(2, 0), e2 = SolarSystemSimulation.planetEclipticAU(2, 10 * 86400);
            double[] w1 = SolarSystemSimulation.eclToWorld(e1[0] * AU, e1[1] * AU, e1[2] * AU);
            double[] w2 = SolarSystemSimulation.eclToWorld(e2[0] * AU, e2[1] * AU, e2[2] * AU);
            double x0 = call(s, "projX", 0, 0, 0), y0 = call(s, "projY", 0, 0, 0);
            double x1 = call(s, "projX", w1[0], w1[1], w1[2]) - x0, y1 = call(s, "projY", w1[0], w1[1], w1[2]) - y0;
            double x2 = call(s, "projX", w2[0], w2[1], w2[2]) - x0, y2 = call(s, "projY", w2[0], w2[1], w2[2]) - y0;
            // Screen y points down, so a counter-clockwise turn has a negative cross product.
            assertTrue(x1 * y2 - y1 * x2 < 0, "Earth moves clockwise on screen at yaw " + yaw);
        }
    }

    /** Ecliptic-frame unit vector → world axes. */
    private static double[] star(double raH, double decD) {
        double[] q = Astro.radec(raH * 15 * Math.PI / 180, decD * Math.PI / 180);
        double[] e = Astro.eqToEcl(q[0], q[1], q[2]);
        return SolarSystemSimulation.eclToWorld(e[0], e[1], e[2]);
    }

    @Test
    void constellationsAreNotMirrored() throws Exception {
        SolarSystemSimulation s = new SolarSystemSimulation();
        s.setSize(1600, 1000);
        Method sky = SolarSystemSimulation.class.getDeclaredMethod("skyToScreen",
                double.class, double.class, double.class, double.class, double.class, double.class);
        sky.setAccessible(true);
        double[] bellatrix = star(5.4188, 6.3497), betelgeuse = star(5.9195, 7.4071), north = star(5.4188, 16.3497);
        int checked = 0;
        for (double pitch = 0.1; pitch < Math.PI; pitch += 0.3)
            for (double yaw = -Math.PI; yaw < Math.PI; yaw += 0.4) {
                view(s, pitch, yaw);
                double[] b = (double[]) sky.invoke(s, bellatrix[0], bellatrix[1], bellatrix[2], 1000.0, 800.0, 500.0);
                double[] e = (double[]) sky.invoke(s, betelgeuse[0], betelgeuse[1], betelgeuse[2], 1000.0, 800.0, 500.0);
                double[] n = (double[]) sky.invoke(s, north[0], north[1], north[2], 1000.0, 800.0, 500.0);
                if (b == null || e == null || n == null) continue;
                double nx = n[0] - b[0], ny = n[1] - b[1], ex = e[0] - b[0], ey = e[1] - b[1];
                // Seen from inside the sky with north up, east (Betelgeuse) is to the LEFT:
                // north = (0,−1), east = (−1,0) on a y-down screen ⇒ cross product < 0.
                assertTrue(nx * ey - ny * ex < 0, String.format("Orion mirrored at pitch %.1f yaw %.1f", pitch, yaw));
                checked++;
            }
        assertTrue(checked > 10, "Orion was in view for only " + checked + " orientations");
    }
}
