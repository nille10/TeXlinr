/*
KI generiert
 */

package view;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Dauerhafte linke Navigationsleiste für die Arbeitsbereiche.
 *
 * <p>Alle Farben werden zur Zeichenzeit aus {@link AppTheme} und dem
 * {@link UIManager} gelesen. Dadurch folgt die Leiste automatisch dem
 * Hell-/Dunkel-Umschalter und jeder Änderung der Akzentfarben, ohne dass
 * nach einem Theme-Wechsel etwas neu gesetzt werden muss.</p>
 *
 * <p>Die Bereiche lassen sich per Mausklick oder mit Strg+1 bis Strg+4
 * (unter macOS Cmd+1 bis Cmd+4) auswählen.</p>
 */
public class NavigationSidebar extends JPanel {

    public static final String TREE = "tree";
    public static final String IMAGES = "images";
    public static final String FORMALITIES = "formalities";
    public static final String SOURCES = "sources";

    private static final int SIDEBAR_WIDTH = 96;
    private static final int BUTTON_WIDTH = 82;
    private static final int BUTTON_HEIGHT = 68;
    private static final int[] SHORTCUT_KEYS = {KeyEvent.VK_1, KeyEvent.VK_2, KeyEvent.VK_3, KeyEvent.VK_4};

    private final Map<String, SidebarButton> buttons = new LinkedHashMap<>();
    private final Consumer<String> onViewSelected;
    private String selectedView = TREE;

    public NavigationSidebar(Consumer<String> onViewSelected) {
        this.onViewSelected = onViewSelected;

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        // rechts 1 px mehr, dort wird die Trennlinie gezeichnet
        setBorder(new EmptyBorder(8, 6, 8, 7));
        setPreferredSize(new Dimension(SIDEBAR_WIDTH, 0));
        setOpaque(false); // Hintergrund wird in paintComponent themeabhängig gezeichnet

        addItem(TREE, "⌘", "Tree");
        add(Box.createVerticalStrut(8));
        addItem(IMAGES, "▧", "Bilder");
        add(Box.createVerticalStrut(8));
        addItem(FORMALITIES, "☷", "Formalitäten");
        add(Box.createVerticalStrut(8));
        addItem(SOURCES, "❝", "Quellen");
        add(Box.createVerticalGlue());

        selectView(TREE);
    }

    // ── Aufbau ───────────────────────────────────────────────────────────────

    private void addItem(String key, String symbol, String label) {
        int mask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        int index = buttons.size();

        String tooltip = label;
        if (index < SHORTCUT_KEYS.length) {
            tooltip += " (" + KeyEvent.getModifiersExText(mask) + "+" + (index + 1) + ")";
            installShortcut(key, KeyStroke.getKeyStroke(SHORTCUT_KEYS[index], mask));
        }

        SidebarButton button = new SidebarButton(symbol, label);
        button.setToolTipText(tooltip);
        button.addActionListener(e -> select(key, true));

        buttons.put(key, button);
        add(button);
    }

    private void installShortcut(String key, KeyStroke keyStroke) {
        String actionKey = "navigate." + key;
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(keyStroke, actionKey);
        getActionMap().put(actionKey, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                select(key, true);
            }
        });
    }

    // ── Auswahl ──────────────────────────────────────────────────────────────

    /**
     * Markiert einen Bereich als aktiv, ohne den Callback auszulösen
     * (z. B. um die Leiste von außen zu synchronisieren).
     */
    public void selectView(String key) {
        select(key, false);
    }

    public String getSelectedView() {
        return selectedView;
    }

    private void select(String key, boolean notify) {
        if (!buttons.containsKey(key)) return;
        selectedView = key;
        buttons.forEach((k, b) -> b.setActive(k.equals(key)));
        if (notify && onViewSelected != null) {
            onViewSelected.accept(key);
        }
    }

    // ── Zeichnen (Farben aus AppTheme / UIManager) ───────────────────────────

    @Override
    protected void paintComponent(Graphics g) {
        g.setColor(background());
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setColor(themeColor("Separator.foreground", AppTheme.isDark()
                ? new Color(0x55, 0x55, 0x58) : new Color(0xD0, 0xD0, 0xD5)));
        g.fillRect(getWidth() - 1, 0, 1, getHeight());
        super.paintComponent(g);
    }

    /** Hintergrund der Leiste: gleiche Farbe wie die Titelleiste aus {@link AppTheme}. */
    private static Color background() {
        return themeColor("TitlePane.background", AppTheme.isDark()
                ? new Color(0x2B, 0x2B, 0x2E) : new Color(0xF5, 0xF5, 0xF8));
    }

    private static Color themeColor(String key, Color fallback) {
        Color c = UIManager.getColor(key);
        return c != null ? c : fallback;
    }

    // ── Eigener Button ───────────────────────────────────────────────────────

    /**
     * Button, der Symbol und Beschriftung selbst zeichnet. Kein HTML im Button-Text,
     * denn dessen Textfarbe würde sich bei einem Theme-Wechsel nicht mitändern.
     */
    private static class SidebarButton extends JButton {

        private final String symbol;
        private final String label;
        private boolean active;

        SidebarButton(String symbol, String label) {
            this.symbol = symbol;
            this.label = label;

            setAlignmentX(Component.CENTER_ALIGNMENT);
            Dimension size = new Dimension(BUTTON_WIDTH, BUTTON_HEIGHT);
            setPreferredSize(size);
            setMinimumSize(size);
            setMaximumSize(size);

            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setRequestFocusEnabled(false); // Mausklick setzt keinen Fokus, Tab-Taste schon
            getAccessibleContext().setAccessibleName(label);
        }

        void setActive(boolean active) {
            if (this.active != active) {
                this.active = active;
                repaint();
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                int w = getWidth();
                int h = getHeight();
                int arc = UIManager.getInt("Button.arc");

                boolean pressed = getModel().isPressed() && getModel().isArmed();
                boolean hover = getModel().isRollover();

                Color bg;
                Color fg;
                if (pressed) {
                    bg = AppTheme.ACCENT_DARK;
                    fg = Color.WHITE;
                } else if (active) {
                    bg = AppTheme.ACCENT;
                    fg = Color.WHITE;
                } else if (hover) {
                    bg = AppTheme.hoverBackground();
                    fg = AppTheme.hoverForeground();
                } else {
                    bg = null;
                    fg = themeColor("Label.foreground", AppTheme.isDark() ? Color.WHITE : new Color(0x1E, 0x1E, 0x1E));
                }

                if (bg != null) {
                    g2.setColor(bg);
                    g2.fillRoundRect(0, 0, w, h, arc, arc);
                }

                paintContent(g2, w, h, fg);

                if (isFocusOwner()) { // Tastaturfokus sichtbar machen
                    g2.setColor(AppTheme.ACCENT_LIGHT);
                    g2.setStroke(new BasicStroke(2f));
                    g2.drawRoundRect(1, 1, w - 3, h - 3, arc, arc);
                }
            } finally {
                g2.dispose();
            }
        }

        private void paintContent(Graphics2D g2, int w, int h, Color fg) {
            Font base = getFont();
            Font symbolFont = base.deriveFont(Font.PLAIN, base.getSize2D() * 1.7f);

            // Beschriftung bei Bedarf verkleinern, damit sie nie abgeschnitten wird
            float labelSize = base.getSize2D() * 0.9f;
            Font labelFont = base.deriveFont(Font.PLAIN, labelSize);
            FontMetrics fmLabel = g2.getFontMetrics(labelFont);
            while (fmLabel.stringWidth(label) > w - 6 && labelSize > 8f) {
                labelSize -= 0.5f;
                labelFont = base.deriveFont(Font.PLAIN, labelSize);
                fmLabel = g2.getFontMetrics(labelFont);
            }
            FontMetrics fmSymbol = g2.getFontMetrics(symbolFont);

            int gap = 2;
            int total = fmSymbol.getHeight() + gap + fmLabel.getHeight();
            int top = (h - total) / 2;

            g2.setColor(fg);
            g2.setFont(symbolFont);
            g2.drawString(symbol, (w - fmSymbol.stringWidth(symbol)) / 2, top + fmSymbol.getAscent());

            g2.setFont(labelFont);
            g2.drawString(label, (w - fmLabel.stringWidth(label)) / 2,
                    top + fmSymbol.getHeight() + gap + fmLabel.getAscent());
        }
    }
}