package dev.jsz.primordia.client;

import dev.jsz.primordia.anim.GroundProbe;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;

/**
 * {@link GroundProbe} backed by a live world. Finds the surface a foot could actually stand on
 * near a given column.
 * <p>
 * "Could actually stand on" is doing real work here. A naive probe that returns the first solid
 * block found scanning downward will happily latch onto the side of a wall or a tree trunk: the
 * foot's target column passes through the trunk, the scan finds the trunk block, and the leg
 * stretches up to plant on it — the limb appears glued to the wall as the creature walks by.
 * <p>
 * Two rules prevent that. A candidate surface must have <b>headroom</b> — the space just above it
 * has to be clear, which a solid wall column never is — and it must be within a <b>step height</b>
 * of the creature's own feet, so nothing plants on a surface it could not have climbed. When
 * neither rule can be satisfied the probe reports {@link Float#NaN} and the animator falls back to
 * the creature's own foot level, leaving the leg hanging naturally beside the obstacle instead of
 * reaching for it.
 * <p>
 * A block-state scan is used rather than {@code World#raycast} because this runs once per foot per
 * frame — up to eight times per creature — and a raycast allocates a context object each call.
 */
public final class WorldGroundProbe implements GroundProbe {
	/** How far above the creature's feet a surface may be and still be steppable. */
	private static final float MAX_STEP_UP = 1.3f;
	/** How far below the creature's feet the scan will look before giving up. */
	private static final float MAX_DROP = 4.0f;
	/** Vertical clearance a candidate surface needs above it to count as standable. */
	private static final double REQUIRED_HEADROOM = 0.4;

	private World world;
	private final BlockPos.Mutable cursor = new BlockPos.Mutable();

	public GroundProbe forWorld(World world) {
		this.world = world;
		return this;
	}

	/**
	 * @param referenceY the creature's own foot level, used as the origin for the step-up and
	 *                   drop limits — not a raw scan start height
	 */
	@Override
	public float groundY(double x, double z, double referenceY) {
		if (world == null) return Float.NaN;

		int blockX = (int) Math.floor(x);
		int blockZ = (int) Math.floor(z);
		int top = (int) Math.floor(referenceY + MAX_STEP_UP);
		int bottom = (int) Math.floor(referenceY - MAX_DROP);

		for (int y = top; y >= bottom; y--) {
			cursor.set(blockX, y, blockZ);
			VoxelShape shape = world.getBlockState(cursor).getCollisionShape(world, cursor);
			if (shape.isEmpty()) continue;

			// Surface height of this block, including partial blocks like slabs and stairs.
			double surface = y + shape.getMax(Direction.Axis.Y);

			// Too high to have been stepped onto — this is a wall face, not a floor.
			if (surface > referenceY + MAX_STEP_UP) continue;

			if (!hasHeadroom(blockX, blockZ, surface)) continue;

			return (float) surface;
		}
		return Float.NaN;
	}

	/**
	 * True when there is clear space directly above {@code surface}. This is what distinguishes a
	 * floor from the side of a wall: a wall's blocks are stacked, so every one of them fails.
     */
	private boolean hasHeadroom(int blockX, int blockZ, double surface) {
		int checkY = (int) Math.floor(surface + 1e-4);
		for (int offset = 0; offset <= 1; offset++) {
			cursor.set(blockX, checkY + offset, blockZ);
			VoxelShape above = world.getBlockState(cursor).getCollisionShape(world, cursor);
			if (above.isEmpty()) {
				// Fully clear block: definitely enough room.
				if (offset == 0) return true;
				continue;
			}
			double blockBottom = (checkY + offset) + above.getMin(Direction.Axis.Y);
			double gap = blockBottom - surface;
			if (gap < REQUIRED_HEADROOM) return false;
			return true;
		}
		return true;
	}
}
