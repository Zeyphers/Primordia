package dev.jsz.primordia.screen;

/**
 * The bench screen's geometry, in one place.
 * <p>
 * These numbers come out of {@code design/gui/splicer_layout.png}, which is the file the layout is
 * actually designed in — move a marker there, run {@code design/gui/splicer_layout.py}, and copy
 * what it prints into here. They live in their own class because both halves of the screen need
 * them and they must agree: the menu positions the slots, the screen paints the wells and works out
 * what the mouse is over, and a slot whose highlight sits next to its item looks like a rendering
 * bug rather than a number typed twice.
 */
public final class SplicerLayout {

	private SplicerLayout() {
	}

	/** The canvas size in the layout file is the screen size. */
	public static final int WIDTH = 248;
	public static final int HEIGHT = 256;

	/** The texture is a power of two; the panel sits in its top-left. */
	public static final int SHEET = 256;

	public static final int TITLE_X = 8;
	public static final int TITLE_Y = 8;

	public static final int ROW_X = 10;
	public static final int ROW_Y = 24;
	public static final int ROW_W = 168;
	public static final int ROW_H = 22;
	/** Top to top, so the one-pixel gutter between rows is not clickable. */
	public static final int ROW_PITCH = 23;

	/** The channel the progress line runs along, from the rows across to the output. */
	public static final int RAIL_X = 178;
	public static final int RAIL_Y = 31;
	public static final int RAIL_W = 44;
	public static final int RAIL_H = 123;

	public static final int OUTPUT_X = 222;
	public static final int OUTPUT_Y = 85;

	public static final int INV_X = 43;
	public static final int INV_Y = 176;
	public static final int HOTBAR_X = 43;
	public static final int HOTBAR_Y = 234;

	public static int rowTop(int index) {
		return ROW_Y + index * ROW_PITCH;
	}

	/** Vertical centre of a row, which is where its branch of the rail leaves it. */
	public static int rowCentre(int index) {
		return rowTop(index) + ROW_H / 2;
	}

	/** The spine every row's line joins before running to the output slot. */
	public static int spineX() {
		return RAIL_X + RAIL_W / 2;
	}

	public static int outputCentreY() {
		return OUTPUT_Y + 8;
	}

	/**
	 * The status lamp, centred above the output slot.
	 * <p>
	 * Not in the layout file: it is drawn entirely in code because it is never the same two frames
	 * running, and a marker for it would only ever be a marker for something that is not painted.
	 */
	public static final int LED_SIZE = 4;

	public static int ledX() {
		return OUTPUT_X + 8 - LED_SIZE / 2;
	}

	public static int ledY() {
		return OUTPUT_Y - 8;
	}

	/** Baseline of the countdown under the output slot. */
	public static int timeY() {
		return OUTPUT_Y + 20;
	}
}
