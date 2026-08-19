package dev.jsz.primordia.splice;

import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.lab.GuideData;
import dev.jsz.primordia.lab.PlayerGuideData;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * The server-side entry points for splicing: what a player is carrying, what they are allowed to
 * carry, and the one operation that changes it.
 * <p>
 * Everything that writes a loadout goes through {@link #install} or {@link #revert} so that the
 * three things which must happen together — persist, re-apply the attributes, tell the client —
 * cannot come apart. A splice that saved but did not apply is a player who is not what their own
 * guide says they are.
 */
public final class Splicing {

	private Splicing() {
	}

	public static SpliceLoadout loadoutOf(ServerPlayer player) {
		return PlayerSpliceData.get((ServerLevel) player.level()).get(player.getUUID());
	}

	public static GuideData guideOf(ServerPlayer player) {
		return PlayerGuideData.get((ServerLevel) player.level()).getGuide(player.getUUID());
	}

	/**
	 * Why a splice cannot be made, or {@link Result#OK}.
	 * <p>
	 * The screen asks before offering the button, so in practice these are belt and braces against a
	 * client that has drifted out of step with the server — but they are checked server-side because
	 * the tree is derived from guide contents and a client can be made to lie about those.
	 */
	public enum Result {
		OK(""),
		BRANCH_LOCKED("Nothing on file is strong enough to work from."),
		NO_SLOTS("No room. Revert something first."),
		NO_DONOR("That bloodline is not in your guide."),
		NO_BENCH("Not out here. This needs a splicing bench."),
		BENCH_BUSY("The bench is already running.");

		public final String message;

		Result(String message) {
			this.message = message;
		}
	}

	/**
	 * Takes a linkage block off a donor already in the player's guide.
	 * <p>
	 * The potency cap is read from the tree at the moment of the splice and baked into the stored
	 * values; see {@link SpliceLoadout}. Replacing an existing splice in the same branch does not
	 * need a free slot, because it does not consume one — which is what makes upgrading a donor as
	 * the player's research deepens the ordinary thing to do rather than a slot-juggling exercise.
	 */
	public static Result install(ServerPlayer player, SpliceBranch branch, long lineage) {
		Result check = check(player, branch, lineage);
		if (check != Result.OK) return check;

		GuideData.Entry donor = donorIn(guideOf(player), lineage);
		SpliceLoadout loadout = loadoutOf(player);
		loadout.install(branch, lineage, donor.displayName(), donor.genome(),
				SpliceTree.reached(guideOf(player), branch).cap);
		commit(player, loadout);
		return Result.OK;
	}

	/**
	 * Whether this splice would be allowed, without making it.
	 * <p>
	 * Shared by the moment the player asks and the moment the bench finishes, so the two can never
	 * disagree about what is legal — the only difference being that eight seconds have passed and
	 * the answer is worked out again from scratch.
	 */
	public static Result check(ServerPlayer player, SpliceBranch branch, long lineage) {
		GuideData guide = guideOf(player);
		SpliceLoadout loadout = loadoutOf(player);

		SpliceDepth depth = SpliceTree.reached(guide, branch);
		if (depth == null) return Result.BRANCH_LOCKED;

		boolean replacing = loadout.inBranch(branch) != null;
		if (!replacing && loadout.used() >= SpliceTree.slots(guide)) return Result.NO_SLOTS;

		GuideData.Entry donor = donorIn(guide, lineage);
		if (donor == null || donor.genome() == null) return Result.NO_DONOR;
		return Result.OK;
	}

	private static GuideData.Entry donorIn(GuideData guide, long lineage) {
		for (GuideData.Entry entry : guide.entries()) {
			if (entry.lineage() == lineage) return entry;
		}
		return null;
	}

	/** Puts one branch back to wild type. Free, per {@code MD/SPLICING.md} §6. */
	public static boolean revert(ServerPlayer player, SpliceBranch branch) {
		SpliceLoadout loadout = loadoutOf(player);
		if (!loadout.revert(branch)) return false;
		commit(player, loadout);
		return true;
	}

	/**
	 * The nearest idle splicing bench, or null if there is none in reach.
	 * <p>
	 * {@code MD/SPLICING.md} §6 makes reversion free but not frictionless — "the brake on
	 * hot-swapping is friction, not loss: reversion runs a cycle at the splicer, so you cannot
	 * re-spec halfway down a cave". This is that rule: the work happens at a machine, and the
	 * machine takes its time about it.
	 * <p>
	 * A small radius rather than a block the player has to be looking at, so this reads as "you are
	 * at your bench" rather than as a precision-clicking exercise. Busy benches are skipped rather
	 * than refused, so a player with two of them can queue a second splice against the free one.
	 */
	private static final int BENCH_RANGE = 6;

	public static dev.jsz.primordia.block.SplicerBlockEntity benchNear(ServerPlayer player) {
		net.minecraft.core.BlockPos at = player.blockPosition();
		dev.jsz.primordia.block.SplicerBlockEntity busy = null;
		for (net.minecraft.core.BlockPos pos : net.minecraft.core.BlockPos.betweenClosed(
				at.offset(-BENCH_RANGE, -3, -BENCH_RANGE), at.offset(BENCH_RANGE, 3, BENCH_RANGE))) {
			if (!player.level().getBlockState(pos)
					.is(dev.jsz.primordia.registry.PrimordiaBlocks.SPLICER)) continue;
			if (player.level().getBlockEntity(pos)
					instanceof dev.jsz.primordia.block.SplicerBlockEntity bench) {
				if (!bench.isRunning()) return bench;
				busy = bench;
			}
		}
		return busy;
	}

	/** Whether there is a bench in reach at all, busy or not. */
	public static boolean atBench(ServerPlayer player) {
		return benchNear(player) != null;
	}

	/**
	 * Hands a splice to a bench to run.
	 * <p>
	 * Everything the request needs is validated <i>now</i> as well as when the job lands, so a
	 * player who asks for something impossible is told immediately rather than after a cycle they
	 * had no reason to wait through. {@link #install} re-checks on completion because the world can
	 * change inside those eight seconds.
	 */
	public static Result beginAt(ServerPlayer player, dev.jsz.primordia.block.SplicerBlockEntity bench,
	                            SpliceBranch branch) {
		if (bench.isRunning()) return Result.BENCH_BUSY;
		GuideData guide = guideOf(player);
		SpliceDepth depth = SpliceTree.reached(guide, branch);
		if (depth == null) return Result.BRANCH_LOCKED;
		SpliceTree.Donor donor = SpliceTree.bestDonor(guide, branch);
		if (donor == null || donor.genome() == null) return Result.NO_DONOR;
		return bench.begin(player, branch, donor.lineage()) ? Result.OK : Result.BENCH_BUSY;
	}

	public static Result begin(ServerPlayer player, SpliceBranch branch, long lineage) {
		dev.jsz.primordia.block.SplicerBlockEntity bench = benchNear(player);
		if (bench == null) return Result.NO_BENCH;
		if (bench.isRunning()) return Result.BENCH_BUSY;
		if (lineage != 0L) {
			Result check = check(player, branch, lineage);
			if (check != Result.OK) return check;
		}
		return bench.begin(player, branch, lineage) ? Result.OK : Result.BENCH_BUSY;
	}

	/** Persists, re-applies and re-syncs. Every write path ends here. */
	public static void commit(ServerPlayer player, SpliceLoadout loadout) {
		PlayerSpliceData store = PlayerSpliceData.get((ServerLevel) player.level());
		loadout.trimTo(SpliceTree.slots(guideOf(player)));
		store.put(player.getUUID(), loadout);
		loadout.apply(player);
		sync(player, loadout);
	}

	/**
	 * Re-applies and re-syncs without writing, for login and respawn.
	 * <p>
	 * Attribute modifiers are not persisted with the player, so a loadout that is only saved is a
	 * loadout that does nothing after a reconnect.
	 */
	public static void refresh(ServerPlayer player) {
		SpliceLoadout loadout = loadoutOf(player);
		loadout.apply(player);
		sync(player, loadout);
	}

	public static void sync(ServerPlayer player, SpliceLoadout loadout) {
		CompoundTag tag = loadout.writeNbt();
		ServerPlayNetworking.send(player, new SpliceSyncPayload(tag));
	}
}
