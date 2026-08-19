package dev.jsz.primordia.registry;

import dev.jsz.primordia.Primordia;
import dev.jsz.primordia.item.BiopsyKitItem;
import dev.jsz.primordia.item.FieldGuideItem;
import dev.jsz.primordia.item.GenomeReportItem;
import dev.jsz.primordia.item.GenomeScannerItem;
import dev.jsz.primordia.item.SequenceDataItem;
import dev.jsz.primordia.item.TissueSampleItem;
import net.minecraft.world.item.Item;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;

import java.util.function.Function;

/**
 * The mod's items.
 * <p>
 * Every item is built through {@link #register} rather than constructed inline, because since 26.2
 * an item has to know its own registry key <i>before</i> it is constructed — {@code Item.Properties}
 * carries the key and the constructor reads it. So the key is minted first and handed to a factory,
 * instead of an already-built item being handed to the registry.
 */
public final class PrimordiaItems {
	/**
	 * Creative and command only — there is deliberately no recipe for it.
	 * <p>
	 * It reports a complete genome instantly, which is precisely the answer the lab pipeline exists
	 * to make the player work for. Leaving it craftable would mean the sequencer, the decoder and
	 * the whole reference library were optional flavour sitting beside a strictly better item.
	 */
	public static final Item GENOME_SCANNER = register("genome_scanner",
			GenomeScannerItem::new, new Item.Properties().stacksTo(1));

	/**
	 * Five samples to a kit.
	 * <p>
	 * One point of damage is spent per sample actually taken — a refused poke costs nothing — so the
	 * durability is literally the number of specimens a kit is good for. Low enough that which
	 * creature to sample is a decision rather than a reflex.
	 */
	public static final Item BIOPSY_KIT = register("biopsy_kit",
			BiopsyKitItem::new, new Item.Properties().stacksTo(1).durability(5));

	public static final Item TISSUE_SAMPLE = register("tissue_sample",
			TissueSampleItem::new, new Item.Properties().stacksTo(16));

	public static final Item SEQUENCE_DATA = register("sequence_data",
			SequenceDataItem::new, new Item.Properties().stacksTo(16));

	public static final Item GENOME_REPORT = register("genome_report",
			GenomeReportItem::new, new Item.Properties().stacksTo(16));

	/**
	 * One per player, in practice. It stacks to one because two guides would split a record that
	 * only means anything whole — filing half your specimens into each is worse than either.
	 */
	/**
	 * One trait, bottled by the splicing bench.
	 * <p>
	 * Drinkable, and it stacks to one: a serum carries a specific bloodline's block at a specific
	 * strength, so two of them are never the same item and must never merge into a pile that has
	 * forgotten which was which.
	 */
	public static final Item SPLICE_SERUM = register("splice_serum",
			properties -> new dev.jsz.primordia.item.SpliceSerumItem(properties),
			new Item.Properties()
					.stacksTo(1)
					.component(net.minecraft.core.component.DataComponents.CONSUMABLE,
							net.minecraft.world.item.component.Consumables.DEFAULT_DRINK));

	public static final Item FIELD_GUIDE = register("field_guide",
			FieldGuideItem::new, new Item.Properties().stacksTo(1));

	private PrimordiaItems() {
	}

	private static Item register(String path, Function<Item.Properties, Item> factory,
	                             Item.Properties properties) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Primordia.id(path));
		return Registry.register(BuiltInRegistries.ITEM, key, factory.apply(properties.setId(key)));
	}

	/**
	 * Items are placed in the menu by {@link PrimordiaItemGroup}, which owns the mod's own tab.
	 * Registration itself happens in this class's static initialiser, so this only has to force it.
	 */
	public static void register() {
	}
}
