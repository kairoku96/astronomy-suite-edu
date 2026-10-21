import javax.swing.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import com.formdev.flatlaf.FlatDarkLaf;

/**
 * The application window around the simulation canvas: menus, the search bar, the object tree
 * with the time controls, and the right-hand dock (transfer planner, sky events, lessons).
 * Split out of {@link SolarSystemSimulation}, which keeps the simulation and its rendering.
 */
final class AppWindow {
    private AppWindow() {}

    static final String APP_NAME = "Astronomy Suite";

    /** A checkbox menu item and the panel setting it mirrors, saved under {@code key} (null: not saved). */
    private record Toggle(String key, JCheckBoxMenuItem item, BooleanSupplier get, Consumer<Boolean> set) {
        void refresh() { item.setSelected(get.getAsBoolean()); }
    }

    /**
     * A checkbox menu item for one setting. {@code hintKey} (0 for none) is the plain key that
     * toggles it from the canvas; the menu only shows it.
     */
    private static JCheckBoxMenuItem toggle(java.util.List<Toggle> all, SolarSystemSimulation panel, String key,
                                            String text, int hintKey, BooleanSupplier get, Consumer<Boolean> set) {
        SolarSystemSimulation.StayOpenCheckBoxMenuItem item =
                new SolarSystemSimulation.StayOpenCheckBoxMenuItem(text, get.getAsBoolean());
        if (hintKey != 0) item.setAccelerator(KeyStroke.getKeyStroke(hintKey, 0));
        item.addActionListener(_ -> {
            set.accept(item.isSelected());
            item.setSelected(get.getAsBoolean());   // the setter may refuse (no GPU)
            panel.repaint();
        });
        all.add(new Toggle(key, item, get, set));
        return item;
    }

    private static void applySettings(AppSettings s, SolarSystemSimulation panel, java.util.List<Toggle> toggles) {
        for (Toggle t : toggles) {
            if (t.key() == null || !s.has(t.key())) continue;
            t.set().accept(s.getBool(t.key(), t.get().getAsBoolean()));
            t.refresh();
        }
        if (s.has("time.scale")) panel.setTimeScaleAndSync(s.getDouble("time.scale", panel.timeScale));
    }

    private static void saveSettings(AppSettings s, JFrame frame, SolarSystemSimulation panel,
                                     java.util.List<Toggle> toggles) {
        // A lesson changes what is shown; leaving it first restores the user's own choices.
        if (panel.lessonActive()) panel.lessonStudio.exitLesson();
        for (Toggle t : toggles) {
            if (t.key() != null) s.put(t.key(), t.get().getAsBoolean());
        }
        s.put("ui.size", panel.uiSize.name());
        s.put("time.scale", panel.timeScale);
        Rectangle r = frame.getRootPane().getClientProperty(NORMAL_BOUNDS) instanceof Rectangle nb ? nb : frame.getBounds();
        s.put("window.x", r.x);
        s.put("window.y", r.y);
        s.put("window.w", r.width);
        s.put("window.h", r.height);
        s.put("window.maximized", (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH);
        s.save();
    }

    /** The window's last un-maximized bounds, so a maximized window still remembers its normal size. */
    private static final String NORMAL_BOUNDS = "sss.normalBounds";

    private static void restoreBounds(JFrame frame, AppSettings s) {
        frame.addComponentListener(new ComponentAdapter() {
            private void track() {
                if ((frame.getExtendedState() & Frame.MAXIMIZED_BOTH) == 0)
                    frame.getRootPane().putClientProperty(NORMAL_BOUNDS, frame.getBounds());
            }
            @Override public void componentResized(ComponentEvent e) { track(); }
            @Override public void componentMoved(ComponentEvent e) { track(); }
        });
        Rectangle r = new Rectangle(s.getInt("window.x", 0), s.getInt("window.y", 0),
                s.getInt("window.w", 0), s.getInt("window.h", 0));
        // Only reuse the saved place if enough of it is still on a screen (a monitor may be gone).
        boolean onScreen = false;
        if (r.width >= 400 && r.height >= 300) {
            for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                Rectangle seen = d.getDefaultConfiguration().getBounds().intersection(r);
                if (seen.width >= 200 && seen.height >= 150) { onScreen = true; break; }
            }
        }
        if (onScreen) frame.setBounds(r); else frame.setLocationRelativeTo(null);
        frame.getRootPane().putClientProperty(NORMAL_BOUNDS, frame.getBounds());
        if (s.getBool("window.maximized", false)) frame.setExtendedState(Frame.MAXIMIZED_BOTH);
    }

    private static void showAbout(JFrame frame) {
        String html = """
                <html><body style='width:400px'>
                <p style='font-size:15pt'><b>%s</b></p>
                <p>A real-date 3-D model of the Solar System, with a planetarium, lessons and spacecraft.
                The planets, comets and asteroids move under their mutual gravity (a symplectic
                Wisdom–Holman integrator with general relativity), the Moon follows ELP/MPP02, and the
                other moons follow theories fitted to JPL ephemerides.</p>
                <p><b>Data</b><br>
                Initial conditions and test references: NASA/JPL Horizons and DE441<br>
                The Moon: ELP/MPP02 (Chapront &amp; Francou 2003)<br>
                Rotation: IAU WGCCRE 2015<br>
                Stars: Yale Bright Star Catalogue<br>
                ΔT and eclipse checks: NASA (Espenak &amp; Meeus)<br>
                Planet maps: Solar System Scope (CC BY 4.0); Earth's night lights and clouds: NASA Visible Earth<br>
                Satellite imagery: Esri World Imagery (© Esri, Maxar, Earthstar Geographics)</p>
                <p><b>Built with</b> FlatLaf and JOGL, running on Java %s.</p>
                <p>MIT License, © 2025 Ethan Lin<br>Settings are saved in %s</p>
                </body></html>""".formatted(APP_NAME, System.getProperty("java.version"),
                AppSettings.file().getParent());
        JOptionPane.showMessageDialog(frame, html, "About " + APP_NAME,
                JOptionPane.INFORMATION_MESSAGE, new ImageIcon(drawIcon(64)));
    }

    private static java.util.List<Image> appIcons() {
        java.util.List<Image> icons = new ArrayList<>();
        for (int size : new int[]{16, 20, 24, 32, 40, 48, 64, 128, 256}) icons.add(drawIcon(size));
        return icons;
    }

    /** The app icon: a ringed planet lit from the upper left, drawn at {@code s} pixels. */
    static BufferedImage drawIcon(int s) {
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        double c = s / 2.0, r = s * 0.29;
        AffineTransform tilt = AffineTransform.getRotateInstance(Math.toRadians(-24), c, c);
        Shape ring = tilt.createTransformedShape(new Ellipse2D.Double(c - 0.47 * s, c - 0.14 * s, 0.94 * s, 0.28 * s));
        Shape backHalf = tilt.createTransformedShape(new Rectangle2D.Double(-s, -s, 3 * s, c + s));
        Shape frontHalf = tilt.createTransformedShape(new Rectangle2D.Double(-s, c, 3 * s, 2 * s));
        g.setStroke(new BasicStroke((float) Math.max(1.3, s * 0.042)));
        Color ringColor = new Color(150, 190, 255);
        g.setColor(ringColor);
        g.setClip(backHalf);
        g.draw(ring);
        g.setClip(null);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(c - 0.4 * r, c - 0.45 * r), (float) (1.6 * r),
                new float[]{0f, 0.55f, 1f},
                new Color[]{new Color(255, 232, 170), new Color(226, 160, 72), new Color(120, 66, 28)}));
        g.fill(new Ellipse2D.Double(c - r, c - r, 2 * r, 2 * r));
        g.setColor(ringColor);
        g.setClip(frontHalf);
        g.draw(ring);
        g.dispose();
        return img;
    }

    /** Build and show the main window. */
    static void open() {
        // Modern flat dark look-and-feel for all Swing chrome (menus, dialogs,
        // tree, split panes, sliders). Falls back to the platform default if
        // FlatLaf is unavailable at runtime.
        try {
            UIManager.put("Component.arc", 10);
            UIManager.put("Button.arc", 10);
            UIManager.put("TextComponent.arc", 8);
            UIManager.put("ScrollBar.thumbArc", 10);
            UIManager.put("ScrollBar.width", 12);
            UIManager.put("Component.focusWidth", 1);
            UIManager.put("MenuItem.selectionArc", 8);
            FlatDarkLaf.setup();
            // Accent tuned to match the in-canvas HUD/info panels.
            UIManager.put("Component.accentColor", new Color(100, 150, 255));
        } catch (Throwable ignored) {
            // FlatLaf not on the classpath (or failed) -> keep default L&F
        }

        AppSettings settings = AppSettings.load();
        JFrame frame = new JFrame(APP_NAME);
        frame.setIconImages(appIcons());
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);   // quit() saves the settings first
        SolarSystemSimulation panel = new SolarSystemSimulation();
        panel.parentFrame = frame; // Set frame reference for menu toggling
        // Every checkbox below mirrors a panel setting; the menus re-read them when opened,
        // since the keys and lessons change the same settings.
        java.util.List<Toggle> toggles = new ArrayList<>();
        Runnable quit = () -> {
            saveSettings(settings, frame, panel, toggles);
            System.exit(0);
        };
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { quit.run(); }
        });
        // ----- Build professional-style UI frame -----
         // Top-level menu bar
         JMenuBar menuBar = new JMenuBar();

        // Simulation menu
        JMenu simMenu = new JMenu("Simulation");
        JMenuItem pauseItem = new SolarSystemSimulation.KeyHintMenuItem("Pause / resume", KeyEvent.VK_SPACE);
        pauseItem.addActionListener(_ -> {
            panel.simulationPaused = !panel.simulationPaused;
            panel.updateTimeScaleLabel();
        });
        JMenuItem resetViewItem = new JMenuItem("Reset View to Sun");
        resetViewItem.addActionListener(_ -> {
            panel.followingPlanet = false;
            panel.followingMoon = false;
            panel.cameraFocusIndex = -1;
            panel.cameraFocusMoon = -1;
            panel.selectedPlanet = -1;
            panel.selectedMoon = -1;
            panel.camX = 0;
            panel.camY = 0;
            panel.currentZoom = 1.0;
        });
        JMenuItem solarEclipseItem = new JMenuItem("Show solar eclipse (Earth)");
        solarEclipseItem.addActionListener(_ -> panel.showEclipse(true));
        JMenuItem eclipsePathItem = new JMenuItem("Next solar eclipse on the globe");
        eclipsePathItem.addActionListener(_ -> {
            double t = SolarSystemSimulation.nextEarthEclipse(true, panel.simTimeSec, +1);
            if (!Double.isNaN(t)) panel.showSolarEclipseOnGlobe(t);
        });
        JMenuItem lunarEclipseItem = new JMenuItem("Show lunar eclipse (Earth)");
        lunarEclipseItem.addActionListener(_ -> panel.showEclipse(false));
        // Real-date scrubbing (planetarium): jump the whole system to a calendar date.
        JMenuItem gotoDateItem = new JMenuItem("Go to date…");
        gotoDateItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_G, InputEvent.CTRL_DOWN_MASK));
        gotoDateItem.addActionListener(_ -> panel.promptGoToDate(frame));
        JMenuItem todayItem = new JMenuItem("Jump to today");
        todayItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_T, InputEvent.CTRL_DOWN_MASK));
        todayItem.addActionListener(_ -> {
            panel.simulationPaused = true;
            panel.goToDate(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        });
        JMenuItem epochItem = new JMenuItem("Reset to J2000 epoch");
        epochItem.addActionListener(_ -> {
            panel.goToDate(java.time.LocalDateTime.of(2000, 1, 1, 12, 0));
        });
        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Q, InputEvent.CTRL_DOWN_MASK));
        exitItem.addActionListener(_ -> quit.run());
        simMenu.add(pauseItem);
        simMenu.add(resetViewItem);
        simMenu.addSeparator();
        simMenu.add(gotoDateItem);
        simMenu.add(todayItem);
        simMenu.add(epochItem);
        simMenu.addSeparator();
        simMenu.add(solarEclipseItem);
        simMenu.add(eclipsePathItem);
        simMenu.add(lunarEclipseItem);
        JMenuItem eventsItem = new JMenuItem("Next events (conjunctions, transits, occultations…)…");
        eventsItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_E, InputEvent.CTRL_DOWN_MASK));
        eventsItem.addActionListener(_ -> { panel.showDockPanel(panel.eventsPanel); panel.eventsPanel.search(); });
        simMenu.add(eventsItem);
        simMenu.addSeparator();
        simMenu.add(exitItem);

        // Objects menu — toggle the built-in catalog of real minor bodies
        JMenu objectsMenu = new JMenu("Objects");
        objectsMenu.add(toggle(toggles, panel, "objects.comets", "Comets  (with tails)", 0,
                () -> panel.showComets, v -> panel.showComets = v));
        objectsMenu.add(toggle(toggles, panel, "objects.asteroids", "Asteroids & near-Earth objects", 0,
                () -> panel.showAsteroids, v -> panel.showAsteroids = v));
        objectsMenu.add(toggle(toggles, panel, "objects.distant", "Distant objects (centaurs / TNOs)", 0,
                () -> panel.showDistantBodies, v -> panel.showDistantBodies = v));
        objectsMenu.addSeparator();
        objectsMenu.add(toggle(toggles, panel, "objects.labels", "Show object labels", 0,
                () -> panel.showSmallBodyLabels, v -> panel.showSmallBodyLabels = v));

        // View menu – mirrors keyboard toggles
        JMenu viewMenu = new JMenu("View");
        viewMenu.add(toggle(toggles, panel, "view.orbits", "Show planet orbits", KeyEvent.VK_O,
                () -> panel.showPlanetOrbits, v -> panel.showPlanetOrbits = v));
        viewMenu.add(toggle(toggles, panel, "view.rings", "Show Saturn rings", KeyEvent.VK_R,
                () -> panel.showRings, v -> panel.showRings = v));
        viewMenu.add(toggle(toggles, panel, "view.labels", "Show labels", KeyEvent.VK_L,
                () -> panel.showLabels, v -> panel.showLabels = v));
        viewMenu.add(toggle(toggles, panel, "view.shading", "Enable shading", KeyEvent.VK_S,
                () -> panel.shadingEnabled, v -> panel.shadingEnabled = v));
        viewMenu.add(toggle(toggles, panel, "view.atmosphere", "Atmospheric glow", KeyEvent.VK_A,
                () -> panel.showAtmosphere, v -> panel.showAtmosphere = v));
        viewMenu.add(toggle(toggles, panel, "view.eclipses", "Eclipse shadows", KeyEvent.VK_E,
                () -> panel.showEclipses, v -> panel.showEclipses = v));
        viewMenu.add(toggle(toggles, panel, "view.clouds", "Earth clouds", KeyEvent.VK_C,
                () -> panel.showClouds, v -> panel.showClouds = v));
        viewMenu.add(toggle(toggles, panel, "view.nightLights", "Earth night lights", KeyEvent.VK_I,
                () -> panel.showNightLights, v -> panel.showNightLights = v));
        // -Dsss.gpu on the command line wins over the saved choice.
        viewMenu.add(toggle(toggles, panel, System.getProperty("sss.gpu") == null ? "view.gpu" : null,
                "GPU sphere shader", KeyEvent.VK_G,
                () -> panel.gpuSpheres, v -> {
                    if (v) {
                        GLSphereRenderer r = panel.gpuRenderer();
                        v = r != null && r.isAvailable();      // no GPU available: keep CPU path
                    }
                    panel.gpuSpheres = v;
                }));
        viewMenu.addSeparator();
        viewMenu.add(toggle(toggles, panel, null, "Show only selected planet", KeyEvent.VK_B,
                () -> panel.showOnlySelected, v -> panel.showOnlySelected = v));
        JMenuItem baryItem = viewMenu.add(toggle(toggles, panel, "view.barycentric", "Sun reflex wobble (barycentric frame)", 0,
                () -> panel.barycentricFrame, v -> { panel.barycentricFrame = v; panel.computeBarycenter(); }));
        baryItem.setToolTipText("Off: Sun fixed, orbits steady. On: Sun wobbles about the barycentre "
                + "(the real motion that reveals exoplanets) — noticeable at high time speed.");
        JMenuItem lightItem = viewMenu.add(toggle(toggles, panel, null, "Apparent positions — light-time from Earth", KeyEvent.VK_T,
                () -> panel.apparentPositions, v -> { panel.apparentPositions = v; panel.updateEphemeris(); }));
        lightItem.setToolTipText("Draw each body where it appears from Earth: where it was when the light now arriving left it. "
                + "A hollow ring marks where it really is.");
        viewMenu.addSeparator();
        viewMenu.add(toggle(toggles, panel, "view.stars", "Real star field (Yale Bright Star Catalogue)", 0,
                () -> panel.showRealStars, v -> panel.showRealStars = v));
        viewMenu.add(toggle(toggles, panel, "view.constellations", "Constellation lines", 0,
                () -> panel.showConstellations, v -> panel.showConstellations = v));
        viewMenu.addSeparator();
        JMenuItem skyItem = new SolarSystemSimulation.KeyHintMenuItem("Sky from Earth…", KeyEvent.VK_K);
        skyItem.addActionListener(_ -> panel.enterSkyView());
        JMenuItem locationItem = new JMenuItem("Observer location…");
        locationItem.addActionListener(_ -> panel.skyView().showLocationDialog());
        viewMenu.add(skyItem);
        viewMenu.add(locationItem);

        // Add planet visibility submenu
        viewMenu.addSeparator();
        JMenu planetsMenu = new JMenu("Planets");
        for (int i = 0; i < SolarSystemSimulation.NAMES.length; i++) {
            final int planetIndex = i;
            planetsMenu.add(toggle(toggles, panel, "planet." + SolarSystemSimulation.NAMES[i], SolarSystemSimulation.NAMES[i], 0,
                    () -> panel.planetVisible[planetIndex],
                    v -> { panel.planetVisible[planetIndex] = v; panel.updateTreeVisibility(); }));
        }
        viewMenu.add(planetsMenu);

        // Add asteroid/debris belts submenu
        viewMenu.addSeparator();
        JMenu beltsMenu = new JMenu("Belts & Lines");
        beltsMenu.add(toggle(toggles, panel, "belts.asteroid", "Asteroid Belt", 0,
                () -> panel.showAsteroidBelt, v -> panel.showAsteroidBelt = v));
        beltsMenu.add(toggle(toggles, panel, "belts.kuiper", "Kuiper Belt", 0,
                () -> panel.showKuiperBelt, v -> panel.showKuiperBelt = v));
        beltsMenu.add(toggle(toggles, panel, "belts.frost", "Frost Lines", 0,
                () -> panel.showIceLines, v -> panel.showIceLines = v));
        viewMenu.add(beltsMenu);

        // UI Size submenu
        viewMenu.addSeparator();
        JMenu uiSizeMenu = new JMenu("UI Size");
        ButtonGroup uiSizeGroup = new ButtonGroup();
        try {
            panel.uiSize = SolarSystemSimulation.UISize.valueOf(settings.get("ui.size", panel.uiSize.name()));
        } catch (IllegalArgumentException ignored) { }
        for (SolarSystemSimulation.UISize size : SolarSystemSimulation.UISize.values()) {
            String label = size.name().charAt(0) + size.name().substring(1).toLowerCase();
            SolarSystemSimulation.StayOpenRadioButtonMenuItem sizeItem =
                    new SolarSystemSimulation.StayOpenRadioButtonMenuItem(label, panel.uiSize == size);
            sizeItem.addActionListener(_ -> {
                panel.uiSize = size;
                panel.repaint();
            });
            uiSizeGroup.add(sizeItem);
            uiSizeMenu.add(sizeItem);
        }

        viewMenu.add(uiSizeMenu);

        JMenu helpMenu = new JMenu("Help");
        JMenuItem controlsItem = new JMenuItem("Controls...");
        controlsItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F1, 0));
        controlsItem.addActionListener(_ -> JOptionPane.showMessageDialog(
                frame,
                """
                        Controls:
                        - Mouse wheel: Zoom in/out
                        - Ctrl + wheel: Change time speed
                        - Click planet: Follow/unfollow
                        - Click moon (while following planet): Follow/unfollow moon
                        - Z: Toggle auto-zoom
                        - X: Cycle zoom mode (Planet/Orbit/Manual)
                        - O: Toggle planet orbits
                        - R: Toggle Saturn rings
                        - L: Toggle labels
                        - B: Show only selected planet
                        - S: Toggle shading
                        - A: Toggle atmospheric glow
                        - E: Toggle eclipse shadows
                        - C: Toggle Earth clouds
                        - I: Toggle Earth night lights
                        - Space / P: Pause / resume
                        - M: Cycle selected planet (shows its stats)
                        - U: Toggle Sun info panel
                        - T: True / apparent positions (light-time from Earth)
                        - H: Hide/show all UI (menu, search, sidebar)
                        - K: Sky from Earth (planetarium) on / off

                        Sky from Earth:
                        - Drag: look around   - Wheel / + -: zoom
                        - Click: select an object (rise / transit / set)
                        - L: observer location   - T: jump to now
                        - C: constellation lines   - N: names
                        - G: alt-az grid   - Q: equatorial grid
                        - E: ecliptic   - F: ground   - A: atmosphere
                        - Esc / K: back to the solar system
                        (In Earth Explorer, V looks up at the sky
                        from the point under the map centre.)

                        Menu shortcuts:
                        - Ctrl+G: Go to date   - Ctrl+T: Jump to today
                        - Ctrl+E: Next events   - Ctrl+L: Lessons
                        - Ctrl+Q: Exit   - F1: this list

                        Shortcuts work from anywhere in the window,
                        except while typing in the search box.

                        Use View menu for:
                        - Asteroid Belt
                        - Kuiper Belt
                        - Frost Lines (H2O and CO2)""",
                "Controls",
                JOptionPane.INFORMATION_MESSAGE));
        JMenuItem aboutItem = new JMenuItem("About " + APP_NAME);
        aboutItem.addActionListener(_ -> showAbout(frame));
        JMenuItem aiChatItem = new JMenuItem("Ask AI Assistant...");
        aiChatItem.addActionListener(_ -> AiAssistant.showAiChat(frame, panel));
        helpMenu.add(controlsItem);
        helpMenu.addSeparator();
        helpMenu.add(aiChatItem);
        helpMenu.addSeparator();
        helpMenu.add(aboutItem);

        // Missions menu — real spacecraft flown inside this view
        JMenu missionsMenu = new JMenu("Missions");
        JMenu lunarMenu = new JMenu("Moon missions");
        JMenu deepMenu = new JMenu("Deep space");
        for (Missions.Mission m : Missions.all()) {
            JMenuItem it = new JMenuItem(m.name + "  —  " + m.subtitle);
            it.setToolTipText(m.summary);
            it.addActionListener(_ -> {
                if (panel.lessonActive()) panel.lessonStudio.exitLesson();
                if (panel.skyViewActive) panel.exitSkyView();
                if (panel.earthExplorerActive) panel.exitEarthExplorer();
                panel.missionPlayer.start(m);
                panel.requestFocusInWindow();
            });
            (m.kind == Missions.Kind.LUNAR ? lunarMenu : deepMenu).add(it);
        }
        missionsMenu.add(lunarMenu);
        missionsMenu.add(deepMenu);
        JMenuItem plannerItem = new JMenuItem("Transfer planner (Lambert / porkchop)…");
        plannerItem.addActionListener(_ -> panel.showDockPanel(panel.transferPlanner));
        missionsMenu.add(plannerItem);
        missionsMenu.addSeparator();
        JMenuItem stopMission = new JMenuItem("Stop mission playback");
        stopMission.addActionListener(_ -> { if (panel.missionPlayer.isActive()) panel.missionPlayer.stop(); });
        missionsMenu.add(stopMission);
        missionsMenu.addSeparator();
        missionsMenu.add(toggle(toggles, panel, "missions.inFlight", "Show spacecraft in flight on the current date", 0,
                panel.missionPlayer::showAllInFlight, panel.missionPlayer::setShowAllInFlight));

        menuBar.add(simMenu);
        menuBar.add(objectsMenu);
        menuBar.add(viewMenu);
        menuBar.add(missionsMenu);

        // Education menu: lessons (run inside this window), plus the orbit overlays for the live sim
        panel.lessonStudio = new LessonStudio(panel, panel.new LessonHost(), panel::onLessonActiveChanged);
        panel.lessonStudio.setVisible(false);
        JMenu educationMenu = new JMenu("Education");
        JMenuItem studioItem = new JMenuItem("Lessons...");
        studioItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_L, InputEvent.CTRL_DOWN_MASK));
        studioItem.addActionListener(_ -> panel.lessonStudio.showPicker());
        educationMenu.add(studioItem);
        String[] lessonLabels = LessonStudio.menuLabels();
        for (int li = 0; li < lessonLabels.length; li++) {
            final int lessonIndex = li;
            JMenuItem it = new JMenuItem("    " + lessonLabels[li]);
            it.addActionListener(_ -> panel.lessonStudio.startLesson(lessonIndex));
            educationMenu.add(it);
        }
        educationMenu.addSeparator();

        // Orbit overlays — annotations on the live simulation
        JMenu overlaysMenu = new JMenu("Overlays");
        overlaysMenu.add(toggle(toggles, panel, "overlays.kepler", "Kepler's 3rd Law (P²/a³)", 0,
                () -> panel.showKeplersLaw, v -> panel.showKeplersLaw = v));
        overlaysMenu.add(toggle(toggles, panel, "overlays.periAphelion", "Perihelion / Aphelion markers", 0,
                () -> panel.showPerihelionAphelion, v -> panel.showPerihelionAphelion = v));
        overlaysMenu.add(toggle(toggles, panel, "overlays.velocityVectors", "Velocity vectors", 0,
                () -> panel.showVelocityVectors, v -> panel.showVelocityVectors = v));
        overlaysMenu.add(toggle(toggles, panel, "overlays.velocityLabels", "Velocity labels", 0,
                () -> panel.showVelocityComparison, v -> panel.showVelocityComparison = v));
        educationMenu.add(overlaysMenu);


        menuBar.add(educationMenu);
        menuBar.add(helpMenu);
        int[] mnemonics = {KeyEvent.VK_S, KeyEvent.VK_O, KeyEvent.VK_V, KeyEvent.VK_M, KeyEvent.VK_E, KeyEvent.VK_H};
        for (int i = 0; i < menuBar.getMenuCount(); i++) {
            JMenu menu = menuBar.getMenu(i);
            menu.setMnemonic(mnemonics[i]);
            menu.addMenuListener(new javax.swing.event.MenuListener() {
                @Override public void menuSelected(javax.swing.event.MenuEvent e) { toggles.forEach(Toggle::refresh); }
                @Override public void menuDeselected(javax.swing.event.MenuEvent e) { }
                @Override public void menuCanceled(javax.swing.event.MenuEvent e) { }
            });
        }
        frame.setJMenuBar(menuBar);

        // ----- Top search bar -----
        JPanel topBar = new JPanel(new BorderLayout(8, 0));
        topBar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        panel.searchBarPanel = topBar; // Store reference for toggling visibility
        JLabel searchLabel = new JLabel("Search body:");
        panel.searchField = new JTextField();
        panel.searchField.setToolTipText("Type a planet or major moon name (e.g., Earth, Titan)");

        // Ensure panel can still receive key events when search is not focused
        panel.setFocusTraversalKeysEnabled(false);

        // Build suggestion popup for search
        panel.searchSuggestionsPopup = new JPopupMenu();
        panel.searchSuggestionsPopup.setFocusable(false);
        panel.searchSuggestionsList = new JList<>();
        panel.searchSuggestionsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        panel.searchSuggestionsList.setFocusable(false);
        JScrollPane suggScroll = new JScrollPane(panel.searchSuggestionsList);
        suggScroll.setFocusable(false);
        suggScroll.setPreferredSize(new Dimension(220, 140));
        panel.searchSuggestionsPopup.add(suggScroll);

        // Precompute all searchable names (planets + moons)
        java.util.List<String> allNames = new java.util.ArrayList<>();
        Collections.addAll(allNames, SolarSystemSimulation.NAMES);
        for (int i = 0; i < SolarSystemSimulation.NAMES.length; i++) {
            Collections.addAll(allNames, SolarSystemSimulation.MOON_NAMES[i]);
        }

        // Update suggestions as the user types
        panel.searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void updateSuggestions() {
                String text = panel.searchField.getText();
                if (text == null) text = "";
                String q = text.trim().toLowerCase();
                if (q.isEmpty()) {
                    panel.searchSuggestionsPopup.setVisible(false);
                    return;
                }
                java.util.List<String> matches = new java.util.ArrayList<>();
                for (String name : allNames) {
                    if (name.toLowerCase().startsWith(q)) {
                        matches.add(name);
                    }
                }
                if (matches.isEmpty()) {
                    panel.searchSuggestionsPopup.setVisible(false);
                    return;
                }
                panel.searchSuggestionsList.setListData(matches.toArray(new String[0]));
                try {
                    Rectangle r = panel.searchField.getBounds();
                    panel.searchSuggestionsPopup.show(panel.searchField, 0, r.height);
                } catch (Exception ignored) {}
            }
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { updateSuggestions(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { updateSuggestions(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { updateSuggestions(); }
        });

        // Clicking a suggestion uses that body
        panel.searchSuggestionsList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    String sel = panel.searchSuggestionsList.getSelectedValue();
                    if (sel != null) {
                        panel.searchField.setText(sel);
                        panel.searchSuggestionsPopup.setVisible(false);
                        panel.searchAndFocus();
                    }
                }
            }
        });


        topBar.add(searchLabel, BorderLayout.WEST);
        topBar.add(panel.searchField, BorderLayout.CENTER);

        JButton searchButton = new JButton("Go");
        searchButton.addActionListener(_ -> panel.searchAndFocus());
        panel.searchField.addActionListener(_ -> panel.searchAndFocus());

        // Add key listener to search field to handle Escape and forward global keys
        panel.searchField.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                int code = e.getKeyCode();

                // Handle search-specific keys
                if (!panel.searchSuggestionsPopup.isVisible()) {
                    // Popup not visible - handle Escape to clear and return focus
                    if (code == KeyEvent.VK_ESCAPE) {
                        panel.searchField.setText("");
                        panel.searchSuggestionsPopup.setVisible(false);
                        panel.requestFocusInWindow();
                        e.consume();
                        return;
                    }
                } else {
                    // Popup is visible - handle navigation
                    if (code == KeyEvent.VK_DOWN) {
                        int idx = panel.searchSuggestionsList.getSelectedIndex();
                        if (idx < panel.searchSuggestionsList.getModel().getSize() - 1) {
                            panel.searchSuggestionsList.setSelectedIndex(idx + 1);
                            panel.searchSuggestionsList.ensureIndexIsVisible(idx + 1);
                        }
                        e.consume();
                        return;
                    } else if (code == KeyEvent.VK_UP) {
                        int idx = panel.searchSuggestionsList.getSelectedIndex();
                        if (idx > 0) {
                            panel.searchSuggestionsList.setSelectedIndex(idx - 1);
                            panel.searchSuggestionsList.ensureIndexIsVisible(idx - 1);
                        }
                        e.consume();
                        return;
                    } else if (code == KeyEvent.VK_ENTER) {
                        String sel = panel.searchSuggestionsList.getSelectedValue();
                        if (sel != null) {
                            panel.searchField.setText(sel);
                        }
                        panel.searchSuggestionsPopup.setVisible(false);
                        panel.searchAndFocus();
                        e.consume();
                        return;
                    } else if (code == KeyEvent.VK_ESCAPE) {
                        panel.searchSuggestionsPopup.setVisible(false);
                        e.consume();
                        return;
                    }
                }
                // Global toggle keys are handled by the app-wide dispatcher; while
                // the search field has focus we let all other keys type normally.
            }
        });

        topBar.add(searchButton, BorderLayout.EAST);

        // Add mouse listener to top bar to close search when clicking outside search field
        topBar.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                // If click is not on search field, close the popup
                if (e.getSource() != panel.searchField && !panel.isSearchUIComponent(e.getComponent())) {
                    if (panel.searchSuggestionsPopup != null) {
                        panel.searchSuggestionsPopup.setVisible(false);
                    }
                }
            }
        });

        // Main content with top bar + simulation panel
        JPanel content = new JPanel(new BorderLayout());
        content.add(topBar, BorderLayout.NORTH);

        // Left navigation: hierarchical planet→moon tree
        JPanel leftNav = new JPanel(new BorderLayout());
        leftNav.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 4));
        panel.sidebarPanel = leftNav; // Store reference for toggling visibility
        JLabel navLabel = new JLabel("Objects");
        navLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        JPanel treePanel = new JPanel(new BorderLayout());
        treePanel.add(navLabel, BorderLayout.NORTH);

        // Build tree nodes
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Solar System");
        panel.treeRootNode = root;
        panel.planetTreePaths = new TreePath[SolarSystemSimulation.NAMES.length];
        panel.planetTreeNodes = new DefaultMutableTreeNode[SolarSystemSimulation.NAMES.length];
        panel.moonTreePaths = new TreePath[SolarSystemSimulation.NAMES.length][];
        for (int i = 0; i < SolarSystemSimulation.NAMES.length; i++) {
            DefaultMutableTreeNode planetNode = new DefaultMutableTreeNode(new SolarSystemSimulation.TreeBody("planet", i, -1, SolarSystemSimulation.NAMES[i]));
            panel.planetTreeNodes[i] = planetNode;
            // Only add to tree if planet is visible
            if (panel.planetVisible[i]) {
                root.add(planetNode);
            }
            panel.planetTreePaths[i] = new TreePath(new Object[]{root, planetNode});
            panel.moonTreePaths[i] = new TreePath[SolarSystemSimulation.MOON_NAMES[i].length];
            for (int m = 0; m < SolarSystemSimulation.MOON_NAMES[i].length; m++) {
                DefaultMutableTreeNode moonNode = new DefaultMutableTreeNode(new SolarSystemSimulation.TreeBody("moon", i, m, SolarSystemSimulation.MOON_NAMES[i][m]));
                planetNode.add(moonNode);
                panel.moonTreePaths[i][m] = new TreePath(new Object[]{root, planetNode, moonNode});
            }
        }
        panel.bodyTreeModel = new DefaultTreeModel(root);
        panel.bodyTree = new JTree(panel.bodyTreeModel);
        panel.bodyTree.setRootVisible(true);
        panel.bodyTree.setShowsRootHandles(true);
        panel.bodyTree.setCellRenderer(new SolarSystemSimulation.BodyTreeRenderer());
        panel.bodyTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        for (int i = 0; i < panel.bodyTree.getRowCount(); i++) {
            panel.bodyTree.expandRow(i);
        }
        panel.bodyTree.addTreeSelectionListener(e -> panel.handleTreeSelection(e.getPath()));
        JScrollPane treeScroll = new JScrollPane(panel.bodyTree);
        treeScroll.setPreferredSize(new Dimension(220, 0));
        treePanel.add(treeScroll, BorderLayout.CENTER);

        leftNav.add(treePanel, BorderLayout.CENTER);

        content.add(leftNav, BorderLayout.WEST);
        content.add(panel, BorderLayout.CENTER);
        //
        // Bottom: time controls (play/pause, fine slider, presets, label)
        JPanel timeControlsPanel = new JPanel();
        timeControlsPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        timeControlsPanel.setLayout(new GridBagLayout());
        GridBagConstraints timeGbc = new GridBagConstraints();
        timeGbc.fill = GridBagConstraints.HORIZONTAL;

        JButton playPause = new JButton(panel.simulationPaused ? "Play" : "Pause");
        playPause.addActionListener(_ -> {
            panel.simulationPaused = !panel.simulationPaused;
            playPause.setText(panel.simulationPaused ? "Play" : "Pause");
            panel.updateTimeScaleLabel();
        });
        // Space, the menu, date jumps and lessons pause the clock too: keep the button in step.
        new javax.swing.Timer(250, _ -> {
            String want = panel.simulationPaused ? "Play" : "Pause";
            if (!want.equals(playPause.getText())) {
                playPause.setText(want);
                panel.updateTimeScaleLabel();
            }
        }).start();

        panel.timeScaleSlider = new JSlider(SolarSystemSimulation.TIME_SLIDER_MIN, SolarSystemSimulation.TIME_SLIDER_MAX,
                panel.timeScaleToSliderValue(panel.timeScale));
        panel.timeScaleSlider.setMajorTickSpacing((SolarSystemSimulation.TIME_SLIDER_MAX - SolarSystemSimulation.TIME_SLIDER_MIN) / 5);
        panel.timeScaleSlider.setMinorTickSpacing((SolarSystemSimulation.TIME_SLIDER_MAX - SolarSystemSimulation.TIME_SLIDER_MIN) / 20);
        panel.timeScaleSlider.setPaintTicks(false);
        panel.timeScaleSlider.setPreferredSize(new Dimension(220, 32));
        panel.timeScaleSlider.addChangeListener(_ -> {
            if (panel.suppressTimeSliderEvents) return;
            panel.timeScale = panel.sliderValueToTimeScale(panel.timeScaleSlider.getValue());
            panel.updateTimeScaleLabel();
        });

        timeGbc.gridx = 0; timeGbc.gridy = 0;
        timeGbc.weightx = 0.33;
        timeGbc.insets = new Insets(0, 0, 0, 6);
        timeControlsPanel.add(playPause, timeGbc);
        timeGbc = new GridBagConstraints();
        timeGbc.gridx = 1; timeGbc.gridy = 0;
        timeGbc.weightx = 0.67;
        timeGbc.fill = GridBagConstraints.HORIZONTAL;
        timeControlsPanel.add(panel.timeScaleSlider, timeGbc);

        JPanel presetGrid = new JPanel(new GridLayout(2, 3, 4, 4));
        presetGrid.setOpaque(false);
        JButton oneSecondBtn = new JButton("1 s/s");
        oneSecondBtn.addActionListener(_ -> panel.setTimeScaleAndSync(SolarSystemSimulation.TIME_SCALE_MIN));
        JButton oneMinuteBtn = new JButton("1 min/s");
        oneMinuteBtn.addActionListener(_ -> panel.setTimeScaleAndSync(60.0));
        JButton oneHourBtn = new JButton("1 hr/s");
        oneHourBtn.addActionListener(_ -> panel.setTimeScaleAndSync(3600.0));
        JButton dayBtn = new JButton("1 day/s");
        dayBtn.addActionListener(_ -> panel.setTimeScaleAndSync(SolarSystemSimulation.DAY));
        JButton weekBtn = new JButton("1 week/s");
        weekBtn.addActionListener(_ -> panel.setTimeScaleAndSync(7 * SolarSystemSimulation.DAY));
        JButton monthBtn = new JButton("1 month/s");
        monthBtn.addActionListener(_ -> panel.setTimeScaleAndSync(30 * SolarSystemSimulation.DAY));
        JButton threeMonthBtn = new JButton("3 months/s");
        threeMonthBtn.addActionListener(_ -> panel.setTimeScaleAndSync(90 * SolarSystemSimulation.DAY));
        JButton yearBtn = new JButton("1 year/s");
        yearBtn.addActionListener(_ -> panel.setTimeScaleAndSync(SolarSystemSimulation.YEAR));
        JButton decadeBtn = new JButton("10 years/s");
        decadeBtn.addActionListener(_ -> panel.setTimeScaleAndSync(10 * SolarSystemSimulation.YEAR));
        presetGrid.add(oneSecondBtn);
        presetGrid.add(oneMinuteBtn);
        presetGrid.add(oneHourBtn);
        presetGrid.add(dayBtn);
        presetGrid.add(weekBtn);
        presetGrid.add(monthBtn);
        presetGrid.add(threeMonthBtn);
        presetGrid.add(yearBtn);
        presetGrid.add(decadeBtn);
        timeGbc = new GridBagConstraints();
        timeGbc.gridx = 0; timeGbc.gridy = 1;
        timeGbc.gridwidth = 2;
        timeGbc.insets = new Insets(4, 0, 0, 0);
        timeControlsPanel.add(presetGrid, timeGbc);

        panel.timeScaleLabel = new JLabel();
        timeGbc = new GridBagConstraints();
        timeGbc.gridx = 0; timeGbc.gridy = 2;
        timeGbc.gridwidth = 2;
        timeGbc.insets = new Insets(4, 0, 0, 0);
        timeGbc.anchor = GridBagConstraints.WEST;
        timeControlsPanel.add(panel.timeScaleLabel, timeGbc);

        // Editable time-scale row: free-form number + unit dropdown, so the speed can be typed
        // exactly (e.g. "2.5 day/s") instead of only nudged with the slider or presets.
        JPanel scaleEditRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        scaleEditRow.setOpaque(false);
        scaleEditRow.add(new JLabel("Set speed:"));
        panel.timeScaleValueField = new JTextField(7);
        panel.timeScaleUnitCombo = new JComboBox<>(SolarSystemSimulation.TS_UNIT_NAMES);
        panel.timeScaleUnitCombo.setSelectedIndex(SolarSystemSimulation.bestTimeUnitIndex(panel.timeScale));
        scaleEditRow.add(panel.timeScaleValueField);
        scaleEditRow.add(panel.timeScaleUnitCombo);

        // Commit the typed number on Enter or when focus leaves the field.
        panel.timeScaleValueField.addActionListener(_ -> panel.applyTimeFieldInput());
        panel.timeScaleValueField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusLost(java.awt.event.FocusEvent e) { panel.applyTimeFieldInput(); }
        });
        // Changing the unit re-applies the typed number against the newly chosen unit.
        panel.timeScaleUnitCombo.addActionListener(_ -> {
            if (panel.suppressTimeFieldEvents) return;
            panel.applyTimeFieldInput();
        });

        timeGbc = new GridBagConstraints();
        timeGbc.gridx = 0; timeGbc.gridy = 3;
        timeGbc.gridwidth = 2;
        timeGbc.insets = new Insets(4, 0, 0, 0);
        timeGbc.anchor = GridBagConstraints.WEST;
        timeControlsPanel.add(scaleEditRow, timeGbc);

        panel.updateTimeScaleLabel();   // now that the field + combo exist, populate them too

        timeGbc = new GridBagConstraints();
        timeGbc.gridx = 0; timeGbc.gridy = 4;
        timeGbc.gridwidth = 2;
        timeGbc.weighty = 1.0;
        timeControlsPanel.add(Box.createVerticalGlue(), timeGbc);

        JSplitPane leftSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, treePanel, timeControlsPanel);
        leftSplit.setResizeWeight(0.9);
        leftSplit.setDividerLocation(0.9);
         leftSplit.setContinuousLayout(true);
         leftSplit.setDividerSize(6);
         leftSplit.setBorder(null);
         leftNav.add(leftSplit, BorderLayout.CENTER);

        content.add(leftNav, BorderLayout.WEST);
        content.add(panel, BorderLayout.CENTER);
        // Right-hand dock: the tool panels and the lesson panel, each shown when in use.
        panel.transferPlanner = new TransferPlanner(panel.new MissionHost(), () -> panel.showDockPanel(null));
        panel.eventsPanel = new EventsPanel(new EventsPanel.Host() {
            public double simTime() { return panel.simTimeSec; }
            public void jumpTo(double sim) {
                panel.simulationPaused = true;
                panel.goToSimTime(sim);
            }
            public void showInSky(String target) {
                panel.enterSkyView();
                if (target != null && !target.equals("Earth")) panel.skyView().lookAt(target);
            }
            public void showOnGlobe(double sim) { panel.showSolarEclipseOnGlobe(sim); }
        }, () -> panel.showDockPanel(null));
        panel.transferPlanner.setVisible(false);
        panel.eventsPanel.setVisible(false);
        JPanel eastDock = new JPanel();
        eastDock.setLayout(new BoxLayout(eastDock, BoxLayout.X_AXIS));
        eastDock.add(panel.transferPlanner);
        eastDock.add(panel.eventsPanel);
        eastDock.add(panel.lessonStudio);
        content.add(eastDock, BorderLayout.EAST);

        frame.setContentPane(content);
        applySettings(settings, panel, toggles);

        frame.pack();
        restoreBounds(frame, settings);
        frame.setVisible(true);
        // Give the canvas keyboard focus so shortcuts work immediately.
        SwingUtilities.invokeLater(panel::requestFocusInWindow);
        // -Dsss.lesson=N starts lesson N (1-based) at start-up; 0 = just open the lesson list.
        String lessonProp = System.getProperty("sss.lesson");
        if (lessonProp != null) {
            try {
                int n = Integer.parseInt(lessonProp.trim());
                SwingUtilities.invokeLater(() -> {
                    if (n > 0) panel.lessonStudio.startLesson(n - 1); else panel.lessonStudio.showPicker();
                });
                String snap = System.getProperty("sss.lessonsnap");      // debug: path[,step]
                if (snap != null) {
                    String[] sp = snap.split(",");
                    LessonStudio.snapshotLater(panel.lessonStudio, content, sp[0],
                            sp.length > 1 ? Integer.parseInt(sp[1].trim()) : 0, 2500);
                }
            } catch (NumberFormatException ignored) { }
        }
    }
}
