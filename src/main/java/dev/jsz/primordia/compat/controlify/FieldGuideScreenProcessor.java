package dev.jsz.primordia.compat.controlify;

import dev.isxander.controlify.Controlify;
import dev.isxander.controlify.InputMode;
import dev.isxander.controlify.bindings.ControlifyBindings;
import dev.isxander.controlify.controller.ControllerEntity;
import dev.isxander.controlify.screenop.ScreenProcessor;
import dev.isxander.controlify.screenop.keyboard.InputTarget;
import dev.isxander.controlify.screenop.keyboard.KeyboardLayouts;
import dev.isxander.controlify.screenop.keyboard.KeyboardOverlayScreen;
import dev.isxander.controlify.utils.HoldRepeatHelper;
import dev.isxander.controlify.utils.MinecraftUtil;
import dev.isxander.controlify.virtualmouse.VirtualMouseBehaviour;
import dev.isxander.controlify.virtualmouse.VirtualMouseHandler;
import dev.jsz.primordia.client.screen.FieldGuideScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

import java.util.Optional;

/**
 * Makes the field guide usable with a controller.
 * <p>
 * It is the only screen in this mod that needs the help. The lab and the cooler are container
 * screens, which Controlify already drives; the settings screen is vanilla buttons and sliders,
 * which it already navigates. The guide is neither — it is a document with no widgets in it at all,
 * which means Controlify's component navigation has nothing to move between and the screen is inert
 * to a controller apart from the button that closes it.
 * <p>
 * The foundation is {@link #virtualMouseBehaviour()}. A book is a pointing surface — the tabs are
 * aimed at, the family tree is dragged, a bloodline is picked out of it by pointing at the animal —
 * and Controlify's virtual mouse drives the real mouse handler, so every gesture the guide already
 * implements arrives through the paths it already has, with nothing here to keep in step. But a
 * cursor on its own is a mouse being mimed with a thumbstick, and the rest of this class is what
 * makes it behave like an interface built for the pad:
 * <pre>
 *   bumpers       previous and next tab
 *   triggers      previous and next page
 *   d-pad         step between the tabs, tree nodes and lines on the page; on the tree,
 *                 pan when there is nothing further that way
 *   left stick    the cursor, for anything the steps do not reach
 *   right stick   turn the specimen, drag the tree sideways; up and down is Controlify's
 *                 scroll, which the book already answers by zooming and turning pages
 *   A             select, which is the click the book already understands
 *   X             name this species, on a plate offering one
 *   B             close
 * </pre>
 * All of it is rebindable in Controlify's own settings, and almost all of it is Controlify's own
 * bindings rather than this mod's, chosen so the book is operated with the buttons the rest of the
 * interface has already taught — bumpers on a tab strip, triggers on pages, d-pad on a selection.
 * The two exceptions are named on {@link PrimordiaControlify#guideName} and
 * {@link PrimordiaControlify#guidePanLeft}, and exist because the bindings that would have been
 * right are switched off on any screen showing a cursor. What the reader is told about any of it is
 * {@link GuideHintBar}.
 */
public class FieldGuideScreenProcessor extends ScreenProcessor<FieldGuideScreen> {

	/** Fraction of the window the on-screen keyboard is allowed, when one is raised. */
	private static final float KEYBOARD_W = 0.8f, KEYBOARD_H = 0.4f;

	/**
	 * Panel pixels the view slides per tick while the stick is held over, at full deflection.
	 * <p>
	 * Slower than the cursor moves, because this drags the whole page under a reader who is looking
	 * at it rather than moving a pointer they are aiming.
	 */
	private static final float PAN_RATE = 3.2f;

	/** Panel pixels a d-pad step pans the tree by, when the step finds nothing to jump to. */
	private static final float EDGE_PAN = 24f;

	/** Below this the stick is resting, not being held slightly. */
	private static final float STICK_DEADZONE = 0.01f;

	/**
	 * Whether the keyboard this processor raised is what the screen is currently behind.
	 * <p>
	 * The overlay is a screen of its own, so while it is up this processor does not run at all —
	 * which makes this flag the whole state machine. Set when the keyboard goes up, and read on the
	 * first update after it comes down, which is the only thing that can happen next.
	 */
	private boolean keyboardRaised;

	/**
	 * Repeat timing: one for the d-pad, one for the triggers.
	 * <p>
	 * Neither is the inherited helper. That belongs to the component navigation in the superclass,
	 * and although this screen has no components for it to move between, sharing a countdown with
	 * code that may start decrementing it is a bug waiting for the day someone adds a widget here.
	 * <p>
	 * Two rather than one because {@code shouldAction} counts down on every call where the binding
	 * is held, so a reader holding a trigger and tapping the d-pad on the same tick would have both
	 * repeating at roughly twice the rate either was set to. Separate counters simply cannot
	 * interfere.
	 */
	private final HoldRepeatHelper stepRepeat = new HoldRepeatHelper(10, 3);
	private final HoldRepeatHelper pageRepeat = new HoldRepeatHelper(10, 3);

	public FieldGuideScreenProcessor(FieldGuideScreen screen) {
		super(screen);
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * {@code ENABLED} rather than {@code DEFAULT}, which would leave it to the player to discover
	 * that this screen needs the cursor turning on by hand. There is nothing here to navigate
	 * between instead: a screen with no widgets and the virtual mouse off is a screen a controller
	 * can only close.
	 */
	@Override
	public VirtualMouseBehaviour virtualMouseBehaviour() {
		return VirtualMouseBehaviour.ENABLED;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * The book prints its own hint about turning pages, in arrow keys. Told a controller has taken
	 * over, it drops it and {@link GuideHintBar} says the same thing in the right glyphs; told the
	 * keyboard is back, it prints it again. A reader switching between the two mid-page sees the
	 * footer follow them.
	 */
	@Override
	public void onInputModeChanged(InputMode mode) {
		super.onInputModeChanged(mode);
		screen.setControllerDriven(mode.isController());
	}

	@Override
	public void onControllerUpdate(ControllerEntity controller) {
		super.onControllerUpdate(controller);

		// Belt and braces for the footer: onInputModeChanged only fires on a change, and a book
		// opened with a controller already in hand never had one.
		screen.setControllerDriven(true);

		// The name editor, for whichever way it was opened — the shortcut below, or a click on the
		// naming line, which is still the gesture a reader with a mouse in the other hand will use.
		// Reading the screen's state rather than the trigger that caused it keeps the two in step.
		if (screen.isNaming()) {
			if (!keyboardRaised) {
				keyboardRaised = openKeyboard(controller);
			} else {
				// Back from the keyboard, so the name is finished. It has to be resolved here and
				// not left to the editor's own Enter, because the overlay swallows Enter to close
				// itself and never passes it on — meaning a name typed on it would otherwise be
				// typed, dismissed and lost. Closing the keyboard is the only "done" it offers, so
				// closing the keyboard is what commits.
				//
				// Cancelling is backspacing the line empty and then closing, which is the same rule
				// the typed editor follows: an empty name is not sent.
				screen.finishNaming();
				keyboardRaised = false;
			}
			return;
		}
		keyboardRaised = false;

		handleTabs(controller);
		handlePages(controller);
		handleSteps(controller);
		handleView(controller);
		handleNaming(controller);
	}

	/** Bumpers, which is where a tab strip lives on every other screen in the game. */
	private void handleTabs(ControllerEntity controller) {
		if (ControlifyBindings.GUI_NEXT_TAB.on(controller).justPressed()) {
			screen.selectSection(screen.currentSection() + 1);
		} else if (ControlifyBindings.GUI_PREV_TAB.on(controller).justPressed()) {
			screen.selectSection(screen.currentSection() - 1);
		}
	}

	/**
	 * Triggers turn pages.
	 * <p>
	 * {@code VMOUSE_PAGE_UP} and {@code VMOUSE_PAGE_DOWN} rather than a binding of this mod's own:
	 * they are already named for paging, already sit on the triggers, and are already the buttons
	 * that page the recipe book. Repeated while held, because turning eleven pages should not be
	 * eleven presses.
	 * <p>
	 * Nothing on the family tree, which is one continuous view with no pages in it — the same
	 * reason the book prints no page count there.
	 */
	private void handlePages(ControllerEntity controller) {
		if (screen.isTreeView()) return;

		if (pageRepeat.shouldAction(ControlifyBindings.VMOUSE_PAGE_DOWN.on(controller))) {
			screen.turnPage(1);
			pageRepeat.onNavigate();
		} else if (pageRepeat.shouldAction(ControlifyBindings.VMOUSE_PAGE_UP.on(controller))) {
			screen.turnPage(-1);
			pageRepeat.onNavigate();
		}
	}

	/**
	 * The d-pad, stepping the cursor from one thing on the page to the next.
	 * <p>
	 * {@code VMOUSE_SNAP_*} is the binding for exactly this and sits on the d-pad already.
	 * Controlify's own handler for it runs first and finds nothing, because it reads its targets
	 * from an interface this screen deliberately does not implement — see {@link GuideCursor}.
	 * <p>
	 * A step that lands on nothing pans the tree instead. That is what makes a view larger than its
	 * window reachable: the reader steps through the boxes they can see, runs out, and the page
	 * slides to show more, without ever changing what they are pressing.
	 */
	private void handleSteps(ControllerEntity controller) {
		int dx = 0, dy = 0;
		if (stepRepeat.shouldAction(ControlifyBindings.VMOUSE_SNAP_LEFT.on(controller))) dx = -1;
		else if (stepRepeat.shouldAction(ControlifyBindings.VMOUSE_SNAP_RIGHT.on(controller))) dx = 1;
		else if (stepRepeat.shouldAction(ControlifyBindings.VMOUSE_SNAP_UP.on(controller))) dy = -1;
		else if (stepRepeat.shouldAction(ControlifyBindings.VMOUSE_SNAP_DOWN.on(controller))) dy = 1;
		else return;

		stepRepeat.onNavigate();

		VirtualMouseHandler vmouse = virtualMouse();
		if (vmouse == null) return;

		if (GuideCursor.step(vmouse, screen.snapTargets(), dx, dy)) {
			playFocusChangeSound();
		} else if (screen.isTreeView()) {
			// Negated: the reader is asking to look further that way, and panning moves the drawing
			// under them, which is the opposite direction. Same sign convention as a drag.
			screen.nudgeView(-dx * EDGE_PAN, -dy * EDGE_PAN);
		}
	}

	/**
	 * The right stick, turning the specimen on its stand and dragging the tree sideways.
	 * <p>
	 * Only sideways. Up and down on that stick is Controlify's scroll, which this book already
	 * answers — it zooms the tree and turns pages — and taking it for panning as well would put one
	 * stick on two jobs at once on the tab where both apply. Vertical reach on the tree is the
	 * d-pad's edge pan instead.
	 * <p>
	 * Analogue rather than a button test, so a stick eased over turns the specimen slowly and a
	 * stick pushed to the stop spins it.
	 */
	private void handleView(ControllerEntity controller) {
		// Null if pre-init did not finish; see the note in GuideHintBar.
		if (PrimordiaControlify.guidePanLeft == null || PrimordiaControlify.guidePanRight == null) return;

		float amount = PrimordiaControlify.guidePanRight.on(controller).analogueNow()
				- PrimordiaControlify.guidePanLeft.on(controller).analogueNow();
		if (Math.abs(amount) < STICK_DEADZONE) return;

		// Negated, which makes this the opposite of what dragging with a mouse does, deliberately.
		// A stick is aimed rather than grabbed: pushed right, the view goes right and the drawing
		// goes left under it, the same as the right stick in every third-person game and the same
		// as the d-pad's edge pan above. A mouse drags the paper; a stick steers the eye. Matching
		// the mouse instead would have put the two controller gestures in opposition to each other,
		// which is the pair a reader is far more likely to use in the same minute.
		screen.nudgeView(-amount * PAN_RATE, 0f);
	}

	/**
	 * Opens the name editor.
	 * <p>
	 * The plate offers a name only for a species that has earned one and not yet been given one, so
	 * most of the time this button does nothing here, and the prompt for it is not shown either.
	 * <p>
	 * Only opens the editor. The keyboard is left to {@link #onControllerUpdate} to raise on the
	 * next update, so that there is one path from "a name is being written" to "a keyboard is up"
	 * rather than one for this button and another for a click on the naming line — and so that the
	 * flag which decides when the name is finished cannot be set by one of them and missed by the
	 * other.
	 */
	private void handleNaming(ControllerEntity controller) {
		if (!screen.offersNaming()) return;
		if (PrimordiaControlify.guideName == null) return;
		if (!PrimordiaControlify.guideName.on(controller).justPressed()) return;
		if (screen.beginNaming()) playClackSound();
	}

	@Override
	protected void render(ControllerEntity controller, GuiGraphicsExtractor context, float tickDelta,
	                      Optional<VirtualMouseHandler> vmouse) {
		// Unlike onControllerUpdate, this runs whenever a controller is merely connected — so a
		// player at the keyboard with a pad plugged in on the desk would otherwise be shown button
		// prompts for a device they are not touching, and the footer's arrow-key hint alongside
		// them.
		if (!Controlify.instance().currentInputMode().isController()) return;

		// A reader who has turned screen guides off in Controlify has turned this off too. It is the
		// same kind of thing for the same reason, and having one switch miss one screen is how a
		// setting stops being trusted.
		if (!controller.settings().generic.guide.showScreenGuides) return;

		GuideHintBar.render(context, screen, controller);
	}

	/**
	 * Puts Controlify's on-screen keyboard over the book, aimed at the name being written.
	 * <p>
	 * Honours the reader's own setting for whether they want one, the same way Controlify's own
	 * keyboard-capable widgets do.
	 */
	private boolean openKeyboard(ControllerEntity controller) {
		// Someone who has turned the on-screen keyboard off has a real one, and the editor works
		// under it exactly as it always has. Reporting that nothing was raised leaves the editor
		// open for them rather than closing it a tick later as finished.
		if (!controller.settings().generic.keyboard.showOnScreenKeyboard) return false;

		MinecraftUtil.setScreen(new KeyboardOverlayScreen(
				screen,
				KeyboardLayouts.simple(),
				new GuideNameInputTarget(screen),
				KeyboardOverlayScreen.aboveOrBelowWidgetPositioner(
						(int) (screen.width * KEYBOARD_W),
						(int) (screen.height * KEYBOARD_H),
						2,
						screen::panelBounds)));
		return true;
	}

	/**
	 * The virtual mouse, if it is running.
	 * <p>
	 * {@link #virtualMouseBehaviour()} asks for it on this screen, but asking is not having: it is
	 * off whenever the reader is on the keyboard, and this is reached from an update that runs then
	 * too.
	 */
	private VirtualMouseHandler virtualMouse() {
		VirtualMouseHandler vmouse = Controlify.instance().virtualMouseHandler();
		return vmouse.isVirtualMouseEnabled() ? vmouse : null;
	}

	/**
	 * Feeds the on-screen keyboard into the guide's own name editor.
	 * <p>
	 * Deliberately nothing but a forward to {@code charTyped} and {@code keyPressed}. The guide
	 * already knows what a letter and a backspace mean while a name is being written; re-deciding
	 * that here would be writing the editor a second time, and the second copy is the one that
	 * would be wrong about the length limit.
	 * <p>
	 * Enter and escape never arrive — the overlay takes both for itself, to close — which is why
	 * the processor commits on close instead of waiting for one.
	 * <p>
	 * Cursor movement and copying are not offered, because the editor has no cursor to move and
	 * nothing worth copying — it is one short line, typed and committed.
	 */
	private record GuideNameInputTarget(FieldGuideScreen guide) implements InputTarget {

		@Override
		public boolean supportsCharInput() {
			return true;
		}

		@Override
		public boolean acceptChar(char ch, int modifiers) {
			guide.charTyped(new CharacterEvent(ch));
			return true;
		}

		@Override
		public boolean supportsKeyCodeInput() {
			return true;
		}

		@Override
		public boolean acceptKeyCode(int keycode, int scancode, int modifiers) {
			guide.keyPressed(new KeyEvent(keycode, scancode, modifiers));
			return true;
		}
	}
}
