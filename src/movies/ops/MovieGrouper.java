package movies.ops;

import movies.core.ConventionRegistry;
import movies.core.FileNameParts;
import movies.core.NameResolver;
import movies.core.NamingConvention;
import movies.core.NameSanitizer;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Groups a flat library into one folder per movie/episode: the movie's videos
 * (EVERY quality variant of it), plus its subtitles - renamed to match the
 * video they belong to. This restores the original tool's "movie per folder"
 * behaviour on top of the current conventions and episode-aware matching.
 *
 * <p>Grouping key: the parsed title + season/episode, so "Movie 720P" and
 * "Movie 1080P" land together, while different episodes get their own
 * folders. Subtitles are matched with the same tier logic as the subtitle
 * sync, so per-episode folders on the subtitle side are handled too.</p>
 */
public class MovieGrouper {

    private static final String[] VIDEO_EXTENSIONS = { ".mp4", ".mkv", ".avi", ".mov", ".webm", ".m4v", ".ts" };
    private static final String[] SUBTITLE_EXTENSIONS = { ".srt", ".vtt", ".ass", ".ssa", ".sub" };

    private final ConventionRegistry registry = new ConventionRegistry();

    /** Builds the move plan without touching the disk. */
    public OperationResult plan(Options options) {
        OperationResult result = new OperationResult();
        File root = new File(options.getFolder());
        if (!root.isDirectory()) {
            result.add(Problem.error("Not a folder: " + options.getFolder()));
            return result;
        }
        List<NamingConvention> selected = registry.selected(options.getConventions());

        // ---- index videos and group them by movie/episode key.
        List<File> videos = new ArrayList<File>();
        collect(root, options.isRecursive(), videos, VIDEO_EXTENSIONS);
        if (videos.isEmpty()) {
            result.add(Problem.warn("No video files found under " + root.getPath()));
            result.setReport("Nothing to group");
            return result;
        }
        Map<File, FileNameParts> partsByVideo = new HashMap<File, FileNameParts>();
        Map<String, List<File>> videosByKey = new LinkedHashMap<String, List<File>>();
        for (File video : videos) {
            FileNameParts parts = NameResolver.resolve(video, registry, selected);
            partsByVideo.put(video, parts);
            String key = groupKey(video, parts);
            List<File> group = videosByKey.get(key);
            if (group == null) {
                group = new ArrayList<File>();
                videosByKey.put(key, group);
            }
            group.add(video);
        }

        // ---- one folder name per key, unique and Windows-safe.
        Map<String, String> folderNameByKey = new LinkedHashMap<String, String>();
        Set<String> usedNames = new HashSet<String>();
        for (Map.Entry<String, List<File>> entry : videosByKey.entrySet()) {
            String name = folderName(entry.getValue().get(0), partsByVideo.get(entry.getValue().get(0)));
            String base = name;
            for (int i = 2; usedNames.contains(name.toLowerCase()); i++) {
                name = base + " (" + i + ")";
            }
            usedNames.add(name.toLowerCase());
            folderNameByKey.put(entry.getKey(), name);
        }

        // ---- plan the video moves and remember where each video ends up.
        List<TransferAction> transfers = new ArrayList<TransferAction>();
        Map<File, File> homeByVideo = new HashMap<File, File>();
        int videosMoved = 0;
        for (Map.Entry<String, List<File>> entry : videosByKey.entrySet()) {
            File dir = new File(root, folderNameByKey.get(entry.getKey()));
            for (File video : entry.getValue()) {
                File target = new File(dir, video.getName());
                File home;
                if (sameDir(video, dir)) {
                    transfers.add(wrap(video, target, TransferAction.State.ALREADY_THERE));
                    home = video.getParentFile();
                } else if (target.exists() && !options.isOverwrite()) {
                    transfers.add(wrap(video, target, TransferAction.State.SKIPPED_EXISTS));
                    home = video.getParentFile(); // stays where it is
                    result.add(Problem.warn("Video keeps its place (target exists): " + target.getPath()));
                } else {
                    transfers.add(wrap(video, target, TransferAction.State.PLANNED));
                    videosMoved++;
                    home = dir;
                }
                homeByVideo.put(video, home);
            }
        }

        // ---- match every subtitle (recursive: per-episode folders happen)
        // and move it next to its video, renamed like the video.
        List<File> subs = new ArrayList<File>();
        collect(root, true, subs, SUBTITLE_EXTENSIONS);
        Set<String> taken = new HashSet<String>();
        for (TransferAction t : transfers) taken.add(t.to.getAbsolutePath());
        int subsMoved = 0;
        int unmatched = 0;
        for (File sub : subs) {
            File video = videoByKey(sub, videosByKey, videos);
            if (video == null) video = SubsSync.matchSubtitle(sub, videos);
            if (video == null) {
                result.add(Problem.warn("No matching movie for: " + sub.getName()));
                unmatched++;
                continue;
            }
            File dir = homeByVideo.get(video);
            String name = IoUtil.baseName(video.getName()) + subtitleExtensionFor(sub);
            File target = new File(dir, name);
            if (taken.contains(target.getAbsolutePath())) {
                // A second subtitle for the same video: disambiguate by counter.
                String base = IoUtil.baseName(name);
                String ext = name.substring(base.length());
                for (int i = 2; i < 100; i++) {
                    File candidate = new File(dir, base + " (" + i + ")" + ext);
                    if (!taken.contains(candidate.getAbsolutePath())) { target = candidate; break; }
                }
            }
            if (sameFile(sub, target)) {
                transfers.add(wrap(sub, target, TransferAction.State.ALREADY_THERE));
            } else if (target.exists() && !options.isOverwrite()) {
                transfers.add(wrap(sub, target, TransferAction.State.SKIPPED_EXISTS));
                result.add(Problem.warn("Subtitle keeps its place (target exists): " + target.getPath()));
            } else {
                transfers.add(wrap(sub, target, TransferAction.State.PLANNED));
                subsMoved++;
            }
            taken.add(target.getAbsolutePath());
        }

        int folderCount = homesOf(videos, homeByVideo).size();
        result.setTransfers(transfers);
        result.setReport(folderCount + " movie folder(s) for " + videos.size() + " video(s): "
                + videosMoved + " video(s) and " + subsMoved + " subtitle(s) to move"
                + (unmatched > 0 ? ", " + unmatched + " subtitle(s) unmatched" : ""));
        return result;
    }

    /** Executes a plan produced by {@link #plan}. Always moves. */
    public void apply(OperationResult result, Options options) {
        for (TransferAction transfer : result.getTransfers()) {
            if (transfer.state != TransferAction.State.PLANNED) continue;
            try {
                IoUtil.mkdirs(transfer.to.getParentFile());
                java.nio.file.Files.move(transfer.from.toPath(), transfer.to.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                transfer.state = TransferAction.State.DONE;
            } catch (IOException e) {
                transfer.state = TransferAction.State.FAILED;
                result.add(Problem.error("Failed: " + transfer.from.getName() + " -> "
                        + transfer.to.getName() + ": " + e.getMessage()));
            }
        }
    }

    // ------------------------------------------------------------ helpers

    /** First try the subtitle's own parsed group key, so it joins its movie even among quality variants. */
    private File videoByKey(File sub, Map<String, List<File>> videosByKey, List<File> videos) {
        ConventionRegistry registry = new ConventionRegistry();
        FileNameParts parts = NameResolver.resolve(sub, registry, registry.all());
        if (parts == null) return null;
        String key = groupKey(sub, parts);
        List<File> group = videosByKey.get(key);
        if (group == null || group.isEmpty()) return null;
        // One video in the group is the obvious target; several qualities:
        // prefer a video whose base name matches the subtitle's.
        if (group.size() == 1) return group.get(0);
        String normalised = NameResolver.normalisedBase(sub.getName());
        for (File video : group) {
            if (NameResolver.normalisedBase(video.getName()).equals(normalised)) return video;
        }
        return group.get(0);
    }

    /** Movie/episode grouping key: parsed title+episode when possible. */
    private static String groupKey(File file, FileNameParts parts) {
        if (parts != null && parts.getTitle() != null && !parts.getTitle().isEmpty()) {
            return parts.episodeKey();
        }
        // Unparsed: the normalised base with quality tokens removed, so the
        // 720p and 1080p variants of an unknown movie still group together.
        String[] tokens = NameResolver.normalisedBase(file.getName()).split(" ");
        StringBuilder sb = new StringBuilder();
        for (String token : tokens) {
            if (qualityLike(token)) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(token);
        }
        return sb.toString().trim();
    }

    private static boolean qualityLike(String token) {
        return token.matches("(?i)\\d{3,4}p");
    }

    /** Folder name for a movie/episode: "Title SxxEyy" or "Title (year)". */
    private String folderName(File video, FileNameParts parts) {
        if (parts != null && parts.getTitle() != null && !parts.getTitle().isEmpty()) {
            StringBuilder sb = new StringBuilder(parts.getTitle().trim());
            if (parts.isEpisode()) {
                sb.append(" S").append(parts.getSeason() < 10 ? "0" : "")
                  .append(parts.getSeason()).append('E').append(parts.getEpisode() < 10 ? "0" : "")
                  .append(parts.getEpisode());
            } else if (parts.getYear() > 0) {
                sb.append(" (").append(parts.getYear()).append(')');
            }
            return NameSanitizer.sanitize(sb.toString(), "");
        }
        return NameSanitizer.sanitize(NameResolver.normalisedBase(video.getName()), "");
    }

    private static Set<String> homesOf(List<File> videos, Map<File, File> homeByVideo) {
        Set<String> out = new HashSet<String>();
        for (File video : videos) {
            File home = homeByVideo.get(video);
            if (home != null) out.add(home.getPath());
        }
        return out;
    }

    private TransferAction wrap(File from, File to, TransferAction.State state) {
        TransferAction action = new TransferAction(TransferAction.Kind.MOVE, from, to);
        action.state = state;
        return action;
    }

    private static void collect(File root, boolean recursive, List<File> into, String[] extensions) {
        File[] children = root.listFiles();
        if (children == null) return;
        List<File> dirs = new ArrayList<File>();
        for (File child : children) {
            if (child.isDirectory()) {
                dirs.add(child);
                continue;
            }
            if (child.getName().startsWith(".")) continue;
            String ext = IoUtil.extensionOf(child.getName());
            for (String candidate : extensions) {
                if (candidate.equals(ext)) { into.add(child); break; }
            }
        }
        if (recursive) {
            for (File dir : dirs) collect(dir, recursive, into, extensions);
        }
    }

    private static String subtitleExtensionFor(File sub) {
        return IoUtil.extensionOf(sub.getName()).isEmpty() ? ".srt" : IoUtil.extensionOf(sub.getName());
    }

    private static boolean sameDir(File file, File dir) {
        try {
            return file.getParentFile().getCanonicalPath().equals(dir.getCanonicalPath());
        } catch (IOException e) {
            return file.getParentFile().equals(dir);
        }
    }

    private static boolean sameFile(File a, File b) {
        try {
            return a.getCanonicalPath().equals(b.getCanonicalPath());
        } catch (IOException e) {
            return a.getAbsolutePath().equals(b.getAbsolutePath());
        }
    }
}
