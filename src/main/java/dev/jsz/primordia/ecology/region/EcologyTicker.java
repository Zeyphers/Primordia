package dev.jsz.primordia.ecology.region;

import dev.jsz.primordia.ecology.WorldImpact;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Drives the ledger: founds regions the world has not seen, catches up the ones the player has been
 * away from, and keeps the live entity population topped up where they are standing.
 * <p>
 * The division of labour between the two simulations is the whole thing, and it is decided here:
 * <b>a region the player is in is simulated by its entities; a region the player is not in is
 * simulated by arithmetic.</b> Running both over the same region would count every birth and death
 * twice, so a region is integrated exactly once — on the tick it becomes active, covering the whole
 * span it was away — and then held current for as long as anyone is there.
 */
public final class EcologyTicker {
	/** Ticks between passes. Five seconds; nothing here needs to be prompt. */
	private static final int INTERVAL = 100;
	/**
	 * Regions around each player kept active, as a Chebyshev radius. One means a 3×3 block of
	 * regions, 384 blocks square — comfortably past what a player can see, so populations are
	 * settled well before they come into view.
	 */
	private static final int ACTIVE_RADIUS = 1;
	/**
	 * Most regions founded in a single pass.
	 * <p>
	 * Founding runs a few hundred simulation steps, so a player crossing unexplored terrain at
	 * speed could otherwise ask for a dozen pre-histories on one tick. The rest wait for the next
	 * pass five seconds later, by which time they are still well outside view distance.
	 */
	private static final int FOUNDINGS_PER_PASS = 2;

	/** Live surface creatures allowed across the whole active area. */
	private static final int CLUSTER_BUDGET = RegionMaterialiser.CLUSTER_BUDGET;
	/**
	 * Live cave creatures allowed across the whole active area, budgeted apart from the surface.
	 * <p>
	 * Lower, because they are tiny and underground sight lines are short — but never zero, which is
	 * what a shared budget amounted to in practice.
	 */
	private static final int CAVE_CLUSTER_BUDGET = 18;

	/** Regions that were active on the previous pass, per world. */
	private static final Map<ServerWorld, Set<Long>> ACTIVE = new HashMap<>();

	private EcologyTicker() {
	}

	public static void register() {
		ServerTickEvents.END_WORLD_TICK.register(EcologyTicker::tick);
		// The active-region map and the per-chunk change budgets are keyed by world and by chunk,
		// and both would otherwise survive a player leaving a single-player world and loading a
		// different one — holding the old world object alive, and carrying its spent terrain budget
		// into a world that never spent it.
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			ACTIVE.clear();
			WorldImpact.reset();
		});
	}

	private static void tick(ServerWorld world) {
		if (world.getTime() % INTERVAL != 0) return;
		if (world.getPlayers().isEmpty()) return;

		RegionLedger ledger = RegionLedger.get(world);
		long day = world.getTime() / RegionSimulation.TICKS_PER_STEP;

		Set<Long> previouslyActive = ACTIVE.computeIfAbsent(world, w -> new HashSet<>());
		Set<Long> nowActive = new HashSet<>();
		int foundings = 0;
		// Counted once across the whole pass rather than per region, because what matters is how
		// many creatures are near the player, not how they are distributed between records.
		//
		// Kept apart by habitat. A single shared count meant the surface fauna — placed first, and
		// far more numerous — spent the whole allowance before the cave pass was reached, so a
		// player underground found nothing at all however many the record said were down there.
		// The two are never in view together, so they were never really competing for anything.
		int clusterSurface = 0;
		int clusterCaves = 0;

		for (ServerPlayerEntity player : world.getPlayers()) {
			RegionPos centre = RegionPos.of(player.getBlockPos());
			for (int dx = -ACTIVE_RADIUS; dx <= ACTIVE_RADIUS; dx++) {
				for (int dz = -ACTIVE_RADIUS; dz <= ACTIVE_RADIUS; dz++) {
					RegionPos pos = centre.offset(dx, dz);
					if (!nowActive.add(pos.key())) continue;

					RegionRecord record = ledger.at(pos, world.getSeed());
					if (!record.founded) {
						if (foundings >= FOUNDINGS_PER_PASS) {
							nowActive.remove(pos.key());
							continue;
						}
						foundings++;
						RegionFounder.found(ledger, record,
								RegionClimate.sample(world, pos), biomeName(world, pos), day);
					} else if (record.version < RegionFounder.VERSION) {
						// Founded by an older build. Bring it forward rather than leaving the
						// player's existing world permanently without whatever has been added since.
						RegionFounder.upgrade(record, RegionClimate.sample(world, pos));
					}

					// Only on the pass where it becomes active: this covers the whole absence in
					// one go, and every later pass leaves the entities to it.
					if (!previouslyActive.contains(pos.key())) {
						RegionSimulation.integrate(ledger, record, day);
					} else {
						RegionSimulation.skipTo(record, day);
					}

					clusterSurface += RegionMaterialiser.countLive(world, pos, false);
					clusterCaves += RegionMaterialiser.countLive(world, pos, true);
					RegionMaterialiser.topUp(world, record,
							clusterSurface < CLUSTER_BUDGET,
							clusterCaves < CAVE_CLUSTER_BUDGET);
				}
			}
		}

		previouslyActive.clear();
		previouslyActive.addAll(nowActive);
		ledger.commit();
	}

	private static String biomeName(ServerWorld world, RegionPos pos) {
		BlockPos centre = new BlockPos(pos.centreBlockX(), world.getSeaLevel(), pos.centreBlockZ());
		return world.getBiome(centre).getKey().map(key -> key.getValue().getPath()).orElse("");
	}
}
