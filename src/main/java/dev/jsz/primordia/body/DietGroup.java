package dev.jsz.primordia.body;

import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Genome;

/**
 * Coarse feeding guild, banded from the continuous {@link Gene#DIET} locus.
 * <p>
 * Diet is deliberately a gradient in the genome rather than a category — the interesting animals
 * sit in the middle — but behaviour and animation need discrete branches, so the bands exist only
 * here at the edge of the system. A creature near a boundary genuinely is an ambiguous case, and
 * drifting across one over generations is a real evolutionary event.
 */
public enum DietGroup {
	HERBIVORE,
	OMNIVORE,
	CARNIVORE;

	public static DietGroup of(Genome genome) {
		float diet = genome.raw(Gene.DIET);
		if (diet < 0.35f) return HERBIVORE;
		if (diet < 0.65f) return OMNIVORE;
		return CARNIVORE;
	}

	public boolean eatsPlants() {
		return this != CARNIVORE;
	}

	public boolean hunts() {
		return this != HERBIVORE;
	}
}
