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
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.joml.Vector4f;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Camera-reprojection motion vectors + depth disocclusion at render resolution, shared by
 * {@link FsrUpscaler} and {@link FsrFrameGeneration} (and the ANR layer as a fallback).
 *
 * <p>Output (RGBA16F): rg = prevUv - uv (FidelityFX convention, jitter removed), b = disoccluded, a = hand.</p>
 */
public final class FsrMotionVectors {
	static final String LOG_PREFIX = "[FSR] ";
	static final int COLOR_USAGE = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING
		| GpuTexture.USAGE_RENDER_ATTACHMENT;
	/** Camera moves larger than this (blocks) between frames are treated as a cut, and history is reset. */
	private static final double CAMERA_CUT_DISTANCE = 32.0;

	@Nullable
	private static FsrMotionVectors active;

	private final RenderTargets renderTargets;
	private final CompositeProgram motionProgram;

	private GpuTextureView depthView;
	private GpuTextureView noHandDepthView;
	private GpuTexture depthViewSource;
	private GpuTexture noHandViewSource;

	private GpuTexture motionTexture;
	private GpuTextureView motionView;
	private GpuTexture previousDepth;
	private GpuTextureView previousDepthView;
	private int width = -1;
	private int height = -1;
	private GpuFormat depthFormat;

	private final Vector4f depthParams = new Vector4f();
	private final Matrix4f reprojection = new Matrix4f();
	private final Vector4f motionParams = new Vector4f();
	private final Vector4f jitterParams = new Vector4f();
	private final Matrix4f previousView = new Matrix4f();
	/** Previous frame's projection with its jitter already removed. */
	private final Matrix4f previousProjection = new Matrix4f();
	private final Vector3d previousCamera = new Vector3d();
	private float previousJitterUvX;
	private float previousJitterUvY;
	private boolean hasPreviousCamera;
	private boolean hasPreviousDepth;
	private boolean handDepthValid;

	private long motionFrame = -1;
	private long historyResetFrame = -1;
	private long drawCounter;
	private int passesWithoutFrameHook;
	private boolean loggedMissingHook;
	private boolean failed;
	private boolean destroyed;

	private FsrMotionVectors(PackSamplers samplers, RenderTargets renderTargets) {
		this.renderTargets = renderTargets;
		String vertex = FsrShaders.read("/fsr3fg/fullscreen.vsh");
		var outputs = new ColorAttachmentPlan.Builder().program("fsr3fg_motion", new int[]{0}, Set.of()).build(1).program("fsr3fg_motion");
		this.motionProgram = new CompositeProgram(samplers, "fsr3fg_motion",
			Map.of(PatchShaderType.VERTEX, vertex, PatchShaderType.FRAGMENT, FsrShaders.read("/fsr3fg/motion.fsh")), outputs,
			Collections.singletonList(null));

		try {
			this.motionProgram.getUniforms().uniformMatrix(UniformUpdateFrequency.CUSTOM, "fgReproject", () -> this.reprojection);
			this.motionProgram.getUniforms().uniform4f(UniformUpdateFrequency.CUSTOM, "fgParams", () -> this.motionParams);
			this.motionProgram.getUniforms().uniform4f(UniformUpdateFrequency.CUSTOM, "fgJitter", () -> this.jitterParams);
			var motionSamplers = this.motionProgram.getSamplers();
			motionSamplers.addDrawTextureSampler(new TextureBinding("fsr3fg/depth", () -> this.depthView, () -> GlSampler.NEAREST), false,
				"fgDepth");
			motionSamplers.addDrawTextureSampler(new TextureBinding("fsr3fg/depthNoHand", () -> this.noHandDepthView, () -> GlSampler.NEAREST),
				false, "fgDepthNoHand");
			motionSamplers.addDrawTextureSampler(
				new TextureBinding("fsr3fg/previousDepth", () -> this.previousDepthView, () -> GlSampler.NEAREST), false, "fgPrevDepth");
			motionSamplers.finish();
		} catch (RuntimeException | Error e) {
			this.motionProgram.destroy();
			throw e;
		}
	}

	/** Created when frame generation or upscaling is enabled. Must run while the pipeline registers samplers. */
	@Nullable
	public static FsrMotionVectors createIfNeeded(PackSamplers samplers, RenderTargets renderTargets) {
		IrisConfig config = Iris.getIrisConfig();

		if (config == null || !(config.isFsr3FrameGenEnabled() || config.isFsrUpscalingEnabled())) {
			return null;
		}

		try {
			var motion = new FsrMotionVectors(samplers, renderTargets);
			active = motion;
			return motion;
		} catch (RuntimeException e) {
			Iris.logger.error(LOG_PREFIX + "Motion vector setup failed; FSR upscaling and frame generation are disabled for this pack.", e);
			return null;
		}
	}

	/** Motion vectors produced this frame (see class doc), or null. Used by the ANR layer when the pack has none. */
	@Nullable
	public static GpuTextureView currentMotionVectors() {
		var motion = active;
		return motion != null && motion.isValidThisFrame() ? motion.motionView : null;
	}

	public boolean hasFailed() {
		return this.failed || this.destroyed;
	}

	public boolean isValidThisFrame() {
		return !this.failed && !this.destroyed && this.motionFrame == FsrFrameState.frameIndex() && this.motionView != null;
	}

	/** True if history (camera cut, resize, first frame) was dropped this frame; temporal consumers must reset too. */
	public boolean historyResetThisFrame() {
		return this.historyResetFrame == FsrFrameState.frameIndex();
	}

	@Nullable
	GpuTextureView view() {
		return this.motionView;
	}

	@Nullable
	GpuTextureView depthView() {
		return this.depthView;
	}

	/** After {@link #render()}: a copy of this frame's raw depth (render resolution), valid until the next render. */
	@Nullable
	GpuTexture depthCopy() {
		return this.previousDepth;
	}

	@Nullable
	GpuTextureView depthCopyView() {
		return this.previousDepthView;
	}

	/**
	 * This frame's un-jittered projection as FSR 3 needs it: x = m22, y = m32 (OpenGL-style, for view distance from
	 * depth), z = Kfov (length of a view ray through the screen corner / through the centre).
	 */
	Vector4f depthParams() {
		return this.depthParams;
	}

	public void onPreHandDepthCopied() {
		this.handDepthValid = true;
	}

	/** After the shader pack's final pass, while depth is still valid. */
	public void render() {
		if (this.failed || this.destroyed) {
			return;
		}

		// Iris keeps one pipeline per dimension alive; the one rendering this frame is the active one.
		active = this;

		if (FsrFrameState.frameIndex() == 0 && ++this.passesWithoutFrameHook > 30 && !this.loggedMissingHook) {
			this.loggedMissingHook = true;
			Iris.logger.warn(LOG_PREFIX + "The swapchain hook (MixinMinecraft_FrameGeneration) never ran: generated frames cannot be "
				+ "shown and upscaling cannot accumulate history on this Minecraft version.");
		}

		try {
			var depth = this.renderTargets.getDepthTexture();
			var noHand = this.renderTargets.getDepthTextureNoHand();
			Matrix4fc view = CapturedRenderingState.INSTANCE.getGbufferModelView();
			Matrix4fc projection = CapturedRenderingState.INSTANCE.getGbufferProjection();

			if (depth == null || view == null || projection == null) {
				return;
			}

			this.ensureResources(depth);
			this.updateDepthViews(depth, noHand);

			Vector3d camera = CameraUniforms.getUnshiftedCameraPosition();

			if (this.hasPreviousCamera && camera.distance(this.previousCamera) > CAMERA_CUT_DISTANCE) {
				this.resetHistory("camera cut");
			}

			float jitterX = FsrFrameState.jitterNdcX();
			float jitterY = FsrFrameState.jitterNdcY();
			// gbufferProjection includes this frame's jitter: P = T(jitter) * P_unjittered. T only touches rows 0/1 and
			// undoRevZ only row 2, so the order does not matter.
			Matrix4f unjittered = new Matrix4f().translation(-jitterX, -jitterY, 0).mul(projection);
			this.buildReprojection(view, projection, unjittered, camera);
			this.motionParams.set(this.previousProjection.m22(), this.previousProjection.m32(), this.hasPreviousDepth ? 1.0f : 0.0f,
				this.handDepthValid && noHand != null ? 1.0f : 0.0f);
			this.jitterParams.set(jitterX * 0.5f, jitterY * 0.5f, this.previousJitterUvX, this.previousJitterUvY);
			this.motionProgram.draw(List.of(this.motionView), ViewportData.defaultValue(), 0, ++this.drawCounter);

			RenderSystem.getDevice().createCommandEncoder()
				.copyTextureToTexture(depth, this.previousDepth, 0, 0, 0, 0, 0, this.width, this.height);
			this.hasPreviousDepth = true;

			this.previousView.set(view);
			this.previousProjection.set(unjittered);
			this.depthParams.set(unjittered.m22(), unjittered.m32(),
				(float) Math.sqrt(1.0 + 1.0 / (unjittered.m00() * unjittered.m00()) + 1.0 / (unjittered.m11() * unjittered.m11())), 0);
			this.previousCamera.set(camera);
			this.previousJitterUvX = jitterX * 0.5f;
			this.previousJitterUvY = jitterY * 0.5f;
			this.hasPreviousCamera = true;
			this.motionFrame = FsrFrameState.frameIndex();
		} catch (RuntimeException e) {
			this.failed = true;
			Iris.logger.error(LOG_PREFIX + "The motion vector pass failed; FSR is disabled until the shader pack is reloaded.", e);
		} finally {
			this.handDepthValid = false;
		}
	}

	private void buildReprojection(Matrix4fc view, Matrix4fc projection, Matrix4fc unjittered, Vector3d camera) {
		if (!this.hasPreviousCamera) {
			this.previousView.set(view);
			this.previousProjection.set(unjittered);
			this.previousCamera.set(camera);
			this.previousJitterUvX = FsrFrameState.jitterNdcX() * 0.5f;
			this.previousJitterUvY = FsrFrameState.jitterNdcY() * 0.5f;
		}

		// Current (jittered) NDC -> view -> world relative to the previous camera -> previous un-jittered clip.
		Matrix4d currentInverse = new Matrix4d(projection).mul(new Matrix4d(view)).invert();
		Matrix4d previous = new Matrix4d(this.previousProjection).mul(new Matrix4d(this.previousView))
			.translate(camera.x - this.previousCamera.x, camera.y - this.previousCamera.y, camera.z - this.previousCamera.z);
		this.reprojection.set(previous.mul(currentInverse));
	}

	private void ensureResources(GpuTexture depth) {
		int w = depth.getWidth(0);
		int h = depth.getHeight(0);

		if (w == this.width && h == this.height && depth.getFormat() == this.depthFormat && this.motionTexture != null) {
			return;
		}

		this.closeTextures();
		var device = RenderSystem.getDevice();
		this.width = w;
		this.height = h;
		this.depthFormat = depth.getFormat();
		this.motionTexture = device.createTexture("Iris FSR motion vectors", COLOR_USAGE, GpuFormat.RGBA16_FLOAT, w, h, 1, 1);
		this.motionView = device.createTextureView(this.motionTexture);
		this.previousDepth = device.createTexture("Iris FSR previous depth", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
			this.depthFormat, w, h, 1, 1);
		this.previousDepthView = device.createTextureView(this.previousDepth, 0, 1);
		this.resetHistory("resize");
		Iris.logger.info(LOG_PREFIX + "Allocated {}x{} motion vector targets (depth {}).", w, h, this.depthFormat);
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
		this.historyResetFrame = FsrFrameState.frameIndex();
		Iris.logger.debug(LOG_PREFIX + "Motion history reset (" + reason + ").");
	}

	private void closeTextures() {
		if (this.motionView != null) {
			this.motionView.close();
		}

		if (this.previousDepthView != null) {
			this.previousDepthView.close();
		}

		if (this.motionTexture != null) {
			this.motionTexture.close();
		}

		if (this.previousDepth != null) {
			this.previousDepth.close();
		}

		this.motionView = this.previousDepthView = null;
		this.motionTexture = this.previousDepth = null;
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
		this.closeTextures();

		if (this.depthView != null) {
			this.depthView.close();
		}

		if (this.noHandDepthView != null) {
			this.noHandDepthView.close();
		}

		this.depthView = this.noHandDepthView = null;
	}
}
