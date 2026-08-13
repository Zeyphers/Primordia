package dev.jsz.primordia.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.jsz.primordia.genome.Archetype;
import dev.jsz.primordia.lab.Discoveries;
import dev.jsz.primordia.lab.GuideData;
import dev.jsz.primordia.registry.PrimordiaItems;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import dev.jsz.primordia.Primordia;
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
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.world.entity.Entity;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

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

	/**
	 * The gate on everything that touches the world.
	 * <p>
	 * 26.2 swapped numeric permission levels for named ones; gamemaster is the successor to the old
	 * level 2 that gates {@code /summon} and friends. Held as a field because it is now applied per
	 * branch rather than once at the root, so that {@code editor} can sit outside it.
	 */
	private static final java.util.function.Predicate<CommandSourceStack> GAMEMASTER =
			source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);

	private PrimordiaCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(Commands.literal("primordia")
						// The root is open and every branch carries its own gate — see GAMEMASTER, which
						// all of them below use except `editor`. Putting the check here instead would be
						// tidier and would also hide `editor` from the players it is meant for: a node
						// whose parent fails `requires` is unreachable however permissive it is itself.
						.then(Commands.literal("spawn")
								.requires(GAMEMASTER)
								.executes(ctx -> spawn(ctx, 1, null, null))
								// A genome straight from the editor. Greedy, because the encoded form is
								// base64 and can carry characters a word argument would stop at.
								.then(Commands.literal("code")
										.then(Commands.argument("genome", StringArgumentType.greedyString())
												.executes(ctx -> spawnCoded(ctx,
														StringArgumentType.getString(ctx, "genome"), 1))))
								.then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
										.executes(ctx -> spawn(ctx, IntegerArgumentType.getInteger(ctx, "count"), null, null))
										.then(Commands.argument("archetype", StringArgumentType.word())
												.suggests((c, b) -> {
													b.suggest("mixed");
													for (Archetype a : Archetype.VALUES) b.suggest(a.name().toLowerCase());
													return b.buildFuture();
												})
												.executes(ctx -> spawn(ctx,
														IntegerArgumentType.getInteger(ctx, "count"),
														StringArgumentType.getString(ctx, "archetype"), null))
												.then(Commands.argument("seed", LongArgumentType.longArg())
														.executes(ctx -> spawn(ctx,
																IntegerArgumentType.getInteger(ctx, "count"),
																StringArgumentType.getString(ctx, "archetype"),
																LongArgumentType.getLong(ctx, "seed")))))))
						// Everything that exists to test the mod rather than to play it lives under
						// one node, gated on one constant. See Primordia.DEBUG_TOOLS.
						.then(Commands.literal("debug")
								.requires(GAMEMASTER.and(source -> Primordia.DEBUG_TOOLS))
								.then(Commands.literal("decay")
										.executes(ctx -> decay(ctx, 0))
										.then(Commands.argument("ticks", IntegerArgumentType.integer(1))
												.executes(ctx -> decay(ctx,
														IntegerArgumentType.getInteger(ctx, "ticks")))))
								.then(Commands.literal("skeleton")
										.executes(ctx -> spawnSkeleton(ctx, 1))
										.then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
												.executes(ctx -> spawnSkeleton(ctx,
														IntegerArgumentType.getInteger(ctx, "count")))))
								.then(Commands.literal("lava")
										.executes(ctx -> lavaMarch(ctx, LAVA_MARCH_RADIUS))
										.then(Commands.argument("radius", IntegerArgumentType.integer(1, 256))
												.executes(ctx -> lavaMarch(ctx,
														IntegerArgumentType.getInteger(ctx, "radius"))))))
						.then(Commands.literal("test")
								.requires(GAMEMASTER)
								.executes(ctx -> spawnTestGrid(ctx, null, false))
								.then(Commands.literal("reload")
										.executes(ctx -> spawnTestGrid(ctx, null, true)))
								// "walk" means walk, not "flip whatever it is doing" — an imperative
								// that sometimes stops the thing it names is a trap.
								.then(Commands.literal("walk")
										.executes(ctx -> setTestWalk(ctx, true)))
								.then(Commands.literal("stand")
										.executes(ctx -> setTestWalk(ctx, false)))
								.then(Commands.argument("seed", LongArgumentType.longArg())
										.executes(ctx -> spawnTestGrid(ctx,
												LongArgumentType.getLong(ctx, "seed"), true))))
						.then(Commands.literal("clear")
								.requires(GAMEMASTER)
								.executes(ctx -> clear(ctx, 32))
								.then(Commands.argument("radius", IntegerArgumentType.integer(1, 256))
										.executes(ctx -> clear(ctx, IntegerArgumentType.getInteger(ctx, "radius")))))
						.then(Commands.literal("info")
								.requires(GAMEMASTER)
								.executes(PrimordiaCommands::info))
						.then(Commands.literal("collect")
								.requires(GAMEMASTER)
								.executes(ctx -> collect(ctx, 48))
								.then(Commands.argument("radius", IntegerArgumentType.integer(1, 256))
										.executes(ctx -> collect(ctx,
												IntegerArgumentType.getInteger(ctx, "radius")))))
						.then(Commands.literal("region")
								.requires(GAMEMASTER)
								.executes(PrimordiaCommands::region))
						.then(Commands.literal("breed")
								.requires(GAMEMASTER)
								.executes(PrimordiaCommands::breed))
						.then(Commands.literal("mutate")
								.requires(GAMEMASTER)
								.executes(PrimordiaCommands::mutate))
						.then(Commands.literal("stats")
								.requires(GAMEMASTER)
								.executes(PrimordiaCommands::stats))
						// No gate. The editor is a modelling tool, not a way to affect the world: it
						// bakes meshes from genomes typed into a web page and can neither read the save
						// nor write to it. Gating it on cheats meant the one command a player might
						// actually want was the one they could not reach.
						.then(Commands.literal("editor")
								.executes(PrimordiaCommands::editor))));
	}

	// ------------------------------------------------------------------ actions

	/**
	 * Starts the creature editor and hands back a clickable link.
	 * <p>
	 * The link is sent rather than a browser being launched directly. A command runs on the server,
	 * and on a dedicated server "open a browser" would mean opening it on the host's machine, not
	 * the player's — so the player's own client is asked to do it, through the same confirmation
	 * screen Minecraft puts in front of every external link.
	 * <p>
	 * Idempotent: running it again with the server already up just returns the link.
	 */
	private static int editor(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		String url;
		try {
			url = dev.jsz.primordia.editor.EditorServer.start();
		} catch (java.io.IOException e) {
			source.sendFailure(Component.literal(
					"Could not start the editor: " + e.getMessage()
					+ " (is something already using that port?)"));
			return 0;
		}

		source.sendSuccess(() -> Component.literal("Creature editor running at ")
				.withStyle(ChatFormatting.GREEN)
				.append(Component.literal(url)
						.withStyle(style -> style
								.withColor(ChatFormatting.AQUA)
								.withUnderlined(true)
								.withClickEvent(new net.minecraft.network.chat.ClickEvent.OpenUrl(
										java.net.URI.create(url)))
								.withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(
										Component.literal("Open in your browser"))))), false);
		source.sendSuccess(() -> Component.literal(
				"Design a creature, copy its genome code, then /primordia spawn code <genome>")
				.withStyle(ChatFormatting.GRAY), false);
		return 1;
	}

	/**
	 * Files every nearby creature straight into the player's field guide.
	 * <p>
	 * A testing shortcut, and deliberately a blunt one: reaching a fully characterised species the
	 * honest way is twelve separate expeditions, which is the right cost when playing and an absurd
	 * one when checking that a plate lays out correctly. Bypasses the sample, the lab and the
	 * report entirely — the guide is the only thing this is for.
	 */
	private static int collect(CommandContext<CommandSourceStack> ctx, int radius) {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.literal("Must be run by a player"));
			return 0;
		}

		ItemStack guide = ItemStack.EMPTY;
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			ItemStack candidate = player.getInventory().getItem(i);
			if (candidate.is(PrimordiaItems.FIELD_GUIDE)) {
				guide = candidate;
				break;
			}
		}
		if (guide.isEmpty()) {
			source.sendFailure(Component.literal("No field guide in your inventory"));
			return 0;
		}

		AABB box = player.getBoundingBox().inflate(radius, radius, radius);
		List<CreatureEntity> nearby = player.level().getEntitiesOfClass(CreatureEntity.class, box,
				creature -> creature.isAlive() && !creature.isCarcass() && creature.getGenome() != null);

		dev.jsz.primordia.lab.PlayerGuideData global = dev.jsz.primordia.lab.PlayerGuideData.get((net.minecraft.server.level.ServerLevel) player.level());
		GuideData data = global.getGuide(player.getUUID());
		
		int before = data.speciesCount();
		for (CreatureEntity creature : nearby) {
			data.file(creature.getGenome());
		}
		global.putGuide(player.getUUID(), data);
		
		Discoveries.checkGuide(player, data);
		
		net.minecraft.nbt.CompoundTag payloadData = new net.minecraft.nbt.CompoundTag();
		data.writeInto(payloadData);
		net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(
				player, new dev.jsz.primordia.lab.GuideDataSyncPayload(payloadData));

		int filed = nearby.size();
		int newSpecies = data.speciesCount() - before;
		source.sendSuccess(() -> Component.literal("Filed " + filed + " specimen(s), "
				+ newSpecies + " new bloodline(s) · "
				+ data.speciesCount() + " on file").withStyle(ChatFormatting.GREEN), false);
		return filed;
	}

	/**
	 * Spawns an exact genome, as produced by the editor's <b>Genome code</b> box.
	 * <p>
	 * The creature is an ordinary member of the population once placed — it eats, breeds and passes
	 * its genes on like anything born in the world. That is the point of routing a designed animal
	 * through {@link Genome#decode} rather than through a bespoke spawn path: whatever comes out of
	 * the editor has to be the same kind of thing the ecology already knows how to simulate, or it
	 * could not interbreed with the wild population at all.
	 */
	private static int spawnCoded(CommandContext<CommandSourceStack> ctx, String code, int count) {
		CommandSourceStack source = ctx.getSource();
		ServerLevel world = source.getLevel();
		Vec3 pos = source.getPosition();

		Genome genome;
		try {
			genome = Genome.decode(code.trim());
		} catch (Exception e) {
			source.sendFailure(Component.literal("Could not read that genome code: " + e.getMessage()));
			return 0;
		}
		if (genome == null) {
			source.sendFailure(Component.literal("Could not read that genome code."));
			return 0;
		}

		Random random = new Random();
		int spawned = 0;
		for (int i = 0; i < count; i++) {
			CreatureEntity creature = PrimordiaEntities.CREATURE.create(world,
					net.minecraft.world.entity.EntitySpawnReason.COMMAND);
			if (creature == null) continue;
			double spread = count == 1 ? 0.0 : 1.5 + count * 0.15;
			creature.snapTo(
					pos.x + (random.nextDouble() - 0.5) * spread,
					pos.y,
					pos.z + (random.nextDouble() - 0.5) * spread,
					random.nextFloat() * 360f, 0f);
			creature.setGenome(genome);
			world.addFreshEntity(creature);
			spawned++;
		}

		int finalSpawned = spawned;
		Genome reported = genome;
		source.sendSuccess(() -> Component.literal("Spawned " + finalSpawned + " designed creature(s) — "
				+ describe(reported)).withStyle(ChatFormatting.GREEN), false);
		return spawned;
	}

	private static int spawn(CommandContext<CommandSourceStack> ctx, int count, String archetypeName, Long seed) {
		CommandSourceStack source = ctx.getSource();
		ServerLevel world = source.getLevel();
		Vec3 pos = source.getPosition();
		// A supplied seed makes the whole batch reproducible, which is what you want when
		// iterating on the generator and comparing before/after.
		Random random = seed == null ? new Random() : new Random(seed);

		// "mixed" (or nothing) gives one of each structured archetype rather than a uniform draw,
		// because a uniform draw is statistically always the same mid-sized quadruped.
		boolean mixed = archetypeName == null || archetypeName.equalsIgnoreCase("mixed");
		Archetype fixed = mixed ? null : Archetype.byName(archetypeName);
		if (!mixed && fixed == null) {
			source.sendFailure(Component.literal("Unknown archetype '" + archetypeName + "'. Options: mixed, "
					+ String.join(", ", java.util.Arrays.stream(Archetype.VALUES)
					.map(a -> a.name().toLowerCase()).toList())));
			return 0;
		}

		int spawned = 0;
		Genome last = null;
		for (int i = 0; i < count; i++) {
			CreatureEntity creature = PrimordiaEntities.CREATURE.create(world, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
			if (creature == null) continue;
			Archetype archetype = fixed != null ? fixed : Archetype.randomStructured(random);
			Genome genome = archetype.create(random);
			// Spread a batch out so they are not stacked inside one another.
			double spread = count == 1 ? 0.0 : 1.5 + count * 0.15;
			creature.snapTo(
					pos.x + (random.nextDouble() - 0.5) * spread,
					pos.y,
					pos.z + (random.nextDouble() - 0.5) * spread,
					random.nextFloat() * 360f, 0f);
			creature.setGenome(genome);
			world.addFreshEntity(creature);
			last = genome;
			spawned++;
		}

		int finalSpawned = spawned;
		Genome reported = last;
		source.sendSuccess(() -> Component.literal("Spawned " + finalSpawned + " creature(s)"
				+ (reported == null ? "" : " — last: " + describe(reported))).withStyle(ChatFormatting.GREEN), false);
		return spawned;
	}

	/**
	 * Winds every nearby body's clock forward, so the stages of decay can be watched in the order
	 * they happen rather than waited out.
	 * <p>
	 * With no argument each body is carried to the far side of its next milestone and no further,
	 * which is what makes it usable as a step button: run it once for the rotten flesh and bone, once
	 * more for the skeleton, once more to see the bones go. Time is added to the bodies rather than
	 * to the world, so nothing else in the level ages along with them.
	 *
	 * @param ticks how far to wind, or 0 for "to the next stage"
	 */
	private static int decay(CommandContext<CommandSourceStack> ctx, int ticks) {
		CommandSourceStack source = ctx.getSource();
		ServerLevel world = source.getLevel();
		Vec3 pos = source.getPosition();
		AABB box = new AABB(pos, pos).inflate(DECAY_RADIUS);

		List<CreatureEntity> bodies = world.getEntitiesOfClass(CreatureEntity.class, box,
				CreatureEntity::isCarcass);
		if (bodies.isEmpty()) {
			source.sendFailure(Component.literal(
					"No bodies within " + (int) DECAY_RADIUS + " blocks. Kill something with "
							+ "/kill — a creature you kill yourself drops loot and leaves none."));
			return 0;
		}

		for (CreatureEntity body : bodies) {
			// One past the boundary, so the milestone is actually crossed on the next tick rather
			// than landed on exactly and left for the tick after.
			body.ageCarcass(ticks > 0 ? ticks : body.ticksUntilNextStage() + 1);
		}

		int count = bodies.size();
		long skeletons = bodies.stream().filter(CreatureEntity::isSkeleton).count();
		source.sendSuccess(() -> Component.literal("Aged " + count + " bod"
				+ (count == 1 ? "y" : "ies") + (skeletons > 0 ? " (" + skeletons + " already bone)" : "")
				+ " — nothing drops on its own, punch one to see what it yields now")
				.withStyle(ChatFormatting.GREEN), false);
		return count;
	}

	/** How far {@code decay} reaches for bodies. */
	private static final double DECAY_RADIUS = 32.0;

	/**
	 * Spawns remains directly, skipping the two in-game days of rotting it normally takes to see
	 * any. What lands is exactly what a body decays into — the same entity in its last stage — so
	 * anything true of these is true of the ones the world produces on its own.
	 */
	private static int spawnSkeleton(CommandContext<CommandSourceStack> ctx, int count) {
		CommandSourceStack source = ctx.getSource();
		ServerLevel world = source.getLevel();
		Vec3 pos = source.getPosition();
		Random random = new Random();

		int spawned = 0;
		for (int i = 0; i < count; i++) {
			CreatureEntity creature = PrimordiaEntities.CREATURE.create(
					world, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
			if (creature == null) continue;
			double spread = count == 1 ? 0.0 : 1.5 + count * 0.15;
			creature.snapTo(
					pos.x + (random.nextDouble() - 0.5) * spread,
					pos.y,
					pos.z + (random.nextDouble() - 0.5) * spread,
					random.nextFloat() * 360f, 0f);
			creature.setGenome(Archetype.randomStructured(random).create(random));
			creature.skeletonise();
			world.addFreshEntity(creature);
			spawned++;
		}

		int finalSpawned = spawned;
		source.sendSuccess(() -> Component.literal("Spawned " + finalSpawned + " skeleton(s)")
				.withStyle(ChatFormatting.GREEN), false);
		return spawned;
	}

	/**
	 * Where the last test grid was laid out, so {@code reload} can re-roll it without moving it.
	 * Held in memory only: losing it across a restart just means the next grid is placed at the
	 * player again, which is the same thing a first {@code /primordia test} does anyway.
	 */
	private static Vec3 gridAnchor;
	private static float gridAnchorYaw;
	private static net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> gridAnchorWorld;

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
	private static int spawnTestGrid(CommandContext<CommandSourceStack> ctx, Long seed, boolean inPlace) {
		CommandSourceStack source = ctx.getSource();
		ServerLevel world = source.getLevel();

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
		boolean reuse = inPlace && gridAnchor != null && gridAnchorWorld == world.dimension();
		Vec3 origin = reuse ? gridAnchor : source.getPosition();
		float yaw = reuse ? gridAnchorYaw : source.getRotation().y;
		gridAnchor = origin;
		gridAnchorYaw = yaw;
		gridAnchorWorld = world.dimension();

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

				CreatureEntity creature = PrimordiaEntities.CREATURE.create(world, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
				if (creature == null) continue;

				double along = TEST_GRID_OFFSET + row * TEST_ROW_SPACING;
				double across = (col - (TEST_SIZES.length - 1) / 2.0) * TEST_COLUMN_SPACING;
				double x = origin.x + forwardX * along + rightX * across;
				double z = origin.z + forwardZ * along + rightZ * across;

				creature.snapTo(x, origin.y, z, facing, 0f);
				// Head and body have to be set to the same facing explicitly. refreshPositionAndAngles
				// only sets the body, and a specimen with AI disabled never runs the head easing that
				// would otherwise bring the two together.
				creature.setYHeadRot(facing);
				creature.setYBodyRot(facing);
				creature.yHeadRotO = facing;
				creature.yBodyRotO = facing;
				creature.setGenome(genome);
				creature.setCustomName(Component.literal(
						archetype.name().toLowerCase() + " · " + TEST_SIZE_NAMES[col]));
				creature.setCustomNameVisible(true);
				world.addFreshEntity(creature);
				// After spawning: setPosing stops the navigator, which has to exist first.
				creature.setPosing(true);
				spawned++;
			}
		}

		int finalSpawned = spawned;
		int finalRemoved = removed;
		source.sendSuccess(() -> Component.literal(String.format(
						"Test grid: %d specimens (%d archetypes × %d sizes), cleared %d — seed %d",
						finalSpawned, Archetype.VALUES.length, TEST_SIZES.length,
						finalRemoved, actualSeed))
				.withStyle(ChatFormatting.AQUA), false);
		source.sendSuccess(() -> Component.literal("  reload re-rolls in place · walk / stand · "
				+ "/primordia test " + actualSeed + " restores this set")
				.withStyle(ChatFormatting.DARK_GRAY), false);
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
	private static int setTestWalk(CommandContext<CommandSourceStack> ctx, boolean walking) {
		CommandSourceStack source = ctx.getSource();
		List<CreatureEntity> posed = nearby(source, 128).stream()
				.filter(CreatureEntity::isPosing)
				.toList();

		if (posed.isEmpty()) {
			source.sendFailure(Component.literal("No test grid nearby — run /primordia test first"));
			return 0;
		}

		for (CreatureEntity creature : posed) {
			creature.setPoseWalking(walking);
		}

		source.sendSuccess(() -> Component.literal(walking
						? "Test grid walking (" + posed.size() + " specimens)"
						: "Test grid standing (" + posed.size() + " specimens)")
				.withStyle(ChatFormatting.AQUA), false);
		return posed.size();
	}

	private static int clear(CommandContext<CommandSourceStack> ctx, int radius) {
		CommandSourceStack source = ctx.getSource();
		List<CreatureEntity> found = nearby(source, radius);
		for (CreatureEntity creature : found) {
			creature.discard();
		}
		source.sendSuccess(() -> Component.literal("Removed " + found.size() + " creature(s)")
				.withStyle(ChatFormatting.YELLOW), false);
		return found.size();
	}

	/** How far {@code debug lava} reaches for creatures, in blocks. */
	private static final int LAVA_MARCH_RADIUS = 200;
	/**
	 * How far above and below the creatures the lava sweep looks.
	 * <p>
	 * This is what keeps the sweep cheap as well as sensible. Unbounded, a search at any radius runs
	 * down into the lava sea under y=-54 and finds tens of thousands of blocks nothing on the surface
	 * could ever walk to; bounding it to the band the animals are actually standing in means the deep
	 * sections are never even opened.
	 */
	private static final int LAVA_SCAN_VERTICAL = 48;
	/** Ceiling on collected lava, so a command run beside a lava lake cannot build a huge list. */
	private static final int LAVA_POSITION_CAP = 20_000;

	/**
	 * Sends every creature in range walking into the nearest lava it can see.
	 * <p>
	 * A population-scale kill switch for testing what the ecology does with a sudden hole in it —
	 * carcass handling, the region ledger's response to a lineage crashing, scavengers arriving.
	 * Doing it by pathfinding rather than by {@code kill} is the entire point: what is being tested
	 * is the dying, not the deaths.
	 * <p>
	 * Only <i>exposed</i> lava counts — a block with air above it. Lava buried under stone is
	 * unreachable however near it is, and offering it as a destination sends an animal to stand on
	 * the rock above it forever.
	 */
	private static int lavaMarch(CommandContext<CommandSourceStack> ctx, int radius) {
		CommandSourceStack source = ctx.getSource();
		ServerLevel world = source.getLevel();
		Vec3 origin = source.getPosition();

		List<CreatureEntity> creatures = nearby(source, radius).stream()
				.filter(c -> !c.isCarcass())
				.filter(c -> c.distanceToSqr(origin) <= (double) radius * radius)
				.toList();
		if (creatures.isEmpty()) {
			source.sendFailure(Component.literal("No living creatures within " + radius + " blocks."));
			return 0;
		}

		double lowest = creatures.stream().mapToDouble(Entity::getY).min().orElse(origin.y);
		double highest = creatures.stream().mapToDouble(Entity::getY).max().orElse(origin.y);
		List<BlockPos> lava = findExposedLava(world, origin, radius,
				(int) lowest - LAVA_SCAN_VERTICAL, (int) highest + LAVA_SCAN_VERTICAL);

		if (lava.isEmpty()) {
			source.sendFailure(Component.literal(
					"Found " + creatures.size() + " creature(s) but no exposed lava within " + radius
							+ " blocks of them (loaded chunks only, and " + LAVA_SCAN_VERTICAL
							+ " blocks above and below)."));
			return 0;
		}

		int sent = 0;
		for (CreatureEntity creature : creatures) {
			BlockPos nearest = null;
			double best = Double.MAX_VALUE;
			for (BlockPos pos : lava) {
				double d = pos.distToCenterSqr(creature.position());
				if (d < best) {
					best = d;
					nearest = pos;
				}
			}
			if (nearest == null) continue;
			creature.marchToLava(nearest);
			sent++;
		}

		int count = sent;
		int pools = lava.size();
		source.sendSuccess(() -> Component.literal(
				count + " creature(s) marching into lava (" + pools + " exposed block(s) found)")
				.withStyle(ChatFormatting.RED), false);
		return count;
	}

	/**
	 * Collects lava with air above it, within {@code radius} of {@code centre} and between the given
	 * heights.
	 * <p>
	 * Walks chunk sections rather than blocks. {@link net.minecraft.world.level.chunk.LevelChunkSection#maybeHas}
	 * rejects a whole 16³ section that holds no lava in constant time against its palette, which is
	 * every section in an ordinary landscape — so the cost of a 200-block sweep is proportional to
	 * how much lava is nearby rather than to the volume searched.
	 * <p>
	 * Unloaded chunks are skipped rather than loaded. A debug command should not generate terrain as
	 * a side effect of looking at it.
	 */
	private static List<BlockPos> findExposedLava(ServerLevel world, Vec3 centre, int radius,
	                                              int minY, int maxY) {
		List<BlockPos> found = new java.util.ArrayList<>();
		minY = Math.max(minY, world.getMinY());
		maxY = Math.min(maxY, world.getMaxY());
		if (minY > maxY) return found;

		int chunkRadius = (radius >> 4) + 1;
		int originChunkX = net.minecraft.core.SectionPos.blockToSectionCoord((int) centre.x);
		int originChunkZ = net.minecraft.core.SectionPos.blockToSectionCoord((int) centre.z);
		double radiusSqr = (double) radius * radius;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

		for (int cx = originChunkX - chunkRadius; cx <= originChunkX + chunkRadius; cx++) {
			for (int cz = originChunkZ - chunkRadius; cz <= originChunkZ + chunkRadius; cz++) {
				net.minecraft.world.level.chunk.LevelChunk chunk =
						world.getChunkSource().getChunkNow(cx, cz);
				if (chunk == null) continue;

				for (int index = chunk.getSectionIndex(minY); index <= chunk.getSectionIndex(maxY); index++) {
					if (index < 0 || index >= chunk.getSections().length) continue;
					net.minecraft.world.level.chunk.LevelChunkSection section = chunk.getSection(index);
					if (section == null || section.hasOnlyAir() || !section.hasFluid()) continue;
					if (!section.maybeHas(state -> state.is(net.minecraft.world.level.block.Blocks.LAVA))) {
						continue;
					}

					int baseY = chunk.getSectionYFromSectionIndex(index) << 4;
					for (int y = 0; y < 16; y++) {
						int worldY = baseY + y;
						if (worldY < minY || worldY > maxY) continue;
						for (int x = 0; x < 16; x++) {
							for (int z = 0; z < 16; z++) {
								if (!section.getBlockState(x, y, z).is(net.minecraft.world.level.block.Blocks.LAVA)) {
									continue;
								}
								int worldX = (cx << 4) + x;
								int worldZ = (cz << 4) + z;
								if (centre.distanceToSqr(worldX + 0.5, centre.y, worldZ + 0.5) > radiusSqr) continue;
								cursor.set(worldX, worldY + 1, worldZ);
								if (!world.getBlockState(cursor).isAir()) continue;
								found.add(new BlockPos(worldX, worldY, worldZ));
								if (found.size() >= LAVA_POSITION_CAP) return found;
							}
						}
					}
				}
			}
		}
		return found;
	}

	private static int info(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		CreatureEntity creature = nearest(source);
		if (creature == null) {
			source.sendFailure(Component.literal("No creature within " + (int) SEARCH_RADIUS + " blocks"));
			return 0;
		}
		Genome genome = creature.getGenome();
		if (genome == null) {
			source.sendFailure(Component.literal("Creature has no genome"));
			return 0;
		}
		BodyPlan plan = BodyPlanCache.get(genome);

		source.sendSuccess(() -> Component.literal("── " + describe(genome) + " ──").withStyle(ChatFormatting.AQUA), false);
		source.sendSuccess(() -> Component.literal(String.format(
				"  bones %d · limbs %d legs / %d arms · %d bone segments per leg",
				plan.bones.length, plan.legs.length, plan.arms.length,
				plan.legs.length == 0 ? 0 : plan.legs[0].bones.length)), false);
		source.sendSuccess(() -> Component.literal(String.format(
				"  size %.2f×%.2f×%.2f m · hip %.2f m · mass %.3f",
				plan.width(), plan.height(), plan.bodyLength, plan.hipHeight, plan.mass)), false);
		source.sendSuccess(() -> Component.literal(String.format(
				"  diet %.2f · speed %.2f · aggression %.2f · social %.2f · mutability %.2f",
				genome.raw(Gene.DIET), genome.raw(Gene.SPEED), genome.raw(Gene.AGGRESSION),
				genome.raw(Gene.SOCIABILITY), genome.raw(Gene.MUTABILITY))), false);
		source.sendSuccess(() -> Component.literal("  " + describeAnatomy(genome, plan))
				.withStyle(ChatFormatting.GRAY), false);
		source.sendSuccess(() -> Component.literal("  " + describeEcology(creature, genome))
				.withStyle(ChatFormatting.GREEN), false);
		source.sendSuccess(() -> Component.literal("  " + genome.encode()).withStyle(ChatFormatting.DARK_GRAY), false);
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

	private static int breed(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		List<CreatureEntity> found = nearby(source, (int) SEARCH_RADIUS);
		found.sort(Comparator.comparingDouble(e -> e.distanceToSqr(source.getPosition())));
		if (found.size() < 2) {
			source.sendFailure(Component.literal("Need two creatures nearby to breed"));
			return 0;
		}
		Genome a = found.get(0).getGenome();
		Genome b = found.get(1).getGenome();
		if (a == null || b == null) {
			source.sendFailure(Component.literal("One of the parents has no genome"));
			return 0;
		}

		Random random = new Random();
		Genome child = Mutation.breed(a, b, random);
		spawnWithGenome(source, child);

		float divergence = Mutation.distance(child, a);
		source.sendSuccess(() -> Component.literal(String.format(
				"Bred %s — divergence from parent A: %.3f%s",
				describe(child), divergence,
				child.lineage() != a.lineage() ? " (NEW LINEAGE)" : "")).withStyle(ChatFormatting.LIGHT_PURPLE), false);
		return 1;
	}

	private static int mutate(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		CreatureEntity parent = nearest(source);
		if (parent == null || parent.getGenome() == null) {
			source.sendFailure(Component.literal("No creature within " + (int) SEARCH_RADIUS + " blocks"));
			return 0;
		}
		Genome child = Mutation.mutate(parent.getGenome(), new Random());
		spawnWithGenome(source, child);
		source.sendSuccess(() -> Component.literal("Spawned mutated offspring: " + describe(child))
				.withStyle(ChatFormatting.LIGHT_PURPLE), false);
		return 1;
	}

	private static int stats(CommandContext<CommandSourceStack> ctx) {
		ctx.getSource().sendSuccess(() -> Component.literal(String.format(
				"meshes ready %d · baking %d · body plans cached %d",
				GenomeMeshCache.readyCount(), GenomeMeshCache.pendingCount(), BodyPlanCache.size())), false);
		return 1;
	}

	// ------------------------------------------------------------------ helpers

	private static void spawnWithGenome(CommandSourceStack source, Genome genome) {
		ServerLevel world = source.getLevel();
		CreatureEntity creature = PrimordiaEntities.CREATURE.create(world, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
		if (creature == null) return;
		Vec3 pos = source.getPosition();
		creature.snapTo(pos.x, pos.y, pos.z, 0f, 0f);
		creature.setGenome(genome);
		world.addFreshEntity(creature);
	}

	private static List<CreatureEntity> nearby(CommandSourceStack source, double radius) {
		Vec3 pos = source.getPosition();
		AABB box = new AABB(pos.x - radius, pos.y - radius, pos.z - radius,
				pos.x + radius, pos.y + radius, pos.z + radius);
		return source.getLevel().getEntitiesOfClass(CreatureEntity.class, box, e -> true);
	}

	private static CreatureEntity nearest(CommandSourceStack source) {
		Vec3 pos = source.getPosition();
		return nearby(source, SEARCH_RADIUS).stream()
				.min(Comparator.comparingDouble(e -> e.distanceToSqr(pos)))
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
	private static int region(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		ServerLevel world = source.getLevel();
		BlockPos pos = BlockPos.containing(source.getPosition());
		RegionPos regionPos = RegionPos.of(pos);
		RegionRecord record = RegionLedger.get(world).existing(regionPos);

		if (record == null || !record.founded) {
			source.sendFailure(Component.literal("No ecology recorded for " + regionPos
					+ " yet — it is founded a few seconds after a player arrives."));
			return 0;
		}

		long day = world.getGameTime() / RegionSimulation.TICKS_PER_STEP;
		source.sendSuccess(() -> Component.literal("── " + regionPos + " ──").withStyle(ChatFormatting.AQUA), false);
		source.sendSuccess(() -> Component.literal(String.format(
				"  vegetation %.0f%% of %.0f%% capacity · temp %.2f · humidity %.2f",
				record.vegetation * 100f, record.productivity * 100f,
				record.temperature, record.humidity)), false);
		source.sendSuccess(() -> Component.literal(String.format(
				"  day %d · last simulated day %d · %d lineage(s)",
				day, record.lastStep, record.lineages.size())).withStyle(ChatFormatting.GRAY), false);

		if (record.lineages.isEmpty()) {
			source.sendSuccess(() -> Component.literal("  no fauna — this region is empty")
					.withStyle(ChatFormatting.DARK_GRAY), false);
			return 1;
		}

		for (LineageRecord lineage : record.lineages) {
			int live = 0;
			for (CreatureEntity creature : RegionMaterialiser.liveIn(world, regionPos)) {
				Genome g = creature.getGenome();
				if (g != null && g.lineage() == lineage.id) live++;
			}
			final int liveCount = live;
			source.sendSuccess(() -> Component.literal(String.format(
					"  %s %s  pop %.1f (%d live) · mass %.3f · %s · gen %d · var %.2f",
					trendArrow(lineage.trend()),
					shortId(lineage.id),
					lineage.total(), liveCount, lineage.meanMass(),
					dietLabel(lineage.meanOf(Gene.DIET)),
					lineage.generation, lineage.variance))
					.withStyle(trendColour(lineage.trend())), false);
		}
		return 1;
	}

	private static String trendArrow(float trend) {
		if (trend > 0.5f) return "▲";
		if (trend < -0.5f) return "▼";
		return "·";
	}

	private static ChatFormatting trendColour(float trend) {
		if (trend > 0.5f) return ChatFormatting.GREEN;
		if (trend < -0.5f) return ChatFormatting.RED;
		return ChatFormatting.WHITE;
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
	private static Entity resolveTarget(CommandContext<CommandSourceStack> ctx, String name)
			throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		return EntityArgument.getEntity(ctx, name);
	}
}
