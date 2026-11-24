package net.irisshaders.iris.anr.fsr;

import java.util.Locale;

/**
 * How Iris spaces the generated frame and the real frame it presents back to back.
 *
 * <p>Iris presents both frames from the render thread (there is no separate present thread yet), so the
 * simplest reliable pacing is the swapchain itself: with vsync on, FIFO presentation shows each image for
 * at least one refresh.</p>
 */
public enum FsrFramePacing {
	/** No extra waiting. With vsync on (recommended), FIFO presentation paces the two images. */
	VSYNC,
	/** Sleep for about half of the measured real frame time between the generated and the real present. */
	SLEEP,
	/** Present both images back to back with no waiting at all (debug only, will look like stutter). */
	NONE;

	public static FsrFramePacing fromConfig(String value) {
		if (value == null) {
			return VSYNC;
		}

		try {
			return valueOf(value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return VSYNC;
		}
	}

	public String configName() {
		return name().toLowerCase(Locale.ROOT);
	}
}
