package astrosim;

import com.formdev.flatlaf.FlatDarkLaf;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.awt.GLJPanel;
import com.jogamp.opengl.util.FPSAnimator;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.awt.event.*;
import java.util.HashMap;
import java.util.Map;

import static astrosim.Bodies.*;

/**
 * Entry point and UI shell: FlatLaf dark theme, GL canvas with click-through
 * HUD overlay, body navigator tree, search, time controls, visual-scale
 * controls, body spawning / vector editing, and the three simulation modes
 * (Sandbox / Ship / Landing) driven by {@link SimulationModeManager}.
 *
 * Keyboard input is a held-key dispatcher (not Swing key bindings) so the
 * flight controller can read continuous WASD/throttle state.
 */
public final class Main {

    private final NBody sim = new NBody();
    private final OrbitCamera cam = new OrbitCamera();
    private final SimState state = new SimState();
    private final ShipController controller = new ShipController();
    private final SimulationModeManager modeMgr = new SimulationModeManager(sim, state, cam, controller);
    private Scene scene;
    private GLJPanel glPanel;
    private HudOverlay overlay;

    private final Map<String, int[]> nameIndex = new HashMap<>(); // name -> {planet, moon, sun}
    private int spawnCounter = 0;

    // toolbar widgets that mirror shared state (guarded sync, see syncTimer)
    private boolean syncingUI = false;
    private JToggleButton pauseBtn;
    private JSlider speedSlider;
    private JLabel speedLabel;
    private JSlider exaggSlider;
    private JCheckBox trueScaleBox;
    private final JToggleButton[] modeButtons = new JToggleButton[3];

    public static void main(String[] args) {
        FlatDarkLaf.setup();
        SwingUtilities.invokeLater(() -> new Main().build());
    }

    private void build() {
        cam.target.set(0, 0, 0);
        cam.distance = 40;

        GLProfile glp = GLProfile.get(GLProfile.GL2);
        GLCapabilities caps = new GLCapabilities(glp);
        caps.setSampleBuffers(true);
        caps.setNumSamples(4);
        glPanel = new GLJPanel(caps);
        scene = new Scene(sim, cam, state, modeMgr);
        glPanel.addGLEventListener(scene);

        overlay = new HudOverlay(scene, cam, state, sim, modeMgr);

        JLayeredPane layered = new JLayeredPane();
        layered.add(glPanel, Integer.valueOf(0));
        layered.add(overlay, Integer.valueOf(1));
        layered.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                glPanel.setBounds(0, 0, layered.getWidth(), layered.getHeight());
                overlay.setBounds(0, 0, layered.getWidth(), layered.getHeight());
            }
        });

        installMouse();
        installKeys();

        JFrame frame = new JFrame("Solar System — N-body Physics Sandbox");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setJMenuBar(buildMenuBar());
        frame.setLayout(new BorderLayout());
        frame.add(buildToolbar(), BorderLayout.NORTH);
        frame.add(buildSidebar(), BorderLayout.WEST);
        frame.add(layered, BorderLayout.CENTER);
        frame.setSize(1380, 840);
        frame.setLocationRelativeTo(null);
        if (Boolean.getBoolean("astrosim.ontop")) frame.setAlwaysOnTop(true);
        frame.setVisible(true);

        FPSAnimator animator = new FPSAnimator(glPanel, 60, true);
        animator.start();
        new Timer(33, e -> overlay.repaint()).start();
        new Timer(250, e -> syncToolbar()).start();
        SwingUtilities.invokeLater(glPanel::requestFocusInWindow);

        // --- optional test/diagnostic hooks (no effect unless the property is set) ---
        String sel = System.getProperty("astrosim.select");
        if (sel != null) new Timer(800, e -> { ((Timer) e.getSource()).stop(); jumpTo(sel); }).start();
        String shot = System.getProperty("astrosim.autoshot");
        int shotMs = Integer.getInteger("astrosim.autoshotMs", 2600);
        if (shot != null) new Timer(shotMs, e -> { ((Timer) e.getSource()).stop(); scene.shotPath = shot; }).start();
        String camDist = System.getProperty("astrosim.camdist");
        if (camDist != null) new Timer(Math.max(300, shotMs - 400), e -> { ((Timer) e.getSource()).stop();
            cam.distance = Double.parseDouble(camDist); }).start();
        String modeProp = System.getProperty("astrosim.mode");
        if (modeProp != null) new Timer(1200, e -> { ((Timer) e.getSource()).stop();
            modeMgr.requestMode(SimulationModeManager.Mode.valueOf(modeProp.toUpperCase())); }).start();
    }

    /* ----------------- interaction ----------------- */

    private int lastX, lastY, pressX, pressY, pressButton;

    private void installMouse() {
        MouseAdapter ma = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                lastX = pressX = e.getX(); lastY = pressY = e.getY();
                pressButton = e.getButton();
                glPanel.requestFocusInWindow();
            }
            @Override public void mouseDragged(MouseEvent e) {
                int dx = e.getX() - lastX, dy = e.getY() - lastY;
                lastX = e.getX(); lastY = e.getY();
                if (SwingUtilities.isLeftMouseButton(e) && !e.isShiftDown())
                    cam.rotate(-dx * 0.006, dy * 0.006);
                else
                    cam.pan(dx, dy);
            }
            @Override public void mouseReleased(MouseEvent e) {
                int moved = Math.abs(e.getX() - pressX) + Math.abs(e.getY() - pressY);
                if (moved < 5 && pressButton == MouseEvent.BUTTON1) handleClick(e.getX(), e.getY());
            }
            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                cam.zoom(e.getWheelRotation() < 0 ? 0.88 : 1 / 0.88);
            }
        };
        glPanel.addMouseListener(ma);
        glPanel.addMouseMotionListener(ma);
        glPanel.addMouseWheelListener(ma);
    }

    private void handleClick(int sx, int sy) {
        if (modeMgr.mode() != SimulationModeManager.Mode.SANDBOX) return; // flight modes own the camera
        int vw = cam.viewportWidth(), vh = cam.viewportHeight();
        if (vw == 0 || vh == 0) return;
        int gx = (int) (sx * (double) vw / glPanel.getWidth());
        int gy = (int) (sy * (double) vh / glPanel.getHeight());
        Vec3[] ray = cam.pickRay(gx, gy);
        if (ray == null) return;
        Picker.Pickable hit = Picker.nearest(ray[0], ray[1], scene.snapshotPickables());
        if (hit == null) { state.followSelected = false; return; }
        if (hit.dynamicBody != null) selectDynamic(hit.dynamicBody);
        else if (hit.planet < 0) selectSun();
        else if (hit.moon < 0) selectPlanet(hit.planet);
        else selectMoon(hit.planet, hit.moon);
    }

    /**
     * Held-key dispatcher. Continuous keys (WASD/throttle/attitude) stream into
     * the {@link ShipController}; discrete hotkeys fire on press. Sandbox-only
     * toggles are suppressed in flight modes where the letters mean thrust.
     */
    private void installKeys() {
        glPanel.setFocusTraversalKeysEnabled(false);
        glPanel.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                int code = e.getKeyCode();
                controller.press(code);
                boolean sandbox = modeMgr.mode() == SimulationModeManager.Mode.SANDBOX;
                switch (code) {
                    case KeyEvent.VK_1 -> modeMgr.requestMode(SimulationModeManager.Mode.SANDBOX);
                    case KeyEvent.VK_2 -> modeMgr.requestMode(SimulationModeManager.Mode.SHIP);
                    case KeyEvent.VK_3 -> modeMgr.requestMode(SimulationModeManager.Mode.LANDING);
                    case KeyEvent.VK_P -> state.paused = !state.paused;
                    case KeyEvent.VK_SPACE -> { if (sandbox) state.paused = !state.paused; }
                    case KeyEvent.VK_O -> { if (sandbox) state.showOrbits = !state.showOrbits; }
                    case KeyEvent.VK_L -> { if (sandbox) state.showLabels = !state.showLabels; }
                    case KeyEvent.VK_M -> { if (sandbox) state.showMoons = !state.showMoons; }
                    case KeyEvent.VK_R -> { if (sandbox) state.showRings = !state.showRings; }
                    case KeyEvent.VK_T -> state.showTrajectory = !state.showTrajectory;
                    case KeyEvent.VK_COMMA -> state.timeScale = Math.max(0.1, state.timeScale / 2);
                    case KeyEvent.VK_PERIOD -> state.timeScale = Math.min(SimState.TIME_SCALE_MAX, state.timeScale * 2);
                    case KeyEvent.VK_BACK_SPACE -> { if (!sandbox) modeMgr.respawn(); }
                    case KeyEvent.VK_ESCAPE -> { if (sandbox) resetView(); }
                    case KeyEvent.VK_F12 -> scene.requestScreenshot();
                }
            }
            @Override public void keyReleased(KeyEvent e) {
                controller.release(e.getKeyCode());
            }
        });
    }

    /* ----------------- selection helpers ----------------- */

    private void focusOn(Vec3 targetAU, double dist) {
        cam.target.set(targetAU);
        cam.distance = Math.max(cam.minDist, Math.min(OrbitCamera.MAX_DIST, dist));
        state.followSelected = true;
    }

    private void selectSun() {
        state.selectSun();
        focusOn(scene.sunWorldAU(), Scale.sunVisualR() * 16);
    }
    private void selectPlanet(int k) {
        state.selectPlanet(k);
        focusOn(scene.planetWorldAU(k), Math.max(Scale.planetVisualR(k) * 12, cam.minDist * 4));
    }
    private void selectMoon(int k, int m) {
        state.selectMoon(k, m);
        focusOn(scene.moonWorldAU(k, m), Math.max(Scale.moonVisualR(k, m) * 16, cam.minDist * 4));
    }
    private void selectDynamic(NBody.Body b) {
        state.selectDynamic(b);
        focusOn(b.r.scale(1.0 / AU), Math.max(Scale.bodyVisualR(b.radiusM) * 14, cam.minDist * 4));
    }
    private void resetView() {
        state.clearSelection();
        cam.target.set(0, 0, 0);
        cam.distance = 40;
        cam.azimuth = Math.toRadians(35);
        cam.elevation = Math.toRadians(22);
    }

    /* ----------------- UI construction ----------------- */

    private JMenuBar buildMenuBar() {
        JMenuBar mb = new JMenuBar();
        JMenu simMenu = new JMenu("Simulation");
        JMenuItem spawn = new JMenuItem("Spawn Body…");
        spawn.addActionListener(e -> showSpawnDialog());
        JMenuItem vectors = new JMenuItem("Edit State Vectors…");
        vectors.addActionListener(e -> showVectorDialog());
        JMenuItem reset = new JMenuItem("Reset View");
        reset.addActionListener(e -> resetView());
        JMenuItem screenshot = new JMenuItem("Screenshot (F12)");
        screenshot.addActionListener(e -> { scene.requestScreenshot(); glPanel.requestFocusInWindow(); });
        JMenuItem record = new JMenuItem("Record GIF (3 s)");
        record.addActionListener(e -> { scene.startRecording(90); glPanel.requestFocusInWindow(); });
        JMenuItem exit = new JMenuItem("Exit");
        exit.addActionListener(e -> System.exit(0));
        simMenu.add(spawn); simMenu.add(vectors); simMenu.addSeparator();
        simMenu.add(reset); simMenu.addSeparator();
        simMenu.add(screenshot); simMenu.add(record); simMenu.addSeparator();
        simMenu.add(exit);

        JMenu modeMenu = new JMenu("Mode");
        JMenuItem m1 = new JMenuItem("Sandbox (1)");
        m1.addActionListener(e -> modeMgr.requestMode(SimulationModeManager.Mode.SANDBOX));
        JMenuItem m2 = new JMenuItem("Ship — Newtonian flight (2)");
        m2.addActionListener(e -> modeMgr.requestMode(SimulationModeManager.Mode.SHIP));
        JMenuItem m3 = new JMenuItem("Landing — surface frame (3)");
        m3.addActionListener(e -> modeMgr.requestMode(SimulationModeManager.Mode.LANDING));
        modeMenu.add(m1); modeMenu.add(m2); modeMenu.add(m3);

        JMenu view = new JMenu("View");
        view.add(toggle("Orbit paths", true, b -> state.showOrbits = b));
        view.add(toggle("Moons", true, b -> state.showMoons = b));
        view.add(toggle("Labels", true, b -> state.showLabels = b));
        view.add(toggle("Saturn rings", true, b -> state.showRings = b));
        view.add(toggle("Starfield", true, b -> state.showStarfield = b));
        view.add(toggle("Predicted trajectory", true, b -> state.showTrajectory = b));

        JMenu help = new JMenu("Help");
        JMenuItem controls = new JMenuItem("Controls");
        controls.addActionListener(e -> JOptionPane.showMessageDialog(glPanel,
                """
                SANDBOX
                  Drag: rotate camera    Right/Shift-drag: pan    Scroll: zoom
                  Click a body: select & follow      Space/P: pause
                  O orbits  L labels  M moons  R rings  T trajectory  Esc reset view

                SHIP (Newtonian flight — press 2)
                  W/S  main engine forward / reverse      A/D  lateral
                  R/F  vertical (ship frame)              Q/E  roll
                  Arrow keys  pitch / yaw
                  Shift / Ctrl  throttle up / down        Z / X  full / cut
                  Space  retro-burn (thrust opposite parent-relative velocity)
                  , / .  time warp halve / double         Backspace  respawn in orbit

                LANDING (auto inside a planet's SOI, or press 3)
                  Same controls; HUD switches to the surface frame.
                  Touch down slower than 5 m/s vertical or the ship is destroyed.
                  Once landed: throttle up and thrust away from the surface to lift off.
                """,
                "Controls", JOptionPane.INFORMATION_MESSAGE));
        help.add(controls);

        mb.add(simMenu); mb.add(modeMenu); mb.add(view); mb.add(help);
        return mb;
    }

    private JCheckBoxMenuItem toggle(String label, boolean init, java.util.function.Consumer<Boolean> set) {
        JCheckBoxMenuItem it = new JCheckBoxMenuItem(label, init);
        it.addActionListener(e -> set.accept(it.isSelected()));
        return it;
    }

    private JComponent buildToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));

        // mode buttons
        ButtonGroup grp = new ButtonGroup();
        String[] names = {"Sandbox", "Ship", "Land"};
        SimulationModeManager.Mode[] modes = SimulationModeManager.Mode.values();
        for (int i = 0; i < 3; i++) {
            final SimulationModeManager.Mode m = modes[i];
            modeButtons[i] = new JToggleButton(names[i], i == 0);
            modeButtons[i].addActionListener(e -> {
                if (!syncingUI) { modeMgr.requestMode(m); glPanel.requestFocusInWindow(); }
            });
            grp.add(modeButtons[i]);
            bar.add(modeButtons[i]);
        }
        bar.add(new JSeparator(SwingConstants.VERTICAL) {{ setPreferredSize(new Dimension(2, 24)); }});

        bar.add(new JLabel("Search:"));
        JTextField search = new JTextField(10);
        search.setToolTipText("Type a planet or moon name, press Enter");
        search.addActionListener(e -> { jumpTo(search.getText().trim()); glPanel.requestFocusInWindow(); });
        bar.add(search);

        pauseBtn = new JToggleButton("Pause");
        pauseBtn.addActionListener(e -> { if (!syncingUI) state.paused = pauseBtn.isSelected(); glPanel.requestFocusInWindow(); });
        bar.add(pauseBtn);

        bar.add(new JLabel("Speed:"));
        speedSlider = new JSlider(0, 1000, scaleToSlider(state.timeScale));
        speedSlider.setPreferredSize(new Dimension(180, 24));
        speedLabel = new JLabel(HudOverlay.formatTimeScale(state.timeScale) + " / s");
        speedLabel.setPreferredSize(new Dimension(96, 24));
        speedSlider.addChangeListener(e -> {
            if (syncingUI) return;
            state.timeScale = sliderToScale(speedSlider.getValue());
            speedLabel.setText(HudOverlay.formatTimeScale(state.timeScale) + " / s");
        });
        bar.add(speedSlider);
        bar.add(speedLabel);

        bar.add(new JSeparator(SwingConstants.VERTICAL) {{ setPreferredSize(new Dimension(2, 24)); }});

        // visual scaling: the gravity math is always true-scale; this only affects drawing
        bar.add(new JLabel("Planet size:"));
        exaggSlider = new JSlider(0, 1000, exaggToSlider(Scale.exaggeration));
        exaggSlider.setPreferredSize(new Dimension(120, 24));
        exaggSlider.setToolTipText("Visual radius exaggeration (does not affect physics)");
        exaggSlider.addChangeListener(e -> {
            if (!syncingUI) Scale.exaggeration = sliderToExagg(exaggSlider.getValue());
        });
        bar.add(exaggSlider);
        trueScaleBox = new JCheckBox("True scale");
        trueScaleBox.setToolTipText("Render bodies at real physical size (flight modes force this on)");
        trueScaleBox.addActionListener(e -> {
            if (!syncingUI && modeMgr.mode() == SimulationModeManager.Mode.SANDBOX)
                Scale.trueScale = trueScaleBox.isSelected();
            glPanel.requestFocusInWindow();
        });
        bar.add(trueScaleBox);

        JButton spawnBtn = new JButton("Spawn Body…");
        spawnBtn.addActionListener(e -> showSpawnDialog());
        bar.add(spawnBtn);
        JButton vecBtn = new JButton("Vectors…");
        vecBtn.setToolTipText("Edit the selected body's position/velocity state vectors");
        vecBtn.addActionListener(e -> showVectorDialog());
        bar.add(vecBtn);

        return bar;
    }

    /** Reflect shared state (mode auto-switches, pause, time warp, scale lock) back into toolbar widgets. */
    private void syncToolbar() {
        syncingUI = true;
        try {
            SimulationModeManager.Mode m = modeMgr.mode();
            modeButtons[m.ordinal()].setSelected(true);
            pauseBtn.setSelected(state.paused);
            boolean sandbox = m == SimulationModeManager.Mode.SANDBOX;
            speedSlider.setEnabled(sandbox);
            if (sandbox) speedSlider.setValue(scaleToSlider(state.timeScale));
            speedLabel.setText(HudOverlay.formatTimeScale(state.timeScale) + " / s");
            exaggSlider.setEnabled(sandbox && !Scale.trueScale);
            trueScaleBox.setEnabled(sandbox);
            trueScaleBox.setSelected(Scale.trueScale);
        } finally {
            syncingUI = false;
        }
    }

    private static int scaleToSlider(double s) {
        double t = Math.log(s / SimState.TIME_SCALE_MIN) / Math.log(SimState.TIME_SCALE_MAX / SimState.TIME_SCALE_MIN);
        return (int) Math.round(1000 * Math.max(0, Math.min(1, t)));
    }
    private static double sliderToScale(int v) {
        double t = v / 1000.0;
        return SimState.TIME_SCALE_MIN * Math.pow(SimState.TIME_SCALE_MAX / SimState.TIME_SCALE_MIN, t);
    }
    private static int exaggToSlider(double e) {
        double t = Math.log(e / Scale.EXAGG_MIN) / Math.log(Scale.EXAGG_MAX / Scale.EXAGG_MIN);
        return (int) Math.round(1000 * Math.max(0, Math.min(1, t)));
    }
    private static double sliderToExagg(int v) {
        double t = v / 1000.0;
        return Scale.EXAGG_MIN * Math.pow(Scale.EXAGG_MAX / Scale.EXAGG_MIN, t);
    }

    /* ----------------- spawn & vector dialogs ----------------- */

    private void showSpawnDialog() {
        JTextField name = new JTextField("Body-" + (++spawnCounter));
        JTextField mass = new JTextField("1.0e22");
        JTextField radius = new JTextField("1000");
        Vec3 t = cam.target;
        JTextField pos = new JTextField(String.format("%.4f, %.4f, %.4f", t.x, t.y, t.z));
        JCheckBox circular = new JCheckBox("Auto circular heliocentric orbit", true);
        JTextField vel = new JTextField("0, 0, 0");
        vel.setEnabled(false);
        circular.addActionListener(e -> vel.setEnabled(!circular.isSelected()));

        JPanel p = new JPanel(new GridLayout(0, 2, 6, 6));
        p.add(new JLabel("Name:"));            p.add(name);
        p.add(new JLabel("Mass (kg):"));       p.add(mass);
        p.add(new JLabel("Radius (km):"));     p.add(radius);
        p.add(new JLabel("Position (AU, x,y,z):")); p.add(pos);
        p.add(circular);                       p.add(new JLabel());
        p.add(new JLabel("Velocity (km/s, x,y,z):")); p.add(vel);

        if (JOptionPane.showConfirmDialog(glPanel, p, "Spawn Celestial Body",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        try {
            double m = Double.parseDouble(mass.getText().trim());
            double radM = Double.parseDouble(radius.getText().trim()) * 1000;
            double[] pAu = parseTriple(pos.getText());
            Vec3 rM = new Vec3(pAu[0] * AU, pAu[1] * AU, pAu[2] * AU);
            // guard: don't spawn inside the Sun
            Vec3 rel = rM.sub(sim.sunPos());
            if (rel.length() < SUN_RADIUS_M * 2) throw new IllegalArgumentException("position is inside the Sun");

            Vec3 vM;
            if (circular.isSelected()) {
                double r = rel.length();
                double vCirc = Math.sqrt(MU_SUN / r);
                Vec3 rhat = rel.normalized();
                Vec3 tangent = new Vec3(0, 1, 0).cross(rhat).normalized();
                if (tangent.lengthSq() < 1e-12) tangent = new Vec3(1, 0, 0);
                vM = sim.sun().v.add(tangent.scale(vCirc));
            } else {
                double[] vk = parseTriple(vel.getText());
                vM = new Vec3(vk[0] * 1000, vk[1] * 1000, vk[2] * 1000);
            }
            Color c = Color.getHSBColor((float) Math.random(), 0.45f, 0.95f);
            NBody.Body b = sim.addBody(name.getText().trim(), m, radM, rM, vM, c);
            selectDynamic(b);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(glPanel, "Invalid input: " + ex.getMessage(),
                    "Spawn failed", JOptionPane.ERROR_MESSAGE);
        }
        glPanel.requestFocusInWindow();
    }

    /** Manual state-vector editor for the selected planet or runtime body. */
    private void showVectorDialog() {
        NBody.Body b;
        if (state.selectedDynamic != null) b = state.selectedDynamic;
        else if (state.selectedPlanet >= 0 && state.selectedMoon < 0) b = sim.planet(state.selectedPlanet);
        else if (state.sunSelected) b = sim.sun();
        else {
            JOptionPane.showMessageDialog(glPanel,
                    "Select a planet, the Sun or a spawned body first (moons are analytic).",
                    "No editable selection", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        JTextField pos = new JTextField(String.format("%.6f, %.6f, %.6f", b.r.x / AU, b.r.y / AU, b.r.z / AU));
        JTextField vel = new JTextField(String.format("%.4f, %.4f, %.4f", b.v.x / 1000, b.v.y / 1000, b.v.z / 1000));
        JPanel p = new JPanel(new GridLayout(0, 2, 6, 6));
        p.add(new JLabel("Position (AU, x,y,z):"));   p.add(pos);
        p.add(new JLabel("Velocity (km/s, x,y,z):")); p.add(vel);

        if (JOptionPane.showConfirmDialog(glPanel, p, "State Vectors — " + b.name,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        try {
            double[] pAu = parseTriple(pos.getText());
            double[] vk = parseTriple(vel.getText());
            b.r.set(pAu[0] * AU, pAu[1] * AU, pAu[2] * AU);
            b.v.set(vk[0] * 1000, vk[1] * 1000, vk[2] * 1000);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(glPanel, "Invalid input: " + ex.getMessage(),
                    "Edit failed", JOptionPane.ERROR_MESSAGE);
        }
        glPanel.requestFocusInWindow();
    }

    private static double[] parseTriple(String s) {
        String[] parts = s.split("[,;\\s]+");
        java.util.List<Double> vals = new java.util.ArrayList<>();
        for (String part : parts) if (!part.isBlank()) vals.add(Double.parseDouble(part.trim()));
        if (vals.size() != 3) throw new IllegalArgumentException("expected three numbers");
        return new double[]{vals.get(0), vals.get(1), vals.get(2)};
    }

    /* ----------------- sidebar & search ----------------- */

    private JComponent buildSidebar() {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Solar System");
        DefaultMutableTreeNode sunNode = new DefaultMutableTreeNode(new BodyNode("Sun", -1, -1, true));
        root.add(sunNode);
        nameIndex.put("sun", new int[]{-1, -1, 1});
        for (int k = 0; k < COUNT; k++) {
            DefaultMutableTreeNode pn = new DefaultMutableTreeNode(new BodyNode(NAMES[k], k, -1, false));
            root.add(pn);
            nameIndex.put(NAMES[k].toLowerCase(), new int[]{k, -1, 0});
            for (int m = 0; m < MOON_NAMES[k].length; m++) {
                pn.add(new DefaultMutableTreeNode(new BodyNode(MOON_NAMES[k][m], k, m, false)));
                nameIndex.put(MOON_NAMES[k][m].toLowerCase(), new int[]{k, m, 0});
            }
        }
        JTree tree = new JTree(new DefaultTreeModel(root));
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setRootVisible(true);
        tree.addTreeSelectionListener(e -> {
            Object node = tree.getLastSelectedPathComponent();
            if (!(node instanceof DefaultMutableTreeNode dn)) return;
            if (!(dn.getUserObject() instanceof BodyNode b)) return;
            if (b.sun) selectSun();
            else if (b.moon < 0) selectPlanet(b.planet);
            else selectMoon(b.planet, b.moon);
        });
        JScrollPane sp = new JScrollPane(tree);
        sp.setPreferredSize(new Dimension(210, 0));
        sp.setBorder(BorderFactory.createTitledBorder("Bodies"));
        return sp;
    }

    private void jumpTo(String name) {
        if (name.isEmpty()) return;
        int[] idx = nameIndex.get(name.toLowerCase());
        if (idx == null) {
            // prefix match fallback
            for (Map.Entry<String, int[]> en : nameIndex.entrySet())
                if (en.getKey().startsWith(name.toLowerCase())) { idx = en.getValue(); break; }
        }
        if (idx == null) return;
        if (idx[2] == 1) selectSun();
        else if (idx[1] < 0) selectPlanet(idx[0]);
        else selectMoon(idx[0], idx[1]);
    }

    private static final class BodyNode {
        final String label; final int planet, moon; final boolean sun;
        BodyNode(String label, int planet, int moon, boolean sun) {
            this.label = label; this.planet = planet; this.moon = moon; this.sun = sun;
        }
        @Override public String toString() { return label; }
    }
}
