package dev.jsz.primordia.mesh;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.body.BoneDef;
import dev.jsz.primordia.body.Feature;
import dev.jsz.primordia.genome.Archetype;
import dev.jsz.primordia.sdf.BodySdf;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

/**
 * Which surfaces a limb bone is driving, by what the surface actually is.
 * <p>
 * {@code gradle skinProbe}. A leg weight on a frill or on a spider's abdomen is invisible in the
 * bind pose and only shows once the gait swings: the ornament stretches toward the limb. Neither
 * the hop filter nor the blend-group rule catches it, because both ask about the <i>skeleton</i>
 * and this is a question about the <i>surface</i> — the frill hangs off a spine bone but sits, in
 * space, right beside a thigh.
 * <p>
 * Prints, per feature, the share of that feature's vertices carrying a limb weight worth seeing.
 */
public final class SkinProbe {

	/** Below this a weight is the falloff's tail rather than an influence that visibly drags. */
	private static final float VISIBLE = 0.10f;

	public static void main(String[] args) {
		// A genome code instead of a seed asks the same questions of one named creature.
		if (args.length > 0 && args[0].length() > 24) {
			// Voxel mode by default, because that is what the editor and the game bake in and the
			// sampling decides which vertices exist at all.
			MeshBaker.setVoxelSize(args.length > 1 ? Float.parseFloat(args[1]) / 16f : 1f / 16f);
			one(args[0]);
			return;
		}
		long seed = args.length > 0 ? Long.parseLong(args[0]) : 4242L;
		int perArchetype = args.length > 1 ? Integer.parseInt(args[1]) : 12;

		Map<Feature, int[]> tally = new EnumMap<>(Feature.class);
		Map<Feature, Float> worst = new EnumMap<>(Feature.class);

		for (Archetype archetype : Archetype.VALUES) {
			Random random = new Random(seed + archetype.ordinal() * 7919L);
			for (int trial = 0; trial < perArchetype; trial++) {
				BodyPlan plan = BodyPlanBuilder.build(archetype.create(random));
				MeshData mesh = MeshBaker.bake(plan, LodTier.resolutionFor(LodTier.NEAR));
				BodySdf sdf = new BodySdf(plan);
				for (int v = 0; v < mesh.vertexCount; v++) {
					Feature feature = sdf.featureAt(mesh.positions[v * 3],
							mesh.positions[v * 3 + 1], mesh.positions[v * 3 + 2]);
					// Legs specifically, by name. The jaw and the arms carry blend groups of their
					// own, so "not axial" would count a jaw driving its own jaw vertices as a fault.
					float limbWeight = 0f;
					for (int i = 0; i < SkinBinder.MAX_INFLUENCES; i++) {
						float w = mesh.boneWeights[v * SkinBinder.MAX_INFLUENCES + i];
						int bone = mesh.boneIndices[v * SkinBinder.MAX_INFLUENCES + i];
						if (plan.bones[bone].name.startsWith("leg")) limbWeight += w;
					}
					// The group the field says this surface belongs to. AXIAL means the blob it
					// came off hangs on the trunk — so an AXIAL vertex with a real leg weight is a
					// piece of body being driven by a limb, whatever the feature happens to be.
					boolean axialSurface = sdf.groupAt(mesh.positions[v * 3],
							mesh.positions[v * 3 + 1], mesh.positions[v * 3 + 2]) == BoneDef.AXIAL;
					int[] counts = tally.computeIfAbsent(feature, f -> new int[3]);
					counts[0]++;
					if (limbWeight > VISIBLE) counts[1]++;
					if (limbWeight > VISIBLE && axialSurface) counts[2]++;
					worst.merge(feature, limbWeight, Math::max);
				}
			}
		}

		System.out.printf("%-12s %10s %12s %10s %14s %10s%n",
				"feature", "vertices", "leg-driven", "share", "body-surface", "worst");
		for (Feature feature : Feature.values()) {
			int[] counts = tally.get(feature);
			if (counts == null || counts[0] == 0) continue;
			System.out.printf("%-12s %10d %12d %9.2f%% %14d %10.2f%n", feature, counts[0], counts[1],
					counts[1] * 100f / counts[0], counts[2], worst.getOrDefault(feature, 0f));
		}
	}

	/**
	 * One creature, and where its limb weights actually land.
	 * <p>
	 * The population table above says which <i>kinds</i> of surface a limb is driving. This says
	 * which vertices, and — the part that matters when a rule is leaking — how the field labelled
	 * them: {@code featureAt} answers with the nearest part, so a toe poking through a veil makes
	 * the veil's own surface read as FOOT, and any rule keyed on the feature alone will wave it
	 * through.
	 */
	static void one(String code) {
		dev.jsz.primordia.genome.Genome genome = dev.jsz.primordia.genome.Genome.decode(code);
		if (genome == null) {
			System.out.println("not a genome code");
			return;
		}
		BodyPlan plan = BodyPlanBuilder.build(genome);
		// Every tier, because the sampling decides which vertices exist and how the field labels
		// them: a rule that holds on the near mesh can still leak on a coarser one.
		for (int tier = 0; tier < LodTier.COUNT; tier++) {
			MeshData m = MeshBaker.bake(plan, LodTier.resolutionFor(tier));
			BodySdf f = new BodySdf(plan);
			int ornamentVerts = 0, ornamentLegDriven = 0;
			for (int v = 0; v < m.vertexCount; v++) {
				Feature feat = f.featureAt(m.positions[v * 3], m.positions[v * 3 + 1], m.positions[v * 3 + 2]);
				if (!isOrnament(feat)) continue;
				ornamentVerts++;
				float w = 0f;
				for (int i = 0; i < SkinBinder.MAX_INFLUENCES; i++) {
					int bone = m.boneIndices[v * SkinBinder.MAX_INFLUENCES + i];
					if (plan.bones[bone].name.startsWith("leg")) {
						w += m.boneWeights[v * SkinBinder.MAX_INFLUENCES + i];
					}
				}
				if (w > VISIBLE) ornamentLegDriven++;
			}
			System.out.printf("tier %d: %d vertices, ornament %d, ornament driven by a leg %d%n",
					tier, m.vertexCount, ornamentVerts, ornamentLegDriven);
		}

		MeshData mesh = MeshBaker.bake(plan, LodTier.resolutionFor(LodTier.NEAR));
		BodySdf sdf = new BodySdf(plan);
		System.out.println("vertices " + mesh.vertexCount + ", bones " + plan.bones.length
				+ ", legs " + plan.legs.length + ", voxel " + MeshBaker.voxelSize());
		StringBuilder ornamentBlobs = new StringBuilder();
		for (dev.jsz.primordia.body.SdfBlob blob : plan.blobs) {
			if (isOrnament(blob.feature())) ornamentBlobs.append(blob.feature()).append(' ');
		}
		System.out.println("ornament blobs: " + (ornamentBlobs.length() == 0 ? "none" : ornamentBlobs));

		// feature x group, for vertices carrying a leg weight worth seeing.
		Map<String, int[]> cross = new java.util.TreeMap<>();
		for (int v = 0; v < mesh.vertexCount; v++) {
			float x = mesh.positions[v * 3], y = mesh.positions[v * 3 + 1], z = mesh.positions[v * 3 + 2];
			Feature feature = sdf.featureAt(x, y, z);
			int group = sdf.groupAt(x, y, z);
			float legWeight = 0f;
			for (int i = 0; i < SkinBinder.MAX_INFLUENCES; i++) {
				int bone = mesh.boneIndices[v * SkinBinder.MAX_INFLUENCES + i];
				if (plan.bones[bone].name.startsWith("leg")) {
					legWeight += mesh.boneWeights[v * SkinBinder.MAX_INFLUENCES + i];
				}
			}
			String key = feature + " / " + (group == BoneDef.AXIAL ? "body" : "limb" + group);
			int[] counts = cross.computeIfAbsent(key, k -> new int[2]);
			counts[0]++;
			if (legWeight > VISIBLE) counts[1]++;
		}
		System.out.printf("%-24s %10s %12s %10s%n", "feature / field group", "vertices", "leg-driven", "share");
		for (Map.Entry<String, int[]> e : cross.entrySet()) {
			int[] c = e.getValue();
			System.out.printf("%-24s %10d %12d %9.1f%%%n", e.getKey(), c[0], c[1], c[1] * 100f / c[0]);
		}

		// The labels agree with each other; the question is whether they agree with the shape. For
		// every leg-driven vertex, how close it sits to an ornament blob's own surface — a vertex
		// sitting on the veil while labelled FOOT is the veil being driven by a toe, whatever the
		// field says it is.
		int onOrnament = 0, legDriven = 0;
		float worstDepth = Float.MAX_VALUE;
		for (int v = 0; v < mesh.vertexCount; v++) {
			float x = mesh.positions[v * 3], y = mesh.positions[v * 3 + 1], z = mesh.positions[v * 3 + 2];
			float legWeight = 0f;
			for (int i = 0; i < SkinBinder.MAX_INFLUENCES; i++) {
				int bone = mesh.boneIndices[v * SkinBinder.MAX_INFLUENCES + i];
				if (plan.bones[bone].name.startsWith("leg")) {
					legWeight += mesh.boneWeights[v * SkinBinder.MAX_INFLUENCES + i];
				}
			}
			if (legWeight <= VISIBLE) continue;
			legDriven++;
			float best = Float.MAX_VALUE;
			for (dev.jsz.primordia.body.SdfBlob blob : plan.blobs) {
				if (blob.subtract()) continue;
				if (!isOrnament(blob.feature())) continue;
				// Scaled distance to the ellipsoid: below 1 is inside it.
				float dx = (x - blob.center().x) / Math.max(1e-5f, blob.radii().x);
				float dy = (y - blob.center().y) / Math.max(1e-5f, blob.radii().y);
				float dz = (z - blob.center().z) / Math.max(1e-5f, blob.radii().z);
				best = Math.min(best, (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
			}
			if (best <= 1.05f) {
				onOrnament++;
				worstDepth = Math.min(worstDepth, best);
			}
		}
		System.out.printf("%nleg-driven vertices: %d, of which sitting on an ornament blob: %d"
				+ " (deepest %.2f of its radius)%n", legDriven, onOrnament,
				worstDepth == Float.MAX_VALUE ? -1f : worstDepth);

		// Whether a limb is physically inside an ornament. Weights cannot save a mesh where a toe
		// is embedded in a veil: the union welds them into one surface, the toe's share of it is
		// correctly limb-driven and the veil's share is correctly body-driven, and the seam between
		// them tears as the leg swings. That is a body-plan fault, not a skinning one.
		System.out.println();
		for (dev.jsz.primordia.body.SdfBlob blob : plan.blobs) {
			if (blob.subtract() || !isOrnament(blob.feature())) continue;
			for (dev.jsz.primordia.body.LimbChain limb : plan.legs) {
				for (int b : limb.bones) {
					dev.jsz.primordia.body.BoneDef bone = plan.bones[b];
					// Sample along the bone; scaled distance below 1 is inside the ellipsoid.
					float deepest = Float.MAX_VALUE;
					for (int i = 0; i <= 8; i++) {
						float t = i / 8f;
						float x = bone.head.x + (bone.tail.x - bone.head.x) * t;
						float y = bone.head.y + (bone.tail.y - bone.head.y) * t;
						float z = bone.head.z + (bone.tail.z - bone.head.z) * t;
						float dx = (x - blob.center().x) / Math.max(1e-5f, blob.radii().x);
						float dy = (y - blob.center().y) / Math.max(1e-5f, blob.radii().y);
						float dz = (z - blob.center().z) / Math.max(1e-5f, blob.radii().z);
						deepest = Math.min(deepest, (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
					}
					if (deepest < 1.15f) {
						System.out.printf("%s bone %s reaches %.2f of the way into a %s blob%n",
								deepest < 1f ? "INSIDE:" : "close:", bone.name, deepest, blob.feature());
					}
				}
			}
		}
	}

	/** Ornament and body parts a limb has no business driving. */
	static boolean isOrnament(Feature f) {
		return switch (f) {
			case FRILL, FIN, EAR, HORN, PLATE, SPINE, HAIR, ABDOMEN, TUSK, BEAK -> true;
			default -> false;
		};
	}

	private SkinProbe() {
	}
}
