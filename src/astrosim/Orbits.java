package astrosim;

import static astrosim.Bodies.*;

/**
 * Static reference ellipses from J2000 elements, used to draw orbit paths.
 * (The bodies themselves move under the n-body integrator; these fixed
 * heliocentric ellipses are an accurate, stable visual guide.)
 */
public final class Orbits {
    private Orbits() {}

    /** Heliocentric position (AU) of planet k's reference ellipse at mean anomaly M (rad). */
    public static Vec3 pointAU(int k, double M) {
        double a_m  = A_METERS[k];
        double e    = ECC[k];
        double inc  = Math.toRadians(INCLINATION_DEG[k]);
        double Omega = Math.toRadians(PLANET_OMEGA0[k]);
        double omega = Math.toRadians(PLANET_VARPI0[k] - PLANET_OMEGA0[k]);

        double E = M;
        for (int it = 0; it < 40; it++) {
            double f = E - e * Math.sin(E) - M;
            double fp = 1 - e * Math.cos(E);
            double d = f / fp;
            E -= d;
            if (Math.abs(d) < 1e-13) break;
        }
        double b_m = a_m * Math.sqrt(1 - e * e);
        double px = a_m * (Math.cos(E) - e);
        double py = b_m * Math.sin(E);

        double co = Math.cos(Omega), so = Math.sin(Omega);
        double cw = Math.cos(omega), sw = Math.sin(omega);
        double ci = Math.cos(inc),   si = Math.sin(inc);
        double m11 = co * cw - so * sw * ci, m12 = -co * sw - so * cw * ci;
        double m21 = so * cw + co * sw * ci, m22 = -so * sw + co * cw * ci;
        double m31 = sw * si,                m32 = cw * si;

        double x = m11 * px + m12 * py;
        double y = m21 * px + m22 * py;
        double z = m31 * px + m32 * py;
        // Match the world frame used by NBody: ecliptic normal +Z -> +Y so the
        // orbital plane is horizontal. (x, y, z) -> (x, z, -y).
        return new Vec3(x / AU, z / AU, -y / AU);
    }
}
