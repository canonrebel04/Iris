package net.irisshaders.iris.mixin;

import net.irisshaders.iris.anr.fsr.FsrUpscaler;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FSR upscaling: render the world at a lower resolution with a jittered projection. Both hooks do nothing unless
 * {@code fsrUpscaling=true} and a shader pack is active.
 */
@Mixin(GameRenderer.class)
public class MixinGameRenderer_Upscaling {
	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V"))
	private void iris$fsrBeforeRenderLevel(CallbackInfo ci) {
		FsrUpscaler.beforeRenderLevel((GameRenderer) (Object) this);
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V", shift = At.Shift.AFTER))
	private void iris$fsrAfterRenderLevel(CallbackInfo ci) {
		FsrUpscaler.afterRenderLevel((GameRenderer) (Object) this);
	}
}
