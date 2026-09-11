package astrosim;

import java.awt.event.KeyEvent;
import java.util.BitSet;

/**
 * Newtonian flight input. Tracks held keys (fed by the AWT key listener in
 * {@link Main}) and converts them each frame into:
 *
 *  - attitude rates (arrow keys pitch/yaw, Q/E roll) integrated on the ship's
 *    orientation quaternion, and
 *  - a thrust acceleration vector written into {@code ship.body.extAccel} so
 *    the leapfrog integrator applies it inside both velocity half-kicks.
 *
 * Bindings (SHIP / LANDING modes):
 *   W/S       main engine forward / reverse        A/D  lateral left / right
 *   R/F       vertical up / down (ship frame)      Q/E  roll
 *   Arrows    pitch / yaw
 *   Shift     throttle up      Ctrl  throttle down  Z/X  full / cut throttle
 *   Space     retro-burn: full thrust opposite velocity relative to the
 *             current gravitational parent (the landing aid)
 */
public final class ShipController {

    private static final double TURN_RATE = Math.toRadians(75);   // rad/s at full deflection
    private static final double THROTTLE_RATE = 0.6;              // fraction per second

    private final BitSet keys = new BitSet(256);

    public synchronized void press(int keyCode)   { if (keyCode < 256) keys.set(keyCode); }
    public synchronized void release(int keyCode) { if (keyCode < 256) keys.clear(keyCode); }
    public synchronized void clearAll()           { keys.clear(); }
    private synchronized boolean down(int k)      { return k < 256 && keys.get(k); }

    /** True while the retro-burn key is held (used by the mode manager / HUD). */
    public boolean retroHeld() { return down(KeyEvent.VK_SPACE); }
    public boolean anyTranslationHeld() {
        return down(KeyEvent.VK_W) || down(KeyEvent.VK_S) || down(KeyEvent.VK_A)
            || down(KeyEvent.VK_D) || down(KeyEvent.VK_R) || down(KeyEvent.VK_F);
    }

    /**
     * Update attitude, throttle, thrust and fuel for one frame.
     *
     * @param ship      the spacecraft (may be landed — thrust still computed for liftoff checks)
     * @param primary   current gravitational parent body (for retro-burn direction), may be null
     * @param dtReal    real seconds since last frame (attitude/throttle feel)
     * @param dtSim     simulated seconds this frame (fuel burn)
     */
    public void update(Spacecraft ship, NBody.Body primary, double dtReal, double dtSim) {
        /* ---- attitude ---- */
        double pitch = (down(KeyEvent.VK_UP) ? 1 : 0) - (down(KeyEvent.VK_DOWN) ? 1 : 0);
        double yaw   = (down(KeyEvent.VK_LEFT) ? 1 : 0) - (down(KeyEvent.VK_RIGHT) ? 1 : 0);
        double roll  = (down(KeyEvent.VK_E) ? 1 : 0) - (down(KeyEvent.VK_Q) ? 1 : 0);
        if (pitch != 0 || yaw != 0 || roll != 0) {
            Vec3 omega = new Vec3(pitch * TURN_RATE, yaw * TURN_RATE, roll * TURN_RATE);
            ship.orientation.integrateLocal(omega, dtReal);
        }

        /* ---- throttle ---- */
        if (down(KeyEvent.VK_Z)) ship.throttle = 1;
        if (down(KeyEvent.VK_X)) ship.throttle = 0;
        if (down(KeyEvent.VK_SHIFT))   ship.throttle = Math.min(1, ship.throttle + THROTTLE_RATE * dtReal);
        if (down(KeyEvent.VK_CONTROL)) ship.throttle = Math.max(0, ship.throttle - THROTTLE_RATE * dtReal);

        /* ---- thrust direction ---- */
        Vec3 dirWorld = null;
        double commanded = 0;

        if (retroHeld() && primary != null) {
            // Retro-burn: full thrust opposite parent-relative velocity (kills descent/orbit speed).
            Vec3 vRel = ship.body.v.sub(primary.v);
            if (vRel.lengthSq() > 1e-6) {
                dirWorld = vRel.scale(-1).normalized();
                commanded = Spacecraft.MAX_THRUST;
            }
        } else {
            Vec3 local = new Vec3(
                    (down(KeyEvent.VK_D) ? 1 : 0) - (down(KeyEvent.VK_A) ? 1 : 0),
                    (down(KeyEvent.VK_R) ? 1 : 0) - (down(KeyEvent.VK_F) ? 1 : 0),
                    (down(KeyEvent.VK_W) ? 1 : 0) - (down(KeyEvent.VK_S) ? 1 : 0));
            if (local.lengthSq() > 0 && ship.throttle > 0) {
                dirWorld = ship.orientation.rotate(local.normalized());
                commanded = ship.throttle * Spacecraft.MAX_THRUST;
            }
        }

        /* ---- apply: a_thrust = F / m, fuel via mdot = F / (Isp g0) ---- */
        if (dirWorld != null && commanded > 0 && !ship.crashed) {
            double achieved = ship.consumeFuel(commanded, Math.abs(dtSim));
            ship.updateMass();
            double a = achieved / ship.totalMass();
            ship.thrustAccel.set(dirWorld.x * a, dirWorld.y * a, dirWorld.z * a);
        } else {
            ship.thrustAccel.set(0, 0, 0);
        }
        ship.body.extAccel.set(ship.thrustAccel);
    }
}
