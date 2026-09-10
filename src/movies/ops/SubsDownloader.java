package movies.ops;

import movies.core.NameResolver;
import movies.core.NamingConvention;
import movies.core.ConventionRegistry;
import movies.core.FileNameParts;
import movies.core.OperationResult;
import movies.core.Options;
import movies.core.Problem;
import movies.util.IoUtil;
import movies.util.Json;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.io.RandomAccessFile;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Bulk-downloads subtitles for videos that have none, using the free
 * OpenSubtitles REST API (api.opensubtitles.com), and names each downloaded
 * subtitle exactly like its video so any player picks it up automatically.
 *
 * <p>How it finds the right subtitle, per video:</p>
 * <ol>
 *   <li>Compute the OpenSubtitles movie hash of the file (a fingerprint of
 *       the file size plus the first and last 64 KiB) and search by it - this
 *       finds the EXACT release, even for gibberish file names.</li>
 *   <li>No hit? Search by title (and season/episode/year when the file name
 *       reveals them).</li>
 * </ol>
 *
 * <p>A free API key is required (opensubtitles.com - user registration, then
 * API keys); it can be given per run or remembered from the GUI. Free keys
 * have a daily download quota, which the tool reports instead of hammering.</p>
 *
 * <p>Dry run (the default) works offline: it only lists the videos that have
 * no subtitle yet. Applying does the network work.</p>
 *
 * <p>Quotas (free tiers): OpenSubtitles allows 5 downloads/day with the API
 * key alone and 20/day once the OpenSubtitles account is given (this class
 * logs in and sends the Bearer token automatically); VIP accounts get
 * 1000/day. As a fallback provider a free SubDL key (subdl.com) adds
 * 50 downloads/day - when OpenSubtitles has no match or its quota is used
 * up, SubDL is tried automatically.</p>
 */
public class SubsDownloader {

    private static final String[] VIDEO_EXTENSIONS = { ".mp4", ".mkv", ".avi", ".mov", ".webm", ".m4v", ".ts" };
    private static final String[] SUBTITLE_EXTENSIONS = { ".srt", ".vtt", ".ass", ".ssa", ".sub" };

    /** Bearer token from the OpenSubtitles /login call (this apply() run). */
    private String bearerToken;
    /** Set when OpenSubtitles reports the daily download quota is used up. */
    private boolean osQuotaExhausted;

    /** Where a video's subtitle belongs (and is looked for). */
    private File subtitleTarget(Options options, File video) {
        File directory = options.isSubsIntoFolder()
                ? new File(video.getParentFile(), SubsRelocator.SUBTITLE_FOLDER)
                : video.getParentFile();
        return new File(directory, IoUtil.baseName(video.getName()) + ".srt");
    }

    /** True when the video already has a subtitle next to it (any known form). */
    private static boolean hasSubtitleNear(File video) {
        String base = IoUtil.baseName(video.getName());
        File[] siblings = video.getParentFile().listFiles();
        if (siblings == null) return false;
        for (File sibling : siblings) {
            if (!sibling.isFile()) continue;
            String lower = sibling.getName().toLowerCase(Locale.ROOT);
            if (!lower.startsWith(base.toLowerCase(Locale.ROOT) + ".")) continue;
            for (String extension : SUBTITLE_EXTENSIONS) {
                if (lower.endsWith(extension)) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ plan

    /** Lists every video without a subtitle. Offline - no API calls. */
    public OperationResult plan(Options options) {
        OperationResult result = new OperationResult();

        File root = new File(options.getFolder());
        if (!root.isDirectory()) {
            result.add(Problem.error("Not a folder: " + options.getFolder()));
            return result;
        }

        List<File> videos = new ArrayList<File>();
        collectVideos(root, options.isRecursive(), videos);
        if (videos.isEmpty()) {
            result.add(Problem.warn("No video files found under " + root.getPath()));
            return result;
        }

        List<File> missing = new ArrayList<File>();
        for (File video : videos) {
            boolean covered = hasSubtitleNear(video)
                    || subtitleTarget(options, video).exists()
                    || options.isSubsIntoFolder() && hasSubtitleNear(video);
            if (covered) continue;
            missing.add(video);
            result.add(Problem.info("No subtitle: " + describe(root, video)));
        }
        for (File video : videos) {
            if (!missing.contains(video)) {
                result.add(Problem.info("Has subtitle: " + describe(root, video)));
            }
        }

        result.setMissingVideos(missing);
        result.setReport(missing.size() + " of " + videos.size() + " video(s) need subtitles."
                + (missing.isEmpty() ? "" : " Apply to download them (OpenSubtitles, language '"
                        + options.getSubsLanguage() + "')."));
        return result;
    }

    /** Downloads subtitles for the videos listed by {@link #plan}. */
    public void apply(OperationResult result, Options options) {
        List<File> missing = result.getMissingVideos();
        if (missing == null) {
            result.add(Problem.error("Run a dry run first."));
            return;
        }
        if (missing.isEmpty()) return;

        String apiKey = resolveApiKey(options);
        if (apiKey.isEmpty()) {
            result.add(Problem.error("No OpenSubtitles API key. Get a free one: opensubtitles.com -> "
                    + "user settings -> API Keys. Pass --api-key (CLI) or save it in the GUI."));
            return;
        }

        try {
            bearerToken = login(options, apiKey);
        } catch (IOException e) {
            result.add(Problem.warn("OpenSubtitles login failed: " + rootMessage(e)
                    + " - continuing with the key-only quota."));
        }

        int done = 0;
        int failed = 0;
        for (File video : missing) {
            File target = subtitleTarget(options, video);
            if (target.exists()) {
                result.add(Problem.info("Skipped, appeared meanwhile: " + target.getName()));
                continue;
            }
            try {
                File downloaded = downloadFor(options, apiKey, video, target);
                if (downloaded != null) {
                    done++;
                    result.add(Problem.info("Downloaded: " + describe(new File(options.getFolder()), downloaded)));
                } else {
                    failed++;
                    result.add(Problem.warn("No subtitle found for " + describe(new File(options.getFolder()), video)));
                }
            } catch (QuotaException e) {
                result.add(Problem.warn(e.getMessage() + " - stopping (quota is per day; try again tomorrow)."));
                break;
            } catch (IOException e) {
                failed++;
                result.add(Problem.error("Failed for " + video.getName() + ": " + rootMessage(e)));
            }
        }
        result.setReport(result.getReport()
                + (result.getReport().isEmpty() ? "" : " ")
                + "Downloaded " + done + ", not found/failed " + failed + ".");
    }

    // --------------------------------------------------------------- search

    private File downloadFor(Options options, String apiKey, File video, File target) throws IOException {
        boolean quotaHit = false;
        if (!osQuotaExhausted) {
            Map<String, Object> release;
            try {
                release = findByHash(options, apiKey, video);
                if (release == null) release = findByTitle(options, apiKey, video);
            } catch (QuotaException e) {
                osQuotaExhausted = true;
                quotaHit = true;
                release = null;
            }
            if (release != null) {
                File downloaded = downloadOpenSubtitles(options, apiKey, release, target);
                if (downloaded != null) return downloaded;
            }
        }
        // Fallback provider (or the only one left when the quota is gone).
        if (!options.getSubdlApiKey().isEmpty()) {
            File viaSubdl = downloadViaSubdl(options, video, target);
            if (viaSubdl != null) return viaSubdl;
        }
        if (quotaHit) {
            throw new QuotaException("OpenSubtitles daily download quota reached"
                    + (options.getSubdlApiKey().isEmpty() ? " (a free SubDL key would add 50 downloads/day)"
                                                          : " and SubDL had no match"));
        }
        return null;
    }

    private File downloadOpenSubtitles(Options options, String apiKey,
                                       Map<String, Object> release, File target) throws IOException {
        Map<String, Object> file = pickSubtitleFile(release);
        if (file == null) return null;
        Object fileId = file.get("file_id");
        if (fileId == null) return null;

        // Request a download link for that file.
        Map<String, Object> download = Json.parseObject(post(
                options.getSubsApiBase() + "/api/v1/download",
                "{\"file_id\":" + String.valueOf(fileId) + "}", apiKey));
        Object link = download.get("link");
        if (!(link instanceof String) || ((String) link).isEmpty()) {
            throw new IOException("no download link in the API response");
        }
        byte[] bytes = fetch((String) link);
        String name = download.get("file_name") instanceof String ? (String) download.get("file_name") : null;
        byte[] unpacked = unpack(bytes, name);
        String extension = extensionOf(name, unpacked);

        File finalTarget = new File(target.getParentFile(), IoUtil.baseName(target.getName()) + extension);
        IoUtil.mkdirs(finalTarget.getParentFile());
        IoUtil.writeAll(finalTarget, unpacked);
        return finalTarget;
    }

    // -------------------------------------------------------------- SubDL

    /**
     * Free fallback provider: subdl.com (2,000 searches + 50 downloads per
     * day with the free key). Searches by file name first, then by film
     * name; for series the per-episode files of a pack are preferred.
     */
    private File downloadViaSubdl(Options options, File video, File target) throws IOException {
        String key = options.getSubdlApiKey();
        String language = options.getSubsLanguage().toUpperCase(Locale.ROOT);
        FileNameParts parts = NameResolver.resolve(video, new ConventionRegistry(), new ConventionRegistry().selected(""));
        boolean series = parts != null && parts.isEpisode();

        String query = options.getSubdlApiBase() + "/api/v1/subtitles?api_key="
                + URLEncoder.encode(key, "UTF-8")
                + "&file_name=" + URLEncoder.encode(video.getName(), "UTF-8")
                + "&languages=" + URLEncoder.encode(language, "UTF-8")
                + "&unpack=1&subs_per_page=10";
        Map<String, Object> response = Json.parseObject(get(query, null));
        List<Map<String, Object>> subtitles = subdlSubtitles(response);
        if (subtitles.isEmpty()) {
            String title = parsedTitle(video);
            if (!title.isEmpty()) {
                StringBuilder url = new StringBuilder(options.getSubdlApiBase());
                url.append("/api/v1/subtitles?api_key=").append(URLEncoder.encode(key, "UTF-8"));
                url.append("&film_name=").append(URLEncoder.encode(title, "UTF-8"));
                url.append("&languages=").append(URLEncoder.encode(language, "UTF-8"));
                url.append("&unpack=1&subs_per_page=10");
                if (series) {
                    url.append("&type=tv&season_number=").append(parts.getSeason());
                    url.append("&episode_number=").append(parts.getEpisode());
                } else {
                    url.append("&type=movie");
                    if (parts != null && parts.getYear() > 0) url.append("&year=").append(parts.getYear());
                }
                response = Json.parseObject(get(url.toString(), null));
                subtitles = subdlSubtitles(response);
            }
        }

        Map<String, Object> chosen = pickSubdl(subtitles, series && parts != null ? parts.getSeason() : -1,
                series ? parts.getEpisode() : -1);
        if (chosen == null) return null;

        String insideUrl = String.valueOf(chosen.get("url"));
        byte[] bytes = fetch(options.getSubdlDownloadBase() + insideUrl);
        String name = chosen.get("name") instanceof String ? (String) chosen.get("name") : null;
        byte[] unpacked = unpack(bytes, name);
        String extension = extensionOf(name, unpacked);

        File finalTarget = new File(target.getParentFile(), IoUtil.baseName(target.getName()) + extension);
        IoUtil.mkdirs(finalTarget.getParentFile());
        IoUtil.writeAll(finalTarget, unpacked);
        return finalTarget;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> subdlSubtitles(Map<String, Object> response) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        Object status = response.get("status");
        if (Boolean.FALSE.equals(status)) return out;
        Object subtitles = response.get("subtitles");
        if (!(subtitles instanceof List)) return out;
        for (Object item : (List<Object>) subtitles) {
            if (item instanceof Map && ((Map<?, ?>) item).get("url") != null) {
                out.add((Map<String, Object>) item);
            }
        }
        return out;
    }

    /**
     * Chooses a subtitle: exact season/episode per-episode files of a pack
     * first, then any per-episode file, then non-full-season entries, then
     * anything at all.
     */
    private static Map<String, Object> pickSubdl(List<Map<String, Object>> subtitles, int season, int episode) {
        Map<String, Object> anyEpisodeFile = null;
        Map<String, Object> plain = null;
        Map<String, Object> any = null;
        for (Map<String, Object> sub : subtitles) {
            Object unpackFiles = sub.get("unpack_files");
            if (unpackFiles instanceof List) {
                for (Object item : (List<?>) unpackFiles) {
                    if (!(item instanceof Map)) continue;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> file = (Map<String, Object>) item;
                    if (file.get("url") == null) continue;
                    if (any == null) any = file;
                    if (anyEpisodeFile == null) anyEpisodeFile = file;
                    int fileSeason = intOf(file.get("season"));
                    int fileEpisode = intOf(file.get("episode"));
                    if (season > 0 && fileSeason == season && episode > 0 && fileEpisode == episode) return file;
                }
            }
            boolean fullSeason = Boolean.TRUE.equals(sub.get("full_season"));
            if (!fullSeason && plain == null) plain = sub;
            if (any == null) any = sub;
        }
        if (anyEpisodeFile != null) return anyEpisodeFile;
        if (plain != null) return plain;
        return any;
    }

    private static int intOf(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // --------------------------------------------------------------- login

    /**
     * OpenSubtitles /login: exchanges the account's username/password for a
     * Bearer token that carries the ACCOUNT's daily quota (20/day free,
     * 1000/day VIP) instead of the 5/day key-only quota. Returns "" when no
     * credentials are configured.
     */
    private String login(Options options, String apiKey) throws IOException {
        String user = options.getOsUser();
        String password = options.getOsPassword();
        if (user.isEmpty()) user = env("OPENSUBTITLES_USER");
        if (password.isEmpty()) password = env("OPENSUBTITLES_PASSWORD");
        if (user.isEmpty() || password.isEmpty()) return "";
        Map<String, Object> response = Json.parseObject(post(
                options.getSubsApiBase() + "/api/v1/login",
                "{\"username\":\"" + Json.escape(user) + "\",\"password\":\"" + Json.escape(password) + "\"}",
                apiKey));
        Object token = response.get("token");
        return token instanceof String ? (String) token : "";
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value.trim();
    }

    /** Search by the OpenSubtitles file hash - exact release match. */
    private Map<String, Object> findByHash(Options options, String apiKey, File video) throws IOException {
        String hash = movieHash(video);
        Map<String, Object> response = Json.parseObject(get(options.getSubsApiBase()
                + "/api/v1/subtitles?moviehash=" + hash + "&languages="
                + URLEncoder.encode(options.getSubsLanguage(), "UTF-8"), apiKey));
        return firstRelease(response);
    }

    /** Fallback: search by parsed title / season / episode / year. */
    private Map<String, Object> findByTitle(Options options, String apiKey, File video) throws IOException {
        String title = parsedTitle(video);
        if (title.isEmpty()) return null;
        ConventionRegistry registry = new ConventionRegistry();
        List<NamingConvention> selected = registry.selected("");
        StringBuilder url = new StringBuilder(options.getSubsApiBase());
        url.append("/api/v1/subtitles?query=").append(URLEncoder.encode(title, "UTF-8"));
        url.append("&languages=").append(URLEncoder.encode(options.getSubsLanguage(), "UTF-8"));
        FileNameParts parts = NameResolver.resolve(video, registry, selected);
        if (parts != null && parts.isEpisode()) {
            url.append("&season=").append(parts.getSeason());
            url.append("&episode=").append(parts.getEpisode());
        }
        if (parts != null && parts.getYear() > 0) {
            url.append("&year=").append(parts.getYear());
        }
        return firstRelease(Json.parseObject(get(url.toString(), apiKey)));
    }

    /** First search result that actually carries a downloadable file. */
    private static Map<String, Object> firstRelease(Map<String, Object> response) {
        Object data = response.get("data");
        if (!(data instanceof List)) return null;
        for (Object item : (List<?>) data) {
            if (!(item instanceof Map)) continue;
            Map<?, ?> entry = (Map<?, ?>) item;
            Object attributes = entry.get("attributes");
            if (!(attributes instanceof Map)) continue;
            Object files = ((Map<?, ?>) attributes).get("files");
            if (files instanceof List && !((List<?>) files).isEmpty()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) entry;
                return cast;
            }
        }
        return null;
    }

    /** Prefer an .srt inside the release; fall back to the first file. */
    private static Map<String, Object> pickSubtitleFile(Map<String, Object> release) {
        Object attributes = release.get("attributes");
        if (!(attributes instanceof Map)) return null;
        Object files = ((Map<?, ?>) attributes).get("files");
        if (!(files instanceof List)) return null;
        Map<String, Object> first = null;
        for (Object item : (List<?>) files) {
            if (!(item instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> file = (Map<String, Object>) item;
            if (file.get("file_id") == null) continue;
            String name = String.valueOf(file.get("file_name"));
            if (name.toLowerCase(Locale.ROOT).endsWith(".srt")) return file;
            if (first == null) first = file;
        }
        return first;
    }

    /** Best-effort clean title from the file name, for the query fallback. */
    private static String parsedTitle(File video) {
        try {
            ConventionRegistry registry = new ConventionRegistry();
            FileNameParts parts = NameResolver.resolve(video, registry, registry.selected(""));
            if (parts != null && !parts.getTitle().trim().isEmpty()) {
                return parts.getTitle().trim();
            }
        } catch (RuntimeException ignore) {
            // fall through to the raw name
        }
        String base = IoUtil.baseName(video.getName());
        base = base.replaceAll("[._]+", " ").replaceAll("(?i)\\b(x26[45]|h\\.?26[45]|xvid|web-?rip|web-?dl|bluray|brrip|hdrip|1080p|720p|480p|2160p)\\b", "");
        return base.replaceAll("\\s+", " ").trim();
    }

    // ------------------------------------------------------------- plumbing

    /**
     * The OpenSubtitles hash: file size plus the little-endian 64-bit sums of
     * the first and last 64 KiB, all modulo 2^64.
     */
    public static String movieHash(File file) throws IOException {
        long size = file.length();
        long hash = size;
        long chunkSize = 64 * 1024;
        RandomAccessFile access = new RandomAccessFile(file, "r");
        try {
            long head = Math.min(size, chunkSize);
            for (long read = 0; read < head; read += 8) {
                hash += readLittleEndian(access, read, head);
            }
            long tailStart = Math.max(0, size - chunkSize);
            access.seek(tailStart);
            long tail = size - tailStart;
            for (long read = 0; read < tail; read += 8) {
                hash += readLittleEndian(access, tailStart + read, tail);
            }
        } finally {
            access.close();
        }
        return Long.toUnsignedString(hash);
    }

    private static long readLittleEndian(RandomAccessFile access, long offset, long limit) throws IOException {
        access.seek(offset);
        long value = 0;
        for (int i = 0; i < 8; i++) {
            long position = offset + i;
            long byteValue = position < limit ? access.read() : 0;
            if (byteValue < 0) byteValue = 0;
            value |= (byteValue & 0xFF) << (8 * i);
        }
        return value;
    }

    /** Zip / gzip / raw subtitle bytes -> plain subtitle bytes. */
    static byte[] unpack(byte[] bytes, String insideName) throws IOException {
        if (bytes.length >= 2 && bytes[0] == 'P' && bytes[1] == 'K') {
            ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(bytes));
            try {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    return IoUtil.readAll(zip, Integer.MAX_VALUE);
                }
                throw new IOException("the downloaded zip is empty");
            } finally {
                zip.close();
            }
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B) {
            GZIPInputStream gzip = new GZIPInputStream(new java.io.ByteArrayInputStream(bytes));
            try {
                return IoUtil.readAll(gzip, Integer.MAX_VALUE);
            } finally {
                gzip.close();
            }
        }
        return bytes;
    }

    private static String extensionOf(String name, byte[] content) {
        if (name != null) {
            String lower = name.toLowerCase(Locale.ROOT);
            for (String extension : SUBTITLE_EXTENSIONS) {
                if (lower.endsWith(extension)) return extension;
            }
        }
        return ".srt";
    }

    private String resolveApiKey(Options options) {
        if (!options.getApiKey().isEmpty()) return options.getApiKey();
        String environment = System.getenv("OPENSUBTITLES_API_KEY");
        return environment == null ? "" : environment.trim();
    }

    private static String describe(File root, File file) {
        String path = file.getPath();
        String prefix = root.getPath().endsWith(File.separator)
                ? root.getPath() : root.getPath() + File.separator;
        return path.startsWith(prefix) ? path.substring(prefix.length()) : path;
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static void collectVideos(File dir, boolean recursive, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isHidden() || child.getName().startsWith(".")) continue;
            if (child.isDirectory()) {
                if (SubsRelocator.SUBTITLE_FOLDER.equalsIgnoreCase(child.getName())) continue;
                if (recursive) collectVideos(child, true, out);
            } else {
                String lower = child.getName().toLowerCase(Locale.ROOT);
                for (String extension : VIDEO_EXTENSIONS) {
                    if (lower.endsWith(extension)) { out.add(child); break; }
                }
            }
        }
    }

    // ------------------------------------------------------------- http

    /** Thrown on 406/429: the daily download quota is used up. */
    static class QuotaException extends IOException {
        QuotaException(String message) { super(message); }
    }

    private String get(String url, String apiKey) throws IOException {
        return request("GET", url, apiKey, null);
    }

    private String post(String url, String body, String apiKey) throws IOException {
        return request("POST", url, apiKey, body);
    }

    private String request(String method, String url, String apiKey, String body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("Accept", "application/json");
        if (apiKey != null && !apiKey.isEmpty()) connection.setRequestProperty("Api-Key", apiKey);
        if (bearerToken != null && !bearerToken.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + bearerToken);
        }
        connection.setRequestProperty("User-Agent", "MovieTool/v2.4");
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            OutputStream out = connection.getOutputStream();
            out.write(body.getBytes("UTF-8"));
            out.close();
        }
        int code = connection.getResponseCode();
        InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String text = stream == null ? "" : new String(IoUtil.readAll(stream, 4 * 1024 * 1024), "UTF-8");
        if (code >= 400) {
            String message = messageOf(text, code);
            if (code == 406 || code == 429) throw new QuotaException(message);
            throw new IOException("HTTP " + code + " from " + hostOf(url) + ": " + message);
        }
        if (code >= 300) throw new IOException("unexpected redirect (HTTP " + code + ")");
        return text;
    }

    private byte[] fetch(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setRequestProperty("User-Agent", "MovieTool/v2.4");
        int code = connection.getResponseCode();
        if (code != 200) throw new IOException("HTTP " + code + " while downloading the subtitle file");
        InputStream in = connection.getInputStream();
        try {
            return IoUtil.readAll(in, 32 * 1024 * 1024);
        } finally {
            in.close();
        }
    }

    private static String messageOf(String body, int code) {
        try {
            Object value = Json.parse(body);
            if (value instanceof Map) {
                Object message = ((Map<?, ?>) value).get("message");
                if (message instanceof String && !((String) message).isEmpty()) return (String) message;
            }
        } catch (RuntimeException ignore) {
            // not JSON - use the raw body
        }
        String trimmed = body.replaceAll("\\s+", " ").trim();
        return trimmed.isEmpty() ? "no details" : trimmed.substring(0, Math.min(trimmed.length(), 140));
    }

    private static String hostOf(String url) {
        try {
            return new URL(url).getHost();
        } catch (IOException e) {
            return url;
        }
    }
}
