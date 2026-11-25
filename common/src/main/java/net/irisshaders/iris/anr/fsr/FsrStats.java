package net.irisshaders.iris.anr.fsr;

import net.irisshaders.iris.Iris;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What FSR is actually doing, for the F3 screen and a periodic log line: the upscaling resolution and the real,
 * generated and presented frame rates. Vanilla's FPS counter only counts real frames, so without this a working frame
 * generator is invisible in the numbers.
 */
public final class FsrStats {
	private static final long WINDOW_NANOS = 1_000_000_000L;
	private static final long LOG_INTERVAL_NANOS = 60_000_000_000L;
	private static final long FIRST_LOG_DELAY_NANOS = 5_000_000_000L;

	// Counted on the render thread.
	private static int realFrames;
	private static int generatedFrames;
	private static int upscaledFrames;
	private static long windowStart = System.nanoTime();

	// Last completed one-second window.
	private static float realFps;
	private static float generatedFps;
	private static float upscaledFps;

	// Upscaling state reported by FsrUpscaler.
	private static int renderWidth;
	private static int renderHeight;
	private static int displayWidth;
	private static int displayHeight;
	private static String upscaleQuality = "";

	private static long firstActivity = -1;
	private static long lastLog;

	private FsrStats() {
	}

	/** One real frame reached the swapchain blit (called whether or not FSR is enabled). */
	static void onRealFrame() {
		realFrames++;
		long now = System.nanoTime();
		long elapsed = now - windowStart;

		if (elapsed >= WINDOW_NANOS) {
			float seconds = elapsed / 1e9f;
			realFps = realFrames / seconds;
			generatedFps = generatedFrames / seconds;
			upscaledFps = upscaledFrames / seconds;
			realFrames = generatedFrames = upscaledFrames = 0;
			windowStart = now;
			maybeLog(now);
		}
	}

	static void onGeneratedFramePresented() {
		generatedFrames++;
	}

	static void onUpscaledFrame(int renderW, int renderH, int displayW, int displayH, String quality) {
		upscaledFrames++;
		renderWidth = renderW;
		renderHeight = renderH;
		displayWidth = displayW;
		displayHeight = displayH;
		upscaleQuality = quality;
	}

	private static boolean anythingEnabled() {
		var config = Iris.getIrisConfig();
		return config != null && (config.isFsrUpscalingEnabled() || config.isFsr3FrameGenEnabled());
	}

	private static void maybeLog(long now) {
		if (upscaledFps <= 0 && generatedFps <= 0) {
			return;
		}

		if (firstActivity < 0) {
			firstActivity = now;
			lastLog = now - LOG_INTERVAL_NANOS + FIRST_LOG_DELAY_NANOS;
		}

		if (now - lastLog >= LOG_INTERVAL_NANOS) {
			lastLog = now;
			Iris.logger.info("[FSR] Status: {} | {}", upscalingLine(), frameGenLine());
		}
	}

	private static String upscalingLine() {
		var config = Iris.getIrisConfig();

		if (config == null || !config.isFsrUpscalingEnabled()) {
			return "upscaling off";
		}

		if (upscaledFps <= 0) {
			FsrUpscaler upscaler = FsrUpscaler.activeUpscaler();
			return "upscaling enabled but not running (" + (upscaler == null ? "no shader pack, or setup failed" : upscaler.idleReason()) + ")";
		}

		return String.format(Locale.ROOT, "upscaling %dx%d -> %dx%d (%s)", renderWidth, renderHeight, displayWidth, displayHeight,
			upscaleQuality);
	}

	private static String frameGenLine() {
		var config = Iris.getIrisConfig();

		if (config == null || !config.isFsr3FrameGenEnabled()) {
			return String.format(Locale.ROOT, "frame generation off, %.0f fps", realFps);
		}

		FsrFrameGeneration generator = FsrFrameGeneration.active();
		String mode = generator == null ? "no shader pack, or setup failed" : generator.statusDescription();
		return String.format(Locale.ROOT, "frame generation %s: %.0f real + %.0f generated = %.0f presented fps", mode, realFps,
			generatedFps, realFps + generatedFps);
	}

	/** Lines for the F3 screen; empty when both FSR features are off in the config. */
	public static List<String> debugLines() {
		var lines = new ArrayList<String>(2);

		if (!anythingEnabled()) {
			return lines;
		}

		lines.add("FSR " + upscalingLine());
		lines.add("FSR " + frameGenLine());
		return lines;
	}
}
