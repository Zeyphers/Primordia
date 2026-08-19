package dev.jsz.primordia.client.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.jsz.primordia.Primordia;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.joml.Vector3f;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A Blockbench animation, evaluated at a time.
 * <p>
 * Only the {@code position} channel is read, because that is all the splicer's sampling cycle uses:
 * a gantry carriage travelling in Z, a head travelling in X, and a probe plunging in Y. A rotation
 * channel would need the bone pivot rather than a plain translate, and nothing here has one — so it
 * is not implemented rather than implemented wrongly and left to rot until something needs it.
 * <p>
 * <b>Bezier keyframes are evaluated properly.</b> Blockbench's default handles are flat, which is an
 * ease in and out of every keyframe; approximating that with a straight line would make a machine
 * that starts and stops dead at each waypoint, and the difference between that and the authored
 * motion is precisely the difference between a mechanism and a slideshow.
 */
public final class BbAnimation {

	/** One keyframe: a time, a value, and the two handles that shape the curve either side of it. */
	private record Key(float time, Vector3f value,
	                   Vector3f leftTime, Vector3f leftValue,
	                   Vector3f rightTime, Vector3f rightValue,
	                   boolean bezier) {
	}

	private final Map<String, List<Key>> tracks = new HashMap<>();
	private final float length;
	private final boolean loops;

	private BbAnimation(float length, boolean loops) {
		this.length = length;
		this.loops = loops;
	}

	/** Seconds one full cycle takes. */
	public float length() {
		return length;
	}

	public boolean loops() {
		return loops;
	}

	/**
	 * Reads one animation, by name, out of a {@code .bbmodel}.
	 * <p>
	 * By name rather than by index so that an artist adding a second animation ahead of this one in
	 * the file cannot silently swap which motion the machine plays. Names are trimmed, because
	 * Blockbench keeps whatever trailing space was typed into the field.
	 */
	public static BbAnimation load(Identifier path, String name) {
		try (BufferedReader reader = Minecraft.getInstance().getResourceManager()
				.openAsReader(path)) {
			JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
			if (json.has("animations")) {
				for (JsonElement raw : json.getAsJsonArray("animations")) {
					JsonObject animation = raw.getAsJsonObject();
					String found = animation.has("name") ? animation.get("name").getAsString().trim() : "";
					if (found.equals(name)) return fromJson(animation);
				}
			}
			Primordia.LOGGER.error("No animation named '{}' in {}", name, path);
		} catch (Exception e) {
			Primordia.LOGGER.error("Could not read animation {} from {}", name, path, e);
		}
		return new BbAnimation(0f, false);
	}

	/**
	 * Builds an animation from an already-parsed {@code animations} entry.
	 * <p>
	 * Split out from {@link #load} for the reason {@code BbModel.fromJson} is: the keyframe and
	 * bezier arithmetic is the part worth testing and it needs no game at all.
	 */
	public static BbAnimation fromJson(JsonObject animation) {
		float length = animation.has("length") ? animation.get("length").getAsFloat() : 0f;
		boolean loops = "loop".equals(animation.has("loop")
				? animation.get("loop").getAsString() : "once");
		BbAnimation out = new BbAnimation(length, loops);

		if (!animation.has("animators")) return out;
		for (Map.Entry<String, JsonElement> entry : animation.getAsJsonObject("animators").entrySet()) {
			JsonObject animator = entry.getValue().getAsJsonObject();
			String bone = animator.has("name") ? animator.get("name").getAsString() : entry.getKey();
			if (!animator.has("keyframes")) continue;

			List<Key> keys = new ArrayList<>();
			for (JsonElement rawKey : animator.getAsJsonArray("keyframes")) {
				JsonObject keyframe = rawKey.getAsJsonObject();
				if (!"position".equals(keyframe.get("channel").getAsString())) continue;
				JsonArray points = keyframe.getAsJsonArray("data_points");
				if (points.isEmpty()) continue;
				JsonObject point = points.get(0).getAsJsonObject();
				keys.add(new Key(
						keyframe.get("time").getAsFloat(),
						new Vector3f(num(point, "x"), num(point, "y"), num(point, "z")),
						vec(keyframe, "bezier_left_time"), vec(keyframe, "bezier_left_value"),
						vec(keyframe, "bezier_right_time"), vec(keyframe, "bezier_right_value"),
						"bezier".equals(optString(keyframe, "interpolation"))));
			}
			// Blockbench writes keyframes in edit order, not in time order.
			keys.sort((a, b) -> Float.compare(a.time(), b.time()));
			if (!keys.isEmpty()) out.tracks.put(bone, keys);
		}
		return out;
	}

	/**
	 * Where every animated bone sits at {@code seconds}.
	 * <p>
	 * Returned as a {@link BbModel.Pose} so the renderer never sees a keyframe. A bone with no track
	 * answers null and is drawn exactly as authored, which is what keeps a partly animated model
	 * from needing every bone listed.
	 */
	public BbModel.Pose poseAt(float seconds) {
		float time = length <= 0f ? 0f
				: loops ? seconds % length : Math.min(seconds, length);
		Map<String, Vector3f> offsets = new HashMap<>();
		for (Map.Entry<String, List<Key>> track : tracks.entrySet()) {
			offsets.put(track.getKey(), sample(track.getValue(), time));
		}
		return offsets::get;
	}

	/**
	 * The pose at rest: every bone at the value its first keyframe holds.
	 * <p>
	 * <b>Not</b> the model as authored, and the difference is deliberate. The opening keyframe of
	 * the splicer's cycle already displaces the carriage down its rail, so an idle machine parked on
	 * frame one is parked where its cycle begins — and the first thing it does when work arrives is
	 * move off, rather than jump to the start and then move off.
	 */
	public BbModel.Pose rest() {
		return poseAt(0f);
	}

	private static Vector3f sample(List<Key> keys, float time) {
		if (keys.isEmpty()) return new Vector3f();
		if (time <= keys.get(0).time()) return new Vector3f(keys.get(0).value());
		Key last = keys.get(keys.size() - 1);
		if (time >= last.time()) return new Vector3f(last.value());

		for (int i = 0; i < keys.size() - 1; i++) {
			Key a = keys.get(i);
			Key b = keys.get(i + 1);
			if (time < a.time() || time > b.time()) continue;
			float span = b.time() - a.time();
			if (span <= 1e-6f) return new Vector3f(b.value());
			float t = (time - a.time()) / span;

			Vector3f out = new Vector3f();
			for (int axis = 0; axis < 3; axis++) {
				float from = component(a.value(), axis);
				float to = component(b.value(), axis);
				float value;
				if (a.bezier() || b.bezier()) {
					value = bezier(a.time(), from,
							a.time() + component(a.rightTime(), axis), from + component(a.rightValue(), axis),
							b.time() + component(b.leftTime(), axis), to + component(b.leftValue(), axis),
							b.time(), to, time);
				} else {
					value = from + (to - from) * t;
				}
				setComponent(out, axis, value);
			}
			return out;
		}
		return new Vector3f(last.value());
	}

	/**
	 * A cubic bezier through two keyframes, solved for the value at a given <i>time</i>.
	 * <p>
	 * The curve is parametric, so its X is time and its Y is the value, and the parameter that
	 * produces a given time is not that time. Solved by bisection rather than by Newton's method:
	 * the handles come out of a modelling tool and nothing stops an artist dragging one until the
	 * curve doubles back, at which point a derivative-based solve wanders off and a bisection simply
	 * converges on one of the answers. Twenty steps is well inside a float's precision over a span
	 * that is never more than a few seconds.
	 */
	private static float bezier(float x0, float y0, float x1, float y1,
	                            float x2, float y2, float x3, float y3, float x) {
		float lo = 0f, hi = 1f;
		for (int i = 0; i < 20; i++) {
			float mid = (lo + hi) * 0.5f;
			if (cubic(x0, x1, x2, x3, mid) < x) lo = mid; else hi = mid;
		}
		return cubic(y0, y1, y2, y3, (lo + hi) * 0.5f);
	}

	private static float cubic(float a, float b, float c, float d, float t) {
		float u = 1f - t;
		return u * u * u * a + 3f * u * u * t * b + 3f * u * t * t * c + t * t * t * d;
	}

	private static float component(Vector3f v, int axis) {
		return axis == 0 ? v.x : axis == 1 ? v.y : v.z;
	}

	private static void setComponent(Vector3f v, int axis, float value) {
		if (axis == 0) v.x = value;
		else if (axis == 1) v.y = value;
		else v.z = value;
	}

	/** Blockbench stores keyframe values as expression strings, which are usually just numbers. */
	private static float num(JsonObject object, String key) {
		if (!object.has(key)) return 0f;
		try {
			return Float.parseFloat(object.get(key).getAsString().trim());
		} catch (NumberFormatException e) {
			// A real expression — "math.sin(query.anim_time * 90)" and friends. Not supported, and
			// zero is the honest answer rather than a guess that would animate wrongly.
			return 0f;
		}
	}

	private static Vector3f vec(JsonObject object, String key) {
		if (!object.has(key) || !object.get(key).isJsonArray()) return new Vector3f();
		JsonArray array = object.getAsJsonArray(key);
		if (array.size() < 3) return new Vector3f();
		return new Vector3f(array.get(0).getAsFloat(), array.get(1).getAsFloat(),
				array.get(2).getAsFloat());
	}

	private static String optString(JsonObject object, String key) {
		return object.has(key) && object.get(key).isJsonPrimitive()
				? object.get(key).getAsString() : null;
	}
}
