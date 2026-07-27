package dev.jsz.primordia.mesh;

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
		float spanX = Math.max(max.x - min.x, 1e-3f);
		float spanY = Math.max(max.y - min.y, 1e-3f);
		float spanZ = Math.max(max.z - min.z, 1e-3f);
		float longest = Math.max(spanX, Math.max(spanY, spanZ));
		float cell = longest / Math.max(2, resolution);

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

					float sx = 0f, sy = 0f, sz = 0f;
					int crossings = 0;
					for (int e = 0; e < 12; e++) {
						int a = EDGE[e][0], b = EDGE[e][1];
						boolean insideA = (mask & (1 << a)) != 0;
						boolean insideB = (mask & (1 << b)) != 0;
						if (insideA == insideB) continue;
						// Linear interpolation to the zero crossing along this edge.
						float t = corners[a] / (corners[a] - corners[b]);
						t = MathX.clamp01(t);
						sx += CORNER[a][0] + (CORNER[b][0] - CORNER[a][0]) * t;
						sy += CORNER[a][1] + (CORNER[b][1] - CORNER[a][1]) * t;
						sz += CORNER[a][2] + (CORNER[b][2] - CORNER[a][2]) * t;
						crossings++;
					}
					if (crossings == 0) continue;

					float wx = min.x + (x + sx / crossings) * cell;
					float wy = min.y + (y + sy / crossings) * cell;
					float wz = min.z + (z + sz / crossings) * cell;

					sdf.gradient(wx, wy, wz, normal);
					positions.add(wx);
					positions.add(wy);
					positions.add(wz);
					normals.add(normal.x);
					normals.add(normal.y);
					normals.add(normal.z);

					cellVertex[(z * cellsY + y) * cellsX + x] = vertexCount++;
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
						boolean inside1 = field[index(x + 1, y, z, nx, ny)] < 0f;
						if (inside0 != inside1) {
							emitQuad(quads, cellVertex, cellsX, cellsY, pos, nrm, inside0,
									x, y - 1, z - 1, x, y, z - 1, x, y, z, x, y - 1, z);
						}
					}
					// +Y edge: spanned by cells varying in X and Z.
					if (y < ny - 1 && xInterior && zInterior) {
						boolean inside1 = field[index(x, y + 1, z, nx, ny)] < 0f;
						if (inside0 != inside1) {
							emitQuad(quads, cellVertex, cellsX, cellsY, pos, nrm, inside0,
									x - 1, y, z - 1, x - 1, y, z, x, y, z, x, y, z - 1);
						}
					}
					// +Z edge: spanned by cells varying in X and Y.
					if (z < nz - 1 && xInterior && yInterior) {
						boolean inside1 = field[index(x, y, z + 1, nx, ny)] < 0f;
						if (inside0 != inside1) {
							emitQuad(quads, cellVertex, cellsX, cellsY, pos, nrm, inside0,
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
	 * Rather than deriving the winding from the edge axis and sign (easy to get subtly wrong in
	 * one of six cases), the quad's geometric normal is compared against the averaged SDF
	 * gradient and the order is reversed when they disagree. That is one cross product per quad
	 * and it cannot produce inside-out geometry.
	 */
	private static void emitQuad(IntList out, int[] cellVertex, int cellsX, int cellsY,
	                             float[] pos, float[] nrm, boolean flipHint,
	                             int ax, int ay, int az, int bx, int by, int bz,
	                             int cx, int cy, int cz, int dx, int dy, int dz) {
		int a = cellVertex[(az * cellsY + ay) * cellsX + ax];
		int b = cellVertex[(bz * cellsY + by) * cellsX + bx];
		int c = cellVertex[(cz * cellsY + cy) * cellsX + cx];
		int d = cellVertex[(dz * cellsY + dy) * cellsX + dx];
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
