package dev.jsz.primordia.lab;

import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.genome.Mutation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reconstructs how the species in a field guide are related.
 * <p>
 * Nothing in the world records that one lineage forked from another. {@link Mutation} assigns a
 * diverging population a fresh random id and keeps no pointer home, and adding one would mean
 * changing the genome's wire format under every creature already alive in the save.
 * <p>
 * So the tree is <b>inferred</b>, from genetic distance between the representative specimens the
 * guide holds — which is what phylogenetics actually does. Two lineages that measure close together
 * are close relatives; the shortest set of links that connects everything is the most parsimonious
 * account of how they diverged. That is a minimum spanning tree over the distance matrix, rooted at
 * the best-studied species because that is the one the reader knows.
 * <p>
 * It is a reconstruction and the guide says so. It can be wrong in the way any distance tree can:
 * two lineages that converged on similar genomes independently will be drawn as siblings.
 */
public final class Phylogeny {

	private Phylogeny() {
	}

	/** One species placed in the tree. */
	public record Node(GuideData.Entry entry, int depth, float distanceToParent, boolean lastChild) {
	}

	/**
	 * A node with its children and a column assigned, ready to draw.
	 * <p>
	 * {@code column} is in node-widths from the left, not pixels: the screen decides how far apart
	 * that is. Leaves take the next free column and a parent sits centred over its children, which
	 * is the smallest layout that still reads as descent rather than as a list.
	 */
	public static final class TreeNode {
		public final GuideData.Entry entry;
		public final int depth;
		public final float distanceToParent;
		public final List<TreeNode> children = new ArrayList<>();
		/** Horizontal position in node-widths. Fractional for a parent centred over its children. */
		public float column;

		TreeNode(GuideData.Entry entry, int depth, float distanceToParent) {
			this.entry = entry;
			this.depth = depth;
			this.distanceToParent = distanceToParent;
		}
	}

	/** The tree as roots with children, laid out left to right. */
	public static List<TreeNode> layout(List<GuideData.Entry> entries) {
		List<Node> flat = build(entries);
		if (flat.isEmpty()) return List.of();

		// build() emits depth-first, so a node's parent is the closest preceding node one level up.
		List<TreeNode> roots = new ArrayList<>();
		TreeNode[] openAtDepth = new TreeNode[64];
		for (Node node : flat) {
			TreeNode made = new TreeNode(node.entry(), node.depth(), node.distanceToParent());
			if (node.depth() == 0 || openAtDepth[node.depth() - 1] == null) {
				roots.add(made);
			} else {
				openAtDepth[node.depth() - 1].children.add(made);
			}
			if (node.depth() < openAtDepth.length) openAtDepth[node.depth()] = made;
		}

		float[] nextColumn = {0f};
		for (TreeNode root : roots) {
			assignColumns(root, nextColumn);
			// A gap between unrelated stocks, so two roots do not read as siblings.
			nextColumn[0] += 1f;
		}
		return roots;
	}

	private static void assignColumns(TreeNode node, float[] nextColumn) {
		if (node.children.isEmpty()) {
			node.column = nextColumn[0];
			nextColumn[0] += 1f;
			return;
		}
		for (TreeNode child : node.children) assignColumns(child, nextColumn);
		node.column = (node.children.get(0).column
				+ node.children.get(node.children.size() - 1).column) * 0.5f;
	}

	/** Every node in a laid-out tree, flattened, for drawing and hit testing. */
	public static void collect(List<TreeNode> roots, List<TreeNode> out) {
		for (TreeNode root : roots) {
			out.add(root);
			collect(root.children, out);
		}
	}

	/**
	 * Distance beyond which two lineages are not treated as related at all.
	 * <p>
	 * Above this they are drawn as separate roots rather than joined by a link the evidence does
	 * not support. {@link Mutation#SPECIATION_DISTANCE} is what the world uses to declare a fork,
	 * so a couple of forks' worth of drift is the point where common ancestry stops being the
	 * simplest explanation.
	 */
	public static final float UNRELATED = Mutation.SPECIATION_DISTANCE * 2.5f;

	/**
	 * Lays the guide's species out as a depth-first tree, ready to print one node per line.
	 * <p>
	 * Returns an empty list for an empty guide, and a flat list of roots when nothing is close
	 * enough to anything else to justify a link.
	 */
	public static List<Node> build(List<GuideData.Entry> entries) {
		List<GuideData.Entry> usable = new ArrayList<>();
		List<Genome> genomes = new ArrayList<>();
		for (GuideData.Entry entry : entries) {
			Genome genome = entry.genome();
			if (genome != null) {
				usable.add(entry);
				genomes.add(genome);
			}
		}
		if (usable.isEmpty()) return List.of();

		int n = usable.size();
		float[][] distance = new float[n][n];
		for (int i = 0; i < n; i++) {
			for (int j = i + 1; j < n; j++) {
				float d = Mutation.distance(genomes.get(i), genomes.get(j));
				distance[i][j] = d;
				distance[j][i] = d;
			}
		}

		// Prim's algorithm, grown from the best-studied species. Entries arrive sorted by how many
		// specimens are filed, so index 0 is the one the reader has the most confidence in — the
		// sensible thing to hang the rest off.
		boolean[] joined = new boolean[n];
		int[] parent = new int[n];
		float[] parentDistance = new float[n];
		List<List<Integer>> children = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			parent[i] = -1;
			children.add(new ArrayList<>());
		}

		joined[0] = true;
		int remaining = n - 1;
		while (remaining > 0) {
			int bestFrom = -1, bestTo = -1;
			float best = Float.MAX_VALUE;
			for (int i = 0; i < n; i++) {
				if (!joined[i]) continue;
				for (int j = 0; j < n; j++) {
					if (joined[j] || distance[i][j] >= best) continue;
					best = distance[i][j];
					bestFrom = i;
					bestTo = j;
				}
			}
			if (bestTo < 0 || best > UNRELATED) {
				// Nothing left is close enough to join. Whatever remains becomes its own root: an
				// unrelated clade drawn as a separate stock is honest, a forced link is not.
				for (int i = 0; i < n; i++) {
					if (!joined[i]) {
						joined[i] = true;
						remaining--;
					}
				}
				break;
			}
			joined[bestTo] = true;
			parent[bestTo] = bestFrom;
			parentDistance[bestTo] = best;
			children.get(bestFrom).add(bestTo);
			remaining--;
		}

		// Closest relatives first, so a clade reads as a clade instead of in filing order.
		for (List<Integer> kids : children) {
			kids.sort(Comparator.comparingDouble(k -> parentDistance[k]));
		}

		List<Node> out = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			if (parent[i] < 0) walk(i, 0, parent, parentDistance, children, usable, out, true);
		}
		return out;
	}

	private static void walk(int index, int depth, int[] parent, float[] parentDistance,
	                         List<List<Integer>> children, List<GuideData.Entry> entries,
	                         List<Node> out, boolean lastChild) {
		out.add(new Node(entries.get(index), depth,
				parent[index] < 0 ? 0f : parentDistance[index], lastChild));
		List<Integer> kids = children.get(index);
		for (int k = 0; k < kids.size(); k++) {
			walk(kids.get(k), depth + 1, parent, parentDistance, children, entries, out,
					k == kids.size() - 1);
		}
	}

	/** How divergent two species are, as a word. Keeps the tree readable without a scale bar. */
	public static String describeDistance(float distance) {
		if (distance <= 0f) return "root stock";
		if (distance < Mutation.SPECIATION_DISTANCE * 0.5f) return "near-identical";
		if (distance < Mutation.SPECIATION_DISTANCE) return "close kin";
		if (distance < Mutation.SPECIATION_DISTANCE * 1.6f) return "diverged";
		return "distant";
	}
}
