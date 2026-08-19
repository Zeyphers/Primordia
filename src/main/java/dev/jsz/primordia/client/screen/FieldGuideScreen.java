package dev.jsz.primordia.client.screen;

import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.lab.DecodeAccuracy;
import dev.jsz.primordia.lab.GuideChapters;
import dev.jsz.primordia.lab.GuideData;
import dev.jsz.primordia.lab.SpawnSpeciesPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import dev.jsz.primordia.lab.NameLineagePayload;
import dev.jsz.primordia.lab.Phylogeny;
import dev.jsz.primordia.util.MathX;
import dev.jsz.primordia.entity.TamingPreference;
import dev.jsz.primordia.client.render.CreaturePreview;
import dev.jsz.primordia.splice.SpliceBranch;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.ChatFormatting;
import dev.jsz.primordia.Primordia;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders the field guide: the mod's manual, plus everything the owner has filed.
 * <p>
 * Reads entirely from the held stack. The guide's contents are item components, which Minecraft
 * replicates to the client on its own, so there is no packet here and no way for the book to show
 * something the server disagrees with.
 * <p>
 * Drawn rather than textured, at 1:1. Every panel, border and tab is a flat fill at exact pixel
 * coordinates and every glyph lands on the pixel grid, which is the only way text in this engine is
 * genuinely sharp — stretching a book texture and scaling the matrix were both tried, and both
 * resample rather than enlarge. The page is made readable by being wide, not by being magnified.
 */
public class FieldGuideScreen extends Screen {

	private static final int PANEL_W = 320, PANEL_H = 240;

	/**
	 * The page itself, so it can be weathered, stained and aged without touching code.
	 * <p>
	 * Everything static about the background lives in this texture — the parchment, the frame, the
	 * rule under the heading and the recess the specimen is drawn into. Only the parts that must
	 * react to state are still drawn: the tabs, which have an active and an idle look, and the
	 * illustration, which is a live model. Anything a paint program can express belongs in the
	 * file, not in a fill call.
	 * <p>
	 * The editable copy is {@code design/gui/field_guide.png}.
	 */
	private static final Identifier PAGE_TEXTURE = Primordia.id("textures/gui/field_guide.png");

	/** Where in the page the tab faces are cut from — a clean patch, clear of the heading rule. */
	private static final int TAB_SAMPLE_U = 40, TAB_SAMPLE_V = 60;
	private static final int TAB_W = 28, TAB_H = 26;
	private static final int MARGIN = 14;
	private static final int LINE_H = 10;

	/**
	 * Height of the heading band under the tab strip: the section title and the rule beneath it.
	 * <p>
	 * The tree is a viewport that pans, so it needs a top edge to be clipped against, and that edge
	 * is the bottom of this band rather than the top of the page. Clipping to the page meant a
	 * panned tree drew its branches straight through the section title and the rule under it — the
	 * heading is part of the book, not part of the diagram.
	 */
	private static final int HEADING_H = 22;

	// Parchment, not the vanilla grey, so the guide reads as a document rather than a machine.
	private static final int PAGE = 0xFFE9E2CC;
	private static final int PAGE_EDGE = 0xFFF6F1E2;
	private static final int FRAME = 0xFF5A4A32;
	private static final int FRAME_DARK = 0xFF3B301F;
	private static final int TAB_IDLE = 0xFFC4BCA4;
	private static final int RULE = 0xFFBDB39A;
	private static final int INK = 0xFF3A3226;
	private static final int INK_FAINT = 0xFF7B7260;
	private static final int INK_TITLE = 0xFF2A2218;

	/**
	 * The guide being read.
	 * <p>
	 * Refreshed from the inventory every tick rather than held as the stack the screen opened with.
	 * A guide's contents are item components, and the server replies to a change by sending the whole
	 * stack back — which the client installs as a <i>new</i> {@link ItemStack} in the slot, leaving
	 * the one captured at open pointing at the state before the edit. That is why renaming a species
	 * reported success and then showed the old name: the rename had landed, on an object this screen
	 * was no longer looking at.
	 */
	private ItemStack guide;

	/** Hash of the guide's entries as last drawn, for noticing edits that arrive while it is open. */
	private int contentSignature;

	/** Pages of the currently selected tab. Rebuilt when the tab changes. */
	private final List<List<Component>> pages = new ArrayList<>();
	private final List<ItemStack> tabIcons = new ArrayList<>();
	private int section;
	private int page;

	private int left, top;

	/**
	 * The species shown one-per-page in the Reference tab, in the same order as its index page.
	 * Held separately from {@link #pages} because those pages are drawn as a split plate — stats on
	 * one side, the animal on the other — rather than as lines of prose.
	 */
	private final List<GuideData.Entry> plates = new ArrayList<>();
	/** Frames the guide has been open, driving the slow turn of the specimen. */
	private float age;
	/** Inferred relationships, laid out as a drawable tree when the Bloodlines tab is opened. */
	private List<Phylogeny.TreeNode> treeRoots = List.of();
	private final List<Phylogeny.TreeNode> treeNodes = new ArrayList<>();
	/**
	 * The Bloodlines tab is a window onto the tree rather than a page of it: pan with the left
	 * button, zoom with the wheel. A tree that had to fit the page stopped being legible the moment
	 * the collection grew, and paginating a tree splits branches across pages, which is worse.
	 */
	private float treeZoom = 1f;
	private float treePanX, treePanY;

	/**
	 * Bounds of the species name on the open plate, and whose name it is.
	 * <p>
	 * Set while drawing rather than computed on click, because the plate's layout is decided as it
	 * is laid out — recomputing where the heading "would have been" in the click handler is the
	 * kind of duplicate that stays correct exactly until the layout changes.
	 */
	private int titleY = -1;
	private int titleW;
	private long titleLineage;

	/** When and where the last left click landed, for spotting a double-click on the name. */
	private long lastClickTime;
	private long lastClickLineage;

	/** Milliseconds within which a second click on the same name counts as a double-click. */
	private static final long DOUBLE_CLICK_MS = 400;

	private static final java.util.Set<String> viewedChapters = new java.util.HashSet<>();
	private static final java.util.Set<Long> viewedLineages = new java.util.HashSet<>();
	private static int viewedTreeEntryCount = -1;

	/**
	 * Turntable angle for the specimen plate, in radians.
	 * <p>
	 * Accumulated rather than derived from a clock, which is what lets a dragged specimen carry on
	 * turning from wherever it was released instead of snapping back to whatever angle the elapsed
	 * time happens to imply.
	 */
	private float plateSpin;

	/** Set while the reader is typing a name for the species on the open plate. */
	private boolean naming;
	private String nameBuffer = "";
	/** Where the naming line was drawn last frame, so a click on it can be caught. */
	private int nameLineY = -1;

	/** Drag tracking, shared by the tree's pan and the plate's turntable. */
	private boolean dragging;
	private double dragFromX, dragFromY;
	private double dragDistance;

	public FieldGuideScreen(ItemStack guide) {
		this(guide, 0);
	}

	/**
	 * Opens the book at a chosen tab.
	 * <p>
	 * Used by the splicing bench, which opens the guide at Self: standing at the machine and being
	 * handed the page you plan from is the whole interaction, and making the player find the tab
	 * themselves every time would be a worse book.
	 */
	public FieldGuideScreen(ItemStack guide, int openAt) {
		super(Component.literal("Primordia Field Guide"));
		this.guide = guide;
		this.section = Math.max(0, Math.min(openAt, GuideChapters.SECTIONS.size() - 1));
	}

	@Override
	protected void init() {
		left = (width - PANEL_W) / 2;
		top = (height - PANEL_H) / 2;

		tabIcons.clear();
		for (GuideChapters.Section s : GuideChapters.SECTIONS) {
			// A tab whose icon names an item that does not exist still needs to draw something, or
			// one bad id would leave an invisible, unclickable tab.
			// get() returns an Optional<Holder> in 26.2; getValue() is the one that still hands back
			// the item itself, falling through to AIR for an id nothing is registered under.
			var item = BuiltInRegistries.ITEM.getValue(Identifier.parse(s.iconItemId()));
			tabIcons.add(new ItemStack(item == Items.AIR ? Items.PAPER : item));
		}
		buildSection();
		if (minecraft != null && minecraft.player != null) {
			minecraft.player.playSound(net.minecraft.sounds.SoundEvents.BOOK_PUT, 0.8f, 1.0f);
		}
	}

	/**
	 * Picks the guide back up out of the inventory and redraws if anything in it changed.
	 * <p>
	 * Covers a rename coming back from the server and a report filing itself while the book is open;
	 * both edit the stack rather than telling this screen anything. The page is held across the
	 * rebuild, because having the book turn back to the front every time a report lands would be a
	 * worse bug than the one this fixes.
	 */
	@Override
	public void tick() {
		super.tick();

		ItemStack live = findGuideInInventory();
		if (live != null) guide = live;

		if (dev.jsz.primordia.PrimordiaClient.getClientGuideData().entries().hashCode() == contentSignature) return;

		// buildSection resets the view as well as the pages, which is right when the reader picks a
		// different tab and wrong here — this is the same page redrawn with newer contents. A report
		// filing itself must not fling the tree back to its default zoom, spin the specimen back to
		// the front, or eat a half-typed name.
		int keepPage = page;
		float keepZoom = treeZoom, keepPanX = treePanX, keepPanY = treePanY, keepSpin = plateSpin;
		boolean keepNaming = naming;
		String keepBuffer = nameBuffer;

		buildSection();

		page = Math.max(0, Math.min(keepPage, pages.size() - 1));
		treeZoom = keepZoom;
		treePanX = keepPanX;
		treePanY = keepPanY;
		// The tree that pan was legal against is not the tree that was just rebuilt — a newly filed
		// bloodline can move the extents under a view that is sitting at its limit.
		clampPan();
		plateSpin = keepSpin;
		naming = keepNaming;
		nameBuffer = keepBuffer;
	}

	/** The live stack for this guide, or null if the reader is no longer carrying one. */
	private ItemStack findGuideInInventory() {
		if (minecraft == null || minecraft.player == null) return null;
		var inventory = minecraft.player.getInventory();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack candidate = inventory.getItem(slot);
			// The same first-match rule the server uses to decide which guide a rename applies to,
			// so a player carrying two of them edits and reads the same one.
			if (candidate.is(dev.jsz.primordia.registry.PrimordiaItems.FIELD_GUIDE)) return candidate;
		}
		return null;
	}

	private void buildSection() {
		pages.clear();
		page = 0;
		GuideChapters.Section current = GuideChapters.SECTIONS.get(section);

		GuideData data = dev.jsz.primordia.PrimordiaClient.getClientGuideData();
		// Recorded here rather than in tick() so that every path which rebuilds — opening the book,
		// changing tab — leaves the two in step, and the first tick after any of them has nothing to do.
		contentSignature = data.entries().hashCode();
		for (GuideChapters.Chapter chapter : current.chapters()) {
			List<Component> lines = new ArrayList<>();
			if (!chapter.unlocked(data)) {
				// The title stays visible. Knowing an entry exists and cannot yet be read is the
				// nudge; hiding it entirely would just look like a shorter book.
				lines.add(Component.literal(chapter.title()).withStyle(ChatFormatting.BOLD, ChatFormatting.OBFUSCATED));
				lines.add(Component.empty());
				for (String w : wrap(chapter.unlock().hint())) {
					lines.add(Component.literal(w).withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
				}
				flush(lines);
				continue;
			}
			lines.add(Component.literal(chapter.title()).withStyle(ChatFormatting.BOLD));
			lines.add(Component.empty());
			for (String paragraph : chapter.paragraphs()) {
				if (paragraph.isEmpty()) {
					lines.add(Component.empty());
					continue;
				}
				for (String wrapped : wrap(paragraph)) lines.add(Component.literal(wrapped));
			}
			flush(lines);
		}
		plates.clear();
		treeRoots = List.of();
		treeNodes.clear();
		treeZoom = 1f;
		treePanX = 0f;
		treePanY = 0f;
		plateSpin = 0f;
		naming = false;
		nameBuffer = "";
		if (section == GuideChapters.REFERENCE_TAB) buildReference();
		if (section == GuideChapters.LINEAGE_TAB) buildLineages();
		if (section == GuideChapters.SELF_TAB) buildSelf(data);

		markSectionViewed(section, data);
	}

	/**
	 * The Reference tab: an index page, then one plate per species.
	 * <p>
	 * The plates are not built as lines here — they are laid out at draw time, because half of each
	 * is a live render of the animal and the other half is a stat block sized to fill the rest.
	 */
	private void buildReference() {
		GuideData data = dev.jsz.primordia.PrimordiaClient.getClientGuideData();
		List<GuideData.Entry> entries = data.entries();

		List<Component> index = new ArrayList<>();
		index.add(Component.literal("Species on file").withStyle(ChatFormatting.BOLD));
		index.add(Component.empty());
		if (entries.isEmpty()) {
			index.add(Component.literal("Nothing filed yet.").withStyle(ChatFormatting.DARK_GRAY));
			index.add(Component.empty());
			for (String w : wrap("Decode a sequence in a Gene Lab while carrying this guide. "
					+ "The report will file itself and the paper will be consumed.")) {
				index.add(Component.literal(w).withStyle(ChatFormatting.DARK_GRAY));
			}
			flush(index);
			return;
		}
		index.add(Component.literal(data.speciesCount() + " species · "
				+ data.specimensFiled() + " specimens filed").withStyle(ChatFormatting.DARK_GRAY));
		index.add(Component.empty());

		// Two columns. One per line ran off the bottom of the page as soon as a dozen bloodlines
		// were on file, and left the right half of a wide page completely empty while doing it.
		int perColumn = (linesPerPage() - 4);
		List<GuideData.Entry> listed = entries;
		for (int row = 0; row < Math.min(perColumn, (listed.size() + 1) / 2); row++) {
			int rightRow = row + perColumn;
			Component line = indexEntry(listed.get(row));
			if (rightRow < listed.size()) {
				line = Component.literal("").append(line).append(pad(line))
						.append(indexEntry(listed.get(rightRow)));
			}
			index.add(line);
		}
		if (listed.size() > perColumn * 2) {
			index.add(Component.empty());
			index.add(Component.literal("…and " + (listed.size() - perColumn * 2) + " more")
					.withStyle(ChatFormatting.DARK_GRAY));
		}
		flush(index);

		// One page per species, drawn by drawPlate. The empty line lists keep the page count and
		// the plate list in step, so page N of this tab is always plates.get(N - indexPages).
		for (GuideData.Entry entry : entries) {
			if (entry.genome() == null) continue;
			plates.add(entry);
			pages.add(List.of());
		}
	}

	/** One line of the species index: the label, its count, and how well it is known. */
	private static Component indexEntry(GuideData.Entry entry) {
		DecodeAccuracy accuracy = entry.accuracy();
		return Component.literal(entry.displayName()).withStyle(ChatFormatting.DARK_AQUA)
				.append(Component.literal(" x" + entry.filed()).withStyle(ChatFormatting.DARK_GRAY))
				.append(Component.literal(" " + accuracy.label.toLowerCase()).withStyle(accuracy.colour));
	}

	/** Spaces enough to carry the second column to the middle of the page. */
	private Component pad(Component left) {
		int target = (PANEL_W - MARGIN * 2) / 2;
		StringBuilder spaces = new StringBuilder();
		int width = font.width(left);
		while (width + font.width(spaces.toString()) < target) spaces.append(' ');
		return Component.literal(spaces.toString());
	}

	/** The Bloodlines tab: a drawn tree, not a list. */
	private void buildLineages() {
		treeRoots = Phylogeny.layout(dev.jsz.primordia.PrimordiaClient.getClientGuideData().entries());
		treeNodes.clear();
		Phylogeny.collect(treeRoots, treeNodes);
		centreTree();
		// One page. The tree pans rather than paginating, because a branch split across two pages
		// is not a tree any more.
		pages.add(List.of());
	}

	/** Node geometry for the tree, in panel pixels. */
	private static final int NODE = 30;
	private static final int NODE_GAP_X = 10;
	private static final int ROW_H = 46;

	/**
	 * Puts the whole tree in the middle of the page.
	 * <p>
	 * Pan starts at zero, which places the root at the layout's own origin — the top-left corner of
	 * the page. That is correct arithmetic and useless to look at: a reader opening the tab wants
	 * the tree, not its first node. This offsets the view so the drawing is centred on whatever
	 * space it actually occupies.
	 */
	private void centreTree() {
		if (treeNodes.isEmpty()) {
			treePanX = 0f;
			treePanY = 0f;
			return;
		}
		float treeW = treeSpanX();
		float treeH = treeSpanY();

		float viewW = PANEL_W - MARGIN * 2;
		float viewH = PANEL_H - TAB_H - 8;
		treePanX = (viewW - treeW) / 2f;
		// The 16 undoes the fixed offset nodeY adds, so the top row lands where this asks it to.
		treePanY = (viewH - treeH) / 2f - 16f;
	}

	/** Width the drawn tree occupies at the current zoom, in panel pixels. */
	private float treeSpanX() {
		float widestColumn = 0f;
		for (Phylogeny.TreeNode node : treeNodes) {
			widestColumn = Math.max(widestColumn, node.column);
		}
		return widestColumn * (NODE + NODE_GAP_X) * treeZoom + NODE * treeZoom;
	}

	/** Height the drawn tree occupies at the current zoom, in panel pixels. */
	private float treeSpanY() {
		int deepest = 0;
		for (Phylogeny.TreeNode node : treeNodes) {
			deepest = Math.max(deepest, node.depth);
		}
		return deepest * ROW_H * treeZoom + NODE * treeZoom;
	}

	/**
	 * Holds the pan inside the tree's own extents, so the drawing can never be dragged off the page.
	 * <p>
	 * A viewport with no travel limit will happily be flung into empty paper, and once the tree is
	 * gone there is nothing on screen to say which way it went — the only recovery was to change tab
	 * and come back. The limit is derived from the layout rather than fixed, so it grows with the
	 * collection on its own: the bound is "the tree may leave the page until {@link #keepOnPage} of
	 * it is left", which for a two-node tree is a few pixels of travel and for a fifty-node one is
	 * most of a screen in every direction. Zoom is in the spans too, so pulling back to see the whole
	 * clade widens the leash at the same time as it shrinks the drawing.
	 * <p>
	 * Clamping the pan rather than the gesture means a drag that runs past the edge simply stops
	 * there and keeps tracking the cursor on the other axis, which is what every map does.
	 */
	private void clampPan() {
		if (treeNodes.isEmpty()) return;
		float keep = keepOnPage();

		// The clip rectangle drawTree uses, which is the real edge of the viewport.
		float spanX = treeSpanX();
		treePanX = MathX.clamp(treePanX,
				2f + keep - MARGIN - spanX,
				PANEL_W - 2f - keep - MARGIN);

		// nodeY offsets by bodyTop + HEADING_H, and bodyTop is TAB_H, so the top of the viewport sits
		// at pan zero. That cancels out of the lower bound and leaves the spans as the only term.
		float spanY = treeSpanY();
		treePanY = MathX.clamp(treePanY,
				keep - spanY,
				PANEL_H - 4f - (TAB_H + HEADING_H) - keep);
	}

	/**
	 * How much of the tree has to stay on the page.
	 * <p>
	 * One whole box and the gap beside it, so whatever is left at the limit is a node the reader can
	 * see and grab rather than a stub of a connector. Capped against the viewport for the case where
	 * the boxes are larger than the window: at maximum zoom on a small tree, demanding a whole node
	 * stay visible would be a stricter limit than the page itself.
	 */
	private float keepOnPage() {
		return Math.min(nodeSize() + NODE_GAP_X, (PANEL_W - MARGIN * 2) * 0.4f);
	}

	/**
	 * Node size and spacing at the current zoom.
	 * <p>
	 * Scaled arithmetically rather than by putting a transform on the matrix, so the counts under
	 * each box stay on the pixel grid. Scaling the matrix would resample the font, which is the
	 * same mistake that made the whole guide look soft.
	 */
	private int nodeSize() {
		return Math.max(8, Math.round(NODE * treeZoom));
	}

	private int nodeX(Phylogeny.TreeNode node) {
		return Math.round(node.column * (NODE + NODE_GAP_X) * treeZoom + treePanX) + MARGIN;
	}

	private int nodeY(Phylogeny.TreeNode node, int bodyTop) {
		return Math.round(node.depth * ROW_H * treeZoom + treePanY) + bodyTop + HEADING_H;
	}

	/**
	 * Draws the family tree: a box per bloodline with the animal inside it, joined to its parent.
	 * <p>
	 * Elbowed connectors rather than straight diagonals, because a diagonal between two boxes reads
	 * as an arbitrary link while a vertical drop into a horizontal run reads as descent — the same
	 * convention a pedigree chart uses, for the same reason.
	 */
	private void drawTree(GuiGraphicsExtractor context, int bodyTop, double mx, double my) {
		if (treeNodes.isEmpty()) {
			context.text(font, Component.literal("Nothing filed yet."),
					MARGIN, bodyTop + 30, INK_FAINT, false);
			int y = bodyTop + 44;
			for (String w : wrap("Two or more bloodlines are needed before anything can be said "
					+ "about how they are related.")) {
				context.text(font, Component.literal(w), MARGIN, y, INK_FAINT, false);
				y += LINE_H;
			}
			return;
		}

		// Clip to the page. Without this a panned tree is drawn straight over the tab strip and
		// off the edge of the book, which is what it did before it became a viewport.
		//
		// Panel-local coordinates, because 26.2 puts the rectangle through the current matrix before
		// pushing it — the opposite of the version this was written against, where the panel offset
		// had to be added by hand. Adding it here as well double-offset the clip by the panel origin
		// and cut away the whole tree, leaving a tab that drew nothing but still answered the mouse.
		//
		// There is no flush to do either side of this any more: 26.2 records the active scissor on
		// each element as it is extracted, so what was queued earlier keeps the rectangle it was
		// queued under rather than picking up whichever one happens to be current at draw time.
		context.enableScissor(2, bodyTop + HEADING_H, PANEL_W - 2, PANEL_H - 4);

		int size = nodeSize();
		for (Phylogeny.TreeNode parent : treeNodes) {
			int px = nodeX(parent) + size / 2;
			int py = nodeY(parent, bodyTop) + size;
			for (Phylogeny.TreeNode child : parent.children) {
				int cx = nodeX(child) + size / 2;
				int cy = nodeY(child, bodyTop);
				int mid = (py + cy) / 2;
				context.fill(px - 1, py, px + 1, mid, RULE);
				context.fill(Math.min(px, cx) - 1, mid - 1, Math.max(px, cx) + 1, mid + 1, RULE);
				context.fill(cx - 1, mid, cx + 1, cy, RULE);
			}
		}

		Phylogeny.TreeNode hovered = null;
		for (Phylogeny.TreeNode node : treeNodes) {
			int x = nodeX(node);
			int y = nodeY(node, bodyTop);
			if (x + size < 0 || x > PANEL_W
					|| y + size < bodyTop + HEADING_H || y > PANEL_H) continue;

			boolean over = mx >= x && mx < x + size && my >= y && my < y + size;
			if (over) hovered = node;

			context.fill(x - 1, y - 1, x + size + 1, y + size + 1, over ? INK_TITLE : FRAME);
			context.fill(x, y, x + size, y + size, 0xFFDDD5BC);

			Genome genome = node.entry.genome();
			boolean drawn = genome != null && CreaturePreview.render(context, genome,
					x + size / 2, y + size / 2 + size / 10, size - 4, age * 0.0065f,
					CreaturePreview.lodForSize(size - 4));
			if (!drawn) {
				context.text(font, Component.literal("?"),
						x + size / 2 - 2, y + size / 2 - 4, INK_FAINT, false);
			}
			// Counts are drawn at their true size whatever the zoom, so they stay legible when the
			// view is pulled back to see the whole tree — and are dropped entirely once the boxes
			// are too small to sit under without colliding.
			if (size >= 22) {
				Component count = Component.literal("x" + node.entry.filed());
				context.text(font, count,
						x + (size - font.width(count)) / 2, y + size + 2,
						INK_FAINT, false);
			}
		}
		featherEdges(context, bodyTop, 2, bodyTop + HEADING_H, PANEL_W - 2, PANEL_H - 4);
		context.disableScissor();
		hoveredNode = hovered;
	}

	/** How far the viewport edge fade reaches inward, in panel pixels. */
	private static final int FEATHER = 14;

	/**
	 * Fades the tree out into the page around the edge of its viewport.
	 * <p>
	 * The scissor alone leaves branches and boxes sliced off mid-stroke at the boundary, which reads
	 * as a rendering fault rather than as a window onto something larger. Washing the last few pixels
	 * back to the page makes the edge look like the drawing running off the paper.
	 * <p>
	 * The wash is the page <i>texture</i> re-blitted over itself, not a flat fill of {@link #PAGE}.
	 * Fading to the flat colour was the first attempt and it was wrong twice over: the constant is
	 * lighter than most of the sheet, so the border read as a white halo rather than as paper, and at
	 * full coverage it painted over the stains and grain the texture carries — a clean cream frame
	 * around a weathered page. Sampling the texture at the same offset the background blit used means
	 * full coverage is indistinguishable from untouched page, whatever is printed there.
	 *
	 * @param bodyTop where the page art starts, so the source offset matches the background blit
	 */
	private void featherEdges(GuiGraphicsExtractor context, int bodyTop, int x1, int y1, int x2, int y2) {
		for (int i = 0; i < FEATHER; i++) {
			// Smoothstep, not a power curve. A quadratic falloff was the first attempt and it did not
			// read as a fade at all: it is already down to half coverage three pixels in, so the whole
			// transition happens inside a band too narrow to see and what is left looks like the same
			// hard cutoff moved inward. What makes an edge read as soft is a gentle *start* — the
			// first few pixels of content must dim almost imperceptibly — which needs a curve that is
			// flat at both ends rather than steepest where the eye is looking.
			float d = (i + 0.5f) / FEATHER;
			int alpha = Math.round(255f * (1f - d * d * (3f - 2f * d)));
			if (alpha <= 0) continue;
			// White tint: the texture keeps its own colours and only the alpha is ours.
			int tint = (alpha << 24) | 0x00FFFFFF;
			page(context, bodyTop, x1, y1 + i, x2 - x1, 1, tint);
			page(context, bodyTop, x1, y2 - i - 1, x2 - x1, 1, tint);
			page(context, bodyTop, x1 + i, y1, 1, y2 - y1, tint);
			page(context, bodyTop, x2 - i - 1, y1, 1, y2 - y1, tint);
		}
	}

	/**
	 * Blits a patch of the page texture over itself at {@code tint}'s alpha.
	 * <p>
	 * The source offset is the destination minus {@code bodyTop}, which is exactly the mapping the
	 * background blit sets up, so the patch lands back on the pixels it came from.
	 */
	private void page(GuiGraphicsExtractor context, int bodyTop, int x, int y, int w, int h, int tint) {
		context.blit(RenderPipelines.GUI_TEXTURED, PAGE_TEXTURE, x, y, x, y - bodyTop, w, h, 512, 256, tint);
	}

	/** The tree node under the cursor this frame, for the tooltip drawn after the matrix pops. */
	private Phylogeny.TreeNode hoveredNode;

	/**
	 * One species across the page: the record on the left, the animal on the right.
	 * <p>
	 * Splitting it this way is what makes the entry read as a specimen plate rather than a table.
	 * The stats take the wider half because they are the part that changes as the reader works; the
	 * illustration is the part that says, at a glance, which animal this is.
	 */
	private void drawPlate(GuiGraphicsExtractor context, GuideData.Entry entry, int bodyTop) {
		int split = MARGIN + (PANEL_W - MARGIN * 2) * 5 / 9;
		Genome genome = entry.genome();
		DecodeAccuracy accuracy = entry.accuracy();

		int y = bodyTop + 26;
		Component title = Component.literal(entry.displayName()).withStyle(ChatFormatting.BOLD);
		if (entry.named()) {
			title = title.copy().append(Component.literal(" " + entry.label()).withStyle(ChatFormatting.GRAY));
		}
		context.text(font, title, MARGIN, y, INK_TITLE, false);
		// Remembered so a double-click on the name can be hit-tested. Recorded every frame the
		// plate is drawn, and cleared by every other section, so it can never point at a heading
		// that is no longer on the page.
		titleY = y;
		titleW = font.width(title);
		titleLineage = entry.lineage();
		y += LINE_H + 2;
		// The marking is already on the title line, so just show the filing stats here.
		String subtitle = entry.filed() + " filed · generation " + entry.generation();
		context.text(font, Component.literal(subtitle), MARGIN, y, INK_FAINT, false);
		y += LINE_H;
		context.text(font, Component.literal(accuracy.label).withStyle(accuracy.colour),
				MARGIN, y, INK_FAINT, false);
		y += LINE_H + 4;

		nameLineY = -1;
		if (entry.nameable() && !entry.named()) {
			nameLineY = y;
			if (naming) {
				String shown = nameBuffer + ((age * 0.06f) % 2 < 1 ? "_" : "");
				context.text(font, Component.literal("Name: ").withStyle(ChatFormatting.DARK_GRAY)
						.append(Component.literal(shown).withStyle(ChatFormatting.BLACK)),
						MARGIN, y, INK, false);
			} else {
				context.text(font,
						Component.literal("⊕ name this species").withStyle(ChatFormatting.ITALIC),
						MARGIN, y, 0xFF2C6E5A, false);
			}
			y += LINE_H + 4;
		}

		// Shown only to someone who can actually use it. An undiscoverable gesture is the same as
		// no gesture, and the heading gives no other sign that it can be clicked.
		if (canConjure()) {
			context.text(font,
					Component.literal("⊕ double-click name to summon").withStyle(ChatFormatting.ITALIC),
					MARGIN, y, 0xFF6A5A8A, false);
			y += LINE_H + 4;
		}

		if (genome != null) {
			int valueX = MARGIN + 68;
			y = statLine(context, "Speed", accuracy.describeFraction(genome.raw(Gene.SPEED)), y, valueX);
			y = statLine(context, "Aggression", accuracy.describeFraction(genome.raw(Gene.AGGRESSION)), y, valueX);
			y = statLine(context, "Fear", accuracy.describeFraction(genome.raw(Gene.FEAR)), y, valueX);
			y = statLine(context, "Social", accuracy.describeFraction(genome.raw(Gene.SOCIABILITY)), y, valueX);
			y = statLine(context, "Diet", accuracy.describeFraction(genome.raw(Gene.DIET)), y, valueX);
			y = statLine(context, "Size", accuracy.describeFraction(genome.raw(Gene.SIZE)), y, valueX);
			y = statLine(context, "Stamina", accuracy.describeFraction(genome.raw(Gene.STAMINA)), y, valueX);
			y = statLine(context, "Drift", accuracy.describeFraction(genome.raw(Gene.MUTABILITY)), y, valueX);
			y += 4;

			// What it will take from your hand. Withheld until the reader has studied the kind
			// enough to have earned it — a bait list handed over on first sight would make the
			// rest of the work optional.
			context.text(font, Component.literal("Takes"), MARGIN, y, INK_FAINT, false);
			if (accuracy.atLeast(DecodeAccuracy.PARTIAL)) {
				ItemStack bait = new ItemStack(TamingPreference.favouriteFood(genome));
				context.item(bait, valueX, y - 5);
				context.text(font, bait.getHoverName(), valueX + 20, y, INK, false);
			} else {
				context.text(font, Component.literal("not yet known"), valueX, y,
						INK_FAINT, false);
			}
			y += LINE_H + 4;

			int needed = accuracy.decodesUntilNextLevel(entry.filed());
			for (String w : wrapTo(needed > 0
					? "Bring back " + needed + " more of this kind to read it clearly."
					: "Nothing further to learn from this one.", split - MARGIN - 4)) {
				context.text(font, Component.literal(w),
						MARGIN, y, needed > 0 ? INK_FAINT : 0xFF2C6E5A, false);
				y += LINE_H;
			}
		}

		// Drawn here rather than painted into the page, because only this tab has a specimen on it
		// — baked into the texture it appeared behind every chapter of prose as well.
		int plateX = split + 6;
		int plateW = PANEL_W - MARGIN - plateX;
		int plateY = bodyTop + 26;
		int plateH = PANEL_H - plateY - 22;
		context.fill(plateX, plateY, plateX + plateW, plateY + plateH, 0x30000000);
		context.fill(plateX, plateY, plateX + plateW, plateY + 1, RULE);
		context.fill(plateX, plateY + plateH - 1, plateX + plateW, plateY + plateH, RULE);
		context.fill(plateX, plateY, plateX + 1, plateY + plateH, RULE);
		context.fill(plateX + plateW - 1, plateY, plateX + plateW, plateY + plateH, RULE);

		boolean drawn = genome != null && CreaturePreview.render(context, genome,
				plateX + plateW / 2, plateY + plateH / 2 + 8,
				Math.min(plateW, plateH), plateSpin);
		if (!drawn) {
			Component waiting = Component.literal("sketching…");
			context.text(font, waiting,
					plateX + (plateW - font.width(waiting)) / 2,
					plateY + plateH / 2 - 4, INK_FAINT, false);
		}
	}

	private int statLine(GuiGraphicsExtractor context, String name, String value, int y, int valueX) {
		context.text(font, Component.literal(name), MARGIN, y, INK_FAINT, false);
		context.text(font, Component.literal(value), valueX, y, INK, false);
		return y + LINE_H;
	}

	/** Wrap to an arbitrary width, for the plate's narrower left column. */
	// ------------------------------------------------------------------ the self tab

	/** Where on the body each branch is drawn as attaching, so a callout has something to point at. */
	private record Anchor(SpliceBranch branch, int x, int y, boolean left) {
	}

	private final List<Anchor> selfAnchors = new ArrayList<>();
	private SpliceBranch selfHover;

	private static final int FIGURE_X = 160;

	/**
	 * The splice tree as an anatomy plate.
	 * <p>
	 * This replaced seven pages of ruled text, which were accurate and unreadable. A tech tree only
	 * does its job if the player can take it in at a glance, and six branches with three depths each
	 * is small enough to draw all at once — so it is drawn all at once, around a figure, with a line
	 * from each branch to the part of the body it changes. What was a list you paged through is a
	 * diagram you look at.
	 * <p>
	 * The figure is a drawn silhouette rather than the player's own model. Nothing in this version
	 * hands a GUI an entity to render, and building that path was more than the picture was worth;
	 * the anatomy is schematic anyway, which is what a plate in a naturalist's journal would be.
	 */
	private void buildSelf(GuideData data) {
		// One page, drawn rather than written. The empty line list keeps the pager at 1/1.
		pages.add(new ArrayList<>());

		selfAnchors.clear();
		int top = TAB_H + 46;
		// Left column reads down the body, right column likewise, so the eye tracks head to foot.
		selfAnchors.add(new Anchor(SpliceBranch.DISPOSITION, FIGURE_X - 11, top + 8, true));
		selfAnchors.add(new Anchor(SpliceBranch.LIGHT, FIGURE_X - 12, top + 34, true));
		selfAnchors.add(new Anchor(SpliceBranch.PHYSIOLOGY, FIGURE_X - 9, top + 74, true));
		selfAnchors.add(new Anchor(SpliceBranch.CLIMATE, FIGURE_X + 12, top + 32, false));
		selfAnchors.add(new Anchor(SpliceBranch.HABIT, FIGURE_X + 22, top + 46, false));
		selfAnchors.add(new Anchor(SpliceBranch.COLOUR, FIGURE_X + 12, top + 66, false));
	}

	/** Draws the figure, the callouts and the leader lines between them. */
	private void drawSelf(GuiGraphicsExtractor context, int bodyTop, double mx, double my) {
		GuideData data = dev.jsz.primordia.PrimordiaClient.getClientGuideData();
		var splices = dev.jsz.primordia.PrimordiaClient.getClientSplices();
		selfHover = null;

		int slots = dev.jsz.primordia.splice.SpliceTree.slots(data);
		Component header = Component.literal("Gene slots " + splices.used() + " / " + slots);
		context.text(font, header, PANEL_W - MARGIN - font.width(header), bodyTop + 8, INK_FAINT, false);

		int leftY = bodyTop + 24;
		int rightY = bodyTop + 24;
		for (Anchor anchor : selfAnchors) {
			int boxW = 96;
			int boxH = 30;
			int boxX = anchor.left() ? MARGIN : PANEL_W - MARGIN - boxW;
			int boxY = anchor.left() ? leftY : rightY;
			if (anchor.left()) leftY += boxH + 6; else rightY += boxH + 6;
			drawCallout(context, data, splices, anchor, boxX, boxY, boxW, boxH, mx, my);
		}
	}

	/**
	 * The recess the player is drawn into, in panel coordinates.
	 * <p>
	 * The figure used to be a sketch of rectangles, on the grounds that nothing here handed a GUI an
	 * entity to render. That was wrong — {@code InventoryScreen} has done exactly that for years —
	 * and a plate about what <i>you</i> are becoming should show you. The callout anchors are
	 * unchanged, because the model occupies the same block of the page the sketch did.
	 */
	private static final int FIGURE_W = 52;
	private static final int FIGURE_H = 86;
	/** Roughly half the recess height, which is what the helper wants to fill it. */
	private static final int FIGURE_SCALE = 38;

	private void box(GuiGraphicsExtractor context, int x, int y, int w, int h, int fill, int edge) {
		context.fill(x, y, x + w, y + h, edge);
		context.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
	}

	/**
	 * One branch's card, and the line tying it to the body.
	 * <p>
	 * A card says three things and no more: what the branch is, how deep the player has taken it,
	 * and what is either carried or still wanted. Anything further is what the bench and the hover
	 * tooltip are for — the plate's job is to be readable in one pass.
	 */
	private void drawCallout(GuiGraphicsExtractor context, GuideData data,
	                         dev.jsz.primordia.splice.SpliceLoadout splices, Anchor anchor,
	                         int x, int y, int w, int h, double mx, double my) {
		SpliceBranch branch = anchor.branch();
		var reached = dev.jsz.primordia.splice.SpliceTree.reached(data, branch);
		var worn = splices.inBranch(branch);
		boolean open = reached != null;
		boolean hovered = mx >= x && mx < x + w && my >= y && my < y + h;
		if (hovered) selfHover = branch;

		// The leader line: out of the card's inner edge, then straight to the body.
		int fromX = anchor.left() ? x + w : x;
		int fromY = y + h / 2;
		int lineColour = worn != null ? 0xFF3F7D4F : open ? 0xFF8A7F66 : 0xFFB9AF97;
		int midX = anchor.left() ? (fromX + anchor.x()) / 2 : (anchor.x() + fromX) / 2;
		hLine(context, Math.min(fromX, midX), Math.max(fromX, midX), fromY, lineColour);
		vLine(context, midX, Math.min(fromY, anchor.y()), Math.max(fromY, anchor.y()), lineColour);
		hLine(context, Math.min(midX, anchor.x()), Math.max(midX, anchor.x()), anchor.y(), lineColour);
		context.fill(anchor.x() - 1, anchor.y() - 1, anchor.x() + 2, anchor.y() + 2, lineColour);

		int edge = hovered ? 0xFF6B6250 : 0xFFB0A68D;
		box(context, x, y, w, h, hovered ? 0xFFE6DFC9 : 0xFFDBD3BC, edge);

		context.text(font, Component.literal(branch.title), x + 4, y + 4,
				open ? INK_TITLE : INK_FAINT, false);

		StringBuilder pips = new StringBuilder();
		for (var depth : dev.jsz.primordia.splice.SpliceDepth.VALUES) {
			pips.append(dev.jsz.primordia.splice.SpliceTree.unlocked(data, branch, depth) ? "●" : "○");
		}
		context.text(font, Component.literal(pips.toString()), x + w - 4 - font.width(pips.toString()),
				y + 4, open ? 0xFF3F7D4F : INK_FAINT, false);

		String note;
		if (worn != null) {
			note = "carrying " + worn.label();
		} else if (open) {
			var best = dev.jsz.primordia.splice.SpliceTree.bestDonor(data, branch);
			note = best == null ? "no donor on file"
					: String.format("%s %.2f", best.label(), Math.min(best.potency(), reached.cap));
		} else {
			var first = dev.jsz.primordia.splice.SpliceDepth.VALUES[0];
			note = dev.jsz.primordia.splice.SpliceTree.progress(data, branch, first)
					+ " of " + first.required + " studied";
		}
		for (String line : wrapTo(note, w - 8)) {
			context.text(font, Component.literal(line), x + 4, y + 16,
					worn != null ? 0xFF3F7D4F : open ? INK : INK_FAINT, false);
			break;
		}
	}

	private void hLine(GuiGraphicsExtractor context, int x0, int x1, int y, int colour) {
		context.fill(x0, y, x1 + 1, y + 1, colour);
	}

	private void vLine(GuiGraphicsExtractor context, int x, int y0, int y1, int colour) {
		context.fill(x, y0, x + 1, y1 + 1, colour);
	}

	/**
	 * The reader, drawn into the middle of their own plate and turning to follow the cursor.
	 * <p>
	 * Called outside the panel's translated matrix, like the tooltips, because the helper works in
	 * real screen coordinates and rotates the model against the true cursor position — handing it
	 * panel-local coordinates would have it staring at a point somewhere off the page.
	 */
	private void drawSelfFigure(GuiGraphicsExtractor context, int mouseX, int mouseY) {
		if (minecraft == null || minecraft.player == null) return;
		int cx = left + FIGURE_X;
		int cy = top + TAB_H + 46;
		net.minecraft.client.gui.screens.inventory.InventoryScreen.extractEntityInInventoryFollowsMouse(
				context,
				cx - FIGURE_W / 2, cy - 4, cx + FIGURE_W / 2, cy + FIGURE_H - 4,
				FIGURE_SCALE, 0.0625f, mouseX, mouseY, minecraft.player);
	}

	/** The hovered branch's package, so the plate can be read in depth without leaving it. */
	private void selfTooltip(GuiGraphicsExtractor context, int mouseX, int mouseY) {
		if (selfHover == null) return;
		GuideData data = dev.jsz.primordia.PrimordiaClient.getClientGuideData();
		var splices = dev.jsz.primordia.PrimordiaClient.getClientSplices();
		var reached = dev.jsz.primordia.splice.SpliceTree.reached(data, selfHover);
		var worn = splices.inBranch(selfHover);

		List<Component> lines = new ArrayList<>();
		lines.add(Component.literal(selfHover.title).withStyle(ChatFormatting.WHITE));
		lines.add(Component.literal(selfHover.blurb).withStyle(ChatFormatting.GRAY));
		lines.add(Component.empty());

		for (var depth : dev.jsz.primordia.splice.SpliceDepth.VALUES) {
			boolean unlocked = dev.jsz.primordia.splice.SpliceTree.unlocked(data, selfHover, depth);
			int have = dev.jsz.primordia.splice.SpliceTree.progress(data, selfHover, depth);
			lines.add(Component.literal((unlocked ? "● " : "○ ") + depth.title
							+ "  to " + String.format("%.2f", depth.cap)
							+ (unlocked ? "" : "   " + Math.min(have, depth.required) + "/" + depth.required))
					.withStyle(unlocked ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY));
		}

		if (worn != null || reached != null) {
			lines.add(Component.empty());
			var best = dev.jsz.primordia.splice.SpliceTree.bestDonor(data, selfHover);
			float cap = reached == null ? 0f : reached.cap;
			for (var row : dev.jsz.primordia.splice.SpliceEffects.rowsFor(selfHover)) {
				float value = worn != null ? worn.valueOf(row.gene())
						: best == null || best.genome() == null ? 0f
								: Math.min(best.genome().raw(row.gene()), cap);
				lines.add(Component.literal("  "
								+ dev.jsz.primordia.splice.SpliceEffects.render(row, value) + "  "
								+ row.summary())
						.withStyle(row.beneficial(value) ? ChatFormatting.DARK_GREEN : ChatFormatting.RED));
			}
		}
		if (worn == null) {
			lines.add(Component.empty());
			lines.add(Component.literal("Isolate it at a splicing bench.")
					.withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
		}
		context.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
	}

	private List<String> wrapTo(String paragraph, int width) {
		List<String> out = new ArrayList<>();
		for (var part : font.getSplitter()
				.splitLines(paragraph, width, net.minecraft.network.chat.Style.EMPTY)) {
			out.add(part.getString());
		}
		return out;
	}

	private static Component trait(String name, String value) {
		return Component.literal(name + ": ").withStyle(ChatFormatting.DARK_GRAY)
				.append(Component.literal(value).withStyle(ChatFormatting.BLACK));
	}

	private List<String> wrap(String paragraph) {
		List<String> out = new ArrayList<>();
		for (var part : font.getSplitter()
				.splitLines(paragraph, PANEL_W - MARGIN * 2 - 8, net.minecraft.network.chat.Style.EMPTY)) {
			out.add(part.getString());
		}
		return out;
	}

	/**
	 * Lines of prose a page holds.
	 * <p>
	 * The slack at the end is what keeps the last line clear of the page number. Sixteen left them
	 * four pixels apart, which reads as a collision.
	 */
	private int linesPerPage() {
		return (PANEL_H - TAB_H - MARGIN * 2 - 22) / LINE_H;
	}

	private void flush(List<Component> lines) {
		int perPage = linesPerPage();
		for (int i = 0; i < lines.size(); i += perPage) {
			pages.add(new ArrayList<>(lines.subList(i, Math.min(lines.size(), i + perPage))));
		}
	}

	// ------------------------------------------------------------------ input

	/** Mouse position in panel-local coordinates, undoing the magnification. */
	private double localX(double mouseX) {
		return mouseX - left;
	}

	private double localY(double mouseY) {
		return mouseY - top;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mouseX = event.x(), mouseY = event.y();
		int button = event.button();
		double mx = localX(mouseX), my = localY(mouseY);

		int tab = tabAt(mx, my);
		if (tab >= 0) {
			if (section != tab) {
				section = tab;
				buildSection();
				if (minecraft != null && minecraft.player != null) {
					minecraft.player.playSound(net.minecraft.sounds.SoundEvents.BOOK_PAGE_TURN, 0.4f,
							1.0f + (float) (Math.random() * 0.2));
				}
			}
			return true;
		}

		// Double-clicking the species heading conjures one, for a creative operator.
		//
		// Checked before the naming line and before the drag, because both of those would swallow
		// the click. The gate here is only so the gesture does nothing surprising in survival — the
		// server re-checks it, and is the only thing that decides.
		if (button == 0 && !naming && titleY >= 0
				&& my >= titleY - 1 && my < titleY + LINE_H
				&& mx >= MARGIN && mx < MARGIN + titleW) {
			long now = net.minecraft.util.Util.getMillis();
			boolean doubled = titleLineage == lastClickLineage
					&& now - lastClickTime <= DOUBLE_CLICK_MS;
			lastClickTime = now;
			lastClickLineage = titleLineage;

			if (doubled && canConjure()) {
				ClientPlayNetworking.send(new SpawnSpeciesPayload(titleLineage));
				// A second double-click should be a second animal, not a repeat of this one.
				lastClickTime = 0;
				lastClickLineage = 0;
			}
			return true;
		}

		// The naming line, when the open plate is offering one.
		if (button == 0 && nameLineY >= 0 && !naming
				&& my >= nameLineY - 1 && my < nameLineY + LINE_H
				&& mx >= MARGIN && mx < PANEL_W / 2) {
			naming = true;
			nameBuffer = "";
			return true;
		}

		// The left button drags: the tree pans under it, the specimen turns on its stand. Whether
		// this was a drag or a click is only known on release, so both are started here.
		if (button == 0 && !naming && my >= TAB_H && my < PANEL_H && mx >= 0 && mx < PANEL_W) {
			dragging = true;
			dragFromX = mouseX;
			dragFromY = mouseY;
			dragDistance = 0;
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
		if (!dragging) return super.mouseDragged(event, deltaX, deltaY);
		dragDistance += Math.abs(deltaX) + Math.abs(deltaY);

		if (section == GuideChapters.LINEAGE_TAB) {
			treePanX += (float) deltaX;
			treePanY += (float) deltaY;
			clampPan();
		} else if (section == GuideChapters.REFERENCE_TAB) {
			plateSpin += (float) deltaX * 0.02f;
		}
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (dragging) {
			dragging = false;
			// A press that never moved is a click. On the tree that means "open this bloodline".
			if (dragDistance < 3 && section == GuideChapters.LINEAGE_TAB && hoveredNode != null) {
				openPlateFor(hoveredNode.entry.lineage());
				return true;
			}
			return true;
		}
		return super.mouseReleased(event);
	}

	/** Index of the tab under a panel-local point, or -1. */
	private int tabAt(double mx, double my) {
		if (my < 0 || my >= TAB_H) return -1;
		for (int i = 0; i < GuideChapters.SECTIONS.size(); i++) {
			int tx = i * (TAB_W + 2);
			if (mx >= tx && mx < tx + TAB_W) return i;
		}
		return -1;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		if (section == GuideChapters.LINEAGE_TAB) {
			// Zoom about the cursor, so the branch being looked at stays under it rather than
			// sliding away as the view scales.
			double mx = localX(mouseX), my = localY(mouseY);
			float previous = treeZoom;
			treeZoom = Math.max(0.35f, Math.min(2.5f, treeZoom * (vertical > 0 ? 1.15f : 1f / 1.15f)));
			float ratio = treeZoom / previous;
			treePanX = (float) (mx - MARGIN - (mx - MARGIN - treePanX) * ratio);
			// Anchored on the same origin the nodes are laid out from, or zooming walks the tree
			// out from under the clip rectangle.
			double originY = TAB_H + HEADING_H;
			treePanY = (float) (my - originY - (my - originY - treePanY) * ratio);
			// Zooming out shrinks the drawing and so tightens the leash: a view that was legally
			// panned at the old scale can be off the page at the new one.
			clampPan();
			return true;
		}
		turn(vertical > 0 ? -1 : 1);
		return true;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Arrow keys turn pages. Paging used to be a click anywhere on the page, which meant the left
	 * button could not be used for anything else — and the specimen plate wants it for the
	 * turntable.
	 */
	@Override
	public boolean charTyped(CharacterEvent event) {
		// A codepoint rather than a char in 26.2, so append its string form and let anything outside
		// the basic plane arrive whole rather than as half a surrogate pair.
		if (naming && nameBuffer.length() < GuideData.MAX_NAME && event.codepoint() >= ' ') {
			nameBuffer += event.codepointAsString();
			return true;
		}
		return super.charTyped(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int keyCode = event.key();
		// Typing swallows everything, or the arrow keys would turn the page out from under the
		// name being written and escape would shut the book rather than cancel the edit.
		if (naming) {
			switch (keyCode) {
				case org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE -> {
					naming = false;
					nameBuffer = "";
				}
				case org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE -> {
					if (!nameBuffer.isEmpty()) {
						nameBuffer = nameBuffer.substring(0, nameBuffer.length() - 1);
					}
				}
				case org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER ->
						commitName();
				default -> {
				}
			}
			return true;
		}
		if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT) {
			turn(-1);
			return true;
		}
		if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT) {
			turn(1);
			return true;
		}
		return super.keyPressed(event);
	}

	/**
	 * Sends the name to the server and closes the editor.
	 * <p>
	 * The plate is not updated here. The guide's contents are item components, so the change comes
	 * back on its own when the server syncs the stack — writing it locally would show the reader a
	 * name that might never have been accepted.
	 */
	private void commitName() {
		String given = nameBuffer.strip();
		naming = false;
		nameBuffer = "";
		if (given.isEmpty()) return;

		int plateIndex = page - (pages.size() - plates.size());
		if (plateIndex < 0 || plateIndex >= plates.size()) return;
		ClientPlayNetworking.send(
				new NameLineagePayload(plates.get(plateIndex).lineage(), given));
	}

	// The book used to wipe the GUI depth buffer after each specimen, because the previews were real
	// three-dimensional geometry drawn straight into the interface: they wrote depth in front of the
	// tab icons, and since they turn, whatever they occluded blinked in time with the rotation.
	// 26.2 removed the problem rather than the workaround — a preview is now rendered to its own
	// offscreen target and blitted back as a flat image, so it cannot write depth over the rest of
	// the book at all. See CreaturePreviewRenderer.

	/**
	 * Whether to offer the conjure gesture at all.
	 * <p>
	 * Cosmetic only — {@link SpawnSpeciesPayload} makes the same checks on arrival and is the one
	 * that decides. This exists so the hint is not shown to a player who cannot use it, and so a
	 * stray double-click in survival does nothing rather than sending a packet that will be
	 * refused.
	 */
	private boolean canConjure() {
		return minecraft != null && minecraft.player != null
				&& minecraft.player.isCreative()
				&& minecraft.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}

	private void openPlateFor(long lineage) {
		section = GuideChapters.REFERENCE_TAB;
		buildSection();
		for (int i = 0; i < plates.size(); i++) {
			if (plates.get(i).lineage() == lineage) {
				page = (pages.size() - plates.size()) + i;
				if (minecraft != null && minecraft.player != null) {
					minecraft.player.playSound(net.minecraft.sounds.SoundEvents.BOOK_PAGE_TURN, 0.4f,
							1.0f + (float) (Math.random() * 0.2));
				}
				return;
			}
		}
	}

	/**
	 * Turns a page, and runs off the end of a tab into the next one.
	 * <p>
	 * The book is meant to be read through. Stopping dead at the last page of a tab makes the
	 * reader go back to the mouse to carry on, which for a journal is the wrong gesture — the
	 * pages should keep turning.
	 */
	private void turn(int delta) {
		int next = page + delta;
		if (next >= 0 && next < pages.size()) {
			page = next;
			if (section < GuideChapters.REFERENCE_TAB) {
				if (minecraft != null && minecraft.player != null) {
					minecraft.player.playSound(net.minecraft.sounds.SoundEvents.BOOK_PAGE_TURN, 0.4f,
							1.0f + (float) (Math.random() * 0.2));
				}
			}
			return;
		}
		int target = section + delta;
		if (target < 0 || target >= GuideChapters.SECTIONS.size()) {
			// Both ends of the book: stay put rather than wrapping round, so the reader can feel
			// where the covers are.
			return;
		}
		section = target;
		buildSection();
		// Entering backwards lands on the last page, which is what turning back should do.
		if (delta < 0) page = Math.max(0, pages.size() - 1);
		
		if (minecraft != null && minecraft.player != null) {
			minecraft.player.playSound(net.minecraft.sounds.SoundEvents.BOOK_PAGE_TURN, 0.4f,
					1.0f + (float) (Math.random() * 0.2));
		}
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		// Deliberately not Screen.renderBackground: in this version that runs a blur pass over the
		// framebuffer behind the screen, and the result reads as though the whole interface — the
		// guide included — has been softened. A flat dim gives the same separation from the world
		// and leaves every pixel of the page exact.
		context.fill(0, 0, width, height, 0xC0101018);
		age += delta;
		// Cleared before anything is drawn and set again only by drawPlate, so a heading can never
		// stay clickable on a page that no longer shows one.
		titleY = -1;
		// Held still while dragged, turning again the moment it is let go — from the angle it was
		// left at, because the angle is state rather than a function of the clock.
		if (!(dragging && section == GuideChapters.REFERENCE_TAB)) {
			plateSpin += delta * 0.0065f;
		}

		double mx = localX(mouseX), my = localY(mouseY);

		// Panel-local coordinates from here down, so the layout reads in the same numbers the
		// background art uses. No scaling: Minecraft's glyphs are baked into an atlas, and putting
		// a scale on the matrix resamples them rather than enlarging them — which is blurring.
		context.pose().pushMatrix();
		context.pose().translate(left, top);

		drawTabs(context, mx, my);

		int bodyTop = TAB_H;
		int bodyH = PANEL_H - TAB_H;
		context.blit(RenderPipelines.GUI_TEXTURED, PAGE_TEXTURE, 0, bodyTop, 0, 0, PANEL_W, bodyH, 512, 256);

		GuideChapters.Section current = GuideChapters.SECTIONS.get(section);
		context.text(font, Component.literal(current.title()),
				MARGIN, bodyTop + 8, INK_TITLE, false);

		if (section == GuideChapters.LINEAGE_TAB) {
			hoveredNode = null;
			drawTree(context, bodyTop, mx, my);
		}
		if (section == GuideChapters.SELF_TAB) {
			drawSelf(context, bodyTop, mx, my);
		}
		if (!pages.isEmpty()) {
			int shown = Math.min(page, pages.size() - 1);
			List<Component> lines = pages.get(shown);
			int plateIndex = shown - (pages.size() - plates.size());
			if (!plates.isEmpty() && plateIndex >= 0 && plateIndex < plates.size()) {
				drawPlate(context, plates.get(plateIndex), bodyTop);
			}
			int y = bodyTop + 26;
			for (Component line : lines) {
				context.text(font, line, MARGIN, y, INK, false);
				y += LINE_H;
			}
			// The tree is one continuous view, so a page count there would be a permanent "1 / 1".
			if (section != GuideChapters.LINEAGE_TAB && pages.size() > 1) {
				Component footer = Component.literal((page + 1) + " / " + pages.size()
						+ "   ·   ← → or scroll");
				context.text(font, footer,
						PANEL_W - MARGIN - font.width(footer),
						PANEL_H - 14, INK_FAINT, false);
			}
		}
		context.pose().popMatrix();

		if (section == GuideChapters.SELF_TAB) {
			drawSelfFigure(context, mouseX, mouseY);
			selfTooltip(context, mouseX, mouseY);
		}

		// Tooltips are drawn outside the translated matrix, in real screen coordinates.
		int tab = tabAt(mx, my);
		if (tab >= 0) {
			context.setTooltipForNextFrame(font,
					Component.literal(GuideChapters.SECTIONS.get(tab).title()), mouseX, mouseY);
		} else if (hoveredNode != null && section == GuideChapters.LINEAGE_TAB) {
			GuideData.Entry entry = hoveredNode.entry;
			DecodeAccuracy accuracy = entry.accuracy();
			List<Component> lines = new ArrayList<>();
			lines.add(Component.literal(entry.displayName()).withStyle(ChatFormatting.AQUA));
			lines.add(Component.literal(entry.filed() + " filed · generation " + entry.generation())
					.withStyle(ChatFormatting.GRAY));
			lines.add(Component.literal(accuracy.label).withStyle(accuracy.colour));
			Genome genome = entry.genome();
			if (genome != null) {
				lines.add(Component.literal("Aggression " + accuracy.describeFraction(genome.raw(Gene.AGGRESSION))
						+ " · Size " + accuracy.describeFraction(genome.raw(Gene.SIZE)))
						.withStyle(ChatFormatting.DARK_GRAY));
			}
			if (hoveredNode.depth > 0) {
				lines.add(Component.literal(Phylogeny.describeDistance(hoveredNode.distanceToParent)
						+ " of the stock above").withStyle(ChatFormatting.DARK_GRAY));
			}
			lines.add(Component.literal("Click to open its entry")
					.withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
			context.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
		}
		super.extractRenderState(context, mouseX, mouseY, delta);
	}

	private void markSectionViewed(int sec, GuideData data) {
		if (sec < GuideChapters.REFERENCE_TAB) {
			GuideChapters.Section s = GuideChapters.SECTIONS.get(sec);
			for (GuideChapters.Chapter chapter : s.chapters()) {
				if (chapter.unlocked(data)) {
					viewedChapters.add(chapter.title());
				}
			}
		} else if (sec == GuideChapters.REFERENCE_TAB) {
			for (GuideData.Entry entry : data.entries()) {
				viewedLineages.add(entry.lineage());
			}
		} else if (sec == GuideChapters.LINEAGE_TAB) {
			viewedTreeEntryCount = data.entries().size();
		}
	}

	private static boolean isTabUnread(int sec) {
		GuideData data = dev.jsz.primordia.PrimordiaClient.getClientGuideData();
		if (sec < GuideChapters.REFERENCE_TAB) {
			GuideChapters.Section s = GuideChapters.SECTIONS.get(sec);
			for (GuideChapters.Chapter chapter : s.chapters()) {
				if (chapter.unlocked(data) && !viewedChapters.contains(chapter.title())) {
					return true;
				}
			}
			return false;
		} else if (sec == GuideChapters.REFERENCE_TAB) {
			for (GuideData.Entry entry : data.entries()) {
				if (!viewedLineages.contains(entry.lineage())) {
					return true;
				}
			}
			return false;
		} else if (sec == GuideChapters.LINEAGE_TAB) {
			int currentCount = data.entries().size();
			return currentCount >= 2 && currentCount > viewedTreeEntryCount;
		}
		return false;
	}

	/**
	 * Draws the tab strip.
	 * <p>
	 * The faces are cut from the page texture rather than filled with a colour picked to match it.
	 * A hand-picked colour is right exactly once and wrong the moment the page is re-weathered or
	 * restained; sampling means the tabs are made of the same paper by construction, and carry the
	 * same grain. Idle tabs are the same cut under a shadow, so they read as folded behind.
	 */
	private void drawTabs(GuiGraphicsExtractor context, double mx, double my) {
		for (int i = 0; i < GuideChapters.SECTIONS.size(); i++) {
			int tx = i * (TAB_W + 2);
			boolean active = i == section;
			boolean over = tabAt(mx, my) == i;
			boolean unread = isTabUnread(i);

			// The active tab is a pixel taller and runs into the page below, so it reads as joined
			// to it rather than as a button sitting on top.
			int height = active ? TAB_H + 1 : TAB_H - 2;
			int ty = active ? 0 : 2;

			if (unread) {
				float pulse = 0.6f + 0.4f * Mth.sin(age * 0.12f);
				int alpha = (int) (pulse * 225);
				int glowColor = (alpha << 24) | 0xFFD700;
				context.fill(tx - 2, ty - 2, tx + TAB_W + 2, ty + height + 1, glowColor);
			}

			context.fill(tx - 1, ty - 1, tx + TAB_W + 1, ty + height, FRAME);
			context.blit(RenderPipelines.GUI_TEXTURED, PAGE_TEXTURE, tx, ty, TAB_SAMPLE_U, TAB_SAMPLE_V,
					TAB_W, height, 512, 256);
			if (!active) context.fill(tx, ty, tx + TAB_W, ty + height, 0x44231B0E);
			if (over && !active) context.fill(tx, ty, tx + TAB_W, ty + height, 0x22FFFFFF);

			if (unread) {
				context.fill(tx + TAB_W - 5, ty + 2, tx + TAB_W - 1, ty + 6, 0xFFFFF066);
				context.fill(tx + TAB_W - 4, ty + 3, tx + TAB_W - 2, ty + 5, 0xFFFFD700);
			}

			context.item(tabIcons.get(i), tx + (TAB_W - 16) / 2, ty + (height - 16) / 2 - 1);
		}
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * A no-op on purpose. The engine runs a blur pass over the framebuffer for any open screen, and
	 * because the guide is drawn into that same frame the softening lands on the page as well as on
	 * the world behind it. 26.2 funnels that pass through this one method, where the two it replaced
	 * — {@code blur} and {@code applyBlur} — used to share the job, so this is now the only place it
	 * has to be refused.
	 */
	@Override
	protected void extractBlurredBackground(GuiGraphicsExtractor context) {
	}

	@Override
	public boolean isPauseScreen() {
		// Reading should not stop the world; the lab may be mid-run.
		return false;
	}
}
