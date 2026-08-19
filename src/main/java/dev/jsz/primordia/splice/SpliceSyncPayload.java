package dev.jsz.primordia.splice;

import dev.jsz.primordia.Primordia;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * The player's own loadout, pushed to their client so the guide can draw what they are carrying.
 * <p>
 * Only the loadout travels. The tree itself is derived from {@code GuideData}, which is already
 * synced, so every unlock condition, every progress count and every donor on the shopping list is
 * computed client-side with no packet — see {@link SpliceTree}.
 */
public record SpliceSyncPayload(CompoundTag data) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<SpliceSyncPayload> TYPE =
			new CustomPacketPayload.Type<>(Primordia.id("splice_sync"));

	public static final StreamCodec<RegistryFriendlyByteBuf, SpliceSyncPayload> CODEC =
			StreamCodec.composite(
					ByteBufCodecs.COMPOUND_TAG, SpliceSyncPayload::data,
					SpliceSyncPayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
	}
}
