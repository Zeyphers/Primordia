package dev.jsz.primordia.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.function.BiFunction;

/**
 * The block half of the two storage stations.
 * <p>
 * Sneaking is a second interaction rather than a second block: on the Genome Bank it prints the
 * index instead of opening the drawer. Squeezing both onto one block keeps the lab from sprawling
 * into a station per verb.
 */
public class SimpleContainerBlock extends Block implements BlockEntityProvider {

	private final BiFunction<BlockPos, BlockState, BlockEntity> factory;
	private final LabMachineBlock.BlockEntityTypeSupplier typeSupplier;
	private final boolean printsIndexOnSneak;
	private final boolean ticks;

	public SimpleContainerBlock(Settings settings,
	                            BiFunction<BlockPos, BlockState, BlockEntity> factory,
	                            LabMachineBlock.BlockEntityTypeSupplier typeSupplier,
	                            boolean printsIndexOnSneak,
	                            boolean ticks) {
		super(settings);
		this.factory = factory;
		this.typeSupplier = typeSupplier;
		this.printsIndexOnSneak = printsIndexOnSneak;
		this.ticks = ticks;
	}

	@Override
	public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
		return factory.apply(pos, state);
	}

	@Override
	protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player,
	                             BlockHitResult hit) {
		if (world.isClient()) return ActionResult.SUCCESS;

		if (printsIndexOnSneak && player.isSneaking() && world instanceof ServerWorld serverWorld) {
			GenomeBankBlockEntity.printIndex(serverWorld, player);
			return ActionResult.CONSUME;
		}
		BlockEntity be = world.getBlockEntity(pos);
		if (be instanceof NamedScreenHandlerFactory screenFactory) {
			player.openHandledScreen(screenFactory);
		}
		return ActionResult.CONSUME;
	}

	@Override
	protected void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState,
	                               boolean moved) {
		if (state.isOf(newState.getBlock())) return;
		BlockEntity be = world.getBlockEntity(pos);
		if (be instanceof SimpleContainerBlockEntity container) {
			ItemScatterer.spawn(world, pos, container);
			world.updateComparators(pos, this);
		}
		super.onStateReplaced(state, world, pos, newState, moved);
	}

	/** Only the Preservation Case ticks, and only against its own block entity type. */
	@Override
	@SuppressWarnings("unchecked")
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state,
	                                                             BlockEntityType<T> type) {
		if (!ticks || world.isClient() || type != typeSupplier.get()) return null;
		return (BlockEntityTicker<T>) (BlockEntityTicker<PreservationCaseBlockEntity>)
				PreservationCaseBlockEntity::tick;
	}
}
