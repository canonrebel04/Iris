package net.irisshaders.iris.anr;

import java.util.Set;

/**
 * Pluggable neural rendering implementation (frame generation, super-resolution, ray reconstruction).
 * The default is a no-op; a native library matching the ABI in {@code docs/ANR.md} can be loaded at runtime.
 */
public interface NeuralRenderingBackend {
	boolean isAvailable();

	Set<NeuralCapability> capabilities();

	void init(NeuralInitInfo info);

	void shutdown();

	NeuralFrameResult evaluate(NeuralFrameInputs inputs);
}
