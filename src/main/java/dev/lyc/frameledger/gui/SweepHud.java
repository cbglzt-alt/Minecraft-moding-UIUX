package dev.lyc.frameledger.gui;

import dev.lyc.frameledger.cull.CullingBenchmark;
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
        CullingBenchmark.Run bench = CullingBenchmark.activeRun();
        CullingBenchmark.Result benchResult = CullingBenchmark.lastResult();

        if (run == null && done.isEmpty() && bench == null && benchResult == null) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        TextRenderer textRenderer = client.textRenderer;

        int lines = 1;
        if (run != null) {
            lines += 2;
        }
        lines += done.size();
        if (bench != null) {
            lines += 3;
        } else if (benchResult != null) {
            lines += 1;
        }
        int height = lines * LINE + 6;

        context.fill(MARGIN, topY, MARGIN + WIDTH, topY + height, COLOR_BG);
        int y = topY + 4;

        if (bench != null) {
            String head = String.format("Culling on/off  %d/%d",
                    bench.pairIndex() + 1, bench.totalPairs());
            context.drawTextWithShadow(textRenderer, head, MARGIN + 4, y, COLOR_TITLE);
            y += LINE;

            int filled = (int) Math.round((WIDTH - 8) * (bench.pairIndex() / (double) bench.totalPairs()));
            context.fill(MARGIN + 4, y + 2, MARGIN + 4 + (WIDTH - 8), y + 5, COLOR_BAR_BG);
            context.fill(MARGIN + 4, y + 2, MARGIN + 4 + filled, y + 5, COLOR_BAR_FG);
            y += LINE;

            context.drawTextWithShadow(textRenderer, "hold still, ESC to cancel",
                    MARGIN + 4, y, COLOR_DIM);
            y += LINE;
        } else if (benchResult != null) {
            String text;
            int colour;
            if (!benchResult.measured()) {
                text = "Culling: not enough samples";
                colour = COLOR_DIM;
            } else if (!benchResult.conclusive()) {
                // A conclusion without its reason is useless: "no gain" because the scene has
                // nothing to cull is a completely different statement from "no gain" because the
                // culling itself does not help here.
                text = explainNoGain(benchResult);
                colour = COLOR_WARN;
            } else {
                text = String.format("Culling: %.0f -> %.0f fps (+%.2f ms)",
                        benchResult.fpsOff(), benchResult.fpsOn(), benchResult.deltaMillis());
                colour = COLOR_GOOD;
            }
            context.drawTextWithShadow(textRenderer, text, MARGIN + 4, y, colour);
            y += LINE;
        }

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
                                   SweepRun.Result result, int y) {        String text;
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

    /**
     * Turns an inconclusive benchmark into a statement about why.
     *
     * <p>"No measurable gain" on its own is unhelpful, because the two reasons behind it call for
     * opposite responses: if nothing was culled the test simply did not exercise the feature and
     * the player should face away from their block entities; if most of them were culled and the
     * frame did not improve, then the feature genuinely does not help in that scene and there is
     * nothing to fix.</p>
     */
    private static String explainNoGain(CullingBenchmark.Result result) {
        if (result.considered() == 0) {
            return "Culling: no gain (no block entities)";
        }
        if (result.culledFraction() < 0.05) {
            return "Culling: no gain (nothing off-screen)";
        }
        return String.format("Culling: no gain (skipped %.0f%%)",
                result.culledFraction() * 100.0);
    }
}
