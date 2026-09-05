package movies.core;

import movies.util.IoUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plans and executes renames towards a naming convention (or a custom pattern).
 *
 * <p>The engine is deliberately two-phase: {@link #plan} produces a dry-run
 * plan describing every rename; {@link RenamePlan#apply} executes it safely by
 * first moving files to temporary names and then to their destinations, so
 * swap-style renames (A to B while B to A) cannot destroy data.</p>
 */
public class RenameEngine {

    /** File extensions the engine treats as interesting (videos and subtitles). */
    private static final String[] DEFAULT_EXTENSIONS = {
            ".mp4", ".mkv", ".avi", ".mov", ".webm", ".m4v", ".ts",
            ".srt", ".vtt", ".ass", ".ssa", ".sub"
    };

    private final ConventionRegistry registry = new ConventionRegistry();
    private final List<Problem> problems = new ArrayList<Problem>();

    public ConventionRegistry registry() {
        return registry;
    }

    public List<Problem> problems() {
        return problems;
    }

    /**
     * Scans the folder and produces a rename plan. Never touches the disk.
     *
     * @param options folder, conventions, pattern, recursion, target convention id
     * @param targetConventionId convention to rebuild names with, or null/blank
     *                           when a custom pattern should be used instead
     */
    public RenamePlan plan(Options options, String targetConventionId) {
        List<Problem> problems = this.problems;
        problems.clear();

        RenamePlan plan = new RenamePlan();
        File root = new File(options.getFolder());
        if (!root.isDirectory()) {
            problems.add(Problem.error("Not a folder: " + options.getFolder()));
            return plan;
        }

        List<NamingConvention> selected = registry.selected(options.getConventions());
        if (selected.isEmpty()) {
            problems.add(Problem.error("No known conventions in: " + options.getConventions()));
            return plan;
        }

        NamingConvention target = targetConventionId == null || targetConventionId.trim().isEmpty()
                ? null : registry.get(targetConventionId.trim());
        if (target == null && !options.hasPattern()) {
            problems.add(Problem.error("Neither a target convention nor a custom pattern was given"));
            return plan;
        }

        List<File> files = new ArrayList<File>();
        collect(root, options.isRecursive(), files, 0);

        Map<String, String> plannedTargets = new HashMap<String, String>();
        for (File file : files) {
            if (file.isDirectory()) continue;
            FileNameParts parsed = registry.detect(file.getName(), selected);
            if (parsed == null) {
                problems.add(Problem.warn("Unrecognised name, skipped: " + file.getName()));
                continue;
            }
            String newName = options.hasPattern()
                    ? PatternBuilder.build(options.getPattern().trim(), parsed)
                    : target.build(parsed);
            if (newName.equals(file.getName())) continue; // already conforming
            File targetFile = new File(file.getParentFile(), newName);

            String previous = plannedTargets.put(file.getParentFile().getAbsolutePath() + File.separatorChar + newName, file.getName());
            if (previous != null) {
                problems.add(Problem.error("Collision: \"" + previous + "\" and \"" + file.getName()
                        + "\" would both become \"" + newName + "\"; neither will be renamed"));
                plan.add(RenameAction.collide(file, targetFile));
                continue;
            }
            if (targetFile.exists() && !targetFile.equals(file)) {
                problems.add(Problem.error("Target already exists, skipped: " + newName));
                plan.add(RenameAction.collide(file, targetFile));
                continue;
            }
            plan.add(RenameAction.rename(file, targetFile));
        }
        return plan;
    }

    /**
     * Lists media files below the folder, optionally recursing. Directories
     * come back after their siblings so per-episode folders are processed once
     * their own files are known.
     */
    static void collect(File root, boolean recursive, List<File> into, int depth) {
        File[] children = root.listFiles();
        if (children == null) return;
        List<File> dirs = new ArrayList<File>();
        for (File child : children) {
            if (child.isDirectory()) dirs.add(child);
            else if (isMediaFile(child.getName())) into.add(child);
        }
        if (recursive && depth < 16) {
            for (File dir : dirs) collect(dir, recursive, into, depth + 1);
        }
    }

    public static boolean isMediaFile(String name) {
        String lower = name.toLowerCase();
        for (String ext : DEFAULT_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    /** The name template used when the user supplies a custom pattern. */
    public static final class PatternBuilder {

        private PatternBuilder() {
        }

        /**
         * Supported tokens: {title} {year} {s01e01} {s1e1} {season} {episode}
         * {episodeTitle} {quality} {language} {ext} {original}.
         */
        public static String build(String pattern, FileNameParts parts) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                if (c != '{') {
                    sb.append(c);
                    continue;
                }
                int close = pattern.indexOf('}', i + 1);
                if (close < 0) {
                    sb.append(c);
                    continue;
                }
                String token = pattern.substring(i + 1, close);
                sb.append(tokenValue(token, parts));
                i = close;
            }
            return sb.toString()
                    .replaceAll("\\s+\\.", ".")   // no space before the extension
                    .replaceAll("\\s{2,}", " ")   // collapse gaps left by empty tokens
                    .trim();
        }

        private static String tokenValue(String token, FileNameParts p) {
            String t = token.toLowerCase();
            if ("title".equals(t)) return p.getTitle();
            if ("year".equals(t)) return p.getYear() > 0 ? Integer.toString(p.getYear()) : "";
            if ("season".equals(t)) return p.isEpisode() ? String.valueOf(p.getSeason()) : "";
            if ("episode".equals(t)) return p.isEpisode() ? String.valueOf(p.getEpisode()) : "";
            if ("episodetitle".equals(t)) return p.getEpisodeTitle();
            if ("quality".equals(t)) return p.getQuality() > 0 ? p.getQuality() + "P" : "";
            if ("language".equals(t)) return p.getLanguage();
            if ("ext".equals(t) || "extension".equals(t)) return p.getExtension();
            if ("original".equals(t)) return stripAnyExtension(p.getOriginalName());
            if ("s01e01".equals(t)) return p.isEpisode() ? "S" + pad2(p.getSeason()) + "E" + pad2(p.getEpisode()) : "";
            if ("s1e1".equals(t)) return p.isEpisode() ? p.getSeason() + "E" + p.getEpisode() : "";
            return "{" + token + "}";
        }

        private static String pad2(int n) { return n < 10 ? "0" + n : Integer.toString(n); }

        private static String stripAnyExtension(String name) {
            int dot = name.lastIndexOf('.');
            return dot > 0 ? name.substring(0, dot) : name;
        }
    }

    /** One pending rename (or a skipped collision). */
    public static final class RenameAction {
        public enum Kind { RENAME, COLLISION }

        public final Kind kind;
        public final File from;
        public final File to;
        private boolean done;

        private RenameAction(Kind kind, File from, File to) {
            this.kind = kind;
            this.from = from;
            this.to = to;
        }

        static RenameAction rename(File from, File to) { return new RenameAction(Kind.RENAME, from, to); }
        static RenameAction collide(File from, File to) { return new RenameAction(Kind.COLLISION, from, to); }

        public boolean isDone() { return done; }
    }

    /** A dry-run plan that can later be applied. */
    public static final class RenamePlan {

        private final List<RenameAction> actions = new ArrayList<RenameAction>();

        void add(RenameAction action) { actions.add(action); }

        public List<RenameAction> actions() {
            return Collections.unmodifiableList(actions);
        }

        public int renameCount() {
            int n = 0;
            for (RenameAction action : actions) {
                if (action.kind == RenameAction.Kind.RENAME) n++;
            }
            return n;
        }

        public boolean isEmpty() { return actions.isEmpty(); }

        /**
         * Executes the plan: files are first moved to unique temporary names,
         * then to their destinations. If anything fails the completed moves
         * are rolled back where possible.
         */
        public void apply() throws IOException {
            List<RenameAction> renames = new ArrayList<RenameAction>();
            for (RenameAction action : actions) {
                if (action.kind == RenameAction.Kind.RENAME && !action.from.equals(action.to)) renames.add(action);
            }
            List<File> tempNames = new ArrayList<File>();
            try {
                int sequence = 0;
                for (RenameAction action : renames) {
                    File tmp = new File(action.from.getParentFile(), ".mvtool-tmp-" + (sequence++) + "-" + action.from.getName());
                    move(action.from, tmp);
                    tempNames.add(tmp);
                    action.done = true;
                }
                for (int i = 0; i < renames.size(); i++) {
                    File destination = renames.get(i).to;
                    IoUtil.mkdirs(destination.getParentFile());
                    move(tempNames.get(i), destination);
                }
            } catch (IOException failure) {
                // Roll back in reverse: files at their destination or in temp go home.
                for (int i = renames.size() - 1; i >= 0; i--) {
                    RenameAction action = renames.get(i);
                    File temp = tempNames.get(i);
                    File home = action.from;
                    File current = temp.exists() ? temp : (action.to.exists() ? action.to : null);
                    if (current == null) continue;
                    try {
                        if (!home.exists()) move(current, home);
                    } catch (IOException ignore) {
                        // Best effort; the original failure is reported by the caller.
                    }
                }
                throw failure;
            }
        }

        private static void move(File from, File to) throws IOException {
            try {
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(from.toPath(), to.toPath());
            }
        }
    }
}
