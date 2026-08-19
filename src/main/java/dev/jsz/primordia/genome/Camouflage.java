package dev.jsz.primordia.genome;

import dev.jsz.primordia.util.MathX;

/**
 * The colour a region's ground actually is, and therefore the colour its animals ought to be.
 * <p>
 * This exists because founding an animal the colour of its home is only half the job. Colour was
 * being set once, at founding, and then left to {@code RegionSimulation}'s neutral drift — which
 * runs on {@link Gene#HUE}, the single most plastic locus in the enum, for the whole of a region's
 * pre-history with nothing pulling back. A random walk that long on an unconstrained locus arrives
 * at uniform, so by the time a player walked into a mesa its fauna was every colour on the wheel,
 * founded terracotta or not. The camouflage was real and it had simply been forgotten.
 * <p>
 * So the target is stored on the region rather than applied and discarded, and selection pulls
 * toward it every step in the same way climate preference is pulled toward the climate. Drift still
 * moves colour — a lineage is not painted on — it just no longer wanders off unopposed.
 * <p>
 * Values are in <b>gene space</b>, not HSV: {@link Gene#SATURATION} and {@link Gene#BRIGHTNESS} are
 * remapped by {@link dev.jsz.primordia.body.BodyPalette} before they reach a colour, and
 * {@code pigmentAllowance} caps saturation by hue on top of that. A band here means what it says at
 * the locus, and what it looks like is that class's business.
 *
 * @param hue         centre of the hue band, in turns: 0.08 is terracotta, 0.25 a leaf green
 * @param hueSpread   half-width of the hue band, or 0.5 for "any hue" where saturation is so low
 *                    that hue cannot be seen anyway
 * @param saturation  centre of the saturation band
 * @param satSpread   half-width of the saturation band
 * @param brightness  centre of the brightness band
 * @param brightSpread half-width of the brightness band
 * @param split       true where the biome has no single value to hide against and the fauna splits
 *                    into two — pale and near-black in a cave, where there is no light to match
 */
public record Camouflage(float hue, float hueSpread, float saturation, float satSpread,
                         float brightness, float brightSpread, boolean split) {

	/**
	 * The ground colour of a biome, by name.
	 * <p>
	 * Read off the biome path for the same reason {@code RegionClimate} does: it is stable across
	 * versions, needs no registry lookup, and the distinctions that matter are exactly the ones the
	 * names already draw. Matched most specific first — {@code wooded_badlands} is badlands with
	 * trees on it and the ground a creature stands on is still terracotta.
	 */
	public static Camouflage forBiome(String biomeName) {
		String name = biomeName == null ? "" : biomeName.toLowerCase();

		// No daylight to hide in, so hue is free and the split is between pale and near-black.
		if (contains(name, "cave", "deep_dark", "dripstone", "lush")) {
			return new Camouflage(0.5f, 0.5f, 0.07f, 0.07f, 0.5f, 0.4f, true);
		}
		// Terracotta, sand and dry stone. The badlands case the whole of this was reported from.
		if (contains(name, "badlands", "mesa", "desert", "beach", "sand", "savanna")) {
			return new Camouflage(0.075f, 0.035f, 0.40f, 0.12f, 0.82f, 0.12f, false);
		}
		// Snow and ice: near-white, and hue is irrelevant at this saturation.
		if (contains(name, "snow", "ice", "frozen", "glacial")) {
			return new Camouflage(0.5f, 0.5f, 0.07f, 0.05f, 0.90f, 0.08f, false);
		}
		// Standing water over dark peat: the darkest and least saturated of the green biomes.
		if (contains(name, "swamp", "mangrove")) {
			return new Camouflage(0.22f, 0.04f, 0.36f, 0.12f, 0.345f, 0.125f, false);
		}
		// Woodland is olive, moss and bark, not parrot green — and dark, because a forest floor is.
		if (contains(name, "jungle", "forest", "taiga", "grove", "birch", "dark")) {
			return new Camouflage(0.29f, 0.07f, 0.39f, 0.13f, 0.42f, 0.15f, false);
		}
		// Open grassland: the same green, lighter and drier — dry grass, not canopy shadow.
		if (contains(name, "plains", "meadow", "cherry")) {
			return new Camouflage(0.25f, 0.05f, 0.35f, 0.10f, 0.68f, 0.12f, false);
		}
		// Bare rock and scree: grey-brown, and no strong colour anywhere to borrow.
		if (contains(name, "peaks", "mountain", "stony", "windswept", "slope")) {
			return new Camouflage(0.08f, 0.05f, 0.14f, 0.10f, 0.50f, 0.18f, false);
		}
		// Everything unrecognised, including modded biomes: earth brown, which is wrong nowhere.
		return new Camouflage(0.155f, 0.055f, 0.42f, 0.14f, 0.55f, 0.18f, false);
	}

	/**
	 * The same target recovered from climate alone, for a region saved before the target was
	 * stored on it.
	 * <p>
	 * Lossy, and deliberately so — {@code RegionClimate} derives temperature and humidity from the
	 * biome name in the first place, so this is running that map backwards and several biomes share
	 * a corner of it. It only has to be closer than the alternative, which is no pull at all and a
	 * fauna that goes on drifting to uniform in every old save.
	 */
	public static Camouflage forClimate(float temperature, float humidity) {
		if (temperature < 0.28f) return forBiome("snowy");
		if (humidity < 0.20f) return forBiome("desert");
		if (humidity > 0.80f) return forBiome("swamp");
		if (humidity > 0.55f) return forBiome("forest");
		return forBiome("plains");
	}

	/** Writes a draw from these bands into a gene vector. */
	public void apply(float[] values, net.minecraft.util.RandomSource random) {
		values[Gene.HUE.ordinal()] = MathX.clamp01(
				hueSpread >= 0.5f ? random.nextFloat() : band(hue, hueSpread, random));
		values[Gene.SATURATION.ordinal()] = band(saturation, satSpread, random);
		// A split biome has two answers and no middle: drawing from a band between them would give
		// every cave animal the mid-grey that neither half of the strategy is.
		values[Gene.BRIGHTNESS.ordinal()] = split
				? (random.nextBoolean() ? MathX.clamp01(brightness + brightSpread * (0.6f + 0.4f * random.nextFloat()))
						: MathX.clamp01(brightness - brightSpread * (0.6f + 0.4f * random.nextFloat())))
				: band(brightness, brightSpread, random);
	}

	/** The target for a locus, or {@code -1} where this biome has no opinion about it. */
	public float targetFor(Gene gene) {
		return switch (gene) {
			// A free hue and a split brightness are both "no single right answer", and selection
			// must not invent one by pulling everything to the middle of the range.
			case HUE -> hueSpread >= 0.5f ? -1f : hue;
			case SATURATION -> saturation;
			case BRIGHTNESS -> split ? -1f : brightness;
			default -> -1f;
		};
	}

	private static float band(float centre, float spread, net.minecraft.util.RandomSource random) {
		return MathX.clamp01(centre + (random.nextFloat() * 2f - 1f) * spread);
	}

	private static boolean contains(String name, String... needles) {
		for (String needle : needles) {
			if (name.contains(needle)) return true;
		}
		return false;
	}
}
