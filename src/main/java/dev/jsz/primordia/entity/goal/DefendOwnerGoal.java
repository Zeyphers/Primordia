package dev.jsz.primordia.entity.goal;

import dev.jsz.primordia.entity.CreatureEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import net.minecraft.world.entity.player.Player;

import java.util.EnumSet;

/**
 * Makes a domesticated creature fight on its owner's behalf, covering both halves of the wolf
 * contract in one goal: it goes after whatever hurt the owner, and it joins in on whatever the
 * owner is hitting.
 * <p>
 * The two cases need different triggers. Retaliation is continuous — anything currently attacking
 * the owner is fair game for as long as it keeps at it. Assistance has to be edge-triggered on the
 * owner's attack counter instead, or the creature would re-acquire a target the owner has since
 * deliberately walked away from and drag the fight back.
 */
public class DefendOwnerGoal extends TargetGoal {
	private final CreatureEntity creature;
	private LivingEntity candidate;
	/** The owner's attack counter as of the last assist, so each swing only recruits once. */
	private int lastAssistedAttackTime;

	public DefendOwnerGoal(CreatureEntity creature) {
		super(creature, false);
		this.creature = creature;
		setFlags(EnumSet.of(Flag.TARGET));
	}

	@Override
	public boolean canUse() {
		if (!creature.isDomesticated() || creature.isSitting()) return false;

		LivingEntity owner = creature.getOwner();
		if (owner == null) return false;

		// Whatever is hitting the owner, first and continuously.
		LivingEntity attacker = owner.getLastHurtByMob();
		if (isValidTarget(attacker, owner)) {
			candidate = attacker;
			return true;
		}

		// Otherwise join whatever the owner has just swung at.
		LivingEntity attacking = owner.getLastHurtMob();
		int attackTime = owner.getLastHurtMobTimestamp();
		if (attackTime != lastAssistedAttackTime && isValidTarget(attacking, owner)) {
			candidate = attacking;
			lastAssistedAttackTime = attackTime;
			return true;
		}

		return false;
	}

	/**
	 * Excludes the owner, other players, and anything else the same player has domesticated —
	 * a pack that turns on itself the moment the owner clips one of them is not a pack.
	 */
	private boolean isValidTarget(LivingEntity target, LivingEntity owner) {
		if (target == null || target == creature || target == owner) return false;
		if (!target.isAlive()) return false;
		if (target instanceof Player) return false;
		if (target instanceof CreatureEntity other
				&& other.isDomesticated()
				&& owner.getUUID().equals(other.getOwnerUuid())) {
			return false;
		}
		return creature.canAttack(target);
	}

	@Override
	public void start() {
		creature.setTarget(candidate);
		super.start();
	}
}
