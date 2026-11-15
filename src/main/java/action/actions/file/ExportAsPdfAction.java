package action.actions.file;

import controller.FileController;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

/**
 * „Export als PDF": baut das Dokument und speichert das PDF an einem gewählten Ort.
 *
 * @see FileController#exportAsPdf()
 */
public class ExportAsPdfAction extends AbstractAction {

    private final FileController fileController;

    public ExportAsPdfAction(FileController fileController) {
        super("Export als PDF");
        this.fileController = fileController;

        putValue(SHORT_DESCRIPTION, "Dokument bauen und als PDF speichern");
        putValue(ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_E, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        fileController.exportAsPdf();
    }
}