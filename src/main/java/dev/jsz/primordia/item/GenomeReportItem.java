package dev.jsz.primordia.item;

import dev.jsz.primordia.lab.DecodeAccuracy;
import dev.jsz.primordia.lab.GenomeReport;
import dev.jsz.primordia.lab.Discoveries;
import dev.jsz.primordia.lab.GuideData;
import dev.jsz.primordia.lab.SampleData;
import dev.jsz.primordia.registry.PrimordiaItems;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

import java.util.List;

/**
 * The decoder's output: a permanent, readable record of one specimen.
 * <p>
 * The accuracy the report was decoded at is baked into the item rather than recomputed on reading.
 * A report is a document — what it said when it was printed is what it says now, and a player who
 * keeps an early vague report alongside a later exact one can see their own understanding of a
 * species improve. Recomputing would quietly rewrite history and destroy that.
 */
public class GenomeReportItem extends Item {

	private static final String ROOT = "PrimordiaReport";
	private static final String KEY_ACCURACY = "Accuracy";
	private static final String KEY_PRIOR = "Prior";

	public GenomeReportItem(Settings settings) {
		super(settings);
	}

	/** Stamps the confidence this report was produced at onto the stack. */
	public static void writeAccuracy(ItemStack stack, DecodeAccuracy accuracy, int priorDecodes) {
		NbtComponent existing = stack.get(DataComponentTypes.CUSTOM_DATA);
		NbtCompound root = existing == null ? new NbtCompound() : existing.copyNbt();
		NbtCompound nbt = new NbtCompound();
		nbt.putString(KEY_ACCURACY, accuracy.name());
		nbt.putInt(KEY_PRIOR, priorDecodes);
		root.put(ROOT, nbt);
		stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(root));
	}

	public static DecodeAccuracy readAccuracy(ItemStack stack) {
		NbtCompound nbt = root(stack);
		if (nbt == null) return DecodeAccuracy.UNKNOWN;
		try {
			return DecodeAccuracy.valueOf(nbt.getString(KEY_ACCURACY));
		} catch (IllegalArgumentException e) {
			// A report written by a future version naming a level this one does not have. Reading
			// it as the lowest is honest: we genuinely cannot interpret it.
			return DecodeAccuracy.UNKNOWN;
		}
	}

	public static int readPriorDecodes(ItemStack stack) {
		NbtCompound nbt = root(stack);
		return nbt == null ? 0 : nbt.getInt(KEY_PRIOR);
	}

	private static NbtCompound root(ItemStack stack) {
		NbtComponent component = stack.get(DataComponentTypes.CUSTOM_DATA);
		if (component == null) return null;
		NbtCompound root = component.copyNbt();
		return root.contains(ROOT) ? root.getCompound(ROOT) : null;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * A report carried alongside a field guide files itself into it and is consumed.
	 * <p>
	 * This is the answer to the pipeline's storage problem. Characterising a species fully takes
	 * twelve decoded specimens, and twelve near-identical items is not a record of anything — it is
	 * the same fact printed twelve times, occupying twelve slots. Filed, they collapse into one
	 * guide entry that counts them.
	 * <p>
	 * Throttled rather than run every tick: a stack sitting in an inventory is not urgent, and the
	 * scan walks the player's whole inventory looking for a guide.
	 */
	@Override
	public void inventoryTick(ItemStack stack, World world, Entity entity, int slot, boolean selected) {
		if (world.isClient() || !(entity instanceof PlayerEntity player)) return;
		if ((player.age + slot) % FILE_INTERVAL != 0) return;

		SampleData data = SampleData.get(stack);
		if (data == null) return;

		ItemStack guide = findGuide(player);
		if (guide.isEmpty()) return;

		GuideData record = GuideData.get(guide);
		record.file(data.genome());
		record.write(guide);
		if (player instanceof net.minecraft.server.network.ServerPlayerEntity server) {
			Discoveries.checkGuide(server, record);
		}

		stack.decrement(1);
		world.playSound(null, player.getBlockPos(), SoundEvents.ITEM_BOOK_PAGE_TURN,
				SoundCategory.PLAYERS, 0.5f, 1.5f);
		player.sendMessage(Text.literal("Filed specimen ").formatted(Formatting.DARK_GRAY)
				.append(Text.literal(data.lineageHex()).formatted(Formatting.AQUA))
				.append(Text.literal(" into the field guide").formatted(Formatting.DARK_GRAY)), true);
	}

	/** Ticks between filing attempts, offset per slot so a stack of reports files one at a time. */
	private static final int FILE_INTERVAL = 10;

	private static ItemStack findGuide(PlayerEntity player) {
		for (int i = 0; i < player.getInventory().size(); i++) {
			ItemStack candidate = player.getInventory().getStack(i);
			if (candidate.isOf(PrimordiaItems.FIELD_GUIDE)) return candidate;
		}
		return ItemStack.EMPTY;
	}

	/** Right-click to print the full report into chat, where it can actually be read. */
	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
		ItemStack stack = player.getStackInHand(hand);
		if (world.isClient()) return TypedActionResult.success(stack, true);

		SampleData data = SampleData.get(stack);
		if (data == null) {
			player.sendMessage(Text.literal("This report is unreadable.").formatted(Formatting.RED), false);
			return TypedActionResult.consume(stack);
		}

		for (Text line : GenomeReport.lines(data.genome(), readAccuracy(stack), readPriorDecodes(stack))) {
			player.sendMessage(line, false);
		}
		world.playSound(null, player.getBlockPos(), SoundEvents.ITEM_BOOK_PAGE_TURN,
				SoundCategory.PLAYERS, 0.7f, 1.1f);
		player.getItemCooldownManager().set(this, 10);
		return TypedActionResult.consume(stack);
	}

	@Override
	public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
		SampleData data = SampleData.get(stack);
		if (data == null) {
			tooltip.add(Text.literal("Unreadable report").formatted(Formatting.DARK_RED, Formatting.ITALIC));
			return;
		}
		tooltip.addAll(GenomeReport.tooltip(data.genome(), readAccuracy(stack)));
		tooltip.add(Text.literal("Right-click to read in full.")
				.formatted(Formatting.DARK_GRAY, Formatting.ITALIC));
	}
}
