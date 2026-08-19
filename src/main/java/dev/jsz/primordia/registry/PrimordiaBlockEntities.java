package dev.jsz.primordia.registry;

import dev.jsz.primordia.Primordia;
import dev.jsz.primordia.block.GeneLabBlockEntity;
import dev.jsz.primordia.block.SampleCoolerBlockEntity;
import dev.jsz.primordia.block.SplicerBlockEntity;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.Set;

public final class PrimordiaBlockEntities {

	public static final BlockEntityType<GeneLabBlockEntity> BASIC_GENE_LAB =
			register("basic_gene_lab", GeneLabBlockEntity::new, PrimordiaBlocks.BASIC_GENE_LAB);

	public static final BlockEntityType<SampleCoolerBlockEntity> SAMPLE_COOLER =
			register("sample_cooler", SampleCoolerBlockEntity::new, PrimordiaBlocks.SAMPLE_COOLER);

	public static final BlockEntityType<SplicerBlockEntity> SPLICER =
			register("splicer", SplicerBlockEntity::new, PrimordiaBlocks.SPLICER);

	private PrimordiaBlockEntities() {
	}

	private static <T extends BlockEntity> BlockEntityType<T> register(
			String path, BlockEntityType.BlockEntitySupplier<? extends T> factory,
			Block... blocks) {
		return Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, Primordia.id(path),
				new BlockEntityType<>(factory, Set.of(blocks)));
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
