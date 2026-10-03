import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live-check tests for the ray-traced cast shadow ({@link SolarSystemSimulation#eclipseFactor})
 * that draws eclipse umbrae and shadow transits. The function returns a Sun-visibility factor
 * (1 = full sun, 0 = deep umbra) for a surface point at unit normal {@code (nx,ny,nz)}, with
 * Sun direction {@code (lx,ly,lz)}, occluders listed as {@code {x,y,z,radius}} in
 * receiver-radius units, and {@code sunAlpha} the Sun's angular radius (radians).
 *
 * <p>The Sun is modelled as an extended disc, so the factor is {@code 1 − (fraction of the
 * Sun's disc the occluder covers)}. That is the physics that sizes an eclipse: the umbra is a
 * thin track (total), a bright ring survives (annular), and whether an eclipse is total or
 * annular depends on the occluder's distance. The shadow is still a pure function of relative
 * geometry, so it is invariant under camera rotation — never a screen-space artefact.
 */
class ShadowTest {

    /** Sun's angular radius as seen from Earth (≈0.2666°); the real value the app computes. */
    private static final double SUN_ALPHA_EARTH = 6.9634e8 / 1.496e11;

    /** A tiny Sun (near point source), for the synthetic large-occluder geometry checks. */
    private static final double SUN_ALPHA_SMALL = 0.02;

    private static double factor(double[] n, double[] l, double[]... occluders) {
        return factor(SUN_ALPHA_SMALL, n, l, occluders);
    }

    private static double factor(double sunAlpha, double[] n, double[] l, double[]... occluders) {
        double[] occ = new double[occluders.length * 4];
        for (int k = 0; k < occluders.length; k++) System.arraycopy(occluders[k], 0, occ, 4 * k, 4);
        return SolarSystemSimulation.eclipseFactor(n[0], n[1], n[2], l[0], l[1], l[2],
                occ, occluders.length, sunAlpha);
    }

    /** Occluder centred on the Sun line through the surface point, {@code dist} radii sunward. */
    private static double[] occOnAxis(double[] n, double[] l, double dist, double radius) {
        return new double[]{n[0] + l[0] * dist, n[1] + l[1] * dist, n[2] + l[2] * dist, radius};
    }

    @Test
    void noOccludersMeansFullSun() {
        assertEquals(1.0, factor(new double[]{0, 0, 1}, new double[]{0, 0, 1}), 0.0);
    }

    @Test
    void occluderOnTheSunLineCastsDeepUmbra() {
        double[] n = {0, 0, 1}, l = {0, 0, 1};
        double f = factor(n, l, occOnAxis(n, l, 3.0, 0.5));
        assertTrue(f < 0.01, "expected deep umbra on-axis, got " + f);
    }

    @Test
    void occluderOffToTheSideDoesNotShadow() {
        double[] n = {0, 0, 1}, l = {0, 0, 1};
        // Same distance sunward but displaced far in x (well beyond the penumbra radius).
        double[] occ = {n[0] + 5.0, n[1], n[2] + 3.0, 0.5};
        assertEquals(1.0, factor(n, l, occ), 1e-9);
    }

    @Test
    void occluderBehindTheSurfaceIsIgnored() {
        // A body on the anti-solar side (along < 0) cannot shadow this point.
        double[] n = {0, 0, 1}, l = {0, 0, 1};
        double f = factor(n, l, occOnAxis(n, l, -3.0, 0.5));
        assertEquals(1.0, f, 1e-9);
    }

    @Test
    void penumbraIncreasesMonotonicallyTowardTheAxis() {
        double[] n = {0, 0, 1}, l = {0, 0, 1};
        double radius = 0.5;
        double prev = -1;
        // Sweep the occluder from far off-axis in toward the Sun line: shadow must deepen,
        // i.e. the visibility factor must fall monotonically from 1 to ~0.
        for (double off = 1.2 * radius; off >= 0; off -= 0.02) {
            double[] occ = {n[0] + off, n[1], n[2] + 3.0, radius};
            double f = factor(n, l, occ);
            if (prev >= 0) assertTrue(f <= prev + 1e-9,
                    String.format("shadow not monotonic: f=%.4f > prev=%.4f at off=%.3f", f, prev, off));
            prev = f;
        }
        assertTrue(prev < 0.01, "on-axis should be deep umbra, got " + prev);
    }

    // ------------------------------------------------------------------------------------
    // Rotation invariance — the "correct at all camera rotation" requirement. Rotating the
    // whole configuration (surface normal, Sun direction, every occluder centre) by an
    // arbitrary 3-D rotation must not change the shadow, because it depends only on relative
    // geometry, not on where the camera looks from.
    // ------------------------------------------------------------------------------------
    @Test
    void shadowIsInvariantUnderCameraRotation() {
        Random r = new Random(4242L);
        for (int trial = 0; trial < 2000; trial++) {
            double[] n = randomUnit(r);
            double[] l = randomUnit(r);
            double dist = 1.5 + r.nextDouble() * 4;
            double off = r.nextDouble() * 1.2;                  // spread across umbra..penumbra..clear
            double radius = 0.2 + r.nextDouble() * 0.6;
            // occluder = point + dist along Sun + off along an arbitrary perpendicular
            double[] perp = perpendicular(l, r);
            double[] occ = {n[0] + l[0]*dist + perp[0]*off,
                            n[1] + l[1]*dist + perp[1]*off,
                            n[2] + l[2]*dist + perp[2]*off, radius};

            double before = factor(n, l, occ);

            double[][] R = randomRotation(r);
            double[] nR = mul(R, n), lR = mul(R, l);
            double[] cR = mul(R, new double[]{occ[0], occ[1], occ[2]});
            double[] occR = {cR[0], cR[1], cR[2], radius};
            double after = factor(nR, lR, occR);

            assertEquals(before, after, 1e-9,
                    "shadow changed under camera rotation (trial " + trial + ")");
        }
    }

    // ------------------------------------------------------------------------------------
    // Perceptual response: the raw Sun-visible fraction is physically exact, but a partly
    // eclipsed patch of ground is still brightly lit. perceptualEclipse keeps the wide penumbra
    // bright and only darkens near totality, so the visible shadow hugs the umbra.
    // ------------------------------------------------------------------------------------
    @Test
    void perceptualCurveKeepsPenumbraBrightAndUmbraDark() {
        assertEquals(1.0, SolarSystemSimulation.perceptualEclipse(1.0), 1e-9, "full sun stays full");
        assertEquals(0.0, SolarSystemSimulation.perceptualEclipse(0.0), 1e-9, "totality stays black");
        // Half the Sun covered should still look nearly fully lit (not a grey blob).
        assertTrue(SolarSystemSimulation.perceptualEclipse(0.5) > 0.95,
                "50% partial should stay bright, got " + SolarSystemSimulation.perceptualEclipse(0.5));
        // Deep partial (90% covered) should be clearly dark.
        assertTrue(SolarSystemSimulation.perceptualEclipse(0.1) < 0.5,
                "90% obscured should read dark, got " + SolarSystemSimulation.perceptualEclipse(0.1));
        // Monotonic: less Sun visible never looks brighter.
        double prev = -1;
        for (double v = 0; v <= 1.0001; v += 0.05) {
            double f = SolarSystemSimulation.perceptualEclipse(Math.min(1, v));
            assertTrue(f >= prev - 1e-9, "perceptual curve must be monotonic");
            prev = f;
        }
    }

    // ------------------------------------------------------------------------------------
    // Extended-Sun physics: an annular eclipse. When the occluder's angular radius is smaller
    // than the Sun's, it can never cover the whole disc, so even dead on-axis a bright ring of
    // Sun survives — the factor bottoms out at 1 − (α_o/α_s)², not 0. (The old point-source
    // model wrongly drove this to full black.)
    // ------------------------------------------------------------------------------------
    @Test
    void annularEclipseLeavesABrightRing() {
        double[] n = {0, 0, 1}, l = {0, 0, 1};
        double sunAlpha = 0.005, dist = 50.0, radius = 0.20;   // α_o = 0.004 < α_s = 0.005
        double f = factor(sunAlpha, n, l, occOnAxis(n, l, dist, radius));
        double expected = 1.0 - Math.pow((radius / dist) / sunAlpha, 2);   // 1 − 0.64 = 0.36
        assertEquals(expected, f, 1e-6, "annular core should retain the ring of Sun");
        assertTrue(f > 0.3, "annular eclipse must not go fully dark, got " + f);
    }

    // ------------------------------------------------------------------------------------
    // Whether an eclipse is total or annular depends on the occluder's DISTANCE (the umbra is
    // a converging cone), so the same occluder that is total up close is annular when farther
    // away. This is exactly the distance dependence the old model discarded.
    // ------------------------------------------------------------------------------------
    @Test
    void totalVersusAnnularDependsOnDistance() {
        double[] n = {0, 0, 1}, l = {0, 0, 1};
        double radius = 0.2725;                                 // Moon in Earth radii
        double near = factor(SUN_ALPHA_EARTH, n, l, occOnAxis(n, l, 52.0, radius));  // α_o > α_s
        double far  = factor(SUN_ALPHA_EARTH, n, l, occOnAxis(n, l, 70.0, radius));  // α_o < α_s
        assertTrue(near < 0.01, "close occluder should give a total eclipse (deep umbra), got " + near);
        assertTrue(far  > 0.25, "distant occluder should be annular (ring of Sun left), got " + far);
    }

    // ------------------------------------------------------------------------------------
    // The "thin path" property: when the occluder and Sun subtend almost the same angle (Moon
    // on Earth), the fully-dark umbra footprint is a tiny fraction of the occluder's own disc,
    // not a blob ~0.4× its radius as the old core was. Off the axis by even 0.15× the occluder
    // radius, totality is already lost.
    // ------------------------------------------------------------------------------------
    @Test
    void totalUmbraFootprintIsAThinTrack() {
        double[] n = {0, 0, 1}, l = {0, 0, 1};
        double radius = 0.2725, dist = 52.0;                   // α_o ≈ 0.00524 vs α_s ≈ 0.00466
        // On-axis: total.
        double onAxis = factor(SUN_ALPHA_EARTH, n, l, occOnAxis(n, l, dist, radius));
        assertTrue(onAxis < 0.01, "on-axis should be a total eclipse, got " + onAxis);
        // Displaced perpendicular by only 0.25× the occluder radius, totality is already lost —
        // whereas the old point-source core stayed fully black out to 0.4× the radius.
        double off = 0.25 * radius;
        double[] occ = {n[0] + off, n[1], n[2] + dist, radius};
        double edge = factor(SUN_ALPHA_EARTH, n, l, occ);
        assertTrue(edge > 0.03,
                "umbra should be far narrower than the occluder disc; f at 0.25·r = " + edge);
    }

    // ---- small vector helpers ----

    private static double[] randomUnit(Random r) {
        double x = r.nextGaussian(), y = r.nextGaussian(), z = r.nextGaussian();
        double n = Math.sqrt(x*x + y*y + z*z);
        if (n < 1e-9) return new double[]{0, 0, 1};
        return new double[]{x/n, y/n, z/n};
    }

    /** A unit vector perpendicular to {@code v}. */
    private static double[] perpendicular(double[] v, Random r) {
        double[] a = randomUnit(r);
        double d = a[0]*v[0] + a[1]*v[1] + a[2]*v[2];
        double px = a[0] - d*v[0], py = a[1] - d*v[1], pz = a[2] - d*v[2];
        double n = Math.sqrt(px*px + py*py + pz*pz);
        if (n < 1e-6) return perpendicular(v, r);
        return new double[]{px/n, py/n, pz/n};
    }

    private static double[] mul(double[][] R, double[] v) {
        return new double[]{
                R[0][0]*v[0] + R[0][1]*v[1] + R[0][2]*v[2],
                R[1][0]*v[0] + R[1][1]*v[1] + R[1][2]*v[2],
                R[2][0]*v[0] + R[2][1]*v[1] + R[2][2]*v[2]};
    }

    /** A random proper rotation from three Euler angles. */
    private static double[][] randomRotation(Random r) {
        double a = r.nextDouble() * 2 * Math.PI, b = r.nextDouble() * 2 * Math.PI, c = r.nextDouble() * 2 * Math.PI;
        double[][] Rz = {{Math.cos(a), -Math.sin(a), 0}, {Math.sin(a), Math.cos(a), 0}, {0, 0, 1}};
        double[][] Ry = {{Math.cos(b), 0, Math.sin(b)}, {0, 1, 0}, {-Math.sin(b), 0, Math.cos(b)}};
        double[][] Rx = {{1, 0, 0}, {0, Math.cos(c), -Math.sin(c)}, {0, Math.sin(c), Math.cos(c)}};
        return matMul(matMul(Rz, Ry), Rx);
    }

    private static double[][] matMul(double[][] A, double[][] B) {
        double[][] C = new double[3][3];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                for (int k = 0; k < 3; k++) C[i][j] += A[i][k] * B[k][j];
        return C;
    }
}
