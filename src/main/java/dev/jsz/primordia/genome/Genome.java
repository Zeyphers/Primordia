package dev.jsz.primordia.genome;

import dev.jsz.primordia.util.MathX;
import net.minecraft.nbt.CompoundTag;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Base64;
import java.util.random.RandomGenerator;

/**
 * An immutable creature genome: one normalised scalar per {@link Gene}, plus the
 * heritable metadata the ecology layer needs (a structural {@code seed} that drives
 * per-individual noise, a {@code lineage} id shared by a clade, and a generation counter).
 * <p>
 * Immutability matters: the genome doubles as the cache key for the baked mesh
 * ({@link dev.jsz.primordia.mesh.GenomeMeshCache}), so two creatures with equal genomes
 * must always produce byte-identical geometry.
 */
public final class Genome {
	private static final byte FORMAT_VERSION = 1;
	private static final int PAYLOAD_BYTES = 1 + 8 + 8 + 4 + Gene.COUNT * 2;

	private final float[] values;
	private final long seed;
	private final long lineage;
	private final int generation;
	private int hash;

	/** Number of distinct values a locus can hold; matches the 16 bits it occupies on the wire. */
	private static final float QUANTUM = 65535f;

	public Genome(float[] values, long seed, long lineage, int generation) {
		if (values.length != Gene.COUNT) {
			throw new IllegalArgumentException("Expected " + Gene.COUNT + " genes, got " + values.length);
		}
		// Snap every locus onto the wire format's 16-bit grid at construction, so the quantised
		// form is the canonical one. Without this, a genome built in memory and the same genome
		// decoded from the network compare unequal by a rounding error — and since the mesh cache
		// is keyed by genome, that silently doubles every bake and every cached body plan.
		this.values = new float[values.length];
		for (int i = 0; i < values.length; i++) {
			this.values[i] = Math.round(MathX.clamp01(values[i]) * QUANTUM) / QUANTUM;
		}
		this.seed = seed;
		this.lineage = lineage;
		this.generation = generation;
	}

	// ------------------------------------------------------------------ access

	/** Raw locus value in [0,1]. */
	public float raw(Gene gene) {
		return values[gene.ordinal()];
	}

	/** Locus value mapped linearly onto [lo,hi]. */
	public float range(Gene gene, float lo, float hi) {
		return MathX.remap01(values[gene.ordinal()], lo, hi);
	}

	/**
	 * Locus value mapped onto [lo,hi] with a bias exponent: {@code bias > 1} clusters
	 * results near {@code lo}, {@code bias < 1} clusters near {@code hi}. Useful for traits
	 * where the interesting variation is at one end (leg length, head size).
	 */
	public float biased(Gene gene, float lo, float hi, float bias) {
		return MathX.lerp(lo, hi, (float) Math.pow(values[gene.ordinal()], bias));
	}

	/** Locus value quantised onto the inclusive integer range [lo,hi]. */
	public int discrete(Gene gene, int lo, int hi) {
		int span = hi - lo + 1;
		int v = lo + (int) (values[gene.ordinal()] * span);
		return MathX.clamp(v, lo, hi);
	}

	/** True when the locus exceeds {@code threshold} — for presence/absence traits. */
	public boolean expresses(Gene gene, float threshold) {
		return values[gene.ordinal()] >= threshold;
	}

	public long seed() {
		return seed;
	}

	public long lineage() {
		return lineage;
	}

	public int generation() {
		return generation;
	}

	/** Defensive copy of the raw locus array; used by {@link Mutation}. */
	public float[] copyValues() {
		return values.clone();
	}

	// ------------------------------------------------------------- construction

	/** A uniformly random genome founding a brand-new lineage. */
	public static Genome random(RandomGenerator random) {
		float[] v = new float[Gene.COUNT];
		for (int i = 0; i < v.length; i++) {
			v[i] = random.nextFloat();
		}
		return new Genome(v, random.nextLong(), random.nextLong(), 0);
	}

	/**
	 * A random genome biased toward the middle of every range, so founders are
	 * "reasonable" animals rather than extreme outliers. Averaging three uniform
	 * samples gives an approximately normal distribution centred on 0.5.
	 */
	public static Genome randomModerate(net.minecraft.util.RandomSource random) {
		float[] v = new float[Gene.COUNT];
		for (int i = 0; i < v.length; i++) {
			v[i] = (random.nextFloat() + random.nextFloat() + random.nextFloat()) / 3f;
		}
		return new Genome(v, random.nextLong(), random.nextLong(), 0);
	}

	public static Genome randomModerate(RandomGenerator random) {
		float[] v = new float[Gene.COUNT];
		for (int i = 0; i < v.length; i++) {
			v[i] = (random.nextFloat() + random.nextFloat() + random.nextFloat()) / 3f;
		}
		return new Genome(v, random.nextLong(), random.nextLong(), 0);
	}

	/**
	 * Creates a founder genome adapted with biome camouflage, or — rarely — with the bright
	 * warning colouration of something that does not need to hide.
	 * <p>
	 * The outlier used to fire on 15% of founders at near-maximum saturation and brightness, which
	 * measured out as one animal in seven being scarlet. Warning colouration only reads as a
	 * warning when the rest of the fauna is drab, so it is now uncommon, and pitched at a strong
	 * rust or ochre rather than a traffic cone. {@link dev.jsz.primordia.body.BodyPalette} caps
	 * saturation by hue on top of this.
	 */
	public static Genome createForBiome(net.minecraft.util.RandomSource random, String biomeCategory) {
		java.util.Random jRandom = new java.util.Random(random.nextLong());
		// Surface archetypes only. A region's cave fauna is seeded separately, by RegionFounder,
		// because it is not a variation on the fauna above it — it lives somewhere else.
		Archetype archetype = Archetype.randomSurface(jRandom);
		Genome g = archetype.create(jRandom);
		float[] v = g.copyValues();

		boolean isOutlier = random.nextFloat() < 0.04f;
		if (isOutlier) {
			v[Gene.HUE.ordinal()] = random.nextBoolean() ? random.nextFloat() * 0.10f : 0.94f + random.nextFloat() * 0.06f;
			v[Gene.SATURATION.ordinal()] = 0.62f + random.nextFloat() * 0.25f;
			v[Gene.BRIGHTNESS.ordinal()] = 0.55f + random.nextFloat() * 0.25f;
			return new Genome(v, g.seed(), g.lineage(), 0);
		}

		String cat = biomeCategory == null ? "" : biomeCategory.toLowerCase();
		if (cat.contains("cave") || cat.contains("deep") || cat.contains("dripstone") || cat.contains("lush")) {
			// Cave dwellers: Albino White, Slate Grey, or Dark Obsidian with low saturation
			v[Gene.HUE.ordinal()] = random.nextFloat();
			v[Gene.SATURATION.ordinal()] = 0.0f + random.nextFloat() * 0.15f;
			v[Gene.BRIGHTNESS.ordinal()] = random.nextBoolean() ? 0.85f + random.nextFloat() * 0.15f : 0.10f + random.nextFloat() * 0.20f;
		} else if (cat.contains("desert") || cat.contains("badlands") || cat.contains("beach") || cat.contains("sand")) {
			v[Gene.HUE.ordinal()] = 0.08f + random.nextFloat() * 0.06f;
			v[Gene.SATURATION.ordinal()] = 0.28f + random.nextFloat() * 0.24f;
			v[Gene.BRIGHTNESS.ordinal()] = 0.70f + random.nextFloat() * 0.25f;
		} else if (cat.contains("snow") || cat.contains("ice") || cat.contains("frozen")) {
			v[Gene.SATURATION.ordinal()] = 0.02f + random.nextFloat() * 0.10f;
			v[Gene.BRIGHTNESS.ordinal()] = 0.82f + random.nextFloat() * 0.16f;
		} else if (cat.contains("swamp")) {
			v[Gene.HUE.ordinal()] = 0.18f + random.nextFloat() * 0.08f;
			v[Gene.SATURATION.ordinal()] = 0.24f + random.nextFloat() * 0.24f;
			v[Gene.BRIGHTNESS.ordinal()] = 0.22f + random.nextFloat() * 0.25f;
		} else if (cat.contains("jungle") || cat.contains("forest") || cat.contains("taiga")) {
			// Woodland camouflage is olive, moss and bark, not parrot green. This band was the
			// most saturated in the table and forest fauna came out luminous because of it.
			v[Gene.HUE.ordinal()] = 0.22f + random.nextFloat() * 0.14f;
			v[Gene.SATURATION.ordinal()] = 0.26f + random.nextFloat() * 0.26f;
		} else {
			v[Gene.HUE.ordinal()] = 0.10f + random.nextFloat() * 0.11f;
			v[Gene.SATURATION.ordinal()] = 0.28f + random.nextFloat() * 0.28f;
		}

		return new Genome(v, g.seed(), g.lineage(), 0);
	}

	/** Returns a copy with {@code gene} forced to {@code value}; used by debug commands. */
	public Genome with(Gene gene, float value) {
		float[] v = values.clone();
		v[gene.ordinal()] = MathX.clamp01(value);
		return new Genome(v, seed, lineage, generation);
	}

	public Genome withGeneration(int generation) {
		return new Genome(values.clone(), seed, lineage, generation);
	}

	// ------------------------------------------------------------ serialisation

	/** Packs the genome into a compact Base64 string suitable for entity data tracking. */
	public String encode() {
		ByteBuffer buf = ByteBuffer.allocate(PAYLOAD_BYTES);
		buf.put(FORMAT_VERSION);
		buf.putLong(seed);
		buf.putLong(lineage);
		buf.putInt(generation);
		// Values are already on the 16-bit grid, so this round-trips exactly.
		for (float value : values) {
			buf.putShort((short) Math.round(value * QUANTUM));
		}
		return Base64.getUrlEncoder().withoutPadding().encodeToString(buf.array());
	}

	/**
	 * Inverse of {@link #encode}. Returns {@code null} for empty, malformed, or
	 * future-version payloads so callers can fall back to a fresh genome rather than crash
	 * a render pass.
	 */
	public static Genome decode(String encoded) {
		if (encoded == null || encoded.isEmpty()) return null;
		byte[] bytes;
		try {
			bytes = Base64.getUrlDecoder().decode(encoded);
		} catch (IllegalArgumentException e) {
			return null;
		}
		if (bytes.length < 21) return null;
		ByteBuffer buf = ByteBuffer.wrap(bytes);
		byte version = buf.get();
		if (version != FORMAT_VERSION) return null;
		long seed = buf.getLong();
		long lineage = buf.getLong();
		int generation = buf.getInt();

		float[] v = new float[Gene.COUNT];
		int available = buf.remaining() / 2;
		for (int i = 0; i < v.length; i++) {
			// Genes added after this payload was written default to the middle of their range.
			v[i] = i < available ? (buf.getShort() & 0xFFFF) / QUANTUM : 0.5f;
		}
		return new Genome(v, seed, lineage, generation);
	}

	public CompoundTag writeNbt() {
		CompoundTag nbt = new CompoundTag();
		nbt.putString("Code", encode());
		return nbt;
	}

	public static Genome readNbt(CompoundTag nbt) {
		return nbt.contains("Code") ? decode(nbt.getStringOr("Code", "")) : null;
	}

	// ------------------------------------------------------------------ identity

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof Genome other)) return false;
		return seed == other.seed && lineage == other.lineage && Arrays.equals(values, other.values);
	}

	@Override
	public int hashCode() {
		int h = hash;
		if (h == 0) {
			h = Arrays.hashCode(values);
			h = 31 * h + Long.hashCode(seed);
			h = 31 * h + Long.hashCode(lineage);
			if (h == 0) h = 1; // 0 is the "not yet computed" sentinel
			hash = h;
		}
		return h;
	}

	@Override
	public String toString() {
		return "Genome[lineage=" + Long.toHexString(lineage) + ", gen=" + generation + "]";
	}
}
