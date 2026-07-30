package dev.jsz.primordia.anim;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BoneDef;
import dev.jsz.primordia.body.LimbChain;
import dev.jsz.primordia.mesh.LodTier;
import dev.jsz.primordia.skeleton.Skeleton;
import dev.jsz.primordia.util.MathX;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Drives one creature's skeleton. There are no authored animations anywhere in this mod — every
 * pose is computed from the creature's own proportions and its current motion, which is the only
 * approach that works when the body plan is not known until runtime.
 * <p>
 * Each update runs in a fixed order, because later stages depend on earlier ones:
 * <ol>
 *   <li><b>Gait</b> — advance the step cycle and resolve where each foot is in world space. Planted
 *       feet do not move, which is what eliminates foot sliding.</li>
 *   <li><b>Body</b> — derive the root transform from the feet: the body rides at hip height above
 *       the average foot, pitches and rolls to match the ground the feet are standing on, and bobs
 *       at twice the step frequency.</li>
 *   <li><b>Spine, neck, tail</b> — bend laterally into turns, look where the head is looking, let the
 *       tail lag behind. These must be posed before IK because they move the hips.</li>
 *   <li><b>Limb IK</b> — only now are the hip positions final, so FABRIK can solve each leg to its
 *       foot target.</li>
 * </ol>
 * Everything except the foot plants works in model space (creature-local, +Z forward, feet at
 * y = 0); world-space foot targets are converted in on the way through.
 */
public final class CreatureAnimator {
	/** Fraction of the gait cycle a foot spends on the ground. Above 0.5 keeps a walk stable. */
	private static final float DUTY_FACTOR = 0.62f;
	/** Below this speed the creature is treated as standing still and feet stop cycling. */
	private static final float IDLE_SPEED = 0.05f;
	/** Vertical distance a foot lifts during swing, as a fraction of hip height. */
	private static final float STEP_LIFT = 0.22f;
	/** How fast a planted foot converges on its plant position. High: settles in a few frames. */
	private static final float SETTLE_RATE_STANCE = 30f;
	/** How fast a foot settles when the creature stops walking. Softer, so stopping looks relaxed. */
	private static final float SETTLE_RATE_STOP = 12f;
	/** How much of a rider's steering angle the spine bends into. */
	private static final float RIDER_STEER_BEND = 0.85f;
	/** Ceiling on that bend, so a rider spinning on the spot cannot fold the animal in half. */
	private static final float RIDER_STEER_LIMIT = 0.75f;
	/**
	 * Tail counter-bend as a fraction of the spine's lean. Under one: the tail trails the turn as
	 * a counterweight rather than mirroring it, which is what a real animal's tail does going into
	 * a corner — swing it as hard the other way and the creature reads as hinged in the middle.
	 */
	private static final float TAIL_COUNTER_LEAN = 0.65f;


	/** Share of terrain pitch taken by rigid body rotation; the remainder bends the spine. */
	private static final float ROOT_PITCH_SHARE = 0.55f;
	/** Fraction of its full length a limb reaches before the solver starts stretching it. */
	private static final float COMFORTABLE_REACH = 0.95f;
	/**
	 * Most the solver may lengthen a limb's bones to reach a target. Only the solve is stretched —
	 * the rendered bones keep their true lengths — so nothing visibly deforms; the point is purely
	 * that the chain keeps a bend for the next frame to continue from instead of locking straight.
	 */
	private static final float MAX_LIMB_STRETCH = 1.06f;
	/** Iterations per limb per frame. Enough for the planar constraint and the solve to agree. */
	private static final int IK_ITERATIONS = 10;

	private final BodyPlan plan;
	private final Skeleton skeleton;
	private final FootState[] feet;
	private final Fabrik fabrik = new Fabrik();

	private final float strideLength;
	private final int[] spineBones;
	private final int[] neckBones;
	private final int[] tailBones;

	/** Position within the gait cycle, [0,1). */
	private float gaitPhase;
	private float lastTime = Float.NaN;
	/** Damped state, so the body settles instead of snapping. */
	private float bodyPitch, bodyRoll, bodyRise, lateralBend, tailLag;
	/** Smoothed steering intent, shared between the spine and the tail that counterweights it. */
	private float riderLean;
	/** Damped behavioural offsets, layered onto the locomotion pose by the axial passes. */
	private float activityHeadPitch, activityHeadYaw, activityTailYaw;
	/** Jaw opening in [0,1], smoothed. 0 is the bind pose, 1 is gape. */
	private float jawOpen;
	private float activityArmSwing, activityLunge, activityCrouch;

	// Scratch, reused every frame.
	private final Vector3f v0 = new Vector3f();
	private final Vector3f v1 = new Vector3f();
	private final Vector3f dir = new Vector3f();
	private final Quaternionf q0 = new Quaternionf();
	private final Quaternionf q1 = new Quaternionf();
	private final Vector3f[] jointScratch;
	private final float[] lengthScratch;

	public CreatureAnimator(BodyPlan plan) {
		this.plan = plan;
		this.skeleton = new Skeleton(plan);
		this.feet = new FootState[plan.legs.length];
		for (int i = 0; i < feet.length; i++) {
			feet[i] = new FootState();
		}
		// A natural stride scales with leg reach / hip height; long strides for large animals.
		this.strideLength = Math.max(0.25f, plan.hipHeight * 1.35f);

		this.spineBones = collect(plan, "spine");
		this.neckBones = collect(plan, "neck");
		this.tailBones = collect(plan, "tail");

		int maxSegments = 2;
		for (LimbChain leg : plan.legs) maxSegments = Math.max(maxSegments, leg.bones.length);
		for (LimbChain arm : plan.arms) maxSegments = Math.max(maxSegments, arm.bones.length);
		this.jointScratch = new Vector3f[maxSegments + 1];
		for (int i = 0; i < jointScratch.length; i++) jointScratch[i] = new Vector3f();
		this.lengthScratch = new float[maxSegments];
	}

	public Skeleton skeleton() {
		return skeleton;
	}

	public boolean getFootWorldPosition(int index, org.joml.Vector3d dest) {
		if (feet != null && index >= 0 && index < feet.length && feet[index] != null && feet[index].initialised) {
			dest.set(feet[index].currentX, feet[index].currentY, feet[index].currentZ);
			return true;
		}
		return false;
	}

	public void update(AnimationContext ctx) {
		float dt;
		if (Float.isNaN(lastTime)) {
			dt = 0f;
		} else {
			// Clamped so a lag spike or a pause cannot teleport the gait forward.
			dt = MathX.clamp(ctx.time - lastTime, 0f, 0.25f);
		}
		lastTime = ctx.time;

		updateActivity(ctx, dt);
		updateGait(ctx, dt);
		// updateBody writes rootTransform, which every bone's frame descends from, so it must
		// come first. Each of the axial passes below then resolves its own bones as it goes,
		// because aiming a bone reads its parent's already-current world matrix.
		updateBody(ctx, dt);
		updateSpine(ctx, dt);
		updateNeckAndHead(ctx);
		// After the head: the jaw hangs off it, so its parent has to be posed first.
		updateJaw(ctx, dt);
		updateTail(ctx, dt);

		skeleton.updateWorld();

		if (LodTier.usesInverseKinematics(ctx.tier)) {
			for (int i = 0; i < plan.legs.length; i++) {
				solveLeg(ctx, plan.legs[i], feet[i]);
			}
			for (LimbChain arm : plan.arms) {
				swingArm(ctx, arm);
			}
			skeleton.updateWorld();
		}

		skeleton.updateSkinMatrices();
	}

	// ----------------------------------------------------------------- behaviour

	/**
	 * Resolves the behavioural pose offsets for this frame — head dip, lunge, tail whip and so on.
	 * <p>
	 * These are computed here and consumed by the axial passes below rather than applied directly,
	 * because those passes set bone directions absolutely. Layering a behaviour on top afterwards
	 * would overwrite the walk cycle instead of blending with it.
	 * <p>
	 * Every offset is damped toward its target rather than assigned, so an attack that begins or
	 * ends mid-stride eases in and out instead of snapping the head to a new angle.
	 */
	private void updateActivity(AnimationContext ctx, float dt) {
		float targetHeadPitch = 0f, targetHeadYaw = 0f;
		float targetTailYaw = 0f, targetArmSwing = 0f;
		float targetLunge = 0f, targetCrouch = 0f;
		float p = MathX.clamp01(ctx.activityProgress);

		switch (ctx.activity) {
			case GRAZE -> {
				// Where the head goes is the look direction's job, not this layer's. It used to be
				// a flat 1.05 radians of pitch — head to the ground, unconditionally — which is
				// right for cropping grass at your feet and wrong for everything else the goal can
				// actually target. A creature browsing leaves off a branch above it still drove its
				// head down, because the constant swamped the look and then the clamp absorbed what
				// was left. The look already points at the exact block being eaten, so all that is
				// needed here is the movement of feeding on top of it.
				//
				// A little extra pitch remains so the animal commits to the mouthful rather than
				// merely facing it, and the crouch settles the body over its food.
				targetHeadPitch = 0.18f;
				targetHeadYaw = 0.10f * (float) Math.sin(ctx.time * 1.3);
				// Fast small bob is the jaw working; the slow one is the head shifting between bites.
				targetHeadPitch += 0.09f * (float) Math.sin(ctx.time * 9.0)
						+ 0.05f * (float) Math.sin(ctx.time * 2.1);
				targetCrouch = 0.10f;
			}
			case BITE -> {
				// Wind back, then snap forward past the rest pose and recover.
				float strike = strikeCurve(p);
				targetHeadPitch = 0.35f * strike;
				targetLunge = 0.30f * strike;
			}
			case CLAW -> {
				float strike = strikeCurve(p);
				targetArmSwing = strike;
				// The body counter-rotates into the swipe, which is what sells the weight of it.
				targetHeadYaw = -0.35f * strike;
				targetLunge = 0.12f * strike;
			}
			case TAIL_SLAM -> {
				float strike = strikeCurve(p);
				targetTailYaw = 1.5f * strike;
				// Shoulders swing opposite the tail: equal and opposite, as a real whip requires.
				targetHeadYaw = 0.4f * strike;
			}
			case RAM -> {
				float strike = strikeCurve(p);
				// Head drops and stays down through the charge rather than snapping back.
				targetHeadPitch = 0.7f * MathX.smoothstep(Math.min(1f, p * 3f));
				targetLunge = 0.45f * strike;
				targetCrouch = 0.12f * strike;
			}
			case SLEEP -> {
				// Head tucked round toward the flank, body settled onto its legs.
				targetHeadPitch = 0.55f;
				targetHeadYaw = 0.45f;
				targetCrouch = 0.55f;
			}
			case FEED -> {
				// Head down at the body on the ground, worrying at it.
				targetHeadPitch = 1.15f;
				targetHeadYaw = 0.22f * (float) Math.sin(ctx.time * 3.1);
				targetHeadPitch += 0.14f * (float) Math.sin(ctx.time * 7.5);
				targetCrouch = 0.18f;
			}
			case CARCASS -> {
				// Fully slack. The roll onto the side is applied by the renderer, since it is a
				// transform of the whole body rather than a pose of any bone in it.
				targetHeadPitch = 0.8f;
				targetCrouch = 1f;
			}
			default -> {
			}
		}

		// A body going slack overrides whatever else it was doing. Death is not a blend.
		if (ctx.collapse > 0f) {
			targetCrouch = Math.max(targetCrouch, ctx.collapse);
		}

		float rate = ctx.activity.isAttack() ? 22f : 7f;
		activityHeadPitch = MathX.damp(activityHeadPitch, targetHeadPitch, rate, dt);
		activityHeadYaw = MathX.damp(activityHeadYaw, targetHeadYaw, rate, dt);
		activityTailYaw = MathX.damp(activityTailYaw, targetTailYaw, rate, dt);
		activityArmSwing = MathX.damp(activityArmSwing, targetArmSwing, rate, dt);
		activityLunge = MathX.damp(activityLunge, targetLunge, rate, dt);
		activityCrouch = MathX.damp(activityCrouch, targetCrouch, rate, dt);
	}

	/**
	 * Anticipation-and-strike curve over an attack's duration: pull back through the first third,
	 * drive forward hard, then ease out. Returns roughly -0.35 to 1.
	 * <p>
	 * The wind-up is what makes a hit readable — an attack that starts at full extension gives the
	 * viewer nothing to react to.
	 */
	private static float strikeCurve(float p) {
		if (p < 0.32f) {
			// Anticipation: draw back.
			return -0.35f * MathX.smoothstep(p / 0.32f);
		}
		if (p < 0.55f) {
			// Strike: fast forward drive out of the wind-up.
			float t = (p - 0.32f) / 0.23f;
			return MathX.lerp(-0.35f, 1f, MathX.smoothstep(t));
		}
		// Recovery: settle back to neutral.
		float t = (p - 0.55f) / 0.45f;
		return 1f - MathX.smoothstep(t);
	}

	// --------------------------------------------------------------------- gait

	/**
	 * Paddling gait. Feet are driven around an ellipse beside the hip instead of being planted,
	 * because in water there is nothing to plant against — the whole world-locked-foot mechanism
	 * that makes walking work is exactly wrong here and has to be bypassed rather than adapted.
	 * <p>
	 * The stroke is asymmetric: a fast power phase pushing backward and down, then a slower
	 * recovery drawn forward and up close to the body. That asymmetry is what makes paddling read
	 * as propulsion rather than as running in mid-air.
	 */
	private void updateSwimGait(AnimationContext ctx, float dt) {
		// A quick, shallow dog paddle: small strokes close under the chest, not the big sweeping
		// ellipse a purpose-built swimmer would use. These are land animals coping with water.
		float strokeRate = 1.6f + 0.6f * Math.min(1f, ctx.speed / Math.max(0.5f, strideLength));
		gaitPhase = (gaitPhase + strokeRate * dt) % 1f;

		float reach = plan.hipHeight * 0.22f;

		for (int i = 0; i < plan.legs.length; i++) {
			LimbChain leg = plan.legs[i];
			FootState foot = feet[i];

			float phase = (gaitPhase + leg.gaitPhase) % 1f;
			float angle = phase * (float) (Math.PI * 2.0);

			// Small circle tucked up and forward of where the foot would rest on land.
			Vector3f local = v0.set(leg.restEffector);
			local.y += plan.hipHeight * 0.45f + reach * 0.6f * (float) Math.sin(angle);
			local.z += reach * (float) Math.cos(angle);
			// Limbs drawn in under the body rather than splayed out to the sides.
			local.x *= 0.65f;

			toWorld(ctx, local, v1);
			// Eased rather than assigned so entering or leaving water transitions smoothly instead
			// of the feet teleporting between gaits.
			double blend = Math.exp(-14.0 * dt);
			foot.currentX = v1.x + (foot.currentX - v1.x) * blend;
			foot.currentY = v1.y + (foot.currentY - v1.y) * blend;
			foot.currentZ = v1.z + (foot.currentZ - v1.z) * blend;
			foot.plantX = foot.currentX;
			foot.plantY = foot.currentY;
			foot.plantZ = foot.currentZ;
			foot.grounded = false;
			foot.initialised = true;
		}
	}

	private void updateGait(AnimationContext ctx, float dt) {
		if (ctx.swimming) {
			updateSwimGait(ctx, dt);
			return;
		}
		boolean moving = ctx.speed > IDLE_SPEED && !ctx.airborne;
		// Step frequency falls out of speed and stride: cadence matches how fast the body moves.
		// The minimum keeps large creatures' legs visibly moving rather than looking frozen.
		// Large creatures still step slower than small ones, but never below a visible threshold.
		float minFreq = MathX.clamp(0.25f / Math.max(0.5f, plan.hipHeight * 0.35f), 0.18f, 0.45f);
		float stepFrequency = moving ? MathX.clamp(ctx.speed / strideLength, minFreq, 3.2f) : 0f;
		gaitPhase = (gaitPhase + stepFrequency * dt) % 1f;

		for (int i = 0; i < plan.legs.length; i++) {
			LimbChain leg = plan.legs[i];
			FootState foot = feet[i];

			restFootWorld(ctx, leg, v0);
			if (!foot.initialised) {
				foot.snapTo(v0.x, groundAt(ctx, v0.x, v0.z), v0.z);
				continue;
			}

			float duty = DUTY_FACTOR;

			if (!moving) {
				// Standing: ease each foot back under its rest position rather than freezing wherever
				// the last step ended, so the creature settles into a natural stance.
				double blend = Math.exp(-6.0 * dt);
				foot.plantX = v0.x + (foot.plantX - v0.x) * blend;
				foot.plantZ = v0.z + (foot.plantZ - v0.z) * blend;

				// Clamp plant distance from hip rest position so turning on the spot never twists or stretches legs
				double dx = foot.plantX - v0.x;
				double dz = foot.plantZ - v0.z;
				double maxDist = leg.totalLength * 0.65f;
				if (dx * dx + dz * dz > maxDist * maxDist) {
					double dist = Math.sqrt(dx * dx + dz * dz);
					foot.plantX = v0.x + (dx / dist) * maxDist;
					foot.plantZ = v0.z + (dz / dist) * maxDist;
				}

				// Un-cross legs when turning > 90 degrees or crossing body midline
				float cos = (float) Math.cos(ctx.bodyYaw);
				float sin = (float) Math.sin(ctx.bodyYaw);
				float modelX = (float) ((foot.plantX - ctx.x) * cos - (foot.plantZ - ctx.z) * sin);
				float modelZ = (float) ((foot.plantX - ctx.x) * sin + (foot.plantZ - ctx.z) * cos);
				float maxReach = leg.totalLength * 0.80f;
				if ((leg.side > 0 && modelX < -0.05f) || (leg.side < 0 && modelX > 0.05f)
						|| (Math.abs(modelZ - leg.restEffector.z) > maxReach)) {
					foot.plantX = v0.x;
					foot.plantZ = v0.z;
				}

				foot.plantY = groundAt(ctx, foot.plantX, foot.plantZ);
				foot.grounded = true;
				settleToward(foot, SETTLE_RATE_STOP, dt);
				continue;
			}

			float legPhase = (gaitPhase + leg.gaitPhase) % 1f;
			if (legPhase < duty) {
				// Stance: the foot is pinned to the world. The body moves over it.
				foot.grounded = true;
				settleToward(foot, SETTLE_RATE_STANCE, dt);
			} else {
				float s = (legPhase - duty) / (1f - duty);
				if (foot.grounded) {
					// Lift-off: choose the next plant well ahead of the rest position.
					// Real animals reach their foot forward during the swing phase so the foot
					// lands in front of where the hip will be, not behind.
					float lead = strideLength * 0.65f;
					double tx = v0.x + ctx.forwardX() * lead;
					double tz = v0.z + ctx.forwardZ() * lead;
					foot.beginSwing(tx, groundAt(ctx, tx, tz), tz);
				}
				foot.swing = s;
				// Anticipatory reach: the foot overshoots forward in early swing, then
				// settles back to the plant target as it touches down — matching how real
				// creatures extend their leg forward before planting it.
				float e = MathX.smoothstep(s);
				float overshoot = 0.15f * (float) Math.sin(Math.PI * s);
				float ex = MathX.lerp((float) foot.xo, (float) foot.plantX, Math.min(1f, e + overshoot));
				float ez = MathX.lerp((float) foot.zo, (float) foot.plantZ, Math.min(1f, e + overshoot));
				foot.currentX = ex;
				foot.currentZ = ez;
				// Parabolic lift, peaking mid-swing.
				float lift = STEP_LIFT * plan.hipHeight * (float) Math.sin(Math.PI * s);
				foot.currentY = MathX.lerp((float) foot.prevY, (float) foot.plantY, e) + lift;
			}
		}
	}

	// --------------------------------------------------------------------- body

	private void updateBody(AnimationContext ctx, float dt) {
		if (ctx.swimming) {
			// Nothing to conform to in water. The body sits low with only the back and head clear,
			// bobbing with the stroke rather than tracking a support polygon that does not exist.
			bodyRise = MathX.damp(bodyRise,
					-plan.hipHeight * 0.90f + plan.hipHeight * 0.05f * (float) Math.sin(gaitPhase * Math.PI * 2.0),
					6f, dt);
			// Nose up, the way an animal swims to keep its head clear of the surface.
			bodyPitch = MathX.damp(bodyPitch,
					0.22f + 0.05f * (float) Math.sin(gaitPhase * Math.PI * 2.0), 5f, dt);
			bodyRoll = MathX.damp(bodyRoll, 0.07f * (float) Math.sin(gaitPhase * Math.PI * 2.0 + 1.2), 5f, dt);
			skeleton.rootTransform.identity()
					.translate(0f, bodyRise - activityCrouch * plan.hipHeight, 0f)
					.rotateX(-bodyPitch)
					.rotateZ(bodyRoll);
			return;
		}

		float sumY = 0f, frontY = 0f, rearY = 0f, leftY = 0f, rightY = 0f;
		float frontW = 0f, rearW = 0f, leftW = 0f, rightW = 0f;

		for (int i = 0; i < plan.legs.length; i++) {
			LimbChain leg = plan.legs[i];
			// Model-space height of this foot relative to the entity's own feet level.
			float modelY = (float) (feet[i].currentY - ctx.y);
			sumY += modelY;
			if (leg.restEffector.z >= 0f) {
				frontY += modelY;
				frontW++;
			} else {
				rearY += modelY;
				rearW++;
			}
			if (leg.side > 0) {
				rightY += modelY;
				rightW++;
			} else {
				leftY += modelY;
				leftW++;
			}
		}

		int n = Math.max(1, plan.legs.length);
		float targetRise = sumY / n;

		// Measure ground incline ahead and behind to organically angle up before stepping up blocks.
		float ahead = Math.max(0.6f, plan.bodyLength * 0.5f);
		float frontTerrain = groundAt(ctx, ctx.x + ctx.forwardX() * ahead, ctx.z + ctx.forwardZ() * ahead);
		float rearTerrain = groundAt(ctx, ctx.x - ctx.forwardX() * ahead, ctx.z - ctx.forwardZ() * ahead);
		float terrainPitch = (float) Math.atan2(frontTerrain - rearTerrain, Math.max(0.2f, ahead * 2f));

		// Pitch and roll come from the support polygon, blended with ahead-terrain anticipation.
		float targetPitch = 0f, targetRoll = 0f;
		if (frontW > 0 && rearW > 0) {
			float span = Math.max(0.2f, plan.bodyLength * 0.6f);
			float footPitch = (float) Math.atan2((frontY / frontW) - (rearY / rearW), span);
			targetPitch = footPitch * 0.5f + terrainPitch * 0.5f;
		} else {
			targetPitch = terrainPitch;
		}
		targetPitch = MathX.clamp(targetPitch, -0.7f, 0.7f);
		if (leftW > 0 && rightW > 0) {
			float span = Math.max(0.2f, plan.width() * 0.6f);
			targetRoll = (float) Math.atan2((rightY / rightW) - (leftY / leftW), span);
		}

		// Organic, smooth damping rates so the creature gracefully angles up at block edges instead of snapping.
		bodyRise = MathX.damp(bodyRise, targetRise, 5.0f, dt);
		bodyPitch = MathX.damp(bodyPitch, targetPitch, 4.5f, dt);
		bodyRoll = MathX.damp(bodyRoll, targetRoll, 4.5f, dt);

		// Vertical bob at twice the step frequency — one dip per footfall of a diagonal pair.
		float bobAmount = ctx.speed > IDLE_SPEED ? plan.hipHeight * 0.035f : plan.hipHeight * 0.008f;
		float bob = bobAmount * (float) Math.sin(gaitPhase * Math.PI * 4.0 + (ctx.speed > IDLE_SPEED ? 0.0 : ctx.time));

		// Sign note: a right-handed rotation about +X tips +Z (forward) toward -Y, i.e. nose down,
		// so a nose-up pitch needs the negated angle. About +Z it lifts +X, i.e. the right side.
		//
		// Only part of the pitch is applied here. Rotating the whole creature rigidly reads as a
		// plank tilting; the rest is distributed along the spine in updateSpine so the torso
		// actually arcs over the slope.
		// Behavioural offsets ride on the locomotion transform: a lunge drives the body forward
		// along its own facing, and crouching drops it toward the ground.
		skeleton.rootTransform.identity()
				.translate(0f,
						bodyRise + bob - activityCrouch * plan.hipHeight,
						activityLunge * plan.bodyLength * 0.28f)
				.rotateX(-bodyPitch * ROOT_PITCH_SHARE)
				.rotateZ(bodyRoll);
	}

	// -------------------------------------------------------------- spine / neck

	private void updateSpine(AnimationContext ctx, float dt) {
		if (spineBones.length == 0) return;
		// Bend into turns, and add a small travelling wave so the torso is never rigid.
		//
		// A rider's steering counts for far more than the measured turn rate. Turn rate is a
		// record of motion that has already happened, so a mount driven by it alone only starts
		// to bend once it is already coming round — the rider sees their input answered a beat
		// late, which reads as a stiff animal rather than a responsive one. Steering intent leads
		// the turn instead: the body leans into the corner as it is asked for.
		float targetBend = MathX.clamp(ctx.turnRate * 0.35f, -0.6f, 0.6f)
				+ MathX.clamp(ctx.riderSteer * RIDER_STEER_BEND, -RIDER_STEER_LIMIT, RIDER_STEER_LIMIT);
		targetBend = MathX.clamp(targetBend, -1.1f, 1.1f);
		// Damped rather than applied directly, so the arc is smooth however sharply the rider
		// flicks the mouse.
		lateralBend = MathX.damp(lateralBend, targetBend, 6f, dt);
		riderLean = MathX.damp(riderLean, ctx.riderSteer, 5f, dt);

		// Only a mild extra sway in water. A land animal dog-paddling does not undulate like a fish;
		// the earlier strong wave read as a swimming lizard rather than a struggling quadruped.
		float amplitude = ctx.swimming ? 0.05f : (ctx.speed > IDLE_SPEED ? 0.06f : 0.015f);

		// The share of the slope not taken by the rigid root rotation, spread across the spine so
		// the back curves into the hill instead of the whole animal tilting as one rigid piece.
		float spinePitch = -bodyPitch * (1f - ROOT_PITCH_SHARE) / spineBones.length;

		for (int i = 0; i < spineBones.length; i++) {
			int bone = spineBones[i];
			float along = spineBones.length == 1 ? 0f : (float) i / (spineBones.length - 1);
			// Undulation travels from hips to shoulders.
			float wave = amplitude * (float) Math.sin(gaitPhase * Math.PI * 2.0 - along * 2.2);
			float yaw = lateralBend / spineBones.length + wave;
			float breathe = 0.012f * (float) Math.sin(ctx.time * 1.7 + along);
			// Weight the bend toward the middle of the back — the shoulders and hips are anchored
			// by the limbs, so a hump-shaped curve is what a real spine does over a rise.
			float bend = spinePitch * (0.6f + 0.8f * (float) Math.sin(Math.PI * (0.15f + 0.7f * along)));

			skeleton.bindDirection(bone, dir);
			q0.identity().rotateY(yaw).rotateX(breathe + bend);
			q0.transform(dir);
			aim(bone, dir);
			skeleton.updateBoneWorld(bone);
		}
	}

	private void updateNeckAndHead(AnimationContext ctx) {
		int total = neckBones.length + 1;
		// Behaviour is added to the look direction, not substituted for it, so a grazing creature
		// still tracks a player it has noticed while its head is down.
		float yaw = MathX.clamp(ctx.lookYaw + activityHeadYaw, -1.4f, 1.4f);
		float pitch = MathX.clamp(ctx.lookPitch + activityHeadPitch, -0.9f, 1.5f);

		// Spread the look across the neck so long-necked creatures curve instead of snapping at the base.
		for (int i = 0; i < neckBones.length; i++) {
			float share = (i + 1f) / total;
			poseLookBone(neckBones[i], yaw * share * 0.7f, pitch * share * 0.7f);
		}
		if (plan.headBone >= 0 && plan.headBone < plan.bones.length) {
			poseLookBone(plan.headBone, yaw, pitch);
		}
	}

	private void poseLookBone(int bone, float yaw, float pitch) {
		skeleton.bindDirection(bone, dir);
		q0.identity().rotateY(yaw).rotateX(pitch);
		q0.transform(dir);
		aim(bone, dir);
		skeleton.updateBoneWorld(bone);
	}

	/**
	 * Swings the mandible.
	 * <p>
	 * The jaw's local axis runs from hinge to chin, so opening is a rotation about the bone's own
	 * X — the same axis its bind rotation was built around, which is why this stays a single
	 * angle rather than needing a full aim.
	 * <p>
	 * Ambient breathing is layered under the behavioural opening rather than replaced by it. A
	 * mouth that is perfectly still whenever the creature is not biting reads as a dead prop, and
	 * the cost of avoiding that is one sine wave.
	 */
	private void updateJaw(AnimationContext ctx, float dt) {
		int jaw = plan.jawBone;
		if (jaw < 0 || jaw >= plan.bones.length) return;

		float target = switch (ctx.activity) {
			// Open on the wind-up, snap shut as the strike lands. Biting with a closed mouth and
			// opening afterwards is the single most obviously wrong thing a jaw can do.
			case BITE -> {
				float p = MathX.clamp01(ctx.activityProgress);
				yield p < 0.45f
						? MathX.smoothstep(p / 0.45f)
						: Math.max(0f, 1f - (p - 0.45f) / 0.30f);
			}
			// Chewing: a fast shallow cycle rather than a gape.
			case GRAZE -> 0.18f + 0.14f * (float) Math.sin(ctx.time * 9.0);
			// Mouth shut through a charge or a stomp; an open jaw would read as a bite.
			case RAM, STOMP -> 0f;
			case CLAW, TAIL_SLAM -> 0.12f * strikeCurve(MathX.clamp01(ctx.activityProgress));
			default -> 0f;
		};

		// Panting while moving, slow breathing at rest, over a floor that keeps the mouth from
		// clamping perfectly shut.
		float ambient = BodyPlan.JAW_REST_SLACK + (ctx.speed > IDLE_SPEED
				? 0.10f + 0.07f * (float) Math.sin(ctx.time * 6.5)
				: 0.02f + 0.02f * (float) Math.sin(ctx.time * 1.5));

		// Snapping shut is far faster than opening — a jaw closes under muscle and gravity
		// together, and easing both ways at one rate makes every bite look languid.
		float goal = MathX.clamp01(target + ambient);
		float rate = goal < jawOpen ? 26f : 14f;
		jawOpen = MathX.damp(jawOpen, goal, rate, dt);

		// The bind pose gapes and rest closes it, not the other way round. Baked shut there is no
		// seam in the mesh for the mouth to open along, so the plan bakes the mandible well clear
		// of the skull and the resting pose is a rotation back up to meet it — which means the
		// default state is a *closing* rotation, and opening is simply less of one.
		//
		// Negative lifts the chin. The bind rotation takes local +Y onto the hinge-to-chin
		// direction, and with the skull's basis the right way up that leaves local +X pointing to
		// the creature's right, so closing is the negative sense.
		q0.identity().rotateX(-plan.jawRestAngle * (1f - jawOpen));
		skeleton.setLocalRotation(jaw, q0);
		skeleton.updateBoneWorld(jaw);
	}

	private void updateTail(AnimationContext ctx, float dt) {
		if (tailBones.length == 0) return;
		// The tail lags the body's turn, then overshoots slightly as it catches up. In water it
		// sculls instead.
		float lagTarget = ctx.swimming
				? 0.25f * (float) Math.sin(gaitPhase * Math.PI * 2.0 - 0.9)
				: MathX.clamp(-ctx.turnRate * 0.8f, -1.0f, 1.0f);
		tailLag = MathX.damp(tailLag, lagTarget, ctx.swimming ? 6f : 4f, dt);

		// Weight shift counterbalance: as the body sways or rolls to one side, the tail swings
		// to the opposite side to maintain physical equilibrium. A steered turn is the largest
		// weight shift there is, so the tail swings out against it — the same reason a cat's tail
		// goes wide on a corner.
		float rollCounterbalance = -bodyRoll * 1.5f - riderLean * TAIL_COUNTER_LEAN;
		float stepSway = ctx.speed > IDLE_SPEED ? -0.18f * (float) Math.sin(gaitPhase * Math.PI * 2.0) : 0f;

		for (int i = 0; i < tailBones.length; i++) {
			int bone = tailBones[i];
			float along = (float) (i + 1) / tailBones.length;
			// Amplitude grows toward the tip, so the tail whips and counterbalances dynamically.
			float yaw = tailLag * along * 0.55f
					+ (rollCounterbalance + stepSway) * along * 0.70f
					+ 0.14f * along * (float) Math.sin(ctx.time * 2.4 - along * 2.5)
					+ activityTailYaw * along * along;
			float pitch = 0.10f * along * (float) Math.sin(ctx.time * 1.9 - along * 2.0)
					- (ctx.speed > IDLE_SPEED ? 0.12f * along : 0f);

			skeleton.bindDirection(bone, dir);
			q0.identity().rotateY(yaw).rotateX(pitch).rotateZ(-rollCounterbalance * along * 0.5f);
			q0.transform(dir);
			aim(bone, dir);
			skeleton.updateBoneWorld(bone);
		}
	}

	// ----------------------------------------------------------------------- IK

	private void solveLeg(AnimationContext ctx, LimbChain leg, FootState foot) {
		int n = leg.bones.length;
		// Seed FABRIK from the current pose: temporal coherence keeps the solution stable between frames.
		for (int i = 0; i < n; i++) {
			skeleton.boneHead(leg.bones[i], jointScratch[i]);
			lengthScratch[i] = skeleton.boneLength(leg.bones[i]);
		}
		skeleton.boneTail(leg.bones[n - 1], jointScratch[n]);

		toModel(ctx, foot.currentX, foot.currentY, foot.currentZ, v1);

		// Clamp foot target in model space below hip height so legs never get pinned upside down in the air
		float maxFootY = jointScratch[0].y - 0.05f;
		if (v1.y > maxFootY) {
			v1.y = maxFootY;
		}

		// Absorb over-reach by letting the limb stretch a little rather than locking straight.
		// A fully extended chain has no bend for the solver to preserve, so the next frame that
		// comes back in range has to re-derive one from scratch — that is the knee pop. A few
		// percent of stretch is imperceptible and keeps the limb bent, and therefore continuous.
		dir.set(v1).sub(jointScratch[0]);
		float reach = dir.length();
		float comfortable = leg.totalLength * COMFORTABLE_REACH;
		if (reach > comfortable && reach > MathX.EPS) {
			float stretch = Math.min(reach / comfortable, MAX_LIMB_STRETCH);
			for (int i = 0; i < n; i++) {
				lengthScratch[i] *= stretch;
			}
			// Past the stretch limit the target is genuinely unreachable; clamp what remains so
			// the solver is never handed a target it can only answer by going ramrod straight.
			float maxReach = comfortable * stretch;
			if (reach > maxReach) {
				v1.set(jointScratch[0]).fma(maxReach / reach, dir);
			}
		}

		fabrik.solve(jointScratch, lengthScratch, n, v1, leg.poleDirection, leg.bendSigns,
				IK_ITERATIONS);
		applyChain(leg.bones, n);
	}

	/**
	 * Arms are not weight-bearing, so they get a cheap counter-swing against the gait instead of a
	 * full IK solve — they have nothing to reach for.
	 */
	private void swingArm(AnimationContext ctx, LimbChain arm) {
		float amplitude = ctx.speed > IDLE_SPEED ? 0.30f : 0.06f;
		float swing = amplitude * (float) Math.sin(gaitPhase * Math.PI * 2.0 + (arm.side > 0 ? Math.PI : 0.0));

		// A claw strike drives one forelimb across the body, overriding its walk swing.
		boolean leading = arm.side > 0;
		float strike = leading ? activityArmSwing : activityArmSwing * -0.35f;

		for (int i = 0; i < arm.bones.length; i++) {
			int bone = arm.bones[i];
			float share = 1f - i * 0.35f;
			skeleton.bindDirection(bone, dir);
			// Natural downward/forward arm posture so forelimbs never point up into torso/hitbox
			q0.identity()
					.rotateX(0.20f + swing * share - strike * 0.6f * share)
					.rotateY(-strike * 0.5f * share * arm.side);
			q0.transform(dir);
			aim(bone, dir);
			skeleton.updateBoneWorld(bone);
		}
	}

	/** Converts solved joint positions back into per-bone local rotations, root to tip. */
	private void applyChain(int[] bones, int n) {
		for (int i = 0; i < n; i++) {
			dir.set(jointScratch[i + 1]).sub(jointScratch[i]);
			if (dir.lengthSquared() < 1e-10f) continue;
			dir.normalize();
			aim(bones[i], dir);
			// The next bone's rest frame depends on this one, so resolve it before moving on.
			skeleton.updateBoneWorld(bones[i]);
		}
	}

	private void aim(int bone, Vector3f modelDirection) {
		skeleton.aimBone(bone, modelDirection, q1, v0);
	}

	/**
	 * Exponentially eases a foot's rendered position toward its plant. Used instead of assignment
	 * everywhere a foot becomes grounded, so no transition into stance is ever a hard jump.
	 */
	private static void settleToward(FootState foot, float rate, float dt) {
		double blend = Math.exp(-rate * dt);
		foot.currentX = foot.plantX + (foot.currentX - foot.plantX) * blend;
		foot.currentY = foot.plantY + (foot.currentY - foot.plantY) * blend;
		foot.currentZ = foot.plantZ + (foot.currentZ - foot.plantZ) * blend;
	}

	// ------------------------------------------------------------------ helpers

	/** World-space position this leg's foot rests at when standing still. */
	private void restFootWorld(AnimationContext ctx, LimbChain leg, Vector3f dest) {
		toWorld(ctx, leg.restEffector, dest);
	}

	/** Model space to world space: rotate by the body yaw, then translate to the entity. */
	private void toWorld(AnimationContext ctx, Vector3f model, Vector3f dest) {
		float c = (float) Math.cos(ctx.bodyYaw);
		float s = (float) Math.sin(ctx.bodyYaw);
		dest.set(
				(float) (ctx.x + model.x * c - model.z * s),
				(float) (ctx.y + model.y),
				(float) (ctx.z + model.x * s + model.z * c));
	}

	/** World space to model space: the exact inverse of {@link #toWorld}. */
	private void toModel(AnimationContext ctx, double wx, double wy, double wz, Vector3f dest) {
		float c = (float) Math.cos(ctx.bodyYaw);
		float s = (float) Math.sin(ctx.bodyYaw);
		float dx = (float) (wx - ctx.x);
		float dy = (float) (wy - ctx.y);
		float dz = (float) (wz - ctx.z);
		dest.set(dx * c + dz * s, dy, -dx * s + dz * c);
	}

	/**
	 * Ground height under a point. If the target sits over a hole, pit, or cliff edge,
	 * searches adjacent ground candidates so feet land on nearby solid ground instead of floating over air.
	 */
	private float groundAt(AnimationContext ctx, double x, double z) {
		float y = ctx.ground.groundY(x, z, ctx.y);
		if (!Float.isNaN(y) && Math.abs(y - ctx.y) < 1.25f) {
			return y;
		}
		double[] offsetsX = { 0.0, -0.35, 0.35, 0.0 };
		double[] offsetsZ = { -0.35, 0.0, 0.0, 0.35 };
		for (int i = 0; i < 4; i++) {
			float candidateY = ctx.ground.groundY(x + offsetsX[i], z + offsetsZ[i], ctx.y);
			if (!Float.isNaN(candidateY) && Math.abs(candidateY - ctx.y) < 1.25f) {
				return candidateY;
			}
		}
		return (float) ctx.y;
	}

	private static int[] collect(BodyPlan plan, String prefix) {
		int count = 0;
		for (BoneDef bone : plan.bones) {
			if (bone.name.startsWith(prefix)) count++;
		}
		int[] out = new int[count];
		int i = 0;
		for (int b = 0; b < plan.bones.length; b++) {
			if (plan.bones[b].name.startsWith(prefix)) out[i++] = b;
		}
		return out;
	}
}
