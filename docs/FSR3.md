# FSR 2/3-style temporal upscaling and FSR 3-style frame generation in Iris

Branch: `fsr3-fg`. Status: **experimental, untested on real hardware**. Everything is **off by default**; with the
default `iris.properties` no FSR object is created, no texture is allocated, no shader is compiled, the main render
target and the projection are never touched and no extra frame is presented, so rendering is identical to upstream Iris.
Both features need a shader pack (they hook into Iris's shader pipeline).

## Why a shader port instead of the native FidelityFX SDK

| | (a) Native FidelityFX SDK via JNI/Panama | (b) Port FSR 3 shaders into Iris (chosen) |
|---|---|---|
| OS | Needs a native DLL/.so per OS. FSR 3 FG in SDK 1.1.x is DX12 + Vulkan only; SDK 2.x (current `main`) is HLSL/DX12-focused | Pure Java + GLSL inside the mod jar: Windows and Linux, x64 and arm64 |
| Graphics API | Vulkan only. Minecraft 26.3 **defaults to OpenGL** (`PreferredGraphicsApi.DEFAULT` tries GL first, then Vulkan) | Runs through Iris's own backend abstraction, so OpenGL and Vulkan |
| Swapchain | `FfxFrameInterpolation` wants to own a replacement swapchain + present thread. Renderpearl hides `VkSwapchainKHR` behind `GpuSurface` | `GpuSurface` already exposes `acquireNextTexture()` / `blitFromTexture()` / `present()`, enough to present an extra frame |
| Shipping | Must build and ship binaries for every platform | Nothing extra to ship |

So Iris generates the frame with its own fullscreen passes (the same `CompositeProgram` machinery as the shader pack's
composite passes and Iris's color-space pass) and presents it itself.

## How it works

```
GameRenderer.render, before renderLevel()  (MixinGameRenderer_Upscaling; only with fsrUpscaling=true)
  FsrUpscaler.beforeRenderLevel()          -> swap GameRenderer.mainRenderTarget for a render-resolution target,
                                              offset the camera projection by a Halton jitter
level render (everything below runs at render resolution when upscaling)
  ... shader pack gbuffers / deferred / composite   (Iris sizes all pack buffers from the main target)
  IrisRenderingPipeline.beginHand()        -> pre-hand depth copy (used to detect hand pixels)
  finalPassRenderer.renderFinalPass()
  FsrMotionVectors.render()                -> fsr3fg/motion.fsh: camera-reprojection motion vectors (jitter removed)
                                              + depth disocclusion (RGBA16F), then copy depth for the next frame
  NeuralRendering.afterFinalPass()         -> ANR layer; uses these motion vectors if anrMotionVectorColortex=-1
  FsrUpscaler.upscaleAndRestore()          -> fsr3fg/fsr2_accumulate.fsh (display-res history) + fsr3fg/fsr2_rcas.fsh
                                              into the full-resolution main target, which is swapped back in
render3dHud (screen effects) at display resolution
GameRenderer.renderLevel TAIL
  colorSpaceConverter.process()
  FsrFrameGeneration.captureHudless()      -> copy of the HUD-less scene (ping-pong with the previous frame)
GameRenderer.render, after renderLevel()
  FsrUpscaler.afterRenderLevel()           -> undo the jitter; safety net that restores the main target
GUI / HUD is drawn
Minecraft.renderFrame, "swapchainBlit"      (MixinMinecraft_FrameGeneration)
  FsrFrameGeneration.beforeSwapchainBlit()
     fsr3fg/interpolate.fsh               -> frame halfway between N-1 and N, UI restored from the real frame
     surface.blitFromTexture(generated); submit; present; [pacing]; acquireNextTexture()
vanilla: blit real frame N; submit; present
```

### Motion vectors (`fsr3fg/motion.fsh`, written for Iris)

No shader-pack support is needed. For every pixel Iris reconstructs the NDC position from depth (Iris's pack
convention: `depth = 1 - raw`, `ndc = depth * 2 - 1`), and multiplies by one matrix built on the CPU in double
precision:

`reproject = prevProjection * prevModelView * translate(cameraPos - prevCameraPos) * inverse(projection * modelView)`

Output `rg = prevUv - uv` (FSR 2/3 convention), `b = 1` where the previous depth shows that something was in front of
this surface (disocclusion), `a = 1` for first-person hand pixels (depth differs from the pre-hand copy; their motion is
forced to 0 because the hand moves with the camera). A camera move over 32 blocks resets history.

Only camera motion is captured. Entities, particles, water animation and anything else that moves on its own get the
camera's motion only; that is what the optical-flow stage (milestone 2) is for.

### Upscaling (`FsrUpscaler`, `fsr3fg/fsr2_accumulate.fsh`, `fsr3fg/fsr2_rcas.fsh`, ported from FidelityFX SDK v1.1.4, MIT)

Render resolution: Minecraft's main render target is swapped (through a mixin accessor) for a target of
`display / ratio` per axis for the duration of the world render. Iris already sizes every shader-pack buffer
(`colortex*`, depth copies, `viewWidth`/`viewHeight`) from the main target, so the whole pack runs at render
resolution without pack changes. The vanilla sky renderer (which caches the main target) is redirected, and the
glowing-entity outline target is resized to match. Before the hand overlay, screen effects, the color-space pass,
screenshots and the GUI, the full-resolution target is back in place.

Jitter: Halton(2,3) minus 0.5, `8 * ratio^2` phases (FSR 2 scheme), applied as `P' = translate(jx, jy, 0) * P` to the
camera projection that `renderLevel()` copies, so terrain, entities, sky and the pack's `gbufferProjection` all see it.
The motion pass removes it (motion vectors are un-jittered, as FSR requires).

Accumulate (one fullscreen pass per display pixel): nearest-depth 3x3 motion-vector dilation; history reprojection
with 4x4 Lanczos-2 sampling and deringing; 3x3 Lanczos-2 (approximate) upsample of the jittered input with
FSR 2's kernel-bias and rectification-box logic in YCoCg; variance-box history rectification; FSR 2's accumulation
weights and signed temporal-reactive factor kept in the history alpha. The depth-clip factor is Iris's disocclusion
mask. Not ported: locks (thin-feature preservation), luma-instability, reactive / transparency-composition masks and
auto-exposure (packs output display-referred LDR). RCAS: FSR 1 `FsrRcasF`, `con = exp2(-(2 - 2 * sharpness))`,
sharpness 0 = off.

Known limitations: a shader pack's own TAA will also see the jitter (turn the pack's TAA off, FSR replaces it); the
first-person hand is not jittered (it stays sharp but is not anti-aliased by FSR); things that move on their own
(entities, particles, water) only get camera motion, so they can smear or ghost slightly; vanilla core shaders that
read `ScreenSize` during the world render still see the display size.

### Interpolation (`fsr3fg/interpolate.fsh`, ported from FidelityFX SDK v1.1.4, MIT)

Ported: `SampleTextureBilinear`, the game-vector branch of `computeInterpolatedColor` (disocclusion-driven
previous/current blend and out-of-screen fallbacks), and `CalculateStaticContentFactor` for UI. Replaced: the FSR 3
"game motion vector field" (a forward-scatter compute pass with atomics) by a backward fixed-point search on the
camera motion vectors, and the FSR 3 disocclusion-mask pass by the depth test above. Not yet ported: optical flow and
the inpainting pyramid (holes fall back to the current frame). Attribution: `third_party/FidelityFX/`.

### Presenting and pacing

The generated frame is presented from the render thread, right before vanilla presents the real frame, through the
public `GpuSurface` API. There is no separate present thread yet, so pacing is basic:

- `fsr3FrameGenPacing=vsync` (default): no extra wait. With **vsync on**, FIFO presentation shows each image for at
  least one refresh. Best results when the real frame rate is at or below half the refresh rate (e.g. 60 Hz display,
  30-60 real FPS; set Max Framerate to half your refresh rate).
- `sleep`: wait about half a real frame (capped at 25 ms) between the generated and the real present. For vsync off.
  This costs real frames, because the render thread is the one waiting.
- `none`: back to back, debug only.

Frame generation adds about half a frame of latency, like FSR 3.

## Configuration (`config/iris.properties`)

Edit with the game closed (Iris reads this file on start-up). Both features need a shader pack to be enabled. They can
be combined: frame generation then interpolates the upscaled, display-resolution frames.

```properties
fsr3FrameGen=false            # master switch
fsr3FrameGenPresent=true      # false = run the passes but never show generated frames
fsr3FrameGenPacing=vsync      # vsync | sleep | none
fsr3FrameGenDebugView=none    # none | motion | disocclusion | interpolated
fsrUpscaling=false            # FSR 2/3-style temporal upscaling
fsrUpscalingQuality=quality   # native_aa (1.0x) | quality (1.5x) | balanced (1.7x) | performance (2.0x) | ultra_performance (3.0x)
fsrUpscalingSharpness=0.2     # 0..1, RCAS
```

Debug views replace the real frame (no generated frames are presented while one is on):

- `motion`: red/green = |motion x/y| (scaled 20x), blue = hand. Standing still should be black; turning the camera
  should light up the whole screen smoothly, with the hand staying black.
- `disocclusion`: red = disoccluded, green = detected UI, blue = inpainting fallback.
- `interpolated`: shows only the generated frames, to judge interpolation quality.

## Logs (`logs/latest.log`)

- `[FSR3-FG] Enabled on the OpenGL backend (present=true, pacing=vsync, debugView=none).` on shader pack load
- `[FSR3-FG] Allocated 2560x1440 frame generation targets (color RGBA8_UNORM, depth D32_FLOAT).` on first frame / resize
- `[FSR3-FG] First generated frame (...)` once interpolation runs
- `[FSR3-FG] The <stage> failed; frame generation is disabled until the shader pack is reloaded.` + stack trace: the
  feature switched itself off; the game keeps rendering normally
- `[FSR3-FG] Setup failed ...`: shader compile/setup error, feature off for this pack
- `[FSR] The swapchain hook ... never ran`: the frame-loop mixin did not apply
- `[FSR-SR] Enabled on the OpenGL backend (quality=quality, 1.5x per axis, sharpness=0.2).` on shader pack load
- `[FSR-SR] Rendering the world at 1706x960 for a 2560x1440 display.` on the first upscaled frame
- `[FSR] Allocated 1706x960 motion vector targets (depth D32_FLOAT).`, `[FSR-SR] Allocated 2560x1440 upscale history.`
- `[FSR-SR] First upscaled frame: 1706x960 -> 2560x1440.`
- `[FSR-SR] The <stage> failed; upscaling is disabled until the shader pack is reloaded ...` + stack trace: native
  resolution from the next frame on
- `[FSR-SR] The upscale pass did not run this frame ...`: safety net restored the full-resolution target (normal once
  when switching dimension or reloading a pack; anything more is a bug)
- `[FSR] Motion vector setup failed` / `The motion vector pass failed`: both features off

## What is verified vs not

Verified here (Linux box, no GPU): both jars build; all four GLSL passes are accepted by Iris's own shader patcher,
the Vulkan-patched output compiles to SPIR-V with glslang and the unpatched sources compile as GLSL 330; the mixin
targets exist in the 26.3 bytecode (`ldc "swapchainBlit"` + `ProfilerFiller.push` in `Minecraft.renderFrame`,
`renderLevel()V` in `GameRenderer.render`, `GameRenderer.mainRenderTarget`, `SkyRenderer.renderTarget` reads in
`SkyRenderer.render`, `LevelRenderer.entityOutlineTarget`).

Not verified (needs a GPU): anything at runtime. In particular texture usage/format compatibility of the copies, the
Y orientation of the reprojection on Vulkan, presenting twice per loop on both backends, and pacing.

## Milestones

1. **(this branch)** Camera motion vectors, HUD-less capture, depth disocclusion, ported interpolation core, UI
   restore, double present, basic pacing, debug views, config, FSR upscaling plan + settings + jitter math.
2. FSR 3 frame-interpolation port proper: game-vector-field forward scatter (compute + image atomics), disocclusion
   mask, inpainting pyramid (Iris already ships FidelityFX SPD), then FidelityFX optical flow (luminance pyramid,
   `compute_optical_flow_v5`, filter, scene-change detection) so entities/particles interpolate correctly.
3. **(done)** FSR 2/3 temporal upscaling: world + pack passes at `fsrUpscalingQuality` resolution with Halton
   jitter, ported accumulate/upsample/rectify + RCAS, display-resolution result before the hand overlay, color-space
   pass and GUI. Follow-ups: locks, reactive mask from pack translucents, luma instability, mip bias for textures.
4. Present thread / better pacing (Vulkan first), multi-frame generation (2-3 generated frames), in-game settings
   screen, and optional pack-provided motion vectors for moving objects.
