package view;

import model.LatexExportMode;
import model.LatexMetadata;
import model.LatexSettings;
import model.PageBreakMode;
import model.image.ImageEntry;
import model.image.ImageLibrary;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Oberfläche für alle Einstellungen des LaTeX-Dokuments ("Formalitäten").
 *
 * <p>Hier steht, was früher beim Export in einem eigenen Dialog abgefragt wurde
 * (Fachbereich, Abteilung, Titel, Art der Arbeit, Ort, Autoren, Betreuer), außerdem
 * die Dokumentvorlage und die Seiteneinstellungen. Das Panel hält keine eigene
 * Geschäftslogik: Der Zustand wird als {@link LatexSettings} gelesen
 * ({@link #getSettings()}) und gesetzt ({@link #setSettings(LatexSettings)}), z. B. beim
 * Öffnen eines Projekts. Änderungen des Benutzers werden über
 * {@link #addChangeListener(Runnable)} gemeldet, damit das Projekt als geändert markiert
 * werden kann.</p>
 *
 * <p>Felder, die im aktuellen Zustand keine Wirkung haben, sind deaktiviert: Die
 * Titelseiten-Angaben gibt es nur bei der Vorlage "Benutzerdefiniert" mit eingeschalteter
 * Titelseite. Die Autoren gelten für beide Vorlagen.</p>
 *
 * <p>Das Logo der Titelseite wird über das Label eines Bildes aus dem Bilderordner gewählt
 * (z. B. {@code fig:logo}, siehe {@link ImageLibrary}). Die Auswahlliste zeigt alle vorhandenen
 * Labels, man kann auch frei tippen. Ein leeres Feld bedeutet: kein Logo.</p>
 */
public class FormalitiesPanel extends JPanel {

    private static final int TEXT_COLUMNS = 28;

    private final JComboBox<LatexExportMode> template =
            new JComboBox<>(new LatexExportMode[]{LatexExportMode.STANDARD, LatexExportMode.EXPERT});
    private final JTextArea autorenArea = new JTextArea(3, TEXT_COLUMNS);

    private final JCheckBox coverPage = new JCheckBox("Titelseite und Inhaltsverzeichnis einfügen");
    private final JTextField titelField = new JTextField(TEXT_COLUMNS);
    private final JTextField artField = new JTextField(TEXT_COLUMNS);
    private final JTextField fachbereichField = new JTextField(TEXT_COLUMNS);
    private final JTextField abteilungField = new JTextField(TEXT_COLUMNS);
    private final JTextField ortField = new JTextField(TEXT_COLUMNS);
    private final JTextArea betreuerArea = new JTextArea(3, TEXT_COLUMNS);

    private final ImageLibrary imageLibrary;
    /**
     * Editierbare ComboBox für das Logo-Label. Beim Wechsel des Look and Feel (z. B. wenn das Theme nach dem
     * Aufbau des Fensters angewendet wird) ersetzt Swing die Editor-Komponente durch eine neue. Deshalb wird der
     * Editor in {@link #updateUI()} neu angebunden, sonst würden Änderungen nicht mehr gemeldet, nicht gespeichert
     * und nicht exportiert.
     */
    private final JComboBox<String> logoBox = new JComboBox<>() {
        @Override
        public void updateUI() {
            String text = null;
            if (getEditor() != null && getEditor().getEditorComponent() instanceof JTextComponent old) {
                text = old.getText();
            }
            super.updateUI();
            rebindLogoEditor(this, text);
        }
    };
    /** Der aktuelle Editor von {@link #logoBox}; wird von {@link #bindLogoEditor()} gesetzt. */
    private JTextComponent logoEditor;
    private final DocumentListener logoDocumentListener = new DocumentListener() {
        @Override public void insertUpdate(DocumentEvent e)  { logoChanged(); }
        @Override public void removeUpdate(DocumentEvent e)  { logoChanged(); }
        @Override public void changedUpdate(DocumentEvent e) { logoChanged(); }
    };
    private final JLabel logoHint = new JLabel(" ");
    /** Label -> Dateiname aller Bilder, für Anzeige in der Liste und für den Hinweis. */
    private final Map<String, String> logoFiles = new HashMap<>();
    /** Die Einträge, mit denen die Auswahlliste zuletzt gefüllt wurde. */
    private List<String> logoChoices = List.of();

    private final JSpinner pageWidth = new JSpinner(new SpinnerNumberModel(210, 50, 500, 1));
    private final JComboBox<String> pageUnit = new JComboBox<>(new String[]{"mm", "cm", "inch"});
    private final JComboBox<PageBreakMode> pageBreakMode = new JComboBox<>(PageBreakMode.values());
    private final JCheckBox showPageNumbers = new JCheckBox("Seitenzahlen anzeigen");

    /** Felder, die nur bei Vorlage "Benutzerdefiniert" mit Titelseite etwas bewirken. */
    private final List<JComponent> titlePageFields = new ArrayList<>();
    private final List<Runnable> changeListeners = new ArrayList<>();
    private boolean loading;

    /**
     * @param imageLibrary der Bilderordner, aus dem das Logo der Titelseite per Label gewählt wird
     */
    public FormalitiesPanel(ImageLibrary imageLibrary) {
        super(new BorderLayout(10, 10));
        this.imageLibrary = imageLibrary;
        setBorder(new EmptyBorder(16, 18, 16, 18));

        JLabel heading = new JLabel("Formalitäten");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 18f));
        add(heading, BorderLayout.NORTH);

        template.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value == LatexExportMode.STANDARD) {
                    setText("Standard – einfacher Artikel");
                } else if (value == LatexExportMode.EXPERT) {
                    setText("Benutzerdefiniert – Studienarbeit mit Titelseite");
                }
                return this;
            }
        });

        autorenArea.setToolTipText("Ein Autor pro Zeile");
        betreuerArea.setToolTipText("Ein Betreuer pro Zeile");
        titelField.setToolTipText("Titel auf der Titelseite");

        logoBox.setEditable(true);
        logoBox.setPrototypeDisplayValue("xxxxxxxxxxxxxxxxxxxxxxxxxxxx");
        logoBox.setToolTipText("Label eines Bildes aus dem Bilderordner, z. B. fig:logo. Leer = kein Logo");
        logoBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                String label = value == null ? "" : value.toString();
                if (label.isEmpty()) {
                    setText("(kein Logo)");
                } else {
                    String file = logoFiles.get(label);
                    setText(file == null ? label : label + "  –  " + file);
                }
                return this;
            }
        });
        bindLogoEditor();
        logoHint.setFont(logoHint.getFont().deriveFont(11f));

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(new EmptyBorder(12, 4, 4, 4));

        int row = 0;
        addSection(form, row++, "Dokument");
        addRow(form, row++, "Dokumentvorlage:", template, false);
        addRow(form, row++, "Autoren:", new JScrollPane(autorenArea), true);

        addSection(form, row++, "Titelseite");
        addRow(form, row++, "Titelseite:", coverPage, false);
        addRow(form, row++, "Titel der Arbeit:", titelField, false, titelField);
        addRow(form, row++, "Art der Arbeit:", artField, false, artField);
        addRow(form, row++, "Fachbereich:", fachbereichField, false, fachbereichField);
        addRow(form, row++, "Abteilung:", abteilungField, false, abteilungField);
        addRow(form, row++, "Logo (Label):", logoPanel(), false, logoBox);
        addRow(form, row++, "Ort:", ortField, false, ortField);
        addRow(form, row++, "Betreuer:", new JScrollPane(betreuerArea), true, betreuerArea);

        addSection(form, row++, "Seite");
        addRow(form, row++, "Seitenbreite:", flow(pageWidth, pageUnit), false);
        addRow(form, row++, "Seitenumbrüche:", pageBreakMode, false);
        addRow(form, row++, "Seitennummerierung:", showPageNumbers, false);

        // füllt die Restbreite, damit die Felder links ihre natürliche Breite behalten
        GridBagConstraints filler = new GridBagConstraints();
        filler.gridx = 2;
        filler.gridy = 0;
        filler.weightx = 1;
        form.add(Box.createHorizontalGlue(), filler);

        TrackingPanel body = new TrackingPanel(new BorderLayout());
        body.add(form, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(body);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);

        installListeners();
        setSettings(LatexSettings.defaults());
        reloadLogoChoices(false);

        // Bilder können hinzukommen oder ein anderes Label bekommen, während dieses Panel nicht sichtbar ist.
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                reloadLogoChoices(true);
            }
        });
    }

    // ── Öffentliche API ──────────────────────────────────────────────────────

    /**
     * Liest alle Eingaben als unveränderlichen Datensatz. Texte werden getrimmt,
     * bei Autoren und Betreuern werden leere Zeilen verworfen.
     *
     * @return die aktuellen Einstellungen
     */
    public LatexSettings getSettings() {
        LatexMetadata metadata = new LatexMetadata(
                fachbereichField.getText().trim(),
                abteilungField.getText().trim(),
                titelField.getText().trim(),
                artField.getText().trim(),
                parseLines(autorenArea.getText()),
                ortField.getText().trim(),
                parseLines(betreuerArea.getText()));

        return new LatexSettings(
                (LatexExportMode) template.getSelectedItem(),
                coverPage.isSelected(),
                metadata,
                ((Number) pageWidth.getValue()).intValue(),
                (String) pageUnit.getSelectedItem(),
                (PageBreakMode) pageBreakMode.getSelectedItem(),
                showPageNumbers.isSelected(),
                logoEditor.getText());
    }

    /**
     * Füllt die Oberfläche mit den übergebenen Einstellungen (z. B. beim Öffnen eines
     * Projekts). Löst keine Änderungsmeldung aus.
     *
     * @param settings die anzuzeigenden Einstellungen; {@code null} setzt die Standardwerte
     */
    public void setSettings(LatexSettings settings) {
        LatexSettings s = settings != null ? settings : LatexSettings.defaults();
        LatexMetadata m = s.metadata();

        loading = true;
        try {
            template.setSelectedItem(s.mode());
            coverPage.setSelected(s.coverPage());
            titelField.setText(m.titel());
            artField.setText(m.artDerArbeit());
            fachbereichField.setText(m.fachbereich());
            abteilungField.setText(m.abteilung());
            ortField.setText(m.ort());
            autorenArea.setText(String.join("\n", m.autoren()));
            betreuerArea.setText(String.join("\n", m.betreuer()));
            pageWidth.setValue(s.pageWidth());
            pageUnit.setSelectedItem(s.pageUnit());
            pageBreakMode.setSelectedItem(s.pageBreakMode());
            showPageNumbers.setSelected(s.showPageNumbers());
            logoBox.setSelectedItem(s.logoLabel());
            logoEditor.setText(s.logoLabel()); // auch dann, wenn der Eintrag schon ausgewählt war
        } finally {
            loading = false;
        }
        updateEnabledState();
    }

    /**
     * Registriert einen Listener, der bei jeder Änderung durch den Benutzer aufgerufen wird
     * (nicht bei {@link #setSettings(LatexSettings)}).
     *
     * @param listener der aufzurufende Listener
     */
    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    // ── Verhalten ────────────────────────────────────────────────────────────

    private void installListeners() {
        template.addActionListener(e -> { updateEnabledState(); fireChanged(); });
        coverPage.addActionListener(e -> { updateEnabledState(); fireChanged(); });
        pageUnit.addActionListener(e -> fireChanged());
        pageBreakMode.addActionListener(e -> fireChanged());
        showPageNumbers.addActionListener(e -> fireChanged());
        pageWidth.addChangeListener(e -> fireChanged());

        DocumentListener textChange = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { fireChanged(); }
            @Override public void removeUpdate(DocumentEvent e)  { fireChanged(); }
            @Override public void changedUpdate(DocumentEvent e) { fireChanged(); }
        };
        for (JTextComponent field : new JTextComponent[]{
                titelField, artField, fachbereichField, abteilungField, ortField,
                autorenArea, betreuerArea}) {
            field.getDocument().addDocumentListener(textChange);
        }

        // Das Logo-Feld ist eine editierbare ComboBox: Änderungen kommen über den Text im Editor,
        // egal ob getippt oder aus der Liste gewählt.
        bindLogoEditor();
        logoBox.addPopupMenuListener(new PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(PopupMenuEvent e) { reloadLogoChoices(true); }
            @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent e) { }
            @Override public void popupMenuCanceled(PopupMenuEvent e) { }
        });
    }

    /** Hängt den Document-Listener an den aktuellen Editor der ComboBox (einmal pro Editor). */
    private void bindLogoEditor() {
        JTextComponent editor = (JTextComponent) logoBox.getEditor().getEditorComponent();
        if (editor == logoEditor) return;
        logoEditor = editor;
        editor.getDocument().addDocumentListener(logoDocumentListener);
    }

    /** Wird nach einem Look-and-Feel-Wechsel aufgerufen: neuen Editor anbinden und den Text übernehmen. */
    private void rebindLogoEditor(JComboBox<String> box, String text) {
        if (logoBox == null || box != logoBox || !box.isEditable()) {
            return; // noch im Konstruktor
        }
        boolean wasLoading = loading;
        loading = true; // der Text wird nur wiederhergestellt, das ist keine Änderung durch den Benutzer
        try {
            bindLogoEditor();
            if (text != null && !text.equals(logoEditor.getText())) {
                logoEditor.setText(text);
            }
        } finally {
            loading = wasLoading;
        }
        updateLogoHint();
    }

    private void logoChanged() {
        updateLogoHint();
        fireChanged();
    }

    private void fireChanged() {
        if (loading) return;
        for (Runnable listener : changeListeners) {
            listener.run();
        }
    }

    /** Aktiviert nur die Felder, die bei der gewählten Vorlage etwas bewirken. */
    private void updateEnabledState() {
        boolean custom = template.getSelectedItem() == LatexExportMode.EXPERT;
        coverPage.setEnabled(custom);
        boolean titlePage = custom && coverPage.isSelected();
        for (JComponent field : titlePageFields) {
            field.setEnabled(titlePage);
        }
        updateLogoHint();
    }

    /**
     * Füllt die Auswahlliste mit den Labels aller Bilder im Bilderordner. Der Text im Feld bleibt
     * dabei unverändert und löst keine Änderungsmeldung aus.
     *
     * @param rescan {@code true}, wenn der Ordner vorher neu eingelesen werden soll
     *               (z. B. weil Dateien von außen hinzugekommen sein können)
     */
    private void reloadLogoChoices(boolean rescan) {
        if (rescan) {
            try {
                imageLibrary.refresh();
            } catch (IOException ignored) {
                // die zuletzt bekannte Liste bleibt
            }
        }

        List<String> choices = new ArrayList<>();
        choices.add("");
        logoFiles.clear();
        for (ImageEntry entry : imageLibrary.getEntries()) {
            if (!entry.getLabel().isBlank()) {
                choices.add(entry.getLabel());
                logoFiles.put(entry.getLabel(), entry.getFileName());
            }
        }

        if (!choices.equals(logoChoices)) {
            logoChoices = choices;
            String keep = logoEditor.getText();
            boolean wasLoading = loading;
            loading = true;
            try {
                logoBox.setModel(new DefaultComboBoxModel<>(choices.toArray(new String[0])));
                logoBox.setSelectedItem(keep);
                logoEditor.setText(keep);
            } finally {
                loading = wasLoading;
            }
        }
        updateLogoHint();
    }

    /** Zeigt unter dem Logo-Feld an, welche Datei gemeint ist, oder warnt, wenn das Label zu keinem Bild passt. */
    private void updateLogoHint() {
        String label = LatexSettings.normalizeLogoLabel(logoEditor.getText());
        if (label.isEmpty() || !logoBox.isEnabled()) {
            logoHint.setText(" ");
            return;
        }
        String file = logoFiles.get(label);
        if (file != null) {
            logoHint.setText("Datei: " + file);
            Color gray = UIManager.getColor("Label.disabledForeground");
            logoHint.setForeground(gray != null ? gray : Color.GRAY);
        } else {
            logoHint.setText("Kein Bild mit diesem Label gefunden");
            logoHint.setForeground(new Color(0xD3, 0x2F, 0x2F));
        }
    }

    private static List<String> parseLines(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        return lines;
    }

    // ── Aufbau ───────────────────────────────────────────────────────────────

    private void addSection(JPanel form, int row, String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 13f));

        JPanel header = new JPanel(new BorderLayout(0, 4));
        header.setOpaque(false);
        header.add(label, BorderLayout.NORTH);
        header.add(new JSeparator(), BorderLayout.SOUTH);

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 3;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(row == 0 ? 0 : 20, 6, 6, 12);
        form.add(header, c);
    }

    /**
     * Fügt eine Zeile mit Beschriftung und Feld hinzu.
     *
     * @param topAligned    {@code true} für mehrzeilige Felder, deren Beschriftung oben stehen soll
     * @param titlePageItems Komponenten, die zur Gruppe der Titelseiten-Felder gehören (zum Aktivieren/Deaktivieren)
     */
    private void addRow(JPanel form, int row, String label, JComponent field, boolean topAligned,
                        JComponent... titlePageItems) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.anchor = topAligned ? GridBagConstraints.NORTHWEST : GridBagConstraints.WEST;
        c.insets = new Insets(5, 6, 5, 12);

        c.gridx = 0;
        form.add(new JLabel(label), c);

        c.gridx = 1;
        form.add(field, c);

        for (JComponent item : titlePageItems) {
            titlePageFields.add(item);
        }
    }

    /** Logo-Auswahl mit dem Hinweis darunter. */
    private JPanel logoPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 3));
        panel.setOpaque(false);
        panel.add(logoBox, BorderLayout.NORTH);
        panel.add(logoHint, BorderLayout.SOUTH);
        return panel;
    }

    private static JPanel flow(JComponent... components) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        panel.setOpaque(false);
        for (JComponent component : components) {
            panel.add(component);
        }
        return panel;
    }

    /** Panel, das im ScrollPane immer die Breite des sichtbaren Bereichs übernimmt. */
    private static final class TrackingPanel extends JPanel implements Scrollable {

        TrackingPanel(LayoutManager layout) {
            super(layout);
        }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }

        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return 16;
        }

        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return (orientation == SwingConstants.VERTICAL ? visible.height : visible.width) * 9 / 10;
        }

        @Override public boolean getScrollableTracksViewportWidth()  { return true; }

        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
}