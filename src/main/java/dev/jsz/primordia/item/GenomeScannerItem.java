package dev.jsz.primordia.item;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.DietGroup;
import dev.jsz.primordia.entity.CreatureEntity;
import dev.jsz.primordia.entity.TamingPreference;
import dev.jsz.primordia.entity.Temperament;
import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Genome;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;

/**
 * The naturalist's field instrument: right-click a creature to read its genome, anatomy and
 * disposition.
 * <p>
 * This is the first piece of the observer tooling the mod is built around — the player's role is
 * to study a fauna they did not design, and that requires being able to actually see what
 * distinguishes one animal from another. It reports the same information as
 * {@code /primordia info} but without needing command permissions, and pointed at a specific
 * animal rather than whichever happens to be nearest.
 */
public class GenomeScannerItem extends Item {

	public GenomeScannerItem(Settings settings) {
		super(settings);
	}

	@Override
	public ActionResult useOnEntity(ItemStack stack, PlayerEntity player, LivingEntity entity, Hand hand) {
		if (!(entity instanceof CreatureEntity creature)) {
			return ActionResult.PASS;
		}
		// Read on the server so the report reflects authoritative state, then message the player.
		if (player.getWorld().isClient()) {
			return ActionResult.SUCCESS;
		}

		Genome genome = creature.getGenome();
		if (genome == null) {
			player.sendMessage(Text.literal("Scan failed: no genome").formatted(Formatting.RED), false);
			return ActionResult.CONSUME;
		}
		BodyPlan plan = creature.getBodyPlan();
		DietGroup diet = creature.getDietGroup();
		Temperament temperament = creature.getTemperament();

		String hex = Long.toHexString(genome.lineage());
		hex = hex.substring(0, Math.min(6, hex.length()));

		player.sendMessage(Text.literal("── Specimen [" + hex.toUpperCase() + "] ──").formatted(Formatting.AQUA, Formatting.BOLD), false);
		
		// Status & Taming information
		if (creature.isTamed()) {
			String ownerText = creature.isSaddled() ? "Tamed (Saddled)" : "Tamed";
			player.sendMessage(Text.literal("  Status: ").formatted(Formatting.GRAY)
					.append(Text.literal(ownerText).formatted(Formatting.GREEN)), false);
		} else {
			Item bait = creature.getFavouriteFood();
			float mass = plan == null ? 0.2f : plan.mass;
			float chance = TamingPreference.tameChance(genome, mass);
			int chancePercent = Math.round(chance * 100f);
			player.sendMessage(Text.literal("  Status: ").formatted(Formatting.GRAY)
					.append(Text.literal("Wild").formatted(Formatting.YELLOW))
					.append(Text.literal(" · Bait: ").formatted(Formatting.GRAY))
					.append(Text.literal(bait.getName().getString()).formatted(Formatting.GOLD))
					.append(Text.literal(String.format(" (~%d%% chance)", chancePercent)).formatted(Formatting.DARK_GREEN)), false);
		}

		player.sendMessage(Text.literal(String.format("  Classification: Gen %d · %s · %s · %s attack",
				genome.generation(),
				diet.name().toLowerCase(),
				temperament.name().toLowerCase(),
				creature.getAttackStyle().name().toLowerCase().replace('_', ' '))).formatted(Formatting.WHITE), false);

		if (plan != null) {
			player.sendMessage(Text.literal(String.format(
					"  Dimensions: %.2fm L × %.2fm W × %.2fm H (Mass: %.2f)",
					plan.bodyLength, plan.width(), plan.height(), plan.mass)).formatted(Formatting.GRAY), false);
			player.sendMessage(Text.literal(String.format(
					"  Anatomy: %d legs (%d segs) · %d arms · %d bones",
					plan.legs.length,
					plan.legs.length == 0 ? 0 : plan.legs[0].bones.length,
					plan.arms.length, plan.bones.length)).formatted(Formatting.DARK_GRAY), false);
		}

		player.sendMessage(Text.literal(String.format(
				"  Stats: Speed %d%% · Aggression %d%% · Fear %d%% · Social %d%%",
				Math.round(genome.raw(Gene.SPEED) * 100f),
				Math.round(genome.raw(Gene.AGGRESSION) * 100f),
				Math.round(genome.raw(Gene.FEAR) * 100f),
				Math.round(genome.raw(Gene.SOCIABILITY) * 100f))).formatted(Formatting.LIGHT_PURPLE), false);

		player.sendMessage(Text.literal(String.format("  Health: %.1f / %.1f",
				creature.getHealth(), creature.getMaxHealth())).formatted(Formatting.RED), false);

		player.getWorld().playSound(null, creature.getBlockPos(),
				SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), SoundCategory.PLAYERS, 0.6f, 1.6f);

		player.getItemCooldownManager().set(this, 10);
		return ActionResult.CONSUME;
	}
}
