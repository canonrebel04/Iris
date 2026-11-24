package net.irisshaders.iris.anr.fsr;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.config.IrisConfig;

/**
 * Placeholder for FSR 2/3-style temporal upscaling (milestone 3 in docs/FSR3.md).
 *
 * <p>Planned data flow, sharing inputs with {@link FsrFrameGeneration}:</p>
 * <ol>
 *     <li>Render the world and the shader pack's gbuffer/deferred/composite passes at
 *     {@link FsrUpscalingQuality#renderSize} of the window, with the projection offset by {@link FsrJitter}.</li>
 *     <li>After the pack's final pass, run the FSR 2/3 upscaler passes (ported to Iris composite/compute programs the
 *     same way as the frame-interpolation pass): reconstruct + dilate depth/motion, depth clip, lock, accumulate,
 *     RCAS sharpening with {@code fsrUpscalingSharpness}. Motion vectors come from the camera-reprojection pass,
 *     computed at render resolution and un-jittered.</li>
 *     <li>Write the display-resolution result into Minecraft's main target before the color-space pass and the GUI,
 *     so frame generation, the HUD-less capture and the GUI all run at display resolution.</li>
 * </ol>
 *
 * <p>Until that lands, enabling {@code fsrUpscaling} only logs a notice; rendering stays at native resolution and no
 * jitter is applied (jitter without an upscaler would only make the image shimmer).</p>
 */
public final class FsrUpscaling {
	private static boolean loggedNotImplemented;

	private FsrUpscaling() {
	}

	/** Always false for now: nothing changes the render resolution or the projection yet. */
	public static boolean isActive() {
		return false;
	}

	static void logStatus(IrisConfig config) {
		if (config.isFsrUpscalingEnabled() && !loggedNotImplemented) {
			loggedNotImplemented = true;
			var quality = config.getFsrUpscalingQuality();
			Iris.logger.warn("[FSR-SR] fsrUpscaling=true ({}, {}x scale, sharpness {}) is not implemented yet; rendering stays at native "
				+ "resolution. See docs/FSR3.md.", quality.configName(), quality.ratio(), config.getFsrUpscalingSharpness());
		}
	}
}
