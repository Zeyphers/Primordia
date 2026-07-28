package dev.jsz.primordia.registry;

import dev.jsz.primordia.Primordia;
import dev.jsz.primordia.screen.GeneLabScreenHandler;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.screen.ScreenHandlerType;

public final class PrimordiaScreenHandlers {

	public static final ScreenHandlerType<GeneLabScreenHandler> GENE_LAB =
			Registry.register(Registries.SCREEN_HANDLER, Primordia.id("gene_lab"),
					new ScreenHandlerType<>(GeneLabScreenHandler::new, FeatureFlags.VANILLA_FEATURES));

	private PrimordiaScreenHandlers() {
	}

	public static void register() {
	}
}
