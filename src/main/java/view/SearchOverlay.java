/*
KI generiert
 */

package view;

import controller.OutlineController;
import model.ContentSearch;
import model.OutlineNode;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Schwebendes Suchfeld für den Inhalt aller Notizen, geöffnet mit zweimal Shift (Shift+Shift).
 *
 * <p>Das Feld erscheint am Cursor im Notizfeld (sonst oben rechts im Fenster). Gesucht wird nicht
 * beim Tippen, sondern erst mit Enter; danach springen Enter, Pfeil runter und der Knopf ▼ zum nächsten
 * Treffer, Shift+Enter, Pfeil hoch und ▲ zum vorherigen (am Ende geht es von vorne los). Ein Treffer wählt
 * seinen Knoten in der Gliederung aus und markiert ihn im Notizfeld; alle weiteren Treffer desselben Knotens
 * sind schwächer hervorgehoben. Esc schließt das Feld.</p>
 *
 * <p>Bei jedem Sprung wird neu gesucht, die Treffer sind also auch nach Änderungen am Text aktuell
 * (siehe {@link ContentSearch}). Das Feld liegt als leichte Komponente in der Ebene der Popups des Fensters,
 * es folgt also dem Fenster und dem Look and Feel.</p>
 */
public final class SearchOverlay extends JPanel {

    private static final int DOUBLE_SHIFT_MS = 400;

    private final JFrame frame;
    private final OutlineController outline;
    private final JTextArea editor;
    private final Runnable showTreeView;

    private final JTextField field = new JTextField(16);
    private final JButton previous = new JButton("▲");
    private final JButton next = new JButton("▼");
    private final JLabel status = new JLabel(" ");

    private final List<Object> highlightTags = new ArrayList<>();
    private Component returnFocusTo;

    /** Der Text, nach dem zuletzt gesucht wurde; ein anderer Text im Feld beginnt eine neue Suche. */
    private String lastQuery;
    private OutlineNode lastNode;
    private int lastStart;

    // Shift+Shift
    private boolean shiftDown;
    private boolean otherKeyDuringShift;
    private long lastShiftRelease;

    /**
     * @param frame        das Hauptfenster
     * @param outline      der Controller (Wurzel, Auswahl)
     * @param textWindow   der Notizbereich, in dem Treffer markiert werden
     * @param showTreeView schaltet den Arbeitsbereich auf die Gliederung um, falls gerade ein anderer sichtbar ist
     */
    public SearchOverlay(JFrame frame, OutlineController outline, TextWindow textWindow, Runnable showTreeView) {
        super(new BorderLayout(6, 0));
        this.frame = frame;
        this.outline = outline;
        this.editor = textWindow.getTextArea();
        this.showTreeView = showTreeView != null ? showTreeView : () -> { };

        buildUi();
        setVisible(false);
        frame.getRootPane().getLayeredPane().add(this, JLayeredPane.POPUP_LAYER);
    }

    // ── Aufbau ───────────────────────────────────────────────────────────────

    private void buildUi() {
        Color border = UIManager.getColor("Component.borderColor");
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(border != null ? border : Color.GRAY),
                new EmptyBorder(6, 8, 6, 8)));
        setOpaque(true);
        setBackground(UIManager.getColor("Panel.background"));

        field.setToolTipText("Wort eingeben und Enter drücken");
        previous.setToolTipText("Vorheriger Treffer (Shift+Enter)");
        next.setToolTipText("Nächster Treffer (Enter)");
        for (JButton b : new JButton[]{previous, next}) {
            b.setFocusable(false);
            b.setMargin(new Insets(2, 8, 2, 8));
        }
        status.setPreferredSize(new Dimension(80, status.getPreferredSize().height));
        status.setHorizontalAlignment(SwingConstants.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        buttons.setOpaque(false);
        buttons.add(previous);
        buttons.add(next);
        buttons.add(status);

        add(field, BorderLayout.CENTER);
        add(buttons, BorderLayout.EAST);

        field.addActionListener(e -> step(+1));
        next.addActionListener(e -> step(+1));
        previous.addActionListener(e -> step(-1));

        InputMap in = field.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap actions = field.getActionMap();
        in.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "search.previous");
        in.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "search.previous");
        in.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "search.next");
        in.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "search.close");
        actions.put("search.previous", action(() -> step(-1)));
        actions.put("search.next", action(() -> step(+1)));
        actions.put("search.close", action(this::close));
    }

    private static Action action(Runnable r) {
        return new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { r.run(); }
        };
    }

    // ── Tastenkürzel Shift+Shift ─────────────────────────────────────────────

    /** Beginnt, auf Shift+Shift zu lauschen. Einmal nach dem Aufbau des Fensters aufrufen. */
    public void install() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(this::dispatch);
    }

    private boolean dispatch(KeyEvent e) {
        Component source = e.getComponent();
        if (source == null || SwingUtilities.getWindowAncestor(source) != frame) {
            return false; // Dialoge und andere Fenster
        }

        if (e.getKeyCode() == KeyEvent.VK_ESCAPE && e.getID() == KeyEvent.KEY_PRESSED && isVisible()) {
            boolean inside = SwingUtilities.isDescendingFrom(source, this);
            close();
            return inside; // Esc im Suchfeld verbrauchen, sonst wie gewohnt weiterreichen
        }

        if (e.getKeyCode() == KeyEvent.VK_SHIFT) {
            if (e.getID() == KeyEvent.KEY_PRESSED && !shiftDown) {
                shiftDown = true;
                otherKeyDuringShift = false;
                long now = e.getWhen();
                if (lastShiftRelease != 0 && now - lastShiftRelease <= DOUBLE_SHIFT_MS) {
                    lastShiftRelease = 0;
                    SwingUtilities.invokeLater(this::open);
                }
            } else if (e.getID() == KeyEvent.KEY_RELEASED) {
                shiftDown = false;
                lastShiftRelease = otherKeyDuringShift ? 0 : e.getWhen();
            }
        } else if (e.getID() == KeyEvent.KEY_PRESSED) {
            otherKeyDuringShift = true; // z. B. Shift+A: das ist kein Doppelklick auf Shift
            lastShiftRelease = 0;
        }
        return false;
    }

    // ── Öffnen und Schließen ─────────────────────────────────────────────────

    /** Zeigt das Suchfeld am Cursor an und setzt den Fokus hinein. */
    public void open() {
        Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (owner != null && !SwingUtilities.isDescendingFrom(owner, this)) {
            returnFocusTo = owner;
        }

        String selected = editor.getSelectedText();
        if (selected != null && !selected.isBlank() && selected.indexOf('\n') < 0) {
            field.setText(selected.strip());
        }

        boolean wasVisible = isVisible();
        setVisible(true);
        setSize(getPreferredSize());
        if (!wasVisible) {
            setLocation(placement());
        }
        revalidate();
        repaint();

        field.requestFocusInWindow();
        field.selectAll();
    }

    /** Schließt das Suchfeld, entfernt die Markierungen und gibt den Fokus zurück. */
    public void close() {
        if (!isVisible()) return;
        setVisible(false);
        clearHighlights();
        lastQuery = null;
        status.setText(" ");

        Component back = returnFocusTo != null && returnFocusTo.isShowing() && returnFocusTo.isFocusable()
                ? returnFocusTo : (editor.isEnabled() ? editor : outline.getTree());
        back.requestFocusInWindow();
    }

    /** Wo das Feld erscheint: unter dem Cursor im Notizfeld, sonst oben rechts im Fenster. */
    private Point placement() {
        JLayeredPane layer = frame.getRootPane().getLayeredPane();
        Dimension size = getPreferredSize();
        Point p = new Point(layer.getWidth() - size.width - 24, 70);

        if (editor.isShowing() && editor.isFocusOwner()) {
            try {
                Rectangle2D r = editor.modelToView2D(editor.getCaretPosition());
                if (r != null) {
                    p = SwingUtilities.convertPoint(editor, (int) r.getX(), (int) (r.getY() + r.getHeight() + 4), layer);
                }
            } catch (BadLocationException ignored) {
                // Standardposition
            }
        }
        p.x = Math.max(4, Math.min(p.x, layer.getWidth() - size.width - 4));
        p.y = Math.max(4, Math.min(p.y, layer.getHeight() - size.height - 4));
        return p;
    }

    // ── Suchen und Springen ──────────────────────────────────────────────────

    /**
     * Springt zum nächsten ({@code +1}) oder vorherigen ({@code -1}) Treffer. Hat sich der Text im Feld
     * geändert, beginnt eine neue Suche ab der Cursorposition im aktuellen Knoten.
     */
    private void step(int direction) {
        String query = field.getText();
        if (query.isEmpty()) {
            clearHighlights();
            status.setText(" ");
            lastQuery = null;
            return;
        }

        ContentSearch search = new ContentSearch(outline.getRootNode(), query);
        if (search.size() == 0) {
            clearHighlights();
            lastQuery = query;
            lastNode = null;
            showStatus("Keine Treffer", true);
            return;
        }

        boolean fresh = !query.equals(lastQuery) || lastNode == null;
        int index;
        if (fresh) {
            OutlineNode node = outline.getSelectedNode();
            int offset = editor.getCaretPosition();
            index = direction > 0 ? search.indexAtOrAfter(node, offset) : search.indexBefore(node, offset);
        } else {
            index = direction > 0
                    ? search.indexAtOrAfter(lastNode, lastStart + 1)
                    : search.indexBefore(lastNode, lastStart);
        }
        lastQuery = query;

        ContentSearch.Hit hit = search.hits().get(index);
        lastNode = hit.node();
        lastStart = hit.start();
        showHit(search, index);
        showStatus((index + 1) + " / " + search.size() + (search.isTruncated() ? "+" : ""), false);
    }

    private void showHit(ContentSearch search, int index) {
        ContentSearch.Hit hit = search.hits().get(index);

        showTreeView.run();
        outline.selectNode(hit.node()); // lädt den Knoten ins Notizfeld
        clearHighlights();

        Highlighter highlighter = editor.getHighlighter();
        Color base = UIManager.getColor("TextArea.selectionBackground");
        if (base == null) base = new Color(0x38, 0x75, 0xD7);
        Highlighter.HighlightPainter strong = new DefaultHighlighter.DefaultHighlightPainter(base);
        Highlighter.HighlightPainter weak = new DefaultHighlighter.DefaultHighlightPainter(
                new Color(base.getRed(), base.getGreen(), base.getBlue(), 70));

        int length = editor.getDocument().getLength();
        try {
            for (ContentSearch.Hit h : search.hits()) {
                if (h.node() != hit.node() || h.start() + h.length() > length) continue;
                boolean current = h == hit;
                highlightTags.add(highlighter.addHighlight(h.start(), h.start() + h.length(), current ? strong : weak));
            }
            Rectangle2D r = editor.modelToView2D(Math.min(hit.start(), length));
            if (r != null) {
                Rectangle visible = r.getBounds();
                visible.grow(0, 24); // etwas Umgebung mit ins Bild holen
                editor.scrollRectToVisible(visible);
                moveAwayFrom(r.getBounds());
            }
        } catch (BadLocationException ignored) {
            // Text hat sich unterdessen geändert
        }
        field.requestFocusInWindow();
    }

    /** Verdeckt das Suchfeld den Treffer, rutscht es darunter (oder darüber, wenn unten kein Platz ist). */
    private void moveAwayFrom(Rectangle hitInEditor) {
        JLayeredPane layer = frame.getRootPane().getLayeredPane();
        Rectangle hit = SwingUtilities.convertRectangle(editor, hitInEditor, layer);
        hit.grow(4, 4);
        if (!getBounds().intersects(hit)) return;

        int below = hit.y + hit.height + 2;
        int y = below + getHeight() <= layer.getHeight() - 4 ? below : hit.y - getHeight() - 2;
        setLocation(getX(), Math.max(4, y));
    }

    private void clearHighlights() {
        Highlighter highlighter = editor.getHighlighter();
        for (Object tag : highlightTags) {
            highlighter.removeHighlight(tag);
        }
        highlightTags.clear();
    }

    private void showStatus(String text, boolean error) {
        status.setText(text);
        Color normal = UIManager.getColor("Label.foreground");
        status.setForeground(error ? new Color(0xD3, 0x2F, 0x2F) : normal);
    }
}