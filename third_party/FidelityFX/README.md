# AMD FidelityFX SDK (MIT)

Parts of Iris's FSR 3-style frame generation are ported from the AMD FidelityFX SDK, which is released under the MIT
license (see `LICENSE.txt` in this folder; a copy also ships inside the mod jar at `fsr3fg/LICENSE-FidelityFX.txt`).

- Upstream: https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK
- Version used: tag `v1.1.4` (the last release that ships GLSL callbacks for frame interpolation and optical flow)

| Iris file | Ported from | Notes |
|---|---|---|
| `common/src/main/resources/fsr3fg/interpolate.fsh` | `sdk/include/FidelityFX/gpu/frameinterpolation/ffx_frameinterpolation.h`, `ffx_frameinterpolation_common.h` | HLSL/FFX-macro code rewritten as a GLSL fragment shader. Game-vector branch of `computeInterpolatedColor`, `SampleTextureBilinear`, `GetBilinearSamplingData`, `CalculateStaticContentFactor`. Optical flow and inpainting not ported yet. |

Files written from scratch for Iris (not derived from FidelityFX source): `fsr3fg/motion.fsh`, `fsr3fg/fullscreen.vsh`,
and everything under `net.irisshaders.iris.anr.fsr`. `FsrJitter` and `FsrUpscalingQuality` reimplement the jitter
sequence and quality ratios described in AMD's public FSR 2 documentation.

Iris also already contained `assets/iris/shaders/mipmaps/ffx_core.h` / `ffx_spd.h` (FidelityFX SPD, MIT) before this
work; those carry their own license headers.
