package dev.jsz.primordia;

import dev.jsz.primordia.client.config.PrimordiaConfig;
import dev.jsz.primordia.client.config.PrimordiaConfigScreen;
import dev.jsz.primordia.client.render.CreatureRenderer;
import dev.jsz.primordia.registry.PrimordiaEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class PrimordiaClient implements ClientModInitializer {
	/**
	 * Unbound by default. The settings live in the Mods list when Mod Menu is installed, and this
	 * exists so they are still reachable when it is not — but claiming a key on first launch to
	 * cover that case would be rude, so the player binds it if they want it.
	 */
	private static KeyBinding settingsKey;

	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(PrimordiaEntities.CREATURE, CreatureRenderer::new);
		// The LOD budget is spent per frame, so it has to be cleared before entities are drawn.
		WorldRenderEvents.START.register(context -> CreatureRenderer.beginFrame());

		// Loading here rather than lazily on the first rendered frame: applying it touches the
		// mesh cache, and doing that mid-frame would drop every mesh already queued for drawing.
		PrimordiaConfig.get();

		settingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.primordia.settings",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_UNKNOWN,
				"category.primordia"));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (settingsKey.wasPressed()) {
				client.setScreen(new PrimordiaConfigScreen(client.currentScreen));
			}
		});

		Primordia.LOGGER.info("Primordia client initialised");
	}
}
