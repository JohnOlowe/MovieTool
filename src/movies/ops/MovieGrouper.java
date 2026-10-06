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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Groups a flat library into one folder per movie/episode: the movie's videos
 * (EVERY quality variant of it), plus its subtitles - renamed to match the
 * video they belong to.
 *
 * <p>With a target convention (CLI {@code -t}, GUI dropdown) the videos are
 * ALSO renamed while they move, and the subtitles follow the video's NEW
 * name. Nameless videos can borrow the name of their episode's subtitle
 * ({@code --names-from subs}). Quality variants are kept distinct: the first
 * video of a group gets the clean name, the others keep quality/tag
 * markers. Matching prefers a video sitting in the SAME folder as the
 * subtitle, so already-grouped material is handled correctly on re-runs.</p>
 */
public class MovieGrouper {

    private static final String[] VIDEO_EXTENSIONS = { ".mp4", ".mkv", ".avi", ".mov", ".webm", ".m4v", ".ts" };
    private static final String[] SUBTITLE_EXTENSIONS = { ".srt", ".vtt", ".ass", ".ssa", ".sub" };

    private final ConventionRegistry registry = new ConventionRegistry();

    /**
     * Builds the move/rename plan without touching the disk.
     *
     * @param targetConventionId convention to rename files towards, or
     *                           null/blank to keep current names
     */
    public OperationResult plan(Options options, String targetConventionId) {
        OperationResult result = new OperationResult();
        File root = new File(options.getFolder());
        if (!root.isDirectory()) {
            result.add(Problem.error("Not a folder: " + options.getFolder()));
            return result;
        }
        List<NamingConvention> selected = registry.selected(options.getConventions());
        NamingConvention target = null;
        if (targetConventionId != null && !targetConventionId.trim().isEmpty()) {
            target = registry.get(targetConventionId.trim());
            if (target == null) {
                result.add(Problem.error("Unknown target convention: " + targetConventionId
                        + " (see 'movietool conventions')"));
                return result;
            }
        }

        // ---- subtitles first: their parsed parts feed the borrow pool.
        List<File> subs = new ArrayList<File>();
        collect(root, true, subs, SUBTITLE_EXTENSIONS);
        Map<File, FileNameParts> partsBySub = new HashMap<File, FileNameParts>();
        Map<String, FileNameParts> subDonors = new HashMap<String, FileNameParts>();
        for (File sub : subs) {
            FileNameParts parts = NameResolver.resolve(sub, registry, selected);
            partsBySub.put(sub, parts);
            if (parts != null && parts.isEpisode()) {
                subDonors.put(parts.getSeason() + "x" + parts.getEpisode(), parts);
            }
        }

        // ---- index ALL videos under the root (always recursive: matching
        // needs the full picture, and already-grouped folders must be seen).
        List<File> videos = new ArrayList<File>();
        collect(root, true, videos, VIDEO_EXTENSIONS);
        if (videos.isEmpty()) {
            result.add(Problem.warn("No video files found under " + root.getPath()));
            result.setReport("Nothing to group");
            return result;
        }
        Map<File, FileNameParts> partsByVideo = new HashMap<File, FileNameParts>();
        for (File video : videos) {
            FileNameParts parts = NameResolver.resolve(video, registry, selected);
            if (parts == null && options.isNameFromSubs()) {
                int[] raw = NameResolver.rawEpisode(video.getName());
                FileNameParts donor = raw == null ? null : subDonors.get(raw[0] + "x" + raw[1]);
                if (donor != null) {
                    parts = new FileNameParts(donor);
                    parts.setExtension(extensionOf(video.getName()));
                    parts.setOriginalName(video.getName());
                    result.add(Problem.info("Named from subtitle: " + video.getName()
                            + " takes its name from " + donor.getOriginalName()));
                }
            }
            partsByVideo.put(video, parts);
        }
        Map<String, List<File>> videosByKey = new LinkedHashMap<String, List<File>>();
        for (File video : videos) {
            String key = groupKey(video, partsByVideo.get(video));
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
            File first = entry.getValue().get(0);
            String name = folderName(first, partsByVideo.get(first));
            String base = name;
            for (int i = 2; usedNames.contains(name.toLowerCase(Locale.ROOT)); i++) {
                name = base + " (" + i + ")";
            }
            usedNames.add(name.toLowerCase(Locale.ROOT));
            folderNameByKey.put(entry.getKey(), name);
        }

        // ---- plan video moves (and optional renames).
        List<TransferAction> transfers = new ArrayList<TransferAction>();
        Map<File, File> homeByVideo = new HashMap<File, File>();
        Map<File, String> baseByVideo = new HashMap<File, String>();
        int videosMoved = 0;
        for (Map.Entry<String, List<File>> entry : videosByKey.entrySet()) {
            File dir = new File(root, folderNameByKey.get(entry.getKey()));
            Set<String> usedBases = new HashSet<String>();
            for (File video : entry.getValue()) {
                FileNameParts parts = partsByVideo.get(video);
                // Full target file name: the convention builds name AND
                // extension; keep-mode reuses the current name.
                String newName = video.getName();
                if (target != null && parts != null) {
                    String built = NameSanitizer.sanitize(target.build(parts), options.getReplaceWith());
                    if (!built.isEmpty()) newName = built;
                }
                String newBase = IoUtil.baseName(newName);
                String newExt = newName.substring(newBase.length());
                if (newExt.isEmpty()) newExt = extensionOf(video.getName());
                // Keep quality variants distinct within the group.
                if (!usedBases.add(newBase)) {
                    String suffix = parts != null && parts.getQuality() > 0 ? " " + parts.getQuality() + "P"
                            : parts != null && !parts.getTags().isEmpty() ? " " + joinTags(parts.getTags()) : "";
                    String candidate = newBase + suffix;
                    for (int i = 2; usedBases.contains(candidate); i++) {
                        candidate = newBase + suffix + " (" + i + ")";
                    }
                    newBase = candidate;
                    usedBases.add(newBase);
                }
                baseByVideo.put(video, newBase);

                File targetFile = new File(dir, newBase + newExt);
                File home;
                if (sameDir(video, dir) && video.getName().equals(targetFile.getName())) {
                    transfers.add(wrap(video, targetFile, TransferAction.State.ALREADY_THERE));
                    home = video.getParentFile();
                } else if (targetFile.exists() && !targetFile.equals(video) && !options.isOverwrite()) {
                    transfers.add(wrap(video, targetFile, TransferAction.State.SKIPPED_EXISTS));
                    home = video.getParentFile(); // stays where it is
                    result.add(Problem.warn("Video keeps its place (target exists): " + targetFile.getPath()));
                } else {
                    transfers.add(wrap(video, targetFile, TransferAction.State.PLANNED));
                    videosMoved++;
                    home = dir;
                }
                homeByVideo.put(video, home);
            }
        }

        // ---- match every subtitle and move it next to its video, renamed
        // like the video's FINAL name (kept or convention-renamed).
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
            String name = baseByVideo.get(video) + subtitleExtensionFor(sub);
            File targetFile = new File(dir, name);
            if (taken.contains(targetFile.getAbsolutePath())) {
                String base = IoUtil.baseName(name);
                String ext = name.substring(base.length());
                for (int i = 2; i < 100; i++) {
                    File candidate = new File(dir, base + " (" + i + ")" + ext);
                    if (!taken.contains(candidate.getAbsolutePath())) { targetFile = candidate; break; }
                }
            }
            if (sameFile(sub, targetFile)) {
                transfers.add(wrap(sub, targetFile, TransferAction.State.ALREADY_THERE));
            } else if (targetFile.exists() && !options.isOverwrite()) {
                transfers.add(wrap(sub, targetFile, TransferAction.State.SKIPPED_EXISTS));
                result.add(Problem.warn("Subtitle keeps its place (target exists): " + targetFile.getPath()));
            } else {
                transfers.add(wrap(sub, targetFile, TransferAction.State.PLANNED));
                subsMoved++;
            }
            taken.add(targetFile.getAbsolutePath());
        }

        int folderCount = homesOf(videos, homeByVideo).size();
        result.setTransfers(transfers);
        result.setReport(folderCount + " movie folder(s) for " + videos.size() + " video(s): "
                + videosMoved + " video(s) and " + subsMoved + " subtitle(s) to move"
                + (target != null ? " (renamed to " + target.id() + ")" : "")
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

    /**
     * Picks the video of a subtitle from its parsed group, preferring a video
     * in the SAME folder as the subtitle (already-grouped material), then an
     * identical normalised name, then the group's first video.
     */
    private File videoByKey(File sub, Map<String, List<File>> videosByKey, List<File> videos) {
        FileNameParts parts = NameResolver.resolve(sub, registry, registry.all());
        if (parts == null) return null;
        List<File> group = videosByKey.get(groupKey(sub, parts));
        if (group == null || group.isEmpty()) return null;
        if (group.size() == 1) return group.get(0);
        String normalised = NameResolver.normalisedBase(sub.getName());
        for (File video : group) {
            if (sameDir(sub, video.getParentFile())) return video;
        }
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

    private static String joinTags(List<String> tags) {
        StringBuilder sb = new StringBuilder();
        for (String tag : tags) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(tag);
        }
        return sb.toString();
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
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
            String ext = extensionOf(child.getName());
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
