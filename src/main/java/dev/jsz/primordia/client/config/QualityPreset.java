package dev.jsz.primordia.client.config;

/**
 * Named points on the quality curve.
 * <p>
 * Two axes move together here and it is worth being explicit about which is which, because they
 * cost very different things. <b>Budgets</b> decide how many creatures may be drawn at a given
 * tier before the rest spill down — raising them costs draw calls and skinning time, and scales
 * with how crowded the scene is. <b>Resolutions</b> decide how finely each creature is meshed —
 * raising them costs bake time once per genome, then memory for as long as the mesh is cached,
 * and is paid whether one creature is on screen or forty.
 * <p>
 * A machine that can afford a lot of one cannot necessarily afford a lot of the other, which is
 * why every field stays individually editable and picking a preset is only a starting point.
 */
public enum QualityPreset {
	POTATO("Potato",
			new int[]{20, 14, 10, 6}, new int[]{3, 6, 15, 0}, new double[]{8, 18, 36}, 40, 192, 0),
	LOW("Low",
			new int[]{28, 18, 12, 8}, new int[]{5, 12, 25, 0}, new double[]{10, 22, 44}, 56, 256, 0),
	BALANCED("Balanced",
			new int[]{40, 26, 15, 9}, new int[]{8, 18, 40, 0}, new double[]{12, 28, 56}, 80, 384, 1),
	HIGH("High",
			new int[]{48, 34, 20, 12}, new int[]{16, 36, 80, 0}, new double[]{20, 44, 80}, 96, 768, 1),
	/** Everything wide open: many creatures, held at fine detail much further out. */
	ULTRA("Ultra",
			new int[]{60, 44, 28, 16}, new int[]{32, 72, 160, 0}, new double[]{32, 64, 112}, 128, 1536, 2),
	/** Not a preset so much as a marker that the individual sliders no longer match one. */
	CUSTOM("Custom", null, null, null, 0, 0, 0);

	public final String label;
	public final int[] resolution;
	public final int[] budget;
	public final double[] distance;
	public final int maxResolution;
	public final int cacheSize;
	/** Highest tier index still running full IK. */
	public final int fullIkTier;

	QualityPreset(String label, int[] resolution, int[] budget, double[] distance,
	              int maxResolution, int cacheSize, int fullIkTier) {
		this.label = label;
		this.resolution = resolution;
		this.budget = budget;
		this.distance = distance;
		this.maxResolution = maxResolution;
		this.cacheSize = cacheSize;
		this.fullIkTier = fullIkTier;
	}

	public boolean isCustom() {
		return this == CUSTOM;
	}

	public static final QualityPreset[] VALUES = values();
}
