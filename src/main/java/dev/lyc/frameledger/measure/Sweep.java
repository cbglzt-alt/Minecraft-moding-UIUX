package dev.lyc.frameledger.measure;

import net.minecraft.client.option.SimpleOption;

import java.util.function.Function;

/**
 * One A/B comparison: an option, the two values to alternate between, and which pass to watch.
 *
 * <p>A sweep is defined by a <em>step down</em> from the player's current value, not by an
 * absolute target. That way the measurement answers the question the player is actually asking —
 * "what would I gain by turning this down one notch" — instead of comparing two arbitrary points
 * they may never use.</p>
 *
 * @param <T> value type of the option
 */
public final class Sweep<T> {
    private final SimpleOption<T> option;
    private final String label;
    private final T valueA;
    private final T valueB;
    private final Pass watchedPass;

    private Sweep(SimpleOption<T> option, String label, T valueA, T valueB, Pass watchedPass) {
        this.option = option;
        this.label = label;
        this.valueA = valueA;
        this.valueB = valueB;
        this.watchedPass = watchedPass;
    }

    /**
     * Builds a sweep by computing one notch below the current value.
     *
     * <p><b>The step is computed, never applied speculatively.</b> An earlier version called the
     * stepper on the live option and then restored the old value to discover what "one notch down"
     * meant. That fired the option's change callback every time the catalog was built, so merely
     * opening the menu would flip clouds and particles on and off. It would also be dangerous for
     * any option whose callback reloads the renderer. Computing the candidate from the current
     * value keeps this side-effect free.</p>
     *
     * @param step maps a value to the next one down, or returns {@code null} when at minimum
     * @return the sweep, or {@code null} when there is nothing to step down to
     */
    public static <T> Sweep<T> stepDown(SimpleOption<T> option, String label, Pass watchedPass,
                                        Function<T, T> step) {
        T current = option.getValue();
        T lowered = step.apply(current);

        if (lowered == null || lowered.equals(current)) {
            return null;
        }
        return new Sweep<>(option, label, current, lowered, watchedPass);
    }

    public String label() {
        return this.label;
    }

    public Pass watchedPass() {
        return this.watchedPass;
    }

    public T valueA() {
        return this.valueA;
    }

    public T valueB() {
        return this.valueB;
    }

    public void applyA() {
        this.option.setValue(this.valueA);
    }

    public void applyB() {
        this.option.setValue(this.valueB);
    }

    /** Restores the player's original value; must always be called when a sweep ends. */
    public void restore() {
        this.option.setValue(this.valueA);
    }
}
