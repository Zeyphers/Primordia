package dev.jsz.primordia.registry;

import dev.jsz.primordia.Primordia;
import dev.jsz.primordia.block.GeneLabBlockEntity;
import dev.jsz.primordia.block.GenomeBankBlockEntity;
import dev.jsz.primordia.block.PreservationCaseBlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

public final class PrimordiaBlockEntities {

	public static final BlockEntityType<GeneLabBlockEntity> BASIC_GENE_LAB =
			register("basic_gene_lab", GeneLabBlockEntity::new, PrimordiaBlocks.BASIC_GENE_LAB);

	public static final BlockEntityType<PreservationCaseBlockEntity> PRESERVATION_CASE =
			register("preservation_case", PreservationCaseBlockEntity::new,
					PrimordiaBlocks.PRESERVATION_CASE);

	public static final BlockEntityType<GenomeBankBlockEntity> GENOME_BANK =
			register("genome_bank", GenomeBankBlockEntity::new, PrimordiaBlocks.GENOME_BANK);

	private PrimordiaBlockEntities() {
	}

	private static <T extends net.minecraft.block.entity.BlockEntity> BlockEntityType<T> register(
			String path, net.minecraft.block.entity.BlockEntityType.BlockEntityFactory<? extends T> factory,
			net.minecraft.block.Block... blocks) {
		return Registry.register(Registries.BLOCK_ENTITY_TYPE, Primordia.id(path),
				BlockEntityType.Builder.<T>create(factory, blocks).build());
	}

	/**
	 * Forces class initialisation.
	 * <p>
	 * The types are static finals, so nothing exists until something touches the class — and the
	 * blocks reference their type lazily through a supplier, so nothing ever would. This is the
	 * push.
	 */
	public static void register() {
	}
}
