/*
KI generiert
 */
package view.image;

import model.image.ImageEntry;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lädt verkleinerte Vorschaubilder im Hintergrund und hält die zuletzt benutzten im Speicher.
 *
 * <p>Unterstützt PNG, JPG (mit Teilauflösung beim Lesen, große Fotos belegen also nicht
 * den vollen Speicher) und die erste Seite von PDFs (über PDFBox). Der Cache-Schlüssel
 * enthält Dateiname, Änderungsdatum und Größe, geänderte Dateien werden also automatisch
 * neu geladen.</p>
 *
 * <p>Alle Methoden außer dem internen Laden laufen im Event-Dispatch-Thread.</p>
 */
public final class ThumbnailCache {

    private final int maxWidth;
    private final int maxHeight;
    private final Map<String, BufferedImage> cache;
    private final Set<String> loading = new HashSet<>();
    private final Set<String> failed = new HashSet<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "thumbnail-loader");
        t.setDaemon(true);
        return t;
    });

    /**
     * @param maxWidth   größte Breite eines Vorschaubilds in Pixeln
     * @param maxHeight  größte Höhe eines Vorschaubilds in Pixeln
     * @param maxEntries Anzahl der Bilder, die im Speicher bleiben
     */
    public ThumbnailCache(int maxWidth, int maxHeight, int maxEntries) {
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
        this.cache = new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
                return size() > maxEntries;
            }
        };
    }

    /**
     * Liefert das Vorschaubild, falls schon geladen. Sonst wird das Laden gestartet und
     * {@code null} zurückgegeben; {@code onLoaded} wird aufgerufen, sobald das Ergebnis
     * (oder ein Fehlschlag) feststeht.
     *
     * @param entry    die Abbildung
     * @param onLoaded wird im Event-Dispatch-Thread nach dem Laden aufgerufen
     * @return das Vorschaubild oder {@code null}
     */
    public BufferedImage get(ImageEntry entry, Runnable onLoaded) {
        String key = keyOf(entry);
        BufferedImage hit = cache.get(key);
        if (hit != null || failed.contains(key)) {
            return hit;
        }
        if (loading.add(key)) {
            Path file = entry.getFile();
            executor.submit(() -> {
                BufferedImage thumbnail = null;
                try {
                    thumbnail = load(file);
                } catch (Exception | OutOfMemoryError ignored) {
                    // wird unten als fehlgeschlagen vermerkt
                }
                BufferedImage result = thumbnail;
                SwingUtilities.invokeLater(() -> {
                    loading.remove(key);
                    if (result != null) {
                        cache.put(key, result);
                    } else {
                        failed.add(key);
                    }
                    onLoaded.run();
                });
            });
        }
        return null;
    }

    /** @return {@code true}, wenn das Laden dieser Abbildung fehlgeschlagen ist */
    public boolean hasFailed(ImageEntry entry) {
        return failed.contains(keyOf(entry));
    }

    private static String keyOf(ImageEntry entry) {
        return entry.getFileName() + "|" + entry.getLastModified() + "|" + entry.getSize();
    }

    // ── Laden (Hintergrundthread) ────────────────────────────────────────────

    private BufferedImage load(Path file) throws IOException {
        boolean pdf = file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf");
        BufferedImage raw = pdf ? renderPdfFirstPage(file) : readSubsampled(file);
        return raw == null ? null : scaleToFit(raw);
    }

    private BufferedImage renderPdfFirstPage(Path file) throws IOException {
        try (PDDocument document = Loader.loadPDF(Files.readAllBytes(file))) {
            if (document.getNumberOfPages() == 0) {
                return null;
            }
            PDRectangle box = document.getPage(0).getCropBox();
            double dpi = 72.0 * (maxWidth * 2) / Math.max(1f, box.getWidth());
            dpi = Math.max(36, Math.min(150, dpi));
            return new PDFRenderer(document).renderImageWithDPI(0, (float) dpi, ImageType.RGB);
        }
    }

    private BufferedImage readSubsampled(Path file) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
            if (input == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                int step = Math.max(1, Math.max(width / (maxWidth * 2), height / (maxHeight * 2)));
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(step, step, 0, 0);
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        }
    }

    private BufferedImage scaleToFit(BufferedImage source) {
        double scale = Math.min(1.0, Math.min(
                (double) maxWidth / source.getWidth(),
                (double) maxHeight / source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));

        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = scaled.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.drawImage(source, 0, 0, width, height, null);
        } finally {
            g2.dispose();
        }
        return scaled;
    }
}