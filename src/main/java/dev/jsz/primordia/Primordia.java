package dev.jsz.primordia;

import dev.jsz.primordia.editor.EditorServer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import dev.jsz.primordia.command.PrimordiaCommands;
import dev.jsz.primordia.ecology.VanillaInteractions;
import dev.jsz.primordia.ecology.region.EcologyTicker;
import dev.jsz.primordia.lab.NameLineagePayload;
import dev.jsz.primordia.lab.SpawnSpeciesPayload;
import dev.jsz.primordia.registry.PrimordiaBlockEntities;
import dev.jsz.primordia.registry.PrimordiaBlocks;
import dev.jsz.primordia.registry.PrimordiaEntities;
import dev.jsz.primordia.registry.PrimordiaItemGroup;
import dev.jsz.primordia.registry.PrimordiaItems;
import dev.jsz.primordia.registry.PrimordiaScreenHandlers;
import dev.jsz.primordia.registry.PrimordiaSounds;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Primordia implements ModInitializer {
	public static final String MOD_ID = "primordia";

	/**
	 * Whether the developer tools are available — everything under {@code /primordia debug}.
	 * <p>
	 * <b>Set this to false before a public release.</b> It is one constant on purpose: anything that
	 * exists to test the mod rather than to play it goes behind this subcommand, so switching the
	 * whole lot off is a one-line change and never a hunt through the command tree. When it is off
	 * the node is not registered at all, so it does not appear in tab completion either.
	 * <p>
	 * Overridable at launch with {@code -Dprimordia.debug=false}, which is there so both states can
	 * be checked without a rebuild.
	 */
	public static final boolean DEBUG_TOOLS =
			Boolean.parseBoolean(System.getProperty("primordia.debug", "true"));
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		// The editor's HTTP listener runs on a non-daemon thread of the JDK's own making, which
		// setExecutor does not reach. Left running it holds the JVM open after Minecraft has closed,
		// and the shutdown watchdog eventually kills the process and files a crash report. Stopping
		// it here is what lets the game exit; the command can always start it again.
		// Dedicated servers only. On a client this event also fires on leaving a world for the main
		// menu, which would shut the editor out from under someone still using it; the client has its
		// own hook on actually quitting (see PrimordiaClient).
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (server.isDedicatedServer()) EditorServer.stop();
		});
		PrimordiaEntities.register();
		PrimordiaSounds.register();
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
		dev.jsz.primordia.lab.GuideDataSyncPayload.register();
		dev.jsz.primordia.splice.SpliceSyncPayload.register();
		dev.jsz.primordia.splice.SpliceRequestPayload.register();
		dev.jsz.primordia.sound.CreatureVoicePayload.register();
		EcologyTicker.register();
		VanillaInteractions.register();

		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			net.minecraft.server.level.ServerPlayer player = handler.getPlayer();
			dev.jsz.primordia.lab.PlayerGuideData global = dev.jsz.primordia.lab.PlayerGuideData.get((net.minecraft.server.level.ServerLevel) player.level());
			// The guide record is created lazily on first read, so its absence right now is the
			// signal that this player has never joined this world under this mod before.
			boolean firstJoin = !global.hasGuide(player.getUUID());
			dev.jsz.primordia.lab.GuideData data = global.getGuide(player.getUUID());
			net.minecraft.nbt.CompoundTag payloadData = new net.minecraft.nbt.CompoundTag();
			data.writeInto(payloadData);
			sender.sendPacket(new dev.jsz.primordia.lab.GuideDataSyncPayload(payloadData));
			// After the guide, because the slot count is derived from it and `refresh` trims to it.
			dev.jsz.primordia.splice.Splicing.refresh(player);

			if (firstJoin) {
				net.minecraft.world.item.ItemStack guide =
						new net.minecraft.world.item.ItemStack(PrimordiaItems.FIELD_GUIDE);
				if (!player.getInventory().add(guide)) {
					player.drop(guide, false);
				}
			}
		});

		net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			dev.jsz.primordia.lab.PlayerGuideData global = dev.jsz.primordia.lab.PlayerGuideData.get((net.minecraft.server.level.ServerLevel) newPlayer.level());
			dev.jsz.primordia.lab.GuideData data = global.getGuide(newPlayer.getUUID());
			net.minecraft.nbt.CompoundTag payloadData = new net.minecraft.nbt.CompoundTag();
			data.writeInto(payloadData);
			net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(newPlayer, new dev.jsz.primordia.lab.GuideDataSyncPayload(payloadData));
			// Attribute modifiers do not survive a respawn, so a loadout that is merely saved is a
			// loadout that quietly stops working the first time the player dies.
			dev.jsz.primordia.splice.Splicing.refresh(newPlayer);

			var inv = newPlayer.getInventory();
			boolean hasGuide = false;
			for (int i = 0; i < inv.getContainerSize(); i++) {
				if (inv.getItem(i).is(PrimordiaItems.FIELD_GUIDE)) {
					hasGuide = true;
					break;
				}
			}
			if (!hasGuide) {
				net.minecraft.world.item.ItemStack guide = new net.minecraft.world.item.ItemStack(PrimordiaItems.FIELD_GUIDE);
				if (!newPlayer.getInventory().add(guide)) {
					newPlayer.drop(guide, false);
				}
			}
		});

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
		// a rendering of that. See MD/ECOLOGY.md.
		//
		// The old note about not registering a second MONSTER spawn group still applies if anyone
		// is tempted: the groups carry independent mob caps, so the entity spawned against both
		// budgets at once and inherited hostile top-up behaviour.

		LOGGER.info("Primordia initialised");
	}
}
