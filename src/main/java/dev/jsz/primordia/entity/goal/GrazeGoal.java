package dev.jsz.primordia.entity.goal;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.DietGroup;
import dev.jsz.primordia.ecology.EnergyBudget;
import dev.jsz.primordia.ecology.WorldImpact;
import dev.jsz.primordia.entity.CreatureActivity;
import dev.jsz.primordia.entity.CreatureEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

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
		// Grazing owns where the creature walks and where it looks.
		setControls(EnumSet.of(Control.MOVE, Control.LOOK));
	}

	@Override
	public boolean canStart() {
		if (!creature.getDietGroup().eatsPlants()) return false;
		if (creature.isCarcass() || creature.isAsleep() || creature.isPosing()) return false;
		if (creature.getTarget() != null) return false;
		// Grazing now actually feeds the animal, so it is worth doing only when there is room to
		// eat. A full herbivore stops cropping and goes and does something else.
		if (!creature.isHungry()) return false;
		if (cooldown-- > 0) return false;
		cooldown = SEARCH_INTERVAL;

		// Omnivores graze, but less single-mindedly than dedicated herbivores.
		float appetite = creature.getDietGroup() == DietGroup.HERBIVORE ? 0.55f : 0.2f;
		if (creature.getRandom().nextFloat() > appetite) return false;

		target = findVegetation();
		return target != null;
	}

	@Override
	public boolean shouldContinue() {
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
			creature.getNavigation().startMovingTo(
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
	public boolean canStop() {
		// Let a half-finished mouthful be interrupted by anything more urgent.
		return true;
	}

	@Override
	public void tick() {
		if (target == null) return;

		// Aimed from the skull, not from the collision box's nominal eye. On a creature with any
		// neck at all those are far apart, and pitching the rendered head by an angle measured from
		// the shoulders leaves it visibly short of the plant it is supposed to be eating.
		creature.lookAtFromHead(
				target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);

		double distanceSq = creature.squaredDistanceTo(
				target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);

		if (distanceSq > REACH_SQ) {
			approach();
			return;
		}

		creature.getNavigation().stop();

		// Settle the aim before biting. The head turns at a bounded rate and the body follows it,
		// so arriving and immediately triggering the chew animation means the first mouthfuls
		// happen while the head is still swinging round from wherever it was walking.
		if (!aimed) {
			aimTicks++;
			if (isAimedAtTarget() || aimTicks > AIM_TIMEOUT) {
				aimed = true;
			} else {
				return;
			}
		}

		chewTicks++;
		// Re-trigger periodically so the timed activity does not lapse mid-meal.
		if (chewTicks % 20 == 1) {
			creature.triggerActivity(CreatureActivity.GRAZE);
		}

		// One mouthful per chew cycle: the plant is eaten, the animal is fed, and the region's
		// standing vegetation goes down by what was taken. That last part is what closes the
		// boom-bust loop — a herd that overgrazes a valley lowers the carrying capacity that
		// decides how many of them it can support, and the crash is nobody's design.
		BodyPlan plan = creature.getBodyPlan();
		if (plan == null || chewTicks % 20 != 10) return;

		creature.addEnergy(EnergyBudget.mouthfulValue(plan));
		if (creature.getWorld() instanceof ServerWorld world && WorldImpact.graze(world, target)) {
			// That mouthful is gone; find the next one rather than chewing on air.
			target = findVegetation();
			if (target == null) {
				chewTicks = 999;
			} else {
				// A new block means a new direction to face, so the aim has to be earned again.
				aimed = false;
				aimTicks = 0;
				approachTicks = 0;
			}
		}
	}

	/**
	 * Walks toward the mouthful, and gives up on it if that is not working.
	 * <p>
	 * Two ways to fail, and both matter. The navigator refusing to produce a path is the common
	 * one — leaves in a canopy have nothing under them to stand on — and it is reported
	 * immediately. The timeout catches the rest: a path that exists but does not arrive, because
	 * something is in the way or the creature is being pushed off it.
	 */
	private void approach() {
		approachTicks++;
		if (approachTicks > APPROACH_TIMEOUT) {
			giveUp();
			return;
		}
		if (!creature.getNavigation().isIdle()) return;

		boolean pathed = creature.getNavigation().startMovingTo(
				target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0);
		if (!pathed) giveUp();
	}

	/** Abandons the current mouthful and sits the goal out, rather than immediately re-picking it. */
	private void giveUp() {
		target = null;
		cooldown = GIVE_UP_COOLDOWN;
	}

	/** Whether the head has actually come round onto the target yet. */
	private boolean isAimedAtTarget() {
		double dx = target.getX() + 0.5 - creature.getX();
		double dz = target.getZ() + 0.5 - creature.getZ();
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		if (horizontal < 1.0e-4) return true;

		float wantYaw = (float) (MathHelper.atan2(dz, dx) * MathHelper.DEGREES_PER_RADIAN) - 90f;
		if (Math.abs(MathHelper.wrapDegrees(creature.getHeadYaw() - wantYaw)) > AIM_TOLERANCE_YAW) {
			return false;
		}

		double dy = target.getY() + 0.5 - creature.getHeadY();
		float wantPitch = (float) (-MathHelper.atan2(dy, horizontal) * MathHelper.DEGREES_PER_RADIAN);
		return Math.abs(MathHelper.wrapDegrees(creature.getPitch() - wantPitch)) <= AIM_TOLERANCE_PITCH;
	}

	/**
	 * Nearest grass, flower or leaf block this particular creature could actually get its mouth to.
	 * <p>
	 * The vertical window is the animal's own, so a browser and a cropper looking at the same wood
	 * see different food in it.
	 */
	private BlockPos findVegetation() {
		World world = creature.getWorld();
		BlockPos origin = creature.getBlockPos();
		int reachUp = browseHeight();
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;

		for (BlockPos pos : BlockPos.iterate(
				origin.add(-SEARCH_RADIUS, -REACH_BELOW, -SEARCH_RADIUS),
				origin.add(SEARCH_RADIUS, reachUp, SEARCH_RADIUS))) {
			if (!isEdible(world, pos)) continue;
			double d = pos.getSquaredDistance(creature.getPos());
			if (d < bestDistance) {
				bestDistance = d;
				best = pos.toImmutable();
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
		return MathHelper.clamp((int) Math.floor(head + 0.75), 1, 6);
	}

	private static boolean isEdible(World world, BlockPos pos) {
		var state = world.getBlockState(pos);
		return state.isIn(BlockTags.REPLACEABLE_BY_TREES)
				|| state.isIn(BlockTags.FLOWERS)
				|| state.isIn(BlockTags.LEAVES);
	}
}
