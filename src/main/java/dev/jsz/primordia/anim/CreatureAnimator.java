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

	// ---------------------------------------------------------------- reach envelope
	//
	// Everything below exists because a leg has a reach and a stride has a length, and until now
	// nothing in this file related the two. Stride was `hipHeight * 1.35`, a proportion picked by
	// eye, while a limb is grown along a shallow Bezier only five to ten per cent longer than the
	// straight line from hip to foot — so a creature standing still already sits at 86% to 99% of
	// its legs' full length, and a stride of that size asks every leg for a target well outside its
	// own reach on every single step. Measured across all eleven archetypes over blocky terrain,
	// 59% of leg-frames were asking for the impossible.
	//
	// The solver cannot report that. It absorbs over-reach by stretching a working copy of the bone
	// lengths, so an unreachable target still comes back with a bend in it — the chain simply ends
	// up short, pointing at somewhere the foot never arrives. On screen that is a rigid leg held out
	// at an angle with its foot off the ground, which is what "the legs go straight and pin" is
	// describing, and it is why every straightness-based check passed while it was happening.
	//
	// So the stride is now derived from the geometry instead of guessed: how far a foot may travel
	// fore and aft, and sideways, before the hip can no longer hold it. See buildEnvelope.

	/**
	 * Hard ceiling on hip-to-foot distance, as a fraction of the limb's own length. Nothing —
	 * gait, terrain or turn — may ask for more than this.
	 */
	private static final float WORKING_REACH = 0.97f;
	/**
	 * How heavily a leg is loaded standing still, as a fraction of {@link #WORKING_REACH}. Under
	 * one on purpose: the body settles a little below its bind height so that a stride has
	 * somewhere to go. An animal standing with its legs locked straight has no step available to
	 * it, which is exactly the corner the old numbers painted every creature into.
	 */
	private static final float STANCE_LOAD = 0.93f;
	/**
	 * Fraction of the envelope an ordinary stride uses. The remainder is headroom for the ground
	 * being somewhere other than where the gait assumed — which, on terrain made of whole blocks,
	 * is most of the time.
	 */
	private static final float STRIDE_MARGIN = 0.85f;
	/**
	 * Smallest excursion a leg is credited with, as a fraction of its length, however cramped its
	 * geometry. Keeps cadence finite for limbs whose rest position is already near the edge of
	 * their own reach.
	 */
	private static final float MIN_EXCURSION = 0.06f;
	/** Ceiling on step frequency. Small animals really do scurry; past this it reads as a blur. */
	private static final float MAX_STEP_FREQUENCY = 6f;
	/** Cadence up to which the torso's bob is drawn at full size, in steps per second. */
	private static final float BOB_FULL_RATE = 1.8f;
	/** Cadence at and above which the bob is gone entirely. */
	private static final float BOB_SILENT_RATE = 4.2f;
	/**
	 * Furthest the neutral stance may be shifted fore or aft of where a limb was grown, as a
	 * fraction of the leg's length.
	 * <p>
	 * Body plans routinely grow a foot far ahead of or behind its own hip, and a foot planted at
	 * the front of its reach has almost no forward room left before it is dragged straight. Walking
	 * from that point costs the whole stride, so the gait works about the middle of the leg's
	 * travel instead of about its bind position. Capped, because the bind position is also the
	 * silhouette the mesh was built around and moving it far enough to fix the arithmetic would be
	 * a different creature.
	 */
	private static final float MAX_STANCE_SHIFT = 0.35f;
	/**
	 * How far past its envelope a planted foot must be dragged before a corrective step fires, as a
	 * fraction of the envelope.
	 * <p>
	 * Stance clamps the plant to the envelope boundary every frame, so without a margin the foot
	 * sits exactly on the trigger and the next millimetre of body travel re-fires it — a leg
	 * stepping every frame, which is what "vibrating" looks like. The step has to be worth taking.
	 */
	private static final float ENVELOPE_HYSTERESIS = 1.06f;
	/**
	 * Shortest time a foot must stay down between corrective steps, in seconds.
	 * <p>
	 * Hysteresis alone is not enough: a foot can land already outside its envelope when the body is
	 * turning, and re-fire immediately. The phase-driven step is never subject to this — the gait's
	 * own timing is what keeps the creature balanced.
	 */
	private static final float STEP_REFRACTORY = 0.12f;
	/**
	 * Fastest the torso may rise or fall, in leg lengths per second.
	 * <p>
	 * The height target is a minimum taken over whichever feet are bearing weight this frame, and
	 * a minimum over a changing set is a step function: every touchdown and lift-off moves it
	 * discontinuously. Damping alone chases that, which reads as the whole animal juddering
	 * vertically. This is the brake, and it is the last thing applied.
	 */
	private static final float MAX_RISE_RATE = 1.4f;
	/** Steepest the body will tilt to follow ground, radians. Roughly twenty degrees. */
	private static final float MAX_BODY_TILT = 0.35f;
	/** Fastest the body may change attitude, radians per second. */
	private static final float MAX_TILT_RATE = 2.2f;
	/** Weight of the look-ahead terrain slope against the slope the feet are actually standing on. */
	private static final float TERRAIN_ANTICIPATION = 0.25f;
	/** Samples along the body used to measure the slope it is walking onto. */
	private static final int TERRAIN_SAMPLES = 5;
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

	// ---- reach envelope, per leg, model units. Constant for a body plan; see buildEnvelope.
	/** Furthest a foot may be from its hip, ever. */
	private final float[] legReach;
	/** How far forward of its rest position a foot may travel. */
	private final float[] excursionFwd;
	/** How far behind it. Asymmetric because a leg whose foot already fans forward has less room. */
	private final float[] excursionBack;
	/** How far to either side. */
	private final float[] excursionSide;
	/** How far below its bind height the body sits when standing, so a stride has somewhere to go. */
	private final float stanceDrop;
	/** Longest leg on the creature; sets how far down a foot may look for ground. */
	private final float longestLeg;

	/**
	 * Model-space hip position of each leg as of the last completed solve, and the root translation
	 * that was in force when it was taken.
	 * <p>
	 * The body height pass needs to know where the hips are, and it runs before the skeleton is
	 * posed — the hips do not exist yet this frame. Last frame's are one frame stale, which on a
	 * quantity that is damped anyway is not observable, and they are <i>measured</i> rather than
	 * reconstructed from the bind pose, so they carry the spine bend and the body attitude that
	 * actually moved them.
	 */
	private final Vector3f[] hipAtLastSolve;
	private float riseAtLastSolve;
	/** Model-space fore/aft offset from each limb's bind foot to the middle of its reach. */
	private float[] strideCentre;
	/** Steps per second the gait is currently running at. Shared with the body's vertical bob. */
	private float stepFrequency;
	private boolean hipsKnown;

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
	private final Vector3f v2 = new Vector3f();
	private final Vector3f dir = new Vector3f();
	private final Quaternionf q0 = new Quaternionf();
	private final Quaternionf q1 = new Quaternionf();
	private final Vector3f[] jointScratch;
	private final float[] lengthScratch;
	/** Scratch for the support-plane and terrain regressions. */
	private final float[] planeX, planeY, planeZ;
	/** Rise per unit of model-space width and depth, from the last support-plane fit. */
	private float planeSlopeX, planeSlopeZ;

	public CreatureAnimator(BodyPlan plan) {
		this.plan = plan;
		this.skeleton = new Skeleton(plan);
		this.feet = new FootState[plan.legs.length];
		for (int i = 0; i < feet.length; i++) {
			feet[i] = new FootState();
		}

		int legCount = plan.legs.length;
		this.legReach = new float[legCount];
		this.excursionFwd = new float[legCount];
		this.excursionBack = new float[legCount];
		this.excursionSide = new float[legCount];
		this.strideCentre = new float[legCount];
		this.hipAtLastSolve = new Vector3f[legCount];
		for (int i = 0; i < legCount; i++) hipAtLastSolve[i] = new Vector3f();

		float longest = 0f;
		for (LimbChain leg : plan.legs) longest = Math.max(longest, leg.totalLength);
		this.longestLeg = Math.max(0.1f, longest);

		this.stanceDrop = buildEnvelope();
		// One stride carries the body from the front of the envelope to the back of it, so the
		// distance covered per gait cycle is twice the half-span the tightest leg can manage. Every
		// leg has to fit, which is why this is a minimum and not an average: a creature whose rear
		// pair could stride further than its front pair would simply tear the front pair straight.
		//
		// The half-span is measured about the middle of each leg's travel, not about the foot's
		// bind position, and that distinction is the difference between a walk and a shiver. Body
		// plans grow feet well fore or aft of their own hips — a quadruped's front foot commonly
		// sits two thirds of a leg length ahead of its shoulder — which leaves that leg a tenth of
		// its length of forward room and well over a full length behind it. Taking the smaller side
		// and doubling it threw the larger side away, and because this is a minimum over every leg,
		// one such limb set the cadence for the whole animal: stride collapsed to a quarter of hip
		// height, step frequency pinned against its ceiling, and every leg blurred.
		float tightest = Float.MAX_VALUE;
		for (int i = 0; i < legCount; i++) {
			// Centre of the reach interval, capped so a limb is never relocated far enough to
			// change the creature's outline.
			float centre = MathX.clamp(0.5f * (excursionFwd[i] - excursionBack[i]),
					-MAX_STANCE_SHIFT * plan.legs[i].totalLength,
					MAX_STANCE_SHIFT * plan.legs[i].totalLength);
			strideCentre[i] = centre;
			// Room left on each side of that centre; the stride has to fit inside the smaller.
			tightest = Math.min(tightest,
					Math.min(excursionFwd[i] - centre, excursionBack[i] + centre));
		}
		this.strideLength = legCount == 0
				? Math.max(0.25f, plan.hipHeight)
				: Math.max(0.06f, 2f * STRIDE_MARGIN * tightest);

		this.spineBones = collect(plan, "spine");
		this.neckBones = collect(plan, "neck");
		this.tailBones = collect(plan, "tail");

		int maxSegments = 2;
		for (LimbChain leg : plan.legs) maxSegments = Math.max(maxSegments, leg.bones.length);
		for (LimbChain arm : plan.arms) maxSegments = Math.max(maxSegments, arm.bones.length);
		this.jointScratch = new Vector3f[maxSegments + 1];
		for (int i = 0; i < jointScratch.length; i++) jointScratch[i] = new Vector3f();
		this.lengthScratch = new float[maxSegments];

		int scratch = Math.max(TERRAIN_SAMPLES, Math.max(1, legCount));
		this.planeX = new float[scratch];
		this.planeY = new float[scratch];
		this.planeZ = new float[scratch];
	}

	/**
	 * Works out how far each foot may travel from where it was grown, and returns how far the body
	 * has to settle for any of that travel to be possible.
	 * <p>
	 * The whole thing is one piece of geometry. A hip sits at {@code b} from its rest foot; the leg
	 * can span at most {@code R = totalLength * WORKING_REACH}. Drop the body by {@code d} and
	 * displace the foot by {@code e} along one axis, and the constraint is simply
	 * {@code |b - (0,d,0) - e| <= R}. Solved for {@code e} that is an interval, and the interval is
	 * the envelope — asymmetric, because a foot that already fans forward of its hip has spent some
	 * of its forward room before the creature has taken a step.
	 * <p>
	 * The drop is chosen first, as the least that loads every leg to {@link #STANCE_LOAD} of its
	 * reach rather than to the hilt. Without it the envelope is empty: a creature standing at full
	 * extension has no direction it can move a foot in that does not over-extend the limb, which is
	 * the arithmetic behind every pinned leg this system used to produce.
	 *
	 * @return the model-space distance the body sits below its bind height when standing
	 */
	private float buildEnvelope() {
		float drop = 0f;
		for (int i = 0; i < plan.legs.length; i++) {
			LimbChain leg = plan.legs[i];
			legReach[i] = leg.totalLength * WORKING_REACH;

			float bx = leg.origin.x - leg.restEffector.x;
			float by = leg.origin.y - leg.restEffector.y;
			float bz = leg.origin.z - leg.restEffector.z;
			// Never ask a leg for more than it already manages standing: a limb grown at 0.99 of
			// its own length is telling us what its owner's stance is, and stretching it to a
			// nominal 0.97 would be reading the constant instead of the creature.
			float rest = (float) Math.sqrt(bx * bx + by * by + bz * bz);
			float loaded = Math.min(legReach[i], rest) * STANCE_LOAD;
			float horizontal = bx * bx + bz * bz;
			float vertical = (float) Math.sqrt(Math.max(0f, loaded * loaded - horizontal));
			// by is the hip's height over its foot and is positive for any leg that reaches the
			// ground; a limb arched so far that its hip sits below its own foot has no drop to give.
			drop = Math.max(drop, by - vertical);
		}

		for (int i = 0; i < plan.legs.length; i++) {
			LimbChain leg = plan.legs[i];
			float floor = leg.totalLength * MIN_EXCURSION;
			float bx = leg.origin.x - leg.restEffector.x;
			float by = leg.origin.y - leg.restEffector.y - drop;
			float bz = leg.origin.z - leg.restEffector.z;
			float r2 = legReach[i] * legReach[i];

			// Fore and aft: the foot may sit anywhere in [bz - s, bz + s] measured from the hip's
			// own z, which about the rest position is [-(s - bz), s + bz] ... and the same figure
			// mirrored for a hip behind its foot. Hence the two separate limits.
			float sz = (float) Math.sqrt(Math.max(0f, r2 - bx * bx - by * by));
			excursionFwd[i] = Math.max(floor, sz + bz);
			excursionBack[i] = Math.max(floor, sz - bz);

			float sx = (float) Math.sqrt(Math.max(0f, r2 - bz * bz - by * by));
			excursionSide[i] = Math.max(floor, sx - Math.abs(bx));
		}
		return Math.max(0f, drop);
	}

	public Skeleton skeleton() {
		return skeleton;
	}

	/**
	 * Distance the body travels per gait cycle, in model units. Derived from the legs' reach
	 * envelope rather than from body proportions — see {@link #buildEnvelope}.
	 */
	/**
	 * Steps per second this creature takes at the given speed and growth.
	 * <p>
	 * Published because the gait's cadence is not derivable from the body plan by anyone outside
	 * this class — it comes from the legs' reach envelope — and anything that has to line up with
	 * the walk needs the same number the walk is using. The editor's clip recorder kept its own copy
	 * of this formula and the two drifted the moment the stride derivation changed, which is exactly
	 * the failure its own comment predicted: a clip that is no longer one whole cycle long cannot
	 * loop, and the seam is the first thing anyone watching a walk cycle sees.
	 */
	public float stepFrequencyFor(float speed, float growth) {
		float s = growth > 1e-3f ? growth : 1f;
		return MathX.clamp(speed / (strideLength * s), 0.15f, MAX_STEP_FREQUENCY);
	}

	/** Seconds one whole gait cycle takes — the clip length that loops seamlessly. */
	public float gaitCycleSeconds(float speed, float growth) {
		float f = stepFrequencyFor(speed, growth);
		return f > MathX.EPS ? 1f / f : 0f;
	}

	public float strideLength() {
		return strideLength;
	}

	/** How far below its bind height the creature stands, model units. */
	public float stanceDrop() {
		return stanceDrop;
	}

	/** Furthest the given leg may ever be asked to reach, model units. */
	public float legReach(int index) {
		return legReach[index];
	}

	public float excursionFwd(int index) {
		return excursionFwd[index];
	}

	public float excursionBack(int index) {
		return excursionBack[index];
	}

	public float excursionSide(int index) {
		return excursionSide[index];
	}

	/** Current damped body pitch, radians, nose-up positive. Exposed for gait diagnostics. */
	/** Model-space height the torso is riding at, bob and crouch included. Diagnostics. */
	public float bodyRise() {
		return riseAtLastSolve;
	}

	public float bodyPitch() {
		return bodyPitch;
	}

	/** Current damped body roll, radians, right-side-up positive. Exposed for gait diagnostics. */
	public float bodyRoll() {
		return bodyRoll;
	}

	/** Whether this foot is currently bearing weight rather than swinging. For gait diagnostics. */
	public boolean isFootGrounded(int index) {
		return feet != null && index >= 0 && index < feet.length
				&& feet[index] != null && feet[index].initialised && feet[index].grounded;
	}

	public boolean getFootWorldPosition(int index, org.joml.Vector3d dest) {
		if (feet != null && index >= 0 && index < feet.length && feet[index] != null && feet[index].initialised) {
			dest.set(feet[index].currentX, feet[index].currentY, feet[index].currentZ);
			return true;
		}
		return false;
	}

	public void update(AnimationContext ctx) {
		boolean isDead = ctx.collapse >= 0.99f || ctx.activity == dev.jsz.primordia.entity.CreatureActivity.CARCASS;
		float dt;
		if (isDead || Float.isNaN(lastTime)) {
			dt = 0f;
		} else {
			// Clamped so a lag spike or a pause cannot teleport the gait forward.
			dt = MathX.clamp(ctx.time - lastTime, 0f, 0.25f);
		}
		lastTime = ctx.time;

		if (isDead) {
			tailLag = 0f;
			lateralBend = 0f;
			riderLean = 0f;
			activityHeadPitch = 0.8f;
			activityHeadYaw = 0f;
			activityTailYaw = 0f;
			activityArmSwing = 0f;
			activityLunge = 0f;
			activityCrouch = 1f;
			jawOpen = 0f;
		}

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

		if (isDead || LodTier.usesInverseKinematics(ctx.tier)) {
			for (int i = 0; i < plan.legs.length; i++) {
				// Recorded before the solve, not after: this is where the hip is, and the solve is
				// about to move everything below it. The body height pass reads these next frame.
				skeleton.boneHead(plan.legs[i].bones[0], hipAtLastSolve[i]);
				solveLeg(ctx, plan.legs[i], feet[i]);
			}
			hipsKnown = true;
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
				targetHeadYaw = 0.10f * idle(ctx, ctx.time * 1.3);
				// Fast small bob is the jaw working; the slow one is the head shifting between bites.
				targetHeadPitch += 0.09f * idle(ctx, ctx.time * 9.0)
						+ 0.05f * idle(ctx, ctx.time * 2.1);
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
				targetHeadYaw = 0.22f * idle(ctx, ctx.time * 3.1);
				targetHeadPitch += 0.14f * idle(ctx, ctx.time * 7.5);
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
		// Cadence falls out of speed and stride, and stride is now the distance the legs can
		// actually carry the body — so a creature whose legs cannot make the step it is being asked
		// for takes more steps rather than longer ones. There is no minimum any more: the old floor
		// existed to keep a large animal's legs visibly moving, but it did that by cycling the gait
		// faster than the body was travelling, which planted every foot ahead of where the hip
		// would ever reach. Legs that move but cannot hold the ground are worse than slow legs.
		stepFrequency = moving ? stepFrequencyFor(ctx.speed, ctx.scale) : 0f;
		gaitPhase = (gaitPhase + stepFrequency * dt) % 1f;

		// How far the body will travel while a foot is on the ground. Half of it is the lead: plant
		// the foot that far ahead of the hip and it passes under the hip at mid-stance and finishes
		// as far behind, which is what makes a stride symmetric. The old fixed 0.65 of a stride put
		// the foot ahead of the hip at touchdown and *still* ahead at lift-off, so every leg spent
		// its whole stance reaching forward — and at the two frequency clamps, where cadence and
		// speed stop agreeing, it was ahead by an unbounded amount.
		float stanceTravel = stepFrequency > MathX.EPS ? ctx.speed * DUTY_FACTOR / stepFrequency : 0f;
		float swingSeconds = stepFrequency > MathX.EPS
				? MathX.clamp((1f - DUTY_FACTOR) / stepFrequency, 0.06f, 0.6f)
				: 0.25f;
		// The plant is chosen when the foot leaves the ground and reached when it lands, and the
		// body keeps moving in between. Leading by half a stance alone therefore aims at where the
		// hip was, not where it will be: at a duty factor of 0.62 the swing carries the body 0.38
		// of a cycle while the lead covers 0.31 of one, so the foot touched down already behind the
		// hip and spent its whole stance falling further back. Measured over the gait report it was
		// 0.29 of a leg length aft on average, every leg, every archetype — the legs trailing look.
		//
		// Adding the swing's own travel makes the plant land where the rest position will be, and
		// the half-stance lead then does what it was always meant to: the foot passes under the hip
		// at mid-stance and finishes as far behind as it started ahead.
		float lead = ctx.speed * swingSeconds + stanceTravel * 0.5f;

		for (int i = 0; i < plan.legs.length; i++) {
			LimbChain leg = plan.legs[i];
			FootState foot = feet[i];

			restFootWorld(ctx, i, leg, v0);
			if (!foot.initialised) {
				foot.snapTo(v0.x, groundAt(ctx, v0.x, v0.z), v0.z);
				continue;
			}

			if (!moving) {
				// Standing: ease each foot back under its rest position rather than freezing
				// wherever the last step ended, so the creature settles into a natural stance.
				double blend = Math.exp(-6.0 * dt);
				foot.plantX = v0.x + (foot.plantX - v0.x) * blend;
				foot.plantZ = v0.z + (foot.plantZ - v0.z) * blend;
				clampPlantToEnvelope(ctx, i, leg, foot);
				foot.plantY = groundAt(ctx, foot.plantX, foot.plantZ);
				foot.grounded = true;
				foot.groundedTime += dt;
				foot.inStanceWindow = true;
				settleToward(foot, SETTLE_RATE_STOP, dt);
				continue;
			}

			float legPhase = (gaitPhase + leg.gaitPhase) % 1f;
			boolean inWindow = legPhase < DUTY_FACTOR;

			if (foot.grounded) {
				// Two things can start a step. The gait phase asking for one, on the falling edge of
				// the stance window — edge-triggered, so a foot that already stepped for its own
				// reasons is not made to step again the moment it lands. And the foot having been
				// carried outside what its leg can hold, which the phase knows nothing about: a
				// creature turning on the spot, cresting a block, or changing speed drags a planted
				// foot out of reach with the gait cycle nowhere near its swing. That case used to
				// end with the limb pointing at a target it could not reach until the phase came
				// round, which is most of what "pinned straight legs" was.
				boolean phaseWants = foot.inStanceWindow && !inWindow;
				// The refractory period stops a foot clamped to its own boundary from re-stepping
				// every frame. It must not also strand a foot that is genuinely out of reach: a
				// creature moving faster than its legs can stride drags a plant a long way in a
				// tenth of a second, and waiting it out is the leg held straight behind the body.
				// So marginal violations wait and gross ones are answered at once.
				boolean settled = foot.groundedTime >= STEP_REFRACTORY;
				boolean dragged = footBeyondEnvelope(ctx, i, leg, foot)
						&& (settled || footStranded(ctx, i, leg, foot));
				if ((phaseWants || dragged) && (phaseWants || canLift(i))) {
					stepTo(ctx, i, leg, foot, lead, swingSeconds);
				}
			}
			foot.inStanceWindow = inWindow;

			if (foot.grounded) {
				// Stance: the foot is pinned to the world. The body moves over it.
				//
				// Except when the body is moving faster than the legs can stride, which for a small
				// creature is most of the time — a fifteen-centimetre insectoid given three blocks a
				// second is being asked for fifteen leg-lengths a second, and no gait makes that
				// work. Something has to give, and the only choices are the foot's grip or the leg
				// itself. Dragging the plant back inside the envelope spends the grip: the feet
				// skate, which is honest about what is happening and leaves every limb bent and
				// attached. It is deliberately the last resort and a walk at any sane speed never
				// reaches it, because a plant inside the envelope is left exactly where it was put.
				foot.groundedTime += dt;
				clampPlantToEnvelope(ctx, i, leg, foot);
				settleToward(foot, SETTLE_RATE_STANCE, dt);
			} else {
				foot.swingTime += dt;
				float s = MathX.clamp01(foot.swingTime / foot.swingDuration);
				foot.swing = s;
				// Anticipatory reach: the foot overshoots forward in early swing, then
				// settles back to the plant target as it touches down — matching how real
				// creatures extend their leg forward before planting it.
				float e = MathX.smoothstep(s);
				float overshoot = 0.15f * (float) Math.sin(Math.PI * s);
				float reach = Math.min(1f, e + overshoot);
				foot.currentX = MathX.lerp((float) foot.xo, (float) foot.plantX, reach);
				foot.currentZ = MathX.lerp((float) foot.zo, (float) foot.plantZ, reach);
				// Parabolic lift, peaking mid-swing. Sized to the leg rather than to the hip height
				// so a short-legged creature does not high-step over ground it is barely clearing.
				float lift = STEP_LIFT * longestLeg * scale(ctx) * (float) Math.sin(Math.PI * s);
				foot.currentY = MathX.lerp((float) foot.prevY, (float) foot.plantY, e) + lift;
				if (s >= 1f) {
					foot.grounded = true;
					foot.swing = 0f;
				}
			}
		}
	}

	/**
	 * Chooses where a foot lands and starts it moving.
	 * <p>
	 * The target is the rest position led forward far enough to cover the swing the foot is about
	 * to take plus half the stance that follows it, then pulled back inside the leg's envelope. The
	 * clamp is the guarantee: whatever the gait, the terrain or the turn asked for, the foot is
	 * planted somewhere the hip can hold it.
	 */
	private void stepTo(AnimationContext ctx, int index, LimbChain leg, FootState foot,
	                    float lead, float swingSeconds) {
		restFootWorld(ctx, index, leg, v0);
		double tx = v0.x + ctx.forwardX() * lead;
		double tz = v0.z + ctx.forwardZ() * lead;
		foot.beginSwing(tx, groundAt(ctx, tx, tz), tz, swingSeconds);
		clampPlantToEnvelope(ctx, index, leg, foot);
		foot.plantY = groundAt(ctx, foot.plantX, foot.plantZ);
	}

	/**
	 * True when a planted foot has been carried outside the box its leg can reach over.
	 * <p>
	 * Horizontal only. Vertical over-reach is the body height pass's problem and it can usually
	 * solve it by settling the creature lower; a foot too far out sideways or fore-and-aft is one
	 * no amount of crouching rescues, and the only honest answer is another step.
	 */
	private boolean footBeyondEnvelope(AnimationContext ctx, int index, LimbChain leg, FootState foot) {
		toModel(ctx, foot.plantX, foot.plantY, foot.plantZ, v1);
		// Every limit is widened by the hysteresis margin. Stance pulls the plant back to the
		// boundary each frame, so a test that fires exactly at the boundary fires forever.
		float dz = v1.z - (leg.restEffector.z + strideCentre[index]);
		float dx = v1.x - leg.restEffector.x;
		if (dz > roomFwd(index) * ENVELOPE_HYSTERESIS
				|| dz < -roomBack(index) * ENVELOPE_HYSTERESIS
				|| Math.abs(dx) > excursionSide[index] * ENVELOPE_HYSTERESIS) {
			return true;
		}
		hipBase(index, v0);
		float room = horizontalRoom(index, v0, v1.y) * ENVELOPE_HYSTERESIS;
		float hx = v1.x - v0.x, hz = v1.z - v0.z;
		return hx * hx + hz * hz > room * room;
	}

	/**
	 * True when a planted foot is not merely outside its stride envelope but outside what the limb
	 * can physically span, so that no amount of crouching or waiting will reach it.
	 */
	private boolean footStranded(AnimationContext ctx, int index, LimbChain leg, FootState foot) {
		toModel(ctx, foot.plantX, foot.plantY, foot.plantZ, v1);
		hipBase(index, v0);
		float dx = v1.x - v0.x;
		float dy = v1.y - (v0.y + riseAtLastSolve);
		float dz = v1.z - v0.z;
		float reach = legReach[index];
		return dx * dx + dy * dy + dz * dz > reach * reach;
	}

	/** Forward room left to this leg from the middle of its travel. */
	private float roomFwd(int index) {
		return Math.max(plan.legs[index].totalLength * MIN_EXCURSION,
				excursionFwd[index] - strideCentre[index]);
	}

	/** Backward room left to this leg from the middle of its travel. */
	private float roomBack(int index) {
		return Math.max(plan.legs[index].totalLength * MIN_EXCURSION,
				excursionBack[index] + strideCentre[index]);
	}

	/**
	 * How far from its hip, measured on the ground plane alone, this leg can put a foot at the
	 * given height right now.
	 * <p>
	 * The three excursions bound each axis separately, which describes a box; a leg describes a
	 * sphere, and a foot at maximum forward <i>and</i> maximum sideways sits in a corner of the box
	 * outside it — measured at 1.28 leg lengths of horizontal offset on a sprawling insectoid while
	 * every individual axis was comfortably inside its own limit. This is the circle to trim to,
	 * and it is worked out from where the hip and the ground actually are rather than stored from
	 * the bind pose, because a creature crouched over broken ground has real horizontal room that a
	 * stance-height constant would refuse it.
	 */
	private float horizontalRoom(int index, Vector3f hipBase, float footY) {
		float dy = hipBase.y + riseAtLastSolve - footY;
		float span = legReach[index] * legReach[index] - dy * dy;
		return span > 0f
				? (float) Math.sqrt(span)
				: plan.legs[index].totalLength * MIN_EXCURSION;
	}

	/** Pulls a plant position back inside its leg's envelope, in place, in world space. */
	private void clampPlantToEnvelope(AnimationContext ctx, int index, LimbChain leg, FootState foot) {
		toModel(ctx, foot.plantX, foot.plantY, foot.plantZ, v1);
		// The box first: it is what keeps a stride symmetric about where the leg was grown and
		// stops feet wandering across the body's midline into each other.
		float centre = leg.restEffector.z + strideCentre[index];
		float dz = MathX.clamp(v1.z - centre, -roomBack(index), roomFwd(index));
		float dx = MathX.clamp(v1.x - leg.restEffector.x, -excursionSide[index], excursionSide[index]);
		v1.x = leg.restEffector.x + dx;
		v1.z = centre + dz;

		// Then the circle, about the hip that has to hold it — measured, so it follows the hip that
		// the spine bend and the body's lean have actually moved rather than the one the bind pose
		// says should be there.
		hipBase(index, v0);
		float limit = horizontalRoom(index, v0, v1.y);
		float hx = v1.x - v0.x, hz = v1.z - v0.z;
		float span = hx * hx + hz * hz;
		if (span > limit * limit && span > 1e-10f) {
			float k = limit / (float) Math.sqrt(span);
			v1.x = v0.x + hx * k;
			v1.z = v0.z + hz * k;
		}

		toWorld(ctx, v1, v0);
		foot.plantX = v0.x;
		foot.plantZ = v0.z;
	}

	/**
	 * Whether this leg may leave the ground right now for a corrective step.
	 * <p>
	 * A phase-driven step is never refused — the gait's own timing is what keeps a creature
	 * balanced, and second-guessing it here would desynchronise every limb. A corrective step is
	 * different: it can fire on any number of legs at once, and a creature that answers a lurch by
	 * lifting all of them is not walking, it is falling. One other weight-bearing leg is the
	 * minimum for anything with legs to spare; a biped has none to spare and is allowed its
	 * airborne moment, which is what running is.
	 */
	private boolean canLift(int index) {
		if (plan.legs.length <= 2) return true;
		int grounded = 0;
		for (int i = 0; i < feet.length; i++) {
			if (i != index && feet[i].grounded) grounded++;
		}
		return grounded >= 2;
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

		// ---- how high the legs can hold the body ---------------------------------------------
		//
		// The old answer was the mean height of the feet, which is where the body would sit if legs
		// were infinitely long. They are not, and the difference is the whole problem: a foot placed
		// out to the side or down a step needs the hip nearer to it, and a body that stays at the
		// average simply over-extends that leg and draws it as a rigid spar.
		//
		// So the body rides at the *lowest* height any weight-bearing leg demands. That is what
		// carrying weight means — the load settles until the supports can take it — and it makes
		// the creature crouch over broken ground and rise again on the flat, for free, out of the
		// same arithmetic that keeps the limbs inside their reach.
		// Heights come from the plants of the feet carrying weight, never from where the feet
		// happen to be. A swinging foot is lifted by design, and averaging that lift into the body's
		// height walks the whole creature upward in time with its own steps.
		float groundSum = 0f, allSum = 0f;
		int supported = 0;
		float maxRise = Float.MAX_VALUE;

		for (int i = 0; i < plan.legs.length; i++) {
			// Model-space height of this foot's plant relative to the entity's own feet level.
			float modelY = (float) (feet[i].plantY - ctx.y) / scale(ctx);
			allSum += modelY;
			if (!feet[i].grounded) continue;
			groundSum += modelY;
			supported++;

			// Hip with the body's vertical offset taken back out, so the constraint can be solved
			// for the offset rather than merely checked against it.
			hipBase(i, v0);
			toModel(ctx, feet[i].plantX, feet[i].plantY, feet[i].plantZ, v1);
			float dx = v0.x - v1.x, dy = v0.y - v1.y, dz = v0.z - v1.z;
			float slack = legReach[i] * legReach[i] - (dx * dx + dz * dz);
			// Horizontally out of reach already: no body height rescues this one, and the gait's
			// corrective step owns it. Leaving it out of the minimum stops one stranded foot from
			// dragging the whole animal onto its belly.
			if (slack <= 0f) continue;
			maxRise = Math.min(maxRise, (float) Math.sqrt(slack) - dy);
		}

		float footLevel = supported > 0
				? groundSum / supported
				: allSum / Math.max(1, plan.legs.length);
		float targetRise = footLevel - stanceDrop;
		if (supported > 0 && maxRise < Float.MAX_VALUE) {
			// Floored so a momentary bad plant cannot drive the torso through the floor.
			targetRise = Math.max(Math.min(targetRise, maxRise), footLevel - longestLeg * 0.5f);
		}

		// ---- which way it leans ---------------------------------------------------------------
		float targetPitch = terrainPitch(ctx);
		float targetRoll = 0f;
		if (fitSupportPlane(ctx)) {
			// planeSlopeZ / planeSlopeX are rise per unit of model-space depth and width.
			targetPitch = MathX.lerp((float) Math.atan(planeSlopeZ), targetPitch, TERRAIN_ANTICIPATION);
			targetRoll = (float) Math.atan(planeSlopeX);
		}
		// Both clamped, and roll for the first time. An unclamped roll is what tipped a creature
		// onto its side at a block edge: the old figure was an arctangent over a guessed span of
		// six tenths of the body's width, so a single foot up one block on a narrow animal asked
		// for sixty degrees of lean and got it.
		targetPitch = MathX.clamp(targetPitch, -MAX_BODY_TILT, MAX_BODY_TILT);
		targetRoll = MathX.clamp(targetRoll, -MAX_BODY_TILT, MAX_BODY_TILT);

		// Organic, smooth damping rates so the creature gracefully angles up at block edges instead of snapping.
		// The height target is a minimum over whichever feet bear weight this frame, so it steps
		// every time one lifts or lands — damping chases that faithfully and the animal judders.
		// The rate limit is what turns a step function back into a walk.
		float dampedRise = MathX.damp(bodyRise, targetRise, 5.0f, dt);
		float riseLimit = MAX_RISE_RATE * longestLeg * dt;
		bodyRise += MathX.clamp(dampedRise - bodyRise, -riseLimit, riseLimit);
		bodyPitch = rateLimited(bodyPitch, MathX.damp(bodyPitch, targetPitch, 4.5f, dt), dt);
		bodyRoll = rateLimited(bodyRoll, MathX.damp(bodyRoll, targetRoll, 4.5f, dt), dt);

		// Vertical bob at twice the step frequency — one dip per footfall of a diagonal pair.
		//
		// Faded out as cadence climbs, and that fade is most of what "the body jitters up and down"
		// was. The amplitude is small, but the frequency is twice the step rate, so a creature
		// trotting at four steps a second bobs at eight hertz — and eight hertz of anything does not
		// read as a gait, it reads as a vibration. A big animal at a walk keeps the whole bob; a
		// small one scurrying keeps none of it, which is also how it looks in life, where a visible
		// rise and fall belongs to slow heavy strides.
		float bobFade = 1f - MathX.clamp01((stepFrequency - BOB_FULL_RATE)
				/ (BOB_SILENT_RATE - BOB_FULL_RATE));
		float bobAmount = ctx.speed > IDLE_SPEED
				? plan.hipHeight * 0.035f * bobFade
				: plan.hipHeight * 0.008f;
		float bob = bobAmount * (float) Math.sin(gaitPhase * Math.PI * 4.0 + (ctx.speed > IDLE_SPEED ? 0.0 : ctx.time));

		// Sign note: a right-handed rotation about +X tips +Z (forward) toward -Y, i.e. nose down,
		// so a nose-up pitch needs the negated angle. About +Z it lifts +X, i.e. the right side.
		//
		// Only part of the pitch is applied here. Rotating the whole creature rigidly reads as a
		// plank tilting; the rest is distributed along the spine in updateSpine so the torso
		// actually arcs over the slope.
		// Behavioural offsets ride on the locomotion transform: a lunge drives the body forward
		// along its own facing, and crouching drops it toward the ground.
		riseAtLastSolve = bodyRise + bob - activityCrouch * plan.hipHeight;
		skeleton.rootTransform.identity()
				.translate(0f, riseAtLastSolve, activityLunge * plan.bodyLength * 0.28f)
				.rotateX(-bodyPitch * ROOT_PITCH_SHARE)
				.rotateZ(bodyRoll);
	}

	/**
	 * Least-squares plane through the feet that are carrying weight, in model space.
	 * <p>
	 * Replaces averaging the front feet against the rear and the left against the right over a span
	 * guessed from the body's bounding box. The guess was the fault: the arctangent's denominator
	 * had nothing to do with how far apart the feet in question actually were, so the same one-block
	 * height difference produced wildly different angles depending on the creature's proportions,
	 * and on a narrow one it produced an angle that laid the animal over on its side. A fit uses the
	 * real positions, needs no span, and works for two legs or eight without a special case.
	 * <p>
	 * Only weight-bearing feet are included. A foot in mid-swing is lifted by design, and feeding
	 * that lift into the body's attitude made every creature rock in time with its own footfalls.
	 *
	 * @return whether the fit succeeded; feet in a line or all at one point have no plane
	 */
	private boolean fitSupportPlane(AnimationContext ctx) {
		planeSlopeX = 0f;
		planeSlopeZ = 0f;
		float sx = 0f, sz = 0f, sy = 0f;
		int n = 0;
		for (int i = 0; i < plan.legs.length; i++) {
			if (!feet[i].grounded) continue;
			toModel(ctx, feet[i].currentX, feet[i].currentY, feet[i].currentZ, v1);
			planeX[n] = v1.x;
			planeZ[n] = v1.z;
			planeY[n] = v1.y;
			sx += v1.x;
			sz += v1.z;
			sy += v1.y;
			n++;
		}
		if (n < 2) return false;

		float mx = sx / n, mz = sz / n, my = sy / n;
		float sxx = 0f, szz = 0f, sxz = 0f, sxy = 0f, szy = 0f;
		for (int i = 0; i < n; i++) {
			float dx = planeX[i] - mx, dz = planeZ[i] - mz, dy = planeY[i] - my;
			sxx += dx * dx;
			szz += dz * dz;
			sxz += dx * dz;
			sxy += dx * dy;
			szy += dz * dy;
		}

		float det = sxx * szz - sxz * sxz;
		if (Math.abs(det) > 1e-5f) {
			planeSlopeX = (szz * sxy - sxz * szy) / det;
			planeSlopeZ = (sxx * szy - sxz * sxy) / det;
			return true;
		}
		// Degenerate: the feet are collinear, which is every biped and any creature with one leg
		// off the ground. Fit the one axis that still has spread and leave the other flat rather
		// than inventing a lean out of a near-zero denominator.
		if (szz > 1e-5f && szz >= sxx) {
			planeSlopeZ = szy / szz;
			return true;
		}
		if (sxx > 1e-5f) {
			planeSlopeX = sxy / sxx;
			return true;
		}
		return false;
	}

	/**
	 * Slope of the ground the body is walking along, from a regression over samples spread along
	 * its own length.
	 * <p>
	 * Two samples — one ahead, one behind — is what this used to be, and on terrain made of blocks
	 * two samples is a coin toss: the pair straddles a step, the measured slope jumps by a whole
	 * block at once, and the body snaps. Several samples fitted as a line turn the same step into
	 * the gentle grade it visually is.
	 */
	private float terrainPitch(AnimationContext ctx) {
		float half = Math.max(0.6f, plan.bodyLength * 0.5f) * scale(ctx);
		float sd = 0f, sy = 0f;
		for (int i = 0; i < TERRAIN_SAMPLES; i++) {
			float t = TERRAIN_SAMPLES == 1 ? 0f : (2f * i / (TERRAIN_SAMPLES - 1) - 1f);
			float d = t * half;
			planeX[i] = d;
			planeY[i] = groundAt(ctx, ctx.x + ctx.forwardX() * d, ctx.z + ctx.forwardZ() * d);
			sd += d;
			sy += planeY[i];
		}
		float md = sd / TERRAIN_SAMPLES, my = sy / TERRAIN_SAMPLES;
		float sdd = 0f, sdy = 0f;
		for (int i = 0; i < TERRAIN_SAMPLES; i++) {
			float dd = planeX[i] - md;
			sdd += dd * dd;
			sdy += dd * (planeY[i] - my);
		}
		return sdd < 1e-5f ? 0f : (float) Math.atan(sdy / sdd);
	}

	/** Caps how fast an attitude angle may change, so nothing snaps however sharp the ground is. */
	private static float rateLimited(float current, float proposed, float dt) {
		float step = MAX_TILT_RATE * dt;
		float delta = proposed - current;
		if (delta > step) return current + step;
		if (delta < -step) return current - step;
		return proposed;
	}

	/**
	 * Model-space hip position of a leg with the body's vertical offset removed — where the hip
	 * would sit if the body were at rise zero. Taken from the last completed solve, so it carries
	 * the spine bend and body attitude that actually moved it; the bind pose is the fallback for
	 * the first frame only.
	 */
	private void hipBase(int index, Vector3f dest) {
		if (hipsKnown) {
			dest.set(hipAtLastSolve[index]).sub(0f, riseAtLastSolve, 0f);
		} else {
			dest.set(plan.legs[index].origin);
		}
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
			boolean isDead = ctx.collapse >= 0.99f || ctx.activity == dev.jsz.primordia.entity.CreatureActivity.CARCASS;
			float wave = isDead ? 0f : amplitude * (float) Math.sin(gaitPhase * Math.PI * 2.0 - along * 2.2);
			float yaw = lateralBend / spineBones.length + wave;
			float breathe = isDead ? 0f : 0.012f * idle(ctx, ctx.time * 1.7 + along);
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
		boolean isDead = ctx.collapse >= 0.99f || ctx.activity == dev.jsz.primordia.entity.CreatureActivity.CARCASS;
		float yaw = isDead ? 0f : MathX.clamp(ctx.lookYaw + activityHeadYaw, -1.4f, 1.4f);
		float pitch = isDead ? 0.8f : MathX.clamp(ctx.lookPitch + activityHeadPitch, -0.9f, 1.5f);

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
			case GRAZE -> 0.18f + 0.14f * idle(ctx, ctx.time * 9.0);
			// Mouth shut through a charge or a stomp; an open jaw would read as a bite.
			case RAM, STOMP -> 0f;
			case CLAW, TAIL_SLAM -> 0.12f * strikeCurve(MathX.clamp01(ctx.activityProgress));
			default -> 0f;
		};

		boolean isDead = ctx.collapse >= 0.99f || ctx.activity == dev.jsz.primordia.entity.CreatureActivity.CARCASS;
		float ambient = isDead ? BodyPlan.JAW_REST_SLACK : (BodyPlan.JAW_REST_SLACK + (ctx.speed > IDLE_SPEED
				? 0.10f + 0.07f * idle(ctx, ctx.time * 6.5)
				: 0.02f + 0.02f * idle(ctx, ctx.time * 1.5)));

		// Snapping shut is far faster than opening — a jaw closes under muscle and gravity
		// together, and easing both ways at one rate makes every bite look languid.
		float goal = isDead ? BodyPlan.JAW_REST_SLACK : MathX.clamp01(target + ambient);
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

		boolean isDead = ctx.collapse >= 0.99f || ctx.activity == dev.jsz.primordia.entity.CreatureActivity.CARCASS;
		for (int i = 0; i < tailBones.length; i++) {
			int bone = tailBones[i];
			float along = (float) (i + 1) / tailBones.length;
			// Amplitude grows toward the tip, so the tail whips and counterbalances dynamically.
			float tailSine = isDead ? 0f : (0.14f * along * idle(ctx, ctx.time * 2.4 - along * 2.5));
			float yaw = tailLag * along * 0.55f
					+ (rollCounterbalance + stepSway) * along * 0.70f
					+ tailSine
					+ activityTailYaw * along * along;
			float pitch = isDead ? 0f : (0.10f * along * idle(ctx, ctx.time * 1.9 - along * 2.0)
					- (ctx.speed > IDLE_SPEED ? 0.12f * along : 0f));

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

		boolean isDead = ctx.collapse >= 0.99f || ctx.activity == dev.jsz.primordia.entity.CreatureActivity.CARCASS;
		if (isDead) {
			dev.jsz.primordia.client.config.PrimordiaConfig cfg = dev.jsz.primordia.client.config.PrimordiaConfig.get();
			float legLen = leg.totalLength;
			float sideSign = leg.side > 0 ? 1f : -1f;
			float splayX = Math.max(0.10f, Math.abs(leg.restEffector.x) * 0.95f + legLen * cfg.deadIkOffsetX * 0.50f);

			// Laying down on back: front legs extend forward (+Z), hind legs extend backward (-Z), uncrossed
			boolean isFrontLeg = leg.restEffector.z >= 0f;
			float zDir = isFrontLeg ? 1.0f : -1.0f;

			v1.x = sideSign * splayX;
			v1.y = jointScratch[0].y - legLen * (0.62f + cfg.deadIkOffsetY * 0.40f);
			v1.z = jointScratch[0].z + zDir * (legLen * 0.28f) + legLen * cfg.deadIkOffsetZ * 0.40f;
		} else {
			// Clamp foot target in model space below hip height so legs never get pinned upside down in the air
			float maxFootY = jointScratch[0].y - 0.05f;
			if (v1.y > maxFootY) {
				v1.y = maxFootY;
			}
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
				leg.bindPerp, IK_ITERATIONS);
		applyChain(leg.bones, n);
	}

	/**
	 * Arms are not weight-bearing, so they get a cheap counter-swing against the gait instead of a
	 * full IK solve — they have nothing to reach for.
	 */
	private void swingArm(AnimationContext ctx, LimbChain arm) {
		boolean isDead = ctx.collapse >= 0.99f || ctx.activity == dev.jsz.primordia.entity.CreatureActivity.CARCASS;
		if (isDead) return;

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

	/**
	 * One cycle of an idle oscillator, or nothing at all when the caller has asked for the gait
	 * alone. See {@link AnimationContext#ambient}.
	 */
	private static float idle(AnimationContext ctx, double phase) {
		return ctx.ambient ? (float) Math.sin(phase) : 0f;
	}

	/** Growth scale, never zero. Model units times this are world units. */
	private static float scale(AnimationContext ctx) {
		return ctx.scale > 1e-3f ? ctx.scale : 1f;
	}

	/**
	 * World-space position this leg's foot works about — the middle of its fore/aft travel, not the
	 * point the limb was grown at.
	 * <p>
	 * Standing and stepping share this one neutral point deliberately. If the stride were centred
	 * here while the stance settled back to the bind position, a creature would shuffle its feet
	 * every time it started and stopped walking.
	 */
	private void restFootWorld(AnimationContext ctx, int index, LimbChain leg, Vector3f dest) {
		v2.set(leg.restEffector);
		v2.z += strideCentre[index];
		toWorld(ctx, v2, dest);
	}

	/** Model space to world space: scale to the creature's size, rotate by body yaw, translate. */
	private void toWorld(AnimationContext ctx, Vector3f model, Vector3f dest) {
		float k = scale(ctx);
		float c = (float) Math.cos(ctx.bodyYaw);
		float s = (float) Math.sin(ctx.bodyYaw);
		float mx = model.x * k, my = model.y * k, mz = model.z * k;
		dest.set(
				(float) (ctx.x + mx * c - mz * s),
				(float) (ctx.y + my),
				(float) (ctx.z + mx * s + mz * c));
	}

	/** World space to model space: the exact inverse of {@link #toWorld}. */
	private void toModel(AnimationContext ctx, double wx, double wy, double wz, Vector3f dest) {
		float k = 1f / scale(ctx);
		float c = (float) Math.cos(ctx.bodyYaw);
		float s = (float) Math.sin(ctx.bodyYaw);
		float dx = (float) (wx - ctx.x);
		float dy = (float) (wy - ctx.y);
		float dz = (float) (wz - ctx.z);
		dest.set((dx * c + dz * s) * k, dy * k, (-dx * s + dz * c) * k);
	}

	/**
	 * Ground height under a point. If the target sits over a hole, pit, or cliff edge,
	 * searches adjacent ground candidates so feet land on nearby solid ground instead of floating over air.
	 * <p>
	 * How far down counts as reachable is the creature's own leg, not a constant. The fixed 1.25
	 * blocks this used to allow was under half of a large animal's reach: a sauropod stepping off a
	 * two-block ledge had the ground beneath its foot rejected as out of range and fell back to its
	 * own foot level, which put the foot in mid-air over the drop with the leg pointing at it.
	 */
	private float groundAt(AnimationContext ctx, double x, double z) {
		float limit = footDropLimit(ctx);
		float low = (float) (ctx.y - limit);
		float high = (float) (ctx.y + limit);

		float y = ctx.ground.groundY(x, z, ctx.y);
		if (!Float.isNaN(y)) {
			// A real surface. Reach toward it as far as the limb goes and no further: a foot at
			// the lip of a drop it cannot follow stops where the leg runs out, which is honest.
			//
			// This used to fall through to the neighbour search when the surface was out of range,
			// and that is what put creatures on top of lakes: a wading column answers with a height
			// just under the water, a short-legged animal cannot reach it, and the search then found
			// the bank it was standing on and lifted the foot to it. A column with an answer gets
			// that answer — see GroundProbe's contract, which has said so all along.
			return MathX.clamp(y, low, high);
		}

		// No standable surface in this column at all: a hole, a pit, or open air past a cliff edge.
		// Only now is looking next door the right thing to do.
		float probe = 0.35f * scale(ctx);
		double[] offsetsX = { 0.0, -probe, probe, 0.0 };
		double[] offsetsZ = { -probe, 0.0, 0.0, probe };
		for (int i = 0; i < 4; i++) {
			float candidate = ctx.ground.groundY(x + offsetsX[i], z + offsetsZ[i], ctx.y);
			if (!Float.isNaN(candidate)) {
				return MathX.clamp(candidate, low, high);
			}
		}
		return (float) ctx.y;
	}

	/**
	 * How far from its own feet level a creature may plant a foot, in world units.
	 * <p>
	 * Its own leg, and nothing else. The flat 1.25 blocks this used to be was wrong at both ends of
	 * the size range: under half a large animal's reach, so a sauropod stepping off a two-block
	 * ledge had the ground under its foot refused as out of range; and six times a small one's, so
	 * an insectoid beside a one-block step was handed a target a body-length below itself and drew
	 * the leg as a spike pointing down at it.
	 */
	private float footDropLimit(AnimationContext ctx) {
		// The floor is a wading depth rather than a step: shallow water gives a surface a little
		// under the creature's own level and every animal can put a foot in it, whatever its size.
		return Math.max(0.4f, longestLeg * scale(ctx) * 1.15f);
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
