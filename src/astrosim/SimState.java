package astrosim;

import static astrosim.Bodies.*;

/** Shared mutable state between the renderer, UI controls and HUD overlay. */
public final class SimState {
    /** Simulated seconds per real second. */
    public volatile double timeScale = 10 * DAY;
    public volatile boolean paused = false;

    public static final double TIME_SCALE_MIN = DAY * 0.1;
    public static final double TIME_SCALE_MAX = 50 * YEAR;

    // selection (planet==-1 & !sunSelected & selectedDynamic==null => nothing; moon==-1 => the planet)
    public volatile int selectedPlanet = -1;
    public volatile int selectedMoon = -1;
    public volatile boolean sunSelected = false;
    public volatile NBody.Body selectedDynamic = null;   // spawned body / spacecraft
    public volatile boolean followSelected = false;

    // view toggles
    public volatile boolean showOrbits = true;
    public volatile boolean showMoons = true;
    public volatile boolean showLabels = true;
    public volatile boolean showStarfield = true;
    public volatile boolean showRings = true;
    public volatile boolean showTrajectory = true;       // predicted flight path overlay

    public boolean hasSelection() { return sunSelected || selectedPlanet >= 0 || selectedDynamic != null; }

    public void selectPlanet(int p) { selectedPlanet = p; selectedMoon = -1; sunSelected = false; selectedDynamic = null; }
    public void selectMoon(int p, int m) { selectedPlanet = p; selectedMoon = m; sunSelected = false; selectedDynamic = null; }
    public void selectSun() { sunSelected = true; selectedPlanet = -1; selectedMoon = -1; selectedDynamic = null; }
    public void selectDynamic(NBody.Body b) { selectedDynamic = b; sunSelected = false; selectedPlanet = -1; selectedMoon = -1; }
    public void clearSelection() { sunSelected = false; selectedPlanet = -1; selectedMoon = -1; selectedDynamic = null; followSelected = false; }
}
