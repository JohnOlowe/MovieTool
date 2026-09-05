package movies.core;

import movies.core.conv.AwafimConvention;
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
     * Tries every selected convention in order and returns the first match,
     * or {@code null} when the name fits none of them.
     */
    public FileNameParts detect(String fileName, List<NamingConvention> selected) {
        for (NamingConvention convention : selected) {
            FileNameParts parts = convention.parse(fileName);
            if (parts != null) return parts;
        }
        return null;
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
