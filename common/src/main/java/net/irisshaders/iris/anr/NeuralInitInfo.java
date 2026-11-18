package net.irisshaders.iris.anr;

/**
 * Information given to a backend once, when it is first loaded.
 */
public record NeuralInitInfo(int abiVersion, int graphicsApi) {
	public static final int ABI_VERSION = 1;
}
