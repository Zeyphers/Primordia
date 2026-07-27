package dev.jsz.primordia;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.body.BoneDef;
import dev.jsz.primordia.body.ToothDef;
import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.sdf.BodySdf;
import dev.jsz.primordia.skeleton.Skeleton;
import dev.jsz.primordia.util.MathX;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Teeth must not come out through the other jaw when the mouth closes.
 * <p>
 * A tooth stopping <i>inside</i> the flesh it bites into is fine and invisible — both are opaque,
 * and the surface drawn over it hides it. One that runs the whole way through is not: that is
 * fangs standing out of the top of the skull and points emerging under the chin.
 * <p>
 * This can only be seen in the closed pose, which is why nothing else caught it. The mesh is baked
 * with the mouth wide open, and in that pose every tooth sits harmlessly in the gap; the collision
 * only exists once the animator rotates the mandible up to meet the skull. Measured at the length
 * teeth were first given, better than a third of them came through.
 */
class ToothClippingTest {

	/** Marches the field the way {@code ToothMesher} does, to find where a tooth leaves the gum. */
	private static float surfaceDistance(BodySdf sdf, ToothDef tooth) {
		float step = Math.max(tooth.radius() * 0.5f, 1e-3f);
		float travelled = 0f;
		while (travelled < step * 120f) {
			travelled += step;
			if (sdf.eval(tooth.root().x + tooth.direction().x * travelled,
					tooth.root().y + tooth.direction().y * travelled,
					tooth.root().z + tooth.direction().z * travelled) > 0f) {
				return travelled;
			}
		}
		return step * 8f;
	}

	@Test
	void noToothEmergesThroughTheOppositeJawWhenTheMouthCloses() {
		Random random = new Random(77);

		for (float diet : new float[]{0.9f, 0.5f, 0.1f}) {
			int through = 0;
			int total = 0;
			float worst = 0f;

			for (int trial = 0; trial < 25; trial++) {
				BodyPlan plan = BodyPlanBuilder.build(Genome.random(random).with(Gene.DIET, diet));
				BodySdf sdf = new BodySdf(plan);

				// The resting pose: mouth shut. This is where the two rows meet.
				Skeleton skeleton = new Skeleton(plan);
				skeleton.resetPose();
				skeleton.setLocalRotation(plan.jawBone,
						new Quaternionf().rotateX(-plan.jawRestAngle));
				skeleton.updateWorld();
				skeleton.updateSkinMatrices();

				for (ToothDef tooth : plan.teeth) {
					boolean lower = tooth.bone() == plan.jawBone;
					BoneDef opposing = plan.bones[lower ? plan.headBone : plan.jawBone];

					float extent = Math.min(surfaceDistance(sdf, tooth) + tooth.protrusion(),
							tooth.maxExtent());
					Vector3f tip = new Vector3f(tooth.root()).fma(extent, tooth.direction());
					skeleton.skinMatrix(tooth.bone()).transformPosition(tip);

					float t = MathX.projectOntoSegment(tip.x, tip.y, tip.z,
							opposing.head.x, opposing.head.y, opposing.head.z,
							opposing.tail.x, opposing.tail.y, opposing.tail.z);
					Vector3f on = new Vector3f(opposing.head).lerp(opposing.tail, t);
					float outside = tip.distance(on)
							- MathX.lerp(opposing.radiusHead, opposing.radiusTail, t);

					// Outside the opposing jaw's surface *and* past its axis: it went through.
					boolean pastAxis = lower ? tip.y > on.y : tip.y < on.y;
					total++;
					if (outside > 0f && pastAxis) {
						through++;
						worst = Math.max(worst, outside);
					}
				}
			}

			assertEquals(0, through, String.format(
					"diet %.1f: %d of %d teeth come through the opposite jaw with the mouth shut "
							+ "(worst by %.4f) — that is teeth standing out of the skull",
					diet, through, total, worst));
		}
	}
}
