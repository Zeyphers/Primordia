package dev.jsz.primordia.entity;

import dev.jsz.primordia.body.DietGroup;
import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Genome;

/**
 * How a creature responds to being threatened, derived from its diet and disposition genes.
 * <p>
 * Deliberately three states rather than a simple hostile/passive split, because the interesting
 * case is the middle one: an animal that ignores you completely until you hit it, and then does
 * not stop. That is how most real large fauna behave, and it makes provoking something a decision
 * with consequences rather than a binary.
 */
public enum Temperament {
	/** Flees when hurt and keeps its distance afterward. Prey animals. */
	SKITTISH,
	/** Ignores you until provoked, then retaliates. Most large herbivores and omnivores. */
	DEFENSIVE,
	/** Hunts on sight, players included. Committed predators. */
	AGGRESSIVE;

	public static Temperament of(Genome genome) {
		DietGroup diet = DietGroup.of(genome);
		float aggression = genome.raw(Gene.AGGRESSION);
		float fear = genome.raw(Gene.FEAR);

		// Only a real carnivore with the disposition for it picks fights unprompted.
		if (diet == DietGroup.CARNIVORE && aggression > 0.55f) {
			return AGGRESSIVE;
		}
		// A bold omnivore, or anything large-tempered enough not to bolt, stands its ground.
		if (aggression > 0.45f && fear < 0.65f) {
			return DEFENSIVE;
		}
		// Timid by disposition, or simply not equipped to fight back.
		if (fear > 0.5f || diet == DietGroup.HERBIVORE) {
			return SKITTISH;
		}
		return DEFENSIVE;
	}

	public boolean fleesWhenHurt() {
		return this == SKITTISH;
	}

	public boolean retaliates() {
		return this != SKITTISH;
	}

	public boolean huntsUnprovoked() {
		return this == AGGRESSIVE;
	}
}
