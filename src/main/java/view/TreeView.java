package view;

import javax.swing.*;
import java.awt.*;

/**
 * Bestehende Tree- und Textansicht. Die vorhandenen Panels werden nur
 * zusammengesetzt und nicht neu implementiert.
 */
public class TreeView extends JPanel {

    public TreeView(OutlinePanel outlinePanel, TextWindow textWindow) {
        super(new BorderLayout());

        JSplitPane splitPane = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT,
                outlinePanel,
                textWindow
        );

        splitPane.setResizeWeight(0.6);
        splitPane.setDividerSize(5);
        splitPane.setDividerLocation(380);
        splitPane.setBorder(null);

        add(splitPane, BorderLayout.CENTER);
    }
}
