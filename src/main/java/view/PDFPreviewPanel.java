package view;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.swing.*;
import javax.swing.border.AbstractBorder;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dauerhaft sichtbare PDF-Vorschau.
 *
 * <p>Zeigt ein mit {@link #showPdf(Path)} geladenes PDF (alle Seiten untereinander)
 * oder, solange noch keines geladen ist, ein leeres A4-Blatt. Seiten werden mit
 * Apache PDFBox im Hintergrund gerendert, nur sichtbare Seiten, in zur
 * Zoomstufe passender Auflösung. Bis das neue Bild fertig ist, bleibt das alte
 * sichtbar, die Vorschau flackert also bei einem Rebuild nicht.</p>
 *
 * <p>Die Zoom-Steuerung schwebt als kleine Leiste unten rechts über dem Dokument.
 * Bedienung:</p>
 * <ul>
 *   <li><b>Maus:</b> Strg + Mausrad zoomt zum Mauszeiger hin, Mausrad scrollt,
 *       Shift + Mausrad scrollt horizontal, Ziehen verschiebt die Seite,
 *       Doppelklick passt die Seite an die Breite an.</li>
 *   <li><b>Tastatur:</b> Strg + / Strg − (auch Ziffernblock) zoomen in Stufen,
 *       Strg + 0 passt an die Breite an, Strg + 1 setzt auf 100 %.
 *       Pfeiltasten, Bild auf/ab, Pos1/Ende scrollen wie gewohnt.</li>
 * </ul>
 *
 * <p>Solange der Nutzer nicht manuell gezoomt hat, folgt die Seite automatisch
 * der Panelbreite. Beim Aktualisieren eines PDFs bleibt die Scrollposition erhalten.</p>
 */
public class PDFPreviewPanel extends JPanel {

    private static final double PX_PER_PT = 96.0 / 72.0;
    private static final double A4_WIDTH_PX  = 210.0 / 25.4 * 96.0;
    private static final double A4_HEIGHT_PX = 297.0 / 25.4 * 96.0;

    private static final double MIN_ZOOM = 0.25;
    private static final double MAX_ZOOM = 4.00;

    /** Zoomstufen für Buttons und Tastatur (das Mausrad zoomt stufenlos). */
    private static final double[] ZOOM_PRESETS =
            {0.25, 0.33, 0.50, 0.67, 0.75, 0.90, 1.00, 1.25, 1.50, 1.75, 2.00, 3.00, 4.00};

    /** Zoomfaktor pro Mausrad-Raste bei Strg + Mausrad. */
    private static final double WHEEL_ZOOM_BASE = 1.12;

    /** Pixel pro Mausrad-Einheit beim normalen Scrollen. */
    private static final int SCROLL_UNIT = 16;

    private static final int PAGE_MARGIN = 24;
    private static final int MENU_MASK = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

    /** Obergrenze für die Render-Auflösung; darüber wird das Bild hochskaliert (spart Speicher). */
    private static final int MAX_RENDER_DPI = 220;
    /** Render-Auflösung wird auf Vielfache davon gerundet, damit kleine Zoomänderungen nicht neu rendern. */
    private static final int DPI_QUANTUM = 12;

    private double zoom = 0.48;
    /** Solange true, wird der Zoom bei jeder Größenänderung an die Breite angepasst. */
    private boolean fitWidth = true;

    // ── Dokumentzustand (nur im Event-Dispatch-Thread anfassen) ─────────────

    private PdfSession session;                       // null = leeres A4-Platzhalterblatt
    private double[] pageW = {A4_WIDTH_PX};           // Seitenbreiten in px bei 100 %
    private double[] pageH = {A4_HEIGHT_PX};
    private int generation;                           // zählt geladene PDFs hoch
    private final PageCache cache = new PageCache();
    private final Set<Long> pending = new HashSet<>();
    private volatile int currentDpi = 96;             // vom Renderthread gelesen

    // Reihenfolge wichtig: zoomValueButton und progressBar müssen vor controls existieren,
    // weil der FloatingControls-Konstruktor den Button verwendet.
    private final JButton zoomValueButton = new JButton();
    private final JProgressBar progressBar = new JProgressBar();
    private final PageCanvas pageCanvas = new PageCanvas();
    private final JScrollPane scrollPane = new JScrollPane(pageCanvas);
    private final FloatingControls controls = new FloatingControls();

    public PDFPreviewPanel() {
        super(new BorderLayout());
        setPreferredSize(new Dimension(410, 0));
        setMinimumSize(new Dimension(280, 0));
        setBorder(new SideLine());

        scrollPane.setBorder(null);
        scrollPane.getVerticalScrollBar().setUnitIncrement(24);
        scrollPane.getHorizontalScrollBar().setUnitIncrement(24);
        // Mausrad wird unten selbst behandelt (Scrollen und Strg-Zoom an einer Stelle).
        scrollPane.setWheelScrollingEnabled(false);
        scrollPane.addMouseWheelListener(this::handleMouseWheel);

        progressBar.setIndeterminate(true);
        progressBar.setBorderPainted(false);
        progressBar.setFocusable(false);
        progressBar.setVisible(false);

        LayeredHost host = new LayeredHost();
        host.add(scrollPane, JLayeredPane.DEFAULT_LAYER);
        host.add(controls, JLayeredPane.PALETTE_LAYER);
        host.add(progressBar, JLayeredPane.PALETTE_LAYER);
        add(host, BorderLayout.CENTER);

        // Scrollbars erscheinen/verschwinden -> schwebende Leiste neu positionieren.
        ComponentAdapter relayout = new ComponentAdapter() {
            @Override public void componentShown(ComponentEvent e)  { host.revalidate(); }
            @Override public void componentHidden(ComponentEvent e) { host.revalidate(); }
        };
        scrollPane.getVerticalScrollBar().addComponentListener(relayout);
        scrollPane.getHorizontalScrollBar().addComponentListener(relayout);

        // Automatisch an die Breite anpassen, solange nicht manuell gezoomt wurde.
        scrollPane.getViewport().addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                if (fitWidth) SwingUtilities.invokeLater(PDFPreviewPanel.this::applyFit);
            }
        });

        installKeyBindings();
        updateZoomLabel();
    }

    // ── Öffentliche API ──────────────────────────────────────────────────────

    /**
     * Lädt ein PDF und zeigt es an (ersetzt ein bereits angezeigtes). Das Parsen
     * läuft im Hintergrund; die Datei wird komplett eingelesen und nicht offen
     * gehalten, sodass LaTeX sie beim nächsten Build überschreiben kann
     * (unter Windows sonst gesperrt).
     *
     * @param pdf Pfad zur PDF-Datei; darf von jedem Thread aufgerufen werden
     */
    public void showPdf(Path pdf) {
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return new PdfSession(Files.readAllBytes(pdf));
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    }
                })
                .whenComplete((newSession, error) -> SwingUtilities.invokeLater(() -> {
                    if (error != null) {
                        Throwable cause = error.getCause() != null ? error.getCause() : error;
                        JOptionPane.showMessageDialog(this,
                                "Das PDF konnte nicht geladen werden:\n" + cause.getMessage(),
                                "PDF-Vorschau", JOptionPane.ERROR_MESSAGE);
                    } else {
                        applySession(newSession);
                    }
                }));
    }

    /** Entfernt das PDF und zeigt wieder das leere A4-Blatt. */
    public void clearPdf() {
        PdfSession old = session;
        session = null;
        pageW = new double[]{A4_WIDTH_PX};
        pageH = new double[]{A4_HEIGHT_PX};
        generation++;
        pending.clear();
        cache.clear();
        if (old != null) old.close();
        if (fitWidth) applyFit();
        pageCanvas.revalidate();
        pageCanvas.repaint();
    }

    /** Zeigt oder versteckt den schmalen Fortschrittsbalken am oberen Rand (Build läuft). */
    public void setBusy(boolean busy) {
        progressBar.setIndeterminate(busy);
        progressBar.setVisible(busy);
    }

    /** Gibt PDF und Renderthread frei, z. B. beim Schließen des Fensters. */
    public void dispose() {
        clearPdf();
    }

    /** Passt die Seite an die Panelbreite an und hält sie bei Größenänderungen angepasst. */
    public void fitToWidth() {
        fitWidth = true;
        applyFit();
    }

    /** Setzt den Zoom (1.0 = 100 %) und deaktiviert die automatische Breitenanpassung. */
    public void setZoom(double newZoom) {
        fitWidth = false;
        zoomTo(newZoom, null);
    }

    public double getZoom() {
        return zoom;
    }

    private void applySession(PdfSession newSession) {
        PdfSession old = session;
        session = newSession;
        pageW = newSession.w;
        pageH = newSession.h;
        generation++;     // alte Bilder bleiben als Platzhalter sichtbar, gelten aber als veraltet
        pending.clear();
        if (old != null) old.close();
        if (fitWidth) applyFit();
        pageCanvas.revalidate();
        pageCanvas.repaint();
    }

    // ── Zoom-Logik ───────────────────────────────────────────────────────────

    private double maxPageWidth() {
        double max = 1;
        for (double w : pageW) max = Math.max(max, w);
        return max;
    }

    private void applyFit() {
        int width = scrollPane.getViewport().getWidth();
        if (width <= 0) return;
        double fit = (width - PAGE_MARGIN * 2) / maxPageWidth();
        // Oberkante bleibt stehen, horizontal wird zur Mitte hin gezoomt.
        zoomTo(fit, new Point(width / 2, 0));
    }

    /**
     * Setzt den Zoom und hält dabei den Dokumentpunkt unter {@code viewportAnchor}
     * (Koordinaten im Viewport) an derselben Bildschirmposition. Bei {@code null}
     * wird zur Viewport-Mitte hin gezoomt.
     */
    private void zoomTo(double newZoom, Point viewportAnchor) {
        newZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, newZoom));
        if (Math.abs(newZoom - zoom) < 1e-4) {
            updateZoomLabel();
            return;
        }

        JViewport viewport = scrollPane.getViewport();
        Point view = viewport.getViewPosition();
        Point anchor = viewportAnchor != null
                ? viewportAnchor
                : new Point(viewport.getWidth() / 2, viewport.getHeight() / 2);

        // Anchor als Bruchteil des Seitenstapels merken, damit er nach dem Zoom wiedergefunden wird.
        Layout before = new Layout();
        double fx = (view.x + anchor.x - before.originX) / (double) before.contentW;
        double fy = (view.y + anchor.y - before.originY) / (double) before.contentH;

        zoom = newZoom;
        updateZoomLabel();
        pageCanvas.revalidate();
        scrollPane.validate();

        Layout after = new Layout();
        setViewPosition(
                (int) Math.round(after.originX + fx * after.contentW - anchor.x),
                (int) Math.round(after.originY + fy * after.contentH - anchor.y));
        pageCanvas.repaint();
    }

    private void zoomStep(boolean in) {
        fitWidth = false;
        zoomTo(nextPreset(in), null);
    }

    private double nextPreset(boolean in) {
        if (in) {
            for (double p : ZOOM_PRESETS) if (p > zoom + 0.005) return p;
            return MAX_ZOOM;
        }
        for (int i = ZOOM_PRESETS.length - 1; i >= 0; i--) {
            if (ZOOM_PRESETS[i] < zoom - 0.005) return ZOOM_PRESETS[i];
        }
        return MIN_ZOOM;
    }

    private void updateZoomLabel() {
        zoomValueButton.setText(Math.round(zoom * 100) + " %");
    }

    // ── Scrollen ─────────────────────────────────────────────────────────────

    private void scrollBy(int dx, int dy) {
        Point p = scrollPane.getViewport().getViewPosition();
        setViewPosition(p.x + dx, p.y + dy);
    }

    private void setViewPosition(int x, int y) {
        JViewport viewport = scrollPane.getViewport();
        Dimension view = viewport.getViewSize();
        Dimension extent = viewport.getExtentSize();
        x = Math.max(0, Math.min(x, Math.max(0, view.width - extent.width)));
        y = Math.max(0, Math.min(y, Math.max(0, view.height - extent.height)));
        viewport.setViewPosition(new Point(x, y));
    }

    // ── Eingabe ──────────────────────────────────────────────────────────────

    private void handleMouseWheel(MouseWheelEvent e) {
        if ((e.getModifiersEx() & MENU_MASK) != 0) {
            fitWidth = false;
            double factor = Math.pow(WHEEL_ZOOM_BASE, -e.getPreciseWheelRotation());
            Point anchor = SwingUtilities.convertPoint(scrollPane, e.getPoint(), scrollPane.getViewport());
            zoomTo(zoom * factor, anchor);
        } else {
            int amount = (int) Math.round(e.getPreciseWheelRotation() * e.getScrollAmount() * SCROLL_UNIT);
            if (e.isShiftDown()) scrollBy(amount, 0);
            else                 scrollBy(0, amount);
        }
        e.consume();
    }

    private void installKeyBindings() {
        InputMap in = getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        ActionMap am = getActionMap();

        bind(in, am, "zoomIn", () -> zoomStep(true),
                KeyEvent.VK_PLUS, KeyEvent.VK_EQUALS, KeyEvent.VK_ADD);
        bind(in, am, "zoomOut", () -> zoomStep(false),
                KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT);
        bind(in, am, "zoomFit", this::fitToWidth,
                KeyEvent.VK_0, KeyEvent.VK_NUMPAD0);
        bind(in, am, "zoom100", () -> setZoom(1.0),
                KeyEvent.VK_1, KeyEvent.VK_NUMPAD1);
    }

    private static void bind(InputMap in, ActionMap am, String name, Runnable action, int... keyCodes) {
        for (int code : keyCodes) {
            in.put(KeyStroke.getKeyStroke(code, MENU_MASK), name);
        }
        am.put(name, new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { action.run(); }
        });
    }

    // ── Rendern ──────────────────────────────────────────────────────────────

    /** Zur Zoomstufe (und Bildschirmskalierung) passende Render-Auflösung. */
    private int computeDpi() {
        GraphicsConfiguration gc = getGraphicsConfiguration();
        double scale = gc == null ? 1.0 : gc.getDefaultTransform().getScaleX();
        double raw = 96.0 * zoom * scale;
        int quantized = (int) (Math.ceil(raw / DPI_QUANTUM) * DPI_QUANTUM);
        return Math.max(DPI_QUANTUM * 2, Math.min(MAX_RENDER_DPI, quantized));
    }

    /** Stößt (falls nicht schon geschehen) das Rendern einer Seite im Hintergrund an. */
    private void requestRender(int page, int dpi) {
        PdfSession s = session;
        if (s == null || page >= s.pageCount) return;

        final int gen = generation;
        long key = ((long) gen << 40) | ((long) page << 16) | dpi;
        if (!pending.add(key)) return;

        s.executor.submit(() -> {
            BufferedImage image = null;
            boolean stale = s.closed || dpi != currentDpi;
            if (!stale) {
                try {
                    image = s.renderer.renderImageWithDPI(page, dpi, ImageType.RGB);
                } catch (Exception | OutOfMemoryError ex) {
                    ex.printStackTrace();
                }
            }
            final BufferedImage result = image;
            final boolean wasStale = stale;
            SwingUtilities.invokeLater(() -> {
                pending.remove(key);
                if (s != session) return;
                if (result != null) {
                    cache.put(page, new Rendered(result, dpi, gen));
                    pageCanvas.repaint();
                } else if (wasStale) {
                    pageCanvas.repaint(); // Zoom hat sich inzwischen geändert -> neu anfordern
                }
            });
        });
    }

    private record Rendered(BufferedImage image, int dpi, int generation) {}

    /** LRU-Cache der gerenderten Seiten mit Pixelbudget (nur im EDT benutzen). */
    private static final class PageCache {
        private static final long MAX_PIXELS = 48_000_000L;
        private final LinkedHashMap<Integer, Rendered> map = new LinkedHashMap<>(16, 0.75f, true);
        private long pixels;

        Rendered get(int page) {
            return map.get(page);
        }

        void put(int page, Rendered r) {
            Rendered old = map.put(page, r);
            if (old != null) pixels -= size(old);
            pixels += size(r);
            Iterator<Map.Entry<Integer, Rendered>> it = map.entrySet().iterator();
            while (pixels > MAX_PIXELS && map.size() > 1 && it.hasNext()) {
                Map.Entry<Integer, Rendered> eldest = it.next();
                pixels -= size(eldest.getValue());
                it.remove();
            }
        }

        void clear() {
            map.clear();
            pixels = 0;
        }

        private static long size(Rendered r) {
            return (long) r.image().getWidth() * r.image().getHeight();
        }
    }

    /** Geladenes PDF samt eigenem Renderthread (PDFBox-Renderer ist nicht thread-sicher). */
    private static final class PdfSession {
        final PDDocument doc;
        final PDFRenderer renderer;
        final int pageCount;
        final double[] w;
        final double[] h;
        final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "pdf-render");
            t.setDaemon(true);
            return t;
        });
        volatile boolean closed;

        PdfSession(byte[] data) throws IOException {
            doc = Loader.loadPDF(data);
            renderer = new PDFRenderer(doc);
            pageCount = doc.getNumberOfPages();
            if (pageCount == 0) {
                doc.close();
                executor.shutdown();
                throw new IOException("Das PDF enthält keine Seiten.");
            }
            w = new double[pageCount];
            h = new double[pageCount];
            for (int i = 0; i < pageCount; i++) {
                PDPage page = doc.getPage(i);
                PDRectangle box = page.getCropBox();
                double bw = box.getWidth();
                double bh = box.getHeight();
                if (Math.floorMod(page.getRotation(), 180) != 0) {
                    double tmp = bw; bw = bh; bh = tmp;
                }
                w[i] = bw * PX_PER_PT;
                h[i] = bh * PX_PER_PT;
            }
        }

        /** Schließt das Dokument, nachdem bereits laufende Render-Aufgaben fertig sind. */
        void close() {
            closed = true;
            executor.submit(() -> {
                try {
                    doc.close();
                } catch (IOException ignored) {
                    // nichts zu tun
                }
            });
            executor.shutdown();
        }
    }

    // ── Layout der Seiten ────────────────────────────────────────────────────

    /** Momentaufnahme der Seitenpositionen (Seiten untereinander, mittig) in Canvas-Koordinaten. */
    private final class Layout {
        final int count = pageW.length;
        final int[] w = new int[count];
        final int[] h = new int[count];
        final int[] offsetY = new int[count];
        final int contentW;
        final int contentH;
        final int originX;
        final int originY;

        Layout() {
            int gap = Math.max(6, (int) Math.round(16 * zoom));
            int maxW = 0;
            int y = 0;
            for (int i = 0; i < count; i++) {
                w[i] = Math.max(1, (int) Math.round(pageW[i] * zoom));
                h[i] = Math.max(1, (int) Math.round(pageH[i] * zoom));
                offsetY[i] = y;
                y += h[i] + gap;
                maxW = Math.max(maxW, w[i]);
            }
            contentW = maxW;
            contentH = Math.max(1, y - gap);
            originX = Math.max(PAGE_MARGIN, (pageCanvas.getWidth() - contentW) / 2);
            originY = Math.max(PAGE_MARGIN, (pageCanvas.getHeight() - contentH) / 2);
        }

        Rectangle rect(int i) {
            return new Rectangle(originX + (contentW - w[i]) / 2, originY + offsetY[i], w[i], h[i]);
        }
    }

    // ── Schwebende Zoom-Leiste ───────────────────────────────────────────────

    private JButton flatButton(String text, String tooltip, Runnable action) {
        JButton b = new JButton(text);
        styleFlat(b, tooltip, action);
        return b;
    }

    private void styleFlat(JButton b, String tooltip, Runnable action) {
        b.setFocusable(false); // Tastaturfokus bleibt auf der Seite
        b.setToolTipText(tooltip);
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.addActionListener(e -> action.run());
    }

    /** Halbtransparente, abgerundete Leiste, die über dem Dokument schwebt. */
    private final class FloatingControls extends JPanel {

        FloatingControls() {
            super(new FlowLayout(FlowLayout.CENTER, 2, 0));
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));

            styleFlat(zoomValueButton, "Auf 100 % setzen (Strg+1)", () -> setZoom(1.0));
            zoomValueButton.setPreferredSize(new Dimension(58, 26));

            add(flatButton("−", "Verkleinern (Strg+−)", () -> zoomStep(false)));
            add(zoomValueButton);
            add(flatButton("+", "Vergrößern (Strg++)", () -> zoomStep(true)));
            add(flatButton("↔", "An Seitenbreite anpassen (Strg+0)", PDFPreviewPanel.this::fitToWidth));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color bg = UIManager.getColor("Panel.background");
                if (bg == null) bg = Color.WHITE;
                Color line = UIManager.getColor("Component.borderColor");
                if (line == null) line = UIManager.getColor("Separator.foreground");
                if (line == null) line = Color.GRAY;

                int arc = 14;
                g2.setColor(new Color(0, 0, 0, 40)); // weicher Schatten
                g2.fillRoundRect(1, 2, getWidth() - 2, getHeight() - 2, arc, arc);
                g2.setColor(new Color(bg.getRed(), bg.getGreen(), bg.getBlue(), 230));
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 2, arc, arc);
                g2.setColor(line);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 2, arc, arc);
            } finally {
                g2.dispose();
            }
        }
    }

    /** Legt ScrollPane vollflächig, Zoom-Leiste unten rechts und Fortschrittsbalken oben darüber. */
    private final class LayeredHost extends JLayeredPane {

        private static final int GAP = 14;

        @Override
        public void doLayout() {
            scrollPane.setBounds(0, 0, getWidth(), getHeight());
            progressBar.setBounds(0, 0, getWidth(), 4);

            JScrollBar vsb = scrollPane.getVerticalScrollBar();
            JScrollBar hsb = scrollPane.getHorizontalScrollBar();
            int right  = GAP + (vsb.isVisible() ? vsb.getWidth() : 0);
            int bottom = GAP + (hsb.isVisible() ? hsb.getHeight() : 0);

            Dimension d = controls.getPreferredSize();
            controls.setBounds(getWidth() - d.width - right, getHeight() - d.height - bottom, d.width, d.height);
        }
    }

    // ── Zeichenfläche ────────────────────────────────────────────────────────

    /** Zeichnet alle Seiten; gerenderte Bilder kommen aus dem Cache, fehlende werden angefordert. */
    private final class PageCanvas extends JPanel implements Scrollable {

        PageCanvas() {
            setOpaque(true);
            setFocusable(true); // damit Tastenkürzel greifen, sobald man ins Dokument klickt
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            MouseAdapter pan = new MouseAdapter() {
                private Point dragStartScreen;
                private Point viewStart;

                @Override public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                    dragStartScreen = e.getLocationOnScreen();
                    viewStart = scrollPane.getViewport().getViewPosition();
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                }

                @Override public void mouseDragged(MouseEvent e) {
                    if (dragStartScreen == null) return;
                    Point now = e.getLocationOnScreen();
                    setViewPosition(viewStart.x - (now.x - dragStartScreen.x),
                            viewStart.y - (now.y - dragStartScreen.y));
                }

                @Override public void mouseReleased(MouseEvent e) {
                    dragStartScreen = null;
                    setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                }

                @Override public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) fitToWidth();
                }
            };
            addMouseListener(pan);
            addMouseMotionListener(pan);
        }

        @Override
        public Dimension getPreferredSize() {
            Layout l = new Layout();
            return new Dimension(l.contentW + PAGE_MARGIN * 2, l.contentH + PAGE_MARGIN * 2);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

                g2.setColor(AppTheme.isDark() ? new Color(46, 48, 52) : new Color(105, 108, 113));
                g2.fillRect(0, 0, getWidth(), getHeight());

                Rectangle clip = g2.getClipBounds() != null ? g2.getClipBounds() : getBounds();
                int wantedDpi = computeDpi();
                currentDpi = wantedDpi;

                Layout layout = new Layout();
                for (int i = 0; i < layout.count; i++) {
                    Rectangle r = layout.rect(i);
                    if (r.y > clip.y + clip.height) break;           // alles Weitere liegt unterhalb
                    if (r.y + r.height + 6 < clip.y) continue;        // noch oberhalb des sichtbaren Bereichs

                    // Dezenter Seitenschatten
                    g2.setColor(new Color(0, 0, 0, 90));
                    g2.fillRoundRect(r.x + 4, r.y + 5, r.width, r.height, 2, 2);

                    // Weißes Blatt (bleibt sichtbar, solange noch nichts gerendert ist)
                    g2.setColor(Color.WHITE);
                    g2.fillRect(r.x, r.y, r.width, r.height);

                    if (session != null) {
                        Rendered cached = cache.get(i);
                        if (cached != null) {
                            g2.drawImage(cached.image(), r.x, r.y, r.width, r.height, null);
                        }
                        boolean fresh = cached != null
                                && cached.dpi() == wantedDpi
                                && cached.generation() == generation;
                        if (!fresh) requestRender(i, wantedDpi);
                    }

                    g2.setColor(new Color(215, 215, 215));
                    g2.drawRect(r.x, r.y, r.width, r.height);
                }
            } finally {
                g2.dispose();
            }
        }

        // Scrollable: Canvas füllt den Viewport, solange der Seitenstapel hineinpasst.

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }

        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return 24;
        }

        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return (orientation == SwingConstants.VERTICAL ? visible.height : visible.width) * 9 / 10;
        }

        @Override public boolean getScrollableTracksViewportWidth() {
            return getParent() instanceof JViewport
                    && ((JViewport) getParent()).getWidth() > getPreferredSize().width;
        }

        @Override public boolean getScrollableTracksViewportHeight() {
            return getParent() instanceof JViewport
                    && ((JViewport) getParent()).getHeight() > getPreferredSize().height;
        }
    }

    /** Dünne Trennlinie zur linken Seite, themenabhängig statt fest verdrahteter Grauwert. */
    private static final class SideLine extends AbstractBorder {
        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            Color line = UIManager.getColor("Separator.foreground");
            g.setColor(line != null ? line : new Color(190, 190, 190));
            g.fillRect(x, y, 1, height);
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return new Insets(0, 1, 0, 0);
        }
    }
}