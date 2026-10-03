import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLDrawableFactory;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.GLOffscreenAutoDrawable;
import com.jogamp.opengl.GLProfile;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * GPU sphere shader for the 2-D solar-system sim (Tier-2 "GLSL pipeline" enabler).
 *
 * <p>This is a drop-in replacement for the per-pixel CPU shading in
 * {@code SolarSystemSimulation.drawTexturedSphere}: it renders one lit, textured
 * sphere <em>impostor</em> (a screen-space quad; the fragment shader ray-casts a unit
 * sphere, derives the surface normal + lat/lon, samples an equirectangular diffuse map,
 * and applies Lambert + ambient, Earth's cloud/night/ocean-glint layers, and true 3-D
 * eclipse shadows) into an off-screen FBO, then reads the {@code n x n} RGBA raster back
 * into the very same {@code int[]} the CPU path fills. The caller blits it unchanged, so
 * compositing, positioning, atmosphere haloes and Saturn's rings are all untouched.</p>
 *
 * <p>Because the shading now runs in GLSL, it also exposes the "reserved" normal-map and
 * specular-map hooks: if {@code assets/textures/<key>_normal.jpg} / {@code _specular.jpg}
 * exist they are bound and used for real surface relief and specular highlights. Bodies
 * without those maps render identically to the CPU path, so the two can be compared
 * pixel-for-pixel via the {@code -Dsss.shot} harness.</p>
 *
 * <p>All GL work happens inside {@link #display} on whatever thread calls
 * {@link #shade} (the Swing EDT), driven by a queued request; there is no background
 * render thread. If an off-screen GL context cannot be created (no driver, headless
 * without GL, etc.) {@link #isAvailable()} stays false and the caller silently keeps the
 * CPU path.</p>
 */
final class GLSphereRenderer implements GLEventListener {

    /** Matches SolarSystemSimulation.SPHERE_RASTER_CAP: the largest raster we ever shade. */
    static final int MAX_SIZE = 820;

    private GLOffscreenAutoDrawable drawable;
    private boolean available = false;
    private boolean initFailed = false;
    private String lastError = null;

    private int program = 0;
    // Cached uniform locations.
    private int uPole, uA, uB, uL, uSpin, uCloudSpin, uAmbient;
    private int uEmissive, uEarth, uHasNight, uHasClouds, uOceanGlint, uHasNormal, uHasSpec;
    private int uOccN, uOcc, uSunAlpha;
    private int uDiffuse, uNight, uClouds, uNormal, uSpec;

    /** GL texture handles by cache key (diffuse keyed by body name; layers by their own key). */
    private final Map<String, Integer> texIds = new HashMap<>();
    /** Keys we tried to load an optional (_normal/_specular) map for and failed — don't retry. */
    private final Map<String, Boolean> optionalTried = new HashMap<>();

    // --- Queued render request (set by shade(), consumed by display()) --------------------
    private int[] outPixels;      // destination ARGB raster (n*n), reused BufferedImage backing
    private int reqN;
    private String reqKey;
    private int[] reqRGB; private int reqW, reqH;
    private float[] reqPole, reqAvec, reqBvec, reqL;
    private float reqSpin, reqCloudSpin, reqAmbient;
    private boolean reqEmissive, reqEarth, reqOceanGlint;
    private int[] reqNightRGB; private int reqNightW, reqNightH;
    private int[] reqCloudRGB; private int reqCloudW, reqCloudH;
    private float[] reqOcc; private int reqOccN; private float reqSunAlpha;
    private boolean requestOk;
    private ByteBuffer readBuf;   // reused readback buffer (MAX_SIZE^2 * 4 bytes)
    private IntBuffer readInts;   // int view of readBuf (packed ARGB)
    private int[] scratch;        // reused MAX_SIZE^2 int scratch for the row flip

    GLSphereRenderer() {
        try {
            GLProfile glp = GLProfile.get(GLProfile.GL2);
            GLCapabilities caps = new GLCapabilities(glp);
            caps.setOnscreen(false);
            caps.setFBO(true);
            caps.setDoubleBuffered(false);
            caps.setAlphaBits(8);          // need a real alpha channel for the readback
            caps.setDepthBits(0);
            GLDrawableFactory factory = GLDrawableFactory.getFactory(glp);
            drawable = factory.createOffscreenAutoDrawable(null, caps, null, MAX_SIZE, MAX_SIZE);
            drawable.addGLEventListener(this);
            drawable.display();            // realizes the context + runs init()
        } catch (Throwable t) {
            initFailed = true;
            lastError = t.toString();
            available = false;
        }
    }

    boolean isAvailable() { return available && !initFailed; }
    String lastError() { return lastError; }

    /** Release the GL context and all cached textures. */
    void dispose() {
        if (drawable != null) {
            try { drawable.destroy(); } catch (Throwable ignored) { }
            drawable = null;
        }
        available = false;
    }

    // --- GLEventListener -------------------------------------------------------------------

    @Override public void init(GLAutoDrawable d) {
        GL2 gl = d.getGL().getGL2();
        try {
            program = buildProgram(gl, VERT_SRC, FRAG_SRC);
            gl.glUseProgram(program);
            uPole = loc(gl, "uPole"); uA = loc(gl, "uA"); uB = loc(gl, "uB"); uL = loc(gl, "uL");
            uSpin = loc(gl, "uSpin"); uCloudSpin = loc(gl, "uCloudSpin"); uAmbient = loc(gl, "uAmbient");
            uEmissive = loc(gl, "uEmissive"); uEarth = loc(gl, "uEarth");
            uHasNight = loc(gl, "uHasNight"); uHasClouds = loc(gl, "uHasClouds");
            uOceanGlint = loc(gl, "uOceanGlint"); uHasNormal = loc(gl, "uHasNormal"); uHasSpec = loc(gl, "uHasSpec");
            uOccN = loc(gl, "uOccN"); uOcc = loc(gl, "uOcc"); uSunAlpha = loc(gl, "uSunAlpha");
            uDiffuse = loc(gl, "uDiffuse"); uNight = loc(gl, "uNight"); uClouds = loc(gl, "uClouds");
            uNormal = loc(gl, "uNormal"); uSpec = loc(gl, "uSpec");
            // Fixed sampler->unit bindings.
            gl.glUniform1i(uDiffuse, 0);
            gl.glUniform1i(uNight, 1);
            gl.glUniform1i(uClouds, 2);
            gl.glUniform1i(uNormal, 3);
            gl.glUniform1i(uSpec, 4);
            gl.glUseProgram(0);
            gl.glDisable(GL.GL_DEPTH_TEST);
            gl.glDisable(GL.GL_BLEND);
            gl.glDisable(GL.GL_CULL_FACE);
            readBuf = Buffers.newDirectByteBuffer(MAX_SIZE * MAX_SIZE * 4);
            readInts = readBuf.asIntBuffer();
            scratch = new int[MAX_SIZE * MAX_SIZE];
            available = true;
        } catch (Throwable t) {
            initFailed = true;
            lastError = t.toString();
            available = false;
        }
    }

    @Override public void dispose(GLAutoDrawable d) {
        GL2 gl = d.getGL().getGL2();
        if (program != 0) { try { gl.glDeleteProgram(program); } catch (Throwable ignored) {} program = 0; }
        for (int id : texIds.values()) {
            try { gl.glDeleteTextures(1, new int[]{id}, 0); } catch (Throwable ignored) { }
        }
        texIds.clear();
    }

    @Override public void reshape(GLAutoDrawable d, int x, int y, int w, int h) { }

    /** Consume the queued render request (see {@link #shade}). */
    @Override public void display(GLAutoDrawable d) {
        if (outPixels == null || program == 0) return;   // realization pass, no request queued
        GL2 gl = d.getGL().getGL2();
        int n = reqN;

        gl.glViewport(0, 0, n, n);
        gl.glClearColor(0f, 0f, 0f, 0f);
        gl.glClear(GL.GL_COLOR_BUFFER_BIT);
        gl.glUseProgram(program);

        // Diffuse (unit 0) — always present.
        bindTexture(gl, GL.GL_TEXTURE0, reqKey, reqRGB, reqW, reqH);
        // Optional relief/specular maps for this body (dormant until the files exist).
        int nrm = reqEmissive ? 0 : optionalTexture(gl, GL.GL_TEXTURE3, reqKey + "_normal", reqKey + "_normal.jpg");
        int spc = reqEmissive ? 0 : optionalTexture(gl, GL.GL_TEXTURE4, reqKey + "_specular", reqKey + "_specular.jpg");

        int hasNight = 0, hasClouds = 0;
        if (reqEarth && reqNightRGB != null) { bindTexture(gl, GL.GL_TEXTURE1, "earth_night", reqNightRGB, reqNightW, reqNightH); hasNight = 1; }
        if (reqEarth && reqCloudRGB != null) { bindTexture(gl, GL.GL_TEXTURE2, "earth_clouds", reqCloudRGB, reqCloudW, reqCloudH); hasClouds = 1; }

        gl.glUniform3f(uPole, reqPole[0], reqPole[1], reqPole[2]);
        gl.glUniform3f(uA, reqAvec[0], reqAvec[1], reqAvec[2]);
        gl.glUniform3f(uB, reqBvec[0], reqBvec[1], reqBvec[2]);
        gl.glUniform3f(uL, reqL[0], reqL[1], reqL[2]);
        gl.glUniform1f(uSpin, reqSpin);
        gl.glUniform1f(uCloudSpin, reqCloudSpin);
        gl.glUniform1f(uAmbient, reqAmbient);
        gl.glUniform1i(uEmissive, reqEmissive ? 1 : 0);
        gl.glUniform1i(uEarth, reqEarth ? 1 : 0);
        gl.glUniform1i(uHasNight, hasNight);
        gl.glUniform1i(uHasClouds, hasClouds);
        gl.glUniform1i(uOceanGlint, reqOceanGlint ? 1 : 0);
        gl.glUniform1i(uHasNormal, nrm);
        gl.glUniform1i(uHasSpec, spc);
        gl.glUniform1i(uOccN, reqOccN);
        gl.glUniform1f(uSunAlpha, reqSunAlpha);
        if (reqOccN > 0) gl.glUniform4fv(uOcc, reqOccN, reqOcc, 0);

        gl.glBegin(GL2.GL_QUADS);
        gl.glVertex2f(-1f, -1f);
        gl.glVertex2f( 1f, -1f);
        gl.glVertex2f( 1f,  1f);
        gl.glVertex2f(-1f,  1f);
        gl.glEnd();
        gl.glUseProgram(0);

        // Read back the n x n frame directly as packed ARGB ints: BGRA + UNSIGNED_INT_8_8_8_8_REV
        // yields 0xAARRGGBB on little-endian, i.e. Java's TYPE_INT_ARGB layout, with no per-channel
        // unpacking. GL is bottom-up, so copy rows in reverse into the destination (one arraycopy
        // per row rather than four bounds-checked byte reads per pixel — the hot cost).
        readBuf.clear();
        gl.glPixelStorei(GL.GL_PACK_ALIGNMENT, 1);
        gl.glReadPixels(0, 0, n, n, GL2.GL_BGRA, GL2.GL_UNSIGNED_INT_8_8_8_8_REV, readBuf);
        readInts.clear();
        readInts.get(scratch, 0, n * n);        // bulk copy the packed ints out of the direct buffer
        int[] out = outPixels;
        for (int iy = 0; iy < n; iy++) {
            System.arraycopy(scratch, (n - 1 - iy) * n, out, iy * n, n);   // flip vertical
        }
        requestOk = true;
    }

    /**
     * Shade one sphere on the GPU, filling {@code out} (an {@code n*n} ARGB raster, the
     * reused BufferedImage backing) exactly as the CPU path would. Returns false if the
     * GPU path is unavailable, in which case the caller falls back to CPU shading.
     *
     * @param pole/avec/bvec view-space body frame (from {@code bodyFrame()})
     * @param lightDir       unit Sun direction in the view frame (from {@code lightDirView()})
     * @param occ            flat {x,y,z,radius}*occN eclipse occluders (receiver-radius units), or null
     */
    boolean shade(int[] out, int n,
                  String key, int[] rgb, int texW, int texH,
                  double[] pole, double[] avec, double[] bvec, double[] lightDir,
                  double spin, double cloudSpin, double ambient, boolean emissive,
                  boolean earth,
                  int[] nightRGB, int nightW, int nightH,
                  int[] cloudRGB, int cloudW, int cloudH,
                  boolean oceanGlint,
                  double[] occ, int occN, double sunAlpha) {
        if (!isAvailable() || out == null || n < 2 || n > MAX_SIZE || rgb == null) return false;
        this.outPixels = out;
        this.reqN = n;
        this.reqKey = key;
        this.reqRGB = rgb; this.reqW = texW; this.reqH = texH;
        this.reqPole = f3(pole); this.reqAvec = f3(avec); this.reqBvec = f3(bvec); this.reqL = f3(lightDir);
        this.reqSpin = (float) spin; this.reqCloudSpin = (float) cloudSpin; this.reqAmbient = (float) ambient;
        this.reqEmissive = emissive; this.reqEarth = earth; this.reqOceanGlint = oceanGlint;
        this.reqNightRGB = nightRGB; this.reqNightW = nightW; this.reqNightH = nightH;
        this.reqCloudRGB = cloudRGB; this.reqCloudW = cloudW; this.reqCloudH = cloudH;
        this.reqSunAlpha = (float) sunAlpha;
        int cappedOccN = Math.min(occN, 8);
        this.reqOccN = cappedOccN;
        if (cappedOccN > 0) {
            this.reqOcc = new float[cappedOccN * 4];
            for (int k = 0; k < cappedOccN * 4; k++) this.reqOcc[k] = (float) occ[k];
        }
        requestOk = false;
        try {
            drawable.display();
        } catch (Throwable t) {
            lastError = t.toString();
            available = false;
            this.outPixels = null;
            return false;
        }
        this.outPixels = null;   // release the reference; request done
        return requestOk;
    }

    // --- helpers ---------------------------------------------------------------------------

    private static float[] f3(double[] v) { return new float[]{(float) v[0], (float) v[1], (float) v[2]}; }

    private int loc(GL2 gl, String name) { return gl.glGetUniformLocation(program, name); }

    /** Bind (uploading + caching on first use) an ARGB texture to {@code texUnit}. */
    private void bindTexture(GL2 gl, int texUnit, String cacheKey, int[] argb, int w, int h) {
        gl.glActiveTexture(texUnit);
        Integer id = texIds.get(cacheKey);
        if (id == null) {
            int[] ids = new int[1];
            gl.glGenTextures(1, ids, 0);
            id = ids[0];
            gl.glBindTexture(GL.GL_TEXTURE_2D, id);
            ByteBuffer buf = argbToRGBA(argb, w, h);
            gl.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA, w, h, 0, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, buf);
            gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR);
            gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR);
            gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_REPEAT);          // longitude wraps
            gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE);   // poles clamp
            texIds.put(cacheKey, id);
        } else {
            gl.glBindTexture(GL.GL_TEXTURE_2D, id);
        }
    }

    /**
     * Bind an optional {@code assets/textures/<file>} map (normal/specular) to {@code texUnit}
     * if it exists on disk, returning 1 when bound (so the shader enables the feature) else 0.
     * Failures are remembered so the file is probed only once.
     */
    private int optionalTexture(GL2 gl, int texUnit, String cacheKey, String fileName) {
        if (texIds.containsKey(cacheKey)) {
            gl.glActiveTexture(texUnit);
            gl.glBindTexture(GL.GL_TEXTURE_2D, texIds.get(cacheKey));
            return 1;
        }
        if (Boolean.TRUE.equals(optionalTried.get(cacheKey))) return 0;
        optionalTried.put(cacheKey, Boolean.TRUE);
        java.io.File f = new java.io.File("assets/textures/" + fileName);
        if (!f.isFile()) return 0;
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(f);
            if (img == null) return 0;
            int w = img.getWidth(), h = img.getHeight();
            int[] px = img.getRGB(0, 0, w, h, null, 0, w);
            bindTexture(gl, texUnit, cacheKey, px, w, h);
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    /** ARGB int[] (row 0 = image top) -> tightly packed RGBA bytes for glTexImage2D. */
    private static ByteBuffer argbToRGBA(int[] argb, int w, int h) {
        ByteBuffer buf = Buffers.newDirectByteBuffer(w * h * 4);
        for (int i = 0; i < w * h; i++) {
            int p = argb[i];
            buf.put((byte) ((p >> 16) & 0xFF));
            buf.put((byte) ((p >> 8) & 0xFF));
            buf.put((byte) (p & 0xFF));
            buf.put((byte) 0xFF);           // diffuse/night/cloud maps are opaque
        }
        buf.flip();
        return buf;
    }

    private static int buildProgram(GL2 gl, String vs, String fs) {
        int v = compile(gl, GL2.GL_VERTEX_SHADER, vs);
        int f = compile(gl, GL2.GL_FRAGMENT_SHADER, fs);
        int p = gl.glCreateProgram();
        gl.glAttachShader(p, v);
        gl.glAttachShader(p, f);
        gl.glLinkProgram(p);
        int[] ok = new int[1];
        gl.glGetProgramiv(p, GL2.GL_LINK_STATUS, ok, 0);
        if (ok[0] == GL.GL_FALSE) {
            int[] len = new int[1];
            gl.glGetProgramiv(p, GL2.GL_INFO_LOG_LENGTH, len, 0);
            byte[] log = new byte[Math.max(1, len[0])];
            gl.glGetProgramInfoLog(p, log.length, len, 0, log, 0);
            throw new RuntimeException("Shader link failed: " + new String(log, 0, Math.max(0, len[0])));
        }
        gl.glDeleteShader(v);
        gl.glDeleteShader(f);
        return p;
    }

    private static int compile(GL2 gl, int type, String src) {
        int s = gl.glCreateShader(type);
        gl.glShaderSource(s, 1, new String[]{src}, null);
        gl.glCompileShader(s);
        int[] ok = new int[1];
        gl.glGetShaderiv(s, GL2.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == GL.GL_FALSE) {
            int[] len = new int[1];
            gl.glGetShaderiv(s, GL2.GL_INFO_LOG_LENGTH, len, 0);
            byte[] log = new byte[Math.max(1, len[0])];
            gl.glGetShaderInfoLog(s, log.length, len, 0, log, 0);
            throw new RuntimeException("Shader compile failed: " + new String(log, 0, Math.max(0, len[0])));
        }
        return s;
    }

    // --- shaders (GLSL 1.20, matches the project's GL2 profile) ----------------------------

    private static final String VERT_SRC =
        "#version 120\n" +
        "varying vec2 vP;\n" +
        "void main(){ vP = gl_Vertex.xy; gl_Position = vec4(gl_Vertex.xy, 0.0, 1.0); }\n";

    private static final String FRAG_SRC =
        "#version 120\n" +
        "const float PI = 3.14159265358979;\n" +
        "varying vec2 vP;\n" +
        "uniform sampler2D uDiffuse, uNight, uClouds, uNormal, uSpec;\n" +
        "uniform vec3 uPole, uA, uB, uL;\n" +
        "uniform float uSpin, uCloudSpin, uAmbient;\n" +
        "uniform int uEmissive, uEarth, uHasNight, uHasClouds, uOceanGlint, uHasNormal, uHasSpec, uOccN;\n" +
        "uniform vec4 uOcc[8];\n" +
        "uniform float uSunAlpha;\n" +   // Sun's angular radius (rad) as seen from the receiver
        // Mirror of SolarSystemSimulation.eclipseFactor: the Sun is an extended disc, so the
        // shadow is 1 - (fraction of the Sun's disc the occluder covers). This makes the umbra
        // shrink to a thin track (total), leave a bright ring (annular), or dim softly (partial).
        "float eclipse(vec3 N){\n" +
        "  float f = 1.0;\n" +
        "  float rs = max(uSunAlpha, 1e-9);\n" +
        "  float rs2 = rs*rs;\n" +
        "  for (int k=0;k<8;k++){\n" +
        "    if (k >= uOccN) break;\n" +
        "    vec3 o = uOcc[k].xyz; float orad = uOcc[k].w;\n" +
        "    vec3 w = o - N;\n" +
        "    float along = dot(w, uL);\n" +
        "    if (along <= 0.0) continue;\n" +
        "    float perp = sqrt(max(dot(w,w) - along*along, 0.0));\n" +
        "    float ro = orad / along;\n" +               // occluder angular radius
        "    float d  = perp / along;\n" +                // angular separation of disc centres
        "    float blocked;\n" +
        "    if (d >= rs + ro) { continue; }\n" +
        "    else if (d <= abs(rs - ro)) {\n" +
        "      float rmin = min(rs, ro); blocked = (rmin*rmin)/rs2;\n" +
        "    } else {\n" +
        "      float ro2 = ro*ro, d2 = d*d;\n" +
        "      float a1 = acos(clamp((d2 + rs2 - ro2)/(2.0*d*rs), -1.0, 1.0));\n" +
        "      float a2 = acos(clamp((d2 + ro2 - rs2)/(2.0*d*ro), -1.0, 1.0));\n" +
        "      float tri = 0.5*sqrt(max(0.0, (-d+rs+ro)*(d+rs-ro)*(d-rs+ro)*(d+rs+ro)));\n" +
        "      blocked = (rs2*a1 + ro2*a2 - tri)/(PI*rs2);\n" +
        "    }\n" +
        "    f *= 1.0 - clamp(blocked, 0.0, 1.0);\n" +
        "  }\n" +
        "  return f;\n" +
        "}\n" +
        "float smoothstep2(float e0, float e1, float x){\n" +
        "  float t = clamp((x-e0)/(e1-e0), 0.0, 1.0); return t*t*(3.0-2.0*t);\n" +
        "}\n" +
        "void main(){\n" +
        "  float rr = dot(vP, vP);\n" +
        "  if (rr > 1.0) discard;\n" +
        "  float nz = sqrt(max(0.0, 1.0 - rr));\n" +
        "  vec3 N = vec3(vP.x, vP.y, nz);\n" +
        "  float lat = asin(clamp(dot(N, uPole), -1.0, 1.0));\n" +
        "  float lon = atan(dot(N, uB), dot(N, uA));\n" +
        "  float u = fract(-(lon + uSpin) / (2.0*PI));\n" +   // negated: correct east-west (un-mirror)
        "  float v = 0.5 - lat/PI;\n" +
        "  vec3 base = texture2D(uDiffuse, vec2(u, v)).rgb;\n" +
        "  if (uEmissive == 1){ gl_FragColor = vec4(base, 1.0); return; }\n" +
        // Optional normal map for real surface relief (dormant unless a *_normal map exists).
        "  vec3 Nsh = N;\n" +
        "  if (uHasNormal == 1){\n" +
        "    vec3 east = normalize(cross(uPole, N));\n" +
        "    vec3 north = cross(N, east);\n" +
        "    vec3 nm = texture2D(uNormal, vec2(u, v)).rgb * 2.0 - 1.0;\n" +
        "    Nsh = normalize(east*nm.x + north*nm.y + N*nm.z);\n" +
        "  }\n" +
        "  float d = max(0.0, dot(Nsh, uL));\n" +
        "  float shade = uAmbient + (1.0 - uAmbient)*d;\n" +
        // Mirror of the CPU cast-shadow logic: the ray-traced shadow darkens the whole surface
        // (direct sun and ambient alike), so it reads as one complete disc across the terminator;
        // city lights are added afterwards so they shine through the shadowed ground.
        "  float ecl = 1.0;\n" +
        // Perceptual darkening: keep the wide penumbra bright, concentrate the visible shadow
        // into the umbra (mirror of SolarSystemSimulation.perceptualEclipse).
        "  if (uOccN > 0) { float o = 1.0 - eclipse(N); float o2=o*o; ecl = 1.0 - o2*o2*o2; shade *= ecl; }\n" +
        "  if (uEarth == 0){\n" +
        "    vec3 col = base*shade;\n" +
        "    if (uHasSpec == 1 && d > 0.0){\n" +
        "      vec3 H = normalize(uL + vec3(0.0,0.0,1.0));\n" +
        "      float sp = pow(max(0.0, dot(Nsh, H)), 40.0) * texture2D(uSpec, vec2(u, v)).r;\n" +
        "      col += vec3(sp);\n" +
        "    }\n" +
        "    gl_FragColor = vec4(min(col, vec3(1.0)), 1.0); return;\n" +
        "  }\n" +
        // --- Earth: clouds over surface, then shade, then ocean glint + night lights ---
        "  vec3 s = base;\n" +
        "  float cloud = 0.0;\n" +
        "  if (uHasClouds == 1){\n" +
        "    float cu = fract(-(lon + uCloudSpin) / (2.0*PI));\n" +   // match surface (un-mirror)
        "    vec3 cp = texture2D(uClouds, vec2(cu, v)).rgb;\n" +
        "    cloud = (cp.r + cp.g + cp.b) / 3.0;\n" +
        "    cloud = clamp((cloud - 0.12) / 0.88, 0.0, 1.0);\n" +
        "    s += (1.0 - s)*cloud;\n" +
        "  }\n" +
        "  vec3 col = s*shade;\n" +
        "  if (uOceanGlint == 1 && d > 0.0 && cloud < 0.45 && s.b > s.r + 0.03137 && s.b > s.g + 0.01568){\n" +
        "    vec3 H = normalize(uL + vec3(0.0,0.0,1.0));\n" +
        "    float ndoth = dot(N, H);\n" +
        "    if (ndoth > 0.5){\n" +
        "      float x = ndoth*ndoth; x*=x; x*=x; x*=x; x*=x; x*=x;\n" +   // ndoth^64
        "      float sp = x * 0.9 * (1.0 - cloud);\n" +
        "      col += vec3(sp, sp, sp*0.85);\n" +
        "    }\n" +
        "  }\n" +
        "  if (uHasNight == 1){\n" +
        "    float nightW = 1.0 - smoothstep2(0.02, 0.22, d);\n" +
        "    if (nightW > 0.001){\n" +
        "      nightW *= (1.0 - cloud*0.7);\n" +
        "      vec3 np = texture2D(uNight, vec2(u, v)).rgb;\n" +
        "      col += np * (1.7 * nightW);\n" +
        "    }\n" +
        "  }\n" +
        "  gl_FragColor = vec4(min(col, vec3(1.0)), 1.0);\n" +
        "}\n";
}
