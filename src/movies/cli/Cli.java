package movies.cli;

import movies.core.ConventionRegistry;
import movies.core.OperationResult;
import movies.core.Options;
import movies.core.Problem;
import movies.core.RenameEngine;
import movies.core.TransferAction;
import movies.gui.MovieToolGui;
import movies.ops.EpisodeLister;
import movies.ops.ImdbRename;
import movies.ops.Flattener;
import movies.ops.HealthCheck;
import movies.ops.SubsMerger;
import movies.ops.SubsRelocator;
import movies.ops.SubsShift;
import movies.ops.SubsSync;
import movies.ops.VttConvert;
import movies.util.IoUtil;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The command line interface: one subcommand per operation, consistent options
 * across all of them, safe dry-run defaults for anything that writes.
 *
 * <pre>
 *   movietool rename -d "D:/Shows/Flash" -t moviebox -r --apply
 *   movietool sync-subs -d "D:/Shows/Flash" -s "D:/Downloads/Subs" -r
 *   movietool merge-subs a.srt b.srt -o merged.srt
 * </pre>
 */
public final class Cli {

    public static final int EXIT_OK = 0;
    public static final int EXIT_ERRORS = 1;
    public static final int EXIT_USAGE = 2;

    private static final Map<String, Command> COMMANDS = new LinkedHashMap<String, Command>();

    static {
        add(new HelpCommand());
        add(new RenameCommand());
        add(new ImdbRenameCommand());
        add(new SyncSubsCommand());
        add(new RelocateSubsCommand());
        add(new FlattenCommand());
        add(new MergeSubsCommand());
        add(new ConvertVttCommand());
        add(new ShiftSubsCommand());
        add(new ListEpisodesCommand());
        add(new CheckCommand());
        add(new ConventionsCommand());
        add(new GuiCommand());
    }

    private Cli() {
    }

    private static void add(Command command) {
        COMMANDS.put(command.name(), command);
    }

    /** Runs the CLI; returns the process exit code. */
    public static int run(String[] args) {
        if (args.length == 0) {
            usage(System.out);
            return EXIT_USAGE;
        }
        String name = args[0].toLowerCase();
        if (name.equals("-h") || name.equals("--help") || name.equals("help")) {
            if (args.length > 1) {
                Command command = COMMANDS.get(args[1].toLowerCase());
                if (command == null) {
                    System.err.println("No such command: " + args[1]);
                    return EXIT_USAGE;
                }
                System.out.println(command.usage());
                return EXIT_OK;
            }
            usage(System.out);
            return EXIT_OK;
        }
        if (name.equals("version") || name.equals("--version")) {
            System.out.println("MovieTool " + Version.TEXT);
            return EXIT_OK;
        }
        Command command = COMMANDS.get(name);
        if (command == null) {
            System.err.println("No such command: " + args[0]);
            System.err.println("Try 'movietool help'.");
            return EXIT_USAGE;
        }
        try {
            return command.execute(Arrays.copyOfRange(args, 1, args.length));
        } catch (IllegalArgumentException e) {
            System.err.println("Usage error: " + e.getMessage());
            System.err.println(command.usage());
            return EXIT_USAGE;
        }
    }

    // --------------------------------------------------------------- output

    static void usage(PrintStream out) {
        out.println(new HelpCommand().usage());
    }

    static void print(OperationResult result, Options options) {
        PrintStream out = System.out;
        for (Problem problem : result.problems()) {
            switch (problem.getSeverity()) {
                case ERROR:
                    out.println("[ERROR] " + problem.getMessage());
                    break;
                case WARN:
                    if (options.getVerbosity() >= 1) out.println("[WARN] " + problem.getMessage());
                    break;
                default:
                    if (options.getVerbosity() >= 1) out.println(problem.getMessage());
            }
        }
        if (options.getVerbosity() >= 1 && !result.getReport().isEmpty()) {
            out.println(result.getReport());
        }
        if (options.getVerbosity() >= 2 && result.getTransfers() != null) {
            for (TransferAction transfer : result.getTransfers()) {
                out.println("  " + transfer.state + ": " + transfer.from + " -> " + transfer.to);
            }
        }
    }

    static void printPlan(OperationResult result, Options options) {
        if (result.getRenamePlan() == null) return;
        for (RenameEngine.RenameAction action : result.getRenamePlan().actions()) {
            if (options.getVerbosity() < 1) continue;
            if (action.kind == RenameEngine.RenameAction.Kind.COLLISION) {
                System.out.println("  [COLLISION] " + action.from.getName() + "  ~~>  " + action.to.getName());
            } else {
                System.out.println("  " + action.from.getName() + "  ->  " + action.to.getName());
            }
        }
    }

    static Options baseOptions(ArgParser args) {
        Options options = new Options();
        options.setFolder(args.value("dir", args.value("file", ".")));
        options.setRecursive(args.flag("recursive"));
        options.setApply(args.flag("apply"));
        options.setOverwrite(args.flag("overwrite"));
        options.setMove(args.flag("move"));
        options.setCsv(args.flag("csv"));
        options.setConventions(args.joined("conventions"));
        options.setPattern(args.value("pattern", ""));
        options.setOutput(args.value("out", ""));
        if (args.flag("verbose")) options.setVerbosity(2);
        if (args.flag("quiet")) options.setVerbosity(0);
        return options;
    }

    // ------------------------------------------------------------- commands

    private interface Command {
        String name();

        String summary();

        String usage();

        int execute(String[] args);
    }

    private static class HelpCommand implements Command {
        public String name() { return "help"; }
        public String summary() { return "Show help"; }
        public String usage() {
            StringBuilder sb = new StringBuilder();
            sb.append("MovieTool ").append(Version.TEXT).append(" - organise movie and episode files\n\n");
            sb.append("Usage:\n  movietool <command> [options]\n\nCommands:\n");
            for (Map.Entry<String, Command> entry : COMMANDS.entrySet()) {
                sb.append(String.format("  %-16s %s%n", entry.getKey(), entry.getValue().summary()));
            }
            sb.append("\nRun 'movietool help <command>' for the options of a single command.\n");
            sb.append("With no arguments MovieTool starts the graphical interface.\n");
            return sb.toString();
        }
        public int execute(String[] args) {
            Cli.usage(System.out);
            return EXIT_OK;
        }
    }

    private static class RenameCommand implements Command {
        public String name() { return "rename"; }
        public String summary() { return "Rename videos/subtitles to a chosen convention or pattern"; }
        public String usage() {
            return "Usage: movietool rename -d <folder> (-t <convention> | --pattern <pattern>) [options]\n"
                    + "\nRenames media files to a naming convention. Dry-run by default.\n"
                    + "  -d, --dir <folder>       Folder to scan\n"
                    + "  -t, --to <convention>    Target convention id (see 'movietool conventions')\n"
                    + "      --pattern <pattern>  Custom name, e.g. \"{title} {s01e01}{ext}\"\n"
                    + "  -c, --conventions <ids>  Restrict auto-detection, e.g. moviebox,awafim\n"
                    + "  -r, --recursive          Include sub-folders\n"
                    + "      --apply              Really rename (default is a dry run)\n"
                    + "  -v, --verbose            Show every planned rename\n"
                    + "  -q, --quiet              Only errors\n";
        }
        public int execute(String[] args) {
            ArgParser argsParser = new ArgParser(args, aliases(), booleanFlags());
            Options options = baseOptions(argsParser);
            String target = argsParser.value("to", "");
            if (target.isEmpty() && !options.hasPattern()) {
                System.err.println("Nothing to do: pass -t <convention> or --pattern.");
                System.err.println(usage());
                return EXIT_USAGE;
            }
            RenameEngine engine = new RenameEngine();
            OperationResult result = new OperationResult();
            RenameEngine.RenamePlan plan = engine.plan(options, target);
            result.setRenamePlan(plan);
            for (Problem problem : engine.problems()) result.add(problem);

            if (plan.isEmpty() && engine.problems().isEmpty()) {
                System.out.println("Nothing to rename, all names already conform.");
                return EXIT_OK;
            }
            printPlan(result, options);
            print(result, options);

            if (!options.isApply()) {
                System.out.println(plan.renameCount() + " rename(s) planned (dry run; add --apply to execute).");
                return plan.renameCount() > 0 && result.errorCount() > 0 ? EXIT_ERRORS : EXIT_OK;
            }
            if (result.errorCount() > 0) {
                System.out.println("Plan has errors; fix them first (nothing was renamed).");
                return EXIT_ERRORS;
            }
            try {
                plan.apply();
            } catch (IOException e) {
                System.err.println("[ERROR] Rename failed, rolled back where possible: " + e.getMessage());
                return EXIT_ERRORS;
            }
            System.out.println(plan.renameCount() + " rename(s) done.");
            return result.errorCount() > 0 ? EXIT_ERRORS : EXIT_OK;
        }
    }

    private static class ImdbRenameCommand implements Command {
        public String name() { return "imdb-rename"; }
        public String summary() { return "Rename episodes to their IMDB titles from a titles list"; }
        public String usage() {
            return "Usage: movietool imdb-rename -d <folder> [--titles <file>] [options]\n"
                    + "\nReads an episode list with lines like 'S1.E2 \u2219 Episode Title' (as copied\n"
                    + "from IMDB or Netflix) and renames each episode's video and subtitle to\n"
                    + "'<Show> - S01E02 - <Episode Title>.MVB.IMDB.en<ext>' - the tag the old\n"
                    + "MoviesRenamer wrote; pass --no-tag to omit it. Characters Windows forbids\n"
                    + "(?, :, \" ...) in titles are sanitised automatically. Dry-run by default.\n"
                    + "  -d, --dir <folder>       Folder with the episode files\n"
                    + "      --titles <file>      Titles list (default: <folder>/titles.list)\n"
                    + "  -s, --subs <folder>      Extra subtitles folder (default: 'Subtitles'\n"
                    + "                           inside --dir when present, else --dir itself)\n"
                    + "       --show <name>       Override the show name (default: from the files)\n"
                    + "       --style <style>     's01e01' (default) or '1x01'\n"
                    + "       --tag <tags>        Tag appended before the extension\n"
                    + "                           (default: MVB.IMDB.en)\n"
                    + "       --no-tag            Append no tag at all\n"
                    + "       --replace-with <c>  Replace illegal characters with this (default: remove)\n"
                    + "  -r, --recursive          Also scan video sub-folders\n"
                    + "      --overwrite          Replace existing files\n"
                    + "      --apply              Really rename (default is a dry run)\n"
                    + "  -v, --verbose            Show every planned rename\n";
        }
        public int execute(String[] args) {
            ArgParser argsParser = new ArgParser(args, aliases(), booleanFlags());
            Options options = baseOptions(argsParser);
            options.setSecondaryFolder(argsParser.joined("subs"));
            options.setTitlesFile(argsParser.joined("titles"));
            options.setShowName(argsParser.value("show", ""));
            if (argsParser.has("tag")) options.setTag(argsParser.joined("tag"));
            if (argsParser.flag("no-tag")) options.setTag("");
            options.setReplaceWith(argsParser.value("replace-with", ""));
            String style = argsParser.value("style", "s01e01").trim();
            String normalized = style.toLowerCase();
            if (!(normalized.equals("s01e01") || normalized.equals("sxe") || normalized.equals("sxxexx")
                    || normalized.equals("1x01") || normalized.equals("x") || normalized.equals("nxn"))) {
                System.err.println("Unknown --style: " + style + " (use s01e01 or 1x01)");
                return EXIT_USAGE;
            }
            options.setStyle(normalized);

            ImdbRename operation = new ImdbRename();
            OperationResult result = operation.plan(options);
            if (options.getVerbosity() >= 1) {
                for (TransferAction transfer : result.getTransfers()) {
                    System.out.println("  " + transfer.state + ": " + transfer.from.getName()
                            + "  ->  " + transfer.to.getName());
                }
            }
            print(result, options);
            if (!options.isApply()) {
                System.out.println("Dry run; add --apply to rename the files.");
                return result.errorCount() > 0 ? EXIT_ERRORS : EXIT_OK;
            }
            operation.apply(result, options);
            int done = 0;
            for (TransferAction t : result.getTransfers()) {
                if (t.state == TransferAction.State.DONE) done++;
            }
            System.out.println(done + " rename(s) done.");
            return result.errorCount() > 0 ? EXIT_ERRORS : EXIT_OK;
        }
    }

    private static class RelocateSubsCommand implements Command {
        public String name() { return "relocate-subs"; }
        public String summary() { return "Move subtitle files into the folder's 'Subtitles' sub-folder"; }
        public String usage() {
            return "Usage: movietool relocate-subs -d <folder> [options]\n"
                    + "\nMoves subtitle files (.srt .vtt .ass .ssa .sub) that sit next to the\n"
                    + "videos into the folder's 'Subtitles' sub-folder - the layout every\n"
                    + "MovieTool operation expects. Names are kept as they are, and files\n"
                    + "already inside 'Subtitles' are never touched. Dry-run by default.\n"
                    + "  -d, --dir <folder>       Videos folder\n"
                    + "  -r, --recursive          Also scan video sub-folders ('Subtitles'\n"
                    + "                           itself is never scanned)\n"
                    + "      --overwrite          Replace existing files in 'Subtitles'\n"
                    + "      --apply              Really move (default is a dry run)\n"
                    + "  -v, --verbose            Show every planned move\n";
        }
        public int execute(String[] args) {
            ArgParser argsParser = new ArgParser(args, aliases(), booleanFlags());
            Options options = baseOptions(argsParser);
            SubsRelocator relocator = new SubsRelocator();
            OperationResult result = relocator.plan(options);
            if (options.getVerbosity() >= 1) {
                for (TransferAction transfer : result.getTransfers()) {
                    System.out.println("  " + transfer.state + ": " + transfer.from.getName()
                            + "  ->  " + transfer.to.getName());
                }
            }
            print(result, options);
            if (!options.isApply()) {
                System.out.println("Dry run; add --apply to move the files.");
                return result.errorCount() > 0 ? EXIT_ERRORS : EXIT_OK;
            }
            relocator.apply(result, options);
            int done = 0;
            for (TransferAction t : result.getTransfers()) {
                if (t.state == TransferAction.State.DONE) done++;
            }
            System.out.println(done + " subtitle file(s) moved.");
            return result.errorCount() > 0 ? EXIT_ERRORS : EXIT_OK;
        }
    }

    private static class SyncSubsCommand implements Command {
        public String name() { return "sync-subs"; }
        public String summary() { return "Copy/move subtitles next to their matching videos"; }
        public String usage() {
            return "Usage: movietool sync-subs -d <videos-folder> [-s <subs-folder>] [options]\n"
                    + "\nMatches subtitles to videos (episode aware, any conventions) and copies\n"
                    + "each subtitle next to its video named exactly like the video. Dry-run by default.\n"
                    + "  -d, --dir <folder>       Videos folder\n"
                    + "  -s, --subs <folder>      Subtitles folder (default: 'Subtitles' inside\n"
                    + "                           --dir when present, else --dir itself)\n"
                    + "  -c, --conventions <ids>  Restrict auto-detection\n"
                    + "  -r, --recursive          Scan sub-folders (default: on for --subs, off for --dir)\n"
                    + "      --recursive-videos   Also scan video sub-folders\n"
                    + "      --move               Move subtitles instead of copying\n"
                    + "      --overwrite          Replace existing subtitles\n"
                    + "      --apply              Really transfer (default is a dry run)\n"
                    + "  -v, --verbose            Show every transfer\n";
        }
        public int execute(String[] args) {
            ArgParser argsParser = new ArgParser(args, aliases(), booleanFlags());
            Options options = baseOptions(argsParser);
            options.setSecondaryFolder(argsParser.value("subs", ""));
            if (argsParser.has("recursive-videos")) options.setRecursive(true);
            else if (!argsParser.has("recursive")) options.setRecursive(false);

            SubsSync sync = new SubsSync();
            OperationResult result = sync.plan(options);
            print(result, options);
            if (!options.isApply()) {
                long planned = 0;
                for (TransferAction t : result.getTransfers()) {
                    if (t.state == TransferAction.State.PLANNED) planned++;
                }
                System.out.println(planned + " transfer(s) planned (dry run; add --apply to execute).");
                return result.errorCount() > 0 ? EXIT_ERRORS : EXIT_OK;
            }
            sync.apply(result, options);
            int done = 0;
            for (TransferAction t : result.getTransfers()) {
                if (t.state == TransferAction.State.DONE) done++;
            }
            System.out.println(done + " transfer(s) done.");
            return result.errorCount() > 0 ? EXIT_ERRORS : EXIT_OK;
        }
    }

    private static class FlattenCommand implements Command {
        public String name() { return "flatten"; }
        public String summary() { return "Move files out of sub-folders into one folder"; }
        public String usage() {
            return "Usage: movietool flatten -d <folder> [-o <target-folder>] [options]\n"
                    + "\nMoves every media file below the folder up into the target folder\n"
                    + "(default: the folder itself), renaming on collisions and removing\n"
                    + "folders it empties. Dry-run by default.\n"
                    + "  -d, --dir <folder>     Folder to flatten\n"
                    + "  -o, --out <folder>     Target folder (default: the folder itself)\n"
                    + "      --apply            Really move (default is a dry run)\n"
                    + "  -v, --verbose          Show every move\n";
        }
        public int execute(String[] args) {
            ArgParser argsParser = new ArgParser(args, aliases(), booleanFlags());
            Options options = baseOptions(argsParser);
            Flattener flattener = new Flattener();
            OperationResult result = flattener.plan(options);
            print(result, options);
            if (!options.isApply()) {
                System.out.println("Dry run; add --apply to move the files.");
                return EXIT_OK;
            }
            flattener.apply(result, options);
            return result.errorCount() > 0 ? EXIT_ERRORS : EXIT_OK;
        }
    }

    private static class MergeSubsCommand implements Command {
        public String name() { return "merge-subs"; }
        public String summary() { return "Stack two or more subtitle tracks into one SRT"; }
        public String usage() {
            return "Usage: movietool merge-subs <first.srt> <second.srt> [more.srt ...] [-o out.srt] [--top]\n"
                    + "\nOverlapping cues from both tracks are stacked; identical neighbours merge.\n"
                    + "  -o, --out <file>   Output SRT (default: <first>.merged.srt)\n"
                    + "      --top          Put the second track's text on top when both are active\n";
        }
        public int execute(String[] args) {
            ArgParser argsParser = new ArgParser(args, aliases(), booleanFlags());
            Options options = baseOptions(argsParser);
            List<String> positionals = argsParser.positionals();
            if (positionals.size() < 2) {
                System.err.println("Two or more subtitle files are required.");
                System.err.println(usage());
                return EXIT_USAGE;
            }
            options.setFolder(positionals.get(0));
            options.setSecondaryFolder(positionals.get(1));
            SubsMerger merger = new SubsMerger();
            OperationResult result = merger.merge(options);
            print(result, options);
            return result.errorCount() > 0 ? EXIT_ERRORS : EXIT_OK;
        }
    }

    private static class ConvertVttCommand implements Command {
        public String name() { return "convert-vtt"; }
        public String summary() { return "Convert WebVTT subtitles to SRT"; }
        public String usage() {
            return "Usage: movietool convert-vtt <file-or-folder> [-o <out-file-or-folder>] [-r] [--overwrite]\n"
                    + "\nConverts .vtt to .srt. With a folder, every .vtt below it is converted\n"
                    + "mirroring the layout into the output folder when one is given.\n";
        }
        public int execute(String[] args) {
            ArgParser argsParser = new ArgParser(args, aliases(), booleanFlags());
            Options options = baseOptions(argsParser);
            List<String> positionals = argsParser.positionals();
            if (!positionals.isEmpty()) options.setFolder(positionals.get(0));
            if (options.getFolder().isEmpty()) {
                System.err.println("No input given.");
                System.err.println(usage());
                return EXIT_USAGE;
            }
            VttConvert converter = new VttConvert();
            OperationResult result = converter.convert(options);
            print(result, options);
            return result.errorCount() > 0 ? EXIT_ERRORS : EXIT_OK;
        }
    }

    private static class ShiftSubsCommand implements Command {
        public String name() { return "shift-subs"; }
        public String summary() { return "Shift subtitle timing by a constant offset"; }
        public String usage() {
            return "Usage: movietool shift-subs <file.srt> --seconds <n> [-o out.srt] [--no-backup]\n"
                    + "\nPositive values delay the subtitles, negative values advance them.\n"
                    + "A .bak copy is kept next to the original unless --no-backup is given.\n"
                    + "      --seconds <n.n>   Amount to shift, e.g. 2.5 or -3\n";
        }
        public int execute(String[] args) {
            ArgParser argsParser = new ArgParser(args, aliases(), booleanFlags());
            Options options = baseOptions(argsParser);
            List<String> positionals = argsParser.positionals();
            if (!positionals.isEmpty()) options.setFolder(positionals.get(0));
            String seconds = argsParser.value("seconds", "");
            if (seconds.isEmpty()) {
                System.err.println("--seconds is required.");
                System.err.println(usage());
                return EXIT_USAGE;
            }
            try {
                options.setShiftSeconds(Double.parseDouble(seconds));
            } catch (NumberFormatException e) {
                System.err.println("Not a number: " + seconds);
                return EXIT_USAGE;
            }
            SubsShift shifter = new SubsShift();
            OperationResult result = shifter.shift(options);
            print(result, options);
            return result.errorCount() > 0 ? EXIT_ERRORS : EXIT_OK;
        }
    }

    private static class ListEpisodesCommand implements Command {
        public String name() { return "list-episodes"; }
        public String summary() { return "List episodes per show and season (optionally CSV)"; }
        public String usage() {
            return "Usage: movietool list-episodes -d <folder> [-r] [-c conventions] [--csv] [-o out.csv]\n"
                    + "\nPrints every recognised episode grouped per show and season.\n"
                    + "      --csv   Emit CSV instead of a formatted list\n";
        }
        public int execute(String[] args) {
            ArgParser argsParser = new ArgParser(args, aliases(), booleanFlags());
            Options options = baseOptions(argsParser);
            EpisodeLister lister = new EpisodeLister();
            OperationResult result = lister.list(options);
            String listing = result.getReport();
            if (!options.getOutput().isEmpty()) {
                print(result, options);
                if (listing.isEmpty()) return EXIT_OK;
                try {
                    IoUtil.writeText(new File(options.getOutput()), listing);
                    System.out.println("Written: " + options.getOutput());
                } catch (IOException e) {
                    System.err.println("[ERROR] Cannot write " + options.getOutput() + ": " + e.getMessage());
                    return EXIT_ERRORS;
                }
                return result.errorCount() > 0 ? EXIT_ERRORS : EXIT_OK;
            }
            // The listing itself is the output; suppress the duplicate report print.
            if (options.getVerbosity() >= 1 && !listing.isEmpty()) {
                System.out.println(listing);
            }
            for (Problem problem : result.problems()) {
                if (problem.getSeverity() != Problem.Severity.INFO && options.getVerbosity() >= 1) {
                    System.out.println(problem);
                }
            }
            return result.errorCount() > 0 ? EXIT_ERRORS : EXIT_OK;
        }
    }

    private static class CheckCommand implements Command {
        public String name() { return "check"; }
        public String summary() { return "Report conventions, duplicates and missing subtitles"; }
        public String usage() {
            return "Usage: movietool check -d <folder> [-r] [-c conventions]\n"
                    + "\nRead-only health report for a media library.\n";
        }
        public int execute(String[] args) {
            ArgParser argsParser = new ArgParser(args, aliases(), booleanFlags());
            Options options = baseOptions(argsParser);
            HealthCheck check = new HealthCheck();
            OperationResult result = check.check(options);
            print(result, options);
            if (options.getVerbosity() >= 1 && !result.getReport().isEmpty()) {
                // report already printed by print(); keep order tidy
            }
            return EXIT_OK;
        }
    }

    private static class ConventionsCommand implements Command {
        public String name() { return "conventions"; }
        public String summary() { return "List the known naming conventions"; }
        public String usage() { return "Usage: movietool conventions\n"; }
        public int execute(String[] args) {
            ConventionRegistry registry = new ConventionRegistry();
            System.out.println("Known naming conventions (ids for -t/--to and -c/--conventions):\n");
            for (movies.core.NamingConvention convention : registry.all()) {
                System.out.println(String.format("  %-14s %-16s %s", convention.id(), convention.displayName(), convention.example()));
            }
            return EXIT_OK;
        }
    }

    private static class GuiCommand implements Command {
        public String name() { return "gui"; }
        public String summary() { return "Open the graphical interface"; }
        public String usage() { return "Usage: movietool gui\n"; }
        public int execute(String[] args) {
            MovieToolGui.launch();
            return EXIT_OK;
        }
    }

    // -------------------------------------------------------------- aliases

    private static Map<String, String> aliases() {
        Map<String, String> aliases = new HashMap<String, String>();
        aliases.put("dir", "--dir,-d");
        aliases.put("file", "--file,-f");
        aliases.put("subs", "--subs,-s");
        aliases.put("out", "--out,-o");
        aliases.put("to", "--to,-t");
        aliases.put("pattern", "--pattern,-p");
        aliases.put("conventions", "--conventions,-c");
        aliases.put("recursive", "--recursive,-r");
        aliases.put("recursive-videos", "--recursive-videos");
        aliases.put("apply", "--apply,-a");
        aliases.put("overwrite", "--overwrite");
        aliases.put("move", "--move,-m");
        aliases.put("csv", "--csv");
        aliases.put("seconds", "--seconds");
        aliases.put("titles", "--titles");
        aliases.put("show", "--show");
        aliases.put("style", "--style");
        aliases.put("tag", "--tag");
        aliases.put("no-tag", "--no-tag");
        aliases.put("replace-with", "--replace-with");
        aliases.put("top", "--top");
        aliases.put("verbose", "--verbose,-v");
        aliases.put("quiet", "--quiet,-q");
        aliases.put("backup", "--backup");
        aliases.put("no-backup", "--no-backup");
        return aliases;
    }

    private static Set<String> booleanFlags() {
        return new HashSet<String>(Arrays.asList(
                "recursive", "recursive-videos", "apply", "overwrite", "move", "csv", "top",
                "verbose", "quiet", "no-backup", "backup", "no-tag"));
    }
}
