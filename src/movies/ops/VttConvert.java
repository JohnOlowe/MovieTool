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

/**
 * Converts WebVTT files to SubRip, in bulk or one at a time. Replaces the old
 * hard-coded VttToSrtConverter main.
 */
public class VttConvert {

    /** Converts every .vtt under options.folder (or just that file) to .srt. */
    public OperationResult convert(Options options) {
        OperationResult result = new OperationResult();
        File input = new File(options.getFolder());
        List<File> sources = new ArrayList<File>();
        if (input.isFile()) {
            sources.add(input);
        } else if (input.isDirectory()) {
            collect(input, options.isRecursive(), sources);
        } else {
            result.add(Problem.error("Input is neither a file nor a folder: " + options.getFolder()));
            return result;
        }
        if (sources.isEmpty()) {
            result.add(Problem.warn("No .vtt files found under " + options.getFolder()));
            return result;
        }

        File outputSetting = options.getOutput().trim().isEmpty() ? null : new File(options.getOutput().trim());
        int converted = 0;
        int overlapping = 0;
        for (File source : sources) {
            File target = targetFor(source, input, outputSetting);
            try {
                SubRip track = SubRip.parseVtt(IoUtil.readText(source));
                if (track.isEmpty()) {
                    result.add(Problem.warn("No cues in " + source.getName() + ", skipped"));
                    continue;
                }
                overlapping += clampOverlaps(track);
                if (target.exists() && !options.isOverwrite()) {
                    result.add(Problem.warn("Exists, skipped: " + target.getName()));
                    continue;
                }
                track.saveSrt(target);
                converted++;
            } catch (IOException e) {
                result.add(Problem.error("Failed " + source.getName() + ": " + e.getMessage()));
            }
        }
        String report = "Converted " + converted + " file(s)";
        if (overlapping > 0) report += ", tightened " + overlapping + " overlapping cue(s)";
        result.setReport(report);
        return result;
    }

    /** Ensures cues are sequential so every player renders them (SRT has no overlap concept). */
    private int clampOverlaps(SubRip track) {
        int fixed = 0;
        for (int i = 1; i < track.cues().size(); i++) {
            SubRip.Cue previous = track.cues().get(i - 1);
            SubRip.Cue current = track.cues().get(i);
            if (current.start < previous.end) {
                long newEnd = current.start;
                if (newEnd > previous.start) {
                    previous.end = newEnd;
                    fixed++;
                }
            }
        }
        return fixed;
    }

    private File targetFor(File source, File inputRoot, File outputSetting) {
        String name = IoUtil.baseName(source.getName()) + ".srt";
        if (outputSetting == null) {
            return new File(source.getParentFile(), name);
        }
        if (outputSetting.isFile()) {
            return outputSetting;
        }
        if (source.equals(inputRoot)) {
            return new File(outputSetting, name);
        }
        // Preserve the relative layout below the scanned folder.
        try {
            String basePath = inputRoot.getCanonicalPath();
            String sourcePath = source.getCanonicalPath();
            if (sourcePath.startsWith(basePath)) {
                String relativeDir = new File(sourcePath).getParentFile().getCanonicalPath().substring(basePath.length());
                File dir = new File(outputSetting, relativeDir.isEmpty() ? "" : relativeDir);
                return new File(dir, name);
            }
        } catch (IOException ignore) {
            // Fall through to the flat layout.
        }
        return new File(outputSetting, name);
    }

    private static void collect(File root, boolean recursive, List<File> into) {
        File[] children = root.listFiles();
        if (children == null) return;
        List<File> dirs = new ArrayList<File>();
        for (File child : children) {
            if (child.isDirectory()) dirs.add(child);
            else if (IoUtil.hasExtension(child.getName(), ".vtt")) into.add(child);
        }
        if (recursive) {
            for (File dir : dirs) collect(dir, recursive, into);
        }
    }
}
