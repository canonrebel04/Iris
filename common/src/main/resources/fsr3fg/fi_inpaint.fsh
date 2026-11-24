#version 330 core

// This file is part of the FidelityFX SDK.
//
// Copyright (C) 2024 Advanced Micro Devices, Inc.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files(the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and /or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions :
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
// THE SOFTWARE.
//
// Iris port (GLSL fragment shader) of FidelityFX SDK v1.1.4:
//   sdk/include/FidelityFX/gpu/frameinterpolation/ffx_frameinterpolation_inpainting.h (ComputeInpaintingLevel,
//       ComputeInpainting, computeInpainting incl. the HUD-less UI restore)
//   sdk/include/FidelityFX/gpu/frameinterpolation/ffx_frameinterpolation_common.h (GetBilinearSamplingData,
//       MinDividedByMax, CalculateStaticContentFactor, RawRGBToLinear for sRGB back buffers)
// Modifications by the Iris contributors:
//   - Fragment shader writing the final generated frame; pyramid levels are separate textures (up to 10).
//   - Debug output from interpolate.fsh (alpha < 0) is passed through unchanged.
// See third_party/FidelityFX/LICENSE.txt.

uniform sampler2D fgInterpolated; // interpolate.fsh output: rgb, a = inpainting weight
uniform sampler2D fgCurrColor;    // current HUD-less frame
uniform sampler2D fgFinalColor;   // current presented frame (with GUI)
uniform sampler2D fgPyramid0;
uniform sampler2D fgPyramid1;
uniform sampler2D fgPyramid2;
uniform sampler2D fgPyramid3;
uniform sampler2D fgPyramid4;
uniform sampler2D fgPyramid5;
uniform sampler2D fgPyramid6;
uniform sampler2D fgPyramid7;
uniform sampler2D fgPyramid8;
uniform sampler2D fgPyramid9;
uniform vec4 fgInpaintParams; // x = number of valid pyramid levels

in vec2 uv;
out vec4 outColor;

const float FFX_FRAMEINTERPOLATION_EPSILON = 1e-03;

struct BilinearSamplingData {
    ivec2 iOffsets[4];
    float fWeights[4];
    ivec2 iBasePos;
};

BilinearSamplingData GetBilinearSamplingData(vec2 fUv, ivec2 iSize) {
    BilinearSamplingData data;
    vec2 fPxSample = (fUv * vec2(iSize)) - vec2(0.5, 0.5);
    data.iBasePos = ivec2(floor(fPxSample));
    vec2 fPxFrac = fract(fPxSample);
    data.iOffsets[0] = ivec2(0, 0);
    data.iOffsets[1] = ivec2(1, 0);
    data.iOffsets[2] = ivec2(0, 1);
    data.iOffsets[3] = ivec2(1, 1);
    data.fWeights[0] = (1.0 - fPxFrac.x) * (1.0 - fPxFrac.y);
    data.fWeights[1] = (fPxFrac.x) * (1.0 - fPxFrac.y);
    data.fWeights[2] = (1.0 - fPxFrac.x) * (fPxFrac.y);
    data.fWeights[3] = (fPxFrac.x) * (fPxFrac.y);
    return data;
}

bool IsOnScreen(ivec2 pos, ivec2 size) {
    return all(lessThan(uvec2(pos), uvec2(size)));
}

float MinDividedByMax(float v0, float v1) {
    float m = max(v0, v1);
    return m != 0.0 ? min(v0, v1) / m : 0.0;
}

float CalculateStaticContentFactor(vec3 fCurrentInterpolationSource, vec3 fPresentColor) {
    vec3 fFactor = clamp(vec3(
        clamp((1.0 - MinDividedByMax(fCurrentInterpolationSource.r, fPresentColor.r)) / 0.1, 0.0, 1.0),
        clamp((1.0 - MinDividedByMax(fCurrentInterpolationSource.g, fPresentColor.g)) / 0.1, 0.0, 1.0),
        clamp((1.0 - MinDividedByMax(fCurrentInterpolationSource.b, fPresentColor.b)) / 0.1, 0.0, 1.0)
    ), 0.0, 1.0);
    return max(fFactor.x, max(fFactor.y, fFactor.z));
}

vec3 RawRGBToLinear(vec3 c) {
    c = max(c, 0.0);
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c));
}

ivec2 PyramidSize(int level) {
    if (level == 0) return textureSize(fgPyramid0, 0);
    if (level == 1) return textureSize(fgPyramid1, 0);
    if (level == 2) return textureSize(fgPyramid2, 0);
    if (level == 3) return textureSize(fgPyramid3, 0);
    if (level == 4) return textureSize(fgPyramid4, 0);
    if (level == 5) return textureSize(fgPyramid5, 0);
    if (level == 6) return textureSize(fgPyramid6, 0);
    if (level == 7) return textureSize(fgPyramid7, 0);
    if (level == 8) return textureSize(fgPyramid8, 0);
    return textureSize(fgPyramid9, 0);
}

vec4 LoadInpaintingPyramid(int level, ivec2 p) {
    if (level == 0) return texelFetch(fgPyramid0, p, 0);
    if (level == 1) return texelFetch(fgPyramid1, p, 0);
    if (level == 2) return texelFetch(fgPyramid2, p, 0);
    if (level == 3) return texelFetch(fgPyramid3, p, 0);
    if (level == 4) return texelFetch(fgPyramid4, p, 0);
    if (level == 5) return texelFetch(fgPyramid5, p, 0);
    if (level == 6) return texelFetch(fgPyramid6, p, 0);
    if (level == 7) return texelFetch(fgPyramid7, p, 0);
    if (level == 8) return texelFetch(fgPyramid8, p, 0);
    return texelFetch(fgPyramid9, p, 0);
}

vec4 ComputeInpaintingLevel(vec2 fUv, int iMipLevel, ivec2 iTexSize) {
    BilinearSamplingData bilinearInfo = GetBilinearSamplingData(fUv, iTexSize);
    vec4 fColor = vec4(0.0);

    for (int iSampleIndex = 0; iSampleIndex < 4; iSampleIndex++) {
        ivec2 iSamplePos = bilinearInfo.iBasePos + bilinearInfo.iOffsets[iSampleIndex];

        if (IsOnScreen(iSamplePos, iTexSize)) {
            vec4 fSample = LoadInpaintingPyramid(iMipLevel, iSamplePos);
            float fWeight = bilinearInfo.fWeights[iSampleIndex] * float(fSample.w > 0.0);
            fColor += vec4(fSample.rgb * fWeight, fWeight);
        }
    }

    return fColor;
}

vec3 ComputeInpainting(vec2 fUv, vec3 fallback) {
    vec4 fColor = vec4(0.0);
    int levels = int(fgInpaintParams.x + 0.5);

    for (int iMipLevel = 0; iMipLevel < 10; iMipLevel++) {
        if (iMipLevel >= levels) {
            break;
        }

        vec4 fMipColor = ComputeInpaintingLevel(fUv, iMipLevel, PyramidSize(iMipLevel));

        if (fMipColor.w > 0.0) {
            vec3 fNormalizedMipColor = fMipColor.rgb / fMipColor.w;
            float fMipWeight = pow(1.0 - float(iMipLevel) / 10.0, 3.0) * fMipColor.w;
            fColor += vec4(fNormalizedMipColor, 1.0) * fMipWeight;
        }
    }

    // Iris: guard the division (FSR 3 assumes some level is always valid).
    return fColor.w > 0.0 ? fColor.rgb / fColor.w : fallback;
}

void main() {
    ivec2 texSize = textureSize(fgCurrColor, 0);
    ivec2 iPxPos = clamp(ivec2(uv * vec2(texSize)), ivec2(0), texSize - 1);
    vec2 fUv = (vec2(iPxPos) + 0.5) / vec2(texSize);

    vec4 fInterpolatedColor = texelFetch(fgInterpolated, iPxPos, 0);

    if (fInterpolatedColor.w < 0.0) {
        // Debug view from interpolate.fsh.
        outColor = vec4(fInterpolatedColor.rgb, 1.0);
        return;
    }

    vec3 fCurrentInterpolationSource = texelFetch(fgCurrColor, iPxPos, 0).rgb;
    float fInPaintingWeight = fInterpolatedColor.w;

    if (fInPaintingWeight > FFX_FRAMEINTERPOLATION_EPSILON) {
        fInterpolatedColor.rgb = mix(fInterpolatedColor.rgb, ComputeInpainting(fUv, fCurrentInterpolationSource), fInPaintingWeight);
    }

    // HUD-less attached: restore UI wherever the presented frame differs from the HUD-less one.
    vec3 fPresentColor = texelFetch(fgFinalColor, iPxPos, 0).rgb;

    if (any(greaterThan(abs(fCurrentInterpolationSource - fPresentColor), vec3(0.0)))) {
        float fStaticFactor = CalculateStaticContentFactor(RawRGBToLinear(fCurrentInterpolationSource), RawRGBToLinear(fPresentColor));

        if (fStaticFactor > FFX_FRAMEINTERPOLATION_EPSILON) {
            fInterpolatedColor.rgb = mix(fInterpolatedColor.rgb, fPresentColor, fStaticFactor);
        }
    }

    outColor = vec4(fInterpolatedColor.rgb, 1.0);
}
