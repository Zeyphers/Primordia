package dev.jsz.primordia.entity.goal;

import dev.jsz.primordia.body.AttackStyle;
import dev.jsz.primordia.ecology.EnergyBudget;
import dev.jsz.primordia.entity.CreatureActivity;
import dev.jsz.primordia.entity.CreatureEntity;
import dev.jsz.primordia.genome.Genome;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;

/**
 * Melee attack that plays the animation the creature's anatomy actually affords, and lands the hit
 * partway through the swing rather than on the first frame.
 * <p>
 * Reach scales with the body: a long-necked biter can strike from further away than a short-armed
 * swiper, which falls out of the morphology instead of being a tuned constant per creature.
 */
public class CreatureAttackGoal extends MeleeAttackGoal {
	private final CreatureEntity creature;
	/** Ticks left before the current swing connects; -1 when no swing is in flight. */
	private int impactCountdown = -1;
	private LivingEntity pendingVictim;

	/** Ticks spent chasing without landing a blow. Reset by every hit that connects. */
	private int chaseTicks;
	/** How long this creature will pursue before giving up, from its own stamina. */
	private int chaseBudget;

	public CreatureAttackGoal(CreatureEntity creature, double speed) {
		super(creature, speed, true);
		this.creature = creature;
	}

	@Override
	public boolean canUse() {
		// A bonded creature fights whatever its owner is fighting regardless of what it eats.
		// Gating this on diet would mean a domesticated herbivore follows its owner into a fight
		// and then stands there, which reads as broken rather than as herbivorous.
		return (creature.isDomesticated() || creature.getDietGroup().hunts() || isDefendingItself())
				&& super.canUse();
	}

	/**
	 * Whether the creature's current target is the thing that attacked it.
	 * <p>
	 * Without this, a wild herbivore could never use this goal at all, and a {@code DEFENSIVE}
	 * herbivore — which is what a bold, unfearful plant-eater comes out as — retaliated only on paper:
	 * the target selector duly picked its attacker, and then no goal existed that would act on it, so
	 * it stood still and was eaten. Diet decides what an animal <i>hunts</i>; it has no business
	 * deciding whether it may defend itself.
	 * <p>
	 * Scoped to its attacker rather than to any target, so this stays a defence and does not quietly
	 * turn herbivores into predators through some other goal that happens to set a target.
	 */
	private boolean isDefendingItself() {
		LivingEntity target = creature.getTarget();
		if (target == null) return false;
		if (target instanceof net.minecraft.world.entity.monster.Monster) return true;
		return target == creature.getLastHurtByMob()
				&& (creature.getTemperament().retaliates() || target instanceof LivingEntity);
	}

	@Override
	public void start() {
		super.start();
		chaseTicks = 0;
		Genome g = creature.getGenome();
		chaseBudget = g == null ? 120 : EnergyBudget.chaseBudgetTicks(g);
	}

	@Override
	public void stop() {
		super.stop();
		chaseTicks = 0;
		impactCountdown = -1;
		pendingVictim = null;
	}

	@Override
	public void tick() {
		super.tick();

		if (giveUpIfOutrun()) return;

		if (impactCountdown > 0) {
			impactCountdown--;
		} else if (impactCountdown == 0) {
			impactCountdown = -1;
			// Land the blow at the point in the animation where the jaw or claw arrives, not when
			// the swing began — otherwise damage visibly precedes the strike.
			if (pendingVictim != null && pendingVictim.isAlive()
					&& creature.distanceToSqr(pendingVictim) <= strikeRangeSq(pendingVictim) * 1.4
					&& creature.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
				creature.doHurtTarget(serverLevel, pendingVictim);
			}
			pendingVictim = null;
		}
	}

	/**
	 * Abandons a pursuit that has gone on too long, and reports whether it did.
	 * <p>
	 * The single most important change to how predation behaves. An unbounded chase into vanilla's
	 * melee goal is a kill with a delay attached — the predator simply follows until it arrives —
	 * so predation rate was set by encounter rate alone, and in a loaded chunk that is very high.
	 * Bounding it means fast, enduring prey genuinely escape, so {@code SPEED} and {@code STAMINA}
	 * become traits worth selecting for on both sides of the chase.
	 * <p>
	 * The clock only runs while the predator is failing to connect. A fight it is winning is not a
	 * chase, and should not be interrupted halfway through.
	 */
	private boolean giveUpIfOutrun() {
		// A bonded creature fights until told otherwise; its owner is the one deciding, not its
		// stamina, and a companion that wanders off mid-fight is worse than useless.
		if (creature.isDomesticated()) return false;
		LivingEntity target = creature.getTarget();
		if (target == null) return false;

		if (++chaseTicks <= chaseBudget) return false;

		// A defence that has gone on too long is a fight the animal is losing, not a hunt it has
		// botched: charging it the failed-hunt energy cost and a hunt cooldown would punish it for
		// having been attacked. Dropping the target is enough — it stops pursuing, and whatever else
		// wants to run is then free to.
		if (isDefendingItself()) {
			creature.setTarget(null);
			return true;
		}

		creature.onHuntFailed();
		return true;
	}

	@Override
	protected void checkAndPerformAttack(LivingEntity target) {
		if (!inStrikingRange(target)) return;
		if (impactCountdown >= 0) return;

		chaseTicks = 0;
		resetAttackCooldown();
		AttackStyle style = creature.getAttackStyle();
		CreatureActivity activity = switch (style) {
			case CLAW -> CreatureActivity.CLAW;
			case TAIL_SLAM -> CreatureActivity.TAIL_SLAM;
			case RAM -> CreatureActivity.RAM;
			case BITE -> CreatureActivity.BITE;
			case STOMP -> CreatureActivity.STOMP;
		};
		creature.triggerActivity(activity);

		impactCountdown = Math.max(1, activity.durationTicks / 2);
		pendingVictim = target;
	}

	private boolean inStrikingRange(LivingEntity target) {
		return creature.distanceToSqr(target) <= strikeRangeSq(target)
				&& creature.getSensing().hasLineOfSight(target);
	}

	private double strikeRangeSq(LivingEntity target) {
		double reach = Math.min(3.5, 1.2 + creature.getBbWidth() * 0.45);
		double combinedRadius = (creature.getBbWidth() + target.getBbWidth()) * 0.5 + reach;
		return combinedRadius * combinedRadius;
	}
}
