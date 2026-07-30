package dev.jsz.primordia.lab;

import dev.jsz.primordia.entity.CreatureEntity;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.registry.PrimordiaEntities;
import dev.jsz.primordia.registry.PrimordiaItems;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

public record SpawnSpeciesPayload(long lineage) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<SpawnSpeciesPayload> TYPE =
			new CustomPacketPayload.Type<>(dev.jsz.primordia.Primordia.id("spawn_species"));

	public static final StreamCodec<RegistryFriendlyByteBuf, SpawnSpeciesPayload> CODEC =
			StreamCodec.composite(
					ByteBufCodecs.VAR_LONG, SpawnSpeciesPayload::lineage,
					SpawnSpeciesPayload::new);

	/**
	 * 26.2 replaced numeric permission levels with named permissions. Gamemaster is the successor
	 * to the old level 2 — the tier that gates {@code /summon} — which is the right bar for
	 * conjuring a specimen out of the guide.
	 */
	private static final Permission REQUIRED_PERMISSION = Permissions.COMMANDS_GAMEMASTER;

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

	public static boolean isAllowed(ServerPlayer player) {
		return player.isCreative() && player.permissions().hasPermission(REQUIRED_PERMISSION);
	}

	private static void apply(ServerPlayer player, SpawnSpeciesPayload payload) {
		if (!isAllowed(player)) {
			player.sendOverlayMessage(Component.literal("Conjuring a specimen needs creative mode and command permissions.")
					.withStyle(ChatFormatting.RED));
			return;
		}

		GuideData.Entry entry = findEntry(player, payload.lineage());
		if (entry == null) {
			player.sendOverlayMessage(Component.literal("No entry for that species in your guide.")
					.withStyle(ChatFormatting.RED));
			return;
		}

		Genome genome = entry.genome();
		if (genome == null) {
			player.sendOverlayMessage(Component.literal("That entry's genome could not be read.")
					.withStyle(ChatFormatting.RED));
			return;
		}

		if (!(player.level() instanceof ServerLevel world)) return;
		CreatureEntity creature = PrimordiaEntities.CREATURE.create(world, EntitySpawnReason.COMMAND);
		if (creature == null) return;

		creature.setGenome(genome);
		creature.snapTo(player.getX(), player.getY(), player.getZ(),
				player.getYRot(), 0f);
		world.addFreshEntity(creature);

		player.sendOverlayMessage(Component.literal("Conjured ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(entry.displayName()).withStyle(ChatFormatting.AQUA)));
	}

	private static GuideData.Entry findEntry(ServerPlayer player, long lineage) {
		dev.jsz.primordia.lab.PlayerGuideData global = dev.jsz.primordia.lab.PlayerGuideData.get((net.minecraft.server.level.ServerLevel) player.level());
		for (GuideData.Entry entry : global.getGuide(player.getUUID()).entries()) {
			if (entry.lineage() == lineage) return entry;
		}
		return null;
	}
}
