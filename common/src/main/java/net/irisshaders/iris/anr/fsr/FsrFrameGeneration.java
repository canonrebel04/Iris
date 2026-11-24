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
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.uniforms.CameraUniforms;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.joml.Vector4f;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * FSR 3-style frame generation implemented with Iris's own fullscreen passes, so it runs on both the OpenGL
 * and Vulkan backends, on Windows and Linux, with no native library.
 *
 * <p>Per real frame N:</p>
 * <ol>
 *     <li>{@link #afterFinalPass()} (after the shader pack's final pass, depth still valid): camera-reprojection
 *     motion vectors + depth disocclusion into {@link #motionTexture}, then keep a copy of depth for frame N+1.</li>
 *     <li>{@link #captureHudless(GpuTexture)} (after Iris's color-space pass, before the GUI is drawn): copy the
 *     HUD-less scene color.</li>
 *     <li>{@link #beforeSwapchainBlit} (from Minecraft's frame loop, after the GUI): interpolate a frame halfway
 *     between N-1 and N, present it, re-acquire the swapchain, and let vanilla present the real frame N.</li>
 * </ol>
 *
 * <p>Everything is created only when {@code fsr3FrameGen=true}; otherwise no instance exists and no hook does
 * anything.</p>
 */
public final class FsrFrameGeneration {
	private static final String LOG_PREFIX = "[FSR3-FG] ";
	private static final int COLOR_USAGE = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING
		| GpuTexture.USAGE_RENDER_ATTACHMENT;
	/** Camera moves larger than this (blocks) between frames are treated as a cut, and history is reset. */
	private static final double CAMERA_CUT_DISTANCE = 32.0;

	@Nullable
	private static FsrFrameGeneration active;
	/** Counts calls to {@link #beforeSwapchainBlit}, i.e. real frames. */
	private static long frameIndex;

	private final RenderTargets renderTargets;
	private final CompositeProgram motionProgram;
	private final CompositeProgram interpolateProgram;
	private final FsrFramePacing pacing;
	private final FsrDebugView debugView;
	private final boolean present;

	// Current-frame inputs for the draw bindings. Draw bindings are resolved on every draw.
	private GpuTextureView depthView;
	private GpuTextureView noHandDepthView;
	private GpuTexture depthViewSource;
	private GpuTexture noHandViewSource;

	private GpuTexture motionTexture;
	private GpuTextureView motionView;
	private GpuTexture previousDepth;
	private GpuTextureView previousDepthView;
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
	private GpuFormat depthFormat;

	private final Matrix4f reprojection = new Matrix4f();
	private final Vector4f motionParams = new Vector4f();
	private final Vector4f interpolateSettings = new Vector4f();
	private final Matrix4f previousView = new Matrix4f();
	private final Matrix4f previousProjection = new Matrix4f();
	private final Vector3d previousCamera = new Vector3d();
	private boolean hasPreviousCamera;
	private boolean hasPreviousDepth;
	private boolean handDepthValid;

	private long motionFrame = -1;
	private long hudlessFrame = -1;
	private long previousHudlessFrame = -2;
	private long historyResetFrame = -1;
	private int framesSinceReset;
	private long drawCounter;

	private double smoothedWorkSeconds = -1;
	private long lastRealPresentNanos;
	private boolean loggedFirstFrame;
	private boolean loggedMissingHook;
	private int finalPassesWithoutPresentHook;
	private boolean loggedFailure;
	private boolean failed;
	private boolean destroyed;

	private FsrFrameGeneration(PackSamplers samplers, RenderTargets renderTargets, IrisConfig config) {
		this.renderTargets = renderTargets;
		this.pacing = config.getFsr3FrameGenPacing();
		this.debugView = config.getFsr3FrameGenDebugView();
		this.present = config.shouldPresentFsr3GeneratedFrames();

		var cleanup = new ArrayList<Runnable>();

		try {
			String vertex = readShader("/fsr3fg/fullscreen.vsh");

			var motionOutputs = new ColorAttachmentPlan.Builder().program("fsr3fg_motion", new int[]{0}, Set.of()).build(1)
				.program("fsr3fg_motion");
			this.motionProgram = new CompositeProgram(samplers, "fsr3fg_motion",
				Map.of(PatchShaderType.VERTEX, vertex, PatchShaderType.FRAGMENT, readShader("/fsr3fg/motion.fsh")), motionOutputs,
				Collections.singletonList(null));
			cleanup.add(this.motionProgram::destroy);
			this.motionProgram.getUniforms().uniformMatrix(UniformUpdateFrequency.CUSTOM, "fgReproject", () -> this.reprojection);
			this.motionProgram.getUniforms().uniform4f(UniformUpdateFrequency.CUSTOM, "fgParams", () -> this.motionParams);
			var motionSamplers = this.motionProgram.getSamplers();
			motionSamplers.addDrawTextureSampler(new TextureBinding("fsr3fg/depth", () -> this.depthView, () -> GlSampler.NEAREST), false,
				"fgDepth");
			motionSamplers.addDrawTextureSampler(new TextureBinding("fsr3fg/depthNoHand", () -> this.noHandDepthView, () -> GlSampler.NEAREST),
				false, "fgDepthNoHand");
			motionSamplers.addDrawTextureSampler(
				new TextureBinding("fsr3fg/previousDepth", () -> this.previousDepthView, () -> GlSampler.NEAREST), false, "fgPrevDepth");
			motionSamplers.finish();

			var interpolateOutputs = new ColorAttachmentPlan.Builder().program("fsr3fg_interpolate", new int[]{0}, Set.of()).build(1)
				.program("fsr3fg_interpolate");
			this.interpolateProgram = new CompositeProgram(samplers, "fsr3fg_interpolate",
				Map.of(PatchShaderType.VERTEX, vertex, PatchShaderType.FRAGMENT, readShader("/fsr3fg/interpolate.fsh")),
				interpolateOutputs, Collections.singletonList(null));
			cleanup.add(this.interpolateProgram::destroy);
			this.interpolateProgram.getUniforms().uniform4f(UniformUpdateFrequency.CUSTOM, "fgSettings", () -> this.interpolateSettings);
			var interpolateSamplers = this.interpolateProgram.getSamplers();
			interpolateSamplers.addDrawTextureSampler(
				new TextureBinding("fsr3fg/previousColor", () -> this.hudlessViews[1 - this.currentHudless], () -> GlSampler.NEAREST), false,
				"fgPrevColor");
			interpolateSamplers.addDrawTextureSampler(
				new TextureBinding("fsr3fg/currentColor", () -> this.hudlessViews[this.currentHudless], () -> GlSampler.NEAREST), false,
				"fgCurrColor");
			interpolateSamplers.addDrawTextureSampler(new TextureBinding("fsr3fg/motion", () -> this.motionView, () -> GlSampler.NEAREST),
				false, "fgMotion");
			interpolateSamplers.addDrawTextureSampler(new TextureBinding("fsr3fg/finalColor", () -> this.finalCopyView, () -> GlSampler.NEAREST),
				false, "fgFinalColor");
			interpolateSamplers.finish();
		} catch (RuntimeException | Error failure) {
			for (var close : cleanup.reversed()) {
				try {
					close.run();
				} catch (RuntimeException | Error e) {
					failure.addSuppressed(e);
				}
			}

			throw failure;
		}
	}

	/**
	 * Creates the frame generator for a new shader pipeline when {@code fsr3FrameGen=true}. Must be called while the
	 * pipeline is still registering its samplers (before the sampler layout is finalized).
	 *
	 * @return null when disabled or when setup failed (Iris then renders exactly as without frame generation)
	 */
	@Nullable
	public static FsrFrameGeneration createIfEnabled(PackSamplers samplers, RenderTargets renderTargets) {
		IrisConfig config = Iris.getIrisConfig();

		if (config == null) {
			return null;
		}

		FsrUpscaling.logStatus(config);

		if (!config.isFsr3FrameGenEnabled()) {
			return null;
		}

		try {
			var generator = new FsrFrameGeneration(samplers, renderTargets, config);
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

	/**
	 * Camera-reprojection motion vectors for the current frame (RGBA16F: rg = prevUv - uv, b = disocclusion,
	 * a = hand), or null if none were produced this frame. Used by the ANR layer when the pack has none.
	 */
	@Nullable
	public static GpuTextureView currentMotionVectors() {
		var generator = active;

		if (generator == null || generator.failed || generator.motionFrame != frameIndex) {
			return null;
		}

		return generator.motionView;
	}

	/** Called from {@code IrisRenderingPipeline.beginHand()} right after the pre-hand depth copy. */
	public void onPreHandDepthCopied() {
		this.handDepthValid = true;
	}

	/** Step 1: after the shader pack's final pass. */
	public void afterFinalPass() {
		if (this.failed || this.destroyed) {
			return;
		}

		if (frameIndex == 0 && ++this.finalPassesWithoutPresentHook > 30 && !this.loggedMissingHook) {
			this.loggedMissingHook = true;
			Iris.logger.warn(LOG_PREFIX + "The swapchain present hook (MixinMinecraft_FrameGeneration) never ran; generated frames "
				+ "cannot be shown on this Minecraft version.");
		}

		try {
			var main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
			var mainColor = main.getColorTexture();
			var depth = this.renderTargets.getDepthTexture();
			var noHand = this.renderTargets.getDepthTextureNoHand();
			Matrix4fc view = CapturedRenderingState.INSTANCE.getGbufferModelView();
			Matrix4fc projection = CapturedRenderingState.INSTANCE.getGbufferProjection();

			if (mainColor == null || depth == null || view == null || projection == null) {
				return;
			}

			this.ensureResources(mainColor, depth);
			this.updateDepthViews(depth, noHand);

			Vector3d camera = CameraUniforms.getUnshiftedCameraPosition();

			if (this.hasPreviousCamera && camera.distance(this.previousCamera) > CAMERA_CUT_DISTANCE) {
				this.resetHistory("camera cut");
			}

			this.buildReprojection(view, projection, camera);
			this.motionParams.set(this.previousProjection.m22(), this.previousProjection.m32(), this.hasPreviousDepth ? 1.0f : 0.0f,
				this.handDepthValid && noHand != null ? 1.0f : 0.0f);
			this.motionProgram.draw(List.of(this.motionView), ViewportData.defaultValue(), 0, ++this.drawCounter);

			RenderSystem.getDevice().createCommandEncoder()
				.copyTextureToTexture(depth, this.previousDepth, 0, 0, 0, 0, 0, this.width, this.height);
			this.hasPreviousDepth = true;

			this.previousView.set(view);
			this.previousProjection.set(projection);
			this.previousCamera.set(camera);
			this.hasPreviousCamera = true;
			this.motionFrame = frameIndex;
		} catch (RuntimeException e) {
			this.fail("motion vector pass", e);
		} finally {
			this.handDepthValid = false;
		}
	}

	/** Step 2: after the color-space pass, before the GUI. {@code mainColor} holds the HUD-less frame. */
	public void captureHudless(GpuTexture mainColor) {
		if (this.failed || this.destroyed || this.motionFrame != frameIndex || mainColor == null) {
			return;
		}

		try {
			if (mainColor.getWidth(0) != this.width || mainColor.getHeight(0) != this.height || mainColor.getFormat() != this.colorFormat) {
				return;
			}

			this.currentHudless = 1 - this.currentHudless;
			RenderSystem.getDevice().createCommandEncoder()
				.copyTextureToTexture(mainColor, this.hudless[this.currentHudless], 0, 0, 0, 0, 0, this.width, this.height);
			// After a reset (resize, camera cut) the other HUD-less buffer must not be interpolated against.
			this.previousHudlessFrame = this.historyResetFrame == frameIndex ? -2 : this.hudlessFrame;
			this.hudlessFrame = frameIndex;
		} catch (RuntimeException e) {
			this.fail("HUD-less capture", e);
		}
	}

	/**
	 * Step 3, called from Minecraft's frame loop right before the real frame is copied to the swapchain.
	 * Always advances the frame counter, even when frame generation is off.
	 */
	public static void beforeSwapchainBlit(com.mojang.renderpearl.api.device.GpuSurface surface) {
		var generator = active;

		try {
			if (generator != null && !generator.failed && !generator.destroyed) {
				generator.generateAndPresent(surface);
			}
		} finally {
			frameIndex++;
		}
	}

	private void generateAndPresent(com.mojang.renderpearl.api.device.GpuSurface surface) {
		boolean haveFrame = this.hudlessFrame == frameIndex && this.motionFrame == frameIndex;

		if (!haveFrame) {
			// The world was not rendered this frame (menus, loading): next interpolation must not span the gap.
			if (this.framesSinceReset != 0) {
				this.resetHistory("no world frame");
			}

			return;
		}

		boolean consecutive = this.previousHudlessFrame == frameIndex - 1;
		this.interpolateSettings.set(this.debugView.shaderId(), consecutive ? this.framesSinceReset : 0, 0, 0);
		this.framesSinceReset = consecutive ? this.framesSinceReset + 1 : 0;

		if (!consecutive && this.debugView == FsrDebugView.NONE) {
			return;
		}

		try {
			var main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
			var mainColor = main.getColorTexture();

			if (mainColor == null || mainColor.getWidth(0) != this.width || mainColor.getHeight(0) != this.height) {
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

	private void buildReprojection(Matrix4fc view, Matrix4fc projection, Vector3d camera) {
		if (!this.hasPreviousCamera) {
			this.previousView.set(view);
			this.previousProjection.set(projection);
			this.previousCamera.set(camera);
		}

		// current NDC -> current view -> eye/player space -> world-relative to the previous camera -> previous clip.
		Matrix4d currentInverse = new Matrix4d(projection).mul(new Matrix4d(view)).invert();
		Matrix4d previous = new Matrix4d(this.previousProjection).mul(new Matrix4d(this.previousView))
			.translate(camera.x - this.previousCamera.x, camera.y - this.previousCamera.y, camera.z - this.previousCamera.z);
		this.reprojection.set(previous.mul(currentInverse));
	}

	private void ensureResources(GpuTexture mainColor, GpuTexture depth) {
		int w = mainColor.getWidth(0);
		int h = mainColor.getHeight(0);

		if (w == this.width && h == this.height && mainColor.getFormat() == this.colorFormat && depth.getFormat() == this.depthFormat
			&& this.motionTexture != null) {
			return;
		}

		this.closeTextures();
		var device = RenderSystem.getDevice();
		this.width = w;
		this.height = h;
		this.colorFormat = mainColor.getFormat();
		this.depthFormat = depth.getFormat();

		this.motionTexture = device.createTexture("Iris FSR3 FG motion", COLOR_USAGE, GpuFormat.RGBA16_FLOAT, w, h, 1, 1);
		this.motionView = device.createTextureView(this.motionTexture);
		this.previousDepth = device.createTexture("Iris FSR3 FG previous depth", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
			this.depthFormat, w, h, 1, 1);
		this.previousDepthView = device.createTextureView(this.previousDepth, 0, 1);

		for (int i = 0; i < 2; i++) {
			this.hudless[i] = device.createTexture("Iris FSR3 FG HUD-less " + i, COLOR_USAGE, this.colorFormat, w, h, 1, 1);
			this.hudlessViews[i] = device.createTextureView(this.hudless[i]);
		}

		this.finalCopy = device.createTexture("Iris FSR3 FG presented copy", COLOR_USAGE, this.colorFormat, w, h, 1, 1);
		this.finalCopyView = device.createTextureView(this.finalCopy);
		this.output = device.createTexture("Iris FSR3 FG output", COLOR_USAGE, this.colorFormat, w, h, 1, 1);
		this.outputView = device.createTextureView(this.output);

		this.resetHistory("resize");
		Iris.logger.info(LOG_PREFIX + "Allocated {}x{} frame generation targets (color {}, depth {}).", w, h, this.colorFormat,
			this.depthFormat);
	}

	private void updateDepthViews(GpuTexture depth, @Nullable GpuTexture noHand) {
		var device = RenderSystem.getDevice();

		if (this.depthViewSource != depth) {
			if (this.depthView != null) {
				this.depthView.close();
			}

			this.depthView = device.createTextureView(depth, 0, 1);
			this.depthViewSource = depth;
		}

		GpuTexture handSource = noHand != null ? noHand : depth;

		if (this.noHandViewSource != handSource) {
			if (this.noHandDepthView != null) {
				this.noHandDepthView.close();
			}

			this.noHandDepthView = device.createTextureView(handSource, 0, 1);
			this.noHandViewSource = handSource;
		}
	}

	private void resetHistory(String reason) {
		this.hasPreviousDepth = false;
		this.hasPreviousCamera = false;
		this.previousHudlessFrame = -2;
		this.framesSinceReset = 0;
		this.historyResetFrame = frameIndex;
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
		for (var view : new GpuTextureView[]{this.motionView, this.previousDepthView, this.hudlessViews[0], this.hudlessViews[1],
			this.finalCopyView, this.outputView}) {
			if (view != null) {
				view.close();
			}
		}

		for (var texture : new GpuTexture[]{this.motionTexture, this.previousDepth, this.hudless[0], this.hudless[1], this.finalCopy,
			this.output}) {
			if (texture != null) {
				texture.close();
			}
		}

		this.motionView = this.previousDepthView = this.finalCopyView = this.outputView = null;
		this.hudlessViews[0] = this.hudlessViews[1] = null;
		this.motionTexture = this.previousDepth = this.finalCopy = this.output = null;
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

		this.motionProgram.destroy();
		this.interpolateProgram.destroy();
		this.closeTextures();

		if (this.depthView != null) {
			this.depthView.close();
		}

		if (this.noHandDepthView != null) {
			this.noHandDepthView.close();
		}
	}

	private static String readShader(String resource) {
		try (var stream = Objects.requireNonNull(FsrFrameGeneration.class.getResourceAsStream(resource), resource)) {
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
