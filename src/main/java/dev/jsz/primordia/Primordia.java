package dev.jsz.primordia;

import dev.jsz.primordia.command.PrimordiaCommands;
import dev.jsz.primordia.registry.PrimordiaEntities;
import dev.jsz.primordia.registry.PrimordiaItems;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Primordia implements ModInitializer {
	public static final String MOD_ID = "primordia";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static Identifier id(String path) {
		return Identifier.of(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		PrimordiaEntities.register();
		PrimordiaItems.register();
		PrimordiaCommands.register();

		// Surface passive/wild spawns — high density (80 weight, 3-7 group)
		BiomeModifications.addSpawn(
				BiomeSelectors.foundInOverworld(),
				SpawnGroup.CREATURE,
				PrimordiaEntities.CREATURE,
				80, 3, 7
		);

		// Cave & Underground spawns — (60 weight, 2-6 group)
		BiomeModifications.addSpawn(
				BiomeSelectors.foundInOverworld(),
				SpawnGroup.MONSTER,
				PrimordiaEntities.CREATURE,
				60, 2, 6
		);

		LOGGER.info("Primordia initialised");
	}
}
