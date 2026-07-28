package dev.jsz.primordia.registry;

import dev.jsz.primordia.Primordia;
import dev.jsz.primordia.item.BiopsyKitItem;
import dev.jsz.primordia.item.FieldGuideItem;
import dev.jsz.primordia.item.GenomeReportItem;
import dev.jsz.primordia.item.GenomeScannerItem;
import dev.jsz.primordia.item.SequenceDataItem;
import dev.jsz.primordia.item.TissueSampleItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

public final class PrimordiaItems {
	/**
	 * Creative and command only — there is deliberately no recipe for it.
	 * <p>
	 * It reports a complete genome instantly, which is precisely the answer the lab pipeline exists
	 * to make the player work for. Leaving it craftable would mean the sequencer, the decoder and
	 * the whole reference library were optional flavour sitting beside a strictly better item.
	 */
	public static final Item GENOME_SCANNER = register("genome_scanner",
			new GenomeScannerItem(new Item.Settings().maxCount(1)));

	public static final Item BIOPSY_KIT = register("biopsy_kit",
			new BiopsyKitItem(new Item.Settings().maxCount(1).maxDamage(64)));

	public static final Item TISSUE_SAMPLE = register("tissue_sample",
			new TissueSampleItem(new Item.Settings().maxCount(16)));

	public static final Item SEQUENCE_DATA = register("sequence_data",
			new SequenceDataItem(new Item.Settings().maxCount(16)));

	public static final Item GENOME_REPORT = register("genome_report",
			new GenomeReportItem(new Item.Settings().maxCount(16)));

	/**
	 * One per player, in practice. It stacks to one because two guides would split a record that
	 * only means anything whole — filing half your specimens into each is worse than either.
	 */
	public static final Item FIELD_GUIDE = register("field_guide",
			new FieldGuideItem(new Item.Settings().maxCount(1)));

	private PrimordiaItems() {
	}

	private static Item register(String path, Item item) {
		return Registry.register(Registries.ITEM, Primordia.id(path), item);
	}

	/**
	 * Items are placed in the menu by {@link PrimordiaItemGroup}, which owns the mod's own tab.
	 * Registration itself happens in this class's static initialiser, so this only has to force it.
	 */
	public static void register() {
	}
}
