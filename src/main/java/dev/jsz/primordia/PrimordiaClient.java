package dev.jsz.primordia;

import dev.jsz.primordia.client.render.CreatureRenderer;
import dev.jsz.primordia.registry.PrimordiaEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public class PrimordiaClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(PrimordiaEntities.CREATURE, CreatureRenderer::new);
		// The LOD budget is spent per frame, so it has to be cleared before entities are drawn.
		WorldRenderEvents.START.register(context -> CreatureRenderer.beginFrame());
		Primordia.LOGGER.info("Primordia client initialised");
	}
}
