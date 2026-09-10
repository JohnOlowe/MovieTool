package movies.gui;

import movies.core.OperationResult;
import movies.core.Options;
import movies.core.Problem;
import movies.core.RenameEngine;
import movies.core.TransferAction;
import movies.cli.Version;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.HeadlessException;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;

/**
 * The MovieTool window: an operation list on the left, a form for the chosen
 * operation in the middle and a shared log/plan area at the bottom. Every
 * operation runs on a background worker so the window stays responsive.
 */
public final class MovieToolGui extends JFrame {

    private final List<OpCard> cards;
    private final OpCard[] cardArray;
    private final DefaultListModel<String> cardTitles = new DefaultListModel<String>();
    private final JList<String> cardList = new JList<String>(cardTitles);
    private final JPanel cardPanel = new JPanel();
    private final java.awt.CardLayout cardLayout = new java.awt.CardLayout();

    private final JButton runButton = new JButton();
    private final JButton applyButton = new JButton("Apply");
    private final JProgressBar progress = new JProgressBar();
    private final JTextArea logArea = new JTextArea();
    private final DefaultTableModel planModel = new DefaultTableModel(new Object[] { "From", "To", "Status" }, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable planTable = new JTable(planModel);
    private final JTabbedPane resultsTabs = new JTabbedPane();
    private final JLabel descriptionLabel = new JLabel();

    private OpCard current;
    private OpWorker worker;
    private OperationResult lastResult;

    private MovieToolGui() {
        super("MovieTool " + Version.TEXT);
        this.cards = Cards.all();
        this.cardArray = cards.toArray(new OpCard[cards.size()]);
        buildUi();
        selectCard(0);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    }

    /** Opens the window on the event dispatch thread. */
    public static void launch() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                } catch (Exception ignore) {
                    // The default look and feel is fine too.
                }
                try {
                    MovieToolGui gui = new MovieToolGui();
                    gui.setSize(980, 720);
                    gui.setMinimumSize(new Dimension(760, 520));
                    gui.setLocationByPlatform(true);
                    gui.setVisible(true);
                } catch (HeadlessException e) {
                    System.err.println("No display is available; use the command line instead ('movietool help').");
                }
            }
        });
    }

    // ------------------------------------------------------------- building

    private void buildUi() {
        for (OpCard card : cards) cardTitles.addElement(card.getTitle());

        cardList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        cardList.setFixedCellHeight(28);
        cardList.setFont(cardList.getFont().deriveFont(Font.PLAIN, 14f));
        cardList.addListSelectionListener(new javax.swing.event.ListSelectionListener() {
            @Override
            public void valueChanged(javax.swing.event.ListSelectionEvent event) {
                if (!event.getValueIsAdjusting()) selectCard(cardList.getSelectedIndex());
            }
        });

        cardPanel.setLayout(cardLayout);
        for (OpCard card : cards) cardPanel.add(card.component(), card.getTitle());

        // The Help entry: not an operation, just the guide.
        javax.swing.JEditorPane helpPane = new javax.swing.JEditorPane("text/html", "");
        helpPane.setEditable(false);
        helpPane.setText(HelpBook.html());
        helpPane.setCaretPosition(0);
        cardPanel.add(new JScrollPane(helpPane), "Help");
        cardTitles.addElement("Help");

        JPanel listPanel = new JPanel(new BorderLayout());
        listPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 4));
        listPanel.add(new JScrollPane(cardList), BorderLayout.CENTER);
        listPanel.setPreferredSize(new Dimension(210, 100));

        JPanel cardHolder = new JPanel(new BorderLayout());
        cardHolder.setBorder(BorderFactory.createEmptyBorder(8, 4, 8, 8));
        cardHolder.add(cardPanel, BorderLayout.NORTH);

        descriptionLabel.setForeground(new Color(90, 90, 90));
        descriptionLabel.setBorder(BorderFactory.createEmptyBorder(6, 10, 2, 10));

        JPanel topPane = new JPanel(new BorderLayout());
        topPane.add(listPanel, BorderLayout.WEST);
        JPanel centerColumn = new JPanel(new BorderLayout());
        centerColumn.add(descriptionLabel, BorderLayout.NORTH);
        centerColumn.add(cardHolder, BorderLayout.CENTER);
        topPane.add(centerColumn, BorderLayout.CENTER);

        // Results area.
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setPreferredSize(new Dimension(100, 220));

        planTable.setRowHeight(22);
        planTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        JScrollPane planScroll = new JScrollPane(planTable);

        resultsTabs.addTab("Plan / results", planScroll);
        resultsTabs.addTab("Log", logScroll);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topPane, resultsTabs);
        split.setResizeWeight(0.62);
        split.setDividerLocation(360);

        // Toolbar row.
        runButton.setAction(new AbstractAction("Run") {
            @Override
            public void actionPerformed(ActionEvent event) {
                runCurrent();
            }
        });
        applyButton.setEnabled(false);
        applyButton.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                applyCurrent();
            }
        });
        progress.setIndeterminate(false);
        progress.setVisible(false);
        progress.setStringPainted(true);

        JPanel toolbar = new JPanel(new BorderLayout());
        JPanel buttons = new JPanel();
        buttons.add(runButton);
        buttons.add(applyButton);
        toolbar.add(buttons, BorderLayout.WEST);
        toolbar.add(progress, BorderLayout.EAST);
        toolbar.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        JPanel content = new JPanel(new BorderLayout());
        content.add(toolbar, BorderLayout.NORTH);
        content.add(split, BorderLayout.CENTER);
        setContentPane(content);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                if (worker != null) worker.cancel(true);
            }
        });
    }

    private void selectCard(int index) {
        if (index < 0 || index > cards.size()) index = 0;
        if (index == cards.size()) {
            current = null;
            cardList.setSelectedIndex(index);
            cardLayout.show(cardPanel, "Help");
            descriptionLabel.setText("Help - what every function does and when to use it.");
            runButton.setEnabled(false);
            applyButton.setEnabled(false);
            return;
        }
        current = cards.get(index);
        cardList.setSelectedIndex(index);
        cardLayout.show(cardPanel, current.getTitle());
        descriptionLabel.setText(current.getDescription());
        runButton.setEnabled(true);
        updateApplyButton();
    }

    // ------------------------------------------------------------- running

    private void runCurrent() {
        if (worker != null || current == null) return;
        final Options options = new Options();
        try {
            current.collect(options);
        } catch (RuntimeException e) {
            GuiUtil.error(this, "Invalid input: " + e.getMessage());
            return;
        }
        if (options.getFolder().trim().isEmpty()) {
            GuiUtil.error(this, "Please choose a folder or file first.");
            return;
        }
        setBusy(true);
        log("Running: " + current.getTitle() + " ...");
        worker = new OpWorker(current, options) {
            @Override
            protected void done() {
                worker = null;
                setBusy(false);
                try {
                    OperationResult result = get();
                    lastResult = result;
                    render(result);
                } catch (Exception e) {
                    log("[ERROR] " + rootMessage(e));
                    GuiUtil.error(MovieToolGui.this, "Operation failed: " + rootMessage(e));
                }
            }
        };
        worker.execute();
    }

    private void applyCurrent() {
        if (worker != null || current == null || !current.canApply() || !current.isApplyReady()) return;
        setBusy(true);
        log("Applying " + current.getTitle() + " ...");
        final OpCard card = current;
        worker = new OpWorker(null, null) {
            @Override
            protected OperationResult compute() {
                return card.apply();
            }

            @Override
            protected void done() {
                worker = null;
                setBusy(false);
                try {
                    render(get());
                } catch (Exception e) {
                    log("[ERROR] " + rootMessage(e));
                }
            }
        };
        worker.execute();
    }

    private void setBusy(boolean busy) {
        runButton.setEnabled(!busy && current != null);
        applyButton.setEnabled(!busy && current != null && current.canApply() && current.isApplyReady());
        progress.setIndeterminate(busy);
        progress.setVisible(busy);
        if (!busy) updateApplyButton();
    }

    private void updateApplyButton() {
        boolean ready = current != null && current.canApply() && current.isApplyReady();
        applyButton.setEnabled(ready);
        applyButton.setToolTipText(ready ? "Execute the previewed plan"
                : "Run a dry run first; Apply then executes the preview");
    }

    // ------------------------------------------------------------- rendering

    private void render(OperationResult result) {
        planModel.setRowCount(0);
        if (result == null) return;

        if (result.getRenamePlan() != null) {
            for (RenameEngine.RenameAction action : result.getRenamePlan().actions()) {
                String status = action.kind == RenameEngine.RenameAction.Kind.COLLISION ? "COLLISION"
                        : action.isDone() ? "done" : "planned";
                planModel.addRow(new Object[] { action.from.getName(), action.to.getName(), status });
            }
        }
        if (!result.getTransfers().isEmpty()) {
            for (TransferAction transfer : result.getTransfers()) {
                planModel.addRow(new Object[] {
                        shorten(transfer.from.getAbsolutePath()),
                        shorten(transfer.to.getAbsolutePath()),
                        prettyState(transfer.state)
                });
            }
        }
        for (Problem problem : result.problems()) log(problem.toString());
        if (!result.getReport().isEmpty()) log(result.getReport());
        if (!result.getTransfers().isEmpty() || result.getRenamePlan() != null && result.getRenamePlan().renameCount() > 0) {
            resultsTabs.setSelectedIndex(0);
        } else {
            resultsTabs.setSelectedIndex(1);
        }
        updateApplyButton();
        int errors = result.errorCount();
        if (errors > 0) log(errors + " error(s) - see the lines above.");
    }

    private static String prettyState(TransferAction.State state) {
        switch (state) {
            case ALREADY_THERE: return "already in place";
            case SKIPPED_EXISTS: return "skipped (exists)";
            case COLLISION: return "conflict";
            case FAILED: return "FAILED";
            case DONE: return "done";
            default: return "planned";
        }
    }

    private static String shorten(String path) {
        if (path.length() <= 80) return path;
        return "..." + path.substring(path.length() - 77);
    }

    private void log(String line) {
        logArea.append(line + System.getProperty("line.separator"));
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    /** SwingWorker wrapper executing a card's operation off the EDT. */
    private abstract static class OpWorker extends SwingWorker<OperationResult, Void> {

        private final OpCard card;
        private final Options options;

        OpWorker(OpCard card, Options options) {
            this.card = card;
            this.options = options;
        }

        @Override
        protected OperationResult doInBackground() {
            if (card == null) return compute();
            return card.run(options);
        }

        OperationResult compute() {
            return null;
        }
    }
}
