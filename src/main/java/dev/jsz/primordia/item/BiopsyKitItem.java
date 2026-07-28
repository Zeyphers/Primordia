package dev.jsz.primordia.item;

import dev.jsz.primordia.entity.CreatureEntity;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.lab.SampleData;
import dev.jsz.primordia.registry.PrimordiaItems;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;

import java.util.List;

/**
 * Takes a tissue sample off a living creature.
 * <p>
 * The entry point to the lab pipeline, and deliberately the only one: everything downstream needs a
 * sample, and a sample can only come off an animal that was standing in front of the player. That
 * is the constraint the whole feature rests on — knowledge about a species has to be paid for by
 * going and finding one, repeatedly, because {@link dev.jsz.primordia.lab.DecodeAccuracy} does not
 * sharpen without more individuals.
 * <p>
 * Jabbing a large animal with a needle is not a free action. The kit takes durability, and anything
 * that retaliates now has a reason to.
 */
public class BiopsyKitItem extends Item {

	/** Ticks before the kit can be used again. Long enough that it is not a spam-click. */
	private static final int COOLDOWN = 30;

	public BiopsyKitItem(Settings settings) {
		super(settings);
	}

	@Override
	public ActionResult useOnEntity(ItemStack stack, PlayerEntity player, LivingEntity entity, Hand hand) {
		if (!(entity instanceof CreatureEntity creature)) {
			if (player.getWorld().isClient()) return ActionResult.SUCCESS;
			// Vanilla animals are authored rather than grown; there is no genome in one to draw.
			player.sendMessage(Text.literal("The swab comes back with nothing a sequencer could read.")
					.formatted(Formatting.DARK_GRAY), true);
			player.getItemCooldownManager().set(this, 10);
			return ActionResult.CONSUME;
		}
		if (player.getWorld().isClient()) return ActionResult.SUCCESS;

		if (creature.isCarcass()) {
			// A body is still tissue, but it has been dead long enough to be worth saying so.
			player.sendMessage(Text.literal("This body has degraded past a usable sample.")
					.formatted(Formatting.DARK_GRAY), true);
			player.getItemCooldownManager().set(this, 10);
			return ActionResult.CONSUME;
		}

		Genome genome = creature.getGenome();
		if (genome == null) {
			player.sendMessage(Text.literal("Sampling failed: no genome").formatted(Formatting.RED), true);
			return ActionResult.CONSUME;
		}

		ItemStack sample = SampleData.of(genome, creature.getWorld().getTime())
				.onto(PrimordiaItems.TISSUE_SAMPLE);
		if (!player.getInventory().insertStack(sample)) {
			player.dropItem(sample, false);
		}

		stack.damage(1, player, LivingEntity.getSlotForHand(hand));
		player.getItemCooldownManager().set(this, COOLDOWN);
		player.getWorld().playSound(null, creature.getBlockPos(),
				SoundEvents.ITEM_BOTTLE_FILL, SoundCategory.PLAYERS, 0.6f, 1.4f);

		// Being stuck with a needle is provocation. Anything that fights back now does, which is
		// what makes sampling an apex predator a different proposition from sampling a grazer —
		// without this the kit is a free action against anything in the world.
		creature.provokeSampling(player);

		player.sendMessage(Text.literal("Sample taken from specimen ")
				.formatted(Formatting.GRAY)
				.append(Text.literal(SampleData.shortLineage(genome)).formatted(Formatting.AQUA)), true);
		return ActionResult.CONSUME;
	}

	@Override
	public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
		tooltip.add(Text.literal("Right-click a creature to take a tissue sample.")
				.formatted(Formatting.DARK_GRAY));
		tooltip.add(Text.literal("The needle hurts — expect anything aggressive to turn on you.")
				.formatted(Formatting.DARK_RED));
		tooltip.add(Text.literal("Samples degrade — sequence them or store them cold.")
				.formatted(Formatting.DARK_GRAY));
	}
}
