package dev.jsz.primordia.sound;

import dev.jsz.primordia.Primordia;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * Tells nearby clients that a creature vocalised, and leaves them to work out what that sounds like.
 * <p>
 * The server stays in charge of <i>when</i> — it owns the AI, the damage and the breeding — but it
 * never touches a waveform. All a client needs is which animal and which occasion, because it
 * already has the genome (it is drawing the creature from it) and can derive the voice itself. Three
 * bytes on the wire instead of a sound event, and every client reaches the same answer because
 * synthesis is deterministic.
 * <p>
 * This replaces the vanilla sound packet the creature used to send. It is not routed through the
 * sound registry at all, so there is no {@code SoundEvent} to register and no {@code sounds.json}
 * entry to keep in step.
 */
public record CreatureVoicePayload(int entityId, byte call, byte variant) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<CreatureVoicePayload> TYPE =
			new CustomPacketPayload.Type<>(Primordia.id("creature_voice"));

	public static final StreamCodec<RegistryFriendlyByteBuf, CreatureVoicePayload> CODEC =
			StreamCodec.composite(
					ByteBufCodecs.VAR_INT, CreatureVoicePayload::entityId,
					ByteBufCodecs.BYTE, CreatureVoicePayload::call,
					ByteBufCodecs.BYTE, CreatureVoicePayload::variant,
					CreatureVoicePayload::new);

	/** How many renderings of one call exist, so a repeated call is not one looping waveform. */
	public static final int VARIANTS = 3;

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
	}

	/**
	 * Sends the call to everyone who can see the creature.
	 * <p>
	 * Tracking distance rather than a radius of our own: a client that is not tracking the entity has
	 * no genome for it and could not synthesise the voice anyway, and vanilla's tracking range is
	 * already further than any of these calls carry.
	 */
	public static void broadcast(Entity creature, CallType call) {
		if (!(creature.level() instanceof ServerLevel) || creature.isSilent()) return;

		final byte variant = (byte) creature.getRandom().nextInt(VARIANTS);
		final CreatureVoicePayload payload =
				new CreatureVoicePayload(creature.getId(), (byte) call.ordinal(), variant);

		for (var player : PlayerLookup.tracking(creature)) {
			ServerPlayNetworking.send(player, payload);
		}
	}
}
