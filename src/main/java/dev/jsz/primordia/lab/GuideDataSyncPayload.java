package dev.jsz.primordia.lab;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import dev.jsz.primordia.Primordia;

public record GuideDataSyncPayload(CompoundTag data) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<GuideDataSyncPayload> TYPE =
			new CustomPacketPayload.Type<>(Primordia.id("guide_data_sync"));

	public static final StreamCodec<RegistryFriendlyByteBuf, GuideDataSyncPayload> CODEC =
			StreamCodec.composite(
					ByteBufCodecs.COMPOUND_TAG, GuideDataSyncPayload::data,
					GuideDataSyncPayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
	}
}
