# AMD FidelityFX SDK (MIT)

Parts of Iris's FSR 2/3-style upscaling and FSR 3-style frame generation are ported from the AMD FidelityFX SDK, which is released under the MIT
license (see `LICENSE.txt` in this folder; a copy also ships inside the mod jar at `fsr3fg/LICENSE-FidelityFX.txt`).

- Upstream: https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK
- Version used: tag `v1.1.4` (the last release that ships GLSL callbacks for frame interpolation and optical flow)

| Iris file | Ported from | Notes |
|---|---|---|
| `common/src/main/resources/fsr3fg/interpolate.fsh` | `sdk/include/FidelityFX/gpu/frameinterpolation/ffx_frameinterpolation.h`, `ffx_frameinterpolation_common.h` | HLSL/FFX-macro code rewritten as a GLSL fragment shader. Game-vector branch of `computeInterpolatedColor`, `SampleTextureBilinear`, `GetBilinearSamplingData`, `CalculateStaticContentFactor`. Optical flow and inpainting not ported yet. |
| `common/src/main/resources/fsr3fg/fsr2_accumulate.fsh` | `sdk/include/FidelityFX/gpu/fsr2/ffx_fsr2_accumulate.h`, `ffx_fsr2_upsample.h`, `ffx_fsr2_reproject.h`, `ffx_fsr2_sample.h`, `ffx_fsr2_common.h`, `ffx_fsr2_reconstruct_dilated_velocity_and_previous_depth.h` | FSR 2 accumulate pass as a GLSL fragment shader: Lanczos upsample, rectification box, history reprojection/rectification, accumulation weights, temporal reactive factor, inline nearest-depth MV dilation. No locks, luma instability, reactive masks or auto-exposure. |
| `common/src/main/resources/fsr3fg/fsr2_rcas.fsh` | `sdk/include/FidelityFX/gpu/fsr1/ffx_fsr1.h` (`FsrRcasF`), `fsr2/ffx_fsr2_rcas.h` | RCAS sharpening as a GLSL fragment shader. |

Files written from scratch for Iris (not derived from FidelityFX source): `fsr3fg/motion.fsh`, `fsr3fg/fullscreen.vsh`,
and everything under `net.irisshaders.iris.anr.fsr`. `FsrJitter` and `FsrUpscalingQuality` reimplement the jitter
sequence and quality ratios described in AMD's public FSR 2 documentation.

Iris also already contained `assets/iris/shaders/mipmaps/ffx_core.h` / `ffx_spd.h` (FidelityFX SPD, MIT) before this
work; those carry their own license headers.
