package dev.jsz.primordia;

import dev.jsz.primordia.client.config.PrimordiaConfig;
import dev.jsz.primordia.client.config.PrimordiaConfigScreen;
import dev.jsz.primordia.client.render.CreaturePreviewRenderer;
import dev.jsz.primordia.client.render.CreatureRenderer;
import dev.jsz.primordia.client.screen.FieldGuideScreen;
import dev.jsz.primordia.client.screen.GeneLabScreen;
import dev.jsz.primordia.client.screen.SampleCoolerScreen;
import dev.jsz.primordia.item.TissueSampleItem;
import dev.jsz.primordia.lab.SampleData;
import dev.jsz.primordia.registry.PrimordiaEntities;
import dev.jsz.primordia.registry.PrimordiaItems;
import dev.jsz.primordia.registry.PrimordiaScreenHandlers;
import dev.jsz.primordia.editor.EditorServer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

public class PrimordiaClient implements ClientModInitializer {
	private static KeyMapping settingsKey;

	private static dev.jsz.primordia.lab.GuideData clientGuideData = dev.jsz.primordia.lab.GuideData.empty();

	public static dev.jsz.primordia.lab.GuideData getClientGuideData() {
		return clientGuideData;
	}

	@Override
	public void onInitializeClient() {
		// The editor's HTTP listener runs on a thread the JDK creates itself and does not mark as a
		// daemon, so anything left listening keeps the JVM alive after the window closes and the
		// shutdown watchdog eventually files a crash report. This is the hook that actually matters
		// on a client: it fires on quitting, not on leaving a world.
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> EditorServer.stop());

		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
				dev.jsz.primordia.lab.GuideDataSyncPayload.TYPE,
				(payload, context) -> context.client().execute(() -> {
					clientGuideData = dev.jsz.primordia.lab.GuideData.fromNbt(payload.data());
				})
		);

		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			clientGuideData = dev.jsz.primordia.lab.GuideData.empty();
		});

		// Creature calls are synthesised on this side from the genome the client already has, so the
		// receiver has to be up before any creature is in earshot.
		dev.jsz.primordia.sound.client.CreatureVoices.register();

		EntityRendererRegistry.register(PrimordiaEntities.CREATURE, CreatureRenderer::new);
		// Guide plates are three-dimensional, so they go through the picture-in-picture path rather
		// than being painted straight into the panel. See CreaturePreviewRenderer.
		PictureInPictureRendererRegistry.register(context -> new CreaturePreviewRenderer());

		// DynamicLightsCompat is not touched from here: it is a `lambdynlights:initializer` entrypoint,
		// so LambDynamicLights loads and calls it itself, and naming the class here would drag it in
		// even when the mod is absent.
		MenuScreens.register(PrimordiaScreenHandlers.GENE_LAB, GeneLabScreen::new);
		MenuScreens.register(PrimordiaScreenHandlers.SAMPLE_COOLER, SampleCoolerScreen::new);

		UseItemCallback.EVENT.register((player, world, hand) -> {
			net.minecraft.world.item.ItemStack stack = player.getItemInHand(hand);
			if (world.isClientSide() && stack.is(PrimordiaItems.FIELD_GUIDE)) {
				Minecraft.getInstance().setScreenAndShow(new FieldGuideScreen(stack));
				return net.minecraft.world.InteractionResult.SUCCESS;
			}
			return net.minecraft.world.InteractionResult.PASS;
		});

		ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
			if (!stack.is(PrimordiaItems.TISSUE_SAMPLE)) return;
			SampleData data = SampleData.get(stack);
			if (data == null || data.isPreserved()) return;
			var world = Minecraft.getInstance().level;
			if (world == null) return;
			lines.add(TissueSampleItem.freshnessLine(data, world.getGameTime()));
		});

		LevelRenderEvents.START_MAIN.register(context -> CreatureRenderer.beginFrame());

		PrimordiaConfig.get();

		settingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.primordia.settings",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_UNKNOWN,
				KeyMapping.Category.MISC));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (settingsKey.consumeClick()) {
				client.setScreenAndShow(new PrimordiaConfigScreen(client.gui.screen()));
			}
		});
	}
}
