package net.irisshaders.iris.anr.fsr;

import java.util.Locale;

/** Optional on-screen debug output for the FSR 3 frame generation passes. */
public enum FsrDebugView {
	/** Normal output. */
	NONE(0),
	/** Show the camera-reprojection motion vectors (red/green = x/y, blue = hand / static). */
	MOTION(1),
	/** Show the disocclusion mask (red = disoccluded in the previous frame, green = UI detected). */
	DISOCCLUSION(2),
	/** Replace every real frame with the generated one, so interpolation artifacts are easy to see. */
	INTERPOLATED(3);

	private final int shaderId;

	FsrDebugView(int shaderId) {
		this.shaderId = shaderId;
	}

	public int shaderId() {
		return this.shaderId;
	}

	public static FsrDebugView fromConfig(String value) {
		if (value == null) {
			return NONE;
		}

		try {
			return valueOf(value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return NONE;
		}
	}

	public String configName() {
		return name().toLowerCase(Locale.ROOT);
	}
}
