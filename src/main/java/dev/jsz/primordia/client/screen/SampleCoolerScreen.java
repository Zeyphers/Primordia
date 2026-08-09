package dev.jsz.primordia.client.screen;

import dev.jsz.primordia.Primordia;
import dev.jsz.primordia.screen.SampleCoolerScreenHandler;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

/**
 * The Sample Cooler's screen.
 * <p>
 * The panel is the mod's own texture rather than vanilla's shulker box, though it was cut from one:
 * two rows of eight, centred, which no vanilla panel is painted for. It was assembled at draw time
 * for a while — three blits and a patch over the spare column — and baking it into a file instead
 * put it in {@code design/gui/} where it can be repainted without touching this class.
 */
public class SampleCoolerScreen extends AbstractContainerScreen<SampleCoolerScreenHandler> {

	private static final Identifier BACKGROUND = Primordia.id("textures/gui/sample_cooler.png");

	public SampleCoolerScreen(SampleCoolerScreenHandler handler, Inventory inventory,
	                          Component title) {
		super(handler, inventory, title,
				SampleCoolerScreenHandler.BACKGROUND_WIDTH,
				SampleCoolerScreenHandler.BACKGROUND_HEIGHT);
		inventoryLabelY = imageHeight - 94;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		super.extractBackground(context, mouseX, mouseY, delta);
		int x = (width - imageWidth) / 2;
		int y = (height - imageHeight) / 2;
		context.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, x, y, 0, 0,
				imageWidth, imageHeight, 256, 256);
	}
}
