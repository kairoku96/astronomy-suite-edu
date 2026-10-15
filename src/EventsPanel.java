import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.EnumSet;
import java.util.List;

/**
 * "Next events" panel, docked beside the 3-D view: searches the sim's ephemerides with
 * {@link EventFinder} from the current sim date and lists what's coming. Selecting an event
 * shows its details; Jump moves the clock to that moment (paused), Sky shows it from the
 * ground in the Sky-from-Earth view.
 */
final class EventsPanel extends JPanel {

    interface Host {
        double simTime();
        void jumpTo(double sim);
        /** Open the Sky-from-Earth view looking at the named object. */
        void showInSky(String target);
        /** Show a solar eclipse's path and shadow on the Earth Explorer globe. */
        void showOnGlobe(double sim);
    }

    private final Host host;
    private final DefaultListModel<EventFinder.Found> model = new DefaultListModel<>();
    private final JList<EventFinder.Found> list = new JList<>(model);
    private final JLabel detail = new JLabel(" ");
    private final JLabel status = new JLabel(" ");
    private final JComboBox<String> span = new JComboBox<>(new String[]{"Next 3 months", "Next year", "Next 2 years", "Next 5 years"});
    private final java.util.Map<EventFinder.Kind, JCheckBox> boxes = new java.util.EnumMap<>(EventFinder.Kind.class);
    private SwingWorker<List<EventFinder.Found>, Void> worker;

    EventsPanel(Host host, Runnable onClose) {
        this.host = host;
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(340, 10));
        setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, new Color(60, 70, 100)));
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBorder(BorderFactory.createEmptyBorder(10, 12, 6, 12));
        JPanel head = new JPanel(new BorderLayout());
        JLabel title = new JLabel("Sky events");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
        head.add(title, BorderLayout.WEST);
        JButton close = new JButton("✕");
        close.addActionListener(_ -> onClose.run());
        head.add(close, BorderLayout.EAST);
        top.add(head);
        JLabel note = new JLabel("<html><div style='width:290px;color:#9aa8c8'>Found by searching the sim's own "
                + "ephemerides from the current sim date. Click an event to jump there.</div></html>");
        note.setFont(note.getFont().deriveFont(11f));
        top.add(note);
        JPanel grid = new JPanel(new GridLayout(0, 2, 2, 0));
        for (EventFinder.Kind k : EventFinder.Kind.values()) {
            JCheckBox b = new JCheckBox(k.label, true);
            b.setFont(b.getFont().deriveFont(11f));
            boxes.put(k, b);
            grid.add(b);
        }
        top.add(grid);
        JPanel row = new JPanel(new BorderLayout(6, 0));
        span.setSelectedIndex(1);
        row.add(span, BorderLayout.CENTER);
        JButton search = new JButton("Search");
        search.addActionListener(_ -> search());
        row.add(search, BorderLayout.EAST);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        top.add(Box.createVerticalStrut(4));
        top.add(row);
        status.setFont(status.getFont().deriveFont(11f));
        top.add(status);
        for (Component c : top.getComponents()) if (c instanceof JComponent j) j.setAlignmentX(LEFT_ALIGNMENT);
        add(top, BorderLayout.NORTH);

        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean foc) {
                EventFinder.Found f = (EventFinder.Found) v;
                String when = MissionPlayer.utcText(f.sim()).replace(" UTC", "");
                super.getListCellRendererComponent(l, "<html><span style='color:#8fa4cc'>" + when + "</span>&nbsp; "
                        + f.title() + "</html>", i, sel, foc);
                setToolTipText(f.detail());
                return this;
            }
        });
        list.addListSelectionListener(_ -> {
            EventFinder.Found f = list.getSelectedValue();
            detail.setText(f == null ? " " : "<html><div style='width:290px'><b>" + f.title() + "</b><br>"
                    + MissionPlayer.utcText(f.sim()) + "<br><br>" + f.detail() + "</div></html>");
        });
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() >= 1 && list.getSelectedValue() != null) host.jumpTo(list.getSelectedValue().sim());
            }
        });
        JScrollPane sc = new JScrollPane(list);
        sc.setBorder(BorderFactory.createEmptyBorder());
        add(sc, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBorder(BorderFactory.createEmptyBorder(6, 12, 10, 12));
        detail.setVerticalAlignment(SwingConstants.TOP);
        bottom.add(detail, BorderLayout.CENTER);
        JPanel btn = new JPanel(new GridLayout(1, 2, 6, 0));
        JButton jump = new JButton("Jump to event");
        jump.addActionListener(_ -> { if (list.getSelectedValue() != null) host.jumpTo(list.getSelectedValue().sim()); });
        JButton sky = new JButton("See it from Earth");
        sky.addActionListener(_ -> {
            EventFinder.Found f = list.getSelectedValue();
            if (f != null) { host.jumpTo(f.sim()); host.showInSky(f.target()); }
        });
        btn.add(jump);
        btn.add(sky);
        // Solar eclipses also get their path on the ground.
        JButton globe = new JButton("Path of the shadow on the globe");
        globe.setVisible(false);
        globe.addActionListener(_ -> {
            EventFinder.Found f = list.getSelectedValue();
            if (f != null) host.showOnGlobe(f.sim());
        });
        list.addListSelectionListener(_ -> {
            EventFinder.Found f = list.getSelectedValue();
            globe.setVisible(f != null && f.title().equals("Solar eclipse"));
        });
        JPanel btns = new JPanel(new BorderLayout(0, 6));
        btns.add(btn, BorderLayout.NORTH);
        btns.add(globe, BorderLayout.SOUTH);
        bottom.add(btns, BorderLayout.SOUTH);
        add(bottom, BorderLayout.SOUTH);
    }

    /** Search from the current sim date over the chosen span, in the background. */
    void search() {
        if (worker != null) worker.cancel(true);
        EnumSet<EventFinder.Kind> kinds = EnumSet.noneOf(EventFinder.Kind.class);
        boxes.forEach((k, b) -> { if (b.isSelected()) kinds.add(k); });
        double[] days = {91, 365.25, 730.5, 1826.25};
        double from = host.simTime(), to = from + days[span.getSelectedIndex()] * 86400;
        status.setText("Searching…");
        model.clear();
        worker = new SwingWorker<>() {
            protected List<EventFinder.Found> doInBackground() { return EventFinder.search(from, to, kinds); }
            protected void done() {
                if (isCancelled()) return;
                try {
                    List<EventFinder.Found> r = get();
                    for (EventFinder.Found f : r) model.addElement(f);
                    status.setText(r.size() + " events from " + MissionPlayer.utcText(from).substring(0, 10));
                } catch (Exception e) {
                    status.setText("Search failed: " + e.getMessage());
                }
            }
        };
        worker.execute();
    }
}
