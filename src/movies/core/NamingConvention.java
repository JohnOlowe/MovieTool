package movies.core;

/**
 * A naming convention knows how to recognise one release-name style and how to
 * produce a file name in that style. Implementations are registered in
 * {@link ConventionRegistry}; both the rename engine and the subtitle sync use
 * them so behaviour never diverges between operations.
 */
public interface NamingConvention {

    /** Short stable id used on the command line, e.g. "moviebox". */
    String id();

    /** Human readable name for the GUI and help text. */
    String displayName();

    /** One line description of the format, e.g. "Title_1080P_S01_E01.mp4". */
    String example();

    /**
     * Parse a plain file name (no path, with extension) into parts.
     *
     * @return parsed parts with {@link FileNameParts#getConvention()} set,
     *         or {@code null} if this convention does not match the name.
     *         Implementations must not throw for unparseable input.
     */
    FileNameParts parse(String fileName);

    /**
     * Build a file name (with extension) from parts using this convention.
     * Implementations should tolerate missing fields gracefully.
     */
    String build(FileNameParts parts);
}
