package dev.jsz.primordia.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;

import java.util.function.BiFunction;

/**
 * The block half of a lab workstation.
 * <p>
 * One class serves both machines: they differ in what their block entity does, not in how they sit
 * in the world, so the facing, the lit state, the drop-contents-on-break behaviour and the
 * open-the-screen interaction are all shared. The factory passed in is the only difference.
 */
public class LabMachineBlock extends Block implements BlockEntityProvider {

	public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
	/**
	 * What the machine is doing, mirrored out of its block entity.
	 * <p>
	 * A boolean lit flag was not enough once the two stages were merged into one block: sequencing
	 * and decoding both mean "working", and the whole point of showing them is that they are
	 * different jobs with different costs. The console's animation is selected from this.
	 */
	public static final EnumProperty<GeneLabBlockEntity.Stage> STAGE =
			EnumProperty.of("stage", GeneLabBlockEntity.Stage.class);

	private final BiFunction<BlockPos, BlockState, BlockEntity> factory;
	private final BlockEntityTypeSupplier typeSupplier;

	/** Deferred because the block is constructed before its block entity type is registered. */
	@FunctionalInterface
	public interface BlockEntityTypeSupplier {
		BlockEntityType<?> get();
	}

	public LabMachineBlock(Settings settings,
	                       BiFunction<BlockPos, BlockState, BlockEntity> factory,
	                       BlockEntityTypeSupplier typeSupplier) {
		super(settings);
		this.factory = factory;
		this.typeSupplier = typeSupplier;
		setDefaultState(getStateManager().getDefaultState()
				.with(FACING, Direction.NORTH)
				.with(STAGE, GeneLabBlockEntity.Stage.IDLE));
	}

	@Override
	public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
		return factory.apply(pos, state);
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(FACING, STAGE);
	}

	@Override
	public BlockState getPlacementState(ItemPlacementContext ctx) {
		return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
	}

	@Override
	protected BlockState rotate(BlockState state, BlockRotation rotation) {
		return state.with(FACING, rotation.rotate(state.get(FACING)));
	}

	@Override
	protected BlockState mirror(BlockState state, BlockMirror mirror) {
		return state.rotate(mirror.getRotation(state.get(FACING)));
	}

	@Override
	protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player,
	                             BlockHitResult hit) {
		if (world.isClient()) return ActionResult.SUCCESS;
		BlockEntity be = world.getBlockEntity(pos);
		if (be instanceof NamedScreenHandlerFactory screenFactory) {
			player.openHandledScreen(screenFactory);
		}
		return ActionResult.CONSUME;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Contents are scattered on break. Without this a machine mid-run swallows whatever was in it,
	 * and a tissue sample is not a reproducible item — it is a specific animal the player went and
	 * found.
	 */
	@Override
	protected void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState,
	                               boolean moved) {
		if (state.isOf(newState.getBlock())) return;
		BlockEntity be = world.getBlockEntity(pos);
		if (be instanceof GeneLabBlockEntity machine) {
			ItemScatterer.spawn(world, pos, machine);
			world.updateComparators(pos, this);
		}
		super.onStateReplaced(state, world, pos, newState, moved);
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * The type check matters: {@code getTicker} is handed whichever block entity type the world is
	 * asking about, and returning a ticker that casts blindly would run this block's logic against
	 * an unrelated block entity.
	 */
	@Override
	@SuppressWarnings("unchecked")
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state,
	                                                             BlockEntityType<T> type) {
		if (world.isClient() || type != typeSupplier.get()) return null;
		return (BlockEntityTicker<T>) (BlockEntityTicker<GeneLabBlockEntity>) GeneLabBlockEntity::tick;
	}

	/** A working machine vents warm air, which reads from across a room where a screen does not. */
	@Override
	public void randomDisplayTick(BlockState state, World world, BlockPos pos, Random random) {
		if (state.get(STAGE) == GeneLabBlockEntity.Stage.IDLE) return;
		double x = pos.getX() + 0.5;
		double y = pos.getY() + 0.9;
		double z = pos.getZ() + 0.5;
		if (random.nextFloat() < 0.4f) {
			world.addParticle(ParticleTypes.SMOKE,
					x + (random.nextDouble() - 0.5) * 0.4, y, z + (random.nextDouble() - 0.5) * 0.4,
					0.0, 0.02, 0.0);
		}
	}
}
