package movies.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The structured pieces of information that can be extracted from a media file
 * name and used to build a new one. A {@code FileNameParts} with
 * {@link #convention} set is a successfully parsed name.
 */
public class FileNameParts {

    /** Show or movie title, still in the original spelling/spacing. */
    private String title = "";
    /** Release year if present, 0 when unknown. */
    private int year;
    /** Season number, 0 when not an episode (or not known). */
    private int season;
    /** Episode number, 0 when not an episode (or not known). */
    private int episode;
    /** Individual episode title when the convention carries one. */
    private String episodeTitle = "";
    /** Video quality in pixels, e.g. 1080; 0 when unknown. */
    private int quality;
    /** Language token such as "English" or "en"; empty when unknown. */
    private String language = "";
    /** Extra site/quality/codec tags preserved from the original name. */
    private final List<String> tags = new ArrayList<String>();
    /** id() of the convention this was parsed with (or should be built with). */
    private String convention = "";
    /** The complete original file name including extension. */
    private String originalName = "";
    /** Lowercase extension including the dot, e.g. ".srt". */
    private String extension = "";

    public FileNameParts() {
    }

    /** Copy constructor used when re-targeting a parsed name. */
    public FileNameParts(FileNameParts other) {
        this.title = other.title;
        this.year = other.year;
        this.season = other.season;
        this.episode = other.episode;
        this.episodeTitle = other.episodeTitle;
        this.quality = other.quality;
        this.language = other.language;
        this.tags.addAll(other.tags);
        this.convention = other.convention;
        this.originalName = other.originalName;
        this.extension = other.extension;
    }

    /** True when this name refers to a specific episode of a series. */
    public boolean isEpisode() {
        return season > 0 && episode > 0;
    }

    /** Title with separators normalised and the year removed; used for matching. */
    public String matchKey() {
        String t = title == null ? "" : title;
        StringBuilder sb = new StringBuilder(t.length());
        boolean lastSpace = true;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '_' || c == '.' || c == '-' || Character.isWhitespace(c)) {
                if (!lastSpace) sb.append(' ');
                lastSpace = true;
            } else {
                sb.append(Character.toLowerCase(c));
                lastSpace = false;
            }
        }
        String key = sb.toString().trim();
        // Drop a trailing year token: "the flash 2014" matches "the flash".
        if (key.length() > 5) {
            int sp = key.lastIndexOf(' ');
            if (sp > 0) {
                String tail = key.substring(sp + 1);
                if (tail.length() == 4) {
                    boolean digits = true;
                    for (int i = 0; i < tail.length(); i++) {
                        if (!Character.isDigit(tail.charAt(i))) { digits = false; break; }
                    }
                    if (digits) key = key.substring(0, sp).trim();
                }
            }
        }
        return key;
    }

    /** "S01E02" style key, or the match key for non-episodes. */
    public String episodeKey() {
        return isEpisode() ? matchKey() + "|s" + season + "e" + episode : matchKey();
    }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = nullSafe(title); }

    public int getYear() { return year; }
    public void setYear(int year) { this.year = year; }

    public int getSeason() { return season; }
    public void setSeason(int season) { this.season = season; }

    public int getEpisode() { return episode; }
    public void setEpisode(int episode) { this.episode = episode; }

    public String getEpisodeTitle() { return episodeTitle; }
    public void setEpisodeTitle(String episodeTitle) { this.episodeTitle = nullSafe(episodeTitle); }

    public int getQuality() { return quality; }
    public void setQuality(int quality) { this.quality = quality; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = nullSafe(language); }

    public List<String> getTags() { return tags; }

    public String getConvention() { return convention; }
    public void setConvention(String convention) { this.convention = nullSafe(convention); }

    public String getOriginalName() { return originalName; }
    public void setOriginalName(String originalName) { this.originalName = nullSafe(originalName); }

    public String getExtension() { return extension; }
    public void setExtension(String extension) { this.extension = nullSafe(extension); }

    private static String nullSafe(String s) { return s == null ? "" : s; }
}
