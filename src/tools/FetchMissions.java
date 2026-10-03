package tools;

import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/**
 * Downloads spacecraft trajectories from JPL Horizons and writes them as the compact track
 * files the sim plays back ({@code assets/missions/<id>.trk.gz}).
 *
 * <p>A track is a set of <em>segments</em>, each a list of ecliptic-J2000 states (TDB seconds
 * since J2000, km, km/s) relative to one centre body: a base segment (the Sun for deep-space
 * probes, the Earth for lunar missions) spanning the whole mission, plus local segments while
 * the craft is near a planet or the Moon. Playback places a sample at the sim's own position
 * of that centre, so a flyby hugs the planet exactly as Horizons has it, independent of the
 * small difference between the sim's ephemeris and JPL's.
 *
 * <p>Sampling is adaptive and validated: each window is fetched densely (finer still around
 * close approaches, where the dynamical time {@code sqrt(r³/GM)} is short), then thinned
 * greedily, keeping a sample only where cubic Hermite interpolation of the kept neighbours —
 * exactly what the sim does — would miss a dense sample by more than {@code max(0.2 km, 1e-5 r)}.
 *
 * <p>Usage (needs network): {@code java tools.FetchMissions [missionId ...]}; no arguments
 * fetches every mission. Run from the project root.
 */
public final class FetchMissions {

    /** A centre body: Horizons code, GM (km³/s²), and the radius (km) inside which it gets a local segment. */
    record Centre(String name, String code, double gm, double window) {}

    static final Centre SUN = new Centre("Sun", "10", 1.32712440041e11, 0);
    static final Centre EARTH = new Centre("Earth", "399", 398600.4418, 2.0e6);
    static final Centre MOON = new Centre("Moon", "301", 4902.800, 1.0e5);
    static final Centre VENUS = new Centre("Venus", "299", 324858.592, 1.2e6);
    static final Centre JUPITER = new Centre("Jupiter", "599", 1.26686534e8, 6.0e7);
    static final Centre SATURN = new Centre("Saturn", "699", 3.7931187e7, 6.0e7);
    static final Centre URANUS = new Centre("Uranus", "799", 5.793939e6, 5.0e7);
    static final Centre NEPTUNE = new Centre("Neptune", "899", 6.836529e6, 8.0e7);
    static final Centre PLUTO = new Centre("Pluto", "999", 869.6, 5.0e6);

    /** One mission to fetch: Horizons id, span, base centre, local centres, dense step for the base. */
    record Mission(String id, String horizonsId, String start, String stop, Centre base,
                   List<Centre> local, String baseStep) {}

    static final List<Mission> MISSIONS = List.of(
        new Mission("voyager1", "-31", "1977-09-05 14:00", "2030-01-01", SUN, List.of(EARTH, JUPITER, SATURN), "1d"),
        new Mission("voyager2", "-32", "1977-08-20 15:33", "2030-01-01", SUN, List.of(EARTH, JUPITER, SATURN, URANUS, NEPTUNE), "1d"),
        new Mission("cassini", "-82", "1997-10-15 09:28", "2017-09-15 11:57", SUN, List.of(EARTH, VENUS, JUPITER, SATURN), "1d"),
        new Mission("newhorizons", "-98", "2006-01-19 19:52", "2030-01-01", SUN, List.of(EARTH, JUPITER, PLUTO), "1d"),
        new Mission("juno", "-61", "2011-08-05 17:20", "2025-09-30", SUN, List.of(EARTH, JUPITER), "1d"),
        new Mission("artemis1", "-1023", "2022-11-16 08:47", "2022-12-11 17:20", EARTH, List.of(MOON), "10m"),
        new Mission("chandrayaan3", "-158", "2023-07-14 09:23", "2023-09-06 12:33", EARTH, List.of(MOON), "10m"),
        new Mission("artemis2", "-1024", "2026-04-02 01:59", "2026-04-10 23:54", EARTH, List.of(MOON), "10m")
    );

    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get("assets", "missions");
        Files.createDirectories(dir);
        Set<String> want = new HashSet<>(Arrays.asList(args));
        for (Mission m : MISSIONS) {
            if (!want.isEmpty() && !want.contains(m.id())) continue;
            System.out.println("== " + m.id());
            StringBuilder out = new StringBuilder();
            out.append("mission ").append(m.id()).append('\n');
            // Base segment over the whole mission.
            List<double[]> base = fetchAdaptive(m.horizonsId(), m.base(), m.start(), m.stop(), m.baseStep());
            appendSegment(out, m.base(), base);
            // Local segments while inside each centre's window.
            for (Centre c : m.local()) {
                List<double[]> coarse = fetch(m.horizonsId(), c, m.start(), m.stop(),
                        m.base() == EARTH ? "10m" : "1d");
                for (double[] w : windows(coarse, c.window(), m.base() == EARTH ? 0 : 2 * 86400.0)) {
                    String a = jdString(w[0]), b = jdString(w[1]);
                    String step = c == MOON ? "5m" : (c == EARTH ? "10m" : "1h");
                    List<double[]> seg = fetchAdaptive(m.horizonsId(), c, a, b, step);
                    if (seg.size() >= 2) appendSegment(out, c, seg);
                }
            }
            Path f = dir.resolve(m.id() + ".trk.gz");
            try (Writer wr = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(f)), StandardCharsets.US_ASCII)) {
                wr.write(out.toString());
            }
            System.out.printf("   wrote %s (%d bytes)%n", f, Files.size(f));
        }
    }

    // ------------------------------------------------------------------ sampling

    /**
     * Fetch a window densely at {@code step}, refine wherever that step is coarse compared with
     * the local dynamical time, then thin to the samples Hermite interpolation needs.
     */
    static List<double[]> fetchAdaptive(String id, Centre c, String start, String stop, String step) throws Exception {
        TreeMap<Double, double[]> dense = new TreeMap<>();
        for (double[] s : fetch(id, c, start, stop, step)) dense.put(s[0], s);
        // Refinement: intervals where the step exceeds 0.12 of sqrt(r³/GM).
        double stepSec = stepSeconds(step);
        List<double[]> list = new ArrayList<>(dense.values());
        List<double[]> fine = new ArrayList<>();      // {t0, t1, stepSec}
        for (int k = 0; k + 1 < list.size(); k++) {
            double[] s = list.get(k), u = list.get(k + 1);
            double r = Math.min(norm(s), norm(u));
            double tdyn = Math.sqrt(r * r * r / c.gm());
            if (u[0] - s[0] > 0.12 * tdyn) {
                double fs = Math.max(60.0, Math.floor(0.08 * tdyn / 60.0) * 60.0);
                if (fs < u[0] - s[0]) fine.add(new double[]{s[0], u[0], fs});
            }
        }
        // Merge adjacent refinement windows with the same (smallest) step.
        List<double[]> merged = new ArrayList<>();
        for (double[] w : fine) {
            double[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && w[0] <= last[1] + 1) { last[1] = w[1]; last[2] = Math.min(last[2], w[2]); }
            else merged.add(w.clone());
        }
        for (double[] w : merged) {
            String st = (int) Math.round(w[2] / 60.0) + "m";
            for (double[] s : fetch(id, c, jdString(w[0]), jdString(w[1]), st)) dense.put(s[0], s);
        }
        List<double[]> all = new ArrayList<>(dense.values());
        List<double[]> kept = thin(all);
        System.out.printf("   %-8s %s .. %s : %d dense -> %d kept (%d refinements)%n",
                c.name(), start, stop, all.size(), kept.size(), merged.size());
        return kept;
    }

    /** Greedy thinning: keep the fewest samples whose Hermite interpolant reproduces every dense one. */
    static List<double[]> thin(List<double[]> d) {
        List<double[]> kept = new ArrayList<>();
        if (d.isEmpty()) return kept;
        int i = 0;
        kept.add(d.get(0));
        while (i < d.size() - 1) {
            int j = i + 1;
            while (j + 1 < d.size() && fits(d, i, j + 1)) j++;
            kept.add(d.get(j));
            i = j;
        }
        return kept;
    }

    static boolean fits(List<double[]> d, int i, int j) {
        double[] a = d.get(i), b = d.get(j);
        double[] p = new double[3];
        for (int k = i + 1; k < j; k++) {
            double[] s = d.get(k);
            hermite(a, b, s[0], p);
            double err = Math.sqrt(sq(p[0] - s[1]) + sq(p[1] - s[2]) + sq(p[2] - s[3]));
            double tol = Math.max(0.2, 1e-5 * norm(s));
            if (err > tol) return false;
        }
        return true;
    }

    /** Cubic Hermite position at t between states a and b ({t,x,y,z,vx,vy,vz}). Same as the sim's. */
    static void hermite(double[] a, double[] b, double t, double[] out) {
        double h = b[0] - a[0], s = (t - a[0]) / h;
        double s2 = s * s, s3 = s2 * s;
        double h00 = 2 * s3 - 3 * s2 + 1, h10 = s3 - 2 * s2 + s, h01 = -2 * s3 + 3 * s2, h11 = s3 - s2;
        for (int c = 0; c < 3; c++)
            out[c] = h00 * a[1 + c] + h10 * h * a[4 + c] + h01 * b[1 + c] + h11 * h * b[4 + c];
    }

    /** Time windows (TDB s) where the distance from the centre is below {@code radius}, padded and merged. */
    static List<double[]> windows(List<double[]> coarse, double radius, double pad) {
        List<double[]> w = new ArrayList<>();
        double[] cur = null;
        for (double[] s : coarse) {
            boolean in = norm(s) < radius;
            if (in && cur == null) cur = new double[]{s[0] - pad, s[0]};
            if (in) cur[1] = s[0] + pad;
            if (!in && cur != null) { w.add(cur); cur = null; }
        }
        if (cur != null) w.add(cur);
        double t0 = coarse.isEmpty() ? 0 : coarse.get(0)[0], t1 = coarse.isEmpty() ? 0 : coarse.get(coarse.size() - 1)[0];
        List<double[]> m = new ArrayList<>();
        for (double[] x : w) {
            x[0] = Math.max(x[0], t0); x[1] = Math.min(x[1], t1);
            if (!m.isEmpty() && x[0] <= m.get(m.size() - 1)[1]) m.get(m.size() - 1)[1] = Math.max(m.get(m.size() - 1)[1], x[1]);
            else m.add(x);
        }
        return m;
    }

    // ------------------------------------------------------------------ Horizons

    /** States {t (TDB s since J2000), x, y, z (km), vx, vy, vz (km/s)} relative to {@code c}, ecliptic J2000. */
    static List<double[]> fetch(String id, Centre c, String start, String stop, String step) throws Exception {
        // Keep every request well under Horizons' output limit by splitting long spans.
        double t0 = parseTime(start), t1 = parseTime(stop), dt = stepSeconds(step);
        double maxSpan = 40_000 * dt;
        List<double[]> all = new ArrayList<>();
        for (double a = t0; a < t1; a += maxSpan) {
            double b = Math.min(t1, a + maxSpan);
            all.addAll(fetchOnce(id, c, jdString(a), jdString(b), step));
        }
        all.sort(Comparator.comparingDouble(s -> s[0]));
        return all;
    }

    static List<double[]> fetchOnce(String id, Centre c, String start, String stop, String step) throws Exception {
        String q = "format=text&MAKE_EPHEM=YES&OBJ_DATA=NO&EPHEM_TYPE=VECTORS&VEC_TABLE=2&CSV_FORMAT=YES"
                + "&REF_PLANE=ECLIPTIC&REF_SYSTEM=ICRF&OUT_UNITS=KM-S&VEC_CORR=NONE&TIME_TYPE=TDB"
                + "&COMMAND=" + enc("'" + id + "'") + "&CENTER=" + enc("'500@" + c.code() + "'")
                + "&START_TIME=" + enc("'" + start + "'") + "&STOP_TIME=" + enc("'" + stop + "'")
                + "&STEP_SIZE=" + enc("'" + step + "'");
        String body = null;
        for (int attempt = 0; attempt < 4 && body == null; attempt++) {
            try {
                HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(
                        URI.create("https://ssd.jpl.nasa.gov/api/horizons.api?" + q))
                        .timeout(Duration.ofMinutes(4)).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) body = r.body();
                else Thread.sleep(3000);
            } catch (IOException e) { Thread.sleep(3000); }
        }
        if (body == null) throw new IOException("Horizons request failed: " + id + " " + start);
        int a = body.indexOf("$$SOE"), b = body.indexOf("$$EOE");
        if (a < 0 || b < 0) throw new IOException("no ephemeris for " + id + " @" + c.name() + " " + start + ": "
                + body.substring(0, Math.min(800, body.length())));
        List<double[]> out = new ArrayList<>();
        for (String line : body.substring(a + 5, b).split("\n")) {
            String[] f = line.trim().split(",");
            if (f.length < 8) continue;
            double jd = Double.parseDouble(f[0].trim());
            double[] s = new double[7];
            s[0] = (jd - 2451545.0) * 86400.0;
            for (int k = 0; k < 6; k++) s[1 + k] = Double.parseDouble(f[2 + k].trim());
            out.add(s);
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    static void appendSegment(StringBuilder out, Centre c, List<double[]> s) {
        out.append("seg ").append(c.name()).append(' ').append(s.size()).append('\n');
        for (double[] x : s)
            out.append(String.format(Locale.ROOT, "%.1f %.3f %.3f %.3f %.7f %.7f %.7f%n",
                    x[0], x[1], x[2], x[3], x[4], x[5], x[6]));
    }

    /** Horizons accepts "JD 2451545.5" as a time. */
    static String jdString(double tdbSec) {
        return String.format(Locale.ROOT, "JD %.6f", 2451545.0 + tdbSec / 86400.0);
    }

    static double parseTime(String s) {
        if (s.startsWith("JD ")) return (Double.parseDouble(s.substring(3)) - 2451545.0) * 86400.0;
        String[] p = s.trim().split("[ ]+");
        java.time.LocalDate d = java.time.LocalDate.parse(p[0]);
        double sec = 0;
        if (p.length > 1) {
            String[] hm = p[1].split(":");
            sec = Integer.parseInt(hm[0]) * 3600 + Integer.parseInt(hm[1]) * 60;
        }
        long days = d.toEpochDay() - java.time.LocalDate.of(2000, 1, 1).toEpochDay();
        return days * 86400.0 + sec - 43200.0;
    }

    static double stepSeconds(String step) {
        double v = Double.parseDouble(step.replaceAll("[a-z]", ""));
        if (step.endsWith("d")) return v * 86400;
        if (step.endsWith("h")) return v * 3600;
        return v * 60;
    }

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    static double norm(double[] s) { return Math.sqrt(s[1] * s[1] + s[2] * s[2] + s[3] * s[3]); }
    static double sq(double x) { return x * x; }
}
