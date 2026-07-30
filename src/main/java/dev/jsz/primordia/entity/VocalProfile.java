package dev.jsz.primordia.entity;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.DietGroup;
import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Genome;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;

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
					SoundEvents.SHEEP_AMBIENT,
					SoundEvents.SHEEP_HURT,
					SoundEvents.SHEEP_DEATH,
					SoundEvents.POLAR_BEAR_WARNING,
					SoundEvents.SHULKER_AMBIENT,
					1.0f, 1.0f
			);
		}

		DietGroup diet = DietGroup.of(genome);
		float aggression = genome.raw(Gene.AGGRESSION);
		float mass = plan.mass;
		float hipHeight = plan.hipHeight;

		// 1. Extreme pitch warping: maps creature size to uncanny pitches (0.28f to 2.25f)
		float basePitch = 0.55f / (float) Math.pow(Math.max(0.15f, hipHeight * 0.65f), 0.60);
		float pitch = Mth.clamp(basePitch, 0.28f, 2.25f);

		// 2. Procedural volume: larger animals are louder.
		float volume = Mth.clamp(0.6f + (float) Math.log1p(mass) * 0.25f, 0.5f, 1.5f);

		// 3. Sound set selection using eldritch, alien, and insectoid sound events
		SoundEvent ambient, hurt, death, attack, mating;

		if (diet == DietGroup.CARNIVORE || aggression > 0.65f) {
			// Aggressive / Predator eldritch acoustic set
			if (mass > 4.0f) {
				ambient = SoundEvents.WARDEN_AMBIENT;
				hurt = SoundEvents.WARDEN_HURT;
				death = SoundEvents.WARDEN_DEATH;
				attack = SoundEvents.WARDEN_ROAR;
				mating = SoundEvents.WARDEN_HEARTBEAT;
			} else if (mass > 0.8f) {
				ambient = SoundEvents.PHANTOM_AMBIENT;
				hurt = SoundEvents.PHANTOM_HURT;
				death = SoundEvents.PHANTOM_DEATH;
				attack = SoundEvents.PHANTOM_BITE;
				mating = SoundEvents.ELDER_GUARDIAN_AMBIENT;
			} else {
				ambient = SoundEvents.ENDERMITE_AMBIENT;
				hurt = SoundEvents.ENDERMITE_HURT;
				death = SoundEvents.ENDERMITE_DEATH;
				attack = SoundEvents.SILVERFISH_HURT;
				mating = SoundEvents.SHULKER_AMBIENT;
			}
		} else if (diet == DietGroup.HERBIVORE) {
			// Herbivorous alien acoustic set
			if (mass > 5.0f) {
				ambient = SoundEvents.ELDER_GUARDIAN_AMBIENT;
				hurt = SoundEvents.ELDER_GUARDIAN_HURT;
				death = SoundEvents.ELDER_GUARDIAN_DEATH;
				attack = SoundEvents.GHAST_WARN;
				mating = SoundEvents.SHULKER_AMBIENT;
			} else if (mass > 1.0f) {
				ambient = SoundEvents.SHULKER_AMBIENT;
				hurt = SoundEvents.SHULKER_HURT;
				death = SoundEvents.SHULKER_DEATH;
				attack = SoundEvents.HOGLIN_ANGRY;
				mating = SoundEvents.SNIFFER_HAPPY;
			} else {
				ambient = SoundEvents.SILVERFISH_AMBIENT;
				hurt = SoundEvents.SPIDER_HURT;
				death = SoundEvents.SPIDER_DEATH;
				attack = SoundEvents.SILVERFISH_HURT;
				mating = SoundEvents.ENDERMITE_AMBIENT;
			}
		} else {
			// Omnivorous alien acoustic set
			if (mass > 2.5f) {
				ambient = SoundEvents.ZOGLIN_AMBIENT;
				hurt = SoundEvents.ZOGLIN_HURT;
				death = SoundEvents.ZOGLIN_DEATH;
				attack = SoundEvents.ZOGLIN_ANGRY;
				mating = SoundEvents.HOGLIN_AMBIENT;
			} else {
				ambient = SoundEvents.HOGLIN_AMBIENT;
				hurt = SoundEvents.HOGLIN_HURT;
				death = SoundEvents.HOGLIN_DEATH;
				attack = SoundEvents.HOGLIN_RETREAT;
				mating = SoundEvents.HOGLIN_AMBIENT;
			}
		}

		return new VocalProfile(ambient, hurt, death, attack, mating, pitch, volume);
	}
}
