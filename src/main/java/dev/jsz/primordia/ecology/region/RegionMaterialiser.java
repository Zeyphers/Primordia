package dev.jsz.primordia.ecology.region;

import dev.jsz.primordia.entity.CreatureEntity;
import dev.jsz.primordia.genome.Archetype;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.registry.PrimordiaEntities;
import net.minecraft.block.BlockState;
import net.minecraft.entity.SpawnReason;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.Heightmap;

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
	public static void topUp(ServerWorld world, RegionRecord record) {
		topUp(world, record, true, true);
	}

	/**
	 * Tops up one or both habitats. The caller gates them separately because they draw on separate
	 * budgets — see {@code EcologyTicker}.
	 */
	public static void topUp(ServerWorld world, RegionRecord record,
	                         boolean surface, boolean caves) {
		Random random = new Random(record.seed ^ world.getTime());
		// Surface and cave fauna are budgeted apart.
		//
		// They occupy different parts of the same region and are never in view together, so a
		// shared budget is not a shared resource — it is the surface animals, which are placed
		// first and are more numerous, quietly consuming every slot before the cave ones are
		// considered. The symptom is an empty cave under a perfectly busy meadow.
		if (surface) topUp(world, record, random, false, ENTITY_BUDGET);
		if (caves) topUp(world, record, random, true, CAVE_ENTITY_BUDGET);
	}

	private static void topUp(ServerWorld world, RegionRecord record, Random random,
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
	public static void absorb(ServerWorld world, CreatureEntity creature) {
		Genome genome = creature.getGenome();
		if (genome == null) return;

		RegionLedger ledger = RegionLedger.get(world);
		RegionRecord record = ledger.at(RegionPos.of(creature.getBlockPos()), world.getSeed());
		LineageRecord lineage = record.lineage(genome.lineage());
		if (lineage == null) {
			// A lineage the record has never heard of: bred in the world, or walked in from a
			// neighbouring region. Either way it lives here now.
			lineage = LineageRecord.of(genome, 0f);
			lineage = record.add(lineage);
			if (lineage == null) return;
		} else {
			// Pull the recorded mean a little toward this individual. Over many absorptions this
			// is how a population's genome in the ledger tracks what actually bred in the world,
			// rather than the record and the entities drifting apart.
			blendTowards(lineage, genome);
		}
		lineage.give();
		record.dirty = true;
		ledger.markDirty();
	}

	/**
	 * Eases the recorded mean toward an individual that is being absorbed, and widens the variance
	 * by however far off it was.
	 * <p>
	 * Weighted by how many the record already holds, so one unusual animal shifts a population of
	 * fifty barely at all and a population of two considerably — which is genuine drift, and the
	 * reason small isolated populations diverge fastest.
	 */
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

	/** Creatures currently alive in this region that the ledger is responsible for. */
	public static int countLive(ServerWorld world, RegionPos pos) {
		return liveIn(world, pos).size();
	}

	/** As above, counting only one habitat's animals. */
	public static int countLive(ServerWorld world, RegionPos pos, boolean subterranean) {
		int count = 0;
		for (CreatureEntity creature : liveIn(world, pos)) {
			Genome genome = creature.getGenome();
			if (genome == null) continue;
			if (Archetype.isSubterranean(genome) == subterranean) count++;
		}
		return count;
	}

	public static List<CreatureEntity> liveIn(ServerWorld world, RegionPos pos) {
		Box box = new Box(
				pos.minBlockX(), world.getBottomY(), pos.minBlockZ(),
				pos.minBlockX() + RegionPos.BLOCKS, world.getTopY(), pos.minBlockZ() + RegionPos.BLOCKS);
		return world.getEntitiesByClass(CreatureEntity.class, box, RegionMaterialiser::isLedgerManaged);
	}

	/**
	 * Whether the ledger accounts for this creature.
	 * <p>
	 * Tamed animals are individuals, not population: they belong to a player, they persist, they do
	 * not despawn, and counting them would have the ledger repopulate a region from animals that
	 * are following someone else across the world. Carcasses are not population either — they are
	 * already dead and were subtracted when they died.
	 */
	public static boolean isLedgerManaged(CreatureEntity creature) {
		return creature.isAlive()
				&& !creature.isCarcass()
				&& !creature.isTamed()
				&& !creature.isPosing()
				&& !creature.hasCustomName();
	}

	/** Returned by {@link #findCaveFloor} when the column holds nothing habitable. */
	private static final int NO_FLOOR = Integer.MIN_VALUE;
	/** Blocks below the surface a cave has to be before it counts as one. */
	private static final int CAVE_MIN_DEPTH = 8;
	/** Ceiling height a cave floor needs above it. Two blocks: these are small animals. */
	private static final int CAVE_HEADROOM = 2;
	/** Light level at or below which a space is dark enough to be a cave rather than a hillside. */
	private static final int CAVE_MAX_LIGHT = 7;

	/**
	 * Finds a dark, enclosed floor somewhere down the column, for a lineage that lives underground.
	 * <p>
	 * Scans downward from below the surface rather than upward from bedrock, so the animals collect
	 * in the shallow cave systems a player actually walks through instead of at the bottom of the
	 * world where nobody would ever meet them.
	 * <p>
	 * The light test is what distinguishes a cave from an overhang. Depth alone would place them
	 * under any cliff face, and the point of a cave animal is that it is somewhere dark.
	 */
	private static int findCaveFloor(ServerWorld world, int x, int z, int surface, Random random) {
		int top = surface - CAVE_MIN_DEPTH;
		int bottom = Math.max(world.getBottomY() + 2, surface - 90);
		if (top <= bottom) return NO_FLOOR;

		// A random start, walked downward and wrapped, so repeated calls in the same column do not
		// all pile onto the topmost cave in it.
		int span = top - bottom;
		int offset = random.nextInt(span);
		BlockPos.Mutable cursor = new BlockPos.Mutable();

		for (int i = 0; i < span; i++) {
			int y = top - ((offset + i) % span);
			cursor.set(x, y - 1, z);
			if (!world.getBlockState(cursor).isSolidBlock(world, cursor)) continue;

			boolean clear = true;
			for (int h = 0; h < CAVE_HEADROOM && clear; h++) {
				cursor.set(x, y + h, z);
				clear = world.getBlockState(cursor).isAir();
			}
			if (!clear) continue;

			cursor.set(x, y, z);
			if (world.getLightLevel(cursor) > CAVE_MAX_LIGHT) continue;
			return y;
		}
		return NO_FLOOR;
	}

	/** Places one individual of a lineage somewhere sensible in the region. */
	private static boolean place(ServerWorld world, RegionPos pos, LineageRecord lineage, Random random) {
		boolean underground = Archetype.isSubterranean(lineage.meanGenome());

		int attempts = underground ? CAVE_PLACEMENT_ATTEMPTS : PLACEMENT_ATTEMPTS;
		for (int attempt = 0; attempt < attempts; attempt++) {
			int x = pos.minBlockX() + random.nextInt(RegionPos.BLOCKS);
			int z = pos.minBlockZ() + random.nextInt(RegionPos.BLOCKS);
			if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;

			int surface = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
			int y = underground ? findCaveFloor(world, x, z, surface, random) : surface;
			if (y == NO_FLOOR) continue;

			BlockPos ground = new BlockPos(x, y - 1, z);
			BlockState state = world.getBlockState(ground);
			if (!state.isSolidBlock(world, ground)) continue;
			BlockPos spawn = new BlockPos(x, y, z);
			if (!world.getBlockState(spawn).isAir()) continue;

			CreatureEntity creature = PrimordiaEntities.CREATURE.create(world);
			if (creature == null) return false;
			creature.setGenome(lineage.sample(random));

			// Headroom for the animal actually being placed, not for a one-block gap. A large
			// creature dropped into a clearing just tall enough for a chicken has its eye inside
			// the block above and takes suffocation damage from the moment it exists.
			int clearance = Math.max(1, (int) Math.ceil(creature.getHeight()));
			boolean fits = true;
			for (int dy = 0; dy < clearance && fits; dy++) {
				fits = world.getBlockState(spawn.up(dy)).isAir();
			}
			if (!fits) continue;
			creature.refreshPositionAndAngles(x + 0.5, y, z + 0.5, random.nextFloat() * 360f, 0f);
			creature.initialize(world, world.getLocalDifficulty(spawn), SpawnReason.NATURAL, null);
			world.spawnEntity(creature);
			return true;
		}
		return false;
	}
}
