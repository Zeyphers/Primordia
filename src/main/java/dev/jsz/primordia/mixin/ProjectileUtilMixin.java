package dev.jsz.primordia.mixin;

import dev.jsz.primordia.entity.CreatureEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * Lets a creature be hit on its spine box as well as its bounding box.
 * <p>
 * <b>Two overloads, because they do not share an implementation.</b> The obvious assumption is that
 * the shorter {@code getEntityHitResult} delegates to the longer one; it does not — each runs its own
 * loop over the entities in the search box. Hooking only the seven-argument form covered projectiles
 * and left melee untouched, which showed up as the box being visible under F3+B and doing nothing
 * whatsoever when punched. The six-argument form is the one the crosshair uses:
 * {@code LocalPlayer.raycastHitResult} to {@code LocalPlayer.pick} to here.
 * <p>
 * <b>Only the hit test changes.</b> The extra box is never handed to collision, pathfinding,
 * suffocation or push resolution, because those read {@link Entity#getBoundingBox()} and this does
 * not touch it. The box therefore overlaps blocks and mobs freely and moves nothing: it adds
 * hittable volume and no physics, which is exactly what {@link CreatureEntity#getSpineHitbox()}
 * exists to be.
 * <p>
 * Vanilla's own result still wins on a tie in distance, so on the ordinary case — a creature whose
 * spine box sits inside its bounding box, which is most of them standing on flat ground — nothing
 * observable changes. It is the case where the two have come apart that this is for.
 */
@Mixin(ProjectileUtil.class)
public abstract class ProjectileUtilMixin {

	@Inject(
			method = "getEntityHitResult(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;"
					+ "Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;"
					+ "Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;F)"
					+ "Lnet/minecraft/world/phys/EntityHitResult;",
			at = @At("RETURN"),
			cancellable = true)
	private static void primordia$includeSpineHitbox(
			Level level, Entity shooter, Vec3 from, Vec3 to, AABB searchBox,
			Predicate<Entity> filter, float margin,
			CallbackInfoReturnable<EntityHitResult> cir) {

		EntityHitResult vanilla = cir.getReturnValue();
		// Beat only what vanilla already found, so a nearer ordinary target is never stolen.
		double bestSq = vanilla != null ? from.distanceToSqr(vanilla.getLocation())
				: from.distanceToSqr(to);
		CreatureEntity bestCreature = null;
		Vec3 bestPoint = null;

		for (Entity candidate : level.getEntities(shooter, searchBox, filter)) {
			if (!(candidate instanceof CreatureEntity creature)) continue;
			AABB spine = creature.getSpineHitbox();
			if (spine == null) continue;

			Optional<Vec3> hit = spine.inflate(margin).clip(from, to);
			if (hit.isEmpty()) continue;
			double distanceSq = from.distanceToSqr(hit.get());
			if (distanceSq < bestSq) {
				bestSq = distanceSq;
				bestCreature = creature;
				bestPoint = hit.get();
			}
		}

		if (bestCreature != null) {
			cir.setReturnValue(new EntityHitResult(bestCreature, bestPoint));
		}
	}

	/**
	 * The crosshair and melee path.
	 * <p>
	 * Winning here does more than register the hit. The caller compares this result against the block
	 * it also picked and keeps whichever is nearer, so a creature whose spine box is in front of a
	 * block now shadows it — you cannot mine through the animal, which is what the ordinary bounding
	 * box has always done and what this box was missing.
	 *
	 * @param limitSq the caller's cutoff: the squared distance to whatever it has already found,
	 *                so a nearer block or entity is never displaced by a farther creature
	 */
	@Inject(
			method = "getEntityHitResult(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;"
					+ "Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;"
					+ "Ljava/util/function/Predicate;D)Lnet/minecraft/world/phys/EntityHitResult;",
			at = @At("RETURN"),
			cancellable = true)
	private static void primordia$includeSpineHitboxForPicking(
			Entity shooter, Vec3 from, Vec3 to, AABB searchBox,
			Predicate<Entity> filter, double limitSq,
			CallbackInfoReturnable<EntityHitResult> cir) {

		EntityHitResult vanilla = cir.getReturnValue();
		double bestSq = vanilla != null ? from.distanceToSqr(vanilla.getLocation()) : limitSq;
		CreatureEntity bestCreature = null;
		Vec3 bestPoint = null;

		for (Entity candidate : shooter.level().getEntities(shooter, searchBox, filter)) {
			if (!(candidate instanceof CreatureEntity creature)) continue;
			AABB spine = creature.getSpineHitbox();
			if (spine == null) continue;

			Optional<Vec3> hit = spine.clip(from, to);
			if (hit.isEmpty()) continue;
			double distanceSq = from.distanceToSqr(hit.get());
			if (distanceSq < bestSq) {
				bestSq = distanceSq;
				bestCreature = creature;
				bestPoint = hit.get();
			}
		}

		if (bestCreature != null) {
			cir.setReturnValue(new EntityHitResult(bestCreature, bestPoint));
		}
	}
}
