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
import net.minecraft.world.World;

import java.util.EnumSet;

/**
 * Sends plant-eaters to find vegetation and crop it.
 * <p>
 * The eating itself is animation-only for now — nothing is consumed and no hunger is satisfied.
 * That arrives with the ecology milestone, at which point this goal becomes the hook the food web
 * hangs from; the search and approach logic here is what it will reuse.
 */
public class GrazeGoal extends Goal {
	private static final int SEARCH_RADIUS = 8;
	private static final int SEARCH_HEIGHT = 3;
	/** Squared distance at which the creature is close enough to start eating. */
	private static final double REACH_SQ = 2.2 * 2.2;
	/** Ticks between search attempts, so a creature with nothing to eat is not scanning constantly. */
	private static final int SEARCH_INTERVAL = 40;

	private final CreatureEntity creature;
	private BlockPos target;
	private int cooldown;
	private int chewTicks;

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
		if (target != null) {
			creature.getNavigation().startMovingTo(
					target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0);
		}
	}

	@Override
	public void stop() {
		target = null;
		chewTicks = 0;
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

		creature.getLookControl().lookAt(
				target.getX() + 0.5, target.getY(), target.getZ() + 0.5);

		double distanceSq = creature.squaredDistanceTo(
				target.getX() + 0.5, target.getY(), target.getZ() + 0.5);

		if (distanceSq > REACH_SQ) {
			if (creature.getNavigation().isIdle()) {
				creature.getNavigation().startMovingTo(
						target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0);
			}
			return;
		}

		creature.getNavigation().stop();
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
			if (target == null) chewTicks = 999;
		}
	}

	/** Nearest grass, flower or leaf block the creature could plausibly reach. */
	private BlockPos findVegetation() {
		World world = creature.getWorld();
		BlockPos origin = creature.getBlockPos();
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;

		for (BlockPos pos : BlockPos.iterate(
				origin.add(-SEARCH_RADIUS, -SEARCH_HEIGHT, -SEARCH_RADIUS),
				origin.add(SEARCH_RADIUS, SEARCH_HEIGHT, SEARCH_RADIUS))) {
			if (!isEdible(world, pos)) continue;
			double d = pos.getSquaredDistance(creature.getPos());
			if (d < bestDistance) {
				bestDistance = d;
				best = pos.toImmutable();
			}
		}
		return best;
	}

	private static boolean isEdible(World world, BlockPos pos) {
		var state = world.getBlockState(pos);
		return state.isIn(BlockTags.REPLACEABLE_BY_TREES)
				|| state.isIn(BlockTags.FLOWERS)
				|| state.isIn(BlockTags.LEAVES);
	}
}
