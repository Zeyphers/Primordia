package dev.jsz.primordia.mesh;

/**
 * A baked creature mesh in <b>bind pose</b>, stored as flat primitive arrays so it can be
 * skinned every frame without allocating.
 * <p>
 * Geometry is quads, not triangles, because Minecraft's entity render layers use
 * {@code DrawMode.QUADS} — emitting quads directly avoids either a custom render layer or the
 * degenerate-fourth-vertex trick.
 * <p>
 * Instances are immutable once built and are shared between every creature with the same
 * genome, so nothing here may be mutated during rendering. Per-frame skinned positions live in
 * {@link SkinnedMesh} instead.
 */
public final class MeshData {
	/** 3 floats per vertex. */
	public final float[] positions;
	/** 3 floats per vertex, from the SDF gradient. */
	public final float[] normals;
	/** 3 floats per vertex, linear RGB in [0,1]. */
	public final float[] colors;
	/** 4 bone indices per vertex. */
	public final int[] boneIndices;
	/** 4 weights per vertex, summing to 1. */
	public final float[] boneWeights;
	/** 4 vertex indices per quad. */
	public final int[] quads;

	public final int vertexCount;
	public final int quadCount;

	/** Bind-pose bounding box, used for frustum culling and collision box sizing. */
	public final float minX, minY, minZ, maxX, maxY, maxZ;

	public MeshData(float[] positions, float[] normals, float[] colors,
	                int[] boneIndices, float[] boneWeights, int[] quads,
	                float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		this.positions = positions;
		this.normals = normals;
		this.colors = colors;
		this.boneIndices = boneIndices;
		this.boneWeights = boneWeights;
		this.quads = quads;
		this.vertexCount = positions.length / 3;
		this.quadCount = quads.length / 4;
		this.minX = minX;
		this.minY = minY;
		this.minZ = minZ;
		this.maxX = maxX;
		this.maxY = maxY;
		this.maxZ = maxZ;
	}

	/** Rough GPU/CPU cost proxy used by the LOD budget. */
	public int cost() {
		return quadCount;
	}
}
