package dev.jsz.primordia.registry;

import dev.jsz.primordia.Primordia;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.text.Text;

/**
 * The mod's own creative tab.
 * <p>
 * Everything Primordia adds lives here and nowhere else. Scattering a dozen items across the
 * vanilla tabs makes the lab hard to find and hard to reason about as a set — the pipeline only
 * makes sense when its four stations and four consumables are seen together, in the order they are
 * used.
 */
public final class PrimordiaItemGroup {

	public static final RegistryKey<ItemGroup> KEY =
			RegistryKey.of(RegistryKeys.ITEM_GROUP, Primordia.id("general"));

	private PrimordiaItemGroup() {
	}

	public static void register() {
		Registry.register(Registries.ITEM_GROUP, KEY, FabricItemGroup.builder()
				.icon(() -> new ItemStack(PrimordiaItems.FIELD_GUIDE))
				.displayName(Text.translatable("itemGroup.primordia.general"))
				.build());

		// Ordered as the player meets them: the field tools, then the pipeline's stations in the
		// order a sample passes through them, then what comes out.
		ItemGroupEvents.modifyEntriesEvent(KEY).register(entries -> {
			entries.add(PrimordiaItems.FIELD_GUIDE);
			entries.add(PrimordiaItems.BIOPSY_KIT);
			entries.add(PrimordiaItems.GENOME_SCANNER);

			entries.add(PrimordiaBlocks.BASIC_GENE_LAB);
			entries.add(PrimordiaBlocks.PRESERVATION_CASE);
			entries.add(PrimordiaBlocks.GENOME_BANK);

			entries.add(PrimordiaItems.TISSUE_SAMPLE);
			entries.add(PrimordiaItems.SEQUENCE_DATA);
			entries.add(PrimordiaItems.GENOME_REPORT);
		});
	}
}
