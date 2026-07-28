package dev.jsz.primordia.registry;

import dev.jsz.primordia.Primordia;
import dev.jsz.primordia.block.GeneLabBlockEntity;
import dev.jsz.primordia.block.GenomeBankBlockEntity;
import dev.jsz.primordia.block.LabMachineBlock;
import dev.jsz.primordia.block.PreservationCaseBlockEntity;
import dev.jsz.primordia.block.SimpleContainerBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;

/** The lab's four stations. */
public final class PrimordiaBlocks {

	/**
	 * The whole pipeline in one machine. Not a full cube — the model has an angled console — so it
	 * is non-opaque and must not have its neighbours' faces culled against it.
	 */
	public static final Block BASIC_GENE_LAB = register("basic_gene_lab",
			new LabMachineBlock(
					Block.Settings.copy(Blocks.BLAST_FURNACE)
							.nonOpaque()
							.luminance(state -> state.get(LabMachineBlock.STAGE)
									== GeneLabBlockEntity.Stage.IDLE ? 0 : 9),
					GeneLabBlockEntity::new,
					() -> PrimordiaBlockEntities.BASIC_GENE_LAB));

	public static final Block PRESERVATION_CASE = register("preservation_case",
			new SimpleContainerBlock(
					Block.Settings.create().strength(3.0f).requiresTool()
							.sounds(BlockSoundGroup.METAL),
					PreservationCaseBlockEntity::new,
					() -> PrimordiaBlockEntities.PRESERVATION_CASE,
					false, true));

	public static final Block GENOME_BANK = register("genome_bank",
			new SimpleContainerBlock(
					Block.Settings.create().strength(3.0f).requiresTool()
							.sounds(BlockSoundGroup.METAL),
					GenomeBankBlockEntity::new,
					() -> PrimordiaBlockEntities.GENOME_BANK,
					true, false));

	private PrimordiaBlocks() {
	}

	private static Block register(String path, Block block) {
		return Registry.register(Registries.BLOCK, Primordia.id(path), block);
	}

	private static Item registerItem(String path, Block block) {
		return Registry.register(Registries.ITEM, Primordia.id(path),
				new BlockItem(block, new Item.Settings()));
	}

	/**
	 * Registers the block items. They are placed in the menu by {@link PrimordiaItemGroup}, which
	 * gathers the whole mod into one tab.
	 */
	public static void register() {
		registerItem("basic_gene_lab", BASIC_GENE_LAB);
		registerItem("preservation_case", PRESERVATION_CASE);
		registerItem("genome_bank", GENOME_BANK);
	}
}
