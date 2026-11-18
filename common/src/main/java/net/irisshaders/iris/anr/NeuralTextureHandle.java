package net.irisshaders.iris.anr;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import net.irisshaders.iris.pipeline.GraphicsBackend;

/**
 * A graphics-API-specific texture identifier passed to a native backend.
 * {@code handle} is a GLuint (OpenGL) or a VkImage (Vulkan). Zero means "not provided".
 */
public record NeuralTextureHandle(int api, long handle, int width, int height) {
	public static final int API_NONE = 0;
	public static final int API_OPENGL = 1;
	public static final int API_VULKAN = 2;

	public static NeuralTextureHandle empty() {
		return new NeuralTextureHandle(API_NONE, 0L, 0, 0);
	}

	public static NeuralTextureHandle from(GpuTextureView view) {
		if (view == null) {
			return empty();
		}

		return from(view.texture(), view.getWidth(0), view.getHeight(0));
	}

	public static NeuralTextureHandle from(GpuTexture texture) {
		if (texture == null) {
			return empty();
		}

		return from(texture, texture.getWidth(0), texture.getHeight(0));
	}

	private static NeuralTextureHandle from(GpuTexture texture, int width, int height) {
		if (texture instanceof GlTexture gl) {
			return new NeuralTextureHandle(API_OPENGL, Integer.toUnsignedLong(gl.glId()), width, height);
		}

		if (texture instanceof VulkanGpuTexture vk) {
			return new NeuralTextureHandle(API_VULKAN, vk.vkImage(), width, height);
		}

		int api = GraphicsBackend.isVulkan() ? API_VULKAN : GraphicsBackend.isOpenGL() ? API_OPENGL : API_NONE;
		return new NeuralTextureHandle(api, 0L, width, height);
	}

	public boolean isPresent() {
		return api != API_NONE && handle != 0L;
	}
}
