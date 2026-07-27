package dev.jsz.primordia.util;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Small math helpers shared by the genome, skeleton, SDF and animation layers.
 * Everything here is allocation-light and safe to call from bake threads.
 */
public final class MathX {
	public static final float EPS = 1.0e-6f;
	public static final Vector3f Y_AXIS = new Vector3f(0f, 1f, 0f);

	private MathX() {
	}

	public static float clamp(float v, float lo, float hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}

	public static float clamp01(float v) {
		return clamp(v, 0f, 1f);
	}

	public static int clamp(int v, int lo, int hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}

	public static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	/** Maps t in [0,1] onto [lo,hi]. */
	public static float remap01(float t, float lo, float hi) {
		return lo + (hi - lo) * clamp01(t);
	}

	/** Maps v from [inLo,inHi] onto [outLo,outHi], clamped. */
	public static float remap(float v, float inLo, float inHi, float outLo, float outHi) {
		if (Math.abs(inHi - inLo) < EPS) return outLo;
		return remap01((v - inLo) / (inHi - inLo), outLo, outHi);
	}

	public static float smoothstep(float t) {
		t = clamp01(t);
		return t * t * (3f - 2f * t);
	}

	/** Frame-rate independent exponential approach. {@code rate} is the fraction closed per second. */
	public static float damp(float current, float target, float rate, float dt) {
		return lerp(target, current, (float) Math.exp(-rate * dt));
	}

	/** Shortest-arc wrap of an angle difference into [-PI, PI]. */
	public static float wrapRadians(float a) {
		a %= (float) (Math.PI * 2.0);
		if (a >= Math.PI) a -= (float) (Math.PI * 2.0);
		if (a < -Math.PI) a += (float) (Math.PI * 2.0);
		return a;
	}

	/**
	 * Quaternion rotating unit vector {@code from} onto unit vector {@code to}.
	 * Handles the antiparallel singularity by picking an arbitrary perpendicular axis.
	 */
	public static Quaternionf rotationBetween(Vector3f from, Vector3f to, Quaternionf dest) {
		float d = from.dot(to);
		if (d > 1f - 1e-6f) {
			return dest.identity();
		}
		if (d < -1f + 1e-6f) {
			// 180 degrees: any perpendicular axis works.
			Vector3f axis = new Vector3f(1f, 0f, 0f).cross(from);
			if (axis.lengthSquared() < 1e-8f) {
				axis.set(0f, 1f, 0f).cross(from);
			}
			axis.normalize();
			return dest.fromAxisAngleRad(axis, (float) Math.PI);
		}
		Vector3f axis = new Vector3f(from).cross(to);
		float s = (float) Math.sqrt((1f + d) * 2f);
		dest.set(axis.x / s, axis.y / s, axis.z / s, s * 0.5f);
		return dest.normalize();
	}

	/** Smooth minimum (polynomial). {@code k} controls the blend radius; k -> 0 becomes a hard min. */
	public static float smoothMin(float a, float b, float k) {
		if (k <= EPS) return Math.min(a, b);
		float h = clamp01(0.5f + 0.5f * (b - a) / k);
		return lerp(b, a, h) - k * h * (1f - h);
	}

	/** Smooth maximum, the dual of {@link #smoothMin}. Used for SDF subtraction. */
	public static float smoothMax(float a, float b, float k) {
		return -smoothMin(-a, -b, k);
	}

	/** Squared distance from point p to the segment ab. */
	public static float distSqPointSegment(float px, float py, float pz,
	                                       float ax, float ay, float az,
	                                       float bx, float by, float bz) {
		float abx = bx - ax, aby = by - ay, abz = bz - az;
		float apx = px - ax, apy = py - ay, apz = pz - az;
		float denom = abx * abx + aby * aby + abz * abz;
		float t = denom < EPS ? 0f : clamp01((apx * abx + apy * aby + apz * abz) / denom);
		float dx = apx - abx * t, dy = apy - aby * t, dz = apz - abz * t;
		return dx * dx + dy * dy + dz * dz;
	}

	/** Parameter t in [0,1] of the closest point on segment ab to p. */
	public static float projectOntoSegment(float px, float py, float pz,
	                                       float ax, float ay, float az,
	                                       float bx, float by, float bz) {
		float abx = bx - ax, aby = by - ay, abz = bz - az;
		float denom = abx * abx + aby * aby + abz * abz;
		if (denom < EPS) return 0f;
		return clamp01(((px - ax) * abx + (py - ay) * aby + (pz - az) * abz) / denom);
	}
}
