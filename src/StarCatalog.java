import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * The real sky behind the Solar System: the Yale Bright Star Catalogue (BSC5, 9096 stars to
 * V ≈ 6.5 — every star visible to the naked eye), the IAU constellation figures and their
 * names, and a Milky Way brightness map. Loaded once from {@code assets/sky/}.
 *
 * <p>Positions are J2000 (ICRF) right ascension / declination; {@link #directionAt} applies
 * each star's proper motion for any epoch (Barnard-fast stars like Arcturus move ~2″/yr).
 * Directions are unit vectors in the <b>ecliptic J2000</b> frame the rest of the sim uses.
 *
 * <p>Sources: BSC5 — Hoffleit &amp; Warren (1991), CDS catalogue V/50. Constellation lines,
 * label positions and the Milky Way outline — d3-celestial, © 2015 Olaf Frohn, BSD-3-Clause
 * (see {@code assets/sky/LICENSE-d3-celestial.txt}); the Milky Way contours were rasterised
 * into {@code milkyway.png}, an equirectangular RA/Dec brightness map.
 */
final class StarCatalog {

    /** Number of stars. */
    final int n;
    /** J2000 RA / Dec (radians), V magnitude, B−V colour, proper motion (radians/yr, μα·cosδ and μδ). */
    final double[] ra, dec, vmag, bv, pmRa, pmDec;
    /** HR number, IAU proper name ("" if none) and Bayer/Flamsteed designation ("" if none). */
    final int[] hr;
    final String[] properName, designation;
    /** Packed RGB tint of each star from its B−V (see {@link #bvToRgb}). */
    final int[] rgb;

    /** A constellation stick figure: one polyline of ecliptic-J2000 unit vectors. */
    record Figure(String abbr, double[][] points) {}
    /** A constellation label anchor. */
    record Label(String abbr, String name, double[] dir) {}
    final List<Figure> figures = new ArrayList<>();
    final List<Label> labels = new ArrayList<>();

    /** Milky Way brightness (0..1) on an equirectangular RA/Dec grid; null if the map is missing. */
    private float[] mw;
    private int mwW, mwH;

    private static StarCatalog shared;
    private static boolean loadFailed;

    /** The catalogue, loaded on first use; null if {@code assets/sky} can't be read. */
    static synchronized StarCatalog get() {
        if (shared == null && !loadFailed) {
            try {
                shared = new StarCatalog(Assets.path("sky").toFile());
            } catch (Exception e) {
                loadFailed = true;
                System.err.println("Star catalogue unavailable: " + e);
            }
        }
        return shared;
    }

    private StarCatalog(File dir) throws Exception {
        List<String> lines = Files.readAllLines(new File(dir, "bsc5.csv").toPath(), StandardCharsets.UTF_8);
        lines.removeIf(l -> l.startsWith("#") || l.isBlank());
        n = lines.size();
        ra = new double[n]; dec = new double[n]; vmag = new double[n]; bv = new double[n];
        pmRa = new double[n]; pmDec = new double[n]; hr = new int[n]; rgb = new int[n];
        properName = new String[n]; designation = new String[n];
        double arcsec = Math.PI / (180.0 * 3600.0);
        for (int i = 0; i < n; i++) {
            String[] f = lines.get(i).split(",", -1);
            hr[i] = Integer.parseInt(f[0]);
            ra[i] = Math.toRadians(Double.parseDouble(f[1]));
            dec[i] = Math.toRadians(Double.parseDouble(f[2]));
            vmag[i] = Double.parseDouble(f[3]);
            bv[i] = Double.parseDouble(f[4]);
            pmRa[i] = Double.parseDouble(f[5]) * arcsec;
            pmDec[i] = Double.parseDouble(f[6]) * arcsec;
            properName[i] = f[7];
            designation[i] = f[8];
            rgb[i] = bvToRgb(bv[i]);
        }
        for (String l : Files.readAllLines(new File(dir, "constellation_lines.txt").toPath(), StandardCharsets.UTF_8)) {
            if (l.startsWith("#") || l.isBlank()) continue;
            String[] f = l.trim().split("\\s+");
            double[][] pts = new double[f.length - 1][];
            for (int k = 1; k < f.length; k++) {
                String[] c = f[k].split(",");
                pts[k - 1] = radecDir(Math.toRadians(Double.parseDouble(c[0])), Math.toRadians(Double.parseDouble(c[1])));
            }
            figures.add(new Figure(f[0], pts));
        }
        for (String l : Files.readAllLines(new File(dir, "constellation_names.txt").toPath(), StandardCharsets.UTF_8)) {
            if (l.startsWith("#") || l.isBlank()) continue;
            String[] f = l.split(",", 4);
            labels.add(new Label(f[0], f[3],
                    radecDir(Math.toRadians(Double.parseDouble(f[1])), Math.toRadians(Double.parseDouble(f[2])))));
        }
        File mwFile = new File(dir, "milkyway.png");
        if (mwFile.isFile()) {
            BufferedImage img = ImageIO.read(mwFile);
            mwW = img.getWidth(); mwH = img.getHeight();
            mw = new float[mwW * mwH];
            for (int y = 0; y < mwH; y++)
                for (int x = 0; x < mwW; x++)
                    mw[y * mwW + x] = (img.getRGB(x, y) & 0xFF) / 255f;
        }
    }

    /** Ecliptic-J2000 unit vector of an ICRF RA / Dec (radians). */
    static double[] radecDir(double ra, double dec) {
        double[] q = Astro.radec(ra, dec);
        return Astro.eqToEcl(q[0], q[1], q[2]);
    }

    /** Ecliptic-J2000 unit vector of star {@code i} at {@code years} after J2000 (proper motion applied). */
    double[] directionAt(int i, double years) {
        double d = dec[i] + pmDec[i] * years;
        double r = ra[i] + pmRa[i] * years / Math.max(1e-6, Math.cos(dec[i]));
        return radecDir(r, d);
    }

    /**
     * Milky Way surface brightness (0..1, bilinear) toward an <b>equatorial J2000</b> unit
     * vector; 0 if the map wasn't loaded.
     */
    float milkyWay(double x, double y, double z) {
        if (mw == null) return 0f;
        double raDeg = Math.toDegrees(Math.atan2(y, x));               // −180..180, the map's longitude
        double decDeg = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, z))));
        double u = (raDeg + 180.0) / 360.0 * mwW - 0.5, v = (90.0 - decDeg) / 180.0 * mwH - 0.5;
        int x0 = (int) Math.floor(u), y0 = (int) Math.floor(v);
        double fx = u - x0, fy = v - y0;
        float a = mwAt(x0, y0), b = mwAt(x0 + 1, y0), c = mwAt(x0, y0 + 1), d = mwAt(x0 + 1, y0 + 1);
        return (float) ((a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy);
    }

    private float mwAt(int x, int y) {
        x = Math.floorMod(x, mwW);
        y = Math.max(0, Math.min(mwH - 1, y));
        return mw[y * mwW + x];
    }

    /** The name to show for star {@code i}: its proper name, else its designation. */
    String displayName(int i) {
        return !properName[i].isEmpty() ? properName[i] : designation[i];
    }

    /**
     * Packed-RGB tint of a star from its B–V colour index: B–V → effective temperature
     * (Ballesteros 2012) → blackbody RGB (Tanner Helland's fit), normalised so the brightest
     * channel is full.
     */
    static int bvToRgb(double bv) {
        double temp = 4600.0 * (1.0 / (0.92 * bv + 1.7) + 1.0 / (0.92 * bv + 0.62));
        double k = temp / 100.0;
        double r = k <= 66 ? 255 : 329.698727446 * Math.pow(k - 60, -0.1332047592);
        double g = k <= 66 ? 99.4708025861 * Math.log(k) - 161.1195681661
                           : 288.1221695283 * Math.pow(k - 60, -0.0755148492);
        double b = k >= 66 ? 255 : k <= 19 ? 0 : 138.5177312231 * Math.log(k - 10) - 305.0447927307;
        double rr = Math.max(0, Math.min(255, r)), gg = Math.max(0, Math.min(255, g)), bb = Math.max(0, Math.min(255, b));
        double mx = Math.max(rr, Math.max(gg, bb));
        if (mx > 0) { rr = rr * 255 / mx; gg = gg * 255 / mx; bb = bb * 255 / mx; }
        return ((int) rr << 16) | ((int) gg << 8) | (int) bb;
    }

    /**
     * How a point source of magnitude {@code m} renders when stars down to {@code limit} are
     * just visible: {radius px, alpha 0..1}. Brightness follows flux ∝ 10^(−0.4 m); bright
     * sources grow (as the eye's/camera's point spread does) instead of saturating.
     */
    static double[] pointSprite(double m, double limit) {
        double rel = Math.pow(10, -0.4 * (m - limit));      // flux relative to the faintest visible
        if (rel <= 1) return new double[]{ 0.6, 0.3 * Math.max(0, rel) };
        double r = 0.6 + 0.55 * Math.log10(rel) + 0.06 * Math.pow(rel, 0.35);
        double alpha = Math.min(1.0, 0.3 + 0.45 * Math.log10(rel));
        return new double[]{ Math.min(r, 9), alpha };
    }
}
