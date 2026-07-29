package dev.jsz.primordia.mesh;

import org.joml.Vector3f;

/**
 * The mean colour of one quad's four corners, for flat per-face colouring at draw time.
 * <p>
 * Colour lives in the vertices — there is no texture — so the hardware interpolates it across every
 * face, and a quad whose corners sample four different points of a pattern comes out as a small
 * gradient. On a smooth body that is exactly right and is what makes markings read as continuous.
 * On a voxel body it is wrong twice over: the shape says the surface is made of flat blocks and the
 * shading says each block is curved, and the blocks stop reading as blocks.
 * <p>
 * Averaging the four corners keeps the pattern — a face still takes its colour from where it
 * actually sits on the animal — while giving each face a single value, the way a block texel does.
 * <p>
 * A rendering concern for the same reason {@link FaceNormal} is: both renderers already emit four
 * unshared vertices per quad, so this only changes which colour those four are handed. The mesh is
 * untouched and nothing needs rebaking.
 */
public final class FaceColour {
	private FaceColour() {
	}

	/**
	 * Writes the mean of the quad's four corner colours into {@code dest}.
	 *
	 * @param colors    vertex colours, three floats each
	 * @param quads     quad index array, four indices per face
	 * @param quadStart index into {@code quads} of this face's first corner
	 */
	public static void average(float[] colors, int[] quads, int quadStart, Vector3f dest) {
		float r = 0f, g = 0f, b = 0f;
		for (int k = 0; k < 4; k++) {
			int p = quads[quadStart + k] * 3;
			r += colors[p];
			g += colors[p + 1];
			b += colors[p + 2];
		}
		dest.set(r * 0.25f, g * 0.25f, b * 0.25f);
	}

	/**
	 * Mean emissive weight across the quad's four corners.
	 * <p>
	 * Flattened alongside the colour rather than left per-vertex. Emissive drives the light level a
	 * vertex is drawn at, so leaving it interpolated would put a gradient back across a face that
	 * had just been flattened — most visibly on the edge of a glowing marking, which is where the
	 * value changes fastest.
	 */
	public static float averageEmissive(float[] emissive, int[] quads, int quadStart) {
		float sum = 0f;
		for (int k = 0; k < 4; k++) {
			sum += emissive[quads[quadStart + k]];
		}
		return sum * 0.25f;
	}
}
