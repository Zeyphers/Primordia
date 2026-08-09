package dev.jsz.primordia.sound.client;

import dev.jsz.primordia.client.config.PrimordiaConfig;
import dev.jsz.primordia.entity.CreatureEntity;
import dev.jsz.primordia.sound.CallType;
import dev.jsz.primordia.sound.CreatureVoicePayload;
import dev.jsz.primordia.sound.VoiceProfile;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;

/**
 * The client end of creature audio: turns a "that animal just called" packet into a noise.
 * <p>
 * Everything needed to do that is already here — the client has the genome, because it is drawing
 * the creature out of it — so the packet carries no audio and no sound event, only which animal and
 * which occasion. See {@link CreatureVoicePayload}.
 */
public final class CreatureVoices {

	private CreatureVoices() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(CreatureVoicePayload.TYPE,
				(payload, context) -> context.client().execute(() -> play(
						payload.entityId(),
						CallType.byId(payload.call()),
						payload.variant())));

		// Voices are keyed by genome, and no genome outlives the world it was in.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> VoiceBank.clear());
	}

	private static void play(int entityId, CallType call, int variant) {
		final Minecraft client = Minecraft.getInstance();
		if (client.level == null) return;

		final Entity entity = client.level.getEntity(entityId);
		if (!(entity instanceof CreatureEntity creature)) return;

		play(creature, call, variant);
	}

	/** Plays a call from a creature the client can see. */
	public static void play(CreatureEntity creature, CallType call, int variant) {
		final PrimordiaConfig config = PrimordiaConfig.get();
		if (!config.creatureVoices) return;

		final VoiceProfile profile = creature.getVoiceProfile();
		if (profile == null) return;

		final Sound sound = VoiceBank.acquire(profile, call,
				Mth.clamp(variant, 0, CreatureVoicePayload.VARIANTS - 1));
		if (sound == null) return;

		// A juvenile is not a small adult — its whole tract is shorter — but re-deriving a second
		// voice per creature would double the cache for a effect a pitch shift carries most of.
		// Growth is in [0,1]; a newborn comes out about a fifth above its adult voice.
		final float youth = 1f + (1f - Mth.clamp(creature.getGrowth(), 0f, 1f)) * 0.22f;

		// Slight per-call detune on top of the baked-in pitch, so a creature repeating itself is not
		// obviously replaying a recording.
		final float wobble = 0.97f + creature.getRandom().nextFloat() * 0.06f;

		Minecraft.getInstance().getSoundManager().play(new ProceduralVoiceInstance(
				sound, call,
				creature.getX(), creature.getEyeY(), creature.getZ(),
				profile.volume() * (config.creatureVoiceVolume / 100f),
				Mth.clamp(profile.playbackPitch() * youth * wobble, 0.5f, 2.0f)));
	}
}
