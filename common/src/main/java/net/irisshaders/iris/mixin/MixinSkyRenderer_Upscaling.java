package net.irisshaders.iris.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.irisshaders.iris.anr.fsr.FsrUpscaler;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The sky renderer caches the main target when it is created. While FSR upscaling renders the world into a
 * lower-resolution main target, point it at that target instead. Identity unless upscaling is active.
 */
@Mixin(SkyRenderer.class)
public class MixinSkyRenderer_Upscaling {
	@ModifyExpressionValue(method = "render", require = 0, at = @At(value = "FIELD",
		target = "Lnet/minecraft/client/renderer/SkyRenderer;renderTarget:Lcom/mojang/blaze3d/pipeline/RenderTarget;"))
	private RenderTarget iris$fsrRenderTarget(RenderTarget original) {
		return FsrUpscaler.remapMainTarget(original);
	}
}
