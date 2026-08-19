package dev.jsz.primordia.mixin;

import dev.jsz.primordia.splice.Splicing;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Makes a spliced metabolism cost what it says it costs.
 * <p>
 * {@code SpliceEffects} maps {@code METABOLISM} and {@code GRAZING_IMPACT} onto how fast the player
 * burns food, and there is no attribute for that — hunger is the one player stat Minecraft does not
 * expose as one. So the two loci that are meant to be the <i>drawbacks</i> of the Physiology and
 * Habit blocks are the two that need a hook, which is a fitting amount of trouble for the parts of
 * the design that stop it being a menu of buffs.
 * <p>
 * Scaling the argument rather than adding a tick of our own keeps it exact: every source of
 * exhaustion the game has — walking, mining, being hit, healing — is multiplied by the same figure
 * the field guide quoted, so the number the player read is the number they feel.
 */
@Mixin(Player.class)
public abstract class PlayerExhaustionMixin {

	@ModifyVariable(method = "causeFoodExhaustion", at = @At("HEAD"), argsOnly = true)
	private float primordia$spliceExhaustion(float exhaustion) {
		// Server side only: the client runs its own food simulation for prediction, and scaling it
		// there as well would double the effect on the one that is displayed.
		if (!((Object) this instanceof ServerPlayer player)) return exhaustion;
		return exhaustion * Splicing.loadoutOf(player).exhaustionMultiplier();
	}
}
