package dev.jsz.primordia.body;

import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.util.MathX;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Grows a {@link BodyPlan} from a {@link Genome}. This is the "development" step — the
 * single place where abstract gene values become an actual animal.
 * <p>
 * Model space is metres, Y up, +Z forward, origin on the ground directly under the middle
 * of the torso. Two invariants make everything downstream simpler:
 * <ul>
 *   <li><b>Parents precede children</b> in {@link BodyPlan#bones}, so a single forward pass
 *       resolves every world transform.</li>
 *   <li><b>Legs are built from the ground up</b>: the hip height is chosen first, the foot is
 *       pinned to y = 0, and the bone lengths are derived from the resulting curve. A creature
 *       therefore cannot generate with legs too short to stand on.</li>
 * </ul>
 * The whole method is a pure function of the genome, so results are cacheable and identical
 * on client and server.
 */
public final class BodyPlanBuilder {
	/** Bones are made this much longer than the straight-line hip-to-foot distance, leaving bend room for IK. */
	private static final float LIMB_SLACK = 1.10f;
	/**
	 * How much a limb narrows from its root to its tip. Was 0.45, which tapered ankles down to
	 * near-nothing; 0.66 keeps the far end substantial enough to survive meshing and to read as a
	 * limb rather than a wire.
	 */
	private static final float LIMB_TAPER = 0.66f;
	/** Additional narrowing from ankle to toe tip, on top of {@link #LIMB_TAPER}. */
	private static final float FOOT_TAPER = 0.8f;
	/** How much of its base radius a tail retains at the tip. */
	private static final float TAIL_TIP_TAPER = 0.42f;

	private BodyPlanBuilder() {
	}

	public static BodyPlan build(Genome g) {
		List<BoneDef> bones = new ArrayList<>();
		List<SdfBlob> blobs = new ArrayList<>();
		List<LimbChain> legs = new ArrayList<>();
		List<LimbChain> arms = new ArrayList<>();
		// Group 0 is the trunk; each limb claims the next id as it is built.
		int nextBlendGroup = BoneDef.AXIAL + 1;

		// ---- gross proportions ------------------------------------------------
		// Wide enough to reach genuine dinosaur scale at the top end. The bias exponent keeps the
		// giants rare — with a linear map most creatures would land mid-range, which at this span
		// means everything is enormous and nothing reads as big by comparison.
		// Meshing cost does not grow with size: the sampling resolution is driven by the ratio of
		// body span to limb thickness, and both scale together.
		// The bias exponent is what makes giants genuinely rare rather than merely possible: a
		// cubic curve puts the median creature near 0.9 m while still allowing 5 m outliers, so a
		// large animal reads as an event. With a gentler curve the whole population drifts big and
		// nothing looks large by comparison.
		float size = g.biased(Gene.SIZE, 0.35f, 1.45f, 2.2f);
		float torsoLength = size * g.range(Gene.TORSO_LENGTH, 0.45f, 1.7f);
		float girth = size * g.range(Gene.TORSO_GIRTH, 0.10f, 0.38f);
		float taper = g.range(Gene.TORSO_TAPER, 0.55f, 1.45f);
		int spineSegments = g.discrete(Gene.SPINE_SEGMENTS, 3, 8);
		float arch = size * g.range(Gene.SPINE_ARCH, -0.10f, 0.22f);

		// Hip height is a first-class trait: it decides stance, and the legs are fitted to it.
		float hipHeight = size * g.biased(Gene.LEG_LENGTH, 0.18f, 1.35f, 1.15f);
		// Floor it so the belly clears the ground. Without this, a wide-bodied genome with a
		// downward spine arch and short legs puts the hip joint within a hair of y = 0: the legs
		// come out as degenerate stubs and the torso drags. The terms are the torso's own radius,
		// the depth of any downward arch, and a fixed margin.
		float maxTorsoRadius = girth * Math.max(taper, 1f);
		float clearance = maxTorsoRadius * 0.45f + Math.max(0f, -arch) + size * 0.05f;
		hipHeight = Math.max(hipHeight, clearance);

		// Limb thickness is decided up front because two later choices depend on it: the blend
		// radius must not exceed it, and the mesher's sampling resolution is derived from it.
		// The floor is both relative to the torso and absolute to the creature's size — a genome
		// pairing a slim torso with the low end of LEG_THICKNESS used to produce limbs a couple of
		// millimetres thick, which read as invisible however finely they were sampled.
		// The absolute floors are also a rendering cost control: the mesher has to resolve cells
		// finer than the thinnest limb, so halving the minimum limb radius doubles grid resolution.
		float legThickness = Math.max(girth * g.range(Gene.LEG_THICKNESS, 0.42f, 1.0f), size * 0.060f);
		float armThickness = Math.max(girth * g.range(Gene.ARM_THICKNESS, 0.26f, 0.6f), size * 0.040f);
		// Must account for the narrowest geometry actually emitted, which is the toe bone's far
		// radius — the leg's own taper times the foot's additional narrowing. Measuring against the
		// untapered thickness would leave the blend radius wider than the thinnest real feature.
		float thinnestLimb = Math.min(legThickness * LIMB_TAPER * FOOT_TAPER, armThickness * LIMB_TAPER);

		// Cap the smooth-union radius against the thinnest limb. A blend wider than the limb it is
		// blending fairs the whole leg into the torso and eats it.
		float blendRadius = Math.min(size * g.range(Gene.BLEND_SMOOTHNESS, 0.025f, 0.13f),
				thinnestLimb * 1.1f);

		// ---- spine ------------------------------------------------------------
		Vector3f[] spinePts = new Vector3f[spineSegments + 1];
		float[] spineRadii = new float[spineSegments + 1];
		for (int i = 0; i <= spineSegments; i++) {
			float u = (float) i / spineSegments;
			spinePts[i] = new Vector3f(
					0f,
					hipHeight + arch * (float) Math.sin(Math.PI * u),
					MathX.lerp(-torsoLength * 0.5f, torsoLength * 0.5f, u));
			spineRadii[i] = torsoRadius(girth, taper, u);
		}

		int rootBone = 0;
		for (int i = 0; i < spineSegments; i++) {
			bones.add(new BoneDef("spine" + i, i == 0 ? -1 : i - 1,
					spinePts[i], spinePts[i + 1], spineRadii[i], spineRadii[i + 1]));
		}
		int shoulderBone = spineSegments - 1;

		// ---- neck -------------------------------------------------------------
		float neckLength = size * g.biased(Gene.NECK_LENGTH, 0.04f, 1.45f, 1.5f);
		int neckSegments = neckLength < 0.12f * size ? 1 : g.discrete(Gene.NECK_SEGMENTS, 1, 4);
		float neckRadius = girth * g.range(Gene.NECK_THICKNESS, 0.22f, 0.78f);
		// Long necks rear up; short thick necks stay level with the spine.
		float neckPitch = MathX.remap(neckLength / size, 0.04f, 1.45f, 0.08f, 0.95f);

		int parent = shoulderBone;
		Vector3f cursor = new Vector3f(spinePts[spineSegments]);
		float segLen = neckLength / neckSegments;
		for (int i = 0; i < neckSegments; i++) {
			// Ease the pitch out along the neck so it arches instead of shooting off straight.
			float pitch = neckPitch * (1f - 0.55f * ((float) i / neckSegments));
			Vector3f dir = new Vector3f(0f, (float) Math.sin(pitch), (float) Math.cos(pitch)).normalize();
			Vector3f next = new Vector3f(cursor).fma(segLen, dir);
			float r0 = MathX.lerp(neckRadius, neckRadius * 0.7f, (float) i / neckSegments);
			float r1 = MathX.lerp(neckRadius, neckRadius * 0.7f, (float) (i + 1) / neckSegments);
			bones.add(new BoneDef("neck" + i, parent, new Vector3f(cursor), next, r0, r1, Feature.BODY, true));
			parent = bones.size() - 1;
			cursor = next;
		}

		// ---- head -------------------------------------------------------------
		float headSize = size * g.biased(Gene.HEAD_SIZE, 0.10f, 0.46f, 1.15f);
		float headLength = headSize * g.range(Gene.HEAD_ELONGATION, 0.85f, 2.6f);
		float headPitch = neckPitch * 0.25f;
		Vector3f headDir = new Vector3f(0f, (float) Math.sin(headPitch) - 0.15f, (float) Math.cos(headPitch)).normalize();
		Vector3f headTail = new Vector3f(cursor).fma(headLength, headDir);
		int headBone = bones.size();
		bones.add(new BoneDef("head", parent, new Vector3f(cursor), headTail,
				headSize * 0.55f, headSize * 0.30f, Feature.HEAD, true));

		addHeadDetail(g, blobs, headBone, cursor, headTail, headDir, headSize);

		// ---- tail -------------------------------------------------------------
		float tailLength = size * g.biased(Gene.TAIL_LENGTH, 0f, 2.0f, 1.3f);
		if (tailLength > 0.08f * size) {
			int tailSegments = g.discrete(Gene.TAIL_SEGMENTS, 1, 6);
			// Floored like the limbs. A tail is long and finely tapered, so it is the geometry most
			// prone to thinning into an invisible thread — and unlike a leg, nothing about the
			// creature standing up forces it to stay substantial.
			// Two floors, and both are needed. The absolute one keeps a tail from being a thread on
			// its own terms; the blend-relative one matters because BLEND_SMOOTHNESS ranges wider
			// than the tail thickness gene does, so a heavily-blended creature could otherwise have
			// its entire tail — base included, not just the tip — absorbed into the smooth union.
			float tailRadius = Math.max(
					Math.max(girth * g.range(Gene.TAIL_THICKNESS, 0.18f, 0.60f), size * 0.045f),
					blendRadius * 1.15f);
			float tailTip = Math.max(tailRadius * TAIL_TIP_TAPER, blendRadius * 1.05f);
			float tailSeg = tailLength / tailSegments;
			int tailParent = rootBone;
			Vector3f tCursor = new Vector3f(spinePts[0]);
			for (int i = 0; i < tailSegments; i++) {
				// Droop increases along the tail so it hangs rather than sticking out rigidly.
				float droop = MathX.lerp(0.05f, 0.55f, (float) i / Math.max(1, tailSegments - 1));
				Vector3f dir = new Vector3f(0f, -(float) Math.sin(droop), -(float) Math.cos(droop)).normalize();
				Vector3f next = new Vector3f(tCursor).fma(tailSeg, dir);
				// Keep the tail tip from burying itself in the ground.
				next.y = Math.max(next.y, tailRadius * 0.8f);
				float r0 = MathX.lerp(tailRadius, tailTip, (float) i / tailSegments);
				float r1 = MathX.lerp(tailRadius, tailTip, (float) (i + 1) / tailSegments);
				bones.add(new BoneDef("tail" + i, tailParent, new Vector3f(tCursor), next, r0, r1, Feature.TAIL, true));
				tailParent = bones.size() - 1;
				tCursor = next;
			}
		}

		// ---- legs -------------------------------------------------------------
		int legPairs = g.discrete(Gene.LEG_PAIRS, 1, 4);
		int legSegments = g.discrete(Gene.LEG_SEGMENTS, 2, 3);
		float splay = g.range(Gene.LEG_SPLAY, 0.05f, 0.70f);
		float footSize = size * g.range(Gene.FOOT_SIZE, 0.04f, 0.17f);
		float digitigrade = g.raw(Gene.DIGITIGRADE);
		float pairPhase = g.range(Gene.GAIT_OFFSET, 0f, 0.5f);

		for (int pair = 0; pair < legPairs; pair++) {
			// u = 1 at the shoulder, decreasing toward the pelvis.
			float u = legPairs == 1 ? 0.4f : MathX.lerp(0.88f, 0.12f, (float) pair / (legPairs - 1));
			int attach = MathX.clamp((int) (u * spineSegments), 0, spineSegments - 1);
			Vector3f spineAt = sampleSpine(spinePts, u);
			float rAt = torsoRadius(girth, taper, u);

			for (int s = -1; s <= 1; s += 2) {
				Vector3f hip = new Vector3f(s * rAt * 0.85f, spineAt.y - rAt * 0.35f, spineAt.z);
				Vector3f foot = new Vector3f(hip.x + s * (rAt * 0.25f + hipHeight * splay), 0f, hip.z);

				// Front limbs bend backward at the elbow, hind limbs forward at the knee.
				boolean front = pair < legPairs / 2f;
				Vector3f pole = new Vector3f(s * 0.2f, 0f, front ? -1f : 1f).normalize();

				float phase = (pair * pairPhase + (s > 0 ? 0.5f : 0f)) % 1f;
				// Every limb gets its own blend group, left and right included — a creature's own
				// legs should never fuse to each other however closely they are packed.
				int group = nextBlendGroup++;
				LimbChain chain = buildLimb(bones, "leg" + pair + (s > 0 ? "R" : "L"), attach, hip, foot, pole,
						legSegments, legThickness,
						legSegments >= 3 ? digitigrade : 0f, s, pair, phase, true, group);
				legs.add(chain);

				// Toe geometry: a short forward-pointing bone parented to the last leg bone.
				// Kept close to the ankle's own thickness so the foot reads as a foot, not a pin.
				int ankleBone = chain.bones[chain.bones.length - 1];
				Vector3f toe = new Vector3f(foot).add(0f, 0f, footSize);
				bones.add(new BoneDef("foot" + pair + (s > 0 ? "R" : "L"), ankleBone,
						new Vector3f(foot), toe,
						legThickness * LIMB_TAPER * 1.05f, legThickness * LIMB_TAPER * FOOT_TAPER,
						Feature.FOOT, true, group));
			}
		}

		// ---- arms -------------------------------------------------------------
		// Arms are now common enough to actually see. They drive the claw attack style, so keeping
		// them rare meant most creatures defaulted to biting regardless of what they had grown.
		float armRoll = g.raw(Gene.ARM_PAIRS);
		int armPairs = armRoll < 0.42f ? 0 : (armRoll < 0.85f ? 1 : 2);
		if (armPairs > 0) {
			float armLength = size * g.range(Gene.ARM_LENGTH, 0.15f, 0.8f);
			for (int pair = 0; pair < armPairs; pair++) {
				float u = 0.92f - pair * 0.16f;
				int attach = MathX.clamp((int) (u * spineSegments), 0, spineSegments - 1);
				Vector3f spineAt = sampleSpine(spinePts, u);
				float rAt = torsoRadius(girth, taper, u);
				for (int s = -1; s <= 1; s += 2) {
					Vector3f shoulder = new Vector3f(s * rAt * 0.9f, spineAt.y + rAt * 0.15f, spineAt.z);
					Vector3f hand = new Vector3f(shoulder.x + s * armLength * 0.35f,
							shoulder.y - armLength * 0.8f, shoulder.z + armLength * 0.45f);
					Vector3f pole = new Vector3f(s * 0.25f, 0f, -1f).normalize();
					arms.add(buildLimb(bones, "arm" + pair + (s > 0 ? "R" : "L"), attach, shoulder, hand, pole,
							2, armThickness, 0f, s, pair, 0f, false, nextBlendGroup++));
				}
			}
		}

		// ---- dorsal ornament & armour plates ----------------------------------
		if (g.expresses(Gene.DORSAL_SPINES, 0.55f) || g.expresses(Gene.SPINE_STYLE, 0.40f)) {
			float spineLen = size * g.range(Gene.DORSAL_SPINE_LENGTH, 0.05f, 0.45f);
			Feature spineFeat = g.raw(Gene.SPINE_STYLE) > 0.5f ? Feature.SPINE : Feature.PLATE;
			for (int i = 1; i < spineSegments; i++) {
				float u = (float) i / spineSegments;
				float r = spineRadii[i];
				float h = spineLen * (0.45f + 0.55f * (float) Math.sin(Math.PI * u));
				blobs.add(new SdfBlob(Math.min(i, spineSegments - 1),
						new Vector3f(0f, spinePts[i].y + r * 0.75f + h * 0.45f, spinePts[i].z),
						new Vector3f(r * 0.22f, h, r * 0.42f), spineFeat, false));
			}
		}

		if (g.expresses(Gene.ARMOR_COVERAGE, 0.42f)) {
			// Dorsal armor plates along flanks and shoulders
			float plateSize = girth * 0.55f;
			for (int i = 0; i < spineSegments; i += 2) {
				Vector3f pos = new Vector3f(0f, spinePts[i].y + spineRadii[i] * 0.85f, spinePts[i].z);
				blobs.add(new SdfBlob(Math.min(i, spineSegments - 1), pos,
						new Vector3f(plateSize * 0.9f, plateSize * 0.35f, plateSize * 0.7f), Feature.PLATE, false));
			}
		}

		// ---- claws & hands ----------------------------------------------------
		boolean hasClaws = g.expresses(Gene.CLAWS, 0.35f);
		boolean hasHands = g.expresses(Gene.HAND_STYLE, 0.35f);

		for (LimbChain leg : legs) {
			if (hasClaws && leg.bones.length > 0) {
				int endBone = leg.bones[leg.bones.length - 1];
				BoneDef boneDef = bones.get(endBone);
				Vector3f tailPos = boneDef.tail;
				float radius = boneDef.maxRadius();
				blobs.add(new SdfBlob(endBone, new Vector3f(tailPos).fma(0.04f, new Vector3f(0f, -1f, 0.8f)),
						new Vector3f(radius * 0.6f, radius * 0.4f, radius * 1.2f), Feature.CLAWS, false));
			}
		}

		for (LimbChain arm : arms) {
			if (arm.bones.length > 0) {
				int endBone = arm.bones[arm.bones.length - 1];
				BoneDef boneDef = bones.get(endBone);
				Vector3f tailPos = boneDef.tail;
				float radius = boneDef.maxRadius();
				Feature feat = hasHands ? Feature.HAND : (hasClaws ? Feature.CLAWS : Feature.LIMB);
				blobs.add(new SdfBlob(endBone, new Vector3f(tailPos),
						new Vector3f(radius * 0.85f, radius * 0.75f, radius * 1.1f), feat, false));
			}
		}

		// ---- derived quantities -----------------------------------------------
		BoneDef[] boneArray = bones.toArray(new BoneDef[0]);
		SdfBlob[] blobArray = blobs.toArray(new SdfBlob[0]);
		Vector3f min = new Vector3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
		Vector3f max = new Vector3f(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
		float mass = 0f;

		float pad = blendRadius * 1.5f + 0.02f;
		for (BoneDef b : boneArray) {
			if (!b.emitsGeometry) continue;
			float r = b.maxRadius() + pad;
			expand(min, max, b.head, r);
			expand(min, max, b.tail, r);
			float avgR = (b.radiusHead + b.radiusTail) * 0.5f;
			mass += (float) Math.PI * avgR * avgR * b.length();
		}
		for (SdfBlob blob : blobArray) {
			if (blob.subtract()) continue;
			expand(min, max, blob.center(), blob.maxRadius() + pad);
			mass += (float) (4.0 / 3.0 * Math.PI * blob.radii().x * blob.radii().y * blob.radii().z);
		}
		// The ground plane is always part of the silhouette envelope — feet sit at y = 0.
		min.y = Math.min(min.y, -pad);

		float bodyLength = max.z - min.z;

		// Thinnest slender feature present — limbs, feet and tails alike. This is what the mesher
		// must be able to resolve, and a tapered tail tip is frequently the narrowest thing on the
		// whole animal, so leaving it out here is what let tails sample away to nothing.
		float minLimbRadius = Float.MAX_VALUE;
		for (BoneDef b : boneArray) {
			if (!b.emitsGeometry) continue;
			if (b.feature != Feature.LIMB && b.feature != Feature.FOOT && b.feature != Feature.TAIL) continue;
			minLimbRadius = Math.min(minLimbRadius, Math.min(b.radiusHead, b.radiusTail));
		}
		// Enforce maximum height cap of 2.5 meters tall
		float rawHeight = max.y - Math.min(0f, min.y);
		if (rawHeight > 2.5f) {
			float scale = 2.5f / rawHeight;
			hipHeight *= scale;
			min.mul(scale);
			max.mul(scale);
			bodyLength *= scale;
			mass *= (scale * scale * scale);
			minLimbRadius *= scale;
			blendRadius *= scale;

			for (int i = 0; i < boneArray.length; i++) {
				BoneDef old = boneArray[i];
				Vector3f h = new Vector3f(old.head).mul(scale);
				Vector3f t = new Vector3f(old.tail).mul(scale);
				boneArray[i] = new BoneDef(old.name, old.parent, h, t,
						old.radiusHead * scale, old.radiusTail * scale,
						old.feature, old.emitsGeometry, old.blendGroup);
			}
			for (int i = 0; i < blobArray.length; i++) {
				SdfBlob old = blobArray[i];
				Vector3f c = new Vector3f(old.center()).mul(scale);
				Vector3f r = new Vector3f(old.radii()).mul(scale);
				blobArray[i] = new SdfBlob(old.bone(), c, r, old.feature(), old.subtract());
			}
			for (LimbChain leg : legs) {
				leg.origin.mul(scale);
				leg.restEffector.mul(scale);
			}
			for (LimbChain arm : arms) {
				arm.origin.mul(scale);
				arm.restEffector.mul(scale);
			}
		}

		return new BodyPlan(g, boneArray, blobArray,
				legs.toArray(new LimbChain[0]), arms.toArray(new LimbChain[0]),
				new BodyPalette(g), blendRadius, rootBone, headBone, hipHeight,
				min, max, bodyLength, mass, minLimbRadius);
	}

	// ------------------------------------------------------------------ helpers

	private static float torsoRadius(float girth, float taper, float u) {
		// Lerp handles front-vs-rear bulk; the sine term keeps the ends from being as thick as the barrel.
		float profile = 0.58f + 0.42f * (float) Math.sin(Math.PI * (0.10f + 0.80f * u));
		return girth * MathX.lerp(taper, 1f, u) * profile;
	}

	private static Vector3f sampleSpine(Vector3f[] pts, float u) {
		float f = MathX.clamp01(u) * (pts.length - 1);
		int i = MathX.clamp((int) f, 0, pts.length - 2);
		float t = f - i;
		return new Vector3f(pts[i]).lerp(pts[i + 1], t);
	}

	/**
	 * Lays a limb along a cubic Bezier from {@code origin} to {@code effector}, bulging toward
	 * {@code pole}. {@code sCurve} flips the second control point to the opposite side, which is
	 * what turns a plantigrade C-shaped leg into a digitigrade S-shaped one.
	 */
	private static LimbChain buildLimb(List<BoneDef> bones, String name, int attach,
	                                   Vector3f origin, Vector3f effector, Vector3f pole,
	                                   int segments, float thickness, float sCurve,
	                                   int side, int pairIndex, float phase, boolean weightBearing,
	                                   int blendGroup) {
		float reach = origin.distance(effector);
		float bend = reach * (LIMB_SLACK - 1f) * 2.2f;

		Vector3f c1 = new Vector3f(origin).fma(bend, pole);
		Vector3f c2 = new Vector3f(effector).fma(bend * (1f - 2f * sCurve), pole);

		Vector3f[] joints = new Vector3f[segments + 1];
		for (int i = 0; i <= segments; i++) {
			joints[i] = cubicBezier(origin, c1, c2, effector, (float) i / segments);
		}
		// Pin the endpoints exactly: the hip must meet the body and the foot must touch y = 0.
		joints[0] = new Vector3f(origin);
		joints[segments] = new Vector3f(effector);

		int[] indices = new int[segments];
		float total = 0f;
		int parent = attach;
		for (int i = 0; i < segments; i++) {
			float r0 = thickness * MathX.lerp(1f, LIMB_TAPER, (float) i / segments);
			float r1 = thickness * MathX.lerp(1f, LIMB_TAPER, (float) (i + 1) / segments);
			bones.add(new BoneDef(name + "_" + i, parent, joints[i], joints[i + 1], r0, r1,
					Feature.LIMB, true, blendGroup));
			parent = bones.size() - 1;
			indices[i] = parent;
			total += joints[i].distance(joints[i + 1]);
		}

		// Record which side of the limb axis each interior joint was grown on. The solver restores
		// these signs every frame, which is what keeps a digitigrade leg's opposed knee and hock
		// from collapsing onto the same side or flipping between frames.
		Vector3f limbAxis = new Vector3f(effector).sub(origin);
		float[] bendSigns = new float[Math.max(0, segments - 1)];
		if (limbAxis.lengthSquared() > 1e-10f) {
			limbAxis.normalize();
			Vector3f perp = new Vector3f(pole).fma(-pole.dot(limbAxis), limbAxis);
			if (perp.lengthSquared() > 1e-8f) {
				perp.normalize();
				for (int i = 1; i < segments; i++) {
					float along = new Vector3f(joints[i]).sub(origin).dot(perp);
					// Exactly-on-axis joints get +1 rather than 0, so the sign is always decisive.
					bendSigns[i - 1] = along < 0f ? -1f : 1f;
				}
			}
		}

		return new LimbChain(indices, new Vector3f(origin), new Vector3f(effector), new Vector3f(pole),
				side, pairIndex, phase, total, weightBearing, bendSigns);
	}

	private static Vector3f cubicBezier(Vector3f p0, Vector3f p1, Vector3f p2, Vector3f p3, float t) {
		float mt = 1f - t;
		float a = mt * mt * mt, b = 3f * mt * mt * t, c = 3f * mt * t * t, d = t * t * t;
		return new Vector3f(
				a * p0.x + b * p1.x + c * p2.x + d * p3.x,
				a * p0.y + b * p1.y + c * p2.y + d * p3.y,
				a * p0.z + b * p1.z + c * p2.z + d * p3.z);
	}

	private static void addHeadDetail(Genome g, List<SdfBlob> blobs, int headBone,
	                                  Vector3f headStart, Vector3f headEnd, Vector3f headDir, float headSize) {
		Vector3f up = new Vector3f(0f, 1f, 0f);
		Vector3f right = new Vector3f(up).cross(headDir);
		if (right.lengthSquared() < 1e-6f) right.set(1f, 0f, 0f);
		right.normalize();
		Vector3f trueUp = new Vector3f(headDir).cross(right).normalize().mul(-1f);

		// Cranium: a bulge behind the eyes that gives the skull a braincase silhouette.
		float bulge = g.range(Gene.CRANIUM_BULGE, 0.55f, 1.25f);
		Vector3f cranium = new Vector3f(headStart).lerp(headEnd, 0.28f).fma(headSize * 0.12f, trueUp);
		blobs.add(new SdfBlob(headBone, cranium,
				new Vector3f(headSize * 0.58f * bulge, headSize * 0.55f * bulge, headSize * 0.62f * bulge),
				Feature.HEAD, false));

		// Jaw: mass slung under the front of the head.
		float jaw = g.range(Gene.JAW_SIZE, 0.35f, 1.15f);
		Vector3f jawPos = new Vector3f(headStart).lerp(headEnd, 0.68f).fma(-headSize * 0.30f * jaw, trueUp);
		blobs.add(new SdfBlob(headBone, jawPos,
				new Vector3f(headSize * 0.34f * jaw, headSize * 0.26f * jaw, headSize * 0.55f * jaw),
				Feature.JAW, false));

		// Head Crest / Hair Tufts
		if (g.expresses(Gene.FUR_CREST, 0.45f)) {
			float hairSize = headSize * g.range(Gene.FUR_CREST, 0.25f, 0.65f);
			Vector3f crestPos = new Vector3f(headStart).fma(headSize * 0.4f, trueUp);
			blobs.add(new SdfBlob(headBone, crestPos,
					new Vector3f(hairSize * 0.35f, hairSize * 0.85f, hairSize * 0.6f), Feature.HAIR, false));
		}

		// Eyes: Standard, Eye Stalks, Compound Eyes, or Multi-pair
		float eyeStyle = g.raw(Gene.EYE_STYLE);
		float eyeRoll = g.raw(Gene.EYE_COUNT);
		int eyePairs = (eyeStyle > 0.82f || eyeRoll > 0.85f) ? 2 : 1;
		float eyeRadius = headSize * g.range(Gene.EYE_SIZE, 0.09f, 0.24f);
		float spacing = g.range(Gene.EYE_SPACING, 0.45f, 0.95f);

		for (int i = 0; i < eyePairs; i++) {
			float along = 0.30f + i * 0.16f;
			Vector3f base = new Vector3f(headStart).lerp(headEnd, along).fma(headSize * 0.18f, trueUp);
			for (int s = -1; s <= 1; s += 2) {
				Vector3f pos = new Vector3f(base).fma(s * headSize * 0.42f * spacing, right);
				
				if (eyeStyle >= 0.30f && eyeStyle < 0.60f) {
					// Eye Stalks: Extruded stalk from head to eye bulb
					Vector3f stalkMid = new Vector3f(pos).fma(headSize * 0.20f, trueUp);
					Vector3f eyeBulb = new Vector3f(pos).fma(headSize * 0.40f, trueUp);
					blobs.add(new SdfBlob(headBone, stalkMid,
							new Vector3f(eyeRadius * 0.45f, headSize * 0.22f, eyeRadius * 0.45f), Feature.EYE_STALK, false));
					blobs.add(new SdfBlob(headBone, eyeBulb, new Vector3f(eyeRadius * 1.1f), Feature.EYE, false));
				} else if (eyeStyle >= 0.60f && eyeStyle < 0.85f) {
					// Compound Eyes: Large faceted dome eyes
					Vector3f compoundRadii = new Vector3f(eyeRadius * 1.6f, eyeRadius * 1.4f, eyeRadius * 2.1f);
					blobs.add(new SdfBlob(headBone, pos, compoundRadii, Feature.EYE, false));
				} else {
					// Standard Eyes
					blobs.add(new SdfBlob(headBone, pos, new Vector3f(eyeRadius), Feature.EYE, false));
				}
			}
		}
	}

	private static void expand(Vector3f min, Vector3f max, Vector3f p, float r) {
		min.set(Math.min(min.x, p.x - r), Math.min(min.y, p.y - r), Math.min(min.z, p.z - r));
		max.set(Math.max(max.x, p.x + r), Math.max(max.y, p.y + r), Math.max(max.z, p.z + r));
	}
}
