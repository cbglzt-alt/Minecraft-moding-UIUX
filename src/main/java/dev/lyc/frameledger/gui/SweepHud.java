package dev.lyc.frameledger.gui;

import dev.lyc.frameledger.measure.Pass;
import dev.lyc.frameledger.measure.SweepController;
import dev.lyc.frameledger.measure.SweepRun;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.List;

/**
 * Overlay for sweep progress and results.
 *
 * <p>Reads as a sentence, not a data dump: "Particles: 1.8 ms (95% CI 1.1–2.5)". The point of the
 * whole mod is a number a player can act on, so the number leads and the statistics qualify it.</p>
 */
public final class SweepHud {
    private static final int MARGIN = 4;
    private static final int WIDTH = 200;
    private static final int LINE = 10;

    private static final int COLOR_BG = 0xC0101010;
    private static final int COLOR_TITLE = 0xFFFFFFFF;
    private static final int COLOR_TEXT = 0xFFB0B0B0;
    private static final int COLOR_GOOD = 0xFF6BD46B;
    private static final int COLOR_DIM = 0xFF707070;
    private static final int COLOR_WARN = 0xFFE0C060;
    private static final int COLOR_BAR_BG = 0xFF2A2A2A;
    private static final int COLOR_BAR_FG = 0xFF4C9AFF;

    private SweepHud() {
    }

    public static void render(DrawContext context, int topY) {
        SweepRun run = SweepController.activeRun();
        List<SweepRun.Result> done = SweepController.completed();

        if (run == null && done.isEmpty()) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        TextRenderer textRenderer = client.textRenderer;

        int lines = 1;
        if (run != null) {
            lines += 2;
        }
        lines += done.size();
        int height = lines * LINE + 6;

        context.fill(MARGIN, topY, MARGIN + WIDTH, topY + height, COLOR_BG);
        int y = topY + 4;

        if (run != null) {
            String head = String.format("Measuring  %s  %d/%d",
                    run.sweep().label(), run.pairIndex() + 1, run.totalPairs());
            context.drawTextWithShadow(textRenderer, head, MARGIN + 4, y, COLOR_TITLE);
            y += LINE;

            int filled = (int) Math.round((WIDTH - 8) * (run.pairIndex() / (double) run.totalPairs()));
            context.fill(MARGIN + 4, y + 2, MARGIN + 4 + (WIDTH - 8), y + 5, COLOR_BAR_BG);
            context.fill(MARGIN + 4, y + 2, MARGIN + 4 + filled, y + 5, COLOR_BAR_FG);
            y += LINE;

            context.drawTextWithShadow(textRenderer, "ESC to cancel", MARGIN + 4, y, COLOR_DIM);
            y += LINE;
        }

        for (SweepRun.Result result : done) {
            drawResult(context, textRenderer, result, y);
            y += LINE;
        }
    }

    private static void drawResult(DrawContext context, TextRenderer textRenderer,
                                   SweepRun.Result result, int y) {
        String text;
        int colour;

        if (!result.isMeasured()) {
            text = result.label() + ": not enough samples";
            colour = COLOR_DIM;
        } else if (!result.conclusive()) {
            text = String.format("%s: no measurable difference", result.label());
            colour = COLOR_WARN;
        } else {
            text = String.format("%s: saves %.2f ms", result.label(), result.deltaMillis());
            colour = COLOR_GOOD;
        }

        context.drawTextWithShadow(textRenderer, text, MARGIN + 4, y, colour);

        if (result.isMeasured() && result.conclusive()) {
            String ci = String.format("+/-%.2f", result.halfWidth());
            int ciWidth = textRenderer.getWidth(ci);
            context.drawTextWithShadow(textRenderer, ci,
                    MARGIN + WIDTH - 4 - ciWidth, y, COLOR_DIM);
        }
    }

    /** Human-readable pass name for the sweep target, e.g. "Particles". */
    public static String passLabel(Pass pass) {
        return pass.label();
    }
}
