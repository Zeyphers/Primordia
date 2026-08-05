package dev.jsz.primordia;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.body.BoneDef;
import dev.jsz.primordia.body.LimbChain;
import dev.jsz.primordia.anim.AnimationContext;
import dev.jsz.primordia.anim.CreatureAnimator;
import dev.jsz.primordia.genome.Archetype;
import dev.jsz.primordia.mesh.LodTier;
import dev.jsz.primordia.skeleton.Skeleton;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.Random;

/**
 * DIAGNOSTIC ONLY — prints, asserts nothing.
 * <p>
 * Asks how much of a creature you can actually see but not hit. The entity's bounding box is
 * derived in {@code CreatureEntity.getDefaultDimensions}; the body it is meant to cover is the
 * baked mesh, whose extent is {@code plan.boundsMin/boundsMax}. Where the mesh reaches outside the
 * box, a swing that visibly connects lands on nothing.
 */
class HitboxCoverageDiagnostic {

	/** The live formula from CreatureEntity.getDefaultDimensions, growth held at 1. */
	private static float[] entityBox(BodyPlan plan) {
		float legSpanX = 0f;
		for (LimbChain leg : plan.legs) {
			legSpanX = Math.max(legSpanX, Math.abs(leg.restEffector.x));
		}
		float width = Math.max(0.50f, Math.max(
				Math.max(legSpanX * 2.0f * 1.05f, plan.width() * 0.9f),
				Math.min(plan.bodyLength * 0.40f, 1.8f)));
		float height = Math.max(0.50f, Math.max(
				plan.hipHeight * 1.25f,
				Math.min(plan.height() * 0.92f, plan.hipHeight * 2.0f)));
		return new float[]{width, height};
	}

	/** Model-space half-extents of the spine box, mirroring CreatureEntity.getSpineHitbox. */
	private static float[] spineBox(BodyPlan plan) {
		float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
		float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
		int n = 0;
		for (BoneDef b : plan.bones) {
			if (!b.emitsGeometry || !b.name.startsWith("spine")) continue;
			float r = b.maxRadius();
			minX = Math.min(minX, Math.min(b.head.x, b.tail.x) - r);
			minY = Math.min(minY, Math.min(b.head.y, b.tail.y) - r);
			minZ = Math.min(minZ, Math.min(b.head.z, b.tail.z) - r);
			maxX = Math.max(maxX, Math.max(b.head.x, b.tail.x) + r);
			maxY = Math.max(maxY, Math.max(b.head.y, b.tail.y) + r);
			maxZ = Math.max(maxZ, Math.max(b.head.z, b.tail.z) + r);
			n++;
		}
		if (n == 0) return null;
		return new float[]{(maxX-minX)*0.5f, (maxY-minY)*0.5f, (maxZ-minZ)*0.5f,
				(minY+maxY)*0.5f};
	}

	/**
	 * How far the torso actually travels while the creature walks. If this is near zero the live
	 * skeleton is telling us nothing the bind pose did not, and a box that tracks it is pointless.
	 */
	/** Proposed height: track the real silhouette instead of forcing a multiple of the hip. */
	private static float proposedHeight(BodyPlan plan) {
		return Math.max(0.35f, Math.max(
				plan.hipHeight * 1.05f,
				Math.min(plan.height() * 0.85f, plan.hipHeight * 1.7f)));
	}

	@Test
	void compareHitboxHeights() {
		System.out.printf("%-13s %8s %8s %8s %8s %9s %9s%n",
				"archetype", "hip", "meshH", "oldH", "newH", "old/mesh", "new/mesh");
		for (Archetype a : Archetype.values()) {
			Random random = new Random(4242);
			double hip=0, mh=0, oh=0, nh=0; int n=0;
			for (int t = 0; t < 40; t++) {
				BodyPlan plan = BodyPlanBuilder.build(a.create(random));
				hip += plan.hipHeight;
				mh  += plan.boundsMax.y;
				oh  += entityBox(plan)[1];
				nh  += proposedHeight(plan);
				n++;
			}
			System.out.printf("%-13s %8.2f %8.2f %8.2f %8.2f %9.2f %9.2f%n",
					a, hip/n, mh/n, oh/n, nh/n, (oh/n)/(mh/n), (nh/n)/(mh/n));
		}
	}

	@Test
	void measureLiveCoreExcursion() {
		System.out.printf("%-11s %10s %10s %10s %10s%n",
				"archetype", "dX", "dY", "dZ", "vsBindY");
		for (Archetype a : new Archetype[]{Archetype.SAURIAN, Archetype.GRAZER,
				Archetype.APEX, Archetype.ARACHNID, Archetype.BIPED}) {
			Random random = new Random(4242);
			double ax=0, ay=0, az=0, bindGap=0; int n=0;
			for (int t = 0; t < 8; t++) {
				BodyPlan plan = BodyPlanBuilder.build(a.create(random));
				CreatureAnimator animator = new CreatureAnimator(plan);
				Skeleton sk = animator.skeleton();
				float[] bind = spineBox(plan);
				if (bind == null) continue;

				double minX=1e9,minY=1e9,minZ=1e9,maxX=-1e9,maxY=-1e9,maxZ=-1e9;
				Vector3f h = new Vector3f(), tl = new Vector3f();
				for (int f = 0; f < 60; f++) {
					AnimationContext ctx = new AnimationContext();
					ctx.time = f / 20f;
					ctx.z = ctx.time * 1.2f;
					ctx.speed = 1.2f;
					ctx.tier = LodTier.NEAR;
					animator.update(ctx);
					// Centre of the live spine, the same quantity getSpineHitbox uses.
					double sx=0, sy=0, sz=0; int c=0;
					for (int i = 0; i < plan.bones.length; i++) {
						if (!plan.bones[i].emitsGeometry || !plan.bones[i].name.startsWith("spine")) continue;
						sk.boneHead(i, h); sk.boneTail(i, tl);
						sx += (h.x+tl.x)*0.5; sy += (h.y+tl.y)*0.5; sz += (h.z+tl.z)*0.5; c++;
					}
					if (c == 0) continue;
					sx/=c; sy/=c; sz/=c;
					minX=Math.min(minX,sx); maxX=Math.max(maxX,sx);
					minY=Math.min(minY,sy); maxY=Math.max(maxY,sy);
					minZ=Math.min(minZ,sz); maxZ=Math.max(maxZ,sz);
				}
				ax += maxX-minX; ay += maxY-minY; az += maxZ-minZ;
				bindGap += Math.abs(((minY+maxY)*0.5) - bind[3]);
				n++;
			}
			System.out.printf("%-11s %10.3f %10.3f %10.3f %10.3f%n", a, ax/n, ay/n, az/n, bindGap/n);
		}
	}

	@Test
	void measureSpineHitboxSize() {
		System.out.printf("%-11s %8s %8s %8s %9s %9s %9s%n",
				"archetype", "spineX", "spineY", "spineZ", "boxW", "boxH", "centreY");
		for (Archetype a : Archetype.values()) {
			Random random = new Random(4242);
			double sx=0, sy=0, sz=0, bw=0, bh=0, cy=0; int n=0, missing=0;
			for (int t = 0; t < 40; t++) {
				BodyPlan plan = BodyPlanBuilder.build(a.create(random));
				float[] sb = spineBox(plan);
				if (sb == null) { missing++; continue; }
				float[] box = entityBox(plan);
				// Worst-case yaw: 45 degrees, where the turned torso encloses widest.
				double rx = (Math.abs(sb[0]) + Math.abs(sb[2])) * Math.sqrt(0.5);
                sx += rx * 2; sy += sb[1] * 2; sz += rx * 2;
				bw += box[0]; bh += box[1]; cy += sb[3];
				n++;
			}
			System.out.printf("%-11s %8.2f %8.2f %8.2f %9.2f %9.2f %9.2f%s%n",
					a, sx/n, sy/n, sz/n, bw/n, bh/n, cy/n,
					missing > 0 ? ("  (" + missing + " had no spine)") : "");
		}
	}

	@Test
	void measureHowMuchOfTheBodyIsOutsideTheHitbox() {
		System.out.printf("%-11s %5s %7s %7s %7s %7s %8s %8s %8s%n",
				"archetype", "n", "boxW", "boxH", "meshW", "meshH", "aboveTop", "outSide", "anyOut%");
		for (Archetype archetype : Archetype.values()) {
			Random random = new Random(4242);
			int n = 0, anyOut = 0;
			double sw = 0, sh = 0, mw = 0, mh = 0, above = 0, side = 0;

			for (int trial = 0; trial < 40; trial++) {
				BodyPlan plan = BodyPlanBuilder.build(archetype.create(random));
				float[] box = entityBox(plan);
				n++;

				// The mesh sits with y = 0 at the creature's feet, x/z centred on the entity, which
				// is the same frame the bounding box is expressed in.
				float meshH = plan.boundsMax.y;
				float halfW = box[0] * 0.5f;
				float overTop = Math.max(0f, meshH - box[1]);
				float overSide = Math.max(0f,
						Math.max(Math.max(plan.boundsMax.x, -plan.boundsMin.x),
								Math.max(plan.boundsMax.z, -plan.boundsMin.z)) - halfW);

				sw += box[0]; sh += box[1];
				mw += Math.max(plan.boundsMax.x, -plan.boundsMin.x) * 2f;
				mh += meshH;
				above += overTop; side += overSide;
				if (overTop > 0.05f || overSide > 0.05f) anyOut++;
			}
			System.out.printf("%-11s %5d %7.2f %7.2f %7.2f %7.2f %8.2f %8.2f %7.0f%%%n",
					archetype, n, sw/n, sh/n, mw/n, mh/n, above/n, side/n, 100.0*anyOut/n);
		}
	}
}
