package dev.jsz.primordia.ecology.region;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/**
 * Coordinates of one ecological region: an 8×8 block of chunks, 128 blocks square.
 * <p>
 * The size is a compromise between two failure modes. Smaller, and a herd's home range spans four
 * ledgers, so a population is split across records that each see only part of it and none of the
 * numbers mean anything. Larger, and a mountain, a lake and a desert average into a single
 * productivity figure that describes none of them — and the biome boundaries the player can
 * actually see stop lining up with where the fauna changes.
 * <p>
 * 128 blocks is also roughly a two-minute walk, which is the scale at which someone notices that
 * the animals are different here.
 */
public record RegionPos(int x, int z) {
	/** Chunks per region edge. */
	public static final int CHUNKS = 8;
	/** Blocks per region edge. */
	public static final int BLOCKS = CHUNKS * 16;
	/** Bit shift from chunk coordinate to region coordinate. */
	private static final int CHUNK_SHIFT = 3;
	/** Bit shift from block coordinate to region coordinate. */
	private static final int BLOCK_SHIFT = 7;

	public static RegionPos of(BlockPos pos) {
		return new RegionPos(pos.getX() >> BLOCK_SHIFT, pos.getZ() >> BLOCK_SHIFT);
	}

	public static RegionPos of(double x, double z) {
		return new RegionPos(
				(int) Math.floor(x) >> BLOCK_SHIFT,
				(int) Math.floor(z) >> BLOCK_SHIFT);
	}

	public static RegionPos ofChunk(ChunkPos pos) {
		return new RegionPos(pos.x() >> CHUNK_SHIFT, pos.z() >> CHUNK_SHIFT);
	}

	/** Packed into a long for use as a map key, avoiding a boxed object per lookup. */
	public long key() {
		return ((long) x << 32) | (z & 0xFFFFFFFFL);
	}

	public static RegionPos fromKey(long key) {
		return new RegionPos((int) (key >> 32), (int) key);
	}

	public RegionPos offset(int dx, int dz) {
		return new RegionPos(x + dx, z + dz);
	}

	/** The four orthogonal neighbours, which are where migration goes. */
	public RegionPos[] neighbours() {
		return new RegionPos[]{offset(1, 0), offset(-1, 0), offset(0, 1), offset(0, -1)};
	}

	/** Block coordinate of the region's lowest corner. */
	public int minBlockX() {
		return x << BLOCK_SHIFT;
	}

	public int minBlockZ() {
		return z << BLOCK_SHIFT;
	}

	/** Block coordinate at the middle of the region, for sampling the biome and terrain height. */
	public int centreBlockX() {
		return minBlockX() + BLOCKS / 2;
	}

	public int centreBlockZ() {
		return minBlockZ() + BLOCKS / 2;
	}

	/**
	 * A stable seed for this region, mixed from the world seed and the coordinates.
	 * <p>
	 * Everything stochastic about a region derives from here rather than from a shared random
	 * source, so a region's founding fauna and its whole simulated history are reproducible from
	 * the world seed alone — which is what makes the off-screen simulation testable at all.
	 */
	public long seed(long worldSeed) {
		long h = worldSeed;
		h ^= (long) x * 0x9E3779B97F4A7C15L;
		h ^= (long) z * 0xC2B2AE3D27D4EB4FL;
		// A round of SplitMix64 finalisation, so neighbouring regions are not correlated.
		h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
		h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
		return h ^ (h >>> 31);
	}

	@Override
	public String toString() {
		return "region[" + x + ", " + z + "]";
	}
}
