package dev.jsz.primordia.ecology;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Genome;

/**
 * The energy economy every ecological behaviour is gated on.
 * <p>
 * A creature carries one scalar, {@code energy} in [0,1], which drains continuously and is refilled
 * by eating. Hunting, foraging, resting and breeding all read it, which is what closes the predation
 * loop: before this existed a carnivore's targeting goal had no condition except that the other
 * animal was smaller, so it killed every herbivore it could reach and then kept going. A predator
 * that has eaten is not a predator.
 * <p>
 * The numbers are expressed against the Minecraft day (24000 ticks) rather than in absolute units,
 * because that is the clock the player reads the world by — "it hunts about once a day" is a
 * statement someone can check, and one that survives a change to tick rate.
 * <p>
 * <b>Mass is paid for here.</b> Drain scales with body mass, and a meal is worth less to a larger
 * animal, so a giant needs more food from the same landscape. That pressure used to be a separate
 * rule — {@code tickNourishment} dealt starvation damage above a carrying capacity — and is now a
 * consequence of the mechanism instead: a big animal in a barren place cannot find enough to eat,
 * which is the same outcome arrived at honestly.
 */
public final class EnergyBudget {
	/** Ticks in a Minecraft day. Every rate below is expressed against this. */
	private static final float DAY = 24000f;

	/** Below this, a hunter will start looking for something to kill. */
	public static final float HUNT_THRESHOLD = 0.58f;
	/** Below this, a plant-eater will go and find vegetation, and a scavenger will take a carcass. */
	public static final float FORAGE_THRESHOLD = 0.82f;
	/** Above this, and mature, a creature will look for a mate. */
	public static final float BREED_THRESHOLD = 0.74f;
	/** What producing offspring costs each parent. Reproduction is expensive or it is not a choice. */
	public static final float BREED_COST = 0.32f;
	/** Energy spent on a chase that caught nothing. Failing to hunt has to hurt. */
	public static final float FAILED_HUNT_COST = 0.06f;

	/**
	 * Largest prey a hunter will take on, as a fraction of its own mass. Above this the target is
	 * too close to its own size to be worth the risk.
	 */
	public static final float MAX_PREY_MASS_RATIO = 0.85f;

	/**
	 * Smallest prey worth chasing, as a fraction of the hunter's own mass.
	 * <p>
	 * Without a floor here, a large predator in a field of very small animals never fills up — one
	 * of them is worth a few percent of its appetite — so it kills continuously and is still
	 * hungry at the end of it. That is the original runaway predation bug arriving through the
	 * arithmetic rather than through the targeting logic, and it is invisible in the source: every
	 * individual decision looks correct.
	 * <p>
	 * Optimal foraging gives the honest version of the rule — a hunt that cannot repay its own cost
	 * is not worth starting — and the side effect is the more valuable part: small animals are
	 * simply not on the menu of large ones. That size refuge is one of the main things that keeps
	 * real ecosystems from collapsing into their largest predator, and here it falls out of one
	 * constant rather than needing a rule of its own.
	 */
	public static final float MIN_PREY_MASS_RATIO = 0.25f;

	/** Energy at which a creature begins taking starvation damage. */
	public static final float STARVING = 0.0f;

	/** Whether prey of this mass is worth a hunter of that mass pursuing. */
	public static boolean isWorthHunting(float hunterMass, float preyMass) {
		return preyMass < hunterMass * MAX_PREY_MASS_RATIO
				&& preyMass >= hunterMass * MIN_PREY_MASS_RATIO;
	}
	/** Damage per starvation tick, applied once a creature is empty. */
	public static final float STARVATION_DAMAGE = 1.0f;

	/**
	 * Fraction of a day an idle, average-metabolism, small creature takes to empty. Slow enough
	 * that a well-fed animal spends most of its time doing something other than eating, which is
	 * what makes the eating legible when it happens.
	 */
	private static final float IDLE_DAYS_TO_EMPTY = 1.6f;

	private EnergyBudget() {
	}

	/**
	 * Energy burned this tick.
	 * <p>
	 * Three multipliers: {@link Gene#METABOLISM} as the individual's own burn rate, body mass
	 * because bulk costs upkeep, and activity because moving is more expensive than standing. A
	 * resting animal is deliberately much cheaper — sleeping through the unproductive half of the
	 * day is a real strategy, and {@link Gene#NOCTURNALITY} is what decides which half that is.
	 */
	public static float drainPerTick(Genome genome, BodyPlan plan, Activity activity) {
		float metabolism = 0.55f + 1.1f * genome.raw(Gene.METABOLISM);
		float bulk = 1f + plan.mass * 0.55f;
		return (1f / (DAY * IDLE_DAYS_TO_EMPTY)) * metabolism * bulk * activity.multiplier;
	}

	/** What a creature is doing, for the purpose of what it costs. */
	public enum Activity {
		RESTING(0.35f),
		IDLE(1.0f),
		MOVING(2.1f),
		SPRINTING(3.4f);

		final float multiplier;

		Activity(float multiplier) {
			this.multiplier = multiplier;
		}
	}

	/**
	 * How much food a carcass of this body holds, in absolute units.
	 * <p>
	 * Absolute rather than normalised because the same carcass has to be worth more to a small
	 * scavenger than to a large one — that asymmetry is the whole reason a big predator cannot live
	 * on small prey, and it falls out of dividing by the eater's own mass in
	 * {@link #energyPerNutrition}.
	 */
	public static float carcassNutrition(BodyPlan plan) {
		return Math.max(0.02f, plan.mass);
	}

	/**
	 * Energy gained per unit of carcass nutrition eaten. Inversely proportional to the eater's mass:
	 * a rabbit fills a stoat and barely registers on a bear.
	 */
	public static float energyPerNutrition(BodyPlan eater) {
		return 1f / Math.max(0.05f, eater.mass * 0.55f);
	}

	/** Nutrition drawn from a carcass per tick of feeding. A full meal takes roughly ten seconds. */
	public static float feedRatePerTick(BodyPlan eater) {
		return Math.max(0.002f, eater.mass * 0.006f);
	}

	/**
	 * Energy from cropping one vegetation block.
	 * <p>
	 * Divided by mass, so a large herbivore has to clear a great deal more ground to stay fed. That
	 * is the term that will drive overgrazing once grazing actually consumes blocks, and it is why
	 * the biggest plant-eaters should end up in the most productive biomes without anything
	 * checking the biome.
	 */
	public static float mouthfulValue(BodyPlan eater) {
		return Math.min(0.5f, 0.075f / Math.max(0.06f, eater.mass * 1.6f));
	}

	/**
	 * How long a creature will pursue prey before giving it up, in ticks.
	 * <p>
	 * This is the single most important number for population stability. A chase that always ends
	 * in a kill makes predation a function of encounter rate alone, and encounter rate in a small
	 * loaded area is high — which is exactly how a valley got stripped. Bounded pursuit means fast
	 * or enduring prey genuinely escape, so {@link Gene#SPEED} and {@link Gene#STAMINA} become
	 * things worth having rather than cosmetic loci.
	 */
	public static int chaseBudgetTicks(Genome genome) {
		return Math.round(40f + 150f * genome.raw(Gene.STAMINA));
	}

	/** Ticks a hunter waits after a failed chase before it will consider hunting again. */
	public static int failedHuntCooldown(Genome genome) {
		return Math.round(160f + 260f * (1f - genome.raw(Gene.AGGRESSION)));
	}

	/**
	 * Ticks from birth to sexual maturity, from {@link Gene#MATURATION_RATE}.
	 * <p>
	 * Short by real-animal standards — half an in-game day to two days — because selection has to be
	 * legible inside a play session. `ROADMAP.md` sets the target at a visible trait shift within
	 * one to two in-game days, and generation length is the term that decides whether that is
	 * possible at all.
	 */
	public static int maturityTicks(Genome genome) {
		return Math.round(DAY * (0.5f + 1.5f * (1f - genome.raw(Gene.MATURATION_RATE))));
	}

	/** Ticks a creature must wait between broods, from {@link Gene#FECUNDITY}. */
	public static int breedingInterval(Genome genome) {
		return Math.round(DAY * (0.4f + 1.6f * (1f - genome.raw(Gene.FECUNDITY))));
	}
}
