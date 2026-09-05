package movies.core.conv;

import movies.core.FileNameParts;

/**
 * Waploaded style names: a bracketed site prefix followed by an underscore
 * separated title and an optional year.
 *
 * <pre>
 *   [Waploaded_20451]Running_the_Bases_2022.mp4
 *   [Waploaded]_indivisible-2018.mp4
 * </pre>
 */
public class WaploadedConvention extends AbstractConvention {

    @Override
    public String id() { return "waploaded"; }

    @Override
    public String displayName() { return "Waploaded"; }

    @Override
    public String example() { return "[Site]Title_2022.mp4"; }

    @Override
    public FileNameParts parse(String fileName) {
        String[] stemExt = splitStem(fileName);
        String stem = stemExt[0];
        String ext = stemExt[1];
        if (ext.isEmpty() || stem.isEmpty()) return null;
        if (!stem.startsWith("[")) return null;

        int close = stem.indexOf(']');
        if (close < 2) return null;
        String site = stem.substring(1, close);
        String rest = stem.substring(close + 1);
        if (rest.startsWith("_")) rest = rest.substring(1);
        if (rest.isEmpty()) return null;

        FileNameParts parts = baseParts(id(), fileName, ext);
        parts.getTags().add(site);

        // Split off a trailing year, either "_2022" or "-2022" or "(2022)".
        String cleaned = rest.replace('(', ' ').replace(')', ' ');
        int year = 0;
        for (String separator : new String[] { "_", "-" }) {
            int idx = cleaned.lastIndexOf(separator);
            if (idx > 0 && isYear(cleaned.substring(idx + separator.length()).trim())) {
                year = Integer.parseInt(cleaned.substring(idx + separator.length()).trim());
                cleaned = cleaned.substring(0, idx);
                break;
            }
        }
        if (year == 0) {
            // Year may be the final underscore token: "Running_the_Bases_2022".
            String[] tokens = cleaned.split("_");
            if (tokens.length > 1 && isYear(tokens[tokens.length - 1].trim())) {
                year = Integer.parseInt(tokens[tokens.length - 1].trim());
                StringBuilder title = new StringBuilder();
                for (int i = 0; i < tokens.length - 1; i++) {
                    if (i > 0) title.append('_');
                    title.append(tokens[i]);
                }
                cleaned = title.toString();
            }
        }
        parts.setYear(year);
        parts.setTitle(underscoresToSpaces(cleaned.trim()));
        if (parts.getTitle().isEmpty()) return null;
        return parts;
    }

    @Override
    public String build(FileNameParts p) {
        StringBuilder sb = new StringBuilder();
        if (!p.getTags().isEmpty()) sb.append('[').append(p.getTags().get(0)).append(']');
        String title = p.getTitle().replace(' ', '_');
        if (!title.isEmpty() && sb.length() > 0 && sb.charAt(sb.length() - 1) != ']') sb.append('_');
        sb.append(title);
        if (p.getYear() > 0) sb.append('_').append(p.getYear());
        sb.append(p.getExtension());
        return sb.toString();
    }
}
