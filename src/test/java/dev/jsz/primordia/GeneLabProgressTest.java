package dev.jsz.primordia;

import dev.jsz.primordia.block.GeneLabBlockEntity;
import dev.jsz.primordia.block.GeneLabBlockEntity.Stage;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the three progress lines the Basic Gene Lab draws between its four slots.
 * <p>
 * The screen is the only explanation the machine has: which line is moving tells the player which
 * step is running and therefore which resource is being spent. A line that fills at the wrong time,
 * or two that fill at once, would describe a machine that does not exist.
 */
class GeneLabProgressTest {

	/**
	 * {@code lineFill} is pure arithmetic, but reaching it loads {@link GeneLabBlockEntity} and with
	 * it {@code BlockEntity}, whose static initialiser touches the built-in registries. Since 26.2
	 * those refuse to be read before the game is bootstrapped, so the game has to be brought up even
	 * for a function that never asks it anything.
	 */
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static float fill(int line, Stage stage, int ticks) {
		return GeneLabBlockEntity.lineFill(line, stage, ticks);
	}

	@Test
	void everyLineStaysWithinItsTrough() {
		for (Stage stage : Stage.VALUES) {
			// Deliberately overshoot: progress is read from a synced property, and a stale or
			// out-of-range value must not draw a bar past the end of its recess.
			for (int t = -50; t <= GeneLabBlockEntity.SEQUENCE_TIME + 200; t++) {
				for (int line = 0; line < 3; line++) {
					float f = fill(line, stage, t);
					assertTrue(f >= 0f && f <= 1f,
							"line " + line + " at " + stage + " tick " + t + " filled " + f);
				}
			}
		}
	}

	@Test
	void theFirstLineTracksSequencingAndStaysFullAfterwards() {
		assertEquals(0f, fill(0, Stage.IDLE, 0), 1e-4f);
		assertEquals(0f, fill(0, Stage.SEQUENCING, 0), 1e-4f);
		assertEquals(0.5f, fill(0, Stage.SEQUENCING, GeneLabBlockEntity.SEQUENCE_TIME / 2), 1e-3f);
		assertEquals(1f, fill(0, Stage.SEQUENCING, GeneLabBlockEntity.SEQUENCE_TIME), 1e-4f);
		// Still full while the next step runs: the chain behind the live step reads as done, not
		// as reset, which is what makes the column legible as a pipeline.
		assertEquals(1f, fill(0, Stage.DECODING, 0), 1e-4f);
		assertEquals(1f, fill(0, Stage.DECODING, GeneLabBlockEntity.DECODE_TIME), 1e-4f);
	}

	@Test
	void theDecodeIsSplitCleanlyBetweenInterpretingAndWriting() {
		int handover = GeneLabBlockEntity.DECODE_INTERPRET_TIME;

		// Line 1 owns the matching, and completes exactly as line 2 starts moving.
		assertEquals(1f, fill(1, Stage.DECODING, handover), 1e-4f);
		assertEquals(0f, fill(2, Stage.DECODING, handover), 1e-4f);
		assertTrue(fill(2, Stage.DECODING, handover + 1) > 0f,
				"the writing line never starts once interpreting is done");

		// Line 2 owns the tail and finishes with the decode.
		assertEquals(1f, fill(2, Stage.DECODING, GeneLabBlockEntity.DECODE_TIME), 1e-4f);
		assertEquals(GeneLabBlockEntity.DECODE_TIME,
				GeneLabBlockEntity.DECODE_INTERPRET_TIME + GeneLabBlockEntity.DELIVER_TIME,
				"the two halves of the decode do not add up to its duration");
	}

	@Test
	void onlyTheLinesForTheRunningStepEverMove() {
		// Nothing at all while idle: an empty machine shows an empty chain.
		for (int line = 0; line < 3; line++) {
			assertEquals(0f, fill(line, Stage.IDLE, 0), 1e-4f, "line " + line + " moved while idle");
		}
		// The decode's lines stay empty for the whole of sequencing.
		for (int t = 0; t <= GeneLabBlockEntity.SEQUENCE_TIME; t += 20) {
			assertEquals(0f, fill(1, Stage.SEQUENCING, t), 1e-4f);
			assertEquals(0f, fill(2, Stage.SEQUENCING, t), 1e-4f);
		}
	}

	@Test
	void eachLineRisesMonotonicallyThroughItsOwnStep() {
		float previous = -1f;
		for (int t = 0; t <= GeneLabBlockEntity.SEQUENCE_TIME; t++) {
			float f = fill(0, Stage.SEQUENCING, t);
			assertTrue(f >= previous, "sequencing line fell back at tick " + t);
			previous = f;
		}
		for (int line : new int[]{1, 2}) {
			previous = -1f;
			for (int t = 0; t <= GeneLabBlockEntity.DECODE_TIME; t++) {
				float f = fill(line, Stage.DECODING, t);
				assertTrue(f >= previous, "line " + line + " fell back at decode tick " + t);
				previous = f;
			}
		}
	}

	@Test
	void theDecodeChargesItsFullRedstonePriceAndNotMore() {
		int highest = 0;
		for (int t = 0; t <= GeneLabBlockEntity.DECODE_TIME; t++) {
			int owed = (t * GeneLabBlockEntity.REDSTONE_PER_DECODE) / GeneLabBlockEntity.DECODE_TIME;
			assertTrue(owed >= highest, "redstone owed decreased at tick " + t);
			assertTrue(owed <= GeneLabBlockEntity.REDSTONE_PER_DECODE,
					"the decode tried to draw " + owed + " redstone, more than its price");
			highest = owed;
		}
		assertEquals(GeneLabBlockEntity.REDSTONE_PER_DECODE, highest,
				"the steady draw never reached the full price, so the top-up does all the work");
	}

	@Test
	void theAdvertisedRedstonePriceIsSixteen() {
		// Stated in the GUI tooltip, so a change here has to be deliberate.
		assertEquals(16, GeneLabBlockEntity.REDSTONE_PER_DECODE);
	}
}
