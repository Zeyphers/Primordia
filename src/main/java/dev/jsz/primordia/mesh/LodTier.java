package dev.jsz.primordia.mesh;

/**
 * Level-of-detail policy. Everything expensive in this mod scales with the LOD tier: mesh
 * resolution, whether IK runs, and how often the animator updates.
 * <p>
 * Tiers are chosen per creature per frame from camera distance <i>and</i> a global frame budget,
 * so a screen full of creatures degrades gracefully rather than tanking the frame rate. The
 * budget is what makes "how many creatures can the world hold" a tunable number instead of an
 * architectural rewrite.
 */
public final class LodTier {
	public static final int NEAR = 0;
	public static final int MID = 1;
	public static final int FAR = 2;
	public static final int DISTANT = 3;
	public static final int COUNT = 4;

	/** Squared block distances at which each tier takes over. */
	private static final double[] DISTANCE_SQ = {12 * 12, 28 * 28, 56 * 56};

	/** Surface Nets cells along the longest axis, per tier. */
	private static final int[] RESOLUTION = {40, 26, 15, 9};

	/**
	 * Hard ceiling on cells per axis. {@link dev.jsz.primordia.mesh.MeshBaker} raises resolution
	 * above the tier value when a creature has limbs too thin to resolve otherwise, and this stops
	 * a hair-thin leg from demanding a grid that costs seconds to bake.
	 */
	public static final int MAX_RESOLUTION = 80;

	/**
	 * How many creatures may occupy each tier per frame. Overflow spills to the next tier down.
	 * Lower than the mesh resolutions might suggest, because quad count grows roughly with the
	 * square of resolution — the near tier is now ~2.4x the geometry per creature it was.
	 */
	private static final int[] BUDGET = {8, 18, 40, Integer.MAX_VALUE};

	private LodTier() {
	}

	public static int resolutionFor(int tier) {
		return RESOLUTION[Math.max(0, Math.min(tier, RESOLUTION.length - 1))];
	}

	/** True when this tier should run full IK; below it, limbs use a cheap canned cycle. */
	public static boolean usesInverseKinematics(int tier) {
		return tier <= MID;
	}

	/** Animation updates per second for this tier. NEAR animates every frame. */
	public static float updateHz(int tier) {
		return switch (tier) {
			case NEAR -> Float.MAX_VALUE;
			case MID -> 30f;
			case FAR -> 12f;
			default -> 5f;
		};
	}

	public static int budgetFor(int tier) {
		return BUDGET[Math.max(0, Math.min(tier, BUDGET.length - 1))];
	}

	/** Base tier from camera distance alone, before the frame budget is applied. */
	public static int fromDistance(double distanceSq) {
		for (int tier = 0; tier < DISTANCE_SQ.length; tier++) {
			if (distanceSq < DISTANCE_SQ[tier]) return tier;
		}
		return DISTANT;
	}

	/**
	 * Per-frame tier allocator. Reset once per frame, then queried per creature in
	 * front-to-back order; when a tier's budget is spent, creatures drop to the next one.
	 */
	public static final class Budget {
		private final int[] used = new int[COUNT];

		public void reset() {
			java.util.Arrays.fill(used, 0);
		}

		public int allocate(double distanceSq) {
			int tier = fromDistance(distanceSq);
			while (tier < DISTANT && used[tier] >= budgetFor(tier)) {
				tier++;
			}
			used[tier]++;
			return tier;
		}
	}
}
