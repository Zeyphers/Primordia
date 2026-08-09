package dev.jsz.primordia.registry;

import dev.jsz.primordia.Primordia;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;

public final class PrimordiaItemGroup {

	public static final ResourceKey<CreativeModeTab> KEY =
			ResourceKey.create(Registries.CREATIVE_MODE_TAB, Primordia.id("general"));

	private PrimordiaItemGroup() {
	}

	public static void register() {
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, KEY, FabricCreativeModeTab.builder()
				.icon(() -> new ItemStack(PrimordiaItems.FIELD_GUIDE))
				.title(Component.translatable("itemGroup.primordia.general"))
				.build());

		CreativeModeTabEvents.modifyOutputEvent(KEY).register(entries -> {
			entries.accept(PrimordiaItems.FIELD_GUIDE);
			entries.accept(PrimordiaItems.BIOPSY_KIT);
			entries.accept(PrimordiaItems.GENOME_SCANNER);

			entries.accept(PrimordiaBlocks.BASIC_GENE_LAB);
			entries.accept(PrimordiaBlocks.SAMPLE_COOLER);

			entries.accept(PrimordiaItems.TISSUE_SAMPLE);
			entries.accept(PrimordiaItems.SEQUENCE_DATA);
			entries.accept(PrimordiaItems.GENOME_REPORT);
		});
	}
}
