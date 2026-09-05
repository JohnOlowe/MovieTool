package movies.gui;

import movies.core.OperationResult;
import movies.core.Options;

import javax.swing.JComponent;

/**
 * One operation panel inside the GUI. Cards collect their widget state into
 * the shared {@link Options} object, execute and optionally apply.
 */
public abstract class OpCard {

    private final String title;
    private final String description;

    protected OpCard(String title, String description) {
        this.title = title;
        this.description = description;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    /** The panel to show in the card container. */
    public abstract JComponent component();

    /** Copies widget state into the options object. */
    public abstract void collect(Options options);

    /** Executes the operation and returns its result. */
    public abstract OperationResult run(Options options);

    /** True when a successful run can be applied afterwards (dry-run workflows). */
    public boolean canApply() {
        return false;
    }

    /** Applies the previously produced plan. Only called when {@link #canApply()}. */
    public OperationResult apply() {
        throw new UnsupportedOperationException("apply not supported by " + title);
    }

    /** Called when the card is shown; used to enable/disable the Apply button. */
    public boolean isApplyReady() {
        return false;
    }
}
