package dev.jsz.primordia;

import dev.jsz.primordia.sound.CallType;
import dev.jsz.primordia.sound.VoiceProfile;
import dev.jsz.primordia.sound.VoiceSynth;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the headroom and hierarchy of the procedural creature mix. */
class VoiceMixTest {

	@Test
	void everyCallLeavesHeadroomBeforeTheSoundEngine() {
		VoiceProfile voice = VoiceProfile.fallback();
		for (CallType call : CallType.VALUES) {
			byte[] pcm = VoiceSynth.render(voice, call, 0);
			int peak = 0;
			for (int i = 0; i + 1 < pcm.length; i += 2) {
				int sample = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
				peak = Math.max(peak, Math.abs(sample));
			}
			assertTrue(peak <= Math.ceil(32767 * 0.82), call + " hit the output rail");
		}
	}

	@Test
	void frequentCallsSitBelowUrgentCallsInTheMix() {
		assertTrue(CallType.SLEEP.volumeScale < CallType.AMBIENT.volumeScale);
		assertTrue(CallType.AMBIENT.volumeScale < CallType.HURT.volumeScale);
		assertTrue(CallType.AMBIENT.volumeScale < CallType.THREAT.volumeScale);
	}
}
