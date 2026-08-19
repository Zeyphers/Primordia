package dev.jsz.primordia.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The splicing bench.
 * <p>
 * Deliberately <b>not</b> built on {@link LabMachineBlock}, which serves the gene lab and the shape
 * of machine that has a screen and an inventory. This one has neither: the player plans at the field
 * guide's Self tab and the bench is where the work physically happens, so right-clicking it opens
 * nothing. What it has instead is a {@link #RUNNING} flag, which exists for one reason — the model
 * animates, and it must only animate while there is something to animate about.
 */
public class SplicerBlock extends Block implements EntityBlock {

	public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

	/**
	 * Whether a splice is being run right now.
	 * <p>
	 * A block state property rather than a field on the block entity, because the renderer needs it
	 * on the client and block states are already synchronised. It is the whole reason this block
	 * carries any state at all.
	 */
	public static final BooleanProperty RUNNING = BlockStateProperties.LIT;

	/**
	 * The body is a clean sixteen by fifteen, so the collision box is the model's own bounds rather
	 * than a full cube — standing on one should put you on its deck, not floating a pixel above it.
	 */
	private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 15, 16);

	public SplicerBlock(Properties settings) {
		super(settings);
		registerDefaultState(getStateDefinition().any()
				.setValue(FACING, Direction.NORTH)
				.setValue(RUNNING, false));
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new SplicerBlockEntity(pos, state);
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, RUNNING);
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
	protected VoxelShape getShape(BlockState state, net.minecraft.world.level.BlockGetter level,
	                              BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	/**
	 * Right-clicking opens the bench.
	 * <p>
	 * It used to open the field guide instead, which was the wrong instinct: the guide is where a
	 * player reads and compares, and a machine that opened a book was a machine with no interface of
	 * its own. The bench now has one, and it is the only place a splice can actually be started.
	 */
	@Override
	protected net.minecraft.world.InteractionResult useWithoutItem(
			BlockState state, Level level, BlockPos pos,
			net.minecraft.world.entity.player.Player player,
			net.minecraft.world.phys.BlockHitResult hit) {
		if (level.isClientSide()) return net.minecraft.world.InteractionResult.SUCCESS;
		BlockEntity be = level.getBlockEntity(pos);
		if (be instanceof net.minecraft.world.MenuProvider menu) player.openMenu(menu);
		return net.minecraft.world.InteractionResult.CONSUME;
	}

	/** Whatever the bench had brewed is dropped rather than swallowed when it is broken. */
	@Override
	protected void affectNeighborsAfterRemoval(BlockState state,
	                                           net.minecraft.server.level.ServerLevel level,
	                                           BlockPos pos, boolean moved) {
		if (level.getBlockEntity(pos) instanceof SplicerBlockEntity bench) {
			net.minecraft.world.Containers.dropContents(level, pos, bench);
		}
		super.affectNeighborsAfterRemoval(state, level, pos, moved);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
	                                                             BlockEntityType<T> type) {
		// The same type check LabMachineBlock makes, for the same reason: getTicker is asked about
		// whichever type the level is looking at, and a blind cast would run this logic against an
		// unrelated block entity.
		if (level.isClientSide()
				|| type != dev.jsz.primordia.registry.PrimordiaBlockEntities.SPLICER) return null;
		return (BlockEntityTicker<T>) (BlockEntityTicker<SplicerBlockEntity>) SplicerBlockEntity::serverTick;
	}

	/** A running splice fizzes over the sample stage. Nothing at all when it is idle. */
	@Override
	public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
		if (!state.getValue(RUNNING)) return;
		if (random.nextFloat() > 0.35f) return;
		level.addParticle(ParticleTypes.END_ROD,
				pos.getX() + 0.5 + (random.nextDouble() - 0.5) * 0.35,
				pos.getY() + 0.45,
				pos.getZ() + 0.5 + (random.nextDouble() - 0.5) * 0.35,
				0.0, 0.015, 0.0);
	}
}
