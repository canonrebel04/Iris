# AMD FidelityFX SDK (MIT)

Parts of Iris's FSR 2/3-style upscaling and FSR 3-style frame generation are ported from the AMD FidelityFX SDK, which is released under the MIT
license (see `LICENSE.txt` in this folder; a copy also ships inside the mod jar at `fsr3fg/LICENSE-FidelityFX.txt`).

- Upstream: https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK
- Version used: tag `v1.1.4` (the last release that ships GLSL callbacks for frame interpolation and optical flow)

| Iris file | Ported from | Notes |
|---|---|---|
| `common/src/main/resources/fsr3fg/interpolate.fsh` | `sdk/include/FidelityFX/gpu/frameinterpolation/ffx_frameinterpolation.h`, `ffx_frameinterpolation_common.h` | HLSL/FFX-macro code rewritten as a GLSL fragment shader. Game-vector branch of `computeInterpolatedColor`, `SampleTextureBilinear`, `GetBilinearSamplingData`. Optical flow not ported. |
| `common/src/main/resources/fsr3fg/fi_scatter.csh` | `frameinterpolation/ffx_frameinterpolation_game_motion_vector_field.h`, `ffx_frameinterpolation_reconstruct_previous_depth.h`, `ffx_frameinterpolation_common.h`, `ffx_frameinterpolation_callbacks_glsl.h` | Game motion vector field forward scatter (packing, priorities, primary/secondary vectors, image atomics) and interpolated-frame depth reconstruction, GLSL compute. |
| `common/src/main/resources/fsr3fg/fi_resolve.csh` | `frameinterpolation/ffx_frameinterpolation_disocclusion_mask.h`, `ffx_frameinterpolation_common.h` | Disocclusion mask and vector-field unpacking, GLSL compute; vector-field holes use an Iris search instead of the MV inpainting pyramid. |
| `common/src/main/resources/fsr3fg/fi_inpaint.fsh` | `frameinterpolation/ffx_frameinterpolation_inpainting.h`, `ffx_frameinterpolation_common.h` | Colour inpainting from the pyramid and HUD-less UI restore (`CalculateStaticContentFactor`), GLSL fragment. |
| `common/src/main/resources/fsr3fg/fsr2_accumulate.fsh` | `sdk/include/FidelityFX/gpu/fsr2/ffx_fsr2_accumulate.h`, `ffx_fsr2_upsample.h`, `ffx_fsr2_reproject.h`, `ffx_fsr2_sample.h`, `ffx_fsr2_common.h`, `ffx_fsr2_reconstruct_dilated_velocity_and_previous_depth.h` | FSR 2 accumulate pass as a GLSL fragment shader: Lanczos upsample, rectification box, history reprojection/rectification, accumulation weights, temporal reactive factor, inline nearest-depth MV dilation. No locks, luma instability, reactive masks or auto-exposure. |
| `common/src/main/resources/fsr3fg/fsr2_rcas.fsh` | `sdk/include/FidelityFX/gpu/fsr1/ffx_fsr1.h` (`FsrRcasF`), `fsr2/ffx_fsr2_rcas.h` | RCAS sharpening as a GLSL fragment shader. |

Files written from scratch for Iris (not derived from FidelityFX source): `fsr3fg/motion.fsh`, `fsr3fg/fullscreen.vsh`,
`fsr3fg/fi_clear.csh`, `fsr3fg/fi_pyramid.fsh` (simple 2x2 weighted downsample standing in for FidelityFX SPD),
and everything under `net.irisshaders.iris.anr.fsr`. `FsrJitter` and `FsrUpscalingQuality` reimplement the jitter
sequence and quality ratios described in AMD's public FSR 2 documentation.

Iris also already contained `assets/iris/shaders/mipmaps/ffx_core.h` / `ffx_spd.h` (FidelityFX SPD, MIT) before this
work; those carry their own license headers.
