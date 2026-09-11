package astrosim;

import javax.swing.JComponent;
import java.awt.*;

import static astrosim.Bodies.*;

/**
 * Transparent, click-through overlay drawn on top of the GL canvas.
 *
 * Preserves the original telemetry set — body labels, the selection info
 * panel (distance, speed, radius, period, eccentricity, inclination, mass)
 * and the time-scale readout — and layers the mode-specific dashboards on
 * top: a mode banner, the SHIP flight panel (absolute velocity, velocity
 * relative to and escape velocity of the current gravitational parent,
 * remaining delta-v / fuel, throttle) and the LANDING surface-frame panel
 * (altitude AGL, vertical climb/sink rate, horizontal surface speed
 * including planetary rotation, touchdown verdict).
 */
public final class HudOverlay extends JComponent {
    private final Scene scene;
    private final OrbitCamera cam;
    private final SimState state;
    private final NBody sim;
    private final SimulationModeManager modeMgr;

    private static final Color PANEL_BG = new Color(20, 22, 28, 215);
    private static final Color PANEL_BORDER = new Color(80, 130, 200, 180);
    private static final Color TEXT = new Color(226, 232, 240);
    private static final Color SUBTLE = new Color(150, 160, 175);
    private static final Color ACCENT = new Color(96, 180, 240);
    private static final Color GOOD = new Color(120, 230, 150);
    private static final Color WARN = new Color(240, 180, 90);
    private static final Color BAD  = new Color(245, 105, 95);

    public HudOverlay(Scene scene, OrbitCamera cam, SimState state, NBody sim, SimulationModeManager modeMgr) {
        this.scene = scene; this.cam = cam; this.state = state; this.sim = sim; this.modeMgr = modeMgr;
        setOpaque(false);
    }

    /** Click-through: never intercept mouse events — they fall to the GL panel below. */
    @Override public boolean contains(int x, int y) { return false; }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        SimulationModeManager.Mode mode = modeMgr.mode();

        if (state.showLabels) drawLabels(g2);
        drawModeBanner(g2, mode);
        drawTimeReadout(g2);
        if (mode != SimulationModeManager.Mode.SANDBOX) {
            drawFlightPanel(g2, mode);
            drawStatusBanner(g2);
        }
        if (state.hasSelection()) drawInfoPanel(g2);
        drawHelpHint(g2, mode);
        g2.dispose();
    }

    /* scale GL pixel coords -> component coords (HiDPI safe) */
    private double[] toComponent(Vec3 worldAU) {
        double[] win = cam.project(worldAU.x, worldAU.y, worldAU.z);
        if (win == null) return null;
        int vw = cam.viewportWidth(), vh = cam.viewportHeight();
        if (vw == 0 || vh == 0) return null;
        double sx = (double) getWidth() / vw, sy = (double) getHeight() / vh;
        return new double[]{ win[0] * sx, win[1] * sy };
    }

    private void drawLabels(Graphics2D g2) {
        g2.setFont(getFont().deriveFont(Font.PLAIN, 12f));
        // Sun
        label(g2, scene.sunWorldAU(), "Sun", new Color(255, 220, 130));
        for (int k = 0; k < COUNT; k++) {
            Color c = COLORS[k];
            label(g2, scene.planetWorldAU(k), NAMES[k],
                    new Color(Math.min(255, c.getRed() + 40), Math.min(255, c.getGreen() + 40), Math.min(255, c.getBlue() + 40)));
        }
        // moon labels only for the selected planet
        if (state.selectedPlanet >= 0) {
            int k = state.selectedPlanet;
            for (int m = 0; m < MOON_NAMES[k].length; m++)
                label(g2, scene.moonWorldAU(k, m), MOON_NAMES[k][m], SUBTLE);
        }
        // runtime bodies (spawned masses, spacecraft)
        for (NBody.Body b : sim.dynamicBodies())
            label(g2, b.r.scale(1.0 / AU), b.name, new Color(170, 220, 255));
    }

    private void label(Graphics2D g2, Vec3 worldAU, String text, Color color) {
        double[] p = toComponent(worldAU);
        if (p == null) return;
        if (p[0] < -40 || p[1] < -40 || p[0] > getWidth() + 40 || p[1] > getHeight() + 40) return;
        int x = (int) p[0] + 8, y = (int) p[1] - 6;
        g2.setColor(new Color(0, 0, 0, 140));
        g2.drawString(text, x + 1, y + 1);
        g2.setColor(color);
        g2.drawString(text, x, y);
        g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 160));
        g2.fillOval((int) p[0] - 2, (int) p[1] - 2, 4, 4);
    }

    private void drawModeBanner(Graphics2D g2, SimulationModeManager.Mode mode) {
        String txt = switch (mode) {
            case SANDBOX -> "SANDBOX";
            case SHIP -> "SHIP — Newtonian flight";
            case LANDING -> "LANDING — " + modeMgr.primaryName() + " surface frame";
        };
        g2.setFont(getFont().deriveFont(Font.BOLD, 13f));
        FontMetrics fm = g2.getFontMetrics();
        int w = fm.stringWidth(txt) + 24, h = 28;
        roundPanel(g2, 14, 14, w, h);
        g2.setColor(mode == SimulationModeManager.Mode.SANDBOX ? ACCENT
                : mode == SimulationModeManager.Mode.SHIP ? GOOD : WARN);
        g2.drawString(txt, 26, 14 + 19);
        g2.setFont(getFont().deriveFont(Font.PLAIN, 11f));
        g2.setColor(SUBTLE);
        g2.drawString("[1] sandbox   [2] ship   [3] landing", 14, 14 + h + 14);
    }

    private void drawTimeReadout(Graphics2D g2) {
        String speed = formatTimeScale(state.timeScale);
        String txt = state.paused ? "PAUSED" : (speed + " / sec");
        g2.setFont(getFont().deriveFont(Font.BOLD, 13f));
        FontMetrics fm = g2.getFontMetrics();
        int w = fm.stringWidth(txt) + 24, h = 30;
        int x = (getWidth() - w) / 2, y = getHeight() - h - 14;
        roundPanel(g2, x, y, w, h);
        g2.setColor(state.paused ? WARN : ACCENT);
        g2.drawString(txt, x + 12, y + 20);
    }

    /* ---- flight & landing telemetry ---- */

    private void drawFlightPanel(Graphics2D g2, SimulationModeManager.Mode mode) {
        Spacecraft ship = modeMgr.ship();
        if (ship == null) return;

        java.util.List<String> lines = new java.util.ArrayList<>();
        java.util.List<Color> colors = new java.util.ArrayList<>();
        java.util.function.BiConsumer<String, Color> add = (s, c) -> { lines.add(s); colors.add(c); };

        String primary = modeMgr.primaryName();
        double vEsc = modeMgr.escapeVelocity();
        double vRel = modeMgr.speedRelPrimary();

        add.accept("FLIGHT TELEMETRY", ACCENT);
        add.accept(String.format("Abs velocity:   %8.3f km/s", modeMgr.speedAbsolute() / 1000), TEXT);
        add.accept(String.format("Rel %-8s    %8.3f km/s", primary + ":", vRel / 1000), TEXT);
        add.accept(String.format("Escape vel:     %8.3f km/s %s", vEsc / 1000, vRel >= vEsc ? "(ESCAPING)" : ""),
                vRel >= vEsc ? WARN : TEXT);
        add.accept(String.format("Delta-v left:   %8.3f km/s", ship.deltaVRemaining() / 1000),
                ship.deltaVRemaining() < 500 ? BAD : TEXT);
        add.accept(String.format("Fuel:           %7.1f %%   (%,.0f kg)", ship.fuelFraction() * 100, ship.fuelMass),
                ship.fuelFraction() < 0.1 ? BAD : TEXT);
        add.accept(String.format("Throttle:       %7.0f %%   thrust %.1f m/s²",
                ship.throttle * 100, ship.thrustAccel.length()), TEXT);
        add.accept(String.format("Mass:           %,8.0f kg", ship.totalMass()), SUBTLE);
        add.accept(String.format("Parent: %s   dist %s", primary, fmtDist(modeMgr.distToPrimary())), SUBTLE);
        add.accept(String.format("Local gravity:  %8.3f m/s²", modeMgr.localGravity()), SUBTLE);

        if (mode == SimulationModeManager.Mode.LANDING) {
            double vv = modeMgr.verticalSpeed();
            add.accept("— SURFACE FRAME —", WARN);
            add.accept(String.format("Altitude AGL:   %s", fmtDist(Math.max(0, modeMgr.altitudeAGL()))), TEXT);
            add.accept(String.format("Vertical vel:   %+8.2f m/s %s", vv, vv >= 0 ? "▲" : "▼"),
                    vv < -Spacecraft.CRASH_SPEED ? BAD : (vv < 0 ? WARN : GOOD));
            add.accept(String.format("Horiz surface:  %8.2f m/s", modeMgr.horizontalSurfaceSpeed()), TEXT);
            add.accept(String.format("Touchdown limit: %.1f m/s (hold SPACE to retro-burn)", Spacecraft.CRASH_SPEED), SUBTLE);
        }

        g2.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        FontMetrics fm = g2.getFontMetrics();
        int w = 0;
        for (String l : lines) w = Math.max(w, fm.stringWidth(l));
        w += 28;
        int lh = fm.getHeight() + 2;
        int h = lh * lines.size() + 22;
        int x = 14, y = 76;
        roundPanel(g2, x, y, w, h);
        int ty = y + 14 + fm.getAscent();
        for (int i = 0; i < lines.size(); i++) {
            g2.setColor(colors.get(i));
            g2.drawString(lines.get(i), x + 14, ty);
            ty += lh;
        }
    }

    private void drawStatusBanner(Graphics2D g2) {
        Spacecraft ship = modeMgr.ship();
        if (ship == null || (!ship.landed && !ship.crashed)) return;
        String txt = ship.crashed
                ? String.format("CRASHED — touchdown at %.1f m/s (limit %.0f).  Press 2 to respawn.",
                        -ship.touchdownSpeed, Spacecraft.CRASH_SPEED)
                : String.format("LANDED on %s — touchdown %.2f m/s. Throttle up + R/W to lift off.",
                        ship.landedPlanet >= 0 ? NAMES[ship.landedPlanet] : "surface", Math.abs(ship.touchdownSpeed));
        g2.setFont(getFont().deriveFont(Font.BOLD, 15f));
        FontMetrics fm = g2.getFontMetrics();
        int w = fm.stringWidth(txt) + 32, h = 36;
        int x = (getWidth() - w) / 2, y = 60;
        roundPanel(g2, x, y, w, h);
        g2.setColor(ship.crashed ? BAD : GOOD);
        g2.drawString(txt, x + 16, y + 24);
    }

    /* ---- selection info (original telemetry, preserved) ---- */

    private void drawInfoPanel(Graphics2D g2) {
        String[] lines = infoLines();
        if (lines == null) return;
        g2.setFont(getFont().deriveFont(Font.PLAIN, 12f));
        FontMetrics fm = g2.getFontMetrics();
        int w = 0;
        for (String l : lines) w = Math.max(w, fm.stringWidth(l));
        w += 28;
        int lh = fm.getHeight() + 2;
        int h = lh * lines.length + 22;
        int x = getWidth() - w - 16, y = 16;
        roundPanel(g2, x, y, w, h);

        int ty = y + 16 + fm.getAscent();
        g2.setFont(getFont().deriveFont(Font.BOLD, 14f));
        g2.setColor(ACCENT);
        g2.drawString(lines[0], x + 14, ty);
        g2.setFont(getFont().deriveFont(Font.PLAIN, 12f));
        ty += lh + 4;
        for (int i = 1; i < lines.length; i++) {
            g2.setColor(lines[i].startsWith("  ") ? SUBTLE : TEXT);
            g2.drawString(lines[i], x + 14, ty);
            ty += lh;
        }
    }

    private String[] infoLines() {
        if (state.sunSelected) {
            return new String[]{
                "Sun",
                "Type: G-type star",
                "Radius: 696,340 km",
                "Mass: 1.989 × 10³⁰ kg",
                "Surface: ~5,778 K"
            };
        }
        NBody.Body dyn = state.selectedDynamic;
        if (dyn != null) {
            double distAu = dyn.r.distance(sim.sunPos()) / AU;
            double speed = dyn.v.sub(sim.sun().v).length() / 1000.0;
            return new String[]{
                dyn.name,
                "Type: " + (dyn.name.equals("Spacecraft") ? "spacecraft" : "spawned body"),
                String.format("Mass: %.3e kg", dyn.mass),
                String.format("Sun distance: %.4f AU", distAu),
                String.format("Heliocentric speed: %.3f km/s", speed),
                String.format("Velocity: (%.2f, %.2f, %.2f) km/s", dyn.v.x / 1000, dyn.v.y / 1000, dyn.v.z / 1000),
                String.format("Position: (%.3f, %.3f, %.3f) AU", dyn.r.x / AU, dyn.r.y / AU, dyn.r.z / AU)
            };
        }
        int k = state.selectedPlanet;
        if (k < 0) return null;
        if (state.selectedMoon >= 0) {
            int m = state.selectedMoon;
            return new String[]{
                MOON_NAMES[k][m],
                "Moon of " + NAMES[k],
                String.format("Orbit period: %.2f days", MOON_PERIOD_DAYS[k][m]),
                String.format("Radius: %,.0f km", MOON_RADIUS_M[k][m] / 1000.0),
                String.format("Eccentricity: %.4f", MOON_ECC[k][m])
            };
        }
        double distAu = sim.planetDist(k) / AU;
        double speed = sim.planetSpeed(k) / 1000.0;
        return new String[]{
            NAMES[k],
            "Type: " + BODY_TYPE[k].replace('_', ' '),
            String.format("Mass: %.4e kg", PLANET_MASS[k]),
            String.format("Distance: %.3f AU", distAu),
            String.format("  ( %,.0f thousand km )", sim.planetDist(k) / 1e6),
            String.format("Speed: %.2f km/s", speed),
            String.format("Radius: %,.0f km", RADIUS_M[k] / 1000.0),
            String.format("Period: %.2f yr", PERIOD_Y[k]),
            String.format("Eccentricity: %.4f", ECC[k]),
            String.format("Inclination: %.2f°", INCLINATION_DEG[k]),
            String.format("Rotation: %.2f h%s", Math.abs(ROTATION_PERIOD_H[k]),
                    ROTATION_PERIOD_H[k] < 0 ? " (retrograde)" : ""),
            String.format("SOI radius: %s", fmtDist(soiRadius(k)))
        };
    }

    private void drawHelpHint(Graphics2D g2, SimulationModeManager.Mode mode) {
        String hint = mode == SimulationModeManager.Mode.SANDBOX
                ? "Drag: rotate  •  Right-drag: pan  •  Scroll: zoom  •  Click: select  •  Space: pause  •  2: fly the ship"
                : "W/S A/D R/F: thrust  •  Arrows/Q/E: attitude  •  Shift/Ctrl: throttle  •  Space: retro-burn  •  ,/.: time warp  •  1: sandbox";
        g2.setFont(getFont().deriveFont(Font.PLAIN, 11f));
        g2.setColor(SUBTLE);
        g2.drawString(hint, 14, getHeight() - 16);
    }

    private void roundPanel(Graphics2D g2, int x, int y, int w, int h) {
        g2.setColor(PANEL_BG);
        g2.fillRoundRect(x, y, w, h, 14, 14);
        g2.setColor(PANEL_BORDER);
        g2.drawRoundRect(x, y, w, h, 14, 14);
    }

    private static String fmtDist(double meters) {
        if (meters < 10_000) return String.format("%,.0f m", meters);
        if (meters < 1e9) return String.format("%,.1f km", meters / 1000);
        if (meters < 0.05 * AU) return String.format("%,.0f thousand km", meters / 1e6);
        return String.format("%.4f AU", meters / AU);
    }

    static String formatTimeScale(double s) {
        if (s < 60) return String.format("%.1f s", s);
        if (s < 3600) return String.format("%.1f min", s / 60);
        if (s < DAY) return String.format("%.1f hr", s / 3600);
        if (s < YEAR) return String.format("%.1f days", s / DAY);
        return String.format("%.2f yr", s / YEAR);
    }
}
