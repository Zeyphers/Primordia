package dev.jsz.primordia.entity.goal;

import dev.jsz.primordia.body.AttackStyle;
import dev.jsz.primordia.entity.CreatureActivity;
import dev.jsz.primordia.entity.CreatureEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;

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

	public CreatureAttackGoal(CreatureEntity creature, double speed) {
		super(creature, speed, true);
		this.creature = creature;
	}

	@Override
	public boolean canStart() {
		return creature.getDietGroup().hunts() && super.canStart();
	}

	@Override
	public void tick() {
		super.tick();

		if (impactCountdown > 0) {
			impactCountdown--;
		} else if (impactCountdown == 0) {
			impactCountdown = -1;
			// Land the blow at the point in the animation where the jaw or claw arrives, not when
			// the swing began — otherwise damage visibly precedes the strike.
			if (pendingVictim != null && pendingVictim.isAlive()
					&& creature.squaredDistanceTo(pendingVictim) <= strikeRangeSq(pendingVictim) * 1.4) {
				creature.tryAttack(pendingVictim);
			}
			pendingVictim = null;
		}
	}

	@Override
	protected void attack(LivingEntity target) {
		if (!inStrikingRange(target)) return;
		if (impactCountdown >= 0) return;

		resetCooldown();
		AttackStyle style = creature.getAttackStyle();
		CreatureActivity activity = switch (style) {
			case CLAW -> CreatureActivity.CLAW;
			case TAIL_SLAM -> CreatureActivity.TAIL_SLAM;
			case RAM -> CreatureActivity.RAM;
			case BITE -> CreatureActivity.BITE;
			case STOMP -> CreatureActivity.STOMP;
		};
		creature.triggerActivity(activity);

		// Connect around halfway through the animation.
		impactCountdown = Math.max(1, activity.durationTicks / 2);
		pendingVictim = target;
	}

	private boolean inStrikingRange(LivingEntity target) {
		return creature.squaredDistanceTo(target) <= strikeRangeSq(target)
				&& creature.getVisibilityCache().canSee(target);
	}

	/**
	 * Squared melee reach. Vanilla's width-based formula is reproduced here rather than inherited,
	 * because the protected accessor for it has moved between Minecraft versions and this is both
	 * shorter and stable. Widened by the creature's own body length so that large animals are not
	 * forced to stand nose-to-nose before their jaws count as being in range.
	 */
	private double strikeRangeSq(LivingEntity target) {
		double reach = Math.min(3.5, 1.2 + creature.getWidth() * 0.45);
		return reach * reach + target.getWidth();
	}
}
