package movies.core.conv;

import movies.core.FileNameParts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * FZMovies style names: underscore separated, usually with a trailing hex hash,
 * often with a "(year)" and a "(site)" group, sometimes with episode markers
 * wrapped in dashes.
 *
 * <pre>
 *   Not_Easily_Broken_(2009)_BluRay_high_(fzmovies.net)_293f7995284bf0d67a542abaac5f04c6.mp4
 *   The_Flash_-_S01E03_-_Things_You_Can_t_Outrun_03131193c1ce0a6f409da82f61a0f22a.mp4
 * </pre>
 */
public class FzmoviesConvention extends AbstractConvention {

    /** Release words that are tags rather than title. */
    private static final String[] RELEASE_WORDS = {
            "bluray", "webrip", "web-dl", "web", "dvdrip", "hdtv", "hdts", "cam", "hdrip",
            "brrip", "bdrip", "amzn", "nf", "high", "normal", "low", "x264", "x265", "hevc",
            "aac", "aac2", "0", "mp3", "10bit", "8bit", "hdr"
    };

    @Override
    public String id() { return "fzmovies"; }

    @Override
    public String displayName() { return "FZMovies"; }

    @Override
    public String example() { return "Title_(2023)_BluRay_(fzmovies.net)_4e1d...mp4"; }

    @Override
    public FileNameParts parse(String fileName) {
        String[] stemExt = splitStem(fileName);
        String stem = stemExt[0];
        String ext = stemExt[1];
        if (ext.isEmpty() || stem.isEmpty()) return null;

        List<String> tokens = new ArrayList<String>(Arrays.asList(stem.split("_")));
        if (tokens.size() < 2) return null;

        // Trailing hex hash identifies fzmovies names.
        String last = tokens.get(tokens.size() - 1);
        if (!isHexHash(last.toLowerCase(Locale.ROOT))) return null;
        tokens.remove(tokens.size() - 1);

        FileNameParts parts = baseParts(id(), fileName, ext);

        List<String> titleTokens = new ArrayList<String>();
        List<String> episodeTokens = new ArrayList<String>();
        List<String> target = titleTokens;
        boolean inEpisode = false;

        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            String lower = token.toLowerCase(Locale.ROOT);

            int[] se = parseSeasonEpisode(token.replace("-", ""));
            if (se != null) {
                parts.setSeason(se[0]);
                parts.setEpisode(se[1]);
                // The next "-" token, if any, starts the episode title.
                if (i + 1 < tokens.size() && tokens.get(i + 1).equals("-")) i++;
                target = episodeTokens;
                inEpisode = true;
                continue;
            }
            if (token.equals("-")) {
                if (!inEpisode) {
                    // Dash before the marker: the title ends here.
                    target = episodeTokens;
                    inEpisode = true;
                }
                continue;
            }
            if (isYear(token.replace("(", "").replace(")", "")) && parts.getYear() == 0) {
                parts.setYear(Integer.parseInt(token.replace("(", "").replace(")", "")));
                continue;
            }
            if (token.startsWith("(") && token.endsWith(")")) {
                String inner = token.substring(1, token.length() - 1);
                if (!inner.isEmpty()) parts.getTags().add(inner);
                continue;
            }
            int quality = qualityTokenValue(token);
            if (quality > 0) {
                parts.setQuality(quality);
                continue;
            }
            if (inReleaseWord(lower) && !inEpisode) {
                parts.getTags().add(token);
                continue;
            }
            target.add(token);
        }

        String title = underscoresToSpaces(joinTokens(titleTokens)).trim();
        if (title.isEmpty()) return null;
        parts.setTitle(title);
        parts.setEpisodeTitle(underscoresToSpaces(joinTokens(episodeTokens)).trim());
        return parts;
    }

    @Override
    public String build(FileNameParts p) {
        StringBuilder sb = new StringBuilder(p.getTitle().replace(' ', '_'));
        if (p.getYear() > 0) sb.append("_(").append(p.getYear()).append(')');
        if (!p.getEpisodeTitle().isEmpty()) {
            sb.append("_-_").append(seasonEpisode(p.getSeason(), p.getEpisode()))
              .append("_-_").append(p.getEpisodeTitle().replace(' ', '_'));
        }
        for (String tag : p.getTags()) {
            if (tag.equalsIgnoreCase("fzmovies.net")) continue;
            sb.append('_').append(tag.replace(' ', '_'));
        }
        sb.append(p.getExtension());
        return sb.toString();
    }

    private static String joinTokens(List<String> tokens) {
        StringBuilder sb = new StringBuilder();
        for (String token : tokens) {
            if (sb.length() > 0) sb.append('_');
            sb.append(token);
        }
        return sb.toString();
    }

    private static boolean inReleaseWord(String lower) {
        for (String word : RELEASE_WORDS) {
            if (lower.equals(word)) return true;
        }
        return false;
    }
}
