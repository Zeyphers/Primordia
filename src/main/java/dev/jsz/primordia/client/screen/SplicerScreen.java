package dev.jsz.primordia.client.screen;

import dev.jsz.primordia.Primordia;
import dev.jsz.primordia.block.SplicerBlockEntity;
import dev.jsz.primordia.PrimordiaClient;
import dev.jsz.primordia.lab.GuideData;
import dev.jsz.primordia.screen.SplicerLayout;
import dev.jsz.primordia.screen.SplicerMenu;
import dev.jsz.primordia.splice.SpliceBranch;
import dev.jsz.primordia.splice.SpliceDepth;
import dev.jsz.primordia.splice.SpliceEffects;
import dev.jsz.primordia.splice.SpliceTree;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.List;

/**
 * The splicing bench: pick a trait to isolate, and the machine bottles it.
 * <p>
 * The panel is a texture rather than a stack of fills — see {@code design/gui/splicer_art.py}, which
 * paints it from the same layout file this class takes its coordinates from. What is drawn in code
 * is only what moves: the row states, and the line that runs from the chosen row along the rail to
 * the output slot as the machine works. That line <i>is</i> the progress bar, which is worth more
 * than a bar in a corner would be — it says which trait is being isolated as well as how far along
 * it is.
 * <p>
 * Every row is computed on the client from {@code GuideData}, which is already synchronised. See
 * {@link SpliceTree}: nothing about the tree needs the server's opinion, so the screen can be honest
 * about a locked branch without asking anybody.
 */
public class SplicerScreen extends AbstractContainerScreen<SplicerMenu> {

	private static final Identifier BACKGROUND = Primordia.id("textures/gui/splicer.png");

	private static final int INK = 0xFFD8D0C0;
	private static final int INK_FAINT = 0xFF8A8172;
	private static final int INK_LOCKED = 0xFF6A6255;
	private static final int GREEN = 0xFF6FD08C;
	private static final int LED_IDLE = 0xFF4A8FD0;
	private static final int LED_BUSY = 0xFFE8C64A;
	private static final int LED_DONE = 0xFF5FE07A;
	private static final int LED_DARK = 0xFF241F1B;
	private static final int ROW_OPEN = 0x33FFFFFF;
	private static final int ROW_HOVER = 0x556FD08C;

	public SplicerScreen(SplicerMenu handler, Inventory inventory, Component title) {
		super(handler, inventory, title, SplicerLayout.WIDTH, SplicerLayout.HEIGHT);
		titleLabelX = SplicerLayout.TITLE_X;
		titleLabelY = SplicerLayout.TITLE_Y;
		inventoryLabelX = SplicerLayout.INV_X;
		inventoryLabelY = SplicerLayout.INV_Y - 11;
	}

	/** The row under the cursor, or -1. Gutters between rows deliberately hit nothing. */
	private int rowAt(double mouseX, double mouseY) {
		int x = (width - imageWidth) / 2;
		int y = (height - imageHeight) / 2;
		for (int i = 0; i < SpliceBranch.VALUES.length; i++) {
			int top = y + SplicerLayout.rowTop(i);
			if (mouseX >= x + SplicerLayout.ROW_X
					&& mouseX < x + SplicerLayout.ROW_X + SplicerLayout.ROW_W
					&& mouseY >= top && mouseY < top + SplicerLayout.ROW_H) {
				return i;
			}
		}
		return -1;
	}

	private static boolean isOpen(GuideData guide, SpliceBranch branch) {
		return SpliceTree.reached(guide, branch) != null
				&& SpliceTree.bestDonor(guide, branch) != null;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		super.extractBackground(context, mouseX, mouseY, delta);
		int x = (width - imageWidth) / 2;
		int y = (height - imageHeight) / 2;

		context.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, x, y, 0, 0,
				imageWidth, imageHeight, SplicerLayout.SHEET, SplicerLayout.SHEET);

		GuideData guide = PrimordiaClient.getClientGuideData();
		int hovered = rowAt(mouseX, mouseY);
		boolean busy = menu.running();

		for (int i = 0; i < SpliceBranch.VALUES.length; i++) {
			SpliceBranch branch = SpliceBranch.VALUES[i];
			SpliceDepth reached = SpliceTree.reached(guide, branch);
			SpliceTree.Donor best = SpliceTree.bestDonor(guide, branch);
			boolean open = reached != null && best != null;

			int top = y + SplicerLayout.rowTop(i);
			int left = x + SplicerLayout.ROW_X;
			if (open) {
				context.fill(left + 1, top + 1, left + SplicerLayout.ROW_W - 1,
						top + SplicerLayout.ROW_H - 1,
						hovered == i && !busy ? ROW_HOVER : ROW_OPEN);
			}

			StringBuilder pips = new StringBuilder();
			for (SpliceDepth depth : SpliceDepth.VALUES) {
				pips.append(SpliceTree.unlocked(guide, branch, depth) ? "●" : "○");
			}
			context.text(font, Component.literal(pips.toString()), left + 5, top + 3,
					open ? GREEN : INK_LOCKED, false);
			context.text(font, Component.literal(branch.title), left + 5, top + 12,
					open ? INK : INK_LOCKED, false);

			String right;
			if (!open) {
				SpliceDepth first = SpliceDepth.VALUES[0];
				right = SpliceTree.progress(guide, branch, first) + " / " + first.required + " studied";
			} else {
				right = best.label() + "   " + String.format("%.2f",
						Math.min(best.potency(), reached.cap));
			}
			context.text(font, Component.literal(right),
					left + SplicerLayout.ROW_W - 6 - font.width(right), top + 12,
					open ? INK : INK_LOCKED, false);
		}

		if (busy) drawRail(context, x, y);
		drawLamp(context, x, y);
	}

	/**
	 * The status lamp above the output slot.
	 * <p>
	 * Three states and no text, so it can be read from across a room at a glance: blue for a bench
	 * standing by, blinking amber while it works, and steady green when there is something in the
	 * slot to collect. The blink is driven off the machine's own tick rather than the client clock,
	 * so a lamp that is flashing is a machine that is genuinely still counting.
	 */
	private void drawLamp(GuiGraphicsExtractor context, int x, int y) {
		boolean busy = menu.running();
		boolean done = !menu.slots.get(0).getItem().isEmpty();

		int colour;
		if (busy) {
			colour = (menu.ticks() / 5) % 2 == 0 ? LED_BUSY : LED_DARK;
		} else {
			colour = done ? LED_DONE : LED_IDLE;
		}

		int lx = x + SplicerLayout.ledX();
		int ly = y + SplicerLayout.ledY();
		int size = SplicerLayout.LED_SIZE;
		// A dark bezel so an unlit lamp still reads as a lamp rather than as nothing.
		context.fill(lx - 1, ly - 1, lx + size + 1, ly + size + 1, LED_DARK);
		context.fill(lx, ly, lx + size, ly + size, colour);
	}

	/**
	 * The line from the running row, along the spine, to the output slot — filled as far as the
	 * machine has got.
	 * <p>
	 * Walked as three legs with a running length rather than drawn as a fraction of each, so the
	 * fill moves at one speed the whole way instead of pausing at every corner.
	 */
	private void drawRail(GuiGraphicsExtractor context, int x, int y) {
		int row = runningRow();
		if (row < 0) return;

		int spine = x + SplicerLayout.spineX();
		int fromX = x + SplicerLayout.ROW_X + SplicerLayout.ROW_W;
		int fromY = y + SplicerLayout.rowCentre(row);
		int toY = y + SplicerLayout.outputCentreY();
		int toX = x + SplicerLayout.OUTPUT_X;

		int legA = Math.abs(spine - fromX);
		int legB = Math.abs(toY - fromY);
		int legC = Math.abs(toX - spine);
		float total = Math.max(1, legA + legB + legC);
		float travelled = total * menu.progress();

		float a = Math.min(travelled, legA);
		context.fill(fromX, fromY - 1, fromX + (int) a, fromY + 2, GREEN);

		if (travelled > legA) {
			float b = Math.min(travelled - legA, legB);
			int step = toY >= fromY ? 1 : -1;
			int end = fromY + (int) (b * step);
			context.fill(spine - 1, Math.min(fromY, end) - 1, spine + 2, Math.max(fromY, end) + 2, GREEN);

			if (travelled > legA + legB) {
				float c = Math.min(travelled - legA - legB, legC);
				context.fill(spine, toY - 1, spine + (int) c, toY + 2, GREEN);
			}
		}
	}

	/**
	 * Which row the bench is working on.
	 * <p>
	 * Read off the menu, which reads it off the machine. Guessing from the row the player last
	 * clicked worked only while the screen stayed open: step out of range, the menu closes, and on
	 * reopening the client knew nothing and drew no line at all.
	 */
	private int runningRow() {
		SpliceBranch branch = menu.runningBranch();
		return branch == null ? -1 : branch.ordinal();
	}

	@Override
	protected void extractLabels(GuiGraphicsExtractor context, int mouseX, int mouseY) {
		context.text(font, title, titleLabelX, titleLabelY, INK, false);

		// Beside the title rather than above the rows. It is a status line, and status belongs in
		// the header with the thing it is the status of — over the rows it read as a column heading
		// and pushed the first row down for no reason.
		Component status = Component.literal(
				menu.running() ? "Isolating…" : "Choose a trait to isolate");
		context.text(font, status, titleLabelX + font.width(title) + 8, titleLabelY, INK_FAINT, false);

		// The countdown, under the slot the serum will land in.
		if (menu.running()) {
			int left = Math.max(0, SplicerBlockEntity.RUN_TICKS - menu.ticks());
			int seconds = (left + 19) / 20;
			String clock = String.format("%d:%02d", seconds / 60, seconds % 60);
			context.text(font, Component.literal(clock),
					SplicerLayout.OUTPUT_X + 8 - font.width(clock) / 2, SplicerLayout.timeY(),
					INK, false);
		}

		context.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, INK_FAINT, false);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 0 && !menu.running()) {
			int row = rowAt(event.x(), event.y());
			if (row >= 0) {
				GuideData guide = PrimordiaClient.getClientGuideData();
				if (isOpen(guide, SpliceBranch.VALUES[row])
						&& minecraft != null && minecraft.gameMode != null) {
					minecraft.gameMode.handleInventoryButtonClick(menu.containerId, row);
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		super.extractRenderState(context, mouseX, mouseY, delta);

		int row = rowAt(mouseX, mouseY);
		if (row < 0) return;
		SpliceBranch branch = SpliceBranch.VALUES[row];
		GuideData guide = PrimordiaClient.getClientGuideData();
		SpliceDepth reached = SpliceTree.reached(guide, branch);
		SpliceTree.Donor best = SpliceTree.bestDonor(guide, branch);

		// Every row explains itself, open or shut. A row that only spoke when it was locked left the
		// player guessing at what they were about to take.
		List<Component> lines = new ArrayList<>();
		lines.add(Component.literal(branch.title).withStyle(ChatFormatting.WHITE));
		lines.add(Component.literal(branch.blurb).withStyle(ChatFormatting.GRAY));
		lines.add(Component.empty());

		for (SpliceDepth depth : SpliceDepth.VALUES) {
			boolean unlocked = SpliceTree.unlocked(guide, branch, depth);
			int have = SpliceTree.progress(guide, branch, depth);
			lines.add(Component.literal((unlocked ? "● " : "○ ") + depth.title
							+ "  to " + String.format("%.2f", depth.cap)
							+ (unlocked ? "" : "   " + Math.min(have, depth.required)
									+ "/" + depth.required))
					.withStyle(unlocked ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY));
		}

		if (reached != null && best != null && best.genome() != null) {
			lines.add(Component.empty());
			lines.add(Component.literal("From " + best.label()).withStyle(ChatFormatting.AQUA));
			for (SpliceEffects.Row effect : SpliceEffects.rowsFor(branch)) {
				float value = Math.min(best.genome().raw(effect.gene()), reached.cap);
				lines.add(Component.literal("  " + SpliceEffects.render(effect, value)
								+ "  " + effect.summary())
						.withStyle(effect.beneficial(value)
								? ChatFormatting.DARK_GREEN : ChatFormatting.RED));
			}
			if (!best.certain()) {
				lines.add(Component.literal("Not fully characterised — the figures are a guess.")
						.withStyle(ChatFormatting.GOLD, ChatFormatting.ITALIC));
			}
		} else {
			lines.add(Component.empty());
			lines.add(Component.literal("Nothing on file is strong enough to work from.")
					.withStyle(ChatFormatting.DARK_RED, ChatFormatting.ITALIC));
		}

		context.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
	}
}
