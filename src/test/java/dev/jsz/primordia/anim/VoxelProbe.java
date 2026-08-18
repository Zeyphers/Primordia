package dev.jsz.primordia.anim;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.genome.Archetype;
import dev.jsz.primordia.mesh.LodTier;
import dev.jsz.primordia.mesh.MeshBaker;
import dev.jsz.primordia.mesh.SurfaceNets;

import java.util.Random;

/**
 * What size of voxel each archetype actually ends up built from.
 * <p>
 * {@code gradle voxelProbe}. Voxel mode is meant to give every creature the same size of block,
 * because that uniformity is the whole reason the effect reads as blocks rather than as low
 * detail. This prints whether it does.
 */
public final class VoxelProbe {
	public static void main(String[] args) {
		float pixels = args.length > 0 ? Float.parseFloat(args[0]) : 1f;
		MeshBaker.setVoxelSize(pixels / 16f);
		float voxel = MeshBaker.voxelSize();
		long seed = 4242L;

		// A second argument is one creature's genome code, for the question this probe gets asked
		// in anger: why is *that* one blockier than everything standing next to it.
		if (args.length > 1) {
			one(args[1], voxel);
			return;
		}

		System.out.printf("base voxel %.4f blocks (%.2f px), NEAR grid %d cells%n%n",
				voxel, pixels, LodTier.resolutionFor(LodTier.NEAR));
		System.out.printf("%-13s %8s %9s | %s%n",
				"archetype", "span", "thinnest", "cell size in pixels, by LOD tier");
		System.out.printf("%-13s %8s %9s | %8s %8s %8s %8s%n",
				"", "", "", "NEAR", "MID", "FAR", "DISTANT");
		for (Archetype a : Archetype.VALUES) {
			Random r = new Random(seed + a.ordinal() * 7919L);
			BodyPlan plan = BodyPlanBuilder.build(a.create(r));
			float span = Math.max(plan.boundsMax.x - plan.boundsMin.x,
					Math.max(plan.boundsMax.y - plan.boundsMin.y,
							plan.boundsMax.z - plan.boundsMin.z));
			StringBuilder row = new StringBuilder();
			for (int tier = 0; tier < LodTier.COUNT; tier++) {
				int cells = cellsFor(plan, span, LodTier.resolutionFor(tier));
				row.append(String.format(" %8.2f", SurfaceNets.voxelCell(span, cells, voxel) * 16f));
			}
			System.out.printf("%-13s %8.2f %9.4f |%s%n", a, span, plan.minLimbRadius, row);
		}
		population(voxel, seed);
	}

	/** Mirrors MeshBaker.resolutionFor, which raises the grid until thin limbs survive it. */
	private static int cellsFor(BodyPlan plan, float span, int requested) {
		if (plan.minLimbRadius <= 1e-5f || span <= 1e-5f) return requested;
		int needed = (int) Math.ceil(span / (plan.minLimbRadius * 0.9f));
		if (plan.minLimbGap != Float.MAX_VALUE && plan.minLimbGap > 1e-5f) {
			needed = Math.max(needed, (int) Math.ceil(span / (plan.minLimbGap * 0.5f)));
		}
		if (MeshBaker.voxelSize() > 0f) {
			needed = Math.max(needed, (int) Math.ceil(span / MeshBaker.voxelSize()));
		}
		int ceiling = Math.min(Math.round(requested * 1.8f), LodTier.maxResolution());
		return Math.max(requested, Math.min(needed, ceiling));
	}

	/** The population, not the eleven representatives: what fraction gets which voxel at NEAR. */
	static void population(float voxel, long seed) {
		System.out.println();
		System.out.println("population at NEAR, 300 genomes per archetype");
		System.out.printf("%-13s %8s %8s %8s %8s %10s%n",
				"archetype", "1px", "2px", "4px", "8px", "worstSpan");
		int[] grand = new int[8];
		for (Archetype a : Archetype.VALUES) {
			int[] hist = new int[8];
			float worst = 0f;
			for (int n = 0; n < 300; n++) {
				Random r = new Random(seed + a.ordinal() * 7919L + n * 104729L);
				BodyPlan plan = BodyPlanBuilder.build(a.create(r));
				float span = Math.max(plan.boundsMax.x - plan.boundsMin.x,
						Math.max(plan.boundsMax.y - plan.boundsMin.y,
								plan.boundsMax.z - plan.boundsMin.z));
				int cells = cellsFor(plan, span, LodTier.resolutionFor(LodTier.NEAR));
				float px = SurfaceNets.voxelCell(span, cells, voxel) * 16f / (voxel * 16f);
				int bucket = Math.min(3, Integer.numberOfTrailingZeros(Math.max(1, (int) px)));
                hist[bucket]++;
                grand[bucket]++;
				if (px > 1.5f) worst = Math.max(worst, span);
			}
			System.out.printf("%-13s %7.1f%% %7.1f%% %7.1f%% %7.1f%% %10.2f%n", a,
					hist[0] / 3f, hist[1] / 3f, hist[2] / 3f, hist[3] / 3f, worst);
		}
		int tot = grand[0] + grand[1] + grand[2] + grand[3];
		System.out.printf("%-13s %7.1f%% %7.1f%% %7.1f%% %7.1f%%%n", "ALL",
				grand[0] * 100f / tot, grand[1] * 100f / tot,
				grand[2] * 100f / tot, grand[3] * 100f / tot);
	}

	/** One named creature, tier by tier, against the same numbers the population table reports. */
	static void one(String code, float voxel) {
		dev.jsz.primordia.genome.Genome g = dev.jsz.primordia.genome.Genome.decode(code);
		if (g == null) {
			System.out.println("not a genome code");
			return;
		}
		BodyPlan plan = BodyPlanBuilder.build(g);
		float span = Math.max(plan.boundsMax.x - plan.boundsMin.x,
				Math.max(plan.boundsMax.y - plan.boundsMin.y,
						plan.boundsMax.z - plan.boundsMin.z));
		System.out.printf("span %.2f blocks, thinnest limb %.4f, min limb gap %.4f%n",
				span, plan.minLimbRadius, plan.minLimbGap == Float.MAX_VALUE ? -1f : plan.minLimbGap);
		System.out.printf("%-8s %8s %10s %10s %10s%n", "tier", "cells", "cell(px)", "vs base", "quads");
		for (int tier = 0; tier < LodTier.COUNT; tier++) {
			int cells = cellsFor(plan, span, LodTier.resolutionFor(tier));
			float cell = SurfaceNets.voxelCell(span, cells, voxel);
			int quads = MeshBaker.bake(plan, LodTier.resolutionFor(tier)).quadCount;
			System.out.printf("%-8d %8d %10.2f %10.2fx %10d%n", tier, cells, cell * 16f, cell / voxel, quads);
		}
		// What it would cost to hold this creature to the base voxel at the near tier.
		int atBase = (int) Math.ceil(span / voxel);
		System.out.printf("holding NEAR to 1px would need %d cells: %d quads%n",
				atBase, MeshBaker.bake(plan, atBase).quadCount);
	}
}
