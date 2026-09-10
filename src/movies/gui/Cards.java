package movies.gui;

import movies.core.ConventionRegistry;
import movies.core.OperationResult;
import movies.core.Options;
import movies.core.Problem;
import movies.core.RenameEngine;
import movies.core.TransferAction;
import movies.ops.EpisodeLister;
import movies.ops.Flattener;
import movies.ops.ImdbRename;
import movies.ops.HealthCheck;
import movies.ops.SubsDownloader;
import movies.ops.SubsMerger;
import movies.ops.SubsRelocator;
import movies.ops.SubsShift;
import movies.ops.SubsSync;
import movies.ops.TitlesCleaner;
import movies.ops.VttConvert;
import movies.util.IoUtil;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * The concrete operation cards. Each is a small form; execution is delegated
 * to the same operations the CLI uses, so behaviour never diverges.
 */
final class Cards {

    private Cards() {
    }

    /** Simple two-column form builder used by every card. */
    static final class Form {
        private final JPanel panel = new JPanel(new GridBagLayout());
        private int row;

        JTextField addPathField(String label, boolean directory) {
            return addPathField(label, directory, null);
        }

        JTextField addPathField(String label, boolean directory, String hint) {
            JTextField field = new JTextField(32);
            GuiUtil.enableFileDrop(field);
            JPanel right = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
            right.add(GuiUtil.browseButton(panel, field, directory));
            if (hint != null) right.add(new JLabel(hint));
            addRow(label, field, right);
            return field;
        }

        JTextField addTextField(String label, String hint) {
            JTextField field = new JTextField(32);
            addRow(label, field, hint == null ? null : new JLabel(hint));
            return field;
        }

        JCheckBox addCheckbox(String label, boolean selected) {
            JCheckBox box = new JCheckBox(label, selected);
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 1;
            c.gridy = row++;
            c.anchor = GridBagConstraints.WEST;
            c.insets = new Insets(4, 4, 4, 4);
            panel.add(box, c);
            return box;
        }

        void addRow(String label, JComponent left, JComponent right) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0;
            c.gridy = row;
            c.anchor = GridBagConstraints.NORTHEAST;
            c.insets = new Insets(4, 8, 4, 4);
            panel.add(new JLabel(label), c);
            c = new GridBagConstraints();
            c.gridx = 1;
            c.gridy = row;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.weightx = 1.0;
            c.insets = new Insets(4, 4, 4, 4);
            panel.add(left, c);
            if (right != null) {
                c = new GridBagConstraints();
                c.gridx = 2;
                c.gridy = row;
                c.anchor = GridBagConstraints.WEST;
                c.insets = new Insets(4, 4, 4, 8);
                panel.add(right, c);
            }
            row++;
        }

        JPanel panel() {
            JPanel wrapper = new JPanel(new GridBagLayout());
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0;
            c.gridy = 0;
            c.anchor = GridBagConstraints.NORTH;
            c.weightx = 1.0;
            wrapper.add(panel, c);
            return wrapper;
        }
    }

    /** Shared plumbing for cards that preview a plan and can apply it. */
    abstract static class PlanCard extends OpCard {
        boolean lastPlanApplicable;

        PlanCard(String title, String description) {
            super(title, description);
        }

        @Override
        public boolean canApply() {
            return true;
        }

        @Override
        public boolean isApplyReady() {
            return lastPlanApplicable;
        }
    }

    // ------------------------------------------------------------- rename

    static final class RenameCard extends PlanCard {
        private final Form form = new Form();
        private final JTextField dir = form.addPathField("Folder:", true);
        private final JComboBox<String> target = new JComboBox<String>();
        private final JTextField pattern = form.addTextField("Or custom pattern:", "{title} {s01e01}{ext}");
        private final JTextField conventions = form.addTextField("Only detect:", "e.g. moviebox,awafim (blank = all)");
        private final JCheckBox recursive = form.addCheckbox("Include sub-folders", false);
        private final JCheckBox dryRun = form.addCheckbox("Dry run (preview only)", true);
        private final ConventionRegistry registry = new ConventionRegistry();
        private final RenameEngine engine = new RenameEngine();
        private volatile RenameEngine.RenamePlan plan;

        RenameCard() {
            super("Rename", "Rename videos and subtitles to a naming convention or a custom pattern.");
            target.addItem("(custom pattern)");
            for (movies.core.NamingConvention convention : registry.all()) {
                target.addItem(convention.id() + "  -  " + convention.example());
            }
            form.addRow("Rename to:", target, null);
        }

        @Override
        public JComponent component() {
            return form.panel();
        }

        @Override
        public void collect(Options options) {
            options.setFolder(dir.getText().trim());
            options.setRecursive(recursive.isSelected());
            options.setDryRun(dryRun.isSelected());
            options.setPattern(pattern.getText().trim());
            options.setConventions(conventions.getText().trim());
        }

        @Override
        public OperationResult run(Options options) {
            OperationResult result = new OperationResult();
            plan = null;
            lastPlanApplicable = false;
            String selected = String.valueOf(target.getSelectedItem());
            String targetId = selected.startsWith("(") ? "" : selected.split("\\s+", 2)[0];
            if (targetId.isEmpty() && !options.hasPattern()) {
                result.add(Problem.error("Pick a target convention or type a custom pattern."));
                return result;
            }
            plan = engine.plan(options, targetId);
            result.setRenamePlan(plan);
            for (Problem problem : engine.problems()) result.add(problem);
            if (plan.isEmpty() && engine.problems().isEmpty()) {
                result.add(Problem.info("Nothing to rename, all names already conform."));
                return result;
            }
            if (!options.isDryRun() && result.errorCount() == 0) {
                try {
                    plan.apply();
                    result.setReport(plan.renameCount() + " rename(s) applied.");
                } catch (Exception e) {
                    result.add(Problem.error("Rename failed, rolled back where possible: " + e.getMessage()));
                }
                lastPlanApplicable = false;
            } else {
                result.setReport(plan.renameCount() + " rename(s) planned (dry run)");
                lastPlanApplicable = plan != null && !plan.isEmpty() && result.errorCount() == 0;
            }
            return result;
        }

        @Override
        public OperationResult apply() {
            OperationResult result = new OperationResult();
            if (plan == null) {
                result.add(Problem.error("Run a dry run first."));
                return result;
            }
            try {
                plan.apply();
                result.add(Problem.info(plan.renameCount() + " rename(s) applied."));
                lastPlanApplicable = false;
            } catch (Exception e) {
                result.add(Problem.error("Rename failed, rolled back where possible: " + e.getMessage()));
            }
            return result;
        }

    }

    // --------------------------------------------------------- imdb-rename

    static final class ImdbCard extends PlanCard {
        private final Form form = new Form();
        private final JTextField dir = form.addPathField("Folder:", true, null);
        private final JTextField titles = form.addPathField("Titles list:", false,
                "default: titles.list in the folder");
        private final JTextField show = form.addTextField("Show name (optional):", "default: detected from the files");
        private final JTextField subs = form.addPathField("Subtitles folder:", true,
                "optional; 'Subtitles' inside the folder is used when present");
        private final JComboBox<String> style = new JComboBox<String>(new String[] { "S01E01", "1x01" });
        private final JTextField tag = form.addTextField("Tag:",
                "appended before the extension (default keeps the old MoviesRenamer tag)");
        private final JCheckBox appendTag = form.addCheckbox("Append tag to renamed files", true);
        private final JTextField replaceWith = form.addTextField("Replace illegal chars with:", "blank = remove (?, : ...)");
        private final JCheckBox recursive = form.addCheckbox("Scan video sub-folders", false);
        private final JCheckBox overwrite = form.addCheckbox("Overwrite existing files", false);
        private final JCheckBox dryRun = form.addCheckbox("Dry run (preview only)", true);
        private final ImdbRename operation = new ImdbRename();
        private volatile OperationResult lastResult;
        private volatile Options lastOptions;

        ImdbCard() {
            super("IMDB rename", "Rename episodes to their IMDB titles from a titles list ('S1.E2 ∙ Title'), subtitles included.");
            tag.setText("MVB.IMDB.en");
            form.addRow("Episode marker:", style, null);
        }

        @Override
        public JComponent component() {
            return form.panel();
        }

        @Override
        public void collect(Options options) {
            options.setFolder(dir.getText().trim());
            options.setTitlesFile(titles.getText().trim());
            options.setShowName(show.getText().trim());
            options.setSecondaryFolder(subs.getText().trim());
            options.setStyle(style.getSelectedIndex() == 1 ? "1x01" : "s01e01");
            options.setTag(appendTag.isSelected() ? tag.getText().trim() : "");
            options.setReplaceWith(replaceWith.getText().trim());
            options.setRecursive(recursive.isSelected());
            options.setOverwrite(overwrite.isSelected());
            options.setDryRun(dryRun.isSelected());
        }

        @Override
        public OperationResult run(Options options) {
            lastPlanApplicable = false;
            lastResult = null;
            lastOptions = options;
            OperationResult result = operation.plan(options);
            long planned = 0;
            for (TransferAction t : result.getTransfers()) {
                if (t.state == TransferAction.State.PLANNED) planned++;
            }
            if (!options.isDryRun() && planned > 0 && result.errorCount() == 0) {
                operation.apply(result, options);
                lastPlanApplicable = false;
            } else {
                lastPlanApplicable = planned > 0 && result.errorCount() == 0;
            }
            return result;
        }

        @Override
        public OperationResult apply() {
            if (lastResult == null || lastOptions == null) {
                OperationResult result = new OperationResult();
                result.add(Problem.error("Run a dry run first."));
                return result;
            }
            operation.apply(lastResult, lastOptions);
            lastPlanApplicable = false;
            return lastResult;
        }
    }

    // ----------------------------------------------------------- sync-subs

    static final class SyncCard extends PlanCard {
        private final Form form = new Form();
        private final JTextField videos = form.addPathField("Videos folder:", true);
        private final JTextField subs = form.addPathField("Subtitles folder:", true,
                "blank = 'Subtitles' inside the videos folder if present");
        private final JCheckBox recursive = form.addCheckbox("Scan sub-folders", true);
        private final JCheckBox move = form.addCheckbox("Move instead of copy", false);
        private final JCheckBox overwrite = form.addCheckbox("Overwrite existing subtitles", false);
        private final JCheckBox dryRun = form.addCheckbox("Dry run (preview only)", true);
        private final SubsSync sync = new SubsSync();
        private volatile OperationResult lastResult;

        SyncCard() {
            super("Sync subtitles", "Match subtitles to their videos and put them next to each video, named like the video.");
            subs.setText("");
        }

        @Override
        public JComponent component() {
            return form.panel();
        }

        @Override
        public void collect(Options options) {
            options.setFolder(videos.getText().trim());
            options.setSecondaryFolder(subs.getText().trim());
            options.setRecursive(recursive.isSelected());
            options.setMove(move.isSelected());
            options.setOverwrite(overwrite.isSelected());
            options.setDryRun(dryRun.isSelected());
        }

        @Override
        public OperationResult run(Options options) {
            lastPlanApplicable = false;
            lastResult = sync.plan(options);
            long planned = 0;
            for (TransferAction t : lastResult.getTransfers()) {
                if (t.state == TransferAction.State.PLANNED) planned++;
            }
            if (!options.isDryRun() && planned > 0 && lastResult.errorCount() == 0) {
                sync.apply(lastResult, options);
                lastPlanApplicable = false;
            } else {
                lastPlanApplicable = planned > 0 && lastResult.errorCount() == 0;
            }
            if (planned == 0 && lastResult.errorCount() == 0) {
                lastResult.add(Problem.info("Nothing to transfer."));
            }
            return lastResult;
        }

        @Override
        public OperationResult apply() {
            if (lastResult == null) {
                OperationResult result = new OperationResult();
                result.add(Problem.error("Run a dry run first."));
                return result;
            }
            Options options = new Options();
            options.setMove(move.isSelected());
            sync.apply(lastResult, options);
            lastPlanApplicable = false;
            return lastResult;
        }
    }

    // ---------------------------------------------------------- relocate

    static final class RelocateCard extends PlanCard {
        private final Form form = new Form();
        private final JTextField dir = form.addPathField("Videos folder:", true);
        private final JCheckBox recursive = form.addCheckbox("Scan sub-folders too", false);
        private final JCheckBox overwrite = form.addCheckbox("Overwrite files already in 'Subtitles'", false);
        private final JCheckBox dryRun = form.addCheckbox("Dry run (preview only)", true);
        private final SubsRelocator relocator = new SubsRelocator();
        private volatile OperationResult lastResult;

        RelocateCard() {
            super("Collect subtitles", "Move subtitles that sit outside the 'Subtitles' sub-folder into it: loose files directly; per-episode subtitle folders move whole (the subtitle stays inside its folder); folders that also contain videos keep the videos.");
        }

        @Override
        public JComponent component() {
            return form.panel();
        }

        @Override
        public void collect(Options options) {
            options.setFolder(dir.getText().trim());
            options.setRecursive(recursive.isSelected());
            options.setOverwrite(overwrite.isSelected());
            options.setDryRun(dryRun.isSelected());
        }

        @Override
        public OperationResult run(Options options) {
            lastPlanApplicable = false;
            lastResult = relocator.plan(options);
            long planned = 0;
            for (TransferAction t : lastResult.getTransfers()) {
                if (t.state == TransferAction.State.PLANNED) planned++;
            }
            if (!options.isDryRun() && planned > 0 && lastResult.errorCount() == 0) {
                relocator.apply(lastResult, options);
                lastPlanApplicable = false;
            } else {
                lastPlanApplicable = planned > 0 && lastResult.errorCount() == 0;
            }
            if (planned == 0 && lastResult.errorCount() == 0) {
                lastResult.add(Problem.info("Nothing to move - subtitles are already collected."));
            }
            return lastResult;
        }

        @Override
        public OperationResult apply() {
            if (lastResult == null) {
                OperationResult result = new OperationResult();
                result.add(Problem.error("Run a dry run first."));
                return result;
            }
            relocator.apply(lastResult, new Options());
            lastPlanApplicable = false;
            return lastResult;
        }
    }

    // ------------------------------------------------------- clean-titles

    static final class CleanTitlesCard extends PlanCard {
        private final Form form = new Form();
        private final JTextField titles = form.addPathField("Titles list:", false,
                "default: titles.list in the folder");
        private final JTextField output = form.addTextField("New file (when not replacing):",
                "default: <original>-clean.list next to it");
        private final JCheckBox inPlace = form.addCheckbox("Replace the original (a .bak copy is kept)", false);
        private final JCheckBox sort = form.addCheckbox("Sort by season/episode, remove duplicates", true);
        private final JCheckBox dryRun = form.addCheckbox("Dry run (preview only)", true);
        private final TitlesCleaner cleaner = new TitlesCleaner();
        private volatile OperationResult lastResult;

        CleanTitlesCard() {
            super("Clean titles list", "Strip a titles list down to its episode lines: the show name, 'TV Series' and other noise are removed; the kept lines are rewritten canonically, deduplicated and sorted.");
        }

        @Override
        public JComponent component() {
            return form.panel();
        }

        @Override
        public void collect(Options options) {
            String file = titles.getText().trim();
            options.setTitlesFile(file);
            options.setFolder(file.isEmpty() ? "" : file);   // the launcher requires a non-empty folder
            options.setOutput(output.getText().trim());
            options.setTitlesInPlace(inPlace.isSelected());
            options.setSortTitles(sort.isSelected());
            options.setDryRun(dryRun.isSelected());
        }

        @Override
        public OperationResult run(Options options) {
            lastPlanApplicable = false;
            lastResult = cleaner.plan(options);
            String content = lastResult.getCleanTitlesContent();
            if (content != null) {
                for (String line : content.split("\\r?\\n|\\r")) {
                    if (!line.isEmpty()) lastResult.add(Problem.info("keep: " + line));
                }
            }
            if (!options.isDryRun() && lastResult.errorCount() == 0
                    && lastResult.getCleanTitlesTarget() != null) {
                cleaner.apply(lastResult, options);
            } else {
                lastPlanApplicable = lastResult.errorCount() == 0 && lastResult.getCleanTitlesTarget() != null;
            }
            return lastResult;
        }

        @Override
        public OperationResult apply() {
            if (lastResult == null) {
                OperationResult result = new OperationResult();
                result.add(Problem.error("Run a dry run first."));
                return result;
            }
            Options options = new Options();
            options.setTitlesInPlace(inPlace.isSelected());
            options.setOverwrite(true);
            cleaner.apply(lastResult, options);
            lastPlanApplicable = false;
            return lastResult;
        }
    }

    // ------------------------------------------------------ download-subs

    static final class DownloadSubsCard extends PlanCard {
        private static final String PREF_KEY = "opensubtitles.apikey";
        private static final String PREF_USER = "opensubtitles.user";
        private static final String PREF_SUBDL = "subdl.apikey";
        private final Form form = new Form();
        private final JTextField dir = form.addPathField("Videos folder:", true);
        private final JTextField language = form.addTextField("Subtitle language:", "ISO code, e.g. en");
        private final JTextField apiKey = form.addTextField("OpenSubtitles API key:",
                "free: opensubtitles.com -> profile -> API Consumers");
        private final JTextField osUser = form.addTextField("OpenSubtitles username:",
                "optional; with password it raises the quota from 5 to 20 downloads/day");
        private final javax.swing.JPasswordField osPassword = new javax.swing.JPasswordField(32);
        { form.addRow("OpenSubtitles password:", osPassword,
                new JLabel("kept only for this session; free account = 20 downloads/day, VIP 1000")); }
        private final JTextField subdlKey = form.addTextField("SubDL API key (fallback):",
                "optional; free at subdl.com - adds 50 downloads/day when OpenSubtitles can't deliver");
        private final JCheckBox remember = form.addCheckbox("Remember keys and username on this computer", true);
        private final JCheckBox recursive = form.addCheckbox("Scan video sub-folders", true);
        private final JCheckBox intoFolder = form.addCheckbox("Put downloads into the 'Subtitles' folder", false);
        private final JCheckBox dryRun = form.addCheckbox("Dry run (preview only)", true);
        private final SubsDownloader downloader = new SubsDownloader();
        private volatile OperationResult lastResult;

        DownloadSubsCard() {
            super("Download subtitles", "Bulk-download subtitles for videos that have none (OpenSubtitles.com, SubDL as fallback) and name each one exactly like its video. Free quotas: 5/day with an OpenSubtitles key, 20/day with your account, +50/day with a SubDL key.");
            language.setText("en");
            Preferences prefs = Preferences.userNodeForPackage(Cards.class);
            String saved = prefs.get(PREF_KEY, "");
            String savedUser = prefs.get(PREF_USER, "");
            String savedSubdl = prefs.get(PREF_SUBDL, "");
            if (!saved.isEmpty()) apiKey.setText(saved);
            if (!savedUser.isEmpty()) osUser.setText(savedUser);
            if (!savedSubdl.isEmpty()) subdlKey.setText(savedSubdl);
            if (saved.isEmpty() && savedUser.isEmpty() && savedSubdl.isEmpty()) {
                remember.setSelected(false);
            }
        }

        @Override
        public JComponent component() {
            return form.panel();
        }

        @Override
        public void collect(Options options) {
            options.setFolder(dir.getText().trim());
            options.setRecursive(recursive.isSelected());
            options.setSubsLanguage(language.getText().trim());
            options.setApiKey(apiKey.getText().trim());
            options.setOsUser(osUser.getText().trim());
            options.setOsPassword(new String(osPassword.getPassword()));
            options.setSubdlApiKey(subdlKey.getText().trim());
            options.setSubsIntoFolder(intoFolder.isSelected());
            options.setDryRun(dryRun.isSelected());
            Preferences prefs = Preferences.userNodeForPackage(Cards.class);
            if (remember.isSelected()) {
                if (!options.getApiKey().isEmpty()) prefs.put(PREF_KEY, options.getApiKey());
                if (!options.getOsUser().isEmpty()) prefs.put(PREF_USER, options.getOsUser());
                if (!options.getSubdlApiKey().isEmpty()) prefs.put(PREF_SUBDL, options.getSubdlApiKey());
            } else {
                prefs.remove(PREF_KEY);
                prefs.remove(PREF_USER);
                prefs.remove(PREF_SUBDL);
            }
        }

        @Override
        public OperationResult run(Options options) {
            lastPlanApplicable = false;
            lastResult = downloader.plan(options);
            if (!options.isDryRun() && !lastResult.getMissingVideos().isEmpty()
                    && lastResult.errorCount() == 0) {
                downloader.apply(lastResult, options);
            } else {
                lastPlanApplicable = lastResult.getMissingVideos() != null
                        && !lastResult.getMissingVideos().isEmpty() && lastResult.errorCount() == 0;
            }
            return lastResult;
        }

        @Override
        public OperationResult apply() {
            if (lastResult == null || lastResult.getMissingVideos() == null) {
                OperationResult result = new OperationResult();
                result.add(Problem.error("Run a dry run first."));
                return result;
            }
            Options options = new Options();
            collect(options);
            options.setDryRun(false);
            downloader.apply(lastResult, options);
            lastPlanApplicable = false;
            return lastResult;
        }
    }

    // ------------------------------------------------------------ flatten


    static final class FlattenCard extends PlanCard {
        private final Form form = new Form();
        private final JTextField dir = form.addPathField("Folder to flatten:", true);
        private final JTextField target = form.addPathField("Target folder:", true);
        private final JCheckBox dryRun = form.addCheckbox("Dry run (preview only)", true);
        private final Flattener flattener = new Flattener();
        private volatile OperationResult lastResult;

        FlattenCard() {
            super("Flatten", "Move files out of nested folders into a single folder.");
        }

        @Override
        public JComponent component() {
            return form.panel();
        }

        @Override
        public void collect(Options options) {
            options.setFolder(dir.getText().trim());
            options.setOutput(target.getText().trim());
            options.setDryRun(dryRun.isSelected());
        }

        @Override
        public OperationResult run(Options options) {
            lastPlanApplicable = false;
            lastResult = flattener.plan(options);
            long planned = 0;
            for (TransferAction t : lastResult.getTransfers()) {
                if (t.state == TransferAction.State.PLANNED) planned++;
            }
            if (!options.isDryRun() && planned > 0 && lastResult.errorCount() == 0) {
                Options applyOptions = new Options();
                flattener.apply(lastResult, applyOptions);
                lastPlanApplicable = false;
            } else {
                lastPlanApplicable = planned > 0 && lastResult.errorCount() == 0;
            }
            if (planned == 0 && lastResult.errorCount() == 0) lastResult.add(Problem.info("Nothing to flatten."));
            return lastResult;
        }

        @Override
        public OperationResult apply() {
            if (lastResult == null) {
                OperationResult result = new OperationResult();
                result.add(Problem.error("Run a dry run first."));
                return result;
            }
            Options options = new Options();
            flattener.apply(lastResult, options);
            lastPlanApplicable = false;
            return lastResult;
        }
    }

    // ---------------------------------------------------------- merge-subs

    static final class MergeCard extends OpCard {
        private final Form form = new Form();
        private final JTextField first = form.addPathField("First subtitles:", false);
        private final JTextField second = form.addPathField("Second subtitles:", false);
        private final JTextField output = form.addTextField("Output (optional):", "default: <first>.merged.srt");
        private final JCheckBox top = form.addCheckbox("Second track's text on top", false);
        private final SubsMerger merger = new SubsMerger();

        MergeCard() {
            super("Merge subtitles", "Stack two subtitle tracks (e.g. two languages or SDH) into one SRT.");
        }

        @Override
        public JComponent component() {
            return form.panel();
        }

        @Override
        public void collect(Options options) {
            options.setFolder(first.getText().trim());
            options.setSecondaryFolder(second.getText().trim());
            options.setOutput(output.getText().trim());
            options.setSecondOnTop(top.isSelected());
        }

        @Override
        public OperationResult run(Options options) {
            return merger.merge(options);
        }
    }

    // --------------------------------------------------------- convert-vtt

    static final class VttCard extends OpCard {
        private final Form form = new Form();
        private final JTextField input = form.addPathField("VTT file or folder:", true);
        private final JTextField output = form.addTextField("Output (optional):", "blank = beside the input");
        private final JCheckBox recursive = form.addCheckbox("Scan sub-folders", true);
        private final JCheckBox overwrite = form.addCheckbox("Overwrite existing SRT", false);
        private final VttConvert converter = new VttConvert();

        VttCard() {
            super("VTT to SRT", "Convert WebVTT subtitles to SRT, one file or a whole folder tree.");
        }

        @Override
        public JComponent component() {
            return form.panel();
        }

        @Override
        public void collect(Options options) {
            options.setFolder(input.getText().trim());
            options.setOutput(output.getText().trim());
            options.setRecursive(recursive.isSelected());
            options.setOverwrite(overwrite.isSelected());
        }

        @Override
        public OperationResult run(Options options) {
            return converter.convert(options);
        }
    }

    // ---------------------------------------------------------- shift-subs

    static final class ShiftCard extends OpCard {
        private final Form form = new Form();
        private final JTextField file = form.addPathField("Subtitles:", false);
        private final JSpinner seconds = new JSpinner(new SpinnerNumberModel(Double.valueOf(0), Double.valueOf(-600),
                Double.valueOf(600), Double.valueOf(0.5)));
        private final JCheckBox backup = form.addCheckbox("Keep a .bak copy", true);
        private final SubsShift shifter = new SubsShift();

        ShiftCard() {
            super("Shift timing", "Move every subtitle earlier or later by a constant amount (positive = later).");
        }

        @Override
        public JComponent component() {
            return form.panel();
        }

        @Override
        public void collect(Options options) {
            options.setFolder(file.getText().trim());
            options.setShiftSeconds(((Number) seconds.getValue()).doubleValue());
            options.setBackup(backup.isSelected());
        }

        @Override
        public OperationResult run(Options options) {
            return shifter.shift(options);
        }
    }

    // ------------------------------------------------------- list-episodes

    static final class EpisodesCard extends OpCard {
        private final Form form = new Form();
        private final JTextField dir = form.addPathField("Folder:", true);
        private final JCheckBox recursive = form.addCheckbox("Include sub-folders", true);
        private final JCheckBox csv = form.addCheckbox("CSV output", false);
        private final EpisodeLister lister = new EpisodeLister();

        EpisodesCard() {
            super("Episodes", "List every episode found, grouped per show and season.");
        }

        @Override
        public JComponent component() {
            return form.panel();
        }

        @Override
        public void collect(Options options) {
            options.setFolder(dir.getText().trim());
            options.setRecursive(recursive.isSelected());
            options.setCsv(csv.isSelected());
        }

        @Override
        public OperationResult run(Options options) {
            return lister.list(options);
        }
    }

    // --------------------------------------------------------------- check

    static final class CheckCard extends OpCard {
        private final Form form = new Form();
        private final JTextField dir = form.addPathField("Folder:", true);
        private final JCheckBox recursive = form.addCheckbox("Include sub-folders", true);
        private final HealthCheck check = new HealthCheck();

        CheckCard() {
            super("Library check", "Report conventions in use, unrecognised names, duplicates and missing subtitles.");
        }

        @Override
        public JComponent component() {
            return form.panel();
        }

        @Override
        public void collect(Options options) {
            options.setFolder(dir.getText().trim());
            options.setRecursive(recursive.isSelected());
        }

        @Override
        public OperationResult run(Options options) {
            return check.check(options);
        }
    }

    static List<OpCard> all() {
        List<OpCard> cards = new ArrayList<OpCard>();
        cards.add(new RenameCard());
        cards.add(new ImdbCard());
        cards.add(new CleanTitlesCard());
        cards.add(new SyncCard());
        cards.add(new RelocateCard());
        cards.add(new DownloadSubsCard());
        cards.add(new FlattenCard());
        cards.add(new MergeCard());
        cards.add(new VttCard());
        cards.add(new ShiftCard());
        cards.add(new EpisodesCard());
        cards.add(new CheckCard());
        return cards;
    }
}
