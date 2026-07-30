package dev.jsz.primordia.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

import java.util.function.BiFunction;

/**
 * The block half of a lab workstation.
 * <p>
 * One class serves both machines: they differ in what their block entity does, not in how they sit
 * in the level, so the facing, the lit state, the drop-contents-on-break behaviour and the
 * open-the-screen interaction are all shared. The factory passed in is the only difference.
 */
public class LabMachineBlock extends Block implements EntityBlock {

	public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
	/**
	 * What the machine is doing, mirrored out of its block entity.
	 * <p>
	 * A boolean lit flag was not enough once the two stages were merged into one block: sequencing
	 * and decoding both mean "working", and the whole point of showing them is that they are
	 * different jobs with different costs. The console's animation is selected from this.
	 */
	public static final EnumProperty<GeneLabBlockEntity.Stage> STAGE =
			EnumProperty.create("stage", GeneLabBlockEntity.Stage.class);

	private final BiFunction<BlockPos, BlockState, BlockEntity> factory;
	private final BlockEntityTypeSupplier typeSupplier;

	/** Deferred because the block is constructed before its block entity type is registered. */
	@FunctionalInterface
	public interface BlockEntityTypeSupplier {
		BlockEntityType<?> get();
	}

	public LabMachineBlock(Properties settings,
	                       BiFunction<BlockPos, BlockState, BlockEntity> factory,
	                       BlockEntityTypeSupplier typeSupplier) {
		super(settings);
		this.factory = factory;
		this.typeSupplier = typeSupplier;
		registerDefaultState(getStateDefinition().any()
				.setValue(FACING, Direction.NORTH)
				.setValue(STAGE, GeneLabBlockEntity.Stage.IDLE));
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return factory.apply(pos, state);
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, STAGE);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext ctx) {
		return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
	}

	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		return state.rotate(mirror.getRotation(state.getValue(FACING)));
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
	                                     BlockHitResult hit) {
		if (level.isClientSide()) return InteractionResult.SUCCESS;
		BlockEntity be = level.getBlockEntity(pos);
		if (be instanceof MenuProvider screenFactory) {
			player.openMenu(screenFactory);
		}
		return InteractionResult.CONSUME;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Contents are scattered on break. Without this a machine mid-run swallows whatever was in it,
	 * and a tissue sample is not a reproducible item — it is a specific animal the player went and
	 * found.
	 */
	@Override
	protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean moved) {
		BlockEntity be = level.getBlockEntity(pos);
		if (be instanceof GeneLabBlockEntity machine) {
			Containers.dropContents(level, pos, machine);
			level.updateNeighbourForOutputSignal(pos, this);
		}
		super.affectNeighborsAfterRemoval(state, level, pos, moved);
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * The type check matters: {@code getTicker} is handed whichever block entity type the level is
	 * asking about, and returning a ticker that casts blindly would run this block's logic against
	 * an unrelated block entity.
	 */
	@Override
	@SuppressWarnings("unchecked")
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
	                                                             BlockEntityType<T> type) {
		if (level.isClientSide() || type != typeSupplier.get()) return null;
		return (BlockEntityTicker<T>) (BlockEntityTicker<GeneLabBlockEntity>) GeneLabBlockEntity::serverTick;
	}

	/** A working machine vents warm air, which reads from across a room where a screen does not. */
	@Override
	public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
		if (state.getValue(STAGE) == GeneLabBlockEntity.Stage.IDLE) return;
		double x = pos.getX() + 0.5;
		double y = pos.getY() + 0.9;
		double z = pos.getZ() + 0.5;
		if (random.nextFloat() < 0.4f) {
			level.addParticle(ParticleTypes.SMOKE,
					x + (random.nextDouble() - 0.5) * 0.4, y, z + (random.nextDouble() - 0.5) * 0.4,
					0.0, 0.02, 0.0);
		}
	}
}
