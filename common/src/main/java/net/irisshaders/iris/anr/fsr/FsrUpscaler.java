package net.irisshaders.iris.anr.fsr;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
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
import net.irisshaders.iris.mixin.GameRendererAccessor;
import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.irisshaders.iris.pipeline.programs.CompositeProgram;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.samplers.PackSamplers;
import net.irisshaders.iris.targets.ColorAttachmentPlan;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * FSR 2/3-style temporal upscaling, ported to Iris fullscreen passes (OpenGL and Vulkan, Windows and Linux, no
 * native code).
 *
 * <p>Per frame, when {@code fsrUpscaling=true} and a shader pack is active:</p>
 * <ol>
 *     <li>{@link #beforeRenderLevel} (GameRenderer.render, just before renderLevel): swap Minecraft's main render
 *     target for a lower-resolution one ({@code fsrUpscalingQuality}), so the world and every pack buffer (which
 *     Iris sizes from the main target) render at render resolution, and offset the projection by a Halton jitter.</li>
 *     <li>After the pack's final pass: {@link FsrMotionVectors} (un-jittered, render resolution), then
 *     {@link #upscaleAndRestore()}: the ported FSR 2 accumulate pass (reproject, Lanczos upsample, rectify, accumulate)
 *     into a display-resolution history, then RCAS into the full-resolution main target, which is swapped back
 *     before the hand overlay, screen effects, color-space pass and GUI.</li>
 *     <li>{@link #afterRenderLevel}: undo the jitter and, if the upscale did not run, restore the main target.</li>
 * </ol>
 */
public final class FsrUpscaler {
	private static final String LOG_PREFIX = "[FSR-SR] ";

	@Nullable
	private static FsrUpscaler active;

	// Main-target swap state. Static because the pipeline can be replaced in the middle of a frame.
	@Nullable
	private static LowResTarget lowResTarget;
	@Nullable
	private static RenderTarget swappedOutTarget;
	private static final Matrix4f unjitteredProjection = new Matrix4f();
	private static boolean projectionJittered;
	private static long jitterSequence;
	private static float jitterPixelX;
	private static float jitterPixelY;
	private static boolean loggedSwapLeak;
	private static boolean loggedFirstSwap;

	private final FsrMotionVectors motion;
	private final FsrUpscalingQuality quality;
	private final float sharpness;
	private final CompositeProgram accumulateProgram;
	private final CompositeProgram rcasProgram;

	private final GpuTexture[] history = new GpuTexture[2];
	private final GpuTextureView[] historyViews = new GpuTextureView[2];
	private int currentHistory;
	private int historyWidth = -1;
	private int historyHeight = -1;
	private long lastUpscaledFrame = Long.MIN_VALUE;
	private int framesSinceReset;
	private long drawCounter;

	private final Vector4f sizes = new Vector4f();
	private final Vector4f params = new Vector4f();
	private final Vector4f rcasParams = new Vector4f();

	private boolean loggedFirstFrame;
	private boolean failed;
	private boolean destroyed;

	private FsrUpscaler(PackSamplers samplers, FsrMotionVectors motion, IrisConfig config) {
		this.motion = motion;
		this.quality = config.getFsrUpscalingQuality();
		this.sharpness = config.getFsrUpscalingSharpness();

		String vertex = FsrShaders.read("/fsr3fg/fullscreen.vsh");
		var accumulateOutputs = new ColorAttachmentPlan.Builder().program("fsr2_accumulate", new int[]{0}, Set.of()).build(1)
			.program("fsr2_accumulate");
		this.accumulateProgram = new CompositeProgram(samplers, "fsr2_accumulate",
			Map.of(PatchShaderType.VERTEX, vertex, PatchShaderType.FRAGMENT, FsrShaders.read("/fsr3fg/fsr2_accumulate.fsh")),
			accumulateOutputs, Collections.singletonList(null));
		CompositeProgram rcas = null;

		try {
			this.accumulateProgram.getUniforms().uniform4f(UniformUpdateFrequency.CUSTOM, "fsrSizes", () -> this.sizes);
			this.accumulateProgram.getUniforms().uniform4f(UniformUpdateFrequency.CUSTOM, "fsrParams", () -> this.params);
			var accumulateSamplers = this.accumulateProgram.getSamplers();
			accumulateSamplers.addDrawTextureSampler(new TextureBinding("fsr2/color", FsrUpscaler::lowResColorView, () -> GlSampler.NEAREST),
				false, "fsrColor");
			accumulateSamplers.addDrawTextureSampler(new TextureBinding("fsr2/motion", this::motionView, () -> GlSampler.NEAREST), false,
				"fsrMotion");
			accumulateSamplers.addDrawTextureSampler(new TextureBinding("fsr2/depth", this::depthView, () -> GlSampler.NEAREST), false,
				"fsrDepth");
			accumulateSamplers.addDrawTextureSampler(
				new TextureBinding("fsr2/history", () -> this.historyViews[1 - this.currentHistory], () -> GlSampler.NEAREST), false,
				"fsrHistory");
			accumulateSamplers.finish();

			var rcasOutputs = new ColorAttachmentPlan.Builder().program("fsr2_rcas", new int[]{0}, Set.of()).build(1).program("fsr2_rcas");
			rcas = new CompositeProgram(samplers, "fsr2_rcas",
				Map.of(PatchShaderType.VERTEX, vertex, PatchShaderType.FRAGMENT, FsrShaders.read("/fsr3fg/fsr2_rcas.fsh")), rcasOutputs,
				Collections.singletonList(null));
			rcas.getUniforms().uniform4f(UniformUpdateFrequency.CUSTOM, "fsrRcas", () -> this.rcasParams);
			var rcasSamplers = rcas.getSamplers();
			rcasSamplers.addDrawTextureSampler(
				new TextureBinding("fsr2/upscaled", () -> this.historyViews[this.currentHistory], () -> GlSampler.NEAREST), false,
				"fsrInput");
			rcasSamplers.finish();
		} catch (RuntimeException | Error e) {
			this.accumulateProgram.destroy();

			if (rcas != null) {
				rcas.destroy();
			}

			throw e;
		}

		this.rcasProgram = rcas;
	}

	/** Created for a new shader pipeline when {@code fsrUpscaling=true}; null otherwise or if setup failed. */
	@Nullable
	public static FsrUpscaler createIfEnabled(PackSamplers samplers, @Nullable FsrMotionVectors motion) {
		IrisConfig config = Iris.getIrisConfig();

		if (config == null || !config.isFsrUpscalingEnabled()) {
			return null;
		}

		if (motion == null) {
			Iris.logger.error(LOG_PREFIX + "No motion vectors are available; upscaling is disabled for this shader pack.");
			return null;
		}

		try {
			var upscaler = new FsrUpscaler(samplers, motion, config);
			active = upscaler;
			Iris.logger.info(LOG_PREFIX + "Enabled on the {} backend (quality={}, {}x per axis, sharpness={}).",
				RenderSystem.getDevice().getDeviceInfo().backendName(), upscaler.quality.configName(), upscaler.quality.ratio(),
				upscaler.sharpness);
			return upscaler;
		} catch (RuntimeException e) {
			Iris.logger.error(LOG_PREFIX + "Setup failed; upscaling is disabled for this shader pack.", e);
			return null;
		}
	}

	/** The upscaler of the current shader pipeline, or null. */
	@Nullable
	static FsrUpscaler activeUpscaler() {
		return active;
	}

	/** Why no frame was upscaled recently, for the F3 screen and the status log. */
	String idleReason() {
		if (this.failed) {
			return "failed, see the log";
		}

		if (this.motion.hasFailed()) {
			return "motion vectors failed, see the log";
		}

		if (!Iris.isPackInUseQuick()) {
			return "no shader pack in use";
		}

		return "no world rendered";
	}

	/** True while the world is being rendered into the low-resolution target. */
	public static boolean isRenderingLowRes() {
		return swappedOutTarget != null;
	}

	/**
	 * For renderers that cached the main target before upscaling started (the vanilla sky renderer): returns the
	 * target the world is currently being rendered into.
	 */
	public static RenderTarget remapMainTarget(RenderTarget target) {
		// The sky renderer is (re)created inside renderLevel(), so it can cache either target. Always hand it the target
		// the world is being rendered into right now; an FSR target it cached may since have been destroyed (shader pack
		// toggled off, pipeline recreated), which used to crash with a null color texture.
		if (target instanceof LowResTarget || (swappedOutTarget != null && target == swappedOutTarget)) {
			RenderTarget current = Minecraft.getInstance().gameRenderer.mainRenderTarget();
			return current != null && current.getColorTextureView() != null ? current : target;
		}

		return target;
	}

	/**
	 * For renderers created while the world renders (the vanilla sky renderer): the full-resolution main target, never
	 * the temporary FSR one, so nothing caches a target FSR may destroy later.
	 */
	public static RenderTarget unswappedMainTarget(RenderTarget target) {
		return swappedOutTarget != null && target instanceof LowResTarget ? swappedOutTarget : target;
	}

	/** The temporary render-resolution main target, typed so it can be recognised wherever it was cached. */
	private static final class LowResTarget extends TextureTarget {
		private LowResTarget(int width, int height, GpuFormat colorFormat, GpuFormat depthFormat) {
			super("Iris FSR render-resolution main", width, height, colorFormat, depthFormat);
		}
	}

	/** GameRenderer.render, right before renderLevel(). Does nothing unless an upscaler is active. */
	public static void beforeRenderLevel(GameRenderer gameRenderer) {
		FsrFrameState.setJitter(0, 0);
		jitterPixelX = jitterPixelY = 0;
		var upscaler = active;
		RenderTarget full = gameRenderer.mainRenderTarget();

		if (full instanceof LowResTarget) {
			// Should never happen (the swap is always undone after renderLevel); never stack swaps.
			return;
		}

		if (upscaler == null || upscaler.failed || upscaler.destroyed || upscaler.motion.hasFailed() || swappedOutTarget != null || full.getColorTexture() == null
			|| full.getDepthTexture() == null || !Iris.isPackInUseQuick()) {
			boolean hadLowRes = lowResTarget != null;

			if (upscaler == null || upscaler.destroyed) {
				// Upscaling off, shader pack off or pipeline gone: free the render-resolution target.
				releaseLowResTarget();
			}

			if (hadLowRes || upscaler != null) {
				syncEntityOutline(full.width, full.height);
			}

			return;
		}

		try {
			int displayWidth = full.width;
			int displayHeight = full.height;
			int renderWidth = upscaler.quality.renderSize(displayWidth);
			int renderHeight = upscaler.quality.renderSize(displayHeight);
			GpuFormat colorFormat = full.getColorTexture().getFormat();
			GpuFormat depthFormat = full.getDepthTexture().getFormat();

			if (lowResTarget == null || lowResTarget.getColorTexture() == null || lowResTarget.getDepthTexture() == null
				|| lowResTarget.getColorTexture().getFormat() != colorFormat || lowResTarget.getDepthTexture().getFormat() != depthFormat) {
				releaseLowResTarget();
				lowResTarget = new LowResTarget(renderWidth, renderHeight, colorFormat, depthFormat);
			} else if (lowResTarget.width != renderWidth || lowResTarget.height != renderHeight) {
				lowResTarget.resize(renderWidth, renderHeight);
			}

			RenderSystem.getDevice().createCommandEncoder()
				.clearColorAndDepthTextures(lowResTarget.getColorTexture(), new Vector4f(0, 0, 0, 1), lowResTarget.getDepthTexture(), 0.0);

			// Halton jitter, applied as P' = T(jitter) * P to the camera projection that renderLevel() copies.
			float[] pixel = new float[2];
			float[] ndc = new float[2];
			FsrJitter.offset(jitterSequence++, FsrJitter.phaseCount(renderWidth, displayWidth), pixel);
			FsrJitter.toClipSpace(pixel, renderWidth, renderHeight, ndc);
			Matrix4f projection = gameRenderer.gameRenderState().levelRenderState.cameraRenderState.projectionMatrix;
			unjitteredProjection.set(projection);
			projection.set(new Matrix4f().translation(ndc[0], ndc[1], 0).mul(unjitteredProjection));
			projectionJittered = true;
			jitterPixelX = pixel[0];
			jitterPixelY = pixel[1];
			FsrFrameState.setJitter(ndc[0], ndc[1]);

			swappedOutTarget = full;
			((GameRendererAccessor) gameRenderer).iris$setMainRenderTarget(lowResTarget);
			syncEntityOutline(renderWidth, renderHeight);

			if (!loggedFirstSwap) {
				loggedFirstSwap = true;
				Iris.logger.info(LOG_PREFIX + "Rendering the world at {}x{} for a {}x{} display.", renderWidth, renderHeight, displayWidth,
					displayHeight);
			}
		} catch (RuntimeException e) {
			undoJitter(gameRenderer);
			restoreMainTarget(gameRenderer);
			upscaler.fail("render-resolution setup", e);
		}
	}

	/** GameRenderer.render, right after renderLevel(). Safety net: never leave the low-res target or jitter behind. */
	public static void afterRenderLevel(GameRenderer gameRenderer) {
		undoJitter(gameRenderer);

		if (swappedOutTarget != null) {
			if (!loggedSwapLeak) {
				loggedSwapLeak = true;
				Iris.logger.warn(LOG_PREFIX + "The upscale pass did not run this frame (pipeline changed or failed); restoring the "
					+ "full-resolution target.");
			}

			RenderTarget full = swappedOutTarget;
			restoreMainTarget(gameRenderer);
			syncEntityOutline(full.width, full.height);
		}

		FsrFrameState.setJitter(0, 0);
	}

	private static void undoJitter(GameRenderer gameRenderer) {
		if (projectionJittered) {
			gameRenderer.gameRenderState().levelRenderState.cameraRenderState.projectionMatrix.set(unjitteredProjection);
			projectionJittered = false;
		}
	}

	private static void restoreMainTarget(GameRenderer gameRenderer) {
		if (swappedOutTarget != null) {
			((GameRendererAccessor) gameRenderer).iris$setMainRenderTarget(swappedOutTarget);
			swappedOutTarget = null;
		}
	}

	/** The glowing-entity outline target must match the size the world is rendered at. */
	private static void syncEntityOutline(int width, int height) {
		var levelRenderer = Minecraft.getInstance().levelRenderer;

		if (levelRenderer == null) {
			return;
		}

		RenderTarget outline = ((LevelRendererAccessor) levelRenderer).getEntityOutlineTarget();

		if (outline != null && (outline.width != width || outline.height != height)) {
			outline.resize(width, height);
		}
	}

	private static void releaseLowResTarget() {
		if (lowResTarget != null && swappedOutTarget == null) {
			lowResTarget.destroyBuffers();
			lowResTarget = null;
		}
	}

	@Nullable
	private static GpuTextureView lowResColorView() {
		return lowResTarget == null ? null : lowResTarget.getColorTextureView();
	}

	@Nullable
	private GpuTextureView motionView() {
		return this.motion.view();
	}

	@Nullable
	private GpuTextureView depthView() {
		return this.motion.depthView();
	}

	/**
	 * After the pack's final pass and {@link FsrMotionVectors#render()}: upscale the render-resolution image into the
	 * full-resolution main target and swap it back in. Always restores the main target, even on failure.
	 */
	public void upscaleAndRestore() {
		var gameRenderer = Minecraft.getInstance().gameRenderer;
		RenderTarget full = swappedOutTarget;

		if (!this.destroyed) {
			// Iris keeps one pipeline per dimension alive; the one rendering decides the next frame's settings.
			active = this;
		}

		if (full == null) {
			return;
		}

		try {
			if (this.failed || this.destroyed || lowResTarget == null || !this.motion.isValidThisFrame()) {
				this.blitFallback(full);
				return;
			}

			int displayWidth = full.width;
			int displayHeight = full.height;
			this.ensureHistory(displayWidth, displayHeight);

			long frame = FsrFrameState.frameIndex();

			if (this.lastUpscaledFrame != frame - 1 || this.motion.historyResetThisFrame()) {
				this.framesSinceReset = 0;
			}

			this.currentHistory = 1 - this.currentHistory;
			this.sizes.set(lowResTarget.width, lowResTarget.height, displayWidth, displayHeight);
			this.params.set(jitterPixelX, jitterPixelY, this.framesSinceReset, 0);
			// FSR 2: sharpness 0..1 maps to 2..0 stops of RCAS attenuation; 0 disables sharpening.
			this.rcasParams.set(this.sharpness <= 0 ? 0 : (float) Math.pow(2.0, -(2.0 - 2.0 * this.sharpness)), 0, 0, 0);

			this.accumulateProgram.draw(List.of(this.historyViews[this.currentHistory]), ViewportData.defaultValue(), 0, ++this.drawCounter);
			this.rcasProgram.draw(List.of(full.getColorTextureView()), ViewportData.defaultValue(), 0, ++this.drawCounter);

			this.lastUpscaledFrame = frame;
			this.framesSinceReset++;
			FsrStats.onUpscaledFrame(lowResTarget.width, lowResTarget.height, displayWidth, displayHeight, this.quality.configName());

			if (!this.loggedFirstFrame) {
				this.loggedFirstFrame = true;
				Iris.logger.info(LOG_PREFIX + "First upscaled frame: {}x{} -> {}x{}.", lowResTarget.width, lowResTarget.height, displayWidth,
					displayHeight);
			}
		} catch (RuntimeException e) {
			this.fail("upscale pass", e);
			this.blitFallback(full);
		} finally {
			restoreMainTarget(gameRenderer);
			syncEntityOutline(full.width, full.height);
		}
	}

	/** Without a usable upscale, at least stretch the render-resolution image so the frame is not blank. */
	private void blitFallback(RenderTarget full) {
		if (lowResTarget == null || full.getColorTextureView() == null) {
			return;
		}

		try {
			lowResTarget.blitAndBlendToTexture(full.getColorTextureView(), full.getDepthTextureView());
		} catch (RuntimeException e) {
			Iris.logger.debug(LOG_PREFIX + "Fallback blit failed: " + e);
		}
	}

	private void ensureHistory(int width, int height) {
		if (width == this.historyWidth && height == this.historyHeight && this.history[0] != null) {
			return;
		}

		this.closeHistory();
		var device = RenderSystem.getDevice();

		for (int i = 0; i < 2; i++) {
			this.history[i] = device.createTexture("Iris FSR upscale history " + i, FsrMotionVectors.COLOR_USAGE, GpuFormat.RGBA16_FLOAT, width,
				height, 1, 1);
			this.historyViews[i] = device.createTextureView(this.history[i]);
		}

		this.historyWidth = width;
		this.historyHeight = height;
		this.lastUpscaledFrame = Long.MIN_VALUE;
		Iris.logger.info(LOG_PREFIX + "Allocated {}x{} upscale history.", width, height);
	}

	private void fail(String stage, RuntimeException e) {
		if (!this.failed) {
			this.failed = true;
			Iris.logger.error(LOG_PREFIX + "The {} failed; upscaling is disabled until the shader pack is reloaded (native resolution "
				+ "from the next frame).", stage, e);
		}
	}

	private void closeHistory() {
		for (int i = 0; i < 2; i++) {
			if (this.historyViews[i] != null) {
				this.historyViews[i].close();
			}

			if (this.history[i] != null) {
				this.history[i].close();
			}

			this.historyViews[i] = null;
			this.history[i] = null;
		}

		this.historyWidth = this.historyHeight = -1;
	}

	public void destroy() {
		if (this.destroyed) {
			return;
		}

		this.destroyed = true;

		if (active == this) {
			active = null;
			// Only drop the shared low-res target when no frame is using it; it is recreated on demand.
			releaseLowResTarget();
		}

		this.accumulateProgram.destroy();
		this.rcasProgram.destroy();
		this.closeHistory();
	}
}
