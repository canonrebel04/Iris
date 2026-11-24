package net.irisshaders.iris.anr.fsr;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.config.IrisConfig;
import net.irisshaders.iris.gl.framebuffer.ViewportData;
import net.irisshaders.iris.gl.sampler.GlSampler;
import net.irisshaders.iris.gl.sampler.SamplerHolder.TextureBinding;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.pipeline.programs.CompositeProgram;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.samplers.PackSamplers;
import net.irisshaders.iris.targets.ColorAttachmentPlan;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector4f;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * FSR 3-style frame generation implemented with Iris's own fullscreen passes, so it runs on both the OpenGL
 * and Vulkan backends, on Windows and Linux, with no native library.
 *
 * <p>Per real frame N:</p>
 * <ol>
 *     <li>{@link FsrMotionVectors#render()} (after the shader pack's final pass): camera-reprojection motion vectors
 *     + depth disocclusion, at render resolution (lower than the display when {@link FsrUpscaler} is on).</li>
 *     <li>{@link #captureHudless(GpuTexture)} (after Iris's color-space pass, before the GUI is drawn, always at
 *     display resolution): copy the HUD-less scene color.</li>
 *     <li>{@link #beforeSwapchainBlit} (from Minecraft's frame loop, after the GUI): interpolate a frame halfway
 *     between N-1 and N, present it, re-acquire the swapchain, and let vanilla present the real frame N.</li>
 * </ol>
 *
 * <p>Everything is created only when {@code fsr3FrameGen=true}; otherwise no instance exists and no hook does
 * anything.</p>
 */
public final class FsrFrameGeneration {
	private static final String LOG_PREFIX = "[FSR3-FG] ";

	@Nullable
	private static FsrFrameGeneration active;

	private final FsrMotionVectors motion;
	private final CompositeProgram interpolateProgram;
	private final FsrFramePacing pacing;
	private final FsrDebugView debugView;
	private final boolean present;

	private final GpuTexture[] hudless = new GpuTexture[2];
	private final GpuTextureView[] hudlessViews = new GpuTextureView[2];
	private int currentHudless;
	private GpuTexture finalCopy;
	private GpuTextureView finalCopyView;
	private GpuTexture output;
	private GpuTextureView outputView;
	private int width = -1;
	private int height = -1;
	private GpuFormat colorFormat;

	private final Vector4f interpolateSettings = new Vector4f();

	private long hudlessFrame = -1;
	private long previousHudlessFrame = -2;
	private long historyResetFrame = -1;
	private int framesSinceReset;
	private long drawCounter;

	private double smoothedWorkSeconds = -1;
	private long lastRealPresentNanos;
	private boolean loggedFirstFrame;
	private boolean loggedFailure;
	private boolean failed;
	private boolean destroyed;

	private FsrFrameGeneration(PackSamplers samplers, FsrMotionVectors motion, IrisConfig config) {
		this.motion = motion;
		this.pacing = config.getFsr3FrameGenPacing();
		this.debugView = config.getFsr3FrameGenDebugView();
		this.present = config.shouldPresentFsr3GeneratedFrames();

		String vertex = FsrShaders.read("/fsr3fg/fullscreen.vsh");
		var interpolateOutputs = new ColorAttachmentPlan.Builder().program("fsr3fg_interpolate", new int[]{0}, Set.of()).build(1)
			.program("fsr3fg_interpolate");
		this.interpolateProgram = new CompositeProgram(samplers, "fsr3fg_interpolate",
			Map.of(PatchShaderType.VERTEX, vertex, PatchShaderType.FRAGMENT, FsrShaders.read("/fsr3fg/interpolate.fsh")),
			interpolateOutputs, Collections.singletonList(null));

		try {
			this.interpolateProgram.getUniforms().uniform4f(UniformUpdateFrequency.CUSTOM, "fgSettings", () -> this.interpolateSettings);
			var interpolateSamplers = this.interpolateProgram.getSamplers();
			interpolateSamplers.addDrawTextureSampler(
				new TextureBinding("fsr3fg/previousColor", () -> this.hudlessViews[1 - this.currentHudless], () -> GlSampler.NEAREST), false,
				"fgPrevColor");
			interpolateSamplers.addDrawTextureSampler(
				new TextureBinding("fsr3fg/currentColor", () -> this.hudlessViews[this.currentHudless], () -> GlSampler.NEAREST), false,
				"fgCurrColor");
			// Motion is at render resolution and may be smaller than the display; the shader samples it with normalized UVs.
			interpolateSamplers.addDrawTextureSampler(new TextureBinding("fsr3fg/motion", this.motion::view, () -> GlSampler.NEAREST),
				false, "fgMotion");
			interpolateSamplers.addDrawTextureSampler(new TextureBinding("fsr3fg/finalColor", () -> this.finalCopyView, () -> GlSampler.NEAREST),
				false, "fgFinalColor");
			interpolateSamplers.finish();
		} catch (RuntimeException | Error e) {
			this.interpolateProgram.destroy();
			throw e;
		}
	}

	/**
	 * Creates the frame generator for a new shader pipeline when {@code fsr3FrameGen=true}. Must be called while the
	 * pipeline is still registering its samplers (before the sampler layout is finalized).
	 *
	 * @return null when disabled or when setup failed (Iris then renders exactly as without frame generation)
	 */
	@Nullable
	public static FsrFrameGeneration createIfEnabled(PackSamplers samplers, @Nullable FsrMotionVectors motion) {
		IrisConfig config = Iris.getIrisConfig();

		if (config == null || !config.isFsr3FrameGenEnabled()) {
			return null;
		}

		if (motion == null) {
			Iris.logger.error(LOG_PREFIX + "No motion vectors are available; frame generation is disabled for this shader pack.");
			return null;
		}

		try {
			var generator = new FsrFrameGeneration(samplers, motion, config);
			active = generator;
			Iris.logger.info(LOG_PREFIX + "Enabled on the {} backend (present={}, pacing={}, debugView={}).",
				RenderSystem.getDevice().getDeviceInfo().backendName(), generator.present, generator.pacing.configName(),
				generator.debugView.configName());
			return generator;
		} catch (RuntimeException e) {
			Iris.logger.error(LOG_PREFIX + "Setup failed; frame generation is disabled for this shader pack.", e);
			return null;
		}
	}

	/** The generator of the current shader pipeline, or null. */
	@Nullable
	public static FsrFrameGeneration active() {
		return active;
	}

	/** After the color-space pass, before the GUI. {@code mainColor} holds the HUD-less, display-resolution frame. */
	public void captureHudless(GpuTexture mainColor) {
		if (this.destroyed) {
			return;
		}

		// Iris keeps one pipeline per dimension alive; the one rendering this frame presents the generated frame.
		active = this;

		if (this.failed || !this.motion.isValidThisFrame() || mainColor == null) {
			return;
		}

		try {
			this.ensureResources(mainColor);

			if (this.motion.historyResetThisFrame()) {
				this.resetHistory("motion history reset");
			}

			long frame = FsrFrameState.frameIndex();
			this.currentHudless = 1 - this.currentHudless;
			RenderSystem.getDevice().createCommandEncoder()
				.copyTextureToTexture(mainColor, this.hudless[this.currentHudless], 0, 0, 0, 0, 0, this.width, this.height);
			// After a reset (resize, camera cut) the other HUD-less buffer must not be interpolated against.
			this.previousHudlessFrame = this.historyResetFrame == frame ? -2 : this.hudlessFrame;
			this.hudlessFrame = frame;
		} catch (RuntimeException e) {
			this.fail("HUD-less capture", e);
		}
	}

	/**
	 * Called from Minecraft's frame loop right before the real frame is copied to the swapchain.
	 * Always advances the frame counter, even when frame generation is off.
	 */
	public static void beforeSwapchainBlit(com.mojang.renderpearl.api.device.GpuSurface surface) {
		var generator = active;

		try {
			if (generator != null && !generator.failed && !generator.destroyed) {
				generator.generateAndPresent(surface);
			}
		} finally {
			FsrFrameState.advanceFrame();
		}
	}

	private void generateAndPresent(com.mojang.renderpearl.api.device.GpuSurface surface) {
		long frame = FsrFrameState.frameIndex();
		boolean haveFrame = this.hudlessFrame == frame && this.motion.isValidThisFrame();

		if (!haveFrame) {
			// The world was not rendered this frame (menus, loading): next interpolation must not span the gap.
			if (this.framesSinceReset != 0) {
				this.resetHistory("no world frame");
			}

			return;
		}

		boolean consecutive = this.previousHudlessFrame == frame - 1;
		this.interpolateSettings.set(this.debugView.shaderId(), consecutive ? this.framesSinceReset : 0, 0, 0);
		this.framesSinceReset = consecutive ? this.framesSinceReset + 1 : 0;

		if (!consecutive && this.debugView == FsrDebugView.NONE) {
			return;
		}

		try {
			var mainColor = Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTexture();

			if (mainColor == null || mainColor.getWidth(0) != this.width || mainColor.getHeight(0) != this.height
				|| mainColor.getFormat() != this.colorFormat) {
				return;
			}

			var encoder = RenderSystem.getDevice().createCommandEncoder();
			encoder.copyTextureToTexture(mainColor, this.finalCopy, 0, 0, 0, 0, 0, this.width, this.height);
			this.interpolateProgram.draw(List.of(this.outputView), ViewportData.defaultValue(), 0, ++this.drawCounter);

			if (!this.loggedFirstFrame) {
				this.loggedFirstFrame = true;
				Iris.logger.info(LOG_PREFIX + "First generated frame ({}x{}, {}).", this.width, this.height, this.colorFormat);
			}

			if (this.debugView != FsrDebugView.NONE) {
				// Debug views replace the real frame so they are visible without flicker.
				encoder.copyTextureToTexture(this.output, mainColor, 0, 0, 0, 0, 0, this.width, this.height);
				return;
			}

			if (!this.present || !surface.isAcquired()) {
				return;
			}

			surface.blitFromTexture(encoder, this.outputView);
			RenderSystem.getDevice().createCommandEncoder().submit();
			surface.present();
			long generatedPresentNanos = System.nanoTime();
			this.pace(generatedPresentNanos);

			try {
				surface.acquireNextTexture();
			} catch (com.mojang.renderpearl.api.device.SurfaceException e) {
				// Vanilla checks isAcquired() before its own blit/present and recovers next frame.
				Iris.logger.warn(LOG_PREFIX + "Could not re-acquire the swapchain after a generated frame; skipping this real frame.", e);
			}
		} catch (RuntimeException e) {
			this.fail("interpolation/present", e);
		}
	}

	private void pace(long generatedPresentNanos) {
		long now = generatedPresentNanos;

		if (this.lastRealPresentNanos != 0) {
			// lastRealPresentNanos is taken after the previous sleep, so this is pure render work.
			double work = Math.max(0, (now - this.lastRealPresentNanos) / 1.0e9);
			this.smoothedWorkSeconds = this.smoothedWorkSeconds < 0 ? work : this.smoothedWorkSeconds * 0.9 + work * 0.1;
		}

		if (this.pacing == FsrFramePacing.SLEEP && this.smoothedWorkSeconds > 0) {
			// Show the generated frame for about half a real frame (capped at 25 ms) before the real one.
			long sleepNanos = (long) (Math.min(this.smoothedWorkSeconds * 0.5, 0.025) * 1.0e9);
			long deadline = now + sleepNanos;

			while (System.nanoTime() < deadline) {
				long remaining = deadline - System.nanoTime();

				if (remaining > 2_000_000L) {
					try {
						Thread.sleep((remaining - 1_000_000L) / 1_000_000L);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						break;
					}
				} else {
					Thread.onSpinWait();
				}
			}
		}

		this.lastRealPresentNanos = System.nanoTime();
	}

	private void ensureResources(GpuTexture mainColor) {
		int w = mainColor.getWidth(0);
		int h = mainColor.getHeight(0);

		if (w == this.width && h == this.height && mainColor.getFormat() == this.colorFormat && this.output != null) {
			return;
		}

		this.closeTextures();
		var device = RenderSystem.getDevice();
		this.width = w;
		this.height = h;
		this.colorFormat = mainColor.getFormat();

		for (int i = 0; i < 2; i++) {
			this.hudless[i] = device.createTexture("Iris FSR3 FG HUD-less " + i, FsrMotionVectors.COLOR_USAGE, this.colorFormat, w, h, 1, 1);
			this.hudlessViews[i] = device.createTextureView(this.hudless[i]);
		}

		this.finalCopy = device.createTexture("Iris FSR3 FG presented copy", FsrMotionVectors.COLOR_USAGE, this.colorFormat, w, h, 1, 1);
		this.finalCopyView = device.createTextureView(this.finalCopy);
		this.output = device.createTexture("Iris FSR3 FG output", FsrMotionVectors.COLOR_USAGE, this.colorFormat, w, h, 1, 1);
		this.outputView = device.createTextureView(this.output);

		this.resetHistory("resize");
		Iris.logger.info(LOG_PREFIX + "Allocated {}x{} frame generation targets (color {}).", w, h, this.colorFormat);
	}

	private void resetHistory(String reason) {
		this.previousHudlessFrame = -2;
		this.framesSinceReset = 0;
		this.historyResetFrame = FsrFrameState.frameIndex();
		Iris.logger.debug(LOG_PREFIX + "History reset (" + reason + ").");
	}

	private void fail(String stage, RuntimeException e) {
		this.failed = true;

		if (!this.loggedFailure) {
			this.loggedFailure = true;
			Iris.logger.error(LOG_PREFIX + "The {} failed; frame generation is disabled until the shader pack is reloaded.", stage, e);
		}
	}

	private void closeTextures() {
		for (var view : new GpuTextureView[]{this.hudlessViews[0], this.hudlessViews[1], this.finalCopyView, this.outputView}) {
			if (view != null) {
				view.close();
			}
		}

		for (var texture : new GpuTexture[]{this.hudless[0], this.hudless[1], this.finalCopy, this.output}) {
			if (texture != null) {
				texture.close();
			}
		}

		this.finalCopyView = this.outputView = null;
		this.hudlessViews[0] = this.hudlessViews[1] = null;
		this.finalCopy = this.output = null;
		this.hudless[0] = this.hudless[1] = null;
	}

	public void destroy() {
		if (this.destroyed) {
			return;
		}

		this.destroyed = true;

		if (active == this) {
			active = null;
		}

		this.interpolateProgram.destroy();
		this.closeTextures();
	}
}
