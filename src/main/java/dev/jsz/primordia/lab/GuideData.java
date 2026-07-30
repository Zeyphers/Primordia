package dev.jsz.primordia.lab;

import dev.jsz.primordia.genome.Genome;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.ListTag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The reference section of a field guide: one entry per species the owner has filed.
 * <p>
 * Stored on the guide's own stack rather than in world data, and that is the important decision.
 * The screen that renders the guide runs on the client, and item components are replicated to the
 * client for free — so the guide can be read with no packet, no synchronisation and no way for the
 * two sides to disagree about what the player knows. A world-side library would have needed all
 * three.
 * <p>
 * It also makes the guide an object rather than an account. It is a book you own, carry, lose in
 * lava, and could hand to someone else; what is written in it is what you put there.
 */
public final class GuideData {

	private static final String ROOT = "PrimordiaGuide";
	private static final String KEY_ENTRIES = "Entries";

	/** Longest name a species may be given. Enough for a binomial, short enough to fit a plate. */
	public static final int MAX_NAME = 20;

	/** One species as the guide records it. */
	public record Entry(long lineage, String label, int filed, int generation, String genomeCode,
	                    String name) {

		/** What to call it: the name its discoverer gave it, or the bloodline's own marking. */
		public String displayName() {
			return name == null || name.isEmpty() ? label : name;
		}

		public boolean named() {
			return name != null && !name.isEmpty();
		}

		/**
		 * Whether this species has been studied thoroughly enough to be worth naming.
		 * <p>
		 * Naming rights are earned rather than given. A creature met once is a sighting; twelve
		 * filed specimens is a description, and describing something is what has always entitled
		 * a naturalist to name it.
		 */
		public boolean nameable() {
			return accuracy() == DecodeAccuracy.COMPLETE;
		}

		/** The representative specimen, or null if the stored code will not decode. */
		public Genome genome() {
			return Genome.decode(genomeCode);
		}

		/** How well this species is characterised, from how many of it have been filed. */
		public DecodeAccuracy accuracy() {
			return DecodeAccuracy.resolve(filed, 1f);
		}
	}

	private final List<Entry> entries;

	private GuideData(List<Entry> entries) {
		this.entries = entries;
	}

	public static GuideData empty() {
		return new GuideData(new ArrayList<>());
	}

	/**
	 * Merges another guide's contents into this one. Used for migrating legacy item-based guides.
	 */
	public void merge(GuideData other) {
		for (Entry entry : other.entries) {
			Genome g = entry.genome();
			if (g != null) {
				for (int i = 0; i < entry.filed(); i++) {
					file(g);
				}
				if (entry.named()) {
					rename(entry.lineage(), entry.name());
				}
			}
		}
	}

	public static GuideData get(ItemStack stack) {
		CustomData component = stack.get(DataComponents.CUSTOM_DATA);
		return fromNbt(component == null ? new CompoundTag() : component.copyTag());
	}

	public void write(ItemStack stack) {
		CustomData existing = stack.get(DataComponents.CUSTOM_DATA);
		CompoundTag root = existing == null ? new CompoundTag() : existing.copyTag();
		writeInto(root);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
	}

	/**
	 * The stack-free half of the storage, so the record keeping can be exercised without a live
	 * item registry — which a headless test has no way to bootstrap.
	 */
	public static GuideData fromNbt(CompoundTag root) {
		List<Entry> out = new ArrayList<>();
		if (root.contains(ROOT)) {
			ListTag list = root.getCompoundOrEmpty(ROOT).getListOrEmpty(KEY_ENTRIES);
			for (int i = 0; i < list.size(); i++) {
				CompoundTag e = list.getCompoundOrEmpty(i);
				out.add(new Entry(e.getLongOr("Lineage", 0L), e.getStringOr("Label", ""),
						e.getIntOr("Filed", 0), e.getIntOr("Generation", 0), e.getStringOr("Genome", ""),
						e.getStringOr("Name", "")));
			}
		}
		return new GuideData(out);
	}

	public void writeInto(CompoundTag root) {
		ListTag list = new ListTag();
		for (Entry entry : entries) {
			CompoundTag e = new CompoundTag();
			e.putLong("Lineage", entry.lineage());
			e.putString("Label", entry.label());
			e.putInt("Filed", entry.filed());
			e.putInt("Generation", entry.generation());
			e.putString("Genome", entry.genomeCode());
			e.putString("Name", entry.name() == null ? "" : entry.name());
			list.add(e);
		}
		CompoundTag guide = new CompoundTag();
		guide.put(KEY_ENTRIES, list);
		root.put(ROOT, guide);
	}

	/**
	 * Files one decoded specimen, merging it into its species' entry.
	 * <p>
	 * Merging rather than appending is the whole point of the guide: twelve individuals of one
	 * lineage become one entry that reads "12 filed" and resolves to exact figures, instead of
	 * twelve report items competing for inventory space and saying almost the same thing.
	 *
	 * @return true if anything changed, so the caller can skip a write when it would be a no-op
	 */
	public boolean file(Genome genome) {
		for (int i = 0; i < entries.size(); i++) {
			Entry entry = entries.get(i);
			if (entry.lineage() != genome.lineage()) continue;
			entries.set(i, new Entry(entry.lineage(), entry.label(), entry.filed() + 1,
					Math.max(entry.generation(), genome.generation()),
					// Keep the newest specimen as the representative, so a lineage that is visibly
					// evolving is illustrated by what it has become rather than what it was.
					genome.generation() >= entry.generation() ? genome.encode() : entry.genomeCode(),
					// A name survives every later specimen. Losing it on the thirteenth would be
					// the cruellest possible moment to take it away.
					entry.name()));
			return true;
		}
		entries.add(new Entry(genome.lineage(), SampleData.shortLineage(genome), 1,
				genome.generation(), genome.encode(), ""));
		return true;
	}

	/**
	 * Names a species, if it has been studied enough to earn one.
	 *
	 * @return true if the name was accepted, false if the lineage is unknown or not yet complete
	 */
	public boolean rename(long lineage, String name) {
		String trimmed = name == null ? "" : name.strip();
		if (trimmed.length() > MAX_NAME) trimmed = trimmed.substring(0, MAX_NAME);
		for (int i = 0; i < entries.size(); i++) {
			Entry entry = entries.get(i);
			if (entry.lineage() != lineage) continue;
			// Checked here rather than trusting the caller: this runs on the server against a
			// packet, and the client that sent it is not an authority on what it has studied.
			if (!entry.nameable()) return false;
			entries.set(i, new Entry(entry.lineage(), entry.label(), entry.filed(),
					entry.generation(), entry.genomeCode(), trimmed));
			return true;
		}
		return false;
	}

	/** Every species on file, best-studied first. */
	public List<Entry> entries() {
		List<Entry> out = new ArrayList<>(entries);
		out.sort(Comparator.comparingInt(Entry::filed).reversed()
				.thenComparing(Entry::label));
		return out;
	}

	public int speciesCount() {
		return entries.size();
	}

	public int specimensFiled() {
		int total = 0;
		for (Entry entry : entries) total += entry.filed();
		return total;
	}
}
