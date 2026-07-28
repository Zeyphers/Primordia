package dev.jsz.primordia.client.screen;

import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.lab.DecodeAccuracy;
import dev.jsz.primordia.lab.GuideChapters;
import dev.jsz.primordia.lab.GuideData;
import dev.jsz.primordia.lab.NameLineagePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import dev.jsz.primordia.lab.Phylogeny;
import dev.jsz.primordia.entity.TamingPreference;
import dev.jsz.primordia.client.render.CreaturePreview;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import dev.jsz.primordia.Primordia;
import net.minecraft.util.Identifier;

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

	private final ItemStack guide;

	/** Pages of the currently selected tab. Rebuilt when the tab changes. */
	private final List<List<Text>> pages = new ArrayList<>();
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
		super(Text.literal("Primordia Field Guide"));
		this.guide = guide;
	}

	@Override
	protected void init() {
		left = (width - PANEL_W) / 2;
		top = (height - PANEL_H) / 2;

		tabIcons.clear();
		for (GuideChapters.Section s : GuideChapters.SECTIONS) {
			// A tab whose icon names an item that does not exist still needs to draw something, or
			// one bad id would leave an invisible, unclickable tab.
			var item = Registries.ITEM.get(Identifier.of(s.iconItemId()));
			tabIcons.add(new ItemStack(item == Items.AIR ? Items.PAPER : item));
		}
		buildSection();
	}

	private void buildSection() {
		pages.clear();
		page = 0;
		GuideChapters.Section current = GuideChapters.SECTIONS.get(section);

		GuideData data = GuideData.get(guide);
		for (GuideChapters.Chapter chapter : current.chapters()) {
			List<Text> lines = new ArrayList<>();
			if (!chapter.unlocked(data)) {
				// The title stays visible. Knowing an entry exists and cannot yet be read is the
				// nudge; hiding it entirely would just look like a shorter book.
				lines.add(Text.literal(chapter.title()).formatted(Formatting.BOLD, Formatting.OBFUSCATED));
				lines.add(Text.empty());
				for (String w : wrap(chapter.unlock().hint())) {
					lines.add(Text.literal(w).formatted(Formatting.DARK_GRAY, Formatting.ITALIC));
				}
				flush(lines);
				continue;
			}
			lines.add(Text.literal(chapter.title()).formatted(Formatting.BOLD));
			lines.add(Text.empty());
			for (String paragraph : chapter.paragraphs()) {
				if (paragraph.isEmpty()) {
					lines.add(Text.empty());
					continue;
				}
				for (String wrapped : wrap(paragraph)) lines.add(Text.literal(wrapped));
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
	}

	/**
	 * The Reference tab: an index page, then one plate per species.
	 * <p>
	 * The plates are not built as lines here — they are laid out at draw time, because half of each
	 * is a live render of the animal and the other half is a stat block sized to fill the rest.
	 */
	private void buildReference() {
		GuideData data = GuideData.get(guide);
		List<GuideData.Entry> entries = data.entries();

		List<Text> index = new ArrayList<>();
		index.add(Text.literal("Species on file").formatted(Formatting.BOLD));
		index.add(Text.empty());
		if (entries.isEmpty()) {
			index.add(Text.literal("Nothing filed yet.").formatted(Formatting.DARK_GRAY));
			index.add(Text.empty());
			for (String w : wrap("Decode a sequence in a Gene Lab while carrying this guide. "
					+ "The report will file itself and the paper will be consumed.")) {
				index.add(Text.literal(w).formatted(Formatting.DARK_GRAY));
			}
			flush(index);
			return;
		}
		index.add(Text.literal(data.speciesCount() + " species · "
				+ data.specimensFiled() + " specimens filed").formatted(Formatting.DARK_GRAY));
		index.add(Text.empty());

		// Two columns. One per line ran off the bottom of the page as soon as a dozen bloodlines
		// were on file, and left the right half of a wide page completely empty while doing it.
		int perColumn = (linesPerPage() - 4);
		List<GuideData.Entry> listed = entries;
		for (int row = 0; row < Math.min(perColumn, (listed.size() + 1) / 2); row++) {
			int rightRow = row + perColumn;
			Text line = indexEntry(listed.get(row));
			if (rightRow < listed.size()) {
				line = Text.literal("").append(line).append(pad(line))
						.append(indexEntry(listed.get(rightRow)));
			}
			index.add(line);
		}
		if (listed.size() > perColumn * 2) {
			index.add(Text.empty());
			index.add(Text.literal("…and " + (listed.size() - perColumn * 2) + " more")
					.formatted(Formatting.DARK_GRAY));
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
	private static Text indexEntry(GuideData.Entry entry) {
		DecodeAccuracy accuracy = entry.accuracy();
		return Text.literal(entry.displayName()).formatted(Formatting.DARK_AQUA)
				.append(Text.literal(" x" + entry.filed()).formatted(Formatting.DARK_GRAY))
				.append(Text.literal(" " + accuracy.label.toLowerCase()).formatted(accuracy.colour));
	}

	/** Spaces enough to carry the second column to the middle of the page. */
	private Text pad(Text left) {
		int target = (PANEL_W - MARGIN * 2) / 2;
		StringBuilder spaces = new StringBuilder();
		int width = textRenderer.getWidth(left);
		while (width + textRenderer.getWidth(spaces.toString()) < target) spaces.append(' ');
		return Text.literal(spaces.toString());
	}

	/** The Bloodlines tab: a drawn tree, not a list. */
	private void buildLineages() {
		treeRoots = Phylogeny.layout(GuideData.get(guide).entries());
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
		float widestColumn = 0f;
		int deepest = 0;
		for (Phylogeny.TreeNode node : treeNodes) {
			widestColumn = Math.max(widestColumn, node.column);
			deepest = Math.max(deepest, node.depth);
		}
		float treeW = widestColumn * (NODE + NODE_GAP_X) * treeZoom + NODE * treeZoom;
		float treeH = deepest * ROW_H * treeZoom + NODE * treeZoom;

		float viewW = PANEL_W - MARGIN * 2;
		float viewH = PANEL_H - TAB_H - 8;
		treePanX = (viewW - treeW) / 2f;
		// The 16 undoes the fixed offset nodeY adds, so the top row lands where this asks it to.
		treePanY = (viewH - treeH) / 2f - 16f;
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
		return Math.round(node.depth * ROW_H * treeZoom + treePanY) + bodyTop + 20;
	}

	/**
	 * Draws the family tree: a box per bloodline with the animal inside it, joined to its parent.
	 * <p>
	 * Elbowed connectors rather than straight diagonals, because a diagonal between two boxes reads
	 * as an arbitrary link while a vertical drop into a horizontal run reads as descent — the same
	 * convention a pedigree chart uses, for the same reason.
	 */
	private void drawTree(DrawContext context, int bodyTop, double mx, double my) {
		if (treeNodes.isEmpty()) {
			context.drawText(textRenderer, Text.literal("Nothing filed yet."),
					MARGIN, bodyTop + 30, INK_FAINT, false);
			int y = bodyTop + 44;
			for (String w : wrap("Two or more bloodlines are needed before anything can be said "
					+ "about how they are related.")) {
				context.drawText(textRenderer, Text.literal(w), MARGIN, y, INK_FAINT, false);
				y += LINE_H;
			}
			return;
		}

		// Clip to the page. Without this a panned tree is drawn straight over the tab strip and
		// off the edge of the book, which is what it did before it became a viewport.
		// Screen coordinates, not panel-local. enableScissor pushes the rectangle straight onto the
		// scissor stack without putting it through the current matrix — verified in the bytecode
		// after guessing wrong in both directions — so the panel offset has to be added by hand.
		context.enableScissor(left + 2, top + bodyTop + 4, left + PANEL_W - 2, top + PANEL_H - 4);

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
			if (x + size < 0 || x > PANEL_W || y + size < bodyTop || y > PANEL_H) continue;

			boolean over = mx >= x && mx < x + size && my >= y && my < y + size;
			if (over) hovered = node;

			context.fill(x - 1, y - 1, x + size + 1, y + size + 1, over ? INK_TITLE : FRAME);
			context.fill(x, y, x + size, y + size, 0xFFDDD5BC);

			Genome genome = node.entry.genome();
			boolean drawn = genome != null && CreaturePreview.render(context, genome,
					x + size / 2, y + size / 2 + size / 10, size - 4, age * 0.0065f);
			if (!drawn) {
				context.drawText(textRenderer, Text.literal("?"),
						x + size / 2 - 2, y + size / 2 - 4, INK_FAINT, false);
			}
			// Counts are drawn at their true size whatever the zoom, so they stay legible when the
			// view is pulled back to see the whole tree — and are dropped entirely once the boxes
			// are too small to sit under without colliding.
			if (size >= 22) {
				Text count = Text.literal("x" + node.entry.filed());
				context.drawText(textRenderer, count,
						x + (size - textRenderer.getWidth(count)) / 2, y + size + 2,
						INK_FAINT, false);
			}
		}
		context.disableScissor();
		hoveredNode = hovered;
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
	private void drawPlate(DrawContext context, GuideData.Entry entry, int bodyTop) {
		int split = MARGIN + (PANEL_W - MARGIN * 2) * 5 / 9;
		Genome genome = entry.genome();
		DecodeAccuracy accuracy = entry.accuracy();

		int y = bodyTop + 26;
		context.drawText(textRenderer, Text.literal(entry.displayName()).formatted(Formatting.BOLD),
				MARGIN, y, INK_TITLE, false);
		y += LINE_H + 2;
		// A named species keeps its marking underneath, because the marking is what the machines
		// print and what the tree is drawn from — the name is the reader's, not the world's.
		String subtitle = (entry.named() ? entry.label() + " · " : "")
				+ entry.filed() + " filed · generation " + entry.generation();
		context.drawText(textRenderer, Text.literal(subtitle), MARGIN, y, INK_FAINT, false);
		y += LINE_H;
		context.drawText(textRenderer, Text.literal(accuracy.label).formatted(accuracy.colour),
				MARGIN, y, INK_FAINT, false);
		y += LINE_H + 4;

		nameLineY = -1;
		if (entry.nameable() && !entry.named()) {
			nameLineY = y;
			if (naming) {
				String shown = nameBuffer + ((age * 0.06f) % 2 < 1 ? "_" : "");
				context.drawText(textRenderer, Text.literal("Name: ").formatted(Formatting.DARK_GRAY)
						.append(Text.literal(shown).formatted(Formatting.BLACK)),
						MARGIN, y, INK, false);
			} else {
				context.drawText(textRenderer,
						Text.literal("⊕ name this species").formatted(Formatting.ITALIC),
						MARGIN, y, 0xFF2C6E5A, false);
			}
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
			context.drawText(textRenderer, Text.literal("Takes"), MARGIN, y, INK_FAINT, false);
			if (accuracy.atLeast(DecodeAccuracy.PARTIAL)) {
				ItemStack bait = new ItemStack(TamingPreference.favouriteFood(genome));
				context.drawItem(bait, valueX, y - 5);
				context.drawText(textRenderer, bait.getName(), valueX + 20, y, INK, false);
			} else {
				context.drawText(textRenderer, Text.literal("not yet known"), valueX, y,
						INK_FAINT, false);
			}
			y += LINE_H + 4;

			int needed = accuracy.decodesUntilNextLevel(entry.filed());
			for (String w : wrapTo(needed > 0
					? "Bring back " + needed + " more of this kind to read it clearly."
					: "Nothing further to learn from this one.", split - MARGIN - 4)) {
				context.drawText(textRenderer, Text.literal(w),
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
			Text waiting = Text.literal("sketching…");
			context.drawText(textRenderer, waiting,
					plateX + (plateW - textRenderer.getWidth(waiting)) / 2,
					plateY + plateH / 2 - 4, INK_FAINT, false);
		}
	}

	private int statLine(DrawContext context, String name, String value, int y, int valueX) {
		context.drawText(textRenderer, Text.literal(name), MARGIN, y, INK_FAINT, false);
		context.drawText(textRenderer, Text.literal(value), valueX, y, INK, false);
		return y + LINE_H;
	}

	/** Wrap to an arbitrary width, for the plate's narrower left column. */
	private List<String> wrapTo(String paragraph, int width) {
		List<String> out = new ArrayList<>();
		for (var part : textRenderer.getTextHandler()
				.wrapLines(paragraph, width, net.minecraft.text.Style.EMPTY)) {
			out.add(part.getString());
		}
		return out;
	}

	private static Text trait(String name, String value) {
		return Text.literal(name + ": ").formatted(Formatting.DARK_GRAY)
				.append(Text.literal(value).formatted(Formatting.BLACK));
	}

	private List<String> wrap(String paragraph) {
		List<String> out = new ArrayList<>();
		for (var part : textRenderer.getTextHandler()
				.wrapLines(paragraph, PANEL_W - MARGIN * 2 - 8, net.minecraft.text.Style.EMPTY)) {
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

	private void flush(List<Text> lines) {
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
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		double mx = localX(mouseX), my = localY(mouseY);

		int tab = tabAt(mx, my);
		if (tab >= 0) {
			if (section != tab) {
				section = tab;
				buildSection();
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
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
		if (!dragging) return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
		dragDistance += Math.abs(deltaX) + Math.abs(deltaY);

		if (section == GuideChapters.LINEAGE_TAB) {
			treePanX += (float) deltaX;
			treePanY += (float) deltaY;
		} else if (section == GuideChapters.REFERENCE_TAB) {
			plateSpin += (float) deltaX * 0.02f;
		}
		return true;
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (dragging) {
			dragging = false;
			// A press that never moved is a click. On the tree that means "open this bloodline".
			if (dragDistance < 3 && section == GuideChapters.LINEAGE_TAB && hoveredNode != null) {
				openPlateFor(hoveredNode.entry.lineage());
				return true;
			}
			return true;
		}
		return super.mouseReleased(mouseX, mouseY, button);
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
			treePanY = (float) (my - TAB_H - 20 - (my - TAB_H - 20 - treePanY) * ratio);
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
	public boolean charTyped(char chr, int modifiers) {
		if (naming && nameBuffer.length() < GuideData.MAX_NAME && chr >= ' ') {
			nameBuffer += chr;
			return true;
		}
		return super.charTyped(chr, modifiers);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
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
		return super.keyPressed(keyCode, scanCode, modifiers);
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

	/** Switches to the Specimens tab and pages straight to one bloodline's plate. */
	private void openPlateFor(long lineage) {
		section = GuideChapters.REFERENCE_TAB;
		buildSection();
		for (int i = 0; i < plates.size(); i++) {
			if (plates.get(i).lineage() == lineage) {
				page = (pages.size() - plates.size()) + i;
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
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		// Deliberately not Screen.renderBackground: in this version that runs a blur pass over the
		// framebuffer behind the screen, and the result reads as though the whole interface — the
		// guide included — has been softened. A flat dim gives the same separation from the world
		// and leaves every pixel of the page exact.
		context.fill(0, 0, width, height, 0xC0101018);
		age += delta;
		// Held still while dragged, turning again the moment it is let go — from the angle it was
		// left at, because the angle is state rather than a function of the clock.
		if (!(dragging && section == GuideChapters.REFERENCE_TAB)) {
			plateSpin += delta * 0.0065f;
		}

		double mx = localX(mouseX), my = localY(mouseY);

		// Panel-local coordinates from here down, so the layout reads in the same numbers the
		// background art uses. No scaling: Minecraft's glyphs are baked into an atlas, and putting
		// a scale on the matrix resamples them rather than enlarging them — which is blurring.
		context.getMatrices().push();
		context.getMatrices().translate(left, top, 0);

		drawTabs(context, mx, my);

		int bodyTop = TAB_H;
		int bodyH = PANEL_H - TAB_H;
		context.drawTexture(PAGE_TEXTURE, 0, bodyTop, 0, 0, PANEL_W, bodyH, 512, 256);

		GuideChapters.Section current = GuideChapters.SECTIONS.get(section);
		context.drawText(textRenderer, Text.literal(current.title()),
				MARGIN, bodyTop + 8, INK_TITLE, false);

		if (section == GuideChapters.LINEAGE_TAB) {
			hoveredNode = null;
			drawTree(context, bodyTop, mx, my);
		}
		if (!pages.isEmpty()) {
			int shown = Math.min(page, pages.size() - 1);
			List<Text> lines = pages.get(shown);
			int plateIndex = shown - (pages.size() - plates.size());
			if (!plates.isEmpty() && plateIndex >= 0 && plateIndex < plates.size()) {
				drawPlate(context, plates.get(plateIndex), bodyTop);
			}
			int y = bodyTop + 26;
			for (Text line : lines) {
				context.drawText(textRenderer, line, MARGIN, y, INK, false);
				y += LINE_H;
			}
			// The tree is one continuous view, so a page count there would be a permanent "1 / 1".
			if (section != GuideChapters.LINEAGE_TAB && pages.size() > 1) {
				Text footer = Text.literal((page + 1) + " / " + pages.size()
						+ "   ·   ← → or scroll");
				context.drawText(textRenderer, footer,
						PANEL_W - MARGIN - textRenderer.getWidth(footer),
						PANEL_H - 14, INK_FAINT, false);
			}
		}
		context.getMatrices().pop();

		// Tooltips are drawn outside the translated matrix, in real screen coordinates.
		int tab = tabAt(mx, my);
		if (tab >= 0) {
			context.drawTooltip(textRenderer,
					Text.literal(GuideChapters.SECTIONS.get(tab).title()), mouseX, mouseY);
		} else if (hoveredNode != null && section == GuideChapters.LINEAGE_TAB) {
			GuideData.Entry entry = hoveredNode.entry;
			DecodeAccuracy accuracy = entry.accuracy();
			List<Text> lines = new ArrayList<>();
			lines.add(Text.literal(entry.displayName()).formatted(Formatting.AQUA));
			lines.add(Text.literal(entry.filed() + " filed · generation " + entry.generation())
					.formatted(Formatting.GRAY));
			lines.add(Text.literal(accuracy.label).formatted(accuracy.colour));
			Genome genome = entry.genome();
			if (genome != null) {
				lines.add(Text.literal("Aggression " + accuracy.describeFraction(genome.raw(Gene.AGGRESSION))
						+ " · Size " + accuracy.describeFraction(genome.raw(Gene.SIZE)))
						.formatted(Formatting.DARK_GRAY));
			}
			if (hoveredNode.depth > 0) {
				lines.add(Text.literal(Phylogeny.describeDistance(hoveredNode.distanceToParent)
						+ " of the stock above").formatted(Formatting.DARK_GRAY));
			}
			lines.add(Text.literal("Click to open its entry")
					.formatted(Formatting.DARK_GRAY, Formatting.ITALIC));
			context.drawTooltip(textRenderer, lines, mouseX, mouseY);
		}
		super.render(context, mouseX, mouseY, delta);
	}

	/**
	 * Draws the tab strip.
	 * <p>
	 * The faces are cut from the page texture rather than filled with a colour picked to match it.
	 * A hand-picked colour is right exactly once and wrong the moment the page is re-weathered or
	 * restained; sampling means the tabs are made of the same paper by construction, and carry the
	 * same grain. Idle tabs are the same cut under a shadow, so they read as folded behind.
	 */
	private void drawTabs(DrawContext context, double mx, double my) {
		for (int i = 0; i < GuideChapters.SECTIONS.size(); i++) {
			int tx = i * (TAB_W + 2);
			boolean active = i == section;
			boolean over = tabAt(mx, my) == i;

			// The active tab is a pixel taller and runs into the page below, so it reads as joined
			// to it rather than as a button sitting on top.
			int height = active ? TAB_H + 1 : TAB_H - 2;
			int ty = active ? 0 : 2;

			context.fill(tx - 1, ty - 1, tx + TAB_W + 1, ty + height, FRAME);
			context.drawTexture(PAGE_TEXTURE, tx, ty, TAB_SAMPLE_U, TAB_SAMPLE_V,
					TAB_W, height, 512, 256);
			if (!active) context.fill(tx, ty, tx + TAB_W, ty + height, 0x44231B0E);
			if (over && !active) context.fill(tx, ty, tx + TAB_W, ty + height, 0x22FFFFFF);

			context.drawItem(tabIcons.get(i), tx + (TAB_W - 16) / 2, ty + (height - 16) / 2 - 1);
		}
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * No-ops on purpose. The engine runs a blur pass over the framebuffer for any open screen, and
	 * because the guide is drawn into that same frame the softening lands on the page as well as on
	 * the world behind it. These two methods are where that pass goes through, so this is where it
	 * is refused.
	 */
	@Override
	public void blur() {
	}

	@Override
	protected void applyBlur(float delta) {
	}

	@Override
	public boolean shouldPause() {
		// Reading should not stop the world; the lab may be mid-run.
		return false;
	}
}
