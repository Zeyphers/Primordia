package dev.jsz.primordia.entity.goal;

import dev.jsz.primordia.entity.CreatureEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.pathing.MobNavigation;
import net.minecraft.entity.ai.pathing.PathNodeType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.EnumSet;

/**
 * Keeps a domesticated creature at its owner's heel, teleporting to catch up when it falls too
 * far behind or gets stuck on the far side of terrain.
 * <p>
 * The teleport is what makes a follower usable rather than merely present: pathfinding alone
 * loses the owner the first time they cross water, jump down a ravine or take a boat, and a
 * companion that is reliably somewhere else is worse than none. It only ever lands on ground the
 * creature could have stood on anyway, so it cannot be used to shortcut a creature into a wall.
 */
public class FollowOwnerGoal extends Goal {
	private final CreatureEntity creature;
	private final double speed;
	private final float startDistance;
	private final float stopDistance;
	private final float teleportDistance;

	private LivingEntity owner;
	private int updateCountdown;
	/** Restored on stop: pathing over water is enabled only while following. */
	private float oldWaterPathPenalty;

	public FollowOwnerGoal(CreatureEntity creature, double speed,
	                       float startDistance, float stopDistance, float teleportDistance) {
		this.creature = creature;
		this.speed = speed;
		this.startDistance = startDistance;
		this.stopDistance = stopDistance;
		this.teleportDistance = teleportDistance;
		setControls(EnumSet.of(Control.MOVE, Control.LOOK));
	}

	@Override
	public boolean canStart() {
		if (!creature.isDomesticated() || creature.isSitting()) return false;
		if (creature.hasPassengers()) return false;

		LivingEntity candidate = creature.getOwner();
		if (candidate == null || candidate.isSpectator()) return false;
		if (creature.squaredDistanceTo(candidate) < startDistance * startDistance) return false;

		owner = candidate;
		return true;
	}

	@Override
	public boolean shouldContinue() {
		if (creature.getNavigation().isIdle()) return false;
		if (!creature.isDomesticated() || creature.isSitting()) return false;
		if (creature.hasPassengers()) return false;
		return owner != null && creature.squaredDistanceTo(owner) > stopDistance * stopDistance;
	}

	@Override
	public void start() {
		updateCountdown = 0;
		if (creature.getNavigation() instanceof MobNavigation) {
			oldWaterPathPenalty = creature.getPathfindingPenalty(PathNodeType.WATER);
			creature.setPathfindingPenalty(PathNodeType.WATER, 0f);
		}
	}

	@Override
	public void stop() {
		owner = null;
		creature.getNavigation().stop();
		if (creature.getNavigation() instanceof MobNavigation) {
			creature.setPathfindingPenalty(PathNodeType.WATER, oldWaterPathPenalty);
		}
	}

	@Override
	public void tick() {
		if (owner == null) return;
		creature.getLookControl().lookAt(owner, 10f, creature.getMaxLookPitchChange());

		if (--updateCountdown > 0) return;
		updateCountdown = getTickCount(10);

		if (creature.isLeashed()) return;
		if (creature.squaredDistanceTo(owner) >= teleportDistance * teleportDistance) {
			tryTeleportNear(owner);
			return;
		}
		creature.getNavigation().startMovingTo(owner, speed);
	}

	private void tryTeleportNear(LivingEntity target) {
		BlockPos origin = target.getBlockPos();
		// A handful of tries around the owner rather than an exhaustive search: this runs every
		// half second per follower, and failing this tick simply means trying again next tick.
		for (int attempt = 0; attempt < 10; attempt++) {
			int dx = randomOffset(-3, 3);
			int dy = randomOffset(-1, 1);
			int dz = randomOffset(-3, 3);
			if (tryTeleportTo(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz)) return;
		}
	}

	private int randomOffset(int min, int max) {
		return creature.getRandom().nextInt(max - min + 1) + min;
	}

	private boolean tryTeleportTo(int x, int y, int z) {
		if (Math.abs(x - owner.getX()) < 2.0 && Math.abs(z - owner.getZ()) < 2.0) return false;
		if (!canTeleportTo(new BlockPos(x, y, z))) return false;
		creature.refreshPositionAndAngles(x + 0.5, y, z + 0.5, creature.getYaw(), creature.getPitch());
		creature.getNavigation().stop();
		return true;
	}

	/**
	 * Checked by hand rather than through the path-node classifier: that helper's signature has
	 * moved between Minecraft versions, and the three conditions that actually matter here are
	 * short enough to state directly — solid floor, clear head, and room for the whole body.
	 */
	private boolean canTeleportTo(BlockPos pos) {
		World world = creature.getWorld();
		BlockPos below = pos.down();
		if (!world.getBlockState(below).isSolidBlock(world, below)) return false;
		if (world.getBlockState(pos).isSolidBlock(world, pos)) return false;
		BlockPos offset = pos.subtract(creature.getBlockPos());
		return world.isSpaceEmpty(creature, creature.getBoundingBox().offset(offset));
	}
}
