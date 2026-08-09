package dev.jsz.primordia.sound.client;

import dev.jsz.primordia.sound.CallType;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;

/**
 * One creature call, on its way to the sound engine.
 * <p>
 * A hand-written {@link SoundInstance} rather than a {@code SimpleSoundInstance}, because the whole
 * point is to skip the registry lookup. {@link #resolve} normally goes to {@code sounds.json} to
 * find out which files an event may play; here it answers out of the instance itself, since the
 * "file" was made a moment ago and was never in a resource pack. Everything the engine does after
 * that — placing it in the world, attenuating it, putting it on the players' sound sliders, cutting
 * it off when the game pauses — happens exactly as it does for a vanilla mob.
 */
public final class ProceduralVoiceInstance implements SoundInstance {

	private final Sound sound;
	private final WeighedSoundEvents events;
	private final CallType call;
	private final double x, y, z;
	private final float volume;
	private final float pitch;

	public ProceduralVoiceInstance(Sound sound, CallType call,
	                               double x, double y, double z, float volume, float pitch) {
		this.sound = sound;
		this.call = call;
		this.x = x;
		this.y = y;
		this.z = z;
		this.volume = volume;
		this.pitch = pitch;

		// Built here rather than shared, so the subtitle line matches the call that is playing.
		this.events = new WeighedSoundEvents(sound.getLocation(), call.subtitleKey());
		this.events.addSound(sound);
	}

	@Override
	public Identifier getIdentifier() {
		return sound.getLocation();
	}

	@Override
	public WeighedSoundEvents resolve(SoundManager manager) {
		return events;
	}

	@Override
	public Sound getSound() {
		return sound;
	}

	/**
	 * Creature voices are living things making noise, which is what the neutral category is for.
	 * A player who has turned Friendly Creatures down expects that to include these.
	 */
	@Override
	public SoundSource getSource() {
		return SoundSource.NEUTRAL;
	}

	@Override
	public boolean isLooping() {
		return false;
	}

	@Override
	public boolean isRelative() {
		return false;
	}

	@Override
	public int getDelay() {
		return 0;
	}

	@Override
	public float getVolume() {
		return volume;
	}

	/**
	 * Playback pitch is a small per-call wobble only. The creature's actual pitch is in the samples
	 * — it came out of its body size — so resampling here would undo the part that carries the size.
	 */
	@Override
	public float getPitch() {
		return pitch;
	}

	@Override
	public double getX() {
		return x;
	}

	@Override
	public double getY() {
		return y;
	}

	@Override
	public double getZ() {
		return z;
	}

	@Override
	public Attenuation getAttenuation() {
		return Attenuation.LINEAR;
	}

	public CallType call() {
		return call;
	}
}
