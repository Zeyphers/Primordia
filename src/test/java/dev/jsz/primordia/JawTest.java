package dev.jsz.primordia;

import dev.jsz.primordia.anim.AnimationContext;
import dev.jsz.primordia.anim.CreatureAnimator;
import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.body.BoneDef;
import dev.jsz.primordia.body.Feature;
import dev.jsz.primordia.entity.CreatureActivity;
import dev.jsz.primordia.genome.Archetype;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.mesh.LodTier;
import dev.jsz.primordia.sdf.BodySdf;
import dev.jsz.primordia.util.MathX;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cover for the hinged jaw.
 * <p>
 * Two things here are easy to get silently backwards. The rotation <b>sign</b>: a jaw that swings
 * up into the skull is a one-character mistake and looks, from most angles, merely odd rather
 * than obviously wrong. And the bind-pose <b>gap</b>: the mesh is polygonised once and skinned
 * forever after, so if the mandible is baked fused to the cranium the mouth can never open, and
 * nothing about the body plan would look wrong when it happens.
 */
class JawTest {

	private static AnimationContext frame(float time, CreatureActivity activity, float progress) {
		AnimationContext ctx = new AnimationContext();
		ctx.time = time;
		ctx.speed = 0f;
		ctx.tier = LodTier.NEAR;
		ctx.activity = activity;
		ctx.activityProgress = progress;
		return ctx;
	}

	@Test
	void everyCreatureHasAJawHingedToItsSkull() {
		Random random = new Random(1212);
		for (int trial = 0; trial < 200; trial++) {
			BodyPlan plan = BodyPlanBuilder.build(Genome.random(random));

			assertTrue(plan.jawBone > 0 && plan.jawBone < plan.bones.length,
					"jaw bone index out of range");
			BoneDef jaw = plan.bones[plan.jawBone];
			assertEquals(plan.headBone, jaw.parent, "the jaw must hinge from the skull");
			assertTrue(jaw.emitsGeometry, "a jaw that emits nothing is an invisible mouth");
			assertTrue(jaw.length() > 0f, "degenerate jaw bone");
			// Shared with the cranium, the smooth union fairs them into one mass and the mouth
			// line vanishes.
			assertNotEquals(plan.bones[plan.headBone].blendGroup, jaw.blendGroup,
					"jaw shares the skull's blend group — it will be welded shut");
		}
	}

	/**
	 * The mouth has to be genuinely hollow between the jaw and the skull above it.
	 * <p>
	 * An earlier version of this test sampled a single point a fixed distance above the chin and
	 * passed while the mandible was in fact swallowed whole inside the head capsule — the sample
	 * had simply landed outside the creature entirely, in front of the face. Sampling <i>along</i>
	 * the mouth line and requiring open air somewhere between the two surfaces is the thing that
	 * actually distinguishes a mouth from a lump.
	 */
	@Test
	void theJawIsBakedAjarRatherThanFused() {
		Random random = new Random(1213);
		int fused = 0;

		for (int trial = 0; trial < 60; trial++) {
			BodyPlan plan = BodyPlanBuilder.build(Genome.random(random));
			BoneDef jaw = plan.bones[plan.jawBone];
			BodySdf sdf = new BodySdf(plan);

			// Walk the front half of the jaw line, where a mouth is open even when shut.
			boolean foundGap = false;
			for (int i = 0; i <= 6 && !foundGap; i++) {
				float along = MathX.lerp(0.55f, 1.0f, i / 6f);
				Vector3f onJaw = new Vector3f(jaw.head).lerp(jaw.tail, along);
				float radius = MathX.lerp(jaw.radiusHead, jaw.radiusTail, along);
				// Step upward off the mandible toward the muzzle looking for daylight.
				for (int step = 1; step <= 8 && !foundGap; step++) {
					float h = radius * (1.0f + step * 0.45f);
					if (sdf.eval(onJaw.x, onJaw.y + h, onJaw.z) > 0f) foundGap = true;
				}
			}
			if (!foundGap) fused++;
		}

		assertEquals(0, fused, fused + " of 60 creatures baked with the mandible fused into the "
				+ "skull — those mouths can never open, however the jaw bone is animated");
	}

	/**
	 * How far the jaw is swung off the skull's axis, and which way.
	 * <p>
	 * In the head bone's local frame +Y runs along the skull from base to snout, so the component
	 * of the jaw's direction <i>perpendicular</i> to it is the whole of the hinge angle: its
	 * length is how far open the mouth is, and its direction is which way the jaw swung. Both
	 * matter, and the second is what catches a reversed rotation — a jaw closing up into the
	 * braincase is off by one character and, from most camera angles, merely looks odd.
	 */
	private static Vector3f hingeOffAxis(BodyPlan plan, CreatureActivity activity, float progress) {
		CreatureAnimator animator = new CreatureAnimator(plan);
		for (int i = 0; i < 60; i++) {
			animator.update(frame(i / 20f, activity, progress));
		}
		Vector3f head = animator.skeleton().boneHead(plan.jawBone, new Vector3f());
		Vector3f tail = animator.skeleton().boneTail(plan.jawBone, new Vector3f());
		Vector3f direction = tail.sub(head).normalize();

		new org.joml.Matrix4f(animator.skeleton().worldMatrix(plan.headBone))
				.invert()
				.transformDirection(direction);
		// Strip the along-the-skull component; what is left is the swing.
		return new Vector3f(direction.x, 0f, direction.z);
	}

	@Test
	void openingTheJawSwingsTheChinAwayFromTheSkull() {
		Random random = new Random(1214);
		for (Archetype archetype : Archetype.VALUES) {
			BodyPlan plan = BodyPlanBuilder.build(archetype.create(random));

			Vector3f rest = hingeOffAxis(plan, CreatureActivity.RAM, 1.0f);
			Vector3f open = hingeOffAxis(plan, CreatureActivity.BITE, 0.40f);

			// Same side as the bind pose already leans: the jaw is baked slightly ajar downward,
			// and opening has to continue in that direction rather than reverse through the skull.
			assertTrue(open.dot(rest) > 0f,
					archetype + ": the jaw swings back through the skull when it opens — "
							+ "rest " + rest + " against open " + open);
			assertTrue(open.length() > rest.length() * 1.5f,
					archetype + ": the jaw barely moved — off-axis swing went from "
							+ rest.length() + " to " + open.length());
		}
	}

	/**
	 * Opening the mouth must move the mandible, not the face.
	 * <p>
	 * This is the failure the geometry alone cannot catch. The jaw bone can hinge perfectly and
	 * the mesh still deform wrongly, because skin weights are assigned by distance to a bone and
	 * the mandible is the one bone that swings while sitting inside another part's silhouette:
	 * cheek and braincase vertices are as near to it as its own surface is. Weighted purely by
	 * distance they follow it, and what the player sees is the whole head stretching vertically
	 * rather than a jaw dropping.
	 */
	@Test
	void openingTheJawMovesTheJawAndNotTheSkull() {
		Random random = new Random(1216);
		for (Archetype archetype : Archetype.VALUES) {
			BodyPlan plan = BodyPlanBuilder.build(archetype.create(random));
			var mesh = dev.jsz.primordia.mesh.MeshBaker.bake(plan, 34);

			// Posed by hand rather than through an activity. Every activity that opens the mouth
			// also moves the head — a bite pitches the skull, a charge drops it further — so two
			// activities differ by neck as well as jaw, and comparing them measures both. Setting
			// the one bone leaves the jaw as the only thing that changed.
			var skinned = new dev.jsz.primordia.mesh.SkinnedMesh();

			var shutSkeleton = new dev.jsz.primordia.skeleton.Skeleton(plan);
			shutSkeleton.resetPose();
			shutSkeleton.updateWorld();
			shutSkeleton.updateSkinMatrices();
			skinned.skin(mesh, shutSkeleton);
			float[] shut = skinned.positions().clone();

			var openSkeleton = new dev.jsz.primordia.skeleton.Skeleton(plan);
			openSkeleton.resetPose();
			openSkeleton.setLocalRotation(plan.jawBone, new org.joml.Quaternionf().rotateX(-0.6f));
			openSkeleton.updateWorld();
			openSkeleton.updateSkinMatrices();
			skinned.skin(mesh, openSkeleton);
			float[] gaping = skinned.positions();

			BoneDef jaw = plan.bones[plan.jawBone];
			BoneDef skull = plan.bones[plan.headBone];
			Vector3f chin = new Vector3f(jaw.head).lerp(jaw.tail, 0.9f);
			// Top of the braincase: as far from the mouth as anything on the head gets.
			Vector3f crown = new Vector3f(skull.head).lerp(skull.tail, 0.3f)
					.add(0f, skull.radiusHead * 0.9f, 0f);

			float chinMoved = biggestShiftNear(mesh, shut, gaping, chin, jaw.radiusTail * 2f);
			float crownMoved = biggestShiftNear(mesh, shut, gaping, crown, skull.radiusHead * 0.6f);

			assertTrue(chinMoved > jaw.length() * 0.05f,
					archetype + ": the chin barely moved when the mouth opened: " + chinMoved);
			assertTrue(crownMoved < chinMoved * 0.35f,
					archetype + ": the top of the skull moved " + crownMoved + " against the chin's "
							+ chinMoved + " — the jaw is dragging the whole head with it");
		}
	}

	/** Largest displacement between two skinned poses, among vertices near a point. */
	private static float biggestShiftNear(dev.jsz.primordia.mesh.MeshData mesh,
	                                      float[] a, float[] b, Vector3f around, float radius) {
		float worst = 0f;
		for (int v = 0; v < mesh.vertexCount; v++) {
			int p = v * 3;
			float dx = mesh.positions[p] - around.x;
			float dy = mesh.positions[p + 1] - around.y;
			float dz = mesh.positions[p + 2] - around.z;
			if (dx * dx + dy * dy + dz * dz > radius * radius) continue;
			float mx = a[p] - b[p], my = a[p + 1] - b[p + 1], mz = a[p + 2] - b[p + 2];
			worst = Math.max(worst, (float) Math.sqrt(mx * mx + my * my + mz * mz));
		}
		return worst;
	}

	@Test
	void aBitingCreatureClosesItsMouthOnTheStrike() {
		BodyPlan plan = BodyPlanBuilder.build(Archetype.APEX.create(new Random(1215)));
		// The strike lands around halfway through; the jaw must be shutting by then, not opening.
		float gape = hingeOffAxis(plan, CreatureActivity.BITE, 0.40f).length();
		float impact = hingeOffAxis(plan, CreatureActivity.BITE, 0.90f).length();
		assertTrue(impact < gape,
				"the jaw is still open at the end of the bite: " + impact + " against a gape of " + gape);
	}
}
