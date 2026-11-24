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
// Iris port (GLSL fragment shader) of parts of FidelityFX SDK v1.1.4
//   sdk/include/FidelityFX/gpu/frameinterpolation/ffx_frameinterpolation.h        (SampleTextureBilinear,
//       computeInterpolatedColor game-vector branch, computeFrameinterpolation)
//   sdk/include/FidelityFX/gpu/frameinterpolation/ffx_frameinterpolation_common.h (GetBilinearSamplingData,
//       IsInRect, MinDividedByMax, CalculateStaticContentFactor)
// Modifications by the Iris contributors:
//   - The FSR 3 "game motion vector field" (built by a forward-scatter compute pass) is replaced by a
//     backward fixed-point search on the camera-reprojection motion vectors produced by motion.fsh.
//   - The FSR 3 disocclusion mask pass is replaced by the depth test in motion.fsh.
//   - The optical-flow branch and the inpainting pyramid are not ported yet; fully disoccluded pixels fall
//     back to the current frame.
//   - UI is restored with CalculateStaticContentFactor against the HUD-less frame, as FSR 3 does when an
//     application provides a HUD-less color buffer.
// See third_party/FidelityFX/LICENSE.txt.

uniform sampler2D fgPrevColor;  // HUD-less color of the previous real frame
uniform sampler2D fgCurrColor;  // HUD-less color of the current real frame
uniform sampler2D fgMotion;     // output of motion.fsh for the current frame
uniform sampler2D fgFinalColor; // current real frame including GUI/HUD
uniform vec4 fgSettings;        // x = debug view (0 none, 1 motion, 2 disocclusion, 3 interpolated), y = frames since reset

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

bool IsInRect(ivec2 pos, ivec2 iRectCorner, ivec2 iRectSize) {
    return (pos.x >= iRectCorner.x && pos.x < (iRectSize.x + iRectCorner.x) && pos.y >= iRectCorner.y && pos.y < (iRectSize.y + iRectCorner.y));
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

struct InterpolationSourceColor {
    vec3 fRaw;
    float fBilinearWeightSum;
};

InterpolationSourceColor SampleTextureBilinear(bool isCurrent, vec2 fUv, vec2 fMotionVector, ivec2 texSize) {
    InterpolationSourceColor result;

    vec2 fReprojectedUv = fUv + fMotionVector;
    BilinearSamplingData bilinearInfo = GetBilinearSamplingData(fReprojectedUv, texSize);

    vec3 fColor = vec3(0.0);
    float fWeightSum = 0.0;

    for (int iSampleIndex = 0; iSampleIndex < 4; iSampleIndex++) {
        ivec2 iSamplePos = bilinearInfo.iBasePos + bilinearInfo.iOffsets[iSampleIndex];

        if (IsInRect(iSamplePos, ivec2(0), texSize)) {
            float fWeight = bilinearInfo.fWeights[iSampleIndex];

            if (isCurrent) {
                fColor += texelFetch(fgCurrColor, iSamplePos, 0).rgb * fWeight;
            } else {
                fColor += texelFetch(fgPrevColor, iSamplePos, 0).rgb * fWeight;
            }

            fWeightSum += fWeight;
        }
    }

    fColor = (fWeightSum != 0.0) ? fColor / fWeightSum : vec3(0.0);

    result.fRaw = fColor;
    result.fBilinearWeightSum = fWeightSum;
    return result;
}

bool insideUv(vec2 p) {
    return p.x > 0.0 && p.x < 1.0 && p.y > 0.0 && p.y < 1.0;
}

// Iris replacement for LoadInpaintedGameFieldMv: find the current-frame pixel c whose motion carries it to
// the interpolated position (c + 0.5 * mv(c) == uv), then return the half vector towards the previous frame.
vec4 findGameVector(vec2 fUv) {
    vec2 c = fUv;

    for (int i = 0; i < 4; i++) {
        c = fUv - 0.5 * texture(fgMotion, c).xy;
    }

    vec4 motion = texture(fgMotion, c);
    return vec4(0.5 * motion.xy, motion.z, motion.w);
}

vec3 computeInterpolatedColor(vec2 fUvInScreenSpace, ivec2 texSize, vec4 gameMv, out float fInPaintingWeight) {
    fInPaintingWeight = 0.0;

    bool bPosOutside = !insideUv(fUvInScreenSpace + gameMv.xy);
    bool bNegOutside = !insideUv(fUvInScreenSpace - gameMv.xy);

    // x = 1: the interpolated pixel is visible in the previous frame, y = 1: visible in the current frame.
    vec2 fDisocclusionFactor = vec2(1.0 - gameMv.z, 1.0);

    InterpolationSourceColor fPrevColorGame = SampleTextureBilinear(false, fUvInScreenSpace, +gameMv.xy, texSize);
    InterpolationSourceColor fCurrColorGame = SampleTextureBilinear(true, fUvInScreenSpace, -gameMv.xy, texSize);

    fDisocclusionFactor.x *= float(!bPosOutside);
    fDisocclusionFactor.y *= float(!bNegOutside);

    fInPaintingWeight = clamp(max(fInPaintingWeight, float(length(fDisocclusionFactor) <= FFX_FRAMEINTERPOLATION_EPSILON)), 0.0, 1.0);

    float t = 0.5;
    t += 0.5 * (1.0 - fDisocclusionFactor.x);
    t -= 0.5 * (1.0 - fDisocclusionFactor.y);

    vec3 fInterpolatedColor = mix(fPrevColorGame.fRaw, fCurrColorGame.fRaw, clamp(t, 0.0, 1.0));

    if (fPrevColorGame.fBilinearWeightSum == 0.0) {
        fInterpolatedColor = fCurrColorGame.fRaw;
    } else if (fCurrColorGame.fBilinearWeightSum == 0.0) {
        fInterpolatedColor = fPrevColorGame.fRaw;
    }

    if (fPrevColorGame.fBilinearWeightSum == 0.0 && fCurrColorGame.fBilinearWeightSum == 0.0) {
        fInPaintingWeight = 1.0;
    }

    return fInterpolatedColor;
}

void main() {
    ivec2 texSize = textureSize(fgCurrColor, 0);
    ivec2 iPxPos = clamp(ivec2(uv * vec2(texSize)), ivec2(0), texSize - 1);
    int debugView = int(fgSettings.x + 0.5);

    vec4 gameMv = findGameVector(uv);
    vec3 fColor;
    float fInPaintingWeight = 0.0;

    if (fgSettings.y < 0.5) {
        // Just reset: copy the current back buffer and don't interpolate.
        fColor = texelFetch(fgCurrColor, iPxPos, 0).rgb;
    } else {
        fColor = computeInterpolatedColor(uv, texSize, gameMv, fInPaintingWeight);

        if (fInPaintingWeight > 0.0) {
            // Inpainting pyramid not ported yet: fall back to the current frame.
            fColor = mix(fColor, texelFetch(fgCurrColor, iPxPos, 0).rgb, fInPaintingWeight);
        }
    }

    // Restore UI: wherever the presented frame differs from the HUD-less frame, show the presented frame.
    vec3 hudless = texelFetch(fgCurrColor, iPxPos, 0).rgb;
    vec3 present = texelFetch(fgFinalColor, iPxPos, 0).rgb;
    float staticFactor = CalculateStaticContentFactor(hudless, present);

    if (debugView == 1) {
        vec2 mv = 2.0 * gameMv.xy;
        outColor = vec4(clamp(abs(mv) * 20.0, 0.0, 1.0), gameMv.w, 1.0);
        return;
    }

    if (debugView == 2) {
        outColor = vec4(gameMv.z, staticFactor, fInPaintingWeight, 1.0);
        return;
    }

    outColor = vec4(mix(fColor, present, staticFactor), 1.0);
}
