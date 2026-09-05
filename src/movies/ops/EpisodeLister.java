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
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lists every episode found below a folder, grouped per show and season.
 * Replaces the listing one used to do by hand.
 */
public class EpisodeLister {

    private static final String[] VIDEO_EXTENSIONS = { ".mp4", ".mkv", ".avi", ".mov", ".webm", ".m4v", ".ts" };

    private final ConventionRegistry registry = new ConventionRegistry();

    /** Produces the listing (plain text or CSV when options.isCsv()). */
    public OperationResult list(Options options) {
        OperationResult result = new OperationResult();
        File root = new File(options.getFolder());
        if (!root.isDirectory()) {
            result.add(Problem.error("Not a folder: " + options.getFolder()));
            return result;
        }
        List<NamingConvention> selected = registry.selected(options.getConventions());

        List<File> files = new ArrayList<File>();
        collectVideos(root, options.isRecursive(), files, 0);
        if (files.isEmpty()) {
            result.add(Problem.warn("No video files found under " + root));
        }

        List<Entry> entries = new ArrayList<Entry>();
        for (File file : files) {
            FileNameParts parts = NameResolver.resolve(file, registry, selected);
            if (parts == null) {
                result.add(Problem.warn("Unrecognised name, listed as unknown: " + file.getName()));
                entries.add(new Entry("?", 0, 0, "", file));
                continue;
            }
            entries.add(new Entry(parts.getTitle(), parts.getSeason(), parts.getEpisode(),
                    parts.getEpisodeTitle(), file));
        }
        sortEntries(entries);

        result.setReport(options.isCsv() ? renderCsv(entries) : renderText(entries));
        return result;
    }

    private String renderText(List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        String currentShow = null;
        int currentSeason = Integer.MIN_VALUE;
        for (Entry entry : entries) {
            String show = entry.show.isEmpty() ? "(unknown)" : entry.show;
            if (!show.equals(currentShow) || entry.season != currentSeason) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(show);
                if (entry.season > 0) sb.append(" - Season ").append(entry.season);
                sb.append('\n');
                currentShow = show;
                currentSeason = entry.season;
            }
            sb.append("  ");
            if (entry.episode > 0) sb.append("E").append(entry.episode < 10 ? "0" : "").append(entry.episode).append(' ');
            sb.append(entry.file.getName());
            if (!entry.episodeTitle.isEmpty()) sb.append("  [").append(entry.episodeTitle).append(']');
            sb.append('\n');
        }
        sb.append('\n').append(entries.size()).append(" file(s)");
        return sb.toString();
    }

    private String renderCsv(List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("show,season,episode,episode_title,file\n");
        for (Entry entry : entries) {
            sb.append(csv(entry.show)).append(',').append(entry.season).append(',').append(entry.episode)
              .append(',').append(csv(entry.episodeTitle)).append(',').append(csv(entry.file.getName()))
              .append('\n');
        }
        return sb.toString();
    }

    private static String csv(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    private static void sortEntries(List<Entry> entries) {
        Collections.sort(entries, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                int byShow = a.show.compareToIgnoreCase(b.show);
                if (byShow != 0) return byShow;
                int bySeason = Integer.compare(a.season, b.season);
                if (bySeason != 0) return bySeason;
                return Integer.compare(a.episode, b.episode);
            }
        });
    }

    private static void collectVideos(File root, boolean recursive, List<File> into, int depth) {
        File[] children = root.listFiles();
        if (children == null || depth > 16) return;
        List<File> dirs = new ArrayList<File>();
        for (File child : children) {
            if (child.isDirectory()) dirs.add(child);
            else if (in(VIDEO_EXTENSIONS, IoUtil.extensionOf(child.getName()))) into.add(child);
        }
        if (recursive) {
            for (File dir : dirs) collectVideos(dir, recursive, into, depth + 1);
        }
    }

    private static boolean in(String[] extensions, String ext) {
        for (String candidate : extensions) {
            if (candidate.equals(ext)) return true;
        }
        return false;
    }

    private static final class Entry {
        final String show;
        final int season;
        final int episode;
        final String episodeTitle;
        final File file;

        Entry(String show, int season, int episode, String episodeTitle, File file) {
            this.show = show == null ? "" : show;
            this.season = season;
            this.episode = episode;
            this.episodeTitle = episodeTitle == null ? "" : episodeTitle;
            this.file = file;
        }
    }
}
