package net.irisshaders.iris.anr.fsr;

/**
 * Sub-pixel camera jitter for temporal upscaling, following the scheme documented for AMD FidelityFX
 * FSR 2 / FSR 3: a Halton(2, 3) sequence whose phase count grows with the square of the upscale ratio
 * ({@code 8 * ratio^2}).
 *
 * <p>Offsets are in render-resolution pixels, in the range [-0.5, 0.5). Convert them to a projection-matrix
 * offset with {@link #toClipSpace}.</p>
 */
public final class FsrJitter {
	private static final int BASE_PHASE_COUNT = 8;

	private FsrJitter() {
	}

	public static int phaseCount(int renderWidth, int displayWidth) {
		float ratio = (float) displayWidth / Math.max(1, renderWidth);
		return Math.max(1, (int) (BASE_PHASE_COUNT * ratio * ratio));
	}

	/** Radical inverse (Halton) of {@code index} in the given base. Index is 1-based. */
	static float halton(int index, int base) {
		float f = 1.0f;
		float result = 0.0f;

		for (int i = index; i > 0; ) {
			f /= base;
			result += f * (i % base);
			i = (int) Math.floor(i / (float) base);
		}

		return result;
	}

	/**
	 * @param frameIndex monotonically increasing frame counter
	 * @param phaseCount see {@link #phaseCount}
	 * @param out        receives {x, y} in render pixels, each in [-0.5, 0.5)
	 */
	public static void offset(long frameIndex, int phaseCount, float[] out) {
		int index = (int) Math.floorMod(frameIndex, (long) phaseCount) + 1;
		out[0] = halton(index, 2) - 0.5f;
		out[1] = halton(index, 3) - 0.5f;
	}

	/**
	 * Converts a pixel jitter to an NDC translation, applied as {@code P' = translation(x, y, 0) * P}, so the whole
	 * frame shifts by that many render pixels. Iris works in OpenGL conventions on both backends (UV and NDC both
	 * have +Y up), so unlike the D3D-oriented FidelityFX samples there is no Y flip here; the upscaler shader uses
	 * the same convention when it converts the jitter back.
	 */
	public static void toClipSpace(float[] pixelJitter, int renderWidth, int renderHeight, float[] out) {
		out[0] = 2.0f * pixelJitter[0] / renderWidth;
		out[1] = 2.0f * pixelJitter[1] / renderHeight;
	}
}
