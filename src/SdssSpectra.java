import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * The real stellar spectra of AST221 Python Project 1: eight SDSS spectra in SDSS's own
 * {@code spec-*.fits} format, kept gzipped in {@code assets/spectra/} (see its README). A minimal
 * FITS binary-table reader takes the coadded spectrum (HDU 1: loglam, flux, ivar) and the
 * pipeline's stellar-template match (HDU 2: the ELODIE_* columns). The name, type and
 * temperature in each file belong to that ELODIE template star, not to the faint star observed.
 */
final class SdssSpectra {

    /**
     * One spectrum. {@code nm}: vacuum wavelength (nm); {@code flux}: F_λ (W m⁻² nm⁻¹);
     * {@code ivar}: inverse variance of {@code flux} in the file's own units (10⁻¹⁷ erg s⁻¹ cm⁻² Å⁻¹)⁻².
     */
    record Spectrum(String file, String template, String spType, double teff, double logg, double feh, double bv,
                    int plate, int mjd, int fiber, double ra, double dec,
                    double[] nm, double[] flux, double[] ivar) {
        /** "HD165341" -> "HD 165341". */
        String name() { return template.replaceFirst("^HD0*", "HD "); }
    }

    /** 10⁻¹⁷ erg s⁻¹ cm⁻² Å⁻¹ in W m⁻² nm⁻¹ (10⁻⁷ W × 10⁴ m⁻² × 10 nm⁻¹). */
    static final double FLUX_UNIT = 1e-19;

    private static List<Spectrum> shared;

    /** All spectra in {@code assets/spectra}, hottest template first; empty if they can't be read. */
    static synchronized List<Spectrum> all() {
        if (shared == null) shared = load(Path.of("assets/spectra"));
        return shared;
    }

    static List<Spectrum> load(Path dir) {
        List<Spectrum> out = new ArrayList<>();
        try (var files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.getFileName().toString().matches("spec-.*\\.fits(\\.gz)?")).sorted().toList()) {
                try { out.add(read(f)); } catch (IOException | RuntimeException ex) { System.err.println("spectrum " + f + ": " + ex); }
            }
        } catch (IOException ex) {
            System.err.println("no stellar spectra in " + dir + ": " + ex);
        }
        out.sort(Comparator.comparingDouble(Spectrum::teff).reversed());
        return List.copyOf(out);
    }

    static Spectrum read(Path f) throws IOException {
        byte[] bytes;
        try (InputStream in = f.toString().endsWith(".gz") ? new GZIPInputStream(Files.newInputStream(f)) : Files.newInputStream(f)) {
            bytes = in.readAllBytes();
        }
        List<Table> hdus = tables(bytes);
        if (hdus.size() < 3) throw new IOException("expected a primary HDU and two tables, found " + hdus.size() + " HDUs");
        Table coadd = hdus.get(1), obj = hdus.get(2);
        int n = coadd.rows;
        double[] nm = new double[n], flux = new double[n], ivar = new double[n];
        for (int i = 0; i < n; i++) {
            nm[i] = Math.pow(10, coadd.num("loglam", i)) / 10;            // Å -> nm
            flux[i] = coadd.num("flux", i) * FLUX_UNIT;
            ivar[i] = coadd.num("ivar", i);
        }
        String name = f.getFileName().toString().replaceFirst("\\.fits(\\.gz)?$", "");
        return new Spectrum(name, obj.str("ELODIE_OBJECT", 0), obj.str("ELODIE_SPTYPE", 0), obj.num("ELODIE_TEFF", 0),
                obj.num("ELODIE_LOGG", 0), obj.num("ELODIE_FEH", 0), obj.num("ELODIE_BV", 0),
                (int) obj.num("PLATE", 0), (int) obj.num("MJD", 0), (int) obj.num("FIBERID", 0),
                obj.num("PLUG_RA", 0), obj.num("PLUG_DEC", 0), nm, flux, ivar);
    }

    // ------------------------------------------------------------------ FITS

    private static final int BLOCK = 2880, CARD = 80;

    /** One HDU: its header keywords and, for a BINTABLE, its rows (big-endian, fixed-width). */
    static final class Table {
        final Map<String, String> header = new HashMap<>();
        final Map<String, int[]> cols = new HashMap<>();        // upper-case name -> {byte offset, type char, repeat}
        ByteBuffer data;
        int rowBytes, rows;

        double num(String col, int row) {
            int[] c = column(col);
            int at = row * rowBytes + c[0];
            return switch ((char) c[1]) {
                case 'E' -> data.getFloat(at);
                case 'D' -> data.getDouble(at);
                case 'J' -> data.getInt(at);
                case 'I' -> data.getShort(at);
                case 'K' -> data.getLong(at);
                case 'B' -> data.get(at) & 0xff;
                default -> throw new IllegalArgumentException(col + " is not numeric (TFORM " + (char) c[1] + ")");
            };
        }

        String str(String col, int row) {
            int[] c = column(col);
            if (c[1] != 'A') throw new IllegalArgumentException(col + " is not a string column");
            byte[] b = new byte[c[2]];
            data.get(row * rowBytes + c[0], b);
            int len = 0;
            while (len < b.length && b[len] != 0) len++;
            return new String(b, 0, len, StandardCharsets.US_ASCII).strip();
        }

        private int[] column(String col) {
            int[] c = cols.get(col.toUpperCase());
            if (c == null) throw new IllegalArgumentException("no column " + col);
            return c;
        }
    }

    /** Every HDU of a FITS file, in order; the primary (and any image) has no columns. */
    static List<Table> tables(byte[] fits) throws IOException {
        List<Table> out = new ArrayList<>();
        int pos = 0;
        while (pos + BLOCK <= fits.length) {
            Table t = new Table();
            boolean end = false;
            while (!end) {
                if (pos + BLOCK > fits.length) throw new IOException("header runs past the end of the file");
                for (int k = 0; k < BLOCK / CARD && !end; k++) {
                    String card = new String(fits, pos + k * CARD, CARD, StandardCharsets.US_ASCII);
                    String key = card.substring(0, 8).strip();
                    if (key.equals("END")) end = true;
                    else if (card.startsWith("= ", 8)) t.header.put(key, value(card.substring(10)));
                }
                pos += BLOCK;
            }
            long dataBytes = dataBytes(t.header);
            if (pos + dataBytes > fits.length) throw new IOException("data runs past the end of the file");
            if ("BINTABLE".equals(t.header.get("XTENSION"))) {
                t.rowBytes = intKey(t.header, "NAXIS1");
                t.rows = intKey(t.header, "NAXIS2");
                t.data = ByteBuffer.wrap(fits, pos, (int) dataBytes).slice();     // big-endian, as FITS is
                int fields = intKey(t.header, "TFIELDS"), offset = 0;
                for (int i = 1; i <= fields; i++) {
                    String form = t.header.get("TFORM" + i);
                    int j = 0;
                    while (j < form.length() && Character.isDigit(form.charAt(j))) j++;
                    int repeat = j == 0 ? 1 : Integer.parseInt(form.substring(0, j));
                    char type = form.charAt(j);
                    String name = t.header.getOrDefault("TTYPE" + i, "COL" + i).toUpperCase();
                    t.cols.put(name, new int[]{offset, type, repeat});
                    offset += fieldBytes(type, repeat);
                }
                if (offset != t.rowBytes) throw new IOException("TFORMs add up to " + offset + " bytes, NAXIS1 says " + t.rowBytes);
            }
            out.add(t);
            pos += (int) ((dataBytes + BLOCK - 1) / BLOCK * BLOCK);
        }
        return out;
    }

    /** A card's value: a quoted string ('' is an escaped quote) or the text before any comment. */
    private static String value(String v) {
        v = v.stripLeading();
        if (v.startsWith("'")) {
            StringBuilder b = new StringBuilder();
            for (int i = 1; i < v.length(); i++) {
                char ch = v.charAt(i);
                if (ch == '\'') {
                    if (i + 1 < v.length() && v.charAt(i + 1) == '\'') { b.append('\''); i++; } else break;
                } else b.append(ch);
            }
            return b.toString().stripTrailing();
        }
        int slash = v.indexOf('/');
        return (slash >= 0 ? v.substring(0, slash) : v).strip();
    }

    private static int intKey(Map<String, String> h, String key) {
        String v = h.get(key);
        if (v == null) throw new IllegalArgumentException("missing " + key);
        return Integer.parseInt(v);
    }

    /** Size of an HDU's data: |BITPIX|/8 × GCOUNT × (PCOUNT + NAXIS1 × NAXIS2 × …). */
    private static long dataBytes(Map<String, String> h) {
        int naxis = intKey(h, "NAXIS");
        if (naxis == 0) return 0;
        long n = 1;
        for (int i = 1; i <= naxis; i++) n *= intKey(h, "NAXIS" + i);
        long pcount = h.containsKey("PCOUNT") ? Long.parseLong(h.get("PCOUNT")) : 0;
        long gcount = h.containsKey("GCOUNT") ? Long.parseLong(h.get("GCOUNT")) : 1;
        return Math.abs(intKey(h, "BITPIX")) / 8 * gcount * (pcount + n);
    }

    /** Bytes of one table field of {@code repeat} elements of TFORM type {@code type}. */
    private static int fieldBytes(char type, int repeat) {
        return switch (type) {
            case 'L', 'B', 'A' -> repeat;
            case 'X' -> (repeat + 7) / 8;
            case 'I' -> 2 * repeat;
            case 'J', 'E' -> 4 * repeat;
            case 'K', 'D', 'C' -> 8 * repeat;
            case 'M' -> 16 * repeat;
            case 'P' -> 8;
            case 'Q' -> 16;
            default -> throw new IllegalArgumentException("unknown TFORM type " + type);
        };
    }
}
