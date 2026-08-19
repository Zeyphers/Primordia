package dev.jsz.primordia.block;

import dev.jsz.primordia.registry.PrimordiaBlockEntities;
import dev.jsz.primordia.splice.SpliceBranch;
import dev.jsz.primordia.splice.Splicing;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.UUID;

/**
 * The bench that runs a splice, over time.
 * <p>
 * The work is not the point — nothing here decides whether a splice is legal, and
 * {@link Splicing} re-checks everything when the job lands. The point is that a splice
 * <b>takes a while and happens somewhere</b>, which is what {@code MD/SPLICING.md} §6 asks for when
 * it says the brake on hot-swapping is friction rather than loss: you cannot re-spec halfway down a
 * cave, because the machine that does it is at your base and it runs a cycle.
 * <p>
 * A job is held by player UUID rather than by a reference, because a run outlives the tick it was
 * asked for and the player may log out, die, or walk to another dimension inside it. All three are
 * handled the same way: the job finishes into thin air and the player, who never left the bench in
 * the fiction, simply does not get what they did not stay for.
 */
public class SplicerBlockEntity extends BlockEntity
		implements net.minecraft.world.Container, net.minecraft.world.MenuProvider {

	/**
	 * How long a splice takes, in ticks. A full minute.
	 * <p>
	 * Deliberately far longer than the sampling animation, which is 8.125 seconds and simply loops
	 * for the duration. An earlier version ran for exactly one pass of the model, which was tidy and
	 * wrong: isolating a trait is the most consequential thing in the mod, and a machine that did it
	 * in the time it takes to walk round the bench made it feel like a crafting recipe. A minute is
	 * long enough to be a decision, and long enough that the player goes and does something else —
	 * which is why the serum lands in a slot and the status lamp is readable from across the room.
	 */
	public static final int RUN_TICKS = 1200;

	/** The finished serum waits here until somebody takes it. */
	private final net.minecraft.core.NonNullList<net.minecraft.world.item.ItemStack> output =
			net.minecraft.core.NonNullList.withSize(1, net.minecraft.world.item.ItemStack.EMPTY);

	private UUID owner;
	private SpliceBranch branch;
	private long lineage;

	/**
	 * The game tick the run started on. Progress is the difference, not a counter.
	 * <p>
	 * A counter only advances while the block entity is ticking, and a block entity only ticks while
	 * its chunk does. Runs used to be eight seconds, so nobody noticed; at a full minute the player
	 * walks off, the chunk drops out of simulation range, and the machine quietly stops — the bar
	 * freezes and the serum never arrives. Measuring against the world clock makes the run finish on
	 * schedule whether or not anybody was there to watch it, which is what a machine left running
	 * ought to do.
	 */
	private long startedAt = -1L;

	public SplicerBlockEntity(BlockPos pos, BlockState state) {
		super(PrimordiaBlockEntities.SPLICER, pos, state);
	}

	public boolean isRunning() {
		return branch != null;
	}

	/** Ticks elapsed in the current run, clamped to the run length. */
	public int ticks() {
		if (branch == null || startedAt < 0L || level == null) return 0;
		long elapsed = level.getGameTime() - startedAt;
		return (int) Math.max(0, Math.min(RUN_TICKS, elapsed));
	}

	/** Which branch is being isolated, or null when idle. */
	public SpliceBranch runningBranch() {
		return branch;
	}

	// ------------------------------------------------------------------ container

	@Override
	public int getContainerSize() {
		return 1;
	}

	@Override
	public boolean isEmpty() {
		return output.get(0).isEmpty();
	}

	@Override
	public net.minecraft.world.item.ItemStack getItem(int slot) {
		return output.get(0);
	}

	@Override
	public net.minecraft.world.item.ItemStack removeItem(int slot, int amount) {
		return net.minecraft.world.ContainerHelper.removeItem(output, 0, amount);
	}

	@Override
	public net.minecraft.world.item.ItemStack removeItemNoUpdate(int slot) {
		return net.minecraft.world.ContainerHelper.takeItem(output, 0);
	}

	@Override
	public void setItem(int slot, net.minecraft.world.item.ItemStack stack) {
		output.set(0, stack);
		setChanged();
	}

	@Override
	public boolean stillValid(net.minecraft.world.entity.player.Player player) {
		return level != null && level.getBlockEntity(worldPosition) == this
				&& player.distanceToSqr(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5) <= 64.0;
	}

	@Override
	public void clearContent() {
		output.clear();
	}

	@Override
	public net.minecraft.network.chat.Component getDisplayName() {
		return net.minecraft.network.chat.Component.translatable("block.primordia.splicer");
	}

	@Override
	public net.minecraft.world.inventory.AbstractContainerMenu createMenu(
			int syncId, net.minecraft.world.entity.player.Inventory inventory,
			net.minecraft.world.entity.player.Player player) {
		return new dev.jsz.primordia.screen.SplicerMenu(syncId, inventory, this,
				dev.jsz.primordia.screen.SplicerMenu.dataOf(this), this);
	}

	/** How far through the current run, 0 to 1. Zero when idle. <b>Server side only.</b> */
	public float progress() {
		return branch == null ? 0f : ticks() / (float) RUN_TICKS;
	}

	/** Client-only: the game tick at which this machine was first seen running. */
	private long clientStart = -1L;

	/**
	 * Seconds into the sampling cycle, for the renderer.
	 * <p>
	 * <b>Not</b> derived from {@link #progress()}, and that mistake is worth recording because its
	 * symptom is so unhelpful. {@link #ticks} is incremented in {@code serverTick} and nothing sends
	 * it anywhere — a block entity only reaches the client through an update packet, and this one has
	 * none. So on the client {@code progress()} is always zero, the animation time was only ever the
	 * partial tick, and that runs 0 to 1 and resets twenty times a second: the machine did not travel,
	 * it <i>vibrated in place</i>.
	 * <p>
	 * The running flag is a block state, which does synchronise, so the client can time the cycle
	 * itself from the moment it sees the machine start. That costs no packets at all. The cost is that
	 * a machine which leaves view mid-cycle restarts its animation when it comes back, which is a
	 * cosmetic slip on a machine nobody was watching.
	 */
	public float animationSeconds(long gameTime, float partialTick, boolean running) {
		if (!running) {
			clientStart = -1L;
			return 0f;
		}
		if (clientStart < 0L) clientStart = gameTime;
		return (gameTime - clientStart + partialTick) / 20f;
	}

	/**
	 * Starts a run. Refuses if one is already going, so two players cannot share a bench mid-cycle
	 * and have the second quietly overwrite the first.
	 */
	public boolean begin(ServerPlayer player, SpliceBranch branch, long lineage) {
		if (isRunning()) return false;

		// It takes blood. Splicing works from the player's own genome as well as the donor's, so the
		// machine opens a vein to get at it — a heart, once, at the moment the run starts rather than
		// spread over it, so the cost is a thing you feel yourself agreeing to.
		if (level instanceof ServerLevel serverLevel) {
			level.playSound(null, getBlockPos(), SoundEvents.BEE_STING, SoundSource.BLOCKS, 0.7f, 1.6f);
			player.hurtServer(serverLevel, player.damageSources().cactus(), 2.0f);
		}

		this.owner = player.getUUID();
		this.branch = branch;
		this.lineage = lineage;
		this.startedAt = level.getGameTime();
		setRunning(true);
		setChanged();
		return true;
	}

	public static void serverTick(Level level, BlockPos pos, BlockState state, SplicerBlockEntity be) {
		if (!be.isRunning()) return;

		int elapsed = be.ticks();
		if (elapsed % 40 == 20) {
			level.playSound(null, pos, SoundEvents.BREWING_STAND_BREW, SoundSource.BLOCKS, 0.5f,
					0.8f + (elapsed / (float) RUN_TICKS) * 0.4f);
		}
		if (elapsed < RUN_TICKS) return;

		be.finish((ServerLevel) level);
	}

	private void finish(ServerLevel level) {
		SpliceBranch running = branch;
		long donor = lineage;
		UUID who = owner;

		branch = null;
		owner = null;
		lineage = 0L;
		startedAt = -1L;
		setRunning(false);
		setChanged();

		ServerPlayer player = who == null ? null : level.getServer().getPlayerList().getPlayer(who);
		// Left mid-cycle. The machine finishes its pass either way — it is a machine — and there is
		// simply nobody there to take the result.
		if (player == null) return;

		// The machine bottles the trait; drinking it is what changes the player. See SpliceSerumItem.
		var guide = Splicing.guideOf(player);
		var depth = dev.jsz.primordia.splice.SpliceTree.reached(guide, running);
		dev.jsz.primordia.splice.SpliceTree.Donor best = null;
		for (var candidate : dev.jsz.primordia.splice.SpliceTree.donorsFor(guide, running)) {
			if (candidate.lineage() == donor) best = candidate;
		}
		if (depth == null || best == null || best.genome() == null) {
			player.sendSystemMessage(Component.literal("The sample was lost."));
			level.playSound(null, getBlockPos(), SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS,
					0.6f, 1.0f);
			return;
		}

		net.minecraft.world.item.ItemStack serum = dev.jsz.primordia.item.SpliceSerumItem.of(
				new net.minecraft.world.item.ItemStack(
						dev.jsz.primordia.registry.PrimordiaItems.SPLICE_SERUM),
				running, best.lineage(), best.label(), best.genome().encode(), depth.cap);

		// Into the slot if it is free, into the player's hands if it is not, onto the floor if they
		// are full. A finished serum is a cycle of the machine's time and must not evaporate.
		if (output.get(0).isEmpty()) {
			output.set(0, serum);
			setChanged();
		} else if (!player.getInventory().add(serum)) {
			player.drop(serum, false);
		}
		player.sendSystemMessage(Component.literal(running.title + " isolated."));
		level.playSound(null, getBlockPos(), SoundEvents.BREWING_STAND_BREW, SoundSource.BLOCKS,
				0.7f, 1.5f);
	}

	/**
	 * Mirrors the run into the block state, which is what the renderer reads.
	 * <p>
	 * Block states synchronise to the client on their own; a field on the block entity would not,
	 * and the animation would only ever play on a listen server.
	 */
	private void setRunning(boolean running) {
		if (level == null) return;
		BlockState state = getBlockState();
		if (state.hasProperty(SplicerBlock.RUNNING) && state.getValue(SplicerBlock.RUNNING) != running) {
			level.setBlock(getBlockPos(), state.setValue(SplicerBlock.RUNNING, running), 3);
		}
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		this.output.clear();
		net.minecraft.world.ContainerHelper.loadAllItems(input, this.output);
		String name = input.getStringOr("Branch", "");
		branch = null;
		for (SpliceBranch candidate : SpliceBranch.VALUES) {
			if (candidate.name().equals(name)) branch = candidate;
		}
		lineage = input.getLongOr("Lineage", 0L);
		startedAt = input.getLongOr("StartedAt", -1L);
		String saved = input.getStringOr("Owner", "");
		// A malformed uuid drops the owner rather than throwing: the run then finishes into thin
		// air, which is the same path a player who logged out already takes.
		try {
			owner = saved.isEmpty() ? null : UUID.fromString(saved);
		} catch (IllegalArgumentException e) {
			owner = null;
		}
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		net.minecraft.world.ContainerHelper.saveAllItems(output, this.output);
		if (branch != null) output.putString("Branch", branch.name());
		output.putLong("Lineage", lineage);
		output.putLong("StartedAt", startedAt);
		if (owner != null) output.putString("Owner", owner.toString());
	}
}
