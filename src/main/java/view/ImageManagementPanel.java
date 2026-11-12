package view;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Einfache Bilderverwaltung mit Drag & Drop, Hinzufügen, Beschriften,
 * Umbenennen und Entfernen.
 */
public class ImageManagementPanel extends JPanel {

    private final DefaultListModel<ImageItem> listModel = new DefaultListModel<>();
    private final JList<ImageItem> imageList = new JList<>(listModel);
    private final JLabel preview = new JLabel("Bild auswählen oder hierher ziehen", SwingConstants.CENTER);

    public ImageManagementPanel() {
        super(new BorderLayout(10, 10));
        setBorder(new EmptyBorder(12, 12, 12, 12));

        JLabel heading = new JLabel("Bilderverwaltung");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 18f));
        add(heading, BorderLayout.NORTH);

        imageList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        imageList.setCellRenderer(new ImageItemRenderer());
        imageList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updatePreview();
            }
        });

        JScrollPane listScroll = new JScrollPane(imageList);
        listScroll.setPreferredSize(new Dimension(280, 300));

        preview.setBorder(BorderFactory.createLineBorder(new Color(190, 190, 190)));
        preview.setPreferredSize(new Dimension(400, 300));

        JPanel content = new JPanel(new BorderLayout(10, 10));
        content.add(listScroll, BorderLayout.WEST);
        content.add(preview, BorderLayout.CENTER);
        add(content, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton addButton = new JButton("Bilder hinzufügen");
        JButton captionButton = new JButton("Beschriftung ändern");
        JButton renameButton = new JButton("Datei umbenennen");
        JButton removeButton = new JButton("Entfernen");

        addButton.addActionListener(e -> chooseImages());
        captionButton.addActionListener(e -> changeCaption());
        renameButton.addActionListener(e -> renameFile());
        removeButton.addActionListener(e -> removeSelected());

        actions.add(addButton);
        actions.add(captionButton);
        actions.add(renameButton);
        actions.add(removeButton);
        add(actions, BorderLayout.SOUTH);

        installDragAndDrop();
    }

    private void chooseImages() {
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "Bilddateien", "png", "jpg", "jpeg", "gif", "bmp", "webp"));

        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            for (File file : chooser.getSelectedFiles()) {
                addImage(file);
            }
        }
    }

    private void installDragAndDrop() {
        new DropTarget(this, DnDConstants.ACTION_COPY, new DropTargetAdapter() {
            @Override
            public void drop(DropTargetDropEvent event) {
                try {
                    if (!event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        event.rejectDrop();
                        return;
                    }

                    event.acceptDrop(DnDConstants.ACTION_COPY);
                    Transferable transferable = event.getTransferable();
                    @SuppressWarnings("unchecked")
                    List<File> droppedFiles = (List<File>) transferable
                            .getTransferData(DataFlavor.javaFileListFlavor);

                    for (File file : droppedFiles) {
                        addImage(file);
                    }
                    event.dropComplete(true);
                } catch (Exception ex) {
                    event.dropComplete(false);
                    JOptionPane.showMessageDialog(
                            ImageManagementPanel.this,
                            "Die Dateien konnten nicht übernommen werden:\n" + ex.getMessage(),
                            "Drag & Drop", JOptionPane.ERROR_MESSAGE);
                }
            }
        }, true);

        // Auch direkt auf der Liste und der Vorschau ablegen können.
        DropTargetAdapter adapter = new DropTargetAdapter() {
            @Override
            public void drop(DropTargetDropEvent event) {
                try {
                    if (!event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        event.rejectDrop();
                        return;
                    }
                    event.acceptDrop(DnDConstants.ACTION_COPY);
                    @SuppressWarnings("unchecked")
                    List<File> dropped = (List<File>) event.getTransferable()
                            .getTransferData(DataFlavor.javaFileListFlavor);
                    for (File file : dropped) addImage(file);
                    event.dropComplete(true);
                } catch (Exception ex) {
                    event.dropComplete(false);
                }
            }
        };
        new DropTarget(imageList, DnDConstants.ACTION_COPY, adapter, true);
        new DropTarget(preview, DnDConstants.ACTION_COPY, adapter, true);
    }

    private void addImage(File file) {
        if (file == null || !file.isFile() || !isImageFile(file)) {
            return;
        }

        for (int i = 0; i < listModel.size(); i++) {
            if (listModel.get(i).file.equals(file)) return;
        }

        listModel.addElement(new ImageItem(file, ""));
    }

    private boolean isImageFile(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".png") || name.endsWith(".jpg")
                || name.endsWith(".jpeg") || name.endsWith(".gif")
                || name.endsWith(".bmp") || name.endsWith(".webp");
    }

    private void changeCaption() {
        ImageItem item = imageList.getSelectedValue();
        if (item == null) return;

        String value = JOptionPane.showInputDialog(
                this, "Beschriftung:", item.caption);
        if (value != null) {
            item.caption = value.trim();
            imageList.repaint();
        }
    }

    private void renameFile() {
        ImageItem item = imageList.getSelectedValue();
        if (item == null) return;

        String currentName = item.file.getName();
        String newName = JOptionPane.showInputDialog(
                this, "Neuer Dateiname (mit Dateiendung):", currentName);

        if (newName == null || newName.trim().isEmpty()
                || newName.equals(currentName)) return;

        try {
            Path source = item.file.toPath();
            Path target = source.resolveSibling(newName.trim());
            if (Files.exists(target)) {
                JOptionPane.showMessageDialog(this, "Eine Datei mit diesem Namen existiert bereits.");
                return;
            }

            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            item.file = target.toFile();
            imageList.repaint();
            updatePreview();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    "Datei konnte nicht umbenannt werden:\n" + ex.getMessage(),
                    "Fehler", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void removeSelected() {
        int index = imageList.getSelectedIndex();
        if (index >= 0) {
            listModel.remove(index);
            preview.setIcon(null);
            preview.setText("Bild auswählen oder hierher ziehen");
        }
    }

    private void updatePreview() {
        ImageItem item = imageList.getSelectedValue();
        if (item == null) {
            preview.setIcon(null);
            preview.setText("Bild auswählen oder hierher ziehen");
            return;
        }

        ImageIcon original = new ImageIcon(item.file.getAbsolutePath());
        if (original.getIconWidth() <= 0) {
            preview.setIcon(null);
            preview.setText("Bild kann nicht angezeigt werden");
            return;
        }

        int maxWidth = Math.max(100, preview.getWidth() - 30);
        int maxHeight = Math.max(100, preview.getHeight() - 30);
        double scale = Math.min((double) maxWidth / original.getIconWidth(),
                (double) maxHeight / original.getIconHeight());
        scale = Math.min(scale, 1.0);

        Image scaled = original.getImage().getScaledInstance(
                (int) (original.getIconWidth() * scale),
                (int) (original.getIconHeight() * scale),
                Image.SCALE_SMOOTH);

        preview.setText(item.caption.isEmpty() ? item.file.getName() : item.caption);
        preview.setHorizontalTextPosition(SwingConstants.CENTER);
        preview.setVerticalTextPosition(SwingConstants.BOTTOM);
        preview.setIcon(new ImageIcon(scaled));
    }

    private static class ImageItem {
        private File file;
        private String caption;

        private ImageItem(File file, String caption) {
            this.file = file;
            this.caption = caption;
        }

        @Override
        public String toString() {
            return caption.isEmpty() ? file.getName() : caption + "  (" + file.getName() + ")";
        }
    }

    private static class ImageItemRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(
                JList<?> list, Object value, int index,
                boolean isSelected, boolean cellHasFocus) {

            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            ImageItem item = (ImageItem) value;
            setText(item.caption.isEmpty()
                    ? item.file.getName()
                    : item.caption + "  (" + item.file.getName() + ")");
            setIcon(new ImageIcon(new ImageIcon(item.file.getAbsolutePath())
                    .getImage().getScaledInstance(32, 32, Image.SCALE_SMOOTH)));
            setBorder(new EmptyBorder(4, 4, 4, 4));
            return this;
        }
    }
}
