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
import net.minecraft.util.TypedActionResult;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;

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

	/**
	 * {@inheritDoc}
	 * <p>
	 * States plainly that this is not a survival item. It has no recipe, because reading a complete
	 * genome off a living animal in one click is exactly the result the lab pipeline exists to make
	 * a player earn — but it remains the fastest way to check what the generator actually produced,
	 * which is worth keeping for creative building and for debugging.
	 */
	@Override
	public void appendTooltip(ItemStack stack, TooltipContext context, java.util.List<Text> tooltip,
	                          net.minecraft.item.tooltip.TooltipType type) {
		tooltip.add(Text.literal("Creative-mode instrument").formatted(Formatting.LIGHT_PURPLE));
		tooltip.add(Text.literal("Not craftable in survival — use the lab pipeline:")
				.formatted(Formatting.DARK_GRAY));
		tooltip.add(Text.literal("Biopsy Kit → Gene Sequencer → Genome Decoder")
				.formatted(Formatting.DARK_GRAY, Formatting.ITALIC));
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
	public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
		ItemStack stack = player.getStackInHand(hand);
		if (world.isClient() || !(world instanceof ServerWorld serverWorld)) {
			return TypedActionResult.success(stack, world.isClient());
		}

		RegionPos pos = RegionPos.of(player.getBlockPos());
		RegionRecord record = RegionLedger.get(serverWorld).existing(pos);

		if (record == null || !record.founded) {
			player.sendMessage(Text.literal("── Survey inconclusive ──")
					.formatted(Formatting.DARK_RED, Formatting.BOLD), false);
			player.sendMessage(Text.literal("  No ecological record for this region yet.")
					.formatted(Formatting.DARK_GRAY), false);
			player.getItemCooldownManager().set(this, 20);
			return TypedActionResult.consume(stack);
		}

		player.sendMessage(Text.literal("── Survey " + pos + " ──")
				.formatted(Formatting.AQUA, Formatting.BOLD), false);
		player.sendMessage(Text.literal(String.format("  Vegetation: %.0f%% of %.0f%% capacity",
				record.vegetation * 100f, record.productivity * 100f))
				.formatted(record.vegetation < record.productivity * 0.4f
						? Formatting.RED : Formatting.GREEN), false);

		if (record.lineages.isEmpty()) {
			player.sendMessage(Text.literal("  No fauna recorded — this region is empty.")
					.formatted(Formatting.DARK_GRAY), false);
		}
		for (LineageRecord lineage : record.lineages) {
			float trend = lineage.trend();
			String arrow = trend > 0.5f ? "▲ growing" : trend < -0.5f ? "▼ declining" : "· steady";
			String hex = Long.toHexString(lineage.id);
			hex = hex.substring(0, Math.min(6, hex.length())).toUpperCase();
			player.sendMessage(Text.literal(String.format(
					"  [%s] %.0f individuals · gen %d · %s",
					hex, lineage.total(), lineage.generation, arrow))
					.formatted(trend > 0.5f ? Formatting.GREEN
							: trend < -0.5f ? Formatting.RED : Formatting.WHITE), false);
		}

		world.playSound(null, player.getBlockPos(),
				SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), SoundCategory.PLAYERS, 0.5f, 1.2f);
		player.getItemCooldownManager().set(this, 20);
		return TypedActionResult.consume(stack);
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
	public ActionResult useOnEntity(ItemStack stack, PlayerEntity player, LivingEntity entity, Hand hand) {
		if (!(entity instanceof CreatureEntity creature)) {
			// Pointing the instrument at a cow is a reasonable thing for a player to try, and
			// silently doing nothing reads as a broken item. Say why instead: vanilla animals are
			// authored, not grown, so there is no gene sequence in them to resolve.
			if (player.getWorld().isClient()) return ActionResult.SUCCESS;

			player.sendMessage(Text.literal("── Scan inconclusive ──")
					.formatted(Formatting.DARK_RED, Formatting.BOLD), false);
			player.sendMessage(Text.literal("  " + entity.getName().getString())
					.formatted(Formatting.GRAY)
					.append(Text.literal(": genome too simple to read — a fixed form, not a grown one.")
							.formatted(Formatting.DARK_GRAY)), false);

			player.getWorld().playSound(null, entity.getBlockPos(),
					SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), SoundCategory.PLAYERS, 0.5f, 0.7f);
			player.getItemCooldownManager().set(this, 10);
			return ActionResult.CONSUME;
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

		player.sendMessage(Text.literal(String.format("  Health: %.1f / %.1f · Energy: %.0f%% (%s)",
				creature.getHealth(), creature.getMaxHealth(),
				creature.getEnergy() * 100f, state(creature))).formatted(Formatting.RED), false);

		player.getWorld().playSound(null, creature.getBlockPos(),
				SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), SoundCategory.PLAYERS, 0.6f, 1.6f);

		player.getItemCooldownManager().set(this, 10);
		return ActionResult.CONSUME;
	}
}
