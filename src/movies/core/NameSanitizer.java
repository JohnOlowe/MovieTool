package movies.core;

/**
 * Removes characters that are illegal in file names on Windows (and hostile
 * on Network shares) from generated names.
 *
 * <p>This is the fix for the old IMDB renamer: a title such as
 * "Trial and Errors?" or "Chapter Two: ..." used to be written to disk
 * verbatim, and the rename silently failed on Windows. Every name this tool
 * generates now passes through {@link #sanitize}.</p>
 */
public final class NameSanitizer {

    private NameSanitizer() {
    }

    /** True when the character cannot appear in a Windows file name. */
    public static boolean isIllegal(char c) {
        if (c < 32 || c == 127) return true;
        switch (c) {
            case '<':
            case '>':
            case ':':
            case '"':
            case '/':
            case '\\':
            case '|':
            case '?':
            case '*':
                return true;
            default:
                return false;
        }
    }

    /** Sanitises with illegal characters removed (the old colon behaviour). */
    public static String sanitize(String name) {
        return sanitize(name, "");
    }

    /**
     * Replaces every illegal character with {@code replacement} (itself stripped
     * of illegal characters), collapses runs of spaces, turns non breaking
     * spaces into normal ones and trims trailing dots and spaces (Windows
     * rejects those too). Returns "" for null input.
     */
    public static String sanitize(String name, String replacement) {
        if (name == null) return "";
        String safeReplacement = sanitizeReplacement(replacement);
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '\u00A0') c = ' '; // non breaking space
            if (isIllegal(c)) {
                if (!safeReplacement.isEmpty()) sb.append(safeReplacement);
            } else {
                sb.append(c);
            }
        }
        String out = sb.toString().replaceAll(" {2,}", " ");
        return trim(out.trim(), " .");
    }

    private static String sanitizeReplacement(String replacement) {
        if (replacement == null || replacement.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < replacement.length(); i++) {
            char c = replacement.charAt(i);
            if (!isIllegal(c)) sb.append(c);
        }
        return sb.toString();
    }

    private static String trim(String s, String chars) {
        int start = 0;
        int end = s.length();
        while (start < end && chars.indexOf(s.charAt(start)) >= 0) start++;
        while (end > start && chars.indexOf(s.charAt(end - 1)) >= 0) end--;
        return s.substring(start, end);
    }
}
