package dev.jsz.primordia.anim;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.LimbChain;
import dev.jsz.primordia.mesh.LodTier;
import org.joml.Vector3f;

/**
 * Walks a creature across {@link BlockTerrain} and measures what its legs actually did.
 * <p>
 * The point of this rig is that walk-cycle faults are not visible in the animator's inputs. Every
 * number the gait controller is handed stays in range while the legs go bolt straight, so the only
 * way to find the fault is to read the solved skeleton — where the bones ended up — and compare it
 * against where the gait said the feet were meant to be. That gap is the whole diagnosis: a leg that
 * cannot reach its target is a leg being drawn at full extension, and full extension is the
 * "pinned straight" the player sees.
 * <p>
 * Nothing here recomputes what the animator should have produced (see {@code MD/PITFALLS.md} §6). It
 * reads the posed skeleton and the terrain, both of which are artefacts.
 */
public final class GaitRig {

	/** Everything one traverse measured. All lengths are in leg lengths unless stated. */
	public static final class Result {
		public String label = "";
		public float hipHeight;
		public float legLength;
		public float speed;

		/**
		 * Fraction of leg-frames where the gait asked for a target beyond the limb's own length.
		 * <p>
		 * This is the number that matters, and it is not the same as the limb coming out straight.
		 * The solver absorbs over-reach by stretching its working copy of the bone lengths, so an
		 * impossible target still solves with a bend in it — the chain just ends up short, pointing
		 * at somewhere the foot never gets to. On screen that is a rigid leg held out at an angle
		 * with its foot off the ground, which is what "pinned straight" describes; measured as
		 * straightness it looks fine, which is why it survived this long.
		 */
		public float overreachFrac;
		/**
		 * The same, counted only over feet the gait believes are carrying weight.
		 * <p>
		 * This is the one that shows. A swinging foot that falls short of its target is a slightly
		 * shorter step and nothing else; a <i>planted</i> foot the leg cannot reach is the limb held
		 * out as a rigid spar with its toe off the ground. Counting both together rewards a creature
		 * for barely moving its legs, which is how a shivering gait scores well.
		 */
		public float overreachStance;
		/** Fraction of leg-frames whose solved limb has no visible bend left at all. */
		public float pinnedFrac;
		/** Worst hip-to-target distance the gait demanded, as a multiple of the leg's own length. */
		public float demandMax;
		/** Mean of the same, over every leg-frame. */
		public float demandMean;
		/** Worst distance between where the foot was asked to be and where the toe ended up. */
		public float reachMissMax;
		/** Mean of the same. */
		public float reachMissMean;
		/** Worst horizontal drift of a toe during a single stance phase. */
		public float slideMax;
		/** Worst height of a weight-bearing toe above the block beneath it. */
		public float footAirMax;
		/** Worst depth of a weight-bearing toe below that block. */
		public float footSunkMax;
		/** Worst body attitude, degrees. */
		public float pitchMaxDeg, rollMaxDeg;
		/** Worst rate of attitude change, degrees per second — how violently the body snaps. */
		public float tiltRateMaxDeg;
		/**
		 * Steps started per leg per second. The eye reads cadence long before it reads reach: past
		 * about three steps a second a leg stops looking like it is walking and starts looking like
		 * it is vibrating, however correct each individual plant is.
		 */
		public float stepRate;
		/**
		 * Direction changes per second in the torso's vertical motion.
		 * <p>
		 * A walk bobs, so this is never zero — one dip per footfall is the whole point. It is the
		 * figure well above the step rate that means the body is not bobbing but chattering, which is
		 * what a target computed as a minimum over a changing set of feet produces.
		 */
		public float riseReversals;
		/**
		 * Fraction of solved knee-frames sitting on the opposite side of the limb from the one it was
		 * grown on.
		 * <p>
		 * The bend side is the whole reason {@code LimbChain.bendSigns} exists, so any figure above
		 * zero is the solver failing at its own stated job. On screen it is a knee that points
		 * backwards, and because it depends on where the foot happens to be it shows on some legs and
		 * not others, and comes and goes within a stride.
		 */
		public float kneeFlipFrac;
		/** Worst angle, degrees, between the runtime bend plane and the one the limb was grown in. */
		public float perpSwingMaxDeg;
		/** RMS frame-to-frame vertical acceleration of the torso, in leg lengths per second squared. */
		public float riseJitter;
		/**
		 * Mean fore/aft position of a weight-bearing toe, relative to where that leg was grown,
		 * in leg lengths. Negative means the foot spends its stance behind the hip.
		 * <p>
		 * A stride is symmetric when the foot lands as far ahead of the hip as it leaves behind it,
		 * so the honest figure here is near zero. It is the one number that says whether a walk
		 * reads as an animal carrying itself or as one being dragged along by the shoulders, and
		 * nothing else in this table measures it: reach, cadence and contact can all be perfect
		 * while every leg trails.
		 */
		public float footBiasMean;
		/** Frames sampled. */
		public int frames;

		public String row() {
			return String.format(
					"%-36s v%4.1f | over %5.1f%%/%5.1f%% pin %5.1f%% demand %4.2f/%4.2f miss %4.2f/%4.2f "
							+ "slide %4.2f air %4.2f sunk %4.2f | pitch %5.1f roll %5.1f tilt %6.1f/s"
							+ " | step %4.1f/s rev %4.1f/s jit %6.1f | knee %5.1f%% perp %5.1f bias %+5.2f",
					label, speed,
					overreachFrac * 100f, overreachStance * 100f, pinnedFrac * 100f, demandMean, demandMax,
					reachMissMean, reachMissMax,
					slideMax, footAirMax, footSunkMax, pitchMaxDeg, rollMaxDeg, tiltRateMaxDeg,
					stepRate, riseReversals, riseJitter, kneeFlipFrac * 100f, perpSwingMaxDeg,
					footBiasMean);
		}

		public static String header() {
			return String.format("%-36s %4s | %-11s %-10s %-16s %-15s %-10s %-9s %-9s | %-32s | %s",
					"specimen / terrain", "v",
					"overreach", "pinned", "demand mean/max", "reachmiss m/max",
					"slide", "air", "sunk", "attitude", "cadence / bounce");
		}
	}

	/**
	 * Hip-to-toe distance, as a fraction of the leg's own length, past which the limb has no
	 * visible bend left. Two per cent of slack on a two-bone chain is a knee offset of fourteen per
	 * cent of the leg — the least deviation that still reads as a joint rather than a stick.
	 */
	private static final float PINNED = 0.98f;
	/** Simulation rate. Higher than a tick so per-frame damping and pops are actually resolved. */
	private static final int FPS = 60;

	private GaitRig() {
	}

	/**
	 * @param turnRadius blocks; {@code 0} walks dead straight, otherwise the creature circles, which
	 *                   is what every wandering animal in the world actually does
	 */
	public static Result walk(BodyPlan plan, BlockTerrain terrain, String label,
	                          float speed, float turnRadius, float seconds) {
		CreatureAnimator animator = new CreatureAnimator(plan);
		AnimationContext ctx = new AnimationContext();
		ctx.tier = LodTier.NEAR;
		ctx.ground = terrain;

		Result r = new Result();
		r.label = label;
		r.hipHeight = plan.hipHeight;
		r.speed = speed;
		float legLength = 0f;
		for (LimbChain leg : plan.legs) legLength = Math.max(legLength, leg.totalLength);
		r.legLength = legLength;

		int steps = (int) (seconds * FPS);
		float dt = 1f / FPS;

		// Start clear of the origin so the interesting features (a ledge at z = 0) are walked into
		// rather than started on top of.
		double x = 0.5, z = -12.5;
		double y = terrain.surfaceAt(x, z);
		float heading = 0f;

		Vector3f hip = new Vector3f();
		Vector3f knee = new Vector3f();
		Vector3f kneeAxis = new Vector3f();
		Vector3f kneeDelta = new Vector3f();
		Vector3f livePerp = new Vector3f();
		Vector3f toe = new Vector3f();
		org.joml.Vector3d target = new org.joml.Vector3d();

		int legCount = plan.legs.length;

		// The plane each limb was grown in. bendSigns are recorded against this, so it is the only
		// frame in which "the correct side" means anything. The solver rebuilds its own from the live
		// hip-to-target axis every frame, which is the thing under test.
		Vector3f[] bindPerp = new Vector3f[legCount];
		for (int bi = 0; bi < legCount; bi++) {
			LimbChain bl = plan.legs[bi];
			Vector3f ax = new Vector3f(bl.restEffector).sub(bl.origin);
			Vector3f pp = new Vector3f(bl.poleDirection);
			if (ax.lengthSquared() > 1e-10f) {
				ax.normalize();
				pp.fma(-pp.dot(ax), ax);
			}
			if (pp.lengthSquared() > 1e-8f) pp.normalize();
			bindPerp[bi] = pp;
		}

		double[] stanceOriginX = new double[legCount];
		double[] stanceOriginZ = new double[legCount];
		boolean[] wasGrounded = new boolean[legCount];
		float prevPitch = 0f, prevRoll = 0f;

		double demandSum = 0, missSum = 0;
		int samples = 0, pinned = 0, overreached = 0;
		int stanceSamples = 0, stanceOverreached = 0;
		double biasSum = 0;
		int biasSamples = 0;
		int kneeSamples = 0, kneeFlips = 0;

		// Cadence and bounce. A step is counted on the grounded->airborne edge, which is the same
		// edge the animator itself treats as the start of a swing.
		int stepsStarted = 0, riseFlips = 0, riseSamples = 0;
		double riseAccelSq = 0;
		float prevRise = 0f, prevRiseVel = 0f;
		boolean riseSeeded = false;

		for (int step = 0; step < steps; step++) {
			float time = step * dt;

			// ---- move the body the way the entity would ----------------------------------
			if (turnRadius > 0.01f) {
				heading += (speed / turnRadius) * dt;
			}
			float fx = -(float) Math.sin(heading);
			float fz = (float) Math.cos(heading);
			x += fx * speed * dt;
			z += fz * speed * dt;

			// Vanilla stands an entity on the highest block under its footprint and steps up onto
			// ledges instantly; it falls onto drops under gravity rather than snapping down.
			double support = terrain.supportUnder(x, z, Math.max(0.3, plan.width() * 0.4));
			if (support > y) {
				y = support;
			} else if (support < y) {
				y = Math.max(support, y - 12.0 * dt);
			}

			float previousYaw = ctx.bodyYaw;
			ctx.x = x;
			ctx.y = y;
			ctx.z = z;
			ctx.bodyYaw = heading;
			ctx.speed = speed;
			ctx.time = time;
			ctx.turnRate = step == 0 ? 0f : (heading - previousYaw) / dt;
			animator.update(ctx);

			// The first half-second is the feet finding their initial plants, which is not walking.
			if (step < FPS / 2) {
				for (int i = 0; i < legCount; i++) wasGrounded[i] = false;
				prevPitch = animator.bodyPitch();
				prevRoll = animator.bodyRoll();
				prevRise = animator.bodyRise();
				prevRiseVel = 0f;
				riseSeeded = false;
				continue;
			}
			r.frames++;

			// ---- attitude ------------------------------------------------------------------
			float pitch = animator.bodyPitch(), roll = animator.bodyRoll();
			r.pitchMaxDeg = Math.max(r.pitchMaxDeg, Math.abs((float) Math.toDegrees(pitch)));
			r.rollMaxDeg = Math.max(r.rollMaxDeg, Math.abs((float) Math.toDegrees(roll)));
			float tiltRate = (float) Math.toDegrees(
					Math.hypot(pitch - prevPitch, roll - prevRoll)) / dt;
			r.tiltRateMaxDeg = Math.max(r.tiltRateMaxDeg, tiltRate);
			prevPitch = pitch;
			prevRoll = roll;

			// ---- vertical chatter ----------------------------------------------------------
			// In leg lengths, so a big animal and a small one are held to the same standard of
			// smoothness relative to their own size.
			float rise = animator.bodyRise() / Math.max(1e-4f, legLength);
			float riseVel = (rise - prevRise) / dt;
			if (riseSeeded) {
				float accel = (riseVel - prevRiseVel) / dt;
				riseAccelSq += (double) accel * accel;
				riseSamples++;
				if (riseVel * prevRiseVel < 0f) riseFlips++;
			}
			prevRise = rise;
			prevRiseVel = riseVel;
			riseSeeded = true;

			// ---- legs ----------------------------------------------------------------------
			for (int i = 0; i < legCount; i++) {
				LimbChain leg = plan.legs[i];
				int last = leg.bones[leg.bones.length - 1];
				animator.skeleton().boneHead(leg.bones[0], hip);
				animator.skeleton().boneTail(last, toe);
				if (!animator.getFootWorldPosition(i, target)) continue;

				// Model space to world, the animator's own convention.
				float c = (float) Math.cos(heading), s = (float) Math.sin(heading);
				double toeX = x + toe.x * c - toe.z * s;
				double toeY = y + toe.y;
				double toeZ = z + toe.x * s + toe.z * c;

				// How far the gait asked the leg to stretch. Convert the target into model space
				// rather than the hip out of it: the hip is already in the frame the bone lengths
				// are measured in, and that is what the demand has to be compared against.
				float tx = (float) ((target.x - x) * c + (target.z - z) * s);
				float ty = (float) (target.y - y);
				float tz = (float) (-(target.x - x) * s + (target.z - z) * c);
				float demand = (float) Math.sqrt(sq(tx - hip.x) + sq(ty - hip.y) + sq(tz - hip.z))
						/ Math.max(1e-4f, leg.totalLength);

				// Stance is where an unreachable target actually shows on screen.
				boolean bearing = animator.isFootGrounded(i);
				if (bearing) {
					stanceSamples++;
					if (demand > 1f) stanceOverreached++;
					// Where the foot sits fore or aft, against where the body plan grew it rather
					// than against the hip: a leg grown with its foot ahead of its own shoulder is
					// not trailing merely for standing where it was built.
					biasSum += ((toe.z - hip.z) - (leg.restEffector.z - leg.origin.z))
							/ Math.max(1e-4f, leg.totalLength);
					biasSamples++;
				}

				// ---- which side the knee came out on ----------------------------------------
				// Measured against the limb's own bind plane, not against whatever plane the solver
				// used this frame. Those are the same thing only while the foot is near where it was
				// grown, and the gap between them is the fault being looked for.
				if (leg.bendSigns.length > 0 && bindPerp[i].lengthSquared() > 0.5f) {
					kneeAxis.set(toe).sub(hip);
					if (kneeAxis.lengthSquared() > 1e-10f) {
						kneeAxis.normalize();
						// The plane the solver would have built this frame, for comparison.
						livePerp.set(leg.poleDirection);
						livePerp.fma(-livePerp.dot(kneeAxis), kneeAxis);
						if (livePerp.lengthSquared() > 1e-8f) {
							livePerp.normalize();
							float swing = (float) Math.toDegrees(
									Math.acos(Math.max(-1f, Math.min(1f, livePerp.dot(bindPerp[i])))));
							r.perpSwingMaxDeg = Math.max(r.perpSwingMaxDeg, swing);
						}
						for (int j = 1; j < leg.bones.length; j++) {
							float want = leg.bendSigns[j - 1];
							if (want == 0f) continue;
							animator.skeleton().boneHead(leg.bones[j], knee);
							kneeDelta.set(knee).sub(hip);
							kneeDelta.fma(-kneeDelta.dot(kneeAxis), kneeAxis);
							float along = kneeDelta.dot(bindPerp[i]);
							// A joint sitting essentially on the axis has no side; ignore it rather than
							// counting numerical noise as an inversion.
							if (Math.abs(along) < leg.totalLength * 0.02f) continue;
							kneeSamples++;
							if (along * want < 0f) kneeFlips++;
						}
					}
				}

				float straightness = hip.distance(toe) / Math.max(1e-4f, leg.totalLength);
				float miss = (float) Math.sqrt(sq(toeX - target.x) + sq(toeY - target.y)
						+ sq(toeZ - target.z)) / Math.max(1e-4f, leg.totalLength);

				demandSum += demand;
				missSum += miss;
				samples++;
				if (straightness > PINNED) pinned++;
				if (demand > 1f) overreached++;
				r.demandMax = Math.max(r.demandMax, demand);
				r.reachMissMax = Math.max(r.reachMissMax, miss);

				// ---- contact quality, only while the foot is meant to be carrying weight -----
				// Asked of the gait rather than inferred from the toe's height. A foot the gait
				// believes is planted while it hangs in mid-air is precisely the fault being
				// measured, and inferring stance from height would quietly exclude it.
				boolean grounded = bearing;
				if (grounded) {
					float surface = terrain.surfaceAt(toeX, toeZ);
					float above = (float) (toeY - surface);
					// Only against ground the creature could have reached. A toe out over the lip of
					// a cliff is standing on the ledge it is beside, and measuring it against the
					// floor three blocks down says nothing about the gait — it is the one place
					// where a foot in mid-air is the correct answer.
					boolean overVoid = Math.abs(surface - y) > leg.totalLength * 1.2f;
					if (!overVoid) {
						if (above > 0) r.footAirMax = Math.max(r.footAirMax, above / leg.totalLength);
						else r.footSunkMax = Math.max(r.footSunkMax, -above / leg.totalLength);
					}

					if (wasGrounded[i]) {
						float drift = (float) Math.sqrt(sq(toeX - stanceOriginX[i]) + sq(toeZ - stanceOriginZ[i]));
						r.slideMax = Math.max(r.slideMax, drift / leg.totalLength);
					} else {
						stanceOriginX[i] = toeX;
						stanceOriginZ[i] = toeZ;
					}
				}
				if (wasGrounded[i] && !grounded) stepsStarted++;
				wasGrounded[i] = grounded;
			}
		}

		float measured = r.frames * dt;
		if (measured > 0f && legCount > 0) {
			r.stepRate = stepsStarted / measured / legCount;
			r.riseReversals = riseFlips / measured;
		}
		if (riseSamples > 0) {
			r.riseJitter = (float) Math.sqrt(riseAccelSq / riseSamples);
		}
		if (samples > 0) {
			r.demandMean = (float) (demandSum / samples);
			r.reachMissMean = (float) (missSum / samples);
			r.pinnedFrac = (float) pinned / samples;
			r.overreachFrac = (float) overreached / samples;
		}
		if (biasSamples > 0) {
			r.footBiasMean = (float) (biasSum / biasSamples);
		}
		if (kneeSamples > 0) {
			r.kneeFlipFrac = (float) kneeFlips / kneeSamples;
		}
		if (stanceSamples > 0) {
			r.overreachStance = (float) stanceOverreached / stanceSamples;
		}
		return r;
	}

	private static double sq(double v) {
		return v * v;
	}
}
