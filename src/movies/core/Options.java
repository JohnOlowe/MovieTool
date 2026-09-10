package movies.core;

/**
 * Options shared by the CLI and the GUI.
 *
 * <p>An {@code Options} instance is the single configuration object handed to the
 * engines and operations, so new knobs only ever need to be added here (plus the
 * wiring in the CLI/GUI). Paths may be {@code null} when not applicable.</p>
 */
public class Options {

    /** Root folder the operation works on. */
    private String folder = "";

    /** Secondary folder (e.g. where downloaded subtitles live). */
    private String secondaryFolder = "";

    /** Output path (a file or a folder, depending on the operation). */
    private String output = "";

    /** Comma separated list of naming convention ids, empty = auto-detect. */
    private String conventions = "";

    /** Custom rename pattern, e.g. "{title} {s01e01}{ext}". Empty = use conventions. */
    private String pattern = "";

    /** Follow sub-folders when scanning. */
    private boolean recursive;

    /** Report what would happen without touching anything (rename/sync/flatten). */
    private boolean dryRun = true;

    /** Apply the changes for real (clears the dry-run plan flag). */
    private boolean apply;

    /** Overwrite existing files instead of skipping them. */
    private boolean overwrite;

    /** Move subtitle files instead of copying them (sync operation). */
    private boolean move;

    /** Keep the original file when shifting subtitles (write a .bak copy). */
    private boolean backup = true;

    /** Seconds to shift subtitle timing by (negative shifts earlier). */
    private double shiftSeconds;

    /** Position of the second track's text when both tracks are active (merge). */
    private boolean secondOnTop;

    /** Prefer this quality (e.g. "1080") when several candidates match. */
    private String preferQuality = "";

    /** Emit a CSV file listing episodes instead of plain text. */
    private boolean csv;

    /** Titles list for the IMDB rename (lines like "S1.E2 ∙ Title"). */
    private String titlesFile = "";

    /** Show name override for the IMDB rename (default: detected from files). */
    private String showName = "";

    /** Episode marker style for the IMDB rename: "s01e01" or "1x01". */
    private String style = "s01e01";

    /** Extra tag appended before the extension. Default: the tag the original
     *  MoviesRenamer always wrote ("MVB.IMDB.en" -> "Name.MVB.IMDB.en.srt").
     *  Empty = no tag (GUI checkbox unticked / CLI --no-tag). */
    private String tag = "MVB.IMDB.en";

    /** What illegal file name characters are replaced with ("" = remove). */
    private String replaceWith = "";

    /** How talky the console/log output should be (0 = quiet, 1 = normal, 2 = debug). */
    private int verbosity = 1;

    /** Clean-titles: replace the original titles list instead of writing
     *  a new <name>-clean.list file next to it. */
    private boolean titlesInPlace;

    /** Clean-titles: sort the kept lines by season/episode (default on). */
    private boolean sortTitles = true;

    /** OpenSubtitles API key (download-subs); falls back to the
     *  OPENSUBTITLES_API_KEY environment variable. */
    private String apiKey = "";

    /** Subtitle language for the download-subs operation (ISO code). */
    private String subsLanguage = "en";

    /** API base for download-subs (overridable for the offline tests). */
    private String subsApiBase = "https://api.opensubtitles.com";

    /** download-subs: put subtitles into the folder's 'Subtitles' sub-folder
     *  instead of next to their videos. */
    private boolean subsIntoFolder;

    public String getFolder() { return folder; }
    public void setFolder(String folder) { this.folder = folder == null ? "" : folder; }

    public String getSecondaryFolder() { return secondaryFolder; }
    public void setSecondaryFolder(String secondaryFolder) { this.secondaryFolder = secondaryFolder == null ? "" : secondaryFolder; }

    public String getOutput() { return output; }
    public void setOutput(String output) { this.output = output == null ? "" : output; }

    public String getConventions() { return conventions; }
    public void setConventions(String conventions) { this.conventions = conventions == null ? "" : conventions; }

    public String getPattern() { return pattern; }
    public void setPattern(String pattern) { this.pattern = pattern == null ? "" : pattern; }

    public boolean isRecursive() { return recursive; }
    public void setRecursive(boolean recursive) { this.recursive = recursive; }

    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }

    public boolean isApply() { return apply; }
    public void setApply(boolean apply) { this.apply = apply; }

    public boolean isOverwrite() { return overwrite; }
    public void setOverwrite(boolean overwrite) { this.overwrite = overwrite; }

    public boolean isMove() { return move; }
    public void setMove(boolean move) { this.move = move; }

    public boolean isBackup() { return backup; }
    public void setBackup(boolean backup) { this.backup = backup; }

    public double getShiftSeconds() { return shiftSeconds; }
    public void setShiftSeconds(double shiftSeconds) { this.shiftSeconds = shiftSeconds; }

    public boolean isSecondOnTop() { return secondOnTop; }
    public void setSecondOnTop(boolean secondOnTop) { this.secondOnTop = secondOnTop; }

    public String getPreferQuality() { return preferQuality; }
    public void setPreferQuality(String preferQuality) { this.preferQuality = preferQuality == null ? "" : preferQuality; }

    public boolean isCsv() { return csv; }
    public void setCsv(boolean csv) { this.csv = csv; }

    public String getTitlesFile() { return titlesFile; }
    public void setTitlesFile(String titlesFile) { this.titlesFile = titlesFile == null ? "" : titlesFile; }

    public String getShowName() { return showName; }
    public void setShowName(String showName) { this.showName = showName == null ? "" : showName; }

    public String getStyle() { return style; }
    public void setStyle(String style) { this.style = style == null ? "s01e01" : style; }

    public String getTag() { return tag; }
    public void setTag(String tag) { this.tag = tag == null ? "" : tag; }

    public String getReplaceWith() { return replaceWith; }
    public void setReplaceWith(String replaceWith) { this.replaceWith = replaceWith == null ? "" : replaceWith; }

    public int getVerbosity() { return verbosity; }
    public void setVerbosity(int verbosity) { this.verbosity = verbosity; }

    /** Convenience for builders/CLI: is a custom pattern configured? */
    public boolean hasPattern() { return pattern != null && !pattern.trim().isEmpty(); }

    /** Default name of the titles list file. */
    public static final String DEFAULT_TITLES_NAME = "titles.list";

    public boolean isTitlesInPlace() { return titlesInPlace; }
    public void setTitlesInPlace(boolean titlesInPlace) { this.titlesInPlace = titlesInPlace; }

    public boolean isSortTitles() { return sortTitles; }
    public void setSortTitles(boolean sortTitles) { this.sortTitles = sortTitles; }

    public String getApiKey() { return apiKey == null ? "" : apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey == null ? "" : apiKey.trim(); }

    public String getSubsLanguage() { return subsLanguage == null || subsLanguage.trim().isEmpty() ? "en" : subsLanguage.trim(); }
    public void setSubsLanguage(String subsLanguage) { this.subsLanguage = subsLanguage; }

    public String getSubsApiBase() { return subsApiBase == null ? "https://api.opensubtitles.com" : subsApiBase; }
    public void setSubsApiBase(String subsApiBase) { this.subsApiBase = subsApiBase; }

    public boolean isSubsIntoFolder() { return subsIntoFolder; }
    public void setSubsIntoFolder(boolean subsIntoFolder) { this.subsIntoFolder = subsIntoFolder; }
}
