import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Project 1 lesson against the notebook it follows: its grid peaks must match the notebook's
 * printed outputs, and the FITS reader must give the same spectra astropy reads (reference values
 * from astropy.io.fits on the same files).
 */
class BlackbodyProjectTest {

    @Test
    void gridPeaksMatchTheNotebook() {
        // Task 2: np.argmax on np.linspace(100, 3000, 1000) nm at 5000 K printed 578.978978978979 nm.
        assertEquals(578.978979, BlackbodyProject.peakOnGrid(5000, 100, 3000, 1000), 1e-5);
        // Task 3: (c / peak_freq).to(u.nm) printed 1020.2202202202201 nm.
        assertEquals(1020.220220, BlackbodyProject.nuPeakOnGrid(5000, 100, 3000, 1000), 1e-5);
    }

    @Test
    void planckIntegratesToStefanBoltzmann() {
        for (double T : new double[]{3000, 5772, 12000}) {
            double exact = LessonKit.SIGMA * Math.pow(T, 4) / Math.PI;
            assertEquals(1, BlackbodyProject.area(T) / exact, 1e-3, "∫Bλ dλ at " + T + " K");
        }
    }

    @Test
    void misplacedParenthesisIsAboutETimesTooHigh() {
        double T = 5000, best = 0;
        for (double nm = 400; nm < 800; nm += 0.1) best = Math.max(best, BlackbodyProject.bLamBug(nm, T));
        double ratio = best / BlackbodyProject.bLam(LessonKit.WIEN / T * 1e9, T);
        assertEquals(2.700, ratio, 0.005);
    }

    @Test
    void readsTheSdssSpectraLikeAstropy() {
        List<SdssSpectra.Spectrum> all = SdssSpectra.all();
        assertEquals(8, all.size(), "eight spectra in assets/spectra");
        for (int i = 1; i < all.size(); i++) assertTrue(all.get(i - 1).teff() >= all.get(i).teff(), "hottest first");
        SdssSpectra.Spectrum s = all.stream().filter(x -> x.template().equals("HD165341")).findFirst().orElseThrow();
        assertEquals("K0V", s.spType());
        assertEquals(5139, s.teff(), 1e-9);
        assertEquals(4.79, s.logg(), 1e-5);
        assertEquals(2667, s.plate());
        assertEquals(54142, s.mjd());
        assertEquals(469, s.fiber());
        assertEquals(3825, s.nm().length);
        assertEquals(382.03215, s.nm()[0], 1e-3);
        assertEquals(921.5098, s.nm()[s.nm().length - 1], 1e-3);
        assertEquals(567.93696, BlackbodyProject.peakOf(s, false), 1e-3);
        assertEquals(1.754e-17, BlackbodyProject.median(s.flux()), 0.002e-17);   // W m⁻² nm⁻¹
        assertEquals(1.342e-21, BlackbodyProject.medianScale(s), 0.002e-21);      // sr
        assertEquals(4905, BlackbodyProject.fitT(s), 6);
    }

    @Test
    void allEightAreAtM67sDistance() {
        // F = πB(R/d)²: for Sun-sized stars the median scale puts all eight within ~25% of M67's 860 pc.
        for (SdssSpectra.Spectrum s : SdssSpectra.all()) {
            double d = LessonKit.R_SUN / Math.sqrt(BlackbodyProject.medianScale(s) / Math.PI) / LessonKit.PC;
            assertTrue(d > 650 && d < 1150, s.name() + " at " + Math.round(d) + " pc");
        }
    }
}
