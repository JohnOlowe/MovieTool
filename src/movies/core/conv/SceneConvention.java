package movies.core.conv;

import movies.core.FileNameParts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scene / release style names with dot separators and a dotted SxxEyy marker.
 *
 * <pre>
 *   Show.Name.S01E02.720p.WEB-DL.x264.NG.srt
 *   Money.Heist.S05E10.1080p.mkv
 *   Some.Movie.2023.1080p.WEB-DL.mkv
 * </pre>
 */
public class SceneConvention extends AbstractConvention {

    private static final Pattern EPISODE = Pattern.compile("(?i)(.+)\\.s(\\d{1,2})e(\\d{1,3})\\.(.+)");
    private static final Pattern MOVIE = Pattern.compile("(?i)(.+)\\.(\\d{4})\\.(.+)");
    private static final String[] NOISE = { "x264", "x265", "h264", "h265", "hevc", "aac", "aac2", "0",
            "mp3", "web-dl", "webrip", "bluray", "hdrip", "hdtv", "web", "dl", "amzn", "nf", "proper",
            "repack", "extended", "internal", "ng", "mkv", "mp4" };

    @Override
    public String id() { return "scene"; }

    @Override
    public String displayName() { return "Scene / dotted"; }

    @Override
    public String example() { return "Show.Name.S01E02.720p.WEB-DL.srt"; }

    @Override
    public FileNameParts parse(String fileName) {
        String[] stemExt = splitStem(fileName);
        String stem = stemExt[0];
        String ext = stemExt[1];
        if (ext.isEmpty() || stem.isEmpty()) return null;

        Matcher m = EPISODE.matcher(stem);
        if (m.matches()) {
            FileNameParts parts = baseParts(id(), fileName, ext);
            parts.setTitle(m.group(1).replace('.', ' ').trim());
            parts.setSeason(Integer.parseInt(m.group(2)));
            parts.setEpisode(Integer.parseInt(m.group(3)));
            collectTags(parts, m.group(4));
            if (parts.getTitle().isEmpty()) return null;
            return parts;
        }

        m = MOVIE.matcher(stem);
        if (m.matches()) {
            FileNameParts parts = baseParts(id(), fileName, ext);
            parts.setTitle(m.group(1).replace('.', ' ').trim());
            parts.setYear(Integer.parseInt(m.group(2)));
            collectTags(parts, m.group(3));
            if (parts.getTitle().isEmpty()) return null;
            return parts;
        }
        return null;
    }

    private void collectTags(FileNameParts parts, String tail) {
        List<String> tokens = new ArrayList<String>(Arrays.asList(tail.split("\\.")));
        for (String token : tokens) {
            if (token.isEmpty()) continue;
            String lower = token.toLowerCase(Locale.ROOT);
            int quality = qualityTokenValue(token);
            if (quality > 0 && parts.getQuality() == 0) { parts.setQuality(quality); continue; }
            if (isLanguageToken(token) && parts.getLanguage().isEmpty()) { parts.setLanguage(lower); continue; }
            if (isNoise(lower)) { parts.getTags().add(token); continue; }
            // Unknown token after the marker: keep it as a tag, it may be the group name.
            parts.getTags().add(token);
        }
    }

    @Override
    public String build(FileNameParts p) {
        StringBuilder sb = new StringBuilder(p.getTitle().replace(' ', '.'));
        if (p.isEpisode()) {
            sb.append(".S").append(pad(p.getSeason())).append('E').append(pad(p.getEpisode()));
        } else if (p.getYear() > 0) {
            sb.append('.').append(p.getYear());
        }
        for (String tag : p.getTags()) sb.append('.').append(tag.replace(' ', '.'));
        if (p.getQuality() > 0) sb.append('.').append(p.getQuality()).append('p');
        if (!p.getLanguage().isEmpty()) sb.append('.').append(p.getLanguage());
        sb.append(p.getExtension());
        return sb.toString();
    }

    private static boolean isNoise(String lower) {
        for (String word : NOISE) {
            if (lower.equals(word)) return true;
        }
        return false;
    }
}
