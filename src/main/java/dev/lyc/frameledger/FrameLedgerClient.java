package dev.lyc.frameledger;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FrameLedger — measures where the frame budget actually goes.
 *
 * <p>The premise is that "this setting costs a lot" is currently a guess shipped with the mod.
 * FrameLedger replaces the guess with a measurement taken on the player's own machine, and
 * reports it as a budget breakdown rather than a single FPS number.</p>
 */
public final class FrameLedgerClient implements ClientModInitializer {
    public static final String MOD_ID = "frameledger";
    public static final Logger LOGGER = LoggerFactory.getLogger("FrameLedger");

    private static KeyBinding toggleLedger;
    private static KeyBinding toggleExpanded;

    @Override
    public void onInitializeClient() {
        toggleLedger = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.frameledger.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F8,
                "category.frameledger"));

        toggleExpanded = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.frameledger.expand",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F9,
                "category.frameledger"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleLedger.wasPressed()) {
                LedgerState.toggle();
            }
            while (toggleExpanded.wasPressed()) {
                LedgerState.toggleExpanded();
            }
        });

        LOGGER.info("FrameLedger ready — F8 toggles the ledger, F9 expands it");
    }
}
