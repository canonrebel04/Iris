package net.irisshaders.iris.anr.fsr;

import java.util.Locale;

/**
 * FSR 2/3 upscaling quality modes and their per-axis scale factors (display size / render size), using the
 * same ratios as AMD's FidelityFX FSR 2 / FSR 3 upscaler.
 */
public enum FsrUpscalingQuality {
	NATIVE_AA(1.0f),
	QUALITY(1.5f),
	BALANCED(1.7f),
	PERFORMANCE(2.0f),
	ULTRA_PERFORMANCE(3.0f);

	private final float ratio;

	FsrUpscalingQuality(float ratio) {
		this.ratio = ratio;
	}

	/** Display resolution divided by render resolution, per axis. */
	public float ratio() {
		return this.ratio;
	}

	public int renderSize(int displaySize) {
		return Math.max(1, (int) (displaySize / this.ratio));
	}

	public static FsrUpscalingQuality fromConfig(String value) {
		if (value == null) {
			return QUALITY;
		}

		try {
			return valueOf(value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return QUALITY;
		}
	}

	public String configName() {
		return name().toLowerCase(Locale.ROOT);
	}
}
