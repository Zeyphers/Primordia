package dev.jsz.primordia.ecology.region;

import dev.jsz.primordia.entity.CreatureEntity;
import dev.jsz.primordia.genome.Archetype;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.registry.PrimordiaEntities;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Turns numbers into animals and animals back into numbers.
 * <p>
 * The contract, and the whole point of the ledger: <b>a population is either in the record or in
 * the world, never both and never neither.</b> {@link #topUp} moves individuals out of
 * {@link LineageRecord#count} and into entities; {@link #absorb} moves them back. Every path that
 * removes a creature from the world without killing it has to go through absorb, or the population
 * leaks — and a leak of a few percent per load cycle is invisible in play, passes every test that
 * does not specifically look for it, and empties the world over an afternoon of exploring.
 * <p>
 * That is what {@code RegionLedgerTest} exists to catch.
 */
public final class RegionMaterialiser {
	/**
	 * Most creatures one region will have walking about at once.
	 * <p>
	 * Bounded by rendering, not by ecology: these are procedural meshes and the near LOD tier
	 * budgets for eight on screen. A region may hold a population of eighty — the ledger tracks all
	 * of them — but only this many are ever entities, and the rest sit in the record as a number.
	 */
	public static final int ENTITY_BUDGET = 10;

	/**
	 * Most ledger-managed creatures allowed within a player's whole active area at once.
	 * <p>
	 * The per-region budget alone is not a bound on anything the player experiences: nine regions
	 * are active around them, so a per-region cap of ten still permits ninety creatures within
	 * sight. These are procedural meshes with CPU skinning and per-tick entity queries in their
	 * goals, and ninety of them is not a busy ecosystem, it is a frame-rate problem.
	 */
	public static final int CLUSTER_BUDGET = 30;

	/**
	 * Cave dwellers a region may have underground at once, budgeted separately from the surface.
	 * <p>
	 * Smaller than the surface allowance because they are tiny and a player underground sees a much
	 * smaller volume than one standing in a field — six lights moving on the walls of a cave is a
	 * populated cave.
	 */
	public static final int CAVE_ENTITY_BUDGET = 8;
	/** Attempts to find a valid spawn position before giving up on an individual. */
	private static final int PLACEMENT_ATTEMPTS = 12;
	/**
	 * Attempts allowed when placing underground.
	 * <p>
	 * Far more than on the surface, because the hit rate is far lower: every surface column has a
	 * top, whereas most columns through a region are solid rock and fail before they are scanned.
	 * Twelve tries found a cave floor so rarely that a region with a recorded population of sixty
	 * put nothing on the ground at all. The attempt itself is a cheap block scan.
	 */
	private static final int CAVE_PLACEMENT_ATTEMPTS = 64;

	private RegionMaterialiser() {
	}

	/**
	 * Brings the region's live entity count up toward its recorded population.
	 * <p>
	 * A top-up rather than a spawn-from-scratch, which is what makes it safe to call on a timer:
	 * it reads how many are already there and only makes up the difference, so calling it twice
	 * does nothing the second time.
	 */
	public static void topUp(ServerLevel world, RegionRecord record) {
		topUp(world, record, true, true);
	}

	/**
	 * Tops up one or both habitats. The caller gates them separately because they draw on separate
	 * budgets — see {@code EcologyTicker}.
	 */
	public static void topUp(ServerLevel world, RegionRecord record,
	                         boolean surface, boolean caves) {
		Random random = new Random(record.seed ^ world.getGameTime());
		// Surface and cave fauna are budgeted apart.
		//
		// They occupy different parts of the same region and are never in view together, so a
		// shared budget is not a shared resource — it is the surface animals, which are placed
		// first and are more numerous, quietly consuming every slot before the cave ones are
		// considered. The symptom is an empty cave under a perfectly busy meadow.
		if (surface) topUp(world, record, random, false, ENTITY_BUDGET);
		if (caves) topUp(world, record, random, true, CAVE_ENTITY_BUDGET);
	}

	private static void topUp(ServerLevel world, RegionRecord record, Random random,
	                          boolean subterranean, int budget) {
		int live = countLive(world, record.pos, subterranean);
		int room = budget - live;
		if (room <= 0) return;

		// The budget is shared out across every lineage present, in proportion to how many of each
		// the region holds, with a floor of one so a rare predator is never invisible.
		//
		// This used to walk the list spawning as many as it could of each in turn, which meant the
		// first lineage took the entire budget every time — a region recorded as holding four
		// species showed ten individuals of whichever happened to be listed first. Since
		// TamingPreference keys the favourite food off the lineage id, the tell was that every
		// animal for hundreds of blocks wanted the same bait.
		float[] populations = new float[record.lineages.size()];
		for (int i = 0; i < populations.length; i++) {
			LineageRecord lineage = record.lineages.get(i);
			// Lineages from the other habitat are simply not candidates for this pass.
			populations[i] = Archetype.isSubterranean(lineage.meanGenome()) == subterranean
					? lineage.count
					: 0f;
		}
		int[] quota = allocate(populations, room);

		for (int i = 0; i < quota.length; i++) {
			LineageRecord lineage = record.lineages.get(i);
			for (int n = 0; n < quota[i]; n++) {
				if (!place(world, record.pos, lineage, random)) break;
				// Only whole animals can be spawned; the fraction stays in the record rather than
				// being rounded away, which is how small populations used to evaporate.
				lineage.take();
				record.dirty = true;
			}
		}
	}

	/**
	 * Shares a fixed number of entity slots out across the lineages present, by population.
	 * <p>
	 * Representation first, proportion second: every lineage with at least one animal recorded gets
	 * a slot before any lineage gets a second one. A purely proportional split starves the rarest
	 * lineage in the region — which, given the trophic pyramid puts predators at the bottom of the
	 * population table, means the predator is the one that never appears.
	 * <p>
	 * Separated out and made visible so the test can drive the real allocation rather
	 * than a copy of it. {@code PITFALLS.md} §6: a test that reimplements the code cannot catch the
	 * code, and the bug this exists to prevent was in the arithmetic, not in the spawning.
	 *
	 * @param populations recorded population per lineage, fractional
	 * @param room        entity slots available
	 * @return how many of each lineage to place; never more than {@code room} in total
	 */
	public static int[] allocate(float[] populations, int room) {
		int[] quota = new int[populations.length];
		if (room <= 0) return quota;

		// Whole animals only — a fraction is not something that can be placed.
		int[] available = new int[populations.length];
		int present = 0;
		int totalAvailable = 0;
		for (int i = 0; i < populations.length; i++) {
			available[i] = (int) Math.floor(Math.max(0f, populations[i]));
			if (available[i] > 0) {
				present++;
				totalAvailable += available[i];
			}
		}
		if (present == 0) return quota;

		// More lineages than slots: show the most abundant, one each. Nothing else fits.
		if (present >= room) {
			Integer[] order = new Integer[populations.length];
			for (int i = 0; i < order.length; i++) order[i] = i;
			java.util.Arrays.sort(order, (a, b) -> Integer.compare(available[b], available[a]));
			for (int i = 0; i < room; i++) {
				if (available[order[i]] > 0) quota[order[i]] = 1;
			}
			return quota;
		}

		// One each, then the remainder in proportion to what is left.
		int allocated = 0;
		for (int i = 0; i < quota.length; i++) {
			if (available[i] > 0) {
				quota[i] = 1;
				allocated++;
			}
		}
		int remaining = room - allocated;
		int pool = totalAvailable - present;
		for (int i = 0; i < quota.length && remaining > 0 && pool > 0; i++) {
			int spare = available[i] - 1;
			if (spare <= 0) continue;
			int extra = Math.min(spare, Math.min(remaining, Math.round(remaining * (spare / (float) pool))));
			quota[i] += extra;
			remaining -= extra;
		}
		// Rounding can leave slots unspent; hand them out to whoever still has animals to give.
		for (int i = 0; i < quota.length && remaining > 0; i++) {
			int spare = available[i] - quota[i];
			if (spare <= 0) continue;
			int extra = Math.min(spare, remaining);
			quota[i] += extra;
			remaining -= extra;
		}
		return quota;
	}

	/**
	 * Moves one creature out of the world and back into the record.
	 * <p>
	 * Called when a creature despawns or its chunk unloads — never when it dies, because a death is
	 * a real loss to the population and the record should show it.
	 */
	public static void absorb(ServerLevel world, CreatureEntity creature) {
		Genome genome = creature.getGenome();
		if (genome == null) return;

		RegionLedger ledger = RegionLedger.get(world);
		RegionRecord record = ledger.at(RegionPos.of(creature.blockPosition()), world.getSeed());
		LineageRecord lineage = record.lineage(genome.lineage());
		if (lineage == null) {
			lineage = LineageRecord.of(genome, 0f);
			lineage = record.add(lineage);
			if (lineage == null) return;
		} else {
			blendTowards(lineage, genome);
		}
		lineage.give();
		record.dirty = true;
		ledger.setDirty();
	}

	private static void blendTowards(LineageRecord lineage, Genome genome) {
		float weight = 1f / Math.max(1f, lineage.count + 1f);
		float[] values = genome.copyValues();
		float spread = 0f;
		for (int i = 0; i < lineage.mean.length; i++) {
			float delta = values[i] - lineage.mean[i];
			spread += delta * delta;
			lineage.mean[i] += delta * weight;
		}
		float rms = (float) Math.sqrt(spread / lineage.mean.length);
		lineage.variance = lineage.variance + (rms - lineage.variance) * weight;
		lineage.generation = Math.max(lineage.generation, genome.generation());
	}

	public static int countLive(ServerLevel world, RegionPos pos) {
		return liveIn(world, pos).size();
	}

	public static int countLive(ServerLevel world, RegionPos pos, boolean subterranean) {
		int count = 0;
		for (CreatureEntity creature : liveIn(world, pos)) {
			Genome genome = creature.getGenome();
			if (genome == null) continue;
			if (Archetype.isSubterranean(genome) == subterranean) count++;
		}
		return count;
	}

	public static List<CreatureEntity> liveIn(ServerLevel world, RegionPos pos) {
		AABB box = new AABB(
				pos.minBlockX(), world.getMinY(), pos.minBlockZ(),
				pos.minBlockX() + RegionPos.BLOCKS, world.getMaxY(), pos.minBlockZ() + RegionPos.BLOCKS);
		return world.getEntitiesOfClass(CreatureEntity.class, box, RegionMaterialiser::isLedgerManaged);
	}

	public static boolean isLedgerManaged(CreatureEntity creature) {
		return creature.isAlive()
				&& !creature.isCarcass()
				&& !creature.isTamed()
				&& !creature.isPosing()
				&& !creature.hasCustomName();
	}

	private static final int NO_FLOOR = Integer.MIN_VALUE;
	private static final int CAVE_MIN_DEPTH = 8;
	private static final int CAVE_HEADROOM = 2;
	private static final int CAVE_MAX_LIGHT = 7;

	private static int findCaveFloor(ServerLevel world, int x, int z, int surface, Random random) {
		int top = surface - CAVE_MIN_DEPTH;
		int bottom = Math.max(world.getMinY() + 2, surface - 90);
		if (top <= bottom) return NO_FLOOR;

		int span = top - bottom;
		int offset = random.nextInt(span);
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

		for (int i = 0; i < span; i++) {
			int y = top - ((offset + i) % span);
			cursor.set(x, y - 1, z);
			if (!world.getBlockState(cursor).isSolidRender()) continue;

			boolean clear = true;
			for (int h = 0; h < CAVE_HEADROOM && clear; h++) {
				cursor.set(x, y + h, z);
				clear = world.getBlockState(cursor).isAir();
			}
			if (!clear) continue;

			cursor.set(x, y, z);
			if (world.getMaxLocalRawBrightness(cursor) > CAVE_MAX_LIGHT) continue;
			return y;
		}
		return NO_FLOOR;
	}

	private static boolean place(ServerLevel world, RegionPos pos, LineageRecord lineage, Random random) {
		boolean underground = Archetype.isSubterranean(lineage.meanGenome());

		List<net.minecraft.world.level.ChunkPos> loadedChunks = new ArrayList<>();
		int minChunkX = pos.minBlockX() >> 4;
		int minChunkZ = pos.minBlockZ() >> 4;
		for (int cx = minChunkX; cx < minChunkX + RegionPos.CHUNKS; cx++) {
			for (int cz = minChunkZ; cz < minChunkZ + RegionPos.CHUNKS; cz++) {
				// hasChunk takes chunk coordinates. hasChunkAt takes *block* coordinates and shifts
				// them down itself, so passing chunk coordinates to it shifted them a second time
				// and probed chunk (x >> 8) instead of (x >> 4).
				//
				// The two agree at the origin and diverge linearly with distance, which is why this
				// only showed up far from spawn: past a few hundred blocks the probe lands on a
				// chunk nowhere near the player, hasChunk says no for all sixty-four, loadedChunks
				// comes back empty and the region materialises nothing at all. Reported as
				// creatures simply not being there after flying out a long way.
				if (world.hasChunk(cx, cz)) {
					loadedChunks.add(new net.minecraft.world.level.ChunkPos(cx, cz));
				}
			}
		}
		if (loadedChunks.isEmpty()) return false;

		int attempts = underground ? CAVE_PLACEMENT_ATTEMPTS : PLACEMENT_ATTEMPTS;
		for (int attempt = 0; attempt < attempts; attempt++) {
			net.minecraft.world.level.ChunkPos chunk = loadedChunks.get(random.nextInt(loadedChunks.size()));
			int x = chunk.getMinBlockX() + random.nextInt(16);
			int z = chunk.getMinBlockZ() + random.nextInt(16);

			int surface = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			int y = underground ? findCaveFloor(world, x, z, surface, random) : surface;
			if (y == NO_FLOOR) continue;

			BlockPos ground = new BlockPos(x, y - 1, z);
			BlockState state = world.getBlockState(ground);
			if (!state.isSolidRender()) continue;
			BlockPos spawn = new BlockPos(x, y, z);
			BlockState spawnState = world.getBlockState(spawn);
			if (!spawnState.getCollisionShape(world, spawn).isEmpty() && !spawnState.canBeReplaced()) continue;

			CreatureEntity creature = PrimordiaEntities.CREATURE.create(world, EntitySpawnReason.NATURAL);
			if (creature == null) return false;
			creature.setGenome(lineage.sample(random));

			int clearance = Math.max(1, (int) Math.ceil(creature.getBbHeight()));
			boolean fits = true;
			for (int dy = 0; dy < clearance && fits; dy++) {
				BlockPos checkPos = spawn.above(dy);
				BlockState checkState = world.getBlockState(checkPos);
				fits = checkState.getCollisionShape(world, checkPos).isEmpty() || checkState.canBeReplaced();
			}
			if (!fits) continue;
			creature.snapTo(x + 0.5, y, z + 0.5, random.nextFloat() * 360f, 0f);
			creature.finalizeSpawn(world, world.getCurrentDifficultyAt(spawn), EntitySpawnReason.NATURAL, null);
			world.addFreshEntity(creature);
			return true;
		}
		return false;
	}
}
