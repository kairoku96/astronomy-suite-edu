import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Lesson: Reading the Sky (AST221 Day 3). The real sky over the UTM campus on the evening
 * the slides use (Sept 21, 2026): altitude and azimuth, the celestial pole at the observer's
 * latitude, RA and Dec, sidereal time and hour angle, and planning an observation of the real
 * Saturn, then near opposition.
 */
final class SkyLesson extends LessonKit {

    SkyLesson(LessonStudio.Host host) { super(host); }

    static final String[] PLACES = {"Mississauga", "Mauna Kea", "Quito", "Reykjavík", "North Pole"};
    static final double[][] PLACE_LL = {{UTM_LAT, UTM_LON}, {19.8207, -155.4681}, {-0.1807, -78.4678}, {64.1466, -21.9426}, {90, 0}};
    static final String[] PLACE_TZ = {TORONTO, "Pacific/Honolulu", "America/Guayaquil", "Atlantic/Reykjavik", "UTC"};
    int place;
    /** The step's view, fixed when the step starts so the user can look around freely. */
    double[] view = {180, 30, 100};
    /** Saturn's rise / transit / set tonight (sim seconds), found once per step. */
    double[] rts;

    void atUtm() { place = 0; host.skyPlace(UTM, UTM_LAT, UTM_LON, TORONTO); }
    /** Sept 21, 2026, 9 pm EDT (01:00 UTC on the 22nd): the evening of the slides' planning example. */
    void evening() { host.goToUTC(2026, 9, 22, 1, 0); }
    void setPlace(int k) {
        place = k;
        host.skyPlace(k == 0 ? UTM : PLACES[k], PLACE_LL[k][0], PLACE_LL[k][1], PLACE_TZ[k]);
    }

    /**
     * 9 pm local time on Sept 21, 2026 at the chosen place, so its stars are out. At the equinox the
     * Sun circles the North Pole's horizon all day, so there it is 9 pm on Dec 21, in the polar night.
     */
    void nightAtPlace() {
        java.time.ZonedDateTime local = place == 4
                ? java.time.ZonedDateTime.of(2026, 12, 21, 21, 0, 0, 0, java.time.ZoneOffset.UTC)
                : java.time.ZonedDateTime.of(2026, 9, 21, 21, 0, 0, 0, java.time.ZoneId.of(PLACE_TZ[place]));
        java.time.ZonedDateTime u = local.withZoneSameInstant(java.time.ZoneOffset.UTC);
        host.goToUTC(u.getYear(), u.getMonthValue(), u.getDayOfMonth(), u.getHour(), u.getMinute());
    }

    /** Where to look: centred on a body's azimuth, a little above it. */
    double[] lookAt(String name, double fov) {
        double[] b = host.skyBody(name);
        return new double[]{Math.round(b[1]), Math.round(Math.max(18, b[0] + 8)), fov};
    }

    /** Hour angle LST − RA, wrapped to (−12 h, 12 h]; negative = east of the meridian, still rising. */
    static double hourAngle(double lst, double ra) {
        double h = ((lst - ra) % 24 + 24) % 24;
        return h > 12 ? h - 24 : h;
    }
    static String hm(double hours) {
        double a = Math.abs(hours);
        int h = (int) a, m = (int) Math.round((a - h) * 60);
        if (m == 60) { h++; m = 0; }
        return (hours < 0 ? "−" : "+") + h + "h" + String.format("%02d", m) + "m";
    }

    void placeRows(LessonStudio.Hud hud) {
        hud.row("place", (place == 0 ? UTM : PLACES[place]) + String.format("  (%.1f°%s)", Math.abs(host.skyLat()), host.skyLat() >= 0 ? "N" : "S"));
        hud.row("local time", host.skyLocalTime().replaceAll("\\s+\\(.*\\)$", ""));
    }

    /** Points of a circle of constant declination (a body's daily path across the sky). */
    double[][] decCircle(double decDeg, int n) {
        double[][] p = new double[n + 1][];
        for (int i = 0; i <= n; i++) p[i] = host.skyEnuRaDec(24.0 * i / n, decDeg);
        return p;
    }
    /** Draw only the part of a polyline above the horizon. */
    static void aboveHorizon(LessonStudio.R3 r, double[][] p, Color c, float w, boolean dashed) {
        List<double[]> run = new ArrayList<>();
        for (int i = 0; i <= p.length; i++) {
            boolean up = i < p.length && p[i][2] > -0.002;
            if (up) run.add(p[i]);
            if ((!up || i == p.length) && run.size() > 1) { r.polyline(run.toArray(new double[0][]), false, c, w, dashed); }
            if (!up) run.clear();
        }
    }

    LessonStudio.LessonDef build() {
        List<LessonStudio.Step> s = new ArrayList<>();

        s.add(step("Altitude and azimuth", """
                This is the real sky over the UTM campus at **9 pm on September 21, 2026**, the
                evening the course slides use. The bright "star" low in the east is
                **Saturn**.

                To say where something is in _your_ sky you need two angles:

                {k:Altitude} is the angle up from the horizon: 0° on the horizon, 90° straight
                overhead at the {k:zenith}.

                {k:Azimuth} is the direction around the horizon, measured from **north through
                east**: N = 0°, E = 90°, S = 180°, W = 270°.

                > The orange grid marks every 15° of altitude and 30° of azimuth. Drag to look
                around; scroll to zoom; double-click to come back.
                """)
                .sky(() -> view)
                .enter(() -> {
                    atUtm(); evening(); host.setTime(120);
                    host.skyLayers(true, false, true, true);
                    view = lookAt("Saturn", 100);
                })
                .controls(c -> timeSlider(c, "min", 60, 30))
                .scene((r, hud, t) -> {
                    double[] sat = host.skyEnu("Saturn"), b = host.skyBody("Saturn");
                    double az = b[1], alt = b[0];
                    double[] foot = enu(az, 0);
                    arc(r, foot, sat, PERI_C, 3f, false);
                    r.label(lerp(foot, sat, 0.5), String.format("altitude %.0f°", alt), PERI_C, 13f, 62, 0);
                    int n = 60;
                    double[][] h = new double[n + 1][];
                    for (int i = 0; i <= n; i++) h[i] = enu(az * i / n, 0);
                    r.polyline(h, false, APO_C, 3f, false);
                    r.label(enu(az * 0.8, 0), String.format("azimuth %.0f° (from N through E)", az), APO_C, 13f, 0, 22);
                    r.ring(sat, 12, SUN_C, 2f);
                    r.label(v(0, 0, 1), "zenith (altitude 90°)", INK, 12f, 0, -14);
                    r.dot(v(0, 0, 1), 4, INK);
                    hud.title("Saturn from Mississauga", ACCENT);
                    placeRows(hud);
                    hud.gap();
                    hud.row("altitude", String.format("%.1f°", alt), PERI_C);
                    hud.row("azimuth", String.format("%.1f°", az), APO_C);
                    hud.formula("altitude 0° (horizon) → 90° (zenith);   azimuth N 0°, E 90°, S 180°, W 270°");
                }));

        s.add(step("The pole star sits at your latitude", """
                Earth spins about an axis that points at the {k:north celestial pole} (NCP), close
                to Polaris. As Earth turns, every star circles that point once a day. Speed up time
                and watch the sky turn around it.

                Here is the key fact: **the altitude of the NCP equals your latitude**. At the North
                Pole it is overhead; on the equator it sits on the northern horizon. In Mississauga
                it is 43.5° up.

                Stars closer to the pole than your latitude never dip below the horizon. They are
                {g:circumpolar}.

                > Pick a different place and watch the pole move. Each is shown at 9 pm local time
                (the North Pole in December: at the equinox the Sun skims its horizon all day).
                """)
                .sky(() -> view)
                .enter(() -> {
                    setPlace(place); nightAtPlace(); host.setTime(900);
                    host.skyLayers(true, false, true, true);
                    view = new double[]{0, Math.max(25, Math.min(70, host.skyLat() * 0.8 + 8)), 110};
                })
                .controls(c -> {
                    c.choices("Observer", PLACES, () -> place, k -> {
                        setPlace(k);
                        nightAtPlace();
                        view = new double[]{0, Math.max(25, Math.min(70, host.skyLat() * 0.8 + 8)), 110};
                    });
                    timeSlider(c, "min", 60, 60);
                })
                .scene((r, hud, t) -> {
                    double[] ncp = host.skyEnuRaDec(0, 90);
                    double lat = host.skyLat(), alt = altOf(ncp);
                    double[] foot = enu(0, 0);
                    if (alt > 0.5) {
                        arc(r, foot, ncp, PERI_C, 3f, false);
                        r.label(lerp(foot, ncp, 0.5), String.format("altitude %.1f° = latitude", alt), PERI_C, 13f, 90, 0);
                    }
                    if (lat < 89.5 && lat > 0.5) {
                        skyCircle(r, ncp, lat, alpha(GREEN, 200), 2f, true);
                        r.label(norm(add(scaleV(ncp, Math.cos(Math.toRadians(lat))), scaleV(v(0, 0, 1), Math.sin(Math.toRadians(lat))))),
                                "circumpolar stars stay inside this circle", GREEN, 12f, 0, -14);
                    }
                    r.ring(ncp, 9, SUN_C, 2.2f);
                    r.label(ncp, "north celestial pole", SUN_C, 12.5f, 0, -22);
                    hud.title("The celestial pole", ACCENT);
                    placeRows(hud);
                    hud.gap();
                    hud.row("observer's latitude", String.format("%.1f°", lat), PERI_C);
                    hud.row("altitude of the NCP", String.format("%.1f°", alt), PERI_C);
                    hud.formula("altitude of the celestial pole = observer's latitude");
                }));

        s.add(step("Right ascension and declination", """
                Altitude and azimuth change all night and depend on where you stand. Astronomers
                also need a grid fixed to the **stars**: Earth's latitude and longitude, projected
                onto the sky.

                {k:Declination} (Dec) is sky latitude: 0° on the {k:celestial equator} (above
                Earth's equator), +90° at the NCP.

                {k:Right ascension} (RA) is sky longitude, measured **eastward in hours** (24 h =
                360°, so 1 h = 15°). RA = 0 h is where the Sun crosses the equator at the March
                equinox. Tonight that point is in Pisces, right beside Saturn.

                Stars keep their RA and Dec for centuries (they drift only by proper motion). The Sun,
                Moon and planets wander through the grid.

                > The green grid is RA / Dec. Speed up time: it turns with the stars, while
                altitude / azimuth stays put.
                """)
                .sky(() -> view)
                .enter(() -> {
                    atUtm(); evening(); host.setTime(120);
                    host.skyLayers(false, true, true, true);
                    view = new double[]{135, 50, 125};
                })
                .controls(c -> timeSlider(c, "min", 60, 60))
                .scene((r, hud, t) -> {
                    double[][] eq = decCircle(0, 192);
                    aboveHorizon(r, eq, TEAL, 3f, false);
                    for (int hr = 0; hr < 24; hr++) {
                        double[] p = host.skyEnuRaDec(hr, 0);
                        if (p[2] < 0.02) continue;
                        r.dot(p, 3, TEAL);
                        r.label(p, hr + "h", TEAL, 11.5f, 0, 14);
                    }
                    double[] q = host.skyEnuRaDec(0, 0);
                    if (q[2] > 0) r.label(q, "RA 0h: the March equinox point", TEAL, 12f, 0, 32);
                    double[] eqLabel = host.skyEnuRaDec(host.skyLst() - 1.5, 0);
                    r.label(eqLabel, "celestial equator (Dec 0°)", TEAL, 12.5f, 0, -16);
                    double[] dn = host.skyBody("Deneb"), sb = host.skyBody("Saturn");
                    double[] dnFoot = host.skyEnuRaDec(dn[2], 0);
                    arc(r, dnFoot, host.skyEnu("Deneb"), VIOLET, 3f, false);
                    r.label(lerp(dnFoot, host.skyEnu("Deneb"), 0.5), String.format("Dec %+.0f°", dn[3]).replace('-', '−'), VIOLET, 12.5f, 44, 0);
                    r.label(host.skyEnu("Deneb"), "Deneb", VIOLET, 12.5f, 0, -18);
                    r.ring(host.skyEnu("Saturn"), 11, SUN_C, 2f);
                    hud.title("The equatorial grid", TEAL);
                    placeRows(hud);
                    hud.gap();
                    hud.row("Saturn  RA", hms(sb[2]), SUN_C);
                    hud.row("Saturn  Dec", dms(sb[3]), SUN_C);
                    hud.row("Deneb  RA", hms(dn[2]), VIOLET);
                    hud.row("Deneb  Dec", dms(dn[3]), VIOLET);
                    hud.formula("RA: 0h → 24h eastward (1h = 15°);   Dec: −90° → +90°");
                }));

        s.add(step("Sidereal time and hour angle", """
                The {k:meridian} is the line from north through the zenith to south. A star is
                highest when it crosses it ({k:transit}).

                {k:Local sidereal time} (LST) is simply **the RA that is on your meridian right
                now**. It runs about 4 minutes a day faster than clock time, because Earth must turn
                a little more than once to face the Sun again.

                A target's {k:hour angle} says how long ago it transited:

                $ HA = LST − RA

                HA < 0: still rising in the east, it transits in |HA| hours. HA > 0: already past the
                meridian, sinking in the west.

                > Speed up time and watch Saturn's hour angle count up to zero as it reaches the
                meridian.
                """)
                .sky(() -> view)
                .enter(() -> {
                    atUtm(); evening(); host.setTime(300);
                    host.skyLayers(false, true, true, true);
                    view = new double[]{160, 38, 115};
                })
                .controls(c -> timeSlider(c, "min", 60, 30))
                .scene((r, hud, t) -> {
                    double[] n0 = enu(0, 0), s0 = enu(180, 0), z = v(0, 0, 1);
                    arc(r, n0, z, alpha(INK, 170), 2f, true);
                    arc(r, z, s0, alpha(INK, 170), 2f, true);
                    r.label(enu(180, 62), "meridian", INK, 12.5f, 0, 0);
                    double lst = host.skyLst();
                    double[] sb = host.skyBody("Saturn");
                    double ha = hourAngle(lst, sb[2]);
                    // Saturn's path along its circle of declination, from the meridian to Saturn.
                    int n = 40;
                    double[][] p = new double[n + 1][];
                    for (int i = 0; i <= n; i++) p[i] = host.skyEnuRaDec(lst - ha * i / n, sb[3]);
                    r.polyline(p, false, ORANGE, 3f, false);
                    r.label(p[n / 2], "HA = " + hm(ha), ORANGE, 13f, 0, -16);
                    double[] mer = host.skyEnuRaDec(lst, 0);
                    r.label(mer, "on the meridian now: RA " + hms(lst).substring(0, 6), TEAL, 12f, 0, 18);
                    r.ring(host.skyEnu("Saturn"), 11, SUN_C, 2f);
                    hud.title("Hour angle", ORANGE);
                    placeRows(hud);
                    hud.gap();
                    hud.row("local sidereal time", hms(lst), TEAL);
                    hud.row("Saturn's RA", hms(sb[2]), SUN_C);
                    hud.row("hour angle  LST − RA", hm(ha), ORANGE);
                    hud.row(ha < 0 ? "transits in" : "transited", hm(Math.abs(ha)).substring(1) + (ha < 0 ? "" : " ago"));
                    hud.formula("HA = LST − RA");
                }));

        s.add(step("Planning: can I see Saturn tonight?", """
                Three questions plan any observation.

                **1. Is it up at night?** The Sun's RA tonight is about 12 h (it is the September
                equinox). A target is best placed when its RA is about **12 h away from the Sun's**:
                then it transits in the middle of the night. Saturn's RA is about 0 h 51 m, almost exactly opposite
                the Sun. Saturn reaches {k:opposition} on October 4.

                **2. How high does it get?** At transit its zenith angle is the difference between
                your latitude φ and its declination δ:

                $ altitude at transit = 90° − |φ − δ|

                **3. Any timing constraints?** Moon phase, twilight, a moon of Saturn at its
                greatest elongation...

                > The dashed orange curve is Saturn's path across tonight's sky.
                """)
                .sky(() -> view)
                .enter(() -> {
                    atUtm(); evening(); host.setTime(600);
                    host.skyLayers(true, false, true, true);
                    rts = host.skyRiseTransitSet("Saturn");
                    view = new double[]{150, 30, 130};
                })
                .controls(c -> timeSlider(c, "min", 60, 60))
                .scene((r, hud, t) -> {
                    double[] sb = host.skyBody("Saturn"), sun = host.skyBody("Sun");
                    double lat = host.skyLat(), lst = host.skyLst();
                    aboveHorizon(r, decCircle(sb[3], 240), ORANGE, 2.4f, true);
                    double[] tr = host.skyEnuRaDec(lst, sb[3]);
                    if (sb[0] < 47) {
                        r.dot(tr, 4, ORANGE);
                        r.label(tr, "transit " + host.skyClock(rts[1]), ORANGE, 12.5f, 0, -16);
                    }
                    r.ring(host.skyEnu("Saturn"), 11, SUN_C, 2f);
                    double dRa = ((sb[2] - sun[2]) % 24 + 24) % 24;
                    hud.title("Observing plan: Saturn", SUN_C);
                    placeRows(hud);
                    hud.gap();
                    hud.row("Sun's RA", hms(sun[2]));
                    hud.row("Saturn's RA / Dec", hms(sb[2]).substring(0, 6) + "  /  " + dms(sb[3]).replaceAll("′.*", "′"));
                    hud.row("RA from the Sun", String.format("%.1f h  (12 h = opposite)", dRa), ORANGE);
                    hud.gap();
                    hud.row("rises / transits / sets", host.skyClock(rts[0]) + "  /  " + host.skyClock(rts[1]) + "  /  " + host.skyClock(rts[2]), ORANGE);
                    hud.row("90° − |φ − δ|", String.format("%.1f°", 90 - Math.abs(lat - sb[3])), PERI_C);
                    hud.row("Saturn's altitude now", String.format("%.1f°", sb[0]));
                    hud.formula(String.format("altitude at transit = 90° − |%.1f° − %.1f°| = %.1f°", lat, sb[3], 90 - Math.abs(lat - sb[3])));
                }));

        s.add(step("Check: a star at RA 15h35m", """
                Same evening, 9 pm. The slides ask about a star with **RA = 15h35m02s**. That is
                **Alphecca**, the brightest star in Corona Borealis, marked here in the west.
                """)
                .sky(() -> view)
                .enter(() -> {
                    atUtm(); evening(); host.setTime(0);
                    host.skyLayers(true, false, true, true);
                    view = new double[]{270, 30, 110};
                })
                .quiz("Could you observe Alphecca tonight, and when?", 0,
                      "The Sun's RA is about 12 h, so a star at 15.6 h is only about 3.6 h east of the Sun. It "
                    + "transits about 3.6 h after the Sun does (mid-afternoon) and is already sinking in the west "
                    + "after dark. Catch it early in the evening, before it sets around midnight.",
                      "Yes, but only in the early evening, in the west",
                      "Yes, all night long",
                      "No, it is only above the horizon in daytime",
                      "Only just before dawn, in the east")
                .scene((r, hud, t) -> {
                    double[] a = host.skyEnu("Alphecca"), ab = host.skyBody("Alphecca"), sun = host.skyBody("Sun");
                    r.ring(a, 11, SUN_C, 2f);
                    r.label(a, "Alphecca", SUN_C, 12.5f, 0, -22);
                    hud.title("Alphecca in Corona Borealis", SUN_C);
                    placeRows(hud);
                    hud.gap();
                    hud.row("Alphecca's RA", hms(ab[2]));
                    hud.row("Sun's RA", hms(sun[2]));
                }));

        s.add(step("Check: how high will Saturn get?", """
                Saturn has declination δ ≈ +2.6°, and Mississauga is at latitude φ = 43.5° N.
                """)
                .sky(() -> view)
                .enter(() -> {
                    atUtm(); evening(); host.setTime(0);
                    host.skyLayers(true, false, true, true);
                    view = new double[]{140, 32, 130};       // Saturn, still low in the east, and the meridian
                })
                .quiz("What is Saturn's highest altitude as seen from Mississauga?", 1,
                      "At transit the zenith angle is |φ − δ| = 43.5° − 2.6° = 40.9°, so the altitude is "
                    + "90° − 40.9° ≈ 49°. That happens around 2 am (daylight time), when its hour angle is zero.",
                      "2.6°", "About 49°", "43.5°", "About 87°")
                .scene((r, hud, t) -> {
                    double[] sb = host.skyBody("Saturn");
                    aboveHorizon(r, decCircle(sb[3], 240), alpha(ORANGE, 200), 2f, true);
                    double[] n0 = enu(0, 0), s0 = enu(180, 0), z = v(0, 0, 1);
                    arc(r, n0, z, alpha(INK, 150), 1.6f, true);
                    arc(r, z, s0, alpha(INK, 150), 1.6f, true);
                    r.label(enu(180, 70), "meridian", INK, 12f, 0, 0);
                    r.ring(host.skyEnu("Saturn"), 11, SUN_C, 2f);
                    r.label(host.skyEnu("Saturn"), "Saturn now", SUN_C, 12.5f, 0, -22);
                    r.label(host.skyEnuRaDec(host.skyLst() + 3, sb[3]), "its path tonight", ORANGE, 12f, 0, -16);
                    hud.title("Saturn", SUN_C);
                    placeRows(hud);
                    hud.row("Saturn's declination δ", dms(sb[3]).replaceAll("′.*", "′"));
                    hud.row("latitude φ", String.format("%.1f°", host.skyLat()));
                    hud.row("altitude now", String.format("%.1f°", sb[0]));
                }));

        return new LessonStudio.LessonDef("Beginner", "Reading the Sky",
                "Altitude, azimuth, RA, Dec and planning an observation",
                "Stand on the UTM campus on the evening the slides use, find the celestial pole at your "
              + "latitude, read RA and Dec off the real sky, and plan when Saturn is best placed.",
                new Color(110, 200, 255), s, _ -> { });
    }
}
