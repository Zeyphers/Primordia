package dev.jsz.primordia;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.jsz.primordia.client.model.BbAnimation;
import dev.jsz.primordia.client.model.BbModel;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cover for the splicer's geometry and its sampling cycle.
 * <p>
 * The model is read straight out of the resources at runtime, which means a mistake in the loader —
 * or an artist saving a file this code cannot read — produces a machine that is invisible, inside
 * out, or standing still, with nothing in the log. None of that needs a running game to catch: the
 * parsing and the keyframe arithmetic are ordinary code over an ordinary file.
 */
class SplicerModelTest {

	private static final Path MODEL =
			Path.of("src/main/resources/assets/primordia/geo/splicer.bbmodel");

	private static JsonObject json() throws IOException {
		try (Reader reader = Files.newBufferedReader(MODEL)) {
			return JsonParser.parseReader(reader).getAsJsonObject();
		}
	}

	private static BbAnimation cycle() throws IOException {
		JsonObject root = json();
		for (var raw : root.getAsJsonArray("animations")) {
			JsonObject animation = raw.getAsJsonObject();
			if (animation.get("name").getAsString().trim().equals("Sampling")) {
				return BbAnimation.fromJson(animation);
			}
		}
		return fail("the model has no animation named 'Sampling'");
	}

	// ------------------------------------------------------------------ geometry

	@Test
	void theModelIsShippedAndReadable() throws IOException {
		assertTrue(Files.exists(MODEL), "the splicer model is not in the resources");
		BbModel model = BbModel.fromJson(json(), List.of(
				net.minecraft.resources.Identifier.parse("primordia:textures/block/splicer.png"),
				net.minecraft.resources.Identifier.parse("primordia:textures/block/splicer_body.png")));

		List<BbModel.Bone> bones = new ArrayList<>();
		collect(model.root(), bones);
		int faces = bones.stream().mapToInt(b -> b.faces.size()).sum();

		// A loader that silently produced nothing would still let the game start, and the machine
		// would simply be invisible.
		assertTrue(faces > 300, "only " + faces + " faces were read — the mesh did not load");
		assertEquals(2, model.textureCount());

		// The bones the animation drives have to survive the outliner walk under these exact names,
		// because the pose is keyed by name and a rename would animate nothing at all.
		for (String required : new String[]{"Arm", "group", "group2", "group3"}) {
			assertTrue(bones.stream().anyMatch(b -> b.name.equals(required)),
					"the model has no bone called '" + required + "'");
		}
	}

	/**
	 * Element rotations are applied, which is what keeps the arm attached to the machine.
	 * <p>
	 * Three of the splicer's twenty elements carry a rotation, and they are exactly the gantry beam,
	 * the sampling head and the probe. The beam is authored lying along Z and turned ninety degrees
	 * across the machine; the head and probe are authored lying flat and stood upright. An importer
	 * that ignores element rotation therefore does not produce a slightly wrong machine — it leaves
	 * the whole arm assembly flat on its side, hanging out of the back of the block, which is
	 * precisely how this looked before the rotations were read.
	 * <p>
	 * Asserted as "every vertex is inside the body shell", because that is the property that was
	 * violated and it does not depend on knowing the right answer to three decimal places.
	 */
	@Test
	void theArmIsRotatedIntoTheMachine() throws IOException {
		BbModel model = BbModel.fromJson(json(), List.of(
				net.minecraft.resources.Identifier.parse("primordia:textures/block/splicer.png"),
				net.minecraft.resources.Identifier.parse("primordia:textures/block/splicer_body.png")));

		List<BbModel.Bone> bones = new ArrayList<>();
		collect(model.root(), bones);

		// The three animated bones hold the arm. The body shell is x -8..8, y 0..15, z -8..8.
		for (String name : new String[]{"group", "group2", "group3"}) {
			BbModel.Bone bone = bones.stream().filter(b -> b.name.equals(name)).findFirst()
					.orElseThrow(() -> new AssertionError("no bone called " + name));
			assertFalse(bone.faces.isEmpty(), name + " carries no geometry");
			for (BbModel.Face face : bone.faces) {
				float[] p = face.positions();
				for (int v = 0; v < 4; v++) {
					float x = p[v * 3], y = p[v * 3 + 1], z = p[v * 3 + 2];
					assertTrue(x >= -8.5f && x <= 8.5f, name + " reaches x=" + x + ", outside the body");
					assertTrue(y >= -0.5f && y <= 15.5f, name + " reaches y=" + y + ", outside the body");
					assertTrue(z >= -8.5f && z <= 8.5f, name + " reaches z=" + z + ", outside the body");
				}
			}
		}
	}

	/**
	 * And the beam really is turned across the machine rather than left lying along it.
	 * <p>
	 * The bounds check above would still pass on an unrotated beam that merely happened to fit, so
	 * this pins the axis: after rotation the gantry is wide in X and thin in Z, and before it was
	 * the other way round.
	 */
	@Test
	void theGantryBeamRunsAcrossTheMachine() throws IOException {
		BbModel model = BbModel.fromJson(json(), List.of(
				net.minecraft.resources.Identifier.parse("primordia:textures/block/splicer.png"),
				net.minecraft.resources.Identifier.parse("primordia:textures/block/splicer_body.png")));

		List<BbModel.Bone> bones = new ArrayList<>();
		collect(model.root(), bones);
		BbModel.Bone beam = bones.stream().filter(b -> b.name.equals("group")).findFirst().orElseThrow();

		float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
		float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
		for (BbModel.Face face : beam.faces) {
			float[] p = face.positions();
			for (int v = 0; v < 4; v++) {
				minX = Math.min(minX, p[v * 3]);
				maxX = Math.max(maxX, p[v * 3]);
				minZ = Math.min(minZ, p[v * 3 + 2]);
				maxZ = Math.max(maxZ, p[v * 3 + 2]);
			}
		}
		assertTrue(maxX - minX > 8f, "the gantry beam is only " + (maxX - minX) + " wide — it was not turned");
		assertTrue(maxZ - minZ < 4f, "the gantry beam is " + (maxZ - minZ) + " deep — it is still lying along Z");
	}

	private static void collect(BbModel.Bone bone, List<BbModel.Bone> out) {
		out.add(bone);
		for (BbModel.Bone child : bone.children) collect(child, out);
	}

	// ------------------------------------------------------------------ the cycle

	@Test
	void theSamplingCycleLoadsAndLoops() throws IOException {
		BbAnimation animation = cycle();
		assertTrue(animation.loops(), "the sampling cycle should loop");
		assertEquals(8.125f, animation.length(), 0.001f);
	}

	/**
	 * The behaviour this whole renderer exists to get right: an idle bench holds the animation's
	 * <b>first frame</b>, not the pose the model was drawn in.
	 * <p>
	 * The opening keyframe already displaces the carriage down its rail, so the two are genuinely
	 * different poses. If rest ever silently became the bind pose, an idle bench would sit at the
	 * wrong end of its own travel and jump the moment work arrived, and nothing would fail.
	 */
	@Test
	void restIsTheFirstFrameAndNotTheBindPose() throws IOException {
		BbAnimation animation = cycle();

		BbModel.Pose rest = animation.rest();
		BbModel.Pose frameZero = animation.poseAt(0f);
		for (String bone : new String[]{"group", "group2", "group3"}) {
			assertVec(frameZero.offsetOf(bone), rest.offsetOf(bone), bone + " at rest is not frame one");
		}

		// And frame one is a real displacement, so holding it is doing something.
		Vector3f carriage = rest.offsetOf("group");
		assertNotNull(carriage, "the carriage has no opening keyframe");
		assertTrue(carriage.length() > 0.5f,
				"frame one puts the carriage at " + carriage + ", which is the bind pose");
	}

	/** A held pose must not creep: the same time has to give the same answer, every time. */
	@Test
	void anIdleMachineDoesNotDrift() throws IOException {
		BbAnimation animation = cycle();
		Vector3f first = animation.rest().offsetOf("group3");
		for (int i = 0; i < 50; i++) {
			assertVec(first, animation.rest().offsetOf("group3"), "the rest pose moved");
		}
	}

	/**
	 * Bezier interpolation must pass exactly through its keyframes.
	 * <p>
	 * The curve is solved for a value at a time rather than evaluated at a parameter, and a solver
	 * that is subtly wrong still produces plausible motion — it just misses every waypoint slightly,
	 * which on this machine means a sampling head that does not quite arrive at the sample.
	 */
	@Test
	void theCurvePassesThroughItsKeyframes() throws IOException {
		BbAnimation animation = cycle();
		// Keyframe times taken from the file: the carriage's opening move.
		assertVec(new Vector3f(0f, 0f, -4f), animation.poseAt(0f).offsetOf("group"),
				"the cycle does not start where its first keyframe says");
		assertVec(new Vector3f(0f, 0f, -2.3f), animation.poseAt(0.45833f).offsetOf("group"),
				"the curve misses the keyframe at 0.45833s");
		assertVec(new Vector3f(0f, 0f, 2.7f), animation.poseAt(1.95833f).offsetOf("group"),
				"the curve misses the keyframe at 1.95833s");
	}

	/**
	 * Between keyframes the head must stay between them. A bezier with mishandled handles overshoots,
	 * and an overshoot here is a probe driven through the deck of its own machine.
	 */
	@Test
	void theHeadStaysInsideItsTravel() throws IOException {
		BbAnimation animation = cycle();
		float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
		for (int step = 0; step <= 400; step++) {
			Vector3f at = animation.poseAt(animation.length() * step / 400f).offsetOf("group");
			assertNotNull(at);
			lo = Math.min(lo, at.z);
			hi = Math.max(hi, at.z);
		}
		// The carriage's authored extremes are -4 and +2.7; a little slack for the curve's own easing.
		assertTrue(lo >= -4.5f, "the carriage undershot to " + lo);
		assertTrue(hi <= 3.2f, "the carriage overshot to " + hi);
	}

	/** A looping cycle has to be continuous across the wrap, or the machine jumps once a cycle. */
	@Test
	void theCycleWrapsWithoutAJump() throws IOException {
		BbAnimation animation = cycle();
		for (String bone : new String[]{"group", "group2", "group3"}) {
			Vector3f start = animation.poseAt(0f).offsetOf(bone);
			Vector3f end = animation.poseAt(animation.length() - 0.001f).offsetOf(bone);
			assertNotNull(start);
			assertNotNull(end);
			assertTrue(start.distance(end) < 0.5f,
					bone + " jumps " + start.distance(end) + " between the end of the cycle and its start");
		}
	}

	private static void assertVec(Vector3f expected, Vector3f actual, String message) {
		assertNotNull(actual, message);
		assertEquals(expected.x, actual.x, 0.01f, message);
		assertEquals(expected.y, actual.y, 0.01f, message);
		assertEquals(expected.z, actual.z, 0.01f, message);
	}
}
