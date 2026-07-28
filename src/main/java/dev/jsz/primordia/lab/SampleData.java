package dev.jsz.primordia.lab;

import dev.jsz.primordia.genome.Genome;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;

/**
 * The genetic payload an item carries through the lab pipeline.
 * <p>
 * One record travels the whole way: a swab taken off a live animal, the sequence read out of it,
 * and the report decoded from that are all the same {@link Genome} wearing different labels. Keeping
 * it as a single payload rather than three parallel formats is what lets a sample stay traceable —
 * the report at the end names the individual it came from, and can be checked against the animal.
 * <p>
 * Stored under {@code minecraft:custom_data} rather than a registered component type. That costs
 * some elegance and buys the guarantee that two samples from different creatures never stack, since
 * stacking is decided by component equality and the encoded genome differs.
 */
public record SampleData(Genome genome, long collectedAtTick, String lineageHex) {

	private static final String ROOT = "PrimordiaSample";
	private static final String KEY_GENOME = "Genome";
	private static final String KEY_COLLECTED = "Collected";
	private static final String KEY_LINEAGE = "Lineage";

	/**
	 * How long a sample keeps before it is no good, in ticks. Two in-game days at room
	 * temperature — long enough to walk a sample home from a expedition, short enough that
	 * stockpiling them without a {@link dev.jsz.primordia.block.PreservationCaseBlock} is a losing
	 * strategy.
	 */
	public static final long SHELF_LIFE = 48_000L;

	/** Builds the payload for a freshly taken swab. */
	public static SampleData of(Genome genome, long worldTime) {
		return new SampleData(genome, worldTime, shortLineage(genome));
	}

	/** Six hex digits of the lineage id — the same label the scanner and the survey print. */
	public static String shortLineage(Genome genome) {
		String hex = Long.toHexString(genome.lineage());
		return hex.substring(0, Math.min(6, hex.length())).toUpperCase();
	}

	/**
	 * How fresh this sample is, 1 at the moment of collection falling to 0 at {@link #SHELF_LIFE}.
	 * <p>
	 * A preserved sample reports 1 forever; see {@link #isPreserved}. Freshness is not merely
	 * cosmetic — {@link GeneSequencing} pays out less usable sequence from a degraded swab, so a
	 * player who lets samples rot gets worse data rather than no data, which is the more
	 * interesting failure.
	 */
	public float freshness(long worldTime) {
		if (collectedAtTick < 0L) return 1f;
		long age = Math.max(0L, worldTime - collectedAtTick);
		if (age >= SHELF_LIFE) return 0f;
		return 1f - (float) age / SHELF_LIFE;
	}

	/** A sample whose clock has been stopped by cold storage. */
	public boolean isPreserved() {
		return collectedAtTick < 0L;
	}

	/** Returns a copy with its clock stopped, as happens when it goes into a Preservation Case. */
	public SampleData preserved() {
		return isPreserved() ? this : new SampleData(genome, -1L, lineageHex);
	}

	/** Returns a copy whose clock restarts now, as happens when it comes back out of cold storage. */
	public SampleData thawed(long worldTime) {
		return isPreserved() ? new SampleData(genome, worldTime, lineageHex) : this;
	}

	// ------------------------------------------------------------------ item stacks

	/** Reads the payload off a stack, or null when the stack is not carrying one. */
	public static SampleData get(ItemStack stack) {
		NbtComponent component = stack.get(DataComponentTypes.CUSTOM_DATA);
		if (component == null) return null;
		NbtCompound root = component.copyNbt();
		if (!root.contains(ROOT)) return null;
		NbtCompound nbt = root.getCompound(ROOT);

		Genome genome = Genome.decode(nbt.getString(KEY_GENOME));
		if (genome == null) return null;
		return new SampleData(genome, nbt.getLong(KEY_COLLECTED), nbt.getString(KEY_LINEAGE));
	}

	/** Writes the payload onto a stack, replacing any already there. */
	public void write(ItemStack stack) {
		NbtComponent existing = stack.get(DataComponentTypes.CUSTOM_DATA);
		NbtCompound root = existing == null ? new NbtCompound() : existing.copyNbt();

		NbtCompound nbt = new NbtCompound();
		nbt.putString(KEY_GENOME, genome.encode());
		nbt.putLong(KEY_COLLECTED, collectedAtTick);
		nbt.putString(KEY_LINEAGE, lineageHex);
		root.put(ROOT, nbt);

		stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(root));
	}

	/** Convenience: a stack of {@code item} already carrying this payload. */
	public ItemStack onto(net.minecraft.item.Item item) {
		ItemStack stack = new ItemStack(item);
		write(stack);
		return stack;
	}
}
