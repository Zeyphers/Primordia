package dev.jsz.primordia.registry;

import dev.jsz.primordia.Primordia;
import dev.jsz.primordia.block.GeneLabBlockEntity;
import dev.jsz.primordia.block.LabMachineBlock;
import dev.jsz.primordia.block.SampleCoolerBlock;
import dev.jsz.primordia.block.SplicerBlock;
import dev.jsz.primordia.item.SampleCoolerBlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;

import java.util.function.Function;

/**
 * The lab's stations.
 * <p>
 * Blocks are built through a factory rather than constructed inline: since 26.2 a block reads its
 * own registry key out of its properties while it is being constructed, so the key has to exist
 * first. See {@link PrimordiaItems} for the same shape on the item side.
 */
public final class PrimordiaBlocks {

	/**
	 * The whole pipeline in one machine. Not a full cube — the model has an angled console — so it
	 * is non-opaque and must not have its neighbours' faces culled against it.
	 */
	public static final Block BASIC_GENE_LAB = register("basic_gene_lab",
			properties -> new LabMachineBlock(
					properties,
					GeneLabBlockEntity::new,
					() -> PrimordiaBlockEntities.BASIC_GENE_LAB),
			Block.Properties.ofFullCopy(Blocks.BLAST_FURNACE)
					.noOcclusion()
					.lightLevel(state -> state.getValue(LabMachineBlock.STAGE)
							== GeneLabBlockEntity.Stage.IDLE ? 0 : 9));

	/**
	 * Cold storage for tissue. Built of metal, and sounds like it.
	 * <p>
	 * Not a full cube: the chest is inset from the block's sides and the aerial on the lid stands
	 * above it, so like the lab it must not have its neighbours' faces culled against it. It was a
	 * plain cube until the modelled version replaced it, and leaving the occlusion behind would have
	 * left the box lit as though it were solid.
	 * <p>
	 * Built from bare properties rather than copied from iron, which is the only way to <i>not</i>
	 * require a pickaxe: {@code requiresCorrectToolForDrops} can be switched on and never off, so a
	 * copy of iron would have meant a cooler that drops nothing to a bare hand — and with samples
	 * inside, a cooler that drops nothing loses its contents. Half a second of hardness so picking
	 * one up again is barely an interruption; a cooler is luggage, and luggage that takes a
	 * pickaxe and eight seconds to move is luggage nobody moves.
	 */
	public static final Block SAMPLE_COOLER = register("sample_cooler",
			properties -> new SampleCoolerBlock(properties, () -> PrimordiaBlockEntities.SAMPLE_COOLER),
			Block.Properties.of()
					.mapColor(MapColor.METAL)
					.sound(SoundType.METAL)
					.noOcclusion()
					.strength(0.5f, 6.0f));

	/**
	 * The splicing bench: where a characterised genome becomes something the player is.
	 * <p>
	 * Lit only while it is running, like the lab, because the one thing worth telling the room is
	 * whether the machine is busy. Non-opaque for the usual reason — the body stops a pixel short of
	 * the top of its block and the gantry stands proud of it.
	 */
	public static final Block SPLICER = register("splicer",
			SplicerBlock::new,
			Block.Properties.ofFullCopy(Blocks.BLAST_FURNACE)
					.noOcclusion()
					.lightLevel(state -> state.getValue(SplicerBlock.RUNNING) ? 10 : 0));

	private PrimordiaBlocks() {
	}

	private static Block register(String path, Function<Block.Properties, Block> factory,
	                              Block.Properties properties) {
		ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, Primordia.id(path));
		return Registry.register(BuiltInRegistries.BLOCK, key, factory.apply(properties.setId(key)));
	}

	private static Item registerItem(String path, Block block) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Primordia.id(path));
		return Registry.register(BuiltInRegistries.ITEM, key,
				new BlockItem(block, new Item.Properties().useBlockDescriptionPrefix().setId(key)));
	}

	/**
	 * Registers the block items. They are placed in the menu by {@link PrimordiaItemGroup}, which
	 * gathers the whole mod into one tab.
	 */
	public static void register() {
		registerItem("basic_gene_lab", BASIC_GENE_LAB);
		registerItem("splicer", SPLICER);
		registerCoolerItem();
	}

	/**
	 * The cooler's block item is its own class, because it keeps cooling in a pocket. See
	 * {@link SampleCoolerBlockItem}: a cooler that only worked once placed would be useless for
	 * carrying samples home, which is the only reason to want one.
	 */
	private static void registerCoolerItem() {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Primordia.id("sample_cooler"));
		Registry.register(BuiltInRegistries.ITEM, key, new SampleCoolerBlockItem(SAMPLE_COOLER,
				new Item.Properties().useBlockDescriptionPrefix().stacksTo(1).setId(key)));
	}
}
