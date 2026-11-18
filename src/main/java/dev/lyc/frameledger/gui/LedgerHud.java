package dev.lyc.frameledger.gui;

import dev.lyc.frameledger.measure.FrameTimer;
import dev.lyc.frameledger.measure.Pass;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Renders the ledger as a small budget breakdown in the corner of the screen.
 *
 * <p>Design intent: this is not a debug wall of text. Each row reads "where the time went",
 * sorted by cost, with a proportional bar so the dominant cost is obvious at a glance. The
 * question it answers is "what should I turn down", which no FPS counter can answer.</p>
 */
public final class LedgerHud {
    private static final int MARGIN = 4;
    private static final int ROW_HEIGHT = 10;
    private static final int BAR_WIDTH = 46;
    private static final int BAR_HEIGHT = 4;
    private static final int WIDTH = 132;
    private static final int COLLAPSED_ROWS = 3;

    private static final int COLOR_BG = 0xC0101010;
    private static final int COLOR_TITLE = 0xFFFFFFFF;
    private static final int COLOR_TEXT = 0xFFB0B0B0;
    private static final int COLOR_BAR_BG = 0xFF2A2A2A;
    private static final int COLOR_BAR_FG = 0xFF4C9AFF;
    private static final int COLOR_DIM = 0xFF707070;

    private LedgerHud() {
    }

    public static void render(DrawContext context, boolean expanded) {
        FrameTimer timer = FrameTimer.get();
        if (!timer.isActive()) {
            return;
        }

        List<Pass> measured = new ArrayList<>(Pass.values().length);
        for (Pass pass : timer.sampledPasses()) {
            measured.add(pass);
        }
        if (measured.isEmpty()) {
            // Queries are still in flight; better to show nothing than a row of zeros.
            return;
        }

        // Heaviest first: the row you read first should be the one worth acting on.
        measured.sort(Comparator.comparingDouble(timer::millis).reversed());
        List<Pass> shown = expanded
                ? measured
                : measured.subList(0, Math.min(COLLAPSED_ROWS, measured.size()));

        double heaviest = timer.millis(shown.get(0));
        double attributed = timer.attributedMillis();

        int height = 14 + shown.size() * ROW_HEIGHT + (expanded ? 4 : 12);
        context.fill(MARGIN, MARGIN, MARGIN + WIDTH, MARGIN + height, COLOR_BG);

        MinecraftClient client = MinecraftClient.getInstance();
        TextRenderer textRenderer = client.textRenderer;

        String unit = timer.source() == FrameTimer.Source.GPU ? "GPU" : "CPU";
        String title = String.format("%s frame  %.1f ms", unit, attributed);
        context.drawTextWithShadow(textRenderer, title, MARGIN + 4, MARGIN + 4, COLOR_TITLE);

        int rowY = MARGIN + 15;
        for (Pass pass : shown) {
            drawRow(context, textRenderer, timer, pass, heaviest, attributed, rowY);
            rowY += ROW_HEIGHT;
        }

        if (!expanded) {
            context.drawTextWithShadow(textRenderer, "F9 for full ledger",
                    MARGIN + 4, rowY + 1, COLOR_DIM);
        }
    }

    private static void drawRow(DrawContext context, TextRenderer textRenderer,
                                FrameTimer timer, Pass pass,
                                double heaviest, double attributed, int y) {
        double ms = timer.millis(pass);
        int x = MARGIN + 4;

        String label = String.format("%-13s %5.1f", pass.label(), ms);
        context.drawTextWithShadow(textRenderer, label, x, y, COLOR_TEXT);

        int barX = x + textRenderer.getWidth(label) + 3;
        int barY = y + 3;
        context.fill(barX, barY, barX + BAR_WIDTH, barY + BAR_HEIGHT, COLOR_BAR_BG);

        int filled = heaviest > 0
                ? (int) Math.round(BAR_WIDTH * (ms / heaviest))
                : 0;
        filled = Math.max(0, Math.min(BAR_WIDTH, filled));
        context.fill(barX, barY, barX + filled, barY + BAR_HEIGHT, COLOR_BAR_FG);

        int share = attributed > 0 ? (int) Math.round(ms / attributed * 100.0) : 0;
        context.drawTextWithShadow(textRenderer, share + "%",
                barX + BAR_WIDTH + 3, y, COLOR_DIM);
    }
}
