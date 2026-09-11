package astrosim;

/**
 * Unit quaternion for 3-D orientation (spacecraft attitude, planet spin,
 * surface-frame anchoring). Convention: q = w + xi + yj + zk, right-handed,
 * rotating column vectors via q v q*.
 */
public final class Quat {
    public double w = 1, x, y, z;

    public Quat() {}
    public Quat(double w, double x, double y, double z) { this.w = w; this.x = x; this.y = y; this.z = z; }
    public Quat(Quat o) { set(o); }

    public Quat set(Quat o) { w = o.w; x = o.x; y = o.y; z = o.z; return this; }

    public static Quat identity() { return new Quat(); }

    public static Quat fromAxisAngle(Vec3 axis, double angleRad) {
        double len = axis.length();
        if (len < 1e-300) return identity();
        double half = angleRad * 0.5;
        double s = Math.sin(half) / len;
        return new Quat(Math.cos(half), axis.x * s, axis.y * s, axis.z * s);
    }

    /** Quaternion looking along {@code fwd} with {@code up} as the vertical hint. */
    public static Quat fromLook(Vec3 fwd, Vec3 up) {
        Vec3 f = fwd.normalized();
        Vec3 r = up.cross(f).normalized();
        if (r.lengthSq() < 1e-20) r = new Vec3(1, 0, 0);
        Vec3 u = f.cross(r);
        // Build from rotation matrix columns (r, u, f).
        double m00 = r.x, m01 = u.x, m02 = f.x;
        double m10 = r.y, m11 = u.y, m12 = f.y;
        double m20 = r.z, m21 = u.z, m22 = f.z;
        double tr = m00 + m11 + m22;
        Quat q = new Quat();
        if (tr > 0) {
            double s = Math.sqrt(tr + 1.0) * 2;
            q.w = 0.25 * s;
            q.x = (m21 - m12) / s;
            q.y = (m02 - m20) / s;
            q.z = (m10 - m01) / s;
        } else if (m00 > m11 && m00 > m22) {
            double s = Math.sqrt(1.0 + m00 - m11 - m22) * 2;
            q.w = (m21 - m12) / s;
            q.x = 0.25 * s;
            q.y = (m01 + m10) / s;
            q.z = (m02 + m20) / s;
        } else if (m11 > m22) {
            double s = Math.sqrt(1.0 + m11 - m00 - m22) * 2;
            q.w = (m02 - m20) / s;
            q.x = (m01 + m10) / s;
            q.y = 0.25 * s;
            q.z = (m12 + m21) / s;
        } else {
            double s = Math.sqrt(1.0 + m22 - m00 - m11) * 2;
            q.w = (m10 - m01) / s;
            q.x = (m02 + m20) / s;
            q.y = (m12 + m21) / s;
            q.z = 0.25 * s;
        }
        return q.normalize();
    }

    /** this = this * o (apply o in local frame). */
    public Quat mulLocal(Quat o) {
        double nw = w * o.w - x * o.x - y * o.y - z * o.z;
        double nx = w * o.x + x * o.w + y * o.z - z * o.y;
        double ny = w * o.y - x * o.z + y * o.w + z * o.x;
        double nz = w * o.z + x * o.y - y * o.x + z * o.w;
        w = nw; x = nx; y = ny; z = nz;
        return this;
    }

    public Quat normalize() {
        double n = Math.sqrt(w * w + x * x + y * y + z * z);
        if (n < 1e-300) { w = 1; x = y = z = 0; return this; }
        w /= n; x /= n; y /= n; z /= n;
        return this;
    }

    /** Rotate a vector by this quaternion (returns a new vector). */
    public Vec3 rotate(Vec3 v) {
        // t = 2 * (q.xyz × v); v' = v + w*t + q.xyz × t
        double tx = 2 * (y * v.z - z * v.y);
        double ty = 2 * (z * v.x - x * v.z);
        double tz = 2 * (x * v.y - y * v.x);
        return new Vec3(
                v.x + w * tx + (y * tz - z * ty),
                v.y + w * ty + (z * tx - x * tz),
                v.z + w * tz + (x * ty - y * tx));
    }

    /** Integrate a body-frame angular velocity (rad/s) over dt seconds. */
    public Quat integrateLocal(Vec3 omegaLocal, double dt) {
        double mag = omegaLocal.length();
        if (mag * dt < 1e-12) return this;
        mulLocal(fromAxisAngle(omegaLocal, mag * dt));
        return normalize();
    }

    /** Local basis vectors. Ship convention: +Z forward, +Y up, +X right. */
    public Vec3 forward() { return rotate(new Vec3(0, 0, 1)); }
    public Vec3 up()      { return rotate(new Vec3(0, 1, 0)); }
    public Vec3 right()   { return rotate(new Vec3(1, 0, 0)); }

    /** Column-major 4x4 rotation matrix for glMultMatrixd. */
    public void toMatrix(double[] m16) {
        double xx = x * x, yy = y * y, zz = z * z;
        double xy = x * y, xz = x * z, yz = y * z;
        double wx = w * x, wy = w * y, wz = w * z;
        m16[0] = 1 - 2 * (yy + zz); m16[4] = 2 * (xy - wz);     m16[8]  = 2 * (xz + wy);     m16[12] = 0;
        m16[1] = 2 * (xy + wz);     m16[5] = 1 - 2 * (xx + zz); m16[9]  = 2 * (yz - wx);     m16[13] = 0;
        m16[2] = 2 * (xz - wy);     m16[6] = 2 * (yz + wx);     m16[10] = 1 - 2 * (xx + yy); m16[14] = 0;
        m16[3] = 0;                 m16[7] = 0;                 m16[11] = 0;                 m16[15] = 1;
    }

    @Override public String toString() { return String.format("q(%.3f, %.3f, %.3f, %.3f)", w, x, y, z); }
}
