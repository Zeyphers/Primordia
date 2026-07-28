package dev.jsz.primordia.lab;

import dev.jsz.primordia.genome.Genome;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateManager;

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
public class GenomeLibrary extends PersistentState {

	private static final String KEY = "primordia_genome_library";

	/** One species' entry in the library. */
	public static final class Entry {
		public final long lineage;
		/** Individuals of this lineage put through a decoder. Drives resolution. */
		public int decoded;
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

	public static GenomeLibrary get(ServerWorld world) {
		// Bound to the overworld deliberately. A library is the player's accumulated knowledge, not
		// a property of the dimension they happened to be standing in when they decoded something.
		PersistentStateManager manager = world.getServer().getOverworld().getPersistentStateManager();
		return manager.getOrCreate(new Type<>(GenomeLibrary::new, GenomeLibrary::fromNbt, null), KEY);
	}

	/**
	 * Files one decoded individual and returns how many of its lineage are now on record,
	 * <b>including</b> this one.
	 */
	public int record(Genome genome) {
		Entry entry = entries.computeIfAbsent(genome.lineage(), Entry::new);
		entry.decoded++;
		entry.latestGeneration = Math.max(entry.latestGeneration, genome.generation());
		if (entry.representative.isEmpty()) {
			entry.representative = genome.encode();
			entry.label = SampleData.shortLineage(genome);
		}
		markDirty();
		return entry.decoded;
	}

	/** How many individuals of this lineage have been decoded. Zero for something never seen. */
	public int decodedCount(long lineage) {
		Entry entry = entries.get(lineage);
		return entry == null ? 0 : entry.decoded;
	}

	/** Every species on record, most-studied first. */
	public List<Entry> all() {
		List<Entry> out = new ArrayList<>(entries.values());
		out.sort(Comparator.comparingInt((Entry e) -> e.decoded).reversed());
		return out;
	}

	public int speciesKnown() {
		return entries.size();
	}

	// ---------------------------------------------------------------- persistence

	@Override
	public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		NbtList list = new NbtList();
		for (Entry entry : entries.values()) {
			NbtCompound e = new NbtCompound();
			e.putLong("Lineage", entry.lineage);
			e.putInt("Decoded", entry.decoded);
			e.putInt("Generation", entry.latestGeneration);
			e.putString("Representative", entry.representative);
			e.putString("Label", entry.label);
			list.add(e);
		}
		nbt.put("Entries", list);
		return nbt;
	}

	private static GenomeLibrary fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		GenomeLibrary library = new GenomeLibrary();
		NbtList list = nbt.getList("Entries", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < list.size(); i++) {
			NbtCompound e = list.getCompound(i);
			Entry entry = new Entry(e.getLong("Lineage"));
			entry.decoded = e.getInt("Decoded");
			entry.latestGeneration = e.getInt("Generation");
			entry.representative = e.getString("Representative");
			entry.label = e.getString("Label");
			library.entries.put(entry.lineage, entry);
		}
		return library;
	}
}
