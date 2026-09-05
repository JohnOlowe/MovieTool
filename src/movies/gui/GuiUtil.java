package movies.gui;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Component;
import java.awt.FileDialog;
import java.awt.Frame;
import java.io.File;
import java.util.prefs.Preferences;

/**
 * Shared GUI plumbing: file choosers that remember the last folder, drop
 * support for dragging files onto path fields and small message helpers.
 */
final class GuiUtil {

    private static final Preferences PREFS = Preferences.userNodeForPackage(GuiUtil.class);
    private static final String LAST_DIR = "lastDir";

    private GuiUtil() {
    }

    /** Lets the user pick a folder; writes the choice into the field. */
    static void pickDirectory(Component parent, javax.swing.JTextField field) {
        JFileChooser chooser = chooser(lastDirectory(), true, null);
        if (choose(parent, chooser, field, true)) remember(chooser);
    }

    /** Lets the user pick an existing file. */
    static void pickFile(Component parent, javax.swing.JTextField field, String description, String... extensions) {
        FileNameExtensionFilter filter = extensions.length == 0 ? null
                : new FileNameExtensionFilter(description, extensions);
        JFileChooser chooser = chooser(lastDirectory(), false, filter);
        if (choose(parent, chooser, field, false)) remember(chooser);
    }

    /** Lets the user pick an output file that may not exist yet. */
    static void saveFile(Component parent, javax.swing.JTextField field, String description, String... extensions) {
        FileNameExtensionFilter filter = extensions.length == 0 ? null
                : new FileNameExtensionFilter(description, extensions);
        JFileChooser chooser = chooser(lastDirectory(), false, filter);
        chooser.setDialogType(javax.swing.JFileChooser.SAVE_DIALOG);
        if (choose(parent, chooser, field, false)) remember(chooser);
    }

    private static JFileChooser chooser(File dir, boolean directoriesOnly, FileNameExtensionFilter filter) {
        JFileChooser chooser = new JFileChooser(dir);
        chooser.setFileSelectionMode(directoriesOnly ? javax.swing.JFileChooser.DIRECTORIES_ONLY
                : javax.swing.JFileChooser.FILES_ONLY);
        if (filter != null) chooser.setFileFilter(filter);
        return chooser;
    }

    private static boolean choose(Component parent, JFileChooser chooser, javax.swing.JTextField field,
                                  boolean directories) {
        while (true) {
            int choice = chooser.showOpenDialog(parent);
            if (choice != JFileChooser.APPROVE_OPTION) return false;
            File selected = chooser.getSelectedFile();
            if (selected == null) return false;
            if (directories && !selected.isDirectory()) {
                JOptionPane.showMessageDialog(parent, "Please choose a folder.", "MovieTool",
                        JOptionPane.INFORMATION_MESSAGE);
                continue;
            }
            field.setText(selected.getAbsolutePath());
            return true;
        }
    }

    private static File lastDirectory() {
        String path = PREFS.get(LAST_DIR, null);
        return path == null ? null : new File(path);
    }

    private static void remember(JFileChooser chooser) {
        File dir = chooser.getCurrentDirectory();
        if (dir != null) PREFS.put(LAST_DIR, dir.getAbsolutePath());
    }

    /** Enables dragging files or folders from the OS shell onto a path field. */
    static void enableFileDrop(final javax.swing.JTextField field) {
        field.setTransferHandler(new javax.swing.TransferHandler() {
            @Override
            public boolean canImport(javax.swing.TransferHandler.TransferSupport support) {
                return support.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
            }

            @Override
            @SuppressWarnings("unchecked")
            public boolean importData(javax.swing.TransferHandler.TransferSupport support) {
                try {
                    java.util.List<File> files = (java.util.List<File>) support.getTransferable()
                            .getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
                    if (files == null || files.isEmpty()) return false;
                    field.setText(files.get(0).getAbsolutePath());
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
        });
    }

    static void error(Component parent, String message) {
        JOptionPane.showMessageDialog(parent, message, "MovieTool", JOptionPane.ERROR_MESSAGE);
    }

    static void info(Component parent, String message) {
        JOptionPane.showMessageDialog(parent, message, "MovieTool", JOptionPane.INFORMATION_MESSAGE);
    }

    /** Text for the row of a browse button. */
    static javax.swing.JButton browseButton(final Component parent, final javax.swing.JTextField field,
                                            final boolean directory) {
        javax.swing.JButton button = new javax.swing.JButton("Browse...");
        button.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                if (directory) pickDirectory(parent, field);
                else pickFile(parent, field, "Media and subtitles", "mp4", "mkv", "avi", "mov", "webm", "m4v",
                        "ts", "srt", "vtt", "ass", "ssa", "sub");
            }
        });
        return button;
    }
}
