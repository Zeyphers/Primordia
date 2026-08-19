package dev.jsz.primordia.item;

import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.splice.SpliceBranch;
import dev.jsz.primordia.splice.SpliceLoadout;
import dev.jsz.primordia.splice.SpliceTree;
import dev.jsz.primordia.splice.Splicing;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

import java.util.function.Consumer;

/**
 * A single trait, isolated and bottled.
 * <p>
 * The splicing bench does not change the player — it produces one of these, and drinking it does.
 * That split is worth the extra item: a splice you can hold is a splice you can look at before you
 * commit to it, carry to where you need it, and hand to somebody else. It also puts the irreversible
 * moment in the player's own hand rather than in a machine's output, which is the difference between
 * a choice and a side effect.
 * <p>
 * The whole donor is bottled — the linkage block, the cap it was isolated at, and the bloodline it
 * came from — so a serum brewed before the player's research deepened stays exactly as strong as it
 * was when it was made. That is deliberate: an old bottle is an old bottle.
 */
public class SpliceSerumItem extends Item {

	private static final String BRANCH = "SpliceBranch";
	private static final String LINEAGE = "SpliceLineage";
	private static final String LABEL = "SpliceLabel";
	private static final String GENOME = "SpliceGenome";
	private static final String CAP = "SpliceCap";

	public SpliceSerumItem(Properties properties) {
		super(properties);
	}

	/** Bottles a donor's block at the strength the bench was able to isolate it. */
	public static ItemStack of(ItemStack stack, SpliceBranch branch, long lineage, String label,
	                           String genomeCode, float cap) {
		CompoundTag tag = new CompoundTag();
		tag.putString(BRANCH, branch.name());
		tag.putLong(LINEAGE, lineage);
		tag.putString(LABEL, label);
		tag.putString(GENOME, genomeCode);
		tag.putFloat(CAP, cap);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return stack;
	}

	private static CompoundTag dataOf(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data == null ? new CompoundTag() : data.copyTag();
	}

	public static SpliceBranch branchOf(ItemStack stack) {
		String name = dataOf(stack).getStringOr(BRANCH, "");
		for (SpliceBranch branch : SpliceBranch.VALUES) {
			if (branch.name().equals(name)) return branch;
		}
		return null;
	}

	public static String labelOf(ItemStack stack) {
		return dataOf(stack).getStringOr(LABEL, "");
	}

	/**
	 * Drinking it is what changes you.
	 * <p>
	 * The bench already checked that the splice was legal when it brewed this, and it is checked
	 * again here, because a bottle can sit in a chest across a great deal of research and the slot
	 * arithmetic may no longer allow it. Refusing at the last moment hands the bottle back rather
	 * than consuming it — losing a serum to a full loadout would be a punishment for planning ahead.
	 */
	@Override
	public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
		if (level.isClientSide() || !(entity instanceof ServerPlayer player)) {
			return super.finishUsingItem(stack, level, entity);
		}

		SpliceBranch branch = branchOf(stack);
		CompoundTag tag = dataOf(stack);
		Genome donor = Genome.decode(tag.getStringOr(GENOME, ""));
		if (branch == null || donor == null) {
			player.sendSystemMessage(Component.literal("The serum has spoiled.")
					.withStyle(ChatFormatting.RED));
			return super.finishUsingItem(stack, level, entity);
		}

		SpliceLoadout loadout = Splicing.loadoutOf(player);
		boolean replacing = loadout.inBranch(branch) != null;
		if (!replacing && loadout.used() >= SpliceTree.slots(Splicing.guideOf(player))) {
			player.sendSystemMessage(Component.literal(Splicing.Result.NO_SLOTS.message)
					.withStyle(ChatFormatting.RED));
			// Handed back rather than drunk: the player keeps what they paid for.
			return stack;
		}

		loadout.install(branch, tag.getLongOr(LINEAGE, 0L), tag.getStringOr(LABEL, "unknown"),
				donor, tag.getFloatOr(CAP, 1f));
		Splicing.commit(player, loadout);
		player.sendSystemMessage(Component.literal(branch.title + " is yours.")
				.withStyle(ChatFormatting.AQUA));
		return super.finishUsingItem(stack, level, entity);
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
	                            Consumer<Component> lines, TooltipFlag flag) {
		SpliceBranch branch = branchOf(stack);
		if (branch == null) {
			lines.accept(Component.literal("Spoiled").withStyle(ChatFormatting.DARK_GRAY));
			return;
		}
		CompoundTag tag = dataOf(stack);
		lines.accept(Component.literal(branch.title).withStyle(ChatFormatting.AQUA));
		lines.accept(Component.literal("from " + tag.getStringOr(LABEL, "unknown"))
				.withStyle(ChatFormatting.GRAY));
		// The whole package, because Rule 2 says you adopt all of it and a bottle is the last
		// chance to read what that means before it stops being reversible without a bench.
		Genome donor = Genome.decode(tag.getStringOr(GENOME, ""));
		if (donor == null) return;
		float cap = tag.getFloatOr(CAP, 1f);
		for (var row : dev.jsz.primordia.splice.SpliceEffects.rowsFor(branch)) {
			float value = Math.min(donor.raw(row.gene()), cap);
			lines.accept(Component.literal("  "
							+ dev.jsz.primordia.splice.SpliceEffects.render(row, value) + "  "
							+ row.summary())
					.withStyle(row.beneficial(value) ? ChatFormatting.DARK_GREEN : ChatFormatting.RED));
		}
	}
}
