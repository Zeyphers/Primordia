package dev.jsz.primordia.compat.controlify;

import dev.isxander.controlify.api.ControlifyApi;
import dev.isxander.controlify.api.bind.InputBindingSupplier;
import dev.isxander.controlify.api.entrypoint.ControlifyEntrypoint;
import dev.isxander.controlify.api.entrypoint.InitContext;
import dev.isxander.controlify.api.entrypoint.PreInitContext;
import dev.isxander.controlify.bindings.BindContext;
import dev.isxander.controlify.bindings.input.AxisInput;
import dev.isxander.controlify.bindings.input.ButtonInput;
import dev.isxander.controlify.controller.input.GamepadInputs;
import dev.isxander.controlify.screenop.ScreenProcessorProvider;
import dev.jsz.primordia.Primordia;
import dev.jsz.primordia.client.PrimordiaKeys;
import dev.jsz.primordia.client.screen.FieldGuideScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;

/**
 * Controller support, when Controlify happens to be installed.
 * <p>
 * Nothing outside this package names a Controlify type, and nothing inside the mod names this
 * class. It is reached only through the {@code controlify} entrypoint in fabric.mod.json, which is
 * queried by Controlify and by nothing else — so with the mod absent this class is never loaded and
 * the types it imports are never asked for. Same arrangement as
 * {@link dev.jsz.primordia.client.DynamicLightsCompat}, and for the same reason.
 * <p>
 * What is added is the field guide, which is the one screen here that a controller genuinely cannot
 * use otherwise — see {@link FieldGuideScreenProcessor} — and a bindable button for the settings
 * screen.
 * <p>
 * There is no rumble. Creature calls drove one for a version: the voice already carries the size of
 * the animal that made it, so a large one nearby could be felt as well as heard, and the thresholds
 * were tuned so that only large and close ones were. It was still annoying. A world of this mod's is
 * full of animals making noise, and a controller that reacts to any of it is a controller buzzing
 * more or less constantly — which is the one thing haptics cannot survive.
 * <p>
 * The gene lab and the sample cooler are deliberately absent. Both are
 * {@code AbstractContainerScreen}s, which Controlify already drives slot by slot, and the settings
 * screen is built from vanilla buttons and sliders, which it already navigates. Registering
 * anything for those would be replacing working behaviour with our own guess at it.
 */
public class PrimordiaControlify implements ControlifyEntrypoint {

	/**
	 * Opens the settings screen.
	 * <p>
	 * Correlated with the keyboard binding rather than duplicating what it does: {@code keyEmulation}
	 * makes Controlify press {@link PrimordiaKeys#SETTINGS} itself, so the button and the key run the
	 * same handler and cannot drift apart. The correlation is what puts the button's glyph beside the
	 * key in the vanilla controls list.
	 */
	static InputBindingSupplier openSettings;

	/**
	 * Names the species on the open plate of the field guide.
	 * <p>
	 * A binding of this mod's own, rather than borrowing one of Controlify's abstract screen
	 * actions, because those are declared for {@link BindContext#REGULAR_SCREEN} and that context is
	 * defined as a screen <i>without</i> the virtual mouse running. The guide asks for the cursor,
	 * so it is never a regular screen, and every {@code GUI_*} action but the handful declared for
	 * any screen at all is suppressed while it is open — silently, since a suppressed binding is not
	 * an error, it simply never fires.
	 * <p>
	 * Defaulted to the west face button, which under the virtual mouse is otherwise a right click —
	 * and a right click is inert on this screen, so the two can share it without either being lost.
	 */
	static InputBindingSupplier guideName;

	/**
	 * Drags the family tree sideways, and turns the specimen on its stand.
	 * <p>
	 * The right stick's horizontal axes, which are the one part of that stick the virtual mouse does
	 * not already claim — up and down are its scroll, which this book answers by zooming and turning
	 * pages. Registered here for the same reason as {@link #guideName}: {@code GUI_SECONDARY_NAVI_*}
	 * is the binding that would otherwise be exactly right, and it is suppressed on any screen with
	 * a cursor.
	 */
	static InputBindingSupplier guidePanLeft, guidePanRight;

	@Override
	public void onControlifyPreInit(PreInitContext context) {
		// Sources and bindings both have to exist before the config is read, or a player's saved
		// setting for them has nowhere to be loaded into. Pre-init is that point — Controlify calls
		// it from its own client initialiser, which Fabric may well run before this mod's, so the
		// key mapping is reached through PrimordiaKeys rather than through anything PrimordiaClient
		// has had a chance to assign.
		KeyMapping settingsKey = PrimordiaKeys.SETTINGS;

		openSettings = context.bindings().registerBinding(builder -> builder
				.id(Primordia.MOD_ID, "open_settings")
				.name(Component.translatable("key.primordia.settings"))
				.description(Component.translatable("controlify.binding.primordia.open_settings.description"))
				.category(Component.translatable("controlify.binding.primordia.category"))
				// No default input. Every face and shoulder button is spoken for in game, and
				// stealing one for a settings screen would be a worse first impression than an
				// unbound entry the few players who want it can bind.
				.allowedContexts(BindContext.IN_GAME)
				.addKeyCorrelation(settingsKey)
				.keyEmulation(settingsKey));

		guideName = context.bindings().registerBinding(builder -> builder
				.id(Primordia.MOD_ID, "guide_name")
				.name(Component.translatable("controlify.binding.primordia.guide_name"))
				.description(Component.translatable("controlify.binding.primordia.guide_name.description"))
				.category(Component.translatable("controlify.binding.primordia.category"))
				.allowedContexts(BindContext.V_MOUSE_CURSOR)
				.defaultInput(new ButtonInput(GamepadInputs.WEST_BUTTON)));

		guidePanLeft = context.bindings().registerBinding(builder -> builder
				.id(Primordia.MOD_ID, "guide_pan_left")
				.name(Component.translatable("controlify.binding.primordia.guide_pan_left"))
				.description(Component.translatable("controlify.binding.primordia.guide_pan.description"))
				.category(Component.translatable("controlify.binding.primordia.category"))
				.allowedContexts(BindContext.V_MOUSE_CURSOR)
				.defaultInput(new AxisInput(GamepadInputs.RIGHT_STICK_AXIS_LEFT)));

		guidePanRight = context.bindings().registerBinding(builder -> builder
				.id(Primordia.MOD_ID, "guide_pan_right")
				.name(Component.translatable("controlify.binding.primordia.guide_pan_right"))
				.description(Component.translatable("controlify.binding.primordia.guide_pan.description"))
				.category(Component.translatable("controlify.binding.primordia.category"))
				.allowedContexts(BindContext.V_MOUSE_CURSOR)
				.defaultInput(new AxisInput(GamepadInputs.RIGHT_STICK_AXIS_RIGHT)));
	}

	@Override
	public void onControlifyInit(InitContext context) {
		// Registered against the class rather than woven in with a mixin, so the field guide stays
		// a plain Screen that knows nothing about controllers.
		ScreenProcessorProvider.registerProvider(FieldGuideScreen.class, FieldGuideScreenProcessor::new);
	}

	@Override
	public void onControllersDiscovered(ControlifyApi api) {
		// Nothing here depends on which controllers exist. The field guide's processor asks for the
		// active one through the arguments it is handed, and there is nothing else.
	}
}
