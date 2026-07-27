package dev.jsz.primordia;

import dev.jsz.primordia.anim.Fabrik;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class FabrikTest {

	private static Vector3f[] chain(int segments, float length) {
		Vector3f[] joints = new Vector3f[segments + 1];
		for (int i = 0; i <= segments; i++) {
			// Start hanging straight down, which is roughly how a limb rests.
			joints[i] = new Vector3f(0f, -length * i, 0f);
		}
		return joints;
	}

	private static float[] lengths(int segments, float length) {
		float[] out = new float[segments];
		java.util.Arrays.fill(out, length);
		return out;
	}

	@Test
	void reachesTargetsWithinRange() {
		Fabrik solver = new Fabrik();
		Random random = new Random(1);

		for (int segments = 2; segments <= 4; segments++) {
			float boneLength = 0.5f;
			float reach = segments * boneLength;

			for (int trial = 0; trial < 300; trial++) {
				Vector3f[] joints = chain(segments, boneLength);
				Vector3f root = new Vector3f(joints[0]);

				// A target comfortably inside the reachable sphere.
				Vector3f target = new Vector3f(
						random.nextFloat() * 2f - 1f,
						random.nextFloat() * 2f - 1f,
						random.nextFloat() * 2f - 1f);
				if (target.lengthSquared() < 1e-4f) continue;
				target.normalize().mul(reach * (0.15f + random.nextFloat() * 0.75f)).add(root);

				solver.solve(joints, lengths(segments, boneLength), segments, target, null, 24);

				assertEquals(0f, joints[segments].distance(target), 0.02f,
						segments + "-segment chain failed to reach a reachable target");
			}
		}
	}

	@Test
	void preservesBoneLengths() {
		Fabrik solver = new Fabrik();
		Random random = new Random(2);
		int segments = 3;
		float boneLength = 0.4f;

		for (int trial = 0; trial < 300; trial++) {
			Vector3f[] joints = chain(segments, boneLength);
			// Deliberately mix reachable and out-of-reach targets.
			Vector3f target = new Vector3f(
					(random.nextFloat() - 0.5f) * 6f,
					(random.nextFloat() - 0.5f) * 6f,
					(random.nextFloat() - 0.5f) * 6f);

			solver.solve(joints, lengths(segments, boneLength), segments, target,
					new Vector3f(0f, 0f, 1f), 16);

			for (int i = 0; i < segments; i++) {
				assertEquals(boneLength, joints[i].distance(joints[i + 1]), 1e-2f,
						"segment " + i + " changed length — the limb stretched");
			}
		}
	}

	@Test
	void keepsTheRootPinned() {
		Fabrik solver = new Fabrik();
		Vector3f[] joints = chain(3, 0.5f);
		Vector3f root = new Vector3f(joints[0]);

		solver.solve(joints, lengths(3, 0.5f), 3, new Vector3f(10f, 10f, 10f), null, 16);
		assertEquals(0f, joints[0].distance(root), 1e-4f, "the hip moved");
	}

	@Test
	void unreachableTargetsStraightenTowardTheTarget() {
		Fabrik solver = new Fabrik();
		Vector3f[] joints = chain(3, 0.5f);
		Vector3f target = new Vector3f(0f, 0f, 20f);

		solver.solve(joints, lengths(3, 0.5f), 3, target, null, 16);

		Vector3f toTarget = new Vector3f(target).sub(joints[0]).normalize();
		for (int i = 0; i < 3; i++) {
			Vector3f segment = new Vector3f(joints[i + 1]).sub(joints[i]).normalize();
			assertEquals(1f, segment.dot(toTarget), 1e-3f, "chain did not straighten out of reach");
		}
	}

	@Test
	void poleConstraintPicksAConsistentBendDirection() {
		Fabrik solver = new Fabrik();
		float boneLength = 0.5f;
		Vector3f target = new Vector3f(0f, -1.2f, 0f);

		// Same target, opposite poles: the knee must end up on opposite sides.
		Vector3f[] forward = chain(3, boneLength);
		solver.solve(forward, lengths(3, boneLength), 3, target, new Vector3f(0f, 0f, 1f), 24);

		Vector3f[] backward = chain(3, boneLength);
		solver.solve(backward, lengths(3, boneLength), 3, target, new Vector3f(0f, 0f, -1f), 24);

		assertTrue(forward[1].z > 0f, "knee did not bend toward the +Z pole (z=" + forward[1].z + ")");
		assertTrue(backward[1].z < 0f, "knee did not bend toward the -Z pole (z=" + backward[1].z + ")");
	}

	@Test
	void repeatedSolvesAreStable() {
		// Frame-to-frame popping is the classic FABRIK failure. Re-solving an unchanged pose must
		// not move a joint further than the solver's own convergence tolerance — asking for
		// bit-identical results would be demanding more precision than the solver ever promises,
		// since the first call stops the moment it is within tolerance and the second refines on.
		Fabrik solver = new Fabrik();
		Vector3f[] joints = chain(3, 0.5f);
		Vector3f target = new Vector3f(0.4f, -1.0f, 0.3f);
		Vector3f pole = new Vector3f(0f, 0f, 1f);

		solver.solve(joints, lengths(3, 0.5f), 3, target, pole, 24);
		Vector3f[] first = snapshot(joints);

		solver.solve(joints, lengths(3, 0.5f), 3, target, pole, 24);
		Vector3f[] second = snapshot(joints);
		float driftA = maxDrift(first, second);

		solver.solve(joints, lengths(3, 0.5f), 3, target, pole, 24);
		float driftB = maxDrift(second, snapshot(joints));

		assertTrue(driftA <= Fabrik.TOLERANCE,
				"re-solving moved a joint " + driftA + ", beyond the solver tolerance " + Fabrik.TOLERANCE);
		// Each solve runs at least one full pass, so the chain approaches the solution
		// asymptotically rather than landing on an exact fixpoint. The property that actually
		// prevents visible popping is that successive re-solves converge rather than wander.
		assertTrue(driftB <= driftA + 1e-6f,
				"drift grew between re-solves (" + driftA + " then " + driftB + ") — the solver is wandering");
		assertEquals(0f, joints[3].distance(target), Fabrik.TOLERANCE * 2f,
				"the effector drifted off the target across repeated solves");
	}

	private static Vector3f[] snapshot(Vector3f[] joints) {
		Vector3f[] copy = new Vector3f[joints.length];
		for (int i = 0; i < joints.length; i++) copy[i] = new Vector3f(joints[i]);
		return copy;
	}

	private static float maxDrift(Vector3f[] a, Vector3f[] b) {
		float max = 0f;
		for (int i = 0; i < a.length; i++) max = Math.max(max, a[i].distance(b[i]));
		return max;
	}
}
