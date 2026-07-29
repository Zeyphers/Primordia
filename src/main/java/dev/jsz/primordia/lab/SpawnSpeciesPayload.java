package dev.jsz.primordia.lab;

import dev.jsz.primordia.Primordia;
import dev.jsz.primordia.entity.CreatureEntity;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.registry.PrimordiaEntities;
import dev.jsz.primordia.registry.PrimordiaItems;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.ItemStack;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Asks the server to place a specimen of a species the player has already catalogued.
 * <p>
 * A convenience for building and for testing the generator: double-click a species' name in the
 * field guide and it walks out in front of you. Deliberately gated to creative mode <i>and</i>
 * command permissions, because it is {@code /primordia spawn} with a nicer front end — you are
 * conjuring an animal out of a book, which is not something the survival loop should offer.
 * <p>
 * <b>The server trusts none of this.</b> The packet carries a lineage id and nothing else: not a
 * genome, not a position, not a claim about who is allowed. A client that has been modified can
 * send any lineage it likes at any time, so the permission check, the guide lookup and the spawn
 * position are all resolved server-side from state the server already holds. The worst a forged
 * packet can do is spawn a creature the sender had already catalogued, in creative, with op.
 */
public record SpawnSpeciesPayload(long lineage) implements CustomPayload {

	public static final CustomPayload.Id<SpawnSpeciesPayload> ID =
			new CustomPayload.Id<>(Primordia.id("spawn_species"));

	public static final PacketCodec<RegistryByteBuf, SpawnSpeciesPayload> CODEC =
			PacketCodec.tuple(
					PacketCodecs.VAR_LONG, SpawnSpeciesPayload::lineage,
					SpawnSpeciesPayload::new);

	/** Permission level the vanilla commands treat as "operator". Matches {@code /primordia}. */
	private static final int REQUIRED_PERMISSION = 2;

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

	/** Whether this player may conjure specimens at all. Checked on the server, every time. */
	public static boolean isAllowed(ServerPlayerEntity player) {
		return player.isCreative() && player.hasPermissionLevel(REQUIRED_PERMISSION);
	}

	private static void apply(ServerPlayerEntity player, SpawnSpeciesPayload payload) {
		if (!isAllowed(player)) {
			player.sendMessage(Text.literal("Conjuring a specimen needs creative mode and command "
					+ "permissions.").formatted(Formatting.RED), true);
			return;
		}

		// Resolved from the book the server holds, not from anything the client sent. This is also
		// what limits the feature to species the player has actually catalogued.
		GuideData.Entry entry = findEntry(player, payload.lineage());
		if (entry == null) {
			player.sendMessage(Text.literal("No entry for that species in your guide.")
					.formatted(Formatting.RED), true);
			return;
		}

		Genome genome = entry.genome();
		if (genome == null) {
			player.sendMessage(Text.literal("That entry's genome could not be read.")
					.formatted(Formatting.RED), true);
			return;
		}

		if (!(player.getWorld() instanceof ServerWorld world)) return;
		CreatureEntity creature = PrimordiaEntities.CREATURE.create(world);
		if (creature == null) return;

		creature.setGenome(genome);
		creature.refreshPositionAndAngles(player.getX(), player.getY(), player.getZ(),
				player.getYaw(), 0f);
		world.spawnEntity(creature);

		player.sendMessage(Text.literal("Conjured ").formatted(Formatting.GRAY)
				.append(Text.literal(entry.displayName()).formatted(Formatting.AQUA)), true);
	}

	/** The player's guide entry for this lineage, or null if they have not catalogued it. */
	private static GuideData.Entry findEntry(ServerPlayerEntity player, long lineage) {
		for (int i = 0; i < player.getInventory().size(); i++) {
			ItemStack candidate = player.getInventory().getStack(i);
			if (!candidate.isOf(PrimordiaItems.FIELD_GUIDE)) continue;
			for (GuideData.Entry entry : GuideData.get(candidate).entries()) {
				if (entry.lineage() == lineage) return entry;
			}
		}
		return null;
	}
}
