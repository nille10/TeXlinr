/*

KI generiert

 */

package action.actions;

import service.LatexCompiler;
import view.PDFPreviewPanel;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Baut die Gliederung als LaTeX-Dokument, kompiliert sie zu einem PDF und zeigt
 * das Ergebnis in der {@link PDFPreviewPanel} an.
 *
 * <p>Ablauf bei jedem Klick (oder F5 / Strg+B):</p>
 * <ol>
 *   <li>Der Export ({@link TexExporter}) liest alle Formalitäten (Vorlage, Titelseite, Seitenformat ...)
 *       aus dem Formalitäten-Panel und schreibt {@code main.tex} in das Build-Verzeichnis.
 *       Das läuft im Event-Dispatch-Thread, damit die Gliederung währenddessen
 *       nicht verändert wird.</li>
 *   <li>{@link LatexCompiler} läuft im Hintergrund, die Oberfläche bleibt bedienbar.</li>
 *   <li>Bei Erfolg wird das PDF in der Vorschau aktualisiert, bei Fehlern erscheint
 *       ein Dialog mit den Fehlerzeilen und dem Log. Die letzte gute Vorschau bleibt stehen.</li>
 * </ol>
 */
public class BuildAction extends AbstractAction {

    /** Schreibt den LaTeX-Quelltext der aktuellen Gliederung in die angegebene Datei. */
    @FunctionalInterface
    public interface TexExporter {
        /**
         * @param texFile Zieldatei ({@code main.tex} im Build-Verzeichnis)
         */
        void export(Path texFile) throws IOException;
    }

    private static final String MAIN_TEX = "main.tex";

    private final JFrame frame;
    private final TexExporter exporter;
    private final PDFPreviewPanel preview;
    private final Path buildDir;
    private final LatexCompiler compiler;

    /**
     * @param frame    Elternfenster für Dialoge
     * @param exporter erzeugt den LaTeX-Quelltext (hier den bestehenden Export einhängen)
     * @param preview  die Vorschau, die nach erfolgreichem Build aktualisiert wird
     * @param buildDir Arbeitsverzeichnis für {@code main.tex}, PDF und Hilfsdateien;
     *                 dort sollten später auch {@code references.bib} und Abbildungen liegen
     * @param compiler der zu verwendende Compiler
     */
    public BuildAction(JFrame frame, TexExporter exporter, PDFPreviewPanel preview,
                       Path buildDir, LatexCompiler compiler) {
        super("Build");
        this.frame = frame;
        this.exporter = exporter;
        this.preview = preview;
        this.buildDir = buildDir;
        this.compiler = compiler;
        putValue(SHORT_DESCRIPTION, "LaTeX kompilieren und Vorschau aktualisieren (F5)");
        putValue(ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0));
    }

    /** Bindet F5 und Strg+B im gesamten Fenster an diese Action. */
    public void registerShortcuts(JRootPane rootPane) {
        InputMap in = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        in.put(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "build");
        in.put(KeyStroke.getKeyStroke(KeyEvent.VK_B,
                Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "build");
        rootPane.getActionMap().put("build", this);
    }

    @Override
    public void actionPerformed(java.awt.event.ActionEvent e) {
        Path tex = buildDir.resolve(MAIN_TEX);
        try {
            Files.createDirectories(buildDir);
            exporter.export(tex);
        } catch (IOException | RuntimeException ex) {
            showError("Export fehlgeschlagen", String.valueOf(ex.getMessage()));
            return;
        }

        setEnabled(false);
        preview.setBusy(true);

        new SwingWorker<LatexCompiler.Result, Void>() {
            @Override
            protected LatexCompiler.Result doInBackground() throws InterruptedException {
                return compiler.compile(tex);
            }

            @Override
            protected void done() {
                setEnabled(true);
                preview.setBusy(false);
                try {
                    LatexCompiler.Result result = get();
                    if (result.success()) {
                        preview.showPdf(result.pdf());
                    } else {
                        showBuildErrors(result);
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ex) {
                    showError("Build fehlgeschlagen", String.valueOf(ex.getCause()));
                }
            }
        }.execute();
    }

    // ── Fehlerdialoge ────────────────────────────────────────────────────────

    private void showError(String title, String message) {
        JOptionPane.showMessageDialog(frame, message, title, JOptionPane.ERROR_MESSAGE);
    }

    private void showBuildErrors(LatexCompiler.Result result) {
        List<String> errors = result.errors();
        StringBuilder head = new StringBuilder("<html><b>Kompilierung fehlgeschlagen.</b>");
        for (int i = 0; i < Math.min(5, errors.size()); i++) {
            head.append("<br>").append(escape(errors.get(i)));
        }
        head.append("</html>");

        JTextArea area = new JTextArea(tail(result.log(), 400), 22, 90);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setCaretPosition(area.getDocument().getLength());

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(new JLabel(head.toString()), BorderLayout.NORTH);
        panel.add(new JScrollPane(area), BorderLayout.CENTER);

        JOptionPane.showMessageDialog(frame, panel, "Build-Fehler", JOptionPane.ERROR_MESSAGE);
    }

    private static String tail(String text, int maxLines) {
        String[] lines = text.split("\\R");
        if (lines.length <= maxLines) return text;
        return String.join("\n", java.util.Arrays.copyOfRange(lines, lines.length - maxLines, lines.length));
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}