package net.irisshaders.iris.config;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.anr.NeuralSuperResolutionQuality;
import net.irisshaders.iris.anr.fsr.FsrDebugView;
import net.irisshaders.iris.anr.fsr.FsrFramePacing;
import net.irisshaders.iris.anr.fsr.FsrUpscalingQuality;
import net.irisshaders.iris.gui.option.IrisVideoSettings;
import net.irisshaders.iris.pathways.colorspace.ColorSpace;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;

/**
 * A class dedicated to storing the config values of shaderpacks. Right now it only stores the path to the current shaderpack
 */
public class IrisConfig {
	private static final String COMMENT = "This file stores configuration options for Iris, such as the currently active shaderpack";
	private static final Gson GSON = new Gson();
	private final Path propertiesPath;
	private final Path excludedPath;
	/**
	 * What shaders should be nuked.
	 */
	private final List<Identifier> shadersToSkip = new ArrayList<>();
	/**
	 * The path to the current shaderpack. Null if the internal shaderpack is being used.
	 */
	private String shaderPackName;
	/**
	 * Whether or not shaders are used for rendering. False to disable all shader-based rendering, true to enable it.
	 */
	private boolean enableShaders;
	/**
	 * Whether or not to allow core shaders to draw to the main color texture.
	 */
	private boolean allowUnknownShaders;
	/**
	 * If debug features should be enabled. Gives much more detailed OpenGL error outputs at the cost of performance.
	 */
	private boolean enableDebugOptions;
	/**
	 * If the update notification should be disabled or not.
	 */
	private boolean disableUpdateMessage;
	/**
	 * Master switch for the neural rendering integration layer. Off by default.
	 * A native backend is loaded only when this is true and anrLibraryPath is set.
	 */
	private boolean anrEnabled;
	private boolean anrFrameGeneration;
	private boolean anrSuperResolution;
	private NeuralSuperResolutionQuality anrSuperResolutionQuality;
	private float anrSuperResolutionScale;
	private boolean anrRayReconstruction;
	/** Absolute or game-directory-relative path to a native library implementing the Iris ANR ABI. Empty means do not load one. */
	private String anrLibraryPath;
	/** colortex index a shader pack writes motion vectors into, or -1 if none is provided. */
	private int anrMotionVectorColortex;
	/** FSR 3-based frame generation implemented inside Iris (shader port, no native code). Off by default. */
	private boolean fsr3FrameGen;
	/** Present generated frames. When false the passes still run (for debugging) but nothing extra is shown. */
	private boolean fsr3FrameGenPresent;
	private FsrFramePacing fsr3FrameGenPacing;
	private FsrDebugView fsr3FrameGenDebugView;
	/** FSR 2/3-style temporal upscaling (needs a shader pack). Off by default. */
	private boolean fsrUpscaling;
	private FsrUpscalingQuality fsrUpscalingQuality;
	private float fsrUpscalingSharpness;

	public IrisConfig(Path propertiesPath, Path excluded) {
		shaderPackName = null;
		enableShaders = true;
		allowUnknownShaders = false;
		enableDebugOptions = false;
		disableUpdateMessage = false;
		anrEnabled = false;
		anrFrameGeneration = false;
		anrSuperResolution = false;
		anrSuperResolutionQuality = NeuralSuperResolutionQuality.BALANCED;
		anrSuperResolutionScale = 1.0f;
		anrRayReconstruction = false;
		anrLibraryPath = "";
		anrMotionVectorColortex = -1;
		fsr3FrameGen = false;
		fsr3FrameGenPresent = true;
		fsr3FrameGenPacing = FsrFramePacing.VSYNC;
		fsr3FrameGenDebugView = FsrDebugView.NONE;
		fsrUpscaling = false;
		fsrUpscalingQuality = FsrUpscalingQuality.QUALITY;
		fsrUpscalingSharpness = 0.2f;
		this.propertiesPath = propertiesPath;
		this.excludedPath = excluded;
	}

	/**
	 * Initializes the configuration, loading it if it is present and creating a default config otherwise.
	 *
	 * @throws IOException file exceptions
	 */
	public void initialize() throws IOException {
		load();
		if (!Files.exists(propertiesPath)) {
			save();
		}
	}

	/**
	 * returns whether or not the current shaderpack is internal
	 *
	 * @return if the shaderpack is internal
	 */
	public boolean isInternal() {
		return false;
	}

	/**
	 * Returns the name of the current shaderpack
	 *
	 * @return Returns the current shaderpack name - if internal shaders are being used it returns "(internal)"
	 */
	public Optional<String> getShaderPackName() {
		return Optional.ofNullable(shaderPackName);
	}

	/**
	 * Sets the name of the current shaderpack
	 */
	public void setShaderPackName(String name) {
		if (name == null || name.equals("(internal)") || name.isEmpty()) {
			this.shaderPackName = null;
		} else {
			this.shaderPackName = name;
		}
	}

	/**
	 * Determines whether or not shaders are used for rendering.
	 *
	 * @return False to disable all shader-based rendering, true to enable shader-based rendering.
	 */
	public boolean areShadersEnabled() {
		return enableShaders;
	}

	public boolean areDebugOptionsEnabled() {
		return enableDebugOptions;
	}

	public boolean shouldDisableUpdateMessage() {
		return disableUpdateMessage;
	}

	public void setDebugEnabled(boolean enabled) {
		enableDebugOptions = enabled;
	}

	/**
	 * Sets whether shaders should be used for rendering.
	 */
	public void setShadersEnabled(boolean enabled) {
		this.enableShaders = enabled;
	}

	/**
	 * loads the config file and then populates the string, int, and boolean entries with the parsed entries
	 *
	 * @throws IOException if the file cannot be loaded
	 */

	public void load() throws IOException {
		if (Files.exists(excludedPath)) {
			JsonArray json = JsonParser.parseString(Files.readString(excludedPath)).getAsJsonObject().getAsJsonArray("excluded");
			for (int i = 0; i < json.size(); i++) {
				Identifier resource = Identifier.tryParse(json.get(i).getAsString());
				if (resource == null) {
					Iris.logger.warn("Unknown shader " + json.get(i).getAsString());
				}

				shadersToSkip.add(resource);
			}
		} else {
			JsonObject defaultV = new JsonObject();
			JsonArray array = new JsonArray();
			array.add("put:valuesHere");
			defaultV.add("excluded", array);
			Files.writeString(excludedPath, GSON.toJson(defaultV));
		}

		if (!Files.exists(propertiesPath)) {
			return;
		}

		Properties properties = new Properties();
		// NB: This uses ISO-8859-1 with unicode escapes as the encoding
		try (InputStream is = Files.newInputStream(propertiesPath)) {
			properties.load(is);
		}

		shaderPackName = properties.getProperty("shaderPack");
		enableShaders = !"false".equals(properties.getProperty("enableShaders"));
		allowUnknownShaders = "true".equals(properties.getProperty("allowUnknownShaders"));
		enableDebugOptions = "true".equals(properties.getProperty("enableDebugOptions"));
		disableUpdateMessage = "true".equals(properties.getProperty("disableUpdateMessage"));
		anrEnabled = "true".equals(properties.getProperty("anrEnable"));
		anrFrameGeneration = "true".equals(properties.getProperty("anrFrameGeneration"));
		anrSuperResolution = "true".equals(properties.getProperty("anrSuperResolution"));
		anrSuperResolutionQuality = NeuralSuperResolutionQuality.fromConfig(properties.getProperty("anrSuperResolutionQuality", "balanced"));
		anrRayReconstruction = "true".equals(properties.getProperty("anrRayReconstruction"));
		anrLibraryPath = properties.getProperty("anrLibraryPath", "");
		try {
			this.anrSuperResolutionScale = Float.parseFloat(properties.getProperty("anrSuperResolutionScale", "1.0"));
		} catch (NumberFormatException e) {
			Iris.logger.warn("anrSuperResolutionScale is invalid; using 1.0.");
			this.anrSuperResolutionScale = 1.0f;
		}
		try {
			this.anrMotionVectorColortex = Integer.parseInt(properties.getProperty("anrMotionVectorColortex", "-1"));
		} catch (NumberFormatException e) {
			Iris.logger.warn("anrMotionVectorColortex is invalid; using -1 (no pack motion vectors).");
			this.anrMotionVectorColortex = -1;
		}
		fsr3FrameGen = "true".equals(properties.getProperty("fsr3FrameGen"));
		fsr3FrameGenPresent = !"false".equals(properties.getProperty("fsr3FrameGenPresent"));
		fsr3FrameGenPacing = FsrFramePacing.fromConfig(properties.getProperty("fsr3FrameGenPacing", "vsync"));
		fsr3FrameGenDebugView = FsrDebugView.fromConfig(properties.getProperty("fsr3FrameGenDebugView", "none"));
		fsrUpscaling = "true".equals(properties.getProperty("fsrUpscaling"));
		fsrUpscalingQuality = FsrUpscalingQuality.fromConfig(properties.getProperty("fsrUpscalingQuality", "quality"));
		try {
			this.fsrUpscalingSharpness = Math.clamp(Float.parseFloat(properties.getProperty("fsrUpscalingSharpness", "0.2")), 0.0f, 1.0f);
		} catch (NumberFormatException e) {
			Iris.logger.warn("fsrUpscalingSharpness is invalid; using 0.2.");
			this.fsrUpscalingSharpness = 0.2f;
		}
		try {
			IrisVideoSettings.shadowDistance = Integer.parseInt(properties.getProperty("maxShadowRenderDistance", "32"));
			IrisVideoSettings.colorSpace = ColorSpace.valueOf(properties.getProperty("colorSpace", "SRGB"));
		} catch (IllegalArgumentException e) {
			Iris.logger.error("Shadow distance setting reset; value is invalid.");
			IrisVideoSettings.shadowDistance = 32;
			IrisVideoSettings.colorSpace = ColorSpace.SRGB;
			save();
		}

		if (shaderPackName != null) {
			if (shaderPackName.equals("(internal)") || shaderPackName.isEmpty()) {
				shaderPackName = null;
			}
		}
	}

	/**
	 * Serializes the config into a file. Should be called whenever any config values are modified.
	 *
	 * @throws IOException file exceptions
	 */
	public void save() throws IOException {
		Properties properties = new Properties();
		properties.setProperty("shaderPack", getShaderPackName().orElse(""));
		properties.setProperty("enableShaders", Boolean.toString(enableShaders));
		properties.setProperty("allowUnknownShaders", Boolean.toString(allowUnknownShaders));
		properties.setProperty("enableDebugOptions", Boolean.toString(enableDebugOptions));
		properties.setProperty("disableUpdateMessage", Boolean.toString(disableUpdateMessage));
		properties.setProperty("anrEnable", Boolean.toString(anrEnabled));
		properties.setProperty("anrFrameGeneration", Boolean.toString(anrFrameGeneration));
		properties.setProperty("anrSuperResolution", Boolean.toString(anrSuperResolution));
		properties.setProperty("anrSuperResolutionQuality", anrSuperResolutionQuality.name().toLowerCase(Locale.ROOT));
		properties.setProperty("anrSuperResolutionScale", Float.toString(anrSuperResolutionScale));
		properties.setProperty("anrRayReconstruction", Boolean.toString(anrRayReconstruction));
		properties.setProperty("anrLibraryPath", anrLibraryPath == null ? "" : anrLibraryPath);
		properties.setProperty("anrMotionVectorColortex", Integer.toString(anrMotionVectorColortex));
		properties.setProperty("fsr3FrameGen", Boolean.toString(fsr3FrameGen));
		properties.setProperty("fsr3FrameGenPresent", Boolean.toString(fsr3FrameGenPresent));
		properties.setProperty("fsr3FrameGenPacing", fsr3FrameGenPacing.configName());
		properties.setProperty("fsr3FrameGenDebugView", fsr3FrameGenDebugView.configName());
		properties.setProperty("fsrUpscaling", Boolean.toString(fsrUpscaling));
		properties.setProperty("fsrUpscalingQuality", fsrUpscalingQuality.configName());
		properties.setProperty("fsrUpscalingSharpness", Float.toString(fsrUpscalingSharpness));
		properties.setProperty("maxShadowRenderDistance", String.valueOf(IrisVideoSettings.shadowDistance));
		properties.setProperty("colorSpace", IrisVideoSettings.colorSpace.name());
		// NB: This uses ISO-8859-1 with unicode escapes as the encoding
		try (OutputStream os = Files.newOutputStream(propertiesPath)) {
			properties.store(os, COMMENT);
		}
	}

	public boolean shouldAllowUnknownShaders() {
		return allowUnknownShaders;
	}

	public boolean shouldSkip(Identifier value) {
		return shadersToSkip.contains(value); // TODO
	}

	public void setUnknown(boolean b) throws IOException {
		this.allowUnknownShaders = b;
		save();
	}

	public boolean isAnrEnabled() {
		return anrEnabled;
	}

	public boolean isAnrFrameGenerationEnabled() {
		return anrFrameGeneration;
	}

	public boolean isAnrSuperResolutionEnabled() {
		return anrSuperResolution;
	}

	public NeuralSuperResolutionQuality getAnrSuperResolutionQuality() {
		return anrSuperResolutionQuality;
	}

	public float getAnrSuperResolutionScale() {
		return anrSuperResolutionScale;
	}

	public boolean isAnrRayReconstructionEnabled() {
		return anrRayReconstruction;
	}

	public String getAnrLibraryPath() {
		return anrLibraryPath;
	}

	public int getAnrMotionVectorColortex() {
		return anrMotionVectorColortex;
	}

	public boolean isFsr3FrameGenEnabled() {
		return fsr3FrameGen;
	}

	public boolean shouldPresentFsr3GeneratedFrames() {
		return fsr3FrameGenPresent;
	}

	public FsrFramePacing getFsr3FrameGenPacing() {
		return fsr3FrameGenPacing;
	}

	public FsrDebugView getFsr3FrameGenDebugView() {
		return fsr3FrameGenDebugView;
	}

	public boolean isFsrUpscalingEnabled() {
		return fsrUpscaling;
	}

	public FsrUpscalingQuality getFsrUpscalingQuality() {
		return fsrUpscalingQuality;
	}

	public float getFsrUpscalingSharpness() {
		return fsrUpscalingSharpness;
	}
}
