package dev.jsz.primordia.item;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.DietGroup;
import dev.jsz.primordia.ecology.region.LineageRecord;
import dev.jsz.primordia.ecology.region.RegionLedger;
import dev.jsz.primordia.ecology.region.RegionPos;
import dev.jsz.primordia.ecology.region.RegionRecord;
import dev.jsz.primordia.entity.CreatureEntity;
import dev.jsz.primordia.entity.TamingPreference;
import dev.jsz.primordia.entity.Temperament;
import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Genome;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.function.Consumer;

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

	public GenomeScannerItem(Properties settings) {
		super(settings);
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> tooltipAdder, TooltipFlag flag) {
		tooltipAdder.accept(Component.literal("Creative-mode instrument").withStyle(ChatFormatting.LIGHT_PURPLE));
		tooltipAdder.accept(Component.literal("Not craftable in survival — use the lab pipeline:")
				.withStyle(ChatFormatting.DARK_GRAY));
		tooltipAdder.accept(Component.literal("Biopsy Kit → Gene Sequencer → Genome Decoder")
				.withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
	}

	/**
	 * Pointed at nothing, the instrument surveys the region instead of an individual.
	 * <p>
	 * This is the only way the player can perceive the half of the ecology that happens where they
	 * are not. A population that halved while they were away looks exactly like one that was always
	 * that size — the world moving without you is worth nothing if there is no way to tell that it
	 * moved. Populations, trends and the state of the vegetation are what make the regional
	 * simulation something a player can notice, disbelieve, and go and check.
	 */
	@Override
	public InteractionResult use(Level world, Player player, InteractionHand hand) {
		if (world.isClientSide() || !(world instanceof ServerLevel serverWorld)) {
			return InteractionResult.SUCCESS;
		}

		// Cooldowns are keyed by stack rather than by item in 26.2, and `use` is not handed one.
		ItemStack stack = player.getItemInHand(hand);
		RegionPos pos = RegionPos.of(player.blockPosition());
		RegionRecord record = RegionLedger.get(serverWorld).existing(pos);

		if (record == null || !record.founded) {
			player.sendSystemMessage(Component.literal("── Survey inconclusive ──")
					.withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
			player.sendSystemMessage(Component.literal("  No ecological record for this region yet.")
					.withStyle(ChatFormatting.DARK_GRAY));
			player.getCooldowns().addCooldown(stack, 20);
			return InteractionResult.CONSUME;
		}

		player.sendSystemMessage(Component.literal("── Survey " + pos + " ──")
				.withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
		player.sendSystemMessage(Component.literal(String.format("  Vegetation: %.0f%% of %.0f%% capacity",
				record.vegetation * 100f, record.productivity * 100f))
				.withStyle(record.vegetation < record.productivity * 0.4f
						? ChatFormatting.RED : ChatFormatting.GREEN));

		if (record.lineages.isEmpty()) {
			player.sendSystemMessage(Component.literal("  No fauna recorded — this region is empty.")
					.withStyle(ChatFormatting.DARK_GRAY));
		}
		for (LineageRecord lineage : record.lineages) {
			float trend = lineage.trend();
			String arrow = trend > 0.5f ? "▲ growing" : trend < -0.5f ? "▼ declining" : "· steady";
			String hex = Long.toHexString(lineage.id);
			hex = hex.substring(0, Math.min(6, hex.length())).toUpperCase();
			player.sendSystemMessage(Component.literal(String.format(
					"  [%s] %.0f individuals · gen %d · %s",
					hex, lineage.total(), lineage.generation, arrow))
					.withStyle(trend > 0.5f ? ChatFormatting.GREEN
							: trend < -0.5f ? ChatFormatting.RED : ChatFormatting.WHITE));
		}

		world.playSound(null, player.blockPosition(),
				SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.PLAYERS, 0.5f, 1.2f);
		player.getCooldowns().addCooldown(stack, 20);
		return InteractionResult.CONSUME;
	}

	/** One word for what this creature is currently doing about being fed. */
	private static String state(CreatureEntity creature) {
		if (creature.isCarcass()) return "dead";
		if (creature.isAsleep()) return "asleep";
		if (creature.wantsToHunt()) return "hunting";
		if (creature.isHungry()) return "foraging";
		return "fed";
	}

	@Override
	public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity entity, InteractionHand hand) {
		if (!(entity instanceof CreatureEntity creature)) {
			if (player.level().isClientSide()) return InteractionResult.SUCCESS;

			player.sendSystemMessage(Component.literal("── Scan inconclusive ──")
					.withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
			player.sendSystemMessage(Component.literal("  " + entity.getName().getString())
					.withStyle(ChatFormatting.GRAY)
					.append(Component.literal(": genome too simple to read — a fixed form, not a grown one.")
							.withStyle(ChatFormatting.DARK_GRAY)));

			player.level().playSound(null, entity.blockPosition(),
					SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.PLAYERS, 0.5f, 0.7f);
			player.getCooldowns().addCooldown(stack, 10);
			return InteractionResult.CONSUME;
		}
		if (player.level().isClientSide()) {
			return InteractionResult.SUCCESS;
		}

		Genome genome = creature.getGenome();
		if (genome == null) {
			player.sendSystemMessage(Component.literal("Scan failed: no genome").withStyle(ChatFormatting.RED));
			return InteractionResult.CONSUME;
		}
		BodyPlan plan = creature.getBodyPlan();
		DietGroup diet = creature.getDietGroup();
		Temperament temperament = creature.getTemperament();

		String hex = Long.toHexString(genome.lineage());
		hex = hex.substring(0, Math.min(6, hex.length()));

		player.sendSystemMessage(Component.literal("── Specimen [" + hex.toUpperCase() + "] ──").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
		
		if (creature.isTamed()) {
			String ownerText = creature.isSaddled() ? "Tamed (Saddled)" : "Tamed";
			player.sendSystemMessage(Component.literal("  Status: ").withStyle(ChatFormatting.GRAY)
					.append(Component.literal(ownerText).withStyle(ChatFormatting.GREEN)));
		} else {
			Item bait = creature.getFavouriteFood();
			float mass = plan == null ? 0.2f : plan.mass;
			float chance = TamingPreference.tameChance(genome, mass);
			int chancePercent = Math.round(chance * 100f);
			player.sendSystemMessage(Component.literal("  Status: ").withStyle(ChatFormatting.GRAY)
					.append(Component.literal("Wild").withStyle(ChatFormatting.YELLOW))
					.append(Component.literal(" · Bait: ").withStyle(ChatFormatting.GRAY))
					.append(Component.literal(bait.getDefaultInstance().getHoverName().getString()).withStyle(ChatFormatting.GOLD))
					.append(Component.literal(String.format(" (~%d%% chance)", chancePercent)).withStyle(ChatFormatting.DARK_GREEN)));
		}

		player.sendSystemMessage(Component.literal(String.format("  Classification: Gen %d · %s · %s · %s attack",
				genome.generation(),
				diet.name().toLowerCase(),
				temperament.name().toLowerCase(),
				creature.getAttackStyle().name().toLowerCase().replace('_', ' '))).withStyle(ChatFormatting.WHITE));

		if (plan != null) {
			player.sendSystemMessage(Component.literal(String.format(
					"  Dimensions: %.2fm L × %.2fm W × %.2fm H (Mass: %.2f)",
					plan.bodyLength, plan.width(), plan.height(), plan.mass)).withStyle(ChatFormatting.GRAY));
			player.sendSystemMessage(Component.literal(String.format(
					"  Anatomy: %d legs (%d segs) · %d arms · %d bones",
					plan.legs.length,
					plan.legs.length == 0 ? 0 : plan.legs[0].bones.length,
					plan.arms.length, plan.bones.length)).withStyle(ChatFormatting.DARK_GRAY));
		}

		player.sendSystemMessage(Component.literal(String.format(
				"  Stats: Speed %d%% · Aggression %d%% · Fear %d%% · Social %d%%",
				Math.round(genome.raw(Gene.SPEED) * 100f),
				Math.round(genome.raw(Gene.AGGRESSION) * 100f),
				Math.round(genome.raw(Gene.FEAR) * 100f),
				Math.round(genome.raw(Gene.SOCIABILITY) * 100f))).withStyle(ChatFormatting.LIGHT_PURPLE));

		player.sendSystemMessage(Component.literal(String.format("  Health: %.1f / %.1f · Energy: %.0f%% (%s)",
				creature.getHealth(), creature.getMaxHealth(),
				creature.getEnergy() * 100f, state(creature))).withStyle(ChatFormatting.RED));

		player.level().playSound(null, creature.blockPosition(),
				SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.PLAYERS, 0.6f, 1.6f);

		player.getCooldowns().addCooldown(stack, 10);
		return InteractionResult.CONSUME;
	}
}
