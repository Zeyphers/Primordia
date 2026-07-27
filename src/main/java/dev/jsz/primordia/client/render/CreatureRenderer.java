package dev.jsz.primordia.client.render;

import dev.jsz.primordia.anim.AnimationContext;
import dev.jsz.primordia.anim.CreatureAnimator;
import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.client.WorldGroundProbe;
import dev.jsz.primordia.client.config.PrimordiaConfig;
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
		// Test specimens render at full detail regardless of distance or crowding, for two
		// reasons. Comparison: a grid whose near rows are finely meshed and whose far rows are
		// coarse cannot be used to judge a change, because half the difference on screen is the
		// LOD and not the generator. Animation: IK only runs on the closer tiers, so on a grid
		// sixty blocks deep most of the specimens would stand with their legs frozen in the bind
		// pose no matter what the walk flag said — which is exactly what "the walk command
		// doesn't work" looked like. Thirty creatures at near detail is the cost of the rig, and
		// the quality presets are there to pay it.
		int tier = entity.isPosing() ? LodTier.NEAR : BUDGET.allocate(distanceSq);

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
		emitGlow(mesh, matrices.peek(), vertexConsumers);
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
		// A posed specimen is not moving, so the measured speed is zero and the gait would never
		// run. Feeding the animator a nominal walking speed cycles the legs on the spot, which is
		// the whole point of the test rig — you cannot judge a walk cycle by chasing it.
		if (entity.isPosing()) {
			context.speed = entity.isPoseWalking() ? CreatureEntity.POSE_WALK_SPEED : 0f;
			// A specimen that never moves never sends a movement packet, so the client has no
			// reason to believe it is standing on anything and reports it airborne. The gait
			// refuses to run in mid-air — correctly, for a falling creature — which is why the
			// grid stood still no matter what the walk flag said.
			context.airborne = false;
			// Its head yaw is never driven either, so it sits at whatever the tracker last had
			// while the body faces the player: the difference is a large angle, and the animator
			// clamps it to the limit, which is every specimen craning hard left or right.
			context.lookYaw = 0f;
			context.lookPitch = 0f;
			context.turnRate = 0f;
			context.riderSteer = 0f;
		}
		context.turnRate = MathHelper.wrapDegrees(entity.bodyYaw - entity.prevBodyYaw)
				* MathHelper.RADIANS_PER_DEGREE * 20f;

		// Steering intent, only while someone is actually driving. Measured against the body
		// rather than the head so it survives the head easing that riding already applies.
		context.riderSteer = 0f;
		if (entity.getControllingPassenger() instanceof net.minecraft.entity.LivingEntity rider) {
			context.riderSteer = MathHelper.wrapDegrees(rider.getYaw() - yaw)
					* MathHelper.RADIANS_PER_DEGREE;
		}

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

	/**
	 * Texture coordinates of a quad's four corners.
	 * <p>
	 * The texture is flat white, so <i>visually</i> any UV would do — but every vertex sharing one
	 * UV is not visually neutral under a shader pack. Iris derives each face's tangent from its UV
	 * deltas, and a quad whose four corners have identical UVs has a zero-area UV triangle: the
	 * determinant is zero, tangent generation falls back to an axis picked per face from the face
	 * normal, and that fallback flips direction between neighbouring quads. Any pack doing
	 * normal-mapped lighting then shades each quad off a different tangent basis, which is what
	 * draws the mesh's own quad grid onto the creature. Giving every quad a real, non-degenerate
	 * UV square makes the tangent well-defined and consistent with the quad's own edges.
	 * <p>
	 * Kept to a small window in the middle of the texture rather than most of it. Shader packs
	 * treat the distance from a vertex to {@code mc_midTexCoord} as the sprite's half-size and
	 * feed it into parallax and generated-normal sampling; a quad claiming to be 60% of the
	 * texture wide reads as an enormous sprite and skews those. Two texels of a 16×16 image is
	 * ample for a stable tangent and unremarkable to everything downstream.
	 */
	private static final float UV_LO = 7f / 16f;
	private static final float UV_HI = 9f / 16f;

	/** Block-light coordinate of full brightness: 15 levels, stored shifted left by four. */
	private static final int FULL_BLOCK_LIGHT = 15 << 4;
	/** Both halves at full, for the emissive pass which is lit by definition. */
	private static final int FULL_BRIGHT = FULL_BLOCK_LIGHT | (FULL_BLOCK_LIGHT << 16);
	/**
	 * Mean emissive weight a quad needs before it is drawn again on the glow layer. Above zero so
	 * the faint wash on a whole-body glow does not put every quad on the creature into the pack's
	 * bloom pass.
	 */
	private static final float GLOW_QUAD_THRESHOLD = 0.12f;

	private void emit(MeshData mesh, MatrixStack.Entry entry, VertexConsumer consumer, int light) {
		float[] positions = SKINNED.positions();
		float[] normals = SKINNED.normals();
		float[] colors = mesh.colors;
		float[] emissive = mesh.emissive;
		int[] quads = mesh.quads;
		int overlay = OverlayTexture.DEFAULT_UV;

		for (int i = 0; i < quads.length; i++) {
			int v = quads[i];
			int p = v * 3;

			// Corners run a, b, c, d around the quad, so the UV square does too.
			int corner = i & 3;
			float u = (corner == 1 || corner == 2) ? UV_HI : UV_LO;
			float t = corner >= 2 ? UV_HI : UV_LO;

			consumer.vertex(entry, positions[p], positions[p + 1], positions[p + 2])
					.color(colors[p], colors[p + 1], colors[p + 2], 1f)
					.texture(u, t)
					.overlay(overlay)
					.light(emissiveLight(light, emissive[v]))
					.normal(entry, normals[p], normals[p + 1], normals[p + 2]);
		}
	}

	/**
	 * Draws the bioluminescent parts again on the emissive render layer.
	 * <p>
	 * Lifting the lightmap coordinate — which is all the first pass does — makes a part look
	 * <i>lit</i>, and vanilla is happy with that. A shader pack is not: it reads the lightmap as
	 * "how much light falls here" and applies its own exposure, bloom and tone mapping on top, so
	 * a bright lightmap value is just a well-lit surface and never emits. Emission has to arrive
	 * on a layer the pack recognises as emissive, which is what {@code entity_translucent_emissive}
	 * is for. Packs single that layer out for bloom, and it renders full-bright in vanilla too, so
	 * one mechanism covers both.
	 * <p>
	 * Additive over the solid body rather than replacing it: the base pass already drew these
	 * quads opaquely, so this only has to add the light on top. Quads are selected whole — a quad
	 * is either glowing or it is not — because the layer switch cannot be made per vertex.
	 */
	private void emitGlow(MeshData mesh, MatrixStack.Entry entry, VertexConsumerProvider providers) {
		float[] emissive = mesh.emissive;
		if (!PrimordiaConfig.get().emissiveGlow) return;

		float[] positions = SKINNED.positions();
		float[] normals = SKINNED.normals();
		float[] colors = mesh.colors;
		int[] quads = mesh.quads;

		VertexConsumer glow = null;
		for (int i = 0; i < quads.length; i += 4) {
			float strength = (emissive[quads[i]] + emissive[quads[i + 1]]
					+ emissive[quads[i + 2]] + emissive[quads[i + 3]]) * 0.25f;
			if (strength <= GLOW_QUAD_THRESHOLD) continue;

			// Deferred so a creature with no light organs never touches the layer at all, which
			// keeps it out of the buffer and out of the pack's bloom pass.
			if (glow == null) {
				glow = providers.getBuffer(RenderLayer.getEntityTranslucentEmissive(TEXTURE));
			}

			for (int k = 0; k < 4; k++) {
				int v = quads[i + k];
				int p = v * 3;
				int corner = k;
				float u = (corner == 1 || corner == 2) ? UV_HI : UV_LO;
				float t = corner >= 2 ? UV_HI : UV_LO;

				glow.vertex(entry, positions[p], positions[p + 1], positions[p + 2])
						.color(colors[p], colors[p + 1], colors[p + 2], strength)
						.texture(u, t)
						.overlay(OverlayTexture.DEFAULT_UV)
						.light(FULL_BRIGHT)
						.normal(entry, normals[p], normals[p + 1], normals[p + 2]);
			}
		}
	}

	/**
	 * Raises the block-light half of a packed lightmap coordinate toward full brightness, leaving
	 * the sky half alone. This is the vanilla half of the effect — it makes the surface read as
	 * lit; {@link #emitGlow} is what makes a shader pack treat it as emitting.
	 */
	private static int emissiveLight(int light, float emissive) {
		if (emissive <= 0.02f) return light;
		if (!dev.jsz.primordia.client.config.PrimordiaConfig.get().emissiveGlow) return light;
		int block = light & 0xFFFF;
		int sky = (light >>> 16) & 0xFFFF;
		int lit = Math.round(MathHelper.lerp(Math.min(emissive, 1f), block, FULL_BLOCK_LIGHT));
		return (sky << 16) | Math.max(block, lit);
	}
}
