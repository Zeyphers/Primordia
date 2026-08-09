package dev.jsz.primordia.mesh;

import dev.jsz.primordia.Primordia;
import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanCache;
import dev.jsz.primordia.body.SkeletonPlan;
import dev.jsz.primordia.genome.Genome;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Baked meshes, keyed by (genome, LOD). Two creatures with the same genome share one mesh, so a
 * herd of forty siblings costs a single bake.
 * <p>
 * Baking is <b>never</b> done on the render thread — a bake is tens of milliseconds, which would
 * be a visible hitch every time a new species walks into view. {@link #getIfReady} returns null
 * while a bake is in flight and the renderer simply skips that creature for a frame or two.
 */
public final class GenomeMeshCache {
	/**
	 * Distinct (genome, LOD) meshes held before eviction begins. Raised by the higher quality
	 * presets: holding more creatures on screen at fine detail is pointless if the cache is too
	 * small to keep their meshes, since they then rebake continuously as they cycle through it.
	 */
	private static volatile int maxEntries = 384;

	public static void setMaxEntries(int entries) {
		maxEntries = Math.max(32, entries);
		evictIfNeeded();
	}

	/**
	 * A skeleton is a second mesh for the same genome and tier, so the flag has to be part of the
	 * key — without it a carcass and its own remains would share one cache entry and whichever
	 * baked first would be handed to both.
	 */
	private record Key(Genome genome, int lod, boolean skeleton) {
	}

	private static final Map<Key, MeshData> READY = new ConcurrentHashMap<>();
	private static final Map<Key, Boolean> IN_FLIGHT = new ConcurrentHashMap<>();
	private static final ConcurrentLinkedQueue<Key> ORDER = new ConcurrentLinkedQueue<>();

	private static final ExecutorService BAKERS = Executors.newFixedThreadPool(
			Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() / 2)),
			new ThreadFactory() {
				private final AtomicInteger counter = new AtomicInteger();

				@Override
				public Thread newThread(Runnable r) {
					Thread t = new Thread(r, "primordia-mesh-bake-" + counter.incrementAndGet());
					// Daemon so a hung bake can never keep the game from exiting.
					t.setDaemon(true);
					t.setPriority(Thread.NORM_PRIORITY - 1);
					return t;
				}
			});

	private GenomeMeshCache() {
	}

	/**
	 * Returns the baked mesh if it is already available, otherwise schedules a bake and returns
	 * {@code null}. Safe to call every frame — repeat calls for an in-flight bake are no-ops.
	 */
	public static MeshData getIfReady(Genome genome, int lod) {
		return getIfReady(genome, lod, false);
	}

	/**
	 * @param skeleton bake the bones rather than the body — see {@link SkeletonPlan}
	 */
	public static MeshData getIfReady(Genome genome, int lod, boolean skeleton) {
		Key key = new Key(genome, lod, skeleton);
		MeshData ready = READY.get(key);
		if (ready != null) return ready;

		if (IN_FLIGHT.putIfAbsent(key, Boolean.TRUE) != null) return null;

		BAKERS.submit(() -> {
			try {
				BodyPlan plan = BodyPlanCache.get(genome);
				if (skeleton) plan = SkeletonPlan.of(plan);
				MeshData mesh = MeshBaker.bake(plan, LodTier.resolutionFor(lod));
				READY.put(key, mesh);
				ORDER.add(key);
				evictIfNeeded();
			} catch (Throwable t) {
				// A bad genome must degrade to an invisible creature, never to a crashed render thread.
				Primordia.LOGGER.error("Mesh bake failed for {} at LOD {} (skeleton={})",
						genome, lod, skeleton, t);
			} finally {
				IN_FLIGHT.remove(key);
			}
		});
		return null;
	}

	private static void evictIfNeeded() {
		while (READY.size() > maxEntries) {
			Key evict = ORDER.poll();
			if (evict == null) break;
			READY.remove(evict);
		}
	}

	public static void clear() {
		READY.clear();
		ORDER.clear();
	}

	public static int readyCount() {
		return READY.size();
	}

	public static int pendingCount() {
		return IN_FLIGHT.size();
	}
}
