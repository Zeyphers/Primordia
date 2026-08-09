package dev.jsz.primordia.mesh;

import dev.jsz.primordia.body.BoneDef;
import dev.jsz.primordia.sdf.BodySdf;
import dev.jsz.primordia.util.MathX;
import org.joml.Vector3f;

/**
 * Naive Surface Nets: extracts a quad mesh from a {@link BodySdf}.
 * <p>
 * Chosen over marching cubes for three reasons that all matter here:
 * <ul>
 *   <li>It emits <b>quads</b>, which is exactly what Minecraft's entity render layers consume.</li>
 *   <li>It needs no 256-entry triangulation table — the whole algorithm is the two loops below.</li>
 *   <li>Its dual vertices sit at the average of the edge crossings, so a coarse grid still gives a
 *       smooth organic surface. Marching cubes at the same resolution looks visibly faceted.</li>
 * </ul>
 * The pass structure is: sample the field on a lattice, place one vertex per sign-changing cell,
 * then walk every sign-changing lattice edge and stitch the four cells around it into a quad.
 */
public final class SurfaceNets {
	/** No limb seen yet; distinct from {@link BoneDef#AXIAL}, which is a real group. */
	private static final int NO_GROUP = -1;

	/**
	 * How far apart two limbs' crossings must sit, in cell widths squared, before the cell is split
	 * into two surfaces.
	 * <p>
	 * Half a cell. Below that the two sets of crossings overlap and the cell is looking at one
	 * continuous piece of body rather than at two limbs with air between them — splitting there
	 * opens a seam and separates nothing. Above it there really are two surfaces and the seam is
	 * the point.
	 */
	private static final float SPLIT_SEPARATION_SQ = 0.25f;
	/** Corner offsets, indexed 0..7 as (x, y, z) bit fields: bit0 = x, bit1 = y, bit2 = z. */
	private static final int[][] CORNER = {
			{0, 0, 0}, {1, 0, 0}, {0, 1, 0}, {1, 1, 0},
			{0, 0, 1}, {1, 0, 1}, {0, 1, 1}, {1, 1, 1}
	};
	/** The 12 cube edges as pairs of corner indices. */
	private static final int[][] EDGE = {
			{0, 1}, {2, 3}, {4, 5}, {6, 7},
			{0, 2}, {1, 3}, {4, 6}, {5, 7},
			{0, 4}, {1, 5}, {2, 6}, {3, 7}
	};

	private SurfaceNets() {
	}

	/** Output of an extraction pass, before skinning and colouring are attached. */
	public record Result(float[] positions, float[] normals, int[] quads, int vertexCount) {
	}

	/**
	 * @param sdf        field to polygonise
	 * @param min        lower corner of the sampling volume
	 * @param max        upper corner of the sampling volume
	 * @param resolution number of cells along the longest axis; other axes are scaled to keep cells cubic
	 */
	public static Result extract(BodySdf sdf, Vector3f min, Vector3f max, int resolution) {
		return extract(sdf, min, max, resolution, 0f);
	}

	/**
	 * The cell this extraction will actually use in voxel mode.
	 * <p>
	 * Exposed because the caller has to reason about the same grid — {@code MeshBaker} sizes its
	 * limb inflation against it — and recomputing it there would be two copies of a rule that has to
	 * agree ({@code PITFALLS.md} §6).
	 *
	 * @param longest    longest span of the sampling volume
	 * @param resolution cells the LOD tier asked for along that span
	 * @param voxelSize  requested voxel edge, above zero
	 */
	public static float voxelCell(float longest, int resolution, float voxelSize) {
		// A voxel grid is a property of the world, not of the creature, so the cell size comes from
		// the request rather than from the body's proportions — two animals of different sizes
		// standing together are then built out of the same size of block.
		//
		// Never finer than the LOD asked for, though. A six-block creature at one-pixel voxels is a
		// 96-cell grid per axis, which is reasonable up close and ruinous for something the size of
		// a dot on the horizon.
		float cell = Math.max(voxelSize, longest / Math.max(2, resolution));
		// Held to a power-of-two multiple of the base voxel so the grids of successive LOD tiers
		// stay aligned with each other. Without that, a creature changes tier and every block in it
		// shifts by a fraction of a voxel, which reads as the whole body twitching.
		float steps = Math.max(1f, cell / voxelSize);
		return voxelSize * Integer.highestOneBit(Math.max(1, (int) steps));
	}

	/**
	 * @param voxelSize when above zero, the mesh is snapped to a world-aligned grid of this edge
	 *                  length and every vertex sits at the centre of its cell — see
	 *                  {@link #snapToVoxelGrid}. Zero gives the ordinary smooth extraction.
	 */
	public static Result extract(BodySdf sdf, Vector3f min, Vector3f max, int resolution,
	                             float voxelSize) {
		return extract(sdf, min, max, resolution, voxelSize, 0f);
	}

	/**
	 * @param limbGap narrowest daylight between two limbs on this body, or zero if unknown. When the
	 *                grid is already fine enough to see that gap, cells are never split — see
	 *                {@link #SPLIT_SEPARATION_SQ}.
	 */
	public static Result extract(BodySdf sdf, Vector3f min, Vector3f max, int resolution,
	                             float voxelSize, float limbGap) {
		float spanX = Math.max(max.x - min.x, 1e-3f);
		float spanY = Math.max(max.y - min.y, 1e-3f);
		float spanZ = Math.max(max.z - min.z, 1e-3f);
		float longest = Math.max(spanX, Math.max(spanY, spanZ));
		boolean voxels = voxelSize > 0f;
		float cell = voxels
				? voxelCell(longest, resolution, voxelSize)
				: longest / Math.max(2, resolution);

		// Whether the mesher has to separate limbs by hand, or whether the grid is already fine
		// enough to do it by sampling.
		//
		// Splitting a cell in two is what stops a pair of limbs meshing into one webbed sheet when
		// they share a cell — and it costs a seam, because the cell then hands one vertex to some
		// of its neighbours and a different one to the rest, so the faces around it never pair up.
		// Every split cell is a small hole, and that was the whole of the tearing around hips:
		// 27% of four-legged creatures had at least one, with not a single quad being dropped to
		// cause it.
		//
		// It is only ever needed when a cell is wider than the daylight between the limbs, because
		// that is the case where one averaged vertex would land in the gap and bridge them. Once
		// the sampling is fine enough to put a sample in that gap — which MeshBaker now arranges
		// for, by raising the resolution against the same measurement — the field's own hard union
		// across limb groups keeps them apart and the split has nothing left to do but tear.
		// Never in voxel mode, where it is incapable of doing anything but harm. Every vertex there
		// is pinned to the centre of its own cell — that pinning is what makes the surface blocky —
		// so a split cell places both of its vertices at the same point and separates precisely
		// nothing, while still handing different vertices to different neighbours and tearing the
		// surface open. It was costing 26% of creatures a hole in exchange for no separation at all.
		boolean splitNeeded = !voxels && (limbGap <= 0f || cell > limbGap * 0.9f);

		int nx = Math.max(2, (int) Math.ceil(spanX / cell)) + 1;
		int ny = Math.max(2, (int) Math.ceil(spanY / cell)) + 1;
		int nz = Math.max(2, (int) Math.ceil(spanZ / cell)) + 1;

		// ---- pass 1: sample the field on the lattice --------------------------
		float[] field = new float[nx * ny * nz];
		int i = 0;
		for (int z = 0; z < nz; z++) {
			float pz = min.z + z * cell;
			for (int y = 0; y < ny; y++) {
				float py = min.y + y * cell;
				for (int x = 0; x < nx; x++) {
					field[i++] = sdf.eval(min.x + x * cell, py, pz);
				}
			}
		}

		// ---- pass 2: one vertex per sign-changing cell ------------------------
		int cellsX = nx - 1, cellsY = ny - 1, cellsZ = nz - 1;
		int[] cellVertex = new int[cellsX * cellsY * cellsZ];
		java.util.Arrays.fill(cellVertex, -1);
		// A cell straddling two limbs carries a second vertex, one per limb. Parallel arrays rather
		// than a map: the split is rare but the lookup is on the hot path of the quad pass, and a
		// second int array per cell costs less than boxing a key for every cell that is not split.
		int[] cellVertexB = new int[cellsX * cellsY * cellsZ];
		int[] cellGroup = new int[cellsX * cellsY * cellsZ];
		int[] cellGroupB = new int[cellsX * cellsY * cellsZ];
		java.util.Arrays.fill(cellVertexB, -1);
		java.util.Arrays.fill(cellGroup, NO_GROUP);
		java.util.Arrays.fill(cellGroupB, NO_GROUP);

		FloatList positions = new FloatList(4096);
		FloatList normals = new FloatList(4096);
		Vector3f normal = new Vector3f();
		float[] corners = new float[8];
		int vertexCount = 0;

		for (int z = 0; z < cellsZ; z++) {
			for (int y = 0; y < cellsY; y++) {
				for (int x = 0; x < cellsX; x++) {
					int mask = 0;
					for (int c = 0; c < 8; c++) {
						float v = field[index(x + CORNER[c][0], y + CORNER[c][1], z + CORNER[c][2], nx, ny)];
						corners[c] = v;
						if (v < 0f) mask |= 1 << c;
					}
					// All corners on the same side: no surface passes through this cell.
					if (mask == 0 || mask == 0xFF) continue;

					// Crossings are accumulated per limb as well as in total, because a cell holding
					// two limbs at once has to produce a vertex for each of them rather than one
					// vertex between them. Only two distinct limbs are tracked: a cell containing
					// three separate ones would need all three within a cell of each other, which
					// no body plan produces, and the general case would cost every cell a list.
					float sx = 0f, sy = 0f, sz = 0f;
					float ax2 = 0f, ay2 = 0f, az2 = 0f;
					float bx2 = 0f, by2 = 0f, bz2 = 0f;
					int crossings = 0, countA = 0, countB = 0;
					int limbA = NO_GROUP, limbB = NO_GROUP;
					for (int e = 0; e < 12; e++) {
						int a = EDGE[e][0], b = EDGE[e][1];
						boolean insideA = (mask & (1 << a)) != 0;
						boolean insideB = (mask & (1 << b)) != 0;
						if (insideA == insideB) continue;
						// Linear interpolation to the zero crossing along this edge.
						float t = corners[a] / (corners[a] - corners[b]);
						t = MathX.clamp01(t);
						float ex = CORNER[a][0] + (CORNER[b][0] - CORNER[a][0]) * t;
						float ey = CORNER[a][1] + (CORNER[b][1] - CORNER[a][1]) * t;
						float ez = CORNER[a][2] + (CORNER[b][2] - CORNER[a][2]) * t;
						sx += ex;
						sy += ey;
						sz += ez;
						crossings++;

						int g = sdf.groupAt(min.x + (x + ex) * cell, min.y + (y + ey) * cell,
								min.z + (z + ez) * cell);
						// Trunk crossings belong to whichever limb is also present — a shoulder is
						// one surface, and splitting it would tear the limb off the body.
						if (g == BoneDef.AXIAL) continue;
						if (limbA == NO_GROUP) limbA = g;
						if (g == limbA) {
							ax2 += ex; ay2 += ey; az2 += ez; countA++;
						} else {
							if (limbB == NO_GROUP) limbB = g;
							if (g == limbB) {
								bx2 += ex; by2 += ey; bz2 += ez; countB++;
							}
						}
					}
					if (crossings == 0) continue;

					int slot = (z * cellsY + y) * cellsX + x;

					// The ordinary case, and every cell that is not straddling two limbs: one
					// vertex at the average of the crossings. That average is what makes Surface
					// Nets smooth; pinning the vertex to the middle of its cell instead is what
					// makes it blocky. Every vertex then sits at a cell centre, so every quad
					// joining two neighbouring cells is an axis-aligned square exactly one cell
					// across — a voxel surface, arrived at by removing a step rather than adding a
					// mode.
					// Split only when the two limbs are genuinely separate surfaces inside this cell.
					//
					// Splitting is what stops two limbs meshing into one webbed sheet, and it costs
					// a seam: the cell hands out one vertex per limb, so the faces around it come
					// in referencing different vertices and their edges never pair up. Every split
					// cell is therefore a small hole, and that is the whole of the tearing seen
					// around hips — measured at 27% of four-legged creatures, with not one quad
					// actually being dropped to cause it.
					//
					// Two limbs merely clipping the same cell are not two surfaces. When their
					// crossings sit on top of each other the cell is looking at one continuous
					// piece of body — a hip, where a leg becomes the trunk — and splitting it buys
					// nothing while tearing the surface open. Only a cell that can see daylight
					// between them needs the split.
					boolean separate = false;
					if (splitNeeded && limbB != NO_GROUP && countA > 0 && countB > 0) {
						float dxg = ax2 / countA - bx2 / countB;
						float dyg = ay2 / countA - by2 / countB;
						float dzg = az2 / countA - bz2 / countB;
						separate = dxg * dxg + dyg * dyg + dzg * dzg > SPLIT_SEPARATION_SQ;
					}

					if (!separate) {
						float ox = voxels ? 0.5f : sx / crossings;
						float oy = voxels ? 0.5f : sy / crossings;
						float oz = voxels ? 0.5f : sz / crossings;
						emitVertex(positions, normals, sdf, normal, min, cell, x, y, z, ox, oy, oz);
						cellVertex[slot] = vertexCount++;
						continue;
					}

					// Two limbs in one cell. Each gets a vertex placed on its own surface, from its
					// own crossings only, so the two stay separate solids instead of being joined
					// through a single shared point sitting in the gap between them.
					float oax = voxels ? 0.5f : ax2 / countA;
					float oay = voxels ? 0.5f : ay2 / countA;
					float oaz = voxels ? 0.5f : az2 / countA;
					emitVertex(positions, normals, sdf, normal, min, cell, x, y, z, oax, oay, oaz);
					cellVertex[slot] = vertexCount++;
					cellGroup[slot] = limbA;

					float obx = voxels ? 0.5f : bx2 / countB;
					float oby = voxels ? 0.5f : by2 / countB;
					float obz = voxels ? 0.5f : bz2 / countB;
					emitVertex(positions, normals, sdf, normal, min, cell, x, y, z, obx, oby, obz);
					cellVertexB[slot] = vertexCount++;
					cellGroupB[slot] = limbB;
				}
			}
		}

		if (vertexCount == 0) {
			return new Result(new float[0], new float[0], new int[0], 0);
		}

		// ---- pass 3: stitch quads across sign-changing lattice edges ----------
		IntList quads = new IntList(4096);
		float[] pos = positions.data;
		float[] nrm = normals.data;

		for (int z = 0; z < nz; z++) {
			for (int y = 0; y < ny; y++) {
				for (int x = 0; x < nx; x++) {
					float v0 = field[index(x, y, z, nx, ny)];
					boolean inside0 = v0 < 0f;

					// An edge along one axis is shared by the four cells that surround it in the
					// other two. Both bounds are guarded on those two axes: the cell index runs
					// {n-1, n}, so n must be at least 1 and at most cells-1. In practice the outer
					// lattice shell is always outside the body and never produces a crossing, but
					// relying on that would make a padding change an out-of-bounds crash.
					boolean xInterior = x > 0 && x < nx - 1;
					boolean yInterior = y > 0 && y < ny - 1;
					boolean zInterior = z > 0 && z < nz - 1;

					// +X edge: spanned by cells varying in Y and Z.
					if (x < nx - 1 && yInterior && zInterior) {
						float v1 = field[index(x + 1, y, z, nx, ny)];
						if (inside0 != (v1 < 0f)) {
							float t = MathX.clamp01(v0 / (v0 - v1));
							emitQuad(quads, cellVertex, cellVertexB, cellGroup, cellGroupB,
									cellsX, cellsY, pos, nrm, inside0, sdf,
									min.x + (x + t) * cell, min.y + y * cell, min.z + z * cell,
									x, y - 1, z - 1, x, y, z - 1, x, y, z, x, y - 1, z);
						}
					}
					// +Y edge: spanned by cells varying in X and Z.
					if (y < ny - 1 && xInterior && zInterior) {
						float v1 = field[index(x, y + 1, z, nx, ny)];
						if (inside0 != (v1 < 0f)) {
							float t = MathX.clamp01(v0 / (v0 - v1));
							emitQuad(quads, cellVertex, cellVertexB, cellGroup, cellGroupB,
									cellsX, cellsY, pos, nrm, inside0, sdf,
									min.x + x * cell, min.y + (y + t) * cell, min.z + z * cell,
									x - 1, y, z - 1, x - 1, y, z, x, y, z, x, y, z - 1);
						}
					}
					// +Z edge: spanned by cells varying in X and Y.
					if (z < nz - 1 && xInterior && yInterior) {
						float v1 = field[index(x, y, z + 1, nx, ny)];
						if (inside0 != (v1 < 0f)) {
							float t = MathX.clamp01(v0 / (v0 - v1));
							emitQuad(quads, cellVertex, cellVertexB, cellGroup, cellGroupB,
									cellsX, cellsY, pos, nrm, inside0, sdf,
									min.x + x * cell, min.y + y * cell, min.z + (z + t) * cell,
									x - 1, y - 1, z, x, y - 1, z, x, y, z, x - 1, y, z);
						}
					}
				}
			}
		}

		return new Result(positions.trimmed(), normals.trimmed(), quads.trimmed(), vertexCount);
	}

	/**
	 * Appends the quad formed by four adjacent cells, if all four actually produced a vertex.
	 * <p>
	 * The quad's geometric normal is compared against the averaged SDF gradient and the order is
	 * reversed when they disagree, rather than being derived from the edge axis and crossing sign.
	 * <p>
	 * The axis-and-sign rule is exact about <i>topological</i> winding and that turns out not to
	 * be the useful question. These are dual vertices: each sits wherever its own cell placed it,
	 * so across a thin limb or a crease the four can be near-collinear and the quad's geometric
	 * normal — the one the GPU rasterises from, and the only one {@code gl_FrontFacing} knows
	 * about — can genuinely oppose the surface even when the topology is right. Aligning against
	 * the field is what keeps the rasterised facing and the shading normal on the same side.
	 * <p>
	 * {@link MeshBaker} re-checks this against the final smoothed normals afterwards, because
	 * smoothing can rotate a vertex normal past the point where this decision still holds.
	 */
	private static void emitQuad(IntList out, int[] cellVertex, int[] cellVertexB,
	                             int[] cellGroup, int[] cellGroupB, int cellsX, int cellsY,
	                             float[] pos, float[] nrm, boolean flipHint,
	                             BodySdf sdf, float wx, float wy, float wz,
	                             int ax, int ay, int az, int bx, int by, int bz,
	                             int cx, int cy, int cz, int dx, int dy, int dz) {
		int sa = (az * cellsY + ay) * cellsX + ax;
		int sb = (bz * cellsY + by) * cellsX + bx;
		int sc = (cz * cellsY + cy) * cellsX + cx;
		int sd = (dz * cellsY + dy) * cellsX + dx;

		int a, b, c, d;
		// Fast path: no cell around this edge holds two limbs, so there is nothing to disambiguate
		// and no reason to pay for a field query. True for all but a handful of cells per creature.
		if (cellVertexB[sa] < 0 && cellVertexB[sb] < 0 && cellVertexB[sc] < 0 && cellVertexB[sd] < 0) {
			a = cellVertex[sa];
			b = cellVertex[sb];
			c = cellVertex[sc];
			d = cellVertex[sd];
		} else {
			// Otherwise ask which limb this stretch of surface actually belongs to, and take the
			// vertex each cell contributes to that limb. A cell with nothing on this limb returns
			// -1 and the face is dropped, which is the point: that face would have been the one
			// bridging two limbs.
			int group = sdf.groupAt(wx, wy, wz);
			a = vertexFor(cellVertex, cellVertexB, cellGroup, cellGroupB, sa, group);
			b = vertexFor(cellVertex, cellVertexB, cellGroup, cellGroupB, sb, group);
			c = vertexFor(cellVertex, cellVertexB, cellGroup, cellGroupB, sc, group);
			d = vertexFor(cellVertex, cellVertexB, cellGroup, cellGroupB, sd, group);
		}
		if (a < 0 || b < 0 || c < 0 || d < 0) return;

		float e1x = pos[b * 3] - pos[a * 3];
		float e1y = pos[b * 3 + 1] - pos[a * 3 + 1];
		float e1z = pos[b * 3 + 2] - pos[a * 3 + 2];
		float e2x = pos[c * 3] - pos[a * 3];
		float e2y = pos[c * 3 + 1] - pos[a * 3 + 1];
		float e2z = pos[c * 3 + 2] - pos[a * 3 + 2];
		float gx = e1y * e2z - e1z * e2y;
		float gy = e1z * e2x - e1x * e2z;
		float gz = e1x * e2y - e1y * e2x;

		float sx = nrm[a * 3] + nrm[b * 3] + nrm[c * 3] + nrm[d * 3];
		float sy = nrm[a * 3 + 1] + nrm[b * 3 + 1] + nrm[c * 3 + 1] + nrm[d * 3 + 1];
		float sz = nrm[a * 3 + 2] + nrm[b * 3 + 2] + nrm[c * 3 + 2] + nrm[d * 3 + 2];

		float dot = gx * sx + gy * sy + gz * sz;
		// Degenerate quad (zero-area cross product): fall back to the sign of the crossing.
		boolean reverse = Math.abs(dot) < 1e-9f ? flipHint : dot < 0f;

		if (reverse) {
			out.add(d);
			out.add(c);
			out.add(b);
			out.add(a);
		} else {
			out.add(a);
			out.add(b);
			out.add(c);
			out.add(d);
		}
	}

	private static int index(int x, int y, int z, int nx, int ny) {
		return (z * ny + y) * nx + x;
	}

	/** Appends one dual vertex at the given fractional offset within cell (x, y, z). */
	private static void emitVertex(FloatList positions, FloatList normals, BodySdf sdf,
	                               Vector3f normal, Vector3f min, float cell,
	                               int x, int y, int z, float ox, float oy, float oz) {
		float wx = min.x + (x + ox) * cell;
		float wy = min.y + (y + oy) * cell;
		float wz = min.z + (z + oz) * cell;
		sdf.gradient(wx, wy, wz, normal);
		positions.add(wx);
		positions.add(wy);
		positions.add(wz);
		normals.add(normal.x);
		normals.add(normal.y);
		normals.add(normal.z);
	}

	/**
	 * The vertex a cell contributes to a surface belonging to {@code group}, or -1 if it has none.
	 * <p>
	 * An unsplit cell answers with its single vertex whatever is asked, which is what keeps every
	 * ordinary quad — the overwhelming majority — stitching exactly as before. A split cell answers
	 * only with the vertex on the matching limb, so a face can never reach from one limb to the
	 * other through it.
	 */
	private static int vertexFor(int[] cellVertex, int[] cellVertexB, int[] cellGroup,
	                            int[] cellGroupB, int slot, int group) {
		if (cellVertexB[slot] < 0) return cellVertex[slot];
		if (cellGroup[slot] == group) return cellVertex[slot];
		if (cellGroupB[slot] == group) return cellVertexB[slot];
		// A request for the trunk is always answerable, and this is where the holes came from.
		//
		// A split cell holds no trunk vertex by construction: the pass that places vertices folds
		// trunk crossings into whichever limb shares the cell rather than giving them their own,
		// precisely so a shoulder stays one surface. So when the edge being stitched resolves to
		// AXIAL — which it does constantly around a hip, where the body is the nearest thing —
		// every split cell answered -1 and the face was dropped. The faces being dropped were the
		// ones carrying the body across between the legs, which is why the tears sat at the hips
		// and got worse the more legs a creature had: 27% of four-legged creatures had at least
		// one. Handing back the cell's primary vertex closes the surface without reopening what
		// the -1 is actually for, which is refusing to bridge one limb to a different limb.
		if (group == BoneDef.AXIAL) return cellVertex[slot];
		return -1;
	}

	// ------------------------------------------------------- tiny growable buffers

	static final class FloatList {
		float[] data;
		int size;

		FloatList(int capacity) {
			data = new float[capacity];
		}

		void add(float v) {
			if (size == data.length) data = java.util.Arrays.copyOf(data, size * 2);
			data[size++] = v;
		}

		float[] trimmed() {
			return java.util.Arrays.copyOf(data, size);
		}
	}

	static final class IntList {
		int[] data;
		int size;

		IntList(int capacity) {
			data = new int[capacity];
		}

		void add(int v) {
			if (size == data.length) data = java.util.Arrays.copyOf(data, size * 2);
			data[size++] = v;
		}

		int[] trimmed() {
			return java.util.Arrays.copyOf(data, size);
		}
	}
}
