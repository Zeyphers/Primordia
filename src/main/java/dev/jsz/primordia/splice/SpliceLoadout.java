package dev.jsz.primordia.splice;

import dev.jsz.primordia.Primordia;
import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.util.MathX;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

import java.util.ArrayList;
import java.util.List;

/**
 * What one player is currently carrying.
 * <p>
 * An installed splice stores the donor's values <b>already clamped to the depth that was open when
 * it was taken</b>, rather than storing a reference to the donor and recomputing. Two reasons, and
 * the second is the important one: recomputing would need the guide at every attribute refresh, and
 * — worse — a player whose research later regressed, or who handed their guide to someone else,
 * would silently change shape. What you are is a fact about you, not a view over a library.
 * <p>
 * {@code MD/SPLICING.md} §6: reversion is free and deliberately so. Slots and depth caps already
 * prevent accumulation, which was the only real problem the old draft's partial refund solved, so
 * nothing here is consumed and nothing is scarred. The brake on hot-swapping is friction at the
 * splicer, not loss.
 */
public final class SpliceLoadout {

	/** Namespace for the attribute modifiers this class owns, so removal is exact. */
	private static final String MODIFIER_PREFIX = "splice/";

	/**
	 * One installed block.
	 *
	 * @param branch  which block
	 * @param lineage the donor bloodline, so the guide can say whose traits these are
	 * @param label   what the donor was called when it was taken
	 * @param values  one value per locus of the branch, in enum order, already clamped
	 */
	public record Installed(SpliceBranch branch, long lineage, String label, float[] values) {

		public float valueOf(Gene gene) {
			List<Gene> genes = branch.genes();
			int index = genes.indexOf(gene);
			return index < 0 ? 0f : values[index];
		}

		/** The branch's headline strength as actually carried, after the depth clamp. */
		public float potency() {
			return branch.cosmetic() ? 1f : valueOf(branch.headline);
		}
	}

	private final List<Installed> installed = new ArrayList<>();

	public List<Installed> installed() {
		return List.copyOf(installed);
	}

	public Installed inBranch(SpliceBranch branch) {
		for (Installed i : installed) {
			if (i.branch() == branch) return i;
		}
		return null;
	}

	public boolean isEmpty() {
		return installed.isEmpty();
	}

	public int used() {
		return installed.size();
	}

	/**
	 * Takes a block off a donor, clamped to {@code cap}, replacing whatever occupied that branch.
	 * <p>
	 * One branch may be carried once. A player wearing two different animals' physiology is not a
	 * chimera, it is a stacking bug, and the slot arithmetic in {@code MD/SPLICING.md} §6 assumes
	 * one block per slot.
	 */
	public void install(SpliceBranch branch, long lineage, String label, Genome donor, float cap) {
		List<Gene> genes = branch.genes();
		float[] values = new float[genes.size()];
		for (int i = 0; i < genes.size(); i++) {
			values[i] = Math.min(donor.raw(genes.get(i)), cap);
		}
		installed.removeIf(i -> i.branch() == branch);
		installed.add(new Installed(branch, lineage, label, values));
	}

	/** Puts a branch back to wild type. Free, and immediate. */
	public boolean revert(SpliceBranch branch) {
		return installed.removeIf(i -> i.branch() == branch);
	}

	public void clear() {
		installed.clear();
	}

	/**
	 * Drops whatever no longer fits.
	 * <p>
	 * Slots come from research, and research cannot be lost, so in ordinary play this never fires.
	 * It exists because the count is derived rather than stored: a retune of {@link SpliceTree}'s
	 * slot arithmetic, or an operator editing a guide, must not leave a player wearing more than
	 * the rules allow with nothing to notice it. Oldest go first, so the most recent deliberate
	 * choice survives.
	 */
	public void trimTo(int slots) {
		while (installed.size() > Math.max(0, slots)) {
			installed.remove(0);
		}
	}

	// ------------------------------------------------------------------ effects

	/** How much faster this player burns food than wild type; 1.0 when carrying nothing. */
	public float exhaustionMultiplier() {
		float extra = 0f;
		for (Installed i : installed) {
			for (Gene gene : i.branch().genes()) {
				SpliceEffects.Row row = SpliceEffects.rowFor(gene);
				if (row != null && row.kind() == SpliceEffects.Kind.EXHAUSTION) {
					extra += (float) row.magnitude(i.valueOf(gene));
				}
			}
		}
		return 1f + extra;
	}

	/** How brightly the player glows, 0 to 1. Zero unless a Light block is installed. */
	public float glowStrength() {
		Installed light = inBranch(SpliceBranch.LIGHT);
		return light == null ? 0f : MathX.clamp01(light.valueOf(Gene.BIOLUMINESCENCE));
	}

	/**
	 * Rewrites the player's attribute modifiers to match what is installed.
	 * <p>
	 * Every modifier this class has ever added is removed first, including for branches no longer
	 * carried, so this is safe to call on login, after a splice, after a reversion, and on a player
	 * who has never spliced anything. Reconciling to the desired state beats tracking deltas: a
	 * missed removal is a permanent buff nobody can see the source of.
	 */
	public void apply(LivingEntity player) {
		for (Gene gene : Gene.VALUES) {
			SpliceEffects.Row row = SpliceEffects.rowFor(gene);
			if (row == null || row.kind() != SpliceEffects.Kind.ATTRIBUTE) continue;
			AttributeInstance instance = player.getAttribute(row.attribute().get());
			if (instance != null) instance.removeModifier(modifierId(gene));
		}

		for (Installed i : installed) {
			for (Gene gene : i.branch().genes()) {
				SpliceEffects.Row row = SpliceEffects.rowFor(gene);
				if (row == null || row.kind() != SpliceEffects.Kind.ATTRIBUTE) continue;
				double amount = row.magnitude(i.valueOf(gene));
				if (Math.abs(amount) < 1e-6) continue;
				AttributeInstance instance = player.getAttribute(row.attribute().get());
				if (instance == null) continue;
				instance.addOrReplacePermanentModifier(
						new AttributeModifier(modifierId(gene), amount, row.operation()));
			}
		}

		// Raising MAX_HEALTH does not heal, and lowering it can leave the player above their own
		// maximum, which renders as a health bar that will not fill and never corrects itself.
		if (player.getHealth() > player.getMaxHealth()) player.setHealth(player.getMaxHealth());
	}

	private static Identifier modifierId(Gene gene) {
		return Primordia.id(MODIFIER_PREFIX + gene.name().toLowerCase());
	}

	// ------------------------------------------------------------------ nbt

	public CompoundTag writeNbt() {
		CompoundTag nbt = new CompoundTag();
		ListTag list = new ListTag();
		for (Installed i : installed) {
			CompoundTag tag = new CompoundTag();
			tag.putString("Branch", i.branch().name());
			tag.putLong("Lineage", i.lineage());
			tag.putString("Label", i.label());
			// Stored on the same 16-bit grid Genome uses, so a loadout round-trips exactly.
			byte[] packed = new byte[i.values().length];
			for (int v = 0; v < packed.length; v++) {
				packed[v] = (byte) (Math.round(MathX.clamp01(i.values()[v]) * 255f) - 128);
			}
			tag.putByteArray("Values", packed);
			list.add(tag);
		}
		nbt.put("Installed", list);
		return nbt;
	}

	public static SpliceLoadout fromNbt(CompoundTag nbt) {
		SpliceLoadout out = new SpliceLoadout();
		ListTag list = nbt.getListOrEmpty("Installed");
		for (int i = 0; i < list.size(); i++) {
			CompoundTag tag = list.getCompoundOrEmpty(i);
			SpliceBranch branch = branchOf(tag.getStringOr("Branch", ""));
			if (branch == null) continue;
			byte[] packed = tag.getByteArray("Values").orElse(new byte[0]);
			// A block that has gained or lost a locus since this was saved cannot be reconstructed
			// honestly, so it is dropped rather than guessed at — the player reverts to wild type in
			// that branch and can splice again from a guide that is still intact.
			if (packed.length != branch.genes().size()) continue;
			float[] values = new float[packed.length];
			for (int v = 0; v < packed.length; v++) {
				values[v] = (packed[v] + 128) / 255f;
			}
			out.installed.add(new Installed(branch, tag.getLongOr("Lineage", 0L),
					tag.getStringOr("Label", "unknown"), values));
		}
		return out;
	}

	private static SpliceBranch branchOf(String name) {
		for (SpliceBranch branch : SpliceBranch.VALUES) {
			if (branch.name().equals(name)) return branch;
		}
		return null;
	}
}
