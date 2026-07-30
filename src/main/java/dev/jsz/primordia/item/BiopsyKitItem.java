package dev.jsz.primordia.item;

import dev.jsz.primordia.entity.CreatureEntity;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.lab.GenomeLibrary;
import dev.jsz.primordia.lab.SampleData;
import dev.jsz.primordia.registry.PrimordiaItems;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.sounds.SoundSource;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.ChatFormatting;
import net.minecraft.world.InteractionHand;

import java.util.function.Consumer;

public class BiopsyKitItem extends Item {

	private static final int COOLDOWN = 30;

	public BiopsyKitItem(Properties settings) {
		super(settings);
	}

	@Override
	public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity entity, InteractionHand hand) {
		if (!(entity instanceof CreatureEntity creature)) {
			if (player.level().isClientSide()) return InteractionResult.SUCCESS;
			player.sendOverlayMessage(Component.literal("The swab comes back with nothing a sequencer could read.")
					.withStyle(ChatFormatting.DARK_GRAY));
			player.getCooldowns().addCooldown(stack, 10);
			return InteractionResult.CONSUME;
		}
		if (player.level().isClientSide()) return InteractionResult.SUCCESS;

		if (creature.isCarcass()) {
			player.sendOverlayMessage(Component.literal("This body has degraded past a usable sample.")
					.withStyle(ChatFormatting.DARK_GRAY));
			player.getCooldowns().addCooldown(stack, 10);
			return InteractionResult.CONSUME;
		}

		Genome genome = creature.getGenome();
		if (genome == null) {
			player.sendOverlayMessage(Component.literal("Sampling failed: no genome").withStyle(ChatFormatting.RED));
			return InteractionResult.CONSUME;
		}

		// A second sample of an individual already accounted for is worth nothing: the library keys
		// specimens by genome, so it would land on a fingerprint already present and move no dial.
		// Refusing rather than warning-and-taking is the point — the kit is good for five specimens,
		// and a message that arrives after the charge is spent is not information the player can use.
		String duplicate = alreadyAccountedFor(player, genome);
		if (duplicate != null) {
			player.sendOverlayMessage(Component.literal(duplicate).withStyle(ChatFormatting.GOLD)
					.append(Component.literal(" · " + SampleData.shortLineage(genome))
							.withStyle(ChatFormatting.DARK_GRAY)));
			player.getCooldowns().addCooldown(stack, 10);
			return InteractionResult.CONSUME;
		}

		ItemStack sample = SampleData.of(genome, creature.level().getGameTime())
				.onto(PrimordiaItems.TISSUE_SAMPLE);
		if (!player.getInventory().add(sample)) {
			player.drop(sample, false);
		}

		stack.hurtAndBreak(1, player, hand);
		player.getCooldowns().addCooldown(stack, COOLDOWN);
		player.level().playSound(null, creature.blockPosition(),
				SoundEvents.BOTTLE_FILL, SoundSource.PLAYERS, 0.6f, 1.4f);

		creature.provokeSampling(player);

		player.sendOverlayMessage(Component.literal("Sample taken from specimen ")
				.withStyle(ChatFormatting.GRAY)
				.append(Component.literal(SampleData.shortLineage(genome)).withStyle(ChatFormatting.AQUA)));
		return InteractionResult.CONSUME;
	}

	/**
	 * Why a second sample of this individual would be wasted, or null if it would not be.
	 * <p>
	 * Two ways an individual can already be accounted for, and they are worth telling apart: one is
	 * finished work, the other is work in progress the player may simply have forgotten they were
	 * carrying. Both mean the same thing for the kit — nothing to gain — and neither is a reason to
	 * stop them sampling a <i>different</i> member of the species, which is what actually advances a
	 * characterisation.
	 */
	private static String alreadyAccountedFor(Player player, Genome genome) {
		if (player.level() instanceof ServerLevel serverLevel
				&& GenomeLibrary.get(serverLevel).hasSpecimen(genome)) {
			return "This specimen is already on record";
		}
		String code = genome.encode();
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack held = player.getInventory().getItem(slot);
			if (!held.is(PrimordiaItems.TISSUE_SAMPLE)) continue;
			SampleData data = SampleData.get(held);
			if (data != null && data.genome().encode().equals(code)) {
				return "You are already carrying a sample of this specimen";
			}
		}
		return null;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> tooltipAdder, TooltipFlag flag) {
		tooltipAdder.accept(Component.literal("Right-click a creature to take a tissue sample.")
				.withStyle(ChatFormatting.DARK_GRAY));
		tooltipAdder.accept(Component.literal("The needle hurts — expect anything aggressive to turn on you.")
				.withStyle(ChatFormatting.DARK_RED));
		tooltipAdder.accept(Component.literal("Samples degrade — sequence them or store them cold.")
				.withStyle(ChatFormatting.DARK_GRAY));
	}
}
