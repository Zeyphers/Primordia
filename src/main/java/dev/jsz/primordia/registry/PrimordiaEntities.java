package dev.jsz.primordia.registry;

import dev.jsz.primordia.Primordia;
import dev.jsz.primordia.entity.CreatureEntity;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.SpawnLocationTypes;
import net.minecraft.entity.SpawnRestriction;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.world.Heightmap;

public final class PrimordiaEntities {
	public static final EntityType<CreatureEntity> CREATURE = Registry.register(
			Registries.ENTITY_TYPE,
			Primordia.id("creature"),
			EntityType.Builder.<CreatureEntity>create(CreatureEntity::new, SpawnGroup.CREATURE)
					.dimensions(0.9f, 1.0f)
					.maxTrackingRange(12)
					.trackingTickInterval(2)
					.build("creature"));

	private PrimordiaEntities() {
	}

	public static void register() {
		FabricDefaultAttributeRegistry.register(CREATURE, CreatureEntity.createCreatureAttributes());
		SpawnRestriction.register(CREATURE, SpawnLocationTypes.ON_GROUND, Heightmap.Type.MOTION_BLOCKING, CreatureEntity::canSpawn);
	}
}
