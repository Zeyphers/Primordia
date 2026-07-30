package dev.jsz.primordia.ecology;

import dev.jsz.primordia.ecology.region.RegionLedger;
import dev.jsz.primordia.ecology.region.RegionPos;
import dev.jsz.primordia.ecology.region.RegionRecord;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.tags.BlockTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;

/**
 * The rules under which a creature is allowed to change the world, and the budget it has to do it.
 * <p>
 * Everything here is a guard rail. The intended reading is a landscape that looks lived in — grass
 * cropped short where a herd feeds, paths worn between the places they go — and the failure mode is
 * a landscape that looks griefed, which is one careless block-break away and is not recoverable by
 * the player. So the permission is a narrow allow-list rather than a broad deny-list:
 * <ul>
 *   <li>Only soft natural vegetation and topsoil may ever be touched. Not logs, not leaves, not
 *       stone, not crops, and nothing a player is likely to have placed.</li>
 *   <li>Every chunk has a small change budget that refills slowly, so a bloom in the population
 *       cannot strip a region even if every animal in it is trying to.</li>
 * </ul>
 * The allow-list approach is deliberate. A deny-list gets a hole every time Minecraft adds a block,
 * and the hole is only discovered when somebody's build has a herd standing in it.
 */
public final class WorldImpact {
	/** Block changes one chunk may accumulate before creatures must leave it alone. */
	private static final int CHUNK_BUDGET = 48;
	/** Ticks after which a chunk's spent budget is fully restored. Twenty minutes. */
	private static final int BUDGET_RECOVERY_TICKS = 24000;

	private record ChunkBudget(int spent, long lastChange) {
	}

	private static final Map<Long, ChunkBudget> BUDGETS = new HashMap<>();

	private WorldImpact() {
	}

	/**
	 * Vegetation a grazing animal is permitted to eat.
	 * <p>
	 * Short growth only. Leaves are deliberately excluded even though {@code GrazeGoal} will walk a
	 * browser up to them: a herd that eats leaves is a herd that deforests, and a bare trunk is far
	 * more conspicuous — and far more annoying — than cropped grass. They can nibble; nothing is
	 * removed.
	 */
	public static boolean isCroppable(BlockState state) {
		return state.is(BlockTags.REPLACEABLE_BY_TREES)
				|| state.is(BlockTags.FLOWERS);
	}

	/** Topsoil that repeated traffic is allowed to wear down. */
	public static boolean isTrampleable(BlockState state) {
		return state.is(Blocks.GRASS_BLOCK)
				|| state.is(Blocks.PODZOL)
				|| state.is(Blocks.MYCELIUM);
	}

	/**
	 * Crops a plant and tells the region it lost some standing vegetation.
	 * Returns false when the block was not edible or the chunk is out of budget.
	 */
	public static boolean graze(ServerLevel world, BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		if (!isCroppable(state)) return false;
		if (!spend(world, pos)) return false;

		world.destroyBlock(pos, false, null, 512);
		debitVegetation(world, pos, 0.004f);
		return true;
	}

	public static void trample(ServerLevel world, BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		Block next;
		if (isTrampleable(state)) {
			next = Blocks.DIRT;
		} else if (state.is(Blocks.DIRT)) {
			next = Blocks.DIRT_PATH;
		} else {
			return;
		}
		if (!world.getBlockState(pos.above()).isAir()) return;
		if (!spend(world, pos)) return;
		world.setBlockAndUpdate(pos, next.defaultBlockState());
	}

	/** Reduces the region's recorded plant stock, which is what makes overgrazing bite. */
	public static void debitVegetation(ServerLevel world, BlockPos pos, float amount) {
		RegionLedger ledger = RegionLedger.get(world);
		RegionRecord record = ledger.at(RegionPos.of(pos), world.getSeed());
		record.vegetation = Math.max(0f, record.vegetation - amount);
		record.dirty = true;
	}

	/**
	 * Takes one change out of a chunk's budget, refilling it for elapsed time first.
	 * <p>
	 * The budget is what turns "creatures modify terrain" from a liability into a feature. Without
	 * it the constraint on how much a region changes is the population, and the population is
	 * exactly the thing that is allowed to bloom.
	 */
	private static boolean spend(Level world, BlockPos pos) {
		long key = ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4);
		long now = world.getGameTime();
		ChunkBudget budget = BUDGETS.get(key);

		int spent = 0;
		if (budget != null) {
			long elapsed = now - budget.lastChange();
			int recovered = (int) (elapsed * CHUNK_BUDGET / BUDGET_RECOVERY_TICKS);
			spent = Math.max(0, budget.spent() - recovered);
		}
		if (spent >= CHUNK_BUDGET) return false;

		BUDGETS.put(key, new ChunkBudget(spent + 1, now));
		// The map is only ever written by the server thread and holds one small record per chunk
		// that has been changed; trimming it keeps a long session from accumulating every chunk
		// the player has ever walked through.
		if (BUDGETS.size() > 4096) {
			BUDGETS.entrySet().removeIf(e -> now - e.getValue().lastChange() > BUDGET_RECOVERY_TICKS);
		}
		return true;
	}

	/** Clears the per-chunk budgets. Called when a server stops so a new world starts clean. */
	public static void reset() {
		BUDGETS.clear();
	}
}
