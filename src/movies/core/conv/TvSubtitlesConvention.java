package movies.core.conv;

import movies.core.FileNameParts;

/**
 * TVSubtitles / Addic7ed style names.
 *
 * <pre>
 *   The Flash 2014  - 9x01 - Wednesday Ever After.WEB.AMZN.en.srt
 *   The Flash 2014  - 4x06 - When Harry Met Harry....HDTV.KILLERS.en.srt
 *   Supergirl - 2x08 - Medusa.WEB.HBOGO.en.srt
 * </pre>
 */
public class TvSubtitlesConvention extends AbstractConvention {

    @Override
    public String id() { return "tvsubtitles"; }

    @Override
    public String displayName() { return "TVSubtitles"; }

    @Override
    public String example() { return "Show Name - 9x01 - Episode Title.WEB.en.srt"; }

    @Override
    public FileNameParts parse(String fileName) {
        if (!fileName.toLowerCase().endsWith(".srt")) return null;
        String stem = fileName.substring(0, fileName.length() - 4);

        String[] segments = stem.split(" - ");
        if (segments.length != 3) return null;

        String show = segments[0].trim();
        String sePart = segments[1].trim();
        String tail = segments[2];

        int x = sePart.indexOf('x');
        if (x <= 0 || x >= sePart.length() - 1) return null;
        int season = parseIntSafe(sePart.substring(0, x), -1);
        int episode = parseIntSafe(sePart.substring(x + 1), -1);
        if (season <= 0 || episode <= 0) return null;

        int dot = tail.indexOf('.');
        if (dot < 0) dot = tail.length();
        String episodeTitle = tail.substring(0, dot).trim();
        if (show.isEmpty() || episodeTitle.isEmpty()) return null;

        FileNameParts parts = baseParts(id(), fileName, ".srt");
        parts.setTitle(show);
        parts.setSeason(season);
        parts.setEpisode(episode);
        parts.setEpisodeTitle(episodeTitle);

        // Preserve the remaining quality/codec tags, detect a trailing language.
        String rest = dot < tail.length() ? tail.substring(dot + 1) : "";
        for (String token : rest.split("\\.")) {
            if (token.trim().isEmpty()) continue;
            if (isLanguageToken(token.trim()) && parts.getLanguage().isEmpty()) {
                parts.setLanguage(token.trim());
            } else {
                parts.getTags().add(token.trim());
            }
        }
        return parts;
    }

    @Override
    public String build(FileNameParts p) {
        StringBuilder sb = new StringBuilder();
        sb.append(p.getTitle()).append(" - ").append(p.getSeason()).append('x')
          .append(padOrPlain(p.getEpisode()));
        if (!p.getEpisodeTitle().isEmpty()) sb.append(" - ").append(p.getEpisodeTitle());
        for (String tag : p.getTags()) sb.append('.').append(tag);
        if (!p.getLanguage().isEmpty()) sb.append('.').append(p.getLanguage());
        sb.append(p.getExtension());
        return sb.toString();
    }

    private static String padOrPlain(int n) {
        return n < 10 ? "0" + n : Integer.toString(n);
    }
}
