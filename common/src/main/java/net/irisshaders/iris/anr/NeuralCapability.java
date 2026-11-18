package net.irisshaders.iris.anr;

/**
 * Features a neural rendering backend may implement. These match Apex Neural Rendering's
 * announced components, but this package does not include or depend on any ANR code.
 */
public enum NeuralCapability {
	FRAME_GENERATION(1),
	SUPER_RESOLUTION(1 << 1),
	RAY_RECONSTRUCTION(1 << 2);

	private final int bit;

	NeuralCapability(int bit) {
		this.bit = bit;
	}

	public int bit() {
		return bit;
	}
}
