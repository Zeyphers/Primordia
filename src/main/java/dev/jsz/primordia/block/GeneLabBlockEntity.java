package dev.jsz.primordia.block;

import dev.jsz.primordia.item.GenomeReportItem;
import dev.jsz.primordia.lab.DecodeAccuracy;
import dev.jsz.primordia.lab.GenomeLibrary;
import dev.jsz.primordia.lab.SampleData;
import dev.jsz.primordia.registry.PrimordiaItems;
import dev.jsz.primordia.screen.GeneLabScreenHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

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
public class GeneLabBlockEntity extends BlockEntity implements WorldlyContainer, MenuProvider {

	public static final int SLOT_SAMPLE = 0;
	public static final int SLOT_FUEL = 1;
	public static final int SLOT_REDSTONE = 2;
	public static final int SLOT_OUTPUT = 3;
	public static final int SLOT_COUNT = 4;

	public static final int SEQUENCE_TIME = 400;
	public static final int DECODE_TIME = 240;
	public static final int REDSTONE_PER_DECODE = 16;
	public static final int DELIVER_TIME = 40;
	public static final int DECODE_INTERPRET_TIME = DECODE_TIME - DELIVER_TIME;

	public enum Stage implements StringRepresentable {
		IDLE("idle"), SEQUENCING("sequencing"), DECODING("decoding");

		public static final Stage[] VALUES = values();
		private final String name;

		Stage(String name) {
			this.name = name;
		}

		@Override
		public String getSerializedName() {
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

	private final NonNullList<ItemStack> inventory = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);

	private Stage stage = Stage.IDLE;
	private int progress;
	private int burnTime;
	private int burnTimeTotal;
	private int redstoneUsed;
	private SampleData inFlight;

	private final ContainerData properties = new ContainerData() {
		@Override
		public int get(int index) {
			return switch (index) {
				case PROPERTY_PROGRESS -> progress;
				case PROPERTY_PROCESS_TIME -> stage == Stage.SEQUENCING ? SEQUENCE_TIME : DECODE_TIME;
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
			}
		}

		@Override
		public int getCount() {
			return PROPERTY_COUNT;
		}
	};

	public GeneLabBlockEntity(BlockPos pos, BlockState state) {
		super(dev.jsz.primordia.registry.PrimordiaBlockEntities.BASIC_GENE_LAB, pos, state);
	}

	public Stage stage() {
		return stage;
	}

	public int progress() {
		return progress;
	}

	public int burnTime() {
		return burnTime;
	}

	public int burnTimeTotal() {
		return burnTimeTotal;
	}

	public int redstoneUsed() {
		return redstoneUsed;
	}

	public ContainerData properties() {
		return properties;
	}

	public SampleData inFlight() {
		return inFlight;
	}

	public static void serverTick(Level level, BlockPos pos, BlockState state, GeneLabBlockEntity be) {
		if (be.burnTime > 0) be.burnTime--;

		boolean dirty = false;

		switch (be.stage) {
			case IDLE -> dirty = be.tryBeginSequencing();
			case SEQUENCING -> dirty = be.tickSequencing(level);
			case DECODING -> dirty = be.tickDecoding(level);
		}

		Stage newDisplayed = be.displayedStage();
		if (state.getValue(LabMachineBlock.STAGE) != newDisplayed) {
			level.setBlock(pos, state.setValue(LabMachineBlock.STAGE, newDisplayed), 3);
			dirty = true;
		}
		if (dirty) be.setChanged();
	}

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

	private boolean tickSequencing(Level level) {
		ItemStack sample = inventory.get(SLOT_SAMPLE);
		SampleData data = SampleData.get(sample);
		if (data == null || !inventory.get(SLOT_OUTPUT).isEmpty()) {
			return reset();
		}

		if (burnTime <= 0) {
			int fuel = consumeFuel();
			if (fuel <= 0) {
				if (progress > 0) progress = Math.max(0, progress - 1);
				return true;
			}
			burnTime = fuel;
			burnTimeTotal = fuel;
		}

		progress++;
		if (progress < SEQUENCE_TIME) return true;

		float freshness = data.freshness(level.getGameTime());
		long elapsed = (long) ((1f - Math.max(0f, Math.min(1f, freshness))) * SampleData.SHELF_LIFE);
		inFlight = new SampleData(data.genome(), level.getGameTime() - elapsed, data.lineageHex());

		sample.shrink(1);
		stage = Stage.DECODING;
		progress = 0;
		redstoneUsed = 0;
		burnTime = 0;
		burnTimeTotal = 0;
		return true;
	}

	private boolean tickDecoding(Level level) {
		if (inFlight == null || !inventory.get(SLOT_OUTPUT).isEmpty()) {
			return reset();
		}

		int owed = (progress * REDSTONE_PER_DECODE) / DECODE_TIME;
		if (redstoneUsed < owed) {
			ItemStack redstone = inventory.get(SLOT_REDSTONE);
			if (!redstone.is(Items.REDSTONE) || redstone.isEmpty()) return true;
			redstone.shrink(1);
			redstoneUsed++;
		}

		progress++;
		if (progress < DECODE_TIME) return true;

		while (redstoneUsed < REDSTONE_PER_DECODE) {
			ItemStack redstone = inventory.get(SLOT_REDSTONE);
			if (!redstone.is(Items.REDSTONE) || redstone.isEmpty()) {
				progress = DECODE_TIME - 1;
				return true;
			}
			redstone.shrink(1);
			redstoneUsed++;
		}

		if (level instanceof ServerLevel serverWorld) {
			GenomeLibrary library = GenomeLibrary.get(serverWorld);
			int prior = library.referenceStrength(inFlight.genome());
			DecodeAccuracy accuracy = DecodeAccuracy.resolve(prior, inFlight.freshness(level.getGameTime()));
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

	private int consumeFuel() {
		ItemStack fuel = inventory.get(SLOT_FUEL);
		if (fuel.isEmpty() || level == null) return 0;
		int value = level.fuelValues().burnDuration(fuel);
		if (value <= 0) return 0;

		var remainder = fuel.getItem().getCraftingRemainder();
		fuel.shrink(1);
		if (fuel.isEmpty() && remainder != null) {
			inventory.set(SLOT_FUEL, remainder.create());
		}
		return value;
	}

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

	@Override
	public int getContainerSize() {
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
	public ItemStack getItem(int slot) {
		return inventory.get(slot);
	}

	@Override
	public ItemStack removeItem(int slot, int amount) {
		ItemStack result = ContainerHelper.removeItem(inventory, slot, amount);
		if (!result.isEmpty()) setChanged();
		return result;
	}

	@Override
	public ItemStack removeItemNoUpdate(int slot) {
		return ContainerHelper.takeItem(inventory, slot);
	}

	@Override
	public void setItem(int slot, ItemStack stack) {
		inventory.set(slot, stack);
		if (stack.getCount() > stack.getMaxStackSize()) stack.setCount(stack.getMaxStackSize());
		setChanged();
	}

	@Override
	public boolean stillValid(Player player) {
		return Container.stillValidBlockEntity(this, player);
	}

	@Override
	public void clearContent() {
		inventory.clear();
	}

	public static boolean isSample(ItemStack stack) {
		return stack.is(PrimordiaItems.TISSUE_SAMPLE) && SampleData.get(stack) != null;
	}

	@Override
	public int[] getSlotsForFace(Direction side) {
		return switch (side) {
			case DOWN -> BOTTOM_SLOTS;
			case UP -> TOP_SLOTS;
			default -> SIDE_SLOTS;
		};
	}

	@Override
	public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction dir) {
		return switch (slot) {
			case SLOT_SAMPLE -> isSample(stack);
			case SLOT_REDSTONE -> stack.is(Items.REDSTONE);
			case SLOT_FUEL -> true;
			default -> false;
		};
	}

	@Override
	public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction dir) {
		return slot == SLOT_OUTPUT;
	}

	@Override
	public AbstractContainerMenu createMenu(int syncId, Inventory playerInventory, Player player) {
		return new GeneLabScreenHandler(syncId, playerInventory, this, properties);
	}

	@Override
	public Component getDisplayName() {
		return Component.translatable("container.primordia.basic_gene_lab");
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		ContainerHelper.saveAllItems(output, inventory);
		output.putInt("Stage", stage.ordinal());
		output.putInt("Progress", progress);
		output.putInt("BurnTime", burnTime);
		output.putInt("BurnTimeTotal", burnTimeTotal);
		output.putInt("RedstoneUsed", redstoneUsed);
		if (inFlight != null) {
			ValueOutput carried = output.child("InFlight");
			carried.putString("Genome", inFlight.genome().encode());
			carried.putLong("Collected", inFlight.collectedAtTick());
			carried.putString("Lineage", inFlight.lineageHex());
		}
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		inventory.clear();
		ContainerHelper.loadAllItems(input, inventory);
		stage = Stage.VALUES[Math.floorMod(input.getIntOr("Stage", 0), Stage.VALUES.length)];
		progress = input.getIntOr("Progress", 0);
		burnTime = input.getIntOr("BurnTime", 0);
		burnTimeTotal = input.getIntOr("BurnTimeTotal", 0);
		redstoneUsed = input.getIntOr("RedstoneUsed", 0);

		inFlight = null;
		input.child("InFlight").ifPresent(carried -> {
			var genomeStr = carried.getStringOr("Genome", "");
			var genome = dev.jsz.primordia.genome.Genome.decode(genomeStr);
			if (genome != null) {
				inFlight = new SampleData(genome, carried.getLongOr("Collected", 0L),
						carried.getStringOr("Lineage", ""));
			}
		});
		if (stage == Stage.DECODING && inFlight == null) reset();
	}
}
