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
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.pipeline.programs.CompositeProgram;
import net.irisshaders.iris.pipeline.programs.ComputeProgram;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.samplers.PackSamplers;
import net.irisshaders.iris.targets.ColorAttachmentPlan;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3i;
import org.joml.Vector4f;

import java.util.ArrayList;
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
 *     <li>{@link #beforeSwapchainBlit} (from Minecraft's frame loop, after the GUI): build the FSR 3 game motion
 *     vector field and disocclusion mask (compute: fi_clear/fi_scatter/fi_resolve), interpolate a frame halfway between
 *     N-1 and N (interpolate.fsh), inpaint holes from a colour pyramid and restore the UI (fi_pyramid/fi_inpaint),
 *     present it, re-acquire the swapchain, and let vanilla present the real frame N.</li>
 * </ol>
 *
 * <p>Without compute shaders (or with {@code fsr3FrameGenVectorField=false}) the vector field is replaced by a
 * backward search on the motion vectors; everything else is the same.</p>
 *
 * <p>Everything is created only when {@code fsr3FrameGen=true}; otherwise no instance exists and no hook does
 * anything.</p>
 */
public final class FsrFrameGeneration {
	private static final String LOG_PREFIX = "[FSR3-FG] ";
	private static final int MAX_PYRAMID_LEVELS = 10;
	private static final int STORAGE_USAGE = net.irisshaders.iris.targets.RenderTarget.USAGE_STORAGE | GpuTexture.USAGE_TEXTURE_BINDING
		| GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC;

	@Nullable
	private static FsrFrameGeneration active;

	private final FsrMotionVectors motion;
	private final CompositeProgram interpolateProgram;
	private final CompositeProgram pyramidProgram;
	private final CompositeProgram inpaintProgram;
	// FSR 3 game motion vector field (null when disabled or unavailable)
	@Nullable
	private ComputeProgram clearProgram;
	@Nullable
	private ComputeProgram scatterProgram;
	@Nullable
	private ComputeProgram resolveProgram;
	private boolean vectorField;
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
	private GpuTexture interpolated;
	private GpuTextureView interpolatedView;
	private final GpuTexture[] pyramid = new GpuTexture[MAX_PYRAMID_LEVELS];
	private final GpuTextureView[] pyramidViews = new GpuTextureView[MAX_PYRAMID_LEVELS];
	private int pyramidLevels;
	private GpuTextureView pyramidSource;
	private int width = -1;
	private int height = -1;
	private GpuFormat colorFormat;

	private final Vector4f interpolateSettings = new Vector4f();
	private final Vector4f pyramidParams = new Vector4f();
	private final Vector4f inpaintParams = new Vector4f();
	private final Vector4f fieldSizes = new Vector4f();
	private final Vector4f fieldDepthParams = new Vector4f();

	// Render-resolution vector field resources
	private GpuTexture fieldX;
	private GpuTextureView fieldXView;
	private GpuTexture fieldY;
	private GpuTextureView fieldYView;
	private GpuTexture interpDepth;
	private GpuTextureView interpDepthView;
	private GpuTexture vectors;
	private GpuTextureView vectorsView;
	private GpuTexture fieldPrevDepth;
	private GpuTextureView fieldPrevDepthView;
	private int fieldWidth = -1;
	private int fieldHeight = -1;
	private GpuFormat fieldDepthFormat;
	private boolean hasFieldPrevDepth;
	private boolean loggedFieldFailure;

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
		var cleanup = new ArrayList<Runnable>();
		this.interpolateProgram = fullscreen(samplers, "fsr3fg_interpolate", vertex, "/fsr3fg/interpolate.fsh");
		cleanup.add(this.interpolateProgram::destroy);

		try {
			this.pyramidProgram = fullscreen(samplers, "fsr3fg_pyramid", vertex, "/fsr3fg/fi_pyramid.fsh");
			cleanup.add(this.pyramidProgram::destroy);
			this.inpaintProgram = fullscreen(samplers, "fsr3fg_inpaint", vertex, "/fsr3fg/fi_inpaint.fsh");
			cleanup.add(this.inpaintProgram::destroy);

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
			interpolateSamplers.addDrawTextureSampler(new TextureBinding("fsr3fg/vectors", () -> this.vectorsView != null ? this.vectorsView
				: this.motion.view(), () -> GlSampler.NEAREST), false, "fgVectors");
			interpolateSamplers.finish();

			this.pyramidProgram.getUniforms().uniform4f(UniformUpdateFrequency.CUSTOM, "fgPyramidParams", () -> this.pyramidParams);
			var pyramidSamplers = this.pyramidProgram.getSamplers();
			pyramidSamplers.addDrawTextureSampler(new TextureBinding("fsr3fg/pyramidSource", () -> this.pyramidSource, () -> GlSampler.NEAREST),
				false, "fgPyramidSource");
			pyramidSamplers.finish();

			this.inpaintProgram.getUniforms().uniform4f(UniformUpdateFrequency.CUSTOM, "fgInpaintParams", () -> this.inpaintParams);
			var inpaintSamplers = this.inpaintProgram.getSamplers();
			inpaintSamplers.addDrawTextureSampler(new TextureBinding("fsr3fg/interpolated", () -> this.interpolatedView, () -> GlSampler.NEAREST),
				false, "fgInterpolated");
			inpaintSamplers.addDrawTextureSampler(
				new TextureBinding("fsr3fg/inpaintCurrent", () -> this.hudlessViews[this.currentHudless], () -> GlSampler.NEAREST), false,
				"fgCurrColor");
			inpaintSamplers.addDrawTextureSampler(new TextureBinding("fsr3fg/finalColor", () -> this.finalCopyView, () -> GlSampler.NEAREST),
				false, "fgFinalColor");

			for (int i = 0; i < MAX_PYRAMID_LEVELS; i++) {
				final int level = i;
				inpaintSamplers.addDrawTextureSampler(new TextureBinding("fsr3fg/pyramid" + i, () -> this.pyramidView(level), () -> GlSampler.NEAREST),
					false, "fgPyramid" + i);
			}

			inpaintSamplers.finish();

			if (config.isFsr3FrameGenVectorFieldEnabled()) {
				this.createVectorFieldPrograms(samplers);
			}
		} catch (RuntimeException | Error e) {
			for (var close : cleanup.reversed()) {
				try {
					close.run();
				} catch (RuntimeException | Error suppressed) {
					e.addSuppressed(suppressed);
				}
			}

			throw e;
		}
	}

	private static CompositeProgram fullscreen(PackSamplers samplers, String name, String vertex, String fragment) {
		var outputs = new ColorAttachmentPlan.Builder().program(name, new int[]{0}, Set.of()).build(1).program(name);
		return new CompositeProgram(samplers, name, Map.of(PatchShaderType.VERTEX, vertex, PatchShaderType.FRAGMENT, FsrShaders.read(fragment)),
			outputs, Collections.singletonList(null));
	}

	/** Pyramid levels beyond the allocated count repeat the last level (the shader never reads them). */
	@Nullable
	private GpuTextureView pyramidView(int level) {
		if (this.pyramidLevels == 0) {
			return this.interpolatedView;
		}

		return this.pyramidViews[Math.min(level, this.pyramidLevels - 1)];
	}

	private void createVectorFieldPrograms(PackSamplers samplers) {
		ComputeProgram clear = null;
		ComputeProgram scatter = null;
		ComputeProgram resolve = null;

		try {
			clear = new ComputeProgram(samplers, "fsr3fg_fi_clear", FsrShaders.read("/fsr3fg/fi_clear.csh"));
			this.addFieldImages(clear);
			clear.getUniforms().uniform4f(UniformUpdateFrequency.CUSTOM, "fgSizes", () -> this.fieldSizes);
			clear.getSamplers().finish();

			scatter = new ComputeProgram(samplers, "fsr3fg_fi_scatter", FsrShaders.read("/fsr3fg/fi_scatter.csh"));
			this.addFieldImages(scatter);
			scatter.getUniforms().uniform4f(UniformUpdateFrequency.CUSTOM, "fgSizes", () -> this.fieldSizes);
			scatter.getUniforms().uniform4f(UniformUpdateFrequency.CUSTOM, "fgDepthParams", () -> this.fieldDepthParams);
			var scatterSamplers = scatter.getSamplers();
			scatterSamplers.addDrawTextureSampler(new TextureBinding("fsr3fg/fiMotion", this.motion::view, () -> GlSampler.NEAREST), false,
				"fgMotion");
			scatterSamplers.addDrawTextureSampler(new TextureBinding("fsr3fg/fiDepth", this.motion::depthCopyView, () -> GlSampler.NEAREST),
				false, "fgDepth");
			scatterSamplers.addDrawTextureSampler(
				new TextureBinding("fsr3fg/fiPrevColor", () -> this.hudlessViews[1 - this.currentHudless], () -> GlSampler.LINEAR), false,
				"fgPrevColor");
			scatterSamplers.addDrawTextureSampler(
				new TextureBinding("fsr3fg/fiCurrColor", () -> this.hudlessViews[this.currentHudless], () -> GlSampler.LINEAR), false,
				"fgCurrColor");
			scatterSamplers.finish();

			resolve = new ComputeProgram(samplers, "fsr3fg_fi_resolve", FsrShaders.read("/fsr3fg/fi_resolve.csh"));
			this.addFieldImages(resolve);
			resolve.getSamplers().addTextureImage(() -> this.vectorsView, InternalTextureFormat.RGBA16F, "fgVectors");
			resolve.getUniforms().uniform4f(UniformUpdateFrequency.CUSTOM, "fgSizes", () -> this.fieldSizes);
			resolve.getUniforms().uniform4f(UniformUpdateFrequency.CUSTOM, "fgDepthParams", () -> this.fieldDepthParams);
			var resolveSamplers = resolve.getSamplers();
			resolveSamplers.addDrawTextureSampler(new TextureBinding("fsr3fg/fiResolveMotion", this.motion::view, () -> GlSampler.NEAREST),
				false, "fgMotion");
			resolveSamplers.addDrawTextureSampler(new TextureBinding("fsr3fg/fiResolveDepth", this.motion::depthCopyView, () -> GlSampler.NEAREST),
				false, "fgDepth");
			resolveSamplers.addDrawTextureSampler(
				new TextureBinding("fsr3fg/fiPrevDepth", () -> this.fieldPrevDepthView, () -> GlSampler.NEAREST), false, "fgPrevDepth");
			resolveSamplers.finish();

			this.clearProgram = clear;
			this.scatterProgram = scatter;
			this.resolveProgram = resolve;
			this.vectorField = true;
		} catch (RuntimeException | Error e) {
			for (var program : new ComputeProgram[]{clear, scatter, resolve}) {
				if (program != null) {
					program.destroy();
				}
			}

			Iris.logger.warn(LOG_PREFIX + "The FSR 3 game motion vector field (compute shaders) is unavailable; using the backward "
				+ "motion-vector search instead.", e);
		}
	}

	private void addFieldImages(ComputeProgram program) {
		var images = program.getSamplers();
		images.addTextureImage(() -> this.fieldXView, InternalTextureFormat.R32UI, "fgFieldX");
		images.addTextureImage(() -> this.fieldYView, InternalTextureFormat.R32UI, "fgFieldY");
		images.addTextureImage(() -> this.interpDepthView, InternalTextureFormat.R32UI, "fgInterpDepth");
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
			Iris.logger.info(LOG_PREFIX + "Enabled on the {} backend (present={}, pacing={}, debugView={}, vectorField={}).",
				RenderSystem.getDevice().getDeviceInfo().backendName(), generator.present, generator.pacing.configName(),
				generator.debugView.configName(), generator.vectorField);
			return generator;
		} catch (RuntimeException e) {
			Iris.logger.error(LOG_PREFIX + "Setup failed; frame generation is disabled for this shader pack.", e);
			return null;
		}
	}

	/** Short state for the F3 screen and the status log. */
	String statusDescription() {
		if (this.failed) {
			return "failed (see the log)";
		}

		if (this.debugView != FsrDebugView.NONE) {
			return "debug view " + this.debugView.configName() + " (replaces the real frame, nothing extra presented)";
		}

		if (!this.present) {
			return "on, not presenting (fsr3FrameGenPresent=false)";
		}

		return "on (" + (this.vectorField ? "FSR 3 vector field" : "motion-vector search fallback") + ", pacing " + this.pacing.configName()
			+ ")";
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
			FsrStats.onRealFrame();
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

		try {
			this.interpolateAndPresent(surface, frame);
		} finally {
			// The vector field's disocclusion test needs the previous frame's depth.
			this.keepFieldDepth();
		}
	}

	private void interpolateAndPresent(com.mojang.renderpearl.api.device.GpuSurface surface, long frame) {
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

			boolean field = consecutive && this.buildVectorField();
			this.interpolateSettings.z = field ? 1 : 0;
			this.interpolateProgram.draw(List.of(this.interpolatedView), ViewportData.defaultValue(), 0, ++this.drawCounter);

			// Colour inpainting pyramid, then inpainting + UI restore into the presented image.
			for (int i = 0; i < this.pyramidLevels; i++) {
				this.pyramidSource = i == 0 ? this.interpolatedView : this.pyramidViews[i - 1];
				this.pyramidParams.set(i == 0 ? 1 : 0, 0, 0, 0);
				this.pyramidProgram.draw(List.of(this.pyramidViews[i]), ViewportData.defaultValue(), 0, ++this.drawCounter);
			}

			this.inpaintParams.set(this.pyramidLevels, 0, 0, 0);
			this.inpaintProgram.draw(List.of(this.outputView), ViewportData.defaultValue(), 0, ++this.drawCounter);

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
			FsrStats.onGeneratedFramePresented();
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

	/** Runs the FSR 3 game-vector-field passes. Returns false (search fallback) when disabled or on failure. */
	private boolean buildVectorField() {
		if (!this.vectorField || this.clearProgram == null) {
			return false;
		}

		try {
			GpuTexture depth = this.motion.depthCopy();
			GpuTextureView motionView = this.motion.view();

			if (depth == null || motionView == null) {
				return false;
			}

			this.ensureFieldResources(depth);
			int w = this.fieldWidth;
			int h = this.fieldHeight;
			Vector4f params = this.motion.depthParams();
			this.fieldSizes.set(w, h, this.width, this.height);
			this.fieldDepthParams.set(params.x, params.y, params.z, this.hasFieldPrevDepth ? 1 : 0);

			var groups = new Vector3i((w + 7) / 8, (h + 7) / 8, 1);
			this.clearProgram.dispatch(groups, 0, ++this.drawCounter);
			this.scatterProgram.dispatch(groups, 0, ++this.drawCounter);
			this.resolveProgram.dispatch(groups, 0, ++this.drawCounter);
			return true;
		} catch (RuntimeException e) {
			this.vectorField = false;

			if (!this.loggedFieldFailure) {
				this.loggedFieldFailure = true;
				Iris.logger.warn(LOG_PREFIX + "The game motion vector field passes failed; falling back to the backward motion-vector search.", e);
			}

			return false;
		}
	}

	private void keepFieldDepth() {
		if (!this.vectorField || this.fieldPrevDepth == null) {
			return;
		}

		try {
			GpuTexture depth = this.motion.depthCopy();

			if (depth != null && depth.getWidth(0) == this.fieldWidth && depth.getHeight(0) == this.fieldHeight
				&& depth.getFormat() == this.fieldDepthFormat) {
				RenderSystem.getDevice().createCommandEncoder()
					.copyTextureToTexture(depth, this.fieldPrevDepth, 0, 0, 0, 0, 0, this.fieldWidth, this.fieldHeight);
				this.hasFieldPrevDepth = true;
			}
		} catch (RuntimeException e) {
			this.hasFieldPrevDepth = false;
		}
	}

	private void ensureFieldResources(GpuTexture depth) {
		int w = depth.getWidth(0);
		int h = depth.getHeight(0);

		if (w == this.fieldWidth && h == this.fieldHeight && depth.getFormat() == this.fieldDepthFormat && this.vectors != null) {
			return;
		}

		this.closeFieldTextures();
		var device = RenderSystem.getDevice();
		this.fieldWidth = w;
		this.fieldHeight = h;
		this.fieldDepthFormat = depth.getFormat();
		this.fieldX = device.createTexture("Iris FSR3 FG vector field X", STORAGE_USAGE, GpuFormat.R32_UINT, w, h, 1, 1);
		this.fieldXView = device.createTextureView(this.fieldX);
		this.fieldY = device.createTexture("Iris FSR3 FG vector field Y", STORAGE_USAGE, GpuFormat.R32_UINT, w, h, 1, 1);
		this.fieldYView = device.createTextureView(this.fieldY);
		this.interpDepth = device.createTexture("Iris FSR3 FG interpolated depth", STORAGE_USAGE, GpuFormat.R32_UINT, w, h, 1, 1);
		this.interpDepthView = device.createTextureView(this.interpDepth);
		this.vectors = device.createTexture("Iris FSR3 FG vectors", STORAGE_USAGE, GpuFormat.RGBA16_FLOAT, w, h, 1, 1);
		this.vectorsView = device.createTextureView(this.vectors);
		this.fieldPrevDepth = device.createTexture("Iris FSR3 FG previous depth", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
			this.fieldDepthFormat, w, h, 1, 1);
		this.fieldPrevDepthView = device.createTextureView(this.fieldPrevDepth, 0, 1);
		this.hasFieldPrevDepth = false;
		Iris.logger.info(LOG_PREFIX + "Allocated {}x{} game motion vector field.", w, h);
	}

	private void closeFieldTextures() {
		for (var view : new GpuTextureView[]{this.fieldXView, this.fieldYView, this.interpDepthView, this.vectorsView, this.fieldPrevDepthView}) {
			if (view != null) {
				view.close();
			}
		}

		for (var texture : new GpuTexture[]{this.fieldX, this.fieldY, this.interpDepth, this.vectors, this.fieldPrevDepth}) {
			if (texture != null) {
				texture.close();
			}
		}

		this.fieldXView = this.fieldYView = this.interpDepthView = this.vectorsView = this.fieldPrevDepthView = null;
		this.fieldX = this.fieldY = this.interpDepth = this.vectors = this.fieldPrevDepth = null;
		this.fieldWidth = this.fieldHeight = -1;
		this.hasFieldPrevDepth = false;
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
		this.interpolated = device.createTexture("Iris FSR3 FG interpolated", FsrMotionVectors.COLOR_USAGE, GpuFormat.RGBA16_FLOAT, w, h, 1, 1);
		this.interpolatedView = device.createTextureView(this.interpolated);

		int levelWidth = w;
		int levelHeight = h;
		this.pyramidLevels = 0;

		while (this.pyramidLevels < MAX_PYRAMID_LEVELS && (levelWidth > 1 || levelHeight > 1)) {
			levelWidth = Math.max(1, levelWidth / 2);
			levelHeight = Math.max(1, levelHeight / 2);
			int i = this.pyramidLevels++;
			this.pyramid[i] = device.createTexture("Iris FSR3 FG inpainting pyramid " + i, FsrMotionVectors.COLOR_USAGE, GpuFormat.RGBA16_FLOAT,
				levelWidth, levelHeight, 1, 1);
			this.pyramidViews[i] = device.createTextureView(this.pyramid[i]);
		}

		this.resetHistory("resize");
		Iris.logger.info(LOG_PREFIX + "Allocated {}x{} frame generation targets (color {}).", w, h, this.colorFormat);
	}

	private void resetHistory(String reason) {
		this.hasFieldPrevDepth = false;
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
		for (var view : new GpuTextureView[]{this.hudlessViews[0], this.hudlessViews[1], this.finalCopyView, this.outputView,
			this.interpolatedView}) {
			if (view != null) {
				view.close();
			}
		}

		for (var texture : new GpuTexture[]{this.hudless[0], this.hudless[1], this.finalCopy, this.output, this.interpolated}) {
			if (texture != null) {
				texture.close();
			}
		}

		for (int i = 0; i < MAX_PYRAMID_LEVELS; i++) {
			if (this.pyramidViews[i] != null) {
				this.pyramidViews[i].close();
			}

			if (this.pyramid[i] != null) {
				this.pyramid[i].close();
			}

			this.pyramidViews[i] = null;
			this.pyramid[i] = null;
		}

		this.pyramidLevels = 0;
		this.pyramidSource = null;
		this.interpolatedView = null;
		this.interpolated = null;

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
		this.pyramidProgram.destroy();
		this.inpaintProgram.destroy();

		for (var program : new ComputeProgram[]{this.clearProgram, this.scatterProgram, this.resolveProgram}) {
			if (program != null) {
				program.destroy();
			}
		}

		this.closeTextures();
		this.closeFieldTextures();
	}
}
