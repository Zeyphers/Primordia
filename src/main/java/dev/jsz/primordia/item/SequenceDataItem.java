package dev.jsz.primordia.item;

import dev.jsz.primordia.lab.SampleData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.util.List;

/**
 * The sequencer's output: a genome that has been read but not interpreted.
 * <p>
 * Deliberately illegible. The item holds the complete genome — nothing is lost between here and the
 * decoder — but shows the player a wall of base pairs instead of any of it, so the middle of the
 * pipeline has a shape: sequencing tells you that you have the data, decoding tells you what it
 * says. Collapsing the two would leave the sequencer with no reason to be a separate machine.
 * <p>
 * The garble is derived from the genome rather than randomised per frame, so a given specimen's
 * data always looks the same and two different specimens always look different. A player who
 * notices that much has learned something true about the item.
 */
public class SequenceDataItem extends Item {

	private static final char[] BASES = {'A', 'C', 'G', 'T'};
	private static final int PREVIEW_ROWS = 3;
	private static final int PREVIEW_COLUMNS = 24;

	public SequenceDataItem(Properties settings) {
		super(settings);
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Named as the file it is pretending to be — {@code Raw_Sequence_Data_A3F91C.fastq} — rather
	 * than as an item. FASTQ is the format sequencer output actually arrives in, and giving each
	 * read the specimen's lineage in its filename means a chest full of them is legible at a
	 * glance: duplicates of one species sort together, and a new lineage is visibly a new file.
	 * <p>
	 * A blank one keeps the generic name, since there is no specimen to name it after.
	 */
	@Override
	public Component getName(ItemStack stack) {
		SampleData data = SampleData.get(stack);
		if (data == null) return super.getName(stack);
		return Component.literal("Raw_Sequence_Data_" + data.lineageHex() + ".fastq");
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, net.minecraft.world.item.component.TooltipDisplay display, java.util.function.Consumer<Component> tooltipAdder, TooltipFlag flag) {
		SampleData data = SampleData.get(stack);
		if (data == null) {
			tooltipAdder.accept(Component.literal("Corrupt read — no recoverable sequence")
					.withStyle(ChatFormatting.DARK_RED, ChatFormatting.ITALIC));
			return;
		}

		tooltipAdder.accept(Component.literal("Unprocessed genome data").withStyle(ChatFormatting.DARK_GREEN));
		for (Component row : garble(data)) {
			tooltipAdder.accept(row);
		}
		tooltipAdder.accept(Component.literal("Requires a Genome Decoder to interpret.")
				.withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
	}

	/** A stable pseudo-sequence for this specimen, so the same data always reads the same. */
	private static List<Component> garble(SampleData data) {
		long state = data.genome().seed() ^ data.genome().lineage();
		List<Component> rows = new java.util.ArrayList<>(PREVIEW_ROWS);
		StringBuilder row = new StringBuilder(PREVIEW_COLUMNS);

		for (int r = 0; r < PREVIEW_ROWS; r++) {
			row.setLength(0);
			for (int c = 0; c < PREVIEW_COLUMNS; c++) {
				// xorshift: cheap, deterministic, and good enough to look like noise.
				state ^= state << 13;
				state ^= state >>> 7;
				state ^= state << 17;
				row.append(BASES[(int) Math.floorMod(state, 4L)]);
			}
			rows.add(Component.literal(row.toString()).withStyle(ChatFormatting.DARK_GREEN));
		}
		return rows;
	}
}
