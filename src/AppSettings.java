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
    private final Path file;
    private final String title;

    private AppSettings(Path file, String title) { this.file = file; this.title = title; }

    static Path file() { return file("settings.properties"); }
    static Path file(String name) {
        return Path.of(System.getProperty("user.home", "."), ".astronomy-suite", name);
    }

    static AppSettings load() { return load(file(), "Astronomy Suite - window settings"); }

    /** A settings file of its own beside the window's (e.g. lesson progress, saved as it changes). */
    static AppSettings load(Path f, String title) {
        AppSettings s = new AppSettings(f, title);
        try {
            if (Files.isRegularFile(f)) {
                try (var in = Files.newBufferedReader(f)) { s.p.load(in); }
            }
        } catch (Exception e) {
            System.err.println("Could not read " + f + ": " + e);
        }
        return s;
    }

    void save() {
        try {
            Files.createDirectories(file.getParent());
            try (var out = Files.newBufferedWriter(file)) { p.store(out, title); }
        } catch (Exception e) {
            System.err.println("Could not write " + file + ": " + e);
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

    void remove(String key) { p.remove(key); }
}
