package dev.jsz.primordia.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.jsz.primordia.Primordia;
import dev.jsz.primordia.mesh.GenomeMeshCache;
import dev.jsz.primordia.mesh.LodTier;
import dev.jsz.primordia.mesh.MeshBaker;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Client rendering settings, persisted to {@code config/primordia.json}.
 * <p>
 * Everything here is a client concern — how many creatures to draw, how finely to mesh them — so
 * none of it is synchronised and none of it affects what the server generates. Two players can
 * run wildly different settings and still be looking at the same animals.
 */
public final class PrimordiaConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static PrimordiaConfig instance;

	public QualityPreset preset = QualityPreset.BALANCED;

	/** Creatures drawn at each tier before the rest spill down to the next one. */
	public int nearCreatures = 8;
	public int midCreatures = 18;
	public int farCreatures = 40;

	/** Block distance at which each tier gives way to the next. */
	public double nearDistance = 12;
	public double midDistance = 28;
	public double farDistance = 56;

	/** Surface Nets cells along the longest axis, per tier. */
	public int nearDetail = 40;
	public int midDetail = 26;
	public int farDetail = 15;
	public int distantDetail = 9;

	/** Ceiling on cells per axis, however thin a creature's limbs are. */
	public int detailCeiling = 80;
	/** Distinct baked meshes held in memory before eviction. */
	public int meshCacheSize = 384;
	/** Highest LOD tier that still runs full inverse kinematics. */
	public int fullIkTier = LodTier.MID;

	/** Share of each vertex normal taken from the analytic SDF gradient, as a percent. */
	public int normalSmoothing = 75;
	/** Whether bioluminescent creatures actually emit light. */
	public boolean emissiveGlow = true;

	private PrimordiaConfig() {
	}

	public static PrimordiaConfig get() {
		if (instance == null) {
			instance = load();
			instance.apply();
		}
		return instance;
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("primordia.json");
	}

	private static PrimordiaConfig load() {
		Path file = path();
		if (Files.exists(file)) {
			try {
				PrimordiaConfig loaded = GSON.fromJson(Files.readString(file), PrimordiaConfig.class);
				// A truncated or hand-edited file deserialises to null rather than throwing, and a
				// null config would NPE on the first frame rendered.
				if (loaded != null) return loaded;
			} catch (Exception e) {
				Primordia.LOGGER.warn("Could not read {}, falling back to defaults", file, e);
			}
		}
		return new PrimordiaConfig();
	}

	public void save() {
		try {
			Path file = path();
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(this));
		} catch (IOException e) {
			Primordia.LOGGER.warn("Could not write config", e);
		}
	}

	/** Overwrites every individual setting from a named preset. No-op for {@link QualityPreset#CUSTOM}. */
	public void applyPreset(QualityPreset newPreset) {
		this.preset = newPreset;
		if (newPreset.isCustom()) return;

		nearDetail = newPreset.resolution[0];
		midDetail = newPreset.resolution[1];
		farDetail = newPreset.resolution[2];
		distantDetail = newPreset.resolution[3];

		nearCreatures = newPreset.budget[0];
		midCreatures = newPreset.budget[1];
		farCreatures = newPreset.budget[2];

		nearDistance = newPreset.distance[0];
		midDistance = newPreset.distance[1];
		farDistance = newPreset.distance[2];

		detailCeiling = newPreset.maxResolution;
		meshCacheSize = newPreset.cacheSize;
		fullIkTier = newPreset.fullIkTier;
	}

	/**
	 * Called whenever an individual slider moves: the settings no longer describe a named preset,
	 * and silently leaving the old label on them would be a lie.
	 */
	public void markCustom() {
		preset = QualityPreset.CUSTOM;
	}

	/**
	 * Pushes the settings into the systems that read them and drops any mesh baked under the old
	 * ones.
	 * <p>
	 * The cache flush is not optional. Meshes are keyed by genome and tier, not by the resolution
	 * they were baked at, so without it a creature already on screen keeps whatever detail level
	 * it happened to be built with and the new setting appears to do nothing at all — which reads
	 * as a broken slider rather than as a caching artefact.
	 */
	public void apply() {
		LodTier.configure(
				new int[]{nearDetail, midDetail, farDetail, distantDetail},
				new int[]{nearCreatures, midCreatures, farCreatures, Integer.MAX_VALUE},
				new double[]{nearDistance, midDistance, farDistance},
				detailCeiling,
				fullIkTier);
		MeshBaker.setGradientWeight(normalSmoothing / 100f);
		GenomeMeshCache.setMaxEntries(meshCacheSize);
		GenomeMeshCache.clear();
	}

	/** Applies, then persists. The usual path out of the settings screen. */
	public void applyAndSave() {
		apply();
		save();
	}
}
