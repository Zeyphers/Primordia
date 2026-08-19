package dev.jsz.primordia.client.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.jsz.primordia.Primordia;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3f;
import org.joml.Vector3f;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A Blockbench model, loaded as it was authored.
 * <p>
 * The splicer is a free-form <b>mesh</b> model — arbitrary polygons with per-face UVs — which the
 * vanilla block model format cannot express at any level of effort: that format is axis-aligned
 * cuboids with box UVs, and this machine is not made of those. Nor can a block model move: the
 * sampling head travels on a gantry, and a JSON model has no bones. So the model is read as-is and
 * drawn by a block entity renderer, which is the same thing {@code CreatureRenderer} already does
 * for creature meshes and uses the same {@code submitCustomGeometry} path.
 * <p>
 * Reading the {@code .bbmodel} directly rather than converting it at build time is deliberate. The
 * conversion would be a second copy of the model that has to be regenerated whenever the artist
 * saves, and the failure mode of forgetting is a machine that silently renders last week's shape.
 * The file the artist edits is the file the game loads.
 * <p>
 * <b>Coordinates.</b> Blockbench works in sixteenths of a block with Y up, and the splicer's body is
 * authored as a clean 16 x 15 x 16 centred on the origin in X and Z. So model space maps to
 * block-local space by dividing by 16 and shifting the horizontal axes by half a block; see
 * {@link #MODEL_TO_BLOCK} and {@link #render}.
 */
public final class BbModel {

	/** Sixteenths of a block to blocks. */
	public static final float MODEL_TO_BLOCK = 1f / 16f;

	/**
	 * One polygon, flattened.
	 * <p>
	 * Positions are model space; UVs are already normalised against the texture size the file
	 * declares, so the renderer never has to know how big the sheet is. Blockbench meshes may hold
	 * triangles as well as quads; this model is all quads, and a triangle would be emitted as a quad
	 * with a doubled last vertex, which is what the quad-only render pipeline wants anyway.
	 */
	public record Face(float[] positions, float[] uvs, Vector3f normal, int texture) {
	}

	/**
	 * A named group from the outliner, with its pivot and its children.
	 * <p>
	 * Elements that sit outside every group are collected into a synthetic unnamed root, so drawing
	 * is one uniform recursion rather than a special case for loose geometry.
	 */
	public static final class Bone {
		public final String name;
		public final Vector3f pivot;
		/** Euler degrees, applied about {@link #pivot}. Usually zero. */
		public final Vector3f rotation;
		public final List<Face> faces = new ArrayList<>();
		public final List<Bone> children = new ArrayList<>();

		Bone(String name, Vector3f pivot, Vector3f rotation) {
			this.name = name;
			this.pivot = pivot;
			this.rotation = rotation;
		}

		boolean rotated() {
			return rotation.lengthSquared() > 1e-9f;
		}
	}

	private final Bone root;
	private final List<Identifier> textures;

	private BbModel(Bone root, List<Identifier> textures) {
		this.root = root;
		this.textures = textures;
	}

	public Bone root() {
		return root;
	}

	public Identifier texture(int index) {
		return textures.get(Math.max(0, Math.min(index, textures.size() - 1)));
	}

	public int textureCount() {
		return textures.size();
	}

	// ------------------------------------------------------------------ loading

	/**
	 * Reads a {@code .bbmodel} out of the resource pack.
	 * <p>
	 * The texture list is supplied by the caller rather than taken from the file's own texture
	 * names, because Blockbench stores those as the artist's local filenames — {@code texture.png},
	 * {@code Body and Carrage.png} — and a resource identifier cannot be derived from a name with a
	 * space in it. The order matches the file's texture array, which is what faces index into.
	 */
	public static BbModel load(Identifier path, List<Identifier> textures) {
		try (BufferedReader reader = Minecraft.getInstance().getResourceManager()
				.openAsReader(path)) {
			JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
			return fromJson(json, textures);
		} catch (Exception e) {
			Primordia.LOGGER.error("Could not read model {}", path, e);
			return new BbModel(new Bone("root", new Vector3f(), new Vector3f()), textures);
		}
	}

	/**
	 * Builds a model from already-parsed json.
	 * <p>
	 * Split out from {@link #load} so the loader can be exercised against the real
	 * {@code .bbmodel} without a running game — the parsing is where the bugs live, and none of it
	 * needs a resource manager. See {@code SplicerModelTest}.
	 */
	public static BbModel fromJson(JsonObject json, List<Identifier> textures) {
		float texW = 64f, texH = 64f;
		if (json.has("resolution")) {
			JsonObject res = json.getAsJsonObject("resolution");
			texW = res.get("width").getAsFloat();
			texH = res.get("height").getAsFloat();
		}

		// Elements by uuid, each already baked into faces in model space.
		Map<String, List<Face>> elements = new HashMap<>();
		for (JsonElement raw : json.getAsJsonArray("elements")) {
			JsonObject element = raw.getAsJsonObject();
			// Only mesh elements are handled: this loader exists for a mesh model, and silently
			// dropping a cube would be worse than not claiming to support one.
			if (!"mesh".equals(optString(element, "type"))) continue;
			elements.put(element.get("uuid").getAsString(), bakeMesh(element, texW, texH));
		}

		// Group definitions carry the pivots; the outliner carries the tree.
		Map<String, JsonObject> groups = new HashMap<>();
		if (json.has("groups")) {
			for (JsonElement raw : json.getAsJsonArray("groups")) {
				JsonObject group = raw.getAsJsonObject();
				groups.put(group.get("uuid").getAsString(), group);
			}
		}

		Bone root = new Bone("root", new Vector3f(), new Vector3f());
		buildTree(json.getAsJsonArray("outliner"), root, groups, elements);
		return new BbModel(root, textures);
	}

	private static void buildTree(JsonArray nodes, Bone parent, Map<String, JsonObject> groups,
	                              Map<String, List<Face>> elements) {
		for (JsonElement node : nodes) {
			if (node.isJsonPrimitive()) {
				List<Face> faces = elements.get(node.getAsString());
				if (faces != null) parent.faces.addAll(faces);
				continue;
			}
			JsonObject group = node.getAsJsonObject();
			String uuid = group.get("uuid").getAsString();
			JsonObject definition = groups.get(uuid);
			// The outliner entry is a reference; the pivot and the name live in the groups array.
			String name = definition != null ? optString(definition, "name") : null;
			Vector3f pivot = definition != null && definition.has("origin")
					? vec(definition.getAsJsonArray("origin")) : new Vector3f();
			Vector3f rotation = definition != null && definition.has("rotation")
					? vec(definition.getAsJsonArray("rotation")) : new Vector3f();
			Bone bone = new Bone(name == null ? uuid : name, pivot, rotation);
			parent.children.add(bone);
			if (group.has("children")) {
				buildTree(group.getAsJsonArray("children"), bone, groups, elements);
			}
		}
	}

	/**
	 * Turns one mesh element into flat quads, in model space.
	 * <p>
	 * Vertices are stored relative to the element's own origin and <b>rotated about it</b>, so both
	 * are folded in here and nothing downstream has to carry either. Normals are computed from the
	 * winding rather than read, because the format does not store them.
	 * <p>
	 * The rotation is not optional detail. The splicer's gantry beam is authored lying along one
	 * axis and turned ninety degrees into place, and its sampling head and probe are turned upright
	 * the same way — so an importer that drops element rotations does not produce a slightly wrong
	 * machine, it produces one whose entire arm assembly is lying flat behind the body. That is
	 * exactly what this looked like before the rotations were applied.
	 */
	private static List<Face> bakeMesh(JsonObject element, float texW, float texH) {
		Vector3f origin = element.has("origin")
				? vec(element.getAsJsonArray("origin")) : new Vector3f();
		Vector3f rotation = element.has("rotation")
				? vec(element.getAsJsonArray("rotation")) : new Vector3f();
		Matrix3f spin = eulerMatrix(rotation);

		Map<String, Vector3f> vertices = new HashMap<>();
		if (element.has("vertices")) {
			for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject("vertices").entrySet()) {
				Vector3f local = vec(entry.getValue().getAsJsonArray());
				if (spin != null) spin.transform(local);
				vertices.put(entry.getKey(), local.add(origin));
			}
		}

		List<Face> out = new ArrayList<>();
		if (!element.has("faces")) return out;
		for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject("faces").entrySet()) {
			JsonObject face = entry.getValue().getAsJsonObject();
			if (!face.has("vertices")) continue;
			JsonArray order = face.getAsJsonArray("vertices");
			if (order.size() < 3) continue;

			JsonObject uvMap = face.has("uv") ? face.getAsJsonObject("uv") : new JsonObject();
			int texture = face.has("texture") && face.get("texture").isJsonPrimitive()
					? face.get("texture").getAsInt() : 0;

			float[] positions = new float[12];
			float[] uvs = new float[8];
			for (int i = 0; i < 4; i++) {
				// A triangle is emitted as a quad with its last vertex doubled: the pipeline draws
				// quads, and a degenerate edge costs one vertex rather than a second render path.
				int source = Math.min(i, order.size() - 1);
				String key = order.get(source).getAsString();
				Vector3f position = vertices.getOrDefault(key, new Vector3f());
				positions[i * 3] = position.x;
				positions[i * 3 + 1] = position.y;
				positions[i * 3 + 2] = position.z;
				if (uvMap.has(key)) {
					JsonArray uv = uvMap.getAsJsonArray(key);
					uvs[i * 2] = uv.get(0).getAsFloat() / texW;
					uvs[i * 2 + 1] = uv.get(1).getAsFloat() / texH;
				}
			}
			out.add(new Face(positions, uvs, normalOf(positions), texture));
		}
		return out;
	}

	/**
	 * Face normal from the winding.
	 * <p>
	 * Taken across the two diagonals rather than from two adjacent edges, so a quad with one nearly
	 * degenerate edge — which a hand-modelled mesh will have — still produces a stable direction
	 * instead of a zero vector.
	 */
	private static Vector3f normalOf(float[] p) {
		Vector3f d1 = new Vector3f(p[6] - p[0], p[7] - p[1], p[8] - p[2]);
		Vector3f d2 = new Vector3f(p[9] - p[3], p[10] - p[4], p[11] - p[5]);
		Vector3f normal = d1.cross(d2, new Vector3f());
		if (normal.lengthSquared() < 1e-9f) return new Vector3f(0f, 1f, 0f);
		return normal.normalize();
	}

	/**
	 * A Blockbench Euler rotation, in degrees, as a matrix — or null when there is no rotation.
	 * <p>
	 * Applied X then Y then Z, which is the order Blockbench itself uses. Every rotated element in
	 * the splicer turns about a single axis, so the order does not currently change the result; it
	 * is written down correctly anyway, because the first model that rotates about two axes at once
	 * would otherwise come apart in a way that is very hard to look at and diagnose.
	 */
	private static Matrix3f eulerMatrix(Vector3f degrees) {
		if (degrees.lengthSquared() < 1e-9f) return null;
		return new Matrix3f().rotateXYZ(
				(float) Math.toRadians(degrees.x),
				(float) Math.toRadians(degrees.y),
				(float) Math.toRadians(degrees.z));
	}

	private static Vector3f vec(JsonArray array) {
		return new Vector3f(array.get(0).getAsFloat(), array.get(1).getAsFloat(),
				array.get(2).getAsFloat());
	}

	private static String optString(JsonObject object, String key) {
		return object.has(key) && object.get(key).isJsonPrimitive()
				? object.get(key).getAsString() : null;
	}

	// ------------------------------------------------------------------ drawing

	/**
	 * Where each bone has been moved to, this frame. Bones absent from the map are at rest.
	 * <p>
	 * Keyed by bone name rather than by uuid so the renderer can talk about "the carriage" without
	 * carrying a table of hex strings, and so an artist renaming a bone breaks visibly rather than
	 * silently animating nothing.
	 */
	public interface Pose {
		Vector3f offsetOf(String bone);
	}

	/** A pose in which nothing has moved: the model exactly as authored. */
	public static final Pose REST = bone -> null;

	/**
	 * Draws every face that uses one texture.
	 * <p>
	 * Called once per texture rather than once per model, because a render type is bound to a single
	 * sheet and this model uses two. Faces carry their texture index from the file, so the split is
	 * the artist's and not a guess.
	 */
	public void render(PoseStack poseStack, VertexConsumer consumer, int textureIndex,
	                   Pose pose, int light, int overlay) {
		poseStack.pushPose();
		// Blockbench sixteenths to block-local, with the horizontal axes shifted because the model
		// is centred on its origin and a block's own space starts at its corner.
		poseStack.translate(0.5f, 0f, 0.5f);
		poseStack.scale(MODEL_TO_BLOCK, MODEL_TO_BLOCK, MODEL_TO_BLOCK);
		renderBone(root, poseStack, consumer, textureIndex, pose, light, overlay);
		poseStack.popPose();
	}

	private void renderBone(Bone bone, PoseStack poseStack, VertexConsumer consumer,
	                        int textureIndex, Pose pose, int light, int overlay) {
		poseStack.pushPose();

		Vector3f offset = pose.offsetOf(bone.name);
		if (offset != null) {
			// Position only: this model animates by translation. A rotation channel would compose
			// here, about the same pivot the static rotation below uses.
			poseStack.translate(offset.x, offset.y, offset.z);
		}

		if (bone.rotated()) {
			// About the pivot, which is what a pivot is for: translate the pivot to the origin,
			// turn, and put it back.
			poseStack.translate(bone.pivot.x, bone.pivot.y, bone.pivot.z);
			poseStack.mulPose(new org.joml.Quaternionf().rotationXYZ(
					(float) Math.toRadians(bone.rotation.x),
					(float) Math.toRadians(bone.rotation.y),
					(float) Math.toRadians(bone.rotation.z)));
			poseStack.translate(-bone.pivot.x, -bone.pivot.y, -bone.pivot.z);
		}

		PoseStack.Pose entry = poseStack.last();
		for (Face face : bone.faces) {
			if (face.texture() != textureIndex) continue;
			float[] p = face.positions();
			float[] uv = face.uvs();
			Vector3f n = face.normal();
			for (int i = 0; i < 4; i++) {
				consumer.addVertex(entry, p[i * 3], p[i * 3 + 1], p[i * 3 + 2])
						.setColor(1f, 1f, 1f, 1f)
						.setUv(uv[i * 2], uv[i * 2 + 1])
						.setOverlay(overlay)
						.setLight(light)
						.setNormal(entry, n.x, n.y, n.z);
			}
		}

		for (Bone child : bone.children) {
			renderBone(child, poseStack, consumer, textureIndex, pose, light, overlay);
		}
		poseStack.popPose();
	}
}
