package dev.jsz.primordia.anim;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.body.LimbChain;
import dev.jsz.primordia.genome.Archetype;
import dev.jsz.primordia.mesh.LodTier;
import org.joml.Vector3f;

import java.util.Random;

/**
 * Bind-pose sanity of every leg's bend hint.
 * <p>
 * {@code gradle kneeProbe}. Reports, per archetype: how many legs were given a pole so nearly
 * parallel to their own limb axis that no bend side could be derived from it (those get no
 * re-siding at all and the knee is free to invert), and the angle between each pole and its limb.
 */
public final class KneeProbe {
	public static void main(String[] args) {
		long seed = 4242L;
		System.out.printf("%-13s %5s %8s %9s %9s %8s %8s%n",
				"archetype", "legs", "degen", "minAngle", "poleLat", "sprawl", "leg/hipH");
		for (Archetype a : Archetype.VALUES) {
			Random r = new Random(seed + a.ordinal() * 7919L);
			BodyPlan plan = BodyPlanBuilder.build(a.create(r));
			int degenerate = 0;
			float min = 999f, sum = 0f, lat = 0f;
			for (LimbChain leg : plan.legs) {
				Vector3f axis = new Vector3f(leg.restEffector).sub(leg.origin).normalize();
				Vector3f pole = new Vector3f(leg.poleDirection).normalize();
				float dot = Math.abs(pole.dot(axis));
				float angle = (float) Math.toDegrees(Math.acos(Math.min(1f, dot)));
				min = Math.min(min, angle);
				sum += angle;
				// How much of the pole points out to the side rather than fore/aft or up.
				lat += Math.abs(pole.x);
				boolean allZero = true;
				for (float s : leg.bendSigns) if (s != 0f) allZero = false;
				if (leg.bendSigns.length > 0 && allZero) degenerate++;
			}
			int n = Math.max(1, plan.legs.length);
			float sprawl = 0f, ratio = 0f;
			for (LimbChain leg : plan.legs) {
				float drop = Math.max(1e-4f, leg.origin.y - leg.restEffector.y);
				sprawl += Math.abs(leg.restEffector.x - leg.origin.x) / drop;
				ratio += leg.totalLength / Math.max(1e-4f, plan.hipHeight);
			}
			System.out.printf("%-13s %5d %8d %9.1f %9.2f %8.2f %8.2f%n",
					a, plan.legs.length, degenerate, min, lat / n, sprawl / n, ratio / n);
		}

		System.out.println();
		System.out.println("per-leg pole, many-legged archetypes (model space, normalised)");
		System.out.printf("%-13s %4s %6s %7s %7s %7s %8s%n",
				"archetype", "leg", "side", "poleX", "poleY", "poleZ", "footZ");
		for (Archetype a : new Archetype[]{Archetype.INSECTOID, Archetype.ARACHNID,
				Archetype.CRUSTACEAN, Archetype.CAVE_CRAWLER, Archetype.SAURIAN}) {
			Random r = new Random(seed + a.ordinal() * 7919L);
			BodyPlan plan = BodyPlanBuilder.build(a.create(r));
			for (int i = 0; i < plan.legs.length; i++) {
				LimbChain leg = plan.legs[i];
				Vector3f pole = new Vector3f(leg.poleDirection).normalize();
				System.out.printf("%-13s %4d %6d %7.2f %7.2f %7.2f %8.3f%n",
						a, i, leg.side, pole.x, pole.y, pole.z,
						leg.restEffector.z - leg.origin.z);
			}
		}
	}
}
