package dev.jsz.primordia.compat.controlify;

import dev.isxander.controlify.api.bind.InputBinding;
import dev.isxander.controlify.api.bind.InputBindingSupplier;
import dev.isxander.controlify.bindings.ControlifyBindings;
import dev.isxander.controlify.controller.ControllerEntity;
import dev.jsz.primordia.client.screen.FieldGuideScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * The strip of button prompts under the book.
 * <p>
 * The one part of controller support a reader sees before pressing anything, and the reason a
 * console interface is usable without being explained. The book had a hint of its own — "← → or
 * scroll" — which is this same idea for the device it was written for; this is that hint, for the
 * device actually in the reader's hands, and the book drops its own while one is shown.
 * <p>
 * Drawn here rather than through Controlify's guide domains, which is the other way to do it. Those
 * render in Controlify's own chrome, and this book is a drawn object down to the stains on the
 * parchment — a grey overlay across the bottom of it would look like a different program. The
 * glyphs are Controlify's either way: {@link InputBinding#inputGlyph()} returns the symbol for
 * whatever is actually bound, in the font for the controller actually being held, so a Switch
 * reader is told B where a PlayStation reader is told circle, and a rebound button is described as
 * it is bound rather than as it shipped.
 */
final class GuideHintBar {

	/** Ink colours picked against the dim the guide lays over the world, not against parchment. */
	private static final int GLYPH = 0xFFEDE6D2, LABEL = 0xFFB3AA92;
	private static final int PLATE = 0xB0201A12;

	/** Gap between one prompt and the next, and the padding inside the plate behind them. */
	private static final int GAP = 10, PAD_X = 6, PAD_Y = 3;

	/** How far under the book the strip sits, and how close to the window's edge it may come. */
	private static final int OFFSET_Y = 5, SCREEN_MARGIN = 2;

	private GuideHintBar() {
	}

	/**
	 * Draws the prompts for whatever the open page can currently do.
	 * <p>
	 * Built per frame from the page's own state rather than being a fixed list, because a prompt for
	 * something that would do nothing is worse than no prompt: a reader who presses the button it
	 * names and gets nothing has been told the controller is not working. So there is no page prompt
	 * on the family tree, which is one continuous view; no zoom prompt anywhere else; and no naming
	 * prompt except on a plate actually offering a name.
	 * <p>
	 * Movement is not listed. The cursor and the d-pad are the first things anyone tries, and a bar
	 * long enough to say so is a bar too long to read.
	 */
	static void render(GuiGraphicsExtractor context, FieldGuideScreen screen, ControllerEntity controller) {
		List<Component> prompts = new ArrayList<>();
		add(prompts, controller, ControlifyBindings.GUI_PREV_TAB, ControlifyBindings.GUI_NEXT_TAB, "Section");
		if (screen.isTreeView()) {
			add(prompts, controller, ControlifyBindings.VMOUSE_SCROLL_UP,
					ControlifyBindings.VMOUSE_SCROLL_DOWN, "Zoom");
		} else if (screen.hasMultiplePages()) {
			add(prompts, controller, ControlifyBindings.VMOUSE_PAGE_UP,
					ControlifyBindings.VMOUSE_PAGE_DOWN, "Page");
		}
		if (screen.showsSpecimen()) {
			add(prompts, controller, PrimordiaControlify.guidePanLeft,
					PrimordiaControlify.guidePanRight, "Turn");
		}
		add(prompts, controller, ControlifyBindings.VMOUSE_LCLICK, null, "Select");
		if (screen.offersNaming()) {
			add(prompts, controller, PrimordiaControlify.guideName, null, "Name");
		}
		add(prompts, controller, ControlifyBindings.GUI_BACK, null, "Close");
		if (prompts.isEmpty()) return;

		Font font = Minecraft.getInstance().font;
		int width = -GAP;
		for (Component prompt : prompts) width += font.width(prompt) + GAP;

		ScreenRectangle panel = screen.panelBounds();
		int x = panel.left() + (panel.width() - width) / 2;
		// Under the book where there is room, and tucked up over its bottom edge where there is not.
		// A window short enough for the second is a window the book itself is nearly filling, and a
		// prompt drawn off the end of the screen is a prompt that may as well not exist.
		int y = Math.min(panel.bottom() + OFFSET_Y,
				screen.height - font.lineHeight - SCREEN_MARGIN - PAD_Y);

		context.fill(x - PAD_X, y - PAD_Y, x + width + PAD_X, y + font.lineHeight + PAD_Y - 1, PLATE);
		for (Component prompt : prompts) {
			context.text(font, prompt, x, y, GLYPH, false);
			x += font.width(prompt) + GAP;
		}
	}

	/**
	 * Adds one prompt: the glyphs for the buttons, then what they do.
	 * <p>
	 * Two bindings where the action is a pair, so the triggers are shown as the pair they are rather
	 * than as whichever half was named first — a bar that says one trigger turns pages has told half
	 * the truth, and the reader has to guess the rest.
	 * <p>
	 * An unbound action has no glyph to show and nothing that could be pressed, so it is dropped
	 * entirely rather than printed with a blank in front of it. That is also what keeps this honest
	 * on a controller with no triggers.
	 */
	private static void add(List<Component> into, ControllerEntity controller,
	                        InputBindingSupplier first, InputBindingSupplier second, String label) {
		// Null rather than merely unbound means registration never happened. Controlify logs and
		// swallows anything thrown out of an entrypoint's pre-init, so a mod's bindings can be
		// half-registered and everything downstream still runs — and this runs every frame, which
		// turns one startup failure into a crash on every frame the book is open. Dropping the
		// prompt is the same thing this already does for a binding with no button on it.
		MutableComponent glyphs = Component.empty();
		boolean any = false;
		for (InputBindingSupplier supplier : new InputBindingSupplier[]{first, second}) {
			if (supplier == null) continue;
			InputBinding bound = supplier.onOrNull(controller);
			if (bound == null || bound.isUnbound()) continue;
			glyphs.append(bound.inputGlyph());
			any = true;
		}
		if (!any) return;

		into.add(glyphs.append(Component.literal(" " + label).withColor(LABEL & 0xFFFFFF)));
	}
}
