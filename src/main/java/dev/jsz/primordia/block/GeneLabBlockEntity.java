package dev.jsz.primordia.block;

import dev.jsz.primordia.item.GenomeReportItem;
import dev.jsz.primordia.lab.DecodeAccuracy;
import dev.jsz.primordia.lab.GenomeLibrary;
import dev.jsz.primordia.lab.SampleData;
import dev.jsz.primordia.registry.PrimordiaBlockEntities;
import dev.jsz.primordia.registry.PrimordiaItems;
import dev.jsz.primordia.screen.GeneLabScreenHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.SidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.PropertyDelegate;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

/**
 * The Basic Gene Lab: one machine that carries a tissue sample the whole way to a finished report.
 * <p>
 * Sequencing and decoding were two blocks and are now two <b>stages</b> of one. The player loads a
 * sample and walks away; the machine reads it, then interprets it, and a report appears. Shuttling
 * an intermediate item between two boxes by hand added a chore rather than a decision — there was
 * never a reason to sequence something and not decode it.
 * <p>
 * The stages keep their separate costs, which is where the interest actually was. Reading tissue is
 * heat, so it burns ordinary furnace fuel. Interpreting the read is computation, so it draws
 * {@link #REDSTONE_PER_DECODE} redstone. A lab stocked with only one of the two gets halfway and
 * stops, visibly, at the stage it cannot pay for.
 */
public class GeneLabBlockEntity extends BlockEntity implements SidedInventory, NamedScreenHandlerFactory {

	public static final int SLOT_SAMPLE = 0;
	public static final int SLOT_FUEL = 1;
	public static final int SLOT_REDSTONE = 2;
	public static final int SLOT_OUTPUT = 3;
	public static final int SLOT_COUNT = 4;

	/** Ticks to read a sample into sequence data. */
	public static final int SEQUENCE_TIME = 400;
	/** Ticks to interpret sequence data into a report. */
	public static final int DECODE_TIME = 240;
	/** Redstone dust consumed by one decode, drawn steadily across the stage. */
	public static final int REDSTONE_PER_DECODE = 16;
	/** Tail of the decode that represents writing the report out, rather than matching it. */
	public static final int DELIVER_TIME = 40;
	/** The part of the decode spent matching the read against the library. */
	public static final int DECODE_INTERPRET_TIME = DECODE_TIME - DELIVER_TIME;

	/**
	 * What the machine is currently doing.
	 * <p>
	 * Ordinals are persisted and sent to the open screen, and the value doubles as a blockstate
	 * property so the console in the world shows the same thing the GUI does. One enum for both
	 * means the block can never disagree with the screen about what it is working on.
	 */
	public enum Stage implements StringIdentifiable {
		IDLE("idle"), SEQUENCING("sequencing"), DECODING("decoding");

		public static final Stage[] VALUES = values();
		private final String name;

		Stage(String name) {
			this.name = name;
		}

		@Override
		public String asString() {
			return name;
		}
	}

	public static final int PROPERTY_PROGRESS = 0;
	public static final int PROPERTY_PROCESS_TIME = 1;
	public static final int PROPERTY_BURN = 2;
	public static final int PROPERTY_BURN_TOTAL = 3;
	public static final int PROPERTY_STAGE = 4;
	public static final int PROPERTY_REDSTONE_USED = 5;
	public static final int PROPERTY_COUNT = 6;

	private static final int[] TOP_SLOTS = {SLOT_SAMPLE};
	private static final int[] BOTTOM_SLOTS = {SLOT_OUTPUT};
	private static final int[] SIDE_SLOTS = {SLOT_FUEL, SLOT_REDSTONE};

	private final DefaultedList<ItemStack> inventory = DefaultedList.ofSize(SLOT_COUNT, ItemStack.EMPTY);

	private Stage stage = Stage.IDLE;
	private int progress;
	private int burnTime;
	private int burnTimeTotal;
	/** Redstone already spent on the decode in progress. */
	private int redstoneUsed;
	/**
	 * The specimen currently in the machine, held between stages.
	 * <p>
	 * Deliberately not an intermediate item in a slot. The sequence exists only inside the run, so
	 * it cannot be half-extracted, cannot be hoppered out mid-process, and cannot be lost to a
	 * full output slot at the moment stage one finishes.
	 */
	private SampleData inFlight;

	private final PropertyDelegate properties = new PropertyDelegate() {
		@Override
		public int get(int index) {
			return switch (index) {
				case PROPERTY_PROGRESS -> progress;
				case PROPERTY_PROCESS_TIME -> currentProcessTime();
				case PROPERTY_BURN -> burnTime;
				case PROPERTY_BURN_TOTAL -> burnTimeTotal;
				case PROPERTY_STAGE -> stage.ordinal();
				case PROPERTY_REDSTONE_USED -> redstoneUsed;
				default -> 0;
			};
		}

		@Override
		public void set(int index, int value) {
			switch (index) {
				case PROPERTY_PROGRESS -> progress = value;
				case PROPERTY_BURN -> burnTime = value;
				case PROPERTY_BURN_TOTAL -> burnTimeTotal = value;
				case PROPERTY_STAGE -> stage = Stage.VALUES[Math.floorMod(value, Stage.VALUES.length)];
				case PROPERTY_REDSTONE_USED -> redstoneUsed = value;
				default -> {
				}
			}
		}

		@Override
		public int size() {
			return PROPERTY_COUNT;
		}
	};

	public GeneLabBlockEntity(BlockPos pos, BlockState state) {
		super(PrimordiaBlockEntities.BASIC_GENE_LAB, pos, state);
	}

	private int currentProcessTime() {
		return stage == Stage.DECODING ? DECODE_TIME : SEQUENCE_TIME;
	}

	// ------------------------------------------------------------------ ticking

	public static void tick(World world, BlockPos pos, BlockState state, GeneLabBlockEntity be) {
		if (world.isClient()) return;

		boolean dirty = false;
		// Fuel only burns while it is being used for something. Ticking this down unconditionally
		// meant a lit machine quietly consumed its coal through the decode stage and while sitting
		// idle, for no work at all.
		if (be.stage == Stage.SEQUENCING && be.burnTime > 0) be.burnTime--;

		switch (be.stage) {
			case IDLE -> dirty = be.tryBeginSequencing();
			case SEQUENCING -> dirty = be.tickSequencing(world);
			case DECODING -> dirty = be.tickDecoding(world);
		}

		// Push the stage into the blockstate so the console animates in the world. Only on an
		// actual change: setBlockState is a chunk update and a neighbour notification, and running
		// one every tick for a machine that is simply still working would be pure cost.
		Stage shown = be.displayedStage();
		if (state.get(LabMachineBlock.STAGE) != shown) {
			world.setBlockState(pos, state.with(LabMachineBlock.STAGE, shown), 3);
			dirty = true;
		}
		if (dirty) be.markDirty();
	}

	/**
	 * The stage the block should be showing.
	 * <p>
	 * A sequencing run with no fuel reads as idle rather than as working, because from the outside
	 * it is: nothing is happening and the player needs to notice that. Decoding has no such stall
	 * state on the block, since it visibly consumes redstone.
	 */
	private Stage displayedStage() {
		if (stage == Stage.SEQUENCING && burnTime <= 0) return Stage.IDLE;
		return stage;
	}

	private boolean tryBeginSequencing() {
		ItemStack sample = inventory.get(SLOT_SAMPLE);
		if (sample.isEmpty() || SampleData.get(sample) == null) return false;
		if (!inventory.get(SLOT_OUTPUT).isEmpty()) return false;

		stage = Stage.SEQUENCING;
		progress = 0;
		redstoneUsed = 0;
		return true;
	}

	private boolean tickSequencing(World world) {
		ItemStack sample = inventory.get(SLOT_SAMPLE);
		SampleData data = SampleData.get(sample);
		if (data == null || !inventory.get(SLOT_OUTPUT).isEmpty()) {
			return reset();
		}

		if (burnTime <= 0) {
			int fuel = consumeFuel();
			if (fuel <= 0) {
				// Nothing to burn: hold, bleeding progress slowly rather than discarding it, so a
				// player who tops the fuel up shortly after has not lost the run.
				if (progress > 0) progress = Math.max(0, progress - 1);
				return true;
			}
			burnTime = fuel;
			burnTimeTotal = fuel;
		}

		progress++;
		if (progress < SEQUENCE_TIME) return true;

		// Read complete. Freshness is resolved now, at the moment the tissue is consumed, and
		// carried forward — data does not rot, tissue does.
		float freshness = data.freshness(world.getTime());
		long elapsed = (long) ((1f - Math.max(0f, Math.min(1f, freshness))) * SampleData.SHELF_LIFE);
		inFlight = new SampleData(data.genome(), world.getTime() - elapsed, data.lineageHex());

		sample.decrement(1);
		stage = Stage.DECODING;
		progress = 0;
		redstoneUsed = 0;
		// Leftover burn is discarded rather than banked toward the next sample.
		//
		// A furnace keeps its heat, and copying that here read as a bug: one lump of coal covers
		// four sequencing runs, so a player who loaded fuel once and took it back out watched the
		// lab keep reading tissue with an empty fuel slot and no way to tell why. Spending the
		// remainder makes the price legible and matches the other half of the machine — one fuel
		// item per read, sixteen redstone per decode.
		burnTime = 0;
		burnTimeTotal = 0;
		return true;
	}

	private boolean tickDecoding(World world) {
		if (inFlight == null || !inventory.get(SLOT_OUTPUT).isEmpty()) {
			return reset();
		}

		// Redstone is drawn steadily rather than all at once, so a lab that runs short stalls
		// partway with the cost already visible instead of refusing to start for no stated reason.
		int owed = (progress * REDSTONE_PER_DECODE) / DECODE_TIME;
		if (redstoneUsed < owed) {
			ItemStack redstone = inventory.get(SLOT_REDSTONE);
			if (!redstone.isOf(Items.REDSTONE) || redstone.isEmpty()) return true;
			redstone.decrement(1);
			redstoneUsed++;
		}

		progress++;
		if (progress < DECODE_TIME) return true;

		// Charge any rounding remainder before finishing, so a decode always costs its full price.
		while (redstoneUsed < REDSTONE_PER_DECODE) {
			ItemStack redstone = inventory.get(SLOT_REDSTONE);
			if (!redstone.isOf(Items.REDSTONE) || redstone.isEmpty()) {
				progress = DECODE_TIME - 1;
				return true;
			}
			redstone.decrement(1);
			redstoneUsed++;
		}

		if (world instanceof ServerWorld serverWorld) {
			GenomeLibrary library = GenomeLibrary.get(serverWorld);
			int prior = library.decodedCount(inFlight.genome().lineage());
			DecodeAccuracy accuracy = DecodeAccuracy.resolve(prior, inFlight.freshness(world.getTime()));
			library.record(inFlight.genome());

			ItemStack report = inFlight.onto(PrimordiaItems.GENOME_REPORT);
			GenomeReportItem.writeAccuracy(report, accuracy, prior);
			inventory.set(SLOT_OUTPUT, report);
		}
		return reset();
	}

	private boolean reset() {
		stage = Stage.IDLE;
		progress = 0;
		redstoneUsed = 0;
		inFlight = null;
		return true;
	}

	private static java.util.Map<net.minecraft.item.Item, Integer> fuelTimes;

	private int consumeFuel() {
		ItemStack fuel = inventory.get(SLOT_FUEL);
		if (fuel.isEmpty()) return 0;
		if (fuelTimes == null) {
			fuelTimes = net.minecraft.block.entity.AbstractFurnaceBlockEntity.createFuelTimeMap();
		}
		int value = fuelTimes.getOrDefault(fuel.getItem(), 0);
		if (value <= 0) return 0;

		net.minecraft.item.Item remainder = fuel.getItem().getRecipeRemainder();
		fuel.decrement(1);
		if (fuel.isEmpty() && remainder != null) {
			inventory.set(SLOT_FUEL, new ItemStack(remainder));
		}
		return value;
	}

	/**
	 * Fill of one of the three progress lines the screen draws between its four slots, 0 to 1.
	 * <p>
	 * Index 0 is sample to sequencer, 1 is sequencer to decoder, 2 is decoder to report. Each owns
	 * one step and fills only while that step runs, so the column says at a glance which of the
	 * three the machine is on — a single bar spanning the whole job cannot, and "half full" would
	 * leave the player unable to tell reading from interpreting when those need different things
	 * from them.
	 * <p>
	 * Lives here rather than on the screen handler so the arithmetic is reachable without a
	 * property delegate, and therefore testable.
	 */
	public static float lineFill(int index, Stage stage, int progress) {
		float fill = switch (index) {
			case 0 -> switch (stage) {
				case IDLE -> 0f;
				case SEQUENCING -> (float) progress / SEQUENCE_TIME;
				case DECODING -> 1f;
			};
			case 1 -> stage == Stage.DECODING
					? (float) progress / DECODE_INTERPRET_TIME
					: 0f;
			case 2 -> stage == Stage.DECODING
					? (float) (progress - DECODE_INTERPRET_TIME) / DELIVER_TIME
					: 0f;
			default -> 0f;
		};
		return Math.max(0f, Math.min(1f, fill));
	}

	// ------------------------------------------------------------------ inventory

	@Override
	public int size() {
		return SLOT_COUNT;
	}

	@Override
	public boolean isEmpty() {
		for (ItemStack stack : inventory) {
			if (!stack.isEmpty()) return false;
		}
		return true;
	}

	@Override
	public ItemStack getStack(int slot) {
		return inventory.get(slot);
	}

	@Override
	public ItemStack removeStack(int slot, int amount) {
		ItemStack result = Inventories.splitStack(inventory, slot, amount);
		if (!result.isEmpty()) markDirty();
		return result;
	}

	@Override
	public ItemStack removeStack(int slot) {
		return Inventories.removeStack(inventory, slot);
	}

	@Override
	public void setStack(int slot, ItemStack stack) {
		inventory.set(slot, stack);
		if (stack.getCount() > stack.getMaxCount()) stack.setCount(stack.getMaxCount());
		markDirty();
	}

	@Override
	public boolean canPlayerUse(PlayerEntity player) {
		return world != null && world.getBlockEntity(pos) == this
				&& player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0;
	}

	@Override
	public void clear() {
		inventory.clear();
	}

	public static boolean isSample(ItemStack stack) {
		return stack.isOf(PrimordiaItems.TISSUE_SAMPLE) && SampleData.get(stack) != null;
	}

	@Override
	public int[] getAvailableSlots(Direction side) {
		return switch (side) {
			case DOWN -> BOTTOM_SLOTS;
			case UP -> TOP_SLOTS;
			default -> SIDE_SLOTS;
		};
	}

	@Override
	public boolean canInsert(int slot, ItemStack stack, Direction dir) {
		return switch (slot) {
			case SLOT_SAMPLE -> isSample(stack);
			case SLOT_REDSTONE -> stack.isOf(Items.REDSTONE);
			case SLOT_FUEL -> true;
			default -> false;
		};
	}

	@Override
	public boolean canExtract(int slot, ItemStack stack, Direction dir) {
		return slot == SLOT_OUTPUT;
	}

	// ------------------------------------------------------------------ screen

	@Override
	public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
		return new GeneLabScreenHandler(syncId, playerInventory, this, properties);
	}

	@Override
	public Text getDisplayName() {
		return Text.translatable("container.primordia.basic_gene_lab");
	}

	// ------------------------------------------------------------------ persistence

	@Override
	protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		super.writeNbt(nbt, registries);
		Inventories.writeNbt(nbt, inventory, registries);
		nbt.putInt("Stage", stage.ordinal());
		nbt.putInt("Progress", progress);
		nbt.putInt("BurnTime", burnTime);
		nbt.putInt("BurnTimeTotal", burnTimeTotal);
		nbt.putInt("RedstoneUsed", redstoneUsed);
		if (inFlight != null) {
			// The in-flight specimen has to survive a save, or unloading the chunk mid-run silently
			// destroys an animal the player went out and found.
			NbtCompound carried = new NbtCompound();
			carried.putString("Genome", inFlight.genome().encode());
			carried.putLong("Collected", inFlight.collectedAtTick());
			carried.putString("Lineage", inFlight.lineageHex());
			nbt.put("InFlight", carried);
		}
	}

	@Override
	protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		super.readNbt(nbt, registries);
		inventory.clear();
		Inventories.readNbt(nbt, inventory, registries);
		stage = Stage.VALUES[Math.floorMod(nbt.getInt("Stage"), Stage.VALUES.length)];
		progress = nbt.getInt("Progress");
		burnTime = nbt.getInt("BurnTime");
		burnTimeTotal = nbt.getInt("BurnTimeTotal");
		redstoneUsed = nbt.getInt("RedstoneUsed");

		inFlight = null;
		if (nbt.contains("InFlight")) {
			NbtCompound carried = nbt.getCompound("InFlight");
			var genome = dev.jsz.primordia.genome.Genome.decode(carried.getString("Genome"));
			if (genome != null) {
				inFlight = new SampleData(genome, carried.getLong("Collected"),
						carried.getString("Lineage"));
			}
		}
		// A decode whose specimen did not survive the round trip has nothing to produce, so it is
		// dropped back to idle rather than left spinning against a null.
		if (stage == Stage.DECODING && inFlight == null) reset();
	}
}
