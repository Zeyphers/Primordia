package dev.jsz.primordia.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.jsz.primordia.Primordia;
import dev.jsz.primordia.mesh.GenomeMeshCache;
import dev.jsz.primordia.mesh.LodTier;
import dev.jsz.primordia.mesh.MeshBaker;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Mth;

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

	/**
	 * Whether creatures use the procedural voice synthesiser.
	 * <p>
	 * Off silences them entirely rather than falling back to vanilla mob sounds. There is nothing to
	 * fall back to — a creature's voice is derived from its genome the same way its body is, and no
	 * recorded animal is a sensible stand-in for a shape the game invented this morning.
	 */
	public boolean creatureVoices = true;

	/**
	 * Creature voice loudness as a percentage, applied on top of the game's own sound sliders.
	 * <p>
	 * A separate control because these calls are not on any vanilla category a player can single
	 * out: they ride the Friendly Creatures slider along with every cow and pig in the world, and
	 * somebody who wants their own animals quieter without silencing the rest needs this.
	 */
	public int creatureVoiceVolume = 100;

	/**
	 * Snaps the mesh to a world-aligned voxel grid instead of following the field smoothly.
	 * <p>
	 * Surface Nets places each dual vertex at the average of the cell's edge crossings, which is
	 * exactly what makes a coarse grid still read as a curved surface. Pinning that vertex to the
	 * centre of its cell instead turns every quad into an axis-aligned square of one cell — the same
	 * result Blender's voxel remesh gives, and the same look as the blocks the creature is standing
	 * on.
	 */
	public boolean voxelMode = true;

	/**
	 * Voxel edge in Minecraft pixels, where sixteen pixels is one block.
	 * <p>
	 * Measured in pixels rather than as a fraction of the creature so that the grid is a property of
	 * the world and not of the animal: two creatures of different sizes standing next to each other
	 * are built from voxels of the same size, which is the whole reason the effect reads as being
	 * made of blocks rather than as being low-detail.
	 * <p>
	 * Fractional, so the grid can go finer than the world's own texel. Cost scales with the cube of
	 * the count, so a quarter-pixel voxel is sixty-four times the work of a whole one — see
	 * {@link #VOXEL_PIXELS_MIN}.
	 */
	public float voxelPixels = 1f;

	/**
	 * Bounds and step for {@link #voxelPixels}, in Minecraft pixels.
	 * <p>
	 * A quarter of a pixel is four times the world's texel resolution per axis. Below that the bake
	 * cost stops buying visible detail — the mesh is already finer than anything it stands next to —
	 * and two whole pixels is the point past which a creature reads as a pile of cubes rather than
	 * as an animal. The step is a quarter so every position on the slider is a exact fraction of a
	 * texel rather than an arbitrary float.
	 */
	public static final float VOXEL_PIXELS_MIN = 0.25f;
	public static final float VOXEL_PIXELS_MAX = 2f;
	public static final float VOXEL_PIXELS_STEP = 0.25f;

	/**
	 * How far upside-down carcasses sink into the ground, scaled by creature leg height (hipHeight).
	 */
	public float carcassSinkFactor = 1.15f;

	/**
	 * Relative X, Y, Z offsets for dead leg IK targets, scaled by leg length.
	 */
	public float deadIkOffsetX = 0.00f;
	public float deadIkOffsetY = -0.35f;
	public float deadIkOffsetZ = 0.00f;

	/**
	 * Gives every face its own normal instead of sharing averaged ones with its neighbours.
	 * <p>
	 * The honest smooth-versus-sharp control. {@link #normalSmoothing} was never one: it blends two
	 * ways of computing a <i>shared</i> vertex normal, and a shared normal is smooth whichever way
	 * it is derived, which is why dragging that slider barely changed anything.
	 * <p>
	 * Read by the renderer, not by the mesher — the mesh is identical either way. Both renderers
	 * already emit four unshared vertices per quad, so this only decides which normal those four
	 * are handed, and it is free. See {@link dev.jsz.primordia.mesh.FaceNormal}.
	 */
	public boolean sharpShading = true;

	/**
	 * Gives every face one flat colour — the mean of its corners — instead of a gradient across it.
	 * <p>
	 * Colour lives in the vertices, so the hardware interpolates it over each face and a quad whose
	 * corners sample four points of a pattern comes out as a small gradient. That is right on a
	 * smooth body and wrong on a voxel one, where the shape says flat block and the shading says
	 * curved surface.
	 * <p>
	 * Read by the renderer, like {@link #sharpShading}, so the mesh is identical either way.
	 */
	public boolean flatFaceColour = true;

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
				if (loaded != null) {
					// The one setting where a hand-edited value is genuinely dangerous rather than
					// merely wrong: zero divides the creature into infinitely many voxels and hangs
					// the bake thread, and the cost of anything below the minimum is cubic.
					loaded.voxelPixels = Mth.clamp(loaded.voxelPixels, VOXEL_PIXELS_MIN, VOXEL_PIXELS_MAX);
					return loaded;
				}
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
		// Sixteen pixels to the block, so one pixel is one sixteenth of a world unit. Zero when the
		// mode is off, which is what the mesher reads as "extract normally".
		MeshBaker.setVoxelSize(voxelMode ? voxelPixels / 16f : 0f);
		GenomeMeshCache.setMaxEntries(meshCacheSize);
		GenomeMeshCache.clear();
	}

	/** Applies, then persists. The usual path out of the settings screen. */
	public void applyAndSave() {
		apply();
		save();
	}
}
