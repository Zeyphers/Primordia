package dev.jsz.primordia.registry;

import dev.jsz.primordia.Primordia;
import dev.jsz.primordia.block.GeneLabBlockEntity;
import dev.jsz.primordia.block.LabMachineBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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
	}
}
