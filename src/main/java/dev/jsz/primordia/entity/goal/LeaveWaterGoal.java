package dev.jsz.primordia.entity.goal;

import dev.jsz.primordia.entity.CreatureEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.EnumSet;

/**
 * Sends a creature that has ended up in water toward the nearest dry land.
 * <p>
 * These are terrestrial animals. Vanilla's {@code SwimGoal} keeps their heads above the surface
 * but gives them no reason to leave, so without this they tread water indefinitely wherever the
 * wander goal happened to drop them. Priority sits above wandering so a swimming creature commits
 * to getting out rather than continuing to pick random destinations across the lake.
 * <p>
 * Gated on {@link CreatureEntity#isSwimmingDepth()} rather than on merely touching water, so that
 * an animal is only pulled ashore when it is actually out of its depth. Wading is not drowning: a
 * tall creature crossing a stream has no reason to abandon where it was going, and gating this on
 * contact made every shallow puddle a wall that turned animals around.
 */
public class LeaveWaterGoal extends Goal {
	private static final int SEARCH_RADIUS = 24;
	private static final int VERTICAL_SPAN = 4;
	/** Re-search interval while still in the water, in ticks. */
	private static final int REPATH_INTERVAL = 30;

	private final CreatureEntity creature;
	private final double speed;
	private BlockPos shore;
	private int repathTimer;

	public LeaveWaterGoal(CreatureEntity creature, double speed) {
		this.creature = creature;
		this.speed = speed;
		setFlags(EnumSet.of(Flag.MOVE));
	}

	@Override
	public boolean canUse() {
		if (!creature.isSwimmingDepth()) return false;
		if (creature.getControllingPassenger() != null) return false;
		shore = findShore();
		return shore != null;
	}

	@Override
	public boolean canContinueToUse() {
		return creature.isSwimmingDepth()
				&& shore != null
				&& creature.getControllingPassenger() == null;
	}

	@Override
	public void start() {
		repathTimer = 0;
		moveToShore();
	}

	@Override
	public void stop() {
		shore = null;
		creature.getNavigation().stop();
	}

	@Override
	public void tick() {
		if (--repathTimer > 0) return;
		repathTimer = REPATH_INTERVAL;
		shore = findShore();
		moveToShore();
	}

	private void moveToShore() {
		if (shore == null) return;
		creature.getNavigation().moveTo(
				shore.getX() + 0.5, shore.getY(), shore.getZ() + 0.5, speed);
	}

	private BlockPos findShore() {
		Level world = creature.level();
		BlockPos origin = creature.blockPosition();
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;

		for (BlockPos pos : BlockPos.betweenClosed(
				origin.offset(-SEARCH_RADIUS, -VERTICAL_SPAN, -SEARCH_RADIUS),
				origin.offset(SEARCH_RADIUS, VERTICAL_SPAN, SEARCH_RADIUS))) {
			if (!isDryFooting(world, pos)) continue;
			double d = pos.distToCenterSqr(creature.position());
			if (d < bestDistance) {
				bestDistance = d;
				best = pos.immutable();
			}
		}
		return best;
	}

	private static boolean isDryFooting(Level world, BlockPos pos) {
		if (!world.getFluidState(pos).isEmpty()) return false;
		if (!world.getFluidState(pos.above()).isEmpty()) return false;
		// Needs something solid to stand on and clear space to stand in.
		return world.getBlockState(pos.below()).isSolidRender()
				&& world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()
				&& world.getBlockState(pos.above()).getCollisionShape(world, pos.above()).isEmpty();
	}
}
