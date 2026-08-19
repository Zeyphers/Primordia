package dev.jsz.primordia.splice;

import dev.jsz.primordia.Primordia;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Every player's splices, stored the way {@code PlayerGuideData} stores their guide.
 * <p>
 * Deliberately the same shape and the same home. {@code MD/SPLICING.md} §10 leaves one multiplayer
 * question open — {@code GenomeLibrary} is world-scoped and framed as "what this world's science
 * knows", so one player's study work unlocking another's nodes needs a decision — and this class
 * takes the side the document argues for by keeping only the <i>loadout</i> per player. What you
 * carry is yours; what is known is the world's. A party can therefore divide the survey between
 * them and each still choose their own animal to become.
 */
public class PlayerSpliceData extends SavedData {

	private static final String KEY = "primordia_player_splices";

	private final Map<UUID, SpliceLoadout> loadouts = new HashMap<>();

	public static final com.mojang.serialization.Codec<PlayerSpliceData> CODEC =
			CompoundTag.CODEC.xmap(PlayerSpliceData::fromNbt, PlayerSpliceData::toNbt);

	public static final SavedDataType<PlayerSpliceData> TYPE = new SavedDataType<>(
			Primordia.id(KEY), PlayerSpliceData::new, CODEC,
			DataFixTypes.SAVED_DATA_MAP_DATA);

	public static PlayerSpliceData get(ServerLevel world) {
		// What a player is does not change with the dimension they walk into.
		SavedDataStorage manager = world.getServer().overworld().getDataStorage();
		return manager.computeIfAbsent(TYPE);
	}

	public SpliceLoadout get(UUID playerId) {
		return loadouts.computeIfAbsent(playerId, id -> new SpliceLoadout());
	}

	public void put(UUID playerId, SpliceLoadout loadout) {
		loadouts.put(playerId, loadout);
		setDirty();
	}

	public CompoundTag toNbt() {
		CompoundTag nbt = new CompoundTag();
		ListTag list = new ListTag();
		for (Map.Entry<UUID, SpliceLoadout> entry : loadouts.entrySet()) {
			CompoundTag tag = new CompoundTag();
			tag.putString("Player", entry.getKey().toString());
			tag.put("Loadout", entry.getValue().writeNbt());
			list.add(tag);
		}
		nbt.put("Players", list);
		return nbt;
	}

	private static PlayerSpliceData fromNbt(CompoundTag nbt) {
		PlayerSpliceData data = new PlayerSpliceData();
		ListTag list = nbt.getListOrEmpty("Players");
		for (int i = 0; i < list.size(); i++) {
			CompoundTag tag = list.getCompoundOrEmpty(i);
			String uuid = tag.getStringOr("Player", "");
			if (uuid.isEmpty()) continue;
			data.loadouts.put(UUID.fromString(uuid),
					SpliceLoadout.fromNbt(tag.getCompoundOrEmpty("Loadout")));
		}
		return data;
	}
}
