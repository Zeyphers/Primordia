package dev.jsz.primordia.registry;

import dev.jsz.primordia.Primordia;
import dev.jsz.primordia.entity.CreatureEntity;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.levelgen.Heightmap;

public final class PrimordiaEntities {
	public static final ResourceKey<EntityType<?>> CREATURE_KEY = ResourceKey.create(
			Registries.ENTITY_TYPE, Primordia.id("creature"));

	public static final EntityType<CreatureEntity> CREATURE = Registry.register(
			BuiltInRegistries.ENTITY_TYPE,
			CREATURE_KEY,
			EntityType.Builder.<CreatureEntity>of(CreatureEntity::new, MobCategory.CREATURE)
					.sized(0.9f, 1.0f)
					.clientTrackingRange(12)
					.updateInterval(2)
					.build(CREATURE_KEY));

	private PrimordiaEntities() {
	}

	public static void register() {
		FabricDefaultAttributeRegistry.register(CREATURE, CreatureEntity.createCreatureAttributes());
		SpawnPlacements.register(CREATURE, SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING, CreatureEntity::canSpawn);
	}
}
