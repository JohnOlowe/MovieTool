package movies.core;

import java.io.File;
import java.util.List;

/**
 * A pending copy/move produced by the subtitle sync or the flattener, kept as
 * data so the CLI can print it, the GUI can table it and both can apply it.
 */
public class TransferAction {

    public enum Kind { COPY, MOVE }

    public enum State { PLANNED, DONE, SKIPPED_EXISTS, COLLISION, FAILED, ALREADY_THERE }

    public final Kind kind;
    public final File from;
    public final File to;
    public State state = State.PLANNED;
    public String note = "";

    public TransferAction(Kind kind, File from, File to) {
        this.kind = kind;
        this.from = from;
        this.to = to;
    }

    @Override
    public String toString() {
        return (kind == Kind.MOVE ? "MOVE " : "COPY ") + from.getName() + "  ->  " + to.getName();
    }
}
