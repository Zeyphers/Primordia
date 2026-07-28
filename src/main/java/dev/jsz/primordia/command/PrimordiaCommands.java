package dev.jsz.primordia.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.jsz.primordia.genome.Archetype;
import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanCache;
import dev.jsz.primordia.body.BoneDef;
import dev.jsz.primordia.body.EarType;
import dev.jsz.primordia.body.EyeStyle;
import dev.jsz.primordia.body.Feature;
import dev.jsz.primordia.body.HornType;
import dev.jsz.primordia.body.SdfBlob;
import dev.jsz.primordia.body.TailShape;
import dev.jsz.primordia.ecology.region.LineageRecord;
import dev.jsz.primordia.ecology.region.RegionLedger;
import dev.jsz.primordia.ecology.region.RegionMaterialiser;
import dev.jsz.primordia.ecology.region.RegionPos;
import dev.jsz.primordia.ecology.region.RegionRecord;
import dev.jsz.primordia.ecology.region.RegionSimulation;
import dev.jsz.primordia.entity.CreatureEntity;
import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.genome.Mutation;
import dev.jsz.primordia.mesh.GenomeMeshCache;
import dev.jsz.primordia.registry.PrimordiaEntities;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Debug and authoring commands. The naturalist tooling proper (genome scanner item, field
 * journal UI) will replace most of these, but for building and tuning the generator these are
 * the fastest way to get creatures in front of you.
 */
public final class PrimordiaCommands {
	private static final double SEARCH_RADIUS = 32.0;

	private PrimordiaCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(CommandManager.literal("primordia")
						.requires(source -> source.hasPermissionLevel(2))
						.then(CommandManager.literal("spawn")
								.executes(ctx -> spawn(ctx, 1, null, null))
								.then(CommandManager.argument("count", IntegerArgumentType.integer(1, 64))
										.executes(ctx -> spawn(ctx, IntegerArgumentType.getInteger(ctx, "count"), null, null))
										.then(CommandManager.argument("archetype", StringArgumentType.word())
												.suggests((c, b) -> {
													b.suggest("mixed");
													for (Archetype a : Archetype.VALUES) b.suggest(a.name().toLowerCase());
													return b.buildFuture();
												})
												.executes(ctx -> spawn(ctx,
														IntegerArgumentType.getInteger(ctx, "count"),
														StringArgumentType.getString(ctx, "archetype"), null))
												.then(CommandManager.argument("seed", LongArgumentType.longArg())
														.executes(ctx -> spawn(ctx,
																IntegerArgumentType.getInteger(ctx, "count"),
																StringArgumentType.getString(ctx, "archetype"),
																LongArgumentType.getLong(ctx, "seed")))))))
						.then(CommandManager.literal("test")
								.executes(ctx -> spawnTestGrid(ctx, null, false))
								.then(CommandManager.literal("reload")
										.executes(ctx -> spawnTestGrid(ctx, null, true)))
								// "walk" means walk, not "flip whatever it is doing" — an imperative
								// that sometimes stops the thing it names is a trap.
								.then(CommandManager.literal("walk")
										.executes(ctx -> setTestWalk(ctx, true)))
								.then(CommandManager.literal("stand")
										.executes(ctx -> setTestWalk(ctx, false)))
								.then(CommandManager.argument("seed", LongArgumentType.longArg())
										.executes(ctx -> spawnTestGrid(ctx,
												LongArgumentType.getLong(ctx, "seed"), true))))
						.then(CommandManager.literal("clear")
								.executes(ctx -> clear(ctx, 32))
								.then(CommandManager.argument("radius", IntegerArgumentType.integer(1, 256))
										.executes(ctx -> clear(ctx, IntegerArgumentType.getInteger(ctx, "radius")))))
						.then(CommandManager.literal("info")
								.executes(PrimordiaCommands::info))
						.then(CommandManager.literal("region")
								.executes(PrimordiaCommands::region))
						.then(CommandManager.literal("breed")
								.executes(PrimordiaCommands::breed))
						.then(CommandManager.literal("mutate")
								.executes(PrimordiaCommands::mutate))
						.then(CommandManager.literal("stats")
								.executes(PrimordiaCommands::stats))
						.then(CommandManager.literal("editor")
								.executes(PrimordiaCommands::openEditor))));
	}

	private static int openEditor(CommandContext<ServerCommandSource> ctx) {
		try {
			var player = ctx.getSource().getPlayerOrThrow();
			dev.jsz.primordia.editor.EditorServer.start(player);
			net.minecraft.util.Util.getOperatingSystem().open(dev.jsz.primordia.editor.EditorServer.getUrl());
			ctx.getSource().sendFeedback(() -> Text.literal("Opening 3D Spore Creature Editor at " + dev.jsz.primordia.editor.EditorServer.getUrl()).formatted(Formatting.GREEN), false);
			return 1;
		} catch (Exception e) {
			ctx.getSource().sendError(Text.literal("Must be run by a player"));
			return 0;
		}
	}

	// ------------------------------------------------------------------ actions

	private static int spawn(CommandContext<ServerCommandSource> ctx, int count, String archetypeName, Long seed) {
		ServerCommandSource source = ctx.getSource();
		ServerWorld world = source.getWorld();
		Vec3d pos = source.getPosition();
		// A supplied seed makes the whole batch reproducible, which is what you want when
		// iterating on the generator and comparing before/after.
		Random random = seed == null ? new Random() : new Random(seed);

		// "mixed" (or nothing) gives one of each structured archetype rather than a uniform draw,
		// because a uniform draw is statistically always the same mid-sized quadruped.
		boolean mixed = archetypeName == null || archetypeName.equalsIgnoreCase("mixed");
		Archetype fixed = mixed ? null : Archetype.byName(archetypeName);
		if (!mixed && fixed == null) {
			source.sendError(Text.literal("Unknown archetype '" + archetypeName + "'. Options: mixed, "
					+ String.join(", ", java.util.Arrays.stream(Archetype.VALUES)
					.map(a -> a.name().toLowerCase()).toList())));
			return 0;
		}

		int spawned = 0;
		Genome last = null;
		for (int i = 0; i < count; i++) {
			CreatureEntity creature = PrimordiaEntities.CREATURE.create(world);
			if (creature == null) continue;
			Archetype archetype = fixed != null ? fixed : Archetype.randomStructured(random);
			Genome genome = archetype.create(random);
			// Spread a batch out so they are not stacked inside one another.
			double spread = count == 1 ? 0.0 : 1.5 + count * 0.15;
			creature.refreshPositionAndAngles(
					pos.x + (random.nextDouble() - 0.5) * spread,
					pos.y,
					pos.z + (random.nextDouble() - 0.5) * spread,
					random.nextFloat() * 360f, 0f);
			creature.setGenome(genome);
			world.spawnEntity(creature);
			last = genome;
			spawned++;
		}

		int finalSpawned = spawned;
		Genome reported = last;
		source.sendFeedback(() -> Text.literal("Spawned " + finalSpawned + " creature(s)"
				+ (reported == null ? "" : " — last: " + describe(reported))).formatted(Formatting.GREEN), false);
		return spawned;
	}

	/**
	 * Where the last test grid was laid out, so {@code reload} can re-roll it without moving it.
	 * Held in memory only: losing it across a restart just means the next grid is placed at the
	 * player again, which is the same thing a first {@code /primordia test} does anyway.
	 */
	private static Vec3d gridAnchor;
	private static float gridAnchorYaw;
	private static net.minecraft.registry.RegistryKey<net.minecraft.world.World> gridAnchorWorld;

	/** The three points on the SIZE locus the test grid samples: runt, median, giant. */
	private static final float[] TEST_SIZES = {0.12f, 0.50f, 0.92f};
	private static final String[] TEST_SIZE_NAMES = {"small", "mid", "large"};
	/** Blocks between neighbouring specimens. Wide enough for a large one not to overlap its row. */
	private static final double TEST_COLUMN_SPACING = 7.0;
	private static final double TEST_ROW_SPACING = 6.0;
	/** How far ahead of the player the near edge of the grid sits. */
	private static final double TEST_GRID_OFFSET = 8.0;

	/**
	 * Lays out one specimen of every archetype at three sizes, walking on the spot, in a grid in
	 * front of the player. Re-running clears the previous grid first, so it is safe to spam while
	 * iterating on the generator — which is the whole point: comparing a change against the
	 * previous build is impossible if you cannot get the same set of animals side by side.
	 * <p>
	 * A null seed re-rolls; passing one reproduces an exact grid.
	 */
	private static int spawnTestGrid(CommandContext<ServerCommandSource> ctx, Long seed, boolean inPlace) {
		ServerCommandSource source = ctx.getSource();
		ServerWorld world = source.getWorld();

		// Clear the previous grid wherever it was, so reloading never leaves a stale population
		// behind to be mistaken for the new one.
		int removed = 0;
		for (CreatureEntity old : nearby(source, 128)) {
			if (old.isPosing()) {
				old.discard();
				removed++;
			}
		}

		// Reloading re-rolls the specimens without moving the exhibit. Laying the new set down at
		// wherever the player happens to be standing means every reload is viewed from a different
		// angle and distance, which is precisely what you cannot afford when the point is to
		// compare one set against the last.
		boolean reuse = inPlace && gridAnchor != null && gridAnchorWorld == world.getRegistryKey();
		Vec3d origin = reuse ? gridAnchor : source.getPosition();
		float yaw = reuse ? gridAnchorYaw : source.getRotation().y;
		gridAnchor = origin;
		gridAnchorYaw = yaw;
		gridAnchorWorld = world.getRegistryKey();

		long actualSeed = seed == null ? System.nanoTime() : seed;
		Random random = new Random(actualSeed);

		double forwardX = -Math.sin(Math.toRadians(yaw));
		double forwardZ = Math.cos(Math.toRadians(yaw));
		double rightX = -forwardZ;
		double rightZ = forwardX;
		// Specimens face the player rather than away.
		float facing = yaw + 180f;

		int spawned = 0;
		for (int row = 0; row < Archetype.VALUES.length; row++) {
			Archetype archetype = Archetype.VALUES[row];
			for (int col = 0; col < TEST_SIZES.length; col++) {
				// One genome per cell, then the size locus is overwritten so the three in a row
				// are the same animal at three scales rather than three unrelated ones.
				Genome genome = archetype.create(random).with(Gene.SIZE, TEST_SIZES[col]);

				CreatureEntity creature = PrimordiaEntities.CREATURE.create(world);
				if (creature == null) continue;

				double along = TEST_GRID_OFFSET + row * TEST_ROW_SPACING;
				double across = (col - (TEST_SIZES.length - 1) / 2.0) * TEST_COLUMN_SPACING;
				double x = origin.x + forwardX * along + rightX * across;
				double z = origin.z + forwardZ * along + rightZ * across;

				creature.refreshPositionAndAngles(x, origin.y, z, facing, 0f);
				// Head and body have to be set to the same facing explicitly. refreshPositionAndAngles
				// only sets the body, and a specimen with AI disabled never runs the head easing that
				// would otherwise bring the two together.
				creature.setHeadYaw(facing);
				creature.setBodyYaw(facing);
				creature.prevHeadYaw = facing;
				creature.prevBodyYaw = facing;
				creature.setGenome(genome);
				creature.setCustomName(Text.literal(
						archetype.name().toLowerCase() + " · " + TEST_SIZE_NAMES[col]));
				creature.setCustomNameVisible(true);
				world.spawnEntity(creature);
				// After spawning: setPosing stops the navigator, which has to exist first.
				creature.setPosing(true);
				spawned++;
			}
		}

		int finalSpawned = spawned;
		int finalRemoved = removed;
		source.sendFeedback(() -> Text.literal(String.format(
						"Test grid: %d specimens (%d archetypes × %d sizes), cleared %d — seed %d",
						finalSpawned, Archetype.VALUES.length, TEST_SIZES.length,
						finalRemoved, actualSeed))
				.formatted(Formatting.AQUA), false);
		source.sendFeedback(() -> Text.literal("  reload re-rolls in place · walk / stand · "
				+ "/primordia test " + actualSeed + " restores this set")
				.formatted(Formatting.DARK_GRAY), false);
		return spawned;
	}

	/**
	 * Starts or stops the walk cycle on every specimen in the test grid. A null {@code walking}
	 * flips whatever the grid is currently doing.
	 * <p>
	 * Worth having both states: a walk cycle is what you want for judging gait and foot placement,
	 * and a still pose is what you want for judging silhouette, proportions and where the mesh
	 * creases — each hides problems that the other makes obvious.
	 */
	private static int setTestWalk(CommandContext<ServerCommandSource> ctx, boolean walking) {
		ServerCommandSource source = ctx.getSource();
		List<CreatureEntity> posed = nearby(source, 128).stream()
				.filter(CreatureEntity::isPosing)
				.toList();

		if (posed.isEmpty()) {
			source.sendError(Text.literal("No test grid nearby — run /primordia test first"));
			return 0;
		}

		for (CreatureEntity creature : posed) {
			creature.setPoseWalking(walking);
		}

		source.sendFeedback(() -> Text.literal(walking
						? "Test grid walking (" + posed.size() + " specimens)"
						: "Test grid standing (" + posed.size() + " specimens)")
				.formatted(Formatting.AQUA), false);
		return posed.size();
	}

	private static int clear(CommandContext<ServerCommandSource> ctx, int radius) {
		ServerCommandSource source = ctx.getSource();
		List<CreatureEntity> found = nearby(source, radius);
		for (CreatureEntity creature : found) {
			creature.discard();
		}
		source.sendFeedback(() -> Text.literal("Removed " + found.size() + " creature(s)")
				.formatted(Formatting.YELLOW), false);
		return found.size();
	}

	private static int info(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource source = ctx.getSource();
		CreatureEntity creature = nearest(source);
		if (creature == null) {
			source.sendError(Text.literal("No creature within " + (int) SEARCH_RADIUS + " blocks"));
			return 0;
		}
		Genome genome = creature.getGenome();
		if (genome == null) {
			source.sendError(Text.literal("Creature has no genome"));
			return 0;
		}
		BodyPlan plan = BodyPlanCache.get(genome);

		source.sendFeedback(() -> Text.literal("── " + describe(genome) + " ──").formatted(Formatting.AQUA), false);
		source.sendFeedback(() -> Text.literal(String.format(
				"  bones %d · limbs %d legs / %d arms · %d bone segments per leg",
				plan.bones.length, plan.legs.length, plan.arms.length,
				plan.legs.length == 0 ? 0 : plan.legs[0].bones.length)), false);
		source.sendFeedback(() -> Text.literal(String.format(
				"  size %.2f×%.2f×%.2f m · hip %.2f m · mass %.3f",
				plan.width(), plan.height(), plan.bodyLength, plan.hipHeight, plan.mass)), false);
		source.sendFeedback(() -> Text.literal(String.format(
				"  diet %.2f · speed %.2f · aggression %.2f · social %.2f · mutability %.2f",
				genome.raw(Gene.DIET), genome.raw(Gene.SPEED), genome.raw(Gene.AGGRESSION),
				genome.raw(Gene.SOCIABILITY), genome.raw(Gene.MUTABILITY))), false);
		source.sendFeedback(() -> Text.literal("  " + describeAnatomy(genome, plan))
				.formatted(Formatting.GRAY), false);
		source.sendFeedback(() -> Text.literal("  " + describeEcology(creature, genome))
				.formatted(Formatting.GREEN), false);
		source.sendFeedback(() -> Text.literal("  " + genome.encode()).formatted(Formatting.DARK_GRAY), false);
		return 1;
	}

	/**
	 * The creature's ecological state: how fed it is, what that makes it willing to do, and how far
	 * through its life it is.
	 * <p>
	 * Energy is the gate on hunting, foraging and breeding and is not replicated to clients or shown
	 * anywhere else, so without this line there is no way to tell a predator that has just eaten
	 * from one that is broken.
	 */
	private static String describeEcology(CreatureEntity creature, Genome genome) {
		if (creature.isCarcass()) {
			return String.format("carcass · %.0f%% remaining",
					creature.getCarcassNutrition()
							/ Math.max(0.001f, dev.jsz.primordia.ecology.EnergyBudget
							.carcassNutrition(BodyPlanCache.get(genome))) * 100f);
		}
		String state;
		if (creature.isAsleep()) {
			state = "asleep";
		} else if (creature.wantsToHunt()) {
			state = "hunting";
		} else if (creature.isHungry()) {
			state = "foraging";
		} else {
			state = "fed";
		}
		int maturity = dev.jsz.primordia.ecology.EnergyBudget.maturityTicks(genome);
		return String.format("energy %.0f%% · %s · %s · gen %d",
				creature.getEnergy() * 100f,
				state,
				creature.isMature() ? "adult" : String.format("juvenile (%d%%)",
						Math.round(100f * creature.getLifeTicks() / (float) maturity)),
				genome.generation());
	}

	/**
	 * One line naming the ornament traits the creature actually expresses. Absent traits are
	 * omitted rather than printed as "none", so the line reads as a description of this animal
	 * instead of a checklist that is mostly empty.
	 */
	private static String describeAnatomy(Genome genome, BodyPlan plan) {
		StringBuilder out = new StringBuilder();
		out.append(EyeStyle.of(genome).name().toLowerCase()).append(" eyes");

		HornType horns = HornType.of(genome);
		if (horns != HornType.NONE) out.append(" · ").append(horns.name().toLowerCase()).append(" horns");

		EarType ears = EarType.of(genome);
		if (ears != EarType.NONE) out.append(" · ").append(ears.name().toLowerCase()).append(" ears");

		boolean hasTail = false;
		for (BoneDef bone : plan.bones) {
			if (bone.feature == Feature.TAIL) hasTail = true;
		}
		if (hasTail) out.append(" · ").append(TailShape.of(genome).name().toLowerCase()).append(" tail");

		for (SdfBlob blob : plan.blobs) {
			if (blob.feature() == Feature.ABDOMEN) {
				out.append(" · segmented body");
				break;
			}
		}
		if (plan.palette.glowStrength > 0f) {
			out.append(String.format(" · %s glow %.0f%%",
					plan.palette.glowRegion.name().toLowerCase().replace('_', ' '),
					plan.palette.glowStrength * 100f));
		}
		return out.toString();
	}

	private static int breed(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource source = ctx.getSource();
		List<CreatureEntity> found = nearby(source, (int) SEARCH_RADIUS);
		found.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(source.getPosition())));
		if (found.size() < 2) {
			source.sendError(Text.literal("Need two creatures nearby to breed"));
			return 0;
		}
		Genome a = found.get(0).getGenome();
		Genome b = found.get(1).getGenome();
		if (a == null || b == null) {
			source.sendError(Text.literal("One of the parents has no genome"));
			return 0;
		}

		Random random = new Random();
		Genome child = Mutation.breed(a, b, random);
		spawnWithGenome(source, child);

		float divergence = Mutation.distance(child, a);
		source.sendFeedback(() -> Text.literal(String.format(
				"Bred %s — divergence from parent A: %.3f%s",
				describe(child), divergence,
				child.lineage() != a.lineage() ? " (NEW LINEAGE)" : "")).formatted(Formatting.LIGHT_PURPLE), false);
		return 1;
	}

	private static int mutate(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource source = ctx.getSource();
		CreatureEntity parent = nearest(source);
		if (parent == null || parent.getGenome() == null) {
			source.sendError(Text.literal("No creature within " + (int) SEARCH_RADIUS + " blocks"));
			return 0;
		}
		Genome child = Mutation.mutate(parent.getGenome(), new Random());
		spawnWithGenome(source, child);
		source.sendFeedback(() -> Text.literal("Spawned mutated offspring: " + describe(child))
				.formatted(Formatting.LIGHT_PURPLE), false);
		return 1;
	}

	private static int stats(CommandContext<ServerCommandSource> ctx) {
		ctx.getSource().sendFeedback(() -> Text.literal(String.format(
				"meshes ready %d · baking %d · body plans cached %d",
				GenomeMeshCache.readyCount(), GenomeMeshCache.pendingCount(), BodyPlanCache.size())), false);
		return 1;
	}

	// ------------------------------------------------------------------ helpers

	private static void spawnWithGenome(ServerCommandSource source, Genome genome) {
		ServerWorld world = source.getWorld();
		CreatureEntity creature = PrimordiaEntities.CREATURE.create(world);
		if (creature == null) return;
		Vec3d pos = source.getPosition();
		creature.refreshPositionAndAngles(pos.x, pos.y, pos.z, 0f, 0f);
		creature.setGenome(genome);
		world.spawnEntity(creature);
	}

	private static List<CreatureEntity> nearby(ServerCommandSource source, double radius) {
		Vec3d pos = source.getPosition();
		Box box = new Box(pos.x - radius, pos.y - radius, pos.z - radius,
				pos.x + radius, pos.y + radius, pos.z + radius);
		return source.getWorld().getEntitiesByClass(CreatureEntity.class, box, e -> true);
	}

	private static CreatureEntity nearest(ServerCommandSource source) {
		Vec3d pos = source.getPosition();
		return nearby(source, SEARCH_RADIUS).stream()
				.min(Comparator.comparingDouble(e -> e.squaredDistanceTo(pos)))
				.orElse(null);
	}

	/**
	 * Reads out the ledger for the region the player is standing in.
	 * <p>
	 * The ecology's only window. Almost everything the regional simulation does happens where
	 * nobody is looking and leaves no trace an observer could read — a population that halved while
	 * the player was away looks exactly like one that was always that size. Without this the whole
	 * off-screen layer is unfalsifiable, both to the player and to anyone tuning it.
	 */
	private static int region(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource source = ctx.getSource();
		ServerWorld world = source.getWorld();
		BlockPos pos = BlockPos.ofFloored(source.getPosition());
		RegionPos regionPos = RegionPos.of(pos);
		RegionRecord record = RegionLedger.get(world).existing(regionPos);

		if (record == null || !record.founded) {
			source.sendError(Text.literal("No ecology recorded for " + regionPos
					+ " yet — it is founded a few seconds after a player arrives."));
			return 0;
		}

		long day = world.getTime() / RegionSimulation.TICKS_PER_STEP;
		source.sendFeedback(() -> Text.literal("── " + regionPos + " ──").formatted(Formatting.AQUA), false);
		source.sendFeedback(() -> Text.literal(String.format(
				"  vegetation %.0f%% of %.0f%% capacity · temp %.2f · humidity %.2f",
				record.vegetation * 100f, record.productivity * 100f,
				record.temperature, record.humidity)), false);
		source.sendFeedback(() -> Text.literal(String.format(
				"  day %d · last simulated day %d · %d lineage(s)",
				day, record.lastStep, record.lineages.size())).formatted(Formatting.GRAY), false);

		if (record.lineages.isEmpty()) {
			source.sendFeedback(() -> Text.literal("  no fauna — this region is empty")
					.formatted(Formatting.DARK_GRAY), false);
			return 1;
		}

		for (LineageRecord lineage : record.lineages) {
			int live = 0;
			for (CreatureEntity creature : RegionMaterialiser.liveIn(world, regionPos)) {
				Genome g = creature.getGenome();
				if (g != null && g.lineage() == lineage.id) live++;
			}
			final int liveCount = live;
			source.sendFeedback(() -> Text.literal(String.format(
					"  %s %s  pop %.1f (%d live) · mass %.3f · %s · gen %d · var %.2f",
					trendArrow(lineage.trend()),
					shortId(lineage.id),
					lineage.total(), liveCount, lineage.meanMass(),
					dietLabel(lineage.meanOf(Gene.DIET)),
					lineage.generation, lineage.variance))
					.formatted(trendColour(lineage.trend())), false);
		}
		return 1;
	}

	private static String trendArrow(float trend) {
		if (trend > 0.5f) return "▲";
		if (trend < -0.5f) return "▼";
		return "·";
	}

	private static Formatting trendColour(float trend) {
		if (trend > 0.5f) return Formatting.GREEN;
		if (trend < -0.5f) return Formatting.RED;
		return Formatting.WHITE;
	}

	private static String dietLabel(float diet) {
		if (diet < 0.35f) return String.format("herbivore %.2f", diet);
		if (diet < 0.65f) return String.format("omnivore %.2f", diet);
		return String.format("carnivore %.2f", diet);
	}

	private static String shortId(long lineage) {
		String hex = Long.toHexString(lineage);
		return hex.substring(0, Math.min(6, hex.length()));
	}

	private static String describe(Genome genome) {
		String hex = Long.toHexString(genome.lineage());
		return "lineage " + hex.substring(0, Math.min(6, hex.length())) + " gen " + genome.generation();
	}

	/** Unused today, kept so entity-selector variants of these commands are a one-line addition. */
	@SuppressWarnings("unused")
	private static Entity resolveTarget(CommandContext<ServerCommandSource> ctx, String name)
			throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		return EntityArgumentType.getEntity(ctx, name);
	}
}
