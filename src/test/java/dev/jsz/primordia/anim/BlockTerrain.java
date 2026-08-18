package dev.jsz.primordia.anim;

import dev.jsz.primordia.util.Noise;

/**
 * A blocky heightmap standing in for real generated terrain, plus the {@link GroundProbe} rules a
 * foot actually meets in game.
 * <p>
 * Deliberately <b>not</b> smooth. Every gait fault worth finding comes from the world being made of
 * whole blocks: a hillside is a staircase, a shoreline is a cliff, and a boulder field is a
 * scattering of one-block pillars. A continuous probe hides all of it — feet glide up a ramp and
 * every measurement comes back clean — which is why the flat and smooth stubs the animation tests
 * used until now never caught anything.
 * <p>
 * The probe reproduces the two rules {@link dev.jsz.primordia.client.WorldGroundProbe} enforces
 * against a live world: a surface more than a step above the creature's feet is a wall face and is
 * refused, and a surface further below than the drop limit is out of range. Both answer
 * {@link Float#NaN}, which is what sends the animator into its neighbour search. The column scan
 * itself collapses to a lookup here because a heightmap column is solid all the way down, so
 * headroom is decided by the top surface alone.
 */
public final class BlockTerrain implements GroundProbe {
	/** Mirrors {@code WorldGroundProbe.MAX_STEP_UP}. */
	public static final float MAX_STEP_UP = 1.3f;
	/** Mirrors {@code WorldGroundProbe.MAX_DROP}. */
	public static final float MAX_DROP = 4.0f;

	/** The shapes of ground a creature actually has to walk over. */
	public enum Kind {
		/** Control. Any fault that shows up here is not the terrain's doing. */
		FLAT,
		/** Gentle fbm hills, quantised to blocks: mostly flat with frequent one-block steps. */
		ROLLING,
		/** A regular staircase climbing one block every two, in +Z. */
		STAIRS,
		/** A single one-block ledge at z = 0 — the case the whole body tips over. */
		LEDGE,
		/** A three-block drop at z = 0, past what a leg can reach down to. */
		CLIFF,
		/** Scattered one-block pillars and pits: the worst case for per-foot height disagreement. */
		BOULDERS,
		/** Everything at once, the way a landscape actually arrives: hills, steps, ledges, rubble. */
		GENERATED
	}

	private final Kind kind;
	private final Noise noise;

	public BlockTerrain(Kind kind, long seed) {
		this.kind = kind;
		this.noise = new Noise(seed);
	}

	/** Y of the top surface of the column containing this block position. Always an integer. */
	public int columnTop(int bx, int bz) {
		return switch (kind) {
			case FLAT -> 0;
			case ROLLING -> Math.round(noise.fbm(bx * 0.09f, 0f, bz * 0.09f, 3, 2.1f, 0.5f) * 4.5f);
			case STAIRS -> Math.floorDiv(bz, 2);
			case LEDGE -> bz >= 0 ? 1 : 0;
			case CLIFF -> bz >= 0 ? 0 : 3;
			case BOULDERS -> {
				float n = noise.sample(bx * 0.7f, 0f, bz * 0.7f);
				yield n > 0.32f ? 1 : (n < -0.32f ? -1 : 0);
			}
			case GENERATED -> {
				int base = Math.round(noise.fbm(bx * 0.05f, 0f, bz * 0.05f, 4, 2.0f, 0.5f) * 7f);
				// Rubble on top of the landform, so slopes are broken rather than clean staircases.
				float detail = noise.sample(bx * 0.55f, 11f, bz * 0.55f);
				if (detail > 0.4f) base += 1;
				else if (detail < -0.45f) base -= 1;
				yield base;
			}
		};
	}

	/** Top surface under a continuous position, ignoring reach limits. The truth the feet aim at. */
	public float surfaceAt(double x, double z) {
		return columnTop((int) Math.floor(x), (int) Math.floor(z));
	}

	@Override
	public float groundY(double x, double z, double referenceY) {
		float surface = surfaceAt(x, z);
		// A surface above the step limit is a wall face from where the creature is standing; every
		// block beneath it in the same column is buried, so the whole column has no answer.
		if (surface > referenceY + MAX_STEP_UP) return Float.NaN;
		if (surface < referenceY - MAX_DROP) return Float.NaN;
		return surface;
	}

	/**
	 * Where a body of this width would rest: the highest surface under its footprint, which is what
	 * vanilla collision gives an entity standing astride a block edge.
	 */
	public float supportUnder(double x, double z, double halfWidth) {
		float best = -Float.MAX_VALUE;
		for (int i = -1; i <= 1; i++) {
			for (int j = -1; j <= 1; j++) {
				best = Math.max(best, surfaceAt(x + i * halfWidth, z + j * halfWidth));
			}
		}
		return best;
	}
}
