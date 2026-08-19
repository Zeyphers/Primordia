package dev.jsz.primordia.splice;

import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.lab.DecodeAccuracy;
import dev.jsz.primordia.lab.GuideData;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The splice tree, evaluated against what a field guide holds.
 * <p>
 * Every method here is a pure function of {@link GuideData}, for the same reason
 * {@code GuideChapters.Unlock.satisfied} is: the guide knows what it holds, so the tree screen runs
 * on the client with no packet and no world lookup. That is what makes the tree screen buildable
 * before anything is behind it — which {@code MD/SPLICING.md} §9 argues is the single most valuable
 * thing in the whole design, because a list of what you could become, mostly empty, is already a
 * reason to go outside.
 * <p>
 * Nothing here consults the player's installed splices. This class answers "what has the world
 * shown you", and {@link PlayerSplices} answers "what are you carrying"; keeping the two apart is
 * what lets the guide draw an honest tree for a player who has never touched a splicer.
 */
public final class SpliceTree {

	private SpliceTree() {
	}

	/**
	 * One lineage on file, considered as a donor for one branch.
	 *
	 * @param lineage  the bloodline's id, so the guide can name it
	 * @param label    what to call it on screen
	 * @param genome   the representative specimen
	 * @param potency  its strength in this branch, in [0,1]
	 * @param accuracy how well it is characterised; below Complete a splice from it is a gamble
	 */
	public record Donor(long lineage, String label, Genome genome, float potency,
	                    DecodeAccuracy accuracy) {

		/** Whether a splice taken from this donor lands on a figure rather than inside a range. */
		public boolean certain() {
			return accuracy == DecodeAccuracy.COMPLETE;
		}
	}

	/**
	 * Every lineage on file that can serve as a donor for this branch, strongest first.
	 * <p>
	 * Includes the uncertain ones deliberately. {@code MD/SPLICING.md} §7 makes splicing below
	 * <i>Complete</i> a real gamble inside the error bars the report already shows, so a donor the
	 * player cannot yet trust still belongs on the list — it is the offer that makes the risk a
	 * choice rather than a rule.
	 */
	public static List<Donor> donorsFor(GuideData data, SpliceBranch branch) {
		List<Donor> out = new ArrayList<>();
		for (GuideData.Entry entry : data.entries()) {
			Genome genome = entry.genome();
			if (genome == null) continue;
			out.add(new Donor(entry.lineage(), entry.displayName(), genome,
					branch.potency(genome), entry.accuracy()));
		}
		out.sort((a, b) -> Float.compare(b.potency, a.potency));
		return out;
	}

	/** The strongest donor on file for this branch, or null when nothing is filed. */
	public static Donor bestDonor(GuideData data, SpliceBranch branch) {
		List<Donor> donors = donorsFor(data, branch);
		return donors.isEmpty() ? null : donors.get(0);
	}

	/**
	 * How many distinct lineages are characterised to <i>Complete</i> and strong enough in this
	 * branch to count toward this depth.
	 * <p>
	 * <i>Complete</i> and not merely close: §7 makes the whole accuracy ladder mean something by
	 * letting the player act on a guess but never bank one.
	 */
	public static int progress(GuideData data, SpliceBranch branch, SpliceDepth depth) {
		int count = 0;
		for (GuideData.Entry entry : data.entries()) {
			if (entry.accuracy() != DecodeAccuracy.COMPLETE) continue;
			Genome genome = entry.genome();
			if (genome == null) continue;
			if (branch.potency(genome) >= depth.bar) count++;
		}
		return count;
	}

	/**
	 * Whether one node is open.
	 * <p>
	 * Depths within a branch are cumulative — the count that opens {@link SpliceDepth#DOMINANT}
	 * necessarily satisfies the ones below it, since the bar is the same or lower — but this is
	 * enforced rather than assumed, so a retune of the numbers cannot leave a hole in the middle of
	 * a branch that the screen would draw as a gap.
	 */
	public static boolean unlocked(GuideData data, SpliceBranch branch, SpliceDepth depth) {
		for (SpliceDepth step : SpliceDepth.VALUES) {
			if (step.ordinal() > depth.ordinal()) break;
			if (progress(data, branch, step) < step.required) return false;
		}
		return true;
	}

	/** Every open depth in a branch. */
	public static Set<SpliceDepth> unlockedIn(GuideData data, SpliceBranch branch) {
		Set<SpliceDepth> out = EnumSet.noneOf(SpliceDepth.class);
		for (SpliceDepth depth : SpliceDepth.VALUES) {
			if (unlocked(data, branch, depth)) out.add(depth);
		}
		return out;
	}

	/** The deepest open node in a branch, or null when the branch is still shut. */
	public static SpliceDepth reached(GuideData data, SpliceBranch branch) {
		return SpliceDepth.highestOf(unlockedIn(data, branch));
	}

	/**
	 * The strongest value the player may express in this branch, or 0 when it is shut.
	 * <p>
	 * This is the number that makes a great donor found early into anticipation rather than an
	 * anticlimax: the guide can show the player a 0.88 lineage and the 0.45 they are currently
	 * allowed to carry of it, which states the reason to keep surveying in one line.
	 */
	public static float capIn(GuideData data, SpliceBranch branch) {
		SpliceDepth depth = reached(data, branch);
		return depth == null ? 0f : depth.cap;
	}

	/**
	 * Gene slots: two to begin with, and one more for each branch taken to
	 * {@link SpliceDepth#DOMINANT}, to a maximum of five.
	 * <p>
	 * The progression curve in one line — specialising buys breadth. The player who masters one
	 * branch earns the room to dabble in another, so the endgame is wide rather than merely tall
	 * and the only way to reach it is to do the thing the mod is about.
	 */
	public static final int BASE_SLOTS = 2;
	public static final int MAX_SLOTS = 5;

	public static int slots(GuideData data) {
		int slots = BASE_SLOTS;
		for (SpliceBranch branch : SpliceBranch.VALUES) {
			// A cosmetic branch is satisfied by any donor at all, so mastering it asks the world for
			// nothing and must not be paid for a slot. Colour is the tutorial; the tutorial does not
			// hand out the reward for finishing the game.
			if (branch.cosmetic()) continue;
			if (unlocked(data, branch, SpliceDepth.DOMINANT)) slots++;
		}
		return Math.min(MAX_SLOTS, slots);
	}
}
