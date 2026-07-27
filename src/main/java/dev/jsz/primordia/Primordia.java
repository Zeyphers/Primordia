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

		// Surface wildlife, at roughly the frequency of a vanilla pig or chicken.
		//
		// Weight is relative to every other mob eligible in the biome, so the number only means
		// anything next to vanilla's: sheep are 12, pigs and chickens 10, cows 8. This was 80 —
		// eight sheep's worth — which did not add creatures to the fauna so much as replace it.
		//
		// Group size is held low for a second reason on top of density: these are procedural
		// meshes, and the near LOD tier only budgets for eight creatures on screen at once. A
		// pack of seven spends that budget on a single herd and everything else drops a tier.
		BiomeModifications.addSpawn(
				BiomeSelectors.foundInOverworld(),
				SpawnGroup.CREATURE,
				PrimordiaEntities.CREATURE,
				10, 2, 4
		);

		// Deliberately no MONSTER registration. Registering the same entity in a second spawn
		// group is not "cave spawns" — the groups carry independent mob caps (~10 for CREATURE
		// against ~70 for MONSTER), so the creature spawned against both budgets at once, and
		// inherited hostile spawning behaviour: in darkness, underground, and topped up
		// continuously rather than placed once when the chunk generates. That was the bulk of
		// the flood. Creatures are animals, and spawn like animals.

		LOGGER.info("Primordia initialised");
	}
}
