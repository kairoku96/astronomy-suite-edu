import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * The window's remembered settings (display toggles, UI size, time speed, window bounds), kept in
 * {@code ~/.astronomy-suite/settings.properties} beside the sky view's observer and the AI key.
 * A missing or unreadable file just means defaults; a value that fails to parse is ignored.
 */
final class AppSettings {
    private final Properties p = new Properties();

    static Path file() {
        return Path.of(System.getProperty("user.home", "."), ".astronomy-suite", "settings.properties");
    }

    static AppSettings load() {
        AppSettings s = new AppSettings();
        try {
            Path f = file();
            if (Files.isRegularFile(f)) {
                try (var in = Files.newBufferedReader(f)) { s.p.load(in); }
            }
        } catch (Exception e) {
            System.err.println("Could not read " + file() + ": " + e);
        }
        return s;
    }

    void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            try (var out = Files.newBufferedWriter(f)) { p.store(out, "Astronomy Suite - window settings"); }
        } catch (Exception e) {
            System.err.println("Could not write " + file() + ": " + e);
        }
    }

    boolean has(String key) { return p.containsKey(key); }

    boolean getBool(String key, boolean def) {
        String v = p.getProperty(key);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }

    int getInt(String key, int def) {
        try { return Integer.parseInt(p.getProperty(key, "").trim()); } catch (NumberFormatException e) { return def; }
    }

    double getDouble(String key, double def) {
        try { return Double.parseDouble(p.getProperty(key, "").trim()); } catch (NumberFormatException e) { return def; }
    }

    String get(String key, String def) { return p.getProperty(key, def); }

    void put(String key, Object value) { p.setProperty(key, String.valueOf(value)); }
}
