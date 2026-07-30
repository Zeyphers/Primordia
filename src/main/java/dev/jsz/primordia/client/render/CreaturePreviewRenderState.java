package dev.jsz.primordia.client.render;

import dev.jsz.primordia.mesh.MeshData;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;

/**
 * One creature plate queued for drawing, as {@link CreaturePreviewRenderer} needs it.
 * <p>
 * 26.2 draws GUIs in two passes: screens <i>extract</i> what they want drawn into render state
 * objects, and the renderer walks those later. Anything three-dimensional goes through the
 * picture-in-picture path, which renders it to an offscreen texture and blits that into the panel —
 * which is how a GUI gets depth testing and its own lighting without disturbing the flat elements
 * around it.
 * <p>
 * The mesh is referenced, not copied. Bind-pose meshes are immutable once baked and shared out of
 * {@link dev.jsz.primordia.mesh.GenomeMeshCache}, so there is nothing here that a later frame could
 * mutate underneath the renderer — unlike a live creature, whose pose genuinely does change between
 * extract and submit.
 *
 * @param mesh  the baked bind-pose mesh
 * @param spin  rotation about the vertical axis, in radians
 * @param midX  mesh centre, subtracted so the creature turns about itself rather than its corner
 * @param scale model units to GUI pixels — the factor that fits the mesh's longest axis into the
 *              plate. The base renderer multiplies this by the window's GUI scale and applies it
 *              before {@code renderToTexture}, so nothing downstream scales again.
 */
public record CreaturePreviewRenderState(
		MeshData mesh,
		float spin,
		float midX, float midY, float midZ,
		int x0, int y0, int x1, int y1,
		float scale,
		ScreenRectangle scissorArea,
		ScreenRectangle bounds
) implements PictureInPictureRenderState {

	public CreaturePreviewRenderState(MeshData mesh, float spin,
	                                  float midX, float midY, float midZ,
	                                  int x0, int y0, int x1, int y1,
	                                  float scale, ScreenRectangle scissorArea) {
		// getBounds takes the corners in (x0, y0, x1, y1) order, which is not the order the record's
		// own accessors are declared in — it builds ScreenRectangle(left, top, x1 - x0, y1 - y0).
		this(mesh, spin, midX, midY, midZ, x0, y0, x1, y1, scale, scissorArea,
				PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissorArea));
	}
}
