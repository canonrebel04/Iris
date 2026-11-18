package net.irisshaders.iris.anr;

import java.util.Set;

/**
 * Default backend. Performs no work and does not change the frame. Used when ANR is disabled
 * or when no compatible native library is present.
 */
public final class DisabledNeuralRenderingBackend implements NeuralRenderingBackend {
	public static final DisabledNeuralRenderingBackend INSTANCE = new DisabledNeuralRenderingBackend();

	private DisabledNeuralRenderingBackend() {
	}

	@Override
	public boolean isAvailable() {
		return false;
	}

	@Override
	public Set<NeuralCapability> capabilities() {
		return Set.of();
	}

	@Override
	public void init(NeuralInitInfo info) {
	}

	@Override
	public void shutdown() {
	}

	@Override
	public NeuralFrameResult evaluate(NeuralFrameInputs inputs) {
		return NeuralFrameResult.skipped();
	}
}
