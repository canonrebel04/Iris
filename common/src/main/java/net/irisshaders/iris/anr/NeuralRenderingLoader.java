package net.irisshaders.iris.anr;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.config.IrisConfig;
import net.irisshaders.iris.pipeline.GraphicsBackend;
import net.irisshaders.iris.platform.IrisPlatformHelpers;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves the backend: a user-supplied native library via Panama if {@code anrLibraryPath} is set
 * and readable, otherwise the no-op default. Never ships or downloads an ANR binary.
 */
public final class NeuralRenderingLoader {
	private NeuralRenderingLoader() {
	}

	public static NeuralRenderingBackend load(IrisConfig config) {
		if (config == null || !config.isAnrEnabled()) {
			return DisabledNeuralRenderingBackend.INSTANCE;
		}

		String rawPath = config.getAnrLibraryPath();
		if (rawPath == null || rawPath.isBlank()) {
			Iris.logger.info(
				"[ANR] Neural rendering is enabled in iris.properties, but anrLibraryPath is empty. " +
					"Leaving it disabled. See docs/ANR.md — no official SDK or licensed binary is bundled.");
			return DisabledNeuralRenderingBackend.INSTANCE;
		}

		Path library = resolve(rawPath.trim());
		if (!Files.isRegularFile(library)) {
			Iris.logger.warn("[ANR] Native library does not exist or is not a file: {}. Leaving neural rendering disabled.",
				library);
			return DisabledNeuralRenderingBackend.INSTANCE;
		}

		try {
			NativeNeuralRenderingBackend backend = NativeNeuralRenderingBackend.open(library);
			int api = GraphicsBackend.isVulkan() ? NeuralTextureHandle.API_VULKAN : NeuralTextureHandle.API_OPENGL;
			backend.init(new NeuralInitInfo(NeuralInitInfo.ABI_VERSION, api));
			Iris.logger.info("[ANR] Loaded native backend from {} with capabilities {}", library, backend.capabilities());
			return backend;
		} catch (Throwable t) {
			Iris.logger.error(
				"[ANR] Failed to load native backend from {}. Leaving neural rendering disabled. " +
					"If the JVM blocked native access, launch with --enable-native-access=ALL-UNNAMED. See docs/ANR.md.",
				library, t);
			return DisabledNeuralRenderingBackend.INSTANCE;
		}
	}

	private static Path resolve(String rawPath) {
		Path path = Path.of(rawPath);
		if (path.isAbsolute()) {
			return path;
		}

		return IrisPlatformHelpers.getInstance().getGameDir().resolve(path);
	}
}
