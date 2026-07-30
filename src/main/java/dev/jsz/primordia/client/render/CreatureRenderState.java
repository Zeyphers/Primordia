package dev.jsz.primordia.client.render;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.mesh.MeshData;
import dev.jsz.primordia.mesh.SkinnedMesh;
import net.minecraft.client.renderer.entity.state.EntityRenderState;

/**
 * Everything {@link CreatureRenderer} needs to draw one creature, copied off the entity during the
 * extract phase.
 * <p>
 * Since 1.21.2 a renderer may not touch the entity while drawing: rendering is split into an
 * extract pass that reads entity state and a submit pass that records draw commands, and the two
 * are separated in time. This class is the whole of what crosses that gap.
 * <p>
 * The skinning buffer lives <i>here</i>, per creature, rather than in one static scratch buffer
 * shared by the renderer. That was safe when {@code render()} skinned and emitted in the same call,
 * and is not safe now — submission is deferred, so every visible creature is extracted before any
 * of them draws, and a shared buffer would hold only the last one's pose by the time the first one
 * was submitted. Every creature on screen would wear the same animation.
 */
public class CreatureRenderState extends EntityRenderState {
	/** Null until the genome arrives from the server, a tick or two after the spawn packet. */
	public dev.jsz.primordia.genome.Genome genome;
	public BodyPlan plan;
	public MeshData mesh;

	/** Body yaw in degrees, interpolated for this frame. */
	public float bodyYawDeg;
	public float climbBlend;
	/**
	 * Half the collision box's width, which is how far the creature's centre sits from a wall it is
	 * flush against — and therefore how far the drawn body has to move to put its feet on that wall.
	 */
	public float halfWidth;
	/** Fraction of adult size, so offspring are drawn as smaller versions of what they will become. */
	public float growth = 1f;
	public boolean carcass;

	/** False when the genome or the baked mesh is not ready; the creature is skipped for a frame. */
	public boolean ready;

	/** Skinned positions and normals for this creature alone — see the class comment. */
	public final SkinnedMesh skinned = new SkinnedMesh();
}
