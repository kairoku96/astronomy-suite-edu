import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.*;

/**
 * Lesson Studio — the app's education feature, built into the main simulation window.
 *
 * <p>This panel docks on the right of the window. When a lesson starts, the simulation
 * canvas hides the solar system and paints the lesson's own animated 3-D scene instead
 * (drag to orbit, scroll to zoom), while this panel shows the narration, a few hands-on
 * controls and, for "check" steps, a multiple-choice question with feedback.
 * The scenes are drawn by {@link R3}, a tiny depth-sorted software renderer on top
 * of Java2D, so the studio has no GPU dependency and can render head-less
 * ({@code -Dsss.lessonshot=dir}).
 *
 * <p>The physics in every scene is computed, not faked: orbits come from Kepler's
 * equation and vis-viva, photon packets really are counted as they cross the
 * Gaussian surface, and the surface / solid-angle integrals are real Riemann sums
 * compared against their closed forms.
 */
public final class LessonStudio extends JPanel {

    // ------------------------------------------------------------------ palette
    static final Color BG_TOP = new Color(11, 16, 32), BG_BOT = new Color(3, 5, 12);
    static final Color PANEL_BG = new Color(17, 22, 37), PANEL_EDGE = new Color(40, 50, 76);
    static final Color INK = new Color(226, 233, 246), DIM = new Color(146, 160, 186);
    static final Color SUN_C = new Color(255, 200, 92);
    static final Color PERI_C = new Color(255, 122, 98), APO_C = new Color(98, 170, 255);
    static final Color ACCENT = new Color(118, 196, 255);
    static final Color GOOD = new Color(70, 180, 110), BAD = new Color(210, 80, 80);
    static final Color VIOLET = new Color(190, 146, 255), TEAL = new Color(90, 220, 200);
    static final Color GREEN = new Color(126, 234, 150), PINK = new Color(255, 130, 200);
    static final Color ORANGE = new Color(255, 170, 70);
    static final String UI_FONT = "Segoe UI", MATH_FONT = "Cambria";

    // =====================================================================
    //  Public entry points
    // =====================================================================

    /** "Level · Title" for each lesson of a course, for the main window's Education menu. */
    static String[] menuLabels(int course) {
        List<LessonDef> ls = courseLessons(buildLessons(null), course);
        String[] out = new String[ls.size()];
        for (int i = 0; i < out.length; i++) out[i] = ls.get(i).level + "  ·  " + ls.get(i).title;
        return out;
    }

    /**
     * Debug aid: after {@code delayMs}, optionally jump to step {@code step} (1-based, 0 = keep),
     * then paint the live, on-screen {@code root} (the whole main window content) to a PNG.
     */
    static void snapshotLater(LessonStudio studio, Component root, String path, int step, int delayMs) {
        Timer t = new Timer(delayMs, _ -> {
            if (step > 0) studio.goStep(step - 1);
            Timer t2 = new Timer(1500, _ -> {
                BufferedImage img = new BufferedImage(root.getWidth(), root.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = img.createGraphics();
                root.paint(g);
                g.dispose();
                try { ImageIO.write(img, "png", new File(path)); } catch (Exception ex) { System.err.println(ex); }
            });
            t2.setRepeats(false);
            t2.start();
        });
        t.setRepeats(false);
        t.start();
    }

    // =====================================================================
    //  Lesson model
    // =====================================================================

    /** A lesson's live scene for one step: draw 3-D primitives into {@code r}, readouts into {@code hud}. */
    @FunctionalInterface interface Scene { void draw(R3 r, Hud hud, double t); }

    /** One multiple-choice check. {@code picked} remembers wrong tries so they stay marked. */
    static final class Quiz {
        final String question; final String[] options; final int correct; final String explain;
        final boolean[] tried; boolean solved;
        Quiz(String question, int correct, String explain, String... options) {
            this.question = question; this.correct = correct; this.explain = explain;
            this.options = options; this.tried = new boolean[options.length];
        }
        void reset() { java.util.Arrays.fill(tried, false); solved = false; }
    }

    /** One step of a lesson: a narrated 3-D scene with optional controls and quiz. */
    static final class Step {
        final String title, html;
        /** Camera goal for the sim: {centreX m, centreY m, radius m, pitch deg, yaw deg}; re-read every frame. */
        Supplier<double[]> cam = () -> new double[]{0, 0, 1.5e11, 45, 0};
        /** Metres per scene unit for this step's annotations. */
        double unitM = 1.495978707e11;
        Runnable enter = () -> { };
        Consumer<Controls> controls;
        Scene scene;
        Quiz quiz;
        /** Sky steps look at the sky from Earth; their scene units are east-north-up directions. */
        boolean sky;
        /** Sky view goal {azimuth°, altitude°, field°}; re-read every frame. */
        Supplier<double[]> skyCam = () -> new double[]{180, 30, 90};
        /** Body the camera goal is centred on (it moves with it), or null. */
        String follow;
        /** Runs every frame before painting (and once on entry): state the view itself depends on. */
        Runnable live;
        /** Covers material the AST221 slides (Days 2–8) don't: hidden unless -Dsss.lessons.all=true. */
        boolean beyondSlides;
        /** The one course that shows this step (its own version of a shared step), or null for all. */
        String course;
        /** The point (scene units; east-north-up on sky steps) the hint is about: it pulses as the step opens. */
        Supplier<double[]> watch;
        /** For a check: the title of the step it reviews, offered as a link back. */
        String reviews;
        Step(String title, String html) { this.title = title; this.html = html; }
        Step only(String courseCode) { course = courseCode; return this; }
        Step watch(Supplier<double[]> p) { watch = p; return this; }
        Step reviews(String stepTitle) { reviews = stepTitle; return this; }
        Step cam(Supplier<double[]> s) { cam = s; return this; }
        Step cam(String body, Supplier<double[]> s) { follow = body; cam = s; return this; }
        Step sky(Supplier<double[]> view) { sky = true; skyCam = view; return this; }
        Step live(Runnable r) { live = r; return this; }
        Step beyondSlides() { beyondSlides = true; return this; }
        /** A sky step that tracks a body; the view is {Δazimuth°, Δaltitude°, field°} from it. */
        Step sky(String track, Supplier<double[]> view) { follow = track; return sky(view); }
        Step unit(double metres) { unitM = metres; return this; }
        Step enter(Runnable r) { enter = r; return this; }
        Step controls(Consumer<Controls> c) { controls = c; return this; }
        Step scene(Scene s) { scene = s; return this; }
        Step quiz(String q, int correct, String explain, String... options) {
            quiz = new Quiz(q, correct, explain, options); return this;
        }
    }

    static final class LessonDef {
        final String level, title, subtitle, blurb; final Color accent;
        final List<Step> steps; final DoubleConsumer tick;
        LessonDef(String level, String title, String subtitle, String blurb, Color accent,
                  List<Step> steps, DoubleConsumer tick) {
            this.level = level; this.title = title; this.subtitle = subtitle; this.blurb = blurb;
            this.accent = accent; this.tick = tick; this.steps = steps;
        }
        int quizCount() { int n = 0; for (Step s : steps) if (s.quiz != null) n++; return n; }

        /** This lesson as a course uses it: only the steps it keeps, under its own title if it gives one. */
        LessonDef forCourse(String courseCode, Entry e) {
            List<Step> mine = steps.stream().filter(s -> s.course == null || s.course.equals(courseCode)).toList();
            List<Step> kept = mine.stream().filter(e.keep()).toList();
            if (e.keep() instanceof Titled t)
                for (String name : t.titles())
                    if (mine.stream().noneMatch(s -> s.title.equals(name)))
                        throw new IllegalStateException(courseCode + ": \"" + title + "\" has no step \"" + name + "\"");
            return new LessonDef(level, e.title() != null ? e.title() : title, e.subtitle() != null ? e.subtitle() : subtitle,
                    e.blurb() != null ? e.blurb() : blurb, accent, kept, tick);
        }
    }

    // =====================================================================
    //  Courses: each one bundles the lessons it uses, in its own order, with the
    //  steps that match its slides (docs/LESSON_PLAN.md, docs/LESSON_PLAN_AST101.md)
    // =====================================================================

    /** Show the steps that go beyond the AST221 slides (Days 2–8) as well: -Dsss.lessons.all=true. */
    static final boolean SHOW_BEYOND_SLIDES = Boolean.getBoolean("sss.lessons.all");

    /** Catalogue positions of the lessons in {@link #buildLessons}. */
    static final int SKY = 0, ANGULAR = 1, TELESCOPE = 2, ORBITS = 3, WEIGH = 4, LIGHT = 5, BLACKBODY = 6,
            PROJECT1 = 7, MAGNITUDE = 8, PARALLAX = 9, WOBBLE = 10, TITAN = 11,
            MOON = 12, RETROGRADE = 13, SCALE = 14, JUPITER_MOONS = 15, KIRKWOOD = 16;

    /** Every lesson with all of its steps; courses pick from these. */
    static List<LessonDef> buildLessons(Host host) {
        return List.of(
                new SkyLesson(host).build(),
                new AngularLesson(host).build(),
                new TelescopeLesson(host).build(),
                new OrbitLesson(host).build(),
                new WeighLesson(host).build(),
                new LightLesson(host).build(),
                new BlackbodyLesson(host).build(),
                new BlackbodyProject(host).build(),
                new MagnitudeLesson(host).build(),
                new ParallaxLesson(host).build(),
                new WobbleLesson(host).build(),
                new TitanLesson(host).build(),
                new MoonLesson(host).build(),
                new RetrogradeLesson(host).build(),
                new ScaleLesson(host).build(),
                new JupiterMoonsLesson(host).build(),
                new KirkwoodLesson(host).build());
    }

    /** One lesson of a course: which catalogue lesson, the steps it keeps, and an optional new title, subtitle and blurb. */
    record Entry(int lesson, Predicate<Step> keep, String title, String subtitle, String blurb) {
        Entry(int lesson, Predicate<Step> keep) { this(lesson, keep, null, null, null); }
    }
    /** Keeps the steps with these titles; a title no step has is an error at start-up. */
    record Titled(String... titles) implements Predicate<Step> {
        public boolean test(Step s) { return java.util.Arrays.asList(titles).contains(s.title); }
    }
    static final Predicate<Step> ALL_STEPS = _ -> true;
    /** AST221: everything but the steps beyond its slides, unless -Dsss.lessons.all=true. */
    static final Predicate<Step> AST221_SLIDES = s -> SHOW_BEYOND_SLIDES || !s.beyondSlides;

    record Course(String code, String name, String blurb, List<Entry> entries) { }

    static final List<Course> COURSES = List.of(
            new Course("AST101", "Exploring the Solar System",
                    "The sky, orbits, light, telescopes and planets, with light math. The lessons follow the AST101 "
                  + "slides in order and keep the steps that match them.",
                    List.of(
                            new Entry(ANGULAR, new Titled("Angular size", "Same angle, different sizes",                            // Day 3
                                    "A planet's tiny disc", "Check: the Moon's angular size"),
                                    null, "Angular size: why the Moon can cover the Sun",
                                    "Watch the Moon exactly cover the Sun over Niagara Falls, measure the sky with your hand, "
                                  + "see why some eclipses are total and others annular, and zoom from the naked eye to a "
                                  + "telescope to find Jupiter's tiny disc."),
                            new Entry(SCALE, ALL_STEPS),                                                                            // Days 3–4
                            new Entry(SKY, new Titled("Altitude and azimuth", "The pole star sits at your latitude",                // Day 5
                                    "Check: reading altitude and azimuth", "Check: the pole star in Reykjavík"),
                                    null, "Altitude, azimuth and the celestial pole",
                                    "Stand on the UTM campus under the real evening sky, find things by altitude and azimuth, "
                                  + "and see that the celestial pole's altitude is your latitude (try other places)."),
                            new Entry(MOON, ALL_STEPS),                                                                             // Day 7
                            new Entry(ORBITS, ALL_STEPS),                                                                           // Days 6, 9–11
                            new Entry(RETROGRADE, ALL_STEPS),                                                                       // Day 9
                            new Entry(PARALLAX, new Titled("Parallax: Earth's orbit as a baseline",                                  // Day 9
                                    "Check: a star twice as far", "Check: why Tycho saw no shift"),
                                    "Stellar Parallax", "The shift Tycho looked for",
                                    "Watch nearby stars shift as the real Earth goes around the Sun: the parallax that Tycho "
                                  + "searched for and could not see, and why it is so small."),
                            new Entry(WEIGH, ALL_STEPS),                                                                            // Day 10
                            new Entry(LIGHT, new Titled("Luminosity: a star's total power", "Wrap it in a Gaussian bubble",          // Day 22
                                    "Flux: the same light, spread thinner", "Check: moving Earth out", "Check: a bigger bubble"),
                                    "Luminosity & Flux", "How much sunlight each planet gets",
                                    "Hide every planet, wrap the real Sun in a bubble and count the light crossing it, then "
                                  + "watch the same light spread thinner with distance: the inverse-square law behind each "
                                  + "planet's sunlight."),
                            new Entry(BLACKBODY, new Titled("The Sun is (nearly) a blackbody", "Stefan–Boltzmann: hot stars are fierce",  // Days 13, 22, 32
                                    "How warm is each planet?", "An eccentric orbit: Mars's seasons of distance",
                                    "Around a different star", "Check: the Sun's colour"),
                                    null, "Thermal light, colour and planet temperatures",
                                    "The Sun as a blackbody, why hotter means bluer and brighter, each planet's temperature "
                                  + "from its live distance and albedo, Mars between perihelion and aphelion, and the "
                                  + "Earth-like zone around a cooler star."),
                            new Entry(MAGNITUDE, new Titled("Where absorption lines come from", "Check: why only certain colours?",    // Day 14
                                    "Check: a fingerprint", "Check: the strongest hydrogen lines"),
                                    "Spectral Lines", "Fingerprints of the elements",
                                    "Electrons jumping between the energy levels of hydrogen make the dark Balmer lines in "
                                  + "Vega's spectrum, at the same wavelengths hot hydrogen glows."),
                            new Entry(TELESCOPE, new Titled("A bigger bucket for light", "Check: a 1-second exposure",              // Days 15, 17
                                    "Diffraction and seeing", "Check: resolving the binary"),
                                    null, "Collecting area, resolution and the atmosphere",
                                    "Open up a telescope's mirror until Neptune appears, see the image a telescope really "
                                  + "delivers of Pluto and Charon from space, Mississauga or Mauna Kea, and thin or thicken "
                                  + "the air to watch the seeing change."),
                            new Entry(JUPITER_MOONS, ALL_STEPS),                                                                    // Days 25–27
                            new Entry(KIRKWOOD, ALL_STEPS),                                                                         // Day 28
                            new Entry(WOBBLE, new Titled("The Sun orbits too", "Radial velocity: the Doppler wobble",                 // Days 11, 30–31
                                    "Transits and eclipses", "Check: which star is heavier?"),
                                    null, "Finding exoplanets: radial velocity and transits",
                                    "Watch the real Sun circle the solar system's centre of mass, the Doppler wobble Jupiter "
                                  + "gives it, and the dip in sunlight when a planet passes in front."))),
            new Course("AST221", "Astrophysics I: Planets, Sun and Stars",
                    "The first half of the course (Days 2–8), its first Python project and a rehearsal of the Titan "
                  + "final project.",
                    List.of(
                            new Entry(SKY, AST221_SLIDES),          // Day 3: coordinates and planning observations
                            new Entry(ANGULAR, AST221_SLIDES),      // Days 2, 4: angular size and solid angle
                            new Entry(TELESCOPE, AST221_SLIDES),    // Day 2: telescopes
                            new Entry(ORBITS, AST221_SLIDES),       // Day 7: Kepler I and II
                            new Entry(WEIGH, AST221_SLIDES),        // Day 7: Kepler III and Newton
                            new Entry(LIGHT, AST221_SLIDES),        // Day 4: radiative quantities
                            new Entry(BLACKBODY, AST221_SLIDES),    // Day 5: blackbody radiation
                            new Entry(PROJECT1, AST221_SLIDES),     // Python project 1: blackbody radiation
                            new Entry(MAGNITUDE, AST221_SLIDES),    // Day 6: magnitudes and spectra
                            new Entry(PARALLAX, AST221_SLIDES),     // Day 7: parallax
                            new Entry(WOBBLE, AST221_SLIDES),       // Days 7, 8: binaries and the Sun's wobble
                            new Entry(TITAN, AST221_SLIDES))));     // final project rehearsal

    /** Index of the course with this code (case-insensitive), or the default (AST221). */
    static int courseIndex(String code) {
        for (int i = 0; i < COURSES.size(); i++) if (COURSES.get(i).code().equalsIgnoreCase(code)) return i;
        return COURSES.size() - 1;
    }

    /** A course's lessons, built from the catalogue. */
    static List<LessonDef> courseLessons(List<LessonDef> catalogue, int course) {
        Course c = COURSES.get(course);
        return c.entries().stream().map(e -> catalogue.get(e.lesson()).forCourse(c.code(), e)).toList();
    }

    // =====================================================================
    //  UI — a panel docked on the right of the main window. While a lesson runs,
    //  the simulation canvas hides the solar system and paints this lesson's
    //  3-D stage instead ({@link #paintStage}), forwarding mouse and keys here.
    // =====================================================================

    static final int PANEL_W = 420, CODE_PANEL_W = 600;

    private final CardLayout cards = new CardLayout();
    private final Host host;
    private final List<LessonDef> catalogue;
    /** The course whose bundle the picker shows (-Dsss.course=AST101 at start-up), and its lessons. */
    private int course = courseIndex(System.getProperty("sss.course", "AST221"));
    private List<LessonDef> lessons;
    private final WidthTracker pickerList = new WidthTracker();
    private final Overlay overlay = new Overlay();
    private final JComponent canvas;                       // the simulation panel the lesson runs in
    private final Consumer<Boolean> onActiveChange;        // told when a lesson starts / ends
    private final JLabel hdrLevel = new JLabel(), hdrTitle = new JLabel();
    private final StepBar stepBar = new StepBar();
    private final JLabel stepCount = new JLabel();
    private final RichText stepTitle = new RichText(21f);
    private final RichText body = new RichText();
    private final JPanel controlsBox = new JPanel(), quizBox = new JPanel();
    private final JButton prevBtn = new JButton("Back"), nextBtn = new JButton("Next  →");
    private final JScrollPane sideScroll;
    private Controls controls;
    private LessonDef lesson;
    private int stepIdx = -1;
    private double simT;

    /**
     * @param canvas          the simulation panel; it calls {@link #paintOverlay} after drawing
     *                        the solar system while {@link #isLessonActive()}
     * @param host            the live simulation the lessons drive
     * @param onActiveChange  called with true when a lesson starts and false when it ends,
     *                        so the host can save / restore its own state and panels
     */
    LessonStudio(JComponent canvas, Host host, Consumer<Boolean> onActiveChange) {
        this.canvas = canvas;
        this.host = host;
        this.catalogue = buildLessons(host);
        this.lessons = courseLessons(catalogue, course);
        this.onActiveChange = onActiveChange;
        setLayout(cards);
        setBackground(PANEL_BG);
        setPreferredSize(new Dimension(PANEL_W, 10));
        setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, PANEL_EDGE));

        // ---- lesson card: header / step title / scrolling narration / nav ----
        JPanel lessonCard = new JPanel(new BorderLayout());
        lessonCard.setOpaque(false);

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBorder(new EmptyBorder(12, 16, 4, 16));
        JPanel hdr = new JPanel(new BorderLayout(10, 0));
        hdr.setOpaque(false);
        JButton exit = new JButton("←  Lessons");
        exit.setFocusable(false);
        exit.setToolTipText("Leave the lesson and return to the simulation (Esc)");
        exit.addActionListener(_ -> exitLesson());
        hdrLevel.setFont(uiFont(Font.BOLD, 11f));
        hdrTitle.setFont(uiFont(Font.BOLD, 15f));
        hdrTitle.setForeground(INK);
        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        titles.add(hdrLevel);
        titles.add(hdrTitle);
        hdr.add(exit, BorderLayout.WEST);
        hdr.add(titles, BorderLayout.CENTER);
        hdr.setAlignmentX(LEFT_ALIGNMENT);
        hdr.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        stepBar.setAlignmentX(LEFT_ALIGNMENT);
        stepBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        stepBar.setPreferredSize(new Dimension(300, 30));
        stepCount.setFont(uiFont(Font.BOLD, 11f));
        stepCount.setForeground(DIM);
        stepCount.setAlignmentX(LEFT_ALIGNMENT);
        top.add(hdr);
        top.add(Box.createVerticalStrut(8));
        top.add(stepBar);
        top.add(Box.createVerticalStrut(8));
        top.add(stepCount);
        top.add(Box.createVerticalStrut(4));
        top.add(stepTitle);
        lessonCard.add(top, BorderLayout.NORTH);

        WidthTracker content = new WidthTracker();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(new EmptyBorder(4, 16, 12, 16));
        controlsBox.setOpaque(false);
        controlsBox.setLayout(new BoxLayout(controlsBox, BoxLayout.Y_AXIS));
        quizBox.setOpaque(false);
        quizBox.setLayout(new BoxLayout(quizBox, BoxLayout.Y_AXIS));
        for (JComponent c : new JComponent[]{body, controlsBox, quizBox}) {
            c.setAlignmentX(LEFT_ALIGNMENT);
            content.add(c);
        }
        sideScroll = new JScrollPane(content);
        sideScroll.setBorder(null);
        sideScroll.setOpaque(false);
        sideScroll.getViewport().setOpaque(false);
        sideScroll.getVerticalScrollBar().setUnitIncrement(16);
        lessonCard.add(sideScroll, BorderLayout.CENTER);

        JPanel nav = new JPanel(new GridLayout(1, 2, 10, 0));
        nav.setOpaque(false);
        nav.setBorder(new EmptyBorder(10, 16, 14, 16));
        prevBtn.addActionListener(_ -> goStep(stepIdx - 1));
        nextBtn.addActionListener(_ -> {
            if (lesson != null && stepIdx == lesson.steps.size() - 1) exitLesson(); else goStep(stepIdx + 1);
        });
        nextBtn.setBackground(new Color(44, 98, 170));
        nextBtn.setForeground(Color.WHITE);
        prevBtn.setFont(uiFont(Font.BOLD, 13f));
        nextBtn.setFont(uiFont(Font.BOLD, 13f));
        prevBtn.setFocusable(false);
        nextBtn.setFocusable(false);
        nav.add(prevBtn);
        nav.add(nextBtn);
        lessonCard.add(nav, BorderLayout.SOUTH);

        add(pickerCard(), "picker");
        add(lessonCard, "lesson");
    }

    boolean isLessonActive() { return lesson != null; }

    private Step step() {
        return (lesson == null || stepIdx < 0) ? null : lesson.steps.get(stepIdx);
    }

    /** Shows the lesson list in the side panel (the simulation keeps running beside it). */
    void showPicker() {
        if (lesson != null) exitLesson();
        cards.show(this, "picker");
        setVisible(true);
        revalidateHost();
    }

    /** Hides the side panel entirely. */
    void closePanel() {
        if (lesson != null) exitLesson();
        setVisible(false);
        revalidateHost();
    }

    String courseCode() { return COURSES.get(course).code(); }

    /** Switches the picker to another course's bundle (leaving any lesson in progress). */
    void setCourse(int c) {
        c = Math.max(0, Math.min(COURSES.size() - 1, c));
        if (c == course && lessons != null) return;
        if (lesson != null) exitLesson();
        course = c;
        lessons = courseLessons(catalogue, course);
        fillPicker();
    }

    /** Starts lesson {@code i} (0-based) of course {@code c}. */
    void startLesson(int c, int i) {
        setCourse(c);
        startLesson(i);
    }

    void startLesson(int i) {
        boolean wasActive = lesson != null;
        lesson = lessons.get(Math.max(0, Math.min(lessons.size() - 1, i)));
        for (Step s : lesson.steps) if (s.quiz != null) s.quiz.reset();
        hdrLevel.setText(lesson.level.toUpperCase());
        hdrLevel.setForeground(lesson.accent);
        hdrTitle.setText(lesson.title);
        stepIdx = -1;
        cards.show(this, "lesson");
        setVisible(true);
        if (!wasActive) onActiveChange.accept(true);
        goStep(0);
        revalidateHost();
        canvas.requestFocusInWindow();
    }

    /** Leaves the lesson: the simulation comes back and the panel returns to the lesson list. */
    void exitLesson() {
        if (lesson == null) return;
        lesson = null;
        stepIdx = -1;
        controls = null;
        setPreferredSize(new Dimension(PANEL_W, 10));
        cards.show(this, "picker");
        onActiveChange.accept(false);
        revalidateHost();
        canvas.repaint();
    }

    private void revalidateHost() {
        Container p = getParent();
        if (p != null) { p.revalidate(); p.repaint(); }
    }

    // For the alignment check (-Dsss.aligncheck): walk every step of the course.
    int lessonCount() { return lessons.size(); }
    int stepCount() { return lesson == null ? 0 : lesson.steps.size(); }
    boolean skyStep() { Step s = step(); return s != null && s.sky; }
    String where() { return lesson == null || stepIdx < 0 ? "" : lesson.title + " / " + lesson.steps.get(stepIdx).title; }

    void goStep(int i) {
        if (lesson == null) return;
        int n = lesson.steps.size();
        i = Math.max(0, Math.min(n - 1, i));
        boolean first = stepIdx < 0;
        Step s = lesson.steps.get(i), prev = first || stepIdx >= n ? null : lesson.steps.get(stepIdx);
        // Switching between the sky and the 3-D view: keep the old picture to dissolve from.
        fadeFrom = prev != null && prev.sky != s.sky ? snapshotCanvas() : null;
        stepShownT = simT;
        stepIdx = i;
        if (host != null) {
            // Every step starts from the plain heliocentric view and the naked eye; it sets up the rest.
            host.setBarycentric(false);
            host.skyOptics(Double.NaN, Double.NaN, null);
            host.skyAir(1);
            host.skyNorthUp(false);
            host.sky(s.sky);
        }
        s.enter.run();
        if (s.live != null) s.live.run();
        overlay.reaim();

        stepCount.setText("STEP " + (i + 1) + " OF " + n + (s.quiz != null ? "  ·  CHECK YOURSELF" : ""));
        stepCount.setForeground(s.quiz != null ? ORANGE : DIM);
        stepTitle.setMarkup("**" + s.title + "**");
        body.setMarkup(s.html);

        controlsBox.removeAll();
        controls = null;
        if (s.controls != null) {
            Controls cs = new Controls(controlsBox);
            cs.changed = () -> { canvas.repaint(); cs.sync(); revalidate(); };
            controls = cs;
            s.controls.accept(controls);
        }
        // Code needs room: the panel widens on steps with a code cell.
        boolean coding = controls != null && !controls.cells.isEmpty();
        setPreferredSize(new Dimension(coding ? CODE_PANEL_W : PANEL_W, 10));
        revalidateHost();
        rebuildQuiz(s);

        prevBtn.setEnabled(i > 0);
        nextBtn.setText(i == n - 1 ? "Finish" : "Next  →");
        stepBar.repaint();
        revalidate();
        repaint();
        canvas.repaint();
        SwingUtilities.invokeLater(() -> sideScroll.getVerticalScrollBar().setValue(0));
    }

    private void rebuildQuiz(Step s) {
        quizBox.removeAll();
        Quiz q = s.quiz;
        if (q == null) return;
        quizBox.add(Box.createVerticalStrut(10));
        RichText qp = new RichText();
        qp.setMarkup("**" + q.question + "**");
        quizBox.add(qp);
        quizBox.add(Box.createVerticalStrut(6));
        for (int k = 0; k < q.options.length; k++) {
            final int pick = k;
            JButton b = new JButton("<html>" + (char) ('A' + k) + ".&nbsp;&nbsp;" + q.options[k] + "</html>");
            b.setHorizontalAlignment(SwingConstants.LEFT);
            b.setFont(uiFont(Font.PLAIN, 13f));
            b.setFocusable(false);
            b.setAlignmentX(LEFT_ALIGNMENT);
            b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 400));
            b.setBorder(new EmptyBorder(9, 12, 9, 12));
            b.setBackground(new Color(38, 48, 74));
            b.setForeground(INK);
            if (q.solved && k == q.correct) { b.setBackground(GOOD); b.setForeground(Color.WHITE); }
            else if (q.tried[k]) { b.setBackground(BAD.darker()); b.setForeground(Color.WHITE); }
            b.addActionListener(_ -> {
                if (q.solved) return;
                if (pick == q.correct) q.solved = true; else q.tried[pick] = true;
                rebuildQuiz(s);
                stepBar.repaint();
                quizBox.revalidate();
                quizBox.repaint();
            });
            quizBox.add(b);
            quizBox.add(Box.createVerticalStrut(6));
        }
        boolean wrong = false;
        for (boolean t : q.tried) wrong |= t;
        if (q.solved || wrong) {
            RichText fb = new RichText();
            if (q.solved) fb.setBox(new Color(23, 58, 38), new Color(191, 245, 207), 10);
            else fb.setBox(new Color(62, 28, 28), new Color(255, 201, 201), 10);
            fb.setMarkup(q.solved ? "**Correct!** " + q.explain
                                  : "**Not quite.** Look at the scene again and try another answer.");
            quizBox.add(fb);
        }
        // "Reviews step 3: …", a link back to the step this check is about (when this course has it).
        int back = -1;
        for (int k = 0; s.reviews != null && k < lesson.steps.size(); k++) if (lesson.steps.get(k).title.equals(s.reviews)) back = k;
        if (back >= 0) {
            final int target = back;
            JLabel link = new JLabel("<html>Reviews step " + (back + 1) + ": " + s.reviews + "&nbsp;&nbsp;<u>go back</u></html>");
            link.setFont(uiFont(Font.PLAIN, 12f));
            link.setForeground(DIM);
            link.setAlignmentX(LEFT_ALIGNMENT);
            link.setBorder(new EmptyBorder(8, 2, 0, 0));
            link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            link.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { goStep(target); }
                @Override public void mouseEntered(MouseEvent e) { link.setForeground(ACCENT); }
                @Override public void mouseExited(MouseEvent e) { link.setForeground(DIM); }
            });
            quizBox.add(link);
        }
    }

    /**
     * One animation frame, driven by the simulation's own timer: advance the lesson's
     * physics, ease the camera and refresh the controls.
     */
    void tick(double dt) {
        if (lesson == null || stepIdx < 0) return;
        dt = Math.max(0, Math.min(0.1, dt));
        simT += dt;
        lesson.tick.accept(dt);
        Step s = step();
        if (s.live != null) s.live.run();
        overlay.tickCamera();
        if (controls != null) controls.sync();
    }

    // ---- the AI assistant's view of the lesson ----

    /**
     * What the AI assistant should know about the Lesson Studio right now: the lesson and step
     * the user is on, the narration they are reading, the check-yourself question (with its answer,
     * for tutoring), the readouts on screen and the current setting of every control.
     */
    String aiContext() {
        StringBuilder b = new StringBuilder(1024);
        if (lesson == null || stepIdx < 0) {
            b.append("Lesson Studio: no lesson running").append(isVisible() ? " (the lesson list is open)." : ".").append('\n');
            b.append("Course bundle: ").append(courseCode()).append(" (").append(COURSES.get(course).name())
             .append("); other courses: ");
            for (Course c : COURSES) if (c != COURSES.get(course)) b.append(c.code()).append(' ');
            b.append('\n');
            b.append("Available lessons: ");
            for (int i = 0; i < lessons.size(); i++)
                b.append(i == 0 ? "" : "; ").append(i + 1).append(". ").append(lessons.get(i).title)
                 .append(" (").append(lessons.get(i).level).append(")");
            return b.append('\n').toString();
        }
        Step s = step();
        int li = lessons.indexOf(lesson) + 1;
        b.append(courseCode()).append(" LESSON IN PROGRESS: lesson ").append(li).append(" of ").append(lessons.size()).append(", \"")
         .append(lesson.title).append("\" (").append(lesson.level).append(": ").append(lesson.subtitle).append(")\n");
        b.append("Step ").append(stepIdx + 1).append(" of ").append(lesson.steps.size()).append(": \"").append(s.title).append("\"")
         .append(s.sky ? " - shown in the Sky from Earth view" : " - shown in the 3-D Solar System view").append('\n');
        b.append("Steps in this lesson: ");
        for (int k = 0; k < lesson.steps.size(); k++)
            b.append(k == 0 ? "" : " | ").append(k + 1).append(". ").append(lesson.steps.get(k).title);
        b.append('\n');
        b.append("Narration on the user's screen:\n").append(plainText(s.html).strip()).append('\n');
        if (s.quiz != null) {
            Quiz q = s.quiz;
            b.append("CHECK-YOURSELF QUESTION: ").append(plainText(q.question).strip()).append('\n');
            for (int k = 0; k < q.options.length; k++)
                b.append("  ").append((char) ('A' + k)).append(". ").append(plainText(q.options[k]).strip())
                 .append(q.tried[k] ? "   (user tried this: wrong)" : "").append('\n');
            b.append("  Correct answer (for tutoring only): ").append((char) ('A' + q.correct)).append(". ")
             .append(plainText(q.options[q.correct]).strip()).append(" - ").append(plainText(q.explain).strip()).append('\n');
            b.append("  Status: ").append(q.solved ? "the user has answered it correctly" : "not answered correctly yet").append('\n');
        }
        Hud hud = overlay.hud;
        if (hud.title != null || !hud.rows.isEmpty() || !hud.formulas.isEmpty()) {
            b.append("Readouts on screen").append(hud.title != null ? " (" + hud.title + ")" : "").append(":\n");
            for (Object[] r : hud.rows) if (r != null) b.append("  ").append(r[0]).append(": ").append(r[1]).append('\n');
            for (String f : hud.formulas) b.append("  formula shown: ").append(f).append('\n');
            if (hud.inset != null) b.append("  chart shown: ").append(hud.insetTitle).append('\n');
        }
        if (controls != null && !controls.describers.isEmpty()) {
            b.append("Controls in the lesson panel:\n");
            for (Supplier<String> d : controls.describers) {
                try { b.append("  ").append(d.get()).append('\n'); } catch (Exception ignored) { }
            }
        }
        b.append("Lesson keys: right arrow or N next step, left arrow back, Esc leaves the lesson; double-click the view to re-centre it.\n");
        return b.toString();
    }

    /** Lesson markup ({k:term}, **bold**, _italic_, "$ " equations, "> " hints) as plain text. */
    static String plainText(String m) {
        StringBuilder out = new StringBuilder();
        for (String raw : m.split("\n")) {
            String line = raw.strip();
            if (line.startsWith("$ ")) line = "    " + line.substring(2);
            else if (line.startsWith("> ")) line = "(hint) " + line.substring(2);
            out.append(line).append('\n');
        }
        return out.toString().replaceAll("\\{[kpavg]:", "").replace("}", "").replace("**", "")
                .replaceAll("(?<=\\s|^)_|_(?=[\\s.,;:!?)]|$)", "").replaceAll("\n{3,}", "\n\n");
    }

    // ---- hosting: the simulation canvas calls these while a lesson runs ----

    /** Draw the step's annotations and readouts over the live simulation. */
    /** Seconds a new step's annotations and readouts take to fade in, and a sky ↔ 3-D switch to dissolve. */
    static final double FADE_IN = 0.35, DISSOLVE = 0.5;
    /** Lesson time ({@link #simT}) when the current step was shown. */
    private double stepShownT;
    /** The last frame of the previous step's view while it dissolves into the new one, or null. */
    private BufferedImage fadeFrom;

    private BufferedImage snapshotCanvas() {
        int w = canvas.getWidth(), h = canvas.getHeight();
        if (w <= 0 || h <= 0) return null;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        canvas.paint(g);
        g.dispose();
        return img;
    }

    static double smooth(double x) { x = Math.max(0, Math.min(1, x)); return x * x * (3 - 2 * x); }

    void paintOverlay(Graphics2D g, int w, int h) {
        double age = simT - stepShownT;
        BufferedImage from = fadeFrom;
        if (from != null) {
            float a = (float) (1 - smooth(age / DISSOLVE));
            if (a <= 0) fadeFrom = null;
            else {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setComposite(AlphaComposite.SrcOver.derive(a));
                g2.drawImage(from, 0, 0, null);
                g2.dispose();
            }
        }
        overlay.paint(g, w, h, (float) smooth(age / FADE_IN));
    }
    /** Ease the camera back to the step's framing (double-click on the view). */
    void reaim() { overlay.reaim(); }

    /**
     * Keys while a lesson runs: → / N next step, ← previous, Esc leaves the lesson.
     * Returns true if handled; every other key still reaches the simulation.
     */
    boolean handleKey(int code) {
        if (lesson == null) return false;
        Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (focus instanceof JSlider && (code == KeyEvent.VK_LEFT || code == KeyEvent.VK_RIGHT)) return false;
        switch (code) {
            case KeyEvent.VK_RIGHT, KeyEvent.VK_N -> { if (stepIdx < lesson.steps.size() - 1) goStep(stepIdx + 1); return true; }
            case KeyEvent.VK_LEFT -> { goStep(stepIdx - 1); return true; }
            case KeyEvent.VK_ESCAPE -> { exitLesson(); return true; }
            default -> { return false; }
        }
    }

    // ---- lesson list ----

    private JComponent pickerCard() {
        JPanel card = new JPanel(new BorderLayout());
        card.setOpaque(false);
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.setBorder(new EmptyBorder(16, 18, 6, 14));
        JLabel t = new JLabel("Lessons");
        t.setFont(uiFont(Font.BOLD, 22f));
        t.setForeground(INK);
        JButton close = new JButton("Close");
        close.setFocusable(false);
        close.addActionListener(_ -> closePanel());
        head.add(t, BorderLayout.WEST);
        head.add(close, BorderLayout.EAST);
        card.add(head, BorderLayout.NORTH);

        WidthTracker list = pickerList;
        list.setOpaque(false);
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBorder(new EmptyBorder(4, 16, 16, 16));
        fillPicker();
        JScrollPane sp = new JScrollPane(list);
        sp.setBorder(null);
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        card.add(sp, BorderLayout.CENTER);
        return card;
    }

    /** The picker's contents for the current course: the course switch, the course's blurb and its lesson tiles. */
    private void fillPicker() {
        WidthTracker list = pickerList;
        list.removeAll();
        RichText intro = new RichText(13.5f);
        intro.setBox(null, DIM, 0);
        intro.setMarkup("Pick a course, then a lesson. Each runs on the live solar system: each step shows just the "
                + "bodies it needs, sets the clock and flies the camera there, and draws its explanations "
                + "right into the scene. Drag to look around, use the sliders, and check yourself as you go.");
        list.add(intro);
        list.add(Box.createVerticalStrut(12));
        JPanel sw = new JPanel(new GridLayout(1, COURSES.size(), 8, 0));
        sw.setOpaque(false);
        ButtonGroup bg = new ButtonGroup();
        for (int i = 0; i < COURSES.size(); i++) {
            final int k = i;
            Course c = COURSES.get(i);
            JToggleButton b = new JToggleButton(c.code());
            b.setFont(uiFont(Font.BOLD, 14f));
            b.setFocusable(false);
            b.setToolTipText(c.name());
            b.setSelected(i == course);
            if (i == course) { b.setBackground(new Color(44, 98, 170)); b.setForeground(Color.WHITE); }
            b.addActionListener(_ -> setCourse(k));
            bg.add(b);
            sw.add(b);
        }
        sw.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        list.add(sw);
        list.add(Box.createVerticalStrut(12));
        Course c = COURSES.get(course);
        RichText name = new RichText(17f);
        name.setBox(null, INK, 0);
        name.setMarkup("**" + c.code() + ": " + c.name() + "**");
        RichText blurb = new RichText(13f);
        blurb.setBox(null, new Color(160, 174, 200), 0);
        blurb.setMarkup(c.blurb() + " " + lessons.size() + " lessons.");
        list.add(name);
        list.add(Box.createVerticalStrut(4));
        list.add(blurb);
        list.add(Box.createVerticalStrut(14));
        for (int i = 0; i < lessons.size(); i++) {
            JComponent tile = lessonTile(lessons.get(i), i);
            tile.setAlignmentX(LEFT_ALIGNMENT);
            list.add(tile);
            list.add(Box.createVerticalStrut(14));
        }
        for (Component j : list.getComponents()) if (j instanceof JComponent jc) jc.setAlignmentX(LEFT_ALIGNMENT);
        list.revalidate();
        list.repaint();
    }

    private JComponent lessonTile(LessonDef L, int index) {
        JPanel tile = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(new Color(24, 31, 50));
                g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 18, 18);
                g.setColor(L.accent);
                g.fillRoundRect(0, 0, 6, getHeight() - 1, 6, 6);
                g.setColor(PANEL_EDGE);
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 18, 18);
                g.dispose();
            }
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        };
        tile.setOpaque(false);
        tile.setBorder(new EmptyBorder(16, 20, 16, 16));
        JPanel c = new JPanel();
        c.setOpaque(false);
        c.setLayout(new BoxLayout(c, BoxLayout.Y_AXIS));
        JLabel lvl = new JLabel(L.level.toUpperCase() + "   ·   LESSON " + (index + 1));
        lvl.setFont(uiFont(Font.BOLD, 11.5f));
        lvl.setForeground(L.accent);
        lvl.setBorder(new EmptyBorder(0, 0, 0, 16));        // room for the label's last glyphs
        RichText ti = new RichText(19f);
        ti.setBox(null, INK, 0);
        ti.setMarkup("**" + L.title + "**");
        RichText su = new RichText(13.5f);
        su.setBox(null, new Color(190, 205, 230), 0);
        su.setMarkup(L.subtitle);
        RichText bl = new RichText(13f);
        bl.setBox(null, new Color(160, 174, 200), 0);
        bl.setMarkup(L.blurb);
        JLabel meta = new JLabel((L.steps.size() - L.quizCount()) + " interactive steps  ·  " + L.quizCount() + " checks");
        meta.setFont(uiFont(Font.PLAIN, 12f));
        meta.setForeground(DIM);
        for (JComponent j : new JComponent[]{lvl, ti, su, bl, meta}) j.setAlignmentX(LEFT_ALIGNMENT);
        c.add(lvl); c.add(Box.createVerticalStrut(6));
        c.add(ti); c.add(Box.createVerticalStrut(2));
        c.add(su); c.add(Box.createVerticalStrut(8));
        c.add(bl); c.add(Box.createVerticalStrut(8));
        c.add(meta);
        tile.add(c, BorderLayout.CENTER);
        JButton go = new JButton("Start lesson  →");
        go.setFont(uiFont(Font.BOLD, 13f));
        go.setBackground(L.accent.darker());
        go.setForeground(Color.WHITE);
        go.setFocusable(false);
        go.addActionListener(_ -> startLesson(index));
        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.setBorder(new EmptyBorder(12, 0, 0, 0));
        south.add(go, BorderLayout.EAST);
        tile.add(south, BorderLayout.SOUTH);
        return tile;
    }

    // ---- small UI helpers ----

    static Font uiFont(int style, float size) { return new Font(UI_FONT, style, 12).deriveFont(size); }

    /**
     * Narration text in a tiny markup: blank-line separated paragraphs, **bold**, _italic_,
     * {k:coloured term} (k/p/a/v/g), "$ " equation lines and "> " hints. It is laid out and
     * drawn with the same font metrics, one word at a time, so runs never overlap and lines
     * always wrap to the component's real width (Swing's HTML view managed neither here).
     */
    static final class RichText extends JComponent {
        private record Run(String text, int style, Color color) { }
        private record Block(char kind, List<Run> runs) { }         // 'p' paragraph, 'e' equation, 'h' hint
        private record Piece(String s, Font f, Color c, float x, float y) { }

        static final Color BODY = new Color(220, 228, 242), EQ_BG = new Color(30, 41, 68), EQ_FG = new Color(255, 226, 160);
        private static final Map<Character, Color> TERM = Map.of(
                'k', new Color(143, 208, 255), 'p', new Color(255, 138, 112), 'a', new Color(112, 170, 255),
                'v', new Color(196, 156, 255), 'g', new Color(143, 240, 168));

        private List<Block> blocks = List.of();
        private final List<Piece> pieces = new ArrayList<>();
        private final List<Rectangle> eqBoxes = new ArrayList<>();
        private Color boxBg, fg = BODY;
        private int pad;
        private int laidW = -1, laidH;

        private final float baseSize;

        RichText() { this(14.5f); }
        RichText(float baseSize) { this.baseSize = baseSize; setOpaque(false); setAlignmentX(LEFT_ALIGNMENT); }

        void setBox(Color bg, Color text, int padding) { boxBg = bg; fg = text; pad = padding; laidW = -1; }

        void setMarkup(String m) {
            blocks = parse(m);
            laidW = -1;
            revalidate();
            repaint();
        }

        private static List<Block> parse(String m) {
            List<Block> out = new ArrayList<>();
            StringBuilder cur = null;
            char kind = 'p';
            for (String raw : m.split("\n")) {
                String line = raw.strip();
                boolean eq = line.startsWith("$ "), hint = line.startsWith("> ");
                if (line.isEmpty() || eq || hint) {
                    if (cur != null) { out.add(new Block(kind, runs(cur.toString()))); cur = null; }
                    if (eq) out.add(new Block('e', runs(line.substring(2))));
                    if (hint) { kind = 'h'; cur = new StringBuilder(line.substring(2)); }
                    continue;
                }
                if (cur == null) { kind = 'p'; cur = new StringBuilder(line); }
                else cur.append(' ').append(line);
            }
            if (cur != null) out.add(new Block(kind, runs(cur.toString())));
            return out;
        }

        /** Inline markup -> styled runs. */
        private static List<Run> runs(String t) {
            List<Run> out = new ArrayList<>();
            StringBuilder b = new StringBuilder();
            boolean bold = false, ital = false;
            Color term = null;
            for (int i = 0; i < t.length(); i++) {
                char ch = t.charAt(i);
                boolean open = ch == '{' && i + 2 < t.length() && t.charAt(i + 2) == ':' && TERM.containsKey(t.charAt(i + 1));
                boolean boldMark = ch == '*' && i + 1 < t.length() && t.charAt(i + 1) == '*';
                // _italic_ marks sit at word edges; an underscore inside a word (b_wien, R_sun) is literal.
                boolean italMark = ch == '_' && !(i > 0 && i + 1 < t.length()
                        && Character.isLetterOrDigit(t.charAt(i - 1)) && Character.isLetterOrDigit(t.charAt(i + 1)));
                if (open || boldMark || italMark || (ch == '}' && term != null)) {
                    if (b.length() > 0) {
                        out.add(new Run(b.toString(), (bold || term != null ? Font.BOLD : 0) | (ital ? Font.ITALIC : 0), term));
                        b.setLength(0);
                    }
                    if (open) { term = TERM.get(t.charAt(i + 1)); i += 2; }
                    else if (boldMark) { bold = !bold; i++; }
                    else if (ch == '_') ital = !ital;
                    else term = null;
                    continue;
                }
                b.append(ch);
            }
            if (b.length() > 0) out.add(new Run(b.toString(), (bold || term != null ? Font.BOLD : 0) | (ital ? Font.ITALIC : 0), term));
            return out;
        }

        /** One render context for measuring AND drawing, so word positions match the glyphs exactly. */
        private static final java.awt.font.FontRenderContext FRC = new java.awt.font.FontRenderContext(null, true, true);
        private static float adv(Font f, String s) { return (float) f.getStringBounds(s, FRC).getWidth(); }

        private void layoutFor(int width) {
            laidW = width;
            pieces.clear();
            eqBoxes.clear();
            float y = pad;
            int inner = Math.max(60, width - 2 * pad);
            for (int bi = 0; bi < blocks.size(); bi++) {
                Block bl = blocks.get(bi);
                boolean eq = bl.kind == 'e', hint = bl.kind == 'h';
                float size = eq ? 17f : hint ? 13.5f : baseSize;
                int indent = eq ? 12 : 0;
                int avail = inner - 2 * indent;
                float top = y;
                if (eq) y += 8;
                java.awt.font.LineMetrics base = blockFont(bl.kind, Font.PLAIN, size).getLineMetrics("Ag", FRC);
                float lh = base.getHeight() * (eq ? 1.15f : 1.3f);
                float x = 0, asc = base.getAscent();
                boolean pendingSpace = false;
                for (Run r : bl.runs) {
                    Font f = blockFont(bl.kind, r.style | (hint ? Font.ITALIC : 0), size);
                    Color c = r.color != null ? r.color : eq ? EQ_FG : hint ? DIM : fg;
                    String[] words = r.text.split(" ", -1);
                    for (int wi = 0; wi < words.length; wi++) {
                        if (wi > 0) pendingSpace = true;
                        String w = words[wi];
                        if (w.isEmpty()) continue;
                        float ww = adv(f, w), sp = pendingSpace && x > 0 ? adv(f, " ") : 0;
                        if (x > 0 && x + sp + ww > avail) { y += lh; x = 0; sp = 0; }
                        pieces.add(new Piece(w, f, c, pad + indent + x + sp, y + asc));
                        x += sp + ww;
                        pendingSpace = false;
                    }
                }
                y += lh;
                if (eq) {
                    y += 6;
                    eqBoxes.add(new Rectangle(pad, Math.round(top), inner, Math.round(y - top)));
                    y += 8;
                } else if (bi < blocks.size() - 1) {
                    y += 10;
                }
            }
            laidH = Math.round(y) + pad;
        }

        private static Font blockFont(char kind, int style, float size) {
            return kind == 'e' ? new Font(MATH_FONT, style, 12).deriveFont(size) : uiFont(style, size);
        }

        @Override public void setBounds(int x, int y, int w, int h) {
            super.setBounds(x, y, w, h);
            if (w > 0 && w != laidW) {
                int old = laidH;
                layoutFor(w);
                if (laidH != old) SwingUtilities.invokeLater(this::revalidate);
            }
        }
        @Override public Dimension getPreferredSize() {
            int w = getWidth() > 0 ? getWidth() : 360;
            if (w != laidW) layoutFor(w);
            return new Dimension(120, laidH);
        }
        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }

        @Override protected void paintComponent(Graphics g0) {
            if (getWidth() != laidW) layoutFor(getWidth());
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            if (boxBg != null) {
                g.setColor(boxBg);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
            }
            g.setColor(EQ_BG);
            for (Rectangle r : eqBoxes) g.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10);
            for (Piece p : pieces) {
                g.setFont(p.f);
                g.setColor(p.c);
                g.drawString(p.s, p.x, p.y);
            }
            g.dispose();
        }
    }

    /** Side-panel content that always wraps to the scroll viewport's width. */
    static final class WidthTracker extends JPanel implements Scrollable {
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 16; }
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return 80; }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }

    /** Clickable progress strip in the header: one pill per step. */
    final class StepBar extends JComponent {
        StepBar() {
            setPreferredSize(new Dimension(300, 40));
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    int k = hit(e.getX());
                    if (k >= 0) goStep(k);
                }
            });
            addMouseMotionListener(new MouseMotionAdapter() {
                @Override public void mouseMoved(MouseEvent e) {
                    int k = hit(e.getX());
                    setToolTipText(k >= 0 && lesson != null ? (k + 1) + ". " + lesson.steps.get(k).title : null);
                }
            });
        }
        private int n() { return lesson == null ? 0 : lesson.steps.size(); }
        private int hit(int x) {
            int n = n();
            if (n == 0) return -1;
            double w = (getWidth() - 20.0) / n;
            int k = (int) ((x - 10) / w);
            return (k >= 0 && k < n) ? k : -1;
        }
        @Override protected void paintComponent(Graphics g0) {
            int n = n();
            if (n == 0) return;
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            double w = (getWidth() - 20.0) / n;
            int y = getHeight() / 2 - 3;
            for (int k = 0; k < n; k++) {
                Step s = lesson.steps.get(k);
                Color c = k == stepIdx ? lesson.accent
                        : k < stepIdx ? new Color(lesson.accent.getRed(), lesson.accent.getGreen(), lesson.accent.getBlue(), 120)
                        : new Color(255, 255, 255, 38);
                g.setColor(c);
                int x = (int) (10 + k * w + 3);
                int ww = (int) (w - 6);
                if (s.quiz != null) {
                    g.fillOval(x + ww / 2 - 5, y - 2, 10, 10);
                    if (s.quiz.solved) {
                        g.setColor(GOOD);
                        g.fillOval(x + ww / 2 - 3, y, 6, 6);
                    }
                } else {
                    g.fillRoundRect(x, y, ww, 6, 6, 6);
                }
            }
            g.dispose();
        }
    }

    /** Hands-on controls for the current step, kept in sync with the lesson state every frame. */
    static final class Controls {
        private final JPanel box;
        private final List<Runnable> syncers = new ArrayList<>();
        /** One line per control with its current setting, for the AI assistant. */
        final List<Supplier<String>> describers = new ArrayList<>();
        private boolean syncing;
        Controls(JPanel box) {
            this.box = box;
            box.add(Box.createVerticalStrut(8));
        }
        void sync() {
            syncing = true;
            try { for (Runnable r : syncers) r.run(); } finally { syncing = false; }
        }
        private JLabel caption(String text) {
            JLabel l = new JLabel(text);
            l.setFont(uiFont(Font.BOLD, 12f));
            l.setForeground(DIM);
            return l;
        }
        /** A labelled slider over [min, max]; {@code onUser} fires only on user input. */
        void slider(String name, double min, double max, DoubleSupplier get, DoubleConsumer set,
                    DoubleFunction<String> fmt) {
            JPanel p = new JPanel(new BorderLayout(0, 2));
            p.setOpaque(false);
            p.setAlignmentX(LEFT_ALIGNMENT);
            p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 64));
            JLabel l = caption(name.toUpperCase());
            JLabel v = new JLabel();
            v.setFont(uiFont(Font.BOLD, 14f));
            v.setForeground(INK);
            JPanel top = new JPanel(new BorderLayout());
            top.setOpaque(false);
            top.add(l, BorderLayout.WEST);
            top.add(v, BorderLayout.EAST);
            JSlider s = new JSlider(0, 1000, 0);
            s.setOpaque(false);
            s.setFocusable(true);
            s.addChangeListener(_ -> {
                if (syncing) return;
                set.accept(min + (max - min) * s.getValue() / 1000.0);
            });
            p.add(top, BorderLayout.NORTH);
            p.add(s, BorderLayout.CENTER);
            box.add(p);
            box.add(Box.createVerticalStrut(10));
            Runnable sy = () -> {
                double x = get.getAsDouble();
                if (!s.getValueIsAdjusting()) {
                    int iv = (int) Math.round(1000 * (x - min) / (max - min));
                    if (iv != s.getValue()) s.setValue(iv);
                }
                String txt = fmt.apply(x);
                if (!txt.equals(v.getText())) v.setText(txt);
            };
            syncers.add(sy);
            syncing = true; sy.run(); syncing = false;
            describers.add(() -> name + ": " + fmt.apply(get.getAsDouble()));
        }
        void toggle(String name, BooleanSupplier get, Consumer<Boolean> set) {
            JCheckBox c = new JCheckBox(name);
            c.setOpaque(false);
            c.setFont(uiFont(Font.PLAIN, 13f));
            c.setForeground(INK);
            c.setFocusable(false);
            c.setAlignmentX(LEFT_ALIGNMENT);
            c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));   // the row's width, not a clipped label
            c.addActionListener(_ -> { if (!syncing) set.accept(c.isSelected()); });
            box.add(c);
            box.add(Box.createVerticalStrut(8));
            syncers.add(() -> { if (c.isSelected() != get.getAsBoolean()) c.setSelected(get.getAsBoolean()); });
            c.setSelected(get.getAsBoolean());
            describers.add(() -> name + ": " + (get.getAsBoolean() ? "on" : "off"));
        }
        void choices(String name, String[] labels, IntSupplier get, IntConsumer set) {
            box.add(caption(name.toUpperCase()));
            box.add(Box.createVerticalStrut(4));
            JPanel g = new JPanel(new GridLayout(0, 3, 6, 6));
            g.setOpaque(false);
            g.setAlignmentX(LEFT_ALIGNMENT);
            ButtonGroup bg = new ButtonGroup();
            JToggleButton[] bs = new JToggleButton[labels.length];
            for (int i = 0; i < labels.length; i++) {
                final int k = i;
                JToggleButton b = new JToggleButton(labels[i]);
                b.setFont(uiFont(Font.PLAIN, 12f));
                b.setFocusable(false);
                b.addActionListener(_ -> { if (!syncing) set.accept(k); });
                bg.add(b);
                g.add(b);
                bs[i] = b;
            }
            g.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34 * ((labels.length + 2) / 3) + 6));
            box.add(g);
            box.add(Box.createVerticalStrut(10));
            syncers.add(() -> {
                int k = get.getAsInt();
                if (k < 0 || k >= bs.length) { if (bg.getSelection() != null) bg.clearSelection(); return; }   // none of them
                for (int i = 0; i < bs.length; i++) if (bs[i].isSelected() != (i == k)) bs[i].setSelected(i == k);
            });
            describers.add(() -> {
                int k = get.getAsInt();
                return name + ": " + (k >= 0 && k < labels.length ? labels[k] : "(none)") + "   [choices: " + String.join(", ", labels) + "]";
            });
        }
        /** The step's code cells, and a hook the studio sets so a finished run repaints the scene. */
        final List<CodeCell> cells = new ArrayList<>();
        Runnable changed = () -> { };

        /**
         * A Python code cell: an editor (Tab indents, Ctrl+Enter runs, blanks highlighted), Run and
         * Reset, then the check's verdict, anything the code printed, and its error if it had one.
         */
        void code(CodeCell cell) {
            cells.add(cell);
            Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 13);
            for (String f : new String[]{"Cascadia Mono", "Consolas", "Menlo", "DejaVu Sans Mono"})
                if (new Font(f, Font.PLAIN, 13).getFamily().equals(f)) { mono = new Font(f, Font.PLAIN, 13); break; }
            final Font monoF = mono;
            JTextArea ed = new JTextArea(cell.text);
            ed.setFont(monoF);
            ed.setTabSize(4);
            ed.setMargin(new Insets(8, 10, 8, 10));
            ed.setBackground(new Color(11, 15, 27));
            ed.setForeground(new Color(222, 230, 245));
            ed.setCaretColor(Color.WHITE);
            ed.setSelectionColor(new Color(50, 80, 140));
            Runnable highlight = () -> {
                ed.getHighlighter().removeAllHighlights();
                String t = ed.getText();
                var paint = new javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(new Color(150, 100, 20));
                for (int i = t.indexOf(CodeCell.BLANK); i >= 0; i = t.indexOf(CodeCell.BLANK, i + 1)) {
                    try { ed.getHighlighter().addHighlight(i, i + CodeCell.BLANK.length(), paint); } catch (Exception ignored) { }
                }
            };
            highlight.run();
            ed.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                void edit() { if (!syncing) { cell.text = ed.getText(); SwingUtilities.invokeLater(highlight); } }
                public void insertUpdate(javax.swing.event.DocumentEvent e) { edit(); }
                public void removeUpdate(javax.swing.event.DocumentEvent e) { edit(); }
                public void changedUpdate(javax.swing.event.DocumentEvent e) { }
            });
            ed.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "indent");
            ed.getActionMap().put("indent", new AbstractAction() {
                public void actionPerformed(ActionEvent e) { ed.replaceSelection("    "); }
            });
            ed.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "run");
            ed.getActionMap().put("run", new AbstractAction() {
                public void actionPerformed(ActionEvent e) { cell.run(changed); }
            });
            JScrollPane sp = new JScrollPane(ed, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
            sp.setBorder(BorderFactory.createLineBorder(PANEL_EDGE));
            sp.setAlignmentX(LEFT_ALIGNMENT);
            int lines = (int) cell.text.lines().count() + 1, lh = ed.getFontMetrics(monoF).getHeight();
            int h = Math.min(lines, 24) * lh + 34;
            sp.setPreferredSize(new Dimension(200, h));
            sp.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
            box.add(caption("YOUR CODE  ·  PYTHON"));
            box.add(Box.createVerticalStrut(4));
            box.add(sp);
            box.add(Box.createVerticalStrut(8));

            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            row.setOpaque(false);
            row.setAlignmentX(LEFT_ALIGNMENT);
            JButton run = new JButton("Run");
            run.setFont(uiFont(Font.BOLD, 13f));
            run.setBackground(new Color(44, 98, 170));
            run.setForeground(Color.WHITE);
            run.setFocusable(false);
            run.setToolTipText("Run the cell (Ctrl+Enter)");
            run.addActionListener(_ -> cell.run(changed));
            JButton reset = new JButton("Reset");
            reset.setFont(uiFont(Font.PLAIN, 12f));
            reset.setFocusable(false);
            reset.setToolTipText("Go back to the starting code");
            reset.addActionListener(_ -> {
                cell.reset();
                syncing = true;
                try { ed.setText(cell.text); } finally { syncing = false; }
                highlight.run();
                changed.run();
            });
            JLabel py = new JLabel();
            py.setFont(uiFont(Font.PLAIN, 11f));
            py.setForeground(DIM);
            row.add(run);
            row.add(Box.createHorizontalStrut(8));
            row.add(reset);
            row.add(Box.createHorizontalStrut(12));
            row.add(py);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, run.getPreferredSize().height + 2));
            box.add(row);
            box.add(Box.createVerticalStrut(8));

            JPanel out = new JPanel();
            out.setOpaque(false);
            out.setLayout(new BoxLayout(out, BoxLayout.Y_AXIS));
            out.setAlignmentX(LEFT_ALIGNMENT);
            box.add(out);
            box.add(Box.createVerticalStrut(10));

            int[] shown = {-1};
            Runnable sy = () -> {
                String st = PythonKernel.shared().status();
                String pyText = cell.running ? "running…" : st == null ? "" : st.length() > 60 ? "" : st;
                if (!pyText.equals(py.getText())) py.setText(pyText);
                run.setEnabled(!cell.running);
                if (shown[0] == cell.version) return;
                shown[0] = cell.version;
                if (!ed.getText().equals(cell.text)) {
                    syncing = true;
                    try { ed.setText(cell.text); } finally { syncing = false; }
                    highlight.run();
                }
                out.removeAll();
                PythonKernel.Result r = cell.result;
                if (r != null) {
                    if (r.unavailable()) out.add(outText(r.problem(), new Color(62, 28, 28), new Color(255, 201, 201), false, monoF));
                    if (r.error() != null) out.add(outText(r.error(), new Color(62, 28, 28), new Color(255, 201, 201), true, monoF));
                    List<Object[]> checks = r.checks();
                    if (!checks.isEmpty()) {
                        StringBuilder b = new StringBuilder();
                        for (Object[] c : checks) b.append(b.length() > 0 ? "\n" : "").append((Boolean) c[0] ? "Passed:  " : "Not yet:  ").append(c[1]);
                        out.add(outText(b.toString(), r.passed() ? new Color(23, 58, 38) : new Color(58, 44, 20),
                                r.passed() ? new Color(191, 245, 207) : new Color(255, 222, 170), false, monoF));
                    }
                    if (!r.stdout().isBlank()) {
                        JLabel cap = caption("PRINTED");
                        cap.setAlignmentX(LEFT_ALIGNMENT);
                        out.add(Box.createVerticalStrut(6));
                        out.add(cap);
                        out.add(outText(r.stdout().stripTrailing(), new Color(11, 15, 27), new Color(200, 210, 228), true, monoF));
                    }
                }
                out.revalidate();
                out.repaint();
            };
            syncers.add(sy);
            syncing = true; sy.run(); syncing = false;
            describers.add(() -> "Python code cell (the student's code now):\n" + cell.text.strip()
                    + "\n  last run: " + cell.summary()
                    + "\n  a working version (for tutoring only; guide, don't hand it over):\n" + cell.solution.strip());
        }

        private static JTextArea outText(String text, Color bg, Color fg, boolean mono, Font monoF) {
            JTextArea a = new JTextArea(text);
            a.setEditable(false);
            a.setLineWrap(true);
            a.setWrapStyleWord(!mono);
            a.setFont(mono ? monoF.deriveFont(12f) : uiFont(Font.PLAIN, 13f));
            a.setBackground(bg);
            a.setForeground(fg);
            a.setBorder(new EmptyBorder(8, 10, 8, 10));
            a.setAlignmentX(LEFT_ALIGNMENT);
            a.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
            return a;
        }

        void button(String name, Runnable r) {
            describers.add(() -> "button: " + name);
            JButton b = new JButton(name);
            b.setFont(uiFont(Font.PLAIN, 12f));
            b.setFocusable(false);
            b.setAlignmentX(LEFT_ALIGNMENT);
            b.addActionListener(_ -> r.run());
            box.add(b);
            box.add(Box.createVerticalStrut(8));
        }
    }

    // =====================================================================
    //  Host — what a lesson may ask of the running simulation
    // =====================================================================

    /**
     * The live simulation, as seen by the lessons. Lessons use the real Sun, planets and clock:
     * they choose what is visible, how fast time runs and where the camera looks, read real
     * positions and orbital elements, and project their own annotations through the sim's camera.
     * All positions are heliocentric, in metres, in the sim's world frame (z = ecliptic north).
     */
    interface Host {
        int planet(String name);
        /** Show only these planets (and optionally one catalogued small body); hide everything else. */
        void showOnly(String smallBody, String... planets);
        void setOrbits(boolean orbits, boolean labels);
        /** Simulated seconds per real second; 0 pauses. */
        void setTime(double simSecondsPerSecond);
        double timeScale();
        void goToDate(int year, int month, int day);
        String dateString();
        double simTime();
        /**
         * Ease the camera to {centreX m, centreY m, radius m to fit, pitch deg off top-down, yaw deg},
         * optionally followed by the centre's height z (m; 0 = the ecliptic, or the followed body).
         */
        void aim(double[] goal);
        double sunRadiusM();

        double planetA(int i);
        double planetEcc(int i);
        double planetEccAnomaly(int i);
        double[] planetOrbit(int i, double E);
        double bodyA(String name);
        double bodyEcc(String name);
        double bodyEccAnomaly(String name);
        double[] bodyOrbit(String name, double E);

        /** World point (m) -> {screenX, screenY, distance from the eye (m), pixels per metre there}. */
        boolean project(double x, double y, double z, double[] out);
        double[] eye();
        /** The Sun's disc on screen {x, y, radius px, distance m}, or null when it is off-screen. */
        double[] sunDisc();

        // ---- dynamics: heliocentric world frame, metres and m/s ----

        /** {x, y, z, vx, vy, vz} at sim time t of a planet's centre, or of any planet's moon by name. */
        double[] stateAt(String body, double t);
        default double[] state(String body) { return stateAt(body, simTime()); }
        /** A planet system's barycentre (e.g. Pluto and Charon's) at sim time t. */
        double[] systemBaryAt(String planet, double t);
        /** The Sun's position and velocity relative to the Solar-System barycentre at sim time t. */
        double[] sunBaryAt(double t);
        /** GM (m³/s²) of the "Sun" or of a planet system. */
        double gm(String body);
        /** Draw the system around its barycentre, so the Sun shows its real wobble. */
        void setBarycentric(boolean on);
        /** {@link #aim} with the goal's centre measured from a moving body ("bary:Pluto" = a system barycentre). */
        void aim(double[] goal, String follow);

        // ---- the sky from a place on Earth (the planetarium view) ----

        /** Switch the canvas to the sky view (true) or back to the 3-D Solar System. */
        void sky(boolean on);
        void skyPlace(String name, double latDeg, double lonDeg, String zoneId);
        /** Ease the view to {azimuth°, altitude°, field°}. */
        void skyAim(double[] azAltFov);
        /** Track a body like a telescope: the goal is {Δazimuth°, Δaltitude°, field°} from it. */
        void skyAim(double[] goal, String track);
        void skyLayers(boolean altAzGrid, boolean eqGrid, boolean constellations, boolean ground);
        /** Keep celestial north up (an equatorial telescope) instead of the zenith (alt-az). */
        void skyNorthUp(boolean on);
        /** Naked-eye limit (NaN = the sky's own), telescope aperture in mm (NaN = none), B−V filter (null = all stars). */
        void skyOptics(double limitMag, double apertureMm, DoublePredicate starFilter);
        /** Air density relative to the sky's own (1 = normal, 0 = no air); extinction scales with it. */
        void skyAir(double density);
        /** {alt°, az°, RA h, Dec °, V mag, diameter ″, distance AU, B−V} of a body or named star, or null. */
        double[] skyBody(String name);
        double[] skyBodyAt(String name, double t);
        /** Apparent east-north-up unit vector of a body or named star, or null. */
        double[] skyEnu(String name);
        double[] skyEnuRaDec(double raHours, double decDeg);
        /** ENU direction -> {screenX, screenY, 1, pixels per radian}; false if behind the viewer. */
        boolean skyProject(double[] enu, double[] out);
        double skyLst();
        double skyLat();
        double skyLimit();
        double skySunAlt();
        double skyEclipsed();
        /** {rise, transit, set} in sim seconds (NaN = none) within ±12 h of now. */
        double[] skyRiseTransitSet(String name);
        String skyLocalTime();
        /** Local clock time "HH:mm" of a sim instant. */
        String skyClock(double simSec);
        void goToUTC(int year, int month, int day, int hour, int minute);
    }

    // =====================================================================
    //  Overlay — the current step's annotations, drawn into the live simulation view
    // =====================================================================

    final class Overlay {
        final R3 r3 = new R3();
        final Hud hud = new Hud();
        private double[] lastGoal;

        /** Re-send the step's camera goal whenever it changes (e.g. a slider moved the detector). */
        void tickCamera() {
            Step s = step();
            if (s == null) return;
            double[] g = s.sky ? s.skyCam.get() : s.cam.get();
            if (lastGoal == null || !java.util.Arrays.equals(g, lastGoal)) {
                if (s.sky) host.skyAim(g, s.follow);
                else if (s.follow != null) host.aim(g, s.follow);
                else host.aim(g);
                lastGoal = g.clone();
            }
        }
        void reaim() { lastGoal = null; }

        /** Three rings swelling out from the step's {@link Step#watch} point as the step opens. */
        private void pulseWatch(Step s) {
            if (s.watch == null) return;
            double age = simT - stepShownT;
            if (age > 3.4) return;
            double[] p = s.watch.get();
            if (p == null) return;
            for (int k = 0; k < 3; k++) {
                double f = (age - 0.25 - 0.9 * k) / 1.3;
                if (f > 0 && f < 1) r3.ring(p, 10 + 48 * f, alpha(SUN_C, (int) (235 * (1 - f))), 2.6f * (float) (1 - 0.5 * f));
            }
        }

        /** Sky steps: scene points are east-north-up directions, projected as the planetarium draws them. */
        private final R3.Projector skyProj = new R3.Projector() {
            private final double[] d = new double[3];
            public boolean proj(double x, double y, double z, double[] o) {
                d[0] = x; d[1] = y; d[2] = z;
                return host.skyProject(d, o);
            }
            public double[] eye() { return v(0, 0, 0); }
        };

        void paint(Graphics2D g0, int w, int h, float alpha) {
            Step s = step();
            if (s == null || alpha <= 0) return;
            Graphics2D g = (Graphics2D) g0.create();
            if (alpha < 1) g.setComposite(AlphaComposite.SrcOver.derive(alpha));
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            final double u = s.unitM;
            r3.ext = s.sky ? skyProj : new R3.Projector() {
                public boolean proj(double x, double y, double z, double[] o) {
                    if (!host.project(x * u, y * u, z * u, o)) return false;
                    o[2] /= u;
                    o[3] *= u;
                    return true;
                }
                public double[] eye() { double[] e = host.eye(); return v(e[0] / u, e[1] / u, e[2] / u); }
            };
            r3.begin(w, h);
            double[] sun = s.sky ? null : host.sunDisc();
            if (sun != null) r3.occluder(sun[0], sun[1], sun[2], sun[3] / u);
            hud.clear();
            if (s.scene != null) s.scene.draw(r3, hud, simT);
            pulseWatch(s);
            r3.render(g);
            hud.paint(g, w, h);
            g.dispose();
        }
    }

    // =====================================================================
    //  R3 — a tiny depth-sorted software 3-D renderer on Java2D
    // =====================================================================

    static final class R3 {
        /** Orbit camera around a target; z is "up". */
        double yaw = Math.toRadians(-60), pitch = Math.toRadians(28), dist = 14, tx, ty, tz;
        int w = 1, h = 1;
        double focal = 1;
        double ex, ey, ez, fx, fy, fz, rx, ry, rz, ux, uy, uz;
        private final ArrayList<Prim> prims = new ArrayList<>(), top = new ArrayList<>();
        private final double[] pa = new double[4], pb = new double[4];

        /** Projection supplied by the host (the live sim camera); o = {x, y, depth, px per unit}. */
        interface Projector { boolean proj(double x, double y, double z, double[] o); double[] eye(); }
        Projector ext;
        /** An opaque disc (the real Sun): primitives behind it and inside it are hidden. */
        private double[] occ;
        void occluder(double sx, double sy, double rpx, double depth) { occ = new double[]{sx, sy, rpx, depth}; }
        private final Map<Float, BasicStroke> strokes = new HashMap<>();

        void begin(int w, int h) {
            this.w = w; this.h = h;
            occ = null;
            if (ext != null) {
                double[] e = ext.eye();
                ex = e[0]; ey = e[1]; ez = e[2];
                prims.clear();
                top.clear();
                return;
            }
            focal = Math.min(w * 0.8, h * 1.05);
            double cp = Math.cos(pitch);
            ex = tx + dist * cp * Math.cos(yaw);
            ey = ty + dist * cp * Math.sin(yaw);
            ez = tz + dist * Math.sin(pitch);
            fx = tx - ex; fy = ty - ey; fz = tz - ez;
            double n = Math.sqrt(fx * fx + fy * fy + fz * fz);
            fx /= n; fy /= n; fz /= n;
            rx = fy; ry = -fx; rz = 0;                           // right = forward x up(z)
            n = Math.hypot(rx, ry);
            if (n < 1e-9) { rx = 1; ry = 0; n = 1; }
            rx /= n; ry /= n;
            ux = ry * fz - rz * fy; uy = rz * fx - rx * fz; uz = rx * fy - ry * fx;
            prims.clear();
            top.clear();
        }

        /** World point -> {screenX, screenY, depth}; false if behind the camera. */
        boolean proj(double x, double y, double z, double[] o) {
            if (ext != null) return ext.proj(x, y, z, o);
            double dx = x - ex, dy = y - ey, dz = z - ez;
            double cz = dx * fx + dy * fy + dz * fz;
            if (cz < 0.05) return false;
            o[0] = w * 0.5 + focal * (dx * rx + dy * ry + dz * rz) / cz;
            o[1] = h * 0.5 - focal * (dx * ux + dy * uy + dz * uz) / cz;
            o[2] = cz;
            o[3] = focal / cz;
            return true;
        }

        /** True when a surface at p with outward normal n faces the camera. */
        boolean facing(double px, double py, double pz, double nx, double ny, double nz) {
            return (ex - px) * nx + (ey - py) * ny + (ez - pz) * nz > 0;
        }

        /** Unit vector from p toward the eye. */
        double[] toEye(double px, double py, double pz) {
            double dx = ex - px, dy = ey - py, dz = ez - pz, n = Math.sqrt(dx * dx + dy * dy + dz * dz);
            return new double[]{dx / n, dy / n, dz / n};
        }

        private BasicStroke stroke(float w) {
            return strokes.computeIfAbsent(w, k -> new BasicStroke(k, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        }

        // ---------------------------------------------------------------- primitives

        abstract static class Prim { double depth, sx, sy; abstract void draw(Graphics2D g); }

        static final class Seg extends Prim {
            final Line2D.Double l; final Color c; final Stroke s;
            Seg(double x1, double y1, double x2, double y2, double d, Color c, Stroke s) {
                l = new Line2D.Double(x1, y1, x2, y2); depth = d; this.c = c; this.s = s;
            }
            void draw(Graphics2D g) { g.setColor(c); g.setStroke(s); g.draw(l); }
        }

        static final class Poly extends Prim {
            final Shape p; final Color fill, edge; final Stroke es;
            Poly(Shape p, double d, Color fill, Color edge, Stroke es) {
                this.p = p; depth = d; this.fill = fill; this.edge = edge; this.es = es;
            }
            void draw(Graphics2D g) {
                if (fill != null) { g.setColor(fill); g.fill(p); }
                if (edge != null) { g.setColor(edge); g.setStroke(es); g.draw(p); }
            }
        }

        static final class Glow extends Prim {
            final double x, y, r; final Color c;
            Glow(double x, double y, double r, double d, Color c) { this.x = x; this.y = y; this.r = r; depth = d; this.c = c; }
            void draw(Graphics2D g) {
                if (r < 0.5) return;
                Color a = c, b = new Color(c.getRed(), c.getGreen(), c.getBlue(), c.getAlpha() / 4),
                      z = new Color(c.getRed(), c.getGreen(), c.getBlue(), 0);
                g.setPaint(new RadialGradientPaint(new Point2D.Double(x, y), (float) r,
                        new float[]{0f, 0.3f, 1f}, new Color[]{a, b, z}));
                g.fill(new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r));
            }
        }

        static final class Text extends Prim {
            final String s; final double x, y; final Color c; final Font f; final boolean box;
            Text(String s, double x, double y, Color c, Font f, boolean box) {
                this.s = s; this.x = x; this.y = y; this.c = c; this.f = f; this.box = box;
            }
            void draw(Graphics2D g) {
                g.setFont(f);
                FontMetrics fm = g.getFontMetrics();
                int tw = fm.stringWidth(s);
                double lx = x - tw / 2.0, ly = y + fm.getAscent() / 2.0 - 2;
                if (box) {
                    g.setColor(new Color(6, 10, 22, 185));
                    g.fill(new RoundRectangle2D.Double(lx - 6, ly - fm.getAscent() - 1, tw + 12, fm.getAscent() + fm.getDescent() + 3, 10, 10));
                }
                g.setColor(c);
                g.drawString(s, (float) lx, (float) ly);
            }
        }

        // ---------------------------------------------------------------- builders

        void seg(double[] a, double[] b, Color c, float w) {
            if (!proj(a[0], a[1], a[2], pa) || !proj(b[0], b[1], b[2], pb)) return;
            Seg sg = new Seg(pa[0], pa[1], pb[0], pb[1], (pa[2] + pb[2]) * 0.5, c, stroke(w));
            sg.sx = (pa[0] + pb[0]) / 2; sg.sy = (pa[1] + pb[1]) / 2;
            prims.add(sg);
        }

        /** Straight line, split into pieces so it depth-sorts correctly against surfaces; dashed skips alternate pieces. */
        void line(double[] a, double[] b, Color c, float w, boolean dashed) {
            double len = Math.sqrt(sq(b[0] - a[0]) + sq(b[1] - a[1]) + sq(b[2] - a[2]));
            int n = Math.max(1, (int) Math.ceil(len / (dashed ? 0.16 : 0.5)));
            // Dashes are cut in scene units; in a scene measured in thousands of km they'd shrink below a
            // pixel and the line would look solid, so keep each dash at least ~4 px on screen.
            if (dashed && proj(a[0], a[1], a[2], pa) && proj(b[0], b[1], b[2], pb))
                n = Math.min(n, Math.max(3, (int) (Math.hypot(pb[0] - pa[0], pb[1] - pa[1]) / 8)));
            if (dashed && n % 2 == 0) n++;
            for (int i = 0; i < n; i++) {
                if (dashed && (i & 1) == 1) continue;
                seg(lerp(a, b, (double) i / n), lerp(a, b, (double) (i + 1) / n), c, w);
            }
        }
        void line(double[] a, double[] b, Color c, float w) { line(a, b, c, w, false); }

        void polyline(double[][] p, boolean closed, Color c, float w, boolean dashed) {
            int n = closed ? p.length : p.length - 1;
            for (int i = 0; i < n; i++) {
                if (dashed && ((i / 2) & 1) == 1) continue;
                seg(p[i], p[(i + 1) % p.length], c, w);
            }
        }

        void poly(double[][] pts, Color fill, Color edge, float ew, double bias) {
            Path2D.Double path = new Path2D.Double();
            double d = 0, cx = 0, cy = 0;
            for (int i = 0; i < pts.length; i++) {
                if (!proj(pts[i][0], pts[i][1], pts[i][2], pa)) return;
                if (i == 0) path.moveTo(pa[0], pa[1]); else path.lineTo(pa[0], pa[1]);
                d += pa[2]; cx += pa[0]; cy += pa[1];
            }
            path.closePath();
            Poly pl = new Poly(path, d / pts.length + bias, fill, edge, edge == null ? null : stroke(ew));
            pl.sx = cx / pts.length; pl.sy = cy / pts.length;
            prims.add(pl);
        }
        void poly(double[][] pts, Color fill, Color edge, float ew) { poly(pts, fill, edge, ew, 0); }

        void dot(double[] p, double px, Color c) { dot(p, px, c, 0); }
        void dot(double[] p, double px, Color c, double bias) {
            if (!proj(p[0], p[1], p[2], pa)) return;
            Poly pl = new Poly(new Ellipse2D.Double(pa[0] - px, pa[1] - px, 2 * px, 2 * px), pa[2] + bias, c, null, null);
            pl.sx = pa[0]; pl.sy = pa[1];
            prims.add(pl);
        }
        void ring(double[] p, double px, Color c, float w) {
            if (!proj(p[0], p[1], p[2], pa)) return;
            Poly pl = new Poly(new Ellipse2D.Double(pa[0] - px, pa[1] - px, 2 * px, 2 * px), pa[2] - 0.01, null, c, stroke(w));
            pl.sx = pa[0]; pl.sy = pa[1];
            prims.add(pl);
        }

        /** Soft light halo of world radius {@code worldR}; {@code bias} pushes it back in the sort. */
        void glow(double[] p, double worldR, Color c, double bias) {
            if (!proj(p[0], p[1], p[2], pa)) return;
            prims.add(new Glow(pa[0], pa[1], worldR * pa[3], pa[2] + bias, c));
        }

        /** Shading callback: outward normal and mu = cos(angle to the viewer). */
        @FunctionalInterface interface Shade { Color at(double nx, double ny, double nz, double mu); }

        /** Opaque, back-face-culled UV sphere. */
        void sphere(double[] c, double R, int nLat, int nLon, Shade sh) {
            for (int i = 0; i < nLat; i++) {
                double t0 = Math.PI * i / nLat, t1 = Math.PI * (i + 1) / nLat, tm = (t0 + t1) / 2;
                for (int j = 0; j < nLon; j++) {
                    double p0 = 2 * Math.PI * j / nLon, p1 = 2 * Math.PI * (j + 1) / nLon, pm = (p0 + p1) / 2;
                    double nx = Math.sin(tm) * Math.cos(pm), ny = Math.sin(tm) * Math.sin(pm), nz = Math.cos(tm);
                    double cx = c[0] + R * nx, cy = c[1] + R * ny, cz = c[2] + R * nz;
                    if (!facing(cx, cy, cz, nx, ny, nz)) continue;
                    double[] e = toEye(cx, cy, cz);
                    double mu = Math.max(0, nx * e[0] + ny * e[1] + nz * e[2]);
                    Color col = sh.at(nx, ny, nz, mu);
                    double[][] q = {sph(c, R, t0, p0), sph(c, R, t0, p1), sph(c, R, t1, p1), sph(c, R, t1, p0)};
                    poly(q, col, col, 1.0f);
                }
            }
        }

        /** Arrow from a to b with a screen-space head. */
        void arrow(double[] a, double[] b, Color c, float w) {
            if (!proj(a[0], a[1], a[2], pa) || !proj(b[0], b[1], b[2], pb)) return;
            double dx = pb[0] - pa[0], dy = pb[1] - pa[1], len = Math.hypot(dx, dy);
            double hs = Math.min(len * 0.6, 8 + 2.6 * w);
            double tip = pb[2];
            if (len > 1) {
                double f = 1 - hs / len * 0.8;
                line(a, lerp(a, b, f), c, w, false);
                double ux1 = dx / len, uy1 = dy / len;
                double bx = pb[0] - ux1 * hs, by = pb[1] - uy1 * hs;
                Path2D.Double tri = new Path2D.Double();
                tri.moveTo(pb[0], pb[1]);
                tri.lineTo(bx - uy1 * hs * 0.5, by + ux1 * hs * 0.5);
                tri.lineTo(bx + uy1 * hs * 0.5, by - ux1 * hs * 0.5);
                tri.closePath();
                Poly pl = new Poly(tri, tip - 0.02, c, null, null);
                pl.sx = pb[0]; pl.sy = pb[1];
                prims.add(pl);
            }
        }

        /** Screen-facing text label drawn on top of the scene (never occluded). */
        private boolean hiddenBySun(double[] o) {
            return occ != null && o[2] > occ[3] && Math.hypot(o[0] - occ[0], o[1] - occ[1]) < occ[2];
        }
        void label(double[] p, String s, Color c, float size, int dx, int dy) {
            if (!proj(p[0], p[1], p[2], pa) || hiddenBySun(pa)) return;
            top.add(new Text(s, pa[0] + dx, pa[1] + dy, c, uiFont(Font.BOLD, size), true));
        }
        /** Label just below a sphere of world radius {@code worldR} centred at p (clears its disc). */
        void labelBelow(double[] p, double worldR, String s, Color c, float size) {
            if (!proj(p[0], p[1], p[2], pa)) return;
            double dy = Math.max(30, worldR * pa[3] + 16);
            top.add(new Text(s, pa[0], pa[1] + dy, c, uiFont(Font.BOLD, size), true));
        }
        void mathLabel(double[] p, String s, Color c, float size, int dx, int dy) {
            if (!proj(p[0], p[1], p[2], pa) || hiddenBySun(pa)) return;
            top.add(new Text(s, pa[0] + dx, pa[1] + dy, c, new Font(MATH_FONT, Font.ITALIC, 12).deriveFont(size), true));
        }

        void render(Graphics2D g) {
            prims.sort((a, b) -> Double.compare(b.depth, a.depth));
            Stroke os = g.getStroke();
            for (Prim p : prims) {
                if (occ != null && p.depth > occ[3] && !(p instanceof Glow)
                        && Math.hypot(p.sx - occ[0], p.sy - occ[1]) < occ[2]) continue;   // behind the Sun
                p.draw(g);
            }
            for (Prim p : top) p.draw(g);
            g.setStroke(os);
        }
    }

    // =====================================================================
    //  HUD — readout card, formula strip and optional inset, drawn over the scene
    // =====================================================================

    static final class Hud {
        String title; Color titleColor = ACCENT;
        final ArrayList<Object[]> rows = new ArrayList<>();
        final ArrayList<String> formulas = new ArrayList<>();
        String insetTitle; int insetW, insetH; BiConsumer<Graphics2D, Rectangle> inset;

        void clear() { title = null; rows.clear(); formulas.clear(); inset = null; }
        void title(String t, Color c) { title = t; titleColor = c; }
        void row(String k, String v) { row(k, v, INK); }
        void row(String k, String v, Color c) { rows.add(new Object[]{k, v, c}); }
        void gap() { rows.add(null); }
        void formula(String f) { formulas.add(f); }
        void inset(String t, int w, int h, BiConsumer<Graphics2D, Rectangle> p) { insetTitle = t; insetW = w; insetH = h; inset = p; }

        void paint(Graphics2D g, int w, int h) {
            double s = Math.max(0.85, Math.min(1.3, h / 800.0));
            Font kf = uiFont(Font.PLAIN, (float) (13 * s)), vf = uiFont(Font.BOLD, (float) (13.5 * s));
            Font tf = uiFont(Font.BOLD, (float) (11.5 * s));
            if (title != null || !rows.isEmpty()) {
                FontMetrics km = g.getFontMetrics(kf), vm = g.getFontMetrics(vf), tm = g.getFontMetrics(tf);
                int kw = 0, vw = 0;
                for (Object[] r : rows) {
                    if (r == null) continue;
                    kw = Math.max(kw, km.stringWidth((String) r[0]));
                    vw = Math.max(vw, vm.stringWidth((String) r[1]));
                }
                int pad = (int) (14 * s), lh = (int) (21 * s), gapH = (int) (8 * s);
                int cw = Math.max(pad * 2 + kw + (int) (18 * s) + vw, title == null ? 0 : tm.stringWidth(title) + pad * 2);
                int ch = pad * 2 - (int) (4 * s) + (title == null ? 0 : lh);
                for (Object[] r : rows) ch += r == null ? gapH : lh;
                int x = 16, y = 16;
                g.setColor(new Color(8, 13, 28, 205));
                g.fillRoundRect(x, y, cw, ch, 16, 16);
                g.setColor(new Color(255, 255, 255, 28));
                g.setStroke(new BasicStroke(1f));
                g.drawRoundRect(x, y, cw, ch, 16, 16);
                int yy = y + pad + km.getAscent() - (int) (2 * s);
                if (title != null) {
                    g.setFont(tf);
                    g.setColor(titleColor);
                    g.drawString(title.toUpperCase(), x + pad, yy);
                    yy += lh;
                }
                for (Object[] r : rows) {
                    if (r == null) { yy += gapH; continue; }
                    g.setFont(kf);
                    g.setColor(DIM);
                    g.drawString((String) r[0], x + pad, yy);
                    g.setFont(vf);
                    g.setColor((Color) r[2]);
                    String v = (String) r[1];
                    g.drawString(v, x + cw - pad - vm.stringWidth(v), yy);
                    yy += lh;
                }
            }
            if (!formulas.isEmpty()) {
                Font ff = new Font(MATH_FONT, Font.PLAIN, 12).deriveFont((float) (20 * s));
                FontMetrics fm = g.getFontMetrics(ff);
                int lh = (int) (fm.getAscent() * 1.25);
                int y = h - 40 - lh * formulas.size();
                for (String f : formulas) {
                    int fw = fm.stringWidth(f);
                    int x = (w - fw) / 2;
                    g.setColor(new Color(8, 13, 28, 205));
                    g.fillRoundRect(x - 16, y - fm.getAscent() - 4, fw + 32, fm.getAscent() + 14, 16, 16);
                    g.setFont(ff);
                    g.setColor(new Color(255, 226, 160));
                    g.drawString(f, x, y + 2);
                    y += lh + 4;
                }
            }
            if (inset != null) {
                int iw = (int) (insetW * s), ih = (int) (insetH * s);
                int x = w - iw - 16, y = 16;
                g.setColor(new Color(8, 13, 28, 215));
                g.fillRoundRect(x, y, iw, ih + (int) (26 * s), 16, 16);
                g.setColor(new Color(255, 255, 255, 28));
                g.drawRoundRect(x, y, iw, ih + (int) (26 * s), 16, 16);
                g.setFont(tf);
                g.setColor(DIM);
                g.drawString(insetTitle.toUpperCase(), x + 12, y + (int) (18 * s));
                Graphics2D gi = (Graphics2D) g.create();
                Rectangle r = new Rectangle(x + 10, y + (int) (26 * s), iw - 20, ih - 8);
                gi.clip(r);
                inset.accept(gi, r);
                gi.dispose();
            }
        }
    }

    // =====================================================================
    //  Math helpers
    // =====================================================================

    static double sq(double x) { return x * x; }
    static double[] v(double x, double y, double z) { return new double[]{x, y, z}; }
    static double[] add(double[] a, double[] b) { return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]}; }
    static double[] scaleV(double[] a, double k) { return new double[]{a[0] * k, a[1] * k, a[2] * k}; }
    static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }
    static double[] norm(double[] a) {
        double n = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        return n < 1e-15 ? new double[]{0, 0, 1} : new double[]{a[0] / n, a[1] / n, a[2] / n};
    }
    static double[] lerp(double[] a, double[] b, double t) {
        return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }
    static double[] sph(double[] c, double R, double th, double ph) {
        return new double[]{c[0] + R * Math.sin(th) * Math.cos(ph), c[1] + R * Math.sin(th) * Math.sin(ph), c[2] + R * Math.cos(th)};
    }
    static Color alpha(Color c, int a) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a))); }
    static Color mix(Color a, Color b, double t) {
        t = Math.max(0, Math.min(1, t));
        return new Color((int) (a.getRed() + (b.getRed() - a.getRed()) * t), (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t), (int) (a.getAlpha() + (b.getAlpha() - a.getAlpha()) * t));
    }
    static Color scale(Color c, double k) {
        return new Color((int) Math.min(255, c.getRed() * k), (int) Math.min(255, c.getGreen() * k), (int) Math.min(255, c.getBlue() * k), c.getAlpha());
    }
    static double ease(double cur, double goal, double dt, double rate) { return cur + (goal - cur) * (1 - Math.exp(-rate * dt)); }

    /** Solve Kepler's equation E - e sin E = M (Newton). */
    static double keplerE(double M, double e) {
        double E = e < 0.8 ? M : Math.PI;
        for (int i = 0; i < 50; i++) {
            double f = E - e * Math.sin(E) - M;
            E -= f / (1 - e * Math.cos(E));
            if (Math.abs(f) < 1e-12) break;
        }
        return E;
    }

    private static final String SUPS = "⁰¹²³⁴⁵⁶⁷⁸⁹";
    static String sup(int n) {
        StringBuilder b = new StringBuilder();
        if (n < 0) { b.append('⁻'); n = -n; }
        for (char ch : Integer.toString(n).toCharArray()) b.append(SUPS.charAt(ch - '0'));
        return b.toString();
    }
    /** 3.83e26 -> "3.83 × 10²⁶". */
    static String sci(double x, int digits) {
        if (x == 0) return "0";
        int ex = (int) Math.floor(Math.log10(Math.abs(x)));
        double m = x / Math.pow(10, ex);
        if (Math.abs(m) >= 9.995 && digits <= 2) { m /= 10; ex++; }
        return String.format("%." + digits + "f × 10%s", m, sup(ex));
    }
    static String au(double x) {
        return x >= 10 ? String.format("%.1f AU", x) : x >= 2 ? String.format("%.2f AU", x) : String.format("%.3f AU", x);
    }
    static String wm2(double f) {
        return f >= 1e5 ? sci(f, 2) + " W/m²" : f >= 100 ? String.format("%,.0f W/m²", f) : String.format("%.1f W/m²", f);
    }

    // =====================================================================
    //  LESSON 1 (Beginner) — eccentricity, perihelion & aphelion, on the real planets
    // =====================================================================

    static final class OrbitLesson {
        static final double AU = 1.495978707e11, DAY = 86400, YEAR = 365.25 * DAY;
        static final double V1AU = 29.78;              // km/s: circular speed at 1 AU, sqrt(GM_sun / 1 AU)
        static final String[] BODY = {"Venus", "Earth", "Mars", "Mercury", "Pluto", "Halley"};
        static final String HALLEY = "1P/Halley";
        static final String[] BODY_NOTE = {
            "the roundest planetary orbit",
            "perihelion ~Jan 3, aphelion ~Jul 4",
            "q to Q varies by ~20%",
            "the most eccentric planet",
            "sometimes closer than Neptune",
            "dives inside Venus's orbit"};

        final Host host;
        OrbitLesson(Host host) { this.host = host; }

        /** An orbit the lesson annotates (AU, heliocentric): a real planet, a real comet, or a what-if ellipse. */
        interface Orbit {
            double a();                 // semi-major axis (AU)
            double e();
            double[] at(double E);      // point on the orbit at eccentric anomaly E (AU)
            double E();                 // current eccentric anomaly
            default double[] now() { return at(E()); }
            default double r() { double[] p = now(); return Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]); }
            default double q() { return a() * (1 - e()); }
            default double Q() { return a() * (1 + e()); }
            /** Vis-viva speed (km/s) at distance r (AU). */
            default double speed(double r) { return V1AU * Math.sqrt(Math.max(0, 2 / r - 1 / a())); }
            default double periodYears() { return Math.pow(a(), 1.5); }
        }

        final class Planet implements Orbit {
            final String name;
            Planet(String name) { this.name = name; }
            int i() { return host.planet(name); }
            public double a() { return host.planetA(i()) / AU; }
            public double e() { return host.planetEcc(i()); }
            public double[] at(double E) { return auV(host.planetOrbit(i(), E)); }
            public double E() { return host.planetEccAnomaly(i()); }
        }

        final class Comet implements Orbit {
            final String name;
            Comet(String name) { this.name = name; }
            public double a() { return host.bodyA(name) / AU; }
            public double e() { return host.bodyEcc(name); }
            public double[] at(double E) { return auV(host.bodyOrbit(name, E)); }
            public double E() { return host.bodyEccAnomaly(name); }
        }

        /** A hypothetical orbit in the ecliptic, perihelion along +x, moving with the real sim clock. */
        final class Ghost implements Orbit {
            double a = 1, e = 0, eGoal = 0;
            public double a() { return a; }
            public double e() { return e; }
            public double[] at(double E) { return v(a * (Math.cos(E) - e), a * Math.sqrt(1 - e * e) * Math.sin(E), 0); }
            public double E() {
                double M = 2 * Math.PI * host.simTime() / (periodYears() * YEAR);
                return keplerE(((M % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI), e);
            }
        }

        static double[] auV(double[] m) { return v(m[0] / AU, m[1] / AU, m[2] / AU); }

        final Planet mercury = new Planet("Mercury"), earth = new Planet("Earth");
        final Ghost ghost = new Ghost();
        boolean autoE; double autoT;
        int body = 1;                     // step 6 selection

        Orbit bodyOrbit(int k) { return k == 5 ? new Comet(HALLEY) : new Planet(BODY[k]); }

        void tick(double dt) {
            if (autoE) {
                autoT += dt;
                ghost.e = ghost.eGoal = 0.4 - 0.4 * Math.cos(autoT * 2 * Math.PI / 12.0);
            } else {
                ghost.e = ease(ghost.e, ghost.eGoal, dt, 4);
            }
        }
        void userE(double x) { ghost.e = ghost.eGoal = x; autoE = false; }

        /** Sim time speed so that an orbit of the given period takes about {@code seconds} on screen. */
        void timeFor(Orbit o, double seconds) { host.setTime(o.periodYears() * YEAR / seconds); }

        /** Frame an orbit: centred on the ellipse, big enough to hold its aphelion. */
        Supplier<double[]> frame(Orbit o, double pitch, double yaw) {
            return () -> {
                double[] c = lerp(o.at(0), o.at(Math.PI), 0.5);
                return new double[]{c[0] * AU, c[1] * AU, o.a() * AU * 1.12, pitch, yaw, c[2] * AU};
            };
        }

        // drawing flags
        static final int FOCI = 1, AXES = 2, PERI = 4, APO = 8, SPEED = 16, STRINGS = 32, REF = 64,
                         PATH = 128, RADIUS = 256, NUMBERS = 512, PULSE_P = 1024, PULSE_A = 2048, BODY_DOT = 4096;

        /** Annotate orbit {@code o} over the live simulation (the sim itself draws the Sun and real planets). */
        void draw(R3 r, Orbit o, int f) {
            double a = o.a(), e = o.e(), c = a * e;
            double[] sun = v(0, 0, 0);
            double[] P = o.at(0), A = o.at(Math.PI);
            double[] C = lerp(P, A, 0.5), F2 = v(2 * C[0], 2 * C[1], 2 * C[2]);
            double[] majorDir = norm(v(P[0] - C[0], P[1] - C[1], P[2] - C[2]));
            double[] minor = o.at(Math.PI / 2);                     // on the minor axis (up to the focus shift)
            double[] up = norm(cross(majorDir, norm(v(minor[0] - C[0] + c * majorDir[0], minor[1] - C[1] + c * majorDir[1], minor[2] - C[2] + c * majorDir[2]))));

            if ((f & PATH) != 0) {
                int N = 220;
                double[][] path = new double[N][];
                for (int i = 0; i < N; i++) path[i] = o.at(2 * Math.PI * i / N);
                Color fill = new Color(190, 146, 255, 18);
                for (int i = 0; i < N; i += 4) r.poly(new double[][]{sun, path[i], path[(i + 4) % N]}, fill, null, 0, 0.3);
                r.polyline(path, true, new Color(200, 170, 255, 230), 2.2f, false);
            }
            if ((f & REF) != 0) {       // the e = 0 circle with the same a, for comparison
                double[][] ref = new double[160][];
                double[] side = cross(up, majorDir);
                for (int i = 0; i < 160; i++) {
                    double th = 2 * Math.PI * i / 160, cs = Math.cos(th) * a, sn = Math.sin(th) * a;
                    ref[i] = v(C[0] + cs * majorDir[0] + sn * side[0], C[1] + cs * majorDir[1] + sn * side[1], C[2] + cs * majorDir[2] + sn * side[2]);
                }
                r.polyline(ref, true, new Color(255, 255, 255, 60), 1.3f, true);
                r.label(ref[40], "circle with the same a (e = 0)", new Color(200, 210, 230), 11f, 0, -14);
            }
            if ((f & AXES) != 0) {
                r.line(A, P, new Color(255, 255, 255, 70), 1.2f, true);
                double lift = 0.14 * a;
                double[] Cl = add(C, scaleV(up, lift)), Pl = add(P, scaleV(up, lift));
                r.line(Cl, Pl, new Color(255, 255, 255, 200), 2.2f);
                r.line(C, Cl, new Color(255, 255, 255, 90), 1f);
                r.line(P, Pl, new Color(255, 255, 255, 90), 1f);
                r.mathLabel(lerp(Cl, Pl, 0.5), "a", Color.WHITE, 17f, 0, -14);
                if (c > 0.02 * a) {
                    double[] Cc = add(C, scaleV(up, 0.06 * a)), Sc = add(sun, scaleV(up, 0.06 * a));
                    r.line(Cc, Sc, SUN_C, 3f);
                    r.mathLabel(lerp(Cc, Sc, 0.5), "c", SUN_C, 17f, 0, -13);
                }
            }
            if ((f & FOCI) != 0) {
                boolean apart = c > 0.25 * a;
                r.dot(C, 3.5, new Color(255, 255, 255, 200));
                if (apart) r.label(C, "centre", new Color(210, 220, 240), 11f, 0, 16);
                if (c > 0.005 * a) {
                    r.ring(F2, 7, new Color(255, 200, 92, 230), 2f);
                    if (apart) r.label(F2, "empty focus", SUN_C, 11f, 0, 18);
                }
                r.label(sun, apart ? "Sun (a focus)" : "Sun (a focus); the centre and other focus are close by",
                        SUN_C, 11.5f, 0, 30);
            }

            if ((f & SPEED) != 0) drawEqualTimes(r, o);

            if ((f & PERI) != 0) {
                r.line(sun, P, PERI_C, 3.4f);
                r.dot(P, 6, PERI_C, -0.05);
                r.label(P, (f & NUMBERS) != 0 ? "Perihelion  q = " + au(o.q()) : "Perihelion", PERI_C, 12.5f, 0, -20);
            }
            if ((f & APO) != 0) {
                r.line(sun, A, APO_C, 3.4f);
                r.dot(A, 6, APO_C, -0.05);
                r.label(A, (f & NUMBERS) != 0 ? "Aphelion  Q = " + au(o.Q()) : "Aphelion", APO_C, 12.5f, 0, -20);
            }

            double E = o.E();
            double[] p = o.at(E);
            if ((f & STRINGS) != 0) {
                r.line(p, sun, PINK, 2.2f);
                r.line(p, F2, ORANGE, 2.2f);
                double d1 = a * (1 - e * Math.cos(E)), d2 = 2 * a - d1;
                r.mathLabel(lerp(p, sun, 0.5), String.format("d₁ = %.2f", d1), PINK, 14f, 0, -12);
                r.mathLabel(lerp(p, F2, 0.5), String.format("d₂ = %.2f", d2), ORANGE, 14f, 0, 16);
            }
            if ((f & RADIUS) != 0) {
                r.line(sun, p, new Color(255, 255, 255, 170), 1.6f, true);
                r.label(lerp(sun, p, 0.5), "r = " + au(o.r()), Color.WHITE, 12f, 0, 16);
            }
            double cosE = Math.cos(E);
            double pulse = 12 + 4 * Math.sin(System.nanoTime() / 1.2e8);
            if ((f & PULSE_P) != 0 && cosE > Math.cos(Math.toRadians(25))) {
                r.ring(p, pulse, PERI_C, 2.5f);
                r.label(p, "closest!", PERI_C, 13f, 0, 26);
            }
            if ((f & PULSE_A) != 0 && cosE < -Math.cos(Math.toRadians(25))) {
                r.ring(p, pulse, APO_C, 2.5f);
                r.label(p, "farthest!", APO_C, 13f, 0, 26);
            }
            if ((f & BODY_DOT) != 0) {         // the what-if planet (real planets are drawn by the sim)
                r.dot(p, 7, new Color(200, 170, 255), -0.05);
                r.ring(p, 9, new Color(235, 220, 255, 200), 1.5f);
            }
            if ((f & SPEED) != 0) {
                double[] p2 = o.at(E + 1e-3);
                double[] dir = norm(v(p2[0] - p[0], p2[1] - p[1], p2[2] - p[2]));
                double vv = o.speed(o.r());
                double len = Math.min(0.9 * a, 0.3 * a * vv / o.speed(a));      // longer arrow = faster
                double[] tip = add(p, scaleV(dir, len));
                r.arrow(p, tip, GREEN, 3f);
                r.label(tip, String.format("v = %.1f km/s", vv), GREEN, 12f, 0, -14);
            }
        }

        /** Kepler's 2nd law: 16 equal-time wedges (equal areas) and the dots that bound them. */
        void drawEqualTimes(R3 r, Orbit o) {
            int K = 16, sub = 10;
            double e = o.e();
            double[] sun = v(0, 0, 0);
            double Enow = o.E(), Mnow = Enow - e * Math.sin(Enow);
            int cur = (int) Math.floor((((Mnow % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI)) / (2 * Math.PI) * K) % K;
            for (int k = 0; k < K; k++) {
                Color col = (k % 2 == 0) ? new Color(126, 234, 150) : new Color(90, 200, 230);
                col = alpha(col, k == cur ? 95 : 38);
                double[] prev = o.at(keplerE(2 * Math.PI * k / K, e));
                for (int s = 1; s <= sub; s++) {
                    double[] nx = o.at(keplerE(2 * Math.PI * (k + (double) s / sub) / K, e));
                    r.poly(new double[][]{sun, prev, nx}, col, null, 0, 0.1);
                    prev = nx;
                }
                r.dot(o.at(keplerE(2 * Math.PI * k / K, e)), 3.2, new Color(255, 255, 255, 220), -0.02);
            }
        }

        /** Days since the orbit's last perihelion, from its mean anomaly. */
        double daysSincePeri(Orbit o) {
            double E = o.E(), M = E - o.e() * Math.sin(E);
            M = ((M % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI);
            return M / (2 * Math.PI) * o.periodYears() * 365.25;
        }

        /** Pause and move the clock so the body is {@code days} past perihelion (to the minute). */
        void scrubTo(Orbit o, double days) {
            host.setTime(0);
            double t = host.simTime() + (days - daysSincePeri(o)) * DAY;
            java.time.LocalDateTime d = java.time.LocalDateTime.of(2000, 1, 1, 12, 0).plusSeconds(Math.round(t));
            host.goToUTC(d.getYear(), d.getMonthValue(), d.getDayOfMonth(), d.getHour(), d.getMinute());
        }

        void timeSlider(Controls c, double maxDays) {
            c.slider("Time speed", 0, maxDays, () -> host.timeScale() / DAY, x -> host.setTime(x * DAY),
                    x -> x < 0.05 ? "paused" : String.format("%.1f days per second", x));
        }

        LessonDef build() {
            List<Step> s = new ArrayList<>();
            final double AUm = AU;

            s.add(new Step("Every planet follows an orbit", """
                These are the real inner planets, moving on their real orbits around the Sun. The path
                a planet takes is its {k:orbit}.

                Each orbit is an {k:ellipse}: a circle that has been stretched. Some are stretched so
                little you can hardly tell. Venus and Earth look like circles, but Mercury's orbit is
                visibly off-centre.

                In this lesson you'll measure that stretch with one number, and find each orbit's
                closest and farthest points from the Sun.

                > Drag to look at the orbits from any angle; scroll to zoom. Time is sped up.
                """)
                .unit(AUm)
                .enter(() -> { host.showOnly(null, "Mercury", "Venus", "Earth", "Mars"); host.setOrbits(true, true); host.setTime(10 * DAY); })
                .cam(() -> new double[]{0, 0, 1.7 * AUm, 50, 20})
                .controls(c -> timeSlider(c, 30))
                .scene((r, hud, t) -> {
                    hud.title("Distance from the Sun, right now", ACCENT);
                    for (String n : new String[]{"Mercury", "Venus", "Earth", "Mars"}) {
                        Planet pl = new Planet(n);
                        hud.row(n, String.format("%s   (e = %.3f)", au(pl.r()), pl.e()));
                    }
                    draw(r, earth, RADIUS);
                }));

            s.add(new Step("Stretch it: eccentricity", """
                The violet orbit is a what-if planet 1 AU from the Sun, like Earth, but you control how
                stretched its orbit is. Earth moves beside it on its real orbit.

                An ellipse has two special points inside it called {k:foci}. The Sun sits at one focus;
                the other focus is empty.

                {k:Eccentricity e} says how stretched the orbit is. It is the distance from the centre
                to a focus, **c**, divided by the **semi-major axis a** (half the longest width):

                $ e = c / a

                e = 0 is a circle (both foci meet at the centre). As e gets closer to 1 the orbit becomes
                longer and thinner.

                The pink and orange lines join the planet to each focus. However the orbit is stretched,
                they always add up to the same length, **2a**. That is the definition of an ellipse.

                > The slider moves by itself. Grab it to take over.
                """)
                .unit(AUm)
                .enter(() -> {
                    host.showOnly(null, "Earth"); host.setOrbits(true, true); host.setTime(25 * DAY);
                    ghost.a = 1; ghost.e = ghost.eGoal = 0; autoE = true; autoT = 0;
                })
                .cam(() -> new double[]{-0.3 * AUm, 0, 1.55 * AUm, 38, 0})
                .controls(c -> c.slider("Eccentricity e (what-if planet)", 0, 0.85, () -> ghost.e, this::userE, x -> String.format("%.2f", x)))
                .scene((r, hud, t) -> {
                    draw(r, ghost, PATH | BODY_DOT | FOCI | AXES | REF | STRINGS);
                    double E = ghost.E(), d1 = ghost.a * (1 - ghost.e * Math.cos(E));
                    hud.title("What-if orbit", VIOLET);
                    hud.row("eccentricity e", String.format("%.2f", ghost.e), SUN_C);
                    hud.row("semi-major axis a", au(ghost.a));
                    hud.row("centre to focus c = ae", au(ghost.a * ghost.e), SUN_C);
                    hud.row("semi-minor axis b", au(ghost.a * Math.sqrt(1 - ghost.e * ghost.e)));
                    hud.gap();
                    hud.row("d₁ + d₂", String.format("%.3f + %.3f = %.3f AU", d1, 2 * ghost.a - d1, 2 * ghost.a), PINK);
                    hud.row("Earth's real e", String.format("%.4f", earth.e()), ACCENT);
                    hud.formula("e = c / a");
                }));

            s.add(new Step("Perihelion: Mercury's closest point", """
                Mercury has the most stretched orbit of any planet (e = 0.206), so its distance from the
                Sun changes a lot. The closest point of an orbit is {p:perihelion} (Greek _peri_, near, +
                _helios_, Sun). It is the end of the long axis nearest the Sun, at a distance

                $ q = a (1 − e)

                Watch the distance readout as the real Mercury swings in. Slow time down to catch it at
                perihelion.
                """)
                .watch(() -> mercury.at(0))
                .unit(AUm)
                .enter(() -> { host.showOnly(null, "Mercury"); host.setOrbits(true, true); timeFor(mercury, 14); })
                .cam(frame(mercury, 32, 0))
                .controls(c -> timeSlider(c, 20))
                .scene((r, hud, t) -> {
                    draw(r, mercury, PERI | RADIUS | NUMBERS | PULSE_P);
                    hud.title("Mercury: perihelion", PERI_C);
                    hud.row("distance now r", au(mercury.r()));
                    hud.row("perihelion q = a(1 − e)", au(mercury.q()), PERI_C);
                    hud.row("a", au(mercury.a()));
                    hud.row("e", String.format("%.4f", mercury.e()));
                    hud.formula(String.format("q = a(1 − e) = %.3f × (1 − %.3f) = %.3f AU", mercury.a(), mercury.e(), mercury.q()));
                }));

            s.add(new Step("Aphelion: the farthest point", """
                The farthest point is {a:aphelion} (_apo_, away from), at the other end of the long axis:

                $ Q = a (1 + e)

                Put the two distances end to end and they span the whole long axis:

                $ q + Q = 2a

                So perihelion and aphelion tell you everything about the orbit's size and shape:
                **a = (q + Q) / 2** and **e = (Q − q) / (Q + q)**. For Mercury, Q is about 1.5 times q.
                """)
                .watch(() -> mercury.at(Math.PI))
                .unit(AUm)
                .enter(() -> { host.showOnly(null, "Mercury"); host.setOrbits(true, true); timeFor(mercury, 14); })
                .cam(frame(mercury, 38, 180))
                .controls(c -> timeSlider(c, 20))
                .scene((r, hud, t) -> {
                    draw(r, mercury, PERI | APO | RADIUS | NUMBERS | PULSE_A);
                    hud.title("Mercury: aphelion", APO_C);
                    hud.row("distance now r", au(mercury.r()));
                    hud.row("perihelion q", au(mercury.q()), PERI_C);
                    hud.row("aphelion Q = a(1 + e)", au(mercury.Q()), APO_C);
                    hud.row("q + Q", au(mercury.q() + mercury.Q()) + " = 2a");
                    hud.row("Q / q", String.format("%.2f×", mercury.Q() / mercury.q()));
                    hud.formula("q + Q = 2a");
                }));

            s.add(new Step("Fast near the Sun, slow far away", """
                The white dots mark where Mercury is at **equal time intervals** (every 5.5 days). They
                crowd together near aphelion and spread apart near perihelion: the planet moves
                {p:fastest at perihelion} and {a:slowest at aphelion}.

                This is Kepler's 2nd law: the line from the Sun to the planet sweeps out **equal areas in
                equal times**. Every coloured wedge has the same area. Close to the Sun the wedges are
                short, so they have to be wide.

                $ vₚ / vₐ = Q / q = (1 + e) / (1 − e)

                The green arrow is Mercury's real orbital speed.

                > Drag "Mercury's day" to walk it around the orbit yourself: every wedge is the same
                5.5 days, but near the Sun those days carry it much farther.
                """)
                .watch(() -> mercury.now())
                .unit(AUm)
                .enter(() -> { host.showOnly(null, "Mercury"); host.setOrbits(true, true); timeFor(mercury, 16); })
                .cam(frame(mercury, 20, 0))
                .controls(c -> {
                    timeSlider(c, 20);
                    c.slider("Mercury's day (since perihelion)", 0, 87.9, () -> daysSincePeri(mercury), x -> scrubTo(mercury, x),
                            x -> String.format("day %.0f of 88", x));
                })
                .scene((r, hud, t) -> {
                    draw(r, mercury, PERI | APO | SPEED);
                    hud.title("Mercury's speed", GREEN);
                    hud.row("speed now", String.format("%.1f km/s", mercury.speed(mercury.r())), GREEN);
                    hud.row("at perihelion vₚ", String.format("%.1f km/s", mercury.speed(mercury.q())), PERI_C);
                    hud.row("at aphelion vₐ", String.format("%.1f km/s", mercury.speed(mercury.Q())), APO_C);
                    hud.row("ratio vₚ / vₐ", String.format("%.2f×", mercury.speed(mercury.q()) / mercury.speed(mercury.Q())));
                    hud.row("(1 + e) / (1 − e)", String.format("%.2f", (1 + mercury.e()) / (1 - mercury.e())));
                    hud.formula("equal areas in equal times");
                }));

            s.add(new Step("Real orbits in our Solar System", """
                Pick a body to fly to its real orbit. Distances are in astronomical units (1 AU = Earth's
                average distance from the Sun, about 150 million km). Earth stays in view for scale.

                Planets have small eccentricities; Venus and Earth are almost circles. Comets are the
                extreme case. Halley's Comet (e = 0.97) dives inside Venus's orbit, then coasts out beyond
                Neptune.

                > Time is sped up so each orbit takes a few seconds. The speeding up and slowing down along
                each orbit is real.
                """)
                .unit(AUm)
                .enter(() -> selectBody(body))
                .cam(() -> frame(bodyOrbit(body), 40, 0).get())
                .controls(c -> c.choices("Body", BODY, () -> body, this::selectBody))
                .scene((r, hud, t) -> {
                    Orbit o = bodyOrbit(body);
                    draw(r, o, PERI | APO | NUMBERS);
                    hud.title(body == 5 ? "Halley's Comet" : BODY[body], ACCENT);
                    hud.row("eccentricity e", String.format("%.4f", o.e()));
                    hud.row("semi-major axis a", au(o.a()));
                    hud.row("perihelion q", au(o.q()), PERI_C);
                    hud.row("aphelion Q", au(o.Q()), APO_C);
                    hud.row("speed at q / at Q", String.format("%.1f / %.1f km/s", o.speed(o.q()), o.speed(o.Q())));
                    hud.row("orbital period", String.format("%.2f years", o.periodYears()));
                    hud.row("note", BODY_NOTE[body], DIM);
                }));

            s.add(new Step("Check: where is it fastest?", """
                Mercury again, with its equal-time dots.
                """)
                .reviews("Fast near the Sun, slow far away")
                .unit(AUm)
                .enter(() -> { host.showOnly(null, "Mercury"); host.setOrbits(true, true); timeFor(mercury, 16); })
                .cam(frame(mercury, 20, 0))
                .quiz("Where on its orbit does a planet move fastest?", 0,
                      "Near the Sun the line from the Sun to the planet is short, so to sweep the same area in the "
                    + "same time it has to swing through a wider angle. Mercury moves (1 + e)/(1 − e) = 1.52 times "
                    + "faster at perihelion than at aphelion.",
                      "At perihelion, the point closest to the Sun",
                      "At aphelion, the point farthest from the Sun",
                      "Halfway between perihelion and aphelion",
                      "Nowhere; it moves at a constant speed")
                .scene((r, hud, t) -> draw(r, mercury, SPEED)));

            s.add(new Step("Check: a comet's closest approach", """
                The violet path is a comet's orbit, with semi-major axis **a = 10 AU** and eccentricity
                **e = 0.9**. Earth is shown on its real orbit.
                """)
                .reviews("Perihelion: Mercury's closest point")
                .unit(AUm)
                .enter(() -> {
                    host.showOnly(null, "Earth", "Jupiter", "Saturn"); host.setOrbits(true, true);
                    ghost.a = 10; ghost.e = ghost.eGoal = 0.9; autoE = false; host.setTime(2 * YEAR);
                })
                .cam(() -> new double[]{-9 * AUm, 0, 11.5 * AUm, 40, 0})
                .quiz("How close does the comet come to the Sun?", 3,
                      "Perihelion is q = a(1 − e) = 10 × 0.1 = 1 AU, so it sweeps past Earth's orbit. "
                    + "Its aphelion is Q = a(1 + e) = 19 AU, out near Uranus.",
                      "19 AU", "10 AU", "9 AU", "1 AU")
                .scene((r, hud, t) -> {
                    draw(r, ghost, PATH | BODY_DOT);
                    hud.title("Comet", VIOLET);
                    hud.row("semi-major axis a", "10 AU");
                    hud.row("eccentricity e", "0.9");
                }));

            s.add(new Step("Check: distance and the seasons", """
                The simulation is set to **January 3, 2026**. Here is Earth on its real orbit that day.
                """)
                .reviews("Real orbits in our Solar System")
                .beyondSlides()                 // seasons aren't in the AST221 slides
                .unit(AUm)
                .enter(() -> { host.showOnly(null, "Earth"); host.setOrbits(true, true); host.goToDate(2026, 1, 3); host.setTime(0); })
                .cam(() -> new double[]{0, 0, 1.35 * AUm, 35, 0})
                .quiz("Earth reaches perihelion around January 3rd, in the middle of northern winter. "
                    + "What does that tell you?", 0,
                      "Earth's orbit has e = 0.017, so its distance only changes by ±1.7% (the sunlight by about 7%). "
                    + "Seasons are caused by the 23.4° tilt of Earth's axis, which is why the two hemispheres "
                    + "have opposite seasons at the same time.",
                      "Distance from the Sun is not what causes the seasons",
                      "The Sun is at the centre of Earth's orbit",
                      "Earth's orbit is very eccentric",
                      "Perihelion is the point farthest from the Sun")
                .scene((r, hud, t) -> {
                    draw(r, earth, PERI | APO | NUMBERS | RADIUS);
                    r.label(earth.at(0), "~ January 3", PERI_C, 11.5f, 0, 18);
                    r.label(earth.at(Math.PI), "~ July 4", APO_C, 11.5f, 0, 18);
                    hud.title("Earth", ACCENT);
                    hud.row("date in the simulation", host.dateString());
                    hud.row("eccentricity e", String.format("%.4f", earth.e()));
                    hud.row("distance today", au(earth.r()));
                    hud.row("perihelion q", au(earth.q()), PERI_C);
                    hud.row("aphelion Q", au(earth.Q()), APO_C);
                }));

            return new LessonDef("Beginner", "Orbits That Stretch",
                    "Eccentricity, perihelion and aphelion",
                    "Watch the real planets, stretch a what-if orbit into an ellipse, follow Mercury as it "
                  + "races past the Sun at perihelion and crawls at aphelion, then fly out to Pluto and "
                  + "Halley's Comet.",
                    new Color(255, 170, 90), s, this::tick);
        }

        void selectBody(int k) {
            body = k;
            Orbit o = bodyOrbit(k);
            if (k == 5) host.showOnly(HALLEY, "Earth");
            else if (k == 1) host.showOnly(null, "Earth");
            else host.showOnly(null, BODY[k], "Earth");
            host.setOrbits(true, true);
            timeFor(o, 12);
        }
    }

    // =====================================================================
    //  LESSON 2 (Intermediate) — luminosity vs flux vs intensity, around the real Sun
    // =====================================================================

    static final class LightLesson {
        static final double L_SUN = 3.828e26;                    // W
        static final double R_SUN = 6.957e8;                     // m
        static final double AU = 1.495978707e11;                 // m
        static final double SIGMA = 5.670374419e-8, T_SUN = 5772;
        static final double I_SUN = L_SUN / (4 * Math.PI * R_SUN * R_SUN) / Math.PI;   // W m^-2 sr^-1  (= sigma T^4 / pi)
        static final double UPA = 2.0;            // scene units per AU (bubble / flux / integral steps)
        static final double UNIT_M = AU / UPA;    // metres per scene unit in those steps
        static final double C_VIS = 2.4;          // light speed at AU scale (units / s): 1.2 AU/s, ~600x real
        static final double WINDOW = 3.0;         // s, sliding window for rate counters
        static final double CONE = 0.12;          // half-width (tan) of the 1-tile bundle at 1 AU
        static final double PIX = 0.03;           // half-angle of one detector pixel (rad): small, so its footprint stays nearly flat

        final Host host;
        LightLesson(Host host) { this.host = host; }

        // --- photon packets ---
        static final int OFF = 0, ISO = 1, BUNDLE = 2;
        int mode = OFF;
        final ArrayList<double[]> ph = new ArrayList<>();       // {dx, dy, dz, r}
        final ArrayDeque<Double> emitT = new ArrayDeque<>(), crossT = new ArrayDeque<>(), detT = new ArrayDeque<>();
        final ArrayDeque<double[]> tileHits = new ArrayDeque<>();   // {t, shell, i, j}
        final ArrayList<double[]> flashes = new ArrayList<>();      // {x, y, z, t0}
        double rate = 60, emitAcc, now;
        long seq;
        final Random rnd = new Random(42);
        /** Metres per scene unit for the current step: R_SUN near the Sun, UNIT_M for the AU-scale step. */
        double unit = R_SUN;
        double startR() { return host.sunRadiusM() / unit; }    // packets leave the real Sun's surface
        /**
         * Packet speed in scene units per second. Near the Sun it is the real speed of light
         * (one solar radius in 2.3 s); at AU scale light is sped up about 600 times so a packet
         * reaches 1 AU in under a second instead of 8.3 minutes.
         */
        double lightSpeed() { return unit == R_SUN ? 299_792_458.0 / R_SUN : C_VIS; }

        // --- step state ---
        double bubbleRs = 3; boolean lumpy;       // Gaussian bubble radius, in solar radii
        double detAU = 1.5;
        double intRs = 2.5; int bands = 8; double sweepT;   // integration sphere radius, in solar radii
        double dR = 4;                   // detector distance in solar radii (intensity steps)
        double sweep2T;

        void setMode(int m, double perSec) {
            if (m != mode) { ph.clear(); flashes.clear(); emitT.clear(); crossT.clear(); detT.clear(); tileHits.clear(); }
            mode = m; rate = perSec;
        }

        /** Only the Sun stays: every planet, moon, orbit and small body is hidden. */
        void sunOnly(double unitM) {
            unit = unitM;
            host.showOnly(null);
            host.setOrbits(false, false);
            host.setTime(DAY_S);                 // the real Sun keeps turning slowly
        }
        static final double DAY_S = 86400;

        double bubbleR(double dx, double dy, double dz) {
            double R = bubbleRs;                   // Sun-scale steps: 1 unit = 1 solar radius
            if (!lumpy) return R;
            double th = Math.acos(Math.max(-1, Math.min(1, dz))), phi = Math.atan2(dy, dx);
            return R * (1 + 0.26 * Math.sin(th) * Math.sin(th) * Math.cos(3 * phi) + 0.16 * Math.cos(2 * th));
        }

        void emit() {
            double dx, dy, dz;
            if (mode == ISO) {
                // R2 low-discrepancy sequence -> evenly spread isotropic directions.
                seq++;
                double u = (0.5 + seq * 0.7548776662466927) % 1, w = (0.5 + seq * 0.5698402909980532) % 1;
                dz = 1 - 2 * u;
                double s = Math.sqrt(1 - dz * dz), phi = 2 * Math.PI * w;
                dx = s * Math.cos(phi); dy = s * Math.sin(phi);
            } else {
                // Isotropic directions restricted to the square bundle around +x
                // (rejection-sampled with the gnomonic solid-angle Jacobian).
                double u, w;
                do {
                    u = (2 * rnd.nextDouble() - 1) * CONE;
                    w = (2 * rnd.nextDouble() - 1) * CONE;
                } while (rnd.nextDouble() > Math.pow(1 + u * u + w * w, -1.5));
                double n = Math.sqrt(1 + u * u + w * w);
                dx = 1 / n; dy = u / n; dz = w / n;
            }
            ph.add(new double[]{dx, dy, dz, startR()});
            emitT.add(now);
        }

        void tick(double dt) {
            now += dt;
            if (mode != OFF) {
                emitAcc += rate * dt;
                while (emitAcc >= 1) { emitAcc -= 1; emit(); }
            }
            for (int i = ph.size() - 1; i >= 0; i--) {
                double[] p = ph.get(i);
                double r0 = p[3], r1 = r0 + lightSpeed() * dt;
                p[3] = r1;
                if (mode == ISO) {
                    double rs = bubbleR(p[0], p[1], p[2]);
                    if (r0 < rs && r1 >= rs) {
                        crossT.add(now);
                        flashes.add(new double[]{p[0] * rs, p[1] * rs, p[2] * rs, now});
                    }
                } else if (mode == BUNDLE) {
                    double u = p[1] / p[0], w = p[2] / p[0];
                    for (int k = 1; k <= 3; k++) {
                        double rk = k * UPA;
                        if (r0 < rk && r1 >= rk) {
                            int ti = (int) Math.floor((u / CONE + 1) / 2 * k), tj = (int) Math.floor((w / CONE + 1) / 2 * k);
                            ti = Math.max(0, Math.min(k - 1, ti)); tj = Math.max(0, Math.min(k - 1, tj));
                            tileHits.add(new double[]{now, k, ti, tj});
                        }
                    }
                    double rd = detAU * UPA, hw = CONE / detAU;
                    if (r0 < rd && r1 >= rd && Math.abs(u) <= hw && Math.abs(w) <= hw) {
                        detT.add(now);
                        flashes.add(new double[]{p[0] * rd, p[1] * rd, p[2] * rd, now});
                    }
                }
                if (r1 > (unit == R_SUN ? 10 : 16)) ph.remove(i);
            }
            double cut = now - WINDOW;
            while (!emitT.isEmpty() && emitT.peekFirst() < cut) emitT.pollFirst();
            while (!crossT.isEmpty() && crossT.peekFirst() < cut) crossT.pollFirst();
            while (!detT.isEmpty() && detT.peekFirst() < cut) detT.pollFirst();
            while (!tileHits.isEmpty() && tileHits.peekFirst()[0] < cut) tileHits.pollFirst();
            flashes.removeIf(f -> now - f[3] > 0.6);
            sweepT += dt;
            sweep2T += dt;
        }

        // ------------------------------------------------------------ drawing pieces

        void photons(R3 r) {
            double s0 = startR();
            for (double[] p : ph) {
                double r1 = p[3], r0 = Math.max(s0, r1 - 0.38);
                r.seg(v(p[0] * r0, p[1] * r0, p[2] * r0), v(p[0] * r1, p[1] * r1, p[2] * r1), new Color(255, 226, 150, 210), 1.7f);
            }
        }

        void flashes(R3 r, Color c) {
            for (double[] f : flashes) {
                double a = 1 - (now - f[3]) / 0.6;
                r.dot(v(f[0], f[1], f[2]), 2 + 5 * a, alpha(c, (int) (230 * a)), -0.05);
            }
        }

        void bubble(R3 r) {
            int nT = 18, nP = 36;
            for (int i = 0; i < nT; i++) {
                double t0 = Math.PI * i / nT, t1 = Math.PI * (i + 1) / nT;
                for (int j = 0; j < nP; j++) {
                    double p0 = 2 * Math.PI * j / nP, p1 = 2 * Math.PI * (j + 1) / nP;
                    double[][] q = {bub(t0, p0), bub(t0, p1), bub(t1, p1), bub(t1, p0)};
                    r.poly(q, new Color(90, 200, 255, 20), new Color(120, 210, 255, 50), 1f);
                }
            }
        }
        private double[] bub(double th, double phi) {
            double dx = Math.sin(th) * Math.cos(phi), dy = Math.sin(th) * Math.sin(phi), dz = Math.cos(th);
            double R = bubbleR(dx, dy, dz);
            return v(dx * R, dy * R, dz * R);
        }

        /** Point on the sphere of radius rr in the gnomonic (u = y/x, w = z/x) chart around +x. */
        static double[] gn(double rr, double u, double w) {
            double n = Math.sqrt(1 + u * u + w * w);
            return v(rr / n, rr * u / n, rr * w / n);
        }

        void bundle(R3 r) {
            double far = 3.3 * UPA;
            for (int sx = -1; sx <= 1; sx += 2)
                for (int sy = -1; sy <= 1; sy += 2)
                    r.line(v(0, 0, 0), gn(far, sx * CONE, sy * CONE), new Color(255, 220, 150, 70), 1.2f, true);
            Color[] cols = {new Color(255, 206, 110), new Color(255, 160, 90), new Color(255, 115, 90)};
            for (int k = 1; k <= 3; k++) {
                int[][] recent = new int[k][k];
                for (double[] h : tileHits) if ((int) h[1] == k && now - h[0] < 0.22) recent[(int) h[2]][(int) h[3]]++;
                double rk = k * UPA;
                for (int i = 0; i < k; i++)
                    for (int j = 0; j < k; j++) {
                        double u0 = -CONE + 2 * CONE * i / k, u1 = -CONE + 2 * CONE * (i + 1) / k;
                        double w0 = -CONE + 2 * CONE * j / k, w1 = -CONE + 2 * CONE * (j + 1) / k;
                        double[][] q = {gn(rk, u0, w0), gn(rk, u1, w0), gn(rk, u1, w1), gn(rk, u0, w1)};
                        int a = 40 + Math.min(150, recent[i][j] * 70);
                        r.poly(q, alpha(cols[k - 1], a), alpha(cols[k - 1], 220), 1.6f);
                    }
                r.label(gn(rk, 0, CONE), k + " AU  ·  " + (k * k) + (k == 1 ? " tile" : " tiles"), cols[k - 1], 12f, 0, -18);
            }
            double rd = detAU * UPA, hw = CONE / detAU;
            double[][] q = {gn(rd, -hw, -hw), gn(rd, hw, -hw), gn(rd, hw, hw), gn(rd, -hw, hw)};
            boolean hit = !detT.isEmpty() && now - detT.peekLast() < 0.15;
            r.poly(q, new Color(120, 255, 170, hit ? 200 : 90), new Color(150, 255, 190), 2.2f, -0.02);
            r.label(gn(rd, 0, -hw), "detector (1 tile)", GREEN, 12f, 0, 20);
        }

        /** Riemann sum for the sphere's area: bands x 2*bands patches, midpoint rule. */
        double patchSum(int nb) {
            double dth = Math.PI / nb, sum = 0;
            for (int i = 0; i < nb; i++) sum += Math.sin((i + 0.5) * dth);
            return sum * dth * 2 * Math.PI;      // times r^2
        }

        /** Names the Sun, just below its disc (drawn at its true size, never enlarged). */
        void sunLabel(R3 r) {
            String text = unit == R_SUN ? "Sun  ·  radius 696,000 km (true size)" : "Sun";
            r.labelBelow(v(0, 0, 0), host.sunRadiusM() / unit, text, SUN_C, 12.5f);
        }

        /** Camera goal around the Sun: {cxM, cyM, radiusM, pitchDeg, yawDeg}. */
        static double[] around(double cxAU, double radiusAU, double pitch, double yaw) {
            return new double[]{cxAU * AU, 0, radiusAU * AU, pitch, yaw};
        }
        /** Camera goal in solar radii (intensity steps). */
        static double[] nearSun(double cxR, double radiusR, double pitch, double yaw) {
            return new double[]{cxR * R_SUN, 0, radiusR * R_SUN, pitch, yaw};
        }

        LessonDef build() {
            List<Step> s = new ArrayList<>();

            s.add(new Step("Luminosity: a star's total power", """
                Every planet is hidden: only the real Sun is left, shown at its true size. A star pours
                out energy in every direction. Its {k:luminosity L} is the **total power** it emits: energy per second, in
                watts, added up over its whole surface and every direction.

                $ L⊙ = 3.83 × 10²⁶ W

                Luminosity belongs to the star alone. It doesn't matter where you stand or how far away
                you are.

                > Each streak is a packet of light energy, moving at the real speed of light: it takes about
                2.3 seconds to cross one solar radius. The Sun sends them out evenly in all directions.
                """)
                .unit(R_SUN)
                .enter(() -> { sunOnly(R_SUN); setMode(ISO, 60); })
                .cam(() -> nearSun(0, 6.5, 58, 25))
                .scene((r, hud, t) -> {
                    photons(r);
                    hud.title("Luminosity", SUN_C);
                    hud.row("L of the Sun", sci(L_SUN, 2) + " W", SUN_C);
                    hud.row("packets emitted (demo)", String.format("%.0f per second", emitT.size() / WINDOW));
                    hud.row("direction", "all directions equally");
                    hud.formula("L = total power out of the star");
                }));

            s.add(new Step("Wrap it in a Gaussian bubble", """
                Surround the Sun with an imaginary closed surface, a {k:Gaussian bubble} (the same trick as
                Gauss's law in electricity). Every packet that leaves the Sun must pass through the bubble
                exactly once. Each crossing flashes.

                Empty space doesn't create or absorb light, so energy is conserved: **the power crossing
                the bubble equals L**, however big the bubble is, and whatever its shape.

                $ ∮ F dA = L

                Resize the bubble and compare the crossing rate with the emission rate. Then make it lumpy.

                > The bubble is drawn a few solar radii across so you can see it around the Sun; the
                result is the same at 1 AU or 100 AU. After a resize, give the light a moment to reach the
                new surface.
                """)
                .unit(R_SUN)
                .enter(() -> { sunOnly(R_SUN); setMode(ISO, 60); bubbleRs = 3; lumpy = false; })
                .cam(() -> nearSun(0, bubbleRs * 1.35 + 1, 58, 25))
                .controls(c -> {
                    c.slider("Bubble size", 1.5, 7, () -> bubbleRs, x -> bubbleRs = x, x -> String.format("%.1f solar radii", x));
                    c.toggle("Lumpy bubble (any closed shape)", () -> lumpy, b -> lumpy = b);
                })
                .scene((r, hud, t) -> {
                    photons(r);
                    bubble(r);
                    flashes(r, new Color(140, 230, 255));
                    double em = emitT.size() / WINDOW, cr = crossT.size() / WINDOW;
                    hud.title("Gaussian bubble", TEAL);
                    hud.row("packets emitted", String.format("%.0f / s", em));
                    hud.row("packets crossing bubble", String.format("%.0f / s", cr), TEAL);
                    hud.row("power through bubble", sci(L_SUN, 2) + " W = L", SUN_C);
                    if (!lumpy) {
                        double rm = bubbleRs * R_SUN;
                        hud.row("bubble area 4πr²", sci(4 * Math.PI * rm * rm, 2) + " m²");
                    } else {
                        hud.row("bubble area", "(lumpy, doesn't matter)");
                    }
                    hud.formula("∮ F dA = L");
                }));

            s.add(new Step("Flux: the same light, spread thinner", """
                The camera pulls back to planetary distances, so the Sun shrinks to a point of light.
                {g:Flux F} is the power passing through **one square metre** (W/m²). Follow the bundle of
                light that fills one tile at 1 AU, Earth's distance. At 2 AU the same bundle covers
                2 × 2 = 4 tiles; at 3 AU, 3 × 3 = 9 tiles. The same power is spread over r² times the area:

                $ F = L / 4πr²

                This is the **inverse-square law**. At Earth F = 1361 W/m², the "solar constant".

                > Slide the green detector. It is always one tile in size, so its hit rate falls as 1/r². (Out
                here light is sped up about 600 times; it really takes 8.3 minutes to reach 1 AU.)
                """)
                .unit(UNIT_M)
                .enter(() -> { sunOnly(UNIT_M); setMode(BUNDLE, 48); detAU = 1.5; })
                .cam(() -> around(1.7, 2.0, 68, 18))
                .controls(c -> c.slider("Detector distance", 0.6, 3.3, () -> detAU, x -> detAU = x, x -> String.format("%.2f AU", x)))
                .scene((r, hud, t) -> {
                    photons(r);
                    bundle(r);
                    flashes(r, GREEN);
                    int[] shell = new int[4];
                    for (double[] h : tileHits) shell[(int) h[1]]++;
                    hud.title("Flux", GREEN);
                    for (int k = 1; k <= 3; k++)
                        hud.row("hits per tile at " + k + " AU", String.format("%.1f / s", shell[k] / WINDOW / (k * k)));
                    hud.gap();
                    hud.row("detector hits", String.format("%.1f / s", detT.size() / WINDOW), GREEN);
                    hud.row("expected (48 / r²)", String.format("%.1f / s", rate / (detAU * detAU)));
                    double f = L_SUN / (4 * Math.PI * sq(detAU * AU));
                    hud.row("Sun's flux at " + String.format("%.2f AU", detAU), wm2(f), GREEN);
                    hud.formula("F = L / 4πr²");
                    hud.inset("Flux vs distance", 250, 170, (g, box) -> fluxChart(g, box, detAU));
                }));

            s.add(new Step("Adding up the bubble: an integral", """
                Why 4πr²? Chop the bubble into patches and add up their areas. A patch between polar
                angles θ and θ + Δθ, and between φ and φ + Δφ around the axis, is nearly a small rectangle:

                $ ΔA ≈ (r Δθ) × (r sinθ Δφ)

                The sinθ is there because circles of latitude shrink toward the poles: their radius is
                r sinθ. As the patches shrink, the sum becomes an integral:

                $ ∫∫ r² sinθ dθ dφ = r² · 2 · 2π = 4πr²

                > (θ runs from 0 to π, φ from 0 to 2π)

                On a sphere centred on the Sun, F is the same on every patch, so L = Σ F ΔA = F · 4πr².

                > Add more bands and watch the sum converge to 4πr².
                """)
                .unit(R_SUN)
                .enter(() -> { sunOnly(R_SUN); setMode(OFF, 0); intRs = 2.5; bands = 8; sweepT = 0; })
                .cam(() -> nearSun(0, intRs * 1.3 + 0.8, 58, 35))
                .controls(c -> {
                    c.slider("Number of bands", 2, 30, () -> bands, x -> {
                        int nb = (int) Math.round(x);
                        if (nb != bands) { bands = nb; sweepT = 0; }
                    }, x -> { int nb = (int) Math.round(x); return nb + "  (" + 2 * nb * nb + " patches)"; });
                    c.slider("Bubble radius", 1.5, 6, () -> intRs, x -> { intRs = x; }, x -> String.format("%.1f solar radii", x));
                    c.button("Replay the sum", () -> sweepT = 0);
                })
                .scene(this::integralScene));

            s.add(new Step("Intensity: brightness along a ray", """
                Now we fly in close to the real Sun and point a telescope at it. One pixel sees a fixed,
                tiny cone of sky, a fixed **solid angle** (measured in steradians, sr). {v:Intensity I}
                (specific intensity, or surface brightness) is the power per square metre **per
                steradian**: W m⁻² sr⁻¹.

                Move the detector back. The pixel's footprint on the Sun (violet) grows as s², but the
                light from each bit of that surface is diluted as 1/s². The two cancel, so **I does not
                change with distance**. What shrinks is the patch of sky the Sun fills, so the flux drops:

                $ F ≈ I · Ω   (for a small star)

                > Inset: what the detector sees. The disc shrinks, but its brightness never changes. (The
                numbers treat the disc as evenly bright; the real Sun is a little darker at its edge.)
                """)
                .unit(R_SUN)
                .enter(() -> { sunOnly(R_SUN); setMode(OFF, 0); dR = 3; })
                .cam(() -> nearSun(dR * 0.45, dR * 0.58 + 1.3, 72, 12))
                .controls(c -> c.slider("Detector distance d", 1.4, 12, () -> dR, x -> dR = x, x -> String.format("%.1f solar radii", x)))
                .scene(this::intensityScene));

            s.add(new Step("From intensity to flux: a second integral", """
                The flux on the detector collects intensity from every direction the Sun fills. Chop that
                patch of sky into small solid angles ΔΩ and add up I cosθ ΔΩ (cosθ accounts for light
                arriving at a slant):

                $ F = ∫ I cosθ dΩ = 2πI ∫ cosθ sinθ dθ = πI sin²θ*

                > (θ runs from 0 to θ*, the Sun's angular radius)

                For a sphere of radius R seen from distance d, sinθ* = R/d, so F = πI (R/d)². At the surface
                (d = R) that is F = πI = σT⁴. Multiply by the Sun's area and you get back the luminosity:

                $ L = 4πR² · σT⁴
                """)
                .unit(R_SUN)
                .enter(() -> { sunOnly(R_SUN); setMode(OFF, 0); dR = 2.2; sweep2T = 0; })
                .cam(() -> nearSun(dR * 0.55, dR * 0.5 + 1.1, 66, 30))
                .controls(c -> {
                    c.slider("Detector distance d", 1.6, 6, () -> dR, x -> { dR = x; }, x -> String.format("%.2f solar radii", x));
                    c.button("Replay the sum", () -> sweep2T = 0);
                })
                .scene(this::fluxIntegralScene));

            s.add(new Step("Putting it together", """
                {k:L}  **luminosity**: the star's total power. Units: W. The same from any distance.

                {g:F}  **flux**: power through one square metre. Units: W m⁻². Falls as 1/r².

                {v:I}  **intensity**: power through one square metre from one steradian of sky. Units:
                W m⁻² sr⁻¹. The same from any distance.

                They are linked by two integrals:

                $ L = ∮ F dA   and   F = ∫ I cosθ dΩ

                The Gaussian bubble gives the first: all the power has to get through _some_ closed
                surface. The second says a star looks equally bright at any distance; it just covers less
                of the sky.
                """)
                .unit(R_SUN)
                .enter(() -> { sunOnly(R_SUN); setMode(ISO, 60); bubbleRs = 3.5; lumpy = false; })
                .cam(() -> nearSun(0, bubbleRs * 1.35 + 1, 60, 40))
                .scene((r, hud, t) -> {
                    photons(r);
                    bubble(r);
                    double rr = bubbleRs, hw = 0.16;
                    double[][] q = {gn(rr, -hw, -hw), gn(rr, hw, -hw), gn(rr, hw, hw), gn(rr, -hw, hw)};
                    r.poly(q, new Color(126, 234, 150, 150), GREEN, 2f, -0.05);
                    r.label(gn(rr, 0, hw), "F: through this 1 m²", GREEN, 12f, 0, -16);
                    r.label(v(0, 0, rr), "L: through the whole bubble", TEAL, 12f, 0, -18);
                    r.label(v(0.7, 0, 0.7), "I: brightness of the surface", VIOLET, 12f, 0, 0);
                    hud.title("Summary", ACCENT);
                    hud.row("L", sci(L_SUN, 2) + " W", SUN_C);
                    hud.row("F at 1 AU", "1361 W/m²", GREEN);
                    hud.row("I (Sun's surface)", sci(I_SUN, 2) + " W m⁻² sr⁻¹", VIOLET);
                }));

            s.add(new Step("Check: moving Earth out", """
                Earth receives F = 1361 W/m² from the Sun at 1 AU.
                """)
                .reviews("Flux: the same light, spread thinner")
                .unit(UNIT_M)
                .enter(() -> { sunOnly(UNIT_M); setMode(BUNDLE, 48); detAU = 2; })
                .cam(() -> around(1.7, 2.0, 68, 18))
                .quiz("If Earth orbited at 2 AU instead, how much solar flux would it receive?", 2,
                      "Twice as far, the same power is spread over 2² = 4 times the area: "
                    + "1361 / 4 ≈ 340 W/m². Notice the green detector at 2 AU gets a quarter of the hits.",
                      "2722 W/m²", "680 W/m²", "340 W/m²", "1361 W/m²")
                .scene((r, hud, t) -> { photons(r); bundle(r); flashes(r, GREEN); }));

            s.add(new Step("Check: a bigger bubble", """
                This Gaussian bubble is 3 solar radii across. Now imagine doubling its radius.
                """)
                .reviews("Wrap it in a Gaussian bubble")
                .unit(R_SUN)
                .enter(() -> { sunOnly(R_SUN); setMode(ISO, 60); bubbleRs = 3; lumpy = false; })
                .cam(() -> nearSun(0, 7, 58, 25))
                .quiz("If you double the bubble's radius, how much power passes through it?", 2,
                      "The bubble's area goes up 4 times, but the flux on it goes down 4 times, so the total is "
                    + "unchanged. Every packet leaving the Sun still crosses the bubble exactly once and nothing "
                    + "absorbs it on the way, so the power through any closed surface around the Sun is "
                    + "L = 3.83 × 10²⁶ W, at any size and in any shape.",
                      "L / 4", "L / 2", "L, all of it", "4L")
                .scene((r, hud, t) -> { photons(r); bubble(r); flashes(r, new Color(140, 230, 255)); }));

            s.add(new Step("Check: the Sun from Neptune", """
                Neptune is 30 AU from the Sun.
                """)
                .unit(R_SUN)
                .enter(() -> { sunOnly(R_SUN); setMode(OFF, 0); dR = 11; })
                .cam(() -> nearSun(dR * 0.45, dR * 0.58 + 1.3, 72, 12))
                .quiz("Seen from Neptune, how does the Sun's surface brightness (intensity) compare with what we see from Earth?", 2,
                      "Intensity is conserved along a ray through empty space, so each bit of the Sun's disc looks exactly "
                    + "as bright. The disc covers 30² = 900 times less solid angle, and that is why the flux at "
                    + "Neptune is 900 times lower.",
                      "900 times dimmer", "30 times dimmer", "Exactly the same", "Brighter, because it is smaller")
                .scene(this::intensityScene));

            for (Step st : s) {
                Scene inner = st.scene;
                st.scene = (r, hud, t) -> {
                    if (inner != null) inner.draw(r, hud, t);
                    sunLabel(r);
                };
            }

            return new LessonDef("Intermediate", "Luminosity, Flux & Intensity",
                    "How starlight spreads out, and what stays the same",
                    "With every planet hidden, wrap the real Sun in a Gaussian bubble to see why its total power "
                  + "is fixed, watch flux fall with the inverse-square law, build 4πr² from a sum of patches, "
                  + "then fly in close to find out why the Sun's surface looks equally bright from any distance.",
                    new Color(150, 130, 255), s, this::tick);
        }

        // ------------------------------------------------------------ step scenes

        void integralScene(R3 r, Hud hud, double t) {
            double rr = intRs;                        // 1 unit = 1 solar radius
            int nb = bands, np = 2 * bands, K = nb * np;
            double dth = Math.PI / nb, dph = 2 * Math.PI / np;
            double sweepDur = 6.0, hold = 2.5;
            if (sweepT > sweepDur + hold) sweepT = 0;
            int added = (int) Math.min(K, Math.floor(sweepT / sweepDur * K));
            boolean sweeping = added < K;
            double sumA = 0;                          // in R☉² (midpoint Riemann sum)
            double[] O = v(0, 0, 0);
            r.line(v(0, 0, -rr - 1), v(0, 0, rr + 1), new Color(255, 255, 255, 70), 1f, true);
            int idx = 0, curI = -1, curJ = -1;
            for (int i = 0; i < nb; i++) {
                double t0 = i * dth, t1 = t0 + dth, tm = t0 + dth / 2;
                Color band = mix(new Color(80, 220, 210), new Color(170, 120, 255), (double) i / Math.max(1, nb - 1));
                for (int j = 0; j < np; j++, idx++) {
                    double p0 = j * dph, p1 = p0 + dph;
                    double[][] q = {sph(O, rr, t0, p0), sph(O, rr, t0, p1), sph(O, rr, t1, p1), sph(O, rr, t1, p0)};
                    if (idx < added) {
                        sumA += sq(intRs) * Math.sin(tm) * dth * dph;
                        boolean cur = sweeping && idx == added - 1;
                        if (cur) { curI = i; curJ = j; }
                        r.poly(q, cur ? new Color(255, 236, 120, 235) : alpha(band, 150), alpha(scale(band, 1.25), 200), 1f);
                    } else {
                        r.poly(q, new Color(120, 180, 255, 10), new Color(120, 180, 255, 40), 1f);
                    }
                }
            }
            if (curI >= 0) {           // label the patch being added
                double t0 = curI * dth, tm = t0 + dth / 2, p0 = curJ * dph, pm = p0 + dph / 2;
                double[] c = sph(O, rr, tm, pm);
                r.line(O, c, new Color(255, 255, 255, 190), 1.6f);
                r.mathLabel(lerp(O, c, 0.55), "r", Color.WHITE, 16f, 0, -10);
                double[] axisPt = v(0, 0, rr * Math.cos(tm));
                r.line(axisPt, c, new Color(255, 200, 120, 200), 1.6f, true);
                r.mathLabel(lerp(axisPt, c, 0.5), "r sinθ", ORANGE, 15f, 0, -12);
                r.mathLabel(sph(O, rr * 1.04, tm, p0), "r Δθ", new Color(255, 236, 120), 15f, -26, 0);
                r.mathLabel(sph(O, rr * 1.04, t0, pm), "r sinθ Δφ", new Color(255, 236, 120), 15f, 0, -16);
            }
            double exact = 4 * Math.PI * sq(intRs), full = patchSum(nb) * sq(intRs);
            double F = L_SUN / (4 * Math.PI * sq(intRs * R_SUN));
            hud.title("Summing patches", TEAL);
            hud.row("patches added", added + " / " + K);
            hud.row("Σ ΔA so far", String.format("%.2f solar radii²", sumA), TEAL);
            hud.row("exact 4πr²", String.format("%.2f solar radii²", exact));
            hud.row("full sum / exact", String.format("%.4f", full / exact));
            hud.gap();
            hud.row("F on this bubble", wm2(F), GREEN);
            hud.row("Σ F ΔA so far", sci(F * sumA * R_SUN * R_SUN, 3) + " W", SUN_C);
            hud.row("L of the Sun", sci(L_SUN, 3) + " W", SUN_C);
            hud.formula("ΔA = (r Δθ)(r sinθ Δφ)   →   ∫∫ r² sinθ dθ dφ = 4πr²");
        }

        /** Detector box facing the Sun at distance d (solar radii) along +x. */
        void detector(R3 r, double d) {
            double hb = 0.12;
            double[][] face = {v(d, -hb, -hb), v(d, hb, -hb), v(d, hb, hb), v(d, -hb, hb)};
            r.poly(face, new Color(80, 90, 120), new Color(200, 210, 240), 1.2f);
            double[][] back = {v(d + 0.3, -hb, -hb), v(d + 0.3, hb, -hb), v(d + 0.3, hb, hb), v(d + 0.3, -hb, hb)};
            for (int k = 0; k < 4; k++)
                r.poly(new double[][]{face[k], face[(k + 1) % 4], back[(k + 1) % 4], back[k]}, new Color(60, 68, 96), new Color(150, 160, 200, 120), 1f);
            r.label(v(d + 0.15, 0, hb), "detector", INK, 12f, 0, -18);
        }

        /** Scene for the intensity steps: the real Sun (radius 1 unit = R☉), detector at distance dR along +x. */
        void intensityScene(R3 r, Hud hud, double t) {
            double R = 1, d = dR;
            double[] D = v(d, 0, 0);
            // Tangent cone: the Sun's limb as seen from the detector.
            double xc = R * R / d, rho = R * Math.sqrt(1 - sq(R / d));
            int nL = 48;
            double[][] limb = new double[nL][];
            for (int k = 0; k < nL; k++) {
                double a = 2 * Math.PI * k / nL;
                limb[k] = v(xc, rho * Math.cos(a), rho * Math.sin(a));
            }
            r.polyline(limb, true, new Color(255, 220, 150, 150), 1.4f, false);
            for (int k = 0; k < nL; k += 6) r.line(D, limb[k], new Color(255, 220, 150, 70), 1f);
            for (int k = 0; k < nL; k += 2) r.poly(new double[][]{D, limb[k], limb[(k + 2) % nL]}, new Color(255, 220, 150, 12), null, 0);

            // One pixel's beam: a cone of half-angle PIX, and its footprint on the Sun.
            int nB = 32;
            double[][] fp = new double[nB][];
            boolean all = true;
            for (int k = 0; k < nB; k++) {
                double a = 2 * Math.PI * k / nB;
                double[] dir = {-1, Math.tan(PIX) * Math.cos(a), Math.tan(PIX) * Math.sin(a)};
                double n = Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]);
                dir[0] /= n; dir[1] /= n; dir[2] /= n;
                double bq = D[0] * dir[0], cq = d * d - R * R, disc = bq * bq - cq;
                if (disc < 0) { all = false; break; }
                double tt = -bq - Math.sqrt(disc);
                fp[k] = v((D[0] + tt * dir[0]) * 1.003, tt * dir[1] * 1.003, tt * dir[2] * 1.003);
            }
            double s = d - R, gamma = 0;
            if (all) {
                r.poly(fp, new Color(190, 146, 255, 220), new Color(225, 200, 255), 1.4f, -0.05);
                for (int k = 0; k < nB; k += 4) r.line(D, fp[k], new Color(200, 160, 255, 150), 1.2f);
                gamma = Math.acos(Math.max(-1, Math.min(1, fp[0][0] / 1.003 / R)));
                r.label(fp[nB / 4], "pixel footprint", VIOLET, 12f, 0, -18);
            }
            detector(r, d);

            double sinT = R / d, thetaStar = Math.asin(sinT);
            double omegaStar = 2 * Math.PI * (1 - Math.cos(thetaStar));
            double omegaPix = 2 * Math.PI * (1 - Math.cos(PIX));
            double F = Math.PI * I_SUN * sinT * sinT;
            double fpArea = 2 * Math.PI * (1 - Math.cos(gamma));          // in R^2
            hud.title("Intensity", VIOLET);
            hud.row("distance d", String.format("%.1f R  (%.4f AU)", d, d * R_SUN / AU));
            hud.row("distance to surface s", String.format("%.2f R", s));
            hud.row("footprint area (grows as s²)", all ? String.format("%.4f R²", fpArea) : "(larger than the Sun)");
            hud.row("footprint / s² (≈ pixel solid angle)", all ? String.format("%.5f sr", fpArea / (s * s)) : "–");
            hud.row("pixel solid angle", String.format("%.5f sr", omegaPix));
            hud.gap();
            hud.row("I seen by the pixel", sci(I_SUN, 2) + " W m⁻² sr⁻¹", VIOLET);
            hud.row("Sun's solid angle Ω", String.format("%.4f sr", omegaStar));
            hud.row("flux F = πI (R/d)²", wm2(F), GREEN);
            hud.formula("I stays the same;  F = π I (R/d)² ≈ I Ω");
            final double th = thetaStar;
            hud.inset("What the detector sees", 230, 190, (g, box) -> {
                g.setColor(new Color(4, 6, 12));
                g.fill(box);
                double cx = box.getCenterX(), cy = box.getCenterY();
                double pxPerRad = (box.height * 0.46) / Math.toRadians(56);
                double rd = th * pxPerRad;
                g.setColor(new Color(255, 214, 140));
                g.fill(new Ellipse2D.Double(cx - rd, cy - rd, 2 * rd, 2 * rd));
                double pp = PIX * pxPerRad;
                g.setColor(new Color(190, 146, 255));
                g.setStroke(new BasicStroke(2f));
                g.draw(new Ellipse2D.Double(cx - pp, cy - pp, 2 * pp, 2 * pp));
                g.setFont(uiFont(Font.PLAIN, 11f));
                g.setColor(DIM);
                g.drawString(String.format("disc radius %.1f°", Math.toDegrees(th)), box.x + 6, box.y + box.height - 8);
                g.setColor(VIOLET);
                g.drawString("pixel", (int) (cx + pp + 4), (int) cy + 4);
            });
        }

        /** Flux as a sum of I cos(theta) dOmega over the Sun's disc, on a sky dome around the detector. */
        void fluxIntegralScene(R3 r, Hud hud, double t) {
            double R = 1, d = dR;
            double[] D = v(d, 0, 0);
            double thetaStar = Math.asin(R / d);
            double rho = Math.min(1.1, 0.7 * (d - R));
            int nT = 6, nP = 24;
            for (int i = 0; i < nT; i++) {
                double t0 = (Math.PI / 2) * i / nT, t1 = (Math.PI / 2) * (i + 1) / nT;
                for (int j = 0; j < nP; j++) {
                    double p0 = 2 * Math.PI * j / nP, p1 = 2 * Math.PI * (j + 1) / nP;
                    r.poly(new double[][]{dome(D, rho, t0, p0), dome(D, rho, t0, p1), dome(D, rho, t1, p1), dome(D, rho, t1, p0)},
                            new Color(120, 180, 255, 8), new Color(120, 180, 255, 45), 1f);
                }
            }
            int cb = 6, cp = 24, K = cb * cp;
            double dth = thetaStar / cb, dph = 2 * Math.PI / cp;
            double dur = 5.0, hold = 2.5;
            if (sweep2T > dur + hold) sweep2T = 0;
            int added = (int) Math.min(K, Math.floor(sweep2T / dur * K));
            double sumO = 0, sumF = 0;
            int idx = 0;
            for (int i = 0; i < cb; i++) {
                double t0 = i * dth, t1 = t0 + dth, tm = t0 + dth / 2;
                double w = Math.cos(tm);
                for (int j = 0; j < cp; j++, idx++) {
                    double p0 = j * dph, p1 = p0 + dph;
                    double[][] q = {dome(D, rho, t0, p0), dome(D, rho, t0, p1), dome(D, rho, t1, p1), dome(D, rho, t1, p0)};
                    if (idx < added) {
                        double dO = Math.sin(tm) * dth * dph;
                        sumO += dO;
                        sumF += I_SUN * w * dO;
                        boolean cur = idx == added - 1 && added < K;
                        Color c = cur ? new Color(255, 240, 140, 240) : mix(new Color(120, 80, 200, 200), new Color(236, 214, 255, 230), (w - 0.7) / 0.3);
                        r.poly(q, c, new Color(240, 230, 255, 160), 1f, -0.02);
                    } else {
                        r.poly(q, new Color(255, 214, 140, 30), new Color(255, 214, 140, 90), 1f, -0.02);
                    }
                }
            }
            double xc = R * R / d, rl = R * Math.sqrt(1 - sq(R / d));
            for (int k = 0; k < 12; k++) {
                double a = 2 * Math.PI * k / 12;
                r.line(D, v(xc, rl * Math.cos(a), rl * Math.sin(a)), new Color(255, 220, 150, 80), 1f);
            }
            r.arrow(D, v(d - rho * 1.35, 0, 0), new Color(255, 255, 255, 200), 1.8f);
            r.label(v(d - rho * 1.35, 0, 0), "normal", INK, 11f, 0, 16);
            r.label(dome(D, rho, thetaStar, Math.PI / 2), "θ* = " + String.format("%.1f°", Math.toDegrees(thetaStar)), ORANGE, 12f, 0, -16);
            r.label(dome(D, rho, Math.PI / 2, Math.PI / 2), "sky dome", DIM, 11f, 0, -14);
            detector(r, d);

            double exactO = 2 * Math.PI * (1 - Math.cos(thetaStar));
            double exactF = Math.PI * I_SUN * sq(Math.sin(thetaStar));
            hud.title("Flux from intensity", VIOLET);
            hud.row("θ* = arcsin(R/d)", String.format("%.1f°", Math.toDegrees(thetaStar)), ORANGE);
            hud.row("patches added", added + " / " + K);
            hud.row("Σ ΔΩ so far", String.format("%.4f sr", sumO));
            hud.row("Ω exact", String.format("%.4f sr", exactO));
            hud.gap();
            hud.row("Σ I cosθ ΔΩ", wm2(sumF), GREEN);
            hud.row("π I sin²θ*", wm2(exactF), GREEN);
            hud.row("I · Ω (ignoring cosθ)", wm2(I_SUN * exactO), DIM);
            hud.formula("F = ∫ I cosθ dΩ = π I sin²θ*");
        }

        /** Point on the sky dome around the detector: angle th from the -x (Sun) direction. */
        static double[] dome(double[] D, double rho, double th, double ph) {
            return v(D[0] - rho * Math.cos(th), D[1] + rho * Math.sin(th) * Math.cos(ph), D[2] + rho * Math.sin(th) * Math.sin(ph));
        }

        /** Inset: F(r) = L / 4 pi r^2 in W/m^2 for the Sun, with the detector's point. */
        static void fluxChart(Graphics2D g, Rectangle b, double rAU) {
            int x0 = b.x + 38, y0 = b.y + b.height - 22, x1 = b.x + b.width - 8, y1 = b.y + 8;
            double rMin = 0.5, rMax = 3.5, fMax = 5500;
            g.setColor(new Color(255, 255, 255, 40));
            g.drawLine(x0, y0, x1, y0);
            g.drawLine(x0, y0, x0, y1);
            g.setFont(uiFont(Font.PLAIN, 10f));
            g.setColor(DIM);
            for (int k = 1; k <= 3; k++) {
                int x = (int) (x0 + (k - rMin) / (rMax - rMin) * (x1 - x0));
                g.drawString(k + " AU", x - 10, y0 + 14);
            }
            g.drawString("5000", b.x, (int) (y0 - 5000 / fMax * (y0 - y1)) + 4);
            g.drawString("W/m²", x0 + 6, y1 + 8);
            Path2D.Double p = new Path2D.Double();
            boolean first = true;
            for (int i = 0; i <= 120; i++) {
                double rr = rMin + (rMax - rMin) * i / 120.0;
                double f = Math.min(fMax, 1361 / (rr * rr));
                double x = x0 + (rr - rMin) / (rMax - rMin) * (x1 - x0), y = y0 - f / fMax * (y0 - y1);
                if (first) { p.moveTo(x, y); first = false; } else p.lineTo(x, y);
            }
            g.setColor(GREEN);
            g.setStroke(new BasicStroke(2f));
            g.draw(p);
            double f = Math.min(fMax, 1361 / (rAU * rAU));
            double x = x0 + (rAU - rMin) / (rMax - rMin) * (x1 - x0), y = y0 - f / fMax * (y0 - y1);
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Double(x - 4, y - 4, 8, 8));
        }
    }

    // =====================================================================
    //  Head-less rendering (-Dsss.lessonshot=dir): every step of every lesson,
    //  as it appears inside the main window ({@code root} holds the canvas + this panel)
    // =====================================================================

    /** The AI assistant's snapshot of the sim, saved beside each shot with -Dsss.lessonshot.ai=true. */
    Supplier<String> aiSnapshot;

    void renderShots(String dir, Container root, DoubleConsumer simFrame) {
        new File(dir).mkdirs();
        showPicker();
        shot(root, dir + "/00_picker.png");
        int n = 1;
        // -Dsss.lessonshot.only=3,5 renders just those lessons (1-based).
        String only = System.getProperty("sss.lessonshot.only", "");
        for (int li = 0; li < lessons.size(); li++) {
            if (!only.isBlank() && !java.util.Arrays.asList(only.split(",")).contains(String.valueOf(li + 1))) continue;
            startLesson(li);
            List<Step> steps = lesson.steps;
            for (int k = 0; k < steps.size(); k++) {
                goStep(k);
                for (int fr = 0; fr < 150; fr++) simFrame.accept(1 / 30.0);   // the sim's frame also ticks the lesson
                // -Dsss.lessonshot.solve=true: fill each code cell with its working version and run it first.
                if (Boolean.getBoolean("sss.lessonshot.solve") && controls != null && !controls.cells.isEmpty()) {
                    for (CodeCell c : controls.cells) {
                        c.text = c.solution;
                        c.runBlocking();
                        if (!c.passed()) System.err.println("  L" + (li + 1) + " step " + (k + 1) + " " + c.id + ": " + c.summary());
                    }
                    goStep(k);
                    for (int fr = 0; fr < 30; fr++) simFrame.accept(1 / 30.0);
                }
                Step st = steps.get(k);
                if (st.quiz != null) { st.quiz.solved = true; rebuildQuiz(st); }
                shot(root, String.format("%s/L%d_step%02d.png", dir, li + 1, k + 1));
                if (aiSnapshot != null && Boolean.getBoolean("sss.lessonshot.ai")) {
                    try {
                        java.nio.file.Files.writeString(java.nio.file.Path.of(String.format("%s/L%d_step%02d_ai.txt", dir, li + 1, k + 1)),
                                aiSnapshot.get());
                    } catch (Exception ex) { System.err.println(ex); }
                }
                n++;
                if (Boolean.getBoolean("sss.lessonshot.controls")) n += exerciseControls(dir, root, simFrame, li, k);
            }
            exitLesson();
        }
        System.out.println("  rendered " + n + " lesson shots");
    }

    /**
     * Head-less check of a step's controls (-Dsss.lessonshot.controls=true): click every choice,
     * button and checkbox and set every slider to its ends, letting the scene settle after each
     * change, and render each state.
     */
    private int exerciseControls(String dir, Container root, DoubleConsumer simFrame, int li, int k) {
        List<JComponent> cs = new ArrayList<>();
        collectControls(controlsBox, cs);
        int n = 0, idx = 0;
        for (JComponent c : cs) {
            List<String> names = new ArrayList<>();
            List<Runnable> acts = new ArrayList<>();
            if (c instanceof JCheckBox b) {
                names.add("on-" + b.getText()); acts.add(b::doClick);
                names.add("off-" + b.getText()); acts.add(b::doClick);
            } else if (c instanceof JToggleButton b) {
                names.add(b.getText()); acts.add(b::doClick);
            } else if (c instanceof JButton b) {
                names.add(b.getText()); acts.add(b::doClick);
            } else if (c instanceof JSlider s) {
                int v0 = s.getValue();
                String lab = sliderName(s);
                names.add(lab + "-min"); acts.add(() -> s.setValue(s.getMinimum()));
                names.add(lab + "-max"); acts.add(() -> s.setValue(s.getMaximum()));
                names.add(lab + "-back"); acts.add(() -> s.setValue(v0));
            }
            for (int a = 0; a < acts.size(); a++) {
                acts.get(a).run();
                for (int fr = 0; fr < 75; fr++) simFrame.accept(1 / 30.0);
                if (names.get(a).endsWith("-back")) continue;        // restoring, not a new state
                String nm = names.get(a).replaceAll("<[^>]*>", "").replaceAll("[^A-Za-z0-9]+", "-");
                shot(root, String.format("%s/L%d_step%02d_c%02d_%s.png", dir, li + 1, k + 1, ++idx,
                        nm.substring(0, Math.min(28, nm.length()))));
                n++;
            }
        }
        return n;
    }

    private static void collectControls(Container c, List<JComponent> out) {
        for (Component ch : c.getComponents()) {
            if (ch instanceof JSlider || ch instanceof AbstractButton) out.add((JComponent) ch);
            else if (ch instanceof Container cc) collectControls(cc, out);
        }
    }

    /** A slider's caption, from the label above it. */
    private static String sliderName(JSlider s) {
        Container p = s.getParent();
        List<JComponent> found = new ArrayList<>();
        java.util.function.Consumer<Container>[] walk = new java.util.function.Consumer[1];
        walk[0] = cc -> { for (Component ch : cc.getComponents()) { if (ch instanceof JLabel l) found.add(l); else if (ch instanceof Container x) walk[0].accept(x); } };
        if (p != null) walk[0].accept(p);
        return found.isEmpty() ? "slider" : ((JLabel) found.get(0)).getText();
    }

    private static void shot(Container root, String path) {
        Container top = root.getParent() != null ? SwingUtilities.getWindowAncestor(root) : root;
        if (top == null) top = root;
        for (int pass = 0; pass < 3; pass++) {          // repeat: wrapped text heights depend on widths
            invalidateAll(top);
            top.validate();
        }
        BufferedImage img = new BufferedImage(root.getWidth(), root.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        root.paint(g);
        g.dispose();
        try { ImageIO.write(img, "png", new File(path)); } catch (Exception ex) { System.err.println("shot failed: " + ex); }
    }

    private static void invalidateAll(Component c) {
        c.invalidate();
        if (c instanceof Container k) for (Component ch : k.getComponents()) invalidateAll(ch);
    }
}
