package dev.jsz.primordia.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.jsz.primordia.genome.Archetype;
import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanCache;
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
						.then(CommandManager.literal("clear")
								.executes(ctx -> clear(ctx, 32))
								.then(CommandManager.argument("radius", IntegerArgumentType.integer(1, 256))
										.executes(ctx -> clear(ctx, IntegerArgumentType.getInteger(ctx, "radius")))))
						.then(CommandManager.literal("info")
								.executes(PrimordiaCommands::info))
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
		source.sendFeedback(() -> Text.literal("  " + genome.encode()).formatted(Formatting.DARK_GRAY), false);
		return 1;
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
