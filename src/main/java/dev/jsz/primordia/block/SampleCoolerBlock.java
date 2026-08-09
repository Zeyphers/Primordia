package dev.jsz.primordia.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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

import java.util.function.Supplier;

/**
 * The Sample Cooler: cold storage that survives being picked up.
 * <p>
 * Everything that makes the contents persist is elsewhere. The block entity inherits the container
 * component from {@code BaseContainerBlockEntity} and declines to scatter its contents on removal;
 * the loot table copies that component onto the dropped item. This class only opens the screen and
 * runs the ticker.
 */
public class SampleCoolerBlock extends Block implements EntityBlock {

	/**
	 * Which way the box is turned, the same property a furnace or a chest carries.
	 * <p>
	 * It earns its place here for the reason it does on those: the model has a front. The hinge and
	 * the vent grille are on one side, and a box that always faced north would have its back to
	 * whoever placed it half the time.
	 */
	public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

	private final Supplier<BlockEntityType<?>> typeSupplier;

	/** Deferred: the block is constructed before its block entity type is registered. */
	public SampleCoolerBlock(Properties settings, Supplier<BlockEntityType<?>> typeSupplier) {
		super(settings);
		this.typeSupplier = typeSupplier;
		registerDefaultState(getStateDefinition().any().setValue(FACING, Direction.NORTH));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	/**
	 * Faces the player, like a chest.
	 * <p>
	 * Without the {@code getOpposite()} that the chest convention normally carries: the cooler's
	 * model has its front on the opposite face to the one that convention assumes, so applying the
	 * usual rule put its back to whoever placed it.
	 */
	@Override
	public BlockState getStateForPlacement(BlockPlaceContext ctx) {
		return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection());
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
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new SampleCoolerBlockEntity(pos, state);
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
	                                           Player player, BlockHitResult hit) {
		if (level.isClientSide()) return InteractionResult.SUCCESS;
		if (level.getBlockEntity(pos) instanceof MenuProvider screenFactory) {
			player.openMenu(screenFactory);
		}
		return InteractionResult.CONSUME;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * The type check matters: {@code getTicker} is handed whichever block entity type the level is
	 * asking about, and a ticker that cast blindly would run this block's logic against an unrelated
	 * block entity.
	 */
	@Override
	@SuppressWarnings("unchecked")
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
	                                                             BlockEntityType<T> type) {
		if (level.isClientSide() || type != typeSupplier.get()) return null;
		return (BlockEntityTicker<T>) (BlockEntityTicker<SampleCoolerBlockEntity>)
				SampleCoolerBlockEntity::serverTick;
	}
}
