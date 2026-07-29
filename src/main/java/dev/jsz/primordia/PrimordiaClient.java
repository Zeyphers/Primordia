package dev.jsz.primordia;

import dev.jsz.primordia.client.config.PrimordiaConfig;
import dev.jsz.primordia.client.config.PrimordiaConfigScreen;
import dev.jsz.primordia.client.DynamicLightsCompat;
import dev.jsz.primordia.client.render.CreatureRenderer;
import dev.jsz.primordia.client.screen.FieldGuideScreen;
import dev.jsz.primordia.client.screen.GeneLabScreen;
import dev.jsz.primordia.item.TissueSampleItem;
import dev.jsz.primordia.lab.SampleData;
import dev.jsz.primordia.registry.PrimordiaBlocks;
import dev.jsz.primordia.registry.PrimordiaEntities;
import dev.jsz.primordia.registry.PrimordiaItems;
import dev.jsz.primordia.registry.PrimordiaScreenHandlers;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreens;
import net.minecraft.client.item.ModelPredicateProviderRegistry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderLayer;
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

		// Optional interop, no-op when the mod is absent. Deferred to client-started so that
		// LambDynamicLights has certainly finished its own registration first.
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> DynamicLightsCompat.register());
		HandledScreens.register(PrimordiaScreenHandlers.GENE_LAB, GeneLabScreen::new);
		// The lab's model is not a full cube and its texture has transparent regions.
		BlockRenderLayerMap.INSTANCE.putBlock(PrimordiaBlocks.BASIC_GENE_LAB,
				RenderLayer.getCutout());

		// An empty swab and one carrying a specimen are different objects to the player, and the
		// difference is invisible without opening the tooltip. The vial's art switches on it: the
		// blank the creative menu hands out is an empty tube, and anything taken off an animal has
		// something in it.
		ModelPredicateProviderRegistry.register(PrimordiaItems.TISSUE_SAMPLE,
				Primordia.id("filled"),
				(stack, world, entity, seed) -> SampleData.get(stack) == null ? 0f : 1f);

		// The guide's screen is opened here rather than from the item, because the item is a class
		// the dedicated server loads too and naming a screen in it would crash on servers.
		UseItemCallback.EVENT.register((player, world, hand) -> {
			net.minecraft.item.ItemStack stack = player.getStackInHand(hand);
			if (world.isClient() && stack.isOf(PrimordiaItems.FIELD_GUIDE)) {
				MinecraftClient.getInstance().setScreen(new FieldGuideScreen(stack));
				return net.minecraft.util.TypedActionResult.success(stack, true);
			}
			return net.minecraft.util.TypedActionResult.pass(stack);
		});

		// A sample's freshness needs the current world time, which the item itself has no access
		// to — appendTooltip runs on a class the dedicated server loads too, and reaching for the
		// client world from there is how a mod ends up crashing on servers. The line is added here
		// instead, where the client world is legitimately in scope.
		ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
			if (!stack.isOf(PrimordiaItems.TISSUE_SAMPLE)) return;
			SampleData data = SampleData.get(stack);
			if (data == null || data.isPreserved()) return;
			var world = MinecraftClient.getInstance().world;
			if (world == null) return;
			lines.add(TissueSampleItem.freshnessLine(data, world.getTime()));
		});
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
