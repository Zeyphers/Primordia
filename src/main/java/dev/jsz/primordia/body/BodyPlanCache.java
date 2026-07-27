package dev.jsz.primordia.body;

import dev.jsz.primordia.genome.Genome;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Memoises {@link BodyPlanBuilder#build} per genome. Both the animator (every frame, both
 * logical sides) and the mesher need the plan, and building one allocates a few hundred small
 * objects — cheap, but not "sixty times a second per creature" cheap.
 * <p>
 * Bounded FIFO eviction rather than true LRU: plans are small and rebuilding one is
 * inexpensive, so the extra bookkeeping of access-order tracking is not worth it.
 */
public final class BodyPlanCache {
	private static final int MAX_ENTRIES = 512;

	private static final Map<Genome, BodyPlan> CACHE = new ConcurrentHashMap<>();
	private static final ConcurrentLinkedQueue<Genome> ORDER = new ConcurrentLinkedQueue<>();

	private BodyPlanCache() {
	}

	public static BodyPlan get(Genome genome) {
		BodyPlan cached = CACHE.get(genome);
		if (cached != null) return cached;

		BodyPlan plan = BodyPlanBuilder.build(genome);
		if (CACHE.putIfAbsent(genome, plan) == null) {
			ORDER.add(genome);
			while (CACHE.size() > MAX_ENTRIES) {
				Genome evict = ORDER.poll();
				if (evict == null) break;
				CACHE.remove(evict);
			}
		}
		return CACHE.getOrDefault(genome, plan);
	}

	public static void clear() {
		CACHE.clear();
		ORDER.clear();
	}

	public static int size() {
		return CACHE.size();
	}
}
