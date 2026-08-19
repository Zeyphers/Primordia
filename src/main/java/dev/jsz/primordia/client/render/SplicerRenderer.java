package dev.jsz.primordia.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.jsz.primordia.Primordia;
import dev.jsz.primordia.block.SplicerBlock;
import dev.jsz.primordia.block.SplicerBlockEntity;
import dev.jsz.primordia.client.model.BbAnimation;
import dev.jsz.primordia.client.model.BbModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Draws the splicer, and runs its sampling cycle only while there is something to sample.
 * <p>
 * <b>The animation is not a decoration on a clock.</b> A model that loops forever off the world time
 * is the easy thing to write and it is wrong here: a machine that gestures at nothing all day reads
 * as scenery, and the one piece of information the player wants from across the room — is my splice
 * done? — is exactly the one it would be throwing away. So the cycle advances only while the block
 * state says the bench is running, and an idle machine holds the animation's <i>first frame</i>.
 * <p>
	 * First frame rather than the modelled bind pose, which is not the same thing: the opening
	 * keyframe already has the carriage four units back down its rail, so an idle bench holds the
	 * start of its cycle, and the first thing it does when work arrives is move off rather than
	 * jump. See {@link BbAnimation#rest()}.
 */
public class SplicerRenderer implements BlockEntityRenderer<SplicerBlockEntity, SplicerRenderState> {

	private static final Identifier GEOMETRY = Primordia.id("geo/splicer.bbmodel");

	/**
	 * The two sheets the model indexes into, in the order the file lists them.
	 * <p>
	 * Named here because Blockbench stores the artist's own filenames — one of which has a space in
	 * it — and a resource identifier cannot hold that. See {@link BbModel#load}.
	 */
	private static final List<Identifier> TEXTURES = List.of(
			Primordia.id("textures/block/splicer.png"),
			Primordia.id("textures/block/splicer_body.png"));

	/**
	 * Which sheets are glass and which are hull, in the same order.
	 * <p>
	 * Not a guess: {@code splicer.png} is 1616 partially transparent pixels against four fully
	 * opaque ones — it is a glass canopy, and cutout renders that as either solid or gone, because
	 * cutout has no opinion between the two. The body sheet is the other way round and stays cutout,
	 * which sorts and depth-writes properly and is what you want for everything that is not glass.
	 */
	private static final boolean[] TRANSLUCENT = {true, false};

	/** Named exactly as the artist named it, trailing space and all; {@code load} trims. */
	private static final String CYCLE = "Sampling";

	/**
	 * <b>No facing correction, deliberately.</b>
	 * <p>
	 * A half turn was added here once and was wrong. The convention for a block entity renderer is
	 * not the convention for a JSON block model: vanilla’s {@code ChestRenderer} applies exactly
	 * {@code Axis.YP.rotationDegrees(-facing.toYRot())} with no offset, and vanilla chest models are
	 * authored with their front on the <i>south</i> face — which is why {@code FACING = SOUTH}
	 * comes out unrotated. This model is authored the same way, with its desk and console at +Z, so
	 * it wants the same formula and nothing else.
	 * <p>
	 * The half turn got added because the machine looked backwards, and it did — but that was the
	 * arm lying flat out of the back of the block for want of its element rotations, not the yaw.
	 * Fixing the geometry and leaving the half turn in place is what actually made it face away.
	 */

	private static BbModel model;
	private static BbAnimation animation;
	private static BbModel.Pose restPose;

	public SplicerRenderer(BlockEntityRendererProvider.Context context) {
	}

	/**
	 * Loads the model on first use rather than in the constructor.
	 * <p>
	 * Renderers are constructed while the resource manager is still being built, so reading a file
	 * from the constructor gets an empty model and no error worth the name. Reloading a resource
	 * pack rebuilds the renderers, which clears these back to null through
	 * {@link #forgetGeometry()}.
	 */
	private static void ensureLoaded() {
		if (model != null) return;
		model = BbModel.load(GEOMETRY, TEXTURES);
		animation = BbAnimation.load(GEOMETRY, CYCLE);
		restPose = animation.rest();
	}

	/** Drops the cached geometry so a resource reload picks up an edited model. */
	public static void forgetGeometry() {
		model = null;
		animation = null;
		restPose = null;
	}

	@Override
	public SplicerRenderState createRenderState() {
		return new SplicerRenderState();
	}

	@Override
	public void extractRenderState(SplicerBlockEntity be, SplicerRenderState state, float partialTick,
	                               Vec3 cameraPos, ModelFeatureRenderer.CrumblingOverlay crumbling) {
		BlockEntityRenderState.extractBase(be, state, crumbling);
		state.facing = be.getBlockState().hasProperty(SplicerBlock.FACING)
				? be.getBlockState().getValue(SplicerBlock.FACING) : Direction.NORTH;
		state.running = be.getBlockState().hasProperty(SplicerBlock.RUNNING)
				&& be.getBlockState().getValue(SplicerBlock.RUNNING);

		// Timed on the client from the moment the running flag arrives, not from the block entity's
		// own counter, which never leaves the server. See SplicerBlockEntity#animationSeconds.
		state.animationSeconds = be.getLevel() == null ? 0f
				: be.animationSeconds(be.getLevel().getGameTime(), partialTick, state.running);
	}

	@Override
	public void submit(SplicerRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
	                   CameraRenderState cameraState) {
		ensureLoaded();
		if (model == null) return;

		BbModel.Pose pose = state.running ? animation.poseAt(state.animationSeconds) : restPose;

		poseStack.pushPose();
		// Rotate about the block's centre, so a machine facing east is the same machine.
		poseStack.translate(0.5f, 0f, 0.5f);
		poseStack.mulPose(Axis.YP.rotationDegrees(-state.facing.toYRot()));
		poseStack.translate(-0.5f, 0f, -0.5f);

		// Hull first, then glass. Translucency has to be drawn over what it is meant to be seen
		// against, and submitting the canopy before the body behind it is how you get a window onto
		// the skybox.
		for (int pass = 0; pass < 2; pass++) {
			boolean glass = pass == 1;
			for (int texture = 0; texture < model.textureCount(); texture++) {
				if (translucent(texture) != glass) continue;
				int index = texture;
				collector.submitCustomGeometry(poseStack,
						glass ? RenderTypes.entityTranslucent(model.texture(index))
								: RenderTypes.entityCutout(model.texture(index)),
						(entry, consumer) -> {
							PoseStack local = new PoseStack();
							local.last().pose().set(entry.pose());
							local.last().normal().set(entry.normal());
							model.render(local, consumer, index, pose, state.lightCoords,
									net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
						});
			}
		}
		poseStack.popPose();

	}

	/** Whether a sheet is drawn translucent. Out of range answers "hull", which is the safe half. */
	private static boolean translucent(int texture) {
		return texture >= 0 && texture < TRANSLUCENT.length && TRANSLUCENT[texture];
	}

	/**
	 * Visible from further off than a block entity's default.
	 * <p>
	 * The whole machine is drawn here rather than by a block model, so at the default distance it
	 * would simply stop existing while its neighbours carried on — and a base is looked at from
	 * across a room more often than from arm's length.
	 */
	@Override
	public int getViewDistance() {
		return 96;
	}
}
