package movies.core;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a file into structured name parts. The file name is tried first;
 * when no specific convention matches, parent folder names are tried (this
 * reproduces the old workflow where subtitles lived inside per-episode folders
 * such as "Outer_Banks_S01_E01/whatever.srt"). The catch-all "regular"
 * convention is only applied after the specific ones have had their chance.
 */
public final class NameResolver {

    private static final Pattern RAW_EPISODE = Pattern.compile("(?i)(?:^|[\\s.\\-_\\[])s(\\d{1,2})e(\\d{1,3})(?:[\\s.\\-_\\]]|$)");

    private NameResolver() {
    }

    /**
     * Tries, in order: specific conventions on the file name, specific
     * conventions on the parent and grandparent folder names, then the
     * catch-all convention on the file name.
     *
     * @return parsed parts, or {@code null} when nothing matched
     */
    public static FileNameParts resolve(File file, ConventionRegistry registry, List<NamingConvention> selected) {
        List<NamingConvention> specific = new ArrayList<NamingConvention>();
        NamingConvention catchAll = null;
        for (NamingConvention convention : selected) {
            if ("regular".equals(convention.id())) catchAll = convention;
            else specific.add(convention);
        }

        FileNameParts parts = detectAll(registry, file.getName(), specific);
        if (parts != null) return parts;

        File parent = file.getParentFile();
        for (int level = 0; level < 2 && parent != null; level++) {
            FileNameParts folderParts = detectAll(registry, parent.getName(), specific);
            if (folderParts != null) {
                FileNameParts copy = new FileNameParts(folderParts);
                copy.setExtension(IoExtension.extensionOf(file.getName()));
                copy.setOriginalName(file.getName());
                return copy;
            }
            parent = parent.getParentFile();
        }

        if (catchAll != null) {
            return catchAll.parse(file.getName());
        }
        return null;
    }

    private static FileNameParts detectAll(ConventionRegistry registry, String name, List<NamingConvention> specific) {
        for (NamingConvention convention : specific) {
            FileNameParts parts = convention.parse(name);
            if (parts != null) return parts;
        }
        return null;
    }

    /**
     * Last-resort episode extraction from a raw name, used when no convention
     * matched. Returns {season, episode} or null.
     */
    public static int[] rawEpisode(String name) {
        Matcher m = RAW_EPISODE.matcher(name);
        if (m.find()) {
            return new int[] { Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) };
        }
        return null;
    }

    /** Lowercase, separator-collapsed base name used for exact-name matching. */
    public static String normalisedBase(String fileName) {
        String base = IoExtension.baseName(fileName);
        StringBuilder sb = new StringBuilder(base.length());
        boolean lastSpace = true;
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            if (c == '_' || c == '.' || c == '-' || c == '[' || c == ']' || c == '(' || c == ')'
                    || Character.isWhitespace(c)) {
                if (!lastSpace) sb.append(' ');
                lastSpace = true;
            } else {
                sb.append(Character.toLowerCase(c));
                lastSpace = false;
            }
        }
        return sb.toString().trim();
    }

    /** Extension helpers kept here so core stays free of ops dependencies. */
    private static final class IoExtension {
        static String extensionOf(String name) {
            int dot = name.lastIndexOf('.');
            if (dot <= 0 || dot == name.length() - 1) return "";
            String ext = name.substring(dot).toLowerCase(Locale.ROOT);
            for (int i = 1; i < ext.length(); i++) {
                if (!Character.isLetterOrDigit(ext.charAt(i))) return "";
            }
            return ext;
        }

        static String baseName(String name) {
            String ext = extensionOf(name);
            return ext.isEmpty() ? name : name.substring(0, name.length() - ext.length());
        }
    }
}
