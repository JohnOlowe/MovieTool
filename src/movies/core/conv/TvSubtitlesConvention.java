package movies.core.conv;

import movies.core.FileNameParts;

/**
 * "Show - 5x05 - Episode Title.WEB.en" style names (tvsubtitles.org and
 * friends) - for SUBTITLES and VIDEOS alike. Both NxNN and SxxEyy episode
 * markers are accepted; the episode title ends at the first technical tag.
 *
 * <pre>
 *   The Flash - 5x05 - Running Ahead.HDTV.x264-FLEET.en.srt
 *   The Flash - S05E05 - Running Ahead.HDTV.x264-FLEET.en.mp4
 * </pre>
 */
public class TvSubtitlesConvention extends AbstractConvention {

    @Override
    public String id() { return "tvsubtitles"; }

    @Override
    public String displayName() { return "TVSubtitles / NxNN"; }

    @Override
    public String example() { return "Show Name - 9x01 - Episode Title.WEB.en.srt"; }

    @Override
    public FileNameParts parse(String fileName) {
        String[] stemExt = splitStem(fileName);
        String stem = stemExt[0];
        String ext = stemExt[1];
        if (ext.isEmpty() || stem.isEmpty()) return null;

        String[] segments = stem.split(" - ");
        if (segments.length != 3) return null;

        String show = segments[0].trim();
        String sePart = segments[1].trim();
        String tail = segments[2];

        int[] se = parseMarker(sePart);
        if (se == null) return null;
        int season = se[0];
        int episode = se[1];

        int dot = tail.indexOf('.');
        if (dot < 0) dot = tail.length();
        String episodeTitle = tail.substring(0, dot).trim();
        if (show.isEmpty() || episodeTitle.isEmpty()) return null;

        FileNameParts parts = baseParts(id(), fileName, ext);
        parts.setTitle(show);
        parts.setSeason(season);
        parts.setEpisode(episode);
        parts.setEpisodeTitle(underscoresToSpaces(episodeTitle));

        // Preserve the remaining quality/codec tags, detect a trailing language.
        String rest = dot < tail.length() ? tail.substring(dot + 1) : "";
        for (String token : rest.split("\\.")) {
            if (token.trim().isEmpty()) continue;
            if (parts.getQuality() == 0 && qualityTokenValue(token) > 0) {
                parts.setQuality(qualityTokenValue(token));
            } else if (isLanguageToken(token.trim()) && parts.getLanguage().isEmpty()) {
                parts.setLanguage(token.trim());
            } else {
                parts.getTags().add(token.trim());
            }
        }
        return parts;
    }

    /** Accepts "5x05" and "S05E05" markers; returns {season, episode} or null. */
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
        StringBuilder sb = new StringBuilder();
        sb.append(p.getTitle()).append(" - ").append(p.getSeason()).append('x')
          .append(padOrPlain(p.getEpisode()));
        if (!p.getEpisodeTitle().isEmpty()) sb.append(" - ").append(p.getEpisodeTitle());
        if (p.getQuality() > 0) sb.append('.').append(p.getQuality()).append('p');
        for (String tag : p.getTags()) sb.append('.').append(tag);
        if (!p.getLanguage().isEmpty()) sb.append('.').append(p.getLanguage());
        sb.append(p.getExtension());
        return sb.toString();
    }

    private static String padOrPlain(int n) {
        return n < 10 ? "0" + n : Integer.toString(n);
    }

    static boolean isSubtitle(String name) {
        return name.toLowerCase().endsWith(".srt");
    }
}
