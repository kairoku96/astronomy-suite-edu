import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where the {@code assets/} folder (textures, sky catalogue, missions, lunar series) lives.
 * Looked up once, in order:
 * <ol>
 *   <li>the {@code sss.assets} system property (the packaged macOS app passes
 *       {@code -Dsss.assets=$APPDIR/assets});</li>
 *   <li>{@code ./assets} in the working directory (run2d.ps1, {@code gradlew run}, the tests);</li>
 *   <li>an {@code assets/} folder beside the compiled classes or jar, or a few folders up from it,
 *       so the app still finds it when launched from Finder or a desktop shortcut, where the
 *       working directory is {@code /} or the user's home.</li>
 * </ol>
 * If none exists the plain relative path is kept, so the loaders report the same "can't read
 * assets/..." errors they always have.
 */
final class Assets {
    private Assets() { }

    /** The assets folder (absolute when found). */
    static final Path DIR = locate();

    /** {@code assets/<first>/<more...>}, e.g. {@code path("missions", "juno.trk.gz")}. */
    static Path path(String first, String... more) { return DIR.resolve(Path.of(first, more)); }

    /** {@code assets/<rel>} as a File, e.g. {@code file("textures/earth_diffuse.jpg")}. */
    static File file(String rel) { return path(rel).toFile(); }

    private static Path locate() {
        String prop = System.getProperty("sss.assets");
        if (prop != null && !prop.isBlank()) return Path.of(prop).toAbsolutePath().normalize();
        Path cwd = Path.of("assets");
        if (Files.isDirectory(cwd)) return cwd.toAbsolutePath().normalize();
        try {
            URI code = Assets.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path up = Path.of(code).toAbsolutePath();
            for (int i = 0; i < 5 && up != null; i++, up = up.getParent()) {
                Path a = up.resolve("assets");
                if (Files.isDirectory(a)) return a.normalize();
            }
        } catch (Exception ignored) { }   // no usable code source (e.g. a custom class loader): fall through
        return cwd;
    }
}
