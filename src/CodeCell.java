import java.util.Map;
import java.util.function.Supplier;

/**
 * One fill-in-the-blank Python cell in a lesson step. The student edits {@link #text} (it starts as
 * {@link #starter}, with blanks written {@code ____}); Run sends it to {@link PythonKernel} with
 * the step's check script and live values from the sim, and the step's scene draws from
 * {@link #result}. The cell belongs to the lesson, so the student's code and last result are still
 * there when they come back to the step.
 */
final class CodeCell {
    static final String BLANK = "____";

    final String id, starter, solution, check;
    final Supplier<Map<String, Object>> sim;
    String text;
    PythonKernel.Result result;
    boolean running;
    /** Bumped on every change, so the panel knows when to redraw the cell. */
    int version;

    /**
     * @param id       the cell's name in Python error messages ("task 1" -> "line 3 of the cell in task 1")
     * @param starter  the code the student starts from, with {@code ____} blanks
     * @param solution a filled-in version (for tests, head-less renders and the AI tutor; never shown)
     * @param check    Python that grades the run (see {@link PythonKernel#HARNESS})
     * @param sim      live values handed to the code as the dictionary {@code sim}
     */
    CodeCell(String id, String starter, String solution, String check, Supplier<Map<String, Object>> sim) {
        this.id = id; this.starter = starter.stripTrailing() + "\n"; this.solution = solution.stripTrailing() + "\n";
        this.check = check; this.sim = sim;
        this.text = this.starter;
    }

    boolean hasBlanks() { return text.contains(BLANK); }
    boolean passed() { return result != null && result.passed(); }

    /** Run the cell in the background; {@code changed} is called (on the Swing thread) as it starts and ends. */
    void run(Runnable changed) {
        if (running) return;
        if (hasBlanks()) {
            result = new PythonKernel.Result("", "Fill in every blank (" + BLANK + ") before running.", Map.of(), null);
            version++;
            changed.run();
            return;
        }
        running = true;
        version++;
        changed.run();
        PythonKernel.shared().run(id, text, check, sim.get(), r -> {
            result = r;
            running = false;
            version++;
            changed.run();
        });
    }

    /** Run and wait (tests and head-less renders). */
    PythonKernel.Result runBlocking() {
        result = PythonKernel.shared().runBlocking(id, text, check, sim.get());
        version++;
        return result;
    }

    void reset() { text = starter; result = null; version++; }

    /** One line on the last run, for the AI assistant. */
    String summary() {
        if (running) return "running";
        if (result == null) return "not run yet";
        if (result.unavailable()) return "Python unavailable: " + result.problem();
        StringBuilder b = new StringBuilder(result.passed() ? "all checks passed" : "not passing yet");
        if (result.error() != null) b.append("; error: ").append(result.error());
        for (Object[] c : result.checks()) b.append("; ").append((Boolean) c[0] ? "OK: " : "NOT OK: ").append(c[1]);
        if (!result.stdout().isBlank()) b.append("; printed: ").append(result.stdout().strip().replace('\n', ' '));
        return b.toString();
    }
}
