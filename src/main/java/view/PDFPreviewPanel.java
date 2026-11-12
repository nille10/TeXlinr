/*
KI generiert
 */
package view;

import javax.swing.*;
import javax.swing.border.AbstractBorder;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;

/**
 * Dauerhaft sichtbare PDF-Vorschau (aktuell noch ein leeres A4-Blatt).
 *
 * <p>Die Zoom-Steuerung schwebt als kleine Leiste unten rechts über dem Dokument,
 * es gibt keinen eigenen Header mehr. Bedienung:</p>
 * <ul>
 *   <li><b>Maus:</b> Strg + Mausrad zoomt zum Mauszeiger hin, Mausrad scrollt,
 *       Shift + Mausrad scrollt horizontal, Ziehen mit gedrückter Maustaste
 *       verschiebt die Seite, Doppelklick passt die Seite an die Breite an.</li>
 *   <li><b>Tastatur:</b> Strg + / Strg − (auch Ziffernblock) zoomen in Stufen,
 *       Strg + 0 passt an die Breite an, Strg + 1 setzt auf 100 %.
 *       Pfeiltasten, Bild auf/ab, Pos1/Ende scrollen wie gewohnt.</li>
 * </ul>
 *
 * <p>Solange der Nutzer nicht manuell gezoomt hat, folgt die Seite automatisch der
 * Panelbreite ("Breite anpassen").</p>
 */
public class PDFPreviewPanel extends JPanel {

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

    private double zoom = 0.48;
    /** Solange true, wird der Zoom bei jeder Größenänderung an die Breite angepasst. */
    private boolean fitWidth = true;

    // Reihenfolge wichtig: zoomValueButton muss vor controls existieren,
    // weil der FloatingControls-Konstruktor ihn verwendet.
    private final JButton zoomValueButton = new JButton();
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

        LayeredHost host = new LayeredHost();
        host.add(scrollPane, JLayeredPane.DEFAULT_LAYER);
        host.add(controls, JLayeredPane.PALETTE_LAYER);
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

    // ── Zoom-Logik ───────────────────────────────────────────────────────────

    private void applyFit() {
        int width = scrollPane.getViewport().getWidth();
        if (width <= 0) return;
        double fit = (width - PAGE_MARGIN * 2) / A4_WIDTH_PX;
        // Oberkante bleibt stehen, horizontal wird zur Mitte hin gezoomt.
        zoomTo(fit, new Point(width / 2, 0));
    }

    /**
     * Setzt den Zoom und hält dabei den Seitenpunkt unter {@code viewportAnchor}
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

        // Anchor als Bruchteil der Seite merken, damit er nach dem Zoom wiedergefunden wird.
        Rectangle before = pageCanvas.pageRect();
        double fx = (view.x + anchor.x - before.x) / (double) before.width;
        double fy = (view.y + anchor.y - before.y) / (double) before.height;

        zoom = newZoom;
        updateZoomLabel();
        pageCanvas.revalidate();
        scrollPane.validate();

        Rectangle after = pageCanvas.pageRect();
        setViewPosition(
                (int) Math.round(after.x + fx * after.width - anchor.x),
                (int) Math.round(after.y + fy * after.height - anchor.y));
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

    /** Legt ScrollPane vollflächig und die Zoom-Leiste unten rechts darüber. */
    private final class LayeredHost extends JLayeredPane {

        private static final int GAP = 14;

        @Override
        public void doLayout() {
            scrollPane.setBounds(0, 0, getWidth(), getHeight());

            JScrollBar vsb = scrollPane.getVerticalScrollBar();
            JScrollBar hsb = scrollPane.getHorizontalScrollBar();
            int right  = GAP + (vsb.isVisible() ? vsb.getWidth() : 0);
            int bottom = GAP + (hsb.isVisible() ? hsb.getHeight() : 0);

            Dimension d = controls.getPreferredSize();
            controls.setBounds(getWidth() - d.width - right, getHeight() - d.height - bottom, d.width, d.height);
        }
    }

    // ── Seite ────────────────────────────────────────────────────────────────

    /**
     * Zeichnet die leere A4-Seite. Später kann hier die gerenderte PDF-Seite
     * anstelle des weißen Platzhalters dargestellt werden.
     */
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

        /** Position und Größe der Seite in Canvas-Koordinaten (zentriert, mit Mindestrand). */
        Rectangle pageRect() {
            int pw = (int) Math.round(A4_WIDTH_PX * zoom);
            int ph = (int) Math.round(A4_HEIGHT_PX * zoom);
            int x = Math.max(PAGE_MARGIN, (getWidth() - pw) / 2);
            int y = Math.max(PAGE_MARGIN, (getHeight() - ph) / 2);
            return new Rectangle(x, y, pw, ph);
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(
                    (int) Math.ceil(A4_WIDTH_PX * zoom) + PAGE_MARGIN * 2,
                    (int) Math.ceil(A4_HEIGHT_PX * zoom) + PAGE_MARGIN * 2);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                g2.setColor(AppTheme.isDark() ? new Color(46, 48, 52) : new Color(105, 108, 113));
                g2.fillRect(0, 0, getWidth(), getHeight());

                Rectangle r = pageRect();

                // Dezenter Seitenschatten
                g2.setColor(new Color(0, 0, 0, 90));
                g2.fillRoundRect(r.x + 4, r.y + 5, r.width, r.height, 2, 2);

                // Leeres weißes DIN-A4-Blatt
                g2.setColor(Color.WHITE);
                g2.fillRect(r.x, r.y, r.width, r.height);
                g2.setColor(new Color(215, 215, 215));
                g2.drawRect(r.x, r.y, r.width, r.height);
            } finally {
                g2.dispose();
            }
        }

        // Scrollable: Canvas füllt den Viewport, solange die Seite hineinpasst.

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