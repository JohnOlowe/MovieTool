package movies.cli;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minimal dependency-free argument parser.
 *
 * <p>Supports {@code --flag value}, {@code --flag=value}, {@code -f value},
 * repeated-value accumulation, bare boolean flags and a positional list.
 * Unknown flags raise a usage error instead of being silently ignored.</p>
 */
public final class ArgParser {

    private final Map<String, String> values = new HashMap<String, String>();
    private final Set<String> flags = new HashSet<String>();
    private final List<String> positionals = new ArrayList<String>();

    private final Map<String, String> aliases = new HashMap<String, String>();

    /**
     * @param args      raw command line arguments
     * @param aliases   canonical name to accepted spellings, e.g. "dir" -> "--dir,-d"
     * @param booleanFlags canonical names of flags that never take a value
     * @throws IllegalArgumentException on unknown or malformed input
     */
    public ArgParser(String[] args, Map<String, String> aliases, Set<String> booleanFlags) {
        for (Map.Entry<String, String> entry : aliases.entrySet()) {
            for (String spelling : entry.getValue().split(",")) {
                this.aliases.put(spelling.trim(), entry.getKey());
            }
        }
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--")) {
                for (int j = i + 1; j < args.length; j++) positionals.add(args[j]);
                break;
            }
            if (arg.startsWith("-") && !arg.equals("-")) {
                String canonical;
                String inlineValue = null;
                int eq = arg.indexOf('=');
                if (eq > 0) {
                    canonical = arg.substring(0, eq);
                    inlineValue = arg.substring(eq + 1);
                } else {
                    canonical = arg;
                }
                String name = canonicalName(canonical);
                if (name == null) {
                    throw new IllegalArgumentException("Unknown option: " + canonical);
                }
                if (booleanFlags.contains(name)) {
                    flags.add(name);
                    continue;
                }
                String value;
                if (inlineValue != null) {
                    value = inlineValue;
                } else {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("Missing value for " + canonical);
                    }
                    value = args[++i];
                }
                accumulate(name, value);
                continue;
            }
            positionals.add(arg);
        }
    }

    private String canonicalName(String spelling) {
        String direct = aliases.get(spelling);
        if (direct != null) return direct;
        // Option words may be spelled with dashes: --dry-run, --dryrun, --dry_run.
        String squashed = spelling.replace("-", "").replace("_", "");
        for (Map.Entry<String, String> entry : aliases.entrySet()) {
            String candidate = entry.getKey().replace("-", "").replace("_", "");
            if (candidate.equals(squashed)) return entry.getValue();
        }
        return null;
    }

    private void accumulate(String name, String value) {
        String previous = values.get(name);
        values.put(name, previous == null ? value : previous + "," + value);
    }

    public boolean has(String name) {
        return flags.contains(name) || values.containsKey(name);
    }

    public boolean flag(String name) {
        return flags.contains(name);
    }

    public String value(String name) {
        return values.get(name);
    }

    public String value(String name, String fallback) {
        String value = values.get(name);
        return value == null ? fallback : value;
    }

    public List<String> positionals() {
        return positionals;
    }

    /** Joins repeated values and strips whitespace around each part. */
    public String joined(String name) {
        String value = values.get(name);
        if (value == null) return "";
        String[] parts = value.split(",");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(trimmed);
        }
        return sb.toString();
    }
}
