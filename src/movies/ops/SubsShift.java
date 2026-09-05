package movies.ops;

import movies.core.OperationResult;
import movies.core.Options;
import movies.core.Problem;
import movies.subs.SubRip;
import movies.util.IoUtil;

import java.io.File;
import java.io.IOException;

/**
 * Shifts subtitle timing by a constant offset. Replaces the timing nudge that
 * used to require editing hard-coded paths and recompiling.
 */
public class SubsShift {

    /** Shifts the single subtitle file named by options.folder. */
    public OperationResult shift(Options options) {
        OperationResult result = new OperationResult();
        File input = new File(options.getFolder());
        if (!input.isFile()) {
            result.add(Problem.error("Not a subtitle file: " + options.getFolder()));
            return result;
        }
        long millis = Math.round(options.getShiftSeconds() * 1000.0);
        if (millis == 0) {
            result.add(Problem.error("Shift amount is zero; pass --seconds like 2.5 or -3"));
            return result;
        }
        try {
            SubRip track = SubRip.loadSrt(input);
            if (track.isEmpty()) {
                result.add(Problem.error("No cues found in " + input.getName()));
                return result;
            }
            track.shift(millis);

            File output = options.getOutput().trim().isEmpty() ? input : new File(options.getOutput().trim());
            if (output.equals(input) && options.isBackup()) {
                File backup = new File(input.getParentFile(), input.getName() + ".bak");
                IoUtil.copy(input, backup);
                result.add(Problem.info("Backup written: " + backup.getName()));
            }
            track.saveSrt(output);

            String direction = millis > 0 ? "later" : "earlier";
            result.setReport("Shifted " + track.cues().size() + " cue(s) " + (millis / 1000.0) + "s " + direction
                    + " -> " + output.getName());
        } catch (IOException e) {
            result.add(Problem.error("Failed: " + e.getMessage()));
        }
        return result;
    }
}
