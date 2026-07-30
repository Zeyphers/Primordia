package dev.jsz.primordia.lab;

import dev.jsz.primordia.Primordia;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Persists the player's field guide discoveries as a global server-side record.
 * <p>
 * This replaces storing guide entries as item components on the book itself. A book is now just a
 * key that grants access to the owner's knowledge; losing the book no longer means losing the data,
 * and picking up a new one will show everything you have already found.
 */
public class PlayerGuideData extends SavedData {

	private static final String KEY = "primordia_player_guides";

	private final Map<UUID, GuideData> playerGuides = new HashMap<>();

	public static final com.mojang.serialization.Codec<PlayerGuideData> CODEC =
			CompoundTag.CODEC.xmap(PlayerGuideData::fromNbt, PlayerGuideData::toNbt);

	public static final SavedDataType<PlayerGuideData> TYPE = new SavedDataType<>(
			Primordia.id(KEY), PlayerGuideData::new, CODEC,
			DataFixTypes.SAVED_DATA_MAP_DATA);

	public static PlayerGuideData get(ServerLevel world) {
		// A player's knowledge is global, not tied to the dimension they happen to be in.
		SavedDataStorage manager = world.getServer().overworld().getDataStorage();
		return manager.computeIfAbsent(TYPE);
	}

	public GuideData getGuide(UUID playerId) {
		return playerGuides.computeIfAbsent(playerId, id -> GuideData.empty());
	}

	/** True once {@link #getGuide} has been called for this player at least once before. */
	public boolean hasGuide(UUID playerId) {
		return playerGuides.containsKey(playerId);
	}

	public void putGuide(UUID playerId, GuideData data) {
		playerGuides.put(playerId, data);
		setDirty();
	}

	public CompoundTag toNbt() {
		CompoundTag nbt = new CompoundTag();
		ListTag list = new ListTag();
		for (Map.Entry<UUID, GuideData> entry : playerGuides.entrySet()) {
			CompoundTag playerTag = new CompoundTag();
			playerTag.putString("Player", entry.getKey().toString());
			CompoundTag guideTag = new CompoundTag();
			entry.getValue().writeInto(guideTag);
			playerTag.put("Guide", guideTag);
			list.add(playerTag);
		}
		nbt.put("Players", list);
		return nbt;
	}

	private static PlayerGuideData fromNbt(CompoundTag nbt) {
		PlayerGuideData data = new PlayerGuideData();
		ListTag list = nbt.getListOrEmpty("Players");
		for (int i = 0; i < list.size(); i++) {
			CompoundTag playerTag = list.getCompoundOrEmpty(i);
			if (playerTag.contains("Player")) {
				String uuidStr = playerTag.getStringOr("Player", "");
				if (!uuidStr.isEmpty()) {
					UUID playerId = UUID.fromString(uuidStr);
					CompoundTag guideTag = playerTag.getCompoundOrEmpty("Guide");
					data.playerGuides.put(playerId, GuideData.fromNbt(guideTag));
				}
			}
		}
		return data;
	}
}
