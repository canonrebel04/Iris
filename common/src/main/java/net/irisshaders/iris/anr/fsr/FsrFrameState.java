package net.irisshaders.iris.anr.fsr;

/**
 * Per-frame state shared by the FSR passes: the real-frame counter (advanced once per swapchain blit) and the
 * sub-pixel jitter applied to this frame's projection by {@link FsrUpscaler} (zero when upscaling is off).
 */
public final class FsrFrameState {
	private static long frameIndex;
	private static float jitterNdcX;
	private static float jitterNdcY;

	private FsrFrameState() {
	}

	/** Real frames so far, counted at the swapchain blit. Stays 0 if that hook never runs. */
	public static long frameIndex() {
		return frameIndex;
	}

	static void advanceFrame() {
		frameIndex++;
	}

	/** Sets the projection offset (NDC units) applied to the current frame; 0, 0 when no jitter is applied. */
	static void setJitter(float ndcX, float ndcY) {
		jitterNdcX = ndcX;
		jitterNdcY = ndcY;
	}

	public static float jitterNdcX() {
		return jitterNdcX;
	}

	public static float jitterNdcY() {
		return jitterNdcY;
	}
}
