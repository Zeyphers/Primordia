package dev.jsz.primordia.client.render;

import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.mesh.FaceColour;
import dev.jsz.primordia.mesh.FaceNormal;
import dev.jsz.primordia.client.config.PrimordiaConfig;
import dev.jsz.primordia.mesh.GenomeMeshCache;
import dev.jsz.primordia.mesh.LodTier;
import dev.jsz.primordia.mesh.MeshData;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.OverlayTexture;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.resources.Identifier;
import org.joml.Vector2f;
import org.joml.Vector3f;

/**
 * Draws a creature into a GUI, from its genome alone.
 * <p>
 * The bind-pose mesh is used directly rather than the animated one. A guide plate is a specimen
 * illustration, not a live animal: it wants the neutral, symmetrical stance a museum mount has, and
 * driving it through the gait solver would mean carrying an animator, a skeleton and a clock for
 * every entry on the page.
 * <p>
 * Meshes arrive asynchronously — {@link GenomeMeshCache#getIfReady} schedules its own bake and
 * returns null until that finishes — so this reports whether it drew anything and the caller shows
 * a placeholder in the meantime. Blocking on a bake would stall the render thread for a frame or
 * more the first time a page was opened.
 */
public final class CreaturePreview {

	/**
	 * The mod renders untextured: colour lives in the mesh's vertices, over a flat white sheet.
	 * Shared with the world renderer, which owns the asset — see {@link CreatureRenderer#TEXTURE}.
	 */
	static final Identifier WHITE = CreatureRenderer.TEXTURE;
	private static final float UV_LO = 0.25f, UV_HI = 0.75f;

	private CreaturePreview() {
	}

	public static int lodForSize(int boxSize) {
		if (boxSize <= 24) return LodTier.DISTANT;
		if (boxSize <= 48) return LodTier.FAR;
		if (boxSize <= 80) return LodTier.MID;
		return LodTier.NEAR;
	}

	/**
	 * Draws the creature centred in a box, scaled to fit and turned to {@code spin} radians.
	 *
	 * @return false if the mesh is not baked yet, so the caller can draw a placeholder instead
	 */
	public static boolean render(GuiGraphicsExtractor context, Genome genome,
	                             int centreX, int centreY, int boxSize, float spin) {
		return render(context, genome, centreX, centreY, boxSize, spin, lodForSize(boxSize));
	}

	public static boolean render(GuiGraphicsExtractor context, Genome genome,
	                             int centreX, int centreY, int boxSize, float spin, int lodTier) {
		MeshData mesh = GenomeMeshCache.getIfReady(genome, lodTier);
		if (mesh == null) return false;

		float spanX = mesh.maxX - mesh.minX;
		float spanY = mesh.maxY - mesh.minY;
		float spanZ = mesh.maxZ - mesh.minZ;
		float largest = Math.max(spanY, Math.max(spanX, spanZ));
		if (largest <= 1.0e-4f) return false;

		// Fit the longest axis into the box with a margin, so a saurian and an insectoid are both
		// legible at the same plate size rather than one filling it and the other being a speck.
		float scale = boxSize * 0.78f / largest;
		float midX = (mesh.minX + mesh.maxX) * 0.5f;
		float midY = (mesh.minY + mesh.maxY) * 0.5f;
		float midZ = (mesh.minZ + mesh.maxZ) * 0.5f;

		// 26.2 draws GUIs in an extract pass followed by a render pass, so nothing can be painted
		// from here directly. Three-dimensional content is queued as a picture-in-picture state and
		// drawn later by CreaturePreviewRenderer; see that class for the division of labour.
		//
		// A queued state carries its own screen-space bounds and is not put through the current
		// matrix, unlike everything drawn inline. Callers work in panel-local coordinates with the
		// panel offset on the pose, so the centre has to be pushed through that matrix by hand or the
		// plate is drawn at the window's corner instead of the book's.
		Vector2f centre = context.pose().transformPosition(centreX, centreY, new Vector2f());
		int screenX = Math.round(centre.x);
		int screenY = Math.round(centre.y);

		int half = boxSize / 2;
		context.guiRenderState.addPicturesInPictureState(new CreaturePreviewRenderState(
				mesh, spin, midX, midY, midZ,
				screenX - half, screenY - half, screenX + half, screenY + half,
				scale, context.scissorStack.peek()));
		return true;
	}

	/**
	 * Writes the bind-pose mesh out.
	 * <p>
	 * Full brightness: a plate in a book is lit by the book, not by wherever the animal was
	 * standing, and inheriting world light would leave specimens collected at night unreadable.
	 */
	static void emit(MeshData mesh, PoseStack.Pose entry, VertexConsumer consumer) {
		float[] positions = mesh.positions;
		float[] normals = mesh.normals;
		float[] colors = mesh.colors;
		int[] quads = mesh.quads;
		int light = 0xF000F0;

		// The plate should show the animal the way the world does, so it reads the same settings.
		PrimordiaConfig config = PrimordiaConfig.get();
		boolean sharp = config.sharpShading;
		boolean flatColour = config.flatFaceColour;
		Vector3f face = new Vector3f();
		Vector3f tint = new Vector3f();

		for (int i = 0; i < quads.length; i += 4) {
			if (sharp) FaceNormal.compute(positions, normals, quads, i, face);
			if (flatColour) FaceColour.average(colors, quads, i, tint);
			for (int k = 0; k < 4; k++) {
				int v = quads[i + k];
				int p = v * 3;

				float u = (k == 1 || k == 2) ? UV_HI : UV_LO;
				float t = k >= 2 ? UV_HI : UV_LO;

				float nx = sharp ? face.x : normals[p];
				float ny = sharp ? face.y : normals[p + 1];
				float nz = sharp ? face.z : normals[p + 2];

				consumer.addVertex(entry, positions[p], positions[p + 1], positions[p + 2])
						.setColor(flatColour ? tint.x : colors[p],
								flatColour ? tint.y : colors[p + 1],
								flatColour ? tint.z : colors[p + 2], 1f)
						.setUv(u, t)
						.setOverlay(OverlayTexture.NO_OVERLAY)
						.setLight(light)
						.setNormal(entry, nx, ny, nz);
			}
		}
	}
}
