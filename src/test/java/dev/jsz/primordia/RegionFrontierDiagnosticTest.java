package dev.jsz.primordia;

import dev.jsz.primordia.ecology.region.LineageRecord;
import dev.jsz.primordia.ecology.region.RegionFounder;
import dev.jsz.primordia.ecology.region.RegionMaterialiser;
import dev.jsz.primordia.ecology.region.RegionNeighbourhood;
import dev.jsz.primordia.ecology.region.RegionPos;
import dev.jsz.primordia.ecology.region.RegionRecord;
import dev.jsz.primordia.genome.Archetype;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * DIAGNOSTIC ONLY — written to investigate "fly a long way from spawn and the creatures stop being
 * there". Asserts almost nothing; it measures and writes a report to
 * {@code build/region-frontier-diagnostic.txt}.
 * <p>
 * Delete when the question is settled.
 */
class RegionFrontierDiagnosticTest {

	private static final StringBuilder REPORT = new StringBuilder();

	/** Same fake as RegionLedgerTest: migration and inherited founders only ever read. */
	private static final class FakeWorld implements RegionNeighbourhood {
		final Map<Long, RegionRecord> records = new HashMap<>();

		@Override
		public RegionRecord existing(RegionPos pos) {
			return records.get(pos.key());
		}

		RegionRecord create(RegionPos pos, long worldSeed) {
			RegionRecord record = new RegionRecord(pos);
			record.seed = pos.seed(worldSeed);
			records.put(pos.key(), record);
			return record;
		}
	}

	/** A middling plains-like climate, so the biome is never the variable under test. */
	private static RegionFounder.Climate plains() {
		return new RegionFounder.Climate(0.55f, 0.48f, 0.68f, 0.18f);
	}

	private static void line(String s) {
		REPORT.append(s).append('\n');
		System.out.println(s);
	}

	@AfterAll
	static void writeReport() throws IOException {
		Path out = Path.of("build", "region-frontier-diagnostic.txt");
		Files.createDirectories(out.getParent());
		Files.writeString(out, REPORT.toString());
	}

	// ------------------------------------------------------------------ measures

	private static float surfacePop(RegionRecord record) {
		float sum = 0f;
		for (LineageRecord l : record.lineages) {
			if (!Archetype.isSubterranean(l.meanGenome())) sum += l.total();
		}
		return sum;
	}

	private static float cavePop(RegionRecord record) {
		float sum = 0f;
		for (LineageRecord l : record.lineages) {
			if (Archetype.isSubterranean(l.meanGenome())) sum += l.total();
		}
		return sum;
	}

	/** Exactly what {@code RegionMaterialiser.topUp} would place on the surface, given this record. */
	private static int surfaceEntities(RegionRecord record) {
		float[] pops = new float[record.lineages.size()];
		for (int i = 0; i < pops.length; i++) {
			LineageRecord l = record.lineages.get(i);
			pops[i] = Archetype.isSubterranean(l.meanGenome()) ? 0f : l.count;
		}
		int[] quota = RegionMaterialiser.allocate(pops, RegionMaterialiser.ENTITY_BUDGET);
		int total = 0;
		for (int q : quota) total += q;
		return total;
	}

	// ---------------------------------------------------- H1 + H3: distance and sign

	/**
	 * Founds an isolated region at increasing block distances from the origin, positive and
	 * negative. No neighbours exist at all, so this is purely: does the coordinate itself change
	 * what a frontier region is founded with?
	 */
	@Test
	void isolatedFoundingAtIncreasingDistanceFromOrigin() {
		line("");
		line("=== H1/H3: isolated founding vs distance from origin (no neighbours anywhere) ===");
		line("blockX        regionX     n   lineages  surfacePop  cavePop  surfaceEntities  empty");

		long[] distances = {128, 1024, 10240, 102400, 1024000, 8192000,
				-128, -1024, -10240, -102400, -1024000, -8192000};
		int samples = 24;

		for (long blockX : distances) {
			int regionX = (int) (blockX >> 7);
			int lineages = 0;
			float surface = 0f;
			float cave = 0f;
			int entities = 0;
			int emptySurface = 0;
			for (int s = 0; s < samples; s++) {
				FakeWorld world = new FakeWorld();
				RegionPos pos = new RegionPos(regionX, s * 13);
				RegionRecord record = world.create(pos, 20260802L);
				RegionFounder.found(world, record, plains(), "plains", 0);
				lineages += record.lineages.size();
				surface += surfacePop(record);
				cave += cavePop(record);
				int e = surfaceEntities(record);
				entities += e;
				if (e == 0) emptySurface++;
			}
			line(String.format("%-13d %-11d %-3d %-9.2f %-11.2f %-8.2f %-16.2f %d/%d",
					blockX, regionX, samples,
					lineages / (float) samples,
					surface / samples,
					cave / samples,
					entities / (float) samples,
					emptySurface, samples));
		}
	}

	// -------------------------------------------- H1b: founded neighbours at founding time

	/**
	 * The same region, founded with a varying number of already-founded neighbours around it.
	 * <p>
	 * Two flavours of neighbour, because the two things that read the neighbourhood disagree about
	 * what counts: {@code RegionFounder.inheritFrom} needs a neighbour that is founded <i>and</i>
	 * has lineages, whereas {@code RegionSimulation.migrate} — which runs on every one of the
	 * 100–300 pre-history steps — only needs it to be founded.
	 */
	@Test
	void foundingWithVaryingNumbersOfFoundedNeighbours() {
		line("");
		line("=== H1b: effect of already-founded neighbours on what a region is founded with ===");
		line("neighbours  kind        n    lineages  surfacePop  cavePop  surfaceEntities  emptySurface");

		int samples = 40;
		for (String kind : new String[]{"populated", "empty-shell"}) {
			for (int neighbours = 0; neighbours <= 4; neighbours++) {
				int lineages = 0;
				float surface = 0f;
				float cave = 0f;
				int entities = 0;
				int emptySurface = 0;
				for (int s = 0; s < samples; s++) {
					FakeWorld world = new FakeWorld();
					RegionPos pos = new RegionPos(500 + s * 7, 500);
					RegionRecord record = world.create(pos, 4242L);

					RegionPos[] around = pos.neighbours();
					for (int n = 0; n < neighbours; n++) {
						RegionRecord neighbour = world.create(around[n], 4242L);
						if (kind.equals("populated")) {
							RegionFounder.found(world, neighbour, plains(), "plains", 0);
						} else {
							neighbour.founded = true;
							neighbour.productivity = 0.68f;
							neighbour.vegetation = 0.61f;
							neighbour.temperature = 0.55f;
							neighbour.humidity = 0.48f;
						}
					}

					RegionFounder.found(world, record, plains(), "plains", 0);
					lineages += record.lineages.size();
					surface += surfacePop(record);
					cave += cavePop(record);
					int e = surfaceEntities(record);
					entities += e;
					if (e == 0) emptySurface++;
				}
				line(String.format("%-11d %-11s %-4d %-9.2f %-11.2f %-8.2f %-16.2f %d/%d",
						neighbours, kind, samples,
						lineages / (float) samples,
						surface / samples,
						cave / samples,
						entities / (float) samples,
						emptySurface, samples));
			}
		}
	}

	// ------------------------------------------------------- H2/H5: a simulated flight

	/**
	 * Emulates {@code EcologyTicker.tick}'s founding order for a player travelling in a straight
	 * line: a 3×3 active window, regions founded in the same dx/dz order, one pass per step.
	 * <p>
	 * The day clock does not advance over a flight of a few minutes (a step is 24000 ticks), so
	 * integration is a no-op and founding is the only thing happening — exactly as in play.
	 */
	@Test
	void populationAlongASimulatedStraightLineFlight() {
		line("");
		line("=== H2/H5: 3x3 active window flown in a straight line, EcologyTicker order ===");
		line("regionIndex  blockX     lineages  surfacePop  cavePop  surfaceEntities");

		FakeWorld world = new FakeWorld();
		int passes = 60;
		int[] indexOfInterest = new int[passes];
		for (int i = 0; i < passes; i++) indexOfInterest[i] = i;

		for (int pass = 0; pass < passes; pass++) {
			RegionPos centre = new RegionPos(pass, 0);
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					RegionPos pos = centre.offset(dx, dz);
					RegionRecord record = world.existing(pos);
					if (record == null) record = world.create(pos, 777L);
					if (!record.founded) {
						RegionFounder.found(world, record, plains(), "plains", 0);
					}
				}
			}
		}

		for (int i = 0; i < passes; i++) {
			RegionRecord record = world.existing(new RegionPos(i, 0));
			line(String.format("%-12d %-10d %-9d %-11.2f %-8.2f %d",
					i, i * 128, record.lineages.size(),
					surfacePop(record), cavePop(record), surfaceEntities(record)));
		}

		// Summary: first ten regions of the flight against the last ten.
		float earlySurface = 0f, lateSurface = 0f;
		int earlyEmpty = 0, lateEmpty = 0;
		for (int i = 0; i < 10; i++) {
			RegionRecord r = world.existing(new RegionPos(i, 0));
			earlySurface += surfacePop(r);
			if (surfaceEntities(r) == 0) earlyEmpty++;
		}
		for (int i = passes - 10; i < passes; i++) {
			RegionRecord r = world.existing(new RegionPos(i, 0));
			lateSurface += surfacePop(r);
			if (surfaceEntities(r) == 0) lateEmpty++;
		}
		line(String.format("first 10 regions: mean surface pop %.2f, %d/10 with no surface entities",
				earlySurface / 10f, earlyEmpty));
		line(String.format("last  10 regions: mean surface pop %.2f, %d/10 with no surface entities",
				lateSurface / 10f, lateEmpty));
	}

	/**
	 * The same flight, but counting every region in the 3×3 swathe rather than the centre row, and
	 * reporting how many regions in the whole explored area would show the player nothing.
	 */
	@Test
	void wholeSwatheAfterAFlight() {
		line("");
		line("=== H2/H5: whole 3x3 swathe after a 60-region flight ===");

		FakeWorld world = new FakeWorld();
		int passes = 60;
		for (int pass = 0; pass < passes; pass++) {
			RegionPos centre = new RegionPos(pass, 0);
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					RegionPos pos = centre.offset(dx, dz);
					RegionRecord record = world.existing(pos);
					if (record == null) record = world.create(pos, 20260802L);
					if (!record.founded) {
						RegionFounder.found(world, record, plains(), "plains", 0);
					}
				}
			}
		}

		int total = 0, noSurfaceEntities = 0, noLineages = 0, noSurfaceLineages = 0;
		float surface = 0f;
		for (RegionRecord record : world.records.values()) {
			if (!record.founded) continue;
			total++;
			surface += surfacePop(record);
			if (record.lineages.isEmpty()) noLineages++;
			if (surfacePop(record) <= 0f) noSurfaceLineages++;
			if (surfaceEntities(record) == 0) noSurfaceEntities++;
		}
		line(String.format("founded regions %d · mean surface pop %.2f", total, surface / total));
		line(String.format("regions with no lineages at all      : %d/%d", noLineages, total));
		line(String.format("regions with zero surface population : %d/%d", noSurfaceLineages, total));
		line(String.format("regions the materialiser would place nothing on the surface in: %d/%d",
				noSurfaceEntities, total));
	}

	// ------------------------------------------------- H-chunk: the placement chunk probe

	/**
	 * Replays the chunk-availability scan at the top of {@code RegionMaterialiser.place}
	 * (RegionMaterialiser.java:313-323) and reports which chunk {@code LevelReader} is actually
	 * being asked about.
	 * <p>
	 * {@code hasChunk(int,int)} takes chunk coordinates. {@code hasChunkAt(int,int)} takes
	 * <b>block</b> coordinates and shifts them down by four
	 * ({@code SectionPos.blockToSectionCoord}). {@code place} passes chunk coordinates to
	 * {@code hasChunkAt}, so every one of its 64 probes is shifted down a second time.
	 */
	@Test
	void placementChunkProbeIsShiftedTowardTheOrigin() {
		line("");
		line("=== H-chunk: which chunk RegionMaterialiser.place actually asks hasChunkAt about ===");
		line("regionX  regionBlockX  intendedChunks  distinctProbedChunks  probedChunkX  probedBlockX  gapBlocks");

		int[] regionXs = {0, 1, 2, 3, 4, 5, 8, 16, 39, 78, 156, 781, 7812,
				-1, -2, -4, -8, -39, -781};
		for (int rx : regionXs) {
			RegionPos pos = new RegionPos(rx, 0);
			int minChunkX = pos.minBlockX() >> 4;

			java.util.Set<Integer> intended = new java.util.LinkedHashSet<>();
			java.util.Set<Integer> probed = new java.util.LinkedHashSet<>();
			for (int cx = minChunkX; cx < minChunkX + RegionPos.CHUNKS; cx++) {
				intended.add(cx);
				probed.add(net.minecraft.core.SectionPos.blockToSectionCoord(cx));
			}

			int probedChunkX = probed.iterator().next();
			int probedBlockX = probedChunkX * 16;
			line(String.format("%-8d %-13d %-15d %-21d %-13d %-13d %d",
					rx, pos.minBlockX(), intended.size(), probed.size(),
					probedChunkX, probedBlockX,
					Math.abs(pos.minBlockX() - probedBlockX)));

			// Every probe in the region collapses onto one chunk, and it is not a chunk in the
			// region: it is at one sixteenth of the region's distance from the origin.
			org.junit.jupiter.api.Assertions.assertEquals(8, intended.size());
			org.junit.jupiter.api.Assertions.assertEquals(1, probed.size(),
					"expected the doubly-shifted probe to collapse to a single chunk");
			org.junit.jupiter.api.Assertions.assertEquals(rx >> 1, probedChunkX);
		}

		line("");
		line("furthest |x| at which the probed chunk is still inside the player's loaded square");
		line("(the probe is chunk x>>8, the player is in chunk x>>4, so the gap grows as 15|x|/256)");
		line("viewDistance(chunks)  maxAbsBlockX  ownRegionOk  neighbourRegionsOk");
		for (int view : new int[]{8, 10, 12, 16, 24, 32}) {
			int last = 0;
			int lastAllNine = 0;
			for (int x = 0; x < 4_000_000; x += 8) {
				int playerChunkX = x >> 4;
				int rx = x >> 7;
				boolean own = Math.abs(playerChunkX - (rx >> 1)) <= view;
				boolean allNine = own
						&& Math.abs(playerChunkX - ((rx - 1) >> 1)) <= view
						&& Math.abs(playerChunkX - ((rx + 1) >> 1)) <= view;
				if (own) last = x;
				if (allNine) lastAllNine = x;
				if (!own && x > 100_000) break;
			}
			line(String.format("%-21d %-13d %-12s %d",
					view, last, "x<=" + last, lastAllNine));
		}
	}

	/**
	 * A control for the flight: the same 60 regions in a row, each founded in complete isolation
	 * from the others. Any difference from the flight is caused by the neighbourhood, not by the
	 * coordinates or the climate.
	 */
	@Test
	void controlSameRowFoundedInIsolation() {
		line("");
		line("=== control: the same row founded one region at a time, in isolation ===");
		float surface = 0f;
		int empty = 0;
		for (int i = 0; i < 60; i++) {
			FakeWorld world = new FakeWorld();
			RegionPos pos = new RegionPos(i, 0);
			RegionRecord record = world.create(pos, 777L);
			RegionFounder.found(world, record, plains(), "plains", 0);
			surface += surfacePop(record);
			if (surfaceEntities(record) == 0) empty++;
		}
		line(String.format("isolated row: mean surface pop %.2f, %d/60 with no surface entities",
				surface / 60f, empty));
	}
}
