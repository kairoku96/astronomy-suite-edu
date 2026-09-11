package astrosim;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.fixedfunc.GLLightingFunc;
import com.jogamp.opengl.glu.GLU;
import com.jogamp.opengl.glu.GLUquadric;
import com.jogamp.opengl.util.awt.AWTGLReadBufferUtil;
import com.jogamp.opengl.util.texture.Texture;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static astrosim.Bodies.*;

/**
 * The render loop (GL2 fixed-function): starfield, emissive Sun, textured
 * planet/moon spheres with axial tilt + sidereal spin, reference orbits,
 * Saturn's rings, runtime bodies, the spacecraft with its predicted flight
 * path, and selection highlighting.
 *
 * Drives the frame pipeline: mode-manager input -> KDK leapfrog step ->
 * collision/anchoring -> camera -> draw. All geometry is submitted
 * EYE-RELATIVE (floating origin, see {@link OrbitCamera}) so true-scale
 * flight/landing rendering keeps sub-metre precision despite GL's float
 * matrices. Bodies whose angular size falls below ~a pixel are drawn as
 * depth-write-off points, which also sidesteps far-plane depth artifacts.
 */
public final class Scene implements GLEventListener {

    private final NBody sim;
    private final OrbitCamera cam;
    private final SimState state;
    private final SimulationModeManager modeMgr;

    private final GLU glu = new GLU();
    private GLUquadric quad;

    private long lastNanos = 0;
    /** Current viewport pixel height, used to size sphere tessellation to on-screen coverage. */
    private int viewH = 800;
    private static final double SATURN_RING_TILT_DEG = 26.7;
    private static final int SATURN = 5;
    private static final int EARTH = 2;
    /** Angular size (rad) below which a sphere is drawn as a point. */
    private static final double POINT_FALLBACK_ANGLE = 3.0e-4;

    /**
     * Per-planet atmosphere tint + shell extent (fraction of radius) for the
     * additive limb glow. null = airless body. Index matches {@link Bodies#NAMES}.
     */
    private static final float[][] ATM = new float[COUNT][];
    static {
        ATM[1] = new float[]{0.95f, 0.85f, 0.55f, 0.10f};  // Venus  — thick, pale gold
        ATM[2] = new float[]{0.35f, 0.60f, 1.00f, 0.055f}; // Earth  — blue
        ATM[3] = new float[]{0.85f, 0.55f, 0.40f, 0.035f}; // Mars   — thin, dusty
        ATM[4] = new float[]{0.85f, 0.78f, 0.65f, 0.030f}; // Jupiter
        ATM[5] = new float[]{0.90f, 0.82f, 0.62f, 0.030f}; // Saturn
        ATM[6] = new float[]{0.55f, 0.85f, 0.90f, 0.045f}; // Uranus — cyan
        ATM[7] = new float[]{0.35f, 0.55f, 0.95f, 0.045f}; // Neptune — deep blue
    }

    private float[] starDirs;                 // unit directions, 3 per star
    private final List<Picker.Pickable> pickables = new ArrayList<>();

    /** Eye position (AU) for the current frame — the floating origin. */
    private final Vec3 eye = new Vec3();

    /** Set to a file path to capture the next rendered frame (GL framebuffer) to PNG. */
    public volatile String shotPath = null;

    public Scene(NBody sim, OrbitCamera cam, SimState state, SimulationModeManager modeMgr) {
        this.sim = sim; this.cam = cam; this.state = state; this.modeMgr = modeMgr;
    }

    @Override
    public void init(GLAutoDrawable d) {
        GL2 gl = d.getGL().getGL2();
        gl.glClearColor(0.01f, 0.01f, 0.03f, 1f);
        gl.glEnable(GL.GL_DEPTH_TEST);
        gl.glEnable(GL.GL_BLEND);
        gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
        gl.glEnable(GL2.GL_LINE_SMOOTH);
        gl.glEnable(GL2.GL_POINT_SMOOTH);
        gl.glHint(GL2.GL_LINE_SMOOTH_HINT, GL.GL_NICEST);

        quad = glu.gluNewQuadric();
        glu.gluQuadricNormals(quad, GLU.GLU_SMOOTH);

        // Lighting: single positional light at the Sun.
        gl.glEnable(GLLightingFunc.GL_LIGHTING);
        gl.glEnable(GLLightingFunc.GL_LIGHT0);
        gl.glEnable(GLLightingFunc.GL_COLOR_MATERIAL);
        gl.glColorMaterial(GL.GL_FRONT_AND_BACK, GLLightingFunc.GL_AMBIENT_AND_DIFFUSE);
        gl.glLightfv(GLLightingFunc.GL_LIGHT0, GLLightingFunc.GL_AMBIENT,  new float[]{0.05f, 0.05f, 0.06f, 1f}, 0);
        gl.glLightfv(GLLightingFunc.GL_LIGHT0, GLLightingFunc.GL_DIFFUSE,  new float[]{1f, 1f, 0.96f, 1f}, 0);
        gl.glLightfv(GLLightingFunc.GL_LIGHT0, GLLightingFunc.GL_SPECULAR, new float[]{0.3f, 0.3f, 0.3f, 1f}, 0);

        generateStars(1800);
        lastNanos = System.nanoTime();
    }

    private void generateStars(int n) {
        Random rng = new Random(42);
        starDirs = new float[n * 3];
        for (int i = 0; i < n; i++) {
            double u = rng.nextDouble() * 2 - 1;
            double th = rng.nextDouble() * 2 * Math.PI;
            double s = Math.sqrt(1 - u * u);
            starDirs[i * 3]     = (float) (s * Math.cos(th));
            starDirs[i * 3 + 1] = (float) u;
            starDirs[i * 3 + 2] = (float) (s * Math.sin(th));
        }
    }

    @Override public void dispose(GLAutoDrawable d) {
        GL2 gl = d.getGL().getGL2();
        Textures.disposeAll(gl);
        if (quad != null) glu.gluDeleteQuadric(quad);
    }

    @Override public void reshape(GLAutoDrawable d, int x, int y, int w, int h) {
        d.getGL().getGL2().glViewport(0, 0, w, h);
    }

    /* ---- world positions in AU ---- */
    private Vec3 sunAU() { Vec3 r = sim.sunPos(); return new Vec3(r.x / AU, r.y / AU, r.z / AU); }
    private Vec3 planetAU(int k) { Vec3 r = sim.planetPos(k); return new Vec3(r.x / AU, r.y / AU, r.z / AU); }
    private Vec3 moonAU(int k, int m) {
        Vec3 p = planetAU(k);
        Vec3 off = MoonModel.position(k, m, sim.simTime);
        double s = Scale.moonScale(k, m) / AU;
        return new Vec3(p.x + off.x * s, p.y + off.y * s, p.z + off.z * s);
    }

    /** World-AU position of the currently selected body, or null. */
    public Vec3 selectionTargetAU() {
        if (state.sunSelected) return sunAU();
        NBody.Body dyn = state.selectedDynamic;
        if (dyn != null) return dyn.r.scale(1.0 / AU);
        if (state.selectedPlanet >= 0) {
            if (state.selectedMoon >= 0) return moonAU(state.selectedPlanet, state.selectedMoon);
            return planetAU(state.selectedPlanet);
        }
        return null;
    }

    @Override
    public void display(GLAutoDrawable d) {
        GL2 gl = d.getGL().getGL2();
        int w = d.getSurfaceWidth(), h = d.getSurfaceHeight();

        // ---- timing + input + physics (KDK leapfrog) + collisions ----
        long now = System.nanoTime();
        double dtReal = Math.min(0.1, (now - lastNanos) / 1e9);
        lastNanos = now;
        double dtSim = state.paused ? 0 : dtReal * state.timeScale;
        modeMgr.update(dtReal, dtSim);
        if (dtSim != 0) {
            sim.step(dtSim);
            modeMgr.postStep();
        }

        // ---- camera follow (sandbox; flight modes latch in the mode manager) ----
        if (modeMgr.mode() == SimulationModeManager.Mode.SANDBOX && state.followSelected) {
            Vec3 t = selectionTargetAU();
            if (t != null) {
                double lerp = 1 - Math.exp(-10.0 * dtReal);
                cam.target.x += (t.x - cam.target.x) * lerp;
                cam.target.y += (t.y - cam.target.y) * lerp;
                cam.target.z += (t.z - cam.target.z) * lerp;
            }
        }

        viewH = h;
        gl.glViewport(0, 0, w, h);
        gl.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT);
        cam.apply(gl, w, h);
        eye.set(cam.eyeSnapshot);

        Vec3 sun = sunAU();
        drawStarfield(gl);

        // light at the Sun (eye-relative)
        gl.glLightfv(GLLightingFunc.GL_LIGHT0, GLLightingFunc.GL_POSITION,
                new float[]{(float) (sun.x - eye.x), (float) (sun.y - eye.y), (float) (sun.z - eye.z), 1f}, 0);

        if (state.showOrbits) drawOrbits(gl, sun);
        drawSun(gl, sun);
        drawPlanets(gl);
        if (state.showMoons) drawMoons(gl);
        if (state.showRings) drawRings(gl);
        drawDynamicBodies(gl);
        drawShip(gl);
        if (state.showTrajectory) drawPredictedPath(gl);
        drawSelectionHighlight(gl);

        rebuildPickables();

        if (DEBUG && frameCounter++ % 60 == 0) {
            Spacecraft sh = modeMgr.ship();
            System.err.printf("DBG mode=%s camDist=%.4e target=(%.6f,%.6f,%.6f) eye=(%.6f,%.6f,%.6f) glErr=%d ship=%s%n",
                    modeMgr.mode(), cam.distance,
                    cam.target.x, cam.target.y, cam.target.z,
                    eye.x, eye.y, eye.z, gl.glGetError(),
                    sh == null ? "none" : sh.body.r.toString());
        }

        if (shotPath != null) captureFrame(gl, d);
        if (recording) recordFrame(gl, d);
    }

    private static final boolean DEBUG = Boolean.getBoolean("astrosim.debug");
    private long frameCounter = 0;

    // ---- capture: PNG stills + animated-GIF recording ----
    private volatile boolean recording = false;
    private final List<BufferedImage> recFrames = new ArrayList<>();
    private int recTarget = 0;                 // frames to grab before writing
    private static final int REC_MAX_W = 900;  // downscale recordings for size/speed

    private static String stamp() {
        return new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
    }

    /** Queue a timestamped PNG of the next rendered frame. */
    public void requestScreenshot() { shotPath = "screenshots/shot_" + stamp() + ".png"; }

    /** Begin capturing ~{@code frames} frames, then write an animated GIF. */
    public void startRecording(int frames) {
        if (recording) return;
        recFrames.clear();
        recTarget = Math.max(1, frames);
        recording = true;
        System.out.println("Recording " + recTarget + " frames…");
    }
    public boolean isRecording() { return recording; }

    private void captureFrame(GL2 gl, GLAutoDrawable d) {
        String path = shotPath;
        shotPath = null;
        try {
            File f = new File(path);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            AWTGLReadBufferUtil u = new AWTGLReadBufferUtil(d.getGLProfile(), false);
            BufferedImage img = u.readPixelsToBufferedImage(gl, true);
            ImageIO.write(img, "png", f);
            System.out.println("Captured frame -> " + path);
        } catch (Exception ex) {
            System.err.println("Capture failed: " + ex);
        }
    }

    private void recordFrame(GL2 gl, GLAutoDrawable d) {
        try {
            AWTGLReadBufferUtil u = new AWTGLReadBufferUtil(d.getGLProfile(), false);
            BufferedImage full = u.readPixelsToBufferedImage(gl, true);
            recFrames.add(downscale(full, REC_MAX_W));
        } catch (Exception ex) {
            System.err.println("Record grab failed: " + ex);
        }
        if (recFrames.size() >= recTarget) {
            recording = false;
            final List<BufferedImage> frames = new ArrayList<>(recFrames);
            recFrames.clear();
            new Thread(() -> writeGif(frames, "screenshots/clip_" + stamp() + ".gif", 66), "gif-writer").start();
        }
    }

    private static BufferedImage downscale(BufferedImage src, int maxW) {
        int w = src.getWidth(), h = src.getHeight();
        if (w <= maxW) return src;
        int nw = maxW, nh = Math.max(1, (int) Math.round(h * (maxW / (double) w)));
        BufferedImage dst = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = dst.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        return dst;
    }

    /** Write an animated, looping GIF from RGB frames at {@code delayMs} per frame. */
    private static void writeGif(List<BufferedImage> frames, String path, int delayMs) {
        if (frames.isEmpty()) return;
        try {
            File f = new File(path);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            javax.imageio.ImageWriter w = ImageIO.getImageWritersBySuffix("gif").next();
            javax.imageio.stream.ImageOutputStream out = ImageIO.createImageOutputStream(f);
            w.setOutput(out);
            w.prepareWriteSequence(null);
            javax.imageio.ImageWriteParam p = w.getDefaultWriteParam();
            javax.imageio.ImageTypeSpecifier type =
                    javax.imageio.ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_RGB);
            for (int i = 0; i < frames.size(); i++) {
                javax.imageio.metadata.IIOMetadata meta = w.getDefaultImageMetadata(type, p);
                configureGifFrame(meta, delayMs, i == 0);
                w.writeToSequence(new javax.imageio.IIOImage(frames.get(i), null, meta), p);
            }
            w.endWriteSequence();
            out.close();
            w.dispose();
            System.out.println("Saved recording -> " + path + " (" + frames.size() + " frames)");
        } catch (Exception ex) {
            System.err.println("GIF write failed: " + ex);
        }
    }

    private static void configureGifFrame(javax.imageio.metadata.IIOMetadata meta, int delayMs, boolean first)
            throws Exception {
        String fmt = meta.getNativeMetadataFormatName();
        javax.imageio.metadata.IIOMetadataNode root =
                (javax.imageio.metadata.IIOMetadataNode) meta.getAsTree(fmt);
        gifChild(root, "GraphicControlExtension")
                .setAttribute("delayTime", Integer.toString(Math.max(1, delayMs / 10)));
        gifChild(root, "GraphicControlExtension").setAttribute("disposalMethod", "none");
        if (first) {
            javax.imageio.metadata.IIOMetadataNode ext = gifChild(root, "ApplicationExtensions");
            javax.imageio.metadata.IIOMetadataNode app = new javax.imageio.metadata.IIOMetadataNode("ApplicationExtension");
            app.setAttribute("applicationID", "NETSCAPE");
            app.setAttribute("authenticationCode", "2.0");
            app.setUserObject(new byte[]{0x1, 0, 0});   // loop forever
            ext.appendChild(app);
        }
        meta.setFromTree(fmt, root);
    }

    private static javax.imageio.metadata.IIOMetadataNode gifChild(
            javax.imageio.metadata.IIOMetadataNode root, String name) {
        for (int i = 0; i < root.getLength(); i++) {
            if (root.item(i).getNodeName().equalsIgnoreCase(name))
                return (javax.imageio.metadata.IIOMetadataNode) root.item(i);
        }
        javax.imageio.metadata.IIOMetadataNode n = new javax.imageio.metadata.IIOMetadataNode(name);
        root.appendChild(n);
        return n;
    }

    private void drawStarfield(GL2 gl) {
        if (!state.showStarfield) return;
        double radius = 800.0;
        gl.glDisable(GLLightingFunc.GL_LIGHTING);
        gl.glDisable(GL.GL_DEPTH_TEST);   // pure background: depth would saturate at flight scale
        gl.glDepthMask(false);
        gl.glPointSize(1.6f);
        gl.glBegin(GL.GL_POINTS);
        for (int i = 0; i < starDirs.length; i += 3) {
            float b = 0.5f + 0.5f * ((i / 3) % 7) / 7f;
            gl.glColor3f(b, b, b * 1.02f);
            // floating origin: stars are eye-centered by construction
            gl.glVertex3d(starDirs[i] * radius, starDirs[i + 1] * radius, starDirs[i + 2] * radius);
        }
        gl.glEnd();
        gl.glDepthMask(true);
        gl.glEnable(GL.GL_DEPTH_TEST);
        gl.glEnable(GLLightingFunc.GL_LIGHTING);
    }

    /* ---- body drawing ---- */

    /**
     * Depth-reliability horizon (AU). At flight zoom the float depth buffer
     * cannot resolve fragments much beyond ~1e5 camera distances — their NDC
     * depth rounds to exactly 1.0 and the depth test rejects them. Bodies whose
     * nearest surface lies beyond this are drawn as angular-preserving
     * background proxies (same direction and angular size, pulled inside the
     * horizon, depth-test off).
     */
    private double depthSafeDist() { return cam.distance * 3e5; }

    /** Background proxies must not shine through the planet we're flying at. */
    private boolean occludedByPrimary(Vec3 posAU) {
        return occludedByPrimary(posAU, -1);
    }

    /** @param selfPlanet planet index being drawn (excluded so a body never occludes itself) */
    private boolean occludedByPrimary(Vec3 posAU, int selfPlanet) {
        if (modeMgr.mode() == SimulationModeManager.Mode.SANDBOX) return false;
        int k = modeMgr.primaryPlanetIndex();
        if (k < 0 || k == selfPlanet) return false;
        Vec3 c = planetAU(k);
        double R = Scale.planetVisualR(k);
        Vec3 toC = c.sub(eye);
        Vec3 dir = posAU.sub(eye).normalized();
        double along = toC.dot(dir);
        if (along <= 0) return false;                          // planet behind the camera
        if (posAU.distance(eye) < along) return false;         // body closer than the planet
        double perp2 = toC.lengthSq() - along * along;
        return perp2 < R * R;
    }

    /** True if a sphere of radius rAU at posAU subtends less than the point threshold. */
    private boolean pointFallback(GL2 gl, Vec3 posAU, double rAU, Color c) {
        double dist = posAU.distance(eye);
        if (dist <= 0 || rAU / dist >= POINT_FALLBACK_ANGLE) return false;
        if (occludedByPrimary(posAU)) return true;
        gl.glDisable(GLLightingFunc.GL_LIGHTING);
        gl.glDisable(GL.GL_DEPTH_TEST);
        gl.glDepthMask(false);
        gl.glPointSize(2.5f);
        gl.glBegin(GL.GL_POINTS);
        gl.glColor3f(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f);
        gl.glVertex3d(posAU.x - eye.x, posAU.y - eye.y, posAU.z - eye.z);
        gl.glEnd();
        gl.glDepthMask(true);
        gl.glEnable(GL.GL_DEPTH_TEST);
        gl.glEnable(GLLightingFunc.GL_LIGHTING);
        return true;
    }

    /**
     * Choose a sphere subdivision count sized to how large the body appears on
     * screen, so a planet that fills the view is smooth (no faceted silhouette or
     * banded day/night terminator) while a distant speck stays cheap. {@code min}
     * is the floor used when the on-screen size can't be estimated.
     */
    private int adaptiveSlices(double distAU, double rAU, int min) {
        if (distAU <= 0) return min;
        double halfFov = Math.toRadians(cam.fovy * 0.5);
        // radius of the body on screen, in pixels
        double pxRadius = (rAU / distAU) / Math.tan(halfFov) * (viewH * 0.5);
        // ~one segment per 3 px of silhouette keeps edges and shading smooth
        int s = (int) Math.round(pxRadius / 3.0);
        if (s < min) s = min;
        if (s > 256) s = 256;      // cap: 256x256 is ample even full-screen
        return s;
    }

    /**
     * Lit sphere with optional diffuse texture, axial tilt and sidereal spin.
     * Texture poles are aligned to the (obliquity-tilted) rotation axis.
     * Bodies beyond the depth horizon are re-projected as background proxies.
     */
    private void drawSphereBody(GL2 gl, Vec3 posAU, double rAU, String texKey, Color color,
                                double obliquityDeg, double spinDeg, int slices) {
        drawSphereBody(gl, posAU, rAU, texKey, color, obliquityDeg, spinDeg, slices, -1, null);
    }

    private void drawSphereBody(GL2 gl, Vec3 posAU, double rAU, String texKey, Color color,
                                double obliquityDeg, double spinDeg, int slices, int selfPlanet) {
        drawSphereBody(gl, posAU, rAU, texKey, color, obliquityDeg, spinDeg, slices, selfPlanet, null);
    }

    /**
     * @param occAbs eclipse occluders as flat quads (worldX, worldY, worldZ, radius)
     *               in AU — bodies that can cast a shadow onto this one (a planet on
     *               its moon, a moon transiting its planet). null = none.
     */
    private void drawSphereBody(GL2 gl, Vec3 posAU, double rAU, String texKey, Color color,
                                double obliquityDeg, double spinDeg, int slices, int selfPlanet,
                                double[] occAbs) {
        double dist = posAU.distance(eye);
        double safe = depthSafeDist();
        boolean background = dist - rAU > safe;
        if (background) {
            if (occludedByPrimary(posAU, selfPlanet)) return;
            double s = (safe * 0.5) / dist;       // pull inside the horizon, keep angular size
            posAU = new Vec3(eye.x + (posAU.x - eye.x) * s,
                             eye.y + (posAU.y - eye.y) * s,
                             eye.z + (posAU.z - eye.z) * s);
            rAU *= s;
            gl.glDisable(GL.GL_DEPTH_TEST);
            gl.glDepthMask(false);
        }
        Texture tex = texKey == null ? null : Textures.diffuse(gl, texKey);
        Texture night = null, clouds = null;
        if (texKey != null) {                              // optional extra maps, drop-in
            night  = Textures.map(gl, texKey, "night");
            clouds = Textures.map(gl, texKey, "clouds");
        }
        // Sun direction and the model rotation (used for per-vertex night lights and
        // eclipse shading, computed in world space alongside GL's own lighting).
        Vec3 L = sunAU().sub(posAU).normalized();
        double[] rot = modelRot(obliquityDeg, spinDeg);
        double[] occRel = background ? null : relOccluders(occAbs, posAU, L, rAU);

        gl.glPushMatrix();
        gl.glTranslated(posAU.x - eye.x, posAU.y - eye.y, posAU.z - eye.z);
        gl.glRotated(obliquityDeg, 1, 0, 0);   // tilt spin axis off the ecliptic normal
        gl.glRotated(-90, 1, 0, 0);            // sphere pole +Z -> +Y
        gl.glRotated(spinDeg, 0, 0, 1);        // sidereal rotation about the pole

        int seg = adaptiveSlices(posAU.distance(eye), rAU, slices);
        boolean ocean = selfPlanet == EARTH;   // subtle specular sheen
        drawLitSphere(gl, rAU, seg, tex, night, color, rot, L, occRel, ocean);
        if (clouds != null) drawCloudShell(gl, rAU, seg, clouds, rot, L, occRel);

        if (selfPlanet >= 0 && ATM[selfPlanet] != null) drawAtmosphere(gl, rAU, seg, ATM[selfPlanet]);
        gl.glPopMatrix();
        if (background) {
            gl.glDepthMask(true);
            gl.glEnable(GL.GL_DEPTH_TEST);
        }
    }

    private static final int GL_FRONT_AND_BACK = 0x0408;

    /**
     * Body-local -> world rotation (3x3, row-major) matching the GL model matrix
     * {@code Rx(obliquity)·Rx(-90)·Rz(spin)}. Used to shade vertices in world space
     * for night lights and eclipses while GL does its own lighting from local normals.
     */
    private static double[] modelRot(double obliquityDeg, double spinDeg) {
        double a = Math.toRadians(obliquityDeg - 90), s = Math.toRadians(spinDeg);
        double cA = Math.cos(a), sA = Math.sin(a), cs = Math.cos(s), ss = Math.sin(s);
        return new double[]{
                cs,      -ss,      0,
                cA * ss,  cA * cs, -sA,
                sA * ss,  sA * cs,  cA
        };
    }

    /** Filter occluders (absolute AU) to those that could actually shadow the body
     *  at {@code center} of radius {@code rAU}, returned as offsets from the body. */
    private double[] relOccluders(double[] occAbs, Vec3 center, Vec3 L, double rAU) {
        if (occAbs == null) return null;
        double[] tmp = new double[occAbs.length];
        int n = 0;
        for (int i = 0; i + 3 < occAbs.length; i += 4) {
            double ox = occAbs[i] - center.x, oy = occAbs[i + 1] - center.y, oz = occAbs[i + 2] - center.z;
            double rad = occAbs[i + 3];
            double tproj = ox * L.x + oy * L.y + oz * L.z;   // sunward distance
            if (tproj <= 0) continue;                        // occluder on the night side
            double px = ox - tproj * L.x, py = oy - tproj * L.y, pz = oz - tproj * L.z;
            double perp = Math.sqrt(px * px + py * py + pz * pz);
            if (perp > rad + rAU) continue;                  // umbra can't reach the body
            tmp[n++] = ox; tmp[n++] = oy; tmp[n++] = oz; tmp[n++] = rad;
        }
        if (n == 0) return null;
        double[] out = new double[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }

    /** Sunlight fraction (0.12 umbra .. 1 lit) at a vertex world-offset, given occluders. */
    private static double eclipseShade(double vx, double vy, double vz, Vec3 L, double[] occ) {
        double shade = 1.0;
        for (int i = 0; i + 3 < occ.length; i += 4) {
            double rx = occ[i] - vx, ry = occ[i + 1] - vy, rz = occ[i + 2] - vz;  // vertex -> occluder
            double rad = occ[i + 3];
            double tproj = rx * L.x + ry * L.y + rz * L.z;
            if (tproj <= 0) continue;
            double px = rx - tproj * L.x, py = ry - tproj * L.y, pz = rz - tproj * L.z;
            double perp = Math.sqrt(px * px + py * py + pz * pz);
            double umbra = rad, pen = rad * 1.25;
            double f = perp <= umbra ? 0.12 : perp >= pen ? 1.0 : 0.12 + 0.88 * (perp - umbra) / (pen - umbra);
            if (f < shade) shade = f;
        }
        return shade;
    }

    /**
     * A GLU-compatible sphere emitted by hand so each vertex can carry an eclipse
     * darkening (per-vertex colour) and an optional additive night-lights pass —
     * neither of which GLU's sphere allows. Day pass is GL-lit from local normals,
     * exactly like the old gluSphere path when there are no extra maps or shadows.
     */
    private void drawLitSphere(GL2 gl, double r, int seg, Texture day, Texture night,
                               Color color, double[] rot, Vec3 L, double[] occ, boolean ocean) {
        int stacks = Math.max(4, seg), slices = Math.max(4, seg);
        double drho = Math.PI / stacks, dtheta = 2 * Math.PI / slices;
        boolean textured = day != null;
        float br = textured ? 1f : color.getRed() / 255f;
        float bg = textured ? 1f : color.getGreen() / 255f;
        float bb = textured ? 1f : color.getBlue() / 255f;

        if (textured) {
            gl.glEnable(GL.GL_TEXTURE_2D);
            day.bind(gl);
            gl.glTexEnvi(GL2.GL_TEXTURE_ENV, GL2.GL_TEXTURE_ENV_MODE, GL2.GL_MODULATE);
        }
        if (ocean) setSpecular(gl, 0.20f, 22f);
        for (int i = 0; i < stacks; i++) {
            double rho = i * drho, rho1 = rho + drho;
            gl.glBegin(GL2.GL_QUAD_STRIP);
            for (int j = 0; j <= slices; j++) {
                double theta = (j == slices) ? 0 : j * dtheta;
                litVertex(gl, r, rho,  theta, (double) i / stacks,       (double) j / slices, rot, L, occ, br, bg, bb, textured);
                litVertex(gl, r, rho1, theta, (double) (i + 1) / stacks, (double) j / slices, rot, L, occ, br, bg, bb, textured);
            }
            gl.glEnd();
        }
        if (ocean) setSpecular(gl, 0f, 0f);
        if (textured) gl.glDisable(GL.GL_TEXTURE_2D);

        if (night != null) nightPass(gl, r, stacks, slices, drho, dtheta, night, rot, L);
    }

    private void litVertex(GL2 gl, double r, double rho, double theta, double iFrac, double s,
                           double[] rot, Vec3 L, double[] occ, float br, float bg, float bb, boolean textured) {
        double sx = -Math.sin(theta) * Math.sin(rho);
        double sy =  Math.cos(theta) * Math.sin(rho);
        double sz =  Math.cos(rho);
        double shade = 1.0;
        if (occ != null) {
            double wx = rot[0] * sx + rot[1] * sy + rot[2] * sz;
            double wy = rot[3] * sx + rot[4] * sy + rot[5] * sz;
            double wz = rot[6] * sx + rot[7] * sy + rot[8] * sz;
            shade = eclipseShade(wx * r, wy * r, wz * r, L, occ);
        }
        if (textured) gl.glTexCoord2d(s, 1.0 - iFrac);
        gl.glColor3d(br * shade, bg * shade, bb * shade);
        gl.glNormal3d(sx, sy, sz);
        gl.glVertex3d(r * sx, r * sy, r * sz);
    }

    /** Additive city-lights pass: opaque only where the surface faces away from the Sun. */
    private void nightPass(GL2 gl, double r, int stacks, int slices, double drho, double dtheta,
                           Texture night, double[] rot, Vec3 L) {
        gl.glDisable(GLLightingFunc.GL_LIGHTING);
        gl.glDepthFunc(GL.GL_LEQUAL);
        gl.glDepthMask(false);
        gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE);
        gl.glEnable(GL.GL_TEXTURE_2D);
        night.bind(gl);
        gl.glTexEnvi(GL2.GL_TEXTURE_ENV, GL2.GL_TEXTURE_ENV_MODE, GL2.GL_MODULATE);
        for (int i = 0; i < stacks; i++) {
            double rho = i * drho, rho1 = rho + drho;
            gl.glBegin(GL2.GL_QUAD_STRIP);
            for (int j = 0; j <= slices; j++) {
                double theta = (j == slices) ? 0 : j * dtheta;
                nightVertex(gl, r, rho,  theta, (double) i / stacks,       (double) j / slices, rot, L);
                nightVertex(gl, r, rho1, theta, (double) (i + 1) / stacks, (double) j / slices, rot, L);
            }
            gl.glEnd();
        }
        gl.glDisable(GL.GL_TEXTURE_2D);
        gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
        gl.glDepthMask(true);
        gl.glDepthFunc(GL.GL_LESS);
        gl.glEnable(GLLightingFunc.GL_LIGHTING);
    }

    private void nightVertex(GL2 gl, double r, double rho, double theta, double iFrac, double s,
                             double[] rot, Vec3 L) {
        double sx = -Math.sin(theta) * Math.sin(rho);
        double sy =  Math.cos(theta) * Math.sin(rho);
        double sz =  Math.cos(rho);
        double wx = rot[0] * sx + rot[1] * sy + rot[2] * sz;
        double wy = rot[3] * sx + rot[4] * sy + rot[5] * sz;
        double wz = rot[6] * sx + rot[7] * sy + rot[8] * sz;
        double ndl = wx * L.x + wy * L.y + wz * L.z;         // >0 day, <0 night
        double a = (0.10 - ndl) / 0.25;                      // fade across the terminator
        a = a < 0 ? 0 : a > 1 ? 1 : a;
        gl.glTexCoord2d(s, 1.0 - iFrac);
        gl.glColor4d(1, 1, 1, a);
        gl.glVertex3d(r * sx, r * sy, r * sz);
    }

    /** Cloud layer as a translucent lit shell just above the surface. */
    private void drawCloudShell(GL2 gl, double r, int seg, Texture clouds, double[] rot, Vec3 L, double[] occ) {
        gl.glEnable(GL.GL_TEXTURE_2D);
        clouds.bind(gl);
        gl.glTexEnvi(GL2.GL_TEXTURE_ENV, GL2.GL_TEXTURE_ENV_MODE, GL2.GL_MODULATE);
        gl.glDepthMask(false);
        drawLitSphere(gl, r * 1.012, seg, clouds, null, Color.WHITE, rot, L, occ, false);
        gl.glDepthMask(true);
        gl.glDisable(GL.GL_TEXTURE_2D);
    }

    private void setSpecular(GL2 gl, float level, float shininess) {
        gl.glMaterialfv(GL_FRONT_AND_BACK, GLLightingFunc.GL_SPECULAR, new float[]{level, level, level, 1f}, 0);
        gl.glMaterialf(GL_FRONT_AND_BACK, GLLightingFunc.GL_SHININESS, shininess);
    }

    /**
     * Additive haze shells just outside the planet surface, giving a soft
     * atmospheric limb glow in the body's tint. Assumes the model matrix is the
     * body's; draws unlit with depth writes off so it never occludes moons.
     */
    private void drawAtmosphere(GL2 gl, double rAU, int seg, float[] atm) {
        int s = Math.max(24, Math.min(seg, 96));
        float r = atm[0], g = atm[1], b = atm[2], ext = atm[3];
        gl.glDisable(GLLightingFunc.GL_LIGHTING);
        gl.glDepthMask(false);
        gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE);   // additive
        // a few thin shells; alpha falls off outward so the glow hugs the limb
        float[] frac  = {0.35f, 0.70f, 1.00f};
        float[] alpha = {0.16f, 0.09f, 0.04f};
        for (int i = 0; i < frac.length; i++) {
            gl.glColor4f(r, g, b, alpha[i]);
            glu.gluSphere(quad, rAU * (1.0 + ext * frac[i]), s, s);
        }
        gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
        gl.glDepthMask(true);
        gl.glEnable(GLLightingFunc.GL_LIGHTING);
    }

    private void drawSun(GL2 gl, Vec3 sun) {
        double r = Scale.sunVisualR();
        double dist = sun.distance(eye);
        double safe = depthSafeDist();
        boolean background = dist - r > safe;
        if (background) {
            if (occludedByPrimary(sun)) return;
            double s = (safe * 0.5) / dist;
            sun = new Vec3(eye.x + (sun.x - eye.x) * s,
                           eye.y + (sun.y - eye.y) * s,
                           eye.z + (sun.z - eye.z) * s);
            r *= s;
            gl.glDisable(GL.GL_DEPTH_TEST);
        }
        gl.glDisable(GLLightingFunc.GL_LIGHTING);

        Texture tex = Textures.diffuse(gl, "sun");
        gl.glPushMatrix();
        gl.glTranslated(sun.x - eye.x, sun.y - eye.y, sun.z - eye.z);
        gl.glRotated(-90, 1, 0, 0);
        gl.glRotated(Math.toDegrees((2 * Math.PI / (SUN_ROTATION_H * 3600.0)) * sim.simTime % (2 * Math.PI)), 0, 0, 1);
        if (tex != null) {
            gl.glEnable(GL.GL_TEXTURE_2D);
            tex.bind(gl);
            glu.gluQuadricTexture(quad, true);
            gl.glColor3f(1f, 1f, 1f);
        } else {
            gl.glColor3f(1f, 0.95f, 0.8f);
        }
        int sunSeg = adaptiveSlices(sun.distance(eye), r, 32);
        glu.gluSphere(quad, r, sunSeg, sunSeg);
        if (tex != null) { glu.gluQuadricTexture(quad, false); gl.glDisable(GL.GL_TEXTURE_2D); }

        // additive glow halos
        gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE);
        gl.glDepthMask(false);
        gl.glColor4f(1f, 0.85f, 0.4f, 0.25f);
        glu.gluSphere(quad, r * 1.4, 24, 24);
        gl.glColor4f(1f, 0.7f, 0.25f, 0.12f);
        glu.gluSphere(quad, r * 2.0, 20, 20);
        gl.glDepthMask(true);
        gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
        gl.glPopMatrix();
        if (background) gl.glEnable(GL.GL_DEPTH_TEST);
        gl.glEnable(GLLightingFunc.GL_LIGHTING);
    }

    private void drawPlanets(GL2 gl) {
        for (int k = 0; k < COUNT; k++) {
            Vec3 p = planetAU(k);
            double r = Scale.planetVisualR(k);
            if (pointFallback(gl, p, r, COLORS[k])) continue;
            double spinDeg = Math.toDegrees(spinAngleRad(k, sim.simTime));
            // close-range true scale benefits from a denser mesh
            int slices = Scale.trueScale && p.distance(eye) < r * 60 ? 48 : 24;
            drawSphereBody(gl, p, r, textureKey(k), COLORS[k], OBLIQUITY_DEG[k], spinDeg, slices, k, moonOccluders(k));
        }
    }

    /** Moons of planet k as eclipse occluders (world AU centre + visual radius), for shadow transits. */
    private double[] moonOccluders(int k) {
        int nm = MOON_NAMES[k].length;
        if (nm == 0) return null;
        double[] a = new double[nm * 4];
        for (int m = 0; m < nm; m++) {
            Vec3 mp = moonAU(k, m);
            a[m * 4] = mp.x; a[m * 4 + 1] = mp.y; a[m * 4 + 2] = mp.z; a[m * 4 + 3] = Scale.moonVisualR(k, m);
        }
        return a;
    }

    private void drawMoons(GL2 gl) {
        Color moonGrey = new Color(204, 204, 209);
        for (int k = 0; k < COUNT; k++) {
            int nm = MOON_NAMES[k].length;
            if (nm == 0) continue;
            Vec3 p = planetAU(k);
            for (int m = 0; m < nm; m++) {
                // moon orbit line
                if (state.showOrbits) {
                    gl.glDisable(GLLightingFunc.GL_LIGHTING);
                    gl.glColor4f(0.6f, 0.6f, 0.7f, 0.35f);
                    gl.glBegin(GL.GL_LINE_LOOP);
                    double s = Scale.moonScale(k, m) / AU;
                    for (int j = 0; j < 96; j++) {
                        double M = 2 * Math.PI * j / 96;
                        Vec3 off = MoonModel.positionAtAnomaly(k, m, M);
                        gl.glVertex3d(p.x + off.x * s - eye.x, p.y + off.y * s - eye.y, p.z + off.z * s - eye.z);
                    }
                    gl.glEnd();
                    gl.glEnable(GLLightingFunc.GL_LIGHTING);
                }
                Vec3 mp = moonAU(k, m);
                double mr = Scale.moonVisualR(k, m);
                if (pointFallback(gl, mp, mr, moonGrey)) continue;
                // Earth's Moon gets a texture hook; others use the generic grey
                String texKey = (k == 2 && m == 0) ? "moon" : null;
                // the parent planet can eclipse the moon (its shadow → a "lunar eclipse")
                double[] occ = {p.x, p.y, p.z, Scale.planetVisualR(k)};
                drawSphereBody(gl, mp, mr, texKey, moonGrey, 0, 0, 14, -1, occ);
            }
        }
    }

    private void drawOrbits(GL2 gl, Vec3 sun) {
        gl.glDisable(GLLightingFunc.GL_LIGHTING);
        gl.glLineWidth(1.2f);
        for (int k = 0; k < COUNT; k++) {
            Color c = COLORS[k];
            gl.glColor4f(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, 0.45f);
            gl.glBegin(GL.GL_LINE_LOOP);
            for (int j = 0; j < 256; j++) {
                double M = 2 * Math.PI * j / 256;
                Vec3 pt = Orbits.pointAU(k, M);
                gl.glVertex3d(sun.x + pt.x - eye.x, sun.y + pt.y - eye.y, sun.z + pt.z - eye.z);
            }
            gl.glEnd();
        }
        gl.glEnable(GLLightingFunc.GL_LIGHTING);
    }

    private void drawRings(GL2 gl) {
        Vec3 p = planetAU(SATURN);
        double pr = Scale.planetVisualR(SATURN);
        if (pr / Math.max(1e-12, p.distance(eye)) < POINT_FALLBACK_ANGLE) return;
        if (p.distance(eye) - pr > depthSafeDist()) return;   // beyond depth horizon: proxy sphere only
        double inner = pr * 1.25, outer = pr * 2.3;

        // Sun direction (sun -> planet) for casting Saturn's shadow onto the rings.
        Vec3 sun = sunAU();
        Vec3 sunDir = p.sub(sun).normalized();
        double tiltR = Math.toRadians(SATURN_RING_TILT_DEG);
        double sinT = Math.sin(tiltR), cosT = Math.cos(tiltR);

        gl.glDisable(GLLightingFunc.GL_LIGHTING);
        gl.glDisable(GL.GL_CULL_FACE);
        gl.glDepthMask(false);
        gl.glPushMatrix();
        gl.glTranslated(p.x - eye.x, p.y - eye.y, p.z - eye.z);
        gl.glRotated(SATURN_RING_TILT_DEG, 1, 0, 0);

        // Dense enough that the annulus edges read as smooth circles even when the
        // rings fill the screen; scales with on-screen size, capped for cost.
        int segs = Math.max(128, Math.min(512, adaptiveSlices(p.distance(eye), outer, 128) * 2));

        // saturn_ring.png is a radial strip: U runs inner(0)->outer(1) and carries
        // the real ring structure (Cassini division, banding, per-radius opacity).
        Texture ring = Textures.named(gl, "saturn_ring.png");
        boolean textured = ring != null;
        if (textured) {
            gl.glEnable(GL.GL_TEXTURE_2D);
            ring.bind(gl);
            ring.setTexParameteri(gl, GL2.GL_TEXTURE_WRAP_S, GL2.GL_CLAMP_TO_EDGE);
            gl.glTexEnvi(GL2.GL_TEXTURE_ENV, GL2.GL_TEXTURE_ENV_MODE, GL2.GL_MODULATE);
        }
        gl.glBegin(GL.GL_TRIANGLE_STRIP);
        for (int j = 0; j <= segs; j++) {
            double a = 2 * Math.PI * j / segs;
            double ca = Math.cos(a), sa = Math.sin(a);
            // world-space offset of each rim point from the planet centre (rings are
            // tilted about the ecliptic X axis), used for the shadow test.
            double si = ringShade(inner * ca, inner * sa, sinT, cosT, sunDir, pr);
            double so = ringShade(outer * ca, outer * sa, sinT, cosT, sunDir, pr);
            if (textured) {
                gl.glColor4f((float) si, (float) si, (float) si, 1f);
                gl.glTexCoord2d(0.0, 0.5); gl.glVertex3d(inner * ca, 0, inner * sa);
                gl.glColor4f((float) so, (float) so, (float) so, 1f);
                gl.glTexCoord2d(1.0, 0.5); gl.glVertex3d(outer * ca, 0, outer * sa);
            } else {
                gl.glColor4f((float) (0.82 * si), (float) (0.74 * si), (float) (0.55 * si), 0.55f);
                gl.glVertex3d(inner * ca, 0, inner * sa);
                gl.glColor4f((float) (0.78 * so), (float) (0.70 * so), (float) (0.50 * so), 0.18f);
                gl.glVertex3d(outer * ca, 0, outer * sa);
            }
        }
        gl.glEnd();
        if (textured) gl.glDisable(GL.GL_TEXTURE_2D);
        gl.glPopMatrix();
        gl.glDepthMask(true);
        gl.glEnable(GLLightingFunc.GL_LIGHTING);
    }

    /**
     * Brightness multiplier for a ring point at in-plane coords (x, z), 1.0 in
     * sunlight and dropping toward ~0.3 inside the planet's shadow cylinder, with
     * a soft penumbra edge. {@code sinT/cosT} tilt the ring plane about X into the
     * world frame; {@code sunDir} points sun->planet; {@code pr} is planet radius.
     */
    private double ringShade(double x, double z, double sinT, double cosT, Vec3 sunDir, double pr) {
        // ring-plane point tilted into the ecliptic frame, relative to planet centre
        double ox = x, oy = -z * sinT, oz = z * cosT;
        double along = ox * sunDir.x + oy * sunDir.y + oz * sunDir.z;   // + = anti-sun side
        if (along <= 0) return 1.0;                                     // sunlit side of planet
        double perp2 = (ox * ox + oy * oy + oz * oz) - along * along;   // distance from shadow axis
        double perp = Math.sqrt(Math.max(0, perp2));
        double umbra = pr, penumbra = pr * 1.12;
        if (perp >= penumbra) return 1.0;
        if (perp <= umbra) return 0.30;
        double t = (perp - umbra) / (penumbra - umbra);                 // 0..1 across penumbra
        return 0.30 + 0.70 * t;
    }

    /* ---- runtime bodies, spacecraft, prediction ---- */

    private void drawDynamicBodies(GL2 gl) {
        Spacecraft ship = modeMgr.ship();
        for (NBody.Body b : sim.dynamicBodies()) {
            if (ship != null && b == ship.body) continue;   // the ship has its own model
            Vec3 p = b.r.scale(1.0 / AU);
            double r = Scale.bodyVisualR(b.radiusM);
            if (pointFallback(gl, p, r, b.color)) continue;
            drawSphereBody(gl, p, r, null, b.color, 0, 0, 20);
        }
    }

    /** Cone-and-cylinder spacecraft, oriented by its attitude quaternion, with a throttle flame. */
    private void drawShip(GL2 gl) {
        Spacecraft ship = modeMgr.ship();
        if (ship == null) return;
        Vec3 p = ship.body.r.scale(1.0 / AU);
        double len = Spacecraft.HEIGHT_M / AU;

        // far away (e.g. sandbox view): just a marker point
        if (pointFallback(gl, p, len * 2, new Color(170, 220, 255))) return;

        double rad = len * 0.22;
        double[] m16 = new double[16];
        ship.orientation.toMatrix(m16);

        gl.glPushMatrix();
        gl.glTranslated(p.x - eye.x, p.y - eye.y, p.z - eye.z);
        gl.glMultMatrixd(m16, 0);

        // hull: cylinder along +Z (forward), centered on origin
        gl.glColor3f(0.82f, 0.86f, 0.92f);
        gl.glPushMatrix();
        gl.glTranslated(0, 0, -len * 0.5);
        glu.gluCylinder(quad, rad, rad, len * 0.7, 16, 1);
        gl.glPopMatrix();
        // nose cone
        gl.glColor3f(0.95f, 0.55f, 0.25f);
        gl.glPushMatrix();
        gl.glTranslated(0, 0, len * 0.2);
        glu.gluCylinder(quad, rad, 0, len * 0.3, 16, 1);
        gl.glPopMatrix();
        // engine bell
        gl.glColor3f(0.35f, 0.37f, 0.42f);
        gl.glPushMatrix();
        gl.glTranslated(0, 0, -len * 0.62);
        glu.gluCylinder(quad, rad * 0.5, rad * 0.85, len * 0.12, 14, 1);
        gl.glPopMatrix();

        // exhaust flame scales with achieved thrust acceleration
        double aMag = ship.thrustAccel.length();
        if (aMag > 0.01) {
            double flame = len * (0.4 + 1.6 * Math.min(1, aMag / 20.0));
            gl.glDisable(GLLightingFunc.GL_LIGHTING);
            gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE);
            gl.glDepthMask(false);
            gl.glColor4f(1f, 0.75f, 0.3f, 0.8f);
            gl.glPushMatrix();
            gl.glTranslated(0, 0, -len * 0.62);
            gl.glRotated(180, 1, 0, 0);
            glu.gluCylinder(quad, rad * 0.45, 0, flame, 12, 1);
            gl.glPopMatrix();
            gl.glDepthMask(true);
            gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
            gl.glEnable(GLLightingFunc.GL_LIGHTING);
        }
        gl.glPopMatrix();
    }

    /** Predicted coast trajectory from the mode manager (metres -> AU), alpha-faded with look-ahead. */
    private void drawPredictedPath(GL2 gl) {
        Vec3[] path = modeMgr.predictedPath();
        if (path == null || path.length < 2) return;
        Spacecraft ship = modeMgr.ship();
        if (ship == null) return;

        gl.glDisable(GLLightingFunc.GL_LIGHTING);
        gl.glDepthMask(false);
        gl.glLineWidth(1.8f);
        gl.glBegin(GL.GL_LINE_STRIP);
        Vec3 sp = ship.body.r;
        gl.glColor4f(0.45f, 1f, 0.65f, 0.95f);
        gl.glVertex3d(sp.x / AU - eye.x, sp.y / AU - eye.y, sp.z / AU - eye.z);
        for (int i = 0; i < path.length; i++) {
            float t = (float) i / path.length;
            gl.glColor4f(0.45f, 1f, 0.65f, 0.9f * (1 - t * 0.8f));
            gl.glVertex3d(path[i].x / AU - eye.x, path[i].y / AU - eye.y, path[i].z / AU - eye.z);
        }
        gl.glEnd();
        gl.glDepthMask(true);
        gl.glEnable(GLLightingFunc.GL_LIGHTING);
    }

    private void drawSelectionHighlight(GL2 gl) {
        Vec3 t = selectionTargetAU();
        if (t == null) return;
        double r;
        if (state.sunSelected) r = Scale.sunVisualR();
        else if (state.selectedDynamic != null) r = Math.max(Scale.bodyVisualR(state.selectedDynamic.radiusM), cam.distance * 0.03);
        else if (state.selectedMoon >= 0) r = Scale.moonVisualR(state.selectedPlanet, state.selectedMoon);
        else r = Scale.planetVisualR(state.selectedPlanet);

        gl.glDisable(GLLightingFunc.GL_LIGHTING);
        gl.glDepthMask(false);
        gl.glLineWidth(1.5f);
        gl.glColor4f(0.4f, 0.9f, 1f, 0.9f);
        // a camera-facing ring
        Vec3 e = cam.eyeSnapshot;
        Vec3 fwd = t.sub(e).normalized();
        Vec3 up0 = new Vec3(0, 1, 0);
        Vec3 right = fwd.cross(up0).normalized();
        Vec3 up = right.cross(fwd).normalized();
        double rr = r * 1.6;
        gl.glBegin(GL.GL_LINE_LOOP);
        for (int j = 0; j < 64; j++) {
            double a = 2 * Math.PI * j / 64;
            double cx = Math.cos(a) * rr, cy = Math.sin(a) * rr;
            gl.glVertex3d(t.x + right.x * cx + up.x * cy - eye.x,
                          t.y + right.y * cx + up.y * cy - eye.y,
                          t.z + right.z * cx + up.z * cy - eye.z);
        }
        gl.glEnd();
        gl.glDepthMask(true);
        gl.glEnable(GLLightingFunc.GL_LIGHTING);
    }

    private void rebuildPickables() {
        List<Picker.Pickable> list = new ArrayList<>();
        double pad = cam.distance * 0.012;   // min clickable radius scales with zoom
        list.add(new Picker.Pickable(sunAU(), Math.max(Scale.sunVisualR() * 1.2, pad), -1, -1));
        for (int k = 0; k < COUNT; k++) {
            list.add(new Picker.Pickable(planetAU(k), Math.max(Scale.planetVisualR(k) * 1.4, pad), k, -1));
            if (state.showMoons) {
                int nm = MOON_NAMES[k].length;
                for (int m = 0; m < nm; m++)
                    list.add(new Picker.Pickable(moonAU(k, m), Math.max(Scale.moonVisualR(k, m) * 1.6, pad * 0.4), k, m));
            }
        }
        for (NBody.Body b : sim.dynamicBodies())
            list.add(new Picker.Pickable(b.r.scale(1.0 / AU),
                    Math.max(Scale.bodyVisualR(b.radiusM) * 1.6, pad * 0.6), b));
        synchronized (pickables) { pickables.clear(); pickables.addAll(list); }
    }

    /** Snapshot of pickable bodies for the mouse handler (called on EDT). */
    public List<Picker.Pickable> snapshotPickables() {
        synchronized (pickables) { return new ArrayList<>(pickables); }
    }

    /** Project a body's label anchor to screen; convenience for the HUD overlay. */
    public Vec3 planetWorldAU(int k) { return planetAU(k); }
    public Vec3 sunWorldAU() { return sunAU(); }
    public Vec3 moonWorldAU(int k, int m) { return moonAU(k, m); }
}
