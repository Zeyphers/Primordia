package dev.jsz.primordia.ecology;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.entity.CreatureEntity;
import dev.jsz.primordia.entity.Temperament;
import dev.jsz.primordia.genome.Archetype;
import dev.jsz.primordia.genome.Genome;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.animal.equine.Llama;
import net.minecraft.world.entity.animal.polarbear.PolarBear;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.monster.skeleton.Skeleton;

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
			if (entity instanceof Mob mob) attach(mob);
		});
	}

	private static void attach(Mob mob) {
		float mass = massOf(mob.getType());
		if (mass <= 0f) return;

		if (mob instanceof Monster hostile) {
			hostile.targetSelector.addGoal(4, new NearestAttackableTargetGoal<>(hostile, CreatureEntity.class,
					10, true, false, (target, level) -> isAvailableTarget(target)));
			return;
		}

		if (mob instanceof IronGolem golem) {
			golem.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(golem, CreatureEntity.class,
					10, true, false, (target, level) -> isAvailableTarget(target)
					&& ((CreatureEntity) target).getTemperament() == Temperament.AGGRESSIVE));
			return;
		}

		boolean hunter = mob instanceof net.minecraft.world.entity.animal.fox.Fox
				|| mob instanceof net.minecraft.world.entity.animal.feline.Ocelot
				|| mob instanceof net.minecraft.world.entity.animal.feline.Cat
				|| mob instanceof net.minecraft.world.entity.animal.wolf.Wolf
				|| mob instanceof PolarBear;

		if (hunter) {
			mob.targetSelector.addGoal(5, new NearestAttackableTargetGoal<>(mob, CreatureEntity.class,
					10, true, false, (target, level) -> isAvailableTarget(target)
					&& !((CreatureEntity) target).isTamed()
					&& EnergyBudget.isWorthHunting(mass, massOf(target))));
		}

		if (!(mob instanceof net.minecraft.world.entity.PathfinderMob pathAware)) return;

		if (mob instanceof Llama || mob instanceof Goat || mob instanceof PolarBear) {
			pathAware.targetSelector.addGoal(2, new HurtByTargetGoal(pathAware));
		} else if (mob instanceof Animal || mob instanceof Villager) {
			final float preyMass = mass;
			pathAware.goalSelector.addGoal(3, new AvoidEntityGoal<>(pathAware, CreatureEntity.class,
					8.0f, 1.3, 1.6,
					creature -> isAvailableTarget(creature)
							&& (EnergyBudget.isWorthHunting(massOf(creature), preyMass)
							|| ((CreatureEntity) creature).getTemperament() == Temperament.AGGRESSIVE)));
		}

		if (mob instanceof Skeleton skeleton) {
			skeleton.targetSelector.addGoal(4, new NearestAttackableTargetGoal<>(skeleton, CreatureEntity.class,
					10, true, false, (target, level) -> isAvailableTarget(target)));
		}
	}

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

	public static float massOf(String entityId) {
		return MASS.getOrDefault(entityId, 0f);
	}

	public static float massOf(EntityType<?> type) {
		return massOf(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
	}
}
