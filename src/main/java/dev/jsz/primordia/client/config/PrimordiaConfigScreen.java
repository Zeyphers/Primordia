package dev.jsz.primordia.client.config;

import dev.jsz.primordia.mesh.GenomeMeshCache;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * Settings screen for the client's rendering quality.
 * <p>
 * Built from plain {@link SliderWidget} and {@link ButtonWidget} in a fixed layout rather than from
 * the vanilla options-list machinery. That machinery is convenient but its constructors have moved
 * almost every Minecraft version, and a settings screen is not worth making the mod fragile across
 * updates for.
 * <p>
 * <b>Grouped by what a setting is for, not by what it is made of.</b> Fifteen sliders in one grid
 * gave no clue which of them to reach for — "Creatures near", "Near range" and "Detail near" all
 * mention the near tier and do three unrelated things. The tabs answer the question the reader
 * actually arrives with: too few animals on screen, or too coarse, or too slow.
 * <p>
 * The quality preset sits above the tabs rather than inside one, because it writes to every tab and
 * a control that silently changes a page you cannot see belongs where you can see it.
 */
public class PrimordiaConfigScreen extends Screen {
	private static final int WIDGET_WIDTH = 190;
	private static final int WIDGET_HEIGHT = 20;
	private static final int GAP_X = 12;
	private static final int GAP_Y = 24;

	private static final int TAB_H = 20;
	private static final int TAB_GAP = 4;

	/** Tabs, in the order a reader is likely to want them. */
	private enum Tab {
		POPULATION("Population", "How many creatures are drawn, and how far out."),
		DETAIL("Detail", "How finely each creature is built, and how many builds are kept."),
		APPEARANCE("Appearance", "How the creatures are shaded and shaped.");

		final String label;
		final String blurb;

		Tab(String label, String blurb) {
			this.label = label;
			this.blurb = blurb;
		}

		static final Tab[] VALUES = values();
	}

	private final Screen parent;
	private final PrimordiaConfig config;
	private Tab tab = Tab.POPULATION;

	public PrimordiaConfigScreen(Screen parent) {
		super(Text.literal("Primordia Settings"));
		this.parent = parent;
		this.config = PrimordiaConfig.get();
	}

	@Override
	protected void init() {
		int totalWidth = WIDGET_WIDTH * 2 + GAP_X;
		int left = (width - totalWidth) / 2;

		addPresetButton(left, 40);
		addTabRow(left, 66, totalWidth);

		int top = 96;
		int i = 0;
		switch (tab) {
			case POPULATION -> {
				addSlider(left, top, i++, "Creatures near", 1, 64,
						() -> config.nearCreatures, v -> config.nearCreatures = v,
						"How many creatures may be drawn at full detail before the rest drop a tier.");
				addSlider(left, top, i++, "Near range", 4, 64,
						() -> (int) config.nearDistance, v -> config.nearDistance = v,
						"Blocks. Raise this to hold creatures at full detail further away.");
				addSlider(left, top, i++, "Creatures mid", 1, 128,
						() -> config.midCreatures, v -> config.midCreatures = v, null);
				addSlider(left, top, i++, "Mid range", 8, 128,
						() -> (int) config.midDistance, v -> config.midDistance = v, null);
				addSlider(left, top, i++, "Creatures far", 1, 256,
						() -> config.farCreatures, v -> config.farCreatures = v, null);
				addSlider(left, top, i++, "Far range", 16, 256,
						() -> (int) config.farDistance, v -> config.farDistance = v, null);
				// Paired left-to-right so each tier's count sits beside its own distance. The old
				// layout listed all three counts and then all three distances, which put the two
				// halves of one decision at opposite ends of the screen.
				addIkButton(left, top, i++);
			}
			case DETAIL -> {
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
			}
			case APPEARANCE -> {
				addToggle(left, top, i++, "Voxel mode",
						() -> config.voxelMode, () -> {
							config.voxelMode = !config.voxelMode;
							// The voxel slider appears and disappears with the toggle, and every
							// cached mesh was built the other way.
							clearAndInit();
						},
						"Snap creatures to a world-aligned block grid instead of a smooth surface — "
								+ "the same idea as Blender's voxel remesh.");
				if (config.voxelMode) {
					addSlider(left, top, i++, "Voxel size (px)", 1, 8,
							() -> config.voxelPixels, v -> config.voxelPixels = v,
							"Voxel edge in Minecraft pixels; sixteen to the block. One matches the "
									+ "world's own texel grid. Small voxels on a large creature are "
									+ "expensive to bake.");
				}
				addShadingButton(left, top, i++);
				addToggle(left, top, i++, "Face colour",
						() -> config.flatFaceColour, () -> config.flatFaceColour = !config.flatFaceColour,
						"Off blends colour across each face, which is what makes markings read as "
								+ "continuous on a smooth body. On gives every face one flat colour, "
								+ "averaged from its corners — the block-texel look, and the companion "
								+ "to Sharp shading in voxel mode.");
				// The smoothing slider is a refinement within smooth shading and means nothing at
				// all under sharp, where every face carries its own normal by construction.
				if (!config.sharpShading) {
					addSlider(left, top, i++, "Surface softness", 0, 100,
							() -> config.normalSmoothing, v -> config.normalSmoothing = v,
							"Fine adjustment within smooth shading: how much of each normal comes "
									+ "from the analytic field rather than from the surrounding "
									+ "faces. Both are smooth, so the difference is subtle — for a "
									+ "faceted look use Sharp shading above.");
				}
				addGlowButton(left, top, i++);
			}
		}

		addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
				.dimensions(width / 2 - 100, height - 30, 200, WIDGET_HEIGHT).build());
	}

	// --------------------------------------------------------------------- tabs

	private void addTabRow(int left, int y, int totalWidth) {
		int count = Tab.VALUES.length;
		int tabWidth = (totalWidth - TAB_GAP * (count - 1)) / count;
		for (int t = 0; t < count; t++) {
			Tab which = Tab.VALUES[t];
			boolean active = which == tab;
			ButtonWidget button = ButtonWidget.builder(
					Text.literal(which.label).formatted(active ? Formatting.YELLOW : Formatting.GRAY),
					b -> {
						if (tab == which) return;
						tab = which;
						clearAndInit();
					})
					.dimensions(left + t * (tabWidth + TAB_GAP), y, tabWidth, TAB_H).build();
			button.active = !active;
			button.setTooltip(Tooltip.of(Text.literal(which.blurb)));
			addDrawableChild(button);
		}
	}

	// ------------------------------------------------------------------ widgets

	private int columnX(int left, int index) {
		return left + (index % 2) * (WIDGET_WIDTH + GAP_X);
	}

	private int rowY(int top, int index) {
		return top + (index / 2) * GAP_Y;
	}

	private void addPresetButton(int left, int y) {
		ButtonWidget button = ButtonWidget.builder(presetLabel(), b -> {
			// Cycling past the end lands on CUSTOM, which is a legitimate stop: it leaves the
			// individual sliders exactly where they are instead of overwriting them.
			int next = (config.preset.ordinal() + 1) % QualityPreset.VALUES.length;
			config.applyPreset(QualityPreset.VALUES[next]);
			config.apply();
			// The preset rewrote every other value, so every tab's widgets are stale.
			clearAndInit();
		}).dimensions(left, y, WIDGET_WIDTH * 2 + GAP_X, WIDGET_HEIGHT).build();
		button.setTooltip(Tooltip.of(Text.literal(
				"Starting point for everything on every tab. Ultra holds many creatures at fine "
						+ "detail much further out; changing any setting moves this to Custom.")));
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
		button.setTooltip(Tooltip.of(Text.literal(
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

	/**
	 * The real smooth-versus-sharp control, as a two-state button rather than a slider.
	 * <p>
	 * It is not a matter of degree: sharp shading gives every face its own vertices, so it is a
	 * different mesh rather than a different weighting. Presenting it on the same slider as
	 * "Surface softness" would imply a continuum that does not exist — which is exactly the
	 * impression the old single slider gave, and why it read as doing nothing.
	 */
	private void addShadingButton(int left, int top, int index) {
		ButtonWidget button = ButtonWidget.builder(shadingLabel(), b -> {
			config.sharpShading = !config.sharpShading;
			config.markCustom();
			config.apply();
			// The softness slider belongs to smooth shading only, so the page changes shape.
			clearAndInit();
		}).dimensions(columnX(left, index), rowY(top, index), WIDGET_WIDTH, WIDGET_HEIGHT).build();
		button.setTooltip(Tooltip.of(Text.literal(
				"Smooth blends each normal across the faces meeting at a vertex. Sharp gives every "
						+ "face its own, so edges read as edges — the faceted look, and what voxel "
						+ "mode wants. Purely a shading choice: the mesh is identical either way, "
						+ "and switching costs nothing.")));
		addDrawableChild(button);
	}

	private Text shadingLabel() {
		return Text.literal("Shading: ").append(
				Text.literal(config.sharpShading ? "Sharp" : "Smooth")
						.formatted(config.sharpShading ? Formatting.GOLD : Formatting.AQUA));
	}

	private void addGlowButton(int left, int top, int index) {
		addToggle(left, top, index, "Glowing creatures",
				() -> config.emissiveGlow, () -> config.emissiveGlow = !config.emissiveGlow,
				"Whether bioluminescent creatures light their own markings at night.");
	}

	/** An on/off button. {@code onToggle} owns flipping the value; this only redraws the label. */
	private void addToggle(int left, int top, int index, String label,
	                       BooleanSupplier getter, Runnable onToggle, String tooltip) {
		ButtonWidget button = ButtonWidget.builder(toggleLabel(label, getter.getAsBoolean()), b -> {
			onToggle.run();
			config.markCustom();
			config.apply();
			b.setMessage(toggleLabel(label, getter.getAsBoolean()));
		}).dimensions(columnX(left, index), rowY(top, index), WIDGET_WIDTH, WIDGET_HEIGHT).build();
		if (tooltip != null) button.setTooltip(Tooltip.of(Text.literal(tooltip)));
		addDrawableChild(button);
	}

	private static Text toggleLabel(String label, boolean on) {
		// "Flat"/"Blended" reads better than On/Off for the colour toggle, where neither state is
		// an absence of anything.
		if (label.equals("Face colour")) {
			return Text.literal(label + ": ").append(Text.literal(on ? "Flat" : "Blended")
					.formatted(on ? Formatting.GOLD : Formatting.AQUA));
		}
		return Text.literal(label + ": ").append(Text.literal(on ? "On" : "Off")
				.formatted(on ? Formatting.GREEN : Formatting.GRAY));
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
			slider.setTooltip(Tooltip.of(Text.literal(tooltip)));
		}
		addDrawableChild(slider);
	}

	// ------------------------------------------------------------------- screen

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 16, 0xFFFFFF);

		// One line saying what this tab is for. Cheaper to read than three tooltips.
		context.drawCenteredTextWithShadow(textRenderer,
				Text.literal(tab.blurb).formatted(Formatting.DARK_GRAY),
				width / 2, 90, 0xAAAAAA);

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
