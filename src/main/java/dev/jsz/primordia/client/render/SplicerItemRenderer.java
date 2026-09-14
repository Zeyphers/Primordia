package dev.jsz.primordia.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.MapCodec;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.special.NoDataSpecialModelRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.function.Consumer;

/**
 * The Splicing Bench as an item: the placed machine, drawn by the same code, holding its idle pose.
 * <p>
 * Wired up through a {@code minecraft:special} item model (see {@code items/splicer.json}), the way
 * vanilla draws a chest or a bell in the inventory. The base model {@code item/splicer} contributes
 * only the display transforms, borrowed from vanilla's chest because the bench is authored the same
 * way round — front on the south face — and a block item's usual transforms would show its back.
 */
public final class SplicerItemRenderer implements NoDataSpecialModelRenderer {

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay,
	                   boolean hasFoil, int outlineColor) {
		SplicerRenderer.submitGeometry(poseStack, collector, SplicerRenderer.idlePose(), light, overlay);
	}

	/** The block it stands in. The GUI uses this to size the icon, and the bench fills its block. */
	@Override
	public void getExtents(Consumer<Vector3fc> output) {
		for (int x = 0; x <= 1; x++) {
			for (int y = 0; y <= 1; y++) {
				for (int z = 0; z <= 1; z++) {
					output.accept(new Vector3f(x, y, z));
				}
			}
		}
	}

	public record Unbaked() implements NoDataSpecialModelRenderer.Unbaked {
		public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(new Unbaked());

		@Override
		public MapCodec<Unbaked> type() {
			return MAP_CODEC;
		}

		@Override
		public SplicerItemRenderer bake(SpecialModelRenderer.BakingContext context) {
			return new SplicerItemRenderer();
		}
	}
}
