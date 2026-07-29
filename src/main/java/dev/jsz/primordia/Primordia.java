package dev.jsz.primordia;

import dev.jsz.primordia.command.PrimordiaCommands;
import dev.jsz.primordia.ecology.region.EcologyTicker;
import dev.jsz.primordia.lab.NameLineagePayload;
import dev.jsz.primordia.lab.SpawnSpeciesPayload;
import dev.jsz.primordia.registry.PrimordiaBlockEntities;
import dev.jsz.primordia.registry.PrimordiaBlocks;
import dev.jsz.primordia.registry.PrimordiaEntities;
import dev.jsz.primordia.registry.PrimordiaItemGroup;
import dev.jsz.primordia.registry.PrimordiaItems;
import dev.jsz.primordia.registry.PrimordiaScreenHandlers;
import net.fabricmc.api.ModInitializer;
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
		// Blocks before block entities: the entity types name the blocks they are valid for, and
		// the blocks reach their type back through a supplier so neither can be first by accident.
		PrimordiaBlocks.register();
		PrimordiaBlockEntities.register();
		PrimordiaScreenHandlers.register();
		PrimordiaItems.register();
		// After the items and blocks it lists, since it holds references to them.
		PrimordiaItemGroup.register();
		PrimordiaCommands.register();
		NameLineagePayload.register();
		SpawnSpeciesPayload.register();
		EcologyTicker.register();

		// Deliberately no BiomeModifications.addSpawn. Creatures are no longer placed by the
		// vanilla spawner at all.
		//
		// The vanilla spawner invents an animal per spawn attempt, each rolling its own random
		// archetype, so a valley's fauna was an independent random draw per individual — as likely
		// to come out as four predators with nothing to eat as anything that could sustain itself.
		// No amount of restraint in the creatures' behaviour rescues a composition that was never
		// viable.
		//
		// Population now lives in the region ledger, and creatures are placed from it by
		// RegionMaterialiser: the record says how many of which lineage live here, and entities are
		// a rendering of that. See docs/ECOLOGY.md.
		//
		// The old note about not registering a second MONSTER spawn group still applies if anyone
		// is tempted: the groups carry independent mob caps, so the entity spawned against both
		// budgets at once and inherited hostile top-up behaviour.

		LOGGER.info("Primordia initialised");
	}
}
