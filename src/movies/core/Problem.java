package movies.core;

/**
 * A single message produced by an operation: an informational line, a warning
 * or an error. Operations never throw for per-file issues; they report
 * {@link Problem}s so the CLI can keep going and the GUI can render the log.
 */
public class Problem {

    public enum Severity { INFO, WARN, ERROR }

    private final Severity severity;
    private final String message;

    public Problem(Severity severity, String message) {
        this.severity = severity;
        this.message = message;
    }

    public static Problem info(String message) { return new Problem(Severity.INFO, message); }
    public static Problem warn(String message) { return new Problem(Severity.WARN, message); }
    public static Problem error(String message) { return new Problem(Severity.ERROR, message); }

    public Severity getSeverity() { return severity; }
    public String getMessage() { return message; }

    @Override
    public String toString() {
        switch (severity) {
            case ERROR: return "[ERROR] " + message;
            case WARN:  return "[WARN] "  + message;
            default:    return message;
        }
    }
}
