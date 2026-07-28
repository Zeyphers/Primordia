package dev.jsz.primordia.entity;

import dev.jsz.primordia.anim.CreatureAnimator;
import dev.jsz.primordia.body.AttackStyle;
import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanCache;
import dev.jsz.primordia.body.DietGroup;
import dev.jsz.primordia.body.LimbChain;
import dev.jsz.primordia.ecology.EnergyBudget;
import dev.jsz.primordia.ecology.FoodSurvey;
import dev.jsz.primordia.ecology.SurvivalDrops;
import dev.jsz.primordia.ecology.WorldImpact;
import dev.jsz.primordia.ecology.region.RegionMaterialiser;
import dev.jsz.primordia.entity.goal.CreatureAttackGoal;
import dev.jsz.primordia.entity.goal.CreatureTemptGoal;
import dev.jsz.primordia.entity.goal.DefendOwnerGoal;
import dev.jsz.primordia.entity.goal.FeedOnCarcassGoal;
import dev.jsz.primordia.entity.goal.FleeLargerCreatureGoal;
import dev.jsz.primordia.entity.goal.FollowOwnerGoal;
import dev.jsz.primordia.entity.goal.GrazeGoal;
import dev.jsz.primordia.entity.goal.LeaveWaterGoal;
import dev.jsz.primordia.entity.goal.RestGoal;
import dev.jsz.primordia.entity.goal.StayGoal;
import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.genome.Mutation;
import dev.jsz.primordia.registry.PrimordiaEntities;
import net.minecraft.util.math.Box;
import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3f;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.ai.goal.EscapeDangerGoal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.RevengeGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.goal.TemptGoal;
import net.minecraft.entity.ai.goal.WanderAroundFarGoal;
import net.minecraft.recipe.Ingredient;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.Optional;
import java.util.UUID;

/**
 * A procedurally generated creature. The entity itself is deliberately thin — it owns a
 * {@link Genome} and standard mob plumbing, and everything distinctive (shape, size, colour,
 * movement) is derived from that genome rather than stored.
 * <p>
 * The genome is replicated to clients as a Base64 string in tracked data. That is the only thing
 * the client needs: from it, it independently builds the identical body plan and mesh, so no
 * geometry ever crosses the network.
 */
public class CreatureEntity extends PathAwareEntity {
	private static final TrackedData<String> GENOME_CODE =
			DataTracker.registerData(CreatureEntity.class, TrackedDataHandlerRegistry.STRING);
	private static final TrackedData<Byte> ACTIVITY =
			DataTracker.registerData(CreatureEntity.class, TrackedDataHandlerRegistry.BYTE);
	private static final TrackedData<Boolean> TAMED =
			DataTracker.registerData(CreatureEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	private static final TrackedData<Boolean> SADDLED =
			DataTracker.registerData(CreatureEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	private static final TrackedData<Optional<UUID>> OWNER =
			DataTracker.registerData(CreatureEntity.class, TrackedDataHandlerRegistry.OPTIONAL_UUID);
	private static final TrackedData<Boolean> CLIMBING =
			DataTracker.registerData(CreatureEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	private static final TrackedData<Boolean> DOMESTICATED =
			DataTracker.registerData(CreatureEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	private static final TrackedData<Boolean> SITTING =
			DataTracker.registerData(CreatureEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	/** Test-rig flag: hold position as a display specimen. See {@code /primordia test}. */
	private static final TrackedData<Boolean> POSING =
			DataTracker.registerData(CreatureEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	/** Whether a posed specimen plays its walk cycle or stands. Toggled by {@code /primordia test walk}. */
	private static final TrackedData<Boolean> POSE_WALKING =
			DataTracker.registerData(CreatureEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	/** Dead and lying where it fell, waiting to be eaten. Tracked because the renderer poses it. */
	private static final TrackedData<Boolean> CARCASS =
			DataTracker.registerData(CreatureEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	/** Resting through the inactive half of its cycle. Tracked for the same reason. */
	private static final TrackedData<Boolean> ASLEEP =
			DataTracker.registerData(CreatureEntity.class, TrackedDataHandlerRegistry.BOOLEAN);

	/** Walking speed reported to the animator by a posed creature, in blocks per second. */
	public static final float POSE_WALK_SPEED = 2.2f;

	/**
	 * Chance that taming a creature also domesticates it, and the smaller per-feed chance for one
	 * that is already tamed.
	 * <p>
	 * Deliberately low. A domesticated creature is a permanent combat companion, so if taming
	 * reliably produced one there would be no reason to ever tame a second creature — and the
	 * feeding path exists so the trait is still reachable for animals tamed before the roll, and
	 * for a player who wants a particular genome rather than whichever one happened to win.
	 */
	private static final float DOMESTICATION_ON_TAME_CHANCE = 0.12f;
	private static final float DOMESTICATION_ON_FEED_CHANCE = 0.08f;

	/** Speed below which the creature is considered standing rather than walking, in blocks/tick. */
	private static final double WALK_THRESHOLD_SQ = 0.0015 * 0.0015;

	/**
	 * Distance beyond which a wild creature dissolves back into its region record. Inside the
	 * radius at which chunks stop ticking, so the absorb always runs before the entity goes quiet.
	 */
	private static final double DESPAWN_RANGE = 112.0;
	/**
	 * Per-tick chance of wearing the ground, before mass and grazing impact scale it. Small enough
	 * that a trail is the record of many crossings rather than of one.
	 */
	private static final float TRAIL_CHANCE_PER_TICK = 0.0006f;
	/** Ticks a carcass lasts before it rots away, if nothing eats it first. Five minutes. */
	private static final int CARCASS_LIFETIME = 6000;
	/**
	 * Ticks a successful hunter is stood down for, so it eats its kill rather than starting another.
	 * Only needs to outlast the walk back to the body; feeding takes it from there.
	 */
	private static final int POST_KILL_COOLDOWN = 400;
	/** Ticks between starvation hits once a creature is empty. */
	private static final int STARVATION_INTERVAL = 60;
	/** Ticks between wild breeding checks. Not free — it surveys the neighbourhood. */
	private static final int BREEDING_CHECK_INTERVAL = 200;
	/**
	 * Creatures of the same lineage within {@link #BREEDING_RANGE} above which no more will breed.
	 * Scaled by local carrying capacity, so a rich valley carries a bigger herd than a scree slope.
	 */
	private static final int BASE_DENSITY_CAP = 6;
	private static final double BREEDING_RANGE = 16.0;

	/** Parsed form of {@link #GENOME_CODE}, invalidated whenever the tracked string changes. */
	private Genome genome;
	private String genomeCodeCache = "";

	/** Client-side only; the server never populates this. */
	private CreatureAnimator animator;

	/** Ticks remaining on a timed activity; while positive, ambient state does not override it. */
	private int activityCooldown;
	/** Client-side activity tracking, for timing animation progress locally. */
	private CreatureActivity clientActivity = CreatureActivity.IDLE;
	private int clientActivityStart;
	/** Cached derivations, invalidated with the genome. */
	private DietGroup dietGroup;
	private AttackStyle attackStyle;
	private Temperament temperament;
	private int loveTimer;
	private VocalProfile vocalProfile;

	/**
	 * How fed this creature is, in [0,1]. The gate on hunting, foraging and breeding, and the thing
	 * that makes a predator stop. Server-authoritative and never replicated — no client behaviour
	 * depends on it, and {@code /primordia info} reads it directly off the server entity.
	 */
	private float energy = 0.85f;
	/** Ticks before this creature will consider hunting again after a chase it lost. */
	private int huntCooldown;
	/** Ticks before it can breed again. Set from {@link Gene#FECUNDITY} after each brood. */
	private int breedCooldown;
	/** Ticks lived. Distinct from {@code age}, which vanilla resets; this one only ever climbs. */
	private int lifeTicks;
	/** Remaining food in this carcass, in the absolute units {@code EnergyBudget} works in. */
	private float carcassNutrition;
	/** Ticks this carcass has lain here. */
	private int carcassTicks;

	public VocalProfile getVocalProfile() {
		if (vocalProfile == null && getGenome() != null) {
			vocalProfile = VocalProfile.create(getGenome(), getBodyPlan());
		}
		return vocalProfile;
	}

	@Override
	protected SoundEvent getAmbientSound() {
		return dev.jsz.primordia.sound.CreatureSoundEngine.getAmbientSound(this);
	}

	@Override
	protected SoundEvent getHurtSound(DamageSource source) {
		return dev.jsz.primordia.sound.CreatureSoundEngine.getHurtSound(this, source);
	}

	@Override
	protected SoundEvent getDeathSound() {
		return dev.jsz.primordia.sound.CreatureSoundEngine.getDeathSound(this);
	}

	@Override
	public float getSoundPitch() {
		return dev.jsz.primordia.sound.CreatureSoundEngine.getPitch(this);
	}

	@Override
	public float getSoundVolume() {
		return dev.jsz.primordia.sound.CreatureSoundEngine.getVolume(this);
	}

	public void playAttackGrowl() {
		VocalProfile vp = getVocalProfile();
		if (vp != null && vp.attackGrowl() != null && !getWorld().isClient()) {
			playSound(vp.attackGrowl(), getSoundVolume() * 1.1f, getSoundPitch());
		}
	}

	public void playMatingCall() {
		VocalProfile vp = getVocalProfile();
		if (vp != null && vp.matingCall() != null && !getWorld().isClient()) {
			playSound(vp.matingCall(), getSoundVolume() * 0.9f, getSoundPitch() * 1.05f);
		}
	}

	@Override
	public boolean tryAttack(Entity target) {
		playAttackGrowl();
		CreatureActivity act = switch (getAttackStyle()) {
			case BITE -> CreatureActivity.BITE;
			case CLAW -> CreatureActivity.CLAW;
			case TAIL_SLAM -> CreatureActivity.TAIL_SLAM;
			case RAM, STOMP -> CreatureActivity.RAM;
		};
		triggerActivity(act);
		return super.tryAttack(target);
	}

	public CreatureEntity(EntityType<? extends CreatureEntity> type, World world) {
		super(type, world);
	}

	/**
	 * Gate on the world populating itself with creatures. Commands are unaffected — {@code
	 * /primordia spawn} and {@code /primordia test} call {@code world.spawnEntity} directly and
	 * never consult this — so a flat world is still perfectly usable as a test bed, it just stops
	 * generating its own population.
	 */
	public static boolean canSpawn(EntityType<CreatureEntity> type, net.minecraft.world.ServerWorldAccess world,
	                               net.minecraft.entity.SpawnReason spawnReason, net.minecraft.util.math.BlockPos pos,
	                               net.minecraft.util.math.random.Random random) {
		if (isWorldGenerated(spawnReason) && isFlatWorld(world)) return false;
		net.minecraft.block.BlockState state = world.getBlockState(pos.down());
		return state.isSolidBlock(world, pos.down()) && pos.getY() >= world.getBottomY() + 4;
	}

	/** True for the spawn reasons the world produces on its own, as opposed to a player asking. */
	private static boolean isWorldGenerated(net.minecraft.entity.SpawnReason reason) {
		return reason == net.minecraft.entity.SpawnReason.NATURAL
				|| reason == net.minecraft.entity.SpawnReason.CHUNK_GENERATION
				|| reason == net.minecraft.entity.SpawnReason.SPAWNER;
	}

	/**
	 * Superflat and debug worlds are build spaces, not ecosystems.
	 * <p>
	 * A superflat is the worst possible case for a ground-spawning mob: solid, flat, fully lit
	 * ground to the horizon, with no water, cliffs or cave mouths to break up the candidate
	 * positions. Essentially every block passes the spawn test, so the spawner saturates its cap
	 * the instant the world loads and the player arrives standing inside a herd. Suppressing it
	 * outright is the difference between a flat world you can test in and one you have to clear
	 * before you can see anything.
	 */
	private static boolean isFlatWorld(net.minecraft.world.ServerWorldAccess world) {
		net.minecraft.world.gen.chunk.ChunkGenerator generator =
				world.toServerWorld().getChunkManager().getChunkGenerator();
		return generator instanceof net.minecraft.world.gen.chunk.FlatChunkGenerator
				|| generator instanceof net.minecraft.world.gen.chunk.DebugChunkGenerator;
	}

	@Override
	public net.minecraft.entity.EntityData initialize(net.minecraft.world.ServerWorldAccess world,
	                                                   net.minecraft.world.LocalDifficulty difficulty,
	                                                   net.minecraft.entity.SpawnReason spawnReason,
	                                                   net.minecraft.entity.EntityData entityData) {
		net.minecraft.entity.EntityData data = super.initialize(world, difficulty, spawnReason, entityData);
		if (getGenome() == null) {
			long worldSeed = world.toServerWorld().getSeed();
			long chunkSeed = (worldSeed ^ ((long) getBlockPos().getX() * 341873128712L + (long) getBlockPos().getZ() * 132897987541L));
			net.minecraft.util.math.random.Random seedRandom = net.minecraft.util.math.random.Random.create(chunkSeed);
			String biomeName = world.getBiome(getBlockPos()).getKey()
					.map(key -> key.getValue().getPath()).orElse("");
			setGenome(Genome.createForBiome(seedRandom, biomeName));
		}
		return data;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * The other half of the ledger's contract: a creature that leaves the world without dying goes
	 * back into the region record it came from, rather than simply ceasing to exist. This is the
	 * "dissolve" step — animals becoming numbers again — and every path that removes a creature
	 * without killing it has to come through here or the population leaks.
	 * <p>
	 * Vanilla's random despawn between 32 and 128 blocks is deliberately not reproduced. For an
	 * ordinary mob that flicker is invisible; for an animal the player is watching a herd of, having
	 * one wink out at forty blocks is not. These despawn only once they are properly out of range,
	 * which is still well inside the distance at which their chunks stop ticking, so the absorb
	 * always gets its chance to run.
	 */
	@Override
	public void checkDespawn() {
		if (!(getWorld() instanceof ServerWorld world)) {
			super.checkDespawn();
			return;
		}
		if (isPersistent() || !RegionMaterialiser.isLedgerManaged(this)) {
			super.checkDespawn();
			return;
		}
		PlayerEntity nearest = world.getClosestPlayer(this, -1.0);
		if (nearest == null) return;
		if (nearest.squaredDistanceTo(this) < DESPAWN_RANGE * DESPAWN_RANGE) return;

		RegionMaterialiser.absorb(world, this);
		discard();
	}

	@Override
	public boolean isClimbing() {
		return dataTracker.get(CLIMBING) || super.isClimbing();
	}

	public static DefaultAttributeContainer.Builder createCreatureAttributes() {
		// ATTACK_DAMAGE is not part of createMobAttributes(). It must be declared here or
		// tryAttack throws "Can't find attribute" and takes the server tick down with it.
		return MobEntity.createMobAttributes()
				.add(EntityAttributes.GENERIC_MAX_HEALTH, 12.0)
				.add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.25)
				.add(EntityAttributes.GENERIC_FOLLOW_RANGE, 24.0)
				.add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 2.0)
				.add(EntityAttributes.GENERIC_ATTACK_KNOCKBACK, 0.0)
				.add(EntityAttributes.GENERIC_ARMOR, 0.0);
	}

	@Override
	protected void initDataTracker(DataTracker.Builder builder) {
		super.initDataTracker(builder);
		builder.add(GENOME_CODE, "");
		builder.add(ACTIVITY, (byte) CreatureActivity.IDLE.ordinal());
		builder.add(TAMED, false);
		builder.add(SADDLED, false);
		builder.add(OWNER, Optional.empty());
		builder.add(DOMESTICATED, false);
		builder.add(SITTING, false);
		builder.add(POSING, false);
		builder.add(POSE_WALKING, true);
		builder.add(CLIMBING, false);
		builder.add(CARCASS, false);
		builder.add(ASLEEP, false);
	}

	// ------------------------------------------------------------------ ecology

	/** How fed this creature is, in [0,1]. */
	public float getEnergy() {
		return energy;
	}

	public void setEnergy(float value) {
		this.energy = MathHelper.clamp(value, 0f, 1f);
	}

	public void addEnergy(float delta) {
		setEnergy(energy + delta);
	}

	/**
	 * Whether this creature will go looking for something to kill.
	 * <p>
	 * Everything that makes a predator stop lives in this one method, and every hunting goal is
	 * gated on it. A creature that has eaten, is asleep, is still recovering from a chase it lost,
	 * or is somebody's tamed companion, does not hunt.
	 */
	public boolean wantsToHunt() {
		if (isCarcass() || isAsleep() || isPosing()) return false;
		if (huntCooldown > 0) return false;
		if (isTamed()) return false;
		if (!getDietGroup().hunts()) return false;
		return energy < EnergyBudget.HUNT_THRESHOLD;
	}

	/** Whether this creature will go looking for plants or a carcass. */
	public boolean isHungry() {
		return !isCarcass() && !isAsleep() && energy < EnergyBudget.FORAGE_THRESHOLD;
	}

	/** Old enough to breed, from {@link Gene#MATURATION_RATE}. */
	public boolean isMature() {
		Genome g = getGenome();
		return g != null && lifeTicks >= EnergyBudget.maturityTicks(g);
	}

	public int getLifeTicks() {
		return lifeTicks;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Stands a successful hunter down long enough to eat what it just killed.
	 * <p>
	 * Without this the loop closes on paper and not in play: a kill leaves a carcass, but the
	 * predator is still below its hunger threshold at that instant, so the targeting goal acquires
	 * the next animal on the very next tick and {@link FeedOnCarcassGoal} — which refuses to run
	 * while a target is set — never gets a turn. The predator walks away from every body it makes
	 * and keeps killing, which is the original bug wearing the new system as a costume.
	 * <p>
	 * The cooldown only has to outlast the walk back to the carcass; feeding raises energy above
	 * the threshold on its own from there.
	 */
	@Override
	public boolean onKilledOther(ServerWorld world, LivingEntity other) {
		boolean result = super.onKilledOther(world, other);
		if (!isDomesticated()) {
			huntCooldown = Math.max(huntCooldown, POST_KILL_COOLDOWN);
			setTarget(null);
		}
		return result;
	}

	/**
	 * Charges a hunter for a pursuit that caught nothing and stands it down for a while.
	 * <p>
	 * The cooldown matters more than the energy cost. Without it the targeting goal simply
	 * re-acquires the animal that just outran it on the very next tick, and a bounded chase becomes
	 * an unbounded one made of short chases.
	 */
	public void onHuntFailed() {
		Genome g = getGenome();
		addEnergy(-EnergyBudget.FAILED_HUNT_COST);
		huntCooldown = g == null ? 200 : EnergyBudget.failedHuntCooldown(g);
		setTarget(null);
	}

	public boolean isAsleep() {
		return dataTracker.get(ASLEEP);
	}

	public void setAsleep(boolean asleep) {
		if (isAsleep() == asleep) return;
		dataTracker.set(ASLEEP, asleep);
		if (asleep) {
			getNavigation().stop();
			setTarget(null);
			dataTracker.set(ACTIVITY, (byte) CreatureActivity.SLEEP.ordinal());
		}
	}

	// ----------------------------------------------------------------- carcasses

	/** A body lying where it fell: not alive, not an item pile, and edible until it is not. */
	public boolean isCarcass() {
		return dataTracker.get(CARCASS);
	}

	public float getCarcassNutrition() {
		return carcassNutrition;
	}

	/**
	 * Turns this entity into the carcass of the creature that just died.
	 * <p>
	 * Spawned as a fresh entity rather than by keeping the dead one alive, because a
	 * {@code LivingEntity} at zero health is in the middle of vanilla's death sequence and fighting
	 * that is how you get an animal that is dead on the server and standing on the client. This is a
	 * new entity that was simply never alive.
	 */
	public static void spawnCarcassOf(CreatureEntity dead) {
		if (dead.isCarcass()) return;
		Genome g = dead.getGenome();
		BodyPlan plan = dead.getBodyPlan();
		if (g == null || plan == null) return;
		if (!(dead.getWorld() instanceof ServerWorld world)) return;

		CreatureEntity carcass = PrimordiaEntities.CREATURE.create(world);
		if (carcass == null) return;
		carcass.setGenome(g);
		carcass.refreshPositionAndAngles(dead.getX(), dead.getY(), dead.getZ(), dead.getYaw(), 0f);
		carcass.becomeCarcass(EnergyBudget.carcassNutrition(plan));
		world.spawnEntity(carcass);
	}

	private void becomeCarcass(float nutrition) {
		dataTracker.set(CARCASS, true);
		dataTracker.set(ACTIVITY, (byte) CreatureActivity.CARCASS.ordinal());
		carcassNutrition = nutrition;
		carcassTicks = 0;
		setAiDisabled(true);
		setSilent(true);
		setPersistent();
		getNavigation().stop();
		setTarget(null);
	}

	/**
	 * Draws food out of this carcass, returning how much was actually taken. Returns 0 once it is
	 * picked clean, which is what tells a feeding goal to stop.
	 */
	public float consumeCarcass(float requested) {
		if (!isCarcass() || carcassNutrition <= 0f) return 0f;
		float taken = Math.min(requested, carcassNutrition);
		carcassNutrition -= taken;
		return taken;
	}

	/**
	 * Ages a carcass out. A body eaten down to nothing simply disappears; one that rots untouched
	 * leaves bone, so a kill site nobody scavenged is still readable on the ground later.
	 */
	private void tickCarcass() {
		carcassTicks++;
		if (carcassNutrition <= 0.001f) {
			discard();
			return;
		}
		if (carcassTicks >= CARCASS_LIFETIME) {
			SurvivalDrops.dropSkeletalRemains(this);
			discard();
		}
	}

	// ------------------------------------------------------- taming and riding

	public boolean isTamed() {
		return dataTracker.get(TAMED);
	}

	public boolean isSaddled() {
		return dataTracker.get(SADDLED);
	}

	public UUID getOwnerUuid() {
		return dataTracker.get(OWNER).orElse(null);
	}

	public boolean isOwner(PlayerEntity player) {
		return player.getUuid().equals(getOwnerUuid());
	}

	/**
	 * A tamed creature that has also bonded: it follows its owner, fights alongside them, and can
	 * be told to stay. Strictly a superset of tamed — nothing is domesticated without being tamed.
	 */
	public boolean isDomesticated() {
		return dataTracker.get(DOMESTICATED);
	}

	public boolean isSitting() {
		return dataTracker.get(SITTING);
	}

	/**
	 * A posed creature is a specimen on a stand: it holds its position and plays the walk cycle
	 * without going anywhere, so a whole population can be inspected side by side. Nothing spawns
	 * this way naturally — only {@code /primordia test} sets it.
	 */
	public boolean isPosing() {
		return dataTracker.get(POSING);
	}

	/** Whether a posed specimen is playing its walk cycle rather than standing still. */
	public boolean isPoseWalking() {
		return dataTracker.get(POSE_WALKING);
	}

	public void setPoseWalking(boolean walking) {
		dataTracker.set(POSE_WALKING, walking);
	}

	/** Freezes the creature in place as an animated display specimen. */
	public void setPosing(boolean posing) {
		dataTracker.set(POSING, posing);
		setAiDisabled(posing);
		setInvulnerable(posing);
		setPersistent();
		if (posing) {
			setSilent(true);
			getNavigation().stop();
			setTarget(null);
		}
	}

	public void setSitting(boolean sitting) {
		dataTracker.set(SITTING, sitting);
	}

	/** The owning player if they are loaded in this world, otherwise null. */
	public LivingEntity getOwner() {
		UUID uuid = getOwnerUuid();
		return uuid == null ? null : getWorld().getPlayerByUuid(uuid);
	}

	/**
	 * Rolls for domestication and reports whether it took. Announces itself loudly when it does —
	 * this is a rare outcome the player would otherwise have no way of noticing.
	 */
	private boolean rollDomestication(PlayerEntity player, float chance) {
		if (isDomesticated() || getRandom().nextFloat() >= chance) return false;

		dataTracker.set(DOMESTICATED, true);
		((ServerWorld) getWorld()).spawnParticles(ParticleTypes.HAPPY_VILLAGER,
				getX(), getBodyY(1.0), getZ(), 18, 0.5, 0.5, 0.5, 0.15);
		playSound(SoundEvents.ENTITY_WOLF_HOWL, 0.7f, 1.0f);
		player.sendMessage(Text.literal("The creature bonds with you — it will fight at your side. "
				+ "Sneak and interact to make it stay.").formatted(Formatting.GOLD), false);
		return true;
	}

	/** The food this creature can be bribed with; stable across a lineage. */
	public Item getFavouriteFood() {
		Genome g = getGenome();
		return g == null ? Items.WHEAT : TamingPreference.favouriteFood(g);
	}

	@Override
	public ActionResult interactMob(PlayerEntity player, Hand hand) {
		ItemStack stack = player.getStackInHand(hand);

		if (!isTamed()) {
			if (!stack.isOf(getFavouriteFood())) {
				return super.interactMob(player, hand);
			}
			if (getWorld().isClient()) return ActionResult.SUCCESS;

			stack.decrementUnlessCreative(1, player);
			Genome g = getGenome();
			BodyPlan plan = getBodyPlan();
			float chance = g == null ? 0.3f
					: TamingPreference.tameChance(g, plan == null ? 0.2f : plan.mass);

			if (getRandom().nextFloat() < chance) {
				dataTracker.set(TAMED, true);
				dataTracker.set(OWNER, Optional.of(player.getUuid()));
				// Taming clears any grudge; otherwise a creature you fought stays hostile.
				setTarget(null);
				setAttacker(null);
				((ServerWorld) getWorld()).spawnParticles(ParticleTypes.HEART,
						getX(), getBodyY(0.9), getZ(), 7, 0.4, 0.4, 0.4, 0.1);
				player.sendMessage(Text.literal("The creature accepts you.")
						.formatted(Formatting.GREEN), true);
				rollDomestication(player, DOMESTICATION_ON_TAME_CHANCE);
			} else {
				((ServerWorld) getWorld()).spawnParticles(ParticleTypes.SMOKE,
						getX(), getBodyY(0.9), getZ(), 5, 0.3, 0.3, 0.3, 0.02);
			}
			return ActionResult.CONSUME;
		}

		// Tamed from here on.

		// Sneak-interact toggles staying. It is on the sneak variant because a plain interact is
		// already spoken for by feeding, saddling and mounting.
		if (isDomesticated() && isOwner(player) && player.shouldCancelInteraction()) {
			if (getWorld().isClient()) return ActionResult.SUCCESS;
			setSitting(!isSitting());
			getNavigation().stop();
			setTarget(null);
			player.sendMessage(Text.literal(isSitting()
					? "The creature settles down to wait."
					: "The creature falls in behind you.").formatted(Formatting.GREEN), true);
			return ActionResult.CONSUME;
		}

		if (stack.isOf(getFavouriteFood()) && loveTimer <= 0) {
			if (getWorld().isClient()) return ActionResult.SUCCESS;
			stack.decrementUnlessCreative(1, player);
			// A tamed creature can still bond later, so animals tamed before this trait existed
			// are not permanently shut out of it.
			if (isOwner(player)) rollDomestication(player, DOMESTICATION_ON_FEED_CHANCE);
			loveTimer = 600;
			playMatingCall();
			((ServerWorld) getWorld()).spawnParticles(ParticleTypes.HEART,
					getX(), getBodyY(0.9), getZ(), 8, 0.4, 0.4, 0.4, 0.1);
			player.sendMessage(Text.literal("The creature enters a breeding mood!")
					.formatted(Formatting.LIGHT_PURPLE), true);
			return ActionResult.CONSUME;
		}

		if (!isSaddled() && stack.isOf(Items.SADDLE)) {
			if (getWorld().isClient()) return ActionResult.SUCCESS;
			if (!canBeSaddled()) {
				player.sendMessage(Text.literal("This creature is too small to carry a rider.")
						.formatted(Formatting.YELLOW), true);
				return ActionResult.CONSUME;
			}
			stack.decrementUnlessCreative(1, player);
			dataTracker.set(SADDLED, true);
			playSound(SoundEvents.ENTITY_HORSE_SADDLE, 0.6f, 1.0f);
			return ActionResult.CONSUME;
		}

		if (isSaddled() && !player.shouldCancelInteraction()) {
			if (getWorld().isClient()) return ActionResult.SUCCESS;
			player.startRiding(this);
			return ActionResult.CONSUME;
		}

		return super.interactMob(player, hand);
	}

	/** A mount has to be big enough to sit on; tiny insectoids are companions, not transport. */
	public boolean canBeSaddled() {
		BodyPlan plan = getBodyPlan();
		return plan != null && plan.hipHeight >= 0.75f && plan.mass >= 0.08f;
	}

	@Override
	public LivingEntity getControllingPassenger() {
		// Only the owner drives, and only with a saddle on.
		if (!isSaddled()) return null;
		if (getFirstPassenger() instanceof PlayerEntity rider && isOwner(rider)) {
			return rider;
		}
		return null;
	}

	@Override
	protected Vec3d getPassengerAttachmentPos(Entity passenger, EntityDimensions dimensions, float scaleFactor) {
		BodyPlan plan = getBodyPlan();
		if (plan == null) {
			return super.getPassengerAttachmentPos(passenger, dimensions, scaleFactor);
		}
		// Seat rider over the back hips, positioned slightly forward on the spine.
		return new Vec3d(0.0, plan.hipHeight * 0.98, -plan.bodyLength * 0.05);
	}

	@Override
	public void travel(Vec3d movementInput) {
		if (!isAlive()) {
			super.travel(movementInput);
			return;
		}
		if (!(getControllingPassenger() instanceof PlayerEntity rider)) {
			super.travel(movementInput);
			return;
		}

		// Smooth turn easing toward rider look direction (eliminates rotation stuttering)
		float targetYaw = rider.getYaw();
		float currentYaw = getYaw();
		float newYaw = MathHelper.stepUnwrappedAngleTowards(currentYaw, targetYaw, 6.0f);

		prevYaw = currentYaw;
		setYaw(newYaw);
		setPitch(rider.getPitch() * 0.5f);
		setRotation(newYaw, getPitch());

		prevBodyYaw = bodyYaw;
		bodyYaw = newYaw;
		// Head tracks body smoothly — snapping headYaw = newYaw caused oscillation because
		// tick() also eases bodyYaw toward headYaw, creating a feedback loop.
		prevHeadYaw = headYaw;
		headYaw = MathHelper.stepUnwrappedAngleTowards(headYaw, newYaw, 8.0f);

		float sideways = rider.sidewaysSpeed * 0.3f;
		float forward = rider.forwardSpeed;
		// Backing up is deliberately slow; these are not reverse-gear animals.
		if (forward <= 0f) forward *= 0.28f;

		if (isLogicalSideForUpdatingMovement()) {
			// Use the creature's own movement speed, capped so large fast creatures
			// don't feel like rockets. A vanilla horse is 0.225.
			float baseSpeed = (float) getAttributeValue(EntityAttributes.GENERIC_MOVEMENT_SPEED);
			float rideSpeed = Math.min(baseSpeed, 0.32f);
			setMovementSpeed(rideSpeed);
			super.travel(new Vec3d(sideways, movementInput.y, forward));
		} else {
			// Remote side: let vanilla interpolation handle it rather than fighting the server.
			setVelocity(Vec3d.ZERO);
		}
		// Ridden creatures should not also be running their own gait decisions.
		setMovementSpeed(0f);
	}


	/**
	 * {@inheritDoc}
	 * <p>
	 * Every goal is registered unconditionally and gated at runtime on {@link Temperament},
	 * because this runs from the constructor — before the genome has been assigned or replicated,
	 * so there is nothing yet to branch on. The gates are cheap and re-evaluated each time a goal
	 * considers starting, which also means a creature whose disposition drifts across a threshold
	 * changes behaviour without needing its goals rebuilt.
	 */
	@Override
	protected void initGoals() {
		goalSelector.add(0, new SwimGoal(this));
		// Above everything: told to stay means stay.
		goalSelector.add(0, new StayGoal(this));

		// Prey bolt when hurt. Higher priority than fighting: a skittish animal should be running
		// before it considers anything else.
		goalSelector.add(1, new EscapeDangerGoal(this, 1.6) {
			@Override
			public boolean canStart() {
				return getTemperament().fleesWhenHurt() && super.canStart();
			}
		});

		// Above everything but fleeing: a resting animal is not available to the rest of the world,
		// and a predator asleep through half the day is the cheapest population brake there is.
		goalSelector.add(1, new RestGoal(this));

		goalSelector.add(2, new CreatureTemptGoal(this, 1.15));
		goalSelector.add(2, new FleeLargerCreatureGoal(this, 1.35));
		goalSelector.add(3, new CreatureAttackGoal(this, 1.15));
		// Above grazing and below fighting: a carcass is worth more than a mouthful of grass, but
		// not worth standing over while something is trying to eat you.
		goalSelector.add(4, new FeedOnCarcassGoal(this, 1.1));
		// Below fighting, above foraging: a companion should finish the fight before it wanders
		// back to heel, but should not stop to graze while its owner walks away.
		goalSelector.add(4, new FollowOwnerGoal(this, 1.25, 10f, 3f, 20f));
		// Above wandering: a creature in water should commit to getting out rather than keep
		// picking random destinations across the lake.
		goalSelector.add(3, new LeaveWaterGoal(this, 1.1));
		goalSelector.add(4, new GrazeGoal(this));
		goalSelector.add(5, new WanderAroundFarGoal(this, 1.0));
		goalSelector.add(6, new LookAtEntityGoal(this, PlayerEntity.class, 8.0f));
		goalSelector.add(7, new LookAroundGoal(this));

		// Fighting for the owner outranks the creature's own grudges.
		targetSelector.add(1, new DefendOwnerGoal(this));

		// Anything not skittish hits back at whatever hit it — including the player, but never
		// its own owner: a companion that mauls you for a misclick is not a companion.
		targetSelector.add(2, new RevengeGoal(this) {
			@Override
			public boolean canStart() {
				if (!getTemperament().retaliates()) return false;
				if (isDomesticated() && getAttacker() instanceof PlayerEntity player
						&& isOwner(player)) {
					return false;
				}
				return super.canStart();
			}
		});

		// Hunters go after creatures smaller than themselves — but only when hungry. This predicate
		// used to be the size check alone, which is why a carnivore killed every herbivore within
		// reach and then started on the next one: nothing in it ever became false. See
		// wantsToHunt(), which is where every reason to stop now lives.
		targetSelector.add(2, new ActiveTargetGoal<>(this, CreatureEntity.class, 10, true, false,
				other -> {
					if (!(other instanceof CreatureEntity prey) || prey == this) return false;
					if (!wantsToHunt()) return false;
					// A carcass is food, not prey. Feeding on one is FeedOnCarcassGoal's job, and
					// targeting it would have the predator try to kill something already dead.
					if (prey.isCarcass()) return false;
					// Never the owner's other animals — a pack that eats itself is not a pack.
					if (isDomesticated() && prey.isDomesticated()
							&& getOwnerUuid() != null && getOwnerUuid().equals(prey.getOwnerUuid())) {
						return false;
					}
					BodyPlan mine = getBodyPlan();
					BodyPlan theirs = prey.getBodyPlan();
					if (mine == null || theirs == null) return false;
					// Bounded at both ends. The upper bound is self-preservation; the lower one is
					// what stops a large predator working through a field of small animals it can
					// never actually get full on. See EnergyBudget#MIN_PREY_MASS_RATIO.
					return EnergyBudget.isWorthHunting(mine.mass, theirs.mass);
				}));

		// Hunters attack vanilla passive animals (Cows, Sheep, Pigs, Chickens, Rabbits, Horses,
		// etc.). Bonded creatures do not go looking: a companion that clears out the farm it is
		// walking past is a liability, so they fight what their owner fights and nothing else.
		targetSelector.add(2, new ActiveTargetGoal<>(this, net.minecraft.entity.passive.AnimalEntity.class, 10, true, false,
				animal -> !isDomesticated() && wantsToHunt()
						&& (getTemperament().huntsUnprovoked()
						|| (getGenome() != null && getGenome().raw(Gene.DIET) > 0.45f))));

		// Committed predators treat the player as prey without being provoked first. A bonded
		// creature never does, whatever its disposition says. Gated on hunger like everything else,
		// so a fed predator is something you can walk past.
		targetSelector.add(4, new ActiveTargetGoal<>(this, PlayerEntity.class, 10, true, false,
				target -> getTemperament().huntsUnprovoked() && !isDomesticated() && wantsToHunt()));
	}

	@Override
	public void onTrackedDataSet(TrackedData<?> data) {
		super.onTrackedDataSet(data);
		if (GENOME_CODE.equals(data)) {
			// The genome arrives on the client a tick or two after the spawn packet. Everything
			// derived from it is stale until this fires — including the collision box, which is
			// why hitboxes did not match the creature's actual size.
			genome = null;
			genomeCodeCache = "";
			dietGroup = null;
			attackStyle = null;
			temperament = null;
			animator = null;
			calculateDimensions();
		}
	}

	public Temperament getTemperament() {
		Genome g = getGenome();
		if (g == null) return Temperament.DEFENSIVE;
		if (temperament == null) temperament = Temperament.of(g);
		return temperament;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * A player's kill drops items; anything else leaves a body. This is the fork that stops a
	 * predator working through a herd from carpeting the ground in beef, leather and bone that
	 * nothing in the world is able to eat — the meat is still there, it is just still attached to
	 * the animal, and something has to come and eat it.
	 */
	@Override
	public void onDeath(DamageSource damageSource) {
		super.onDeath(damageSource);
		if (getWorld().isClient() || isCarcass()) return;

		if (SurvivalDrops.killedByPlayer(damageSource)) {
			SurvivalDrops.dropLoot(this, 1f);
		} else {
			spawnCarcassOf(this);
		}
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * A carcass is not alive and cannot be killed again, but a player is allowed to butcher one —
	 * driving a predator off a fresh kill and taking it is a reasonable thing to want to do, and
	 * the yield scales with how much of the body is left.
	 */
	@Override
	public boolean damage(DamageSource source, float amount) {
		if (!isCarcass()) return super.damage(source, amount);
		if (getWorld().isClient()) return false;
		if (!(source.getAttacker() instanceof PlayerEntity)) return false;

		BodyPlan plan = getBodyPlan();
		float remaining = plan == null ? 0f
				: carcassNutrition / Math.max(0.001f, EnergyBudget.carcassNutrition(plan));
		SurvivalDrops.dropLoot(this, remaining);
		discard();
		return true;
	}



	@Override
	public void tick() {
		super.tick();

		// Smooth body rotation easing — but skip when ridden, since travel() drives yaw directly
		if (getControllingPassenger() == null) {
			this.bodyYaw = MathHelper.stepUnwrappedAngleTowards(this.bodyYaw, this.getHeadYaw(), 7.5f);
		}

		boolean canClimb = getBodyPlan() != null && getBodyPlan().legs.length >= 4 && getBodyPlan().mass <= 0.38f;
		if (canClimb) {
			dataTracker.set(CLIMBING, horizontalCollision);
		}

		if (getWorld().isClient()) return;

		if (isCarcass()) {
			tickCarcass();
			return;
		}

		lifeTicks++;
		if (huntCooldown > 0) huntCooldown--;
		if (breedCooldown > 0) breedCooldown--;

		tickEnergy();
		tickWildBreeding();
		tickBreeding();
		tickTrail();

		// Timed activities expire on their own, so no goal has to remember to clear one.
		if (activityCooldown > 0) {
			activityCooldown--;
			return;
		}
		// SLEEP is ambient but not derived from motion, so the velocity check below would clear it
		// every tick. A sleeping creature is not idle, it is asleep, and only RestGoal ends that.
		if (isAsleep()) {
			if (getActivity() != CreatureActivity.SLEEP) {
				dataTracker.set(ACTIVITY, (byte) CreatureActivity.SLEEP.ordinal());
			}
			return;
		}
		CreatureActivity ambient = getVelocity().horizontalLengthSquared() > WALK_THRESHOLD_SQ
				? CreatureActivity.WALK
				: CreatureActivity.IDLE;
		if (getActivity() != ambient) {
			dataTracker.set(ACTIVITY, (byte) ambient.ordinal());
		}
	}

	private void tickBreeding() {
		if (loveTimer <= 0) return;
		loveTimer--;

		if (loveTimer % 20 == 0) {
			((ServerWorld) getWorld()).spawnParticles(ParticleTypes.HEART,
					getX(), getBodyY(0.9), getZ(), 1, 0.3, 0.3, 0.3, 0.05);
		}

		if (age % 30 != 0 || getGenome() == null) return;

		Box searchBox = getBoundingBox().expand(8.0, 4.0, 8.0);
		List<CreatureEntity> partners = getWorld().getEntitiesByClass(CreatureEntity.class, searchBox,
				other -> other != this && other.isAlive() && !other.isCarcass()
						&& other.loveTimer > 0 && other.getGenome() != null);

		for (CreatureEntity partner : partners) {
			float dist = Mutation.distance(this.getGenome(), partner.getGenome());
			if (dist < 0.45f) {
				this.loveTimer = 0;
				partner.loveTimer = 0;

				ServerWorld world = (ServerWorld) getWorld();
				world.spawnParticles(ParticleTypes.HEART, getX(), getBodyY(0.9), getZ(), 14, 0.5, 0.5, 0.5, 0.1);
				world.spawnParticles(ParticleTypes.HEART, partner.getX(), partner.getBodyY(0.9), partner.getZ(), 14, 0.5, 0.5, 0.5, 0.1);

				// The world random rather than ThreadLocalRandom: the ecology has to be
				// reproducible from a seed, and a thread-local source is by definition not.
				Genome childGenome = Mutation.breed(this.getGenome(), partner.getGenome(),
						new java.util.Random(getRandom().nextLong()));
				CreatureEntity child = PrimordiaEntities.CREATURE.create(world);
				if (child != null) {
					child.dataTracker.set(GENOME_CODE, childGenome.encode());
					child.refreshPositionAndAngles(
							(getX() + partner.getX()) * 0.5,
							(getY() + partner.getY()) * 0.5,
							(getZ() + partner.getZ()) * 0.5,
							getYaw(), getPitch());
					// Inherited, not assumed. This used to set TAMED unconditionally, which was
					// harmless while only a player could trigger breeding and is wrong the moment
					// wild animals can: every birth in the world would have come out tame, and
					// tamed creatures are exempt from starving and from being hunted.
					boolean bornTame = this.isTamed() && partner.isTamed();
					child.dataTracker.set(TAMED, bornTame);
					child.dataTracker.set(OWNER, bornTame
							? this.dataTracker.get(OWNER)
							: Optional.empty());
					// Offspring start hungry, and both parents pay for them.
					child.setEnergy(0.55f);
					this.addEnergy(-EnergyBudget.BREED_COST);
					partner.addEnergy(-EnergyBudget.BREED_COST);
					this.breedCooldown = EnergyBudget.breedingInterval(this.getGenome());
					partner.breedCooldown = EnergyBudget.breedingInterval(partner.getGenome());
					world.spawnEntity(child);
				}
				break;
			}
		}
	}


	/**
	 * Burns energy, and starves anything that runs out.
	 * <p>
	 * This replaces the old carrying-capacity check, which dealt starvation damage to any creature
	 * whose mass exceeded what the surrounding land could support. That rule had the right intent —
	 * bulk should have to be paid for — but it enforced the outcome directly instead of letting it
	 * happen: an animal was punished for being big in a poor place whether or not it had actually
	 * failed to find food. Now the cost of being large is a faster drain and a bigger appetite
	 * ({@link EnergyBudget#drainPerTick}, {@link EnergyBudget#mouthfulValue}), and a giant on a
	 * scree slope starves because it genuinely cannot eat enough, which is the same pressure
	 * arrived at honestly.
	 * <p>
	 * Tamed creatures are exempt from starving — their owner is presumed to be feeding them — but
	 * they still burn energy, so a companion that has not been fed will go and forage.
	 */
	private void tickEnergy() {
		Genome g = getGenome();
		BodyPlan plan = getBodyPlan();
		if (g == null || plan == null) return;

		EnergyBudget.Activity activity;
		if (isAsleep()) {
			activity = EnergyBudget.Activity.RESTING;
		} else if (getTarget() != null || isSprinting()) {
			activity = EnergyBudget.Activity.SPRINTING;
		} else if (getVelocity().horizontalLengthSquared() > WALK_THRESHOLD_SQ) {
			activity = EnergyBudget.Activity.MOVING;
		} else {
			activity = EnergyBudget.Activity.IDLE;
		}
		addEnergy(-EnergyBudget.drainPerTick(g, plan, activity));

		if (energy > EnergyBudget.STARVING || isTamed()) return;
		if (age % STARVATION_INTERVAL != 0) return;
		damage(getWorld().getDamageSources().starve(), EnergyBudget.STARVATION_DAMAGE);
	}

	/**
	 * Wears a trail into the ground under a heavy animal that keeps using the same route.
	 * <p>
	 * The chance is deliberately tiny and scaled by mass, so a single small creature crossing a
	 * meadow leaves nothing and a herd of large ones that walks the same line between water and
	 * grazing eventually wears a visible path. That is the whole appeal — the route is not drawn by
	 * anything, it is where they actually went, and it tells the player something true about a herd
	 * they may never have seen.
	 * <p>
	 * Every change goes through {@link WorldImpact}, which holds the allow-list and the per-chunk
	 * budget. Nothing here decides on its own that a block may be modified.
	 */
	private void tickTrail() {
		if (isTamed() || !isOnGround()) return;
		if (getVelocity().horizontalLengthSquared() <= WALK_THRESHOLD_SQ) return;
		if (!(getWorld() instanceof ServerWorld world)) return;

		BodyPlan plan = getBodyPlan();
		Genome g = getGenome();
		if (plan == null || g == null) return;

		float pressure = plan.mass * (0.4f + g.raw(Gene.GRAZING_IMPACT));
		if (getRandom().nextFloat() >= pressure * TRAIL_CHANCE_PER_TICK) return;

		WorldImpact.trample(world, getBlockPos().down());
	}

	/**
	 * Puts a well-fed adult into breeding condition on its own.
	 * <p>
	 * Before this, {@code loveTimer} was set in exactly one place — a player feeding a tamed
	 * creature — so the wild birth rate was zero. Spawning was the only source of animals and
	 * predation was a pure sink, which meant every population was monotonically decreasing by
	 * construction. No amount of restraint on the part of the predators would have fixed that; a
	 * herd that cannot replace its losses is stripped by anything at all.
	 * <p>
	 * Gated on local density rather than a global cap, and the density allowance comes from
	 * {@link FoodSurvey#carryingCapacity}, so a productive valley carries a larger herd than a
	 * barren ridge without either being written down anywhere.
	 */
	private void tickWildBreeding() {
		if (isTamed() || isPosing() || isAsleep()) return;
		if (loveTimer > 0 || breedCooldown > 0) return;
		if (age % BREEDING_CHECK_INTERVAL != 0) return;
		if (!isMature() || energy < EnergyBudget.BREED_THRESHOLD) return;

		Genome g = getGenome();
		BodyPlan plan = getBodyPlan();
		if (g == null || plan == null) return;

		DietGroup diet = getDietGroup();
		float prey = diet.hunts() ? FoodSurvey.preyDensity(getWorld(), this) : 0f;
		float capacity = FoodSurvey.carryingCapacity(getWorld(), getBlockPos(), diet, prey);
		// Capacity is a supportable mass; how many animals that is depends on how big they are.
		int allowance = Math.max(1, Math.min(BASE_DENSITY_CAP,
				Math.round(capacity / Math.max(0.03f, plan.mass))));

		Box range = getBoundingBox().expand(BREEDING_RANGE, 8.0, BREEDING_RANGE);
		int neighbours = getWorld().getEntitiesByClass(CreatureEntity.class, range,
				other -> other != this && other.isAlive() && !other.isCarcass()
						&& other.getGenome() != null
						&& other.getGenome().lineage() == g.lineage()).size();
		if (neighbours >= allowance) return;

		loveTimer = 600;
		playMatingCall();
	}

	// ----------------------------------------------------------------- activity

	public CreatureActivity getActivity() {
		return CreatureActivity.byId(dataTracker.get(ACTIVITY));
	}

	/** Starts a timed activity, replacing any already running. Server side only. */
	public void triggerActivity(CreatureActivity activity) {
		if (getWorld().isClient()) return;
		dataTracker.set(ACTIVITY, (byte) activity.ordinal());
		activityCooldown = activity.durationTicks;
	}

	public boolean isBusy() {
		return activityCooldown > 0;
	}

	public DietGroup getDietGroup() {
		Genome g = getGenome();
		if (g == null) return DietGroup.OMNIVORE;
		if (dietGroup == null) dietGroup = DietGroup.of(g);
		return dietGroup;
	}

	public AttackStyle getAttackStyle() {
		BodyPlan plan = getBodyPlan();
		if (plan == null) return AttackStyle.BITE;
		if (attackStyle == null) attackStyle = AttackStyle.forPlan(plan);
		return attackStyle;
	}

	// ------------------------------------------------------------------- genome

	public Genome getGenome() {
		String code = dataTracker.get(GENOME_CODE);
		if (genome == null || !genomeCodeCache.equals(code)) {
			genomeCodeCache = code;
			genome = Genome.decode(code);
		}
		return genome;
	}

	public void setGenome(Genome genome) {
		this.genome = genome;
		this.genomeCodeCache = genome.encode();
		this.dietGroup = null;
		this.attackStyle = null;
		this.temperament = null;
		dataTracker.set(GENOME_CODE, genomeCodeCache);
		applyGenomeAttributes(genome);
		// The collision box depends on the body plan, so it has to be recomputed now.
		calculateDimensions();
	}

	/** Null until the genome has replicated; callers must handle that. */
	public BodyPlan getBodyPlan() {
		Genome g = getGenome();
		return g == null ? null : BodyPlanCache.get(g);
	}

	private void applyGenomeAttributes(Genome g) {
		BodyPlan plan = BodyPlanCache.get(g);

		// Bigger animals are tougher; the mass proxy already folds in girth and limb bulk.
		EntityAttributeInstance health = getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
		if (health != null) {
			health.setBaseValue(4.0 + Math.min(60.0, plan.mass * 90.0));
			setHealth(getMaxHealth());
		}

		// Long-legged, low-mass creatures move fast. Speed is capped so even the largest
		// creatures stay in a reasonable range (vanilla horse = 0.225).
		EntityAttributeInstance speed = getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
		if (speed != null) {
			double legFactor = 0.16 + 0.18 * Math.min(plan.hipHeight, 2.5);
			double massPenalty = 1.0 / (1.0 + plan.mass * 0.4);
			double geneFactor = 0.6 + 0.8 * g.raw(Gene.SPEED);
			speed.setBaseValue(Math.max(0.12, Math.min(0.35, legFactor * massPenalty * geneFactor)));
		}

		// Damage comes from the weapon the creature actually grew: a big jaw on a heavy body hits
		// hard, a herbivore's flat-toothed nibble barely registers.
		EntityAttributeInstance damage = getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE);
		if (damage != null) {
			double bulk = 1.0 + plan.mass * 12.0;
			double weapon = 0.5 + 1.2 * g.raw(Gene.JAW_SIZE);
			double intent = 0.4 + 1.1 * g.raw(Gene.DIET) * (0.5 + g.raw(Gene.AGGRESSION));
			damage.setBaseValue(Math.max(0.5, Math.min(14.0, bulk * weapon * intent * 0.5)));
		}

		// Armour plating, from the gene and from actually having dorsal spines.
		EntityAttributeInstance armor = getAttributeInstance(EntityAttributes.GENERIC_ARMOR);
		if (armor != null) {
			double plated = g.expresses(Gene.DORSAL_SPINES, 0.62f) ? 2.0 : 0.0;
			armor.setBaseValue(Math.min(14.0, g.raw(Gene.ARMOR) * 8.0 + plated));
		}

		// Heavy animals shrug off hits and shove hard; small ones get thrown around.
		EntityAttributeInstance knockback = getAttributeInstance(EntityAttributes.GENERIC_ATTACK_KNOCKBACK);
		if (knockback != null) {
			knockback.setBaseValue(Math.min(2.0, plan.mass * 4.0));
		}
	}

	// --------------------------------------------------------------- dimensions

	public List<Box> getLegSubHitboxes() {
		BodyPlan plan = getBodyPlan();
		if (plan == null) return List.of();
		List<Box> legBoxes = new ArrayList<>();
		Vec3d pos = getPos();
		float yawRad = (float) Math.toRadians(-getYaw());
		float cos = (float) Math.cos(yawRad);
		float sin = (float) Math.sin(yawRad);

		float radius = Math.max(0.25f, plan.minLimbRadius * 1.4f);
		float legHeight = Math.max(0.6f, plan.hipHeight);

		for (LimbChain leg : plan.legs) {
			Vector3f rest = leg.restEffector;
			double lx = rest.x * cos - rest.z * sin;
			double lz = rest.x * sin + rest.z * cos;
			double wx = pos.x + lx;
			double wz = pos.z + lz;
			Box legBox = new Box(wx - radius, pos.y, wz - radius, wx + radius, pos.y + legHeight, wz + radius);
			legBoxes.add(legBox);
		}
		return legBoxes;
	}

	@Override
	public EntityDimensions getBaseDimensions(EntityPose pose) {
		Genome g = getGenome();
		if (g == null) {
			return super.getBaseDimensions(pose);
		}
		BodyPlan plan = BodyPlanCache.get(g);
		// Lateral width comes from how far the legs splay, and torso girth.
		float legSpanX = 0f;
		for (LimbChain leg : plan.legs) {
			legSpanX = Math.max(legSpanX, Math.abs(leg.restEffector.x));
		}
		// The box used to cover legs and torso only, on the reasoning that a tail and a neck are
		// thin and should not stop an animal fitting through a gap. In practice it meant a
		// long-necked or long-tailed creature ran its head and tail straight through walls — most
		// obviously on fast ones, where the client's interpolation carries the mesh further past
		// the hitbox before the next position update lands.
		//
		// A Minecraft hitbox is axis-aligned and square in plan, so it cannot follow a body that
		// turns; using the full body length as the width would make a long creature a moving 3×3
		// block that could not path anywhere. Just under half of it covers the head and most of the
		// neck at any facing while still leaving the animal able to walk between trees.
		float width = Math.max(0.50f, Math.max(
				Math.max(legSpanX * 2.0f * 1.05f, plan.width() * 0.9f),
				Math.min(plan.bodyLength * 0.40f, 1.8f)));
		// Tall enough to cover the head rather than stopping at the shoulder, but capped against hip
		// height. Minecraft puts the eye at 85% of the box and suffocates anything whose eye is
		// inside a block, so a creature holding a long neck straight up would otherwise take
		// suffocation damage every time it walked under a tree.
		float height = Math.max(0.50f, Math.max(
				plan.hipHeight * 1.25f,
				Math.min(plan.height() * 0.92f, plan.hipHeight * 2.0f)));

		if (getAttributeInstance(EntityAttributes.GENERIC_STEP_HEIGHT) != null) {
			getAttributeInstance(EntityAttributes.GENERIC_STEP_HEIGHT)
					.setBaseValue(Math.max(1.0, Math.min(2.5, plan.hipHeight * 1.15)));
		}
		return EntityDimensions.changing(width, height);
	}

	// ------------------------------------------------------------------- client

	/**
	 * Progress through the current timed activity, 0 to 1, measured from when this client first
	 * observed the activity change. Ambient states always report 0.
	 * <p>
	 * Timing locally rather than syncing a start tick keeps a field off the wire; attacks run for
	 * under a second, so a tick of skew between clients is not observable.
	 */
	public float clientActivityProgress(CreatureActivity activity, float tickDelta) {
		if (activity.isAmbient()) {
			clientActivity = activity;
			return 0f;
		}
		if (activity != clientActivity) {
			clientActivity = activity;
			clientActivityStart = age;
		}
		float elapsed = (age - clientActivityStart) + tickDelta;
		return MathHelper.clamp(elapsed / activity.durationTicks, 0f, 1f);
	}

	/** Client-side render state, lazily created and rebuilt when the genome changes. */
	public CreatureAnimator getOrCreateAnimator() {
		BodyPlan plan = getBodyPlan();
		if (plan == null) return null;
		if (animator == null || animator.skeleton().plan != plan) {
			animator = new CreatureAnimator(plan);
		}
		return animator;
	}

	// ---------------------------------------------------------------------- nbt

	@Override
	public void writeCustomDataToNbt(NbtCompound nbt) {
		super.writeCustomDataToNbt(nbt);
		nbt.putString("Genome", dataTracker.get(GENOME_CODE));
		nbt.putBoolean("Tamed", isTamed());
		nbt.putBoolean("Domesticated", isDomesticated());
		nbt.putBoolean("Sitting", isSitting());
		nbt.putBoolean("Posing", isPosing());
		nbt.putBoolean("PoseWalking", isPoseWalking());
		nbt.putBoolean("Saddled", isSaddled());
		nbt.putFloat("Energy", energy);
		nbt.putInt("LifeTicks", lifeTicks);
		nbt.putInt("HuntCooldown", huntCooldown);
		nbt.putInt("BreedCooldown", breedCooldown);
		nbt.putBoolean("Asleep", isAsleep());
		if (isCarcass()) {
			nbt.putBoolean("Carcass", true);
			nbt.putFloat("CarcassNutrition", carcassNutrition);
			nbt.putInt("CarcassTicks", carcassTicks);
		}
		UUID owner = getOwnerUuid();
		if (owner != null) {
			nbt.putUuid("Owner", owner);
		}
	}

	@Override
	public void readCustomDataFromNbt(NbtCompound nbt) {
		super.readCustomDataFromNbt(nbt);
		if (nbt.contains("Genome")) {
			Genome decoded = Genome.decode(nbt.getString("Genome"));
			if (decoded != null) {
				setGenome(decoded);
			}
		}
		dataTracker.set(TAMED, nbt.getBoolean("Tamed"));
		// Domestication is a strict superset of taming; an untamed creature cannot be bonded.
		dataTracker.set(DOMESTICATED, nbt.getBoolean("Domesticated") && nbt.getBoolean("Tamed"));
		dataTracker.set(SITTING, nbt.getBoolean("Sitting"));
		if (nbt.getBoolean("Posing")) setPosing(true);
		// Absent on grids saved before the walk toggle existed, and false is the wrong default
		// there — those were spawned walking.
		dataTracker.set(POSE_WALKING, !nbt.contains("PoseWalking") || nbt.getBoolean("PoseWalking"));
		dataTracker.set(SADDLED, nbt.getBoolean("Saddled"));
		// Absent on creatures saved before the energy economy existed. Defaulting to zero would
		// starve every animal in an existing world on first load, so they wake up well fed.
		energy = nbt.contains("Energy") ? nbt.getFloat("Energy") : 0.85f;
		lifeTicks = nbt.getInt("LifeTicks");
		huntCooldown = nbt.getInt("HuntCooldown");
		breedCooldown = nbt.getInt("BreedCooldown");
		dataTracker.set(ASLEEP, nbt.getBoolean("Asleep"));
		if (nbt.getBoolean("Carcass")) {
			becomeCarcass(nbt.getFloat("CarcassNutrition"));
			carcassTicks = nbt.getInt("CarcassTicks");
		}
		dataTracker.set(OWNER, nbt.containsUuid("Owner")
				? Optional.of(nbt.getUuid("Owner"))
				: Optional.empty());
	}

	/** A tamed creature drops its saddle when it dies, so the gear is not simply lost. */
	@Override
	protected void dropInventory() {
		super.dropInventory();
		if (isSaddled()) {
			dropItem(Items.SADDLE);
		}
	}

	@Override
	public boolean canBeLeashed() {
		return true;
	}
}
