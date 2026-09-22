package movies.ops;

import movies.core.OperationResult;
import movies.core.Options;
import movies.core.Problem;
import movies.subs.SubRip;
import movies.util.IoUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shifts subtitle timing by a constant offset. Replaces the timing nudge that
 * used to require editing hard-coded paths and recompiling.
 *
 * <p>Accepts ONE subtitle file, a folder (every {@code .srt} inside, optionally
 * recursive), or any number of "shift passes": each pass is one offset plus the
 * files/folders it applies to. All passes execute in a single run, so some
 * subtitles can move together (same offset) while others get their own
 * independent offsets. Every shifted file keeps a one-time {@code .bak} of its
 * original - never overwritten by an already-shifted file.</p>
 */
public class SubsShift {

    /** Shifts the file(s)/folder(s)/passes held by the options. */
    public OperationResult shift(Options options) {
        OperationResult result = new OperationResult();
        List<Options.ShiftGroup> groups = options.getShiftGroups();
        if (groups.isEmpty()) {
            String folder = options.getFolder() == null ? "" : options.getFolder().trim();
            if (folder.isEmpty()) {
                result.add(Problem.error("No subtitle file, folder or file list given"));
                return result;
            }
            Options.ShiftGroup legacy = options.addShiftGroup(options.getShiftSeconds());
            legacy.paths.add(folder);
            groups = options.getShiftGroups();
        }

        int done = 0;
        int failed = 0;
        int[] donePerGroup = new int[groups.size()];
        for (int g = 0; g < groups.size(); g++) {
            Options.ShiftGroup group = groups.get(g);
            if (group.paths.isEmpty()) {
                result.add(Problem.warn("A shift pass has no files - skipped"));
                continue;
            }
            long millis = Math.round(group.seconds * 1000.0);
            if (millis == 0) {
                result.add(Problem.error("Shift amount is zero for " + group.paths.size()
                        + " file(s); pass --seconds like 2.5 or -3"));
                continue;
            }
            boolean single = groups.size() == 1 && group.paths.size() == 1;
            for (String path : group.paths) {
                File input = new File(path);
                if (input.isDirectory()) {
                    List<File> found = new ArrayList<File>();
                    collectSubtitles(input, options.isRecursive(), found);
                    if (found.isEmpty()) {
                        result.add(Problem.warn("No .srt files found in " + path
                                + (options.isRecursive() ? "" : " (enable sub-folders to search deeper)")));
                        continue;
                    }
                    for (File file : found) {
                        if (shiftOne(file, file, options, millis, result)) { done++; donePerGroup[g]++; }
                        else failed++;
                    }
                } else if (input.isFile()) {
                    File output = single && !options.getOutput().trim().isEmpty()
                            ? new File(options.getOutput().trim()) : input;
                    if (shiftOne(input, output, options, millis, result)) { done++; donePerGroup[g]++; }
                    else failed++;
                } else {
                    result.add(Problem.error("Not a subtitle file or folder: " + path));
                    failed++;
                }
            }
        }
        result.setReport(buildSummary(done, failed, groups, donePerGroup));
        return result;
    }

    /** Shifts one file in place (or to the given output). True on success. */
    private boolean shiftOne(File input, File output, Options options, long millis, OperationResult result) {
        try {
            SubRip track = SubRip.loadSrt(input);
            if (track.isEmpty()) {
                result.add(Problem.warn("No cues found in " + input.getName() + " - skipped"));
                return false;
            }
            track.shift(millis);

            if (output.equals(input) && options.isBackup()) {
                File backup = new File(input.getParentFile(), input.getName() + ".bak");
                // One-time backup: never overwrite a previous .bak with an
                // already-shifted file - the ORIGINAL original must survive
                // repeated shifts.
                if (!backup.exists()) IoUtil.copy(input, backup);
            }
            track.saveSrt(output);

            String direction = millis > 0 ? "later" : "earlier";
            result.add(Problem.info("Shifted " + input.getName() + " by " + (millis / 1000.0) + "s " + direction));
            return true;
        } catch (IOException e) {
            result.add(Problem.error("Failed: " + input.getName() + ": " + e.getMessage()));
            return false;
        }
    }

    private static String buildSummary(int done, int failed, List<Options.ShiftGroup> groups, int[] donePerGroup) {
        if (done == 0 && failed == 0) return "Nothing shifted";
        StringBuilder sb = new StringBuilder(done + " subtitle file(s) shifted");
        if (failed > 0) sb.append(", ").append(failed).append(" failed");
        if (groups.size() > 1) {
            sb.append(" in ").append(groups.size()).append(" pass(es):");
            for (int g = 0; g < groups.size(); g++) {
                double seconds = groups.get(g).seconds;
                sb.append(' ').append(donePerGroup[g]).append(" at ")
                        .append(seconds >= 0 ? "+" : "").append(seconds).append("s;");
            }
        } else if (!groups.isEmpty()) {
            double seconds = groups.get(0).seconds;
            sb.append(' ').append(seconds >= 0 ? "+" : "").append(seconds).append("s");
        }
        return sb.toString();
    }

    private static void collectSubtitles(File dir, boolean recursive, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isHidden() || child.getName().startsWith(".")) continue;
            if (child.isDirectory()) {
                if (recursive) collectSubtitles(child, true, out);
            } else if (child.getName().toLowerCase(Locale.ROOT).endsWith(".srt")
                    && !child.getName().toLowerCase(Locale.ROOT).endsWith(".bak")) {
                out.add(child);
            }
        }
    }
}
