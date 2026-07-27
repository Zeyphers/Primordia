package dev.jsz.primordia.registry;

import dev.jsz.primordia.Primordia;
import dev.jsz.primordia.item.GenomeScannerItem;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

public final class PrimordiaItems {
	public static final Item GENOME_SCANNER = register("genome_scanner",
			new GenomeScannerItem(new Item.Settings().maxCount(1)));

	private PrimordiaItems() {
	}

	private static Item register(String path, Item item) {
		return Registry.register(Registries.ITEM, Primordia.id(path), item);
	}

	public static void register() {
		// Tools group: it is an instrument, not a weapon or a building block.
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS)
				.register(entries -> entries.add(GENOME_SCANNER));
	}
}
