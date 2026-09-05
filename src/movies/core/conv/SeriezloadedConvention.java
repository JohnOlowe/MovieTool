package movies.core.conv;

import movies.core.FileNameParts;

/**
 * SeriezLoaded style names.
 *
 * <pre>
 *   Morningside (2025) (SeriezLoaded.ng).mkv
 * </pre>
 */
public class SeriezloadedConvention extends AbstractConvention {

    @Override
    public String id() { return "seriezloaded"; }

    @Override
    public String displayName() { return "SeriezLoaded"; }

    @Override
    public String example() { return "Title (2025) (Site).mkv"; }

    @Override
    public FileNameParts parse(String fileName) {
        String[] stemExt = splitStem(fileName);
        String stem = stemExt[0];
        String ext = stemExt[1];
        if (ext.isEmpty() || stem.isEmpty()) return null;

        java.util.List<String> groups = extractParenthesised(stem);
        if (groups.isEmpty()) return null;

        String cleaned = stripParenthesised(stem).trim();
        if (cleaned.isEmpty()) return null;

        FileNameParts parts = baseParts(id(), fileName, ext);
        int year = 0;
        boolean first = true;
        for (String group : groups) {
            String g = group.trim();
            if (first && isYear(g)) {
                year = Integer.parseInt(g);
            } else if (!g.isEmpty()) {
                parts.getTags().add(g);
            }
            first = false;
        }
        parts.setYear(year);
        parts.setTitle(cleaned);
        return parts;
    }

    @Override
    public String build(FileNameParts p) {
        StringBuilder sb = new StringBuilder(p.getTitle());
        if (p.getYear() > 0) sb.append(" (").append(p.getYear()).append(')');
        for (String tag : p.getTags()) sb.append(" (").append(tag).append(')');
        sb.append(p.getExtension());
        return sb.toString();
    }
}
