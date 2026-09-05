package movies.core.conv;

import movies.core.FileNameParts;

import java.util.Locale;

/**
 * MovieBox style names.
 *
 * <pre>
 *   The_Flash_1080P_S01_E01.mp4            (episode video)
 *   The_People_We_Hate_at_the_Wedding_480P.mp4   (movie video)
 *   The_Flash_S1_E1_English.srt            (episode subtitle)
 *   American_Murderer_English.srt          (movie subtitle)
 *   Countdown_480P_S01_E08.srt             (renamed subtitle, language optional)
 *   Outer_Banks_S01_E01                    (per-episode download folder)
 * </pre>
 */
public class MovieboxConvention extends AbstractConvention {

    @Override
    public String id() { return "moviebox"; }

    @Override
    public String displayName() { return "MovieBox"; }

    @Override
    public String example() { return "Title_1080P_S01_E01.mp4"; }

    @Override
    public FileNameParts parse(String fileName) {
        String[] stemExt = splitStem(fileName);
        String stem = stemExt[0];
        String ext = stemExt[1];
        if (stem.isEmpty()) return null;

        String[] tokens = stem.split("_");
        if (tokens.length < 2) return null;

        FileNameParts parts = baseParts(id(), fileName, ext);
        int end = tokens.length; // exclusive index of the last consumed token

        // Trailing language token (subtitle style: "..._English").
        String last = tokens[end - 1];
        if (isLanguageToken(last) || last.equalsIgnoreCase("english")) {
            parts.setLanguage(last);
            end--;
            if (end < 2) return null;
            last = tokens[end - 1];
        }

        // Trailing split S/E pair ("..._S01_E01").
        int episodeNumber = episodeTokenValue(last);
        if (episodeNumber > 0 && end >= 3) {
            int seasonNumber = seasonTokenValue(tokens[end - 2]);
            if (seasonNumber > 0) {
                parts.setSeason(seasonNumber);
                parts.setEpisode(episodeNumber);
                end -= 2;
            }
        }

        // Trailing quality token.
        int quality = qualityTokenValue(tokens[end - 1]);
        if (quality > 0) {
            parts.setQuality(quality);
            end--;
        }

        if (end < 1) return null;

        // The convention needs a marker: a quality, an episode pair, or (for
        // subtitles) a language token. Otherwise "Some_Name.mp4" would wrongly
        // count as MovieBox.
        boolean hasMarker = parts.getQuality() > 0 || parts.isEpisode()
                || (!parts.getLanguage().isEmpty() && MovieboxConvention.isSubtitleExtension(ext));
        if (!hasMarker) return null;

        StringBuilder title = new StringBuilder();
        for (int i = 0; i < end; i++) {
            if (i > 0) title.append('_');
            title.append(tokens[i]);
        }
        if (title.length() == 0) return null;
        parts.setTitle(title.toString());
        return parts;
    }

    @Override
    public String build(FileNameParts p) {
        StringBuilder sb = new StringBuilder(p.getTitle());
        if (p.getQuality() > 0) sb.append('_').append(p.getQuality()).append('P');
        if (p.isEpisode()) sb.append('_').append('S').append(pad(p.getSeason())).append('_').append('E').append(pad(p.getEpisode()));
        String language = p.getLanguage();
        if (language != null && !language.isEmpty()) sb.append('_').append(language);
        else if (isSubtitleExtension(p.getExtension())) sb.append("_English");
        sb.append(p.getExtension());
        return sb.toString();
    }

    static boolean isSubtitleExtension(String extension) {
        return extension != null && (extension.equalsIgnoreCase(".srt") || extension.equalsIgnoreCase(".vtt")
                || extension.equalsIgnoreCase(".ass") || extension.equalsIgnoreCase(".ssa")
                || extension.equalsIgnoreCase(".sub"));
    }
}
