package dev.jsz.primordia;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.mesh.LodTier;
import dev.jsz.primordia.mesh.MeshBaker;
import dev.jsz.primordia.mesh.MeshData;
import dev.jsz.primordia.mesh.SurfaceNets;
import dev.jsz.primordia.sdf.BodySdf;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins voxel mode to an actual grid.
 * <p>
 * The whole claim of the mode is that the surface is made of cubes of a stated size. That is
 * checkable and worth checking, because "blocky-looking" and "on a grid" are not the same thing —
 * a mesh can read as chunky on screen while its vertices sit at arbitrary positions, and the tell
 * only appears when two creatures stand side by side and their blocks do not line up.
 */
class VoxelModeTest {
	/** One Minecraft pixel, the default voxel edge. */
	private static final float PIXEL = 1f / 16f;

	private static SurfaceNets.Result extract(Genome genome, int resolution, float voxelSize) {
		BodyPlan plan = BodyPlanBuilder.build(genome);
		return SurfaceNets.extract(new BodySdf(plan), plan.boundsMin, plan.boundsMax,
				resolution, voxelSize);
	}

	private static BodyPlan planOf(Genome genome) {
		return BodyPlanBuilder.build(genome);
	}

	@Test
	void everyVertexSitsAtTheCentreOfItsCell() {
		Random random = new Random(1212);
		int checked = 0;

		for (int trial = 0; trial < 40; trial++) {
			Genome genome = Genome.random(random);
			BodyPlan plan = planOf(genome);
			SurfaceNets.Result net = extract(genome, 24, PIXEL);
			if (net.vertexCount() == 0) continue;
			checked++;

			// The mesher may coarsen the grid to a power-of-two multiple of the voxel when the LOD
			// asks for something coarser, so the spacing is recovered from the mesh rather than
			// assumed — but it must still be a multiple of the pixel.
			float cell = inferCell(net, plan);
			assertTrue(cell > 0f, "could not infer a cell size");
			float multiple = cell / PIXEL;
			assertEquals(Math.round(multiple), multiple, 1e-3f,
					"cell size " + cell + " is not a whole number of pixels");

			float[] pos = net.positions();
			for (int v = 0; v < net.vertexCount(); v++) {
				assertOnLattice(pos[v * 3], plan.boundsMin.x, cell, "x");
				assertOnLattice(pos[v * 3 + 1], plan.boundsMin.y, cell, "y");
				assertOnLattice(pos[v * 3 + 2], plan.boundsMin.z, cell, "z");
			}
		}
		assertTrue(checked > 30, "too few meshes to be meaningful: " + checked);
	}

	/** A coordinate must be half a cell off the lattice origin, plus a whole number of cells. */
	private static void assertOnLattice(float value, float origin, float cell, String axis) {
		float steps = (value - origin) / cell - 0.5f;
		float error = Math.abs(steps - Math.round(steps));
		assertTrue(error < 1e-3f, String.format(
				"%s = %.5f is %.4f of a cell off the voxel lattice", axis, value, error));
	}

	/**
	 * Smallest non-zero gap between vertex coordinates on the X axis, which for a lattice is the
	 * cell size. Read off the mesh rather than recomputed from the inputs, per {@code PITFALLS.md}
	 * §6 — recomputing what the mesher was told cannot catch the mesher ignoring it.
	 */
	private static float inferCell(SurfaceNets.Result net, BodyPlan plan) {
		float[] pos = net.positions();
		float smallest = Float.MAX_VALUE;
		for (int v = 0; v < net.vertexCount(); v++) {
			float offset = Math.abs(pos[v * 3] - plan.boundsMin.x);
			for (int w = v + 1; w < Math.min(net.vertexCount(), v + 64); w++) {
				float gap = Math.abs(pos[w * 3] - pos[v * 3]);
				if (gap > 1e-4f) smallest = Math.min(smallest, gap);
			}
			if (offset > 0f && smallest < Float.MAX_VALUE && v > 200) break;
		}
		return smallest == Float.MAX_VALUE ? 0f : smallest;
	}

	@Test
	void smoothModeIsNotOnTheLattice() {
		// The complement of the test above, and the one that proves it is measuring something.
		// Surface Nets places its vertices at averaged edge crossings, so essentially none of them
		// should land on cell centres by chance.
		Genome genome = Genome.random(new Random(9));
		BodyPlan plan = planOf(genome);
		SurfaceNets.Result net = extract(genome, 24, 0f);
		assertTrue(net.vertexCount() > 0, "smooth extraction produced nothing");

		float cell = Math.max(plan.boundsMax.x - plan.boundsMin.x,
				Math.max(plan.boundsMax.y - plan.boundsMin.y,
						plan.boundsMax.z - plan.boundsMin.z)) / 24f;

		int onLattice = 0;
		float[] pos = net.positions();
		for (int v = 0; v < net.vertexCount(); v++) {
			float steps = (pos[v * 3] - plan.boundsMin.x) / cell - 0.5f;
			if (Math.abs(steps - Math.round(steps)) < 1e-3f) onLattice++;
		}
		assertTrue(onLattice < net.vertexCount() / 4, String.format(
				"%d of %d smooth vertices are on the lattice — voxel snapping may be leaking in",
				onLattice, net.vertexCount()));
	}

	/**
	 * Every face of a voxel body lies on an axis.
	 * <p>
	 * A face joins the centres of the four cells around one lattice edge, so its four corners share
	 * a coordinate on that edge's axis and the face is a plane of constant X, Y or Z. This is the
	 * geometric claim voxel mode makes, and unlike a look it can be checked exactly.
	 * <p>
	 * Checked against the mesher itself rather than against a finished creature, so nothing a later
	 * stage does to the mesh can mask a face the lattice never squared off.
	 */
	@Test
	void everyVoxelFaceLiesOnAnAxis() {
		Random random = new Random(2024);
		int faces = 0;

		for (int trial = 0; trial < 20; trial++) {
			SurfaceNets.Result net = extract(Genome.random(random), 24, PIXEL);
			float[] pos = net.positions();
			int[] quads = net.quads();

			for (int i = 0; i < quads.length; i += 4) {
				// Newell's method: a Surface Nets quad can be non-planar in general, and this gives
				// the area-weighted average direction rather than one corner's opinion of it.
				float nx = 0f, ny = 0f, nz = 0f;
				for (int k = 0; k < 4; k++) {
					int cur = quads[i + k] * 3;
					int next = quads[i + (k + 1) % 4] * 3;
					nx += (pos[cur + 1] - pos[next + 1]) * (pos[cur + 2] + pos[next + 2]);
					ny += (pos[cur + 2] - pos[next + 2]) * (pos[cur] + pos[next]);
					nz += (pos[cur] - pos[next]) * (pos[cur + 1] + pos[next + 1]);
				}
				float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
				if (len < 1e-9f) continue;
				faces++;

				float dominant = Math.max(Math.abs(nx), Math.max(Math.abs(ny), Math.abs(nz))) / len;
				assertEquals(1f, dominant, 1e-3f, String.format(
						"voxel face normal (%.4f, %.4f, %.4f) is not axis-aligned", nx / len, ny / len, nz / len));
			}
		}
		assertTrue(faces > 500, "too few faces to be meaningful: " + faces);
	}

	@Test
	void largerVoxelsGiveFewerOfThem() {
		Genome genome = Genome.random(new Random(31));
		int fine = extract(genome, 48, PIXEL).vertexCount();
		int coarse = extract(genome, 48, PIXEL * 4).vertexCount();

		assertTrue(fine > 0 && coarse > 0, "an extraction produced no geometry");
		assertTrue(coarse < fine, String.format(
				"four-pixel voxels (%d verts) were not coarser than one-pixel (%d)", coarse, fine));
	}

	/**
	 * A creature with legs finer than a voxel must still come out with legs.
	 * <p>
	 * This is PITFALLS §3 arriving by a different road. The ordinary pipeline escapes it by raising
	 * the sampling resolution until cells are finer than the thinnest limb; voxel mode cannot,
	 * because its cell size is fixed by the world. {@code MeshBaker} thickens the field instead, and
	 * this checks that the thickening is enough — arachnids are the case that needs it, their legs
	 * being routinely finer than one pixel-sized voxel.
	 */
	@Test
	void spindlyLimbsSurviveTheVoxelGrid() {
		MeshBaker.setVoxelSize(PIXEL);
		try {
			Random random = new Random(4242);
			int checked = 0;

			for (int trial = 0; trial < 200 && checked < 12; trial++) {
				Genome genome = Genome.random(random);
				BodyPlan plan = BodyPlanBuilder.build(genome);
				// Only genomes whose limbs are finer than a voxel: the ones that would break.
				if (plan.minLimbRadius >= PIXEL * 0.5f || plan.legs.length == 0) continue;
				checked++;

				MeshData mesh = MeshBaker.bake(plan, 24);
				assertTrue(mesh.quadCount > 0, "a spindly genome voxelised to nothing");

				// The mesh has to reach the ground. A leg that broke up into disconnected blocks
				// leaves the body floating with a gap under it, which is the visible symptom.
				float lowest = Float.MAX_VALUE;
				for (int v = 0; v < mesh.vertexCount; v++) {
					lowest = Math.min(lowest, mesh.positions[v * 3 + 1]);
				}
				assertTrue(lowest < plan.hipHeight * 0.5f, String.format(
						"lowest geometry sits at %.3f on a creature with hips at %.3f — the legs did "
								+ "not survive voxelisation", lowest, plan.hipHeight));
			}
			assertTrue(checked >= 8, "too few spindly genomes found to be meaningful: " + checked);
		} finally {
			MeshBaker.setVoxelSize(0f);
		}
	}

	@Test
	void thickLimbedCreaturesAreNotSilentlyFattened() {
		// Inflation must be zero when it is not needed, or every creature in voxel mode comes out
		// heavier than it is in the world's own physics and hitbox.
		Random random = new Random(555);
		for (int trial = 0; trial < 60; trial++) {
			Genome genome = Genome.random(random);
			BodyPlan plan = BodyPlanBuilder.build(genome);
			if (plan.minLimbRadius < PIXEL) continue;

            MeshBaker.setVoxelSize(0f);
			MeshData plain = MeshBaker.bake(plan, 24);
			MeshBaker.setVoxelSize(PIXEL);
			MeshData voxel = MeshBaker.bake(plan, 24);
			MeshBaker.setVoxelSize(0f);

			// Bounds are compared rather than volume: a thick-limbed creature needs no inflation, so
			// voxelising should snap its surface to a grid without growing it appreciably.
			float grew = (voxel.maxY - voxel.minY) - (plain.maxY - plain.minY);
			assertTrue(grew < PIXEL * 3f, String.format(
					"a thick-limbed creature grew %.3f in voxel mode, which is inflation it did not need",
					grew));
			return;
		}
	}

	@Test
	void voxelModeStillProducesAWholeCreature() {
		Random random = new Random(77);
		for (int trial = 0; trial < 30; trial++) {
			Genome genome = Genome.random(random);
			SurfaceNets.Result net = extract(genome, 32, PIXEL);

			// Voxelising must not lose the animal. A body that vanishes at one pixel would mean the
			// grid had been coarsened past the creature's thinnest parts — the same failure
			// PITFALLS §3 describes for limbs finer than a sampling cell.
			assertTrue(net.vertexCount() > 0, "voxel mode produced an empty mesh");
			assertTrue(net.quads().length >= 4, "voxel mode produced no faces");
			for (float f : net.positions()) {
				assertTrue(Float.isFinite(f), "a vertex position was not finite");
			}
		}
	}
	/**
	 * Two creatures standing together are built out of the same size of block.
	 * <p>
	 * The lattice is a power-of-two multiple of the base voxel, so a grid even slightly coarser
	 * than a pixel is rounded up to two and that animal is visibly built from bigger blocks than
	 * its neighbour. Large, thick-limbed genomes used to land there: nothing about their limbs
	 * asked for a finer grid, so they kept the tier's own resolution, and past about five blocks
	 * of span that resolution is coarser than a pixel. It was 0.2% of the population — rare, and
	 * unmissable side by side, which is the worst combination for a look that is entirely about
	 * uniformity.
	 * <p>
	 * Checked at the near tier, where the blocks are big enough on screen to compare. The distant
	 * tiers coarsen on purpose.
	 */
	@Test
	void everyCreatureIsBuiltFromTheSameSizedBlockUpClose() {
		Random random = new Random(90210);
		float previous = MeshBaker.voxelSize();
		MeshBaker.setVoxelSize(PIXEL);
		try {
			int checked = 0;
			for (int trial = 0; trial < 60; trial++) {
				// Half the sample deliberately large: this only ever went wrong on big animals, and
				// a uniform draw hits one about twice in a thousand.
				// Deliberately large *and* thick-limbed. This only ever went wrong where the limbs
				// asked for nothing — a slender giant already gets a fine grid to keep its legs
				// from vanishing, and is carried over the line by accident.
				Genome genome = Genome.random(random)
						.with(Gene.SIZE, 0.85f + random.nextFloat() * 0.15f)
						.with(Gene.LEG_THICKNESS, 0.7f + random.nextFloat() * 0.3f)
						.with(Gene.LEG_LENGTH, 0.5f + random.nextFloat() * 0.5f);
				BodyPlan plan = planOf(genome);
				float span = Math.max(plan.boundsMax.x - plan.boundsMin.x,
						Math.max(plan.boundsMax.y - plan.boundsMin.y,
								plan.boundsMax.z - plan.boundsMin.z));
				// Past the resolution ceiling a pixel is unaffordable and coarsening is the
				// intended answer; those are outside this claim.
				if (span > 8.5f) continue;
				MeshData mesh = MeshBaker.bake(plan, LodTier.resolutionFor(LodTier.NEAR));
				if (mesh.quadCount == 0) continue;
				checked++;
				float cell = inferCell(mesh, plan);
				assertEquals(PIXEL, cell, PIXEL * 0.05f, String.format(
						"a %.2f-block creature meshed at %.1f pixels per block while its neighbours "
								+ "get one", span, cell / PIXEL));
			}
			assertTrue(checked > 25, "too few meshes to be meaningful: " + checked);
		} finally {
			MeshBaker.setVoxelSize(previous);
		}
	}

	/** {@link #inferCell(SurfaceNets.Result, BodyPlan)} against a baked mesh rather than a net. */
	private static float inferCell(MeshData mesh, BodyPlan plan) {
		float smallest = Float.MAX_VALUE;
		int verts = mesh.positions.length / 3;
		for (int v = 0; v < verts; v++) {
			for (int w = v + 1; w < Math.min(verts, v + 64); w++) {
				float gap = Math.abs(mesh.positions[w * 3] - mesh.positions[v * 3]);
				if (gap > 1e-4f) smallest = Math.min(smallest, gap);
			}
			if (smallest < Float.MAX_VALUE && v > 200) break;
		}
		return smallest == Float.MAX_VALUE ? 0f : smallest;
	}
	/**
	 * The creature that reported this: a 6.4-block animal on thick legs, meshed at two pixels per
	 * block while everything beside it was on one. Kept as a genome code rather than as a
	 * description, because the bug was in the interaction between its span and its limb thickness
	 * and no summary of it would reproduce that.
	 */
	private static final String THICK_LIMBED_GIANT =
			"AZ-nzvaXojnv4MMo4cr96J8AAAAA77WHAqFGceITOL1zzPLm39fVzOI2hXybTK5yg1KHJdoAIvrDtn63rFA5"
					+ "gArO8vHCF1GVKj8VcCVzS4Jlf3mEidPF3ZQr6K9heb6c0iX5yr2lePJUKYMHZppIcWIFJpyBg6hp"
					+ "tHfr7LX_1zcMMVy2ci6i7xvk8K5grKu73iN9efXFOXRBg-kO_hBwlIo9B-sbhWz7yC4X7OuOp50k"
					+ "BaWfcPZT1yV-EilfeyYpPUO6Atw";

	@Test
	void theReportedGiantIsBuiltFromOnePixelBlocks() {
		Genome genome = Genome.decode(THICK_LIMBED_GIANT);
		assertNotNull(genome, "the recorded genome code no longer decodes");
		float previous = MeshBaker.voxelSize();
		MeshBaker.setVoxelSize(PIXEL);
		try {
			BodyPlan plan = planOf(genome);
			MeshData mesh = MeshBaker.bake(plan, LodTier.resolutionFor(LodTier.NEAR));
			assertEquals(PIXEL, inferCell(mesh, plan), PIXEL * 0.05f,
					"the creature this was reported on is back to oversized blocks");
		} finally {
			MeshBaker.setVoxelSize(previous);
		}
	}
}
