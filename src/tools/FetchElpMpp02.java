package tools;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.time.Duration;
import java.util.zip.GZIPOutputStream;

/**
 * Downloads the ELP/MPP02 lunar theory's series and packs them into the asset the sim reads
 * ({@code assets/moon/elpmpp02.bin.gz}, see {@code MoonEphemeris}).
 *
 * <p>ELP/MPP02 (J. Chapront &amp; G. Francou, A&amp;A 404, 735, 2003) is published by IMCCE as
 * six text files ({@code ELP_MAIN.S1–S3}, {@code ELP_PERT.S1–S3}) of Delaunay-argument
 * multipliers, amplitudes and their derivatives with respect to the fitted constants, on
 * {@code ftp://cyrano-se.obspm.fr/pub/2_lunar_solutions/2_elpmpp02/}. Evaluating them first
 * means combining those into one amplitude and one argument polynomial per term (the setup
 * part of the authors' Fortran). This tool takes the series in that evaluated form from
 * T. Jang's MIT-licensed port, generated from the IMCCE files by a transcription of the
 * Fortran setup with the parameters fitted to lunar laser ranging (ICOR = 0), and pinned to
 * a commit here. {@code MoonEphemeris} is checked against the reference positions the
 * authors publish with the theory ({@code MoonEphemerisTest}), which verifies the series
 * end to end. (IMCCE's FTP server did not answer when this was written; the mirror is what
 * is reachable over HTTPS.)
 *
 * <p>The packed file: the ASCII magic {@code ELPMPP02}, a series count, then per series its
 * coordinate (0 longitude, 1 latitude, 2 distance), its power of t (−1 for the main
 * problem, 0–3 for the perturbations) and its terms, six doubles each: the amplitude
 * (″ or km) and the argument's polynomial coefficients in t (centuries of TDB since J2000),
 * sorted by increasing |amplitude|. Big-endian, gzipped.
 *
 * <p>Usage (needs network): {@code java tools.FetchElpMpp02}, from the project root.
 */
public final class FetchElpMpp02 {

    static final String COMMIT = "bccec6c805ca6f7917ebaa8847f6dcd649b2a54b";
    static final String BASE = "https://raw.githubusercontent.com/twjang/simple-elpmpp02-rs/" + COMMIT + "/src/bins/";
    static final Path OUT = Path.of("assets/moon/elpmpp02.bin.gz");

    /** {coordinate, power of t} of every series that exists; power −1 is the main problem. */
    static final int[][] SERIES = {
        {0, -1}, {1, -1}, {2, -1},
        {0, 0}, {0, 1}, {0, 2}, {0, 3},
        {1, 0}, {1, 1}, {1, 2},
        {2, 0}, {2, 1}, {2, 2}, {2, 3},
    };

    public static void main(String[] args) throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        Files.createDirectories(OUT.getParent());
        int terms = 0;
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                new GZIPOutputStream(Files.newOutputStream(OUT))))) {
            out.writeBytes("ELPMPP02");
            out.writeInt(SERIES.length);
            for (int[] s : SERIES) {
                String name = s[1] < 0 ? "coeff_main_" + s[0] + ".bin" : "coeff_perturb_" + s[0] + "_" + s[1] + ".bin";
                HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(BASE + name))
                        .timeout(Duration.ofSeconds(60)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                if (r.statusCode() != 200) throw new IOException(name + ": HTTP " + r.statusCode());
                byte[] b = r.body();
                if (b.length % 48 != 0) throw new IOException(name + ": " + b.length + " bytes is not whole terms");
                ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
                int n = b.length / 48;
                out.writeByte(s[0]);
                out.writeByte(s[1]);
                out.writeInt(n);
                for (int i = 0; i < 6 * n; i++) out.writeDouble(bb.getDouble());
                terms += n;
                System.out.printf("  %-24s %6d terms%n", name, n);
            }
        }
        System.out.printf("Wrote %s: %d terms, %d bytes%n", OUT, terms, Files.size(OUT));
    }
}
