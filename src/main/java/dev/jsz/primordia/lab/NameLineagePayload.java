package dev.jsz.primordia.lab;

import dev.jsz.primordia.Primordia;
import dev.jsz.primordia.registry.PrimordiaItems;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.ItemStack;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Carries a name the player has given a species from their screen to the server.
 * <p>
 * A packet is needed because the guide's contents live on the item stack, and the client's copy of
 * a stack is a replica — writing to it there would look right until the next inventory sync
 * overwrote it. The server owns the book.
 * <p>
 * The server does not take the client's word for anything except the two values here. Whether the
 * species may be named at all is re-checked against the guide the server holds, because a client
 * that has been modified is perfectly capable of claiming it finished work it never did.
 */
public record NameLineagePayload(long lineage, String name) implements CustomPayload {

	public static final CustomPayload.Id<NameLineagePayload> ID =
			new CustomPayload.Id<>(Primordia.id("name_lineage"));

	public static final PacketCodec<RegistryByteBuf, NameLineagePayload> CODEC =
			PacketCodec.tuple(
					PacketCodecs.VAR_LONG, NameLineagePayload::lineage,
					// Bounded on the wire as well as on arrival: an unbounded string field is an
					// invitation to send a megabyte of it.
					PacketCodecs.string(GuideData.MAX_NAME * 4), NameLineagePayload::name,
					NameLineagePayload::new);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}

	/** Registers the type. Must run on both sides, so it lives in the common initialiser. */
	public static void register() {
		PayloadTypeRegistry.playC2S().register(ID, CODEC);

		ServerPlayNetworking.registerGlobalReceiver(ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			context.server().execute(() -> apply(player, payload));
		});
	}

	private static void apply(ServerPlayerEntity player, NameLineagePayload payload) {
		ItemStack guide = ItemStack.EMPTY;
		for (int i = 0; i < player.getInventory().size(); i++) {
			ItemStack candidate = player.getInventory().getStack(i);
			if (candidate.isOf(PrimordiaItems.FIELD_GUIDE)) {
				guide = candidate;
				break;
			}
		}
		if (guide.isEmpty()) return;

		GuideData data = GuideData.get(guide);
		if (!data.rename(payload.lineage(), payload.name())) return;
		data.write(guide);

		String given = payload.name().strip();
		player.sendMessage(Text.literal("Named ").formatted(Formatting.GRAY)
				.append(Text.literal(given).formatted(Formatting.AQUA)), true);
	}
}
