package dev.jsz.primordia;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.body.BoneDef;
import dev.jsz.primordia.body.Feature;
import dev.jsz.primordia.genome.Archetype;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.mesh.MeshBaker;
import dev.jsz.primordia.mesh.MeshData;
import dev.jsz.primordia.mesh.SkinBinder;
import dev.jsz.primordia.sdf.BodySdf;
import dev.jsz.primordia.util.MathX;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins {@link SkinBinder} to the skeleton's own topology, not merely to bind-pose proximity.
 * <p>
 * A frill, horn or other ornament can sit, at bind pose, geometrically closer to a leg than to
 * anything on its own branch — a tall frill arcing down past where a foreleg happens to be, say.
 * Distance alone cannot distinguish "adjacent" from "merely nearby", so before this a frill vertex
 * could pick up a real weight on a leg bone: correct at the one pose it was measured in, and wrong
 * the moment that leg moved through a gait, which is what read as a veil stretching toward a leg.
 * <p>
 * The oracle here is deliberately independent of {@code SkinBinder}'s own filtering decision — the
 * one piece of logic actually under test. It reimplements the generic, low-risk parts (nearest-bone
 * geometry, parent-chain BFS) fresh, from {@link BoneDef#parent} and the same public
 * {@link MathX#projectOntoSegment} the binder itself calls, and then checks the binder's actual
 * output against that — never the other way around ({@code PITFALLS.md} §6: a test that
 * reimplements the code under test cannot catch the code under test).
 * <p>
 * The one thing the oracle does have to share is what "owning bone" <i>means</i>, because the hop
 * budget is measured from it. It is the nearest bone the surface is entitled to be driven by, not
 * simply the nearest bone: a leg is not allowed to own a frill it dangles past, and anchoring the
 * budget on a bone that cannot drive the vertex would measure every influence from the wrong branch
 * of the skeleton. That definition is restated below rather than imported, in the same spirit as
 * {@link #MAX_HOPS}.
 */
class SkinBindingTopologyTest {
	/**
	 * Mirrors {@code SkinBinder.MAX_HOPS}. Not importable — it is a private implementation constant
	 * — so it is restated here with the same justification: local blending across a joint never
	 * needs more than this many hops, and the shortest real ornament-to-limb path in the generator
	 * is roughly double it.
	 */
	private static final int MAX_HOPS = 3;

	@Test
	void everyVertexInfluenceStaysWithinHopRangeOfItsOwningBone() {
		Random random = new Random(20260731);
		int vertices = 0;

		for (int trial = 0; trial < 60; trial++) {
			Genome genome = Archetype.randomStructured(random).create(random);
			BodyPlan plan = BodyPlanBuilder.build(genome);
			MeshData mesh = MeshBaker.bake(plan, 20);
			if (mesh.vertexCount == 0) continue;

			int[][] hops = bfsHopDistances(plan.bones);

			BodySdf sdf = new BodySdf(plan);
			boolean[] limbBone = new boolean[plan.bones.length];
			for (dev.jsz.primordia.body.LimbChain limb : plan.legs) {
				for (int bone : limb.bones) limbBone[bone] = true;
			}
			for (dev.jsz.primordia.body.LimbChain limb : plan.arms) {
				for (int bone : limb.bones) limbBone[bone] = true;
			}

			for (int v = 0; v < mesh.vertexCount; v++) {
				int owner = nearestBone(plan.bones, mesh.positions, v, limbBone,
						sdf.featureAt(mesh.positions[v * 3], mesh.positions[v * 3 + 1],
								mesh.positions[v * 3 + 2]),
						sdf.groupAt(mesh.positions[v * 3], mesh.positions[v * 3 + 1],
								mesh.positions[v * 3 + 2]));
				if (owner < 0) continue;
				vertices++;

				for (int i = 0; i < SkinBinder.MAX_INFLUENCES; i++) {
					int bone = mesh.boneIndices[v * SkinBinder.MAX_INFLUENCES + i];
					float weight = mesh.boneWeights[v * SkinBinder.MAX_INFLUENCES + i];
					if (weight <= 0f) continue;

					assertTrue(hops[owner][bone] <= MAX_HOPS, String.format(
							"vertex %d (owned by %s) carries weight %.4f on %s, %d hops away — "
									+ "geometry from one branch of the skeleton has bled into another",
							v, plan.bones[owner].name, weight, plan.bones[bone].name, hops[owner][bone]));
				}
			}
		}
		assertTrue(vertices > 5000, "too few vertices exercised to be meaningful: " + vertices);
	}

	/**
	 * The complement: an ornament that is genuinely close in the hierarchy — a horn on the head
	 * blending a little into the skull it grows from, say — must still be allowed to share weight.
	 * Without this, a filter that rejected everything would pass the test above trivially.
	 */
	@Test
	void adjacentBonesCanStillShareWeight() {
		Random random = new Random(4242);
		int sawSharedWeight = 0;

		for (int trial = 0; trial < 80 && sawSharedWeight < 20; trial++) {
			Genome genome = Archetype.randomStructured(random).create(random);
			BodyPlan plan = BodyPlanBuilder.build(genome);
			MeshData mesh = MeshBaker.bake(plan, 20);

			for (int v = 0; v < mesh.vertexCount; v++) {
				int base = v * SkinBinder.MAX_INFLUENCES;
				int nonZero = 0;
				for (int i = 0; i < SkinBinder.MAX_INFLUENCES; i++) {
					if (mesh.boneWeights[base + i] > 1e-4f) nonZero++;
				}
				if (nonZero >= 2) sawSharedWeight++;
			}
		}
		assertTrue(sawSharedWeight >= 20,
				"almost no vertex ever blends between two bones — the hop filter may be too strict");
	}

	/** Nearest bone to a vertex by capsule-surface distance — the vertex's owning bone. */
	private static int nearestBone(BoneDef[] bones, float[] positions, int vertex,
	                               boolean[] limbBone, Feature feature, int group) {
		// A limb bone may only own a surface that is limb, or one the field assigns to that limb.
		boolean limbSurface = feature == Feature.LIMB || feature == Feature.FOOT
				|| feature == Feature.CLAWS || feature == Feature.HAND;
		float px = positions[vertex * 3];
		float py = positions[vertex * 3 + 1];
		float pz = positions[vertex * 3 + 2];

		int best = -1;
		float bestDist = Float.MAX_VALUE;
		for (int b = 0; b < bones.length; b++) {
			BoneDef bone = bones[b];
			if (!bone.emitsGeometry && bone.length() <= 1e-5f) continue;
			if (limbBone[b] && !limbSurface && bone.blendGroup != group) continue;

			float t = MathX.projectOntoSegment(px, py, pz,
					bone.head.x, bone.head.y, bone.head.z,
					bone.tail.x, bone.tail.y, bone.tail.z);
			float ax = bone.head.x + (bone.tail.x - bone.head.x) * t;
			float ay = bone.head.y + (bone.tail.y - bone.head.y) * t;
			float az = bone.head.z + (bone.tail.z - bone.head.z) * t;
			float dx = px - ax, dy = py - ay, dz = pz - az;
			float radius = MathX.lerp(bone.radiusHead, bone.radiusTail, t);
			float d = Math.max(0f, (float) Math.sqrt(dx * dx + dy * dy + dz * dz) - radius);

			if (d < bestDist) {
				bestDist = d;
				best = b;
			}
		}
		return best;
	}

	/** Parent-chain hop distance between every pair of bones, by plain BFS over parent/child edges. */
	private static int[][] bfsHopDistances(BoneDef[] bones) {
		int n = bones.length;
		List<List<Integer>> adjacency = new ArrayList<>(n);
		for (int i = 0; i < n; i++) adjacency.add(new ArrayList<>());
		for (int i = 0; i < n; i++) {
			int parent = bones[i].parent;
			if (parent < 0 || parent >= n) continue;
			adjacency.get(i).add(parent);
			adjacency.get(parent).add(i);
		}

		int[][] hops = new int[n][n];
		for (int start = 0; start < n; start++) {
			int[] row = hops[start];
			Arrays.fill(row, Integer.MAX_VALUE / 2);
			row[start] = 0;
			Deque<Integer> queue = new ArrayDeque<>();
			queue.add(start);
			while (!queue.isEmpty()) {
				int cur = queue.poll();
				for (int next : adjacency.get(cur)) {
					if (row[next] != Integer.MAX_VALUE / 2) continue;
					row[next] = row[cur] + 1;
					queue.add(next);
				}
			}
		}
		return hops;
	}
}
