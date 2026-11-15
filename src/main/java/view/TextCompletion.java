/*
KI generiert
 */

package view;

import javax.swing.*;
import javax.swing.text.AbstractDocument;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;
import javax.swing.event.CaretEvent;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vervollständigungshilfe für die Kürzel im Notizfeld.
 *
 * <p>Während du tippst, erscheint unter dem Cursor eine Auswahl:</p>
 * <ul>
 *   <li>nach {@code \cite:} die Zitierschlüssel der Quellen (nach einem Komma weitere Quellen),</li>
 *   <li>nach {@code \fig:} (allgemein nach {@code \}Präfix{@code :}) die Labels der Abbildungen,</li>
 *   <li>nach {@code \} und ein paar Buchstaben die Kürzel selbst, z. B. {@code \c} bietet {@code cite:} an.</li>
 * </ul>
 *
 * <p>Mit Pfeil hoch/runter wählen, Tab oder Enter übernehmen, Esc schließt. Strg+Leertaste öffnet
 * die Auswahl bei Bedarf neu. Solange keine Auswahl offen ist, verhalten sich alle Tasten wie gewohnt.
 * Die Liste kommt bei jedem Öffnen frisch aus den Anbietern, neue Bilder und Quellen erscheinen also sofort.</p>
 */
public final class TextCompletion {

    /** Ein Vorschlag: der einzufügende Text und eine kurze Erläuterung. */
    public record Item(String insert, String detail) {
    }

    private enum Kind { CITE, FIGURE, SHORTCUT }

    /** Wo der Cursor gerade steht: Art, Beginn des zu ersetzenden Texts und das bisher Getippte. */
    private record Context(Kind kind, int start, String typed) {
    }

    private static final Pattern CITE = Pattern.compile("(?<!\\\\)\\\\cite:([\\p{L}\\p{N}_:.\\-,]*)$");
    private static final Pattern LABEL = Pattern.compile("(?<!\\\\)\\\\([\\p{L}\\p{N}_\\-]+:[\\p{L}\\p{N}_:\\-]*)$");
    private static final Pattern WORD = Pattern.compile("(?<!\\\\)\\\\([A-Za-z]+)$");
    private static final int MAX_ROWS = 8;

    private final JTextComponent area;
    private final JPopupMenu popup = new JPopupMenu();
    private final DefaultListModel<Item> model = new DefaultListModel<>();
    private final JList<Item> list = new JList<>(model);
    private final JScrollPane scroll = new JScrollPane(list);

    private Supplier<List<Item>> figures = List::of;
    private Supplier<List<Item>> citations = List::of;
    private Context context;
    private boolean applying;

    public TextCompletion(JTextComponent area) {
        this.area = area;

        list.setFocusable(false);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new ItemRenderer());
        scroll.setFocusable(false);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        popup.setFocusable(false);
        popup.setBorder(BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor") != null
                ? UIManager.getColor("Component.borderColor") : Color.GRAY));
        popup.add(scroll);

        install();
    }

    /**
     * Legt fest, woher die Vorschläge kommen. Die Anbieter werden bei jedem Öffnen der Auswahl aufgerufen.
     *
     * @param figures   Labels der Abbildungen (nur solche mit Doppelpunkt sind als Kürzel nutzbar)
     * @param citations Zitierschlüssel der Quellen
     */
    public void setSources(Supplier<List<Item>> figures, Supplier<List<Item>> citations) {
        this.figures = figures != null ? figures : List::of;
        this.citations = citations != null ? citations : List::of;
    }

    // ── Einbau ───────────────────────────────────────────────────────────────

    private void install() {
        area.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { scheduleUpdate(); }
            @Override public void removeUpdate(DocumentEvent e)  { scheduleUpdate(); }
            @Override public void changedUpdate(DocumentEvent e) { }
        });
        area.addCaretListener((CaretEvent e) -> {
            if (popup.isVisible()) scheduleUpdate(); // Cursor weggewandert: Auswahl schließen oder anpassen
        });
        area.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { hidePopup(); }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int index = list.locationToIndex(e.getPoint());
                if (index >= 0) {
                    list.setSelectedIndex(index);
                    accept();
                }
            }
        });

        bind(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "completion.down", () -> move(1));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "completion.up", () -> move(-1));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "completion.enter", this::accept);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "completion.tab", this::accept);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "completion.escape", this::hidePopup);

        // Strg+Leertaste: Auswahl bei Bedarf (neu) öffnen
        area.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK), "completion.open");
        area.getActionMap().put("completion.open", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { update(); }
        });
    }

    /**
     * Belegt eine Taste: Ist die Auswahl offen, läuft {@code whenVisible}, sonst die bisherige Aktion der Taste
     * (Enter = Zeilenumbruch, Tab = Einrücken, Pfeile = Cursor ...).
     */
    private void bind(KeyStroke key, String name, Runnable whenVisible) {
        InputMap inputMap = area.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap actionMap = area.getActionMap();
        Object oldName = inputMap.get(key);
        Action old = oldName == null ? null : actionMap.get(oldName);

        actionMap.put(name, new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (popup.isVisible()) {
                    whenVisible.run();
                } else if (old != null && old.isEnabled()) {
                    old.actionPerformed(e);
                }
            }
        });
        inputMap.put(key, name);
    }

    // ── Auswahl öffnen und schließen ─────────────────────────────────────────

    private void scheduleUpdate() {
        if (applying) return;
        SwingUtilities.invokeLater(this::update);
    }

    private void update() {
        if (applying) return;
        if (!area.isShowing() || !area.isFocusOwner()) {
            hidePopup();
            return;
        }
        Context ctx = findContext();
        if (ctx == null) {
            hidePopup();
            return;
        }
        List<Item> items = candidates(ctx);
        if (items.isEmpty() || (items.size() == 1 && items.get(0).insert().equalsIgnoreCase(ctx.typed())
                && ctx.kind() != Kind.SHORTCUT)) {
            hidePopup(); // nichts (mehr) zu vervollständigen
            return;
        }
        context = ctx;
        show(items);
    }

    private void show(List<Item> items) {
        model.clear();
        items.forEach(model::addElement);
        list.setSelectedIndex(0);

        FontMetrics fm = list.getFontMetrics(list.getFont());
        int rowHeight = fm.getHeight() + 8;
        int width = 240;
        for (Item item : items) {
            width = Math.max(width, fm.stringWidth(item.insert() + "     " + item.detail()) + 30);
        }
        width = Math.min(width, 620);
        int rows = Math.min(MAX_ROWS, items.size());
        list.setFixedCellHeight(rowHeight);
        scroll.setPreferredSize(new Dimension(width, rows * rowHeight + 2));
        popup.pack();

        try {
            Rectangle2D r = area.modelToView2D(context.start());
            if (r == null) return;
            popup.show(area, (int) r.getX(), (int) (r.getY() + r.getHeight()));
        } catch (BadLocationException ignored) {
            hidePopup();
        }
    }

    private void hidePopup() {
        if (popup.isVisible()) {
            popup.setVisible(false);
        }
        context = null;
    }

    private void move(int delta) {
        int size = model.size();
        if (size == 0) return;
        int next = (list.getSelectedIndex() + delta + size) % size;
        list.setSelectedIndex(next);
        list.ensureIndexIsVisible(next);
    }

    /** Übernimmt den gewählten Vorschlag: ersetzt das bisher Getippte. */
    private void accept() {
        Item item = list.getSelectedValue();
        Context ctx = context;
        if (item == null || ctx == null) {
            hidePopup();
            return;
        }
        int end = area.getCaretPosition();
        int start = ctx.start();
        if (end < start) {
            hidePopup();
            return;
        }

        applying = true;
        try {
            ((AbstractDocument) area.getDocument()).replace(start, end - start, item.insert(), null);
            area.setCaretPosition(start + item.insert().length());
        } catch (BadLocationException ignored) {
            // Text hat sich unterdessen geändert
        } finally {
            applying = false;
        }
        hidePopup();
        if (ctx.kind() == Kind.SHORTCUT) {
            SwingUtilities.invokeLater(this::update); // gleich die passende Liste (Schlüssel, Labels) öffnen
        }
    }

    // ── Kontext und Vorschläge ───────────────────────────────────────────────

    private Context findContext() {
        try {
            int caret = area.getCaretPosition();
            int line = area.getDocument().getDefaultRootElement().getElementIndex(caret);
            int lineStart = area.getDocument().getDefaultRootElement().getElement(line).getStartOffset();
            String before = area.getText(lineStart, caret - lineStart);

            Matcher cite = CITE.matcher(before);
            if (cite.find()) {
                String keys = cite.group(1);
                int comma = keys.lastIndexOf(',');
                return new Context(Kind.CITE, lineStart + cite.start(1) + comma + 1, keys.substring(comma + 1));
            }
            Matcher label = LABEL.matcher(before);
            if (label.find()) {
                return new Context(Kind.FIGURE, lineStart + label.start(1), label.group(1));
            }
            Matcher word = WORD.matcher(before);
            if (word.find()) {
                return new Context(Kind.SHORTCUT, lineStart + word.start(1), word.group(1));
            }
        } catch (BadLocationException ignored) {
            // Cursor außerhalb des Textes
        }
        return null;
    }

    private List<Item> candidates(Context ctx) {
        return switch (ctx.kind()) {
            case CITE -> filter(citations.get(), ctx.typed());
            case FIGURE -> filter(figures.get(), ctx.typed());
            case SHORTCUT -> shortcuts(ctx.typed());
        };
    }

    /** Vorschläge, die mit dem Getippten beginnen (Groß-/Kleinschreibung egal), danach solche, die es enthalten. */
    private static List<Item> filter(List<Item> all, String typed) {
        String t = typed.toLowerCase(Locale.ROOT);
        List<Item> starts = new ArrayList<>();
        List<Item> contains = new ArrayList<>();
        for (Item item : all) {
            String s = item.insert().toLowerCase(Locale.ROOT);
            if (s.startsWith(t)) starts.add(item);
            else if (!t.isEmpty() && s.contains(t)) contains.add(item);
        }
        starts.addAll(contains);
        return starts;
    }

    /** Die Kürzel selbst: {@code cite:} und die Präfixe der Abbildungs-Labels (z. B. {@code fig:}). */
    private List<Item> shortcuts(String typed) {
        Map<String, Item> all = new LinkedHashMap<>();
        if (!citations.get().isEmpty()) {
            all.put("cite:", new Item("cite:", "Quelle zitieren"));
        }
        for (Item figure : figures.get()) {
            int colon = figure.insert().indexOf(':');
            if (colon > 0) {
                String prefix = figure.insert().substring(0, colon + 1);
                all.putIfAbsent(prefix, new Item(prefix, "Abbildung einfügen"));
            }
        }
        List<Item> result = new ArrayList<>();
        for (Item item : all.values()) {
            if (item.insert().startsWith(typed)) result.add(item);
        }
        return result;
    }

    // ── Darstellung ──────────────────────────────────────────────────────────

    private static final class ItemRenderer extends JPanel implements ListCellRenderer<Item> {
        private final JLabel name = new JLabel();
        private final JLabel detail = new JLabel();

        ItemRenderer() {
            super(new BorderLayout(14, 0));
            setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
            name.setFont(name.getFont().deriveFont(Font.BOLD));
            add(name, BorderLayout.WEST);
            add(detail, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Item> list, Item item, int index,
                                                      boolean selected, boolean focus) {
            name.setText(item.insert());
            detail.setText(item.detail());
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            name.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            Color muted = UIManager.getColor("Label.disabledForeground");
            detail.setForeground(selected ? list.getSelectionForeground() : (muted != null ? muted : Color.GRAY));
            return this;
        }
    }
}