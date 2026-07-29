package dev.jsz.primordia.entity.goal;

import dev.jsz.primordia.ecology.EnergyBudget;
import dev.jsz.primordia.entity.CreatureEntity;
import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Genome;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.util.math.Box;

import java.util.EnumSet;

/**
 * Sends a creature to sleep through the inactive half of its daily cycle.
 * <p>
 * This is the cheapest population brake in the whole ecology and the one that reads most clearly as
 * behaviour rather than as balancing. A predator that is asleep for half the day is a predator not
 * hunting for half the day, and it costs one boolean — where the alternative, tuning hunger rates
 * until the numbers work out, would regulate the same thing invisibly.
 * <p>
 * {@link Gene#NOCTURNALITY} decides which half. A population that splits across that locus splits
 * its own predation pressure with it, because a nocturnal hunter and a diurnal grazer barely meet —
 * which is a real niche appearing out of one gene and no special-case code.
 * <p>
 * Hunger overrides sleep. An animal that has not eaten does not lie down, so this can never become
 * the reason something starves.
 */
public class RestGoal extends Goal {
	/** Ticks of a Minecraft day. */
	private static final int DAY = 24000;
	/** Sunset and sunrise, in day ticks. Night is the interval between them. */
	private static final int DUSK = 12500;
	private static final int DAWN = 23000;

	/**
	 * Largest shift either way on an individual's dusk and dawn, in ticks.
	 * <p>
	 * Twenty seconds. A herd still visibly settles one animal at a time rather than dropping in
	 * unison, but the whole population is down well inside a minute of the hour arriving — which
	 * matters because {@code /time set night} is how anyone actually checks this, and a stagger
	 * measured in minutes is indistinguishable from the feature not working.
	 */
	private static final int SCHEDULE_JITTER = 400;

	/** Radius within which an approaching threat will wake a sleeping creature. */
	private static final double ALERT_RANGE = 10.0;
	/**
	 * Ticks a newly placed creature must be awake before it may rest.
	 * <p>
	 * Only there so a player walking into freshly materialised terrain does not find a field of
	 * animals that were asleep from the instant they existed. Five seconds is enough to read as
	 * "they were up and then they settled"; it was half a minute, which is long enough to look like
	 * the schedule is ignoring the clock.
	 */
	private static final int SETTLE_TICKS = 100;
	/** Re-check interval while awake. One second, so a change of hour is acted on promptly. */
	private static final int CHECK_INTERVAL = 20;
	/** Re-check interval while asleep. Half a second is well inside a predator's approach. */
	private static final int ALERT_CHECK_INTERVAL = 10;

	private final CreatureEntity creature;
	private int cooldown;

	public RestGoal(CreatureEntity creature) {
		this.creature = creature;
		setControls(EnumSet.of(Control.MOVE, Control.LOOK, Control.JUMP));
	}

	@Override
	public boolean canStart() {
		if (cooldown-- > 0) return false;
		cooldown = CHECK_INTERVAL;

		if (creature.isCarcass() || creature.isPosing()) return false;
		// A companion keeps its owner's hours, not its own.
		if (creature.isDomesticated()) return false;
		// A creature that has just been placed into the world by the region materialiser should be
		// seen to be alive before it lies down. Otherwise a player arriving in a new area finds a
		// field of animals that were asleep from the instant they existed, which reads as broken
		// rather than as nocturnal.
		if (creature.getLifeTicks() < SETTLE_TICKS) return false;
		if (creature.getTarget() != null || creature.getAttacker() != null) return false;
		if (creature.getControllingPassenger() != null) return false;
		// Only starvation beats sleep. This was FORAGE_THRESHOLD, which energy drops below within
		// minutes of spawning and rarely climbs back above — so in practice nothing ever slept and
		// the world clock appeared to have no effect at all. See EnergyBudget#REST_THRESHOLD.
		if (creature.getEnergy() < EnergyBudget.REST_THRESHOLD) return false;
		if (!isRestPeriod()) return false;
		return !threatNearby();
	}

	@Override
	public boolean shouldContinue() {
		if (creature.getAttacker() != null || creature.getTarget() != null) return false;
		// Waking up starving is worse than losing sleep. Lower than the bar for lying down, so a
		// creature hovering at the threshold does not flicker in and out of sleep.
		if (creature.getEnergy() <= EnergyBudget.WAKE_HUNGRY) return false;
		if (!isRestPeriod()) return false;
		// Throttled: this runs every tick for every sleeping creature, and a box query per animal
		// per tick is a real cost for a check whose answer cannot change meaningfully in half a
		// second. The cheap conditions above stay unthrottled because they are field reads.
		if (creature.age % ALERT_CHECK_INTERVAL != 0) return true;
		return !threatNearby();
	}

	@Override
	public void start() {
		creature.getNavigation().stop();
		creature.setAsleep(true);
	}

	@Override
	public void stop() {
		creature.setAsleep(false);
		cooldown = CHECK_INTERVAL;
	}

	@Override
	public boolean canStop() {
		return true;
	}

	@Override
	public void tick() {
		// Sleeping is the absence of doing anything; holding the navigation stopped is the whole
		// behaviour. Energy drain drops to the resting multiplier in CreatureEntity#tickEnergy.
		creature.getNavigation().stop();
	}

	private boolean isRestPeriod() {
		Genome g = creature.getGenome();
		if (g == null) return false;
		return isRestingHour(creature.getWorld().getTimeOfDay(),
				g.raw(Gene.NOCTURNALITY), g.seed());
	}

	/**
	 * Whether the world clock currently says this animal should be asleep.
	 * <p>
	 * A pure function of the time of day — <b>not</b> of anything the creature has been counting.
	 * {@code getTimeOfDay} is the same value the sun is drawn from and the same one {@code /time set}
	 * writes, so moving the world to night moves every night-sleeping creature into its rest period
	 * on the same tick, with no per-entity clock to catch up first.
	 * <p>
	 * Each animal gets a small offset on its dusk and dawn, derived from its structural seed. Without
	 * it every member of a lineage shares one {@link Gene#NOCTURNALITY} value and therefore one
	 * schedule, so an entire herd lies down on the same tick and gets up on the same tick — which
	 * does not read as animals sleeping, it reads as the game having frozen them.
	 *
	 * @param timeOfDay    world time; only its position within the day matters
	 * @param nocturnality {@link Gene#NOCTURNALITY}, above 0.5 meaning awake at night
	 * @param seed         the individual's structural seed, so siblings stagger
	 */
	public static boolean isRestingHour(long timeOfDay, float nocturnality, long seed) {
		long time = Math.floorMod(timeOfDay, DAY);
		int offset = (int) Math.floorMod(seed >> 12, SCHEDULE_JITTER * 2L) - SCHEDULE_JITTER;
		boolean night = time >= DUSK + offset && time < DAWN + offset;
		return (nocturnality > 0.5f) != night;
	}

	/**
	 * Whether anything that might eat this creature is close enough to matter. Checked on the way
	 * in and every tick on the way out, because a herd that sleeps through a predator walking into
	 * the middle of it is not asleep, it is unconscious.
	 */
	private boolean threatNearby() {
		var mine = creature.getBodyPlan();
		if (mine == null) return false;
		Box box = creature.getBoundingBox().expand(ALERT_RANGE, 5.0, ALERT_RANGE);
		return !creature.getWorld().getEntitiesByClass(CreatureEntity.class, box,
				other -> other != creature && other.isAlive() && !other.isCarcass()
						&& !other.isAsleep()
						&& other.getDietGroup().hunts()
						&& other.getBodyPlan() != null
						&& other.getBodyPlan().mass > mine.mass * 1.1f).isEmpty();
	}
}
