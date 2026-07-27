package dev.jsz.primordia.entity;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.DietGroup;
import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Genome;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.MathHelper;

/**
 * Derives a unique procedural voice profile (ambient, hurt, death, attack growl, mating call, pitch, volume)
 * from a creature's genome and morphology.
 */
public record VocalProfile(
		SoundEvent ambientSound,
		SoundEvent hurtSound,
		SoundEvent deathSound,
		SoundEvent attackGrowl,
		SoundEvent matingCall,
		float pitch,
		float volume
) {
	public static VocalProfile create(Genome genome, BodyPlan plan) {
		if (genome == null || plan == null) {
			return new VocalProfile(
					SoundEvents.ENTITY_COW_AMBIENT,
					SoundEvents.ENTITY_COW_HURT,
					SoundEvents.ENTITY_COW_DEATH,
					SoundEvents.ENTITY_POLAR_BEAR_WARNING,
					SoundEvents.ENTITY_CAT_PURR,
					1.0f, 1.0f
			);
		}

		DietGroup diet = DietGroup.of(genome);
		float aggression = genome.raw(Gene.AGGRESSION);
		float mass = plan.mass;
		float hipHeight = plan.hipHeight;

		// 1. Extreme pitch warping: maps creature size to uncanny pitches (0.28f to 2.25f)
		float basePitch = 0.55f / (float) Math.pow(Math.max(0.15f, hipHeight * 0.65f), 0.60);
		float pitch = MathHelper.clamp(basePitch, 0.28f, 2.25f);

		// 2. Procedural volume: larger animals are louder.
		float volume = MathHelper.clamp(0.6f + (float) Math.log1p(mass) * 0.25f, 0.5f, 1.5f);

		// 3. Sound set selection using eldritch, alien, and insectoid sound events
		SoundEvent ambient, hurt, death, attack, mating;

		if (diet == DietGroup.CARNIVORE || aggression > 0.65f) {
			// Aggressive / Predator eldritch acoustic set
			if (mass > 4.0f) {
				ambient = SoundEvents.ENTITY_WARDEN_AMBIENT;
				hurt = SoundEvents.ENTITY_WARDEN_HURT;
				death = SoundEvents.ENTITY_WARDEN_DEATH;
				attack = SoundEvents.ENTITY_WARDEN_ROAR;
				mating = SoundEvents.ENTITY_WARDEN_HEARTBEAT;
			} else if (mass > 0.8f) {
				ambient = SoundEvents.ENTITY_PHANTOM_AMBIENT;
				hurt = SoundEvents.ENTITY_PHANTOM_HURT;
				death = SoundEvents.ENTITY_PHANTOM_DEATH;
				attack = SoundEvents.ENTITY_PHANTOM_BITE;
				mating = SoundEvents.ENTITY_ELDER_GUARDIAN_AMBIENT;
			} else {
				ambient = SoundEvents.ENTITY_ENDERMITE_AMBIENT;
				hurt = SoundEvents.ENTITY_ENDERMITE_HURT;
				death = SoundEvents.ENTITY_ENDERMITE_DEATH;
				attack = SoundEvents.ENTITY_SILVERFISH_HURT;
				mating = SoundEvents.ENTITY_SHULKER_AMBIENT;
			}
		} else if (diet == DietGroup.HERBIVORE) {
			// Herbivorous alien acoustic set
			if (mass > 5.0f) {
				ambient = SoundEvents.ENTITY_ELDER_GUARDIAN_AMBIENT;
				hurt = SoundEvents.ENTITY_ELDER_GUARDIAN_HURT;
				death = SoundEvents.ENTITY_ELDER_GUARDIAN_DEATH;
				attack = SoundEvents.ENTITY_GHAST_WARN;
				mating = SoundEvents.ENTITY_SHULKER_AMBIENT;
			} else if (mass > 1.0f) {
				ambient = SoundEvents.ENTITY_SHULKER_AMBIENT;
				hurt = SoundEvents.ENTITY_SHULKER_HURT;
				death = SoundEvents.ENTITY_SHULKER_DEATH;
				attack = SoundEvents.ENTITY_HOGLIN_ANGRY;
				mating = SoundEvents.ENTITY_SNIFFER_HAPPY;
			} else {
				ambient = SoundEvents.ENTITY_SILVERFISH_AMBIENT;
				hurt = SoundEvents.ENTITY_SPIDER_HURT;
				death = SoundEvents.ENTITY_SPIDER_DEATH;
				attack = SoundEvents.ENTITY_SILVERFISH_HURT;
				mating = SoundEvents.ENTITY_ENDERMITE_AMBIENT;
			}
		} else {
			// Omnivorous alien acoustic set
			if (mass > 2.5f) {
				ambient = SoundEvents.ENTITY_ZOGLIN_AMBIENT;
				hurt = SoundEvents.ENTITY_ZOGLIN_HURT;
				death = SoundEvents.ENTITY_ZOGLIN_DEATH;
				attack = SoundEvents.ENTITY_ZOGLIN_ANGRY;
				mating = SoundEvents.ENTITY_HOGLIN_AMBIENT;
			} else {
				ambient = SoundEvents.ENTITY_HOGLIN_AMBIENT;
				hurt = SoundEvents.ENTITY_HOGLIN_HURT;
				death = SoundEvents.ENTITY_HOGLIN_DEATH;
				attack = SoundEvents.ENTITY_HOGLIN_RETREAT;
				mating = SoundEvents.ENTITY_HOGLIN_AMBIENT;
			}
		}

		return new VocalProfile(ambient, hurt, death, attack, mating, pitch, volume);
	}
}
