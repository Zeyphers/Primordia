package dev.jsz.primordia.body;

import dev.jsz.primordia.genome.Gene;

/**
 * How a creature fights, <b>derived from its body rather than assigned to it</b>.
 * <p>
 * This is the same principle as the rest of the mod: nothing about a creature is authored, so a
 * lookup table of "species → attack" would be the one hand-written exception. Instead the body
 * plan is inspected and the attack that its anatomy actually affords is chosen — a creature with
 * arms swipes because it has arms, a heavy-tailed one slams because the tail is a usable club, a
 * big-jawed one bites. Evolve the tail away and the lineage stops tail-slamming on its own.
 */
public enum AttackStyle {
	/** Lunge and snap with the head. The default for anything with a usable jaw. */
	BITE,
	/** Swipe across the body with a forelimb. Requires arms. */
	CLAW,
	/** Whip the tail sideways. Requires a long, heavy tail. */
	TAIL_SLAM,
	/** Charge head-down. Suits short-necked, armoured or crested heads. */
	RAM,
	/** Heavy, tall creatures stomp down with a front foot. */
	STOMP;

	public static AttackStyle forPlan(BodyPlan plan) {
		float size = Math.max(0.1f, plan.bodyLength);

		// Arms are specialised manipulators; if a creature grew them, they are the obvious weapon.
		if (plan.arms.length > 0) {
			for (LimbChain arm : plan.arms) {
				if (arm.totalLength > size * 0.28f) return CLAW;
			}
		}

		// A tail is only a weapon if it is both long enough to swing and massive enough to land.
		float tailLength = 0f;
		float tailBulk = 0f;
		for (BoneDef bone : plan.bones) {
			if (bone.feature != Feature.TAIL) continue;
			tailLength += bone.length();
			tailBulk = Math.max(tailBulk, bone.radiusHead);
		}
		if (tailLength > size * 0.55f && tailBulk > plan.minLimbRadius * 2.2f) {
			return TAIL_SLAM;
		}

		// A short neck puts the skull right behind the shoulders, which is what makes ramming
		// survivable; dorsal armour points the same way.
		float neckLength = 0f;
		for (BoneDef bone : plan.bones) {
			if (bone.name.startsWith("neck")) neckLength += bone.length();
		}
		// Past Gene.DORSAL_SPINES.threshold there are spines; this asks the harder question of whether
		// the back is plated enough to charge behind, so it keeps its own cut.
		boolean armoured = plan.genome.expresses(Gene.DORSAL_SPINES, 0.62f)
				|| plan.genome.raw(Gene.ARMOR) > 0.65f;
		if (neckLength < size * 0.18f && armoured) {
			return RAM;
		}

		return BITE;
	}
}
