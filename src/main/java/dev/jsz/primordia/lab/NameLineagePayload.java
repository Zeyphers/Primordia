package dev.jsz.primordia.lab;

import dev.jsz.primordia.registry.PrimordiaItems;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

public record NameLineagePayload(long lineage, String name) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<NameLineagePayload> TYPE =
			new CustomPacketPayload.Type<>(dev.jsz.primordia.Primordia.id("name_lineage"));

	public static final StreamCodec<RegistryFriendlyByteBuf, NameLineagePayload> CODEC =
			StreamCodec.composite(
					ByteBufCodecs.VAR_LONG, NameLineagePayload::lineage,
					ByteBufCodecs.stringUtf8(GuideData.MAX_NAME * 4), NameLineagePayload::name,
					NameLineagePayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);

		ServerPlayNetworking.registerGlobalReceiver(TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> apply(player, payload));
		});
	}

	private static void apply(ServerPlayer player, NameLineagePayload payload) {
		dev.jsz.primordia.lab.PlayerGuideData global = dev.jsz.primordia.lab.PlayerGuideData.get((net.minecraft.server.level.ServerLevel) player.level());
		GuideData data = global.getGuide(player.getUUID());
		
		if (!data.rename(payload.lineage(), payload.name())) return;
		global.putGuide(player.getUUID(), data);
		
		net.minecraft.nbt.CompoundTag payloadData = new net.minecraft.nbt.CompoundTag();
		data.writeInto(payloadData);
		ServerPlayNetworking.send(player, new GuideDataSyncPayload(payloadData));

		String given = payload.name().strip();
		player.sendOverlayMessage(Component.literal("Named ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(given).withStyle(ChatFormatting.AQUA)));
	}
}
