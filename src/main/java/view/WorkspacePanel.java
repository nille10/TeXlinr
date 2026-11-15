package view;

import javax.swing.*;
import java.awt.*;

/**
 * Enthält die vier Arbeitsbereiche und schaltet sie per CardLayout um.
 * Die Panels werden nur einmal erzeugt und bleiben beim Wechsel erhalten.
 */
public class WorkspacePanel extends JPanel {

    private final CardLayout cardLayout;

    public WorkspacePanel(
            TreeView treeView,
            ImageManagementPanel imageManagementPanel,
            FormalitiesPanel formalitiesPanel,
            SourcePanel sourcePanel) {

        cardLayout = new CardLayout();
        setLayout(cardLayout);

        add(treeView, NavigationSidebar.TREE);
        add(imageManagementPanel, NavigationSidebar.IMAGES);
        add(formalitiesPanel, NavigationSidebar.FORMALITIES);
        add(sourcePanel, NavigationSidebar.SOURCES);

        showView(NavigationSidebar.TREE);
    }

    public void showView(String viewKey) {
        cardLayout.show(this, viewKey);
    }
}