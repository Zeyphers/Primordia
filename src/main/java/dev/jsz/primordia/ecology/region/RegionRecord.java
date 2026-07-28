package dev.jsz.primordia.ecology.region;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the world remembers about one region while nobody is standing in it.
 * <p>
 * This is the truth, and entities are a rendering of it. Cull six herbivores from a valley and the
 * count here drops by six; leave, come back in a week, and it has recovered or it has not, according
 * to what else lives here. Before this existed, populations simply froze on unload and the world was
 * exactly as the player left it, which is the opposite of the intended feeling.
 */
public final class RegionRecord {
	/**
	 * Most lineages a region will hold. Beyond this the weakest is dropped when a new one arrives.
	 * <p>
	 * Eight is a deliberate constraint on the fiction as much as on the file size: a region with
	 * twenty coexisting species reads as noise, and a region with three or four distinct animals
	 * that are visibly adapted to it reads as a place.
	 */
	public static final int MAX_LINEAGES = 8;

	public final RegionPos pos;
	/** Stable per-region seed; everything stochastic here derives from it. */
	public long seed;
	/** Game day at which this record was last integrated forward. */
	public long lastStep;
	/** Standing plant stock, 0 barren to 1 lush. */
	public float vegetation = 0.5f;
	/** The biome's ceiling on vegetation and its regrowth rate, sampled once at founding. */
	public float productivity = 0.5f;
	/**
	 * The biome's climate, normalised to the same [0,1] scale as {@link dev.jsz.primordia.genome.Gene#TEMP_PREFERENCE}
	 * and {@link dev.jsz.primordia.genome.Gene#HUMIDITY_PREFERENCE}. Selection pulls a lineage's
	 * preference loci toward these, which is what makes a region's fauna come to fit it.
	 */
	public float temperature = 0.5f;
	public float humidity = 0.5f;
	/** Whether founding fauna have been generated for this region yet. */
	public boolean founded;

	public final List<LineageRecord> lineages = new ArrayList<>();

	/** Set whenever anything changes, so the ledger knows to persist. */
	public transient boolean dirty;

	public RegionRecord(RegionPos pos) {
		this.pos = pos;
	}

	public LineageRecord lineage(long id) {
		for (LineageRecord record : lineages) {
			if (record.id == id) return record;
		}
		return null;
	}

	/**
	 * Adds a lineage, displacing the smallest population if the region is already full.
	 * Returns the record actually stored, or null if it was not worth keeping.
	 */
	public LineageRecord add(LineageRecord record) {
		LineageRecord existing = lineage(record.id);
		if (existing != null) {
			existing.count += record.count;
			existing.held += record.held;
			dirty = true;
			return existing;
		}
		if (lineages.size() >= MAX_LINEAGES) {
			LineageRecord weakest = null;
			for (LineageRecord candidate : lineages) {
				if (weakest == null || candidate.biomass() < weakest.biomass()) weakest = candidate;
			}
			if (weakest == null || weakest.biomass() >= record.biomass()) return null;
			lineages.remove(weakest);
		}
		lineages.add(record);
		dirty = true;
		return record;
	}

	/** Total living biomass across every lineage. Used by the trophic pyramid check. */
	public float totalBiomass() {
		float sum = 0f;
		for (LineageRecord record : lineages) sum += record.biomass();
		return sum;
	}

	public float totalPopulation() {
		float sum = 0f;
		for (LineageRecord record : lineages) sum += record.total();
		return sum;
	}

	/** Drops lineages that have fallen below viability. Extinction has to be real and permanent. */
	public void pruneExtinct() {
		if (lineages.removeIf(record -> record.total() < 0.5f)) {
			dirty = true;
		}
	}

	// ---------------------------------------------------------------------- nbt

	public NbtCompound writeNbt() {
		NbtCompound nbt = new NbtCompound();
		nbt.putLong("Seed", seed);
		nbt.putLong("LastStep", lastStep);
		nbt.putFloat("Vegetation", vegetation);
		nbt.putFloat("Productivity", productivity);
		nbt.putFloat("Temperature", temperature);
		nbt.putFloat("Humidity", humidity);
		nbt.putBoolean("Founded", founded);
		NbtList list = new NbtList();
		for (LineageRecord record : lineages) {
			list.add(record.writeNbt());
		}
		nbt.put("Lineages", list);
		return nbt;
	}

	public static RegionRecord readNbt(RegionPos pos, NbtCompound nbt) {
		RegionRecord record = new RegionRecord(pos);
		record.seed = nbt.getLong("Seed");
		record.lastStep = nbt.getLong("LastStep");
		record.vegetation = nbt.getFloat("Vegetation");
		record.productivity = nbt.getFloat("Productivity");
		record.temperature = nbt.contains("Temperature") ? nbt.getFloat("Temperature") : 0.5f;
		record.humidity = nbt.contains("Humidity") ? nbt.getFloat("Humidity") : 0.5f;
		record.founded = nbt.getBoolean("Founded");
		NbtList list = nbt.getList("Lineages", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < list.size(); i++) {
			record.lineages.add(LineageRecord.readNbt(list.getCompound(i)));
		}
		return record;
	}
}
