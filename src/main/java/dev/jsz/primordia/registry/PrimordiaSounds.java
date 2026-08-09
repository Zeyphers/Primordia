package dev.jsz.primordia.registry;

import dev.jsz.primordia.Primordia;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

/**
 * The mod's own sounds.
 * <p>
 * Registered as variable-range events rather than fixed-range ones, which is what lets a caller
 * pass a volume that actually changes how far the sound carries — a cooler heard across a base is
 * not what a lid is.
 */
public final class PrimordiaSounds {

	public static final SoundEvent SAMPLE_COOLER_OPEN = register("block.sample_cooler.open");
	public static final SoundEvent SAMPLE_COOLER_CLOSE = register("block.sample_cooler.close");

	private PrimordiaSounds() {
	}

	private static SoundEvent register(String path) {
		Identifier id = Primordia.id(path);
		return Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
	}

	/**
	 * Forces class initialisation, the same push the other registries need: the events are static
	 * finals on a class nothing else touches until a cooler is opened, by which time registration is
	 * long closed.
	 */
	public static void register() {
	}
}
