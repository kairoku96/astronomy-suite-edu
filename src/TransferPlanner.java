import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Interplanetary transfer planner, docked beside the 3-D view.
 *
 * <p>Pick two bodies and a departure date: the planner takes both planets' states from the
 * sim's own N-body ephemeris (the departure planet at departure, the target at arrival),
 * solves Lambert's problem around the Sun for the conic that joins them in the chosen time
 * of flight ({@link Lambert}), and reports the hyperbolic excess speeds v∞ at each end, the
 * launch energy C3 = v∞², and the Δv to leave a low circular parking orbit and to be captured
 * into one at the target ({@code Δv = √(v∞² + 2μ/r) − √(μ/r)}, the Oberth-effect formula).
 * The transfer arc — the solved conic, propagated exactly — is drawn in the scene with the two
 * planets where they will be, and a virtual craft rides it when the clock is inside the window.
 *
 * <p>The porkchop plot sweeps a grid of departure and arrival dates and colours each cell by
 * C3 (or total Δv), with contours; click a cell to load that transfer.
 */
final class TransferPlanner extends JPanel {

    static final double AU_KM = 149597870.7, DAY = 86400.0;

    /** Bodies the planner can use: index into the sim's planet table, name, radius and GM (km, km³/s²). */
    static final String[] NAMES = {"Mercury", "Venus", "Earth", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune", "Pluto", "Ceres"};
    static final double[] RADIUS = {2439.7, 6051.8, 6378.137, 3396.2, 71492, 60268, 25559, 24764, 1188.3, 469.7};
    static final double[] MU = {22031.87, 324858.59, 398600.44, 42828.38, 1.26686534e8, 3.7931187e7, 5.793939e6, 6.836529e6, 869.6, 62.63};

    /** Parking-orbit radius (km) used for the Δv figures: 200 km up, or 1.1 radii for giants. */
    static double parkingRadius(int i) { return RADIUS[i] > 20000 ? 1.1 * RADIUS[i] : RADIUS[i] + 200; }

    /** One solved transfer. Positions/velocities heliocentric ecliptic J2000 in AU and AU/day. */
    static final class Transfer {
        int from, to;
        double depSim, tofDays;
        double[] r1, v1, r2, v2, vp1, vp2;
        double vinfDep, vinfArr, c3, dvDep, dvArr;
        double arrSim() { return depSim + tofDays * DAY; }
        double dvTotal() { return dvDep + dvArr; }
    }

    /** Heliocentric state (AU, AU/day) of planet i at UTC sim time. */
    static double[] planet(int i, double sim) { return SolarSystemSimulation.planetStateAU(i, sim); }

    /** Solve the transfer, or null if Lambert has no single-revolution solution. */
    static Transfer solve(int from, int to, double depSim, double tofDays) {
        return solve(from, to, depSim, tofDays, planet(from, depSim), planet(to, depSim + tofDays * DAY));
    }

    static Transfer solve(int from, int to, double depSim, double tofDays, double[] p1, double[] p2) {
        double[] r1 = {p1[0], p1[1], p1[2]}, r2 = {p2[0], p2[1], p2[2]};
        double[] v = Lambert.solve(r1, r2, tofDays, NBodyEphemeris.GM_SUN, new double[]{0, 0, 1});
        if (v == null || !Double.isFinite(v[0])) return null;
        Transfer T = new Transfer();
        T.from = from; T.to = to; T.depSim = depSim; T.tofDays = tofDays;
        T.r1 = r1; T.r2 = r2;
        T.v1 = new double[]{v[0], v[1], v[2]};
        T.v2 = new double[]{v[3], v[4], v[5]};
        T.vp1 = new double[]{p1[3], p1[4], p1[5]};
        T.vp2 = new double[]{p2[3], p2[4], p2[5]};
        double k = AU_KM / DAY;                                   // AU/day → km/s
        T.vinfDep = dist(T.v1, T.vp1) * k;
        T.vinfArr = dist(T.v2, T.vp2) * k;
        T.c3 = T.vinfDep * T.vinfDep;
        T.dvDep = oberth(T.vinfDep, MU[from], parkingRadius(from));
        T.dvArr = oberth(T.vinfArr, MU[to], parkingRadius(to));
        return T;
    }

    /** Δv between a circular orbit of radius r and a hyperbola with excess speed vinf. */
    static double oberth(double vinf, double mu, double r) {
        return Math.sqrt(vinf * vinf + 2 * mu / r) - Math.sqrt(mu / r);
    }

    /** Hohmann time of flight (days) between the two bodies' current semi-major axes. */
    static double hohmannDays(int from, int to, double sim) {
        double a1 = semiMajor(from, sim), a2 = semiMajor(to, sim);
        double a = 0.5 * (a1 + a2);
        return Math.PI * Math.sqrt(a * a * a / NBodyEphemeris.GM_SUN);
    }

    static double semiMajor(int i, double sim) {
        double[] s = planet(i, sim);
        return NBodyEphemeris.elements(NBodyEphemeris.GM_SUN, s[0], s[1], s[2], s[3], s[4], s[5])[0];
    }

    /**
     * Porkchop grid: C3 (km²/s²) and arrival v∞ (km/s) for departures dep0..dep1 (nx cells) and
     * arrivals arr0..arr1 (ny cells), UTC sim seconds. NaN where arrival ≤ departure or no solution.
     */
    static double[][][] porkchop(int from, int to, double dep0, double dep1, int nx, double arr0, double arr1, int ny) {
        double[][] pd = new double[nx][], pa = new double[ny][];
        for (int i = 0; i < nx; i++) pd[i] = planet(from, dep0 + (dep1 - dep0) * i / (nx - 1));
        for (int j = 0; j < ny; j++) pa[j] = planet(to, arr0 + (arr1 - arr0) * j / (ny - 1));
        double[][] c3 = new double[nx][ny], va = new double[nx][ny];
        for (int i = 0; i < nx; i++) {
            double d = dep0 + (dep1 - dep0) * i / (nx - 1);
            for (int j = 0; j < ny; j++) {
                double a = arr0 + (arr1 - arr0) * j / (ny - 1);
                double tof = (a - d) / DAY;
                c3[i][j] = va[i][j] = Double.NaN;
                if (tof < 10) continue;
                Transfer T = solve(from, to, d, tof, pd[i], pa[j]);
                if (T == null) continue;
                c3[i][j] = T.c3;
                va[i][j] = T.vinfArr;
            }
        }
        return new double[][][]{c3, va};
    }

    static double dist(double[] a, double[] b) {
        return Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]));
    }

    // ================================================================== UI

    private final MissionPlayer.Host host;
    private final JComboBox<String> fromBox = new JComboBox<>(NAMES), toBox = new JComboBox<>(NAMES);
    private final JTextField depField = new JTextField(10);
    private final JSpinner tofSpin = new JSpinner(new SpinnerNumberModel(200.0, 10.0, 30000.0, 5.0));
    private final JLabel result = new JLabel();
    private final JComboBox<String> metric = new JComboBox<>(new String[]{"C3 at departure", "Total Δv (LEO → low orbit)"});
    private final PorkchopView pork = new PorkchopView();
    private final JCheckBox show3d = new JCheckBox("Show transfer in 3-D", true);
    private Transfer current;

    TransferPlanner(MissionPlayer.Host host, Runnable onClose) {
        this.host = host;
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(360, 10));
        setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, new Color(60, 70, 100)));
        JPanel col = new WidthTrackingPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        JPanel head = new JPanel(new BorderLayout());
        JLabel title = new JLabel("Transfer planner");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
        head.add(title, BorderLayout.WEST);
        JButton close = new JButton("✕");
        close.addActionListener(_ -> onClose.run());
        head.add(close, BorderLayout.EAST);
        head.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        col.add(head);
        col.add(note("Lambert's problem between two bodies' real positions (from the sim's N-body ephemeris)."));

        fromBox.setSelectedItem("Earth");
        toBox.setSelectedItem("Mars");
        col.add(row("From", fromBox));
        col.add(row("To", toBox));
        JButton simDate = new JButton("Sim date");
        simDate.addActionListener(_ -> depField.setText(dateOf(host.simTime()).toString()));
        JPanel dep = new JPanel(new BorderLayout(4, 0));
        dep.add(depField, BorderLayout.CENTER);
        dep.add(simDate, BorderLayout.EAST);
        col.add(row("Departure", dep));
        JButton hoh = new JButton("Hohmann");
        hoh.setToolTipText("Set the time of flight to the Hohmann half-orbit between the two orbits");
        hoh.addActionListener(_ -> tofSpin.setValue((double) Math.round(hohmannDays(fromBox.getSelectedIndex(), toBox.getSelectedIndex(), host.simTime()))));
        JPanel tof = new JPanel(new BorderLayout(4, 0));
        tof.add(tofSpin, BorderLayout.CENTER);
        tof.add(hoh, BorderLayout.EAST);
        col.add(row("Flight (days)", tof));

        JPanel buttons = new JPanel(new GridLayout(1, 2, 6, 0));
        JButton solveB = new JButton("Solve transfer");
        solveB.addActionListener(_ -> solveFromFields());
        JButton porkB = new JButton("Porkchop plot");
        porkB.addActionListener(_ -> computePorkchop());
        buttons.add(solveB);
        buttons.add(porkB);
        buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        col.add(Box.createVerticalStrut(6));
        col.add(buttons);
        col.add(Box.createVerticalStrut(8));
        result.setVerticalAlignment(SwingConstants.TOP);
        result.setMaximumSize(new Dimension(Integer.MAX_VALUE, 160));
        result.setAlignmentX(LEFT_ALIGNMENT);
        col.add(result);
        col.add(Box.createVerticalStrut(6));
        col.add(row("Colour by", metric));
        metric.addActionListener(_ -> pork.repaint());
        pork.setAlignmentX(LEFT_ALIGNMENT);
        col.add(pork);
        col.add(note("Click the plot to pick a departure / arrival pair. Contours: C3 = 10, 15, 20, 30, 50, 80 km²/s²."));
        JButton fly = new JButton("Fly this transfer");
        fly.addActionListener(_ -> fly());
        show3d.addActionListener(_ -> host.missionEnded());
        JPanel bottom = new JPanel(new GridLayout(1, 2, 6, 0));
        bottom.add(fly);
        bottom.add(show3d);
        bottom.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        bottom.setAlignmentX(LEFT_ALIGNMENT);
        col.add(Box.createVerticalStrut(6));
        col.add(bottom);
        for (Component c : col.getComponents()) if (c instanceof JComponent j) j.setAlignmentX(LEFT_ALIGNMENT);
        JScrollPane sc = new JScrollPane(col, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sc.setBorder(null);
        sc.getVerticalScrollBar().setUnitIncrement(16);
        add(sc, BorderLayout.CENTER);
    }

    /** A column that takes the viewport's width (so rows wrap to it) and its own natural height. */
    static final class WidthTrackingPanel extends JPanel implements Scrollable {
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 16; }
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return r.height; }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }

    private static JComponent row(String label, JComponent c) {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        JLabel l = new JLabel(label);
        l.setPreferredSize(new Dimension(86, 24));
        p.add(l, BorderLayout.WEST);
        p.add(c, BorderLayout.CENTER);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        p.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        return p;
    }

    private static JComponent note(String text) {
        JLabel l = new JLabel("<html><div style='width:255px;color:#9aa8c8'>" + text + "</div></html>");
        l.setFont(l.getFont().deriveFont(11f));
        l.setBorder(BorderFactory.createEmptyBorder(2, 0, 6, 0));
        return l;
    }

    /** Called when the panel is opened: seed the fields from the sim's date. */
    void open() {
        if (depField.getText().isBlank()) depField.setText(dateOf(host.simTime()).toString());
        if (current == null) {
            tofSpin.setValue((double) Math.round(hohmannDays(fromBox.getSelectedIndex(), toBox.getSelectedIndex(), host.simTime())));
        }
    }

    static LocalDate dateOf(double sim) {
        return java.time.LocalDateTime.of(2000, 1, 1, 12, 0).plusSeconds((long) sim).toLocalDate();
    }

    static double simOf(LocalDate d) {
        return java.time.Duration.between(java.time.LocalDateTime.of(2000, 1, 1, 12, 0), d.atStartOfDay()).getSeconds();
    }

    private void solveFromFields() {
        int a = fromBox.getSelectedIndex(), b = toBox.getSelectedIndex();
        if (a == b) { result.setText("<html>Pick two different bodies.</html>"); return; }
        double dep;
        try { dep = simOf(LocalDate.parse(depField.getText().trim())); }
        catch (DateTimeParseException e) { result.setText("<html>Departure must be a date like 2026-11-05.</html>"); return; }
        setCurrent(solve(a, b, dep, ((Number) tofSpin.getValue()).doubleValue()));
    }

    private void setCurrent(Transfer T) {
        current = T;
        if (T == null) { result.setText("<html>No single-revolution transfer for that geometry.</html>"); host.missionEnded(); return; }
        depField.setText(dateOf(T.depSim).toString());
        tofSpin.setValue((double) Math.round(T.tofDays));
        String a = NAMES[T.from], b = NAMES[T.to];
        result.setText(String.format("<html><table cellpadding=1>"
                + "<tr><td>Depart %s</td><td><b>%s</b></td></tr>"
                + "<tr><td>Arrive %s</td><td><b>%s</b> (%.0f d)</td></tr>"
                + "<tr><td>Departure v∞</td><td><b>%.2f km/s</b>  C3 = %.1f km²/s²</td></tr>"
                + "<tr><td>Arrival v∞</td><td><b>%.2f km/s</b></td></tr>"
                + "<tr><td>Δv from %s orbit</td><td>%.2f km/s</td></tr>"
                + "<tr><td>Δv into %s orbit</td><td>%.2f km/s</td></tr>"
                + "<tr><td>Total Δv</td><td><b>%.2f km/s</b></td></tr></table></html>",
                a, dateOf(T.depSim), b, dateOf(T.arrSim()), T.tofDays, T.vinfDep, T.c3, T.vinfArr,
                a, T.dvDep, b, T.dvArr, T.dvTotal()));
        pork.repaint();
        host.missionEnded();          // repaint the scene
    }

    private void computePorkchop() {
        int a = fromBox.getSelectedIndex(), b = toBox.getSelectedIndex();
        if (a == b) return;
        double dep;
        try { dep = simOf(LocalDate.parse(depField.getText().trim())); }
        catch (DateTimeParseException e) { dep = host.simTime(); }
        double hoh = hohmannDays(a, b, dep);
        // Departure window: ± half a synodic period (capped) around the chosen date.
        double P1 = 2 * Math.PI * Math.sqrt(Math.pow(semiMajor(a, dep), 3) / NBodyEphemeris.GM_SUN);
        double P2 = 2 * Math.PI * Math.sqrt(Math.pow(semiMajor(b, dep), 3) / NBodyEphemeris.GM_SUN);
        double syn = Math.abs(1 / (1 / P1 - 1 / P2));
        double half = Math.min(0.25 * syn, 200) * DAY;
        double d0 = dep - half, d1 = dep + half;
        double a0 = d0 + 0.5 * hoh * DAY, a1 = d1 + 1.4 * hoh * DAY;
        final double fd0 = d0, fd1 = d1, fa0 = a0, fa1 = a1;
        pork.busy = true;
        pork.repaint();
        new SwingWorker<double[][][], Void>() {
            protected double[][][] doInBackground() { return porkchop(a, b, fd0, fd1, 140, fa0, fa1, 120); }
            protected void done() {
                try {
                    pork.set(get(), fd0, fd1, fa0, fa1, a, b);
                    // Load the cheapest cell.
                    double best = Double.MAX_VALUE; int bi = -1, bj = -1;
                    double[][] c3 = pork.data[0];
                    for (int i = 0; i < c3.length; i++)
                        for (int j = 0; j < c3[0].length; j++)
                            if (c3[i][j] < best) { best = c3[i][j]; bi = i; bj = j; }
                    if (bi >= 0) pork.pick(bi, bj);
                } catch (Exception e) {
                    result.setText("<html>Porkchop failed: " + e.getMessage() + "</html>");
                }
                pork.busy = false;
                pork.repaint();
            }
        }.execute();
    }

    /** Jump to departure and run the clock so the flight lasts about twenty seconds. */
    private void fly() {
        if (current == null) solveFromFields();
        if (current == null) return;
        host.jumpTo(current.depSim);
        host.setTimeScale(current.tofDays * DAY / 20.0);
        host.setPaused(false);
        show3d.setSelected(true);
    }

    // ------------------------------------------------------------------ porkchop component

    private final class PorkchopView extends JComponent {
        double[][][] data;
        double d0, d1, a0, a1;
        int from, to;
        boolean busy;
        int si = -1, sj = -1;
        private BufferedImage img;
        private String imgKey = "";

        PorkchopView() {
            setPreferredSize(new Dimension(330, 300));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 300));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    if (data == null) return;
                    Rectangle r = plotRect();
                    if (!r.contains(e.getPoint())) return;
                    int i = (int) ((e.getX() - r.x) / (double) r.width * data[0].length);
                    int j = (int) ((r.y + r.height - e.getY()) / (double) r.height * data[0][0].length);
                    pick(Math.max(0, Math.min(data[0].length - 1, i)), Math.max(0, Math.min(data[0][0].length - 1, j)));
                }
            });
        }

        void set(double[][][] d, double d0, double d1, double a0, double a1, int from, int to) {
            data = d; this.d0 = d0; this.d1 = d1; this.a0 = a0; this.a1 = a1; this.from = from; this.to = to;
            imgKey = "";
        }

        void pick(int i, int j) {
            si = i; sj = j;
            double dep = d0 + (d1 - d0) * i / (data[0].length - 1);
            double arr = a0 + (a1 - a0) * j / (data[0][0].length - 1);
            setCurrent(solve(from, to, dep, (arr - dep) / DAY));
        }

        Rectangle plotRect() { return new Rectangle(44, 8, getWidth() - 54, getHeight() - 44); }

        double value(int i, int j) {
            double c3 = data[0][i][j];
            if (metric.getSelectedIndex() == 0) return c3;
            double vinf = Math.sqrt(c3);
            return oberth(vinf, MU[from], parkingRadius(from)) + oberth(data[1][i][j], MU[to], parkingRadius(to));
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(12, 15, 26));
            g2.fillRect(0, 0, getWidth(), getHeight());
            Rectangle r = plotRect();
            if (data == null) {
                g2.setColor(new Color(150, 165, 195));
                g2.drawString(busy ? "Computing…" : "Porkchop plot appears here.", r.x + 10, r.y + 20);
                return;
            }
            int nx = data[0].length, ny = data[0][0].length;
            String key = metric.getSelectedIndex() + ":" + nx + ":" + ny + ":" + System.identityHashCode(data);
            if (!key.equals(imgKey)) {
                imgKey = key;
                img = new BufferedImage(nx, ny, BufferedImage.TYPE_INT_ARGB);
                double lo = Double.MAX_VALUE;
                for (int i = 0; i < nx; i++) for (int j = 0; j < ny; j++) { double v = value(i, j); if (v < lo) lo = v; }
                for (int i = 0; i < nx; i++)
                    for (int j = 0; j < ny; j++) {
                        double v = value(i, j);
                        int argb;
                        if (!Double.isFinite(v)) argb = 0xFF0C0F1A;
                        else {
                            double u = Math.log(v / lo) / Math.log(12.0);       // 0 at the minimum, 1 at 12× it
                            argb = ramp(Math.max(0, Math.min(1, u)));
                        }
                        img.setRGB(i, ny - 1 - j, argb);
                    }
            }
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(img, r.x, r.y, r.width, r.height, null);
            // C3 contours by marching squares on the C3 grid.
            g2.setStroke(new BasicStroke(1f));
            for (double level : new double[]{10, 15, 20, 30, 50, 80}) {
                g2.setColor(new Color(255, 255, 255, 120));
                Path2D.Double p = new Path2D.Double();
                for (int i = 0; i + 1 < nx; i++)
                    for (int j = 0; j + 1 < ny; j++) contourCell(p, level, i, j, r, nx, ny);
                g2.draw(p);
            }
            // Axes.
            g2.setColor(new Color(150, 165, 195));
            g2.drawRect(r.x, r.y, r.width, r.height);
            Font f = g2.getFont().deriveFont(10f);
            g2.setFont(f);
            for (int k = 0; k <= 3; k++) {
                double d = d0 + (d1 - d0) * k / 3;
                String s = dateOf(d).toString();
                int x = r.x + r.width * k / 3;
                g2.drawString(s, Math.min(Math.max(0, x - 28), getWidth() - 56), r.y + r.height + 13);
                double a = a0 + (a1 - a0) * k / 3;
                int y = r.y + r.height - r.height * k / 3;
                Graphics2D gr = (Graphics2D) g2.create();
                gr.rotate(-Math.PI / 2, 10, y);
                gr.drawString(dateOf(a).toString().substring(0, 7), 10 - 16, y + 4);
                gr.dispose();
            }
            g2.drawString("departure →", r.x + r.width - 62, r.y + r.height + 27);
            g2.drawString("arrival ↑", r.x + 4, r.y + 12);
            if (si >= 0) {
                double x = r.x + (si + 0.5) * r.width / nx, y = r.y + r.height - (sj + 0.5) * r.height / ny;
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(1.6f));
                g2.draw(new Ellipse2D.Double(x - 5, y - 5, 10, 10));
            }
        }

        private void contourCell(Path2D.Double p, double L, int i, int j, Rectangle r, int nx, int ny) {
            double[][] c = data[0];
            double v00 = c[i][j], v10 = c[i + 1][j], v01 = c[i][j + 1], v11 = c[i + 1][j + 1];
            if (!(Double.isFinite(v00) && Double.isFinite(v10) && Double.isFinite(v01) && Double.isFinite(v11))) return;
            double[][] pts = new double[4][];
            int n = 0;
            if ((v00 < L) != (v10 < L)) pts[n++] = new double[]{i + (L - v00) / (v10 - v00), j};
            if ((v10 < L) != (v11 < L)) pts[n++] = new double[]{i + 1, j + (L - v10) / (v11 - v10)};
            if ((v01 < L) != (v11 < L)) pts[n++] = new double[]{i + (L - v01) / (v11 - v01), j + 1};
            if ((v00 < L) != (v01 < L)) pts[n++] = new double[]{i, j + (L - v00) / (v01 - v00)};
            for (int k = 0; k + 1 < n; k += 2) {
                p.moveTo(r.x + (pts[k][0] + 0.5) * r.width / nx, r.y + r.height - (pts[k][1] + 0.5) * r.height / ny);
                p.lineTo(r.x + (pts[k + 1][0] + 0.5) * r.width / nx, r.y + r.height - (pts[k + 1][1] + 0.5) * r.height / ny);
            }
        }

        /** Sequential ramp: deep violet (cheap) through teal to pale yellow (expensive). */
        private int ramp(double u) {
            double[][] stops = {{0.10, 0.85, 0.55}, {0.13, 0.55, 0.75}, {0.28, 0.30, 0.65}, {0.35, 0.12, 0.35}, {0.10, 0.06, 0.12}};
            double x = u * (stops.length - 1);
            int k = Math.min(stops.length - 2, (int) x);
            double t = x - k;
            int rr = (int) (255 * (stops[k][0] + t * (stops[k + 1][0] - stops[k][0])));
            int gg = (int) (255 * (stops[k][1] + t * (stops[k + 1][1] - stops[k][1])));
            int bb = (int) (255 * (stops[k][2] + t * (stops[k + 1][2] - stops[k][2])));
            return 0xFF000000 | (Math.min(255, rr) << 16) | (Math.min(255, gg) << 8) | Math.min(255, bb);
        }
    }

    // ------------------------------------------------------------------ scene drawing

    /** The transfer arc, both planets at departure/arrival, and the virtual craft. */
    void paintScene(Graphics2D g2) {
        Transfer T = current;
        if (T == null || !show3d.isSelected() || !isVisibleOrRemembered()) return;
        double[] sun = host.centreWorld("Sun");
        double[] o = new double[2];
        double m = Astro.AU_M;
        Path2D.Double path = new Path2D.Double();
        boolean pen = false;
        int n = 400;
        double[] s = new double[6];
        for (int k = 0; k <= n; k++) {
            System.arraycopy(T.r1, 0, s, 0, 3);
            System.arraycopy(T.v1, 0, s, 3, 3);
            NBodyEphemeris.drift(NBodyEphemeris.GM_SUN, s, 0, T.tofDays * k / n);
            double[] w = SolarSystemSimulation.eclToWorld(s[0] * m, s[1] * m, s[2] * m);
            if (!host.project(sun[0] + w[0], sun[1] + w[1], sun[2] + w[2], o)) { pen = false; continue; }
            if (!pen) { path.moveTo(o[0], o[1]); pen = true; } else path.lineTo(o[0], o[1]);
        }
        Object aa = g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.setColor(new Color(255, 120, 200, 220));
        g2.draw(path);
        ghost(g2, sun, T.r1, NAMES[T.from] + " at departure", new Color(140, 200, 255));
        ghost(g2, sun, T.r2, NAMES[T.to] + " at arrival", new Color(255, 170, 120));
        double now = host.simTime();
        if (now >= T.depSim && now <= T.arrSim()) {
            System.arraycopy(T.r1, 0, s, 0, 3);
            System.arraycopy(T.v1, 0, s, 3, 3);
            NBodyEphemeris.drift(NBodyEphemeris.GM_SUN, s, 0, (now - T.depSim) / DAY);
            double[] w = SolarSystemSimulation.eclToWorld(s[0] * m, s[1] * m, s[2] * m);
            if (host.project(sun[0] + w[0], sun[1] + w[1], sun[2] + w[2], o)) {
                g2.setColor(new Color(255, 120, 200));
                g2.fill(new Ellipse2D.Double(o[0] - 4, o[1] - 4, 8, 8));
                g2.setColor(Color.WHITE);
                g2.drawString(String.format("Transfer craft  (day %.0f of %.0f)", (now - T.depSim) / DAY, T.tofDays), (float) o[0] + 9, (float) o[1] + 16);
            }
        }
        if (aa != null) g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
    }

    private boolean isVisibleOrRemembered() { return true; }

    private void ghost(Graphics2D g2, double[] sun, double[] rAU, String label, Color c) {
        double m = Astro.AU_M;
        double[] w = SolarSystemSimulation.eclToWorld(rAU[0] * m, rAU[1] * m, rAU[2] * m);
        double[] o = new double[2];
        if (!host.project(sun[0] + w[0], sun[1] + w[1], sun[2] + w[2], o)) return;
        g2.setColor(c);
        g2.setStroke(new BasicStroke(1.5f));
        g2.draw(new Ellipse2D.Double(o[0] - 6, o[1] - 6, 12, 12));
        g2.drawString(label, (float) o[0] + 9, (float) o[1] + 14);
    }

    Transfer current() { return current; }
}
