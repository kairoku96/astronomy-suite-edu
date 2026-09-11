package astrosim;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.fixedfunc.GLMatrixFunc;
import com.jogamp.opengl.glu.GLU;

/**
 * Arcball-style orbit camera operating in scene units (AU), with a
 * FLOATING-ORIGIN modelview: the view matrix is built with the eye at (0,0,0)
 * and every draw call submits eye-relative coordinates ({@link Scene} subtracts
 * {@link #eyeSnapshot}). OpenGL's fixed-function pipeline multiplies vertices
 * in 32-bit floats, so at true planetary scale (camera metres above a surface
 * 1 AU from the origin) absolute coordinates would jitter by kilometres; with
 * eye-relative submission all magnitudes near the camera stay tiny and double
 * precision is preserved on the CPU side.
 *
 * SHIP/LANDING modes additionally drive {@code upOverride} (local vertical on
 * approach, so the horizon tilts with planetary curvature) and {@code minDist}
 * (metre-scale chase distances).
 */
public final class OrbitCamera {
    public final Vec3 target = new Vec3();   // look-at point, AU
    public double distance = 32.0;           // AU
    public double azimuth = Math.toRadians(35);
    public double elevation = Math.toRadians(22);

    public double fovy = 45.0;
    private double near = 0.01, far = 4000.0;

    /** Optional camera-up override (unit, world frame). Null = world +Y. */
    public volatile Vec3 upOverride = null;
    /** Zoom-in limit, mode-dependent (sandbox: 0.01 AU; flight: tens of metres). */
    public volatile double minDist = 0.01;

    private final double[] proj = new double[16];
    private final double[] mv = new double[16];
    private final int[] viewport = new int[4];
    private final GLU glu = new GLU();

    /** Eye position (AU) captured at the last {@link #apply}; the floating origin. */
    public final Vec3 eyeSnapshot = new Vec3();

    public static final double MIN_DIST = 0.01;
    public static final double MAX_DIST = 1500.0;
    private static final double NEAR_FLOOR =
            Double.parseDouble(System.getProperty("astrosim.nearfloor", "1e-10"));

    /** Eye position in AU (world frame). */
    public Vec3 eye() {
        double ce = Math.cos(elevation), se = Math.sin(elevation);
        double ca = Math.cos(azimuth),   sa = Math.sin(azimuth);
        return new Vec3(
                target.x + distance * ce * ca,
                target.y + distance * se,
                target.z + distance * ce * sa);
    }

    public void rotate(double dAz, double dEl) {
        azimuth += dAz;
        elevation += dEl;
        double lim = Math.toRadians(89);
        if (elevation > lim) elevation = lim;
        if (elevation < -lim) elevation = -lim;
    }

    public void zoom(double factor) {
        distance *= factor;
        if (distance < minDist) distance = minDist;
        if (distance > MAX_DIST) distance = MAX_DIST;
    }

    /** Pan the target in the camera's screen plane. */
    public void pan(double dxScreen, double dyScreen) {
        Vec3 eye = eye();
        Vec3 fwd = target.sub(eye).normalized();
        Vec3 worldUp = currentUp();
        Vec3 right = fwd.cross(worldUp).normalized();
        Vec3 up = right.cross(fwd).normalized();
        double scale = distance * 0.0015;
        target.x += (-right.x * dxScreen + up.x * dyScreen) * scale;
        target.y += (-right.y * dxScreen + up.y * dyScreen) * scale;
        target.z += (-right.z * dxScreen + up.z * dyScreen) * scale;
    }

    private Vec3 currentUp() {
        Vec3 u = upOverride;
        return u != null ? u : new Vec3(0, 1, 0);
    }

    /** Set projection + floating-origin modelview; capture matrices for picking/labels. */
    public void apply(GL2 gl, int width, int height) {
        int h = Math.max(1, height);
        double aspect = (double) width / h;
        // Near/far track the zoom so depth precision follows the camera from
        // interplanetary range down to metres above a surface. At true scale
        // the near plane widens (2% of camera distance) so the float depth
        // buffer can still resolve a whole planet; bodies beyond the reliable
        // range are re-projected as background proxies by the Scene.
        near = Math.max(NEAR_FLOOR, distance * (Scale.trueScale ? 0.05 : 0.002));
        far = Math.max(4000.0, distance * 100.0);

        gl.glMatrixMode(GLMatrixFunc.GL_PROJECTION);
        gl.glLoadIdentity();
        glu.gluPerspective(fovy, aspect, near, far);

        gl.glMatrixMode(GLMatrixFunc.GL_MODELVIEW);
        gl.glLoadIdentity();
        Vec3 e = eye();
        eyeSnapshot.set(e);
        Vec3 up = currentUp();
        // Floating origin: eye at (0,0,0), look toward the eye-relative target.
        glu.gluLookAt(0, 0, 0,
                target.x - e.x, target.y - e.y, target.z - e.z,
                up.x, up.y, up.z);

        gl.glGetDoublev(GLMatrixFunc.GL_MODELVIEW_MATRIX, mv, 0);
        gl.glGetDoublev(GLMatrixFunc.GL_PROJECTION_MATRIX, proj, 0);
        gl.glGetIntegerv(com.jogamp.opengl.GL.GL_VIEWPORT, viewport, 0);
    }

    /** Project a world point (AU) to screen pixels (top-left origin). Returns null if behind. */
    public double[] project(double xAu, double yAu, double zAu) {
        double[] win = new double[3];
        if (!glu.gluProject(xAu - eyeSnapshot.x, yAu - eyeSnapshot.y, zAu - eyeSnapshot.z,
                mv, 0, proj, 0, viewport, 0, win, 0)) return null;
        if (win[2] > 1.0) return null; // behind far / clipped
        double screenY = viewport[3] - win[1]; // GL y is bottom-up
        return new double[]{ win[0], screenY, win[2] };
    }

    /** Build a pick ray from a screen pixel (top-left origin). Returns {originAu(3), dirAu(3)} in world frame. */
    public Vec3[] pickRay(int screenX, int screenY) {
        double winY = viewport[3] - screenY;
        double[] p0 = new double[3], p1 = new double[3];
        boolean a = glu.gluUnProject(screenX, winY, 0.0, mv, 0, proj, 0, viewport, 0, p0, 0);
        boolean b = glu.gluUnProject(screenX, winY, 1.0, mv, 0, proj, 0, viewport, 0, p1, 0);
        if (!a || !b) return null;
        // Unprojected points are eye-relative; shift back to world frame.
        Vec3 origin = new Vec3(p0[0] + eyeSnapshot.x, p0[1] + eyeSnapshot.y, p0[2] + eyeSnapshot.z);
        Vec3 dir = new Vec3(p1[0] - p0[0], p1[1] - p0[1], p1[2] - p0[2]).normalized();
        return new Vec3[]{ origin, dir };
    }

    public int viewportWidth()  { return viewport[2]; }
    public int viewportHeight() { return viewport[3]; }
}
