package dev.jsz.primordia.mixin.client;

import dev.jsz.primordia.entity.CreatureEntity;
import net.minecraft.client.renderer.debug.EntityHitboxDebugRenderer;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the spine hitbox alongside the ordinary one under F3+B.
 * <p>
 * Vanilla's debug view reads {@link Entity#getBoundingBox()} and nothing else, so a hitbox that is
 * deliberately not the bounding box — see {@link CreatureEntity#getSpineHitbox()} — is invisible
 * there. That is a poor state for a hitbox to be in: the only way to tell whether it covers the
 * animal is to swing at it and guess from whether anything happened.
 * <p>
 * Drawn in red against vanilla's white so the two are never confused, and purely as a gizmo — this
 * reads the box and changes nothing about it.
 */
@Mixin(EntityHitboxDebugRenderer.class)
public abstract class EntityHitboxDebugRendererMixin {

	/** Red, full alpha. Vanilla's entity box is white and its eye line magenta. */
	private static final int PRIMORDIA_SPINE_COLOUR = 0xFFFF3B30;

	@Inject(method = "showHitboxes(Lnet/minecraft/world/entity/Entity;FZ)V", at = @At("RETURN"))
	private void primordia$drawSpineHitbox(Entity entity, float partialTick, boolean detailed,
	                                       CallbackInfo ci) {
		if (!(entity instanceof CreatureEntity creature)) return;
		AABB spine = creature.getSpineHitbox();
		if (spine == null) return;
		Gizmos.cuboid(spine, GizmoStyle.stroke(PRIMORDIA_SPINE_COLOUR));
	}
}
