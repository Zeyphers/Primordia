package dev.jsz.primordia.ecology.region;

import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;

/**
 * Reads a biome into the three numbers the regional model works in.
 * <p>
 * Derived from the biome's identity rather than from sampling blocks, because founding happens for
 * regions whose chunks are not loaded — there is nothing to sample — and because a region should
 * mean the same thing whether the player arrived from the north or the south.
 * <p>
 * Productivity is the one that matters most: it sets how much plant food the region supplies per
 * step, and therefore how large a population, and how large an individual, the place can carry.
 * A jungle should be able to feed something a scree slope cannot.
 */
public final class RegionClimate {
	private RegionClimate() {
	}

	/**
	 * Depth at which the cave biome is sampled.
	 * <p>
	 * Well below the surface, because the biome at sea level is the meadow or the desert and says
	 * nothing about what is underneath it. Minecraft generates its cave biomes as a separate layer,
	 * and this is the height at which lush caves and dripstone are actually found.
	 */
	/**
	 * Depths at which the cave biome is sampled.
	 * <p>
	 * Minecraft generates its cave biomes as a separate layer over a wide vertical range, so one
	 * height is not enough — a lush cave at Y 30 is invisible to a probe at Y 8.
	 */
	private static final int[] CAVE_SAMPLE_DEPTHS = {-24, -4, 16, 36};
	/** Points per axis sampled across the region. */
	private static final int CAVE_SAMPLE_GRID = 3;

	public static RegionFounder.Climate sample(ServerWorld world, RegionPos pos) {
		BlockPos centre = new BlockPos(pos.centreBlockX(), world.getSeaLevel(), pos.centreBlockZ());
		RegistryEntry<Biome> entry = world.getBiome(centre);
		Biome biome = entry.value();

		// Minecraft temperature runs about -0.7 (snowy) to 2.0 (desert); the genome works in [0,1].
		float temperature = clamp01((biome.getTemperature() + 0.7f) / 2.7f);
		String name = entry.getKey().map(key -> key.getValue().getPath()).orElse("");

		return new RegionFounder.Climate(temperature, humidity(name), productivity(name),
				sampleCaves(world, pos));
	}

	/**
	 * The richest cave biome anywhere under this region.
	 * <p>
	 * Sampled over a grid and at several depths, and reduced with {@code max} rather than averaged.
	 * A region is 128 blocks square, and a lush cave occupying a corner of it is still a lush cave
	 * to anyone who walks into it — averaging would dilute it to nothing, and the single centre
	 * column this used to read would miss it outright. That was why teleporting to a lush cave
	 * found no fauna: the biome under the region's midpoint was ordinary stone.
	 */
	private static float sampleCaves(ServerWorld world, RegionPos pos) {
		float best = 0f;
		int step = RegionPos.BLOCKS / (CAVE_SAMPLE_GRID + 1);

		for (int ix = 1; ix <= CAVE_SAMPLE_GRID; ix++) {
			for (int iz = 1; iz <= CAVE_SAMPLE_GRID; iz++) {
				int x = pos.minBlockX() + ix * step;
				int z = pos.minBlockZ() + iz * step;
				for (int y : CAVE_SAMPLE_DEPTHS) {
					if (y < world.getBottomY()) continue;
					String name = world.getBiome(new BlockPos(x, y, z)).getKey()
							.map(key -> key.getValue().getPath()).orElse("");
					best = Math.max(best, caveRichness(name));
					if (best >= 1f) return best;
				}
			}
		}
		return best;
	}

	/**
	 * How much life the caves under this region can support, 0 to 1.
	 * <p>
	 * Lush caves are the only place underground with anything growing in it — moss, vines, glow
	 * berries, water — and they are where a cave fauna belongs. Ordinary stone caves get a small
	 * value rather than zero, so a lineage still turns up in them occasionally: a system where the
	 * animals exist in exactly one biome and nowhere else reads as a spawn table, and the point of
	 * the ledger is that populations spread from where they do well into where they merely persist.
	 */
	private static float caveRichness(String caveBiomeName) {
		if (contains(caveBiomeName, "lush")) return 1.0f;
		if (contains(caveBiomeName, "dripstone")) return 0.45f;
		if (contains(caveBiomeName, "deep_dark")) return 0.20f;
		return 0.18f;
	}

	/**
	 * How wet the region is. Read off the biome name for the same reason
	 * {@code Genome.createForBiome} does — it is stable across versions, needs no registry lookups,
	 * and the categories that matter here are exactly the ones the names already distinguish.
	 */
	private static float humidity(String name) {
		if (contains(name, "swamp", "mangrove")) return 0.95f;
		if (contains(name, "jungle")) return 0.90f;
		if (contains(name, "river", "ocean", "beach", "lush")) return 0.75f;
		if (contains(name, "forest", "taiga", "grove", "birch", "dark")) return 0.62f;
		if (contains(name, "plains", "meadow", "hills")) return 0.48f;
		if (contains(name, "savanna")) return 0.28f;
		if (contains(name, "desert", "badlands", "mesa")) return 0.06f;
		if (contains(name, "snowy", "frozen", "ice", "peaks")) return 0.35f;
		return 0.5f;
	}

	/**
	 * Plant productivity: the ceiling on standing vegetation and how fast it comes back.
	 * <p>
	 * This is the number that decides where the big animals end up living, without anything ever
	 * checking a biome for that purpose. Bulk costs upkeep, upkeep has to be met from local plant
	 * growth, and a badlands cannot meet it.
	 */
	private static float productivity(String name) {
		if (contains(name, "jungle")) return 0.98f;
		if (contains(name, "swamp", "mangrove", "lush")) return 0.86f;
		if (contains(name, "forest", "grove", "birch", "dark", "taiga")) return 0.76f;
		if (contains(name, "plains", "meadow")) return 0.68f;
		if (contains(name, "savanna")) return 0.46f;
		if (contains(name, "beach", "river")) return 0.34f;
		if (contains(name, "snowy", "frozen", "ice")) return 0.22f;
		if (contains(name, "peaks", "mountain", "stony", "windswept")) return 0.18f;
		if (contains(name, "desert", "badlands", "mesa")) return 0.10f;
		if (contains(name, "ocean")) return 0.12f;
		return 0.5f;
	}

	private static boolean contains(String name, String... needles) {
		for (String needle : needles) {
			if (name.contains(needle)) return true;
		}
		return false;
	}

	private static float clamp01(float v) {
		return v < 0f ? 0f : (v > 1f ? 1f : v);
	}
}
