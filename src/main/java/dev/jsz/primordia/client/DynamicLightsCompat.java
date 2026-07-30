package dev.jsz.primordia.client;

import dev.jsz.primordia.Primordia;
import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.client.config.PrimordiaConfig;
import dev.jsz.primordia.entity.CreatureEntity;
import dev.jsz.primordia.registry.PrimordiaEntities;
import dev.jsz.primordia.util.MathX;
import dev.lambdaurora.lambdynlights.api.DynamicLightsContext;
import dev.lambdaurora.lambdynlights.api.DynamicLightsInitializer;
import dev.lambdaurora.lambdynlights.api.entity.EntityLightSourceManager;
import dev.lambdaurora.lambdynlights.api.entity.luminance.EntityLuminance;
import dev.lambdaurora.lambdynlights.api.item.ItemLightSourceManager;
import net.minecraft.world.entity.Entity;

/**
 * Makes bioluminescent creatures cast real light, when LambDynamicLights is installed.
 * <p>
 * The mod's own glow is a rendering trick — the emissive pass lights the creature's own markings and
 * nothing else, so a glowing animal in a dark cave is bright while the floor under it stays black.
 * Dynamic lights is what closes that, and a creature that grew light organs ought to light the cave
 * it walks through.
 * <p>
 * <b>Reached through an entrypoint, not reflection.</b> This was bound by reflection while
 * {@code DynamicLightHandlers} existed; 4.x replaced that with a declared entrypoint and a luminance
 * interface, and the reflective lookup had been quietly failing ever since. Being an entrypoint costs
 * nothing in optionality — Fabric only loads this class when something queries
 * {@code lambdynlights:initializer}, and the only thing that ever queries it is LambDynamicLights
 * itself. With the mod absent, nothing here is loaded and none of its types are ever asked for, which
 * is why the API is a {@code compileOnly} dependency and no guard is needed.
 */
public final class DynamicLightsCompat implements DynamicLightsInitializer {

	/**
	 * Light levels a glowing creature spans.
	 * <p>
	 * Topping out below a torch's fourteen. A creature is a moving light source the player has no
	 * control over, and one bright enough to suppress mob spawning wherever it wandered would be a
	 * gameplay change rather than an effect.
	 */
	private static final float MIN_LIGHT = 4f;
	private static final float MAX_LIGHT = 12f;

	/** Set once the light source is actually in the table, so the log line means something. */
	private static boolean announced;

	@Override
	public void onInitializeDynamicLights(DynamicLightsContext context) {
		// Registration is an event rather than a one-off call because LambDynamicLights rebuilds its
		// light-source table on every resource reload — data packs get to contribute — so anything
		// registered once at startup would vanish the first time the player pressed F3+T.
		context.entityLightSourceManager().onRegisterEvent().register(DynamicLightsCompat::onRegister);
	}

	private static void onRegister(EntityLightSourceManager.RegisterContext context) {
		context.register(PrimordiaEntities.CREATURE, Bioluminescence.INSTANCE);
		// Announced from here rather than from the entrypoint above, because reaching the entrypoint
		// only proves the two mods found each other; this proves the creature is in the light table.
		// Once, not once per resource reload.
		if (!announced) {
			announced = true;
			Primordia.LOGGER.info("Bioluminescent creatures will emit light through LambDynamicLights");
		}
	}

	/**
	 * How brightly this creature lights its surroundings, 0 to 15.
	 * <p>
	 * Taken from the same {@code glowStrength} the renderer emits its emissive pass from, so what
	 * lights the cave and what lights the animal are the same number. Deriving it separately — from
	 * the gene, say — would let the two drift apart, and a creature that lit the ground while
	 * appearing dark would be a puzzle with no visible cause.
	 */
	static int luminanceOf(Entity entity) {
		if (!(entity instanceof CreatureEntity creature)) return 0;
		// A player who has turned the glow off has turned it off.
		if (!PrimordiaConfig.get().emissiveGlow) return 0;
		// The lights go out when the animal does.
		if (creature.isCarcass()) return 0;

		BodyPlan plan = creature.getBodyPlan();
		if (plan == null) return 0;

		float strength = plan.palette.glowStrength;
		if (strength <= 0f) return 0;
		// glowStrength runs from 0.25 at the faintest expression to 0.85 at the fullest.
		return Math.round(MathX.clamp(
				MathX.remap(strength, 0.25f, 0.85f, MIN_LIGHT, MAX_LIGHT), 0f, 15f));
	}

	/**
	 * The creature's glow, as a luminance LambDynamicLights can ask for.
	 * <p>
	 * A registered type rather than an anonymous one because {@link EntityLuminance} is codec-backed:
	 * the type is how a luminance names itself when written out, and one that cannot name itself would
	 * break the moment anything tried to serialise the table it sits in. Nothing here is water
	 * sensitive — real bioluminescence mostly lives in the sea — so this deliberately skips
	 * {@code WaterSensitiveEntityLuminance}.
	 */
	private static final class Bioluminescence implements EntityLuminance {
		static final Bioluminescence INSTANCE = new Bioluminescence();
		/** Declared after {@link #INSTANCE} so the unit codec has something to hold. */
		static final Type TYPE = Type.registerSimple(Primordia.id("bioluminescence"), INSTANCE);

		@Override
		public Type type() {
			return TYPE;
		}

		@Override
		public int getLuminance(ItemLightSourceManager itemLightSourceManager, Entity entity) {
			return luminanceOf(entity);
		}
	}
}
