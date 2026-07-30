package dev.jsz.primordia.entity.goal;

import dev.jsz.primordia.entity.CreatureEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

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
		setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		if (!creature.isDomesticated() || creature.isSitting()) return false;
		if (creature.isVehicle()) return false;

		LivingEntity candidate = creature.getOwner();
		if (candidate == null || candidate.isSpectator()) return false;
		if (creature.distanceToSqr(candidate) < startDistance * startDistance) return false;

		owner = candidate;
		return true;
	}

	@Override
	public boolean canContinueToUse() {
		if (creature.getNavigation().isDone()) return false;
		if (!creature.isDomesticated() || creature.isSitting()) return false;
		if (creature.isVehicle()) return false;
		return owner != null && creature.distanceToSqr(owner) > stopDistance * stopDistance;
	}

	@Override
	public void start() {
		updateCountdown = 0;
		if (creature.getNavigation() instanceof GroundPathNavigation) {
			oldWaterPathPenalty = creature.getPathfindingMalus(PathType.WATER);
			creature.setPathfindingMalus(PathType.WATER, 0f);
		}
	}

	@Override
	public void stop() {
		owner = null;
		creature.getNavigation().stop();
		if (creature.getNavigation() instanceof GroundPathNavigation) {
			creature.setPathfindingMalus(PathType.WATER, oldWaterPathPenalty);
		}
	}

	@Override
	public void tick() {
		if (owner == null) return;
		creature.getLookControl().setLookAt(owner, 10f, creature.getMaxHeadXRot());

		if (--updateCountdown > 0) return;
		updateCountdown = 10;

		if (creature.isLeashed()) return;
		if (creature.distanceToSqr(owner) >= teleportDistance * teleportDistance) {
			tryTeleportNear(owner);
			return;
		}
		creature.getNavigation().moveTo(owner, speed);
	}

	private void tryTeleportNear(LivingEntity target) {
		BlockPos origin = target.blockPosition();
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
		creature.snapTo(x + 0.5, y, z + 0.5, creature.getYRot(), creature.getXRot());
		creature.getNavigation().stop();
		return true;
	}

	private boolean canTeleportTo(BlockPos pos) {
		Level world = creature.level();
		BlockPos below = pos.below();
		if (!world.getBlockState(below).isSolidRender()) return false;
		if (world.getBlockState(pos).isSolidRender()) return false;
		BlockPos offset = pos.subtract(creature.blockPosition());
		return world.noCollision(creature, creature.getBoundingBox().move(offset));
	}
}
