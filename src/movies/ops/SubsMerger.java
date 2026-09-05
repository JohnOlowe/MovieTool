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
 * Merges two or more subtitle tracks into one SRT, stacking the text of cues
 * that overlap. Replaces the old hard-coded MergeSubtitles main.
 */
public class SubsMerger {

    /** Merges the input files listed in options (folder = first, secondary = second). */
    public OperationResult merge(Options options) {
        OperationResult result = new OperationResult();
        List<File> inputs = resolveInputs(options);
        if (inputs.size() < 2) {
            result.add(Problem.error("Two or more subtitle files are required"));
            return result;
        }

        List<SubRip> tracks = new ArrayList<SubRip>();
        for (File input : inputs) {
            try {
                SubRip track = SubRip.loadSrt(input);
                if (track.isEmpty()) {
                    result.add(Problem.warn("No cues found in " + input.getName()));
                }
                tracks.add(track);
            } catch (IOException e) {
                result.add(Problem.error("Cannot read " + input + ": " + e.getMessage()));
                return result;
            }
        }

        SubRip merged = tracks.get(0);
        for (int i = 1; i < tracks.size(); i++) {
            merged = SubRip.merge(merged, tracks.get(i), options.isSecondOnTop() && i == 1);
        }

        File output = resolveOutput(options, inputs);
        try {
            merged.saveSrt(output);
        } catch (IOException e) {
            result.add(Problem.error("Cannot write " + output + ": " + e.getMessage()));
            return result;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Merged ").append(tracks.size()).append(" tracks, ")
          .append(merged.cues().size()).append(" cue(s) -> ").append(output.getName());
        result.setReport(sb.toString());
        return result;
    }

    private List<File> resolveInputs(Options options) {
        List<File> inputs = new ArrayList<File>();
        if (!options.getFolder().trim().isEmpty()) inputs.add(new File(options.getFolder().trim()));
        if (!options.getSecondaryFolder().trim().isEmpty()) inputs.add(new File(options.getSecondaryFolder().trim()));
        return inputs;
    }

    private File resolveOutput(Options options, List<File> inputs) {
        if (!options.getOutput().trim().isEmpty()) {
            File out = new File(options.getOutput().trim());
            if (out.isDirectory()) {
                return new File(out, defaultName(inputs));
            }
            return out;
        }
        return new File(inputs.get(0).getParentFile(), defaultName(inputs));
    }

    private String defaultName(List<File> inputs) {
        String base = IoUtil.baseName(inputs.get(0).getName());
        return base + ".merged.srt";
    }
}
