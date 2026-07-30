package dev.jsz.primordia.sound;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.DietGroup;
import dev.jsz.primordia.entity.CreatureEntity;
import dev.jsz.primordia.genome.Gene;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.animal.cow.CowSoundVariants;
import net.minecraft.world.entity.animal.wolf.WolfSoundVariants;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;

/**
 * Computes dynamic pitch, volume, and sound events based on creature body mass, diet, and temperament.
 */
public final class CreatureSoundEngine {
	private CreatureSoundEngine() {
	}

	// 26.2 moved cow and wolf sounds out of flat SoundEvents fields and into per-variant sound
	// sets, so the ones this file wants now have to be read out of the classic variant. Everything
	// else it reaches for (ravager, hoglin, fox, goat, panda, spider, sniffer) is still a plain field.

	private static net.minecraft.world.entity.animal.cow.CowSoundVariant classicCow() {
		return SoundEvents.COW_SOUNDS.get(CowSoundVariants.SoundSet.CLASSIC);
	}

	private static SoundEvent classicWolfAmbient() {
		return SoundEvents.WOLF_SOUNDS.get(WolfSoundVariants.SoundSet.CLASSIC)
				.adultSounds().ambientSound().value();
	}

	public static float getPitch(CreatureEntity creature) {
		BodyPlan plan = creature.getBodyPlan();
		if (plan == null) return 1.0f;
		return Mth.clamp(1.5f - (plan.mass * 0.90f), 0.40f, 1.85f);
	}

	public static float getVolume(CreatureEntity creature) {
		BodyPlan plan = creature.getBodyPlan();
		if (plan == null) return 1.0f;
		return Mth.clamp(0.4f + plan.mass * 0.6f, 0.4f, 1.6f);
	}

	public static SoundEvent getAmbientSound(CreatureEntity creature) {
		BodyPlan plan = creature.getBodyPlan();
		if (plan == null) return classicCow().ambientSound().value();

		float diet = creature.getGenome() != null ? creature.getGenome().raw(Gene.DIET) : 0.5f;

		if (plan.mass > 1.2f) {
			if (diet > 0.6f) return SoundEvents.RAVAGER_AMBIENT;
			return SoundEvents.SNIFFER_SCENTING;
		} else if (plan.mass > 0.5f) {
			if (diet > 0.6f) return SoundEvents.HOGLIN_AMBIENT;
			if (diet < 0.35f) return SoundEvents.PANDA_AMBIENT;
			return classicWolfAmbient();
		} else if (plan.mass < 0.25f) {
			// Many-legged and small used to buzz like a bee. It is the one vanilla loop that reads
			// as a specific animal rather than as a texture of sound — a bee is unmistakably a bee,
			// so every insectoid in the mod sounded like the same borrowed creature. A spider's
			// rasp carries the same "small and many-legged" idea without naming something else.
			if (plan.legs.length >= 6) return SoundEvents.SPIDER_AMBIENT;
			return SoundEvents.FOX_AMBIENT;
		}

		return SoundEvents.GOAT_AMBIENT;
	}

	public static SoundEvent getHurtSound(CreatureEntity creature, DamageSource source) {
		BodyPlan plan = creature.getBodyPlan();
		if (plan == null) return classicCow().hurtSound().value();

		if (plan.mass > 1.0f) {
			return SoundEvents.RAVAGER_HURT;
		} else if (plan.mass > 0.4f) {
			return SoundEvents.HOGLIN_HURT;
		} else {
			return SoundEvents.FOX_HURT;
		}
	}

	public static SoundEvent getDeathSound(CreatureEntity creature) {
		BodyPlan plan = creature.getBodyPlan();
		if (plan == null) return classicCow().deathSound().value();

		if (plan.mass > 1.0f) {
			return SoundEvents.RAVAGER_DEATH;
		} else if (plan.mass > 0.4f) {
			return SoundEvents.HOGLIN_DEATH;
		} else {
			return SoundEvents.FOX_DEATH;
		}
	}
}
