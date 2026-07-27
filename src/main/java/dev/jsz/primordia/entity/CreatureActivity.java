package dev.jsz.primordia.entity;

/**
 * What a creature is currently doing, replicated to clients so the animator can pose accordingly.
 * <p>
 * Ordinals are the wire format — append only, never reorder.
 * <p>
 * {@link #IDLE} and {@link #WALK} are ambient states the entity recomputes every tick from its own
 * motion. Everything else is a timed one-shot triggered by a goal, which reverts automatically
 * when its duration runs out, so no goal has to remember to clear it.
 */
public enum CreatureActivity {
	IDLE(0),
	WALK(0),
	/** Head down, cropping vegetation. Herbivores and omnivores. */
	GRAZE(70),
	/** Lunge and snap. */
	BITE(14),
	/** Forelimb swipe across the body. */
	CLAW(16),
	/** Sideways tail whip. */
	TAIL_SLAM(20),
	/** Head-down charge. */
	RAM(18),
	/** Foreleg raised high and slammed down. */
	STOMP(24);

	public static final CreatureActivity[] VALUES = values();

	/** Duration in ticks; zero means an ambient state with no timeout. */
	public final int durationTicks;

	CreatureActivity(int durationTicks) {
		this.durationTicks = durationTicks;
	}

	public boolean isAmbient() {
		return durationTicks == 0;
	}

	public boolean isAttack() {
		return this == BITE || this == CLAW || this == TAIL_SLAM || this == RAM;
	}

	public static CreatureActivity byId(int id) {
		return id >= 0 && id < VALUES.length ? VALUES[id] : IDLE;
	}
}
