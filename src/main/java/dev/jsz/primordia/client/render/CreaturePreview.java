package dev.jsz.primordia.client.render;

import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.mesh.FaceColour;
import dev.jsz.primordia.mesh.FaceNormal;
import dev.jsz.primordia.client.config.PrimordiaConfig;
import dev.jsz.primordia.mesh.GenomeMeshCache;
import dev.jsz.primordia.mesh.LodTier;
import dev.jsz.primordia.mesh.MeshData;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
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

	/** The mod renders untextured: colour lives in the mesh's vertices, over a flat white sheet. */
	private static final Identifier WHITE = Identifier.ofVanilla("textures/misc/white.png");
	private static final float UV_LO = 0.25f, UV_HI = 0.75f;

	private CreaturePreview() {
	}

	/**
	 * Draws the creature centred in a box, scaled to fit and turned to {@code spin} radians.
	 *
	 * @return false if the mesh is not baked yet, so the caller can draw a placeholder instead
	 */
	public static boolean render(DrawContext context, Genome genome,
	                             int centreX, int centreY, int boxSize, float spin) {
		MeshData mesh = GenomeMeshCache.getIfReady(genome, LodTier.NEAR);
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

		MatrixStack matrices = context.getMatrices();
		matrices.push();
		matrices.translate(centreX, centreY, 200.0);
		// Y is inverted in GUI space; Z is scaled with the rest so a deep body is not clipped by
		// the depth range while it turns side-on.
		matrices.scale(scale, -scale, scale);
		// A slight downward tilt reads as a specimen on a stand rather than an orthographic blueprint.
		matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-12f));
		matrices.multiply(RotationAxis.POSITIVE_Y.rotation(spin));
		matrices.translate(-midX, -midY, -midZ);

		DiffuseLighting.enableGuiDepthLighting();
		VertexConsumerProvider.Immediate consumers = context.getVertexConsumers();
		RenderLayer layer = RenderLayer.getEntityCutoutNoCull(WHITE);
		emit(mesh, matrices.peek(), consumers.getBuffer(layer));

		// Only this layer. The provider handed out here is the screen's shared buffer, and it is
		// still holding every item and glyph drawn so far this frame — the tab icons among them,
		// because DrawContext queues those rather than rasterising them on the spot.
		//
		// A bare draw() flushes all of it, wherever the caller happens to be. Drawn from inside the
		// bloodlines viewport that meant the tab strip was rasterised under this method's lighting
		// and inside its scissor rectangle, so the icons came out dimmed and clipped — and since
		// each visible node did it again, zooming out until more nodes fit made it worse, and
		// hovering (which changes what is queued when) made it flicker.
		consumers.draw(layer);
		DiffuseLighting.enableGuiDepthLighting();

		matrices.pop();
		return true;
	}

	/**
	 * Writes the bind-pose mesh out.
	 * <p>
	 * Full brightness: a plate in a book is lit by the book, not by wherever the animal was
	 * standing, and inheriting world light would leave specimens collected at night unreadable.
	 */
	private static void emit(MeshData mesh, MatrixStack.Entry entry, VertexConsumer consumer) {
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
			if (sharp) FaceNormal.compute(positions, quads, i, face);
			if (flatColour) FaceColour.average(colors, quads, i, tint);
			for (int k = 0; k < 4; k++) {
				int v = quads[i + k];
				int p = v * 3;

				float u = (k == 1 || k == 2) ? UV_HI : UV_LO;
				float t = k >= 2 ? UV_HI : UV_LO;

				float nx = sharp ? face.x : normals[p];
				float ny = sharp ? face.y : normals[p + 1];
				float nz = sharp ? face.z : normals[p + 2];

				consumer.vertex(entry, positions[p], positions[p + 1], positions[p + 2])
						.color(flatColour ? tint.x : colors[p],
								flatColour ? tint.y : colors[p + 1],
								flatColour ? tint.z : colors[p + 2], 1f)
						.texture(u, t)
						.overlay(OverlayTexture.DEFAULT_UV)
						.light(light)
						.normal(entry, nx, ny, nz);
			}
		}
	}
}
