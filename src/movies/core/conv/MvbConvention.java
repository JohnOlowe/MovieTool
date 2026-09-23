package movies.core.conv;

import movies.core.FileNameParts;

import java.util.List;

/**
 * The user's personal "MVB" format (Moviebox + IMDB):
 *
 * <pre>
 *   The Flash - S05E05 - Running Ahead.MVB.IMDB.en.mp4    (episodes)
 *   Se7en.MVB.IMDB.en.mp4                                 (movies)
 * </pre>
 *
 * Both SxxEyy and NxNN markers are understood on input; output always uses
 * SxxEyy. The language defaults to "en" when the file does not carry one.
 */
public class MvbConvention extends AbstractConvention {

    private static final String MARKER = ".mvb.imdb.";

    @Override
    public String id() { return "mvb"; }

    @Override
    public String displayName() { return "MVB (Moviebox + IMDB)"; }

    @Override
    public String example() { return "The Flash - S05E05 - Running Ahead.MVB.IMDB.en.mp4"; }

    @Override
    public FileNameParts parse(String fileName) {
        String[] stemExt = splitStem(fileName);
        String stem = stemExt[0];
        String ext = stemExt[1];
        if (ext.isEmpty() || stem.isEmpty()) return null;

        int marker = stem.toLowerCase().indexOf(MARKER);
        if (marker <= 0) return null;
        String head = stem.substring(0, marker);
        String tail = stem.substring(marker + MARKER.length()); // e.g. "en"

        String language = "en";
        String lang = tail.trim();
        if (lang.startsWith(".")) lang = lang.substring(1);
        if (lang.matches("(?i)[a-z]{2,3}")) language = lang.toLowerCase();

        FileNameParts parts = baseParts(id(), fileName, ext);
        parts.setLanguage(language);

        String[] segments = head.split(" - ");
        if (segments.length == 3) {
            int[] se = parseMarker(segments[1].trim());
            String epFragment = segments.length > 2 ? segments[2] : "";
            if (se != null && !epFragment.trim().isEmpty()) {
                TechTail cut = splitTechnicalTail(epFragment.trim());
                parts.setTitle(underscoresToSpaces(segments[0].trim()));
                parts.setSeason(se[0]);
                parts.setEpisode(se[1]);
                parts.setEpisodeTitle(underscoresToSpaces(cut.title));
                parts.getTags().addAll(cut.tags);
                if (cut.quality > 0) parts.setQuality(cut.quality);
                return parts;
            }
        }
        // Movie (or unrecognised structure): the whole head is the title.
        if (head.trim().isEmpty()) return null;
        List<String> groups = extractParenthesised(head);
        String cleaned = stripParenthesised(head).replace('_', ' ').trim();
        for (String group : groups) {
            if (isYear(group.trim())) parts.setYear(Integer.parseInt(group.trim()));
        }
        parts.setTitle(cleaned);
        return parts;
    }

    /** Accepts "5x05" and "S05E05". */
    private static int[] parseMarker(String token) {
        String t = token.trim();
        int x = t.indexOf('x');
        if (x < 0) x = t.indexOf('X');
        if (x > 0 && x < t.length() - 1) {
            int season = parseIntSafe(t.substring(0, x), -1);
            int episode = parseIntSafe(t.substring(x + 1), -1);
            if (season > 0 && episode > 0) return new int[] { season, episode };
            return null;
        }
        if (t.length() > 3 && (t.charAt(0) == 's' || t.charAt(0) == 'S')) {
            int e = t.indexOf('e');
            if (e < 0) e = t.indexOf('E');
            if (e > 1 && e < t.length() - 1) {
                int season = parseIntSafe(t.substring(1, e), -1);
                int episode = parseIntSafe(t.substring(e + 1), -1);
                if (season > 0 && episode > 0) return new int[] { season, episode };
            }
        }
        return null;
    }

    @Override
    public String build(FileNameParts p) {
        String language = p.getLanguage().isEmpty() ? "en" : p.getLanguage();
        StringBuilder sb = new StringBuilder();
        if (p.isEpisode()) {
            sb.append(p.getTitle()).append(" - S").append(pad(p.getSeason()))
              .append('E').append(pad(p.getEpisode()));
            if (!p.getEpisodeTitle().isEmpty()) sb.append(" - ").append(p.getEpisodeTitle());
        } else {
            sb.append(p.getTitle());
        }
        sb.append(".MVB.IMDB.").append(language).append(p.getExtension());
        return sb.toString();
    }
}
