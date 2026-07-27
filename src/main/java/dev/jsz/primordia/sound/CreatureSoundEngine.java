package dev.jsz.primordia.sound;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.DietGroup;
import dev.jsz.primordia.entity.CreatureEntity;
import dev.jsz.primordia.genome.Gene;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.MathHelper;

/**
 * Computes dynamic pitch, volume, and sound events based on creature body mass, diet, and temperament.
 */
public final class CreatureSoundEngine {
	private CreatureSoundEngine() {
	}

	public static float getPitch(CreatureEntity creature) {
		BodyPlan plan = creature.getBodyPlan();
		if (plan == null) return 1.0f;
		// Larger creatures have deeper vocal pitch (down to 0.4f), smaller creatures have higher pitch (up to 1.85f)
		return MathHelper.clamp(1.5f - (plan.mass * 0.90f), 0.40f, 1.85f);
	}

	public static float getVolume(CreatureEntity creature) {
		BodyPlan plan = creature.getBodyPlan();
		if (plan == null) return 1.0f;
		return MathHelper.clamp(0.4f + plan.mass * 0.6f, 0.4f, 1.6f);
	}

	public static SoundEvent getAmbientSound(CreatureEntity creature) {
		BodyPlan plan = creature.getBodyPlan();
		if (plan == null) return SoundEvents.ENTITY_COW_AMBIENT;

		float diet = creature.getGenome() != null ? creature.getGenome().raw(Gene.DIET) : 0.5f;

		if (plan.mass > 1.2f) {
			if (diet > 0.6f) return SoundEvents.ENTITY_RAVAGER_AMBIENT;
			return SoundEvents.ENTITY_SNIFFER_SCENTING;
		} else if (plan.mass > 0.5f) {
			if (diet > 0.6f) return SoundEvents.ENTITY_HOGLIN_AMBIENT;
			if (diet < 0.35f) return SoundEvents.ENTITY_PANDA_AMBIENT;
			return SoundEvents.ENTITY_WOLF_AMBIENT;
		} else if (plan.mass < 0.25f) {
			if (plan.legs.length >= 6) return SoundEvents.ENTITY_BEE_LOOP;
			return SoundEvents.ENTITY_FOX_AMBIENT;
		}

		return SoundEvents.ENTITY_GOAT_AMBIENT;
	}

	public static SoundEvent getHurtSound(CreatureEntity creature, DamageSource source) {
		BodyPlan plan = creature.getBodyPlan();
		if (plan == null) return SoundEvents.ENTITY_COW_HURT;

		if (plan.mass > 1.0f) {
			return SoundEvents.ENTITY_RAVAGER_HURT;
		} else if (plan.mass > 0.4f) {
			return SoundEvents.ENTITY_HOGLIN_HURT;
		} else {
			return SoundEvents.ENTITY_FOX_HURT;
		}
	}

	public static SoundEvent getDeathSound(CreatureEntity creature) {
		BodyPlan plan = creature.getBodyPlan();
		if (plan == null) return SoundEvents.ENTITY_COW_DEATH;

		if (plan.mass > 1.0f) {
			return SoundEvents.ENTITY_RAVAGER_DEATH;
		} else if (plan.mass > 0.4f) {
			return SoundEvents.ENTITY_HOGLIN_DEATH;
		} else {
			return SoundEvents.ENTITY_FOX_DEATH;
		}
	}
}
