package dev.jsz.primordia.sound.client;

import com.mojang.blaze3d.audio.SoundBuffer;
import dev.jsz.primordia.Primordia;
import dev.jsz.primordia.sound.CallType;
import dev.jsz.primordia.sound.VoiceProfile;
import dev.jsz.primordia.sound.VoiceSynth;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.Identifier;
import net.minecraft.util.valueproviders.ConstantFloat;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Holds synthesised creature voices and hands them to the game's sound engine.
 *
 * <h2>The cache that isn't on disk</h2>
 * Rendering one call costs about a millisecond (see {@link VoiceSynth}) and about forty kilobytes.
 * Writing that to a file and reading it back saves very little, and would need a Vorbis encoder to
 * save anything at all, so this keeps finished PCM in memory and nothing ever touches the
 * filesystem. What is actually worth holding on to is not the synthesis but the OpenAL upload, and
 * the game itself keeps that once a buffer has been handed over.
 *
 * <h2>How a synthesised sound reaches the speakers</h2>
 * {@code SoundEngine#play} resolves a sound event, gets an {@code Identifier} for the ogg it wants,
 * and asks {@link SoundBufferLibrary} for the decoded samples — through {@code computeIfAbsent}. So
 * putting a finished buffer into that map under the identifier the engine is about to ask for makes
 * it find the audio already decoded and never look for a file. From there the call is an ordinary
 * Minecraft sound: positioned, attenuated, on the players' sound sliders, silenced when the game
 * pauses.
 * <p>
 * The map's value type is a {@code CompletableFuture}, which is what makes the asynchronous path
 * free — a voice that has not been rendered yet is seeded as an incomplete future, the engine
 * attaches its {@code thenAccept}, and the samples arrive a few milliseconds later on a worker
 * thread without the client thread ever having waited.
 */
public final class VoiceBank {

	/**
	 * Ceiling on retained PCM. Around forty kilobytes a call and seven call types means this holds
	 * the full vocal range of well over a hundred distinct creatures, which is far more than are
	 * ever within earshot at once.
	 */
	private static final long MAX_PCM_BYTES = 24L * 1024 * 1024;

	/**
	 * Synthesis runs here rather than on the client thread. It is short work, but it is short work
	 * that would otherwise land in the middle of a frame, and a call arriving two frames late is
	 * inaudible where a stutter is not.
	 */
	private static final ExecutorService SYNTH = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Primordia Voice Synth");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});

	/**
	 * Live OpenAL buffers this class has seeded into the engine, most recently used last.
	 * <p>
	 * Bounded because every entry is memory on the sound card that the game will never reclaim on
	 * its own — the engine only clears buffers it believes it loaded, and it did not load these.
	 */
	private static final int MAX_LIVE_BUFFERS = 256;

	/**
	 * How long a seeded buffer must go unused before it may be deleted, in nanoseconds.
	 * <p>
	 * Generously longer than the longest call, because deleting a buffer that a channel is still
	 * playing from would cut the sound off. Ten seconds against a ceiling of 2.6.
	 */
	private static final long EVICT_AFTER_NANOS = 10_000_000_000L;

	/** Access-ordered, so the eldest entry is always the least recently heard. */
	private static final Map<Key, byte[]> PCM = new LinkedHashMap<>(64, 0.75f, true);
	private static long pcmBytes;

	/** Access-ordered too, mapping each seeded buffer to when it was last handed out. */
	private static final Map<Identifier, Long> LIVE = new LinkedHashMap<>(64, 0.75f, true);

	private VoiceBank() {
	}

	private record Key(int voice, int call, int variant) {
		Identifier location() {
			return Primordia.id("voice/" + Integer.toHexString(voice) + "_" + call + "_" + variant);
		}
	}

	/**
	 * Builds the {@link Sound} for one call and makes sure the engine has its samples.
	 *
	 * @return the sound to hand to a {@code SoundInstance}, or null if the sound engine is not up
	 */
	public static Sound acquire(VoiceProfile profile, CallType call, int variant) {
		final Key key = new Key(profile.hash(), call.ordinal(), variant);
		final Identifier location = key.location();

		final Sound sound = new Sound(
				location,
				ConstantFloat.of(1f),
				ConstantFloat.of(1f),
				1,
				Sound.Type.FILE,
				// Never streamed: streaming would send the engine down the ogg-decoding path, which
				// is exactly the path there is no file for.
				false,
				false,
				// Attenuation distance in blocks. Sixteen is vanilla's default for a mob.
				16);

		final Map<Identifier, CompletableFuture<SoundBuffer>> cache = engineCache();
		if (cache == null) return null;

		// getPath() is what SoundEngine actually looks the buffer up by — the location run through
		// the sounds/*.ogg converter — so that, and not the event id, is the key to seed.
		final Identifier bufferKey = sound.getPath();
		final CompletableFuture<SoundBuffer> existing = cache.get(bufferKey);

		if (existing == null || isDiscarded(existing)) {
			// A resource reload calls SoundBufferLibrary#clear, which deletes every AL buffer it is
			// holding, ours included. Re-seeding whenever the entry is missing or spent means a
			// reload costs one re-render and needs no reload listener of its own.
			cache.put(bufferKey, buffer(key, profile, call, variant));
		}
		LIVE.put(bufferKey, System.nanoTime());
		evict(cache);

		return sound;
	}

	/**
	 * Deletes the least recently heard voices once too many are resident.
	 * <p>
	 * Only entries that have been silent for {@link #EVICT_AFTER_NANOS} are eligible, and the walk
	 * stops at the first one that is too recent — the map is in access order, so everything past
	 * that point is newer still. That leaves the cap soft: a hundred creatures all calling at once
	 * will overshoot rather than cut each other off, and settle on the next quiet moment.
	 */
	private static void evict(Map<Identifier, CompletableFuture<SoundBuffer>> cache) {
		if (LIVE.size() <= MAX_LIVE_BUFFERS) return;

		final long now = System.nanoTime();
		final var it = LIVE.entrySet().iterator();
		while (LIVE.size() > MAX_LIVE_BUFFERS && it.hasNext()) {
			final var entry = it.next();
			if (now - entry.getValue() < EVICT_AFTER_NANOS) return;
			release(cache.remove(entry.getKey()));
			it.remove();
		}
	}

	/**
	 * Hands one buffer's memory back to OpenAL.
	 * <p>
	 * On the client thread, which is where the game does its own buffer deletion during a reload. A
	 * future still in flight is left to complete and then discarded, so a voice that is mid-render
	 * when it falls out of the cache does not leak.
	 */
	private static void release(CompletableFuture<SoundBuffer> future) {
		if (future == null) return;
		future.thenAccept(SoundBuffer::discardAlBuffer);
	}

	private static CompletableFuture<SoundBuffer> buffer(Key key, VoiceProfile profile,
	                                                     CallType call, int variant) {
		final byte[] ready;
		synchronized (PCM) {
			ready = PCM.get(key);
		}
		if (ready != null) {
			return CompletableFuture.completedFuture(wrap(ready));
		}
		return CompletableFuture.supplyAsync(() -> {
			final byte[] pcm = VoiceSynth.render(profile, call, variant);
			store(key, pcm);
			return wrap(pcm);
		}, SYNTH);
	}

	/**
	 * Copies PCM into a direct buffer for {@code alBufferData}.
	 * <p>
	 * A fresh one every time rather than a shared one: {@link SoundBuffer} takes ownership and drops
	 * its reference once the samples are on the card, so the buffer cannot be handed out twice. The
	 * {@code byte[]} in {@link #PCM} is the thing that is actually reused.
	 */
	private static SoundBuffer wrap(byte[] pcm) {
		final ByteBuffer direct = ByteBuffer.allocateDirect(pcm.length);
		direct.put(pcm);
		direct.flip();
		return new SoundBuffer(direct, VoiceSynth.FORMAT);
	}

	private static void store(Key key, byte[] pcm) {
		synchronized (PCM) {
			final byte[] prev = PCM.put(key, pcm);
			if (prev != null) pcmBytes -= prev.length;
			pcmBytes += pcm.length;

			final var it = PCM.entrySet().iterator();
			while (pcmBytes > MAX_PCM_BYTES && it.hasNext()) {
				pcmBytes -= it.next().getValue().length;
				it.remove();
			}
		}
	}

	/**
	 * True once the game has deleted the AL buffer out from under us — after a resource reload, or
	 * a sound device change. A future that has not completed yet is not discarded; it has simply not
	 * arrived.
	 */
	private static boolean isDiscarded(CompletableFuture<SoundBuffer> future) {
		if (!future.isDone() || future.isCompletedExceptionally()) return false;
		final SoundBuffer buffer = future.getNow(null);
		return buffer == null || !buffer.isValid();
	}

	@SuppressWarnings("unchecked")
	private static Map<Identifier, CompletableFuture<SoundBuffer>> engineCache() {
		final Minecraft client = Minecraft.getInstance();
		if (client == null || client.getSoundManager() == null) return null;
		final var engine = client.getSoundManager().soundEngine;
		if (engine == null) return null;
		final SoundBufferLibrary buffers = engine.soundBuffers;
		return buffers == null ? null : (Map<Identifier, CompletableFuture<SoundBuffer>>) buffers.cache;
	}

	/**
	 * Drops everything held. Called on leaving a world, where none of these voices apply again —
	 * every creature that had one is gone, and the next world's animals will have different genomes.
	 */
	public static void clear() {
		synchronized (PCM) {
			PCM.clear();
			pcmBytes = 0;
		}
		final Map<Identifier, CompletableFuture<SoundBuffer>> cache = engineCache();
		if (cache != null) {
			for (Identifier id : LIVE.keySet()) {
				release(cache.remove(id));
			}
		}
		LIVE.clear();
	}
}
