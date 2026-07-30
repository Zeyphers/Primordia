package dev.jsz.primordia.entity.goal;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.DietGroup;
import dev.jsz.primordia.ecology.EnergyBudget;
import dev.jsz.primordia.ecology.WorldImpact;
import dev.jsz.primordia.entity.CreatureActivity;
import dev.jsz.primordia.entity.CreatureEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.tags.BlockTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

import java.util.EnumSet;

/**
 * Sends plant-eaters to find vegetation, aim at it, and crop it.
 * <p>
 * The creature walks to the mouthful with its head already on it, settles its aim, and only then
 * starts chewing. That ordering is the point: an animal that bites at the air beside a plant and
 * an animal that eats the plant look identical in the code and completely different on screen.
 * <p>
 * How high it can browse comes from its own anatomy rather than a constant. A long-necked saurian
 * takes leaves out of a canopy; something knee-high crops the ground and cannot see the canopy as
 * food at all. Besides being the more interesting behaviour, this is what keeps the goal from
 * fixating on a block it can never get to.
 */
public class GrazeGoal extends Goal {
	private static final int SEARCH_RADIUS = 8;
	/** Squared distance at which the creature is close enough to start eating. */
	private static final double REACH_SQ = 2.2 * 2.2;
	/** Ticks between search attempts, so a creature with nothing to eat is not scanning constantly. */
	private static final int SEARCH_INTERVAL = 40;
	/**
	 * Ticks allowed to close on a mouthful before it is written off.
	 * <p>
	 * Without this the goal could not fail. It held MOVE and LOOK, {@code chewTicks} only advanced
	 * inside {@link #REACH_SQ}, and so the "have I finished eating" test never came true for a
	 * target that was never reached — a creature that picked an unreachable block stood on the spot
	 * indefinitely, outranking the wander goal that would otherwise have moved it on. That was the
	 * single largest source of animals standing still doing nothing.
	 */
	private static final int APPROACH_TIMEOUT = 100;
	/** Ticks the goal sits out after giving up, so a failure turns into wandering rather than a retry loop. */
	private static final int GIVE_UP_COOLDOWN = 160;
	/** How far off the head may still be pointing and count as aimed, in degrees. */
	private static final float AIM_TOLERANCE_YAW = 22f;
	private static final float AIM_TOLERANCE_PITCH = 30f;
	/** Ticks spent aiming before eating starts anyway, so a blocked head cannot deadlock the meal. */
	private static final int AIM_TIMEOUT = 30;
	/** How far below its own feet a creature will reach for a mouthful. */
	private static final int REACH_BELOW = 2;

	private final CreatureEntity creature;
	private BlockPos target;
	private int cooldown;
	private int chewTicks;
	private int approachTicks;
	private int aimTicks;
	private boolean aimed;

	public GrazeGoal(CreatureEntity creature) {
		this.creature = creature;
		setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		if (!creature.getDietGroup().eatsPlants()) return false;
		if (creature.isCarcass() || creature.isAsleep() || creature.isPosing()) return false;
		if (creature.getTarget() != null) return false;
		if (!creature.isHungry()) return false;
		if (cooldown-- > 0) return false;
		cooldown = SEARCH_INTERVAL;

		float appetite = creature.getDietGroup() == DietGroup.HERBIVORE ? 0.55f : 0.2f;
		if (creature.getRandom().nextFloat() > appetite) return false;

		target = findVegetation();
		return target != null;
	}

	@Override
	public boolean canContinueToUse() {
		return target != null && chewTicks < 200 && creature.getTarget() == null
				&& creature.getEnergy() < 1f;
	}

	@Override
	public void start() {
		chewTicks = 0;
		approachTicks = 0;
		aimTicks = 0;
		aimed = false;
		if (target != null) {
			creature.getNavigation().moveTo(
					target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0);
		}
	}

	@Override
	public void stop() {
		target = null;
		chewTicks = 0;
		aimed = false;
		creature.getNavigation().stop();
	}

	@Override
	public boolean isInterruptable() {
		return true;
	}

	@Override
	public void tick() {
		if (target == null) return;

		creature.lookAtFromHead(
				target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);

		double distanceSq = creature.distanceToSqr(
				target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);

		if (distanceSq > REACH_SQ) {
			approach();
			return;
		}

		creature.getNavigation().stop();

		if (!aimed) {
			aimTicks++;
			if (isAimedAtTarget() || aimTicks > AIM_TIMEOUT) {
				aimed = true;
			} else {
				return;
			}
		}

		chewTicks++;
		if (chewTicks % 20 == 1) {
			creature.triggerActivity(CreatureActivity.GRAZE);
		}

		BodyPlan plan = creature.getBodyPlan();
		if (plan == null || chewTicks % 20 != 10) return;

		creature.addEnergy(EnergyBudget.mouthfulValue(plan));
		if (creature.level() instanceof ServerLevel world && WorldImpact.graze(world, target)) {
			target = findVegetation();
			if (target == null) {
				chewTicks = 999;
			} else {
				aimed = false;
				aimTicks = 0;
				approachTicks = 0;
			}
		}
	}

	private void approach() {
		approachTicks++;
		if (approachTicks > APPROACH_TIMEOUT) {
			giveUp();
			return;
		}
		if (!creature.getNavigation().isDone()) return;

		boolean pathed = creature.getNavigation().moveTo(
				target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0);
		if (!pathed) giveUp();
	}

	private void giveUp() {
		target = null;
		cooldown = GIVE_UP_COOLDOWN;
	}

	private boolean isAimedAtTarget() {
		double dx = target.getX() + 0.5 - creature.getX();
		double dz = target.getZ() + 0.5 - creature.getZ();
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		if (horizontal < 1.0e-4) return true;

		float wantYaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90f;
		if (Math.abs(Mth.wrapDegrees(creature.getYHeadRot() - wantYaw)) > AIM_TOLERANCE_YAW) {
			return false;
		}

		double dy = target.getY() + 0.5 - creature.getHeadY();
		float wantPitch = (float) (-Mth.atan2(dy, horizontal) * Mth.RAD_TO_DEG);
		return Math.abs(Mth.wrapDegrees(creature.getXRot() - wantPitch)) <= AIM_TOLERANCE_PITCH;
	}

	private BlockPos findVegetation() {
		Level world = creature.level();
		BlockPos origin = creature.blockPosition();
		int reachUp = browseHeight();
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;

		for (BlockPos pos : BlockPos.betweenClosed(
				origin.offset(-SEARCH_RADIUS, -REACH_BELOW, -SEARCH_RADIUS),
				origin.offset(SEARCH_RADIUS, reachUp, SEARCH_RADIUS))) {
			if (!isEdible(world, pos)) continue;
			double d = pos.distToCenterSqr(creature.position());
			if (d < bestDistance) {
				bestDistance = d;
				best = pos.immutable();
			}
		}
		return best;
	}

	/**
	 * How many blocks above its feet the creature can take a mouthful from.
	 * <p>
	 * Measured from where the head actually sits, plus a little for stretching. Clamped at the
	 * bottom so that even a low-slung animal can reach the block it is standing beside, and at the
	 * top so that a giant is not scanning half a chunk of canopy every time it gets hungry.
	 */
	private int browseHeight() {
		double head = creature.getHeadY() - creature.getY();
		return Mth.clamp((int) Math.floor(head + 0.75), 1, 6);
	}

	private static boolean isEdible(Level world, BlockPos pos) {
		var state = world.getBlockState(pos);
		return state.is(BlockTags.REPLACEABLE_BY_TREES)
				|| state.is(BlockTags.FLOWERS)
				|| state.is(BlockTags.LEAVES);
	}
}
