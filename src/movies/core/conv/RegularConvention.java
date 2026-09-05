package movies.core.conv;

import movies.core.FileNameParts;

import java.util.List;
import java.util.Locale;

/**
 * Plain names: an ordinary title with spaces or underscores, an optional
 * "(year)" group and an optional quality marker. This is the catch-all
 * convention, so it deliberately accepts almost anything with a known
 * media extension.
 *
 * <pre>
 *   Some Movie Name.mp4
 *   Some_Movie_Name_(2023).mp4
 *   Movie 2023 1080p.mkv
 * </pre>
 */
public class RegularConvention extends AbstractConvention {

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

        // Trailing " 1080p" style quality token.
        int lastSpace = cleaned.lastIndexOf(' ');
        if (lastSpace > 0) {
            int quality = qualityTokenValue(cleaned.substring(lastSpace + 1).trim());
            if (quality > 0) {
                parts.setQuality(quality);
                cleaned = cleaned.substring(0, lastSpace);
            }
        }

        String title = cleaned.replace('_', ' ').trim();
        if (title.isEmpty()) return null;
        parts.setTitle(title);
        return parts;
    }

    @Override
    public String build(FileNameParts p) {
        StringBuilder sb = new StringBuilder(p.getTitle());
        if (p.getYear() > 0) sb.append(" (").append(p.getYear()).append(')');
        if (p.getQuality() > 0) sb.append(' ').append(p.getQuality()).append('P');
        sb.append(p.getExtension());
        return sb.toString();
    }

    static boolean isSubtitle(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".srt");
    }
}
