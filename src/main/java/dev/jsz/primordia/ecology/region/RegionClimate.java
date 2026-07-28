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

	public static RegionFounder.Climate sample(ServerWorld world, RegionPos pos) {
		BlockPos centre = new BlockPos(pos.centreBlockX(), world.getSeaLevel(), pos.centreBlockZ());
		RegistryEntry<Biome> entry = world.getBiome(centre);
		Biome biome = entry.value();

		// Minecraft temperature runs about -0.7 (snowy) to 2.0 (desert); the genome works in [0,1].
		float temperature = clamp01((biome.getTemperature() + 0.7f) / 2.7f);
		String name = entry.getKey().map(key -> key.getValue().getPath()).orElse("");

		return new RegionFounder.Climate(temperature, humidity(name), productivity(name));
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
