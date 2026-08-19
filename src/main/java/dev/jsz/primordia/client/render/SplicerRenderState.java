package dev.jsz.primordia.client.render;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.Direction;

/**
 * What the splicer renderer needs, extracted once per frame.
 * <p>
 * The 26.2 pipeline splits extraction from drawing: {@code extractRenderState} may touch the world,
 * {@code submit} may not. So the running flag and the animation clock are read here and the draw
 * pass sees nothing but numbers.
 */
public class SplicerRenderState extends BlockEntityRenderState {
	public Direction facing = Direction.NORTH;
	/** Seconds into the sampling cycle. Held at zero whenever the machine is idle. */
	public float animationSeconds;
	public boolean running;
}
