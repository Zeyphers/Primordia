package dev.jsz.primordia.block;

import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.lab.DecodeAccuracy;
import dev.jsz.primordia.lab.GenomeLibrary;
import dev.jsz.primordia.registry.PrimordiaBlockEntities;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * Where finished reports are filed, and where the world's accumulated knowledge can be read back.
 * <p>
 * The storage half is ordinary. The useful half is the index: sneaking on the bank prints what this
 * world knows and how well it knows it, which is the only place the {@link GenomeLibrary} — an
 * invisible number that silently governs every decode — becomes something the player can actually
 * look at. Without it the progression happens but cannot be observed, and a system that improves
 * without telling anyone is indistinguishable from one that does nothing.
 */
public class GenomeBankBlockEntity extends SimpleContainerBlockEntity {

	public static final int SIZE = 27;
	/** How many species the index lists before it summarises the rest. */
	private static final int INDEX_LIMIT = 12;

	public GenomeBankBlockEntity(BlockPos pos, BlockState state) {
		super(PrimordiaBlockEntities.GENOME_BANK, pos, state, SIZE);
	}

	/** Prints what this world has on file. */
	public static void printIndex(ServerWorld world, PlayerEntity player) {
		GenomeLibrary library = GenomeLibrary.get(world);
		List<GenomeLibrary.Entry> entries = library.all();

		player.sendMessage(Text.literal("── Genome Bank ──")
				.formatted(Formatting.AQUA, Formatting.BOLD), false);
		if (entries.isEmpty()) {
			player.sendMessage(Text.literal("  Nothing on file. Decode a sequence to begin a record.")
					.formatted(Formatting.DARK_GRAY), false);
			return;
		}
		player.sendMessage(Text.literal("  " + library.speciesKnown() + " lineage(s) on file")
				.formatted(Formatting.GRAY), false);

		int shown = 0;
		for (GenomeLibrary.Entry entry : entries) {
			if (shown++ >= INDEX_LIMIT) break;
			// The level a *further* specimen would decode at, which is what the player wants to
			// know: it is the answer to "is it worth going out for another one of these".
			DecodeAccuracy level = DecodeAccuracy.resolve(entry.decoded, 1f);
			int needed = level.decodesUntilNextLevel(entry.decoded);

			Genome representative = Genome.decode(entry.representative);
			String label = entry.label.isEmpty() ? "??????" : entry.label;
			String generation = representative == null ? "" : " · gen " + entry.latestGeneration;

			player.sendMessage(Text.literal("  [" + label + "] ").formatted(Formatting.AQUA)
					.append(Text.literal(entry.decoded + " decoded" + generation)
							.formatted(Formatting.WHITE))
					.append(Text.literal(" · " + level.label).formatted(level.colour))
					.append(needed > 0
							? Text.literal(" (" + needed + " to improve)").formatted(Formatting.DARK_GRAY)
							: Text.literal(" (complete)").formatted(Formatting.DARK_AQUA)), false);
		}
		if (entries.size() > INDEX_LIMIT) {
			player.sendMessage(Text.literal("  …and " + (entries.size() - INDEX_LIMIT) + " more")
					.formatted(Formatting.DARK_GRAY), false);
		}
	}

	@Override
	public Text getDisplayName() {
		return Text.translatable("container.primordia.genome_bank");
	}

	@Override
	public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
		return GenericContainerScreenHandler.createGeneric9x3(syncId, playerInventory, this);
	}
}
