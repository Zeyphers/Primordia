package dev.jsz.primordia.ecology.region;

import com.mojang.serialization.Codec;
import dev.jsz.primordia.Primordia;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The world's memory of its own ecology, one record per region, persisted with the save.
 * <p>
 * Kept as a single {@link SavedData} rather than as chunk data on purpose. A region's record
 * has to be readable when its chunks are <i>not</i> loaded — migration reads its neighbours, and the
 * regional simulation integrates regions the player has left — and chunk-attached data is by
 * definition unavailable exactly then.
 * <p>
 * Size: a region record is under a kilobyte, so ten thousand visited regions cost under ten
 * megabytes. That is the trade the whole design rests on — a population as numbers is four orders
 * of magnitude cheaper than a population as entities, which is what makes simulating the parts of
 * the world nobody is looking at affordable at all.
 */
public class RegionLedger extends SavedData implements RegionNeighbourhood {
	/** The mod id now comes from the identifier's namespace, so the path is the bare noun. */
	private static final String STATE_ID = "regions";

	private final Map<Long, RegionRecord> records = new HashMap<>();

	/**
	 * Persistence goes through a compound tag rather than a hand-written record codec: the ledger
	 * is a sparse map keyed by packed region coordinates, which a struct codec expresses badly and
	 * an NBT map expresses exactly.
	 * <p>
	 * The data fixer type is nominal. {@code DataFixTypes.update} only rewrites a tag whose stored
	 * version predates the running one, and this mod's 26.2 line writes no older versions, so no
	 * vanilla fixer ever sees these keys.
	 */
	public static final Codec<RegionLedger> CODEC =
			CompoundTag.CODEC.xmap(RegionLedger::readNbt, RegionLedger::toNbt);

	public static final SavedDataType<RegionLedger> TYPE = new SavedDataType<>(
			Primordia.id(STATE_ID), RegionLedger::new, CODEC,
			DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES);

	public RegionLedger() {
	}

	public static RegionLedger get(ServerLevel world) {
		return world.getDataStorage().computeIfAbsent(TYPE);
	}

	/**
	 * The record for this region, creating an empty one if the world has never seen it.
	 * <p>
	 * Creating rather than returning null is deliberate: every caller wants a record, and the
	 * distinction that matters is {@link RegionRecord#founded}, not whether the map has a key.
	 */
	public RegionRecord at(RegionPos pos, long worldSeed) {
		return records.computeIfAbsent(pos.key(), key -> {
			RegionRecord record = new RegionRecord(pos);
			record.seed = pos.seed(worldSeed);
			setDirty();
			return record;
		});
	}

	public RegionRecord at(ServerLevel world, BlockPos pos) {
		return at(RegionPos.of(pos), world.getSeed());
	}

	/** The record for this region only if it already exists. Null means never visited. */
	public RegionRecord existing(RegionPos pos) {
		return records.get(pos.key());
	}

	public List<RegionRecord> all() {
		return new ArrayList<>(records.values());
	}

	public int size() {
		return records.size();
	}

	/** Flushes any record that changed since the last save. */
	public void commit() {
		boolean any = false;
		for (RegionRecord record : records.values()) {
			if (record.dirty) {
				record.dirty = false;
				any = true;
			}
		}
		if (any) setDirty();
	}

	// ---------------------------------------------------------------------- nbt

	public CompoundTag toNbt() {
		CompoundTag nbt = new CompoundTag();
		CompoundTag regions = new CompoundTag();
		for (Map.Entry<Long, RegionRecord> entry : records.entrySet()) {
			regions.put(Long.toString(entry.getKey()), entry.getValue().writeNbt());
		}
		nbt.put("Regions", regions);
		return nbt;
	}

	public static RegionLedger readNbt(CompoundTag nbt) {
		RegionLedger ledger = new RegionLedger();
		CompoundTag regions = nbt.getCompoundOrEmpty("Regions");
		for (String key : regions.keySet()) {
			try {
				long packed = Long.parseLong(key);
				RegionPos pos = RegionPos.fromKey(packed);
				ledger.records.put(packed, RegionRecord.readNbt(pos, regions.getCompoundOrEmpty(key)));
			} catch (NumberFormatException ignored) {
			}
		}
		return ledger;
	}
}
