package astrosim;

import java.util.List;

/** Ray vs. sphere selection in scene (AU) space. */
public final class Picker {
    private Picker() {}

    /** A selectable body: planet==-1 => Sun; moon==-1 => the planet itself;
     *  dynamicBody != null => a runtime body (spawned mass / spacecraft). */
    public static final class Pickable {
        public final Vec3 center;   // AU
        public final double radius; // AU (pick radius, may be padded)
        public final int planet;
        public final int moon;
        public final NBody.Body dynamicBody;
        public Pickable(Vec3 center, double radius, int planet, int moon) {
            this(center, radius, planet, moon, null);
        }
        public Pickable(Vec3 center, double radius, NBody.Body dynamicBody) {
            this(center, radius, -1, -1, dynamicBody);
        }
        private Pickable(Vec3 center, double radius, int planet, int moon, NBody.Body dyn) {
            this.center = center; this.radius = radius; this.planet = planet; this.moon = moon; this.dynamicBody = dyn;
        }
    }

    /** Nearest sphere hit along the ray, or null. */
    public static Pickable nearest(Vec3 origin, Vec3 dir, List<Pickable> bodies) {
        Pickable best = null;
        double bestT = Double.MAX_VALUE;
        for (Pickable p : bodies) {
            double t = raySphere(origin, dir, p.center, p.radius);
            if (t >= 0 && t < bestT) { bestT = t; best = p; }
        }
        return best;
    }

    /** Returns nearest positive intersection distance along dir, or -1 if none. */
    private static double raySphere(Vec3 o, Vec3 d, Vec3 c, double r) {
        double ox = o.x - c.x, oy = o.y - c.y, oz = o.z - c.z;
        double b = ox * d.x + oy * d.y + oz * d.z;
        double cc = ox * ox + oy * oy + oz * oz - r * r;
        double disc = b * b - cc;
        if (disc < 0) return -1;
        double sq = Math.sqrt(disc);
        double t1 = -b - sq;
        if (t1 >= 0) return t1;
        double t2 = -b + sq;
        return t2 >= 0 ? t2 : -1;
    }
}
