package astrosim;

import static astrosim.Bodies.*;

/**
 * Analytic Keplerian moon positions, lifted into 3-D. Moons orbit their parent
 * planet (hybrid model: planets are n-body, moons are Kepler), in a plane tilted
 * by their inclination about the ecliptic x-axis with the ascending node at 0.
 *
 * Returned vectors are offsets (m) from the parent planet, in the ecliptic frame.
 */
public final class MoonModel {
    private MoonModel() {}

    /** Position of a moon at mean anomaly M (rad), as an offset from its parent. */
    public static Vec3 positionAtAnomaly(int planet, int moon, double M) {
        double a = MOON_A_M[planet][moon];
        double e = MOON_ECC[planet][moon];
        double arg = MOON_ARG_PERI[planet][moon];
        double inc = Math.toRadians(MOON_INCL_DEG[planet][moon]);

        // Solve Kepler's equation.
        double Ean = M;
        for (int it = 0; it < 40; it++) {
            double f = Ean - e * Math.sin(Ean) - M;
            double fp = 1 - e * Math.cos(Ean);
            double d = f / fp;
            Ean -= d;
            if (Math.abs(d) < 1e-13) break;
        }
        double px = a * (Math.cos(Ean) - e);
        double py = a * Math.sqrt(1 - e * e) * Math.sin(Ean);

        // Rotate by argument of periapsis in-plane, then tilt the plane by inclination.
        double x1 = px * Math.cos(arg) - py * Math.sin(arg);
        double y1 = px * Math.sin(arg) + py * Math.cos(arg);
        double ci = Math.cos(inc), siI = Math.sin(inc);
        // World frame matches NBody/Orbits: orbital-plane normal along +Y
        // (horizontal). (x, y, z) -> (x, z, -y).
        return new Vec3(x1, y1 * siI, -y1 * ci);
    }

    /** Current position of a moon, given total simulated time (s). */
    public static Vec3 position(int planet, int moon, double simTime) {
        double periodSec = MOON_PERIOD_DAYS[planet][moon] * DAY;
        double meanMotion = 2 * Math.PI / periodSec;
        double M = (meanMotion * simTime) % (2 * Math.PI);
        return positionAtAnomaly(planet, moon, M);
    }
}
