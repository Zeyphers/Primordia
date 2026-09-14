package dev.jsz.primordia.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/**
 * The mod's key mappings, registered on first use rather than by whoever initialises first.
 * <p>
 * This exists because two client initialisers want the settings key and Fabric does not order them:
 * {@link dev.jsz.primordia.PrimordiaClient} registers the tick handler that acts on it, and
 * Controlify's entrypoint — which is a {@code client} entrypoint of its own — asks for it so a
 * controller button can be bound to the same action. Held in {@link PrimordiaClient} as a field
 * assigned during init, it was null for the compat layer whenever Fabric happened to run Controlify
 * first, which left a binding a player could see and bind and which then did nothing.
 * <p>
 * A static field on a class of its own has no order to get wrong: the first of the two to look at
 * it is the one that causes the registration, and both are inside client initialisation, which is
 * the window {@link KeyMappingHelper} requires.
 */
public final class PrimordiaKeys {

	/** Opens the settings screen. Unbound by default; see the note in the Controlify compat layer. */
	public static final KeyMapping SETTINGS = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.primordia.settings",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_UNKNOWN,
			KeyMapping.Category.MISC));

	private PrimordiaKeys() {
	}
}
