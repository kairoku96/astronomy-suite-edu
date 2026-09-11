package astrosim;

/** Minimal double-precision 3D vector. Methods return new instances; in-place
 *  helpers are used in the integrator hot loop for speed. */
public final class Vec3 {
    public double x, y, z;

    public Vec3() {}
    public Vec3(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
    public Vec3(Vec3 o) { this.x = o.x; this.y = o.y; this.z = o.z; }

    public Vec3 set(double x, double y, double z) { this.x = x; this.y = y; this.z = z; return this; }
    public Vec3 set(Vec3 o) { this.x = o.x; this.y = o.y; this.z = o.z; return this; }

    public Vec3 add(Vec3 o) { return new Vec3(x + o.x, y + o.y, z + o.z); }
    public Vec3 sub(Vec3 o) { return new Vec3(x - o.x, y - o.y, z - o.z); }
    public Vec3 scale(double s) { return new Vec3(x * s, y * s, z * s); }

    /** this += o*s (in place). */
    public Vec3 addScaled(Vec3 o, double s) { x += o.x * s; y += o.y * s; z += o.z * s; return this; }

    public double dot(Vec3 o) { return x * o.x + y * o.y + z * o.z; }
    public double distance(Vec3 o) {
        double dx = x - o.x, dy = y - o.y, dz = z - o.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
    public Vec3 cross(Vec3 o) {
        return new Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
    }
    public double length() { return Math.sqrt(x * x + y * y + z * z); }
    public double lengthSq() { return x * x + y * y + z * z; }

    public Vec3 normalized() {
        double n = length();
        return n > 0 ? new Vec3(x / n, y / n, z / n) : new Vec3();
    }

    @Override public String toString() { return String.format("(%.3e, %.3e, %.3e)", x, y, z); }
}
