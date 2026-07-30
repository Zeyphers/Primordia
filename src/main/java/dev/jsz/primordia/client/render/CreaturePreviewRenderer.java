package dev.jsz.primordia.client.render;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;

/**
 * Draws the creature plates queued by {@link CreaturePreview}.
 * <p>
 * Registered once at client init through Fabric's picture-in-picture registry. The base class does
 * the offscreen target, the projection, the centring translate and the overall scale; everything
 * below is only the model's own orientation.
 */
public class CreaturePreviewRenderer extends PictureInPictureRenderer<CreaturePreviewRenderState> {

	/** Tilt the specimen down slightly, so a plate reads as a mount rather than a side elevation. */
	private static final float PITCH_DEGREES = -12f;

	@Override
	public Class<CreaturePreviewRenderState> getRenderStateClass() {
		return CreaturePreviewRenderState.class;
	}

	/**
	 * Puts the model origin at the middle of the plate rather than its foot.
	 * <p>
	 * The base class returns the full height, which places the origin on the bottom edge — correct
	 * for a vanilla entity, which is modelled standing on its own feet. A creature plate is centred
	 * on the mesh's own midpoint instead, so leaving the default cut the specimen in half.
	 */
	@Override
	protected float getTranslateY(int height, int guiScale) {
		return height / 2f;
	}

	@Override
	protected void renderToTexture(CreaturePreviewRenderState state, PoseStack poseStack,
	                               SubmitNodeCollector collector) {
		// The same lighting vanilla gives an entity shown in a UI. Without it the mesh is lit by
		// whatever the last world pass left set up, which for a book on a night-time screen is
		// nothing at all.
		Minecraft.getInstance().gameRenderer.lighting().setupFor(Lighting.Entry.ENTITY_IN_UI);

		// The base applies scale(s, s, -s), which leaves a Y-up mesh standing on its head. Vanilla's
		// own entity plates fix this in the caller's rotation quaternion; there is no caller here, so
		// the half-turn about X lives with the rest of the orientation. Composed with the base's
		// scale it reproduces the Y-negated scale this drawing used before the port.
		poseStack.mulPose(Axis.XP.rotationDegrees(180f));
		poseStack.mulPose(Axis.XP.rotationDegrees(PITCH_DEGREES));
		poseStack.mulPose(Axis.YP.rotation(state.spin()));
		// Turn the creature about its own middle rather than about the corner of its bounds.
		poseStack.translate(-state.midX(), -state.midY(), -state.midZ());

		collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(CreaturePreview.WHITE),
				(pose, consumer) -> CreaturePreview.emit(state.mesh(), pose, consumer));
	}

	@Override
	protected String getTextureLabel() {
		return "primordia creature preview";
	}
}
