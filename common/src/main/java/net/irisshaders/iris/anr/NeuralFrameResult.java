package net.irisshaders.iris.anr;

/**
 * Status returned by {@link NeuralRenderingBackend#evaluate}. Presentation of generated
 * or upscaled frames back into Minecraft is not implemented yet.
 */
public record NeuralFrameResult(int status, int generatedFrames, int outputWidth, int outputHeight) {
	public static final int STATUS_OK = 0;
	public static final int STATUS_SKIPPED = 1;
	public static final int STATUS_ERROR = -1;

	public static NeuralFrameResult skipped() {
		return new NeuralFrameResult(STATUS_SKIPPED, 0, 0, 0);
	}
}
