package movies.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What an operation produced: loggable problems, a human readable report and,
 * depending on the operation, a rename plan and/or pending file transfers.
 * Both the CLI and the GUI consume the same object.
 */
public class OperationResult {

    private final List<Problem> problems = new ArrayList<Problem>();
    private String report = "";
    private RenameEngine.RenamePlan renamePlan;
    private List<TransferAction> transfers;

    public List<Problem> problems() {
        return problems;
    }

    public void add(Problem problem) {
        problems.add(problem);
    }

    public String getReport() {
        return report;
    }

    public void setReport(String report) {
        this.report = report == null ? "" : report;
    }

    public RenameEngine.RenamePlan getRenamePlan() {
        return renamePlan;
    }

    public void setRenamePlan(RenameEngine.RenamePlan renamePlan) {
        this.renamePlan = renamePlan;
    }

    public List<TransferAction> getTransfers() {
        return transfers == null ? Collections.<TransferAction>emptyList() : transfers;
    }

    public void setTransfers(List<TransferAction> transfers) {
        this.transfers = transfers;
    }

    public int errorCount() {
        return count(Problem.Severity.ERROR);
    }

    public int warnCount() {
        return count(Problem.Severity.WARN);
    }

    private int count(Problem.Severity severity) {
        int n = 0;
        for (Problem problem : problems) {
            if (problem.getSeverity() == severity) n++;
        }
        return n;
    }
}
