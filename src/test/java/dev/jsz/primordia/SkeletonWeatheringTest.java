package dev.jsz.primordia;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.client.render.CreatureRenderer;
import dev.jsz.primordia.genome.Genome;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cover for the ground-up weathering geometry, independent of a running renderer.
 * <p>
 * {@code CreatureRenderer.poseSpaceHeightRange} is the one piece of the effect that can go wrong
 * silently: it has to read the same pose the vertices are about to be drawn with, including the
 * carcass flip in {@code submit()} that turns the body onto its side. Get that transform wrong and
 * the darkening either creeps sideways instead of up, or is measured against a "ground" that is
 * not where the body actually lies — both invisible from the code and only found by looking at a
 * skeleton in a world.
 */
class SkeletonWeatheringTest {

	/** The exact sequence submit() applies to a carcass: lift, flip onto the back, settle. */
	private static PoseStack.Pose carcassPose(float carcassLift, float hipHeight) {
		PoseStack stack = new PoseStack();
		stack.translate(0f, carcassLift, 0f);
		stack.mulPose(Axis.ZP.rotationDegrees(180f));
		stack.translate(0f, -hipHeight, 0f);
		return stack.last();
	}

	@Test
	void aStandingBoxHasMoreHeightThanALyingOneOfTheSamePlan() {
		Random random = new Random(1);
		BodyPlan plan = BodyPlanBuilder.build(Genome.random(random));
		Vector3f scratch = new Vector3f();

		PoseStack.Pose standing = new PoseStack().last();
		float[] standingRange = CreatureRenderer.poseSpaceHeightRange(plan, standing, scratch);

		PoseStack.Pose lying = carcassPose(0.2f, plan.hipHeight);
		float[] lyingRange = CreatureRenderer.poseSpaceHeightRange(plan, lying, scratch);

		float standingSpan = standingRange[1] - standingRange[0];
		float lyingSpan = lyingRange[1] - lyingRange[0];
		assertTrue(lyingSpan < standingSpan,
				"a body on its side should measure thinner top-to-bottom than the same body "
						+ "standing, or the flip transform is not being read correctly");
	}

	@Test
	void theRangeIsNeverInvertedOrDegenerate() {
		Random random = new Random(2);
		Vector3f scratch = new Vector3f();
		for (int trial = 0; trial < 30; trial++) {
			BodyPlan plan = BodyPlanBuilder.build(Genome.random(random));
			PoseStack.Pose lying = carcassPose(0.15f, plan.hipHeight);
			float[] range = CreatureRenderer.poseSpaceHeightRange(plan, lying, scratch);
			assertTrue(range[0] <= range[1], "min above max for " + plan.genome);
			assertTrue(range[1] - range[0] > 0.001f,
					"a lying body should still have some thickness to weather up through");
		}
	}

	@Test
	void growthScalesTheRangeDown() {
		Random random = new Random(3);
		BodyPlan plan = BodyPlanBuilder.build(Genome.random(random));
		Vector3f scratch = new Vector3f();

		PoseStack full = new PoseStack();
		full.translate(0f, 0.2f, 0f);
		full.mulPose(Axis.ZP.rotationDegrees(180f));
		full.translate(0f, -plan.hipHeight, 0f);
		float[] adultRange = CreatureRenderer.poseSpaceHeightRange(plan, full.last(), scratch);

		PoseStack juvenile = new PoseStack();
		juvenile.scale(0.4f, 0.4f, 0.4f);
		juvenile.translate(0f, 0.2f, 0f);
		juvenile.mulPose(Axis.ZP.rotationDegrees(180f));
		juvenile.translate(0f, -plan.hipHeight, 0f);
		float[] juvenileRange = CreatureRenderer.poseSpaceHeightRange(plan, juvenile.last(), scratch);

		assertTrue(juvenileRange[1] - juvenileRange[0] < adultRange[1] - adultRange[0],
				"a smaller creature's lying-down thickness should shrink with it");
	}
}
