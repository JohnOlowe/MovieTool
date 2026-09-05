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

    /** How talky the console/log output should be (0 = quiet, 1 = normal, 2 = debug). */
    private int verbosity = 1;

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

    public int getVerbosity() { return verbosity; }
    public void setVerbosity(int verbosity) { this.verbosity = verbosity; }

    /** Convenience for builders/CLI: is a custom pattern configured? */
    public boolean hasPattern() { return pattern != null && !pattern.trim().isEmpty(); }
}
