import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Project 1 coding lesson against real Python: every cell's working version must pass its
 * check, the classic mistakes must get their hints, and errors must point at the student's line.
 * Skipped where Python with numpy and astropy isn't installed.
 */
class BlackbodyProjectTest {

    static final PythonKernel K = PythonKernel.shared();
    static final BlackbodyProject LESSON = new BlackbodyProject(null);

    /** What the sim would hand over, at Earth's 2027 perihelion. */
    static Map<String, Object> sim() {
        Map<String, Object> m = new LinkedHashMap<>(), d = new LinkedHashMap<>(), t = new LinkedHashMap<>();
        double[] au = {0.4, 0.72, 0.98329, 1.55, 5.1, 9.7, 19.5, 29.9};
        t.put("Sun", 5772.0);
        for (int i = 0; i < 8; i++) { d.put(BlackbodyLesson.PLANETS[i], au[i]); t.put(BlackbodyLesson.PLANETS[i], BlackbodyLesson.T_MEAS[i]); }
        m.put("date", "2027-01-03");
        m.put("earth_sun_au", 0.98329);
        m.put("planet_au", d);
        m.put("temperatures", t);
        m.put("T_sun", 5772.0);
        return m;
    }

    static PythonKernel.Result run(CodeCell c, String code) { return K.runBlocking(c.id, code, c.check, sim()); }

    static String text(PythonKernel.Result r) {
        StringBuilder b = new StringBuilder(r.unavailable() ? r.problem() : String.valueOf(r.error()));
        for (Object[] c : r.checks()) b.append(" | ").append(c[0]).append(": ").append(c[1]);
        return b.toString();
    }

    @BeforeAll
    static void needsPython() {
        PythonKernel.Result r = K.runBlocking("probe", "x = 1", "", Map.of());
        assumeTrue(!r.unavailable(), "needs Python 3 with numpy and astropy: " + r.problem());
    }

    @Test
    void everyWorkingVersionPassesInLessonOrder() {
        List<CodeCell> cells = LESSON.cells();
        assertEquals(8, cells.size());
        for (CodeCell c : cells) {
            assertTrue(c.starter.contains(CodeCell.BLANK), c.id + " has blanks to fill");
            PythonKernel.Result r = run(c, c.solution);
            assertTrue(r.passed(), c.id + ": " + text(r) + "\nprinted: " + r.stdout());
        }
        // The numbers the scene draws.
        PythonKernel.Result light = run(cells.get(0), cells.get(0).solution);
        assertEquals(0.98329 * LessonKit.AU / LessonKit.C / 60, light.num("minutes"), 1e-9);
        PythonKernel.Result peak = run(cells.get(3), cells.get(3).solution);
        assertEquals(502.04, peak.num("wien"), 0.01);
        assertEquals(502.04, peak.num("peak"), 1.5);                 // grid spacing 2.9 nm
        PythonKernel.Result nu = run(cells.get(5), cells.get(5).solution);
        assertEquals(5.1e-3 / 5772 * 1e9, nu.num("c_over_nu"), 3);   // Wien's law for B_ν, as a wavelength
    }

    @Test
    void theNotebooksMisplacedParenthesisGetsItsHint() {
        CodeCell planck = LESSON.cells().get(1);
        String bug = BlackbodyProject.solve(planck.starter,
                "(2 * h * c**2 / wavelengths**5) / np.exp(h * c / (wavelengths * k_B * temperature) - 1)");
        PythonKernel.Result r = run(planck, bug);
        assertFalse(r.passed());
        assertTrue(text(r).contains("inside np.exp"), text(r));
    }

    @Test
    void forgettingPiSteradiansGetsItsHint() {
        CodeCell planck = LESSON.cells().get(1), flux = LESSON.cells().get(6);
        assertTrue(run(planck, planck.solution).passed());
        PythonKernel.Result r = run(flux, BlackbodyProject.solve(flux.starter, "planck_lambda(5772 * u.K, waves) * (R_sun / d)**2"));
        assertFalse(r.passed());
        assertTrue(text(r).contains("UnitConversionError") && text(r).contains("steradians"), text(r));
    }

    @Test
    void errorsPointAtTheStudentsOwnLine() {
        PythonKernel.Result r = K.runBlocking("scratch", "x = 1\ny = x + undefined_thing\n", "", Map.of());
        assertNotNull(r.error());
        assertTrue(r.error().contains("NameError") && r.error().contains("line 2"), r.error());
        PythonKernel.Result syn = K.runBlocking("scratch", "x = (1 +\n", "", Map.of());
        assertTrue(syn.error() != null && syn.error().contains("SyntaxError"), String.valueOf(syn.error()));
    }

    @Test
    void printedOutputComesBack() {
        PythonKernel.Result r = K.runBlocking("scratch", "print('the Sun', 5772 * u.K)", "", Map.of());
        assertNull(r.error());
        assertEquals("the Sun 5772.0 K", r.stdout().strip());
    }

    @Test
    void jsonRoundTrips() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("s", "a \"quote\"\nnew line ✓");
        m.put("n", 1.5e-17);
        m.put("l", List.of(1.0, 2.0));
        m.put("b", true);
        assertEquals(m, PythonKernel.Json.parse(PythonKernel.Json.write(m)));
    }
}
