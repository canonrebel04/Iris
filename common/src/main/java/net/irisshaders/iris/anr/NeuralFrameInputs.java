package net.irisshaders.iris.anr;

/**
 * Per-frame tensors and camera state gathered after Iris's composite/final pass.
 * Matrix arrays are 4x4 column-major (JOML / OpenGL convention), 16 floats each.
 */
public final class NeuralFrameInputs {
	public int featureFlags;
	public NeuralTextureHandle color = NeuralTextureHandle.empty();
	public NeuralTextureHandle depth = NeuralTextureHandle.empty();
	public NeuralTextureHandle motionVectors = NeuralTextureHandle.empty();
	public final float[] view = new float[16];
	public final float[] projection = new float[16];
	public final float[] previousView = new float[16];
	public final float[] previousProjection = new float[16];
	public float jitterX;
	public float jitterY;
	public float exposure = 1.0f;
	public float frameDeltaSeconds;
	public float superResolutionScale = 1.0f;
	public int superResolutionQuality = NeuralSuperResolutionQuality.BALANCED.id();

	public boolean hasMotionVectors() {
		return motionVectors.isPresent();
	}
}
