package dev.jsz.primordia.lab;

import com.mojang.serialization.Codec;
import dev.jsz.primordia.Primordia;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.genome.Mutation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Comparator;

/**
 * What this world's science knows, accumulated across every genome ever decoded in it.
 * <p>
 * The point of the whole pipeline is that a first encounter tells you almost nothing and a
 * well-studied species tells you everything, so something has to remember which is which. That is
 * this: a per-lineage tally of how many individuals have been put through a decoder, persisted with
 * the save, and consulted by {@link DecodeAccuracy} every time a report is produced.
 * <p>
 * Keyed by lineage rather than by genome. Two members of a species are not the same genome — they
 * differ at every quantitative locus — and requiring an exact match would mean no amount of work
 * ever improved anything. The lineage id is the mod's existing notion of "same kind of animal",
 * forked by {@link dev.jsz.primordia.genome.Mutation} when a population diverges far enough to
 * count as something new, which is exactly the granularity a reference library wants: study a
 * species and you understand its members; watch it speciate and the new branch is unfamiliar again.
 */
public class GenomeLibrary extends SavedData {

	/** The mod id now comes from the identifier's namespace, so the path is the bare noun. */
	private static final String KEY = "genome_library";

	/**
	 * How much of a lineage's resolution may be borrowed from its relatives.
	 * <p>
	 * Five is deliberately below {@link DecodeAccuracy#GOOD}'s requirement, so a shelf full of close
	 * cousins can carry a newly met species as far as a partial description and no further. Anything
	 * better has to come from specimens of the animal itself. References contextualise your own work;
	 * they do not stand in for it.
	 */
	private static final int MAX_BORROWED = 5;

	/** One species' entry in the library. */
	public static final class Entry {
		public final long lineage;
		/**
		 * Fingerprints of the distinct individuals of this lineage that have been decoded.
		 * <p>
		 * A set rather than a tally, and this is the whole point: two samples cut from the same
		 * animal encode to byte-identical genomes, so the second one lands on a fingerprint already
		 * present and moves nothing. Sampling one creature five times tells you what one creature is;
		 * it is five readings of a single data point, and a tally could not tell the difference.
		 */
		public final java.util.Set<Long> specimens = new java.util.LinkedHashSet<>();
		/** Generation of the most recent specimen, so the listing shows a lineage advancing. */
		public int latestGeneration;
		/** A representative genome, kept so the bank can describe a species with no specimen to hand. */
		public String representative = "";
		public String label = "";

		Entry(long lineage) {
			this.lineage = lineage;
		}
	}

	private final Map<Long, Entry> entries = new HashMap<>();

	/** See {@link dev.jsz.primordia.ecology.region.RegionLedger#CODEC} on the tag-shaped codec. */
	public static final Codec<GenomeLibrary> CODEC =
			CompoundTag.CODEC.xmap(GenomeLibrary::fromNbt, GenomeLibrary::toNbt);

	public static final SavedDataType<GenomeLibrary> TYPE = new SavedDataType<>(
			Primordia.id(KEY), GenomeLibrary::new, CODEC,
			DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES);

	public static GenomeLibrary get(ServerLevel world) {
		// Bound to the overworld deliberately. A library is the player's accumulated knowledge, not
		// a property of the dimension they happened to be standing in when they decoded something.
		SavedDataStorage manager = world.getServer().overworld().getDataStorage();
		return manager.computeIfAbsent(TYPE);
	}

	/**
	 * Files one decoded individual and returns how many of its lineage are now on record,
	 * <b>including</b> this one.
	 */
	public int record(Genome genome) {
		Entry entry = entries.computeIfAbsent(genome.lineage(), Entry::new);
		entry.specimens.add(fingerprint(genome));
		entry.latestGeneration = Math.max(entry.latestGeneration, genome.generation());
		if (entry.representative.isEmpty()) {
			entry.representative = genome.encode();
			entry.label = SampleData.shortLineage(genome);
		}
		setDirty();
		return entry.specimens.size();
	}

	/**
	 * Identity of one individual, for telling specimens apart.
	 * <p>
	 * The encoded genome is the animal: every locus it has, at the values it has them. Two creatures
	 * of a lineage differ at the quantitative loci and hash apart; two swabs off one creature do not.
	 */
	private static long fingerprint(Genome genome) {
		String code = genome.encode();
		long h = 1125899906842597L;
		for (int i = 0; i < code.length(); i++) {
			h = 31L * h + code.charAt(i);
		}
		return h;
	}

	/** Whether this exact individual has already been through a decoder. */
	public boolean hasSpecimen(Genome genome) {
		Entry entry = entries.get(genome.lineage());
		return entry != null && entry.specimens.contains(fingerprint(genome));
	}

	/** How many <b>distinct</b> individuals of this lineage have been decoded. */
	public int distinctCount(long lineage) {
		Entry entry = entries.get(lineage);
		return entry == null ? 0 : entry.specimens.size();
	}

	/**
	 * How well referenced a species is: its own specimens, plus what its relatives already say.
	 * <p>
	 * This is what a real characterisation looks like. A genome in isolation is a column of numbers
	 * with nothing to measure it against; it acquires meaning from the other genomes around it, and a
	 * locus is only readable once you have seen what else that locus does. So a species is understood
	 * through the web of things already understood near it — study a family and each new member of it
	 * is legible sooner, which is also the reward for having done the work.
	 * <p>
	 * Relatedness is measured, not recorded: {@link Mutation#distance} over the weighted loci, the
	 * same measure {@link Phylogeny} draws its tree from. Contribution falls off with the square of
	 * the distance and reaches nothing at {@link Phylogeny#UNRELATED}, so a sister species counts for
	 * a great deal and a distant one for almost nothing.
	 */
	public int referenceStrength(Genome subject) {
		int own = distinctCount(subject.lineage());

		float borrowed = 0f;
		for (Entry other : entries.values()) {
			if (other.lineage == subject.lineage() || other.specimens.isEmpty()) continue;
			Genome representative = Genome.decode(other.representative);
			if (representative == null) continue;

			float d = Mutation.distance(subject, representative);
			if (d >= Phylogeny.UNRELATED) continue;
			float closeness = 1f - d / Phylogeny.UNRELATED;
			borrowed += other.specimens.size() * closeness * closeness;
		}

		return own + Math.min(MAX_BORROWED, Math.round(borrowed));
	}

	/** Every species on record, most-studied first. */
	public List<Entry> all() {
		List<Entry> out = new ArrayList<>(entries.values());
		out.sort(Comparator.comparingInt((Entry e) -> e.specimens.size()).reversed());
		return out;
	}

	public int speciesKnown() {
		return entries.size();
	}

	// ---------------------------------------------------------------- persistence

	public CompoundTag toNbt() {
		CompoundTag nbt = new CompoundTag();
		ListTag list = new ListTag();
		for (Entry entry : entries.values()) {
			CompoundTag e = new CompoundTag();
			e.putLong("Lineage", entry.lineage);
			long[] specimens = new long[entry.specimens.size()];
			int at = 0;
			for (Long fingerprint : entry.specimens) specimens[at++] = fingerprint;
			e.putLongArray("Specimens", specimens);
			e.putInt("Generation", entry.latestGeneration);
			e.putString("Representative", entry.representative);
			e.putString("Label", entry.label);
			list.add(e);
		}
		nbt.put("Entries", list);
		return nbt;
	}

	private static GenomeLibrary fromNbt(CompoundTag nbt) {
		GenomeLibrary library = new GenomeLibrary();
		ListTag list = nbt.getListOrEmpty("Entries");
		for (int i = 0; i < list.size(); i++) {
			CompoundTag e = list.getCompoundOrEmpty(i);
			Entry entry = new Entry(e.getLongOr("Lineage", 0L));
			long[] specimens = e.getLongArray("Specimens").orElse(new long[0]);
			for (long fingerprint : specimens) entry.specimens.add(fingerprint);
			if (specimens.length == 0) {
				// A library written before specimens were told apart holds only a tally, and the
				// individuals behind it are unrecoverable. Seeding one placeholder per decode keeps
				// the count the player earned; they are distinct from each other and from any real
				// fingerprint, so a genuine specimen decoded later still registers as new.
				int legacy = e.getIntOr("Decoded", 0);
				for (int n = 0; n < legacy; n++) entry.specimens.add(~((entry.lineage * 31L) + n));
			}
			entry.latestGeneration = e.getIntOr("Generation", 0);
			entry.representative = e.getStringOr("Representative", "");
			entry.label = e.getStringOr("Label", "");
			library.entries.put(entry.lineage, entry);
		}
		return library;
	}
}
