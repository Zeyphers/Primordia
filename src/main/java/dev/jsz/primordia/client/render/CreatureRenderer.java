package dev.jsz.primordia.client.render;

import dev.jsz.primordia.anim.AnimationContext;
import dev.jsz.primordia.anim.CreatureAnimator;
import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.client.WorldGroundProbe;
import dev.jsz.primordia.entity.CreatureActivity;
import dev.jsz.primordia.entity.CreatureEntity;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.mesh.GenomeMeshCache;
import dev.jsz.primordia.mesh.LodTier;
import dev.jsz.primordia.mesh.MeshData;
import dev.jsz.primordia.mesh.SkinnedMesh;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

/**
 * Renders a procedural creature: pick a LOD tier, fetch the baked mesh for the genome, run the
 * animator, skin the vertices, emit them.
 * <p>
 * There is no {@code EntityModel} here at all — a vanilla model is a fixed tree of boxes decided
 * at compile time, which cannot express a body whose limb count is not known until the entity
 * spawns.
 * <p>
 * Colour lives in the vertices, so every creature in the world shares one flat white texture and
 * therefore one render layer and one draw batch, no matter how many distinct species are on
 * screen.
 */
public class CreatureRenderer extends EntityRenderer<CreatureEntity> {
	/**
	 * Vanilla's 16×16 pure white texture. Vertex colours multiply against it, so it acts as a
	 * neutral base. If a future version drops this asset, ship a 1×1 white PNG and point here.
	 */
	private static final Identifier TEXTURE = Identifier.ofVanilla("textures/misc/white.png");

	/** Shared across every creature: rendering is single-threaded and each is emitted in full. */
	private static final SkinnedMesh SKINNED = new SkinnedMesh();
	private static final LodTier.Budget BUDGET = new LodTier.Budget();

	private final AnimationContext context = new AnimationContext();
	private final WorldGroundProbe probe = new WorldGroundProbe();

	public CreatureRenderer(EntityRendererFactory.Context ctx) {
		super(ctx);
		this.shadowRadius = 0.5f;
		this.shadowOpacity = 0.6f;
	}

	/** Resets the per-frame LOD budget. Hooked to the start of world rendering. */
	public static void beginFrame() {
		BUDGET.reset();
	}

	@Override
	public Identifier getTexture(CreatureEntity entity) {
		return TEXTURE;
	}

	@Override
	public void render(CreatureEntity entity, float yaw, float tickDelta, MatrixStack matrices,
	                   VertexConsumerProvider vertexConsumers, int light) {
		Genome genome = entity.getGenome();
		CreatureAnimator animator = entity.getOrCreateAnimator();
		// The genome arrives a tick or two after the spawn packet; until then there is nothing to draw.
		if (genome == null || animator == null) {
			super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);
			return;
		}

		BodyPlan plan = animator.skeleton().plan;
		this.shadowRadius = MathHelper.clamp(plan.width() * 0.5f, 0.25f, 2.0f);

		Vec3d camera = MinecraftClient.getInstance().gameRenderer.getCamera().getPos();
		double distanceSq = camera.squaredDistanceTo(entity.getX(), entity.getY(), entity.getZ());
		int tier = BUDGET.allocate(distanceSq);

		MeshData mesh = resolveMesh(genome, tier);
		if (mesh == null || mesh.quadCount == 0) {
			// Still baking. Skipping a frame is far better than stalling the render thread.
			super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);
			return;
		}

		fillContext(entity, yaw, tickDelta, tier);
		animator.update(context);
		SKINNED.skin(mesh, animator.skeleton());

		matrices.push();
		// Model space is built facing +Z; Minecraft yaw 0 also faces +Z but increases toward -X,
		// so the model rotates by the negated yaw.
		matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-yaw));
		VertexConsumer consumer = vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(TEXTURE));
		emit(mesh, matrices.peek(), consumer, light);
		matrices.pop();

		super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);
	}

	/**
	 * Returns the mesh for the requested tier, falling back to any coarser tier already baked.
	 * Coarser bakes finish sooner, so a newly-seen species pops in low-detail almost immediately
	 * and sharpens a frame or two later rather than being invisible while the fine bake runs.
	 */
	private MeshData resolveMesh(Genome genome, int tier) {
		MeshData mesh = GenomeMeshCache.getIfReady(genome, tier);
		if (mesh != null) return mesh;
		for (int fallback = tier + 1; fallback < LodTier.COUNT; fallback++) {
			mesh = GenomeMeshCache.getIfReady(genome, fallback);
			if (mesh != null) return mesh;
		}
		return null;
	}

	private void fillContext(CreatureEntity entity, float yaw, float tickDelta, int tier) {
		Vec3d pos = entity.getLerpedPos(tickDelta);
		context.x = pos.x;
		context.y = pos.y;
		context.z = pos.z;

		context.bodyYaw = yaw * MathHelper.RADIANS_PER_DEGREE;

		float headYaw = MathHelper.lerpAngleDegrees(tickDelta, entity.prevHeadYaw, entity.headYaw);
		context.lookYaw = MathHelper.wrapDegrees(headYaw - yaw) * MathHelper.RADIANS_PER_DEGREE;
		context.lookPitch = MathHelper.lerp(tickDelta, entity.prevPitch, entity.getPitch())
				* MathHelper.RADIANS_PER_DEGREE;

		// Per-tick deltas scaled to per-second, which is the unit the animator works in.
		double dx = entity.getX() - entity.prevX;
		double dz = entity.getZ() - entity.prevZ;
		context.speed = (float) Math.sqrt(dx * dx + dz * dz) * 20f;
		context.turnRate = MathHelper.wrapDegrees(entity.bodyYaw - entity.prevBodyYaw)
				* MathHelper.RADIANS_PER_DEGREE * 20f;

		context.time = (entity.age + tickDelta) / 20f;
		context.airborne = !entity.isOnGround();
		context.swimming = entity.isTouchingWater();
		context.tier = tier;
		context.ground = probe.forWorld(entity.getWorld());

		// Activity progress is timed locally from when the client first saw the state change,
		// rather than synced. Attacks last well under a second, so a tick of network skew is
		// invisible, and this keeps an extra tracked field off the wire.
		CreatureActivity activity = entity.getActivity();
		context.activity = activity;
		context.activityProgress = entity.clientActivityProgress(activity, tickDelta);
	}

	private void emit(MeshData mesh, MatrixStack.Entry entry, VertexConsumer consumer, int light) {
		float[] positions = SKINNED.positions();
		float[] normals = SKINNED.normals();
		float[] colors = mesh.colors;
		int[] quads = mesh.quads;
		int overlay = OverlayTexture.DEFAULT_UV;

		for (int i = 0; i < quads.length; i++) {
			int p = quads[i] * 3;
			consumer.vertex(entry, positions[p], positions[p + 1], positions[p + 2])
					.color(colors[p], colors[p + 1], colors[p + 2], 1f)
					// A flat white texture means any UV works; the centre avoids edge bleeding.
					.texture(0.5f, 0.5f)
					.overlay(overlay)
					.light(light)
					.normal(entry, normals[p], normals[p + 1], normals[p + 2]);
		}
	}
}
