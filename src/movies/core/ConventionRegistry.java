package movies.core;

import movies.core.conv.AwafimConvention;
import movies.core.conv.MvbConvention;
import movies.core.conv.FzmoviesConvention;
import movies.core.conv.MovieboxConvention;
import movies.core.conv.NkiriConvention;
import movies.core.conv.RegularConvention;
import movies.core.conv.SceneConvention;
import movies.core.conv.SeriezloadedConvention;
import movies.core.conv.TvSubtitlesConvention;
import movies.core.conv.WaploadedConvention;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of the known naming conventions plus the auto-detection logic.
 *
 * <p>Conventions are tried in registration order; more specific ones come
 * first so "The Flash S04E05 - ..." is not eaten by the plain convention.
 * Users can restrict detection to a set of ids via the CLI/GUI.</p>
 */
public final class ConventionRegistry {

    private final Map<String, NamingConvention> conventions = new LinkedHashMap<String, NamingConvention>();

    public ConventionRegistry() {
        register(new MvbConvention());
        register(new TvSubtitlesConvention());
        register(new AwafimConvention());
        register(new WaploadedConvention());
        register(new SeriezloadedConvention());
        register(new FzmoviesConvention());
        register(new NkiriConvention());
        register(new MovieboxConvention());
        register(new SceneConvention());
        register(new RegularConvention());
    }

    public void register(NamingConvention convention) {
        conventions.put(convention.id(), convention);
    }

    /** All registered conventions in detection order. */
    public List<NamingConvention> all() {
        return new ArrayList<NamingConvention>(conventions.values());
    }

    public NamingConvention get(String id) {
        return conventions.get(id);
    }

    /** Resolves the requested convention ids, or every convention when blank. */
    public List<NamingConvention> selected(String csvList) {
        if (csvList == null || csvList.trim().isEmpty()) return all();
        List<NamingConvention> picked = new ArrayList<NamingConvention>();
        for (String id : csvList.split(",")) {
            NamingConvention convention = conventions.get(id.trim());
            if (convention != null) picked.add(convention);
        }
        return picked;
    }

    /**
     * Tries every selected convention and returns the BEST match - the one
     * that extracts the most structure (episode, year, quality, language)
     * with the cleanest title. Earlier conventions win ties, so genuinely
     * site-specific formats keep priority over the catch-all, while greedy
     * site conventions can no longer starve a better generic parse
     * ("Downfalls_High_2021_720P.mp4" used to land in moviebox with the
     * year stuck in the title).
     */
    public FileNameParts detect(String fileName, List<NamingConvention> selected) {
        return detectBest(fileName, selected);
    }

    /** Best-scoring parse among all conventions, or null when none match. */
    public static FileNameParts detectBest(String fileName, List<NamingConvention> selected) {
        FileNameParts best = null;
        int bestScore = Integer.MIN_VALUE;
        for (NamingConvention convention : selected) {
            FileNameParts parts = convention.parse(fileName);
            // Strict > : on equal scores the earlier (more specific)
            // convention in detection order keeps the win.
            if (parts != null && parseScore(parts) > bestScore) {
                bestScore = parseScore(parts);
                best = parts;
            }
        }
        return best;
    }

    /** How much structure a parse extracted; higher is better. */
    public static int parseScore(FileNameParts p) {
        int score = 0;
        if (p.isEpisode()) score += 4;
        if (p.getYear() > 0) score += 3;
        if (p.getQuality() > 0) score += 2;
        if (p.getLanguage() != null && !p.getLanguage().isEmpty()) score += 2;
        String title = p.getTitle() == null ? "" : p.getTitle();
        if (!title.isEmpty()) {
            if (title.indexOf('_') < 0) score += 2;
            if (title.indexOf('.') < 0) score += 2;
            if (!title.toLowerCase().matches(".*\\d{3,4}p.*")) score += 2;
        }
        if (p.getEpisodeTitle() != null && !p.getEpisodeTitle().isEmpty()) {
            score += 1;
            score += junkEpisodeTitlePenalty(p.getEpisodeTitle());
        }
        return score;
    }

    /**
     * Episode titles made of dot-separated ALL-CAPS tokens ("MVB.IMDB",
     * "HDTV.x264") are leaked tags, not titles - such parses lose points so
     * a cleaner interpretation can win. Single-letter tokens ("P.O.W") are
     * natural and never penalised.
     */
    private static int junkEpisodeTitlePenalty(String episodeTitle) {
        if (episodeTitle.indexOf('.') < 0) return 0;
        for (String token : episodeTitle.split("\\.")) {
            String t = token.trim();
            if (t.length() >= 2 && t.matches("[A-Z0-9&+\\-]+")) return -3;
        }
        return 0;
    }

    /** Comma separated list of all convention ids (for help text). */
    public static String idList() {
        ConventionRegistry r = new ConventionRegistry();
        StringBuilder sb = new StringBuilder();
        for (NamingConvention c : r.all()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(c.id());
        }
        return sb.toString();
    }
}
