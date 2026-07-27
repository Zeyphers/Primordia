package dev.jsz.primordia.mesh;

/**
 * Level-of-detail policy. Everything expensive in this mod scales with the LOD tier: mesh
 * resolution, whether IK runs, and how often the animator updates.
 * <p>
 * Tiers are chosen per creature per frame from camera distance <i>and</i> a global frame budget,
 * so a screen full of creatures degrades gracefully rather than tanking the frame rate. The
 * budget is what makes "how many creatures can the world hold" a tunable number instead of an
 * architectural rewrite.
 * <p>
 * The numbers are settable at runtime by the client's quality settings rather than being
 * compile-time constants. They are deliberately plain statics with no dependency on the config
 * class: this package is common code that also runs on the server and under test, so it must not
 * reach into client-only classes. The client pushes values in; nothing here pulls them out.
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
	 * How many creatures may occupy each tier per frame. Overflow spills to the next tier down.
	 * Lower than the mesh resolutions might suggest, because quad count grows roughly with the
	 * square of resolution — the near tier is now ~2.4x the geometry per creature it was.
	 */
	private static final int[] BUDGET = {8, 18, 40, Integer.MAX_VALUE};

	/**
	 * Hard ceiling on cells per axis. {@link dev.jsz.primordia.mesh.MeshBaker} raises resolution
	 * above the tier value when a creature has limbs too thin to resolve otherwise, and this stops
	 * a hair-thin leg from demanding a grid that costs seconds to bake.
	 */
	private static int maxResolution = 80;

	/** Highest tier index that still runs full IK; above it, limbs use a cheap canned cycle. */
	private static int fullIkTier = MID;

	private LodTier() {
	}

	// ------------------------------------------------------------------ tuning

	/**
	 * Replaces the per-tier policy. Callers are responsible for flushing
	 * {@link GenomeMeshCache} afterwards — meshes already baked at the old resolutions stay valid
	 * objects, they are simply the wrong detail level, and nothing would ever rebake them.
	 *
	 * @param resolution  cells per axis for each of the four tiers
	 * @param budget      creatures per tier per frame; the last entry is effectively unlimited
	 * @param distance    block distances at which tiers 1..3 take over
	 */
	public static void configure(int[] resolution, int[] budget, double[] distance,
	                             int newMaxResolution, int newFullIkTier) {
		System.arraycopy(resolution, 0, RESOLUTION, 0, Math.min(resolution.length, RESOLUTION.length));
		System.arraycopy(budget, 0, BUDGET, 0, Math.min(budget.length, BUDGET.length));
		// The most distant tier always absorbs the remainder, whatever the caller passed.
		BUDGET[DISTANT] = Integer.MAX_VALUE;
		for (int i = 0; i < DISTANCE_SQ.length && i < distance.length; i++) {
			DISTANCE_SQ[i] = distance[i] * distance[i];
		}
		maxResolution = Math.max(8, newMaxResolution);
		fullIkTier = Math.max(0, Math.min(DISTANT, newFullIkTier));
	}

	public static int maxResolution() {
		return maxResolution;
	}

	public static int resolutionFor(int tier) {
		return RESOLUTION[Math.max(0, Math.min(tier, RESOLUTION.length - 1))];
	}

	/** True when this tier should run full IK; below it, limbs use a cheap canned cycle. */
	public static boolean usesInverseKinematics(int tier) {
		return tier <= fullIkTier;
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
