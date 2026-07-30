package dev.jsz.primordia.client.render;

import dev.jsz.primordia.Primordia;
import dev.jsz.primordia.anim.AnimationContext;
import dev.jsz.primordia.anim.CreatureAnimator;
import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BoneDef;
import dev.jsz.primordia.client.WorldGroundProbe;
import dev.jsz.primordia.client.config.PrimordiaConfig;
import dev.jsz.primordia.entity.CreatureActivity;
import dev.jsz.primordia.entity.CreatureEntity;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.mesh.FaceColour;
import dev.jsz.primordia.mesh.FaceNormal;
import dev.jsz.primordia.mesh.GenomeMeshCache;
import dev.jsz.primordia.mesh.LodTier;
import dev.jsz.primordia.mesh.MeshData;
import dev.jsz.primordia.mesh.SkinnedMesh;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

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
 * <p>
 * <b>Two phases.</b> Since 1.21.2 the entity is only reachable during {@link #extractRenderState},
 * and drawing happens later in {@link #submit} against nothing but the state object. Everything
 * that reads the entity — pose, gait, LOD choice, skinning — therefore happens in extract, and
 * submit is only transforms and vertices. Custom geometry reaches the pipeline through
 * {@code submitCustomGeometry}, which hands back a {@link VertexConsumer} at draw time; that is
 * the one door left open for a mesh vanilla knows nothing about.
 */
public class CreatureRenderer extends EntityRenderer<CreatureEntity, CreatureRenderState> {
	/**
	 * A 16×16 pure white sheet. Vertex colours multiply against it, so it acts as a neutral base.
	 * <p>
	 * This used to be vanilla's {@code minecraft:textures/misc/white.png}, and the note here said to
	 * ship our own if a future version ever dropped it. 26.2 dropped it, and every creature rendered
	 * untextured — so the asset now lives in this mod, where nothing upstream can take it away.
	 */
	static final Identifier TEXTURE = Primordia.id("textures/misc/white.png");

	/**
	 * Fraction of its colour a carcass keeps.
	 * <p>
	 * A dead creature is drawn from the same mesh and the same palette as the live one, and lying on
	 * its side is not always enough to tell them apart at a glance — particularly for a resting
	 * animal, which is also horizontal. Draining most of the colour separates the two immediately
	 * without changing the silhouette, and reads as the thing it is meant to.
	 * <p>
	 * Not zero on purpose: a fully grey body loses the markings that identify which species it was,
	 * which is exactly the question a player standing over a carcass is asking.
	 */
	private static final float CARCASS_CHROMA = 0.3f;

	/** Rec. 709 luminance weights, so desaturation preserves perceived brightness. */
	private static final float LUMA_R = 0.2126f, LUMA_G = 0.7152f, LUMA_B = 0.0722f;

	private static final LodTier.Budget BUDGET = new LodTier.Budget();

	/** Scratch for the extract pass only, which is single-threaded and consumes it immediately. */
	private final AnimationContext context = new AnimationContext();
	private final WorldGroundProbe probe = new WorldGroundProbe();

	public CreatureRenderer(EntityRendererProvider.Context ctx) {
		super(ctx);
		this.shadowRadius = 0.5f;
		this.shadowStrength = 0.6f;
	}

	/** Resets the per-frame LOD budget. Hooked to the start of world rendering. */
	public static void beginFrame() {
		BUDGET.reset();
	}

	@Override
	public CreatureRenderState createRenderState() {
		return new CreatureRenderState();
	}

	@Override
	public void extractRenderState(CreatureEntity entity, CreatureRenderState state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);

		state.ready = false;
		Genome genome = entity.getGenome();
		CreatureAnimator animator = entity.getOrCreateAnimator();
		// The genome arrives a tick or two after the spawn packet; until then there is nothing to draw.
		if (genome == null || animator == null) return;

		BodyPlan plan = animator.skeleton().plan;
		state.genome = genome;
		state.plan = plan;
		this.shadowRadius = Mth.clamp(plan.width() * 0.5f * entity.getGrowth(), 0.25f, 2.0f);

		// Test specimens render at full detail regardless of distance or crowding, for two
		// reasons. Comparison: a grid whose near rows are finely meshed and whose far rows are
		// coarse cannot be used to judge a change, because half the difference on screen is the
		// LOD and not the generator. Animation: IK only runs on the closer tiers, so on a grid
		// sixty blocks deep most of the specimens would stand with their legs frozen in the bind
		// pose no matter what the walk flag said — which is exactly what "the walk command
		// doesn't work" looked like. Thirty creatures at near detail is the cost of the rig, and
		// the quality presets are there to pay it.
		int tier = entity.isPosing() ? LodTier.NEAR : BUDGET.allocate(state.distanceToCameraSq);

		MeshData mesh = resolveMesh(genome, tier);
		if (mesh == null || mesh.quadCount == 0) {
			// Still baking. Skipping a frame is far better than stalling the render thread.
			return;
		}
		state.mesh = mesh;

		float yaw = Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot);
		state.bodyYawDeg = yaw;
		// state.climbBlend = entity.getClimbBlend(); // DISABLED: wall climbing commented out
		state.growth = entity.getGrowth();
		// Divided through by the growth scale because it is used inside it. Everything else in the
		// submit pass is a model measurement that scales with the body; this one is a world measurement
		// off the collision box, and would otherwise be applied twice on a juvenile.
		state.halfWidth = entity.getBbWidth() * 0.5f / state.growth;
		state.carcass = entity.isCarcass() || entity.isDeadOrDying() || entity.getHealth() <= 0f;

		fillContext(entity, yaw, partialTick, tier);
		animator.update(context);
		// Skinned into this creature's own buffer, because submit runs long after extract.
		state.skinned.skin(mesh, animator.skeleton());
		state.ready = true;
	}

	@Override
	public void submit(CreatureRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
	                   CameraRenderState cameraState) {
		if (!state.ready) {
			super.submit(state, poseStack, collector, cameraState);
			return;
		}
		BodyPlan plan = state.plan;

		poseStack.pushPose();
		// Model space is built facing +Z; Minecraft yaw 0 also faces +Z but increases toward -X,
		// so the model rotates by the negated yaw.
		poseStack.mulPose(Axis.YP.rotationDegrees(-state.bodyYawDeg));
		// Outermost, so every measurement below it stays in model units and shrinks with the body. A
		// juvenile is the same animal drawn smaller — there is no separate child model, which is the
		// only way this could work for creatures whose shape is not known until they are born.
		if (state.growth != 1f) {
			poseStack.scale(state.growth, state.growth, state.growth);
		}

		// DISABLED: wall climbing commented out — climb rotation block bypassed.
		// float climb = state.climbBlend;
		// if (climb > 0.01f) {
		//     poseStack.translate(0f, 0f, (state.halfWidth + plan.hipHeight * 0.5f) * climb);
		//     poseStack.mulPose(Axis.XP.rotationDegrees(-90f * climb));
		//     poseStack.translate(0f, plan.hipHeight * 0.5f * climb, 0f);
		// }
		if (state.carcass) {
			// A dead animal ends up on its back, legs in the air. Rolling the whole body is the right
			// level to do this at — the alternative is posing every bone into a heap, which the
			// skeleton has no concept of and which would need a second solver to look like anything.
			//
			// Read bottom-up, which is the order the geometry passes through: drop the trunk from
			// standing height onto the model origin, turn it over, then lift by half the body's
			// thickness so the back it is now lying on meets the ground. Turning first would swing the
			// whole animal through the floor, because the model origin is between its feet rather than
			// through the middle of it.
			//
			poseStack.translate(0f, carcassLift(plan), 0f);
			poseStack.mulPose(Axis.ZP.rotationDegrees(180f));
			poseStack.translate(0f, -plan.hipHeight, 0f);
		}

		collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(TEXTURE),
				(pose, consumer) -> emit(state, pose, consumer));

		if (PrimordiaConfig.get().emissiveGlow && hasGlow(state.mesh)) {
			collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(TEXTURE),
					(pose, consumer) -> emitGlow(state, pose, consumer));
		}
		poseStack.popPose();

		super.submit(state, poseStack, collector, cameraState);
	}

	/**
	 * How far a body on its back has to be raised for its spine to rest on the ground.
	 * <p>
	 * Turned over, the animal's back is its lowest point, so this is the trunk's dorsal surface
	 * measured from the hip — read off the spine bone, which carries the trunk's own radius.
	 * <p>
	 * Half the bounding box width used to stand in for it, and for anything that holds its legs out
	 * wide — which is most of them, and all the climbers — that width is mostly legs. The body was
	 * lifted by half a leg span and lay in the air. The other obvious candidate, the bounding box above
	 * the hip, is worse: it is measured to the top of the head, so the corpse balances on its skull
	 * with the spine higher still.
	 * <p>
	 * Dorsal plates, fins and horns are not counted and will pass through the floor. That is the right
	 * way round — a body sunk slightly into the ground reads as a body on the ground, and one hovering
	 * above it does not.
	 */
	private static float carcassLift(BodyPlan plan) {
		// Lift the flipped body by its dorsal height above the hip so its back rests flat on the ground plane (y = 0).
		return Math.max(0.1f, plan.boundsMax.y - plan.hipHeight);
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
		Vec3 pos = entity.getPosition(tickDelta);
		context.x = pos.x;
		context.y = pos.y;
		context.z = pos.z;

		context.bodyYaw = yaw * Mth.DEG_TO_RAD;

		float headYaw = Mth.rotLerp(tickDelta, entity.yHeadRotO, entity.yHeadRot);
		context.lookYaw = Mth.wrapDegrees(headYaw - yaw) * Mth.DEG_TO_RAD;
		context.lookPitch = Mth.lerp(tickDelta, entity.xRotO, entity.getXRot()) * Mth.DEG_TO_RAD;

		// Per-tick deltas scaled to per-second, which is the unit the animator works in.
		double dx = entity.getX() - entity.xo;
		double dz = entity.getZ() - entity.zo;
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
		context.turnRate = Mth.wrapDegrees(entity.yBodyRot - entity.yBodyRotO) * Mth.DEG_TO_RAD * 20f;

		// Steering intent, only while someone is actually driving. Measured against the body
		// rather than the head so it survives the head easing that riding already applies.
		context.riderSteer = 0f;
		if (entity.getControllingPassenger() instanceof net.minecraft.world.entity.LivingEntity rider) {
			context.riderSteer = Mth.wrapDegrees(rider.getYRot() - yaw) * Mth.DEG_TO_RAD;
		}

		context.time = (entity.tickCount + tickDelta) / 20f;
		context.airborne = !entity.onGround();
		if (entity.isClimbing()) {
			// A climbing creature has almost no horizontal speed — it is held flat against the wall — and
			// it is never on the ground, so the gait read it as an animal hanging motionless in mid-air
			// and froze every leg. Both of the things the walk cycle keys off have to be answered in the
			// wall's frame instead: it is standing on the surface, and its speed is how fast it is
			// travelling over it.
			double climbY = entity.getY() - entity.yo;
			double climbX = entity.getX() - entity.xo;
			double climbZ = entity.getZ() - entity.zo;
			context.speed = (float) Math.sqrt(
					climbX * climbX + climbY * climbY + climbZ * climbZ) * 20f;
			context.airborne = false;
			// Nothing should lean: the turn onto the wall is a whole-body rotation the renderer applies,
			// and feeding it to the spine as a turn as well banks the creature into the rock.
			context.turnRate = 0f;
			context.lookYaw = 0f;
			context.lookPitch = 0f;
		}
		context.swimming = entity.isInWater();
		// A carcass is fully slack; a sleeping animal is settled but still holding itself together.
		boolean isDead = entity.isCarcass() || entity.isDeadOrDying() || entity.getHealth() <= 0f;
		context.collapse = isDead ? 1f : (entity.isAsleep() ? 0.55f : 0f);
		if (isDead) {
			// Nothing about a body on the ground should read as locomotion, and the gait would
			// otherwise keep cycling from residual drift in the entity's position.
			context.speed = 0f;
			context.turnRate = 0f;
			context.lookYaw = 0f;
			context.lookPitch = 0f;
		}
		context.tier = tier;
		// On a wall the surface underfoot is the wall. The world probe deliberately refuses the sides of
		// blocks — a foot must not glue itself to a passing tree trunk — so a climber asking it where to
		// stand gets no answer anywhere and the legs fall back to a rescue meant for a foot over a cliff
		// edge. A flat plane at the creature's own feet is exactly the surface it is on, once the
		// renderer has turned the body onto the rock.
		context.ground = entity.isClimbing()
				? dev.jsz.primordia.anim.GroundProbe.flat((float) context.y)
				: probe.forWorld(entity.level());

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

	/** Scratch for the current face's normal and colour. Drawing is single-threaded. */
	private final org.joml.Vector3f faceNormal = new org.joml.Vector3f();
	private final org.joml.Vector3f faceColour = new org.joml.Vector3f();

	private void emit(CreatureRenderState state, PoseStack.Pose pose, VertexConsumer consumer) {
		MeshData mesh = state.mesh;
		SkinnedMesh skinned = state.skinned;
		float[] positions = skinned.positions();
		float[] normals = skinned.normals();
		float[] colors = mesh.colors;
		float[] emissive = mesh.emissive;
		int[] quads = mesh.quads;
		int overlay = OverlayTexture.NO_OVERLAY;
		int light = state.lightCoords;

		// Sharp shading hands all four corners of a face the face's own normal instead of their
		// individual smooth ones. Nothing about the mesh changes — this loop already emits four
		// unshared vertices per quad, so the only difference is which normal each one is given.
		PrimordiaConfig config = PrimordiaConfig.get();
		boolean sharp = config.sharpShading;
		boolean flatColour = config.flatFaceColour;

		// Colour drains out of a body once it is dead. Hoisted out of the loop because it is one
		// value for the whole creature, and left at exactly 1 for the living so the arithmetic below
		// is skipped entirely rather than multiplying every vertex by one.
		float chroma = state.carcass ? CARCASS_CHROMA : 1f;

		for (int i = 0; i < quads.length; i += 4) {
			if (sharp) {
				// From the skinned positions, so a face turns with the limb it belongs to.
				FaceNormal.compute(positions, quads, i, faceNormal);
			}
			float faceEmissive = 0f;
			if (flatColour) {
				FaceColour.average(colors, quads, i, faceColour);
				faceEmissive = FaceColour.averageEmissive(emissive, quads, i);
			}

			for (int k = 0; k < 4; k++) {
				int v = quads[i + k];
				int p = v * 3;

				// Corners run a, b, c, d around the quad, so the UV square does too.
				float u = (k == 1 || k == 2) ? UV_HI : UV_LO;
				float t = k >= 2 ? UV_HI : UV_LO;

				float nx = sharp ? faceNormal.x : normals[p];
				float ny = sharp ? faceNormal.y : normals[p + 1];
				float nz = sharp ? faceNormal.z : normals[p + 2];

				float cr = flatColour ? faceColour.x : colors[p];
				float cg = flatColour ? faceColour.y : colors[p + 1];
				float cb = flatColour ? faceColour.z : colors[p + 2];
				float ce = flatColour ? faceEmissive : emissive[v];

				if (chroma < 1f) {
					// Pulled toward its own luminance rather than toward flat grey, so a pale animal
					// and a dark one keep the difference between them and only the colour goes out of
					// both. Weights are Rec. 709, which is what makes the grey match the brightness
					// the eye reads rather than the average of the channels.
					float luma = cr * LUMA_R + cg * LUMA_G + cb * LUMA_B;
					cr = luma + (cr - luma) * chroma;
					cg = luma + (cg - luma) * chroma;
					cb = luma + (cb - luma) * chroma;
				}

				consumer.addVertex(pose, positions[p], positions[p + 1], positions[p + 2])
						.setColor(cr, cg, cb, 1f)
						.setUv(u, t)
						.setOverlay(overlay)
						.setLight(emissiveLight(light, ce))
						.setNormal(pose, nx, ny, nz);
			}
		}
	}

	/** True if any quad is emissive enough to be worth a second pass. */
	private static boolean hasGlow(MeshData mesh) {
		float[] emissive = mesh.emissive;
		int[] quads = mesh.quads;
		for (int i = 0; i < quads.length; i += 4) {
			float strength = (emissive[quads[i]] + emissive[quads[i + 1]]
					+ emissive[quads[i + 2]] + emissive[quads[i + 3]]) * 0.25f;
			if (strength > GLOW_QUAD_THRESHOLD) return true;
		}
		return false;
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
	private void emitGlow(CreatureRenderState state, PoseStack.Pose pose, VertexConsumer glow) {
		MeshData mesh = state.mesh;
		SkinnedMesh skinned = state.skinned;
		float[] emissive = mesh.emissive;
		float[] positions = skinned.positions();
		float[] normals = skinned.normals();
		float[] colors = mesh.colors;
		int[] quads = mesh.quads;

		for (int i = 0; i < quads.length; i += 4) {
			float strength = (emissive[quads[i]] + emissive[quads[i + 1]]
					+ emissive[quads[i + 2]] + emissive[quads[i + 3]]) * 0.25f;
			if (strength <= GLOW_QUAD_THRESHOLD) continue;

			for (int k = 0; k < 4; k++) {
				int v = quads[i + k];
				int p = v * 3;
				float u = (k == 1 || k == 2) ? UV_HI : UV_LO;
				float t = k >= 2 ? UV_HI : UV_LO;

				glow.addVertex(pose, positions[p], positions[p + 1], positions[p + 2])
						.setColor(colors[p], colors[p + 1], colors[p + 2], strength)
						.setUv(u, t)
						.setOverlay(OverlayTexture.NO_OVERLAY)
						.setLight(FULL_BRIGHT)
						.setNormal(pose, normals[p], normals[p + 1], normals[p + 2]);
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
		if (!PrimordiaConfig.get().emissiveGlow) return light;
		int block = light & 0xFFFF;
		int sky = (light >>> 16) & 0xFFFF;
		int lit = Math.round(Mth.lerp(Math.min(emissive, 1f), block, FULL_BLOCK_LIGHT));
		return (sky << 16) | Math.max(block, lit);
	}
}
