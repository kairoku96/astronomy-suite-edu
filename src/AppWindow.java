import javax.swing.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import com.formdev.flatlaf.FlatDarkLaf;

/**
 * The application window around the simulation canvas: menus, the search bar, the object tree
 * with the time controls, and the right-hand dock (transfer planner, sky events, lessons).
 * Split out of {@link SolarSystemSimulation}, which keeps the simulation and its rendering.
 */
final class AppWindow {
    private AppWindow() {}

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

        JFrame frame = new JFrame("Solar System Simulation");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        SolarSystemSimulation panel = new SolarSystemSimulation();
        panel.parentFrame = frame; // Set frame reference for menu toggling
        // ----- Build professional-style UI frame -----
         // Top-level menu bar
         JMenuBar menuBar = new JMenuBar();

        // Simulation menu
        JMenu simMenu = new JMenu("Simulation");
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
        JMenuItem lunarEclipseItem = new JMenuItem("Show lunar eclipse (Earth)");
        lunarEclipseItem.addActionListener(_ -> panel.showEclipse(false));
        // Real-date scrubbing (planetarium): jump the whole system to a calendar date.
        JMenuItem gotoDateItem = new JMenuItem("Go to date…");
        gotoDateItem.addActionListener(_ -> panel.promptGoToDate(frame));
        JMenuItem todayItem = new JMenuItem("Jump to today");
        todayItem.addActionListener(_ -> {
            panel.simulationPaused = true;
            panel.goToDate(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        });
        JMenuItem epochItem = new JMenuItem("Reset to J2000 epoch");
        epochItem.addActionListener(_ -> {
            panel.goToDate(java.time.LocalDateTime.of(2000, 1, 1, 12, 0));
        });
        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.addActionListener(_ -> System.exit(0));
        simMenu.add(resetViewItem);
        simMenu.addSeparator();
        simMenu.add(gotoDateItem);
        simMenu.add(todayItem);
        simMenu.add(epochItem);
        simMenu.addSeparator();
        simMenu.add(solarEclipseItem);
        simMenu.add(lunarEclipseItem);
        JMenuItem eventsItem = new JMenuItem("Next events (conjunctions, transits, occultations…)…");
        eventsItem.addActionListener(_ -> { panel.showDockPanel(panel.eventsPanel); panel.eventsPanel.search(); });
        simMenu.add(eventsItem);
        simMenu.addSeparator();
        simMenu.add(exitItem);

        // Objects menu — toggle the built-in catalog of real minor bodies
        JMenu objectsMenu = new JMenu("Objects");
        SolarSystemSimulation.StayOpenCheckBoxMenuItem cometsItem = new SolarSystemSimulation.StayOpenCheckBoxMenuItem("Comets  (with tails)", false);
        cometsItem.addActionListener(_ -> { panel.showComets = cometsItem.isSelected(); panel.repaint(); });
        SolarSystemSimulation.StayOpenCheckBoxMenuItem asteroidsItem = new SolarSystemSimulation.StayOpenCheckBoxMenuItem("Asteroids & near-Earth objects", false);
        asteroidsItem.addActionListener(_ -> { panel.showAsteroids = asteroidsItem.isSelected(); panel.repaint(); });
        SolarSystemSimulation.StayOpenCheckBoxMenuItem distantItem = new SolarSystemSimulation.StayOpenCheckBoxMenuItem("Distant objects (centaurs / TNOs)", false);
        distantItem.addActionListener(_ -> { panel.showDistantBodies = distantItem.isSelected(); panel.repaint(); });
        SolarSystemSimulation.StayOpenCheckBoxMenuItem bodyLabelsItem = new SolarSystemSimulation.StayOpenCheckBoxMenuItem("Show object labels", true);
        bodyLabelsItem.addActionListener(_ -> { panel.showSmallBodyLabels = bodyLabelsItem.isSelected(); panel.repaint(); });
        objectsMenu.add(cometsItem);
        objectsMenu.add(asteroidsItem);
        objectsMenu.add(distantItem);
        objectsMenu.addSeparator();
        objectsMenu.add(bodyLabelsItem);

        // View menu – mirrors keyboard toggles
        JMenu viewMenu = new JMenu("View");
        SolarSystemSimulation.StayOpenCheckBoxMenuItem orbitsItem = new SolarSystemSimulation.StayOpenCheckBoxMenuItem("Show planet orbits", true);
        orbitsItem.addActionListener(_ -> {
            panel.showPlanetOrbits = orbitsItem.isSelected();
            panel.repaint();
        });
        SolarSystemSimulation.StayOpenCheckBoxMenuItem ringsItem = new SolarSystemSimulation.StayOpenCheckBoxMenuItem("Show Saturn rings", true);
        ringsItem.addActionListener(_ -> {
            panel.showRings = ringsItem.isSelected();
            panel.repaint();
        });
        SolarSystemSimulation.StayOpenCheckBoxMenuItem labelsItem = new SolarSystemSimulation.StayOpenCheckBoxMenuItem("Show labels", true);
        labelsItem.addActionListener(_ -> {
            panel.showLabels = labelsItem.isSelected();
            panel.repaint();
        });
        SolarSystemSimulation.StayOpenCheckBoxMenuItem shadingItem = new SolarSystemSimulation.StayOpenCheckBoxMenuItem("Enable shading", true);
        shadingItem.addActionListener(_ -> {
            panel.shadingEnabled = shadingItem.isSelected();
            panel.repaint();
        });
        SolarSystemSimulation.StayOpenCheckBoxMenuItem atmosphereItem = new SolarSystemSimulation.StayOpenCheckBoxMenuItem("Atmospheric glow  (A)", true);
        atmosphereItem.addActionListener(_ -> {
            panel.showAtmosphere = atmosphereItem.isSelected();
            panel.repaint();
        });
        SolarSystemSimulation.StayOpenCheckBoxMenuItem eclipsesItem = new SolarSystemSimulation.StayOpenCheckBoxMenuItem("Eclipse shadows  (E)", true);
        eclipsesItem.addActionListener(_ -> {
            panel.showEclipses = eclipsesItem.isSelected();
            panel.repaint();
        });
        SolarSystemSimulation.StayOpenCheckBoxMenuItem cloudsItem = new SolarSystemSimulation.StayOpenCheckBoxMenuItem("Earth clouds  (C)", true);
        cloudsItem.addActionListener(_ -> {
            panel.showClouds = cloudsItem.isSelected();
            panel.repaint();
        });
        SolarSystemSimulation.StayOpenCheckBoxMenuItem nightLightsItem = new SolarSystemSimulation.StayOpenCheckBoxMenuItem("Earth night lights  (I)", true);
        nightLightsItem.addActionListener(_ -> {
            panel.showNightLights = nightLightsItem.isSelected();
            panel.repaint();
        });
        SolarSystemSimulation.StayOpenCheckBoxMenuItem gpuItem = new SolarSystemSimulation.StayOpenCheckBoxMenuItem("GPU sphere shader  (G)", panel.gpuSpheres);
        gpuItem.addActionListener(_ -> {
            boolean want = gpuItem.isSelected();
            if (want) {
                GLSphereRenderer r = panel.gpuRenderer();
                want = r != null && r.isAvailable();
                if (!want) gpuItem.setSelected(false);   // no GPU available: keep CPU path
            }
            panel.gpuSpheres = want;
            panel.repaint();
        });
        SolarSystemSimulation.StayOpenCheckBoxMenuItem selectedOnlyItem = new SolarSystemSimulation.StayOpenCheckBoxMenuItem("Show only selected planet", false);
        selectedOnlyItem.addActionListener(_ -> {
            panel.showOnlySelected = selectedOnlyItem.isSelected();
            panel.repaint();
        });
        SolarSystemSimulation.StayOpenCheckBoxMenuItem baryItem = new SolarSystemSimulation.StayOpenCheckBoxMenuItem("Sun reflex wobble (barycentric frame)", false);
        baryItem.setToolTipText("Off: Sun fixed, orbits steady. On: Sun wobbles about the barycentre "
                + "(the real motion that reveals exoplanets) — noticeable at high time speed.");
        baryItem.addActionListener(_ -> {
            panel.barycentricFrame = baryItem.isSelected();
            panel.computeBarycenter();
            panel.repaint();
        });
        viewMenu.add(orbitsItem);
        viewMenu.add(ringsItem);
        viewMenu.add(labelsItem);
        viewMenu.add(shadingItem);
        viewMenu.add(atmosphereItem);
        viewMenu.add(eclipsesItem);
        viewMenu.add(cloudsItem);
        viewMenu.add(nightLightsItem);
        viewMenu.add(gpuItem);
        viewMenu.addSeparator();
        viewMenu.add(selectedOnlyItem);
        viewMenu.add(baryItem);
        SolarSystemSimulation.StayOpenCheckBoxMenuItem lightItem = new SolarSystemSimulation.StayOpenCheckBoxMenuItem("Apparent positions — light-time from Earth  (T)", false);
        lightItem.setToolTipText("Draw each body where it appears from Earth: where it was when the light now arriving left it. "
                + "A hollow ring marks where it really is.");
        lightItem.addActionListener(_ -> {
            panel.apparentPositions = lightItem.isSelected();
            panel.updateEphemeris();
            panel.repaint();
        });
        viewMenu.add(lightItem);
        viewMenu.addSeparator();
        SolarSystemSimulation.StayOpenCheckBoxMenuItem starsItem = new SolarSystemSimulation.StayOpenCheckBoxMenuItem("Real star field (Yale Bright Star Catalogue)", true);
        starsItem.addActionListener(_ -> {
            panel.showRealStars = starsItem.isSelected();
            panel.repaint();
        });
        SolarSystemSimulation.StayOpenCheckBoxMenuItem constItem = new SolarSystemSimulation.StayOpenCheckBoxMenuItem("Constellation lines", false);
        constItem.addActionListener(_ -> {
            panel.showConstellations = constItem.isSelected();
            panel.repaint();
        });
        viewMenu.add(starsItem);
        viewMenu.add(constItem);
        viewMenu.addSeparator();
        JMenuItem skyItem = new JMenuItem("Sky from Earth…  (K)");
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
            // Dwarf planets (except Pluto) start unchecked
            boolean initialState = panel.planetVisible[i];
            SolarSystemSimulation.StayOpenCheckBoxMenuItem checkbox = new SolarSystemSimulation.StayOpenCheckBoxMenuItem(SolarSystemSimulation.NAMES[i], initialState);
            checkbox.addActionListener(_ -> {
                panel.planetVisible[planetIndex] = checkbox.isSelected();
                panel.updateTreeVisibility();
                panel.repaint();
            });
            planetsMenu.add(checkbox);
        }
        viewMenu.add(planetsMenu);

        // Add asteroid/debris belts submenu
        viewMenu.addSeparator();
        JMenu beltsMenu = new JMenu("Belts & Lines");
        SolarSystemSimulation.StayOpenCheckBoxMenuItem asteroidBeltItem = new SolarSystemSimulation.StayOpenCheckBoxMenuItem("Asteroid Belt", false);
        asteroidBeltItem.addActionListener(_ -> {
            panel.showAsteroidBelt = asteroidBeltItem.isSelected();
            panel.repaint();
        });
        SolarSystemSimulation.StayOpenCheckBoxMenuItem kuiperBeltItem = new SolarSystemSimulation.StayOpenCheckBoxMenuItem("Kuiper Belt", false);
        kuiperBeltItem.addActionListener(_ -> {
            panel.showKuiperBelt = kuiperBeltItem.isSelected();
            panel.repaint();
        });
        SolarSystemSimulation.StayOpenCheckBoxMenuItem iceLinesItem = new SolarSystemSimulation.StayOpenCheckBoxMenuItem("Frost Lines", false);
        iceLinesItem.addActionListener(_ -> {
            panel.showIceLines = iceLinesItem.isSelected();
            panel.repaint();
        });
        beltsMenu.add(asteroidBeltItem);
        beltsMenu.add(kuiperBeltItem);
        beltsMenu.add(iceLinesItem);
        viewMenu.add(beltsMenu);

        // UI Size submenu
        viewMenu.addSeparator();
        JMenu uiSizeMenu = new JMenu("UI Size");
        ButtonGroup uiSizeGroup = new ButtonGroup();

        SolarSystemSimulation.StayOpenRadioButtonMenuItem smallUIItem = new SolarSystemSimulation.StayOpenRadioButtonMenuItem("Small", false);
        smallUIItem.addActionListener(_ -> {
            panel.uiSize = SolarSystemSimulation.UISize.SMALL;
            panel.repaint();
        });
        uiSizeGroup.add(smallUIItem);
        uiSizeMenu.add(smallUIItem);

        SolarSystemSimulation.StayOpenRadioButtonMenuItem normalUIItem = new SolarSystemSimulation.StayOpenRadioButtonMenuItem("Normal", true);
        normalUIItem.addActionListener(_ -> {
            panel.uiSize = SolarSystemSimulation.UISize.NORMAL;
            panel.repaint();
        });
        uiSizeGroup.add(normalUIItem);
        uiSizeMenu.add(normalUIItem);

        SolarSystemSimulation.StayOpenRadioButtonMenuItem largeUIItem = new SolarSystemSimulation.StayOpenRadioButtonMenuItem("Large", false);
        largeUIItem.addActionListener(_ -> {
            panel.uiSize = SolarSystemSimulation.UISize.LARGE;
            panel.repaint();
        });
        uiSizeGroup.add(largeUIItem);
        uiSizeMenu.add(largeUIItem);

        viewMenu.add(uiSizeMenu);

        JMenu helpMenu = new JMenu("Help");
        JMenuItem controlsItem = new JMenuItem("Controls...");
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

                        Shortcuts work from anywhere in the window,
                        except while typing in the search box.

                        Use View menu for:
                        - Asteroid Belt
                        - Kuiper Belt
                        - Frost Lines (H2O and CO2)""",
                "Controls",
                JOptionPane.INFORMATION_MESSAGE));
        JMenuItem aboutItem = new JMenuItem("About");
        aboutItem.addActionListener(_ -> JOptionPane.showMessageDialog(
                frame,
                "Solar System Simulation\n" +
                        "Real-date 3-D model: planets, comets and asteroids integrated under mutual gravity\n" +
                        "(symplectic Wisdom–Holman, JPL Horizons initial conditions); moons on fitted JPL theories.",
                "About",
                JOptionPane.INFORMATION_MESSAGE));
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
        SolarSystemSimulation.StayOpenCheckBoxMenuItem inFlightItem = new SolarSystemSimulation.StayOpenCheckBoxMenuItem("Show spacecraft in flight on the current date", true);
        inFlightItem.addActionListener(_ -> { panel.missionPlayer.setShowAllInFlight(inFlightItem.isSelected()); panel.repaint(); });
        missionsMenu.add(inFlightItem);

        menuBar.add(simMenu);
        menuBar.add(objectsMenu);
        menuBar.add(viewMenu);
        menuBar.add(missionsMenu);

        // Education menu: lessons (run inside this window), plus the orbit overlays for the live sim
        panel.lessonStudio = new LessonStudio(panel, panel.new LessonHost(), panel::onLessonActiveChanged);
        panel.lessonStudio.setVisible(false);
        JMenu educationMenu = new JMenu("Education");
        JMenuItem studioItem = new JMenuItem("Lessons...");
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
        SolarSystemSimulation.StayOpenCheckBoxMenuItem keplersLawItem = new SolarSystemSimulation.StayOpenCheckBoxMenuItem("Kepler's 3rd Law (P²/a³)", false);
        keplersLawItem.addActionListener(_ -> {
            panel.showKeplersLaw = keplersLawItem.isSelected();
            panel.repaint();
        });
        SolarSystemSimulation.StayOpenCheckBoxMenuItem periAphelionItem = new SolarSystemSimulation.StayOpenCheckBoxMenuItem("Perihelion / Aphelion markers", false);
        periAphelionItem.addActionListener(_ -> {
            panel.showPerihelionAphelion = periAphelionItem.isSelected();
            panel.repaint();
        });
        SolarSystemSimulation.StayOpenCheckBoxMenuItem velocityVectorsItem = new SolarSystemSimulation.StayOpenCheckBoxMenuItem("Velocity vectors", false);
        velocityVectorsItem.addActionListener(_ -> {
            panel.showVelocityVectors = velocityVectorsItem.isSelected();
            panel.repaint();
        });
        SolarSystemSimulation.StayOpenCheckBoxMenuItem velocityComparisonItem = new SolarSystemSimulation.StayOpenCheckBoxMenuItem("Velocity labels", false);
        velocityComparisonItem.addActionListener(_ -> {
            panel.showVelocityComparison = velocityComparisonItem.isSelected();
            panel.repaint();
        });
        overlaysMenu.add(keplersLawItem);
        overlaysMenu.add(periAphelionItem);
        overlaysMenu.add(velocityVectorsItem);
        overlaysMenu.add(velocityComparisonItem);
        educationMenu.add(overlaysMenu);


        menuBar.add(educationMenu);
        menuBar.add(helpMenu);
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

        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        frame.setTitle("Solar System Simulation");
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
