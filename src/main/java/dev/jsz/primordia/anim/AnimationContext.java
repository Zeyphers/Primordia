package dev.jsz.primordia.anim;

/**
 * Everything {@link CreatureAnimator} needs to know about the world this frame, packaged so the
 * animator has no dependency on Minecraft entity classes.
 * <p>
 * Reused and refilled each frame rather than reallocated.
 */
public final class AnimationContext {
	/** Interpolated entity position, world space. The origin sits at the creature's feet. */
	public double x, y, z;
	/** Body facing, radians. Minecraft convention: 0 faces +Z, increasing turns toward -X. */
	public float bodyYaw;
	/** Head yaw relative to the body, radians. */
	public float lookYaw;
	/** Head pitch, radians. Positive looks down. */
	public float lookPitch;
	/** Horizontal ground speed in blocks per second. */
	public float speed;
	/** Body turn rate in radians per second; drives the spine's lateral bend. */
	public float turnRate;
	/** World time in seconds, interpolated. Drives idle motion and is the animator's clock. */
	public float time;
	/** True while the creature is off the ground. */
	public boolean airborne;
	/** True while the creature is in water; suppresses ground contact entirely. */
	public boolean swimming;
	/** LOD tier from {@link dev.jsz.primordia.mesh.LodTier}. */
	public int tier;

	/** What the creature is doing, driving the behavioural animation layer. */
	public dev.jsz.primordia.entity.CreatureActivity activity =
			dev.jsz.primordia.entity.CreatureActivity.IDLE;
	/** Progress through a timed activity, 0 to 1. Meaningless for ambient states. */
	public float activityProgress;

	public GroundProbe ground = GroundProbe.flat(0f);

	/** Unit forward vector X component, derived from {@link #bodyYaw}. */
	public float forwardX() {
		return -(float) Math.sin(bodyYaw);
	}

	/** Unit forward vector Z component, derived from {@link #bodyYaw}. */
	public float forwardZ() {
		return (float) Math.cos(bodyYaw);
	}
}
