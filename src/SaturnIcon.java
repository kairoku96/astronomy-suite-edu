import java.awt.image.BufferedImage;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * The app icon: Saturn ray-traced from the sim's own data. The globe is the oblate spheroid
 * (flattening 0.098) wrapped in {@code assets/textures/saturn_diffuse.jpg}; the rings are the
 * radial profile in {@code saturn_ring.png} over 1.11–2.27 equatorial radii, lit with the same
 * slab scattering the sim uses ({@link SolarSystemSimulation#ringTau},
 * {@link SolarSystemSimulation#ringReflect}). The rings shadow the globe (sunlight crosses the
 * slab as e^(−τ/μ0)) and the globe shadows the rings. Lighting is done in linear light.
 * Written by {@code -Dsss.icon=<png>}; the result is committed as {@code assets/icon/orrerium.png}.
 */
final class SaturnIcon {
    static final double FLATTENING = 0.09796;          // (R_eq − R_pol) / R_eq
    static final double RING_INNER = 1.11, RING_OUTER = 2.27;
    // The view: from 24° above the ring plane (Earth sees at most 26.7°), the Sun 30° up on the
    // same face and 40° to the left of the viewer, so the terminator and the globe's shadow
    // across the rings both show. Rolled so the rings rise to the right.
    static final double VIEW_ELEV = 24, SUN_ELEV = 30, SUN_AZ = 40, ROLL = -20;
    static final double MINNAERT_K = 0.9;              // gas-giant limb darkening
    static final double EXPOSURE = 1.3;

    private static final double C = 1 - FLATTENING;
    private static final double[] SRGB_TO_LIN = new double[256];
    static {
        for (int i = 0; i < 256; i++) {
            double c = i / 255.0;
            SRGB_TO_LIN[i] = c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
        }
    }

    private static int toSrgb(double lin) {
        lin = Math.max(0, Math.min(1, lin));
        double c = lin <= 0.0031308 ? 12.92 * lin : 1.055 * Math.pow(lin, 1 / 2.4) - 0.055;
        return (int) Math.round(c * 255);
    }

    private final BufferedImage tex;
    private final int[] ring;
    private final double[] V, L, right, up;
    private final double refShade = 0.9 / SolarSystemSimulation.ringReflect(0.5, 0.5, 0.5, true);
    /**
     * The ring map's colours are much darker than the rings are: the B ring's albedo is about
     * the globe's, so scale the ring colours (in linear light) to bring the brightest dense
     * ringlet up to the globe's mean brightness.
     */
    private final double ringGain;

    private SaturnIcon(BufferedImage tex, int[] ring) {
        this.tex = tex;
        this.ring = ring;
        double globe = 0;
        int samples = 0;
        for (int y = 0; y < tex.getHeight(); y += 8) for (int x = 0; x < tex.getWidth(); x += 8) {
            globe += luminance(tex.getRGB(x, y)); samples++;
        }
        double peak = 1e-6;
        for (int c : ring) if ((c >>> 24) > 200) peak = Math.max(peak, luminance(c));
        ringGain = globe / samples / peak;
        double b = Math.toRadians(VIEW_ELEV), b0 = Math.toRadians(SUN_ELEV), az = Math.toRadians(SUN_AZ);
        V = new double[]{0, -Math.cos(b), Math.sin(b)};                    // planet -> viewer (z = pole)
        L = new double[]{-Math.sin(az) * Math.cos(b0), -Math.cos(az) * Math.cos(b0), Math.sin(b0)};
        double[] u = norm(new double[]{-V[2] * V[0], -V[2] * V[1], 1 - V[2] * V[2]});   // pole, made ⊥ V
        double[] r = cross(u, V);
        double rho = Math.toRadians(ROLL), cr = Math.cos(rho), sr = Math.sin(rho);
        right = new double[3]; up = new double[3];
        for (int i = 0; i < 3; i++) { right[i] = cr * r[i] + sr * u[i]; up[i] = -sr * r[i] + cr * u[i]; }
    }

    /**
     * A {@code size}² ARGB icon with Saturn (rings included) fitted inside the centred
     * {@code content}² square, anti-aliased with {@code ss}² samples per pixel.
     */
    static BufferedImage render(int size, int content, int ss) throws IOException {
        BufferedImage tex = ImageIO.read(Assets.file("textures/saturn_diffuse.jpg"));
        BufferedImage rim = ImageIO.read(Assets.file("textures/saturn_ring.png"));
        if (tex == null || rim == null) throw new IOException("Saturn textures missing from " + Assets.DIR);
        int w = rim.getWidth();
        SaturnIcon s = new SaturnIcon(tex, rim.getRGB(0, rim.getHeight() / 2, w, 1, null, 0, w));

        // Coarse pass: where on screen (in equatorial radii) is anything drawn?
        double minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9, half = RING_OUTER + 0.2;
        int n = 400;
        for (int iy = 0; iy < n; iy++) for (int ix = 0; ix < n; ix++) {
            double x = (ix + 0.5) / n * 2 * half - half, y = half - (iy + 0.5) / n * 2 * half;
            if (s.trace(x, y)[3] > 0.02) {
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            }
        }
        double scale = content / Math.max(maxX - minX, maxY - minY);    // pixels per radius
        double cx = (minX + maxX) / 2, cy = (minY + maxY) / 2;

        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int py = 0; py < size; py++) for (int px = 0; px < size; px++) {
            double r = 0, g = 0, bl = 0, a = 0;
            for (int j = 0; j < ss; j++) for (int i = 0; i < ss; i++) {
                double x = cx + (px + (i + 0.5) / ss - size / 2.0) / scale;
                double y = cy - (py + (j + 0.5) / ss - size / 2.0) / scale;
                double[] c = s.trace(x, y);
                r += c[0]; g += c[1]; bl += c[2]; a += c[3];
            }
            if (a <= 0) continue;
            int alpha = (int) Math.round(255 * a / (ss * ss));
            if (alpha == 0) continue;
            out.setRGB(px, py, alpha << 24 | toSrgb(r / a) << 16 | toSrgb(g / a) << 8 | toSrgb(bl / a));
        }
        return out;
    }

    /** Premultiplied linear {r, g, b, alpha} along the view ray through screen point (x, y). */
    private double[] trace(double x, double y) {
        double[] o = new double[3], d = {-V[0], -V[1], -V[2]};
        for (int i = 0; i < 3; i++) o[i] = x * right[i] + y * up[i] + 10 * V[i];
        double tP = hitGlobe(o, d);
        double tR = Math.abs(d[2]) > 1e-12 ? -o[2] / d[2] : Double.NaN;

        double[] planet = null;
        if (!Double.isNaN(tP)) planet = shadeGlobe(new double[]{o[0] + tP * d[0], o[1] + tP * d[1], o[2] + tP * d[2]});

        double ringA = 0, rr = 0, rg = 0, rb = 0;
        if (tR > 0 && (planet == null || tR < tP)) {
            double qx = o[0] + tR * d[0], qy = o[1] + tR * d[1];
            int lc = ringAt(Math.hypot(qx, qy));
            int a8 = lc >>> 24;
            if (a8 > 0) {
                double tau = SolarSystemSimulation.ringTau(a8);
                double mu = Math.abs(V[2]), mu0 = Math.abs(L[2]);
                ringA = 1 - Math.exp(-tau / mu);          // opacity grows as the rings turn edge-on
                double shade = refShade * SolarSystemSimulation.ringReflect(tau, mu0, mu, Math.signum(L[2]) == Math.signum(V[2]));
                if (!Double.isNaN(hitGlobe(new double[]{qx, qy, 0}, L))) shade = 0;   // in Saturn's shadow
                shade = Math.min(1.6, shade) * EXPOSURE * ringGain;
                rr = SRGB_TO_LIN[lc >> 16 & 0xFF] * shade;
                rg = SRGB_TO_LIN[lc >> 8 & 0xFF] * shade;
                rb = SRGB_TO_LIN[lc & 0xFF] * shade;
            }
        }
        if (planet == null) return new double[]{rr * ringA, rg * ringA, rb * ringA, ringA};
        double k = 1 - ringA;
        return new double[]{rr * ringA + planet[0] * k, rg * ringA + planet[1] * k, rb * ringA + planet[2] * k, 1};
    }

    /** Nearest positive distance along d from o to the spheroid x² + y² + (z/c)² = 1, or NaN. */
    private static double hitGlobe(double[] o, double[] d) {
        double oz = o[2] / C, dz = d[2] / C;
        double a = d[0] * d[0] + d[1] * d[1] + dz * dz;
        double b = o[0] * d[0] + o[1] * d[1] + oz * dz;
        double cc = o[0] * o[0] + o[1] * o[1] + oz * oz - 1;
        double disc = b * b - a * cc;
        if (disc < 0) return Double.NaN;
        double t = (-b - Math.sqrt(disc)) / a;
        if (t <= 1e-9) t = (-b + Math.sqrt(disc)) / a;
        return t > 1e-9 ? t : Double.NaN;
    }

    /** Linear RGB of the globe at surface point p: texture × Minnaert, dimmed by the rings' shadow. */
    private double[] shadeGlobe(double[] p) {
        double[] nrm = norm(new double[]{p[0], p[1], p[2] / (C * C)});
        double mu0 = dot(nrm, L), mu = Math.max(0.05, dot(nrm, V));
        if (mu0 <= 0) return new double[]{0, 0, 0};
        double shade = Math.pow(mu0, MINNAERT_K) * Math.pow(mu, MINNAERT_K - 1);
        if (Math.abs(L[2]) > 1e-9) {                     // the rings' shadow on the cloud tops
            double s = -p[2] / L[2];
            if (s > 0) {
                int a8 = ringAt(Math.hypot(p[0] + s * L[0], p[1] + s * L[1])) >>> 24;
                if (a8 > 0) shade *= Math.exp(-SolarSystemSimulation.ringTau(a8) / Math.abs(L[2]));
            }
        }
        double lat = Math.atan2(p[2], Math.hypot(p[0], p[1]));
        double lon = Math.atan2(p[1], p[0]);
        int tw = tex.getWidth(), th = tex.getHeight();
        int tx = (int) ((lon / (2 * Math.PI) + 0.5) * tw) % tw;
        int ty = Math.min(th - 1, Math.max(0, (int) ((0.5 - lat / Math.PI) * th)));
        int rgb = tex.getRGB(tx, ty);
        shade *= EXPOSURE;
        return new double[]{SRGB_TO_LIN[rgb >> 16 & 0xFF] * shade, SRGB_TO_LIN[rgb >> 8 & 0xFF] * shade,
                SRGB_TO_LIN[rgb & 0xFF] * shade};
    }

    /** Ring profile (ARGB) at radius r in equatorial radii; 0 outside the rings. */
    private int ringAt(double r) {
        if (r < RING_INNER || r > RING_OUTER) return 0;
        return ring[(int) ((r - RING_INNER) / (RING_OUTER - RING_INNER) * (ring.length - 1))];
    }

    private static double luminance(int rgb) {
        return 0.2126 * SRGB_TO_LIN[rgb >> 16 & 0xFF] + 0.7152 * SRGB_TO_LIN[rgb >> 8 & 0xFF] + 0.0722 * SRGB_TO_LIN[rgb & 0xFF];
    }

    private static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }
    private static double[] norm(double[] a) {
        double l = Math.sqrt(dot(a, a));
        return new double[]{a[0] / l, a[1] / l, a[2] / l};
    }
}
