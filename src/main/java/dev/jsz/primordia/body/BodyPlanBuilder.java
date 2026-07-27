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

	/**
	 * How far the jaw is tilted open in the bind pose, as a slope away from the head direction.
	 * <p>
	 * Not cosmetic: the mesh is polygonised once in bind pose, so this gap is the only thing that
	 * puts two separable surfaces where the mouth is. Baked shut, the mandible and the skull come
	 * out of the mesher as one welded lump and no amount of animation can part them.
	 */
	private static final float JAW_BIND_OPEN = 0.78f;

	/** Spine parameter the front-most pair of legs attaches at. */
	private static final float FOREMOST_LEG_U = 0.88f;
	/** Spine parameter the rear-most pair reaches at maximum clustering. */
	private static final float HINDMOST_LEG_U = 0.62f;
	/**
	 * Required hip-to-hip spacing between adjacent leg pairs, in multiples of the leg radius.
	 * Two capsules of radius r are exactly touching at 2r, so this is a 15% clearance margin.
	 */
	private static final float LEG_PITCH = 2.3f;
	/** Most of the torso length the hips may be spread over, before legs are thinned instead. */
	private static final float MAX_LEG_SPAN = 0.45f;

	private BodyPlanBuilder() {
	}

	public static BodyPlan build(Genome g) {
		List<BoneDef> bones = new ArrayList<>();
		List<SdfBlob> blobs = new ArrayList<>();
		List<LimbChain> legs = new ArrayList<>();
		List<LimbChain> arms = new ArrayList<>();
		List<ToothDef> teeth = new ArrayList<>();
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

		// ---- fit the legs into the room the body actually has -------------------
		// Legs have to be spaced further apart along the body than they are thick. When they are
		// not, their capsules genuinely intersect, and the union of two overlapping solids is one
		// solid — neither the per-limb blend groups nor any amount of sampling resolution can pull
		// them back apart afterwards. That is what welded the legs of clustered, many-legged
		// creatures into a continuous sheet: an arachnid was carrying 55 mm-radius legs on hips
		// 33 mm apart, so every same-side pair interpenetrated by construction.
		//
		// Two levers, applied in that order. Spreading the hips back down the body costs only the
		// clustered look, so it goes first but is capped — spreading them the full length of the
		// torso would turn every spider into a centipede. Whatever crowding is left is then paid
		// for by thinning the legs, which is what a real arachnid does: the reason a spider can
		// carry eight legs on a body that short is that they are wire-thin.
		int legPairs = g.discrete(Gene.LEG_PAIRS, 1, 4);
		// Clustering pulls the rearmost pair forward. Without it, every many-legged creature runs
		// its legs the whole length of the body — a centipede — and packing them onto the front
		// segment, which is what a spider does, is simply not in the space.
		float rearmostU = MathX.lerp(0.12f, HINDMOST_LEG_U, g.raw(Gene.LEG_CLUSTERING));
		if (legPairs > 1) {
			float gaps = legPairs - 1;
			float needed = LEG_PITCH * legThickness * gaps;
			if ((FOREMOST_LEG_U - rearmostU) * torsoLength < needed) {
				float span = Math.min(needed, MAX_LEG_SPAN * torsoLength);
				rearmostU = FOREMOST_LEG_U - span / torsoLength;
				// Floored against the hip height rather than against zero: past roughly this
				// ratio a leg stops reading as a limb and reads as a wire, and ThinLimbTest
				// holds the other end of the same trade.
				legThickness = Math.max(span / (LEG_PITCH * gaps), hipHeight * 0.05f);
			}
		}
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
		// Slimmer than the whole skull: this capsule is the braincase and upper jaw only, and it
		// has to leave room under itself for the mandible to sit clear of it. At the old 0.55 the
		// jaw was swallowed whole inside the head's own surface, so there was no mouth to open
		// and rotating the bone just dragged the face with it.
		bones.add(new BoneDef("head", parent, new Vector3f(cursor), headTail,
				headSize * 0.42f, headSize * 0.22f, Feature.HEAD, true));

		addHeadDetail(g, blobs, headBone, cursor, headTail, headDir, headSize);

		// ---- jaw --------------------------------------------------------------
		// A hinged bone of its own rather than a lump of mass welded under the skull, for two
		// reasons that both come from how this pipeline works.
		//
		// The mesh is polygonised once in bind pose and only ever skinned afterwards, so a mouth
		// that is fused shut when it is baked can never open: there is no seam to open along, and
		// rotating the jaw would stretch one continuous surface rather than part it. The jaw is
		// therefore baked slightly ajar, which is what puts a real gap in the geometry.
		//
		// And it takes a blend group of its own, so the SDF unions mandible and cranium with a
		// hard minimum instead of a smooth one. Smooth-unioned they fair into a single mass and
		// the mouth line disappears; kept apart, the seam *is* the mouth.
		Vector3f headRight = new Vector3f(0f, 1f, 0f).cross(headDir, new Vector3f());
		if (headRight.lengthSquared() < 1e-6f) headRight.set(1f, 0f, 0f);
		headRight.normalize();
		Vector3f headUp = new Vector3f(headDir).cross(headRight).normalize().mul(-1f);

		float jawMass = g.range(Gene.JAW_SIZE, 0.35f, 1.15f);
		// Broad crushing jaws against narrow snatching ones. Without this every mouth is the same
		// tube and the whole face reads as one blob stacked on another whatever else varies.
		float jawWidth = g.range(Gene.JAW_WIDTH, 0.62f, 1.95f);
		// Widening spends the same flesh over more span, so the mandible flattens as it broadens
		// rather than simply inflating.
		float jawDepth = 1f / (float) Math.sqrt(jawWidth);
		// Hinged back near the ear, not at the snout: a jaw pivoting from the front of the face
		// opens like a drawbridge instead of a mouth.
		Vector3f jawHinge = new Vector3f(cursor).lerp(headTail, 0.18f)
				.fma(-headSize * 0.26f, headUp);
		Vector3f jawDir = new Vector3f(headDir).fma(-JAW_BIND_OPEN, headUp).normalize();
		float jawLength = headLength * 0.80f;
		Vector3f jawTail = new Vector3f(jawHinge).fma(jawLength, jawDir);

		int jawBone = bones.size();
		int jawGroup = nextBlendGroup++;
		bones.add(new BoneDef("jaw", headBone, jawHinge, jawTail,
				Math.max(headSize * 0.20f * jawMass, blendRadius * 1.3f),
				Math.max(headSize * 0.13f * jawMass, blendRadius * 1.15f),
				Feature.JAW, true, jawGroup));
		// The mandible's own bulk, carried on the jaw bone so it swings with it.
		blobs.add(new SdfBlob(jawBone, new Vector3f(jawHinge).lerp(jawTail, 0.52f),
				new Vector3f(headSize * 0.26f * jawMass * jawWidth,
						headSize * 0.15f * jawMass * jawDepth,
						jawLength * 0.44f),
				Feature.JAW, false));

		addTeeth(g, teeth, headBone, jawBone, cursor, headTail, headRight, headUp,
				jawHinge, jawTail, headSize, jawWidth);

		// ---- abdomen ----------------------------------------------------------
		// A high BODY_SEGMENTATION splits the trunk into a cephalothorax and a separate abdomen
		// joined by a narrow waist. This is the structural difference between "an animal that
		// happens to have eight legs" and a spider: the mass sits *behind* the legs rather than
		// being strung out between them, and the legs all hang off the front segment.
		boolean segmented = g.expresses(Gene.BODY_SEGMENTATION, 0.62f);
		int abdomenBone = -1;
		Vector3f abdomenRear = null;
		if (segmented) {
			float abdomenR = girth * g.range(Gene.ABDOMEN_SIZE, 1.05f, 2.30f);
			// The abdomen hangs behind and below the hip, so its vertical radius is capped against
			// the hip height — an abdomen that ploughs through the floor reads as broken, not heavy.
			float abdomenRy = Math.min(abdomenR * 0.80f, spinePts[0].y * 0.92f);
			// The waist is the one place on a segmented body where the blend radius could swallow
			// the geometry outright, so it is floored against it rather than against the girth alone.
			float waist = Math.max(girth * 0.26f, blendRadius * 1.35f);

			Vector3f abdomenStart = new Vector3f(spinePts[0]);
			Vector3f abdomenCenter = new Vector3f(abdomenStart)
					.add(0f, 0f, -(abdomenR * 0.95f + torsoLength * 0.08f));
			abdomenBone = bones.size();
			bones.add(new BoneDef("abdomen", rootBone, abdomenStart, abdomenCenter,
					waist, Math.max(abdomenR * 0.45f, waist), Feature.BODY, true));
			blobs.add(new SdfBlob(abdomenBone, new Vector3f(abdomenCenter),
					new Vector3f(abdomenR * 0.88f, abdomenRy, abdomenR * 1.05f), Feature.ABDOMEN, false));
			abdomenRear = new Vector3f(abdomenCenter).add(0f, 0f, -abdomenR * 0.80f);
		}

		// ---- tail -------------------------------------------------------------
		float tailLength = size * g.biased(Gene.TAIL_LENGTH, 0f, 2.0f, 1.3f);
		TailShape tailShape = TailShape.of(g);
		if (tailLength > 0.08f * size) {
			int tailSegments = g.discrete(Gene.TAIL_SEGMENTS, 1, 6);
			// Floored like the limbs. A tail is long and finely tapered, so it is the geometry most
			// prone to thinning into an invisible thread — and unlike a leg, nothing about the
			// creature standing up forces it to stay substantial.
			// Two floors, and both are needed. The absolute one keeps a tail from being a thread on
			// its own terms; the blend-relative one matters because BLEND_SMOOTHNESS ranges wider
			// than the tail thickness gene does, so a heavily-blended creature could otherwise have
			// its entire tail — base included, not just the tip — absorbed into the smooth union.
			// Flat and finned tails thin the capsule first: a wide blob unioned with a
			// full-thickness round capsule gives a round tail with side flanges, not a flat one.
			float tailRadius = Math.max(
					Math.max(girth * g.range(Gene.TAIL_THICKNESS, 0.18f, 0.60f) * tailShape.capsuleScale(),
							size * 0.045f),
					blendRadius * 1.15f);
			float tailTip = Math.max(tailRadius * TAIL_TIP_TAPER, blendRadius * 1.05f);
			float tailSeg = tailLength / tailSegments;
			// On a segmented body the tail grows off the back of the abdomen rather than the
			// pelvis, which is what turns a spider into a scorpion.
			int tailParent = segmented ? abdomenBone : rootBone;
			Vector3f tCursor = new Vector3f(segmented ? abdomenRear : spinePts[0]);
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
				addTailShape(g, blobs, tailShape, tailParent, tCursor, next, (r0 + r1) * 0.5f,
						i == tailSegments - 1);
				tCursor = next;
			}
		}

		// ---- legs -------------------------------------------------------------
		// legPairs and rearmostU are settled earlier, alongside leg thickness: the three are
		// mutually constrained and have to be reconciled before the blend radius is derived.
		int legSegments = g.discrete(Gene.LEG_SEGMENTS, 2, 3);
		float splay = g.range(Gene.LEG_SPLAY, 0.05f, 0.70f);
		float footSize = size * g.range(Gene.FOOT_SIZE, 0.04f, 0.17f);
		float digitigrade = g.raw(Gene.DIGITIGRADE);
		float pairPhase = g.range(Gene.GAIT_OFFSET, 0f, 0.5f);
		// Biased hard toward zero. A mid joint riding above the hip is the arachnid tell, and a
		// trait that the median creature has is not a tell — it is just the default silhouette.
		float legArch = g.biased(Gene.LEG_ARCH, 0f, 1f, 2.6f);

		// Feet fan forward and back along the body instead of every leg reaching straight out to
		// the side. Hip spacing alone only separates the limbs where they meet the body; parallel
		// legs stay exactly as close for their whole length, so the fan is what keeps them apart
		// out at the ankle where they are longest and most visible. It is also just what a
		// many-legged animal does — the front pair reaches ahead, the rear pair trails behind.
		float fanReach = legPairs == 1 ? 0f
				: Math.max(hipHeight * 0.35f, LEG_PITCH * 0.55f * legThickness * (legPairs - 1));

		for (int pair = 0; pair < legPairs; pair++) {
			// u = 1 at the shoulder, decreasing toward the pelvis.
			float u = legPairs == 1 ? 0.4f
					: MathX.lerp(FOREMOST_LEG_U, rearmostU, (float) pair / (legPairs - 1));
			// +1 for the front-most pair through to -1 for the rear-most.
			float fanBias = legPairs == 1 ? 0f : 1f - 2f * ((float) pair / (legPairs - 1));
			int attach = MathX.clamp((int) (u * spineSegments), 0, spineSegments - 1);
			Vector3f spineAt = sampleSpine(spinePts, u);
			float rAt = torsoRadius(girth, taper, u);

			for (int s = -1; s <= 1; s += 2) {
				// Arched limbs stand wider. A leg that tents up over the body has to put its foot
				// further out to reach the ground at all, and the sprawl is half of what makes an
				// arachnid read as one — the same span on straight legs is just a tall animal.
				float stance = splay * (1f + 0.85f * legArch);
				Vector3f hip = new Vector3f(s * rAt * 0.85f, spineAt.y - rAt * 0.35f, spineAt.z);
				Vector3f foot = new Vector3f(hip.x + s * (rAt * 0.25f + hipHeight * stance), 0f,
						hip.z + fanBias * fanReach);

				// Front limbs bend backward at the elbow, hind limbs forward at the knee. The
				// arch component lifts the bend out of the horizontal plane, so the knee rises
				// above the hip and the leg reaches down to the foot from a high corner.
				//
				// That opposed convention is right for a quadruped and actively wrong for an
				// arachnid: it bows the middle pairs of a many-legged creature *toward* each
				// other, and at the bend scale a high arch asks for, the two bulges meet in mid
				// air. Arched limbs therefore bow the way their foot already fans — knee forward
				// on the legs reaching forward — so the whole set radiates instead of converging.
				// Past two pairs the opposed convention has nothing to describe anyway: "elbow
				// versus knee" is a fact about quadrupeds, and a creature with six or eight legs
				// has no forelimbs and hindlimbs, just legs. Those always radiate.
				boolean front = pair < legPairs / 2f;
				float radial = Math.max(MathX.clamp01(legArch), legPairs > 2 ? 0.75f : 0f);
				float poleZ = MathX.lerp(front ? -1f : 1f, fanBias, radial);
				Vector3f pole = new Vector3f(s * (0.2f + 0.7f * legArch), legArch * 1.5f, poleZ);
				// A pole that has collapsed to nothing gives the solver no bend hint at all.
				if (pole.lengthSquared() < 1e-6f) pole.set(s * 0.2f, 0f, front ? -1f : 1f);
				pole.normalize();

				float phase = (pair * pairPhase + (s > 0 ? 0.5f : 0f)) % 1f;
				// Every limb gets its own blend group, left and right included — a creature's own
				// legs should never fuse to each other however closely they are packed.
				int group = nextBlendGroup++;
				LimbChain chain = buildLimb(bones, "leg" + pair + (s > 0 ? "R" : "L"), attach, hip, foot, pole,
						legSegments, legThickness,
						legSegments >= 3 ? digitigrade : 0f, s, pair, phase, true, group,
						1f + 2.4f * legArch);
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
							2, armThickness, 0f, s, pair, 0f, false, nextBlendGroup++, 1f));
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
			// A continuous armoured back rather than a row of discs. Three things make the
			// difference: one blob per spine segment instead of every other one, each long enough
			// along Z to overlap its neighbours, and centres sunk most of the way into the body so
			// what surfaces is a broad thickening of the hide. Placed proud of the back and spaced
			// out, the same blobs read as dinner plates glued to the spine.
			float segmentLength = torsoLength / spineSegments;
			for (int i = 0; i < spineSegments; i++) {
				float u = (i + 0.5f) / spineSegments;
				Vector3f at = sampleSpine(spinePts, u);
				float r = torsoRadius(girth, taper, u);
				blobs.add(new SdfBlob(Math.min(i, spineSegments - 1),
						new Vector3f(0f, at.y + r * 0.42f, at.z),
						new Vector3f(r * 0.95f, r * 0.58f, segmentLength * 0.80f),
						Feature.PLATE, false));
			}
		}

		// ---- light organs -----------------------------------------------------
		// Discrete photophores down the flanks, for the strongly bioluminescent only. Weaker
		// glow genotypes light existing geometry instead (see BodyPalette / Pattern) rather than
		// growing organs, so the trait fades in smoothly instead of popping into existence.
		if (g.expresses(Gene.BIOLUMINESCENCE, 0.78f)) {
			// Set into the flank rather than stuck onto it. Sunk this far the pod barely changes
			// the silhouette, but it still wins the nearest-part test that decides vertex colour,
			// so what the player sees is a glowing patch in the skin rather than a bead on a
			// string. Photophores are part of the animal; they should not read as jewellery.
			float podRadius = Math.max(girth * 0.16f, blendRadius * 1.2f);
			for (int i = 1; i < spineSegments; i++) {
				float u = (float) i / spineSegments;
				float r = torsoRadius(girth, taper, u);
				for (int s = -1; s <= 1; s += 2) {
					blobs.add(new SdfBlob(Math.min(i, spineSegments - 1),
							new Vector3f(s * r * 0.68f, spinePts[i].y - r * 0.18f, spinePts[i].z),
							new Vector3f(podRadius), Feature.GLOW, false));
				}
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
			for (int i = 0; i < teeth.size(); i++) {
				ToothDef old = teeth.get(i);
				teeth.set(i, new ToothDef(old.bone(), new Vector3f(old.root()).mul(scale),
						old.direction(), old.protrusion() * scale, old.radius() * scale,
						old.blunt()));
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
				new BodyPalette(g), blendRadius, rootBone, headBone, jawBone,
				(float) Math.atan(JAW_BIND_OPEN) * 0.88f, hipHeight, min, max,
				teeth.toArray(new ToothDef[0]),
				bodyLength, mass, minLimbRadius);
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
	                                   int blendGroup, float bendScale) {
		float reach = origin.distance(effector);
		// The default bulge is only enough to give IK somewhere to bend. An arachnid leg needs
		// far more than that — the mid joint has to clear the hip, not merely bow slightly
		// toward the pole — so arched limbs scale the control-point offset up rather than just
		// tilting the pole, which on its own moves the joint by a few percent of the reach.
		float bend = reach * (LIMB_SLACK - 1f) * 2.2f * bendScale;

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

		// Upper jaw: the muzzle above the mouth line, and part of the skull. The mandible below it
		// is a hinged bone of its own so that it can actually move — see the jaw section of build.
		float muzzle = g.range(Gene.JAW_SIZE, 0.35f, 1.15f);
		Vector3f muzzlePos = new Vector3f(headStart).lerp(headEnd, 0.66f)
				.fma(-headSize * 0.05f * muzzle, trueUp);
		float snoutWidth = g.range(Gene.JAW_WIDTH, 0.62f, 1.95f);
		blobs.add(new SdfBlob(headBone, muzzlePos,
				new Vector3f(headSize * 0.30f * muzzle * snoutWidth,
						headSize * 0.21f * muzzle / (float) Math.sqrt(snoutWidth),
						headSize * 0.52f * muzzle),
				Feature.HEAD, false));

		// Head Crest / Hair Tufts
		if (g.expresses(Gene.FUR_CREST, 0.45f)) {
			float hairSize = headSize * g.range(Gene.FUR_CREST, 0.25f, 0.65f);
			Vector3f crestPos = new Vector3f(headStart).fma(headSize * 0.4f, trueUp);
			blobs.add(new SdfBlob(headBone, crestPos,
					new Vector3f(hairSize * 0.35f, hairSize * 0.85f, hairSize * 0.6f), Feature.HAIR, false));
		}

		addSnoutDetail(g, blobs, headBone, headStart, headEnd, headDir, right, trueUp, headSize);
		addFrill(g, blobs, headBone, headStart, headDir, trueUp, headSize);
		addEars(g, blobs, headBone, headStart, headEnd, right, trueUp, headSize);
		addHorns(g, blobs, headBone, headStart, headEnd, headDir, right, trueUp, headSize);
		addEyes(g, blobs, headBone, headStart, headEnd, right, trueUp, headSize);
	}

	/**
	 * Adds the blobs that give a tail its cross-section, called once per tail segment.
	 * {@code last} marks the tip segment, which is where club and fan shapes put their mass.
	 */
	private static void addTailShape(Genome g, List<SdfBlob> blobs, TailShape shape, int bone,
	                                 Vector3f head, Vector3f tail, float radius, boolean last) {
		if (shape == TailShape.ROUND) return;

		Vector3f mid = new Vector3f(head).lerp(tail, 0.5f);
		float halfLength = Math.max(head.distance(tail) * 0.5f, radius);
		float depth = g.range(Gene.TAIL_FIN_DEPTH, 1.35f, 3.10f);

		switch (shape) {
			case FLAT -> blobs.add(new SdfBlob(bone, mid,
					new Vector3f(radius * depth, radius * 0.55f, halfLength), Feature.FIN, false));
			case FIN -> blobs.add(new SdfBlob(bone,
					new Vector3f(mid).add(0f, radius * depth * 0.30f, 0f),
					new Vector3f(radius * 0.50f, radius * depth, halfLength), Feature.FIN, false));
			case CLUB -> {
				if (last) {
					blobs.add(new SdfBlob(bone, new Vector3f(tail),
							new Vector3f(radius * 2.10f, radius * 1.85f, radius * 2.10f),
							Feature.PLATE, false));
				}
			}
			case FAN -> {
				if (last) {
					blobs.add(new SdfBlob(bone, new Vector3f(mid).lerp(tail, 0.65f),
							new Vector3f(radius * depth * 1.15f, radius * 0.50f, halfLength * 1.40f),
							Feature.FIN, false));
				}
			}
			default -> {
			}
		}
	}

	/**
	 * Teeth along both jaw lines: the upper row pinned to the skull, the lower to the mandible, so
	 * they part when the mouth opens.
	 * <p>
	 * These are {@link ToothDef}s, not blobs in the signed distance field, and that is the whole
	 * point. Anything in the field goes through the smooth union that fairs limbs into hips, which
	 * rounds a tooth off and melts it into the lip — a mouth of them came out as white lumps. They
	 * are also finer than one sampling cell, so most did not survive the mesher at all.
	 * <p>
	 * Each is rooted <i>inside</i> the gum and protrudes from it. The buried part is simply hidden
	 * by the flesh drawn over it, which costs nothing and means no seam can show at the gum line
	 * however the body around it happens to be meshed.
	 * <p>
	 * Diet drives the shape, and it is the one cue that says what an animal eats before it does
	 * anything: a carnivore gets a few long points, a herbivore a dense row of short blunt
	 * grinders, and an omnivore the mixed dentition that actually distinguishes one — grabbing
	 * teeth at the front and crushing ones behind.
	 */
	private static void addTeeth(Genome g, List<ToothDef> teeth, int headBone, int jawBone,
	                             Vector3f headStart, Vector3f headEnd, Vector3f right, Vector3f up,
	                             Vector3f jawHinge, Vector3f jawTail, float headSize,
	                             float jawWidth) {
		float diet = g.raw(Gene.DIET);
		boolean carnivore = diet > 0.65f;
		boolean herbivore = diet < 0.35f;

		int count = herbivore ? 5 : (carnivore ? 3 : 4);
		// The row is splayed by tilting each tooth outward, not by moving its root out to the
		// cheek. A root offset sideways sits outside a narrow mandible entirely and the tooth
		// floats free of the face; on the bone's own axis it is buried whatever the jaw's girth,
		// and the mesher walks outward from there to wherever the flesh actually ends.
		float splay = 0.42f * Math.min(jawWidth, 1.6f);
		// How far the point stands clear of the gum. The mesher measures this outward from the
		// flesh itself, not from the bone axis: the skull and the mandible carry different depths
		// of it, and an offset from the axis left one whole row buried inside the lip.
		float reach = headSize * (herbivore ? 0.10f : (carnivore ? 0.28f : 0.17f));
		float radius = headSize * (herbivore ? 0.045f : (carnivore ? 0.042f : 0.038f));

		for (int i = 0; i < count; i++) {
			float along = MathX.lerp(0.40f, 0.90f, (i + 0.5f) / count);
			// An omnivore's front teeth grab and its back teeth crush; that gradient is the tell.
			float front = 1f - (i / (float) Math.max(1, count - 1));
			float grow = herbivore ? 1f : MathX.lerp(0.7f, 1.25f, front);
			boolean blunt = herbivore || (!carnivore && front < 0.5f);

			for (int s = -1; s <= 1; s += 2) {
				// Upper row: rooted on the skull's axis, growing down and out into the mouth.
				Vector3f upperRoot = new Vector3f(headStart).lerp(headEnd, along);
				Vector3f downOut = new Vector3f(up).negate().fma(s * splay, right).normalize();
				teeth.add(new ToothDef(headBone, upperRoot, downOut, reach * grow, radius, blunt));

				// Lower row: rooted on the mandible's axis, growing up and out to meet it.
				Vector3f lowerRoot = new Vector3f(jawHinge).lerp(jawTail, along);
				Vector3f upOut = new Vector3f(up).fma(s * splay, right).normalize();
				teeth.add(new ToothDef(jawBone, lowerRoot, upOut, reach * grow, radius, blunt));
			}
		}
	}

	/** Beak sheath and tusks — the two ways a jaw can advertise what it eats. */
	private static void addSnoutDetail(Genome g, List<SdfBlob> blobs, int headBone,
	                                   Vector3f headStart, Vector3f headEnd, Vector3f headDir,
	                                   Vector3f right, Vector3f trueUp, float headSize) {
		if (g.expresses(Gene.SNOUT_TYPE, 0.68f)) {
			float beak = headSize * g.range(Gene.SNOUT_TYPE, 0.50f, 1.05f);
			// Two beads: the base takes the width of the jaw, the tip pinches down to a point.
			blobs.add(new SdfBlob(headBone, new Vector3f(headEnd).fma(beak * 0.18f, headDir),
					new Vector3f(headSize * 0.20f, headSize * 0.22f, beak * 0.34f), Feature.BEAK, false));
			blobs.add(new SdfBlob(headBone,
					new Vector3f(headEnd).fma(beak * 0.52f, headDir).fma(-headSize * 0.06f, trueUp),
					new Vector3f(headSize * 0.10f, headSize * 0.11f, beak * 0.26f), Feature.BEAK, false));
		}

		if (g.expresses(Gene.TUSKS, 0.62f)) {
			float tuskLength = headSize * g.range(Gene.TUSKS, 0.40f, 1.05f);
			float tuskRadius = headSize * 0.105f;
			for (int s = -1; s <= 1; s += 2) {
				Vector3f base = new Vector3f(headStart).lerp(headEnd, 0.74f)
						.fma(s * headSize * 0.24f, right)
						.fma(-headSize * 0.20f, trueUp);
				// Three beads curving forward then up, so a tusk sweeps rather than spikes.
				for (int i = 0; i < 3; i++) {
					float t = (i + 1) / 3f;
					Vector3f p = new Vector3f(base)
							.fma(tuskLength * t, headDir)
							.fma(tuskLength * 0.50f * t * t, trueUp);
					blobs.add(new SdfBlob(headBone, p,
							new Vector3f(tuskRadius * (1f - 0.22f * i)), Feature.TUSK, false));
				}
			}
		}
	}

	/** A neck frill: a thin disc standing up behind the skull. */
	private static void addFrill(Genome g, List<SdfBlob> blobs, int headBone, Vector3f headStart,
	                             Vector3f headDir, Vector3f trueUp, float headSize) {
		if (!g.expresses(Gene.FRILL, 0.62f)) return;
		float frill = headSize * g.range(Gene.FRILL, 1.15f, 2.50f);
		Vector3f pos = new Vector3f(headStart)
				.fma(-headSize * 0.12f, headDir)
				.fma(headSize * 0.30f, trueUp);
		blobs.add(new SdfBlob(headBone, pos,
				new Vector3f(frill * 0.85f, frill * 0.78f, headSize * 0.15f), Feature.FRILL, false));
	}

	private static void addEars(Genome g, List<SdfBlob> blobs, int headBone, Vector3f headStart,
	                            Vector3f headEnd, Vector3f right, Vector3f trueUp, float headSize) {
		EarType type = EarType.of(g);
		if (type == EarType.NONE) return;
		float ear = headSize * g.range(Gene.EAR_SIZE, 0.35f, 1.20f);

		for (int s = -1; s <= 1; s += 2) {
			Vector3f anchor = new Vector3f(headStart).lerp(headEnd, 0.18f)
					.fma(s * headSize * 0.42f, right)
					.fma(headSize * 0.26f, trueUp);
			switch (type) {
				case ROUND -> blobs.add(new SdfBlob(headBone, anchor,
						new Vector3f(ear * 0.16f, ear * 0.52f, ear * 0.48f), Feature.EAR, false));
				case UPRIGHT -> {
					// Two beads so the ear tapers to a point instead of reading as a paddle.
					for (int i = 0; i < 2; i++) {
						Vector3f p = new Vector3f(anchor)
								.fma(ear * (0.45f + 0.60f * i), trueUp)
								.fma(s * ear * 0.10f * i, right);
						blobs.add(new SdfBlob(headBone, p,
								new Vector3f(ear * 0.14f, ear * (0.45f - 0.14f * i), ear * (0.26f - 0.08f * i)),
								Feature.EAR, false));
					}
				}
				case DROOPING -> {
					for (int i = 0; i < 2; i++) {
						Vector3f p = new Vector3f(anchor)
								.fma(-ear * (0.35f + 0.55f * i), trueUp)
								.fma(s * ear * 0.14f, right);
						blobs.add(new SdfBlob(headBone, p,
								new Vector3f(ear * 0.13f, ear * 0.42f, ear * 0.30f), Feature.EAR, false));
					}
				}
				case FANNED -> blobs.add(new SdfBlob(headBone,
						new Vector3f(anchor).fma(s * ear * 0.38f, right),
						new Vector3f(ear * 0.70f, ear * 0.52f, ear * 0.12f), Feature.EAR, false));
				default -> {
				}
			}
		}
	}

	/**
	 * Horns, antlers and casques. All of them are chains of tapering beads attached to the head
	 * bone, so they follow head tracking for free and cost nothing in the skeleton.
	 * <p>
	 * {@link HornType#NONE} takes the bottom 40% of the locus deliberately: a fauna in which every
	 * animal is horned reads as noise, and the structures only mean something if they are rare.
	 */
	private static void addHorns(Genome g, List<SdfBlob> blobs, int headBone, Vector3f headStart,
	                             Vector3f headEnd, Vector3f headDir, Vector3f right, Vector3f trueUp,
	                             float headSize) {
		HornType type = HornType.of(g);
		if (type == HornType.NONE) return;
		float length = headSize * g.range(Gene.HORN_SIZE, 0.40f, 1.75f);
		float baseRadius = headSize * 0.16f;

		if (type == HornType.NASAL) {
			growHorn(blobs, headBone,
					new Vector3f(headStart).lerp(headEnd, 0.80f).fma(headSize * 0.18f, trueUp),
					new Vector3f(trueUp).fma(0.45f, headDir).normalize(), null,
					length, baseRadius * 1.30f, 5);
			return;
		}
		if (type == HornType.CREST) {
			// A blade on the midline: tall, and thin across the skull.
			int beads = 5;
			for (int i = 0; i < beads; i++) {
				float t = i / (float) (beads - 1);
				float rise = headSize * 0.34f
						+ length * 0.50f * (float) Math.sin(Math.PI * (0.22f + 0.62f * t));
				Vector3f p = new Vector3f(headStart).lerp(headEnd, 0.14f + 0.46f * t).fma(rise, trueUp);
				blobs.add(new SdfBlob(headBone, p,
						new Vector3f(headSize * 0.09f, length * 0.40f, headSize * 0.28f),
						Feature.HORN, false));
			}
			return;
		}

		// Paired types. A second, smaller pair behind the first is uncommon but striking.
		int pairs = g.expresses(Gene.HORN_PAIRS, 0.74f) ? 2 : 1;
		for (int pair = 0; pair < pairs; pair++) {
			float along = type == HornType.BROW ? 0.42f - pair * 0.15f : 0.16f + pair * 0.19f;
			float lateral = type == HornType.BROW ? 0.40f : 0.36f;
			float scale = 1f - pair * 0.30f;

			for (int s = -1; s <= 1; s += 2) {
				Vector3f base = new Vector3f(headStart).lerp(headEnd, along)
						.fma(s * headSize * lateral, right)
						.fma(headSize * 0.24f, trueUp);
				Vector3f dir = switch (type) {
					case BROW -> new Vector3f(trueUp).mul(0.85f).fma(0.55f, headDir)
							.fma(s * 0.25f, right).normalize();
					case CURVED -> new Vector3f(trueUp).mul(0.75f).fma(-0.55f, headDir)
							.fma(s * 0.45f, right).normalize();
					default -> new Vector3f(trueUp).mul(0.90f).fma(-0.40f, headDir)
							.fma(s * 0.35f, right).normalize();
				};
				// The curl is what separates a ram's horn from a straight spike: the chain is
				// pulled increasingly off its own axis as it grows.
				Vector3f curl = type == HornType.CURVED
						? new Vector3f(headDir).mul(-0.90f).fma(-0.50f, trueUp).normalize()
						: null;

				growHorn(blobs, headBone, base, dir, curl, length * scale, baseRadius * scale, 5);

				if (type == HornType.ANTLER) {
					Vector3f tineDir = new Vector3f(dir).fma(s * 0.90f, right)
							.fma(0.50f, headDir).normalize();
					Vector3f tineBase = new Vector3f(base).fma(length * 0.45f * scale, dir);
					growHorn(blobs, headBone, tineBase, tineDir, null,
							length * 0.50f * scale, baseRadius * 0.60f * scale, 3);
				}
			}
		}
	}

	/** Lays a tapering chain of beads from {@code base} along {@code dir}, optionally curling. */
	private static void growHorn(List<SdfBlob> blobs, int bone, Vector3f base, Vector3f dir,
	                             Vector3f curl, float length, float baseRadius, int beads) {
		for (int i = 0; i < beads; i++) {
			float t = (i + 0.5f) / beads;
			Vector3f p = new Vector3f(base).fma(length * t, dir);
			if (curl != null) p.fma(length * 0.55f * t * t, curl);
			// Floored so the tip stays wide enough for the mesher to resolve at all.
			float r = Math.max(baseRadius * (1f - 0.78f * t), baseRadius * 0.20f);
			blobs.add(new SdfBlob(bone, p, new Vector3f(r), Feature.HORN, false));
		}
	}

	private static void addEyes(Genome g, List<SdfBlob> blobs, int headBone, Vector3f headStart,
	                            Vector3f headEnd, Vector3f right, Vector3f trueUp, float headSize) {
		EyeStyle style = EyeStyle.of(g);
		float eyeRadius = headSize * g.range(Gene.EYE_SIZE, 0.09f, 0.24f);
		float spacing = g.range(Gene.EYE_SPACING, 0.45f, 0.95f);

		if (style == EyeStyle.CLUSTER) {
			// Eight eyes in two rows across the front of the face, the outer and lower ones
			// smaller. This one layout does more to say "arachnid" than the legs do.
			for (int row = 0; row < 2; row++) {
				for (int col = 0; col < 2; col++) {
					float along = 0.30f + row * 0.15f;
					float lateral = (0.16f + col * 0.30f) * spacing;
					float scale = (1f - row * 0.22f) * (1f - col * 0.30f);
					Vector3f base = new Vector3f(headStart).lerp(headEnd, along)
							.fma(headSize * (0.24f - row * 0.10f), trueUp);
					for (int s = -1; s <= 1; s += 2) {
						blobs.add(new SdfBlob(headBone,
								new Vector3f(base).fma(s * headSize * lateral, right),
								new Vector3f(eyeRadius * 1.15f * scale), Feature.EYE, false));
					}
				}
			}
			return;
		}

		// Every other style can still run to a second pair, which is how a six-eyed creature
		// that is not an arachnid happens.
		int eyePairs = g.expresses(Gene.EYE_COUNT, 0.85f) ? 2 : 1;
		for (int i = 0; i < eyePairs; i++) {
			float along = 0.30f + i * 0.16f;
			Vector3f base = new Vector3f(headStart).lerp(headEnd, along).fma(headSize * 0.18f, trueUp);
			for (int s = -1; s <= 1; s += 2) {
				Vector3f pos = new Vector3f(base).fma(s * headSize * 0.42f * spacing, right);
				switch (style) {
					case STALKED -> {
						Vector3f stalkMid = new Vector3f(pos).fma(headSize * 0.20f, trueUp);
						Vector3f bulb = new Vector3f(pos).fma(headSize * 0.40f, trueUp);
						blobs.add(new SdfBlob(headBone, stalkMid,
								new Vector3f(eyeRadius * 0.45f, headSize * 0.22f, eyeRadius * 0.45f),
								Feature.EYE_STALK, false));
						blobs.add(new SdfBlob(headBone, bulb,
								new Vector3f(eyeRadius * 1.10f), Feature.EYE, false));
					}
					case COMPOUND -> blobs.add(new SdfBlob(headBone, pos,
							new Vector3f(eyeRadius * 1.60f, eyeRadius * 1.40f, eyeRadius * 2.10f),
							Feature.EYE, false));
					case WIDE -> blobs.add(new SdfBlob(headBone, pos,
							new Vector3f(eyeRadius * 1.25f, eyeRadius * 0.72f, eyeRadius * 1.55f),
							Feature.EYE, false));
					case HOODED -> {
						blobs.add(new SdfBlob(headBone, pos, new Vector3f(eyeRadius), Feature.EYE, false));
						// The brow is the whole point: without it a hooded eye is just a small one.
						blobs.add(new SdfBlob(headBone, new Vector3f(pos).fma(eyeRadius * 1.15f, trueUp),
								new Vector3f(eyeRadius * 0.85f, eyeRadius * 0.45f, eyeRadius * 1.45f),
								Feature.PLATE, false));
					}
					default -> blobs.add(new SdfBlob(headBone, pos,
							new Vector3f(eyeRadius), Feature.EYE, false));
				}
			}
		}
	}

	private static void expand(Vector3f min, Vector3f max, Vector3f p, float r) {
		min.set(Math.min(min.x, p.x - r), Math.min(min.y, p.y - r), Math.min(min.z, p.z - r));
		max.set(Math.max(max.x, p.x + r), Math.max(max.y, p.y + r), Math.max(max.z, p.z + r));
	}
}
