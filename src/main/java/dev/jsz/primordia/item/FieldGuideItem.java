package dev.jsz.primordia.item;

import dev.jsz.primordia.lab.GuideData;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;

import java.util.function.Consumer;

public class FieldGuideItem extends Item {

	public FieldGuideItem(Properties settings) {
		super(settings);
	}

	@Override
	public InteractionResult use(Level world, Player player, InteractionHand hand) {
		return InteractionResult.SUCCESS;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> tooltipAdder, TooltipFlag flag) {
		// Read from the synced client-side data rather than the item stack components
		GuideData data = dev.jsz.primordia.PrimordiaClient.getClientGuideData();
		if (data.speciesCount() == 0) {
			tooltipAdder.accept(Component.literal("No species on file yet").withStyle(ChatFormatting.DARK_GRAY));
		} else {
			tooltipAdder.accept(Component.literal(data.speciesCount() + " species · "
					+ data.specimensFiled() + " specimens filed").withStyle(ChatFormatting.AQUA));
		}
		tooltipAdder.accept(Component.literal("Reports file themselves while this is carried.")
				.withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
		tooltipAdder.accept(Component.literal("Right-click to read.")
				.withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
	}
}
