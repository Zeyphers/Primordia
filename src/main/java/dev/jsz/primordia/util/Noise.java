package dev.jsz.primordia.util;

/**
 * Compact deterministic 3D gradient noise, seeded per instance.
 * <p>
 * Used for coat patterns and for jittering body proportions so that two creatures
 * with the same genome still bake identically (same seed) while different genomes
 * diverge. Deliberately self-contained so it can run on bake threads without
 * touching any Minecraft state.
 */
public final class Noise {
	private final int[] perm = new int[512];

	public Noise(long seed) {
		int[] p = new int[256];
		for (int i = 0; i < 256; i++) p[i] = i;
		// Fisher-Yates driven by a splitmix64 stream.
		long s = seed;
		for (int i = 255; i > 0; i--) {
			s += 0x9E3779B97F4A7C15L;
			long z = s;
			z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
			z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
			z = z ^ (z >>> 31);
			int j = (int) Math.floorMod(z, i + 1);
			int t = p[i];
			p[i] = p[j];
			p[j] = t;
		}
		for (int i = 0; i < 512; i++) perm[i] = p[i & 255];
	}

	private static float fade(float t) {
		return t * t * t * (t * (t * 6f - 15f) + 10f);
	}

	private static float grad(int hash, float x, float y, float z) {
		int h = hash & 15;
		float u = h < 8 ? x : y;
		float v = h < 4 ? y : (h == 12 || h == 14 ? x : z);
		return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
	}

	/** Classic Perlin noise, output roughly in [-1, 1]. */
	public float sample(float x, float y, float z) {
		int xi = (int) Math.floor(x) & 255;
		int yi = (int) Math.floor(y) & 255;
		int zi = (int) Math.floor(z) & 255;
		float xf = x - (float) Math.floor(x);
		float yf = y - (float) Math.floor(y);
		float zf = z - (float) Math.floor(z);
		float u = fade(xf), v = fade(yf), w = fade(zf);

		int a = perm[xi] + yi, aa = perm[a & 255] + zi, ab = perm[(a + 1) & 255] + zi;
		int b = perm[(xi + 1) & 255] + yi, ba = perm[b & 255] + zi, bb = perm[(b + 1) & 255] + zi;

		float x1 = MathX.lerp(grad(perm[aa & 255], xf, yf, zf), grad(perm[ba & 255], xf - 1f, yf, zf), u);
		float x2 = MathX.lerp(grad(perm[ab & 255], xf, yf - 1f, zf), grad(perm[bb & 255], xf - 1f, yf - 1f, zf), u);
		float y1 = MathX.lerp(x1, x2, v);

		x1 = MathX.lerp(grad(perm[(aa + 1) & 255], xf, yf, zf - 1f), grad(perm[(ba + 1) & 255], xf - 1f, yf, zf - 1f), u);
		x2 = MathX.lerp(grad(perm[(ab + 1) & 255], xf, yf - 1f, zf - 1f), grad(perm[(bb + 1) & 255], xf - 1f, yf - 1f, zf - 1f), u);
		float y2 = MathX.lerp(x1, x2, v);

		return MathX.lerp(y1, y2, w);
	}

	/** Fractal Brownian motion over {@link #sample}. */
	public float fbm(float x, float y, float z, int octaves, float lacunarity, float gain) {
		float sum = 0f, amp = 0.5f, freq = 1f, norm = 0f;
		for (int i = 0; i < octaves; i++) {
			sum += amp * sample(x * freq, y * freq, z * freq);
			norm += amp;
			freq *= lacunarity;
			amp *= gain;
		}
		return norm < MathX.EPS ? 0f : sum / norm;
	}
}
