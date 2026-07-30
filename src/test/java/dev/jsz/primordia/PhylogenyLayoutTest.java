package dev.jsz.primordia;

import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.lab.GuideData;
import dev.jsz.primordia.lab.Phylogeny;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the family tree's shape.
 * <p>
 * The tree is drawn as boxes joined by connectors, so a layout fault is not a wrong number — it is
 * two bloodlines stacked on the same square, or a branch drawn off the side of the page. Neither
 * looks like a bug at a glance; both look like the tree is simply wrong about the animals.
 */
class PhylogenyLayoutTest {

	/** Files {@code count} specimens of a stock drifted {@code drift} from a founder. */
	private static void fileStock(CompoundTag stack, Genome founder, long lineage,
	                              float drift, int count, Random random) {
		float[] values = founder.copyValues();
		for (int i = 0; i < values.length; i++) {
			values[i] = Math.min(1f, Math.max(0f, values[i] + drift));
		}
		for (int i = 0; i < count; i++) {
			GuideData data = GuideData.fromNbt(stack);
			data.file(new Genome(values, random.nextLong(), lineage, i));
			data.writeInto(stack);
		}
	}

	private static List<Phylogeny.TreeNode> flatten(List<Phylogeny.TreeNode> roots) {
		List<Phylogeny.TreeNode> out = new ArrayList<>();
		Phylogeny.collect(roots, out);
		return out;
	}

	@Test
	void everyFiledBloodlineAppearsExactlyOnceInTheTree() {
		Random random = new Random(2024);
		CompoundTag stack = new CompoundTag();
		Genome founder = Genome.random(random);
		for (int i = 0; i < 5; i++) {
			fileStock(stack, founder, founder.lineage() + i, 0.03f * i, 2, random);
		}

		GuideData data = GuideData.fromNbt(stack);
		List<Phylogeny.TreeNode> nodes = flatten(Phylogeny.layout(data.entries()));

		assertEquals(data.speciesCount(), nodes.size(),
				"the tree does not hold exactly the species that were filed");
		List<Long> seen = nodes.stream().map(n -> n.entry.lineage()).toList();
		assertEquals(seen.size(), seen.stream().distinct().count(),
				"a bloodline appears twice in the tree");
	}

	@Test
	void noTwoNodesLandOnTheSameSquare() {
		Random random = new Random(77);
		CompoundTag stack = new CompoundTag();
		Genome founder = Genome.random(random);
		for (int i = 0; i < 8; i++) {
			fileStock(stack, founder, founder.lineage() + i, 0.02f * i, 1, random);
		}

		List<Phylogeny.TreeNode> nodes = flatten(
				Phylogeny.layout(GuideData.fromNbt(stack).entries()));
		for (int i = 0; i < nodes.size(); i++) {
			for (int j = i + 1; j < nodes.size(); j++) {
				Phylogeny.TreeNode a = nodes.get(i), b = nodes.get(j);
				if (a.depth != b.depth) continue;
				assertTrue(Math.abs(a.column - b.column) > 0.01f,
						"two bloodlines share a square at depth " + a.depth
								+ ": " + a.entry.label() + " and " + b.entry.label());
			}
		}
	}

	@Test
	void aParentSitsOverItsChildren() {
		Random random = new Random(31337);
		CompoundTag stack = new CompoundTag();
		Genome founder = Genome.random(random);
		for (int i = 0; i < 6; i++) {
			fileStock(stack, founder, founder.lineage() + i, 0.025f * i, 3, random);
		}

		List<Phylogeny.TreeNode> nodes = flatten(
				Phylogeny.layout(GuideData.fromNbt(stack).entries()));
		for (Phylogeny.TreeNode node : nodes) {
			if (node.children.isEmpty()) continue;
			float first = node.children.get(0).column;
			float last = node.children.get(node.children.size() - 1).column;
			// Centred between its outermost children, or the connectors fan the wrong way and the
			// chart stops reading as descent.
			assertEquals((first + last) * 0.5f, node.column, 0.01f,
					node.entry.label() + " is not centred over its children");
			for (Phylogeny.TreeNode child : node.children) {
				assertEquals(node.depth + 1, child.depth,
						"a child is not one row below its parent");
			}
		}
	}

	@Test
	void columnsStartAtZeroAndStayCompact() {
		Random random = new Random(9);
		CompoundTag stack = new CompoundTag();
		Genome founder = Genome.random(random);
		for (int i = 0; i < 7; i++) {
			fileStock(stack, founder, founder.lineage() + i, 0.02f * i, 1, random);
		}

		List<Phylogeny.TreeNode> nodes = flatten(
				Phylogeny.layout(GuideData.fromNbt(stack).entries()));
		float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
		for (Phylogeny.TreeNode node : nodes) {
			min = Math.min(min, node.column);
			max = Math.max(max, node.column);
		}
		assertEquals(0f, min, 0.01f, "the tree does not begin at the left edge");
		// Leaves take one column each plus a gap between unrelated stocks; anything much wider
		// than the node count means the layout is leaving holes and will pan off the page.
		assertTrue(max <= nodes.size() + 2,
				"the tree is " + max + " columns wide for " + nodes.size() + " bloodlines");
	}

	@Test
	void anEmptyGuideProducesNoTreeRatherThanThrowing() {
		assertTrue(Phylogeny.layout(GuideData.fromNbt(new CompoundTag()).entries()).isEmpty());
	}
}
