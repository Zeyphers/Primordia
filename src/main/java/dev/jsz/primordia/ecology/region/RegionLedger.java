package dev.jsz.primordia.ecology.region;

import dev.jsz.primordia.Primordia;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The world's memory of its own ecology, one record per region, persisted with the save.
 * <p>
 * Kept as a single {@link PersistentState} rather than as chunk data on purpose. A region's record
 * has to be readable when its chunks are <i>not</i> loaded — migration reads its neighbours, and the
 * regional simulation integrates regions the player has left — and chunk-attached data is by
 * definition unavailable exactly then.
 * <p>
 * Size: a region record is under a kilobyte, so ten thousand visited regions cost under ten
 * megabytes. That is the trade the whole design rests on — a population as numbers is four orders
 * of magnitude cheaper than a population as entities, which is what makes simulating the parts of
 * the world nobody is looking at affordable at all.
 */
public class RegionLedger extends PersistentState implements RegionNeighbourhood {
	private static final String STATE_ID = Primordia.MOD_ID + "_regions";

	private final Map<Long, RegionRecord> records = new HashMap<>();

	public static final Type<RegionLedger> TYPE = new Type<>(
			RegionLedger::new, RegionLedger::readNbt, null);

	public RegionLedger() {
	}

	public static RegionLedger get(ServerWorld world) {
		return world.getPersistentStateManager().getOrCreate(TYPE, STATE_ID);
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
			markDirty();
			return record;
		});
	}

	public RegionRecord at(ServerWorld world, BlockPos pos) {
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
		if (any) markDirty();
	}

	// ---------------------------------------------------------------------- nbt

	@Override
	public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
		NbtCompound regions = new NbtCompound();
		for (Map.Entry<Long, RegionRecord> entry : records.entrySet()) {
			regions.put(Long.toString(entry.getKey()), entry.getValue().writeNbt());
		}
		nbt.put("Regions", regions);
		return nbt;
	}

	public static RegionLedger readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
		RegionLedger ledger = new RegionLedger();
		NbtCompound regions = nbt.getCompound("Regions");
		for (String key : regions.getKeys()) {
			try {
				long packed = Long.parseLong(key);
				RegionPos pos = RegionPos.fromKey(packed);
				ledger.records.put(packed, RegionRecord.readNbt(pos, regions.getCompound(key)));
			} catch (NumberFormatException ignored) {
				// A key that is not a packed region coordinate is not ours. Skipping it is better
				// than failing to load the save.
			}
		}
		return ledger;
	}
}
