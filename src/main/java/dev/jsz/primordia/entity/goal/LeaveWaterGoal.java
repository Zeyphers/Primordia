package dev.jsz.primordia.entity.goal;

import dev.jsz.primordia.entity.CreatureEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.EnumSet;

/**
 * Sends a creature that has ended up in water toward the nearest dry land.
 * <p>
 * These are terrestrial animals. Vanilla's {@code SwimGoal} keeps their heads above the surface
 * but gives them no reason to leave, so without this they tread water indefinitely wherever the
 * wander goal happened to drop them. Priority sits above wandering so a swimming creature commits
 * to getting out rather than continuing to pick random destinations across the lake.
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
		setControls(EnumSet.of(Control.MOVE));
	}

	@Override
	public boolean canStart() {
		if (!creature.isTouchingWater()) return false;
		// A ridden creature goes where its rider says, water or not.
		if (creature.getControllingPassenger() != null) return false;
		shore = findShore();
		return shore != null;
	}

	@Override
	public boolean shouldContinue() {
		return creature.isTouchingWater()
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
		// Water pushes the creature around and paths expire; re-target periodically.
		shore = findShore();
		moveToShore();
	}

	private void moveToShore() {
		if (shore == null) return;
		creature.getNavigation().startMovingTo(
				shore.getX() + 0.5, shore.getY(), shore.getZ() + 0.5, speed);
	}

	/** Nearest standable dry block: solid underfoot, open above, and not itself submerged. */
	private BlockPos findShore() {
		World world = creature.getWorld();
		BlockPos origin = creature.getBlockPos();
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;

		for (BlockPos pos : BlockPos.iterate(
				origin.add(-SEARCH_RADIUS, -VERTICAL_SPAN, -SEARCH_RADIUS),
				origin.add(SEARCH_RADIUS, VERTICAL_SPAN, SEARCH_RADIUS))) {
			if (!isDryFooting(world, pos)) continue;
			double d = pos.getSquaredDistance(creature.getPos());
			if (d < bestDistance) {
				bestDistance = d;
				best = pos.toImmutable();
			}
		}
		return best;
	}

	private static boolean isDryFooting(World world, BlockPos pos) {
		if (!world.getFluidState(pos).isEmpty()) return false;
		if (!world.getFluidState(pos.up()).isEmpty()) return false;
		// Needs something solid to stand on and clear space to stand in.
		return world.getBlockState(pos.down()).isSolidBlock(world, pos.down())
				&& world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()
				&& world.getBlockState(pos.up()).getCollisionShape(world, pos.up()).isEmpty();
	}
}
