# Iris neural rendering integration (ANR)

This is an Iris-side integration layer for features offered by [Apex Neural Rendering](https://github.com/apex-compute/ANR):

- **FG** — frame generation
- **SR** — super-resolution upscaling
- **RR** — ray-reconstruction denoising

It does **not** include, vendor, link, or ship any ANR source or binary. Apex has not published an SDK or a license for those components. Until that changes, the default backend is a no-op and rendering is identical to upstream Iris.

## Architecture

```
IrisRenderingPipeline.finalizeLevelRendering()
  compositeRenderer.renderAll()
  finalPassRenderer.renderFinalPass()     <-- scene color is now on the main render target
  NeuralRendering.afterFinalPass(...)     <-- no-op unless anrEnable=true and a native lib loaded
      NeuralRenderingLoader
          DisabledNeuralRenderingBackend  (default)
          NativeNeuralRenderingBackend    (Java 25 FFM / Panama, user-supplied path only)
```

`net.irisshaders.iris.anr` lives in the `common` sources, so both Fabric and NeoForge get it.

When `anrEnable` is false (the default):

- no native library is loaded
- `afterFinalPass` returns immediately
- no textures are sampled and no matrices are copied for this path

When `anrEnable` is true, Iris tries to load the library named by `anrLibraryPath`. If the path is empty, the file is missing, the ABI does not match, or Panama cannot open the library, Iris logs a clear message and stays on the no-op backend. Rendering still does not change.

A successful `evaluate` call currently **does not present** generated or upscaled frames back to the Minecraft swapchain. That work is blocked on a licensed SDK and on frame-pacing / presentation design. The hook exists so a future backend can receive the right inputs.

## What is gathered each frame

After the shader pack's composite and final pass:

| Input | Source |
|---|---|
| Color | `Minecraft.gameRenderer.mainRenderTarget().getColorTextureView()` — the pack's final output, **before** Iris's color-space converter (`finalizeGameRendering`) |
| Depth | `RenderTargets.getDepthTexture()`, falling back to the main target's depth |
| Motion vectors | Optional. If `anrMotionVectorColortex` is a valid colortex index, that buffer's current (non-alt) view is passed through. There is **no automatic detection** of pack layouts. |
| View / projection | `CapturedRenderingState` `gbufferModelView` and `gbufferProjection` (current and previous frame; previous is stored by this layer) |
| Frame delta | `SystemTimeUniforms.TIMER.getLastFrameTime()` (seconds) |
| Jitter | Always `(0, 0)` today — not sourced from vanilla or the pack |
| Exposure | Always `1.0` today — Iris does not currently expose a stable auto-exposure uniform |

Texture handles are graphics-API specific: an OpenGL `GLuint` or a Vulkan `VkImage`, tagged in the ABI.

### Motion vectors

Shader packs do not have a single standard colortex for motion vectors. Set `anrMotionVectorColortex` to the index your pack uses (for example `4` for `colortex4`) if you know it.

When `anrMotionVectorColortex=-1` and FSR 3 frame generation is on (`fsr3FrameGen=true`), the layer receives Iris's own camera-reprojection motion vectors instead (RGBA16F, `rg = prevUv - uv`). See [FSR3.md](FSR3.md).

## Configuration (`iris.properties`)

All options default to off / empty. They are only needed if you are experimenting with a self-built native library that implements the ABI below.

```properties
anrEnable=false
anrFrameGeneration=false
anrSuperResolution=false
anrSuperResolutionQuality=balanced
anrSuperResolutionScale=1.0
anrRayReconstruction=false
anrLibraryPath=
anrMotionVectorColortex=-1
```

- `anrSuperResolutionQuality`: `performance`, `balanced`, or `quality`
- `anrSuperResolutionScale`: input-to-output scale the backend should aim for (e.g. `0.5` for 50% render scale). `1.0` means no spatial upscale.
- `anrLibraryPath`: absolute path, or a path relative to the game directory, of a native library exporting the symbols below. Iris never downloads this file.
- `anrMotionVectorColortex`: pack colortex index, or `-1` to omit motion vectors

There is no in-game UI for these options yet. Edit the file and restart (or reload the pack after restarting once so the file has been created). The native library is loaded at most once per session; changing the path at runtime is not picked up until restart.

If the JVM refuses Panama access, launch with `--enable-native-access=ALL-UNNAMED` (or a narrower module list) in addition to setting the path.

## Native ABI

A C header sketch is in [`iris_anr_abi.h`](iris_anr_abi.h). Summary:

- `iris_anr_abi_version` must return `1`
- `iris_anr_capabilities` is a bitfield: `1` = FG, `2` = SR, `4` = RR
- `iris_anr_init` / `iris_anr_shutdown` bookend the session
- `iris_anr_evaluate` receives one real frame's inputs; it must not assume Iris will display any output it produces

Structs are packed with natural C alignment on LP64 (see the header). Matrices are 4×4 column-major. Texture `handle` is `0` when a resource is unavailable.

## Licensing

Iris is licensed under **LGPL-3.0-only** (with an AGPL-3.0 `glsl-transformer` dependency). The public [apex-compute/ANR](https://github.com/apex-compute/ANR) repository currently has **no published license**. ANR FG is distributed as a closed beta (OptiScaler injection into DLSS-G games). Its source is listed as "coming soon."

Until Apex grants a license that allows redistribution and linking, Iris **must not** vendor or ship ANR code or binaries. This layer is only a contract and a hook.

OptiScaler is GPL-3.0; it is also not bundled here, and its injector model does not apply to the Minecraft Java client.

## Current status and TODOs

Done:

- Backend interface, no-op default, optional FFM loader
- Config keys, all off by default
- After-final-pass hook with color, depth, optional pack motion vectors, and camera matrices

Not done:

- Presenting FG / SR / RR output into the game (frame pacing, vsync, HUD / hand, swapchain)
- A camera-only motion vector pass
- Sourcing TAA jitter and exposure
- Hooking the vanilla (no shader pack) pipeline
- An in-game settings screen
- Loading a real ANR library (blocked on SDK + license)

## In-game testing

With default config, start Minecraft 26.3 with this build and a shader pack. Behavior and performance should match upstream `26.3-new`. You should not see `[ANR]` lines in the log.

To test the loader path without a real backend: set `anrEnable=true` and leave `anrLibraryPath` empty, then confirm the log says the feature is staying disabled and the image is unchanged. Pointing `anrLibraryPath` at a missing file should warn and stay disabled. Do not drop an unlicensed ANR binary into the game directory.
