package dev.jsz.primordia.item;

import dev.jsz.primordia.lab.DecodeAccuracy;
import dev.jsz.primordia.lab.GenomeReport;
import dev.jsz.primordia.lab.Discoveries;
import dev.jsz.primordia.lab.GuideData;
import dev.jsz.primordia.lab.SampleData;
import dev.jsz.primordia.registry.PrimordiaItems;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundSource;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;

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

	public GenomeReportItem(Properties settings) {
		super(settings);
	}

	/** Stamps the confidence this report was produced at onto the stack. */
	public static void writeAccuracy(ItemStack stack, DecodeAccuracy accuracy, int priorDecodes) {
		CustomData existing = stack.get(DataComponents.CUSTOM_DATA);
		CompoundTag root = existing == null ? new CompoundTag() : existing.copyTag();
		CompoundTag nbt = new CompoundTag();
		nbt.putString(KEY_ACCURACY, accuracy.name());
		nbt.putInt(KEY_PRIOR, priorDecodes);
		root.put(ROOT, nbt);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
	}

	public static DecodeAccuracy readAccuracy(ItemStack stack) {
		CompoundTag nbt = root(stack);
		if (nbt == null) return DecodeAccuracy.UNKNOWN;
		try {
			return DecodeAccuracy.valueOf(nbt.getStringOr(KEY_ACCURACY, ""));
		} catch (IllegalArgumentException e) {
			// A report written by a future version naming a level this one does not have. Reading
			// it as the lowest is honest: we genuinely cannot interpret it.
			return DecodeAccuracy.UNKNOWN;
		}
	}

	public static int readPriorDecodes(ItemStack stack) {
		CompoundTag nbt = root(stack);
		return nbt == null ? 0 : nbt.getIntOr(KEY_PRIOR, 0);
	}

	private static CompoundTag root(ItemStack stack) {
		CustomData component = stack.get(DataComponents.CUSTOM_DATA);
		if (component == null) return null;
		CompoundTag root = component.copyTag();
		return root.contains(ROOT) ? root.getCompound(ROOT).orElse(null) : null;
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
	public void inventoryTick(ItemStack stack, net.minecraft.server.level.ServerLevel world, Entity entity, net.minecraft.world.entity.EquipmentSlot slot) {
		if (!(entity instanceof Player player)) return;
		// The slot is null for anything that is not being worn or held — which is every report in a
		// normal inventory, and so very much the common case rather than an edge one. It replaced an
		// int slot index that this used to stagger the scan across slots; there is no index to
		// stagger by any more, and the interval alone is throttle enough for a walk that only happens
		// while a report and a guide are both in the same inventory.
		int stagger = slot == null ? 0 : slot.ordinal();
		if ((player.tickCount + stagger) % FILE_INTERVAL != 0) return;

		SampleData data = SampleData.get(stack);
		if (data == null) return;

		ItemStack guide = findGuide(player);
		if (guide.isEmpty()) return;

		dev.jsz.primordia.lab.PlayerGuideData global = dev.jsz.primordia.lab.PlayerGuideData.get(world);
		GuideData record = global.getGuide(player.getUUID());
		
		// If the guide item still has legacy data on it, merge it before filing.
		GuideData legacy = GuideData.get(guide);
		if (legacy.speciesCount() > 0 && record.speciesCount() == 0) {
			record.merge(legacy);
			guide.remove(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
		}
		
		record.file(data.genome());
		global.putGuide(player.getUUID(), record);
		
		if (player instanceof net.minecraft.server.level.ServerPlayer server) {
			Discoveries.checkGuide(server, record);
			CompoundTag payloadData = new CompoundTag();
			record.writeInto(payloadData);
			net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(
					server, new dev.jsz.primordia.lab.GuideDataSyncPayload(payloadData));
		}

		stack.shrink(1);
		world.playSound(null, player.blockPosition(), SoundEvents.BOOK_PAGE_TURN,
				SoundSource.PLAYERS, 0.5f, 1.5f);
		player.sendOverlayMessage(Component.literal("Filed specimen ").withStyle(ChatFormatting.DARK_GRAY)
				.append(Component.literal(data.lineageHex()).withStyle(ChatFormatting.AQUA))
				.append(Component.literal(" into the field guide").withStyle(ChatFormatting.DARK_GRAY)));
	}

	private static final int FILE_INTERVAL = 10;

	private static ItemStack findGuide(Player player) {
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			ItemStack candidate = player.getInventory().getItem(i);
			if (candidate.is(PrimordiaItems.FIELD_GUIDE)) return candidate;
		}
		return ItemStack.EMPTY;
	}

	@Override
	public InteractionResult use(Level world, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (world.isClientSide()) return InteractionResult.SUCCESS;

		SampleData data = SampleData.get(stack);
		if (data == null) {
			player.sendSystemMessage(Component.literal("This report is unreadable.").withStyle(ChatFormatting.RED));
			return InteractionResult.CONSUME;
		}

		for (Component line : GenomeReport.lines(data.genome(), readAccuracy(stack), readPriorDecodes(stack))) {
			player.sendSystemMessage(line);
		}
		world.playSound(null, player.blockPosition(), SoundEvents.BOOK_PAGE_TURN,
				SoundSource.PLAYERS, 0.7f, 1.1f);
		player.getCooldowns().addCooldown(stack, 10);
		return InteractionResult.CONSUME;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, net.minecraft.world.item.component.TooltipDisplay display, java.util.function.Consumer<Component> tooltipAdder, TooltipFlag flag) {
		SampleData data = SampleData.get(stack);
		if (data == null) {
			tooltipAdder.accept(Component.literal("Unreadable report").withStyle(ChatFormatting.DARK_RED, ChatFormatting.ITALIC));
			return;
		}
		for (Component c : GenomeReport.tooltip(data.genome(), readAccuracy(stack))) {
			tooltipAdder.accept(c);
		}
		tooltipAdder.accept(Component.literal("Right-click to read in full.")
				.withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
	}
}
