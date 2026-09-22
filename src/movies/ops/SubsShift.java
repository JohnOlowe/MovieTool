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
 * <p>One subtitle file OR a whole folder: give a directory and every
 * {@code .srt} inside it (optionally recursive) is delayed/advanced by the
 * same amount - a {@code .bak} copy of each original is kept next to it.</p>
 */
public class SubsShift {

    /** Shifts the subtitle file (or every .srt in the folder) named by options.folder. */
    public OperationResult shift(Options options) {
        OperationResult result = new OperationResult();
        File input = new File(options.getFolder());
        long millis = Math.round(options.getShiftSeconds() * 1000.0);
        if (millis == 0) {
            result.add(Problem.error("Shift amount is zero; pass --seconds like 2.5 or -3"));
            return result;
        }
        if (input.isDirectory()) {
            return shiftFolder(input, options, millis);
        }
        if (!input.isFile()) {
            result.add(Problem.error("Not a subtitle file: " + options.getFolder()));
            return result;
        }
        File output = options.getOutput().trim().isEmpty() ? input : new File(options.getOutput().trim());
        shiftOne(input, output, options, millis, result);
        return result;
    }

    /** Folder mode: shift every .srt in the folder (and sub-folders when recursive). */
    private OperationResult shiftFolder(File folder, Options options, long millis) {
        OperationResult result = new OperationResult();
        List<File> files = new ArrayList<File>();
        collectSubtitles(folder, options.isRecursive(), files);
        if (files.isEmpty()) {
            result.add(Problem.warn("No .srt files found in " + folder.getPath()
                    + (options.isRecursive() ? "" : " (enable 'include sub-folders' to search deeper)")));
            return result;
        }
        int done = 0;
        int failed = 0;
        for (File file : files) {
            if (shiftOne(file, file, options, millis, result)) done++; else failed++;
        }
        String direction = millis > 0 ? "later" : "earlier";
        result.setReport(done + " subtitle file(s) shifted " + (millis / 1000.0) + "s " + direction
                + (failed > 0 ? ", " + failed + " failed" : "") + " - " + folder.getPath());
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
