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
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Moves subtitles that sit outside the folder's {@code Subtitles} sub-folder
 * into it - the layout every MovieTool operation expects. Names are kept
 * exactly as they are; this is a pure layout migration.
 *
 * <ul>
 *   <li>A loose subtitle file directly in the videos folder moves into
 *       {@code Subtitles} itself.</li>
 *   <li>A per-episode folder that contains only subtitles moves WHOLE, so the
 *       subtitle stays inside its respective folder:
 *       {@code Outer_Banks_S01_E01/whatever.srt} becomes
 *       {@code Subtitles/Outer_Banks_S01_E01/whatever.srt}. (Operations later
 *       recognise the episode from the folder name, like the old tool.)</li>
 *   <li>A folder that also contains videos keeps the videos where they are;
 *       its subtitles are grouped under {@code Subtitles/<folder name>/}.</li>
 * </ul>
 *
 * <p>{@code Subtitles} folders are never scanned into, existing targets are
 * skipped (individual files unless overwriting; whole folders never), and the
 * move is a dry run by default.</p>
 */
public class SubsRelocator {

    public static final String SUBTITLE_FOLDER = "Subtitles";

    private static final String[] SUBTITLE_EXTENSIONS = { ".srt", ".vtt", ".ass", ".ssa", ".sub" };
    private static final String[] VIDEO_EXTENSIONS = { ".mp4", ".mkv", ".avi", ".mov", ".webm", ".m4v", ".ts" };

    /** Builds the move plan without touching the disk. */
    public OperationResult plan(Options options) {
        OperationResult result = new OperationResult();

        File root = new File(options.getFolder());
        if (!root.isDirectory()) {
            result.add(Problem.error("Not a folder: " + options.getFolder()));
            return result;
        }

        File subsDir = new File(root, SUBTITLE_FOLDER);
        List<TransferAction> transfers = new ArrayList<TransferAction>();
        // The same file can be reached twice (extraction in a mixed folder AND
        // the recursive walk into that folder) - plan each source only once.
        Set<String> plannedFrom = new HashSet<String>();
        collect(root, root, subsDir, options, result, transfers, plannedFrom);

        int plannedFolders = 0;
        int plannedFiles = 0;
        int skipped = 0;
        for (TransferAction action : transfers) {
            if (action.state == TransferAction.State.PLANNED) {
                if (action.from.isDirectory()) plannedFolders++; else plannedFiles++;
            } else {
                skipped++;
            }
        }

        if (transfers.isEmpty()) {
            result.setReport("No subtitle files found in " + root.getName() + " - nothing to move.");
        } else {
            StringBuilder report = new StringBuilder();
            if (plannedFolders > 0) report.append(plannedFolders).append(" subtitle folder(s)");
            if (plannedFiles > 0) {
                if (report.length() > 0) report.append(" and ");
                report.append(plannedFiles).append(" subtitle file(s)");
            }
            report.append(" to move into ").append(subsDir.getPath());
            if (skipped > 0) report.append(" (").append(skipped).append(" skipped, already there)");
            result.setReport(report.toString());
        }
        result.setTransfers(transfers);
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

    /**
     * Plans one directory level. Loose subtitle files here move into
     * {@code Subtitles} (or {@code Subtitles/<this folder's name>} below the
     * root); each sub-directory decides between a whole move, grouped
     * extraction, or recursion - see the class comment.
     */
    private void collect(File level, File root, File subsDir, Options options,
                         OperationResult result, List<TransferAction> transfers, Set<String> plannedFrom) {
        File[] children = level.listFiles();
        if (children == null) return;
        sortByName(children);
        File fileBase = level.equals(root) ? subsDir : new File(subsDir, level.getName());

        for (File child : children) {
            if (child.isHidden() || child.getName().startsWith(".")) continue;
            if (child.isDirectory()) {
                if (isSubtitleFolder(child)) continue;
                List<File> subsAnywhere = new ArrayList<File>();
                collectSubFiles(child, subsAnywhere);
                if (subsAnywhere.isEmpty()) {
                    if (options.isRecursive()) collect(child, root, subsDir, options, result, transfers, plannedFrom);
                    continue;
                }
                if (containsVideo(child)) {
                    // Keep the videos; group the folder's subtitles (wherever
                    // they sit inside it) under Subtitles/<folder name>.
                    for (File sub : subsAnywhere) {
                        addFileMove(sub, subsDir, child.getName(), options, result, transfers, plannedFrom);
                    }
                    if (options.isRecursive()) collect(child, root, subsDir, options, result, transfers, plannedFrom);
                } else if (containsSubFilesDirectly(child)) {
                    // The common case: a pure per-episode subtitle folder.
                    // Move it whole, the subtitle stays in its folder.
                    addFolderMove(child, new File(subsDir, child.getName()), options, result, transfers, plannedFrom);
                } else if (options.isRecursive()) {
                    // Subtitles live deeper inside (e.g. "Season 1/EP/..."):
                    // let those deeper folders decide for themselves.
                    collect(child, root, subsDir, options, result, transfers, plannedFrom);
                }
            } else if (isSubtitle(child.getName())) {
                addFileMove(child, fileBase, null, options, result, transfers, plannedFrom);
            }
        }
    }

    private void addFileMove(File sub, File baseDir, String group, Options options,
                             OperationResult result, List<TransferAction> transfers, Set<String> plannedFrom) {
        if (!plannedFrom.add(sub.getAbsolutePath())) return;   // already planned
        File base = group == null ? baseDir : new File(baseDir, group);
        File target = new File(base, sub.getName());
        if (target.exists() && !options.isOverwrite()) {
            transfers.add(skipped(TransferAction.Kind.MOVE, sub, target));
            result.add(Problem.warn("Not moving " + sub.getName() + ": " + SUBTITLE_FOLDER + "/"
                    + (group == null ? "" : group + "/") + sub.getName() + " already exists (use overwrite to replace)"));
            return;
        }
        TransferAction action = new TransferAction(TransferAction.Kind.MOVE, sub, target);
        action.note = target.exists() ? "replaces existing file" : "";
        transfers.add(action);
    }

    private void addFolderMove(File dir, File target, Options options,
                               OperationResult result, List<TransferAction> transfers, Set<String> plannedFrom) {
        if (!plannedFrom.add(dir.getAbsolutePath())) return;   // already planned
        if (target.exists()) {
            // Whole folders are never overwritten - too easy to destroy data.
            transfers.add(skipped(TransferAction.Kind.MOVE, dir, target));
            result.add(Problem.warn("Not moving folder " + dir.getName() + ": " + SUBTITLE_FOLDER
                    + "/" + dir.getName() + " already exists (folders are never overwritten)"));
            return;
        }
        transfers.add(new TransferAction(TransferAction.Kind.MOVE, dir, target));
    }

    private static TransferAction skipped(TransferAction.Kind kind, File from, File to) {
        TransferAction action = new TransferAction(kind, from, to);
        action.state = TransferAction.State.SKIPPED_EXISTS;
        action.note = "already in " + SUBTITLE_FOLDER;
        return action;
    }

    /** Subtitle files directly inside {@code dir} (not deeper). */
    private static boolean containsSubFilesDirectly(File dir) {
        File[] children = dir.listFiles();
        if (children == null) return false;
        for (File child : children) {
            if (child.isFile() && isSubtitle(child.getName())) return true;
        }
        return false;
    }

    /** Subtitle files anywhere under {@code dir}; "Subtitles" sub-folders are skipped. */
    private static void collectSubFiles(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isHidden() || child.getName().startsWith(".")) continue;
            if (child.isDirectory()) {
                if (!isSubtitleFolder(child)) collectSubFiles(child, out);
            } else if (isSubtitle(child.getName())) {
                out.add(child);
            }
        }
    }

    /** Any video file anywhere under {@code dir}. */
    private static boolean containsVideo(File dir) {
        File[] children = dir.listFiles();
        if (children == null) return false;
        for (File child : children) {
            if (child.isHidden() || child.getName().startsWith(".")) continue;
            if (child.isDirectory()) {
                if (isSubtitleFolder(child)) continue;
                if (containsVideo(child)) return true;
            } else if (isVideo(child.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSubtitle(String name) {
        return hasExtension(name, SUBTITLE_EXTENSIONS);
    }

    private static boolean isVideo(String name) {
        return hasExtension(name, VIDEO_EXTENSIONS);
    }

    private static boolean hasExtension(String name, String[] extensions) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String extension : extensions) {
            if (lower.endsWith(extension)) return true;
        }
        return false;
    }

    private static boolean isSubtitleFolder(File dir) {
        return SUBTITLE_FOLDER.equalsIgnoreCase(dir.getName());
    }

    private static void sortByName(File[] files) {
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) { return a.getName().compareToIgnoreCase(b.getName()); }
        });
    }
}
