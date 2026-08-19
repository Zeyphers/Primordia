package dev.jsz.primordia.registry;

import dev.jsz.primordia.Primordia;
import dev.jsz.primordia.screen.GeneLabScreenHandler;
import dev.jsz.primordia.screen.SampleCoolerScreenHandler;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Registry;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;

public final class PrimordiaScreenHandlers {

	public static final MenuType<GeneLabScreenHandler> GENE_LAB =
			Registry.register(BuiltInRegistries.MENU, Primordia.id("gene_lab"),
					new MenuType<>(GeneLabScreenHandler::new, FeatureFlags.VANILLA_SET));

	public static final MenuType<SampleCoolerScreenHandler> SAMPLE_COOLER =
			Registry.register(BuiltInRegistries.MENU, Primordia.id("sample_cooler"),
					new MenuType<>(SampleCoolerScreenHandler::new, FeatureFlags.VANILLA_SET));

	public static final MenuType<dev.jsz.primordia.screen.SplicerMenu> SPLICER =
			Registry.register(BuiltInRegistries.MENU, Primordia.id("splicer"),
					new MenuType<>(dev.jsz.primordia.screen.SplicerMenu::new, FeatureFlags.VANILLA_SET));

	private PrimordiaScreenHandlers() {
	}

	public static void register() {
	}
}
