package view;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * Oberfläche für Dokument-Formalitäten.
 *
 * Hinweis: Diese Eingabefelder bilden zunächst die Benutzeroberfläche.
 * Die Werte sind noch nicht mit einem Export- oder Dokumentmodell verbunden.
 */
public class FormalitiesPanel extends JPanel {

    private final JSpinner pageWidth;
    private final JComboBox<String> pageUnit;
    private final JComboBox<String> pageBreakMode;
    private final JCheckBox coverPage;
    private final JCheckBox showPageNumbers;

    public FormalitiesPanel() {
        super(new BorderLayout(10, 10));
        setBorder(new EmptyBorder(16, 18, 16, 18));

        JLabel heading = new JLabel("Formalitäten");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 18f));
        add(heading, BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(new EmptyBorder(16, 4, 4, 4));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(7, 6, 7, 12);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        pageWidth = new JSpinner(new SpinnerNumberModel(210, 50, 500, 1));
        pageUnit = new JComboBox<>(new String[]{"mm", "cm", "inch"});
        pageBreakMode = new JComboBox<>(new String[]{
                "Automatisch", "Vor jedem Haupteintrag", "Manuell"
        });
        coverPage = new JCheckBox("Deckblatt verwenden");
        showPageNumbers = new JCheckBox("Seitenzahlen anzeigen", true);

        int row = 0;
        addRow(form, c, row++, "Seitenbreite:", pageWidth, pageUnit);
        addRow(form, c, row++, "Seitenumbrüche:", pageBreakMode);
        addRow(form, c, row++, "Deckblatt:", coverPage);
        addRow(form, c, row++, "Seitennummerierung:", showPageNumbers);

        GridBagConstraints filler = (GridBagConstraints) c.clone();
        filler.gridx = 0;
        filler.gridy = row;
        filler.weighty = 1;
        form.add(Box.createVerticalGlue(), filler);

        add(form, BorderLayout.CENTER);

        JLabel info = new JLabel(
                "Die Einstellungen werden in einem späteren Schritt mit dem Dokumentexport verbunden.");
        info.setForeground(Color.GRAY);
        add(info, BorderLayout.SOUTH);
    }

    private void addRow(JPanel panel, GridBagConstraints c, int row,
                        String label, JComponent... components) {
        c.gridy = row;
        c.gridx = 0;
        c.weightx = 0;
        panel.add(new JLabel(label), c);

        c.gridx = 1;
        c.weightx = 1;
        JPanel valuePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        for (JComponent component : components) valuePanel.add(component);
        panel.add(valuePanel, c);
    }

    public int getPageWidth() {
        return ((Number) pageWidth.getValue()).intValue();
    }

    public String getPageUnit() {
        return (String) pageUnit.getSelectedItem();
    }

    public String getPageBreakMode() {
        return (String) pageBreakMode.getSelectedItem();
    }

    public boolean isCoverPageEnabled() {
        return coverPage.isSelected();
    }

    public boolean isPageNumbersEnabled() {
        return showPageNumbers.isSelected();
    }
}