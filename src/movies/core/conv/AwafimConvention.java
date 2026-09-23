package movies.core.conv;

import movies.core.FileNameParts;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Awafim / general "Show SxxExx - Episode Title (Site)" style names.
 *
 * <pre>
 *   The Flash S04E05 - Girls Night Out (Awafim.tv).mp4
 *   The Flash S07E16 - P.O.W.mp4
 *   The Flash S06E11 - Love is a Battlefield (Awafim.tv) (1).mp4
 * </pre>
 */
public class AwafimConvention extends AbstractConvention {

    private static final Pattern SE = Pattern.compile("(?i)(?:^|[\\s.\\-_])s(\\d{1,2})e(\\d{1,3})(?:[\\s.\\-_]|$)");

    /** A quality token leading the text after the episode marker: ".720p", " 1080P". */
    private static final Pattern LEADING_QUALITY =
            Pattern.compile("(?i)^\\s*[._\\-\\s]*(\\d{3,4}p)(?![a-z0-9])");

    @Override
    public String id() { return "awafim"; }

    @Override
    public String displayName() { return "Awafim / SxxExx"; }

    @Override
    public String example() { return "Show S04E05 - Episode Title (Site).mp4"; }

    @Override
    public FileNameParts parse(String fileName) {
        String[] stemExt = splitStem(fileName);
        String stem = stemExt[0];
        String ext = stemExt[1];
        if (ext.isEmpty() || stem.isEmpty()) return null;

        Matcher m = SE.matcher(stem);
        if (!m.find()) return null;
        int season = Integer.parseInt(m.group(1));
        int episode = Integer.parseInt(m.group(2));
        int quality = 0;

        String title = trimSeparators(stem.substring(0, m.start()));
        String rest = stem.substring(m.end());

        // Everything after the marker: optional quality, an episode title,
        // optional "(Site)" and "(n)".
        List<String> groups = extractParenthesised(rest);
        String withoutParens = stripParenthesised(rest);
        Matcher leadingQuality = LEADING_QUALITY.matcher(withoutParens);
        if (leadingQuality.lookingAt()) {
            quality = qualityTokenValue(leadingQuality.group(1));
            withoutParens = withoutParens.substring(leadingQuality.end());
        }
        // Trailing technical tokens are tags/quality/language, not the title:
        // "Running Ahead.HDTV.x264-FLEET.en" keeps "Running Ahead" only.
        TechTail tail = splitTechnicalTail(trimSeparators(withoutParens));
        String episodeTitle = tail.title;
        if (episodeTitle.endsWith("-")) episodeTitle = episodeTitle.substring(0, episodeTitle.length() - 1).trim();
        if (title.isEmpty() || episodeTitle.isEmpty()) return null;
        if (quality == 0) quality = tail.quality;

        // A previously imdb-renamed file parses as "Show - SxxExx - Title";
        // make sure no trailing separator sneaks into the show name.
        FileNameParts parts = baseParts(id(), fileName, ext);
        parts.setTitle(title);
        parts.setSeason(season);
        parts.setEpisode(episode);
        if (quality > 0) parts.setQuality(quality);
        if (!tail.language.isEmpty()) parts.setLanguage(tail.language);
        parts.getTags().addAll(tail.tags);
        parts.setEpisodeTitle(underscoresToSpaces(episodeTitle));
        for (String tag : groups) {
            if (tag.matches("\\d+")) continue; // duplicate download counter, not a tag
            parts.getTags().add(tag);
        }
        return parts;
    }

    @Override
    public String build(FileNameParts p) {
        StringBuilder sb = new StringBuilder();
        sb.append(p.getTitle()).append(' ')
          .append('S').append(padded(p.getSeason())).append('E').append(padded(p.getEpisode()));
        if (!p.getEpisodeTitle().isEmpty()) {
            sb.append(" - ").append(p.getEpisodeTitle());
        }
        for (String tag : p.getTags()) sb.append(" (").append(tag).append(')');
        sb.append(p.getExtension());
        return sb.toString();
    }

    private static String padded(int n) {
        return n < 10 ? "0" + n : Integer.toString(n);
    }

    static boolean looksLikeDuplicateMarker(String token) {
        return token.toLowerCase(Locale.ROOT).matches("\\d+");
    }
}
