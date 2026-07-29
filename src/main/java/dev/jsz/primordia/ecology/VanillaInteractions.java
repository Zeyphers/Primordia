package dev.jsz.primordia.ecology;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.entity.CreatureEntity;
import dev.jsz.primordia.entity.Temperament;
import dev.jsz.primordia.genome.Archetype;
import dev.jsz.primordia.genome.Genome;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.ai.goal.FleeEntityGoal;
import net.minecraft.entity.ai.goal.RevengeGoal;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.GoatEntity;
import net.minecraft.entity.passive.LlamaEntity;
import net.minecraft.entity.passive.PolarBearEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.mob.SkeletonEntity;

import java.util.HashMap;
import java.util.Map;

/**
 * Teaches vanilla mobs what a Primordia creature is.
 * <p>
 * Without this the two faunas ignore each other entirely: creatures hunt cows, and nothing vanilla
 * ever reacts to a creature at all. A wolf walks past something twice its size; a herd of sheep
 * grazes beside a predator. The world reads as two ecosystems drawn on the same map.
 * <p>
 * <b>Every rule here is the mod's own rule.</b> Who eats whom is decided by
 * {@link EnergyBudget#isWorthHunting} — the same size window the creatures use on each other, and
 * the same one the regional simulation uses off-screen — with vanilla mobs given a nominal mass on
 * Primordia's scale. Nothing has a bespoke per-mob threshold, so a change to the window moves both
 * faunas together and they cannot drift apart.
 * <p>
 * Goals are added on entity load rather than through a mixin on {@code initGoals}. It needs an
 * access widener for two fields and leaves every vanilla method body alone, where a mixin would
 * have to survive both version changes and every other mod injecting into the same method.
 */
public final class VanillaInteractions {

	/**
	 * Vanilla mobs' masses on Primordia's scale, where a creature runs from about 0.02 to 1.0.
	 * <p>
	 * Estimated from the animal rather than from its hitbox — a chicken and a rabbit are the same
	 * size in blocks and the same size as prey, which is what this is for. Anything absent is left
	 * out of the food web entirely, which is the right answer for fish, bats and squid.
	 */
	private static final Map<String, Float> MASS = new HashMap<>();

	static {
		MASS.put("minecraft:chicken", 0.03f);
		MASS.put("minecraft:rabbit", 0.03f);
		MASS.put("minecraft:cat", 0.05f);
		MASS.put("minecraft:ocelot", 0.06f);
		MASS.put("minecraft:fox", 0.06f);
		MASS.put("minecraft:wolf", 0.12f);
		MASS.put("minecraft:spider", 0.15f);
		MASS.put("minecraft:cave_spider", 0.08f);
		MASS.put("minecraft:sheep", 0.18f);
		MASS.put("minecraft:pig", 0.20f);
		MASS.put("minecraft:goat", 0.22f);
		MASS.put("minecraft:zombie", 0.25f);
		MASS.put("minecraft:husk", 0.25f);
		MASS.put("minecraft:drowned", 0.25f);
		MASS.put("minecraft:zombie_villager", 0.25f);
		MASS.put("minecraft:skeleton", 0.25f);
		MASS.put("minecraft:stray", 0.25f);
		MASS.put("minecraft:villager", 0.25f);
		MASS.put("minecraft:pillager", 0.25f);
		MASS.put("minecraft:vindicator", 0.28f);
		MASS.put("minecraft:cow", 0.30f);
		MASS.put("minecraft:mooshroom", 0.30f);
		MASS.put("minecraft:llama", 0.30f);
		MASS.put("minecraft:donkey", 0.32f);
		MASS.put("minecraft:mule", 0.38f);
		MASS.put("minecraft:horse", 0.40f);
		MASS.put("minecraft:panda", 0.45f);
		MASS.put("minecraft:polar_bear", 0.45f);
		MASS.put("minecraft:hoglin", 0.55f);
		MASS.put("minecraft:ravager", 0.80f);
		MASS.put("minecraft:iron_golem", 0.90f);
		MASS.put("minecraft:warden", 0.95f);
	}

	private VanillaInteractions() {
	}

	public static void register() {
		ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			if (entity instanceof CreatureEntity) return;
			if (entity instanceof MobEntity mob) attach(mob);
		});
	}

	private static void attach(MobEntity mob) {
		float mass = massOf(mob.getType());
		if (mass <= 0f) return;

		if (mob instanceof HostileEntity hostile) {
			// Hostiles attack anything alive, so size does not enter into it. Tamed creatures are
			// included deliberately — a zombie does not care whose animal it is.
			hostile.targetSelector.add(4, new ActiveTargetGoal<>(hostile, CreatureEntity.class,
					10, true, false, VanillaInteractions::isAvailableTarget));
			return;
		}

		if (mob instanceof IronGolemEntity golem) {
			// A golem is a guard, not a predator: it goes after what threatens the village rather
			// than after anything it could beat.
			golem.targetSelector.add(3, new ActiveTargetGoal<>(golem, CreatureEntity.class,
					10, true, false, target -> isAvailableTarget(target)
					&& ((CreatureEntity) target).getTemperament() == Temperament.AGGRESSIVE));
			return;
		}

		// Everything else is an animal, and which side of the food web it is on depends on its size
		// against the creature's — the same question the creatures ask about each other.
		boolean hunter = mob instanceof net.minecraft.entity.passive.FoxEntity
				|| mob instanceof net.minecraft.entity.passive.OcelotEntity
				|| mob instanceof net.minecraft.entity.passive.CatEntity
				|| mob instanceof net.minecraft.entity.passive.WolfEntity
				|| mob instanceof PolarBearEntity;

		if (hunter) {
			mob.targetSelector.add(5, new ActiveTargetGoal<>(mob, CreatureEntity.class,
					10, true, false, target -> isAvailableTarget(target)
					// Wild only. A predator picking off a player's tamed animals is not an
					// ecosystem, it is a grief.
					&& !((CreatureEntity) target).isTamed()
					&& EnergyBudget.isWorthHunting(mass, massOf(target))));
		}

		// Both of the goals below steer with a navigator, which only a PathAwareEntity has. Every
		// animal and villager is one; the check is here because MobEntity in general is not.
		if (!(mob instanceof net.minecraft.entity.mob.PathAwareEntity pathAware)) return;

		// A defender fights back rather than running, and everything else runs.
		if (mob instanceof LlamaEntity || mob instanceof GoatEntity || mob instanceof PolarBearEntity) {
			pathAware.targetSelector.add(2, new RevengeGoal(pathAware));
		} else if (mob instanceof AnimalEntity || mob instanceof VillagerEntity) {
			final float preyMass = mass;
			pathAware.goalSelector.add(3, new FleeEntityGoal<>(pathAware, CreatureEntity.class,
					8.0f, 1.3, 1.6,
					// Flee what could actually eat you. A cow has no reason to run from something
					// the size of a rabbit, and every reason to run from the thing that has been
					// eating rabbits.
					creature -> isAvailableTarget(creature)
							&& (EnergyBudget.isWorthHunting(massOf(creature), preyMass)
							|| ((CreatureEntity) creature).getTemperament() == Temperament.AGGRESSIVE)));
		}

		if (mob instanceof SkeletonEntity skeleton) {
			// Shoots at creatures the way it shoots at anything else that moves.
			skeleton.targetSelector.add(4, new ActiveTargetGoal<>(skeleton, CreatureEntity.class,
					10, true, false, VanillaInteractions::isAvailableTarget));
		}
	}

	/**
	 * Whether this creature is something vanilla should react to at all.
	 * <p>
	 * A carcass is already dead and a cave dweller is forty blocks down — neither is anything a
	 * surface mob should be pathing toward. The habitat check mirrors the one the regional
	 * simulation makes when deciding who can eat whom, so the two agree about who ever meets.
	 */
	private static boolean isAvailableTarget(LivingEntity entity) {
		if (!(entity instanceof CreatureEntity creature)) return false;
		if (!creature.isAlive() || creature.isCarcass() || creature.isPosing()) return false;
		Genome genome = creature.getGenome();
		return genome != null && !Archetype.isSubterranean(genome);
	}

	private static float massOf(LivingEntity entity) {
		if (!(entity instanceof CreatureEntity creature)) return 0.2f;
		BodyPlan plan = creature.getBodyPlan();
		return plan == null ? 0.2f : plan.mass;
	}

	/**
	 * A mob's nominal mass on Primordia's scale, or zero if it is outside the food web.
	 * <p>
	 * Keyed by registry id rather than by {@code EntityType} constant so that the table is data and
	 * not code. Two things follow: it can be read without the game's registries existing, which is
	 * what lets the food web be tested at all — and a mob from another mod can be given a place in
	 * it later without this class needing to compile against that mod.
	 */
	public static float massOf(String entityId) {
		return MASS.getOrDefault(entityId, 0f);
	}

	public static float massOf(EntityType<?> type) {
		return massOf(net.minecraft.registry.Registries.ENTITY_TYPE.getId(type).toString());
	}
}
