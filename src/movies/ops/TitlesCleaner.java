package movies.ops;

import movies.core.OperationResult;
import movies.core.Options;
import movies.core.Problem;
import movies.util.IoUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Strips a titles list (the IMDB/Netflix style copy-paste file used by the
 * IMDB rename) down to the episode lines only.
 *
 * <p>Everything that is not an episode entry - the show name, "TV Series",
 * season overview lines, blank lines, page artefacts - is removed. What stays
 * is rewritten canonically as {@code S1.E2 ∙ Episode Title}, deduplicated
 * (first occurrence wins) and sorted by season/episode.</p>
 *
 * <p>By default the cleaned list is written to a NEW file
 * ({@code <original>-clean.list}); the original can be replaced instead -
 * a {@code .bak} copy of it is kept either way when replacing.</p>
 */
public class TitlesCleaner {

    /** Builds the cleaned content and reports what would happen; writes nothing. */
    public OperationResult plan(Options options) {
        OperationResult result = new OperationResult();

        File file = resolveFile(options);
        if (file == null) {
            result.add(Problem.error("No titles list found (looked for " + Options.DEFAULT_TITLES_NAME
                    + " in the folder; or pass a file)."));
            return result;
        }
        if (!file.isFile()) {
            result.add(Problem.error("Not a file: " + file.getPath()));
            return result;
        }

        List<String> lines;
        try {
            lines = IoUtil.readLines(file);
        } catch (IOException e) {
            result.add(Problem.error("Cannot read titles list: " + e.getMessage()));
            return result;
        }

        // Keep only real episode lines, deduplicated.
        LinkedHashMap<Integer, Episode> unique = new LinkedHashMap<Integer, Episode>();
        int dropped = 0;
        int duplicates = 0;
        for (String raw : lines) {
            ImdbRename.EpisodeEntry entry = ImdbRename.parseEpisodeLine(raw);
            if (entry == null) {
                if (!raw.replace("\uFEFF", "").trim().isEmpty()) dropped++;
                continue;
            }
            int key = entry.season * 1000 + entry.episode;
            if (unique.containsKey(key)) {
                duplicates++;
            } else {
                unique.put(key, new Episode(entry.season, entry.episode, entry.title));
            }
        }

        List<Episode> episodes = new ArrayList<Episode>(unique.values());
        if (options.isSortTitles()) {
            Collections.sort(episodes);
        }

        StringBuilder content = new StringBuilder();
        for (Episode episode : episodes) {
            content.append(canonical(episode)).append(System.getProperty("line.separator"));
        }

        File target = resolveTarget(options, file);
        result.setReport(episodes.size() + " episode line(s) kept, " + dropped + " other line(s) removed, "
                + duplicates + " duplicate(s) removed -> " + target.getPath()
                + (target.equals(file) ? " (original replaced; a .bak copy is kept)" : ""));
        if (episodes.isEmpty()) {
            result.add(Problem.warn("No episode lines found - nothing would remain."));
        }
        result.setCleanTitlesContent(content.toString());
        result.setCleanTitlesTarget(target);
        return result;
    }

    /** Writes the cleaned list produced by {@link #plan}. */
    public void apply(OperationResult result, Options options) {
        File target = result.getCleanTitlesTarget();
        String content = result.getCleanTitlesContent();
        if (target == null || content == null) {
            result.add(Problem.error("Run a dry run first."));
            return;
        }
        try {
            if (target.equals(resolveFile(options))) {
                // Replacing the original: keep a one-time backup.
                File backup = new File(target.getParentFile(), target.getName() + ".bak");
                if (!backup.exists() && target.exists()) {
                    IoUtil.copy(target, backup);
                    result.add(Problem.info("Backup: " + backup.getName()));
                }
            } else if (target.exists() && !options.isOverwrite()) {
                result.add(Problem.error("Target already exists: " + target.getPath()
                        + " (enable overwrite to replace it)"));
                return;
            }
            IoUtil.writeText(target, content);
            result.add(Problem.info("Written: " + target.getPath()
                    + " (" + content.split("\r?\n|\r").length + " lines)"));
        } catch (IOException e) {
            result.add(Problem.error("Failed to write " + target.getPath() + ": " + e.getMessage()));
        }
    }

    // ------------------------------------------------------------- helpers

    /** "S1.E2 ∙ Title" - the canonical IMDB-style form. */
    static String canonical(Episode episode) {
        return "S" + episode.season + ".E" + episode.episode + " \u2219 " + episode.title;
    }

    /** Explicit --titles file wins; a folder argument means <folder>/titles.list. */
    static File resolveFile(Options options) {
        String explicit = options.getTitlesFile().trim();
        if (!explicit.isEmpty()) {
            File file = new File(explicit);
            return file.isFile() ? file : file;   // caller reports "not a file"
        }
        String folder = options.getFolder().trim();
        if (folder.isEmpty()) return null;
        File base = new File(folder);
        if (base.isFile()) return base;           // a file was given as the folder
        File candidate = new File(base, Options.DEFAULT_TITLES_NAME);
        return candidate.isFile() ? candidate : null;
    }

    private static File resolveTarget(Options options, File original) {
        if (options.isTitlesInPlace()) return original;
        String output = options.getOutput().trim();
        if (!output.isEmpty()) return new File(output);
        String name = original.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : ".list";
        return new File(original.getParentFile(), base + "-clean" + ext);
    }

    /** One cleaned episode; sortable by season, then episode. */
    static final class Episode implements Comparable<Episode> {
        final int season;
        final int episode;
        final String title;

        Episode(int season, int episode, String title) {
            this.season = season;
            this.episode = episode;
            this.title = title;
        }

        @Override
        public int compareTo(Episode other) {
            int bySeason = Integer.compare(season, other.season);
            return bySeason != 0 ? bySeason : Integer.compare(episode, other.episode);
        }
    }
}
