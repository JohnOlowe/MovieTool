package movies.ops;

import movies.core.OperationResult;
import movies.core.Options;
import movies.core.Problem;
import movies.core.RenameEngine;
import movies.core.TransferAction;
import movies.util.IoUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pulls media files out of nested folders into one folder, renaming on
 * collision ("name (2).ext") and cleaning up emptied directories.
 */
public class Flattener {

    /** Builds the move plan without touching the disk. */
    public OperationResult plan(Options options) {
        OperationResult result = new OperationResult();
        File root = new File(options.getFolder());
        if (!root.isDirectory()) {
            result.add(Problem.error("Not a folder: " + options.getFolder()));
            return result;
        }
        File target = options.getOutput().trim().isEmpty() ? root : new File(options.getOutput().trim());
        if (!target.isDirectory()) {
            result.add(Problem.error("Target folder does not exist: " + target));
            return result;
        }

        List<File> files = new ArrayList<File>();
        collect(root, files, 0);

        List<TransferAction> transfers = new ArrayList<TransferAction>();
        Set<String> takenNames = new HashSet<String>();
        // Existing files in the target folder claim their names first.
        File[] existing = target.listFiles();
        if (existing != null) {
            for (File file : existing) takenNames.add(file.getName().toLowerCase());
        }

        for (File file : files) {
            if (file.getParentFile().equals(target)) continue; // already flat
            String name = uniqueName(file.getName(), takenNames);
            File destination = new File(target, name);
            takenNames.add(name.toLowerCase());
            if (destination.exists() && !options.isOverwrite()) {
                TransferAction skipped = new TransferAction(TransferAction.Kind.MOVE, file, destination);
                skipped.state = TransferAction.State.SKIPPED_EXISTS;
                transfers.add(skipped);
                continue;
            }
            transfers.add(new TransferAction(TransferAction.Kind.MOVE, file, destination));
        }
        result.setTransfers(transfers);
        int movable = 0;
        for (TransferAction transfer : transfers) {
            if (transfer.state == TransferAction.State.PLANNED) movable++;
        }
        result.setReport(transfers.size() + " file(s) outside " + target.getName() + ", " + movable + " planned to move");
        return result;
    }

    /** Executes a plan produced by {@link #plan}. */
    public void apply(OperationResult result, Options options) {
        List<File> touchedDirs = new ArrayList<File>();
        for (TransferAction transfer : result.getTransfers()) {
            if (transfer.state != TransferAction.State.PLANNED) continue;
            try {
                IoUtil.mkdirs(transfer.to.getParentFile());
                Files.move(transfer.from.toPath(), transfer.to.toPath());
                transfer.state = TransferAction.State.DONE;
                File dir = transfer.from.getParentFile();
                if (!touchedDirs.contains(dir)) touchedDirs.add(dir);
            } catch (IOException e) {
                transfer.state = TransferAction.State.FAILED;
                result.add(Problem.error("Failed to move " + transfer.from.getName() + ": " + e.getMessage()));
            }
        }
        if (!touchedDirs.isEmpty()) {
            tidyEmptiedFolders(touchedDirs, result);
        }
    }

    /** Deletes directories the flattener emptied (never the root itself). */
    private void tidyEmptiedFolders(List<File> dirs, OperationResult result) {
        for (File dir : dirs) {
            File current = dir;
            while (current != null && current.listFiles() != null && current.listFiles().length == 0) {
                if (current.delete()) {
                    result.add(Problem.info("Removed empty folder: " + current.getName()));
                    current = current.getParentFile();
                } else {
                    break;
                }
            }
        }
    }

    private String uniqueName(String name, Set<String> takenNames) {
        if (!takenNames.contains(name.toLowerCase())) return name;
        String base = IoUtil.baseName(name);
        String ext = IoUtil.extensionOf(name);
        for (int i = 2; i < 1000; i++) {
            String candidate = base + " (" + i + ")" + ext;
            if (!takenNames.contains(candidate.toLowerCase())) return candidate;
        }
        return name;
    }

    private static void collect(File root, List<File> into, int depth) {
        File[] children = root.listFiles();
        if (children == null || depth > 16) return;
        for (File child : children) {
            if (child.isDirectory()) collect(child, into, depth + 1);
            else if (RenameEngine.isMediaFile(child.getName())) into.add(child);
        }
    }
}
