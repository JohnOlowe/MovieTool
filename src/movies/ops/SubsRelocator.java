package movies.ops;

import movies.core.OperationResult;
import movies.core.Options;
import movies.core.Problem;
import movies.core.TransferAction;
import movies.util.IoUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Moves subtitle files that sit next to the videos (the old layout) into the
 * folder's {@code Subtitles} sub-folder - the layout every MovieTool
 * operation expects. Files already inside {@code Subtitles} are never
 * touched, and the folder itself is never scanned into itself.
 *
 * <p>This is a pure layout migration: names are preserved, nothing is parsed
 * or renamed (that is the rename/sync operations' job). Existing files in
 * {@code Subtitles} are skipped unless overwriting is requested.</p>
 */
public class SubsRelocator {

    public static final String SUBTITLE_FOLDER = "Subtitles";

    private static final String[] SUBTITLE_EXTENSIONS = { ".srt", ".vtt", ".ass", ".ssa", ".sub" };

    /** Builds the move plan without touching the disk. */
    public OperationResult plan(Options options) {
        OperationResult result = new OperationResult();

        File root = new File(options.getFolder());
        if (!root.isDirectory()) {
            result.add(Problem.error("Not a folder: " + options.getFolder()));
            return result;
        }

        File subsDir = new File(root, SUBTITLE_FOLDER);
        List<File> files = new ArrayList<File>();
        collect(root, options.isRecursive(), files);

        List<TransferAction> transfers = new ArrayList<TransferAction>();
        int planned = 0;
        for (File file : files) {
            File target = new File(subsDir, file.getName());
            TransferAction action = new TransferAction(TransferAction.Kind.MOVE, file, target);
            if (target.exists() && !options.isOverwrite()) {
                action.state = TransferAction.State.SKIPPED_EXISTS;
                action.note = "already in " + SUBTITLE_FOLDER;
                result.add(Problem.warn("Not moving " + file.getName() + ": " + SUBTITLE_FOLDER
                        + "/" + file.getName() + " already exists (use overwrite to replace)"));
            } else {
                action.note = options.isOverwrite() && target.exists() ? "replaces existing file" : "";
                planned++;
            }
            transfers.add(action);
        }

        result.setTransfers(transfers);
        String where = subsDir.getPath();
        if (planned == 0 && transfers.isEmpty()) {
            result.setReport("No subtitle files found in " + root.getName() + " - nothing to move.");
        } else {
            result.setReport(planned + " subtitle file(s) to move into " + where
                    + (transfers.size() > planned ? " (" + (transfers.size() - planned) + " skipped, already there)" : ""));
        }
        return result;
    }

    /** Executes a plan produced by {@link #plan}. */
    public void apply(OperationResult result, Options options) {
        for (TransferAction transfer : result.getTransfers()) {
            if (transfer.state != TransferAction.State.PLANNED) continue;
            try {
                IoUtil.mkdirs(transfer.to.getParentFile());
                if (options.isOverwrite()) {
                    Files.move(transfer.from.toPath(), transfer.to.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(transfer.from.toPath(), transfer.to.toPath());
                }
                transfer.state = TransferAction.State.DONE;
            } catch (IOException e) {
                transfer.state = TransferAction.State.FAILED;
                result.add(Problem.error("Failed: " + transfer.from.getName() + " -> "
                        + transfer.to.getName() + ": " + e.getMessage()));
            }
        }
    }

    // ------------------------------------------------------------- helpers

    private static boolean isSubtitle(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String extension : SUBTITLE_EXTENSIONS) {
            if (lower.endsWith(extension)) return true;
        }
        return false;
    }

    private static boolean isSubtitleFolder(File dir) {
        return SUBTITLE_FOLDER.equalsIgnoreCase(dir.getName());
    }

    /**
     * Collects subtitle files from {@code dir} (top level always; sub-folders
     * only when recursive). Any folder called "Subtitles" is skipped, so
     * files already migrated are never picked up again.
     */
    private void collect(File dir, boolean recursive, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        List<File> subDirs = new ArrayList<File>();
        for (File child : children) {
            if (child.isDirectory()) {
                if (!isSubtitleFolder(child)) subDirs.add(child);
            } else if (isSubtitle(child.getName())) {
                out.add(child);
            }
        }
        if (recursive) {
            for (File subDir : subDirs) collect(subDir, true, out);
        }
    }
}
