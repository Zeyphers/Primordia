package dev.jsz.primordia.entity;

import dev.jsz.primordia.anim.CreatureAnimator;
import dev.jsz.primordia.body.AttackStyle;
import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BoneDef;
import dev.jsz.primordia.skeleton.Skeleton;
import dev.jsz.primordia.body.BodyPlanCache;
import dev.jsz.primordia.body.DietGroup;
import dev.jsz.primordia.body.LimbChain;
import dev.jsz.primordia.ecology.EnergyBudget;
import dev.jsz.primordia.ecology.FoodSurvey;
import dev.jsz.primordia.ecology.SurvivalDrops;
import dev.jsz.primordia.ecology.WorldImpact;
import dev.jsz.primordia.ecology.region.RegionMaterialiser;
import dev.jsz.primordia.util.MathX;
// import dev.jsz.primordia.entity.goal.ClimbWallGoal; // DISABLED: wall climbing commented out
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
import dev.jsz.primordia.sound.CallType;
import dev.jsz.primordia.sound.CreatureVoicePayload;
import dev.jsz.primordia.sound.VoiceProfile;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3f;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.TemptGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.ChatFormatting;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;

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
public class CreatureEntity extends PathfinderMob {
	private static final EntityDataAccessor<String> GENOME_CODE =
			SynchedEntityData.defineId(CreatureEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<Byte> ACTIVITY =
			SynchedEntityData.defineId(CreatureEntity.class, EntityDataSerializers.BYTE);
	private static final EntityDataAccessor<Boolean> TAMED =
			SynchedEntityData.defineId(CreatureEntity.class, EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<Boolean> SADDLED =
			SynchedEntityData.defineId(CreatureEntity.class, EntityDataSerializers.BOOLEAN);
	/**
	 * The owning player's UUID as text, or empty for none.
	 * <p>
	 * 26.2 removed {@code OPTIONAL_UUID} and tracks owners as an {@code EntityReference} instead.
	 * That is not equivalent here: a reference resolves against entities the world currently has,
	 * so it goes empty the moment the owner logs out, and a bonded companion would stop recognising
	 * them across a relog. The identity a creature remembers is therefore still a UUID — synced and
	 * saved — and the reference-shaped part, resolving that id to an actual entity, stays in
	 * {@link #getOwner()} where it was always a live lookup anyway.
	 */
	private static final EntityDataAccessor<String> OWNER =
			SynchedEntityData.defineId(CreatureEntity.class, EntityDataSerializers.STRING);
	/**
	 * Horizontal direction of the wall this creature is on, as a {@link Direction} id, or -1.
	 * <p>
	 * Replicated because the renderer needs it: a climber is drawn rotated onto the surface it is
	 * clinging to, and which surface that is cannot be worked out client-side without re-scanning
	 * the blocks around every creature every frame.
	 */
	private static final EntityDataAccessor<Byte> CLIMB_FACING =
			SynchedEntityData.defineId(CreatureEntity.class, EntityDataSerializers.BYTE);
	private static final EntityDataAccessor<Boolean> CLIMBING =
			SynchedEntityData.defineId(CreatureEntity.class, EntityDataSerializers.BOOLEAN);
	/**
	 * How far grown this creature is, {@link #BABY_SCALE} at birth to 1 once adult.
	 * <p>
	 * Replicated because it scales the drawn body and the collision box alike, and the client cannot
	 * derive it — age is server state.
	 */
	private static final EntityDataAccessor<Float> GROWTH =
			SynchedEntityData.defineId(CreatureEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Boolean> DOMESTICATED =
			SynchedEntityData.defineId(CreatureEntity.class, EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<Boolean> SITTING =
			SynchedEntityData.defineId(CreatureEntity.class, EntityDataSerializers.BOOLEAN);
	/** Test-rig flag: hold position as a display specimen. See {@code /primordia test}. */
	private static final EntityDataAccessor<Boolean> POSING =
			SynchedEntityData.defineId(CreatureEntity.class, EntityDataSerializers.BOOLEAN);
	/** Whether a posed specimen plays its walk cycle or stands. Toggled by {@code /primordia test walk}. */
	private static final EntityDataAccessor<Boolean> POSE_WALKING =
			SynchedEntityData.defineId(CreatureEntity.class, EntityDataSerializers.BOOLEAN);
	/** Dead and lying where it fell, waiting to be eaten. Tracked because the renderer poses it. */
	/**
	 * Whether the flesh is gone. Synced rather than derived from the tick count because the client
	 * picks a different mesh for it, and a client that has to guess the stage will show the wrong
	 * one for as long as it takes the next update to arrive.
	 */
	private static final EntityDataAccessor<Boolean> SKELETON =
			SynchedEntityData.defineId(CreatureEntity.class, EntityDataSerializers.BOOLEAN);
	/**
	 * How far through {@link #SKELETON_LIFETIME} this skeleton is, 0 to 1. Synced because the
	 * renderer uses it to darken the bones from the ground up, and that has to match on every
	 * client watching rather than be guessed from an unsynced local clock.
	 */
	private static final EntityDataAccessor<Float> SKELETON_AGE =
			SynchedEntityData.defineId(CreatureEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Boolean> CARCASS =
			SynchedEntityData.defineId(CreatureEntity.class, EntityDataSerializers.BOOLEAN);
	/** Resting through the inactive half of its cycle. Tracked for the same reason. */
	private static final EntityDataAccessor<Boolean> ASLEEP =
			SynchedEntityData.defineId(CreatureEntity.class, EntityDataSerializers.BOOLEAN);

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
	/**
	 * Ticks before a carcass's meat spoils. One in-game day.
	 * <p>
	 * Nothing falls to the ground at this mark — it changes what butchering the body yields, from
	 * meat to rotten flesh, and nothing else. See {@link #hurtServer} for where the drop actually
	 * happens; it only ever happens because a hand made it.
	 */
	public static final int CARCASS_ROT_TICKS = 24000;
	/** Ticks a carcass keeps its flesh before it is stripped to a skeleton. Two in-game days. */
	public static final int CARCASS_LIFETIME = 48000;
	/**
	 * Ticks a skeleton lies where it fell, on top of {@link #CARCASS_LIFETIME}. Ten in-game days.
	 * <p>
	 * Long on purpose. Bones are the only lasting mark that something lived and died in a place, and
	 * a landmark that expires in a session is not a landmark — at ten days a kill site is still there
	 * when a player comes back through, and the ecology can be read off the ground rather than
	 * inferred from what happens to be alive at the moment.
	 */
	public static final int SKELETON_LIFETIME = 240000;
	/** Ticks a carcass remains fresh (5 in-game hours = 5,000 ticks). After this, butchering yields rotten flesh. */
	public static final int FRESH_CARCASS_TICKS = 5000;
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
	private VoiceProfile voiceProfile;

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
	/** Client-side easing of the turn onto a wall, 0 upright to 1 flat against it. */
	private float climbBlend;
	/** Share of the remaining angle covered per tick. About a third of a second end to end. */
	private static final float CLIMB_BLEND_RATE = 0.18f;
	/** Remaining food in this carcass, in the absolute units {@code EnergyBudget} works in. */
	private float carcassNutrition;
	/** Ticks this creature has spent inside a block. Reset the moment it is clear again. */
	private int entombedTicks;
	/** Ticks of being stuck before it is moved out bodily. Three seconds of trying first. */
	public static final int ENTOMBED_RELOCATE_TICKS = 60;
	/** How far to look for somewhere this body fits, in blocks. */
	public static final double ENTOMBED_SEARCH_RADIUS = 4.0;
	/** Per-tick shove toward open air. Enough to slide a body out, short of launching it. */
	public static final double ENTOMBED_SHOVE = 0.055;
	/** It runs, rather than strolls, out of a wall. */
	public static final double ENTOMBED_SPEED = 1.35;

	/**
	 * The overworld clock reading when this body was made, which is what its age is measured
	 * against.
	 * <p>
	 * Day time rather than game time, and an anchor rather than a counter, for two reasons that both
	 * come from what a carcass is. A counter only advances while the body is being ticked, so a
	 * carcass in an unloaded chunk was frozen — walk away for a week and come back to a corpse as
	 * fresh as the day it died. And a counter is deaf to the clock: {@code /time add 24000} moves the
	 * world on a day and left every body in it untouched, which made decay impossible to test and
	 * inconsistent with everything else that ages.
	 */
	private long carcassBornAt;
	/** Whether the rotten meat and loose bone have already fallen off it. */
	private boolean carcassRotted;
	/**
	 * Set at death when this animal is to leave a body, cleared when the body is spawned.
	 * <p>
	 * Not saved. It is only ever true for the twenty ticks between dying and being removed, and the
	 * one way to lose it is for the chunk to unload inside that window — where suppressing the body is
	 * the right answer anyway.
	 */
	private boolean carcassOwed;

	/**
	 * This creature's vocal apparatus, derived once from its genome and body and cached.
	 * <p>
	 * Derived on both sides. The server never renders audio, but it does not need to: the client has
	 * the genome already and reaches the same profile, which is what lets a call cross the wire as
	 * three bytes. See {@link CreatureVoicePayload}.
	 */
	public VoiceProfile getVoiceProfile() {
		if (voiceProfile == null && getGenome() != null) {
			voiceProfile = VoiceProfile.of(getGenome(), getBodyPlan());
		}
		return voiceProfile;
	}

	/**
	 * Every vocalisation, sent to whoever can see this animal.
	 * <p>
	 * Server-side only, and deliberately not routed through {@code playSound}. A vanilla sound packet
	 * names a registered event, and these creatures have no registered events — their voices are
	 * synthesised per genome on the client that hears them.
	 */
	public void vocalise(CallType call) {
		if (level().isClientSide()) return;
		CreatureVoicePayload.broadcast(this, call);
	}

	// Vanilla's three sound hooks are silenced rather than pointed anywhere. Returning null is the
	// supported way to say "this mob has no such sound" — LivingEntity and Mob both null-check
	// before playing — and it stops the engine from emitting a sound packet that would race the
	// synthesised call and arrive as an echo. What replaces each of them is directly below.

	@Override
	protected SoundEvent getAmbientSound() {
		return null;
	}

	@Override
	protected SoundEvent getHurtSound(DamageSource source) {
		return null;
	}

	@Override
	protected SoundEvent getDeathSound() {
		return null;
	}

	@Override
	public void playAmbientSound() {
		vocalise(isBaby() ? CallType.CHIRP : CallType.AMBIENT);
	}

	@Override
	protected void playHurtSound(DamageSource source) {
		vocalise(CallType.HURT);
	}

	/**
	 * Loudness of the non-vocal sounds vanilla still plays for this creature — footfalls in water,
	 * landing from a fall. The voice does not go through here; it carries its own volume, taken from
	 * the same body mass this is.
	 */
	@Override
	public float getSoundVolume() {
		BodyPlan plan = getBodyPlan();
		return plan == null ? 1.0f : Mth.clamp(0.4f + plan.mass * 0.6f, 0.4f, 1.6f);
	}

	@Override
	public float getVoicePitch() {
		BodyPlan plan = getBodyPlan();
		return plan == null ? 1.0f : Mth.clamp(1.5f - (plan.mass * 0.90f), 0.40f, 1.85f);
	}

	public void playAttackGrowl() {
		vocalise(CallType.THREAT);
	}

	/**
	 * Tearing at a body.
	 * <p>
	 * The one creature sound that is still a vanilla sample, and the one that should be: this is the
	 * noise of the meal, not of the animal. It has no vocal tract behind it, so there is nothing for
	 * the synthesiser to derive it from — a fox's wet crunch reads as flesh where the polite chewing
	 * of {@code GENERIC_EAT} does not. Pitch still tracks the body, so something enormous tears at a
	 * lower register, and the volume sits under a call so a pack at a kill does not drown the area.
	 */
	public void playFeedingSound() {
		if (level().isClientSide()) return;
		playSound(SoundEvents.FOX_EAT, getSoundVolume() * 0.8f,
				getVoicePitch() * (0.85f + random.nextFloat() * 0.2f));
	}

	public void playMatingCall() {
		vocalise(CallType.MATING);
	}

	@Override
	public boolean doHurtTarget(ServerLevel world, Entity target) {
		playAttackGrowl();
		CreatureActivity act = switch (getAttackStyle()) {
			case BITE -> CreatureActivity.BITE;
			case CLAW -> CreatureActivity.CLAW;
			case TAIL_SLAM -> CreatureActivity.TAIL_SLAM;
			case RAM, STOMP -> CreatureActivity.RAM;
		};
		triggerActivity(act);
		return super.doHurtTarget(world, target);
	}

	public CreatureEntity(EntityType<? extends CreatureEntity> type, Level world) {
		super(type, world);
	}

	public static boolean canSpawn(EntityType<CreatureEntity> type, net.minecraft.world.level.ServerLevelAccessor world,
	                               net.minecraft.world.entity.EntitySpawnReason spawnReason, net.minecraft.core.BlockPos pos,
	                               net.minecraft.util.RandomSource random) {
		if (isWorldGenerated(spawnReason) && isFlatWorld(world)) return false;
		net.minecraft.world.level.block.state.BlockState state = world.getBlockState(pos.below());
		return state.isSolidRender() && pos.getY() >= world.getMinY() + 4;
	}

	private static boolean isWorldGenerated(net.minecraft.world.entity.EntitySpawnReason reason) {
		return reason == net.minecraft.world.entity.EntitySpawnReason.NATURAL
				|| reason == net.minecraft.world.entity.EntitySpawnReason.CHUNK_GENERATION
				|| reason == net.minecraft.world.entity.EntitySpawnReason.SPAWNER;
	}

	private static boolean isFlatWorld(net.minecraft.world.level.ServerLevelAccessor world) {
		net.minecraft.world.level.chunk.ChunkGenerator generator =
				world.getLevel().getChunkSource().getGenerator();
		return generator instanceof net.minecraft.world.level.levelgen.FlatLevelSource
				|| generator instanceof net.minecraft.world.level.levelgen.DebugLevelSource;
	}

	@Override
	public net.minecraft.world.entity.SpawnGroupData finalizeSpawn(net.minecraft.world.level.ServerLevelAccessor world,
	                                                   net.minecraft.world.DifficultyInstance difficulty,
	                                                   net.minecraft.world.entity.EntitySpawnReason spawnReason,
	                                                   net.minecraft.world.entity.SpawnGroupData entityData) {
		net.minecraft.world.entity.SpawnGroupData data = super.finalizeSpawn(world, difficulty, spawnReason, entityData);
		if (getGenome() == null) {
			long worldSeed = world.getLevel().getSeed();
			long chunkSeed = (worldSeed ^ ((long) blockPosition().getX() * 341873128712L + (long) blockPosition().getZ() * 132897987541L));
			net.minecraft.util.RandomSource seedRandom = net.minecraft.util.RandomSource.create(chunkSeed);
			String biomeName = world.getBiome(blockPosition()).unwrapKey()
					.map(key -> key.identifier().getPath()).orElse("");
			setGenome(Genome.createForBiome(seedRandom, biomeName));
		}
		return data;
	}

	@Override
	public void checkDespawn() {
		if (!(level() instanceof ServerLevel world)) {
			super.checkDespawn();
			return;
		}
		if (isPersistenceRequired() || !RegionMaterialiser.isLedgerManaged(this)) {
			super.checkDespawn();
			return;
		}
		Player nearest = world.getNearestPlayer(this, -1.0);
		if (nearest == null) return;
		if (nearest.distanceToSqr(this) < DESPAWN_RANGE * DESPAWN_RANGE) return;

		RegionMaterialiser.absorb(world, this);
		discard();
	}

	public boolean canClimb() {
		// DISABLED: wall climbing commented out — always returns false.
		// Original logic checked subterranean archetype, leg count and mass.
		return false;
	}

	public Direction getClimbFacing() {
		byte id = entityData.get(CLIMB_FACING);
		return id < 0 || id >= Direction.values().length ? null : Direction.from3DDataValue(id);
	}

	/**
	 * Which way the creature is leaning, and whether it is on a wall at all. Rendering reads this.
	 * <p>
	 * Prefer {@link #setClimbing} — this is the raw setter, and the one place it is called directly is
	 * to let go.
	 */
	public void setClimbFacing(Direction facing) {
		if (level().isClientSide()) return;
		entityData.set(CLIMB_FACING, facing == null ? (byte) -1 : (byte) facing.get3DDataValue());
		entityData.set(CLIMBING, facing != null);
		// A mantle deliberately survives this. Reaching the top is what ends the goal, so the goal is
		// gone by the time the push over the lip needs to happen, and clearing it here would mean every
		// successful climb ended by sliding back down the face.
		if (facing == null) climbEngaged = false;
	}

	// ------------------------------------------------------------------ climbing

	/**
	 * {@inheritDoc}
	 * <p>
	 * Climbers get a navigation whose graph includes wall surfaces, so a path can run floor → wall →
	 * floor instead of stopping at the first cliff. Built for every creature because navigation is
	 * constructed before the genome arrives; for anything that cannot climb the evaluator adds no
	 * wall edges and this is plain ground navigation.
	 */
	@Override
	protected net.minecraft.world.entity.ai.navigation.PathNavigation createNavigation(Level level) {
		// DISABLED: wall climbing commented out — using vanilla ground navigation.
		// Was: return new dev.jsz.primordia.entity.ai.SurfaceClimberNavigation(this, level);
		return new net.minecraft.world.entity.ai.navigation.GroundPathNavigation(this, level);
	}

	/**
	 * Blocks gained per tick going up a wall. Below a walk: climbing is work, and a creature that went
	 * up a cliff face as fast as it crosses a floor reads as flying.
	 */
	private static final double CLIMB_SPEED = 0.13;
	/**
	 * How hard a climber holds itself in against the surface, per tick.
	 * <p>
	 * Only has to beat the gap the creature's own collision box leaves; anything larger just grinds it
	 * into the blocks. This is what keeps it on the wall now that nothing else does.
	 */
	private static final double CLIMB_PRESS = 0.08;
	/** Per tick closing speed while walking the last stride into the wall. */
	private static final double CLIMB_APPROACH_PUSH = 0.15;
	/** Ticks of forward push after a climb runs out of wall, to carry the body over the lip. */
	private static final int MANTLE_TICKS = 7;
	private static final double MANTLE_PUSH = 0.16;
	private static final double MANTLE_LIFT = 0.06;

	/** -1 down, 0 hold position, +1 up. Server-side; nothing needs to see it but the physics. */
	private float climbDrive;
	/** Sideways travel across the face, -1 to +1, positive toward {@code climbFacing.getClockWise()}. */
	private float climbSideDrive;
	/** Tick the intent was last renewed. Climbing lapses without one, so nothing can hang on a wall. */
	private int climbIntentTick = Integer.MIN_VALUE;
	/** True once the body has actually met the surface, which is what tells a top from a start. */
	private boolean climbEngaged;
	/** Ticks left of the push over the top of a wall, and which way it is going. */
	private int mantleTicks;
	private Direction mantleInto;

	/** Ticks left to back over a ledge and find the face below it, and which way is over. */
	private int dismountTicks;
	private Direction dismountOver;
	/** How long a creature will feel for the wall below a ledge before giving up and simply falling. */
	private static final int DISMOUNT_TICKS = 14;
	/** Per tick walk out over the lip, while still standing on it. */
	private static final double DISMOUNT_PUSH = 0.13;
	/** Per tick descent once past the lip, slow enough to feel the face on the way past. */
	private static final double DISMOUNT_SINK = 0.18;
	/** Deepest drop worth scanning for a floor. Below this it is a shaft, not a wall. */
	private static final int MAX_DROP_SCAN = 24;

	// ------------------------------------------------------------------ growing up

	/** Size of a newborn, as a fraction of its adult self. */
	public static final float BABY_SCALE = 0.42f;
	/** Ticks between growth updates. Every half second is far finer than the eye can follow. */
	private static final int GROWTH_INTERVAL = 10;

	/**
	 * Ticks since birth, or -1 for a creature that was never born — which is most of them.
	 * <p>
	 * Deliberately separate from {@link #lifeTicks}, which every creature accumulates from the moment it
	 * spawns. Keying growth off that would have made every animal the world generated start out as a
	 * baby and grow, so a fresh cave would be full of infants with no parents. Only offspring are young:
	 * a population that has always been there is made of adults, and this field is what tells the two
	 * apart across a save.
	 */
	private int juvenileTicks = -1;

	/**
	 * Marks this creature as newly born, so it starts small and grows into its adult body.
	 * <p>
	 * Called for offspring and nothing else. See {@link #juvenileTicks}.
	 */
	public void bearAsJuvenile() {
		juvenileTicks = 0;
		updateGrowth();
	}

	/** How far grown, {@link #BABY_SCALE} to 1. Scales the body, the collision box and the shadow. */
	public float getGrowth() {
		return entityData.get(GROWTH);
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Overridden so vanilla's own notion of a young animal lines up with this one — it is what keeps
	 * offspring out of everything that quite reasonably refuses to involve children.
	 */
	@Override
	public boolean isBaby() {
		return getGrowth() < 0.999f;
	}

	private void updateGrowth() {
		if (level().isClientSide() || juvenileTicks < 0) return;
		Genome g = getGenome();
		if (g == null) return;

		float maturity = Math.max(1f, EnergyBudget.maturityTicks(g));
		float grown = Mth.clamp(juvenileTicks / maturity, 0f, 1f);
		float scale = BABY_SCALE + (1f - BABY_SCALE) * grown;
		if (grown >= 1f) juvenileTicks = -1;

		if (Math.abs(scale - getGrowth()) < 0.001f) return;
		entityData.set(GROWTH, scale);
		// The collision box is derived from this, so it has to be rebuilt as the body changes.
		refreshDimensions();
	}

	/**
	 * Declares this tick's climbing intent: which wall, and whether to go up, down or hold still.
	 * <p>
	 * Must be renewed every tick. A goal that stops being ticked — interrupted, or the creature simply
	 * dying — releases the wall by doing nothing, which is a great deal safer than relying on every
	 * exit path remembering to clear a flag.
	 * <p>
	 * <b>The climb itself is scripted.</b> Vanilla's climbing is a side effect: {@code LivingEntity}
	 * adds an upward nudge to anything whose {@code onClimbable()} is true <i>on a tick where it also
	 * collided horizontally</i>, and that is the whole mechanism. Driving these creatures through it
	 * meant hoping they pressed a wall hard enough, on the right tick, to be noticed — and they did
	 * not, so they milled about at the bottom of every cliff in the cave. It also cannot express what
	 * a climber actually needs to do, which is hold a position on a wall rather than always rise.
	 * {@link #climbTravel} therefore moves them itself.
	 */
	public void setClimbing(Direction into, float verticalDrive) {
		setClimbing(into, verticalDrive, 0f);
	}

	/** As {@link #setClimbing(Direction, float)}, plus travel across the face. */
	public void setClimbing(Direction into, float verticalDrive, float sidewaysDrive) {
		if (level().isClientSide() || into == null || !canClimb()) return;
		// A mantle or a dismount is committed. Renewing the intent part-way through either would put the
		// creature back into the climb it has just finished, on a wall that is no longer beside it, and
		// it would spend the next second and a half shoved across the top of the cliff it came up.
		if (mantleTicks > 0 || dismountTicks > 0) return;
		setClimbFacing(into);
		climbDrive = verticalDrive;
		climbSideDrive = Mth.clamp(sidewaysDrive, -1f, 1f);
		climbIntentTick = tickCount;
	}

	/**
	 * Heads for a point while on a wall, and reports whether it is still going.
	 * <p>
	 * Steering rather than pathfinding, and the difference matters: this resolves the offset to the
	 * target into "up the face" and "across the face" and drives both, which gets a creature anywhere it
	 * can see on the surface it is on and around the corners in between. What it does not do is reason
	 * about a route — a target behind an overhang, or on a face only reachable the long way round, will
	 * have it pressed against the nearest point and going no further. Ground movement has a real
	 * pathfinder behind it; this does not.
	 */
	public boolean climbToward(Direction into, double targetX, double targetY, double targetZ) {
		if (into == null) return false;
		Direction across = into.getClockWise();
		double sideways = (targetX - getX()) * across.getStepX() + (targetZ - getZ()) * across.getStepZ();
		double rise = targetY - getY();

		// Deadbands, so a creature that has arrived settles instead of shivering across the last inch.
		float up = Math.abs(rise) < CLIMB_ARRIVED ? 0f : (float) Mth.clamp(rise * 2.0, -1.0, 1.0);
		float side = Math.abs(sideways) < CLIMB_ARRIVED ? 0f
				: (float) Mth.clamp(sideways * 2.0, -1.0, 1.0);

		setClimbing(into, up, side);
		return up != 0f || side != 0f;
	}

	/** How close to a target counts as arrived, in blocks. */
	private static final double CLIMB_ARRIVED = 0.35;

	/**
	 * Backs a creature over a ledge and onto the face below it.
	 * <p>
	 * The reverse of a mantle, and needed for the same reason: a climber that can only go up strands
	 * itself on every ledge it reaches. Nothing about ordinary movement gets an animal off a cliff except
	 * falling off it.
	 *
	 * @param over the direction the ground drops away in
	 */
	public void beginDescent(Direction over) {
		if (level().isClientSide() || over == null || !canClimb()) return;
		if (mantleTicks > 0 || dismountTicks > 0 || isClimbing()) return;
		dismountOver = over;
		dismountTicks = DISMOUNT_TICKS;
		climbDrive = -1f;
		climbSideDrive = 0f;
		climbIntentTick = tickCount;
		// Face the wall it is about to be on — which is the one it is backing off — from the outset, so
		// the lean is already turning as it goes over rather than snapping once it arrives.
		setClimbFacing(over.getOpposite());
	}

	public boolean isDescending() {
		return dismountTicks > 0;
	}

	/**
	 * A direction the ground drops away in far enough to be worth climbing down, or null.
	 * <p>
	 * Wants a real face to climb: the neighbouring column open, a floor somewhere below it rather than a
	 * void, and the creature's own block backed by solid rock underneath, since that rock is the surface
	 * it will be hanging off.
	 */
	public Direction ledgeEdge(Direction preferred, int minDrop) {
		if (!onGround()) return null;
		net.minecraft.core.BlockPos under = blockPosition().below();
		if (!solidAt(under) || !solidAt(under.below())) return null;
		if (dropDepth(preferred) >= minDrop) return preferred;
		for (Direction facing : Direction.Plane.HORIZONTAL) {
			if (dropDepth(facing) >= minDrop) return facing;
		}
		return null;
	}

	/** How many blocks of open air lie below the neighbouring column, or 0 if it is not a drop at all. */
	private int dropDepth(Direction over) {
		if (over == null) return 0;
		net.minecraft.core.BlockPos side = blockPosition().relative(over);
		if (solidAt(side)) return 0;

		int depth = 0;
		net.minecraft.core.BlockPos cursor = side.below();
		while (depth < MAX_DROP_SCAN && !solidAt(cursor)) {
			depth++;
			cursor = cursor.below();
		}
		// No floor inside the scan is a shaft, and going over the edge of one is not climbing down.
		return depth >= MAX_DROP_SCAN ? 0 : depth;
	}

	public boolean isClimbing() {
		return getClimbFacing() != null;
	}

	/** Mid-push over the top of a wall — a committed action no goal may take back. */
	public boolean isMantling() {
		return mantleTicks > 0;
	}

	/**
	 * Whether the block column immediately beside the creature in this direction is a wall.
	 * <p>
	 * Asked in whole blocks rather than in the creature's own reach, and that matters: these bodies
	 * are procedural, a cave crawler's is a third of a block wide, and a test measured outward from
	 * such a body never reaches past the block it is standing in. So the small climbers — the ones this
	 * whole feature exists for — could stand at the foot of a cliff and correctly conclude there was no
	 * wall there. Two blocks tall, so a kerb is not something to climb.
	 */
	public boolean wallBeside(Direction into) {
		if (into == null) return false;
		net.minecraft.core.BlockPos base = blockPosition().relative(into);
		return solidAt(base) && solidAt(base.above());
	}

	/**
	 * Whether the body is actually touching the surface, as opposed to merely next to it.
	 * <p>
	 * The narrower of the two questions, and the one the physics needs: {@link #wallBeside} says a
	 * climb is available, this says the creature has arrived and may start going up. Without the
	 * distinction it would rise through the air a block short of the wall it meant to be on.
	 */
	private boolean touchingWall(Direction into) {
		if (into == null) return false;
		double reach = getBbWidth() * 0.5 + 0.25;
		double x = getX() + into.getStepX() * reach;
		double z = getZ() + into.getStepZ() * reach;
		return solidAt(x, getY() + 0.2, z)
				|| solidAt(x, getY() + Math.min(1.2, getBbHeight()), z);
	}

	private boolean solidAt(double x, double y, double z) {
		return solidAt(net.minecraft.core.BlockPos.containing(x, y, z));
	}

	private boolean solidAt(net.minecraft.core.BlockPos pos) {
		return !level().getBlockState(pos).getCollisionShape(level(), pos).isEmpty();
	}

	/**
	 * A wall the creature could start climbing, favouring one direction, or null if it is in the open.
	 */
	public Direction wallAdjacent(Direction preferred) {
		if (wallBeside(preferred)) return preferred;
		for (Direction facing : Direction.Plane.HORIZONTAL) {
			if (wallBeside(facing)) return facing;
		}
		return null;
	}

	/**
	 * Moves a creature that is on a wall, in place of the usual walking physics.
	 * <p>
	 * Deliberately total once it has hold: position, gravity and facing all come from here, so there is
	 * nothing left for friction, step height or a missed collision to interfere with. That is the
	 * entire reason this exists — see {@link #setClimbing}.
	 * <p>
	 * Three phases. Walking the last stride in, which keeps normal gravity because the creature is
	 * still an animal on the floor. Climbing, which has none. And the push over the top, without which
	 * everything that reached the lip slid straight back down the face it had just climbed.
	 */
	private void climbTravel() {
		Direction into = getClimbFacing();

		if (dismountTicks > 0) {
			dismountTravel();
			return;
		}

		// Had the wall and lost it under the body. Going up, that is the top, and the body needs carrying
		// over the lip. Going down or across it is an outside corner or an overhang: try to find the
		// surface round the corner, and failing that let go, because pushing forward over a drop would
		// throw the creature off the cliff it was descending.
		if (mantleTicks <= 0 && into != null && climbEngaged && !touchingWall(into)) {
			if (climbDrive > 0f) {
				mantleTicks = MANTLE_TICKS;
				mantleInto = into;
			} else if (!turnOutsideCorner(into)) {
				setClimbFacing(null);
				return;
			} else {
				into = getClimbFacing();
			}
		}

		if (mantleTicks > 0) {
			mantleTicks--;
			faceTheWall(mantleInto);
			setDeltaMovement(mantleInto.getStepX() * MANTLE_PUSH, MANTLE_LIFT,
					mantleInto.getStepZ() * MANTLE_PUSH);
			move(net.minecraft.world.entity.MoverType.SELF, getDeltaMovement());
			resetFallDistance();
			// Landing ends it early: the creature is over the lip and standing on the top, and carrying
			// on would shove it across the surface it has just arrived on.
			if (mantleTicks == 0 || onGround()) {
				mantleTicks = 0;
				mantleInto = null;
				setClimbFacing(null);
			}
			return;
		}

		if (into == null) return;
		faceTheWall(into);

		if (!touchingWall(into)) {
			// Not on it yet. Walk in under gravity — this is still a creature standing on the ground.
			setDeltaMovement(into.getStepX() * CLIMB_APPROACH_PUSH,
					getDeltaMovement().y,
					into.getStepZ() * CLIMB_APPROACH_PUSH);
			applyGravity();
			move(net.minecraft.world.entity.MoverType.SELF, getDeltaMovement());
			return;
		}

		climbEngaged = true;

		// An inside corner: the way across is blocked by another face. Turn onto it rather than grinding
		// into it, which is what lets a climber follow a surface round a bend instead of stopping at one.
		Direction across = into.getClockWise();
		if (climbSideDrive != 0f) {
			Direction travel = climbSideDrive > 0f ? across : across.getOpposite();
			if (wallBeside(travel) && touchingWall(travel)) {
				setClimbFacing(travel);
				climbSideDrive = 0f;
				into = travel;
				across = into.getClockWise();
			}
		}

		setDeltaMovement(
				into.getStepX() * CLIMB_PRESS + across.getStepX() * climbSideDrive * CLIMB_SPEED,
				climbDrive * CLIMB_SPEED,
				into.getStepZ() * CLIMB_PRESS + across.getStepZ() * climbSideDrive * CLIMB_SPEED);
		move(net.minecraft.world.entity.MoverType.SELF, getDeltaMovement());
		// A climber does not fall while it is holding on, and letting fall distance accumulate over a
		// long ascent would kill it the moment it stepped off at the top.
		resetFallDistance();

		// Down onto the floor is the end of a descent — there is nothing left to hang from.
		if (onGround() && climbDrive < 0f) setClimbFacing(null);
	}

	/**
	 * Follows the surface round an outside corner, and reports whether it found one.
	 * <p>
	 * A creature travelling across a face that runs out has gone past the end of the wall. The face that
	 * continues there is the one at right angles pointing back the way it came, so it wraps onto that
	 * rather than dropping off the end of every wall it crosses.
	 */
	private boolean turnOutsideCorner(Direction into) {
		Direction across = into.getClockWise();
		Direction travel = climbSideDrive >= 0f ? across : across.getOpposite();
		Direction wrapped = travel.getOpposite();
		if (wallBeside(wrapped)) {
			setClimbFacing(wrapped);
			return true;
		}
		// Nothing round that corner; the other one is worth a look before letting go.
		if (wallBeside(travel)) {
			setClimbFacing(travel);
			return true;
		}
		return false;
	}

	/**
	 * Backs over a ledge until the face below it is within reach, then hands over to the climb.
	 * <p>
	 * Two halves, because the creature has to leave the ground before it can find anything to hold. It
	 * walks out over the lip while it is still standing on it, and once it is past the edge and falling
	 * it stops pushing and sinks slowly, feeling behind itself for the wall. Continuing to push outward
	 * after the edge would carry it out of reach of the very surface it is looking for.
	 */
	private void dismountTravel() {
		dismountTicks--;
		Direction over = dismountOver;
		Direction face = over.getOpposite();
		faceTheWall(face);

		if (!onGround() && touchingWall(face)) {
			// Found it. Hand straight over to the climb, already engaged and heading down.
			dismountTicks = 0;
			dismountOver = null;
			climbEngaged = true;
			climbDrive = -1f;
			climbIntentTick = tickCount;
			setClimbFacing(face);
			return;
		}

		if (onGround()) {
			setDeltaMovement(over.getStepX() * DISMOUNT_PUSH, getDeltaMovement().y,
					over.getStepZ() * DISMOUNT_PUSH);
			applyGravity();
		} else {
			setDeltaMovement(0.0, -DISMOUNT_SINK, 0.0);
		}
		move(net.minecraft.world.entity.MoverType.SELF, getDeltaMovement());
		resetFallDistance();

		if (dismountTicks <= 0) {
			// Never found a face. Let go and fall the ordinary way rather than hang in the air.
			dismountTicks = 0;
			dismountOver = null;
			setClimbFacing(null);
		}
	}

	/**
	 * Turns the body to face the surface it is on.
	 * <p>
	 * The renderer pitches a climbing creature ninety degrees onto its wall about its own forward axis,
	 * so a body pointed anywhere else lies across the wall rather than up it. All three angles are
	 * driven, which also stops the standing-still body easing in {@link #tick} pulling the body back
	 * toward wherever the head happens to be looking.
	 */
	private void faceTheWall(Direction into) {
		float wall = into.toYRot();
		setYRot(Mth.rotateIfNecessary(getYRot(), wall, CLIMB_TURN_RATE));
		yBodyRot = Mth.rotateIfNecessary(yBodyRot, wall, CLIMB_TURN_RATE);
		yHeadRot = Mth.rotateIfNecessary(yHeadRot, wall, CLIMB_TURN_RATE);
	}

	/** Degrees per tick the body swings round onto the wall. About half a second end to end. */
	private static final float CLIMB_TURN_RATE = 18f;

	/**
	 * How far onto the wall the body has turned, 0 upright to 1 flat against it.
	 * <p>
	 * Client-side and eased, because the underlying flag is a boolean that flips on a single tick
	 * and a creature that snaps through a right angle reads as a glitch rather than as a climber.
	 */
	public float getClimbBlend() {
		return climbBlend;
	}

	@Override
	public boolean onClimbable() {
		return entityData.get(CLIMBING) || super.onClimbable();
	}

	public static AttributeSupplier.Builder createCreatureAttributes() {
		// ATTACK_DAMAGE is not part of createMobAttributes(). It must be declared here or
		// tryAttack throws "Can't find attribute" and takes the server tick down with it.
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 12.0)
				.add(Attributes.MOVEMENT_SPEED, 0.25)
				.add(Attributes.FOLLOW_RANGE, 24.0)
				.add(Attributes.ATTACK_DAMAGE, 2.0)
				.add(Attributes.ATTACK_KNOCKBACK, 0.0)
				.add(Attributes.ARMOR, 0.0);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(GENOME_CODE, "");
		builder.define(ACTIVITY, (byte) CreatureActivity.IDLE.ordinal());
		builder.define(TAMED, false);
		builder.define(SADDLED, false);
		builder.define(OWNER, "");
		builder.define(DOMESTICATED, false);
		builder.define(SITTING, false);
		builder.define(POSING, false);
		builder.define(POSE_WALKING, true);
		builder.define(CLIMBING, false);
		builder.define(CLIMB_FACING, (byte) -1);
		builder.define(CARCASS, false);
		builder.define(SKELETON, false);
		builder.define(SKELETON_AGE, 0f);
		builder.define(ASLEEP, false);
		// Full size by default: only something actually born starts smaller. See juvenileTicks.
		builder.define(GROWTH, 1f);
	}

	// ------------------------------------------------------------------ ecology

	/** How fed this creature is, in [0,1]. */
	public float getEnergy() {
		return energy;
	}

	public void setEnergy(float value) {
		this.energy = Mth.clamp(value, 0f, 1f);
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

	/**
	 * Fraction of its health a creature that stands its ground will spend before it breaks and runs.
	 * <p>
	 * Below {@code DEFENSIVE}'s appetite for a fight but well above dead, so the animal genuinely
	 * fights first and the flight is a decision rather than a reflex.
	 */
	private static final float BREAKS_AND_RUNS_BELOW = 0.4f;

	/**
	 * Whether this creature would rather run than stay where it is.
	 * <p>
	 * Skittish animals always would. A defensive one holds its ground — that is what makes it
	 * defensive — right up until it is plainly losing, and then it runs, because an animal that fights
	 * to the death against something bigger than it is not defending itself, it is dying slowly. The
	 * two together are what the player sees as a fight with an outcome instead of a creature standing
	 * still while something eats it.
	 * <p>
	 * Only ever consulted by {@link PanicGoal}, which additionally requires that something have
	 * actually hurt the animal, so a healthy creature at rest never flees from nothing.
	 */
	public boolean wantsToFlee() {
		if (getTemperament().fleesWhenHurt()) return true;
		// A companion stays where its owner put it; deciding to bolt is the owner's call.
		if (isDomesticated()) return false;
		return getHealth() < getMaxHealth() * BREAKS_AND_RUNS_BELOW;
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
	public boolean killedEntity(ServerLevel world, LivingEntity other, DamageSource damageSource) {
		boolean result = super.killedEntity(world, other, damageSource);
		if (!isDomesticated()) {
			huntCooldown = Math.max(huntCooldown, POST_KILL_COOLDOWN);
			setTarget(null);
		}
		return result;
	}

	public void onHuntFailed() {
		Genome g = getGenome();
		addEnergy(-EnergyBudget.FAILED_HUNT_COST);
		huntCooldown = g == null ? 200 : EnergyBudget.failedHuntCooldown(g);
		setTarget(null);
	}

	public boolean isAsleep() {
		return entityData.get(ASLEEP);
	}

	public void setAsleep(boolean asleep) {
		if (isAsleep() == asleep) return;
		entityData.set(ASLEEP, asleep);
		if (asleep) {
			getNavigation().stop();
			setTarget(null);
			entityData.set(ACTIVITY, (byte) CreatureActivity.SLEEP.ordinal());
		}
	}

	public boolean isCarcass() {
		return entityData.get(CARCASS);
	}

	/** Stripped to bone: the last stage a body goes through, and the longest. */
	public boolean isSkeleton() {
		return entityData.get(SKELETON);
	}

	/** 0 for a fresh skeleton, 1 the moment it is due to crumble away. */
	public float getSkeletonAge() {
		return entityData.get(SKELETON_AGE);
	}

	public float getCarcassNutrition() {
		return carcassNutrition;
	}

	public static void spawnCarcassOf(CreatureEntity dead) {
		if (dead.isCarcass()) return;
		Genome g = dead.getGenome();
		BodyPlan plan = dead.getBodyPlan();
		if (g == null || plan == null) return;
		if (!(dead.level() instanceof ServerLevel world)) return;

		CreatureEntity carcass = PrimordiaEntities.CREATURE.create(world, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
		if (carcass == null) return;
		carcass.setGenome(g);
		float deathYaw = dead.yBodyRot;
		carcass.snapTo(dead.getX(), dead.getY(), dead.getZ(), deathYaw, 0f);
		carcass.setYRot(deathYaw);
		carcass.yRotO = deathYaw;
		carcass.yBodyRot = deathYaw;
		carcass.yBodyRotO = deathYaw;
		carcass.yHeadRot = deathYaw;
		carcass.yHeadRotO = deathYaw;
		carcass.becomeCarcass(EnergyBudget.carcassNutrition(plan));
		world.addFreshEntity(carcass);
	}

	private void becomeCarcass(float nutrition) {
		entityData.set(CARCASS, true);
		entityData.set(ACTIVITY, (byte) CreatureActivity.CARCASS.ordinal());
		carcassNutrition = nutrition;
		carcassBornAt = level() == null ? 0L : level().getOverworldClockTime();
		carcassRotted = false;
		setNoAi(true);
		setSilent(true);
		setPersistenceRequired();
		getNavigation().stop();
		setTarget(null);
		refreshDimensions();
	}

	public float consumeCarcass(float requested) {
		if (!isCarcass() || carcassNutrition <= 0f) return 0f;
		float taken = Math.min(requested, carcassNutrition);
		carcassNutrition -= taken;
		return taken;
	}

	public int getCarcassTicks() {
		return carcassAge();
	}

	/**
	 * How long this body has lain here, in ticks of the day cycle.
	 * <p>
	 * Clamped at zero because the clock can be wound backwards — {@code /time set morning} on an
	 * evening — and a body younger than new is a body whose stages run in reverse.
	 */
	private int carcassAge() {
		if (level() == null) return 0;
		long age = level().getOverworldClockTime() - carcassBornAt;
		return (int) Math.max(0L, Math.min(age, Integer.MAX_VALUE));
	}

	public boolean isFreshCarcass() {
		return !isCarcass() || carcassAge() <= FRESH_CARCASS_TICKS;
	}

	/**
	 * The three stages a body goes through, in order: it lies there, its meat spoils, it is stripped
	 * to bone, and only then does it go.
	 * <p>
	 * Nothing here spawns an item on its own. Rotting and stripping are changes to the body a player
	 * can see and, once spoiled, butcher for rotten flesh instead of meat — but the world never puts
	 * loot on the ground without a hand doing it, which is {@link #hurtServer}'s job. A creature that
	 * scavenges the nutrition down to zero skips straight to the skeleton, silently, for the same
	 * reason: nothing hit it, so nothing drops.
	 */
	private void tickCarcass() {
		int age = carcassAge();
		yBodyRot = yBodyRotO;
		setYRot(yBodyRotO);
		yHeadRot = yBodyRotO;

		if (isSkeleton()) {
			int skeletonAge = age - CARCASS_LIFETIME;
			if (skeletonAge >= SKELETON_LIFETIME) {
				discard();
				return;
			}
			// Every tick rather than on an interval: SynchedEntityData already only sends a value
			// that actually changed, so the throttling is free and does not need doing twice.
			entityData.set(SKELETON_AGE, MathX.clamp01((float) skeletonAge / SKELETON_LIFETIME));
			return;
		}
		if (carcassNutrition <= 0.001f) {
			becomeSkeleton();
			return;
		}
		if (!carcassRotted && age >= CARCASS_ROT_TICKS) {
			carcassRotted = true;
		}
		if (age >= CARCASS_LIFETIME) {
			becomeSkeleton();
		}
	}

	/**
	 * Gets out of a wall, rather than standing in it until it dies.
	 * <p>
	 * Minecraft's answer to an entity inside a block is a point of suffocation damage every tick and
	 * nothing else — the animal is not told it is dying and its goals go on grazing. Terrain shifts,
	 * a body grows into a burrow it fitted through as a juvenile, a spawn lands a fraction inside a
	 * ledge, and what a player sees is a creature calmly standing still while its health drains.
	 * <p>
	 * Three responses, in order of how stuck it is. A shove toward the nearest open air handles the
	 * common case of a body half inside a block, which is out in a tick or two. Pathing to that same
	 * opening handles the case where it has room to walk but no reason to. Relocating handles being
	 * properly entombed — no path exists, so no amount of running would ever have helped, and the
	 * choice is between putting it in the open and watching it die in stone.
	 */
	private void tickEntombed() {
		if (!isInWall()) {
			entombedTicks = 0;
			return;
		}
		entombedTicks++;
		// Nothing sleeps through being buried.
		if (isAsleep()) setAsleep(false);

		Vec3 out = nearestOpening();
		if (out == null) return;

		Vec3 push = out.normalize().scale(ENTOMBED_SHOVE);
		setDeltaMovement(getDeltaMovement().add(push.x, Math.max(0.0, push.y), push.z));

		// Re-issued rather than set once: the path is short and the creature is being shoved along
		// it, so the destination it was given a second ago is often already behind it.
		if (entombedTicks % 10 == 1) {
			getNavigation().moveTo(getX() + out.x, getY() + out.y, getZ() + out.z, ENTOMBED_SPEED);
		}

		if (entombedTicks >= ENTOMBED_RELOCATE_TICKS) {
			snapTo(getX() + out.x, getY() + out.y, getZ() + out.z, getYRot(), getXRot());
			getNavigation().stop();
			entombedTicks = 0;
		}
	}

	/**
	 * Offset to the nearest place this body would fit, or null if there is none within reach.
	 * <p>
	 * Searched outward so the answer is the shortest way out, and sideways before upward: a creature
	 * that shoves itself up through a ceiling has swapped one block for the one above it, while the
	 * ground it walked in from is almost always still open.
	 */
	private Vec3 nearestOpening() {
		for (double radius = 0.5; radius <= ENTOMBED_SEARCH_RADIUS; radius += 0.5) {
			Vec3 best = null;
			double bestDistance = Double.MAX_VALUE;
			for (int i = 0; i < 12; i++) {
				double angle = i * Math.PI / 6.0;
				double dx = Math.cos(angle) * radius;
				double dz = Math.sin(angle) * radius;
				for (double dy : new double[]{0.0, 0.5, -0.5, 1.0}) {
					if (!isFree(dx, dy, dz)) continue;
					double distance = dx * dx + dy * dy * 2.0 + dz * dz;
					if (distance < bestDistance) {
						bestDistance = distance;
						best = new Vec3(dx, dy, dz);
					}
				}
			}
			if (best != null) return best;
		}
		return null;
	}

	/**
	 * Winds a body's clock forward, for testing decay without sitting through it.
	 * <p>
	 * Only the clock is moved. The transitions themselves are left to the next {@link #tickCarcass},
	 * so what a test sees is the same code path a body reaches on its own — a shortcut that skipped
	 * ahead by setting the stage directly would be testing the shortcut.
	 */
	public void ageCarcass(int ticks) {
		if (!isCarcass()) return;
		carcassBornAt -= ticks;
	}

	/** How much longer this body has in its current stage, in ticks. */
	public int ticksUntilNextStage() {
		if (!isCarcass()) return -1;
		int age = carcassAge();
		if (isSkeleton()) return CARCASS_LIFETIME + SKELETON_LIFETIME - age;
		if (!carcassRotted) return CARCASS_ROT_TICKS - age;
		return CARCASS_LIFETIME - age;
	}

	/**
	 * Strips a creature to bone immediately, for testing what remains look like without waiting two
	 * in-game days for one. Drops nothing: the meat is treated as already gone.
	 */
	public void skeletonise() {
		if (!isCarcass()) becomeCarcass(0f);
		carcassRotted = true;
		becomeSkeleton();
	}

	/**
	 * Strips the body to its skeleton in place.
	 * <p>
	 * The same entity rather than a fresh one: it already holds the genome the bones are shaped
	 * from, and swapping entities at the moment of transition would drop the remains a tick out of
	 * step with the body — visibly a different object appearing where one vanished.
	 */
	private void becomeSkeleton() {
		if (isSkeleton()) return;
		carcassRotted = true;
		entityData.set(SKELETON, true);
		carcassNutrition = 0f;
		// Anchored so the ten days of bone start now, whatever brought it to this stage — a body
		// eaten clean in an afternoon should not lie there as a skeleton for a day less than one
		// that rotted down on its own.
		if (carcassAge() < CARCASS_LIFETIME) {
			carcassBornAt = level().getOverworldClockTime() - CARCASS_LIFETIME;
		}
		setPersistenceRequired();
	}

	public boolean isTamed() {
		return entityData.get(TAMED);
	}

	public boolean isSaddled() {
		return entityData.get(SADDLED);
	}

	private String ownerTextCache = "";
	private UUID ownerUuidCache;

	public UUID getOwnerUuid() {
		String text = entityData.get(OWNER);
		if (text.isEmpty()) return null;
		if (!text.equals(ownerTextCache)) {
			ownerTextCache = text;
			try {
				ownerUuidCache = UUID.fromString(text);
			} catch (IllegalArgumentException malformed) {
				ownerUuidCache = null;
			}
		}
		return ownerUuidCache;
	}

	public boolean isOwner(Player player) {
		return player.getUUID().equals(getOwnerUuid());
	}

	public boolean isDomesticated() {
		return entityData.get(DOMESTICATED);
	}

	public boolean isSitting() {
		return entityData.get(SITTING);
	}

	public boolean isPosing() {
		return entityData.get(POSING);
	}

	public boolean isPoseWalking() {
		return entityData.get(POSE_WALKING);
	}

	public void setPoseWalking(boolean walking) {
		entityData.set(POSE_WALKING, walking);
	}

	public void setPosing(boolean posing) {
		entityData.set(POSING, posing);
		setNoAi(posing);
		setInvulnerable(posing);
		setPersistenceRequired();
		if (posing) {
			setSilent(true);
			getNavigation().stop();
			setTarget(null);
		}
	}

	public void setSitting(boolean sitting) {
		entityData.set(SITTING, sitting);
	}

	public LivingEntity getOwner() {
		UUID uuid = getOwnerUuid();
		return uuid == null ? null : level().getPlayerByUUID(uuid);
	}

	private boolean rollDomestication(Player player, float chance) {
		if (isDomesticated() || getRandom().nextFloat() >= chance) return false;

		entityData.set(DOMESTICATED, true);
		((ServerLevel) level()).sendParticles(ParticleTypes.HAPPY_VILLAGER,
				getX(), getY(1.0), getZ(), 18, 0.5, 0.5, 0.5, 0.15);
		playSound(SoundEvents.WOLF_AMBIENT_BABY.value(), 0.7f, 1.0f);
		player.sendSystemMessage(Component.literal("The creature bonds with you — it will fight at your side. "
				+ "Sneak and interact to make it stay.").withStyle(ChatFormatting.GOLD));
		return true;
	}

	public Item getFavouriteFood() {
		Genome g = getGenome();
		return g == null ? Items.WHEAT : TamingPreference.favouriteFood(g);
	}

	@Override
	public InteractionResult mobInteract(Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);

		if (!isTamed()) {
			if (!stack.is(getFavouriteFood())) {
				return super.mobInteract(player, hand);
			}
			if (level().isClientSide()) return InteractionResult.SUCCESS;

			stack.consume(1, player);
			Genome g = getGenome();
			BodyPlan plan = getBodyPlan();
			float chance = g == null ? 0.3f
					: TamingPreference.tameChance(g, plan == null ? 0.2f : plan.mass);

			if (getRandom().nextFloat() < chance) {
				entityData.set(TAMED, true);
				entityData.set(OWNER, player.getUUID().toString());
				setTarget(null);
				setLastHurtByMob(null);
				((ServerLevel) level()).sendParticles(ParticleTypes.HEART,
						getX(), getY(0.9), getZ(), 7, 0.4, 0.4, 0.4, 0.1);
				player.sendOverlayMessage(Component.literal("The creature accepts you.")
						.withStyle(ChatFormatting.GREEN));
				rollDomestication(player, DOMESTICATION_ON_TAME_CHANCE);
			} else {
				((ServerLevel) level()).sendParticles(ParticleTypes.SMOKE,
						getX(), getY(0.9), getZ(), 5, 0.3, 0.3, 0.3, 0.02);
			}
			return InteractionResult.CONSUME;
		}

		if (isDomesticated() && isOwner(player) && player.isSecondaryUseActive()) {
			if (level().isClientSide()) return InteractionResult.SUCCESS;
			setSitting(!isSitting());
			getNavigation().stop();
			setTarget(null);
			player.sendOverlayMessage(Component.literal(isSitting()
					? "The creature settles down to wait."
					: "The creature falls in behind you.").withStyle(ChatFormatting.GREEN));
			return InteractionResult.CONSUME;
		}

		if (stack.is(getFavouriteFood()) && loveTimer <= 0) {
			if (level().isClientSide()) return InteractionResult.SUCCESS;

			// Neither of these consumes the food. Refusing to breed and taking the item anyway is how a
			// player ends up with an empty hand and no idea why nothing happened.
			if (isBaby()) {
				player.sendOverlayMessage(Component.literal("This one is still growing.")
						.withStyle(ChatFormatting.YELLOW));
				return InteractionResult.CONSUME;
			}
			if (breedCooldown > 0) {
				// A pair used to be able to breed again the instant the last one was born, so a handful
				// of food produced a herd. The wait is the creature's own: it comes off FECUNDITY, the
				// same gene that paces wild broods, so a fast breeder recovers sooner than a slow one.
				player.sendOverlayMessage(Component.literal(
								"Not ready to breed — about " + (breedCooldown / 1200 + 1) + " min")
						.withStyle(ChatFormatting.YELLOW));
				return InteractionResult.CONSUME;
			}

			stack.consume(1, player);
			if (isOwner(player)) rollDomestication(player, DOMESTICATION_ON_FEED_CHANCE);
			loveTimer = 600;
			playMatingCall();
			((ServerLevel) level()).sendParticles(ParticleTypes.HEART,
					getX(), getY(0.9), getZ(), 8, 0.4, 0.4, 0.4, 0.1);
			player.sendOverlayMessage(Component.literal("The creature enters a breeding mood!")
					.withStyle(ChatFormatting.LIGHT_PURPLE));
			return InteractionResult.CONSUME;
		}

		if (!isSaddled() && stack.is(Items.SADDLE)) {
			if (level().isClientSide()) return InteractionResult.SUCCESS;
			if (!canBeSaddled()) {
				player.sendOverlayMessage(Component.literal("This creature is too small to carry a rider.")
						.withStyle(ChatFormatting.YELLOW));
				return InteractionResult.CONSUME;
			}
			stack.consume(1, player);
			entityData.set(SADDLED, true);
			playSound(SoundEvents.HORSE_SADDLE.value(), 0.6f, 1.0f);
			return InteractionResult.CONSUME;
		}

		if (isSaddled() && !player.isSecondaryUseActive()) {
			if (level().isClientSide()) return InteractionResult.SUCCESS;
			player.startRiding(this);
			return InteractionResult.CONSUME;
		}

		return super.mobInteract(player, hand);
	}

	public boolean canBeSaddled() {
		BodyPlan plan = getBodyPlan();
		// Size is measured on the adult body, so a juvenile of a rideable species has to be turned down
		// on its age rather than on its build — it will grow into it.
		return plan != null && !isBaby() && plan.hipHeight >= 0.75f && plan.mass >= 0.08f;
	}

	@Override
	public LivingEntity getControllingPassenger() {
		if (!isSaddled()) return null;
		if (getFirstPassenger() instanceof Player rider && isOwner(rider)) {
			return rider;
		}
		return null;
	}

	@Override
	protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float scaleFactor) {
		BodyPlan plan = getBodyPlan();
		if (plan == null) {
			return super.getPassengerAttachmentPoint(passenger, dimensions, scaleFactor);
		}
		float growth = getGrowth();
		return new Vec3(0.0, plan.hipHeight * 0.98 * growth, -plan.bodyLength * 0.05 * growth);
	}

	@Override
	public void travel(Vec3 movementInput) {
		if (!isAlive()) {
			super.travel(movementInput);
			return;
		}
		// DISABLED: wall climbing commented out.
		// if (getControllingPassenger() == null
		//         && (isClimbing() || mantleTicks > 0 || dismountTicks > 0)) {
		//     climbTravel();
		//     return;
		// }
		if (!(getControllingPassenger() instanceof Player rider)) {
			super.travel(movementInput);
			return;
		}

		float targetYaw = rider.getYRot();
		float currentYaw = getYRot();
		float newYaw = Mth.rotateIfNecessary(currentYaw, targetYaw, 6.0f);

		yRotO = currentYaw;
		setYRot(newYaw);
		setXRot(rider.getXRot() * 0.5f);

		yBodyRotO = yBodyRot;
		yBodyRot = newYaw;
		yHeadRotO = yHeadRot;
		yHeadRot = Mth.rotateIfNecessary(yHeadRot, newYaw, 8.0f);

		float sideways = rider.xxa * 0.3f;
		float forward = rider.zza;
		if (forward <= 0f) forward *= 0.28f;

		if (isLocalInstanceAuthoritative()) {
			float baseSpeed = (float) getAttributeValue(Attributes.MOVEMENT_SPEED);
			float rideSpeed = Math.min(baseSpeed, 0.32f);
			setSpeed(rideSpeed);
			super.travel(new Vec3(sideways, movementInput.y, forward));
		} else {
			setDeltaMovement(Vec3.ZERO);
		}
		setSpeed(0f);
	}

	@Override
	protected void registerGoals() {
		goalSelector.addGoal(0, new FloatGoal(this));
		goalSelector.addGoal(0, new StayGoal(this));

		goalSelector.addGoal(1, new PanicGoal(this, 1.6) {
			@Override
			public boolean canUse() {
				return wantsToFlee() && super.canUse();
			}
		});

		goalSelector.addGoal(1, new RestGoal(this));

		goalSelector.addGoal(2, new CreatureTemptGoal(this, 1.15));
		goalSelector.addGoal(2, new FleeLargerCreatureGoal(this, 1.35));
		goalSelector.addGoal(3, new CreatureAttackGoal(this, 1.15));
		goalSelector.addGoal(4, new FeedOnCarcassGoal(this, 1.1));
		// goalSelector.addGoal(4, new ClimbWallGoal(this, 1.0)); // DISABLED: wall climbing commented out
		goalSelector.addGoal(4, new FollowOwnerGoal(this, 1.25, 10f, 3f, 20f));
		goalSelector.addGoal(3, new LeaveWaterGoal(this, 1.1));
		goalSelector.addGoal(4, new GrazeGoal(this));
		goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 1.0));
		goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0f));
		goalSelector.addGoal(7, new RandomLookAroundGoal(this));

		targetSelector.addGoal(1, new DefendOwnerGoal(this));

		targetSelector.addGoal(2, new HurtByTargetGoal(this) {
			@Override
			public boolean canUse() {
				LivingEntity attacker = getLastHurtByMob();
				if (attacker instanceof net.minecraft.world.entity.monster.Monster) {
					return super.canUse();
				}
				if (!getTemperament().retaliates()) return false;
				if (isDomesticated() && attacker instanceof Player player
						&& isOwner(player)) {
					return false;
				}
				return super.canUse();
			}
		});

		targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, net.minecraft.world.entity.monster.Monster.class, 10, true, false,
				(monster, level) -> {
					if (isDomesticated() || isAsleep() || isCarcass()) return false;
					if (getTemperament() == Temperament.SKITTISH) return false;
					BodyPlan mine = getBodyPlan();
					if (mine == null) return false;
					float theirs = dev.jsz.primordia.ecology.VanillaInteractions.massOf(monster.getType());
					return theirs > 0f && EnergyBudget.isWorthHunting(mine.mass, theirs);
				}));

		targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, CreatureEntity.class, 10, true, false,
				(other, level) -> {
					if (!(other instanceof CreatureEntity prey) || prey == this) return false;
					if (!wantsToHunt()) return false;
					if (prey.isCarcass()) return false;
					if (isDomesticated() && prey.isDomesticated()
							&& getOwnerUuid() != null && getOwnerUuid().equals(prey.getOwnerUuid())) {
						return false;
					}
					BodyPlan mine = getBodyPlan();
					BodyPlan theirs = prey.getBodyPlan();
					if (mine == null || theirs == null) return false;
					return EnergyBudget.isWorthHunting(mine.mass, theirs.mass);
				}));

		targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, net.minecraft.world.entity.animal.Animal.class, 10, true, false,
				(animal, level) -> {
					if (isDomesticated() || !wantsToHunt()) return false;
					if (!getTemperament().huntsUnprovoked()
							&& (getGenome() == null || getGenome().raw(Gene.DIET) <= 0.45f)) {
						return false;
					}
					BodyPlan mine = getBodyPlan();
					if (mine == null) return false;
					float theirs = dev.jsz.primordia.ecology.VanillaInteractions
							.massOf(animal.getType());
					return theirs > 0f && EnergyBudget.isWorthHunting(mine.mass, theirs);
				}));

		targetSelector.addGoal(4, new NearestAttackableTargetGoal<>(this, Player.class, 10, true, false,
				(target, level) -> getTemperament().huntsUnprovoked() && !isDomesticated() && wantsToHunt()));
	}

	@Override
	public void onSyncedDataUpdated(EntityDataAccessor<?> data) {
		super.onSyncedDataUpdated(data);
		if (GENOME_CODE.equals(data)) {
			// The genome arrives on the client a tick or two after the spawn packet. Everything
			// derived from it is stale until this fires — including the collision box, which is
			// why hitboxes did not match the creature's actual size.
			genome = null;
			genomeCodeCache = "";
			dietGroup = null;
			attackStyle = null;
			temperament = null;
			voiceProfile = null;
			animator = null;
			refreshDimensions();
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
	 * <p>
	 * Unless there is nothing left to leave. A death by fire or lava consumes the body, so it
	 * neither drops nor becomes a carcass — a corpse lying intact in a lava lake reads as a bug
	 * whatever the ecology says, and a floating one is worse.
	 */
	@Override
	public void die(DamageSource damageSource) {
		super.die(damageSource);
		if (level().isClientSide() || isCarcass()) return;

		// Where the death call goes, now that getDeathSound() is silent. Here rather than in that
		// method because a carcass must not scream: it is already dead, and dies() runs again when
		// one is destroyed.
		vocalise(CallType.DEATH);

		// Goals stop being ticked the moment the creature dies, so nothing else will clear this, and a
		// climber still flagged as on a wall drifts down through its whole death animation instead of
		// dropping. Onto the floor is where a body belongs.
		setClimbFacing(null);

		if (consumesTheBody(damageSource)) return;

		if (SurvivalDrops.killedByPlayer(damageSource)) {
			SurvivalDrops.dropLoot(this, 1f);
		} else {
			// Owed, not spawned. See remove().
			carcassOwed = true;
		}
	}

	/**
	 * Spawns the body once the dying animal is gone, rather than beside it.
	 * <p>
	 * A creature is not removed at the moment it dies — vanilla keeps it around for the twenty ticks
	 * of {@code deathTime} — so a carcass created in {@link #die} appeared while the animal that
	 * produced it was still standing there, and for a full second the player watched two copies of the
	 * same creature occupy the same spot. Waiting for the removal costs that second and nothing else.
	 * <p>
	 * Only on {@code KILLED}. A body owed to a creature that left because its chunk unloaded is a body
	 * that should not exist, and spawning one into a chunk on its way out would either vanish with it
	 * or resurrect the animal as furniture.
	 */
	@Override
	public void remove(RemovalReason reason) {
		boolean owed = carcassOwed && reason == RemovalReason.KILLED && !level().isClientSide();
		carcassOwed = false;
		super.remove(reason);
		if (owed) spawnCarcassOf(this);
	}

	/**
	 * Whether this death leaves nothing behind to find.
	 * <p>
	 * Burning covers fire, lava and magma; the void and the kill command leave nothing by
	 * definition. Everything else — including a player's kill with a flaming sword, which the
	 * damage type does not mark as fire — still yields a body or a drop.
	 */
	private static boolean consumesTheBody(DamageSource source) {
		if (source == null) return false;
		return source.is(DamageTypeTags.IS_FIRE)
				|| source.is(DamageTypes.LAVA)
				|| source.is(DamageTypes.IN_FIRE)
				|| source.is(DamageTypes.ON_FIRE)
				|| source.is(DamageTypes.HOT_FLOOR)
				|| source.is(DamageTypes.FELL_OUT_OF_WORLD)
				|| source.is(DamageTypes.GENERIC_KILL);
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * A carcass is not alive and cannot be killed again, but a player is allowed to butcher one —
	 * driving a predator off a fresh kill and taking it is a reasonable thing to want to do, and
	 * the yield scales with how much of the body is left.
	 * <p>
	 * Fire is the exception to "cannot be killed again". Ignoring every non-player source is right
	 * for arrows and for other predators, but it also made a body immune to the lava it was lying
	 * in, so a kill that happened to fall in would float there indefinitely. Burning removes it and
	 * yields nothing, matching what happens to a creature that dies in lava in the first place.
	 */
	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		if (!isCarcass()) return super.hurtServer(level, source, amount);
		if (consumesTheBody(source)) {
			discard();
			return true;
		}
		if (!(source.getEntity() instanceof Player)) return false;

		BodyPlan plan = getBodyPlan();
		float remaining = plan == null ? 0f
				: carcassNutrition / Math.max(0.001f, EnergyBudget.carcassNutrition(plan));
		SurvivalDrops.dropLoot(this, remaining);
		discard();
		return true;
	}



	/**
	 * Whether the creature moved horizontally this tick, by the same measure vanilla's body control
	 * uses.
	 * <p>
	 * Deliberately the identical test and threshold rather than something of our own: the two
	 * decisions have to agree on what "moving" means, or there is a sliver of speed where vanilla
	 * points the body down the path and this class turns it back toward the head.
	 */
	private boolean isMovingHorizontally() {
		double dx = getX() - xo;
		double dz = getZ() - zo;
		return dx * dx + dz * dz > 2.500000277905201E-7;
	}

	@Override
	public void tick() {
		super.tick();

		// Smooth body rotation easing — but only while standing still, and never when ridden, since
		// travel() drives yaw directly there.
		//
		// Restricting it to a stationary creature is the whole point. Vanilla's own body control has
		// already run inside super.tick(), and while the creature is moving it points the body along
		// the direction of travel. Easing toward the head unconditionally undid that a few degrees
		// every tick, so a creature walking one way while a look goal aimed its head somewhere else
		// turned to follow its gaze instead of its path — which read as an animal wandering sideways,
		// and only whenever something happened to catch its eye.
		if (getControllingPassenger() == null && !isMovingHorizontally() && !isDeadOrDying() && !isCarcass()) {
			this.yBodyRot = Mth.rotateIfNecessary(this.yBodyRot, this.getYHeadRot(), 7.5f);
		}

		// DISABLED: wall climbing commented out — climb-lapse and blend always zero.
		// if (!level().isClientSide() && isClimbing()
		//         && mantleTicks <= 0 && dismountTicks <= 0 && tickCount - climbIntentTick > 1) {
		//     setClimbFacing(null);
		// }
		// float target = getClimbFacing() != null ? 1f : 0f;
		// climbBlend += (target - climbBlend) * CLIMB_BLEND_RATE;
		climbBlend = 0f;

		if (level().isClientSide()) return;

		if (isCarcass()) {
			tickCarcass();
			return;
		}

		tickEntombed();

		lifeTicks++;
		if (juvenileTicks >= 0) {
			juvenileTicks++;
			if (juvenileTicks % GROWTH_INTERVAL == 0) updateGrowth();
		}
		noticeWatchers();
		if (huntCooldown > 0) huntCooldown--;
		if (breedCooldown > 0) breedCooldown--;

		tickEnergy();
		tickWildBreeding();
		tickBreeding();
		tickTrail();

		if (activityCooldown > 0) {
			activityCooldown--;
			return;
		}
		if (isAsleep()) {
			if (getActivity() != CreatureActivity.SLEEP) {
				entityData.set(ACTIVITY, (byte) CreatureActivity.SLEEP.ordinal());
			}
			// Snoring: the creature's own tract, barely voiced. Mostly air, which is what makes it
			// read as breathing rather than as a quiet call.
			if ((tickCount + getId()) % 80 == 0) {
				vocalise(CallType.SLEEP);
			}
			return;
		}
		CreatureActivity ambient = getDeltaMovement().horizontalDistanceSqr() > WALK_THRESHOLD_SQ
				? CreatureActivity.WALK
				: CreatureActivity.IDLE;
		if (getActivity() != ambient) {
			entityData.set(ACTIVITY, (byte) ambient.ordinal());
		}
	}

	private static final int SIGHTING_INTERVAL = 40;
	private static final double SIGHTING_RANGE = 24.0;

	private void noticeWatchers() {
		if (isCarcass() || tickCount % SIGHTING_INTERVAL != 0) return;
		if (!(level() instanceof ServerLevel world)) return;

		for (var player : world.players()) {
			if (player.distanceToSqr(this) <= SIGHTING_RANGE * SIGHTING_RANGE) {
				dev.jsz.primordia.lab.Discoveries.grant(player,
						dev.jsz.primordia.lab.Discoveries.SOMETHING_MOVES);
			}
		}
	}

	private void tickBreeding() {
		if (loveTimer <= 0) return;
		loveTimer--;

		if (loveTimer % 20 == 0) {
			((ServerLevel) level()).sendParticles(ParticleTypes.HEART,
					getX(), getY(0.9), getZ(), 1, 0.3, 0.3, 0.3, 0.05);
		}

		if (tickCount % 30 != 0 || getGenome() == null) return;

		AABB searchBox = getBoundingBox().inflate(8.0, 4.0, 8.0);
		List<CreatureEntity> partners = level().getEntitiesOfClass(CreatureEntity.class, searchBox,
				other -> other != this && other.isAlive() && !other.isCarcass()
						&& other.loveTimer > 0 && other.getGenome() != null);

		for (CreatureEntity partner : partners) {
			float dist = Mutation.distance(this.getGenome(), partner.getGenome());
			if (dist < 0.45f) {
				this.loveTimer = 0;
				partner.loveTimer = 0;

				ServerLevel world = (ServerLevel) level();
				world.sendParticles(ParticleTypes.HEART, getX(), getY(0.9), getZ(), 14, 0.5, 0.5, 0.5, 0.1);
				world.sendParticles(ParticleTypes.HEART, partner.getX(), partner.getY(0.9), partner.getZ(), 14, 0.5, 0.5, 0.5, 0.1);

				Genome childGenome = Mutation.breed(this.getGenome(), partner.getGenome(),
						new java.util.Random(getRandom().nextLong()));
				CreatureEntity child = PrimordiaEntities.CREATURE.create(world, net.minecraft.world.entity.EntitySpawnReason.BREEDING);
				if (child != null) {
					child.entityData.set(GENOME_CODE, childGenome.encode());
					child.snapTo(
							(getX() + partner.getX()) * 0.5,
							(getY() + partner.getY()) * 0.5,
							(getZ() + partner.getZ()) * 0.5,
							getYRot(), getXRot());
					boolean bornTame = this.isTamed() && partner.isTamed();
					child.entityData.set(TAMED, bornTame);
					child.entityData.set(OWNER, bornTame
							? this.entityData.get(OWNER)
							: "");
					child.setEnergy(0.55f);
					child.bearAsJuvenile();
					this.addEnergy(-EnergyBudget.BREED_COST);
					partner.addEnergy(-EnergyBudget.BREED_COST);
					this.breedCooldown = EnergyBudget.breedingInterval(this.getGenome());
					partner.breedCooldown = EnergyBudget.breedingInterval(partner.getGenome());
					world.addFreshEntity(child);
				}
				break;
			}
		}
	}

	private void tickEnergy() {
		Genome g = getGenome();
		BodyPlan plan = getBodyPlan();
		if (g == null || plan == null) return;

		EnergyBudget.Activity activity;
		if (isAsleep()) {
			activity = EnergyBudget.Activity.RESTING;
		} else if (getTarget() != null || isSprinting()) {
			activity = EnergyBudget.Activity.SPRINTING;
		} else if (getDeltaMovement().horizontalDistanceSqr() > WALK_THRESHOLD_SQ) {
			activity = EnergyBudget.Activity.MOVING;
		} else {
			activity = EnergyBudget.Activity.IDLE;
		}
		addEnergy(-EnergyBudget.drainPerTick(g, plan, activity));

		if (energy > EnergyBudget.STARVING || isTamed()) return;
		if (tickCount % STARVATION_INTERVAL != 0) return;
		if (level() instanceof ServerLevel serverLevel) {
			hurtServer(serverLevel, level().damageSources().starve(), EnergyBudget.STARVATION_DAMAGE);
		}
	}

	private void tickTrail() {
		if (isTamed() || !onGround()) return;
		if (getDeltaMovement().horizontalDistanceSqr() <= WALK_THRESHOLD_SQ) return;
		if (!(level() instanceof ServerLevel world)) return;

		BodyPlan plan = getBodyPlan();
		Genome g = getGenome();
		if (plan == null || g == null) return;

		float pressure = plan.mass * (0.4f + g.raw(Gene.GRAZING_IMPACT));
		if (getRandom().nextFloat() >= pressure * TRAIL_CHANCE_PER_TICK) return;

		WorldImpact.trample(world, blockPosition().below());
	}

	private void tickWildBreeding() {
		if (isTamed() || isPosing() || isAsleep()) return;
		if (loveTimer > 0 || breedCooldown > 0) return;
		if (tickCount % BREEDING_CHECK_INTERVAL != 0) return;
		if (!isMature() || energy < EnergyBudget.BREED_THRESHOLD) return;

		Genome g = getGenome();
		BodyPlan plan = getBodyPlan();
		if (g == null || plan == null) return;

		DietGroup diet = getDietGroup();
		float prey = diet.hunts() ? FoodSurvey.preyDensity(level(), this) : 0f;
		float capacity = FoodSurvey.carryingCapacity(level(), blockPosition(), diet, prey);
		int allowance = Math.max(1, Math.min(BASE_DENSITY_CAP,
				Math.round(capacity / Math.max(0.03f, plan.mass))));

		AABB range = getBoundingBox().inflate(BREEDING_RANGE, 8.0, BREEDING_RANGE);
		int neighbours = level().getEntitiesOfClass(CreatureEntity.class, range,
				other -> other != this && other.isAlive() && !other.isCarcass()
						&& other.getGenome() != null
						&& other.getGenome().lineage() == g.lineage()).size();
		if (neighbours >= allowance) return;

		loveTimer = 600;
		playMatingCall();
	}

	public CreatureActivity getActivity() {
		return CreatureActivity.byId(entityData.get(ACTIVITY));
	}

	public void triggerActivity(CreatureActivity activity) {
		if (level().isClientSide()) return;
		entityData.set(ACTIVITY, (byte) activity.ordinal());
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

	public Genome getGenome() {
		String code = entityData.get(GENOME_CODE);
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
		this.voiceProfile = null;
		entityData.set(GENOME_CODE, genomeCodeCache);
		applyGenomeAttributes(genome);
		refreshDimensions();
	}

	public BodyPlan getBodyPlan() {
		Genome g = getGenome();
		return g == null ? null : BodyPlanCache.get(g);
	}

	private void applyGenomeAttributes(Genome g) {
		BodyPlan plan = BodyPlanCache.get(g);

		AttributeInstance health = getAttribute(Attributes.MAX_HEALTH);
		if (health != null) {
			health.setBaseValue(4.0 + Math.min(60.0, plan.mass * 90.0));
			setHealth(getMaxHealth());
		}

		AttributeInstance speed = getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed != null) {
			double legFactor = 0.16 + 0.18 * Math.min(plan.hipHeight, 2.5);
			double massPenalty = 1.0 / (1.0 + plan.mass * 0.4);
			double geneFactor = 0.6 + 0.8 * g.raw(Gene.SPEED);
			speed.setBaseValue(Math.max(0.12, Math.min(0.35, legFactor * massPenalty * geneFactor)));
		}

		AttributeInstance damage = getAttribute(Attributes.ATTACK_DAMAGE);
		if (damage != null) {
			double bulk = 1.0 + plan.mass * 12.0;
			double weapon = 0.5 + 1.2 * g.raw(Gene.JAW_SIZE);
			double intent = 0.4 + 1.1 * g.raw(Gene.DIET) * (0.5 + g.raw(Gene.AGGRESSION));
			// Diet decides whether an animal goes looking for a fight. It has no business deciding how
			// much a hoof or a horn does once one finds it — scaled by DIET alone, every plant-eater
			// bottomed out at the 0.5 floor, so a defensive herbivore fighting back for its life did a
			// quarter of a heart per blow and the retaliation was decoration. Bulk and disposition carry
			// that case: what it weighs and how bold it is, with no credit for wanting to hunt.
			double defence = bulk * weapon * (0.25 + 0.75 * g.raw(Gene.AGGRESSION)) * 0.35;
			damage.setBaseValue(Math.max(0.5,
					Math.min(14.0, Math.max(bulk * weapon * intent * 0.5, defence))));
		}

		AttributeInstance armor = getAttribute(Attributes.ARMOR);
		if (armor != null) {
			double plated = g.expresses(Gene.DORSAL_SPINES, 0.62f) ? 2.0 : 0.0;
			armor.setBaseValue(Math.min(14.0, g.raw(Gene.ARMOR) * 8.0 + plated));
		}

		AttributeInstance knockback = getAttribute(Attributes.ATTACK_KNOCKBACK);
		if (knockback != null) {
			knockback.setBaseValue(Math.min(2.0, plan.mass * 4.0));
		}
	}

	public static final float SAMPLE_DAMAGE = 2.0f;
	private static final float SAMPLE_HEALTH_FLOOR = 1.0f;

	public void provokeSampling(Player player) {
		if (level().isClientSide() || isCarcass()) return;
		if (isDomesticated() && player.getUUID().equals(getOwnerUuid())) return;

		if (getHealth() - SAMPLE_DAMAGE >= SAMPLE_HEALTH_FLOOR) {
			if (level() instanceof ServerLevel serverLevel) {
				hurtServer(serverLevel, damageSources().playerAttack(player), SAMPLE_DAMAGE);
			}
		}

		if (getTemperament().retaliates()) {
			setTarget(player);
			setLastHurtByMob(player);
		} else if (getTemperament().fleesWhenHurt()) {
			setLastHurtByMob(player);
		}
	}

	public double getHeadY() {
		BodyPlan plan = getBodyPlan();
		if (plan == null || plan.headBone < 0 || plan.headBone >= plan.bones.length) {
			return getEyeY();
		}
		var bone = plan.bones[plan.headBone];
		return getY() + (bone.head.y + bone.tail.y) * 0.5;
	}

	public void lookAtFromHead(double x, double y, double z) {
		getLookControl().setLookAt(x, y + (getEyeY() - getHeadY()), z);
	}

	/**
	 * An extra box on the middle of the spine that exists <b>only to register hits</b>.
	 * <p>
	 * Minecraft gives an entity one bounding box and uses it for everything at once — collision,
	 * pushing, suffocation, pathfinding and hit detection. That forces a compromise on a creature
	 * whose body is nothing like a box: {@code getDefaultDimensions} caps the height at twice the
	 * hip so long-necked animals do not suffocate under trees, and keeps the footprint modest so
	 * they still fit through terrain. The cost is that the box does not always sit where the body
	 * looks like it is, and a swing that visibly connects can land on nothing.
	 * <p>
	 * This box is not the bounding box and never becomes one. It is returned to the hit-scan and to
	 * nothing else, so it cannot push a mob, cannot be pushed, does not collide with terrain and
	 * happily overlaps anything — it adds hittable volume and changes no physics.
	 *
	 * @return the box in world space, or null when the body plan is not available yet
	 */
	public AABB getSpineHitbox() {
		BodyPlan plan = getBodyPlan();
		if (plan == null) return null;

		// The live posed skeleton when there is one, the bind pose when there is not.
		//
		// This is what makes the box follow the animal rather than sit in the middle of its bounding
		// box looking decorative. The animator applies the body bob, the lean into a turn, the roll,
		// and the root drop that carries the whole torso — so a creature's core is somewhere
		// different every frame, and bind-pose bone positions describe where it would be if it were
		// standing perfectly still, which it never is.
		//
		// The animator is driven by the renderer, so a posed skeleton exists on the client and not on
		// the server. That asymmetry is fine here and nowhere else: the client is what aims, and the
		// server only checks that the player was close enough to the entity. Creating an animator
		// here would be worse than useless on a server — one per creature, updated by nothing, for
		// numbers identical to the bind pose it already has.
		Skeleton posed = animator != null ? animator.skeleton() : null;
		if (posed != null && posed.plan != plan) posed = null;

		float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
		float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
		int counted = 0;
		Vector3f head = new Vector3f();
		Vector3f tail = new Vector3f();
		for (int i = 0; i < plan.bones.length; i++) {
			BoneDef bone = plan.bones[i];
			if (!bone.emitsGeometry || !bone.name.startsWith("spine")) continue;
			if (posed != null) {
				posed.boneHead(i, head);
				posed.boneTail(i, tail);
			} else {
				head.set(bone.head);
				tail.set(bone.tail);
			}
			float r = bone.maxRadius();
			minX = Math.min(minX, Math.min(head.x, tail.x) - r);
			minY = Math.min(minY, Math.min(head.y, tail.y) - r);
			minZ = Math.min(minZ, Math.min(head.z, tail.z) - r);
			maxX = Math.max(maxX, Math.max(head.x, tail.x) + r);
			maxY = Math.max(maxY, Math.max(head.y, tail.y) + r);
			maxZ = Math.max(maxZ, Math.max(head.z, tail.z) + r);
			counted++;
		}
		if (counted == 0) return null;

		double cx = (minX + maxX) * 0.5, cy = (minY + maxY) * 0.5, cz = (minZ + maxZ) * 0.5;
		double halfX = (maxX - minX) * 0.5, halfY = (maxY - minY) * 0.5, halfZ = (maxZ - minZ) * 0.5;

		// The torso runs along the body axis, so it turns with the creature. A world-space AABB
		// cannot itself be rotated, so the horizontal half-extents are those of the box enclosing the
		// turned torso — correct at every yaw, and tightest at the diagonals.
		float yawRad = (float) Math.toRadians(-getYRot());
		double cos = Math.cos(yawRad), sin = Math.sin(yawRad);
		double growth = getGrowth();
		double wx = getX() + (cx * cos - cz * sin) * growth;
		double wz = getZ() + (cx * sin + cz * cos) * growth;
		double wy = getY() + cy * growth;

		double rx = Math.max(0.25, (Math.abs(halfX * cos) + Math.abs(halfZ * sin)) * growth);
		double rz = Math.max(0.25, (Math.abs(halfX * sin) + Math.abs(halfZ * cos)) * growth);
		double ry = Math.max(0.25, halfY * growth);
		return new AABB(wx - rx, wy - ry, wz - rz, wx + rx, wy + ry, wz + rz);
	}

	public List<AABB> getLegSubHitboxes() {
		BodyPlan plan = getBodyPlan();
		if (plan == null) return List.of();
		List<AABB> legBoxes = new ArrayList<>();
		Vec3 pos = position();
		float yawRad = (float) Math.toRadians(-getYRot());
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
			AABB legBox = new AABB(wx - radius, pos.y, wz - radius, wx + radius, pos.y + legHeight, wz + radius);
			legBoxes.add(legBox);
		}
		return legBoxes;
	}

	@Override
	public EntityDimensions getDefaultDimensions(Pose pose) {
		Genome g = getGenome();
		if (g == null) {
			return super.getDefaultDimensions(pose);
		}
		BodyPlan plan = BodyPlanCache.get(g);
		float legSpanX = 0f;
		for (LimbChain leg : plan.legs) {
			legSpanX = Math.max(legSpanX, Math.abs(leg.restEffector.x));
		}
		float width = Math.max(0.50f, Math.max(
				Math.max(legSpanX * 2.0f * 1.05f, plan.width() * 0.9f),
				Math.min(plan.bodyLength * 0.40f, 1.8f)));
		// Sized to the silhouette rather than to a multiple of the hip.
		//
		// The old floor of 0.50 was taller than several archetypes are: a cave crawler stands 0.40
		// and was given a box 25% taller than itself, an insectoid 23%, and seven of the eleven
		// archetypes had a box overhanging the animal inside it. Short-legged creatures came off
		// worst, because a floor expressed in absolute blocks is largest relative to whatever is
		// smallest.
		//
		// Shortening is safe in both directions that matter. Suffocation gets better, not worse —
		// Minecraft puts the eye at 85% of the box, so a lower box holds the eye further from the
		// block above it. And nothing becomes harder to hit, because the spine hitbox now covers
		// the torso independently of this box (see getSpineHitbox).
		float height = Math.max(0.35f, Math.max(
				plan.hipHeight * 1.05f,
				Math.min(plan.height() * 0.85f, plan.hipHeight * 1.7f)));

		float growth = getGrowth();
		if (getAttribute(Attributes.STEP_HEIGHT) != null) {
			getAttribute(Attributes.STEP_HEIGHT)
					.setBaseValue(Math.max(1.0, Math.min(2.5, plan.hipHeight * 1.15 * growth)));
		}
		return EntityDimensions.scalable(width * growth, height * growth);
	}

	/**
	 * How deep the water has to be before this creature stops walking and starts swimming.
	 * <p>
	 * Vanilla returns a flat {@code 0.4} blocks for every mob alive, from a chicken to a ravager,
	 * and {@link net.minecraft.world.entity.ai.goal.FloatGoal} compares the water depth against it
	 * to decide whether to start pushing the animal toward the surface. That constant is the whole
	 * bug: a two-block-tall creature stepping into an ankle-deep stream cleared 0.4 immediately and
	 * began treading water with its feet on dry gravel.
	 * <p>
	 * Measuring against the creature's own eye height instead makes the rule the one a player
	 * already expects — <b>if its head is above the surface, it can walk</b> — and it scales itself,
	 * so a cave crawler still swims in water a saurian wades through, and a juvenile starts swimming
	 * sooner than the adult it will become.
	 * <p>
	 * Returned bare, with no lower bound, and that matters. Vanilla's own version reads
	 * {@code getEyeHeight() < 0.4 ? 0.0 : 0.4}, and the zero is not a special case for tiddlers — it
	 * is there because {@link #getFluidHeight} only measures the water <i>inside the entity's own
	 * bounding box</i>, so a creature shorter than the threshold can never reach it however deep it
	 * sinks. Clamping this to a 0.4 floor therefore does not make small creatures cautious, it makes
	 * them unable to swim at all: an earlier draft of this method did exactly that and left every
	 * insectoid, crustacean and cave crawler — a quarter of all creatures — walking along the bottom
	 * of lakes until they drowned. Eye height is always 85% of the box, so it is always reachable.
	 */
	@Override
	public double getFluidJumpThreshold() {
		return getEyeHeight();
	}

	/**
	 * Whether this creature is out of its depth, as against merely standing in water.
	 * <p>
	 * The single definition of "swimming" for everything that needs to know — the swim animation,
	 * and whether the animal should be trying to get out. Deliberately the same comparison vanilla's
	 * float goal makes against {@link #getFluidJumpThreshold()}, so an animal cannot be paddling for
	 * the surface while it is drawn wading, or drawn swimming while it walks along the bottom.
	 */
	public boolean isSwimmingDepth() {
		return isInWater() && getFluidHeight(FluidTags.WATER) > getFluidJumpThreshold();
	}

	@Override
	public boolean isPushable() {
		if (isCarcass()) return false;
		return super.isPushable();
	}

	@Override
	public void push(Entity entity) {
		if (isCarcass()) return;
		super.push(entity);
	}

	@Override
	public void push(double x, double y, double z) {
		if (isCarcass()) return;
		super.push(x, y, z);
	}

	public float clientActivityProgress(CreatureActivity activity, float tickDelta) {
		if (activity.isAmbient()) {
			clientActivity = activity;
			return 0f;
		}
		if (activity != clientActivity) {
			clientActivity = activity;
			clientActivityStart = tickCount;
		}
		float elapsed = (tickCount - clientActivityStart) + tickDelta;
		return Mth.clamp(elapsed / activity.durationTicks, 0f, 1f);
	}

	public CreatureAnimator getOrCreateAnimator() {
		BodyPlan plan = getBodyPlan();
		if (plan == null) return null;
		if (animator == null || animator.skeleton().plan != plan) {
			animator = new CreatureAnimator(plan);
		}
		return animator;
	}

	@Override
	public void addAdditionalSaveData(ValueOutput nbt) {
		super.addAdditionalSaveData(nbt);
		nbt.putString("Genome", entityData.get(GENOME_CODE));
		nbt.putBoolean("Tamed", isTamed());
		nbt.putBoolean("Domesticated", isDomesticated());
		nbt.putBoolean("Sitting", isSitting());
		nbt.putBoolean("Posing", isPosing());
		nbt.putBoolean("PoseWalking", isPoseWalking());
		nbt.putBoolean("Saddled", isSaddled());
		nbt.putFloat("Energy", energy);
		nbt.putInt("LifeTicks", lifeTicks);
		nbt.putInt("JuvenileTicks", juvenileTicks);
		nbt.putInt("HuntCooldown", huntCooldown);
		nbt.putInt("BreedCooldown", breedCooldown);
		nbt.putBoolean("Asleep", isAsleep());
		if (isCarcass()) {
			nbt.putBoolean("Carcass", true);
			nbt.putFloat("CarcassNutrition", carcassNutrition);
			nbt.putLong("CarcassBornAt", carcassBornAt);
			nbt.putBoolean("CarcassRotted", carcassRotted);
			nbt.putBoolean("Skeleton", isSkeleton());
		}
		String owner = entityData.get(OWNER);
		if (!owner.isEmpty()) {
			nbt.putString("Owner", owner);
		}
	}

	@Override
	public void readAdditionalSaveData(ValueInput nbt) {
		super.readAdditionalSaveData(nbt);
		if (nbt.getString("Genome").isPresent()) {
			Genome decoded = Genome.decode(nbt.getStringOr("Genome", ""));
			if (decoded != null) {
				setGenome(decoded);
			}
		}
		entityData.set(TAMED, nbt.getBooleanOr("Tamed", false));
		entityData.set(DOMESTICATED, nbt.getBooleanOr("Domesticated", false) && nbt.getBooleanOr("Tamed", false));
		entityData.set(SITTING, nbt.getBooleanOr("Sitting", false));
		if (nbt.getBooleanOr("Posing", false)) setPosing(true);
		// Both of these default to something other than the type's zero when the key is absent, which
		// is exactly what the *Or readers express.
		entityData.set(POSE_WALKING, nbt.getBooleanOr("PoseWalking", true));
		entityData.set(SADDLED, nbt.getBooleanOr("Saddled", false));
		energy = nbt.getFloatOr("Energy", 0.85f);
		lifeTicks = nbt.getIntOr("LifeTicks", 0);
		// Absent on anything saved before juveniles existed, and -1 is the right answer for those:
		// they were adults when the world was last open and should not be reborn as babies.
		juvenileTicks = nbt.getIntOr("JuvenileTicks", -1);
		updateGrowth();
		huntCooldown = nbt.getIntOr("HuntCooldown", 0);
		breedCooldown = nbt.getIntOr("BreedCooldown", 0);
		entityData.set(ASLEEP, nbt.getBooleanOr("Asleep", false));
		if (nbt.getBooleanOr("Carcass", false)) {
			becomeCarcass(nbt.getFloatOr("CarcassNutrition", 0f));
			// Bodies saved before decay followed the clock carry an elapsed count instead of an
			// anchor. Reading it as "this many ticks ago" puts them at the same age they were.
			carcassBornAt = nbt.getLongOr("CarcassBornAt",
					(level() == null ? 0L : level().getOverworldClockTime()) - nbt.getIntOr("CarcassTicks", 0));
			carcassRotted = nbt.getBooleanOr("CarcassRotted", carcassAge() >= CARCASS_ROT_TICKS);
			entityData.set(SKELETON, nbt.getBooleanOr("Skeleton", false));
		}
		entityData.set(OWNER, nbt.getStringOr("Owner", ""));
	}

	@Override
	protected void dropEquipment(ServerLevel serverLevel) {
		super.dropEquipment(serverLevel);
		if (isSaddled()) {
			spawnAtLocation(serverLevel, Items.SADDLE);
		}
	}

	@Override
	public boolean canBeLeashed() {
		return true;
	}
}
