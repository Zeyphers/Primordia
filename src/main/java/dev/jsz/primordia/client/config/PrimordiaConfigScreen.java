package dev.jsz.primordia.client.config;

import dev.jsz.primordia.mesh.GenomeMeshCache;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * Settings screen for the client's rendering quality.
 * <p>
 * Built from plain {@link SliderWidget} and {@link ButtonWidget} in a fixed two-column layout
 * rather than from the vanilla options-list machinery. That machinery is convenient but its
 * constructors have moved almost every Minecraft version, and a settings screen is not worth
 * making the mod fragile across updates for.
 */
public class PrimordiaConfigScreen extends Screen {
	private static final int WIDGET_WIDTH = 190;
	private static final int WIDGET_HEIGHT = 20;
	private static final int GAP_X = 12;
	private static final int GAP_Y = 24;

	private final Screen parent;
	private final PrimordiaConfig config;

	public PrimordiaConfigScreen(Screen parent) {
		super(Text.literal("Primordia Settings"));
		this.parent = parent;
		this.config = PrimordiaConfig.get();
	}

	@Override
	protected void init() {
		int totalWidth = WIDGET_WIDTH * 2 + GAP_X;
		int left = (width - totalWidth) / 2;
		int top = 46;

		int i = 0;
		addPresetButton(left, top, i++);

		addSlider(left, top, i++, "Creatures near", 1, 64,
				() -> config.nearCreatures, v -> config.nearCreatures = v,
				"How many creatures may be drawn at full detail before the rest drop a tier.");
		addSlider(left, top, i++, "Creatures mid", 1, 128,
				() -> config.midCreatures, v -> config.midCreatures = v, null);
		addSlider(left, top, i++, "Creatures far", 1, 256,
				() -> config.farCreatures, v -> config.farCreatures = v, null);

		addSlider(left, top, i++, "Near range", 4, 64,
				() -> (int) config.nearDistance, v -> config.nearDistance = v,
				"Blocks. Raise this to hold creatures at full detail further away.");
		addSlider(left, top, i++, "Mid range", 8, 128,
				() -> (int) config.midDistance, v -> config.midDistance = v, null);
		addSlider(left, top, i++, "Far range", 16, 256,
				() -> (int) config.farDistance, v -> config.farDistance = v, null);

		addSlider(left, top, i++, "Detail near", 8, 96,
				() -> config.nearDetail, v -> config.nearDetail = v,
				"Mesh resolution. Costs bake time once per genome, then memory while cached.");
		addSlider(left, top, i++, "Detail mid", 8, 80,
				() -> config.midDetail, v -> config.midDetail = v, null);
		addSlider(left, top, i++, "Detail far", 6, 60,
				() -> config.farDetail, v -> config.farDetail = v, null);
		addSlider(left, top, i++, "Detail ceiling", 16, 160,
				() -> config.detailCeiling, v -> config.detailCeiling = v,
				"Upper bound however thin a creature's limbs are. Guards bake cost.");

		addSlider(left, top, i++, "Mesh cache", 64, 2048,
				() -> config.meshCacheSize, v -> config.meshCacheSize = v,
				"Baked meshes kept in memory. Too small and creatures rebake as they cycle out.");
		addSlider(left, top, i++, "Smooth normals", 0, 100,
				() -> config.normalSmoothing, v -> config.normalSmoothing = v,
				"Share of each normal from the analytic field rather than the facets. "
						+ "Lower is crisper, higher is smoother.");

		addIkButton(left, top, i++);
		addGlowButton(left, top, i++);

		addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
				.dimensions(width / 2 - 100, height - 30, 200, WIDGET_HEIGHT).build());
	}

	// ------------------------------------------------------------------ widgets

	private int columnX(int left, int index) {
		return left + (index % 2) * (WIDGET_WIDTH + GAP_X);
	}

	private int rowY(int top, int index) {
		return top + (index / 2) * GAP_Y;
	}

	private void addPresetButton(int left, int top, int index) {
		ButtonWidget button = ButtonWidget.builder(presetLabel(), b -> {
			// Cycling past the end lands on CUSTOM, which is a legitimate stop: it leaves the
			// individual sliders exactly where they are instead of overwriting them.
			int next = (config.preset.ordinal() + 1) % QualityPreset.VALUES.length;
			config.applyPreset(QualityPreset.VALUES[next]);
			config.apply();
			b.setMessage(presetLabel());
			// The preset rewrote every other value, so the sliders have to be rebuilt.
			clearAndInit();
		}).dimensions(columnX(left, index), rowY(top, index), WIDGET_WIDTH, WIDGET_HEIGHT).build();
		button.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(
				"Starting point for everything below. Ultra holds many creatures at fine detail "
						+ "much further out; changing any slider moves this to Custom.")));
		addDrawableChild(button);
	}

	private Text presetLabel() {
		return Text.literal("Quality: ").append(Text.literal(config.preset.label)
				.formatted(config.preset.isCustom() ? Formatting.YELLOW : Formatting.AQUA));
	}

	private void addIkButton(int left, int top, int index) {
		ButtonWidget button = ButtonWidget.builder(ikLabel(), b -> {
			config.fullIkTier = (config.fullIkTier + 1) % 4;
			config.markCustom();
			config.apply();
			b.setMessage(ikLabel());
		}).dimensions(columnX(left, index), rowY(top, index), WIDGET_WIDTH, WIDGET_HEIGHT).build();
		button.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(
				"How far out limbs are solved with real IK rather than a canned walk cycle.")));
		addDrawableChild(button);
	}

	private Text ikLabel() {
		String tier = switch (config.fullIkTier) {
			case 0 -> "Near only";
			case 1 -> "To mid";
			case 2 -> "To far";
			default -> "Everywhere";
		};
		return Text.literal("Full IK: " + tier);
	}

	private void addGlowButton(int left, int top, int index) {
		ButtonWidget button = ButtonWidget.builder(glowLabel(), b -> {
			config.emissiveGlow = !config.emissiveGlow;
			config.markCustom();
			b.setMessage(glowLabel());
		}).dimensions(columnX(left, index), rowY(top, index), WIDGET_WIDTH, WIDGET_HEIGHT).build();
		button.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(
				"Whether bioluminescent creatures light their own markings at night.")));
		addDrawableChild(button);
	}

	private Text glowLabel() {
		return Text.literal("Glowing creatures: "
				+ (config.emissiveGlow ? "On" : "Off"));
	}

	private void addSlider(int left, int top, int index, String label, int min, int max,
	                       IntSupplier getter, IntConsumer setter, String tooltip) {
		int x = columnX(left, index);
		int y = rowY(top, index);
		SliderWidget slider = new SliderWidget(x, y, WIDGET_WIDTH, WIDGET_HEIGHT,
				Text.literal(label + ": " + getter.getAsInt()),
				(getter.getAsInt() - min) / (double) (max - min)) {
			@Override
			protected void updateMessage() {
				setMessage(Text.literal(label + ": " + current()));
			}

			@Override
			protected void applyValue() {
				setter.accept(current());
				config.markCustom();
				config.apply();
			}

			private int current() {
				return (int) Math.round(min + value * (max - min));
			}
		};
		if (tooltip != null) {
			slider.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(tooltip)));
		}
		addDrawableChild(slider);
	}

	// ------------------------------------------------------------------- screen

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 16, 0xFFFFFF);

		// Live cache readout: the most direct feedback that a change actually did something, and
		// the fastest way to notice a cache set too small to hold what is on screen.
		String status = GenomeMeshCache.readyCount() + " meshes cached · "
				+ GenomeMeshCache.pendingCount() + " baking";
		context.drawCenteredTextWithShadow(textRenderer, Text.literal(status).formatted(Formatting.DARK_GRAY),
				width / 2, height - 44, 0xAAAAAA);
	}

	@Override
	public void close() {
		config.applyAndSave();
		if (client != null) client.setScreen(parent);
	}
}
