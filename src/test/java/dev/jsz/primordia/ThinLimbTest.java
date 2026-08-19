package dev.jsz.primordia;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.body.Feature;
import dev.jsz.primordia.body.LimbChain;
import dev.jsz.primordia.body.SdfBlob;
import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.mesh.LodTier;
import dev.jsz.primordia.mesh.MeshBaker;
import dev.jsz.primordia.mesh.MeshData;
import dev.jsz.primordia.util.MathX;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression cover for limbs vanishing from the mesh.
 * <p>
 * Surface Nets only emits geometry where the sampled field changes sign, so a limb thinner than
 * one sampling cell can fall entirely between samples: the leg is not coarse, it is absent. This
 * was visible in game as creatures with missing legs, and it is invisible to every other test in
 * the suite because the body plan is perfectly valid — only the mesh is wrong.
 */
class ThinLimbTest {

	/** Builds the worst case on purpose: minimum limb thickness, maximum body size and reach. */
	private static Genome slenderGenome(Random random) {
		return Genome.random(random)
				.with(Gene.LEG_THICKNESS, 0f)
				.with(Gene.ARM_THICKNESS, 0f)
				.with(Gene.TORSO_GIRTH, 0f)
				.with(Gene.LEG_LENGTH, 1f)
				.with(Gene.SIZE, 1f)
				.with(Gene.TORSO_LENGTH, 1f)
				.with(Gene.TAIL_LENGTH, 1f);
	}

	@Test
	void thinnestPossibleLegsStillProduceGeometry() {
		Random random = new Random(6060);
		for (int trial = 0; trial < 12; trial++) {
			BodyPlan plan = BodyPlanBuilder.build(slenderGenome(random));
			MeshData mesh = MeshBaker.bake(plan, LodTier.resolutionFor(LodTier.NEAR));

			for (LimbChain leg : plan.legs) {
				// Sample the middle of the middle bone — the part most likely to be missed.
				int bone = leg.bones[leg.bones.length / 2];
				float mx = (plan.bones[bone].head.x + plan.bones[bone].tail.x) * 0.5f;
				float my = (plan.bones[bone].head.y + plan.bones[bone].tail.y) * 0.5f;
				float mz = (plan.bones[bone].head.z + plan.bones[bone].tail.z) * 0.5f;

				// Any surface belonging to this limb has to lie close to its own axis.
				float tolerance = plan.bones[bone].maxRadius() * 3f + plan.blendRadius * 2f;
				boolean found = false;
				for (int v = 0; v < mesh.vertexCount && !found; v++) {
					int p = v * 3;
					float dx = mesh.positions[p] - mx;
					float dy = mesh.positions[p + 1] - my;
					float dz = mesh.positions[p + 2] - mz;
					if (dx * dx + dy * dy + dz * dz <= tolerance * tolerance) found = true;
				}
				assertTrue(found, "leg bone " + plan.bones[bone].name
						+ " (radius " + plan.bones[bone].maxRadius() + ") produced no mesh nearby "
						+ "— the limb vanished during meshing");
			}
		}
	}

	@Test
	void blendRadiusNeverSwallowsALimb() {
		Random random = new Random(6061);
		for (int trial = 0; trial < 200; trial++) {
			BodyPlan plan = BodyPlanBuilder.build(Genome.random(random));
			// A smooth-union radius wider than the limb it blends fairs the whole leg into the
			// torso, which erases it just as effectively as under-sampling does.
			assertTrue(plan.blendRadius <= plan.minLimbRadius * 1.15f + MathX.EPS,
					"blend radius " + plan.blendRadius + " exceeds thinnest limb " + plan.minLimbRadius);
		}
	}

	@Test
	void limbsHaveAWorkableMinimumThickness() {
		Random random = new Random(6062);
		for (int trial = 0; trial < 300; trial++) {
			BodyPlan plan = BodyPlanBuilder.build(Genome.random(random));
			assertTrue(plan.minLimbRadius > 0f, "minLimbRadius was not computed");
			// Measured against the leg's own length, not the whole body: a creature with a long
			// tail is not thereby required to have thick legs, so total body length is the wrong
			// yardstick. What matters visually is the limb's slenderness ratio.
			assertTrue(plan.minLimbRadius > plan.hipHeight * 0.02f,
					"limb radius " + plan.minLimbRadius + " against a hip height of " + plan.hipHeight
							+ " is a slenderness ratio over 50:1 — that reads as wireframe");
		}
	}

	@Test
	void detailCostStaysWithinBudget() {
		// Guards the other direction: the resolution floor must not let one slender genome
		// explode into a mesh that costs more than the LOD budget was sized for.
		Random random = new Random(6063);
		int worstQuads = 0;
		String worst = "";

		for (int trial = 0; trial < 25; trial++) {
			Genome genome = trial % 2 == 0 ? slenderGenome(random) : Genome.random(random);
			BodyPlan plan = BodyPlanBuilder.build(genome);
			MeshData mesh = MeshBaker.bake(plan, LodTier.resolutionFor(LodTier.NEAR));
			if (mesh.quadCount > worstQuads) {
				worstQuads = mesh.quadCount;
				worst = "minLimbRadius=" + plan.minLimbRadius + " span=" + plan.bodyLength;
			}
		}
		assertTrue(worstQuads < 60_000,
				"near-tier mesh reached " + worstQuads + " quads (" + worst + "), beyond what the LOD budget assumes");
		System.out.println("[ThinLimbTest] worst near-tier mesh: " + worstQuads + " quads (" + worst + ")");
	}

	/**
	 * The same rule applied to the ornament that is thin on purpose.
	 * <p>
	 * A frill and a dorsal spine are sheets, and a sheet under one cell across does not come out
	 * coarse — it comes out perforated, surfacing only in the patches where a sample happened to
	 * land inside it. Two thirds of them were under that line, which is what put holes in every
	 * frill and every spine in the game. Limbs answer this by raising resolution; ornament cannot
	 * afford to, so {@code BodyPlanBuilder} floors its thin axis instead, and this holds that floor.
	 */
	@Test
	void thinOrnamentSurvivesTheSamplingGrid() {
		Random random = new Random(99);
		int checked = 0;

		for (int trial = 0; trial < 120; trial++) {
			Genome genome = Genome.random(random)
					.with(Gene.FRILL, 1f)
					.with(Gene.DORSAL_SPINES, 1f)
					.with(Gene.SPINE_STYLE, 0.9f);
			BodyPlan plan = BodyPlanBuilder.build(genome);
			float span = Math.max(plan.boundsMax.x - plan.boundsMin.x,
					Math.max(plan.boundsMax.y - plan.boundsMin.y, plan.boundsMax.z - plan.boundsMin.z));
			float cell = span / LodTier.resolutionFor(LodTier.NEAR);

			for (SdfBlob blob : plan.blobs) {
				if (blob.subtract()) continue;
				if (blob.feature() != Feature.FRILL && blob.feature() != Feature.SPINE
						&& blob.feature() != Feature.PLATE && blob.feature() != Feature.FIN) continue;
				Vector3f r = blob.radii();
				float thin = Math.min(r.x, Math.min(r.y, r.z));
				// A blob whose longest axis is itself under a cell is too small to rescue without
				// turning it into a ball, and is deliberately left alone.
				if (blob.maxRadius() < cell) continue;
				checked++;
				assertTrue(thin >= cell * 0.999f, blob.feature() + " blob is " + (thin / cell)
						+ " of a cell on its thin axis — it will mesh with holes in it");
			}
		}
		assertTrue(checked > 0, "no thin ornament was generated to check");
	}
}
