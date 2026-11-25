package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.irisshaders.iris.anr.fsr.FsrUpscaler;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * The sky renderer is (re)created inside renderLevel() and keeps the main target it was given. Make sure it is always
 * given the real full-resolution main target, never FSR's temporary render-resolution one (which can be destroyed when
 * the shader pack is turned off). {@link MixinSkyRenderer_Upscaling} redirects its draws while FSR renders at a lower
 * resolution. Identity unless upscaling is active.
 */
@Mixin(LevelRenderer.class)
public class MixinLevelRenderer_UpscalingSky {
	@ModifyArg(method = "addSkyPass", require = 0, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/SkyRenderer;<init>(Lnet/minecraft/client/renderer/texture/TextureManager;Lnet/minecraft/client/resources/model/sprite/AtlasManager;Lcom/mojang/blaze3d/pipeline/RenderTarget;)V"),
		index = 2)
	private RenderTarget iris$fsrSkyRendererTarget(RenderTarget target) {
		return FsrUpscaler.unswappedMainTarget(target);
	}
}
