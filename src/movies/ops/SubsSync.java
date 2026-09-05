package movies.ops;

import movies.core.ConventionRegistry;
import movies.core.FileNameParts;
import movies.core.NameResolver;
import movies.core.NamingConvention;
import movies.core.OperationResult;
import movies.core.Options;
import movies.core.Problem;
import movies.core.TransferAction;
import movies.util.IoUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Matches subtitles to videos and copies (or moves) each subtitle next to its
 * video under the video's exact name.
 *
 * <p>This generalises the old CopySubs/MovieSubtitlesRename pair: any naming
 * conventions are supported on both sides, matching is episode-aware, and the
 * plan can be previewed before anything is written.</p>
 */
public class SubsSync {

    private static final String[] VIDEO_EXTENSIONS = { ".mp4", ".mkv", ".avi", ".mov", ".webm", ".m4v", ".ts" };
    private static final String[] SUBTITLE_EXTENSIONS = { ".srt", ".vtt", ".ass", ".ssa", ".sub" };

    /** One indexed video candidate. */
    private static final class VideoCandidate {
        final File file;
        final FileNameParts parts;
        final int[] rawEpisode;
        final String normalised;

        VideoCandidate(File file, FileNameParts parts, int[] rawEpisode, String normalised) {
            this.file = file;
            this.parts = parts;
            this.rawEpisode = rawEpisode;
            this.normalised = normalised;
        }
    }

    private final ConventionRegistry registry = new ConventionRegistry();
    private final List<Problem> problems = new ArrayList<Problem>();

    public ConventionRegistry registry() {
        return registry;
    }

    /** Builds the transfer plan without touching the disk. */
    public OperationResult plan(Options options) {
        OperationResult result = new OperationResult();
        List<Problem> problems = this.problems;
        problems.clear();

        File videosRoot = new File(options.getFolder());
        if (!videosRoot.isDirectory()) {
            result.add(Problem.error("Videos folder is not a folder: " + options.getFolder()));
            return result;
        }
        boolean sameTree = options.getSecondaryFolder().trim().isEmpty();
        File subsRoot = sameTree ? videosRoot : new File(options.getSecondaryFolder());
        if (!subsRoot.isDirectory()) {
            result.add(Problem.error("Subtitles folder is not a folder: " + options.getSecondaryFolder()));
            return result;
        }

        List<NamingConvention> selected = registry.selected(options.getConventions());

        // ---- index the videos.
        List<VideoCandidate> videos = new ArrayList<VideoCandidate>();
        List<File> videoFiles = new ArrayList<File>();
        collectInto(videosRoot, options.isRecursive(), videoFiles, true);
        for (File file : videoFiles) {
            FileNameParts parts = NameResolver.resolve(file, registry, selected);
            videos.add(new VideoCandidate(file, parts, NameResolver.rawEpisode(file.getName()),
                    NameResolver.normalisedBase(file.getName())));
        }
        if (videos.isEmpty()) {
            result.add(Problem.warn("No video files found under " + videosRoot));
        }

        // ---- match every subtitle. Subtitles are always searched recursively:
        // they often arrive inside per-episode folders, which the old tool relied on.
        List<File> subFiles = new ArrayList<File>();
        collectInto(subsRoot, true, subFiles, false);
        Map<String, String> claimedTargets = new HashMap<String, String>();
        List<TransferAction> transfers = new ArrayList<TransferAction>();
        Set<String> usedVideos = new HashSet<String>();

        for (File sub : subFiles) {
            FileNameParts subParts = NameResolver.resolve(sub, registry, selected);
            VideoCandidate best = findBestVideo(videos, sub, subParts, usedVideos);
            if (best == null) {
                problems.add(Problem.warn("No matching video for: " + describe(sub, subsRoot)));
                continue;
            }
            String targetName = IoUtil.baseName(best.file.getName()) + subtitleExtensionFor(sub);
            File target = new File(best.file.getParentFile(), targetName);
            String targetPath = target.getAbsolutePath();

            if (target.equals(sub) || sameFile(target, sub)) {
                transfers.add(wrap(options, sub, target, TransferAction.State.ALREADY_THERE));
                usedVideos.add(best.file.getAbsolutePath());
                continue;
            }
            String previousOwner = claimedTargets.get(targetPath);
            if (previousOwner != null) {
                // A second language/variant: disambiguate with the language token or a counter.
                String alt = disambiguatedName(targetName, subParts, claimedTargets.keySet(), target.getParentFile());
                if (alt == null) {
                    problems.add(Problem.warn("Multiple subtitles target " + targetName
                            + "; keeping the first (" + previousOwner + "), skipping " + sub.getName()));
                    transfers.add(wrap(options, sub, target, TransferAction.State.COLLISION));
                    continue;
                }
                target = new File(target.getParentFile(), alt);
                targetPath = target.getAbsolutePath();
            }
            claimedTargets.put(targetPath, sub.getName());
            usedVideos.add(best.file.getAbsolutePath());

            if (target.exists() && !options.isOverwrite()) {
                transfers.add(wrap(options, sub, target, TransferAction.State.SKIPPED_EXISTS));
                continue;
            }
            transfers.add(wrap(options, sub, target, TransferAction.State.PLANNED));
        }

        result.setTransfers(transfers);
        result.setReport(summary(transfers));
        return result;
    }

    /** Executes a plan produced by {@link #plan}. */
    public void apply(OperationResult result, Options options) {
        for (TransferAction transfer : result.getTransfers()) {
            if (transfer.state != TransferAction.State.PLANNED) continue;
            try {
                IoUtil.mkdirs(transfer.to.getParentFile());
                if (options.isMove()) {
                    java.nio.file.Files.move(transfer.from.toPath(), transfer.to.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } else {
                    IoUtil.copy(transfer.from, transfer.to);
                }
                transfer.state = TransferAction.State.DONE;
            } catch (IOException e) {
                transfer.state = TransferAction.State.FAILED;
                result.add(Problem.error("Failed: " + transfer.from.getName() + " -> "
                        + transfer.to.getName() + ": " + e.getMessage()));
            }
        }
    }

    // ------------------------------------------------------------ matching

    private VideoCandidate findBestVideo(List<VideoCandidate> videos, File sub, FileNameParts subParts,
                                         Set<String> usedVideos) {
        // Tier 1 & 2: parse-based keys (episode key first, then movie key).
        if (subParts != null) {
            String episodeKey = subParts.episodeKey();
            String movieKey = subParts.matchKey();
            List<VideoCandidate> candidates = new ArrayList<VideoCandidate>();
            for (VideoCandidate video : videos) {
                if (video.parts == null) continue;
                String key = video.parts.episodeKey();
                if (key.equals(episodeKey) || key.equals(movieKey)) candidates.add(video);
            }
            VideoCandidate best = pick(candidates, sub, usedVideos);
            if (best != null) return best;
        }
        // Tier 3: raw episode marker in the name, unique across the library.
        int[] raw = NameResolver.rawEpisode(sub.getName());
        if (raw != null) {
            List<VideoCandidate> candidates = new ArrayList<VideoCandidate>();
            for (VideoCandidate video : videos) {
                int[] other = video.parts != null && video.parts.isEpisode()
                        ? new int[] { video.parts.getSeason(), video.parts.getEpisode() } : video.rawEpisode;
                if (other != null && other[0] == raw[0] && other[1] == raw[1]) candidates.add(video);
            }
            if (candidates.size() == 1) return candidates.get(0);
        }
        // Tier 4: identical normalised base names.
        String normalised = NameResolver.normalisedBase(sub.getName());
        List<VideoCandidate> candidates = new ArrayList<VideoCandidate>();
        for (VideoCandidate video : videos) {
            if (video.normalised.equals(normalised)) candidates.add(video);
        }
        return pick(candidates, sub, usedVideos);
    }

    private VideoCandidate pick(List<VideoCandidate> candidates, File sub, Set<String> usedVideos) {
        if (candidates.isEmpty()) return null;
        VideoCandidate best = null;
        int bestScore = Integer.MIN_VALUE;
        for (VideoCandidate candidate : candidates) {
            int score = 0;
            if (usedVideos.contains(candidate.file.getAbsolutePath())) score -= 5;
            if (NameResolver.normalisedBase(candidate.file.getName()).equals(NameResolver.normalisedBase(sub.getName()))) {
                score += 3;
            }
            if (best == null || score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    private TransferAction wrap(Options options, File from, File to, TransferAction.State state) {
        TransferAction action = new TransferAction(options.isMove() ? TransferAction.Kind.MOVE : TransferAction.Kind.COPY,
                from, to);
        action.state = state;
        return action;
    }

    /** Second subtitle for the same video gets a language or counter suffix. */
    private String disambiguatedName(String takenName, FileNameParts subParts, java.util.Set<String> taken,
                                     File parentDir) {
        String base = IoUtil.baseName(takenName);
        String ext = takenName.substring(base.length());
        if (subParts != null && !subParts.getLanguage().isEmpty()) {
            String candidate = base + "." + subParts.getLanguage() + ext;
            if (!taken.contains(new File(parentDir, candidate).getAbsolutePath())) return candidate;
        }
        for (int i = 2; i < 100; i++) {
            String candidate = base + " (" + i + ")" + ext;
            if (!taken.contains(new File(parentDir, candidate).getAbsolutePath())) return candidate;
        }
        return null;
    }

    private String subtitleExtensionFor(File sub) {
        return IoUtil.extensionOf(sub.getName()).isEmpty() ? ".srt" : IoUtil.extensionOf(sub.getName());
    }

    private boolean sameFile(File a, File b) {
        try {
            return a.getCanonicalPath().equals(b.getCanonicalPath());
        } catch (IOException e) {
            return a.getAbsolutePath().equals(b.getAbsolutePath());
        }
    }

    private String describe(File file, File root) {
        try {
            String rootPath = root.getCanonicalPath();
            String filePath = file.getCanonicalPath();
            if (filePath.startsWith(rootPath)) return filePath.substring(rootPath.length() + 1);
        } catch (IOException ignore) {
            // Fall through to the plain name.
        }
        return file.getName();
    }

    private String summary(List<TransferAction> transfers) {
        int planned = 0;
        int done = 0;
        int skipped = 0;
        int problems = 0;
        for (TransferAction t : transfers) {
            switch (t.state) {
                case PLANNED: planned++; break;
                case DONE: done++; break;
                case ALREADY_THERE:
                case SKIPPED_EXISTS: skipped++; break;
                default: problems++;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(transfers.size()).append(" subtitle(s): ");
        if (planned > 0) sb.append(planned).append(" planned, ");
        if (done > 0) sb.append(done).append(" done, ");
        if (skipped > 0) sb.append(skipped).append(" already in place/skipped, ");
        if (problems > 0) sb.append(problems).append(" conflicts, ");
        return sb.toString().replaceAll(", $", "");
    }

    private static void collectInto(File root, boolean recursive, List<File> into, boolean videoMode) {
        File[] children = root.listFiles();
        if (children == null) return;
        List<File> dirs = new ArrayList<File>();
        for (File child : children) {
            if (child.isDirectory()) {
                dirs.add(child);
                continue;
            }
            String name = child.getName();
            if (name.startsWith(".")) continue;
            String ext = IoUtil.extensionOf(name);
            boolean wanted = videoMode ? in(VIDEO_EXTENSIONS, ext) : in(SUBTITLE_EXTENSIONS, ext);
            if (wanted) into.add(child);
        }
        if (recursive) {
            for (File dir : dirs) collectInto(dir, recursive, into, videoMode);
        }
    }

    private static boolean in(String[] extensions, String ext) {
        for (String candidate : extensions) {
            if (candidate.equals(ext)) return true;
        }
        return false;
    }
}
