package net.irisshaders.iris.anr;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.config.IrisConfig;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Entry point used by the world rendering pipeline. When the backend is disabled (the default)
 * {@link #afterFinalPass} returns immediately and rendering is unchanged.
 */
public final class NeuralRendering {
	private static final Object LOCK = new Object();
	private static NeuralRenderingBackend backend = DisabledNeuralRenderingBackend.INSTANCE;
	private static boolean loaded;
	private static boolean loggedMissingMotionVectors;
	private static boolean loggedEvaluateError;
	private static final Matrix4f previousView = new Matrix4f();
	private static final Matrix4f previousProjection = new Matrix4f();
	private static boolean hasPreviousMatrices;

	private NeuralRendering() {
	}

	public static boolean isActive() {
		IrisConfig config = Iris.getIrisConfig();
		return config != null && config.isAnrEnabled() && backend.isAvailable();
	}

	/**
     * Called after {@code FinalPassRenderer.renderFinalPass()} while the pack's scene color is on
     * the main render target and depth is still available from Iris's {@link RenderTargets}.
     */
	public static void afterFinalPass(RenderTargets renderTargets) {
		IrisConfig config = Iris.getIrisConfig();
		if (config == null || !config.isAnrEnabled()) {
			return;
		}

		ensureLoaded(config);
		if (!backend.isAvailable()) {
			return;
		}

		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		Matrix4fc view = CapturedRenderingState.INSTANCE.getGbufferModelView();
		Matrix4fc projection = CapturedRenderingState.INSTANCE.getGbufferProjection();
		if (main == null || view == null || projection == null) {
			return;
		}

		NeuralFrameInputs inputs = new NeuralFrameInputs();
		inputs.featureFlags = requestedFlags(config);
		if (inputs.featureFlags == 0) {
			return;
		}

		inputs.color = NeuralTextureHandle.from(main.getColorTextureView());
		inputs.depth = depthHandle(renderTargets, main);
		inputs.motionVectors = motionVectorHandle(config, renderTargets);
		view.get(inputs.view);
		projection.get(inputs.projection);
		if (hasPreviousMatrices) {
			previousView.get(inputs.previousView);
			previousProjection.get(inputs.previousProjection);
		} else {
			view.get(inputs.previousView);
			projection.get(inputs.previousProjection);
		}

		// Camera jitter and auto-exposure are not currently sourced from vanilla or the pack.
		inputs.jitterX = 0.0f;
		inputs.jitterY = 0.0f;
		inputs.exposure = 1.0f;
		inputs.frameDeltaSeconds = SystemTimeUniforms.TIMER.getLastFrameTime();
		inputs.superResolutionScale = config.getAnrSuperResolutionScale();
		inputs.superResolutionQuality = config.getAnrSuperResolutionQuality().id();

		try {
			backend.evaluate(inputs);
		} catch (RuntimeException e) {
			if (!loggedEvaluateError) {
				loggedEvaluateError = true;
				Iris.logger.error("[ANR] evaluate() failed; further errors will be suppressed until reload.", e);
			}
		}

		previousView.set(view);
		previousProjection.set(projection);
		hasPreviousMatrices = true;
	}

	public static void shutdown() {
		synchronized (LOCK) {
			if (loaded) {
				backend.shutdown();
				backend = DisabledNeuralRenderingBackend.INSTANCE;
				loaded = false;
			}
			hasPreviousMatrices = false;
			loggedMissingMotionVectors = false;
			loggedEvaluateError = false;
		}
	}

	private static void ensureLoaded(IrisConfig config) {
		if (loaded) {
			return;
		}

		synchronized (LOCK) {
			if (loaded) {
				return;
			}

			backend = NeuralRenderingLoader.load(config);
			loaded = true;
		}
	}

	private static int requestedFlags(IrisConfig config) {
		int flags = 0;
		SetCaps caps = new SetCaps(backend.capabilities());
		if (config.isAnrFrameGenerationEnabled() && caps.has(NeuralCapability.FRAME_GENERATION)) {
			flags |= NeuralCapability.FRAME_GENERATION.bit();
		}
		if (config.isAnrSuperResolutionEnabled() && caps.has(NeuralCapability.SUPER_RESOLUTION)) {
			flags |= NeuralCapability.SUPER_RESOLUTION.bit();
		}
		if (config.isAnrRayReconstructionEnabled() && caps.has(NeuralCapability.RAY_RECONSTRUCTION)) {
			flags |= NeuralCapability.RAY_RECONSTRUCTION.bit();
		}
		return flags;
	}

	private static NeuralTextureHandle depthHandle(RenderTargets renderTargets, RenderTarget main) {
		if (renderTargets != null && renderTargets.getDepthTexture() != null) {
			return NeuralTextureHandle.from(renderTargets.getDepthTexture());
		}

		return NeuralTextureHandle.from(main.getDepthTexture());
	}

	/**
     * If the user names a colortex index that a shader pack writes motion vectors into, pass that
     * texture through. There is no automatic detection of pack layouts, and no camera-only motion
     * vector pass is generated (see docs/ANR.md).
     */
	private static NeuralTextureHandle motionVectorHandle(IrisConfig config, RenderTargets renderTargets) {
		int index = config.getAnrMotionVectorColortex();
		if (index < 0) {
			return NeuralTextureHandle.empty();
		}

		if (renderTargets == null || index >= renderTargets.getRenderTargetCount()) {
			logMissingMotion(index);
			return NeuralTextureHandle.empty();
		}

		var target = renderTargets.get(index);
		if (target == null) {
			logMissingMotion(index);
			return NeuralTextureHandle.empty();
		}

		return NeuralTextureHandle.from(target.getTextureView(false, false));
	}

	private static void logMissingMotion(int index) {
		if (!loggedMissingMotionVectors) {
			loggedMissingMotionVectors = true;
			Iris.logger.warn(
				"[ANR] anrMotionVectorColortex is set to {}, but that colortex is not available this frame. " +
					"Motion vectors will be omitted. A camera-only motion vector pass is not implemented; see docs/ANR.md.",
				index);
		}
	}

	private record SetCaps(java.util.Set<NeuralCapability> caps) {
		boolean has(NeuralCapability cap) {
			return caps.contains(cap);
		}
	}
}
