package movies.ops;

import movies.core.ConventionRegistry;
import movies.core.FileNameParts;
import movies.core.NameResolver;
import movies.core.NamingConvention;
import movies.core.OperationResult;
import movies.core.Options;
import movies.core.Problem;
import movies.util.IoUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Library health report: which naming conventions are in use, files that no
 * convention recognises, duplicate episodes and episodes missing subtitles.
 */
public class HealthCheck {

    private static final String[] VIDEO_EXTENSIONS = { ".mp4", ".mkv", ".avi", ".mov", ".webm", ".m4v", ".ts" };
    private static final String[] SUBTITLE_EXTENSIONS = { ".srt", ".vtt", ".ass", ".ssa", ".sub" };

    private final ConventionRegistry registry = new ConventionRegistry();

    public OperationResult check(Options options) {
        OperationResult result = new OperationResult();
        File root = new File(options.getFolder());
        if (!root.isDirectory()) {
            result.add(Problem.error("Not a folder: " + options.getFolder()));
            return result;
        }
        List<NamingConvention> selected = registry.selected(options.getConventions());

        List<File> files = new ArrayList<File>();
        collect(root, options.isRecursive(), files, 0);

        int videos = 0;
        int subtitles = 0;
        int unrecognised = 0;
        Map<String, Integer> conventionUse = new HashMap<String, Integer>();
        Map<String, List<String>> episodeVideos = new HashMap<String, List<String>>();
        Map<String, List<String>> episodeSubs = new HashMap<String, List<String>>();

        for (File file : files) {
            String ext = IoUtil.extensionOf(file.getName());
            boolean isVideo = in(VIDEO_EXTENSIONS, ext);
            boolean isSub = in(SUBTITLE_EXTENSIONS, ext);
            if (!isVideo && !isSub) continue;
            if (isVideo) videos++; else subtitles++;

            FileNameParts parts = NameResolver.resolve(file, registry, selected);
            if (parts == null) {
                unrecognised++;
                result.add(Problem.warn("Unrecognised name: " + file.getName()));
                continue;
            }
            Integer seen = conventionUse.get(parts.getConvention());
            conventionUse.put(parts.getConvention(), seen == null ? 1 : seen + 1);

            String key = parts.episodeKey() + "|" + (isVideo ? "v" : "s");
            Map<String, List<String>> bucket = isVideo ? episodeVideos : episodeSubs;
            List<String> names = bucket.get(parts.episodeKey());
            if (names == null) {
                names = new ArrayList<String>();
                bucket.put(parts.episodeKey(), names);
            }
            names.add(file.getName());
        }

        // Duplicate episodes: same key present twice among videos.
        int duplicates = 0;
        for (Map.Entry<String, List<String>> entry : episodeVideos.entrySet()) {
            if (entry.getValue().size() > 1) {
                duplicates++;
                result.add(Problem.warn("Duplicate episode: " + entry.getValue()));
            }
        }

        // Missing subtitles: video without any sub for the same key.
        int missingSubs = 0;
        for (Map.Entry<String, List<String>> entry : episodeVideos.entrySet()) {
            if (!episodeSubs.containsKey(entry.getKey())) {
                missingSubs++;
                if (missingSubs <= 50) {
                    result.add(Problem.info("No subtitle for: " + entry.getValue().get(0)));
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Scanned ").append(files.size()).append(" file(s): ")
          .append(videos).append(" video(s), ").append(subtitles).append(" subtitle(s)\n");
        sb.append("Conventions in use:");
        if (conventionUse.isEmpty()) sb.append(" none");
        for (Map.Entry<String, Integer> entry : conventionUse.entrySet()) {
            sb.append("\n  ").append(entry.getKey()).append(": ").append(entry.getValue());
        }
        sb.append("\nUnrecognised: ").append(unrecognised)
          .append("\nDuplicate episodes: ").append(duplicates)
          .append("\nEpisodes without subtitle: ").append(missingSubs);
        result.setReport(sb.toString());
        return result;
    }

    private static void collect(File root, boolean recursive, List<File> into, int depth) {
        File[] children = root.listFiles();
        if (children == null || depth > 16) return;
        List<File> dirs = new ArrayList<File>();
        for (File child : children) {
            if (child.isDirectory()) dirs.add(child);
            else into.add(child);
        }
        if (recursive) {
            for (File dir : dirs) collect(dir, recursive, into, depth + 1);
        }
    }

    private static boolean in(String[] extensions, String ext) {
        for (String candidate : extensions) {
            if (candidate.equals(ext)) return true;
        }
        return false;
    }
}
