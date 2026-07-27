package dev.jsz.primordia.entity;

import dev.jsz.primordia.anim.CreatureAnimator;
import dev.jsz.primordia.body.AttackStyle;
import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanCache;
import dev.jsz.primordia.body.DietGroup;
import dev.jsz.primordia.body.LimbChain;
import dev.jsz.primordia.ecology.FoodSurvey;
import dev.jsz.primordia.entity.goal.CreatureAttackGoal;
import dev.jsz.primordia.entity.goal.CreatureTemptGoal;
import dev.jsz.primordia.entity.goal.FleeLargerCreatureGoal;
import dev.jsz.primordia.entity.goal.GrazeGoal;
import dev.jsz.primordia.entity.goal.LeaveWaterGoal;
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

	/** Speed below which the creature is considered standing rather than walking, in blocks/tick. */
	private static final double WALK_THRESHOLD_SQ = 0.0015 * 0.0015;
	/** Ticks between food-availability checks. Six seconds; the survey is not free. */
	private static final int NOURISHMENT_INTERVAL = 120;

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

	public static boolean canSpawn(EntityType<CreatureEntity> type, net.minecraft.world.ServerWorldAccess world,
	                               net.minecraft.entity.SpawnReason spawnReason, net.minecraft.util.math.BlockPos pos,
	                               net.minecraft.util.math.random.Random random) {
		net.minecraft.block.BlockState state = world.getBlockState(pos.down());
		return state.isSolidBlock(world, pos.down()) && pos.getY() >= world.getBottomY() + 4;
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
		builder.add(CLIMBING, false);
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
			} else {
				((ServerWorld) getWorld()).spawnParticles(ParticleTypes.SMOKE,
						getX(), getBodyY(0.9), getZ(), 5, 0.3, 0.3, 0.3, 0.02);
			}
			return ActionResult.CONSUME;
		}

		// Tamed from here on.
		if (stack.isOf(getFavouriteFood()) && loveTimer <= 0) {
			if (getWorld().isClient()) return ActionResult.SUCCESS;
			stack.decrementUnlessCreative(1, player);
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

		// Prey bolt when hurt. Higher priority than fighting: a skittish animal should be running
		// before it considers anything else.
		goalSelector.add(1, new EscapeDangerGoal(this, 1.6) {
			@Override
			public boolean canStart() {
				return getTemperament().fleesWhenHurt() && super.canStart();
			}
		});

		goalSelector.add(2, new CreatureTemptGoal(this, 1.15));
		goalSelector.add(2, new FleeLargerCreatureGoal(this, 1.35));
		goalSelector.add(3, new CreatureAttackGoal(this, 1.15));
		// Above wandering: a creature in water should commit to getting out rather than keep
		// picking random destinations across the lake.
		goalSelector.add(3, new LeaveWaterGoal(this, 1.1));
		goalSelector.add(4, new GrazeGoal(this));
		goalSelector.add(5, new WanderAroundFarGoal(this, 1.0));
		goalSelector.add(6, new LookAtEntityGoal(this, PlayerEntity.class, 8.0f));
		goalSelector.add(7, new LookAroundGoal(this));

		// Anything not skittish hits back at whatever hit it — including the player.
		targetSelector.add(1, new RevengeGoal(this) {
			@Override
			public boolean canStart() {
				return getTemperament().retaliates() && super.canStart();
			}
		});

		// Hunters go after creatures smaller than themselves.
		targetSelector.add(2, new ActiveTargetGoal<>(this, CreatureEntity.class, 10, true, false,
				other -> {
					if (!(other instanceof CreatureEntity prey) || prey == this) return false;
					BodyPlan mine = getBodyPlan();
					BodyPlan theirs = prey.getBodyPlan();
					if (mine == null || theirs == null) return false;
					return theirs.mass < mine.mass * 0.85f;
				}));

		// Hunters attack vanilla passive animals (Cows, Sheep, Pigs, Chickens, Rabbits, Horses, etc.)
		targetSelector.add(2, new ActiveTargetGoal<>(this, net.minecraft.entity.passive.AnimalEntity.class, 10, true, false,
				animal -> getTemperament().huntsUnprovoked() || (getGenome() != null && getGenome().raw(Gene.DIET) > 0.45f)));

		// Committed predators treat the player as prey without being provoked first.
		targetSelector.add(3, new ActiveTargetGoal<>(this, PlayerEntity.class, 10, true, false,
				target -> getTemperament().huntsUnprovoked()));
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

	@Override
	public void onDeath(DamageSource damageSource) {
		super.onDeath(damageSource);
		dev.jsz.primordia.ecology.SurvivalDrops.dropLoot(this, damageSource);
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

		tickNourishment();
		tickBreeding();

		// Timed activities expire on their own, so no goal has to remember to clear one.
		if (activityCooldown > 0) {
			activityCooldown--;
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
				other -> other != this && other.isAlive() && other.loveTimer > 0 && other.getGenome() != null);

		for (CreatureEntity partner : partners) {
			float dist = Mutation.distance(this.getGenome(), partner.getGenome());
			if (dist < 0.45f) {
				this.loveTimer = 0;
				partner.loveTimer = 0;

				ServerWorld world = (ServerWorld) getWorld();
				world.spawnParticles(ParticleTypes.HEART, getX(), getBodyY(0.9), getZ(), 14, 0.5, 0.5, 0.5, 0.1);
				world.spawnParticles(ParticleTypes.HEART, partner.getX(), partner.getBodyY(0.9), partner.getZ(), 14, 0.5, 0.5, 0.5, 0.1);

				Genome childGenome = Mutation.breed(this.getGenome(), partner.getGenome(), java.util.concurrent.ThreadLocalRandom.current());
				CreatureEntity child = PrimordiaEntities.CREATURE.create(world);
				if (child != null) {
					child.dataTracker.set(GENOME_CODE, childGenome.encode());
					child.refreshPositionAndAngles(
							(getX() + partner.getX()) * 0.5,
							(getY() + partner.getY()) * 0.5,
							(getZ() + partner.getZ()) * 0.5,
							getYaw(), getPitch());
					child.dataTracker.set(TAMED, true);
					child.dataTracker.set(OWNER, this.dataTracker.get(OWNER));
					world.spawnEntity(child);
				}
				break;
			}
		}
	}


	/**
	 * Applies the cost of being large in a place that cannot feed you.
	 * <p>
	 * A creature whose mass exceeds what the surrounding land can support slowly starves. This is
	 * what stops giants from simply existing everywhere: bulk has to be paid for by an environment
	 * productive enough to sustain it, so the biggest animals collect where the food is and thin
	 * out where it is not. Tamed creatures are exempt — their owner is presumed to be feeding them.
	 * <p>
	 * Deliberately slow and forgiving. The intent is a pressure that shapes where large animals
	 * live, not an execution timer.
	 */
	private void tickNourishment() {
		if (isTamed()) return;
		if (age % NOURISHMENT_INTERVAL != 0) return;

		BodyPlan plan = getBodyPlan();
		if (plan == null) return;

		DietGroup diet = getDietGroup();
		float prey = diet.hunts() ? FoodSurvey.preyDensity(getWorld(), this) : 0f;
		float capacity = FoodSurvey.carryingCapacity(getWorld(), getBlockPos(), diet, prey);

		// A margin of grace, so an animal merely passing through a barren patch is unaffected.
		if (plan.mass <= capacity * 1.35f) return;

		// Damage scales with how far over the limit it is, and is capped low enough that reaching
		// better ground is always a realistic escape.
		float excess = plan.mass / Math.max(0.01f, capacity * 1.35f);
		float damage = Math.min(2.0f, 0.35f * (excess - 1f));
		if (damage > 0.05f) {
			damage(getWorld().getDamageSources().starve(), damage);
		}
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
		// Hitbox encompasses legs and torso only — NOT the tail or neck.
		// Lateral width comes from how far the legs splay, and torso girth.
		float legSpanX = 0f;
		for (LimbChain leg : plan.legs) {
			legSpanX = Math.max(legSpanX, Math.abs(leg.restEffector.x));
		}
		// Full leg span (both sides) plus a small margin; torso width as a floor.
		float width = Math.max(0.50f, Math.max(legSpanX * 2.0f * 1.05f, plan.width() * 0.75f));
		// Height from ground to top of torso; no need to cover tail or head.
		float height = Math.max(0.50f, plan.hipHeight * 1.25f);

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
		nbt.putBoolean("Saddled", isSaddled());
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
		dataTracker.set(SADDLED, nbt.getBoolean("Saddled"));
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
