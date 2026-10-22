import javax.swing.SwingUtilities;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The real Python behind the Lesson Studio's code cells: one persistent process, like a notebook
 * kernel, so a function written in one step is still there in the next. It runs the Python
 * installed on the student's computer (with numpy and astropy, as the course notebooks need):
 * {@code -Dsss.python=<path>} if given, otherwise {@code py -3}, {@code python} or {@code python3}.
 *
 * <p>Each request is one JSON line: the student's code, the lesson's check script and the live
 * values the sim hands the code as {@code sim}. The harness runs the code with its printing
 * captured, then the check, which grades the result and returns numbers and arrays for the scene
 * to draw. A run that takes longer than {@link #TIMEOUT_MS} is stopped and Python restarted.
 */
final class PythonKernel {

    /** What one run returned: printed text, the code's error (null if it ran) and the check's report. */
    record Result(String stdout, String error, Map<String, Object> data, String problem) {
        /** Python couldn't be started, or lacks numpy / astropy. */
        boolean unavailable() { return problem != null; }
        /** The code ran and every check passed. */
        boolean passed() { return problem == null && error == null && Boolean.TRUE.equals(data.get("pass")); }
        /** The check's lines: {passed?, text}. */
        List<Object[]> checks() {
            List<Object[]> out = new ArrayList<>();
            if (data.get("checks") instanceof List<?> l)
                for (Object o : l) if (o instanceof List<?> c && c.size() == 2) out.add(new Object[]{Boolean.TRUE.equals(c.get(0)), String.valueOf(c.get(1))});
            return out;
        }
        double num(String key) { return data.get(key) instanceof Number n ? n.doubleValue() : Double.NaN; }
        String str(String key) { return data.get(key) instanceof String s ? s : null; }
        double[] arr(String key) {
            if (!(data.get(key) instanceof List<?> l)) return null;
            double[] a = new double[l.size()];
            for (int i = 0; i < a.length; i++) a[i] = l.get(i) instanceof Number n ? n.doubleValue() : Double.NaN;
            return a;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map(String key) { return data.get(key) instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of(); }
    }

    static final long TIMEOUT_MS = 30_000, START_MS = 90_000;
    private static final String PREFIX = "@@KERNEL@@", EOF = "@@EOF@@";

    private static PythonKernel shared;
    static synchronized PythonKernel shared() {
        if (shared == null) shared = new PythonKernel();
        return shared;
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "python-kernel");
        t.setDaemon(true);
        return t;
    });
    private Process proc;
    private BufferedWriter toPython;
    private BlockingQueue<String> replies;
    private final StringBuilder stderrTail = new StringBuilder();
    private List<String> command;               // the command that worked, kept for restarts
    private volatile String info, problem;     // "Python 3.13.1 · numpy 2.3.3 · astropy 8.0.1" / why it can't run

    private PythonKernel() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
    }

    /** One line about the Python in use, or why there is none; null until it has been started. */
    String status() { return problem != null ? problem : info; }

    /** Start Python in the background, so the first Run doesn't wait for numpy and astropy to load. */
    void warmUp() { worker.submit(this::ensureStarted); }

    /** Run a cell off the UI thread; {@code done} is called on the Swing thread. */
    void run(String cell, String code, String check, Map<String, Object> sim, Consumer<Result> done) {
        worker.submit(() -> {
            Result r = runNow(cell, code, check, sim);
            SwingUtilities.invokeLater(() -> done.accept(r));
        });
    }

    /** Run a cell and wait for it (tests and head-less renders). */
    Result runBlocking(String cell, String code, String check, Map<String, Object> sim) {
        try { return worker.submit(() -> runNow(cell, code, check, sim)).get(); }
        catch (Exception ex) { return new Result("", null, Map.of(), "Python run failed: " + ex); }
    }

    /** Forget everything the student defined (and anything stuck) by restarting Python. */
    void restart() { worker.submit(this::stop); }

    // ------------------------------------------------------------------ the process

    private Result runNow(String cell, String code, String check, Map<String, Object> sim) {
        String why = ensureStarted();
        if (why != null) return new Result("", null, Map.of(), why);
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("cell", cell);
        req.put("code", code);
        req.put("check", check == null ? "" : check);
        req.put("sim", sim == null ? Map.of() : sim);
        try {
            toPython.write(Json.write(req));
            toPython.newLine();
            toPython.flush();
            String line = replies.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (line == null) {
                stop();
                return new Result("", "Your code ran for more than " + TIMEOUT_MS / 1000 + " seconds, so it was stopped. "
                        + "Look for a loop that never ends, or a grid with far too many points. "
                        + "Python was restarted: run the earlier steps' cells again.", Map.of(), null);
            }
            if (line.equals(EOF)) {
                String tail = tail();
                stop();
                return new Result("", "Python stopped unexpectedly and was restarted. Run the earlier steps' cells again."
                        + (tail.isBlank() ? "" : "\n" + tail), Map.of(), null);
            }
            return toResult(Json.parse(line));
        } catch (IOException | InterruptedException | RuntimeException ex) {
            stop();
            return new Result("", "Lost contact with Python (" + ex.getMessage() + "); it was restarted.", Map.of(), null);
        }
    }

    @SuppressWarnings("unchecked")
    private static Result toResult(Object o) {
        Map<String, Object> m = o instanceof Map<?, ?> mm ? (Map<String, Object>) mm : Map.of();
        Map<String, Object> data = m.get("data") instanceof Map<?, ?> d ? (Map<String, Object>) d : Map.of();
        return new Result(m.get("stdout") instanceof String s ? s : "", m.get("error") instanceof String e ? e : null, data, null);
    }

    /** Null when Python is running with numpy and astropy; otherwise what is wrong, for the student. */
    private String ensureStarted() {
        if (proc != null && proc.isAlive()) return problem;
        problem = null;
        Path harness;
        try {
            Path dir = Files.createTempDirectory("sss-python");
            harness = dir.resolve("sim_kernel.py");
            Files.writeString(harness, HARNESS, StandardCharsets.UTF_8);
            harness.toFile().deleteOnExit();
            dir.toFile().deleteOnExit();
        } catch (IOException ex) {
            return problem = "Couldn't write the Python helper script: " + ex.getMessage();
        }
        List<List<String>> tries = new ArrayList<>();
        if (command != null) tries.add(command);
        String forced = System.getProperty("sss.python");
        if (forced != null && !forced.isBlank()) tries.add(List.of(forced));
        else if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            tries.add(List.of("py", "-3")); tries.add(List.of("python")); tries.add(List.of("python3"));
        } else {
            tries.add(List.of("python3")); tries.add(List.of("python"));
        }
        String last = null;
        for (List<String> cmd : tries) {
            String r = startWith(cmd, harness);
            if (r == null) { command = cmd; return problem; }
            last = r;
        }
        return problem = "Python 3 wasn't found on this computer, so code cells can't run. Install Python 3 from "
                + "python.org, then run:  python -m pip install numpy astropy  (or start the app with "
                + "-Dsss.python=<path to python>)." + (last == null ? "" : "\n(" + last + ")");
    }

    /** Null on success, else why this command didn't give a working kernel. */
    @SuppressWarnings("unchecked")
    private String startWith(List<String> cmd, Path harness) {
        List<String> full = new ArrayList<>(cmd);
        full.add("-u");
        full.add(harness.toString());
        ProcessBuilder pb = new ProcessBuilder(full).directory(harness.getParent().toFile());
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        pb.environment().put("PYTHONUNBUFFERED", "1");
        pb.environment().remove("PYTHONSTARTUP");
        try {
            proc = pb.start();
        } catch (IOException ex) {
            proc = null;
            return String.join(" ", cmd) + ": " + ex.getMessage();
        }
        toPython = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8));
        replies = new LinkedBlockingQueue<>();
        BlockingQueue<String> q = replies;
        Process p = proc;
        Thread out = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) if (line.startsWith(PREFIX)) q.add(line.substring(PREFIX.length()));
            } catch (IOException ignored) { }
            q.add(EOF);
        }, "python-stdout");
        Thread err = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) synchronized (stderrTail) {
                    stderrTail.append(line).append('\n');
                    if (stderrTail.length() > 4000) stderrTail.delete(0, stderrTail.length() - 4000);
                }
            } catch (IOException ignored) { }
        }, "python-stderr");
        out.setDaemon(true); err.setDaemon(true);
        out.start(); err.start();
        try {
            String hello = q.poll(START_MS, TimeUnit.MILLISECONDS);
            if (hello == null || hello.equals(EOF)) {
                String t = tail();
                stop();
                return String.join(" ", cmd) + " didn't start" + (t.isBlank() ? "" : ": " + t.strip());
            }
            Map<String, Object> h = (Map<String, Object>) Json.parse(hello);
            Map<String, Object> v = h.get("hello") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            info = "Python " + v.getOrDefault("python", "?")
                    + (v.containsKey("numpy") ? " · numpy " + v.get("numpy") : "")
                    + (v.containsKey("astropy") ? " · astropy " + v.get("astropy") : "");
            if (h.get("missing") instanceof List<?> miss && !miss.isEmpty()) {
                String names = String.join(" and ", miss.stream().map(String::valueOf).toList());
                problem = info + " was found, but it doesn't have " + names + ". Install "
                        + (miss.size() > 1 ? "them" : "it") + " with:  " + String.join(" ", cmd) + " -m pip install numpy astropy";
            }
            return null;
        } catch (InterruptedException | RuntimeException ex) {
            stop();
            return String.join(" ", cmd) + ": " + ex.getMessage();
        }
    }

    private String tail() {
        synchronized (stderrTail) {
            String s = stderrTail.toString();
            stderrTail.setLength(0);
            return s.length() > 600 ? s.substring(s.length() - 600) : s;
        }
    }

    private synchronized void stop() {
        if (proc != null) {
            proc.destroy();
            try { if (!proc.waitFor(2, TimeUnit.SECONDS)) proc.destroyForcibly(); } catch (InterruptedException ignored) { }
            proc = null;
        }
    }

    // ------------------------------------------------------------------ the Python side

    /**
     * The harness. Student code runs in one namespace that lasts between cells, with the notebook's
     * imports already made. Each cell is compiled under its own name, so errors point at the
     * student's own line. Checks run in a separate namespace with their own constants (a student
     * who reuses the name {@code c} can't break the grading) and helpers: {@code need} fetches a
     * student variable, {@code quantity} insists on convertible units, {@code report.ok/bad}
     * grade, and {@code report.data} carries numbers and arrays back to the scene.
     */
    static final String HARNESS = """
            import sys, io, json, math, traceback, contextlib
            PREFIX = "@@KERNEL@@"
            real_out = sys.stdout

            def clean(o):
                # JSON-safe copy: Quantities lose their units, arrays become lists, NaN becomes null.
                if hasattr(o, "unit") and hasattr(o, "value"):
                    o = o.value
                if hasattr(o, "tolist"):
                    o = o.tolist()
                if isinstance(o, dict):
                    return {str(k): clean(v) for k, v in o.items()}
                if isinstance(o, (list, tuple, set)):
                    return [clean(v) for v in o]
                if isinstance(o, float):
                    return o if math.isfinite(o) else None
                if o is None or isinstance(o, (bool, int, str)):
                    return o
                return str(o)

            def send(obj):
                real_out.write(PREFIX + json.dumps(clean(obj)) + "\\n")
                real_out.flush()

            ns = {"__name__": "__main__", "math": math}
            ref = {"math": math}
            info = {"python": sys.version.split()[0]}
            missing = []
            try:
                import numpy as np
                if not hasattr(np, "trapezoid"):
                    np.trapezoid = np.trapz
                ns["np"] = ref["np"] = np
                info["numpy"] = np.__version__
            except ImportError:
                missing.append("numpy")
            try:
                import astropy
                from astropy import units as u, constants as const
                for k in ("c", "h", "k_B", "b_wien", "sigma_sb", "R_sun", "L_sun", "M_sun", "G"):
                    ns[k] = getattr(const, k)
                ns.update(u=u, const=const)
                ref.update(u=u, const=const)
                info["astropy"] = astropy.__version__
            except ImportError:
                missing.append("astropy")
            send({"hello": info, "missing": missing})

            sources = {}

            def student_error(e):
                if isinstance(e, SyntaxError) and e.filename in sources:
                    text = (e.text or "").strip()
                    return "SyntaxError on line %s: %s\\n    %s" % (e.lineno, e.msg, text)
                frames = [f for f in traceback.extract_tb(e.__traceback__) if f.filename in sources]
                msg = "%s: %s" % (type(e).__name__, e)
                if type(e).__name__ == "UnitConversionError" and " sr" in str(e):
                    msg += ("\\n  Hint: one side still has steradians (sr). Intensity becomes flux by "
                            "integrating over directions, a factor of pi sr.")
                if frames:
                    f = frames[-1]
                    lines = sources[f.filename].splitlines()
                    code = lines[f.lineno - 1].strip() if 0 < f.lineno <= len(lines) else ""
                    where = "line %d" % f.lineno
                    if f.filename != current[0]:
                        where += " of the cell in %s" % f.filename.strip("<>")
                    return "%s\\n  on %s:  %s" % (msg, where, code)
                return msg

            current = [""]

            class Fail(Exception):
                pass

            class Report:
                def __init__(self):
                    self.items, self.data = [], {}
                def ok(self, text):
                    self.items.append([True, text])
                def bad(self, text):
                    self.items.append([False, text])

            def need(name, hint=""):
                if name not in ns:
                    raise Fail("%s isn't defined yet%s" % (name, (": " + hint) if hint else "."))
                return ns[name]

            def quantity(v, unit, what):
                unit = u.Unit(unit)
                if not isinstance(v, u.Quantity):
                    raise Fail("%s has no units. It should be in %s: attach units with astropy, as in 500 * u.nm."
                               % (what, unit.to_string()))
                try:
                    return v.to(unit)
                except u.UnitConversionError:
                    raise Fail("%s is in %s, which can't be converted to %s." % (what, v.unit.to_string(), unit.to_string()))

            def number(v, what):
                if isinstance(v, u.Quantity):
                    try:
                        return float(v.to(u.dimensionless_unscaled).value)
                    except u.UnitConversionError:
                        raise Fail("%s should be a plain ratio, but it has units of %s." % (what, v.unit.to_string()))
                try:
                    return float(v)
                except Exception:
                    raise Fail("%s should be a number." % what)

            def planck_ref(T, lam):
                x = (const.h * const.c / (lam * const.k_B * T)).decompose().value
                return (2 * const.h * const.c ** 2 / lam ** 5 / np.expm1(x)).to(u.W / u.m ** 2 / u.nm) / u.sr

            def planck_nu_ref(T, nu):
                x = (const.h * nu / (const.k_B * T)).decompose().value
                return (2 * const.h * nu ** 3 / const.c ** 2 / np.expm1(x)).to(u.W / u.m ** 2 / u.Hz) / u.sr

            ref.update(need=need, quantity=quantity, number=number, Fail=Fail,
                       planck_ref=planck_ref, planck_nu_ref=planck_nu_ref)

            for line in sys.stdin:
                try:
                    req = json.loads(line)
                except ValueError:
                    continue
                name = "<%s>" % req.get("cell", "cell")
                current[0] = name
                src = req.get("code", "")
                sources[name] = src
                ns["sim"] = req.get("sim", {})
                out = io.StringIO()
                err = None
                report = Report()
                try:
                    with contextlib.redirect_stdout(out):
                        exec(compile(src, name, "exec"), ns)
                except BaseException as e:
                    if isinstance(e, KeyboardInterrupt):
                        raise
                    err = student_error(e)
                if err is None and req.get("check"):
                    env = dict(ref)
                    env.update(ns=ns, sim=ns["sim"], report=report)
                    try:
                        with contextlib.redirect_stdout(io.StringIO()):
                            exec(compile(req["check"], "<check>", "exec"), env)
                    except Fail as f:
                        report.bad(str(f))
                    except Exception as e:
                        if any(fr.filename in sources for fr in traceback.extract_tb(e.__traceback__)):
                            err = "While checking, your code raised " + student_error(e)
                        else:
                            report.bad("The check couldn't finish: %s: %s" % (type(e).__name__, e))
                data = report.data
                data["checks"] = report.items
                data["pass"] = err is None and bool(report.items) and all(ok for ok, _ in report.items)
                text = out.getvalue()
                if len(text) > 6000:
                    text = text[:6000] + "\\n... (output cut short)"
                send({"stdout": text, "error": err, "data": data})
            """;

    // ------------------------------------------------------------------ a little JSON

    /** Just enough JSON for the kernel protocol: maps, lists, strings, numbers, booleans, null. */
    static final class Json {
        static String write(Object o) {
            StringBuilder b = new StringBuilder();
            write(o, b);
            return b.toString();
        }
        private static void write(Object o, StringBuilder b) {
            if (o == null) b.append("null");
            else if (o instanceof String s) str(s, b);
            else if (o instanceof Boolean x) b.append(x);
            else if (o instanceof Number n) {
                double d = n.doubleValue();
                if (Double.isFinite(d)) b.append(n instanceof Integer || n instanceof Long ? String.valueOf(n.longValue()) : String.valueOf(d));
                else b.append("null");
            } else if (o instanceof Map<?, ?> m) {
                b.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) b.append(',');
                    first = false;
                    str(String.valueOf(e.getKey()), b);
                    b.append(':');
                    write(e.getValue(), b);
                }
                b.append('}');
            } else if (o instanceof double[] a) {
                b.append('[');
                for (int i = 0; i < a.length; i++) { if (i > 0) b.append(','); write(a[i], b); }
                b.append(']');
            } else if (o instanceof Iterable<?> it) {
                b.append('[');
                boolean first = true;
                for (Object x : it) { if (!first) b.append(','); first = false; write(x, b); }
                b.append(']');
            } else str(String.valueOf(o), b);
        }
        private static void str(String s, StringBuilder b) {
            b.append('"');
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                switch (ch) {
                    case '"' -> b.append("\\\"");
                    case '\\' -> b.append("\\\\");
                    case '\n' -> b.append("\\n");
                    case '\r' -> b.append("\\r");
                    case '\t' -> b.append("\\t");
                    default -> { if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch)); else b.append(ch); }
                }
            }
            b.append('"');
        }

        static Object parse(String s) {
            int[] at = {0};
            Object v = value(s, at);
            return v;
        }
        private static void ws(String s, int[] at) { while (at[0] < s.length() && Character.isWhitespace(s.charAt(at[0]))) at[0]++; }
        private static Object value(String s, int[] at) {
            ws(s, at);
            if (at[0] >= s.length()) throw new IllegalArgumentException("unexpected end of JSON");
            char ch = s.charAt(at[0]);
            switch (ch) {
                case '{' -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    at[0]++;
                    ws(s, at);
                    if (s.charAt(at[0]) == '}') { at[0]++; return m; }
                    while (true) {
                        ws(s, at);
                        String k = string(s, at);
                        ws(s, at);
                        if (s.charAt(at[0]++) != ':') throw new IllegalArgumentException("expected ':' in JSON");
                        m.put(k, value(s, at));
                        ws(s, at);
                        char c = s.charAt(at[0]++);
                        if (c == '}') return m;
                        if (c != ',') throw new IllegalArgumentException("expected ',' or '}' in JSON");
                    }
                }
                case '[' -> {
                    List<Object> l = new ArrayList<>();
                    at[0]++;
                    ws(s, at);
                    if (s.charAt(at[0]) == ']') { at[0]++; return l; }
                    while (true) {
                        l.add(value(s, at));
                        ws(s, at);
                        char c = s.charAt(at[0]++);
                        if (c == ']') return l;
                        if (c != ',') throw new IllegalArgumentException("expected ',' or ']' in JSON");
                    }
                }
                case '"' -> { return string(s, at); }
                case 't' -> { at[0] += 4; return Boolean.TRUE; }
                case 'f' -> { at[0] += 5; return Boolean.FALSE; }
                case 'n' -> { at[0] += 4; return null; }
                default -> {
                    int start = at[0];
                    while (at[0] < s.length() && "+-0123456789.eE".indexOf(s.charAt(at[0])) >= 0) at[0]++;
                    return Double.parseDouble(s.substring(start, at[0]));
                }
            }
        }
        private static String string(String s, int[] at) {
            if (s.charAt(at[0]) != '"') throw new IllegalArgumentException("expected a string in JSON");
            at[0]++;
            StringBuilder b = new StringBuilder();
            while (true) {
                char ch = s.charAt(at[0]++);
                if (ch == '"') return b.toString();
                if (ch != '\\') { b.append(ch); continue; }
                char e = s.charAt(at[0]++);
                switch (e) {
                    case 'n' -> b.append('\n');
                    case 't' -> b.append('\t');
                    case 'r' -> b.append('\r');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'u' -> { b.append((char) Integer.parseInt(s.substring(at[0], at[0] + 4), 16)); at[0] += 4; }
                    default -> b.append(e);
                }
            }
        }
    }
}
