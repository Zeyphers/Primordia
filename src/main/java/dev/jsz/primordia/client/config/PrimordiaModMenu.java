package dev.jsz.primordia.client.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Puts Primordia's settings behind its entry in the Mods list.
 * <p>
 * Mod Menu is an optional dependency: this class is only ever loaded because Mod Menu itself
 * looks up the {@code modmenu} entrypoint, so nothing here runs — and nothing here needs to be on
 * the classpath — when it is absent. The settings screen stays reachable without it through the
 * keybind registered in {@code PrimordiaClient}.
 */
public class PrimordiaModMenu implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return PrimordiaConfigScreen::new;
	}
}
