package movies.ops;

import movies.core.ConventionRegistry;
import movies.core.FileNameParts;
import movies.core.NameResolver;
import movies.core.NameSanitizer;
import movies.core.NamingConvention;
import movies.core.OperationResult;
import movies.core.Options;
import movies.core.Problem;
import movies.core.TransferAction;
import movies.util.IoUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renames every episode according to the episode titles in an IMDB-style
 * titles list, and brings its subtitle along.
 *
 * <p>The list is a text file with lines like (as copied from IMDB or Netflix):</p>
 *
 * <pre>
 *   Outer Banks (2020)
 *   S1.E1 ∙ Pilot
 *   S1.E2 ∙ Middle of Nowhere: Fun Bro?
 * </pre>
 *
 * <p>For each "S1.E2 ∙ Title" line the matching video (and its subtitle, which
 * may sit in a "Subtitles" folder) is renamed to
 * {@code Show - S01E02 - Middle of Nowhere Fun Bro.mp4}. The separator is the
 * IMDB middle dot (∙, also accepted: • and ·). Titles containing characters
 * that Windows forbids (?, :, ", ...) are sanitised automatically — the old
 * tool failed on exactly those.</p>
 */
public class ImdbRename {

    /** One parsed "S1.E2 ∙ Title" line. */
    public static final class EpisodeEntry {
        public final int season;
        public final int episode;
        public final String title;
        public final String rawLine;

        EpisodeEntry(int season, int episode, String title, String rawLine) {
            this.season = season;
            this.episode = episode;
            this.title = title;
            this.rawLine = rawLine;
        }
    }

    /** "S1.E2 ∙ Title", "s01e02 • Title" and "1x02 · Title" all parse. */
    private static final Pattern TITLE_LINE = Pattern.compile(
            "^\\s*(?:(?:s(\\d{1,2})\\s*[.x]?\\s*e(\\d{1,3}))|(?:(\\d{1,2})x(\\d{1,3})))"
                    + "\\s*[\\u2219\\u2022\\u00B7]\\s*(.+?)\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** The IMDB middle dot and close variants. */
    private static final String DOTS = "\u2219\u2022\u00B7";

    private static final String[] VIDEO_EXTENSIONS = { ".mp4", ".mkv", ".avi", ".mov", ".webm", ".m4v", ".ts" };
    private static final String[] SUBTITLE_EXTENSIONS = { ".srt", ".vtt", ".ass", ".ssa", ".sub" };

    private final ConventionRegistry registry = new ConventionRegistry();

    // ------------------------------------------------------------- parsing

    /**
     * Parses the titles list. Header lines without an episode marker are
     * skipped; lines that look like episodes but fail to parse produce a
     * warning instead of aborting (the old tool bailed out on those).
     */
    public List<EpisodeEntry> parseTitles(File titlesFile, OperationResult result) throws IOException {
        List<EpisodeEntry> entries = new ArrayList<EpisodeEntry>();
        int lineNumber = 0;
        for (String raw : IoUtil.readLines(titlesFile)) {
            lineNumber++;
            String line = raw.replace("\uFEFF", "").trim();
            if (line.isEmpty()) continue;
            Matcher m = TITLE_LINE.matcher(line);
            if (m.matches()) {
                int season;
                int episode;
                if (m.group(1) != null) {
                    season = Integer.parseInt(m.group(1));
                    episode = Integer.parseInt(m.group(2));
                } else {
                    season = Integer.parseInt(m.group(3));
                    episode = Integer.parseInt(m.group(4));
                }
                if (episode <= 0) {
                    result.add(Problem.warn("Line " + lineNumber + ": episode number must be positive, skipped"));
                    continue;
                }
                entries.add(new EpisodeEntry(season, episode, stripQuotes(m.group(5)), line));
            } else if (looksLikeEpisodeLine(line)) {
                result.add(Problem.warn("Line " + lineNumber + " does not start with an episode number, skipped: "
                        + shorten(line)));
            }
        }
        return entries;
    }

    private static boolean looksLikeEpisodeLine(String line) {
        int dot = -1;
        for (int i = 0; i < line.length(); i++) {
            if (DOTS.indexOf(line.charAt(i)) >= 0) { dot = i; break; }
        }
        if (dot < 0) return false;
        String prefix = line.substring(0, dot).trim();
        return prefix.matches("(?i)^(?:s\\d{1,2}\\b.*|\\d{1,2}x.*)");
    }

    private static String stripQuotes(String title) {
        String t = title.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            t = t.substring(1, t.length() - 1).trim();
        }
        return t;
    }

    private static String shorten(String line) {
        return line.length() <= 60 ? line : line.substring(0, 57) + "...";
    }

    // ---------------------------------------------------------------- plan

    /** Builds the rename/move plan without touching the disk. */
    public OperationResult plan(Options options) {
        OperationResult result = new OperationResult();

        File root = new File(options.getFolder());
        if (!root.isDirectory()) {
            result.add(Problem.error("Not a folder: " + options.getFolder()));
            return result;
        }
        File titlesFile = resolveTitlesFile(options, root, result);
        if (titlesFile == null) return result;

        List<EpisodeEntry> entries;
        try {
            entries = parseTitles(titlesFile, result);
        } catch (IOException e) {
            result.add(Problem.error("Cannot read titles list: " + e.getMessage()));
            return result;
        }
        if (entries.isEmpty()) {
            result.add(Problem.warn("No lines like 'S1.E2 ∙ Title' found in " + titlesFile.getName()));
            return result;
        }

        // Duplicate S/E lines: first one wins.
        LinkedHashMap<String, EpisodeEntry> unique = new LinkedHashMap<String, EpisodeEntry>();
        for (EpisodeEntry entry : entries) {
            String key = entry.season + "x" + entry.episode;
            if (unique.containsKey(key)) {
                result.add(Problem.warn("Duplicate entry for S" + entry.season + "E" + entry.episode
                        + "; keeping the first"));
            } else {
                unique.put(key, entry);
            }
        }

        List<NamingConvention> selected = registry.selected(options.getConventions());

        // ---- index the videos by episode.
        Map<String, File> videos = new HashMap<String, File>();
        Map<String, FileNameParts> videoParts = new HashMap<String, FileNameParts>();
        List<File> videoFiles = new ArrayList<File>();
        collectFiles(root, options.isRecursive(), videoFiles, false);
        for (File file : videoFiles) {
            Candidate candidate = episodeOf(file, selected);
            if (candidate == null) continue;
            if (videos.containsKey(candidate.key)) {
                result.add(Problem.warn("Duplicate video for " + candidate.key + ": " + file.getName()
                        + " ignored (first one wins)"));
                continue;
            }
            videos.put(candidate.key, file);
            videoParts.put(candidate.key, candidate.parts);
        }

        // ---- index the subtitles: the folder itself plus its "Subtitles"
        // sub-folder when present (or an explicitly given folder).
        Map<String, File> subs = new HashMap<String, File>();
        Map<String, FileNameParts> subParts = new HashMap<String, FileNameParts>();
        List<File> subRoots = new ArrayList<File>();
        subRoots.add(root);
        String subSpec = options.getSecondaryFolder().trim();
        if (!subSpec.isEmpty()) {
            subRoots.add(new File(subSpec));
        } else {
            File defaultSubs = new File(root, "Subtitles");
            if (defaultSubs.isDirectory()) {
                subRoots.add(defaultSubs);
                result.add(Problem.info("Subtitles source: " + defaultSubs));
            }
        }
        for (File subRoot : subRoots) {
            List<File> found = new ArrayList<File>();
            collectFiles(subRoot, true, found, true);
            for (File file : found) {
                Candidate candidate = episodeOf(file, selected);
                if (candidate == null) continue;
                if (!subs.containsKey(candidate.key)) {
                    subs.put(candidate.key, file);
                    subParts.put(candidate.key, candidate.parts);
                }
            }
        }

        // ---- plan one rename pair per titles entry.
        String style = normalizeStyle(options.getStyle());
        List<TransferAction> transfers = new ArrayList<TransferAction>();
        Set<String> claimed = new HashSet<String>();
        int matched = 0;

        for (EpisodeEntry entry : unique.values()) {
            String key = entry.season + "x" + entry.episode;
            File video = videos.get(key);
            File sub = subs.get(key);
            if (video == null && sub == null) {
                result.add(Problem.info("No files for S" + entry.season + "E" + entry.episode
                        + " \u2219 " + entry.title));
                continue;
            }
            matched++;

            String show = resolveShow(options, videoParts.get(key), subParts.get(key), root);
            String marker = "s01e01".equals(style)
                    ? "S" + pad(entry.season) + "E" + pad(entry.episode)
                    : entry.season + "x" + pad(entry.episode);
            String title = NameSanitizer.sanitize(entry.title, options.getReplaceWith());
            if (title.isEmpty()) title = "Episode " + entry.episode;
            String base = NameSanitizer.sanitize(show + " - " + marker + " - " + title, options.getReplaceWith());
            String tag = NameSanitizer.sanitize(options.getTag(), options.getReplaceWith());
            if (!tag.isEmpty()) base = base + "." + tag;

            if (video != null) {
                File target = new File(video.getParentFile(), base + IoUtil.extensionOf(video.getName()));
                addTransfer(transfers, options, video, target, claimed);
            }
            if (sub != null) {
                File dir = video != null ? video.getParentFile() : root;
                File target = new File(dir, base + IoUtil.extensionOf(sub.getName()));
                if (video == null) {
                    result.add(Problem.info("Subtitle only (no video): " + sub.getName() + " -> "
                            + dir.getName() + "/" + target.getName()));
                }
                addTransfer(transfers, options, sub, target, claimed);
            }
        }

        result.setTransfers(transfers);
        result.setReport(unique.size() + " episode title(s) read, " + matched + " matched, "
                + countPlanned(transfers) + " rename(s) planned");
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

    private static final class Candidate {
        final String key;
        final FileNameParts parts;

        Candidate(String key, FileNameParts parts) {
            this.key = key;
            this.parts = parts;
        }
    }

    private Candidate episodeOf(File file, List<NamingConvention> selected) {
        FileNameParts parts = NameResolver.resolve(file, registry, selected);
        if (parts != null) {
            if (parts.isEpisode()) {
                return new Candidate(parts.getSeason() + "x" + parts.getEpisode(), parts);
            }
            return null; // parsed as a movie, not an episode
        }
        int[] raw = NameResolver.rawEpisode(file.getName());
        return raw == null ? null : new Candidate(raw[0] + "x" + raw[1], null);
    }

    private String resolveShow(Options options, FileNameParts videoParts, FileNameParts subParts, File root) {
        String override = options.getShowName().trim();
        if (!override.isEmpty()) return override;
        FileNameParts parts = videoParts != null ? videoParts : subParts;
        if (parts != null && !parts.getTitle().isEmpty()) {
            return parts.getTitle().replace('_', ' ').replace('.', ' ').trim();
        }
        return root.getName().replace('_', ' ').trim();
    }

    private File resolveTitlesFile(Options options, File root, OperationResult result) {
        String spec = options.getTitlesFile().trim();
        if (!spec.isEmpty()) {
            File file = new File(spec);
            if (!file.isFile()) {
                result.add(Problem.error("Titles list not found: " + spec));
                return null;
            }
            return file;
        }
        File fallback = new File(root, "titles.list");
        if (fallback.isFile()) {
            result.add(Problem.info("Titles list: " + fallback));
            return fallback;
        }
        result.add(Problem.error("No titles list given and " + fallback + " does not exist. Pass --titles <file>."));
        return null;
    }

    private void addTransfer(List<TransferAction> transfers, Options options, File from, File to,
                             Set<String> claimed) {
        TransferAction action = new TransferAction(TransferAction.Kind.MOVE, from, to);
        if (isSameFile(from, to)) {
            action.state = TransferAction.State.ALREADY_THERE;
        } else if (!claimed.add(to.getAbsolutePath())) {
            action.state = TransferAction.State.COLLISION;
            action.note = "another file is already renamed to this name";
        } else if (to.exists() && !options.isOverwrite()) {
            action.state = TransferAction.State.SKIPPED_EXISTS;
        } else {
            action.state = TransferAction.State.PLANNED;
        }
        transfers.add(action);
    }

    private static boolean isSameFile(File a, File b) {
        try {
            return a.getCanonicalPath().equals(b.getCanonicalPath());
        } catch (IOException e) {
            return a.getAbsolutePath().equals(b.getAbsolutePath());
        }
    }

    private static int countPlanned(List<TransferAction> transfers) {
        int n = 0;
        for (TransferAction t : transfers) {
            if (t.state == TransferAction.State.PLANNED) n++;
        }
        return n;
    }

    /** Accepts s01e01 (default), sxe, 1x01 and x spellings. */
    static String normalizeStyle(String style) {
        String s = style == null ? "" : style.trim().toLowerCase();
        if (s.isEmpty() || s.equals("s01e01") || s.equals("sxxexx") || s.equals("sxe") || s.equals("se")) {
            return "s01e01";
        }
        if (s.equals("1x01") || s.equals("nxn") || s.equals("x")) return "1x01";
        return "s01e01";
    }

    private static String pad(int n) {
        return n < 10 ? "0" + n : Integer.toString(n);
    }

    private static void collectFiles(File root, boolean recursive, List<File> into, boolean subtitleMode) {
        File[] children = root.listFiles();
        if (children == null) return;
        List<File> dirs = new ArrayList<File>();
        for (File child : children) {
            if (child.isDirectory()) {
                if (recursive) dirs.add(child);
                continue;
            }
            if (child.getName().startsWith(".")) continue;
            String ext = IoUtil.extensionOf(child.getName());
            boolean wanted = false;
            if (subtitleMode) {
                for (String candidate : SUBTITLE_EXTENSIONS) if (candidate.equals(ext)) wanted = true;
            } else {
                for (String candidate : VIDEO_EXTENSIONS) if (candidate.equals(ext)) wanted = true;
            }
            if (wanted) into.add(child);
        }
        for (File dir : dirs) collectFiles(dir, true, into, subtitleMode);
    }
}
