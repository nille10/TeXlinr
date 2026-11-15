package view;

import action.actions.BuildAction;
import action.actions.EditActions;
import action.actions.FileActions;
import action.actions.OutlineActions;
import action.actions.edit.CopyAction;
import action.actions.edit.CutAction;
import action.actions.edit.InsertAction;
import action.manager.UndoManager;
import action.actions.outline.*;
import controller.*;
import model.image.ImageLibrary;
import model.OutlineNode;
import model.source.BibtexWriter;
import model.source.SourceField;
import service.LatexCompiler;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import javax.imageio.ImageIO;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * MainWindow – Hauptfenster des LaTeX-Outliners.
 *
 * <p>Baut das komplette Fenster zusammen: Menüleiste, Toolbar, linke Navigation,
 * die umschaltbaren Arbeitsbereiche (Gliederung, Abbildungen, Formalia, Quellen), die
 * dauerhaft sichtbare PDF-Vorschau sowie die Status-Leiste. Erzeugt dabei alle
 * Controller ({@link OutlineController}, {@link FileController},
 * {@link EditController}) und verdrahtet deren Actions mit Menü, Toolbar und den
 * Tastaturkürzeln des Baums. Enthält selbst keine Geschäftslogik, sondern
 * delegiert jede Aktion an den zuständigen Controller.</p>
 *
 * <p>Der Build-Button der Toolbar ({@link BuildAction}) exportiert die Gliederung
 * als {@code main.tex} in ein temporäres Build-Verzeichnis, kompiliert sie mit der
 * lokal installierten TeX-Distribution ({@link LatexCompiler}) und aktualisiert
 * die {@link PDFPreviewPanel}. Das Verzeichnis wird beim Schließen gelöscht.</p>
 *
 * Layout:
 *   ┌──────────────────────────────────────────────────────┐
 *   │  Menüleiste (JMenuBar)                               │
 *   ├──────────────────────────────────────────────────────┤
 *   │  Toolbar                                    [Build]  │
 *   ├─────┬─────────────────────────────────┬──────────────┤
 *   │ Nav │  WorkspacePanel                 │ PDF-Vorschau │
 *   │     │  (Gliederung / Bilder / Formalia)│              │
 *   ├─────┴─────────────────────────────────┴──────────────┤
 *   │  Status-Leiste                                       │
 *   └──────────────────────────────────────────────────────┘
 */
public class MainWindow extends JFrame {

    /** Das Panel mit dem Baum (JTree) und den Zusatzspalten. */
    private final OutlinePanel outlinePanel;

    /** Der zentrale Controller für Baum-Operationen, Dirty-Status und Spaltenverwaltung. */
    private final OutlineController outlineController;

    /** Der Notizbereich für den aktuell ausgewählten Knoten. */
    private final TextWindow textWindow;

    /** Die Status-Leiste am unteren Fensterrand. */
    private final JLabel statusBar;

    /** Der Basis-Fenstertitel ohne Dirty-Markierung. */
    private static final String BASE_TITLE = "TeXlinr";

    /** Der globale Undo-Manager. */
    private final UndoManager undoManager;

    /** Die dauerhaft sichtbare PDF-Vorschau am rechten Rand. */
    private final PDFPreviewPanel preview;

    /** Temporäres Arbeitsverzeichnis für main.tex, PDF und Hilfsdateien des Builds. */
    private final Path buildDir;

    /** Der Controller für Speichern, Laden und Exportieren von Dateien. */
    private final FileController fileController;

    /** Der Bilderordner {@code img/} samt Angaben zu den Abbildungen. */
    private final ImageLibrary imageLibrary;


    /**
     * Baut das Hauptfenster komplett auf.
     *
     * <p>Erstellt in dieser Reihenfolge: {@link OutlinePanel} und
     * {@link TextWindow}, den globalen {@link UndoManager}, die drei Controller,
     * die Tastaturkürzel des Baums, die Actions, die PDF-Vorschau samt
     * {@link BuildAction}, Menüleiste und Toolbar, die Arbeitsbereiche sowie die
     * Status-Leiste. Registriert außerdem den Window-Closing-Handler, der vor dem
     * Schließen den Speicherstatus prüft, und versucht abschließend, die zuletzt
     * bearbeitete Datei zu importieren.</p>
     */
    public MainWindow() {
        super(BASE_TITLE);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(1400, 720);
        setIconImage(loadLogo());
        setMinimumSize(new Dimension(600, 400));
        setLocationRelativeTo(null);

        outlinePanel = new OutlinePanel(this);
        textWindow = new TextWindow();
        undoManager = new UndoManager();

        // Controller
        outlineController = new OutlineController(outlinePanel, textWindow, this);
        fileController = new FileController(outlineController, this);
        EditController editController = new EditController(this, undoManager, outlineController);

        // KeyBindings für den Tree registrieren
        //Wichtig, da sonst andere JavaSwing Elemente KeyBindings schlucken
        registerOutlineKeyBindings(outlinePanel.getTree(), editController, undoManager);

        // Actions
        EditActions editActions = new EditActions(editController);
        FileActions fileActions = new FileActions(fileController);
        OutlineActions outlineActions = new OutlineActions(outlineController, undoManager);

        // Dauerhaft sichtbare PDF-Vorschau
        preview = new PDFPreviewPanel();

        // Build: LaTeX exportieren -> kompilieren -> Vorschau aktualisieren
        buildDir = createBuildDir();
        BuildAction build = new BuildAction(
                this,
                fileController::exportLatexTo,
                preview,
                buildDir,
                new LatexCompiler());              // oder new LatexCompiler(LatexCompiler.Engine.XELATEX)
        build.registerShortcuts(getRootPane());

        // Menüleiste
        Menubar menubar = new Menubar(fileActions, outlineActions, editActions, this, outlinePanel, outlineController);
        setJMenuBar(menubar);

        // Toolbar (Build-Button sitzt rechts außen)
        Toolbar toolbar = new Toolbar(outlineActions, build);

        // Arbeitsbereiche
        TreeView treeView = new TreeView(outlinePanel, textWindow);
        imageLibrary = new ImageLibrary(ImageLibrary.defaultDirectory());
        ImageManagementPanel imageManagementPanel = new ImageManagementPanel(imageLibrary);
        FormalitiesPanel formalitiesPanel = new FormalitiesPanel(imageLibrary);
        // Speichern/Laden und die Dirty-Markierung der Formalitäten laufen über den OutlineController
        outlineController.setFormalitiesPanel(formalitiesPanel);
        // Quellen (Literaturverzeichnis): ebenfalls über den OutlineController gespeichert und geladen
        SourcePanel sourcePanel = new SourcePanel();
        outlineController.setSourcePanel(sourcePanel);

        // Vervollständigung im Textfeld: \fig:label und \cite:key
        textWindow.setCompletionSources(
                () -> imageLibrary.getEntries().stream()
                        .filter(entry -> entry.getLabel().contains(":"))
                        .map(entry -> new TextCompletion.Item(entry.getLabel(),
                                entry.getCaption().isBlank() ? entry.getFileName() : entry.getCaption()))
                        .toList(),
                () -> BibtexWriter.usable(outlineController.getSources()).stream()
                        .map(source -> new TextCompletion.Item(source.getKey(), describe(source.get(SourceField.TITLE),
                                source.get(SourceField.YEAR))))
                        .toList());

        WorkspacePanel workspacePanel = new WorkspacePanel(
                treeView,
                imageManagementPanel,
                formalitiesPanel,
                sourcePanel
        );

        // Linke Navigation
        NavigationSidebar navigationSidebar =
                new NavigationSidebar(workspacePanel::showView);

        // Suche im Inhalt aller Notizen: Shift+Shift
        SearchOverlay searchOverlay = new SearchOverlay(this, outlineController, textWindow, () -> {
            workspacePanel.showView(NavigationSidebar.TREE);
            navigationSidebar.selectView(NavigationSidebar.TREE);
        });
        searchOverlay.install();

        // Arbeitsbereich und PDF-Vorschau sind durch einen verschiebbaren Trenner getrennt
        workspacePanel.setMinimumSize(new Dimension(420, 0));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, workspacePanel, preview);
        split.setResizeWeight(1.0);          // beim Vergrößern des Fensters wächst der Arbeitsbereich
        split.setContinuousLayout(true);     // Vorschau passt sich beim Ziehen live an
        split.setDividerSize(6);
        split.setBorder(null);
        split.addComponentListener(new java.awt.event.ComponentAdapter() {
            private boolean initialized;
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                // Einmalig die Startbreite der Vorschau setzen (ihre bevorzugte Breite, 410 px)
                if (!initialized && split.getWidth() > 0) {
                    initialized = true;
                    split.setDividerLocation(split.getWidth() - split.getDividerSize()
                            - preview.getPreferredSize().width);
                }
            }
        });

        // Hauptlayout
        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(toolbar, BorderLayout.NORTH);
        getContentPane().add(navigationSidebar, BorderLayout.WEST);
        getContentPane().add(split, BorderLayout.CENTER);

        // Status-Leiste
        statusBar = new JLabel(" Bereit");
        statusBar.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        //statusBar.setForeground(new Color(100, 100, 120));
        statusBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(200, 200, 210)),
                BorderFactory.createEmptyBorder(2, 8, 2, 8)
        ));
        getContentPane().add(statusBar, BorderLayout.SOUTH);

        textWindow.setOnContentChanged(() -> {
            OutlineNode selected = outlineController.getSelectedNode();
            if (selected != null) {
                outlineController.getTreeModel().nodeChanged(selected);
            }
        });

        //Fenster schließen
        this.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if(fileController.checkSavedStatus() != SaveStatus.SAVE_FAILED) {
                    fileController.saveLastPath();
                    imageManagementPanel.flush();
                    preview.dispose();
                    deleteRecursively(buildDir);
                    dispose();
                }
            }
        });

        fileController.tryImportLastEditedFile();
        outlineController.saved();
    }

    /** Kurzbeschreibung einer Quelle für die Vervollständigung: Titel und Jahr. */
    private static String describe(String title, String year) {
        String t = title.strip().replace('\n', ' ');
        return year.isBlank() ? t : t + " (" + year.strip() + ")";
    }

    /**
     * Legt das temporäre Build-Verzeichnis an.
     *
     * @return der Pfad des neuen Verzeichnisses
     * @throws UncheckedIOException wenn das Verzeichnis nicht angelegt werden konnte
     */
    private static Path createBuildDir() {
        try {
            return Files.createTempDirectory("texlinr-build");
        } catch (IOException e) {
            throw new UncheckedIOException("Build-Verzeichnis konnte nicht angelegt werden", e);
        }
    }

    /**
     * Löscht ein Verzeichnis samt Inhalt; Fehler werden bewusst ignoriert, da es
     * sich nur um temporäre Dateien handelt.
     *
     * @param dir das zu löschende Verzeichnis, darf {@code null} sein
     */
    private static void deleteRecursively(Path dir) {
        if (dir == null) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // temporäre Dateien, nicht kritisch
        }
    }

    /**
     * Registriert die Tastaturkürzel für Baum-Operationen (ein-/ausrücken,
     * auf-/zuklappen, ausschneiden/kopieren/einfügen, löschen).
     *
     * <p>Bindungen werden direkt am {@code JTree} im {@code WHEN_FOCUSED}-Zustand
     * registriert, da sonst andere Swing-Elemente dieselben Tastenkombinationen
     * abfangen würden.</p>
     *
     * @param tree           der Baum, an dem die Bindungen registriert werden
     * @param editController der Controller für Ausschneiden/Kopieren/Einfügen
     * @param undoManager    der globale Undo-Manager, den die Undo-fähigen Actions benötigen
     */
    private void registerOutlineKeyBindings(JTree tree, EditController editController, UndoManager undoManager) {

        InputMap inputMap = tree.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap actionMap = tree.getActionMap();

        inputMap.put(
                KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                "promoteNode");

        actionMap.put("promoteNode", new PromoteEntryAction(outlineController, undoManager));

        inputMap.put(
                KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                "demoteNode");

        actionMap.put("demoteNode", new DemoteEntryAction(outlineController, undoManager));

        inputMap.put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
                "toggleNode");

        actionMap.put("toggleNode", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                OutlineNode selected = outlineController.getSelectedNode();
                if (selected == null) return;

                javax.swing.tree.TreePath path = new javax.swing.tree.TreePath(
                        outlineController.getTreeModel().getPathToRoot(selected));

                JTree t = outlinePanel.getTree();

                if (t.isExpanded(path)) {
                    t.collapsePath(path);
                } else {
                    t.expandPath(path);
                }
            }
        });

        inputMap.put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | KeyEvent.SHIFT_DOWN_MASK),
                "toggleAll");

        actionMap.put("toggleAll", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                JTree t = outlinePanel.getTree();

                // Prüfen ob irgendetwas aufgeklappt ist — wenn ja, alles zuklappen
                boolean anyExpanded = false;
                for (int i = 0; i < t.getRowCount(); i++) {
                    if (t.isExpanded(i)) {
                        anyExpanded = true;
                        break;
                    }
                }

                if (anyExpanded) {
                    outlineController.collapseAll();
                } else {
                    outlineController.expandAll();
                }
            }
        });

        inputMap.put(
                KeyStroke.getKeyStroke(KeyEvent.VK_X, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                "cutNode");

        actionMap.put("cutNode", new CutAction(editController));

        inputMap.put(
                KeyStroke.getKeyStroke(KeyEvent.VK_C, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                "copyNode");

        actionMap.put("copyNode", new CopyAction(editController));

        inputMap.put(
                KeyStroke.getKeyStroke(KeyEvent.VK_V, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                "pasteNode");

        actionMap.put("pasteNode", new InsertAction(editController));

        inputMap.put(
                KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0),
                "deleteNode");

        actionMap.put("deleteNode", new DeleteEntryAction(outlineController, undoManager));
    }

    /**
     * Lädt das Anwendungs-Logo für das Fenster-Icon.
     *
     * @return das geladene Logo, oder {@code null}, wenn die Ressource nicht
     *         gefunden wurde oder beim Laden ein Fehler auftrat
     */
    private Image loadLogo() {

        try {
            return ImageIO.read(
                    getClass().getResource("/images/texlinr_logo.png")
            );

        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Logo konnte nicht geladen werden");
            return null;
        }
    }

    /**
     * Liefert die Status-Leiste am unteren Fensterrand.
     *
     * @return die Status-Leiste
     */
    public JLabel getStatusBar() {return statusBar; }

    /**
     * Liefert den zentralen Controller für Baum-Operationen, Dirty-Status und
     * Spaltenverwaltung.
     *
     * @return der {@link OutlineController} dieses Fensters
     */
    public OutlineController getOutlineController() {return outlineController; }

    /**
     * Liefert den globalen Undo-Manager.
     *
     * @return der {@link UndoManager} dieses Fensters
     */
    public UndoManager getUndoManager() { return undoManager; }

    /**
     * Liefert die PDF-Vorschau.
     *
     * @return das {@link PDFPreviewPanel} dieses Fensters
     */
    public PDFPreviewPanel getPreview() { return preview; }

    /**
     * Liefert den Bilderordner samt Angaben zu den Abbildungen.
     *
     * @return die {@link ImageLibrary} dieses Fensters
     */
    public ImageLibrary getImageLibrary() { return imageLibrary; }

    /**
     * Liefert das Hauptfenster.
     *
     * @return {@link JFrame}
     */
    public JFrame getJFrame(){ return this;}
}