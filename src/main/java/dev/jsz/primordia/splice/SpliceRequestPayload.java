package dev.jsz.primordia.splice;

import dev.jsz.primordia.Primordia;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * The player asking to take a block, or to put one back.
 * <p>
 * Named in the same shape as {@code NameLineagePayload}: the screen states an intent and the server
 * decides whether it is allowed. Nothing about the tree is trusted from this side — {@link Splicing}
 * re-derives every unlock from the server's own copy of the guide, because a client that can be made
 * to claim a node is open could otherwise wear anything.
 *
 * @param branchName which branch, by enum name
 * @param lineage    the donor bloodline, or 0 to revert the branch instead
 */
public record SpliceRequestPayload(String branchName, long lineage) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<SpliceRequestPayload> TYPE =
			new CustomPacketPayload.Type<>(Primordia.id("splice_request"));

	public static final StreamCodec<RegistryFriendlyByteBuf, SpliceRequestPayload> CODEC =
			StreamCodec.composite(
					ByteBufCodecs.STRING_UTF8, SpliceRequestPayload::branchName,
					ByteBufCodecs.VAR_LONG, SpliceRequestPayload::lineage,
					SpliceRequestPayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);
		ServerPlayNetworking.registerGlobalReceiver(TYPE, (payload, context) ->
				context.server().execute(() -> handle(context.player(), payload)));
	}

	private static void handle(ServerPlayer player, SpliceRequestPayload payload) {
		SpliceBranch branch = null;
		for (SpliceBranch candidate : SpliceBranch.VALUES) {
			if (candidate.name().equals(payload.branchName())) branch = candidate;
		}
		if (branch == null) return;

		// The bench runs it; the reply here only says whether it was accepted. What the splice
		// actually did is reported when the machine finishes, by SplicerBlockEntity.
		Splicing.Result result = Splicing.begin(player, branch, payload.lineage());
		if (result != Splicing.Result.OK) {
			player.sendSystemMessage(Component.literal(result.message));
			return;
		}
		player.sendSystemMessage(Component.literal(payload.lineage() == 0L
				? "Reverting " + branch.title + "..."
				: "Splicing " + branch.title + "..."));
	}
}
