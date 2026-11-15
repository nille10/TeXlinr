package model.image;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Eine Abbildung im Bilderordner samt der Angaben, die für die LaTeX-Umgebung
 * {@code figure} nötig sind: Platzierung, Breite, Beschriftung und Label.
 *
 * <p>Reine Daten ohne Swing-Bezug. Die Datei selbst liegt im Bilderordner
 * (siehe {@link ImageLibrary}), die Zusatzangaben werden dort in einer
 * Metadaten-Datei gespeichert. Dateiänderungen (Umbenennen, Größe, Änderungsdatum)
 * verwaltet ausschließlich die {@link ImageLibrary}.</p>
 *
 * <p>{@link #toLatex()} erzeugt den fertigen Block, z. B.:</p>
 * <pre>
 * \begin{figure}[htbp]
 *     \centering
 *     \includegraphics[width=1\textwidth]{img/ueberblick_ui.png}
 *     \caption{Grafische Erklärung des Aufbaus}
 *     \label{fig:ui}
 * \end{figure}
 * </pre>
 */
public class ImageEntry {

    /** Name des Unterordners, relativ zur {@code .tex}-Datei. */
    static final String LATEX_FOLDER = "img";

    private Path file;
    private ImagePlacement placement = ImagePlacement.AUTO;
    private int widthPercent = 100;
    private String caption = "";
    private String label = "";
    private long lastModified;
    private long size;

    ImageEntry(Path file) {
        this.file = file;
    }

    // ── Datei ────────────────────────────────────────────────────────────────

    /** @return der absolute Pfad der Bilddatei */
    public Path getFile() {
        return file;
    }

    /** @return der Dateiname inklusive Endung, z. B. {@code "ueberblick_ui.png"} */
    public String getFileName() {
        return file.getFileName().toString();
    }

    /** @return die Dateiendung in Kleinbuchstaben ohne Punkt, z. B. {@code "png"} */
    public String getExtension() {
        return ImageLibrary.extensionOf(getFileName());
    }

    /** @return {@code true}, wenn es sich um ein PDF handelt */
    public boolean isPdf() {
        return "pdf".equals(getExtension());
    }

    /** @return der Pfad, wie er in {@code \includegraphics} steht, z. B. {@code "img/ueberblick_ui.png"} */
    public String getLatexPath() {
        return LATEX_FOLDER + "/" + getFileName();
    }

    /** @return Änderungszeitpunkt der Datei in Millisekunden (Stand des letzten Scans) */
    public long getLastModified() {
        return lastModified;
    }

    /** @return Dateigröße in Byte (Stand des letzten Scans) */
    public long getSize() {
        return size;
    }

    void setFile(Path file) {
        this.file = file;
    }

    void setFileStats(long lastModified, long size) {
        this.lastModified = lastModified;
        this.size = size;
    }

    // ── LaTeX-Angaben ────────────────────────────────────────────────────────

    public ImagePlacement getPlacement() {
        return placement;
    }

    public void setPlacement(ImagePlacement placement) {
        this.placement = placement == null ? ImagePlacement.AUTO : placement;
    }

    /** @return die Breite in Prozent der Textbreite (5 bis 100) */
    public int getWidthPercent() {
        return widthPercent;
    }

    public void setWidthPercent(int widthPercent) {
        this.widthPercent = Math.max(5, Math.min(100, widthPercent));
    }

    public String getCaption() {
        return caption;
    }

    public void setCaption(String caption) {
        this.caption = caption == null ? "" : caption;
    }

    /** @return das Label für {@code \ref}, z. B. {@code "fig:ui"}; leer, wenn keines gesetzt ist */
    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label == null ? "" : label.trim();
    }

    // ── LaTeX-Ausgabe ────────────────────────────────────────────────────────

    /**
     * Erzeugt die komplette {@code figure}-Umgebung für diese Abbildung.
     *
     * <p>Beschriftung und Label werden nur ausgegeben, wenn sie gesetzt sind.
     * Die Beschriftung wird unverändert übernommen (sie darf LaTeX enthalten),
     * Zeilenumbrüche werden zu Leerzeichen.</p>
     *
     * @return der LaTeX-Block, abgeschlossen mit Zeilenumbruch
     */
    public String toLatex() {
        StringBuilder sb = new StringBuilder();
        sb.append("\\begin{figure}[").append(placement.getCode()).append("]\n");
        sb.append("    \\centering\n");
        sb.append("    \\includegraphics[width=").append(widthFactor())
                .append("\\textwidth]{").append(getLatexPath()).append("}\n");
        if (!caption.isBlank()) {
            sb.append("    \\caption{").append(caption.trim().replaceAll("\\s*\\R\\s*", " ")).append("}\n");
        }
        if (!label.isBlank()) {
            sb.append("    \\label{").append(label).append("}\n");
        }
        sb.append("\\end{figure}\n");
        return sb.toString();
    }

    /** Breite als Faktor ohne überflüssige Nullen: 100 -> "1", 80 -> "0.8", 5 -> "0.05". */
    private String widthFactor() {
        return BigDecimal.valueOf(widthPercent).movePointLeft(2).stripTrailingZeros()
                .toPlainString().toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return getFileName();
    }
}