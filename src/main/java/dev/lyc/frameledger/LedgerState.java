package dev.lyc.frameledger;

import net.minecraft.client.MinecraftClient;

/**
 * Holds the single piece of user state FrameLedger has: whether the ledger is expanded.
 */
public final class LedgerState {
    private static boolean enabled = true;
    private static boolean expanded = false;

    private LedgerState() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static boolean isExpanded() {
        return expanded;
    }

    public static void toggle() {
        enabled = !enabled;
        report(enabled ? "Ledger shown" : "Ledger hidden");
    }

    public static void toggleExpanded() {
        expanded = !expanded;
        report(expanded ? "Ledger expanded" : "Ledger collapsed");
    }

    /** Shows a status line in the action bar. */
    public static void report(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.inGameHud != null) {
            client.inGameHud.setOverlayMessage(
                    net.minecraft.text.Text.literal("[FrameLedger] " + message), false);
        }
    }
}
