package movies.core.conv;

import movies.core.FileNameParts;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain names: an ordinary title with spaces or underscores, an optional
 * "(year)" group, an optional quality marker and an optional SxxEyy episode
 * marker (Dood-style "Flash_S05E03.mp4" included). This is the catch-all
 * convention, so it deliberately accepts almost anything with a known media
 * extension.
 *
 * <pre>
 *   Some Movie Name.mp4
 *   Some_Movie_Name_(2023).mp4
 *   Movie 2023 1080p.mkv
 *   Flash_S05E03.mp4
 * </pre>
 */
public class RegularConvention extends AbstractConvention {

    /** "S05E03" style token inside a name, with token boundaries. */
    private static final Pattern SEASON_EPISODE =
            Pattern.compile("(?i)(?:^|[._\\- ])s(\\d{1,2})e(\\d{1,3})(?=[._\\- ]|$)");

    @Override
    public String id() { return "regular"; }

    @Override
    public String displayName() { return "Plain"; }

    @Override
    public String example() { return "Some Movie Name (2023).mp4"; }

    @Override
    public FileNameParts parse(String fileName) {
        String[] stemExt = splitStem(fileName);
        String stem = stemExt[0];
        String ext = stemExt[1];
        if (stem.isEmpty()) return null;

        FileNameParts parts = baseParts(id(), fileName, ext);

        List<String> groups = extractParenthesised(stem);
        String cleaned = stripParenthesised(stem);

        for (String group : groups) {
            String g = group.trim();
            if (isYear(g) && parts.getYear() == 0) parts.setYear(Integer.parseInt(g));
            else if (!g.isEmpty() && !parts.getTags().contains(g)) parts.getTags().add(g);
        }

        // Trailing " 1080p" style quality token, after any of " . _ -".
        int tailStart = -1;
        for (int i = cleaned.length() - 2; i >= 0; i--) {
            char c = cleaned.charAt(i);
            if (c == ' ' || c == '.' || c == '_' || c == '-') { tailStart = i; break; }
        }
        if (tailStart >= 0) {
            int quality = qualityTokenValue(cleaned.substring(tailStart + 1).trim());
            if (quality > 0) {
                parts.setQuality(quality);
                cleaned = cleaned.substring(0, tailStart);
            }
        }

        // Structured "S05E03" episode marker (Dood and friends): recorded as
        // real season/episode fields and removed from the title.
        Matcher marker = SEASON_EPISODE.matcher(cleaned);
        if (marker.find()) {
            parts.setSeason(Integer.parseInt(marker.group(1)));
            parts.setEpisode(Integer.parseInt(marker.group(2)));
            cleaned = cleaned.substring(0, marker.start()) + cleaned.substring(marker.end());
        }

        String title = cleaned.replace('_', ' ').trim();
        if (title.isEmpty()) return null;
        parts.setTitle(title);
        return parts;
    }

    @Override
    public String build(FileNameParts p) {
        StringBuilder sb = new StringBuilder(p.getTitle());
        if (p.isEpisode()) sb.append(' ').append("S").append(pad(p.getSeason())).append('E').append(pad(p.getEpisode()));
        if (p.getYear() > 0) sb.append(" (").append(p.getYear()).append(')');
        if (p.getQuality() > 0) sb.append(' ').append(p.getQuality()).append('P');
        sb.append(p.getExtension());
        return sb.toString();
    }

    static boolean isSubtitle(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".srt");
    }
}
