package tools;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads the semi-major axes of every numbered asteroid between 1.8 and 3.7 AU from the JPL
 * Small-Body Database and writes their histogram, in 0.01 AU bins, to
 * {@code assets/asteroids/main_belt_a.csv}: the real main belt, Kirkwood gaps and all, for the
 * Kirkwood-gap lesson. Numbered asteroids only, so every orbit is well determined.
 *
 * <p>Usage (needs network): {@code java tools.FetchAsteroids}. Run from the project root.
 */
public final class FetchAsteroids {

    static final double A_MIN = 1.8, A_MAX = 3.7, BIN = 0.01;

    public static void main(String[] args) throws IOException, InterruptedException {
        String q = "fields=a&sb-kind=a&sb-ns=n&sb-cdata="
                + URLEncoder.encode("{\"AND\":[\"a|RG|" + A_MIN + "|" + A_MAX + "\"]}", StandardCharsets.UTF_8);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://ssd-api.jpl.nasa.gov/sbdb_query.api?" + q))
                .timeout(Duration.ofMinutes(5)).build();
        String body = http.send(req, HttpResponse.BodyHandlers.ofString()).body();

        int n = (int) Math.round((A_MAX - A_MIN) / BIN);
        int[] count = new int[n];
        int total = 0;
        Matcher m = Pattern.compile("\\[\"([0-9.]+)\"\\]").matcher(body);
        while (m.find()) {
            double a = Double.parseDouble(m.group(1));
            int k = (int) Math.floor((a - A_MIN) / BIN);
            if (k >= 0 && k < n) { count[k]++; total++; }
        }
        if (total == 0) throw new IOException("no asteroids in the reply: " + body.substring(0, Math.min(300, body.length())));

        StringBuilder out = new StringBuilder();
        out.append("# Numbered asteroids by semi-major axis, ").append(A_MIN).append("-").append(A_MAX).append(" AU in ")
           .append(BIN).append(" AU bins\n");
        out.append("# Source: NASA/JPL SBDB Query API, fetched ").append(LocalDate.now()).append("; ").append(total)
           .append(" asteroids (tools.FetchAsteroids)\n");
        out.append("a_from_au,count\n");
        for (int k = 0; k < n; k++) out.append(String.format("%.2f,%d%n", A_MIN + k * BIN, count[k]));
        Path p = Path.of("assets", "asteroids", "main_belt_a.csv");
        Files.createDirectories(p.getParent());
        Files.writeString(p, out.toString());
        System.out.println("Wrote " + p + " (" + total + " asteroids)");
    }
}
