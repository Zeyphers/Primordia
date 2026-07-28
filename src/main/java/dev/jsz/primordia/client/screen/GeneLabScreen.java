package dev.jsz.primordia.client.screen;

import dev.jsz.primordia.Primordia;
import dev.jsz.primordia.block.GeneLabBlockEntity;
import dev.jsz.primordia.screen.GeneLabScreenHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * The Basic Gene Lab's screen: the pipeline drawn as a route across the panel.
 * <p>
 * The three routes are traced from the background art rather than invented here. Each is a chain of
 * axis-aligned legs, and progress fills them end to end in order, so a bar can turn a corner and
 * still read as one continuous journey from the slot it leaves to the slot it arrives at.
 * <p>
 * There is no single overall status label. One existed and it lied: it read the machine's stage,
 * which says what the machine is <i>trying</i> to do, and so announced "Sequencing" at a lab that
 * had run out of fuel and was doing nothing. Stalling is now shown by the live route turning red,
 * with the reason named beneath.
 */
public class GeneLabScreen extends HandledScreen<GeneLabScreenHandler> {

	private static final Identifier BACKGROUND = Primordia.id("textures/gui/basic_gene_lab.png");
	private static final Identifier FURNACE =
			Identifier.ofVanilla("textures/gui/container/furnace.png");

	private static final int FLAME_U = 176, FLAME_V = 0, FLAME_W = 14, FLAME_H = 14;

	/** Redstone pips: 3px squares on a 4px pitch, so a 4x4 block spans the slot's own width. */
	private static final int PIP_SIZE = 3, PIP_PITCH = 4;

	/** Ticks each line of the decoding readout stays up. Fast enough to look like a machine working. */
	private static final int TERMINAL_INTERVAL = 4;
	private static final int TERMINAL_X = 10;
	private static final int TERMINAL_Y = 88;
	private static final int TERMINAL_LINE_HEIGHT = 10;

	/**
	 * Filler for the decoding readout.
	 * <p>
	 * Entirely cosmetic, and honest about it — none of these report a real quantity. Decoding is
	 * the one stage with nothing to watch: no fuel burning down, no item moving, just a bar that
	 * takes twelve seconds. A log scrolling past says "this is working on something" far better
	 * than a static label, which is the whole job.
	 */
	private static final String[] TERMINAL_LINES = {
			"aligning read %04X",
			"indexing %d-mers",
			"seeking locus %04X",
			"building consensus",
			"cross-ref library",
			"gap penalty %d.%02d",
			"coverage %dx",
			"phasing haplotype %d",
			"resolving ambiguity",
			"scoring divergence",
			"collapsing repeats",
			"calling variants %d",
	};

	/** Ticks this screen has been open; drives the readout. */
	private int ticks;

	private static final int RUNNING = 0xFF4CC94C;   // green: reading
	private static final int DECODING = 0xFF52CDED;  // cyan: matches the lab's own screen palette
	private static final int WRITING = 0xFFE8D46A;   // warm: the report being written out
	private static final int STALLED = 0xFFD63A2F;   // red: waiting on a resource
	private static final int DONE = 0xFF6E6E6E;      // grey: finished, no longer the live step

	/** Direction a leg fills, which is the direction the sample travels along it. */
	private enum Dir { DOWN, UP, RIGHT, LEFT }

	/** One straight run of a route. {@code w}/{@code h} are its full extent in the art. */
	private record Leg(int x, int y, int w, int h, Dir dir) {
		int length() {
			return dir == Dir.DOWN || dir == Dir.UP ? h : w;
		}
	}

	/**
	 * The three routes, in pipeline order, traced pixel-for-pixel off the background.
	 * <p>
	 * Legs tile their route without overlapping — each corner belongs to exactly one leg — so a
	 * partially filled route never double-paints a bend and never leaves a notch in one.
	 */
	private static final Leg[][] ROUTES = {
			// Sample down the left, then right into the fuel slot.
			{new Leg(40, 38, 5, 37, Dir.DOWN), new Leg(40, 75, 18, 5, Dir.RIGHT)},
			// Fuel straight across to redstone.
			{new Leg(75, 75, 17, 5, Dir.RIGHT)},
			// Redstone right, up the step, then right into the report.
			{new Leg(110, 75, 14, 5, Dir.RIGHT), new Leg(119, 56, 5, 19, Dir.UP),
					new Leg(124, 56, 7, 5, Dir.RIGHT)},
	};

	public GeneLabScreen(GeneLabScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title);
		backgroundWidth = GeneLabScreenHandler.BACKGROUND_WIDTH;
		backgroundHeight = GeneLabScreenHandler.BACKGROUND_HEIGHT;
		playerInventoryTitleY = backgroundHeight - 94;
	}

	@Override
	protected void init() {
		super.init();
		titleX = (backgroundWidth - textRenderer.getWidth(title)) / 2;
	}

	@Override
	protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
		int x = (width - backgroundWidth) / 2;
		int y = (height - backgroundHeight) / 2;
		context.drawTexture(BACKGROUND, x, y, 0, 0, backgroundWidth, backgroundHeight, 256, 256);

		for (int i = 0; i < ROUTES.length; i++) {
			drawRoute(context, x, y, ROUTES[i], handler.lineFill(i), colourFor(i));
		}

		// Flame above the fuel slot, on the route fuel pays for.
		float burn = handler.burnFraction();
		if (burn > 0f) {
			int lit = (int) Math.ceil(burn * FLAME_H);
			context.drawTexture(FURNACE,
					x + GeneLabScreenHandler.FUEL_X + 1,
					y + GeneLabScreenHandler.FUEL_Y - 17 + FLAME_H - lit,
					FLAME_U, FLAME_V + FLAME_H - lit, FLAME_W, lit);
		}

		// Redstone drawn so far, as a block of pips above its slot. Sixteen dust is a real cost and
		// watching them fill counts it out; the pips are spaced so they read as separate squares
		// rather than merging into one bar, which is what a tighter grid did.
		if (handler.stage() == GeneLabBlockEntity.Stage.DECODING) {
			int used = handler.redstoneUsed();
			int px = x + GeneLabScreenHandler.REDSTONE_X;
			int py = y + GeneLabScreenHandler.REDSTONE_Y - 19;
			for (int i = 0; i < GeneLabBlockEntity.REDSTONE_PER_DECODE; i++) {
				int cx = px + (i % 4) * PIP_PITCH;
				int cy = py + (i / 4) * PIP_PITCH;
				context.fill(cx, cy, cx + PIP_SIZE, cy + PIP_SIZE,
						i < used ? 0xFFD63A2F : 0xFF6B5252);
			}
		}
	}

	/** Paints {@code fill} of a route, spending the budget leg by leg so bends fill in order. */
	private void drawRoute(DrawContext context, int ox, int oy, Leg[] route, float fill, int colour) {
		if (fill <= 0f) return;
		int total = 0;
		for (Leg leg : route) total += leg.length();

		int budget = Math.max(1, Math.round(fill * total));
		for (Leg leg : route) {
			if (budget <= 0) return;
			int n = Math.min(budget, leg.length());
			budget -= n;
			int x = ox + leg.x(), y = oy + leg.y();
			switch (leg.dir()) {
				case DOWN -> context.fill(x, y, x + leg.w(), y + n, colour);
				case UP -> context.fill(x, y + leg.h() - n, x + leg.w(), y + leg.h(), colour);
				case RIGHT -> context.fill(x, y, x + n, y + leg.h(), colour);
				case LEFT -> context.fill(x + leg.w() - n, y, x + leg.w(), y + leg.h(), colour);
			}
		}
	}

	/** Colour of one route: what it is doing, or why it is not. */
	private int colourFor(int index) {
		if (!handler.lineActive(index)) return DONE;
		if (handler.isStalled()) return STALLED;
		return switch (index) {
			case 0 -> RUNNING;
			case 1 -> DECODING;
			default -> WRITING;
		};
	}

	/**
	 * One accurate status line, in the clear band beneath the routes.
	 * <p>
	 * Unlike the label this replaced, it distinguishes what the machine is attempting from whether
	 * it can, so a lab out of fuel says so instead of claiming to be working.
	 */
	@Override
	protected void handledScreenTick() {
		super.handledScreenTick();
		ticks++;
	}

	@Override
	protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
		super.drawForeground(context, mouseX, mouseY);

		// Decoding gets a scrolling readout instead of a caption. Everything else gets one line,
		// centred, saying what is actually true.
		if (handler.stage() == GeneLabBlockEntity.Stage.DECODING && !handler.isStalled()) {
			drawTerminal(context);
			return;
		}

		String message;
		int colour;
		switch (handler.stage()) {
			case IDLE -> {
				boolean empty = handler.getSlot(GeneLabBlockEntity.SLOT_SAMPLE).getStack().isEmpty();
				message = empty ? "Awaiting sample" : "Ready";
				colour = 0x808080;
			}
			case SEQUENCING -> {
				message = handler.isStalled() ? "Stalled — no fuel" : "Reading tissue";
				colour = handler.isStalled() ? 0xB03028 : 0x3A7A3A;
			}
			default -> {
				message = "Stalled — no redstone";
				colour = 0xB03028;
			}
		}
		Text text = Text.literal(message);
		context.drawText(textRenderer, text,
				(backgroundWidth - textRenderer.getWidth(text)) / 2, 96, colour, false);
	}

	/** Two lines of log: the one just finished, dimmed, above the one running now. */
	private void drawTerminal(DrawContext context) {
		int step = ticks / TERMINAL_INTERVAL;
		if (handler.lineFill(2) > 0f) {
			// The last stretch is the report being written, which is worth saying plainly.
			Text done = Text.literal("> writing report");
			context.drawText(textRenderer, done, TERMINAL_X, TERMINAL_Y + TERMINAL_LINE_HEIGHT,
					0x8A7A28, false);
			context.drawText(textRenderer, Text.literal("  " + terminalLine(step - 1)),
					TERMINAL_X, TERMINAL_Y, 0x8A9A8A, false);
			return;
		}
		context.drawText(textRenderer, Text.literal("  " + terminalLine(step - 1)),
				TERMINAL_X, TERMINAL_Y, 0x8A9A8A, false);
		// A caret on the live line, blinking on the same clock, so the log reads as still running
		// rather than as having stopped on its last message.
		String caret = (ticks / 8) % 2 == 0 ? "_" : "";
		context.drawText(textRenderer, Text.literal("> " + terminalLine(step) + caret),
				TERMINAL_X, TERMINAL_Y + TERMINAL_LINE_HEIGHT, 0x2A6B2A, false);
	}

	/**
	 * One line of the readout, chosen and filled deterministically from its position in the
	 * sequence, so the log is stable per frame rather than flickering between redraws.
	 */
	private static String terminalLine(int step) {
		if (step < 0) return "";
		int hash = step * 0x9E3779B9;
		hash ^= hash >>> 15;
		int magnitude = Math.abs(hash);
		String template = TERMINAL_LINES[Math.floorMod(step, TERMINAL_LINES.length)];
		long count = template.chars().filter(c -> c == '%').count();
		if (count == 2) {
			return String.format(template, magnitude % 9, magnitude % 100);
		}
		if (count == 1) {
			return String.format(template, template.contains("%04X")
					? magnitude % 0xFFFF
					: 1 + magnitude % 4096);
		}
		return template;
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		renderBackground(context, mouseX, mouseY, delta);
		super.render(context, mouseX, mouseY, delta);
		drawMouseoverTooltip(context, mouseX, mouseY);
		renderSlotHints(context, mouseX, mouseY);
	}

	/** Explains the two fuel slots on hover; nothing else in the game pairs coal with redstone. */
	private void renderSlotHints(DrawContext context, int mouseX, int mouseY) {
		if (!handler.getCursorStack().isEmpty()) return;
		int ox = (width - backgroundWidth) / 2;
		int oy = (height - backgroundHeight) / 2;

		if (isOver(mouseX, mouseY, ox + GeneLabScreenHandler.FUEL_X, oy + GeneLabScreenHandler.FUEL_Y)
				&& handler.getSlot(GeneLabBlockEntity.SLOT_FUEL).getStack().isEmpty()) {
			context.drawTooltip(textRenderer, List.of(
					Text.literal("Sequencing fuel"),
					Text.literal("§7Any furnace fuel — reads the tissue")), mouseX, mouseY);
		} else if (isOver(mouseX, mouseY, ox + GeneLabScreenHandler.REDSTONE_X,
				oy + GeneLabScreenHandler.REDSTONE_Y)
				&& handler.getSlot(GeneLabBlockEntity.SLOT_REDSTONE).getStack().isEmpty()) {
			context.drawTooltip(textRenderer, List.of(
					Text.literal("Decoding power"),
					Text.literal("§7" + GeneLabBlockEntity.REDSTONE_PER_DECODE
							+ " redstone per decode")), mouseX, mouseY);
		}
	}

	private static boolean isOver(int mouseX, int mouseY, int slotX, int slotY) {
		return mouseX >= slotX && mouseX < slotX + 16 && mouseY >= slotY && mouseY < slotY + 16;
	}
}
