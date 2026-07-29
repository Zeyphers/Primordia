package dev.jsz.primordia.client;

import dev.jsz.primordia.Primordia;
import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.client.config.PrimordiaConfig;
import dev.jsz.primordia.entity.CreatureEntity;
import dev.jsz.primordia.registry.PrimordiaEntities;
import dev.jsz.primordia.util.MathX;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.EntityType;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Makes bioluminescent creatures cast real light, when LambDynamicLights is installed.
 * <p>
 * The mod's own glow is a rendering trick — the emissive pass lights the creature's own markings and
 * nothing else, so a glowing animal in a dark cave is bright while the floor under it stays black.
 * Dynamic lights is what closes that, and a creature that grew light organs ought to light the cave
 * it walks through.
 * <p>
 * <b>Bound by reflection, deliberately.</b> A compile dependency on LambDynamicLights would mean a
 * new maven repository, a version to keep in step, and a build that fails when that repository is
 * unreachable — for an integration that only matters when the player happens to have installed one
 * optional mod. The API surface used here is two types and one method, all resolved through
 * {@link FabricLoader#isModLoaded} first and wrapped so that a change on their side degrades to a
 * log line rather than a crash.
 * <p>
 * The trade is that this cannot be checked by the compiler, so the signatures were read out of the
 * shipped jar rather than guessed:
 * <pre>
 * DynamicLightHandlers.registerDynamicLightHandler(EntityType&lt;T&gt;, DynamicLightHandler&lt;T&gt;)
 * DynamicLightHandler#getLuminance(T) : int
 * DynamicLightHandler#isWaterSensitive(T) : boolean   (default)
 * </pre>
 */
public final class DynamicLightsCompat {
	private static final String LAMBDYNLIGHTS = "lambdynlights";
	private static final String HANDLERS_CLASS = "dev.lambdaurora.lambdynlights.api.DynamicLightHandlers";
	private static final String HANDLER_CLASS = "dev.lambdaurora.lambdynlights.api.DynamicLightHandler";

	/**
	 * Light levels a glowing creature spans.
	 * <p>
	 * Topping out below a torch's fourteen. A creature is a moving light source the player has no
	 * control over, and one bright enough to suppress mob spawning wherever it wandered would be a
	 * gameplay change rather than an effect.
	 */
	private static final float MIN_LIGHT = 4f;
	private static final float MAX_LIGHT = 12f;

	private DynamicLightsCompat() {
	}

	/**
	 * Registers the handler if the mod is present.
	 * <p>
	 * Called once the client has finished starting rather than from the client initialiser, so that
	 * LambDynamicLights has certainly run its own registration first. Nothing here depends on that
	 * ordering today — the handler is keyed to our entity type, which theirs never touches — but the
	 * cost of being certain is one event subscription.
	 */
	public static void register() {
		if (!FabricLoader.getInstance().isModLoaded(LAMBDYNLIGHTS)) return;

		try {
			Class<?> handlers = Class.forName(HANDLERS_CLASS);
			Class<?> handlerType = Class.forName(HANDLER_CLASS);

			Object handler = Proxy.newProxyInstance(
					DynamicLightsCompat.class.getClassLoader(),
					new Class<?>[]{handlerType},
					new Handler());

			handlers.getMethod("registerDynamicLightHandler", EntityType.class, handlerType)
					.invoke(null, PrimordiaEntities.CREATURE, handler);

			Primordia.LOGGER.info("Bioluminescent creatures will emit light through LambDynamicLights");
		} catch (Throwable t) {
			// Never fatal. The creatures still glow on their own surfaces; they simply do not light
			// what is around them.
			Primordia.LOGGER.warn("LambDynamicLights is installed but its light-handler API did not "
					+ "resolve — bioluminescence will not light the world", t);
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
	static int luminanceOf(Object entity) {
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

	/** Bridges the proxy's calls onto {@link #luminanceOf}. */
	private static final class Handler implements InvocationHandler {
		@Override
		public Object invoke(Object proxy, Method method, Object[] args) {
			return switch (method.getName()) {
				case "getLuminance" -> luminanceOf(args[0]);
				// Bioluminescence is not put out by water. Real ones mostly live in it.
				case "isWaterSensitive" -> Boolean.FALSE;
				case "toString" -> "Primordia bioluminescence handler";
				case "hashCode" -> System.identityHashCode(proxy);
				case "equals" -> proxy == args[0];
				default -> throw new UnsupportedOperationException(method.getName());
			};
		}
	}
}
