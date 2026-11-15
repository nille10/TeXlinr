package view;

import model.image.ImageEntry;
import model.image.ImageLibrary;
import model.image.ImagePlacement;
import view.image.ThumbnailCache;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.HierarchyEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

/**
 * Bilderverwaltung im Stil eines Datei-Browsers.
 *
 * <p>Links zeigt ein Raster alle Bilder des Ordners {@code img/} mit Vorschaubild
 * (siehe {@link ImageLibrary}). Ein Klick wählt ein Bild aus, rechts lassen sich dann
 * die Angaben für die LaTeX-Umgebung {@code figure} bearbeiten: Platzierung, Breite,
 * Beschriftung und Label. Der fertige LaTeX-Code wird live angezeigt und lässt sich
 * kopieren.</p>
 *
 * <p>Bilder kommen per Drag &amp; Drop, über „Bilder hinzufügen" oder indem du sie im
 * Datei-Browser direkt in den Ordner kopierst; beim Zurückwechseln in das Fenster
 * und beim Anzeigen des Panels wird der Ordner automatisch neu eingelesen.
 * Änderungen an den Angaben werden kurz verzögert in {@value ImageLibrary#METADATA_FILE}
 * gespeichert.</p>
 */
public class ImageManagementPanel extends JPanel {

    private static final int THUMB_W = 128;
    private static final int THUMB_H = 96;
    private static final int CELL_W = 148;
    private static final int CELL_H = 148;
    private static final int PREVIEW_W = 300;
    private static final int PREVIEW_H = 200;
    private static final String COPY_TEXT = "LaTeX-Code kopieren";

    private final ImageLibrary library;
    private final DefaultListModel<ImageEntry> listModel = new DefaultListModel<>();
    private final ThumbnailCache gridThumbnails = new ThumbnailCache(THUMB_W, THUMB_H, 600);
    private final ThumbnailCache previewThumbnails = new ThumbnailCache(PREVIEW_W, PREVIEW_H, 24);
    private final JList<ImageEntry> imageList;
    private final JScrollPane listScroll;
    private final DetailPanel detail;
    private final Timer saveTimer;

    private String lastSignature = "";
    private Window observedWindow;

    /**
     * @param library der Bilderordner, dessen Inhalt angezeigt und verwaltet wird
     */
    public ImageManagementPanel(ImageLibrary library) {
        super(new BorderLayout(0, 10));
        this.library = library;
        setBorder(new EmptyBorder(12, 12, 12, 12));

        saveTimer = new Timer(500, e -> saveNow());
        saveTimer.setRepeats(false);

        add(buildHeader(), BorderLayout.NORTH);

        imageList = buildList();
        listScroll = new JScrollPane(imageList);
        listScroll.getVerticalScrollBar().setUnitIncrement(24);

        detail = new DetailPanel();
        JScrollPane detailScroll = new JScrollPane(detail,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        detailScroll.setBorder(null);
        detailScroll.getVerticalScrollBar().setUnitIncrement(16);
        detailScroll.setPreferredSize(new Dimension(350, 400));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, detailScroll);
        split.setResizeWeight(1.0);
        split.setContinuousLayout(true);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);

        installFileDrop();

        // Beim Anzeigen des Panels den Ordner neu einlesen.
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                refresh();
            }
        });

        refresh();
    }

    // ── Öffentliche API ──────────────────────────────────────────────────────

    /** @return die Bibliothek, die dieses Panel anzeigt */
    public ImageLibrary getLibrary() {
        return library;
    }

    /** Liest den Bilderordner neu ein (z. B. nachdem Dateien von außen hinzugekommen sind). */
    public void refresh() {
        refresh(selectedName());
    }

    /** Schreibt ausstehende Änderungen sofort in die Metadaten-Datei, z. B. beim Beenden. */
    public void flush() {
        if (saveTimer.isRunning()) {
            saveTimer.stop();
            saveNow();
        }
    }

    @Override
    public void addNotify() {
        super.addNotify();
        // Wenn das Fenster wieder aktiv wird, wurden evtl. Dateien im Datei-Browser geändert.
        Window window = SwingUtilities.getWindowAncestor(this);
        if (window != null && window != observedWindow) {
            observedWindow = window;
            window.addWindowListener(new WindowAdapter() {
                @Override
                public void windowActivated(WindowEvent e) {
                    if (isShowing()) refresh();
                }
            });
        }
    }

    // ── Aufbau ───────────────────────────────────────────────────────────────

    private JComponent buildHeader() {
        JLabel heading = new JLabel("Bilder");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 18f));

        JLabel folder = new JLabel(library.getDirectory().toString());
        folder.setFont(folder.getFont().deriveFont(11f));
        folder.setEnabled(false);
        folder.setToolTipText(library.getDirectory().toString());

        JPanel titles = new JPanel(new GridLayout(2, 1));
        titles.add(heading);
        titles.add(folder);

        JButton addButton = new JButton("Bilder hinzufügen…");
        JButton openButton = new JButton("Ordner öffnen");
        JButton refreshButton = new JButton("Aktualisieren");
        addButton.addActionListener(e -> chooseImages());
        openButton.addActionListener(e -> openFolder());
        refreshButton.addActionListener(e -> refresh());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(addButton);
        buttons.add(openButton);
        buttons.add(refreshButton);

        JPanel header = new JPanel(new BorderLayout(10, 0));
        header.add(titles, BorderLayout.CENTER);
        header.add(buttons, BorderLayout.EAST);
        return header;
    }

    private JList<ImageEntry> buildList() {
        JList<ImageEntry> list = new JList<>(listModel) {
            @Override
            public String getToolTipText(MouseEvent event) {
                int index = locationToIndex(event.getPoint());
                Rectangle bounds = index < 0 ? null : getCellBounds(index, index);
                if (bounds == null || !bounds.contains(event.getPoint())) {
                    return null;
                }
                ImageEntry entry = getModel().getElementAt(index);
                StringBuilder sb = new StringBuilder("<html><b>").append(escapeHtml(entry.getFileName())).append("</b>");
                if (!entry.getCaption().isBlank()) {
                    sb.append("<br>").append(escapeHtml(entry.getCaption()));
                }
                if (!entry.getLabel().isBlank()) {
                    sb.append("<br><i>").append(escapeHtml(entry.getLabel())).append("</i>");
                }
                return sb.append("</html>").toString();
            }

            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (getModel().getSize() == 0) {
                    paintEmptyHint(g, this);
                }
            }
        };
        ToolTipManager.sharedInstance().registerComponent(list);

        list.setLayoutOrientation(JList.HORIZONTAL_WRAP);
        list.setVisibleRowCount(-1);
        list.setFixedCellWidth(CELL_W);
        list.setFixedCellHeight(CELL_H);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new CellRenderer());
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                detail.showEntry(list.getSelectedValue());
            }
        });

        list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "removeImage");
        list.getActionMap().put("removeImage", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { detail.removeCurrent(); }
        });
        list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "renameImage");
        list.getActionMap().put("renameImage", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { detail.renameCurrent(); }
        });
        return list;
    }

    private void paintEmptyHint(Graphics g, JComponent c) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Color fg = c.getForeground();
            g2.setColor(new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 140));
            String[] lines = {
                    "Noch keine Bilder",
                    "Dateien hierher ziehen oder in den Ordner „" + library.getDirectory().getFileName() + "“ kopieren"
            };
            FontMetrics fm = g2.getFontMetrics();
            int y = c.getHeight() / 2 - fm.getHeight() / 2;
            for (String line : lines) {
                g2.drawString(line, (c.getWidth() - fm.stringWidth(line)) / 2, y);
                y += fm.getHeight() + 2;
            }
        } finally {
            g2.dispose();
        }
    }

    // ── Einlesen und Auswahl ─────────────────────────────────────────────────

    private void refresh(String selectName) {
        List<ImageEntry> fresh;
        try {
            fresh = library.refresh();
        } catch (IOException ex) {
            showError("Der Bilderordner konnte nicht gelesen werden:\n" + ex.getMessage());
            return;
        }

        String signature = signatureOf(fresh);
        if (signature.equals(lastSignature)) {
            return; // nichts geändert, Auswahl und Scrollposition bleiben
        }
        lastSignature = signature;

        listModel.clear();
        for (ImageEntry entry : fresh) {
            listModel.addElement(entry);
        }
        selectByName(selectName);
        if (imageList.getSelectedValue() == null) {
            detail.showEntry(null);
        }
    }

    private static String signatureOf(List<ImageEntry> entries) {
        StringBuilder sb = new StringBuilder();
        for (ImageEntry entry : entries) {
            sb.append(entry.getFileName()).append(':')
                    .append(entry.getLastModified()).append(':')
                    .append(entry.getSize()).append(';');
        }
        return sb.toString();
    }

    private String selectedName() {
        ImageEntry selected = imageList == null ? null : imageList.getSelectedValue();
        return selected == null ? null : selected.getFileName();
    }

    private void selectByName(String name) {
        if (name == null) return;
        for (int i = 0; i < listModel.size(); i++) {
            if (listModel.get(i).getFileName().equalsIgnoreCase(name)) {
                imageList.setSelectedIndex(i);
                imageList.ensureIndexIsVisible(i);
                return;
            }
        }
    }

    // ── Hinzufügen ───────────────────────────────────────────────────────────

    private void chooseImages() {
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "Bilder (png, jpg, pdf, gif, bmp)", "png", "jpg", "jpeg", "pdf", "gif", "bmp"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            importFiles(Arrays.asList(chooser.getSelectedFiles()));
        }
    }

    /** Kopiert Dateien (oder die Bilder eines Ordners) im Hintergrund in den Bilderordner. */
    private void importFiles(List<File> files) {
        List<Path> sources = expand(files);
        if (sources.isEmpty()) return;

        new SwingWorker<ImportResult, Void>() {
            @Override
            protected ImportResult doInBackground() {
                List<String> imported = new ArrayList<>();
                List<String> problems = new ArrayList<>();
                for (Path source : sources) {
                    try {
                        imported.add(library.importFile(source).getFileName().toString());
                    } catch (IOException ex) {
                        problems.add(source.getFileName() + ": " + ex.getMessage());
                    }
                }
                return new ImportResult(imported, problems);
            }

            @Override
            protected void done() {
                try {
                    ImportResult result = get();
                    String last = result.imported().isEmpty()
                            ? selectedName()
                            : result.imported().get(result.imported().size() - 1);
                    refresh(last);
                    if (!result.problems().isEmpty()) {
                        showError("Nicht alle Dateien konnten übernommen werden:\n\n"
                                + String.join("\n", result.problems()));
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ex) {
                    showError("Import fehlgeschlagen:\n" + ex.getCause());
                }
            }
        }.execute();
    }

    private record ImportResult(List<String> imported, List<String> problems) {}

    private static List<Path> expand(List<File> files) {
        List<Path> result = new ArrayList<>();
        for (File file : files) {
            if (file.isDirectory()) {
                File[] children = file.listFiles(File::isFile);
                if (children != null) {
                    Arrays.sort(children);
                    for (File child : children) {
                        if (ImageLibrary.canImport(child.getName())) {
                            result.add(child.toPath());
                        }
                    }
                }
            } else {
                result.add(file.toPath());
            }
        }
        return result;
    }

    private void openFolder() {
        try {
            refresh(); // legt den Ordner an, falls er noch nicht existiert
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                showError("Der Ordner kann auf diesem System nicht geöffnet werden:\n" + library.getDirectory());
                return;
            }
            Desktop.getDesktop().open(library.getDirectory().toFile());
        } catch (IOException | RuntimeException ex) {
            showError("Der Ordner konnte nicht geöffnet werden:\n" + ex.getMessage());
        }
    }

    // ── Drag & Drop ──────────────────────────────────────────────────────────

    private void installFileDrop() {
        TransferHandler handler = new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                try {
                    List<File> files = (List<File>) support.getTransferable()
                            .getTransferData(DataFlavor.javaFileListFlavor);
                    importFiles(files);
                    return true;
                } catch (UnsupportedFlavorException | IOException ex) {
                    return false;
                }
            }
        };
        setTransferHandler(handler);
        listScroll.setTransferHandler(handler);
        listScroll.getViewport().setTransferHandler(handler);
        imageList.setTransferHandler(handler);
        detail.setTransferHandler(handler);
    }

    // ── Speichern ────────────────────────────────────────────────────────────

    private void scheduleSave() {
        saveTimer.restart();
    }

    private void saveNow() {
        try {
            library.save();
        } catch (IOException ex) {
            showError("Die Angaben konnten nicht gespeichert werden:\n" + ex.getMessage());
        }
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Bilder", JOptionPane.ERROR_MESSAGE);
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static DocumentListener onTextChange(Runnable action) {
        return new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { action.run(); }
            @Override public void removeUpdate(DocumentEvent e)  { action.run(); }
            @Override public void changedUpdate(DocumentEvent e) { action.run(); }
        };
    }

    // ── Raster-Zelle ─────────────────────────────────────────────────────────

    /** Zeichnet Vorschaubild und Dateiname; die Auswahl wird als abgerundetes Feld hinterlegt. */
    private final class CellRenderer extends JPanel implements ListCellRenderer<ImageEntry> {

        private final JLabel thumbnail = new JLabel("", SwingConstants.CENTER);
        private final JLabel name = new JLabel("", SwingConstants.CENTER);
        private boolean selected;
        private Color selectionColor = Color.GRAY;

        CellRenderer() {
            super(new BorderLayout(0, 4));
            setOpaque(false);
            setBorder(new EmptyBorder(6, 6, 6, 6));
            thumbnail.setPreferredSize(new Dimension(THUMB_W, THUMB_H));
            name.setFont(name.getFont().deriveFont(11f));
            add(thumbnail, BorderLayout.CENTER);
            add(name, BorderLayout.SOUTH);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends ImageEntry> list, ImageEntry value,
                                                      int index, boolean isSelected, boolean cellHasFocus) {
            BufferedImage image = gridThumbnails.get(value, list::repaint);
            if (image != null) {
                thumbnail.setIcon(new ImageIcon(image));
                thumbnail.setText(null);
            } else {
                thumbnail.setIcon(null);
                thumbnail.setText(gridThumbnails.hasFailed(value) ? "–" : "…");
            }

            Color border = UIManager.getColor("Component.borderColor");
            thumbnail.setBorder(BorderFactory.createLineBorder(border != null ? border : Color.LIGHT_GRAY));

            name.setText(value.getFileName());
            name.setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
            selected = isSelected;
            selectionColor = list.getSelectionBackground();
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (selected) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(selectionColor);
                    g2.fillRoundRect(2, 2, getWidth() - 4, getHeight() - 4, 12, 12);
                } finally {
                    g2.dispose();
                }
            }
            super.paintComponent(g);
        }
    }

    // ── Angaben zum ausgewählten Bild ────────────────────────────────────────

    /** Vorschau, Dateiinfos und die LaTeX-Angaben des ausgewählten Bildes. */
    private final class DetailPanel extends JPanel {

        private final JLabel previewLabel = new JLabel("", SwingConstants.CENTER);
        private final JLabel nameLabel = new JLabel(" ");
        private final JLabel infoLabel = new JLabel(" ");
        private final JButton renameButton = new JButton("Umbenennen…");
        private final JButton removeButton = new JButton("Entfernen");
        private final JComboBox<ImagePlacement> placementBox = new JComboBox<>(ImagePlacement.values());
        private final JSpinner widthSpinner = new JSpinner(new SpinnerNumberModel(100, 5, 100, 5));
        private final JTextArea captionArea = new JTextArea(3, 20);
        private final JTextField labelField = new JTextField();
        private final JButton newLabelButton = new JButton("Neu");
        private final JLabel labelError = new JLabel(" ");
        private final JTextArea codeArea = new JTextArea(8, 20);
        private final JButton copyButton = new JButton(COPY_TEXT);

        private ImageEntry current;
        private boolean updating;

        DetailPanel() {
            super(new GridBagLayout());
            setBorder(new EmptyBorder(0, 12, 0, 0));

            previewLabel.setPreferredSize(new Dimension(PREVIEW_W, PREVIEW_H));
            Color border = UIManager.getColor("Component.borderColor");
            previewLabel.setBorder(BorderFactory.createLineBorder(border != null ? border : Color.LIGHT_GRAY));

            nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD));
            infoLabel.setFont(infoLabel.getFont().deriveFont(11f));
            infoLabel.setEnabled(false);
            labelError.setForeground(new Color(0xD3, 0x2F, 0x2F));
            labelError.setFont(labelError.getFont().deriveFont(11f));

            widthSpinner.setEditor(new JSpinner.NumberEditor(widthSpinner, "0' %'"));

            captionArea.setLineWrap(true);
            captionArea.setWrapStyleWord(true);
            codeArea.setEditable(false);
            codeArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

            JPanel fileButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            fileButtons.add(renameButton);
            fileButtons.add(removeButton);

            JPanel widthPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            widthPanel.add(widthSpinner);
            widthPanel.add(new JLabel("der Textbreite"));

            JPanel labelPanel = new JPanel(new BorderLayout(6, 0));
            labelPanel.add(labelField, BorderLayout.CENTER);
            labelPanel.add(newLabelButton, BorderLayout.EAST);
            newLabelButton.setToolTipText("Label neu aus dem Dateinamen erzeugen");

            int row = 0;
            place(previewLabel, row++, 0, 2, 1, 0, GridBagConstraints.NONE);
            place(nameLabel, row++, 0, 2, 1, 0, GridBagConstraints.HORIZONTAL);
            place(infoLabel, row++, 0, 2, 1, 0, GridBagConstraints.HORIZONTAL);
            place(fileButtons, row++, 0, 2, 1, 0, GridBagConstraints.HORIZONTAL);
            place(new JSeparator(), row++, 0, 2, 1, 0, GridBagConstraints.HORIZONTAL);
            place(new JLabel("Ausrichtung"), row, 0, 1, 0, 0, GridBagConstraints.NONE);
            place(placementBox, row++, 1, 1, 1, 0, GridBagConstraints.HORIZONTAL);
            place(new JLabel("Breite"), row, 0, 1, 0, 0, GridBagConstraints.NONE);
            place(widthPanel, row++, 1, 1, 1, 0, GridBagConstraints.HORIZONTAL);
            place(new JLabel("Beschriftung"), row, 0, 1, 0, 0, GridBagConstraints.NONE);
            place(new JScrollPane(captionArea), row++, 1, 1, 1, 0, GridBagConstraints.HORIZONTAL);
            place(new JLabel("Label"), row, 0, 1, 0, 0, GridBagConstraints.NONE);
            place(labelPanel, row++, 1, 1, 1, 0, GridBagConstraints.HORIZONTAL);
            place(labelError, row++, 1, 1, 1, 0, GridBagConstraints.HORIZONTAL);
            place(new JLabel("LaTeX-Code"), row++, 0, 2, 1, 0, GridBagConstraints.HORIZONTAL);
            place(new JScrollPane(codeArea), row++, 0, 2, 1, 1, GridBagConstraints.BOTH);
            place(copyButton, row, 0, 2, 1, 0, GridBagConstraints.HORIZONTAL);

            placementBox.addActionListener(e -> {
                if (updating || current == null) return;
                current.setPlacement((ImagePlacement) placementBox.getSelectedItem());
                changed();
            });
            widthSpinner.addChangeListener(e -> {
                if (updating || current == null) return;
                current.setWidthPercent((Integer) widthSpinner.getValue());
                changed();
            });
            captionArea.getDocument().addDocumentListener(onTextChange(() -> {
                if (updating || current == null) return;
                current.setCaption(captionArea.getText());
                changed();
            }));
            labelField.getDocument().addDocumentListener(onTextChange(() -> {
                if (updating || current == null) return;
                applyLabel(labelField.getText().trim());
            }));
            newLabelButton.addActionListener(e -> {
                if (current != null) labelField.setText(library.suggestLabel(current));
            });
            copyButton.addActionListener(e -> copyCode());
            renameButton.addActionListener(e -> renameCurrent());
            removeButton.addActionListener(e -> removeCurrent());

            showEntry(null);
        }

        private void place(JComponent component, int row, int column, int span,
                           double weightX, double weightY, int fill) {
            GridBagConstraints gc = new GridBagConstraints();
            gc.gridx = column;
            gc.gridy = row;
            gc.gridwidth = span;
            gc.weightx = weightX;
            gc.weighty = weightY;
            gc.fill = fill;
            gc.anchor = GridBagConstraints.NORTHWEST;
            gc.insets = new Insets(3, 0, 3, column == 0 && span == 1 ? 10 : 0);
            add(component, gc);
        }

        /** Zeigt die Angaben des Bildes an; {@code null} leert und deaktiviert das Formular. */
        void showEntry(ImageEntry entry) {
            updating = true;
            try {
                current = entry;
                setFormEnabled(entry != null);
                if (entry == null) {
                    previewLabel.setIcon(null);
                    previewLabel.setText("Bild auswählen");
                    nameLabel.setText(" ");
                    infoLabel.setText(" ");
                    captionArea.setText("");
                    labelField.setText("");
                    codeArea.setText("");
                    clearLabelError();
                    return;
                }
                nameLabel.setText(entry.getFileName());
                infoLabel.setText(describe(entry));
                placementBox.setSelectedItem(entry.getPlacement());
                widthSpinner.setValue(entry.getWidthPercent());
                captionArea.setText(entry.getCaption());
                captionArea.setCaretPosition(0);
                labelField.setText(entry.getLabel());
                clearLabelError();
                codeArea.setText(entry.toLatex());
                codeArea.setCaretPosition(0);
                refreshPreview();
            } finally {
                updating = false;
            }
        }

        private void setFormEnabled(boolean enabled) {
            renameButton.setEnabled(enabled);
            removeButton.setEnabled(enabled);
            placementBox.setEnabled(enabled);
            widthSpinner.setEnabled(enabled);
            captionArea.setEnabled(enabled);
            labelField.setEnabled(enabled);
            newLabelButton.setEnabled(enabled);
            copyButton.setEnabled(enabled);
        }

        private void refreshPreview() {
            if (current == null) return;
            BufferedImage image = previewThumbnails.get(current, this::refreshPreview);
            if (image != null) {
                previewLabel.setIcon(new ImageIcon(image));
                previewLabel.setText(null);
            } else {
                previewLabel.setIcon(null);
                previewLabel.setText(previewThumbnails.hasFailed(current)
                        ? "Keine Vorschau verfügbar" : "Lade Vorschau…");
            }
        }

        private void changed() {
            codeArea.setText(current.toLatex());
            codeArea.setCaretPosition(0);
            scheduleSave();
        }

        private void applyLabel(String text) {
            String error = null;
            if (!ImageLibrary.isValidLabel(text)) {
                error = "Nur Buchstaben, Ziffern und : _ - . erlaubt";
            } else if (!text.isEmpty() && library.isLabelTaken(text, current)) {
                error = "Dieses Label wird schon von einem anderen Bild verwendet";
            }

            if (error == null) {
                clearLabelError();
                current.setLabel(text);
                changed();
            } else {
                labelField.putClientProperty("JComponent.outline", "error");
                labelError.setText(error);
            }
        }

        private void clearLabelError() {
            labelField.putClientProperty("JComponent.outline", null);
            labelError.setText(" ");
        }

        private void copyCode() {
            if (current == null) return;
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(current.toLatex()), null);
            copyButton.setText("Kopiert!");
            Timer reset = new Timer(1200, e -> copyButton.setText(COPY_TEXT));
            reset.setRepeats(false);
            reset.start();
        }

        void renameCurrent() {
            if (current == null) return;
            String fileName = current.getFileName();
            int dot = fileName.lastIndexOf('.');
            String base = dot > 0 ? fileName.substring(0, dot) : fileName;

            String input = (String) JOptionPane.showInputDialog(ImageManagementPanel.this,
                    "Neuer Dateiname (ohne Endung):\nUmlaute und Sonderzeichen werden für LaTeX ersetzt.",
                    "Umbenennen", JOptionPane.PLAIN_MESSAGE, null, null, base);
            if (input == null || input.isBlank()) return;

            try {
                flush();
                library.rename(current, input);
                String newName = current.getFileName();
                lastSignature = "";
                refresh(newName);
            } catch (IOException ex) {
                showError("Die Datei konnte nicht umbenannt werden:\n" + ex.getMessage());
            }
        }

        void removeCurrent() {
            if (current == null) return;
            int choice = JOptionPane.showConfirmDialog(ImageManagementPanel.this,
                    "„" + current.getFileName() + "“ aus dem Bilderordner entfernen?\n"
                            + "Die Datei kommt in den Papierkorb (sofern vom System unterstützt).\n"
                            + "Verweise im Text laufen danach ins Leere.",
                    "Bild entfernen", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) return;

            try {
                flush();
                library.delete(current);
                lastSignature = "";
                refresh(null);
            } catch (IOException ex) {
                showError("Die Datei konnte nicht entfernt werden:\n" + ex.getMessage());
            }
        }

        private String describe(ImageEntry entry) {
            String type = entry.getExtension().toUpperCase(Locale.ROOT);
            String size = humanSize(entry.getSize());
            if (entry.isPdf()) {
                return "PDF · " + size;
            }
            try (ImageInputStream input = ImageIO.createImageInputStream(entry.getFile().toFile())) {
                Iterator<ImageReader> readers = input == null ? null : ImageIO.getImageReaders(input);
                if (readers != null && readers.hasNext()) {
                    ImageReader reader = readers.next();
                    try {
                        reader.setInput(input);
                        return type + " · " + reader.getWidth(0) + " × " + reader.getHeight(0) + " px · " + size;
                    } finally {
                        reader.dispose();
                    }
                }
            } catch (IOException ignored) {
                // nur die Größe anzeigen
            }
            return type + " · " + size;
        }

        private String humanSize(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format(Locale.GERMAN, "%.0f KB", bytes / 1024.0);
            return String.format(Locale.GERMAN, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
    }
}