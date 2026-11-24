package net.irisshaders.iris.mixin;

import net.irisshaders.iris.anr.fsr.FsrFrameGeneration;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets FSR 3-style frame generation present a generated frame right before vanilla copies the real frame to the
 * swapchain. {@code require = 0}: if a future Minecraft version moves this call, the game still starts and
 * Iris logs that the present hook is missing. {@link FsrFrameGeneration#beforeSwapchainBlit} does nothing unless {@code fsr3FrameGen=true}.
 */
@Mixin(Minecraft.class)
public class MixinMinecraft_FrameGeneration {
	@Inject(method = "renderFrame", at = @At(value = "INVOKE_STRING",
		target = "Lnet/minecraft/util/profiling/ProfilerFiller;push(Ljava/lang/String;)V", args = "ldc=swapchainBlit"), require = 0)
	private void iris$presentGeneratedFrame(boolean advanceGameTime, CallbackInfo ci) {
		FsrFrameGeneration.beforeSwapchainBlit(((Minecraft) (Object) this).windowSurface());
	}
}
