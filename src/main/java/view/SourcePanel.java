/*
KI generiert
 */

package view;

import model.source.BibtexWriter;
import model.source.SourceEntry;
import model.source.SourceField;
import model.source.SourceType;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Arbeitsbereich "Quellen": Eingabemaske für das Literaturverzeichnis.
 *
 * <p>Links steht die Liste aller Quellen, rechts die Maske der gewählten Quelle. Die Art (Buch,
 * Internetseite, Zeitschriftenartikel ...) bestimmt, welche Felder erscheinen und welche Pflicht
 * sind. Darunter zeigt eine Vorschau die daraus erzeugte {@code .bib}-Datei
 * (siehe {@link BibtexWriter}).</p>
 *
 * <p>Der Zitierschlüssel wird aus Autor/Jahr bzw. Titel vorgeschlagen und mitgeführt, solange man ihn
 * nicht selbst ändert. Alle Farben kommen vom Look and Feel.</p>
 */
public class SourcePanel extends JPanel {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final String CARD_FORM = "form";
    private static final String CARD_EMPTY = "empty";

    private final DefaultListModel<SourceEntry> listModel = new DefaultListModel<>();
    private final JList<SourceEntry> list = new JList<>(listModel);
    private final JButton addButton = new JButton("+ Neue Quelle");
    private final JButton duplicateButton = new JButton("Duplizieren");
    private final JButton deleteButton = new JButton("Löschen");

    private final JComboBox<SourceType> typeBox = new JComboBox<>(SourceType.values());
    private final JTextField keyField = new JTextField(24);
    private final JButton newKeyButton = new JButton("Neu erzeugen");
    private final JLabel keyHint = new JLabel(" ");
    private final JCheckBox corporate = new JCheckBox("Autor ist eine Organisation (z. B. Oracle Corporation)");
    private final JLabel problems = new JLabel(" ");

    private final Map<SourceField, JTextComponent> inputs = new EnumMap<>(SourceField.class);
    private final Map<SourceField, JLabel> labels = new EnumMap<>(SourceField.class);
    private final Map<SourceField, JComponent> rowComponents = new EnumMap<>(SourceField.class);
    private final JLabel corporateSpacer = new JLabel(" ");
    private final JPanel corporateRow = new JPanel(new BorderLayout());

    private final JTextArea preview = new JTextArea();
    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);

    private final List<Runnable> changeListeners = new ArrayList<>();
    private SourceEntry current;
    private boolean loading;

    public SourcePanel() {
        super(new BorderLayout(10, 10));
        setBorder(new EmptyBorder(16, 18, 16, 18));

        JLabel heading = new JLabel("Quellen");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 18f));
        add(heading, BorderLayout.NORTH);

        JSplitPane rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, buildFormArea(), buildPreview());
        rightSplit.setResizeWeight(0.7);
        rightSplit.setBorder(null);

        JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildListArea(), rightSplit);
        main.setResizeWeight(0.0);
        main.setDividerLocation(250);
        main.setBorder(null);
        add(main, BorderLayout.CENTER);

        installListeners();
        updateButtons();
        updatePreview();
        cards.show(cardPanel, CARD_EMPTY);
    }

    // ── Öffentliche API ──────────────────────────────────────────────────────

    /** @return Kopien aller Quellen in Listenreihenfolge */
    public List<SourceEntry> getEntries() {
        List<SourceEntry> result = new ArrayList<>();
        for (int i = 0; i < listModel.size(); i++) {
            result.add(listModel.get(i).copy());
        }
        return result;
    }

    /** Ersetzt alle Quellen (z. B. beim Öffnen eines Projekts). Löst keine Änderungsmeldung aus. */
    public void setEntries(List<SourceEntry> entries) {
        loading = true;
        try {
            list.clearSelection();
            listModel.clear();
            if (entries != null) {
                for (SourceEntry e : entries) {
                    listModel.addElement(e.copy());
                }
            }
        } finally {
            loading = false;
        }
        if (listModel.isEmpty()) {
            showEntry(null);
        } else {
            list.setSelectedIndex(0);
        }
        updateButtons();
        updatePreview();
        list.repaint();
    }

    /** Der Inhalt der {@code .bib}-Datei zum aktuellen Stand. */
    public String getBibtex() {
        return BibtexWriter.write(entriesView());
    }

    /** Alle vergebenen Zitierschlüssel, z. B. für Eingabehilfen im Text. */
    public List<String> getKeys() {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < listModel.size(); i++) {
            keys.add(listModel.get(i).getKey());
        }
        return keys;
    }

    /** Registriert einen Listener, der bei jeder Änderung durch den Benutzer läuft (nicht bei {@link #setEntries}). */
    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    // ── Aufbau ───────────────────────────────────────────────────────────────

    private JComponent buildListArea() {
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index,
                                                          boolean selected, boolean focus) {
                super.getListCellRendererComponent(l, value, index, selected, focus);
                SourceEntry e = (SourceEntry) value;
                String title = e.get(SourceField.TITLE).strip();
                String text = e.getKey().isEmpty() ? "(ohne Schlüssel)" : e.getKey();
                if (!title.isEmpty()) text += "  ·  " + title;
                if (!e.missingRequired().isEmpty()) text = "⚠ " + text;
                setText(text);
                setToolTipText(e.getType().label());
                setBorder(new EmptyBorder(4, 6, 4, 6));
                return this;
            }
        });

        JPanel secondary = new JPanel(new GridLayout(1, 0, 6, 0));
        secondary.add(duplicateButton);
        secondary.add(deleteButton);
        JPanel buttons = new JPanel(new GridLayout(0, 1, 0, 6));
        buttons.add(addButton);
        buttons.add(secondary);

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(new EmptyBorder(0, 0, 0, 8));
        panel.add(new JScrollPane(list), BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent buildFormArea() {
        JLabel empty = new JLabel("<html><div style='text-align:center'>Noch keine Quelle gewählt.<br>"
                + "Mit „+ Neue Quelle“ eine anlegen.</div></html>", SwingConstants.CENTER);
        empty.setEnabled(false);

        cardPanel.add(buildForm(), CARD_FORM);
        cardPanel.add(empty, CARD_EMPTY);
        return cardPanel;
    }

    private JComponent buildForm() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(new EmptyBorder(0, 8, 4, 8));

        int row = 0;
        addRow(form, row++, new JLabel("Art der Quelle:"), typeBox, false);

        JPanel keyRow = new JPanel(new BorderLayout(6, 0));
        keyRow.add(keyField, BorderLayout.CENTER);
        keyRow.add(newKeyButton, BorderLayout.EAST);
        newKeyButton.setToolTipText("Schlüssel neu aus Autor/Jahr bzw. Titel erzeugen");
        addRow(form, row++, new JLabel("Zitierschlüssel:"), keyRow, false);

        keyHint.setFont(keyHint.getFont().deriveFont(11f));
        keyHint.setEnabled(false);
        addRow(form, row++, new JLabel(" "), keyHint, false);

        for (SourceField f : SourceField.values()) {
            JTextComponent input;
            JComponent shown;
            if (f == SourceField.AUTHOR || f == SourceField.EDITOR) {
                JTextArea area = new JTextArea(f == SourceField.AUTHOR ? 3 : 2, 30);
                input = area;
                shown = new JScrollPane(area);
            } else {
                JTextField field = new JTextField(30);
                input = field;
                shown = field;
            }
            input.setToolTipText(tooltip(f));
            input.getDocument().addDocumentListener(new FieldListener(f, input));
            inputs.put(f, input);

            JLabel label = new JLabel(f.label() + ":");
            labels.put(f, label);

            JPanel holder = new JPanel(new BorderLayout());
            holder.setOpaque(false);
            holder.add(shown, BorderLayout.CENTER);
            rowComponents.put(f, holder);
            addRow(form, row++, label, holder, f == SourceField.AUTHOR || f == SourceField.EDITOR);

            if (f == SourceField.AUTHOR) {
                corporateRow.setOpaque(false);
                corporateRow.add(corporate, BorderLayout.CENTER);
                addRow(form, row++, corporateSpacer, corporateRow, false);
            }
        }

        problems.setForeground(new Color(0xD3, 0x2F, 0x2F));
        addRow(form, row++, new JLabel(" "), problems, false);

        // schiebt alles nach oben
        GridBagConstraints filler = new GridBagConstraints();
        filler.gridx = 0;
        filler.gridy = row;
        filler.weighty = 1;
        form.add(Box.createVerticalGlue(), filler);

        JScrollPane scroll = new JScrollPane(form);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private JComponent buildPreview() {
        preview.setEditable(false);
        preview.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        preview.setTabSize(2);

        JButton copy = new JButton("Kopieren");
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(getBibtex()), null));
        JButton save = new JButton("Als .bib speichern …");
        save.addActionListener(e -> saveBibFile());

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        bar.add(copy);
        bar.add(save);

        JLabel title = new JLabel("Vorschau der .bib-Datei");
        title.setFont(title.getFont().deriveFont(Font.BOLD));

        JPanel head = new JPanel(new BorderLayout());
        head.add(title, BorderLayout.WEST);
        head.add(bar, BorderLayout.EAST);

        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBorder(new EmptyBorder(8, 8, 0, 8));
        panel.add(head, BorderLayout.NORTH);
        panel.add(new JScrollPane(preview), BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(400, 220));
        return panel;
    }

    private static void addRow(JPanel form, int row, JComponent label, JComponent field, boolean tall) {
        GridBagConstraints lc = new GridBagConstraints();
        lc.gridx = 0;
        lc.gridy = row;
        lc.anchor = tall ? GridBagConstraints.NORTHWEST : GridBagConstraints.WEST;
        lc.insets = new Insets(4, 0, 4, 12);
        form.add(label, lc);

        GridBagConstraints fc = new GridBagConstraints();
        fc.gridx = 1;
        fc.gridy = row;
        fc.weightx = 1;
        fc.fill = GridBagConstraints.HORIZONTAL;
        fc.insets = new Insets(4, 0, 4, 0);
        form.add(field, fc);
    }

    private static String tooltip(SourceField f) {
        return switch (f) {
            case AUTHOR, EDITOR -> "Eine Person pro Zeile, am besten „Nachname, Vorname“";
            case PAGES -> "z. B. 12-15";
            case ACCESSED -> "Datum des Abrufs, z. B. 15.11.2026";
            case URL -> "Vollständiger Link, z. B. https://www.latex-project.org/";
            case THESIS_KIND -> "z. B. Bachelorarbeit, Masterarbeit oder Dissertation";
            case DOI -> "z. B. 10.1000/xyz123";
            default -> null;
        };
    }

    // ── Verhalten ────────────────────────────────────────────────────────────

    private void installListeners() {
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !loading) {
                showEntry(list.getSelectedValue());
                updateButtons();
            }
        });

        addButton.addActionListener(e -> addEntry());
        duplicateButton.addActionListener(e -> duplicateEntry());
        deleteButton.addActionListener(e -> deleteEntry());

        typeBox.addActionListener(e -> {
            if (loading || current == null) return;
            current.setType((SourceType) typeBox.getSelectedItem());
            fillAccessedDate(current);
            loadFields();
            applyAutoKey();
            afterEdit();
        });

        keyField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { keyEdited(); }
            @Override public void removeUpdate(DocumentEvent e)  { keyEdited(); }
            @Override public void changedUpdate(DocumentEvent e) { keyEdited(); }
        });

        newKeyButton.addActionListener(e -> {
            if (current == null) return;
            String suggestion = uniqueKey(current.suggestKey(), current);
            current.setKey(suggestion);
            current.setAutoKey(suggestion);
            setText(keyField, suggestion);
            afterEdit();
        });

        corporate.addActionListener(e -> {
            if (loading || current == null) return;
            current.setCorporateAuthor(corporate.isSelected());
            applyAutoKey();
            afterEdit();
        });
    }

    private void keyEdited() {
        if (loading || current == null) return;
        current.setKey(keyField.getText());
        afterEdit();
    }

    private final class FieldListener implements DocumentListener {
        private final SourceField field;
        private final JTextComponent component;

        FieldListener(SourceField field, JTextComponent component) {
            this.field = field;
            this.component = component;
        }

        private void edited() {
            if (loading || current == null) return;
            current.set(field, component.getText());
            applyAutoKey();
            afterEdit();
        }

        @Override public void insertUpdate(DocumentEvent e)  { edited(); }
        @Override public void removeUpdate(DocumentEvent e)  { edited(); }
        @Override public void changedUpdate(DocumentEvent e) { edited(); }
    }

    /** Führt den Schlüssel mit, solange er dem zuletzt automatisch erzeugten entspricht. */
    private void applyAutoKey() {
        if (current == null) return;
        if (current.getKey().isBlank() || current.getKey().equals(current.getAutoKey())) {
            String suggestion = uniqueKey(current.suggestKey(), current);
            current.setKey(suggestion);
            current.setAutoKey(suggestion);
            setText(keyField, suggestion);
        }
    }

    /** Nach jeder Änderung: Liste, Hinweise und Vorschau aktualisieren und melden. */
    private void afterEdit() {
        int index = listModel.indexOf(current);
        if (index >= 0) {
            listModel.set(index, current); // Zeile neu zeichnen
        }
        updateHints();
        updatePreview();
        fireChanged();
    }

    private void fireChanged() {
        if (loading) return;
        for (Runnable listener : changeListeners) {
            listener.run();
        }
    }

    // ── Liste bearbeiten ─────────────────────────────────────────────────────

    private void addEntry() {
        SourceType type = current != null ? current.getType() : SourceType.BOOK;
        SourceEntry entry = new SourceEntry(type);
        fillAccessedDate(entry);
        String key = uniqueKey("Quelle", entry);
        entry.setKey(key);
        entry.setAutoKey(key);
        listModel.addElement(entry);
        list.setSelectedValue(entry, true);
        updatePreview();
        fireChanged();
        SwingUtilities.invokeLater(() -> {
            JTextComponent first = inputs.get(entry.getType().hasField(SourceField.AUTHOR)
                    ? SourceField.AUTHOR : SourceField.TITLE);
            first.requestFocusInWindow();
        });
    }

    private void duplicateEntry() {
        if (current == null) return;
        SourceEntry copy = current.copy();
        String key = uniqueKey(current.getKey().isBlank() ? "Quelle" : current.getKey(), copy);
        copy.setKey(key);
        copy.setAutoKey("");
        listModel.addElement(copy);
        list.setSelectedValue(copy, true);
        updatePreview();
        fireChanged();
    }

    private void deleteEntry() {
        int index = list.getSelectedIndex();
        if (index < 0) return;
        SourceEntry e = listModel.get(index);
        String name = e.getKey().isBlank() ? "diese Quelle" : "„" + e.getKey() + "“";
        int answer = JOptionPane.showConfirmDialog(this, name + " wirklich löschen?",
                "Quelle löschen", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;

        listModel.remove(index);
        if (!listModel.isEmpty()) {
            list.setSelectedIndex(Math.min(index, listModel.size() - 1));
        } else {
            showEntry(null);
        }
        updateButtons();
        updatePreview();
        fireChanged();
    }

    // ── Anzeige ──────────────────────────────────────────────────────────────

    private void showEntry(SourceEntry entry) {
        current = entry;
        if (entry == null) {
            cards.show(cardPanel, CARD_EMPTY);
            updateButtons();
            return;
        }
        loading = true;
        try {
            typeBox.setSelectedItem(entry.getType());
            setText(keyField, entry.getKey());
            corporate.setSelected(entry.isCorporateAuthor());
            for (SourceField f : SourceField.values()) {
                setText(inputs.get(f), entry.get(f));
            }
        } finally {
            loading = false;
        }
        loadFields();
        cards.show(cardPanel, CARD_FORM);
        updateHints();
    }

    /** Blendet die Felder ein, die zur Art der Quelle gehören, und markiert Pflichtfelder mit *. */
    private void loadFields() {
        SourceType type = current.getType();
        for (SourceField f : SourceField.values()) {
            boolean visible = type.hasField(f);
            labels.get(f).setVisible(visible);
            rowComponents.get(f).setVisible(visible);
            labels.get(f).setText(f.label() + (type.isRequired(f) ? " *" : "") + ":");
        }
        boolean authorVisible = type.hasField(SourceField.AUTHOR);
        corporateSpacer.setVisible(authorVisible);
        corporateRow.setVisible(authorVisible);

        // Werte eines neu eingeblendeten Feldes (z. B. Abrufdatum) sichtbar machen
        loading = true;
        try {
            for (SourceField f : SourceField.values()) {
                setText(inputs.get(f), current.get(f));
            }
        } finally {
            loading = false;
        }
        cardPanel.revalidate();
        cardPanel.repaint();
    }

    private void updateHints() {
        if (current == null) return;

        String key = current.getKey();
        keyHint.setText(key.isEmpty() ? " " : "Im Text zitieren mit  \\cite:" + key);

        List<String> issues = new ArrayList<>();
        List<SourceField> missing = current.missingRequired();
        if (!missing.isEmpty()) {
            StringBuilder sb = new StringBuilder("Pflichtfelder fehlen: ");
            for (int i = 0; i < missing.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(missing.get(i).label());
            }
            issues.add(sb.toString());
        }
        if (key.isEmpty()) {
            issues.add("Zitierschlüssel fehlt");
        } else if (!SourceEntry.isValidKey(key)) {
            issues.add("Schlüssel nur mit Buchstaben, Ziffern und _ : . -");
        } else if (isKeyTaken(key, current)) {
            issues.add("Schlüssel ist schon vergeben");
        }
        problems.setText(issues.isEmpty() ? " " : "<html>" + String.join("<br>", issues) + "</html>");
    }

    private void updateButtons() {
        boolean has = list.getSelectedValue() != null;
        duplicateButton.setEnabled(has);
        deleteButton.setEnabled(has);
    }

    private void updatePreview() {
        preview.setText(getBibtex());
        preview.setCaretPosition(0);
    }

    // ── Hilfen ───────────────────────────────────────────────────────────────

    private List<SourceEntry> entriesView() {
        List<SourceEntry> result = new ArrayList<>();
        for (int i = 0; i < listModel.size(); i++) {
            result.add(listModel.get(i));
        }
        return result;
    }

    private void setText(JTextComponent component, String text) {
        boolean was = loading;
        loading = true;
        try {
            if (!component.getText().equals(text)) {
                component.setText(text);
            }
        } finally {
            loading = was;
        }
    }

    /** Bei Arten mit Abrufdatum wird heute vorbelegt, solange das Feld leer ist. */
    private static void fillAccessedDate(SourceEntry entry) {
        if (entry.getType().hasField(SourceField.ACCESSED) && entry.get(SourceField.ACCESSED).isBlank()) {
            entry.set(SourceField.ACCESSED, LocalDate.now().format(DATE));
        }
    }

    /** BibTeX unterscheidet Groß-/Kleinschreibung bei Schlüsseln nicht. */
    private boolean isKeyTaken(String key, SourceEntry except) {
        for (SourceEntry e : entriesView()) {
            if (e != except && e.getKey().equalsIgnoreCase(key)) return true;
        }
        return false;
    }

    private String uniqueKey(String base, SourceEntry except) {
        Set<String> taken = new HashSet<>();
        for (SourceEntry e : entriesView()) {
            if (e != except) taken.add(e.getKey().toLowerCase(Locale.ROOT));
        }
        String key = base;
        int n = 2;
        while (taken.contains(key.toLowerCase(Locale.ROOT))) {
            key = base + n++;
        }
        return key;
    }

    private void saveBibFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(".bib-Datei speichern");
        chooser.setSelectedFile(new File("literatur.bib"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File file = chooser.getSelectedFile();
        if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".bib")) {
            file = new File(file.getParentFile(), file.getName() + ".bib");
        }
        try {
            Files.writeString(file.toPath(), getBibtex(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Speichern fehlgeschlagen: " + ex.getMessage(),
                    "Fehler", JOptionPane.ERROR_MESSAGE);
        }
    }
}