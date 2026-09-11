package astrosim;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.util.texture.Texture;
import com.jogamp.opengl.util.texture.TextureIO;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Lazy GL texture cache for planet surface maps.
 *
 * Asset convention (see assets/textures/README.md):
 *   assets/textures/&lt;key&gt;_diffuse.{png,jpg,jpeg}   — equirectangular color map (used now)
 *   assets/textures/&lt;key&gt;_normal.*                — reserved hook (programmable pipeline)
 *   assets/textures/&lt;key&gt;_specular.*              — reserved hook (programmable pipeline)
 *
 * where &lt;key&gt; is the lowercase body name ("earth", "mars", "jupiter", ...,
 * plus "sun" and "moon"). Bodies without a diffuse map fall back to their flat
 * data-table color, so the app runs with an empty assets directory.
 *
 * Must be called from the GL thread (Scene.display).
 */
public final class Textures {
    private Textures() {}

    public static final String DIR = "assets/textures";
    private static final String[] EXT = {".png", ".jpg", ".jpeg"};

    // GL_EXT_texture_filter_anisotropic enum values (not in the core GL2 constants).
    private static final int GL_TEXTURE_MAX_ANISOTROPY_EXT = 0x84FE;
    private static final int GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT = 0x84FF;

    private static final Map<String, Texture> cache = new HashMap<>();
    private static final Set<String> missing = new HashSet<>();

    /** Diffuse texture for a body key, or null if no map file exists. */
    public static Texture diffuse(GL2 gl, String key) {
        return map(gl, key, "diffuse");
    }

    /**
     * Texture map of a given {@code kind} ("diffuse", "night", "clouds",
     * "specular") for a body key, or null if that file doesn't exist. Bodies
     * without a given map simply skip that effect, so extra maps are drop-in.
     */
    public static Texture map(GL2 gl, String key, String kind) {
        String ck = key + "_" + kind;
        Texture t = cache.get(ck);
        if (t != null) return t;
        if (missing.contains(ck)) return null;
        File f = findMap(key, kind);
        if (f == null) { missing.add(ck); return null; }
        try {
            t = TextureIO.newTexture(f, true);
            applyFilters(gl, t);
            cache.put(ck, t);
            System.out.println("Loaded texture: " + f.getPath());
            return t;
        } catch (Exception ex) {
            System.err.println("Texture load failed for " + f.getPath() + ": " + ex);
            missing.add(ck);
            return null;
        }
    }

    /**
     * Load a texture by exact file name under {@link #DIR} (cached by name), for
     * assets that don't follow the {@code <body>_diffuse} convention — e.g. the
     * Saturn ring strip. Returns null if the file is absent.
     */
    public static Texture named(GL2 gl, String fileName) {
        Texture t = cache.get(fileName);
        if (t != null) return t;
        if (missing.contains(fileName)) return null;
        File f = new File(DIR, fileName);
        if (!f.isFile()) { missing.add(fileName); return null; }
        try {
            t = TextureIO.newTexture(f, true);
            applyFilters(gl, t);
            cache.put(fileName, t);
            System.out.println("Loaded texture: " + f.getPath());
            return t;
        } catch (Exception ex) {
            System.err.println("Texture load failed for " + f.getPath() + ": " + ex);
            missing.add(fileName);
            return null;
        }
    }

    /** Trilinear mipmapping + max anisotropic filtering for crisp grazing angles. */
    private static void applyFilters(GL2 gl, Texture t) {
        t.setTexParameteri(gl, GL2.GL_TEXTURE_MIN_FILTER, GL2.GL_LINEAR_MIPMAP_LINEAR);
        t.setTexParameteri(gl, GL2.GL_TEXTURE_MAG_FILTER, GL2.GL_LINEAR);
        if (gl.isExtensionAvailable("GL_EXT_texture_filter_anisotropic")) {
            float[] max = new float[1];
            gl.glGetFloatv(GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT, max, 0);
            t.setTexParameterf(gl, GL_TEXTURE_MAX_ANISOTROPY_EXT, max[0]);
        }
    }

    /**
     * Locate a map file for (key, kind) where kind is "diffuse", "normal" or
     * "specular". Normal/specular are not consumed by the current
     * fixed-function renderer but the lookup is the stable hook for the
     * shader-based pipeline.
     */
    public static File findMap(String key, String kind) {
        for (String ext : EXT) {
            File f = new File(DIR, key + "_" + kind + ext);
            if (f.isFile()) return f;
        }
        return null;
    }

    /** Drop GL handles (call on GL dispose). */
    public static void disposeAll(GL2 gl) {
        for (Texture t : cache.values()) t.destroy(gl);
        cache.clear();
        missing.clear();
    }
}
