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
// Iris port (GLSL fragment shader) of the FSR 2 accumulate pass from FidelityFX SDK v1.1.4:
//   sdk/include/FidelityFX/gpu/fsr2/ffx_fsr2_accumulate.h (InitParams, Accumulate, RectifyHistory,
//       ComputeBaseAccumulationWeight, ComputeTemporalReactiveFactor)
//   sdk/include/FidelityFX/gpu/fsr2/ffx_fsr2_upsample.h   (ComputeUpsampledColorAndWeight, Deringing,
//       GetUpsampleLanczosWeight, ComputeMaxKernelWeight; the non-Xbox 3x3 path)
//   sdk/include/FidelityFX/gpu/fsr2/ffx_fsr2_reproject.h  (ReprojectHistoryColor, ComputeReprojectedUVs,
//       Lanczos history sampling with deringing)
//   sdk/include/FidelityFX/gpu/fsr2/ffx_fsr2_sample.h     (Lanczos2, Lanczos2ApproxSq)
//   sdk/include/FidelityFX/gpu/fsr2/ffx_fsr2_common.h     (RectificationBox, RGB <-> YCoCg)
//   sdk/include/FidelityFX/gpu/fsr2/ffx_fsr2_reconstruct_dilated_velocity_and_previous_depth.h (nearest-depth
//       motion vector dilation, done inline here instead of in a separate pass)
// Modifications by the Iris contributors:
//   - HLSL/compute -> GLSL 330 fullscreen fragment shader (one invocation per display pixel), Iris samplers.
//   - No lock, luma-instability, reactive or transparency/composition masks, no auto-exposure (inputs are the shader
//     pack's display-referred LDR output, exposure 1). The depth-clip factor is the disocclusion mask produced by
//     Iris's camera-reprojection motion pass.
//   - UV/NDC use the OpenGL convention (+Y up) on both backends; the jitter is in the same convention.

// Render-resolution inputs
uniform sampler2D fsrColor;   // shader pack output (after the final pass), render resolution
uniform sampler2D fsrMotion;  // Iris motion pass: rg = prevUv - uv (un-jittered), b = disocclusion, a = hand
uniform sampler2D fsrDepth;   // raw depth (reversed Z: larger = closer)
// Display-resolution input
uniform sampler2D fsrHistory; // previous output: rgb = color, a = signed temporal reactive factor

uniform vec4 fsrSizes;  // xy = render size, zw = display size
uniform vec4 fsrParams; // xy = jitter in render pixels, z = frames since reset (0 = reset)

in vec2 uv;
out vec4 outHistory;

const float FSR2_EPSILON = 1e-03;
const float FSR2_FLT_MAX = 3.402823466e+38;
const float FSR2_FP16_MAX = 65504.0;
const float fUpsampleLanczosWeightScale = 1.0 / 12.0;
const float fMaxAccumulationLanczosWeight = 1.0;

vec2 RenderSize() { return fsrSizes.xy; }
vec2 DisplaySize() { return fsrSizes.zw; }
vec2 DownscaleFactor() { return fsrSizes.xy / fsrSizes.zw; }
vec2 Jitter() { return fsrParams.xy; }

float saturate(float x) { return clamp(x, 0.0, 1.0); }

// ---- ffx_fsr2_common.h ----------------------------------------------------------------------------------------------

struct RectificationBox {
    vec3 boxCenter;
    vec3 boxVec;
    vec3 aabbMin;
    vec3 aabbMax;
    float fBoxCenterWeight;
};

void RectificationBoxAddSample(bool bInitialSample, inout RectificationBox box, vec3 colorSample, float fSampleWeight) {
    if (bInitialSample) {
        box.aabbMin = colorSample;
        box.aabbMax = colorSample;
        vec3 weightedSample = colorSample * fSampleWeight;
        box.boxCenter = weightedSample;
        box.boxVec = colorSample * weightedSample;
        box.fBoxCenterWeight = fSampleWeight;
    } else {
        box.aabbMin = min(box.aabbMin, colorSample);
        box.aabbMax = max(box.aabbMax, colorSample);
        vec3 weightedSample = colorSample * fSampleWeight;
        box.boxCenter += weightedSample;
        box.boxVec += colorSample * weightedSample;
        box.fBoxCenterWeight += fSampleWeight;
    }
}

void RectificationBoxComputeVarianceBoxData(inout RectificationBox box) {
    box.fBoxCenterWeight = abs(box.fBoxCenterWeight) > FSR2_EPSILON ? box.fBoxCenterWeight : 1.0;
    box.boxCenter /= box.fBoxCenterWeight;
    box.boxVec /= box.fBoxCenterWeight;
    box.boxVec = sqrt(abs(box.boxVec - box.boxCenter * box.boxCenter));
}

vec3 YCoCgToRGB(vec3 c) {
    return vec3(c.x + c.y - c.z, c.x + c.z, c.x - c.y - c.z);
}

vec3 RGBToYCoCg(vec3 c) {
    return vec3(0.25 * c.r + 0.5 * c.g + 0.25 * c.b, 0.5 * c.r - 0.5 * c.b, -0.25 * c.r + 0.5 * c.g - 0.25 * c.b);
}

bool IsOnScreen(ivec2 pos, ivec2 size) {
    return all(lessThan(uvec2(pos), uvec2(size)));
}

bool IsUvInside(vec2 p) {
    return p.x >= 0.0 && p.x <= 1.0 && p.y >= 0.0 && p.y <= 1.0;
}

vec3 LoadPreparedInputColor(ivec2 p) {
    // PrepareRgb with exposure 1, then YCoCg.
    return RGBToYCoCg(clamp(texelFetch(fsrColor, p, 0).rgb, 0.0, FSR2_FP16_MAX));
}

// ---- ffx_fsr2_sample.h ----------------------------------------------------------------------------------------------

float Lanczos2NoClamp(float x) {
    const float PI = 3.141592653589793;
    return abs(x) < FSR2_EPSILON ? 1.0 : (sin(PI * x) / (PI * x)) * (sin(0.5 * PI * x) / (0.5 * PI * x));
}

float Lanczos2(float x) {
    return Lanczos2NoClamp(min(abs(x), 2.0));
}

float Lanczos2ApproxSq(float x2) {
    x2 = min(x2, 4.0);
    float a = (2.0 / 5.0) * x2 - 1.0;
    float b = (1.0 / 4.0) * x2 - 1.0;
    return ((25.0 / 16.0) * a * a - (25.0 / 16.0 - 1.0)) * (b * b);
}

vec4 Lanczos2_1D(vec4 c0, vec4 c1, vec4 c2, vec4 c3, float t) {
    float w0 = Lanczos2(-1.0 - t);
    float w1 = Lanczos2(-0.0 - t);
    float w2 = Lanczos2(1.0 - t);
    float w3 = Lanczos2(2.0 - t);
    return (w0 * c0 + w1 * c1 + w2 * c2 + w3 * c3) / (w0 + w1 + w2 + w3);
}

vec4 LoadHistory(ivec2 p) {
    ivec2 size = ivec2(DisplaySize());
    return texelFetch(fsrHistory, clamp(p, ivec2(0), size - 1), 0);
}

// HistorySample: 4x4 Lanczos-2 with deringing against the 4 nearest texels (FetchedBicubicSamples + Lanczos2).
vec4 HistorySample(vec2 fUv) {
    vec2 fPxSample = fUv * DisplaySize() - 0.5;
    ivec2 iPxSample = ivec2(floor(fPxSample));
    vec2 fPxFrac = fract(fPxSample);

    vec4 rows[4];
    vec4 c11 = vec4(0.0), c21 = vec4(0.0), c12 = vec4(0.0), c22 = vec4(0.0);

    for (int y = 0; y < 4; y++) {
        vec4 c0 = LoadHistory(iPxSample + ivec2(-1, y - 1));
        vec4 c1 = LoadHistory(iPxSample + ivec2(0, y - 1));
        vec4 c2 = LoadHistory(iPxSample + ivec2(1, y - 1));
        vec4 c3 = LoadHistory(iPxSample + ivec2(2, y - 1));
        rows[y] = Lanczos2_1D(c0, c1, c2, c3, fPxFrac.x);

        if (y == 1) {
            c11 = c1;
            c21 = c2;
        } else if (y == 2) {
            c12 = c1;
            c22 = c2;
        }
    }

    vec4 fColorXY = Lanczos2_1D(rows[0], rows[1], rows[2], rows[3], fPxFrac.y);

    // Deringing
    vec4 fDeringingMin = min(min(c11, c21), min(c12, c22));
    vec4 fDeringingMax = max(max(c11, c21), max(c12, c22));
    return clamp(fColorXY, fDeringingMin, fDeringingMax);
}

// ---- ffx_fsr2_upsample.h --------------------------------------------------------------------------------------------

float GetUpsampleLanczosWeight(vec2 fSrcSampleOffset, float fKernelWeight) {
    vec2 fSrcSampleOffsetBiased = fSrcSampleOffset * fKernelWeight;
    return Lanczos2ApproxSq(dot(fSrcSampleOffsetBiased, fSrcSampleOffsetBiased));
}

float ComputeMaxKernelWeight() {
    const float fKernelSizeBias = 1.0;
    float fKernelWeight = 1.0 + (1.0 / DownscaleFactor() - 1.0).x * fKernelSizeBias;
    return min(1.99, fKernelWeight);
}

struct Params {
    ivec2 iPxHrPos;
    vec2 fHrUv;
    vec2 fLrUvJittered;
    vec2 fMotionVector;
    vec2 fReprojectedHrUv;
    float fHrVelocity;
    float fDepthClipFactor;
    bool bIsExistingSample;
    bool bIsResetFrame;
    bool bIsNewSample;
};

vec4 ComputeUpsampledColorAndWeight(Params params, inout RectificationBox clippingBox, float fReactiveFactor) {
    vec2 fDstOutputPos = vec2(params.iPxHrPos) + 0.5;
    vec2 fSrcOutputPos = fDstOutputPos * DownscaleFactor();
    ivec2 iSrcInputPos = ivec2(floor(fSrcOutputPos));
    ivec2 renderSize = ivec2(RenderSize());

    vec2 fSrcUnjitteredPos = (vec2(iSrcInputPos) + 0.5) - Jitter();

    ivec2 offsetTL;
    offsetTL.x = (fSrcUnjitteredPos.x > fSrcOutputPos.x) ? -2 : -1;
    offsetTL.y = (fSrcUnjitteredPos.y > fSrcOutputPos.y) ? -2 : -1;

    bool bFlipRow = fSrcUnjitteredPos.y > fSrcOutputPos.y;
    bool bFlipCol = fSrcUnjitteredPos.x > fSrcOutputPos.x;
    vec2 fOffsetTL = vec2(offsetTL);

    vec4 fColorAndWeight = vec4(0.0);
    vec2 fBaseSampleOffset = fSrcUnjitteredPos - fSrcOutputPos;

    float fKernelReactiveFactor = max(fReactiveFactor, float(params.bIsNewSample));
    float fKernelBiasMax = ComputeMaxKernelWeight() * (1.0 - fKernelReactiveFactor);
    float fKernelBiasMin = max(1.0, (1.0 + fKernelBiasMax) * 0.3);
    float fKernelBiasFactor = max(0.0, max(0.25 * params.fDepthClipFactor, fKernelReactiveFactor));
    float fKernelBias = mix(fKernelBiasMax, fKernelBiasMin, fKernelBiasFactor);

    float fRectificationCurveBias = mix(-2.0, -3.0, saturate(params.fHrVelocity / 50.0));

    for (int row = 0; row < 3; row++) {
        for (int col = 0; col < 3; col++) {
            ivec2 sampleColRow = ivec2(bFlipCol ? (3 - col) : col, bFlipRow ? (3 - row) : row);
            vec2 fOffset = fOffsetTL + vec2(sampleColRow);
            vec2 fSrcSampleOffset = fBaseSampleOffset + fOffset;

            ivec2 iSrcSamplePos = iSrcInputPos + offsetTL + sampleColRow;
            vec3 fSample = LoadPreparedInputColor(clamp(iSrcSamplePos, ivec2(0), renderSize - 1));

            float fOnScreenFactor = float(IsOnScreen(iSrcSamplePos, renderSize));
            float fSampleWeight = fOnScreenFactor * GetUpsampleLanczosWeight(fSrcSampleOffset, fKernelBias);

            fColorAndWeight += vec4(fSample * fSampleWeight, fSampleWeight);

            float fSrcSampleOffsetSq = dot(fSrcSampleOffset, fSrcSampleOffset);
            float fBoxSampleWeight = exp(fRectificationCurveBias * fSrcSampleOffsetSq);
            RectificationBoxAddSample(row == 0 && col == 0, clippingBox, fSample, fBoxSampleWeight);
        }
    }

    RectificationBoxComputeVarianceBoxData(clippingBox);

    fColorAndWeight.w *= float(fColorAndWeight.w > FSR2_EPSILON);

    if (fColorAndWeight.w > FSR2_EPSILON) {
        fColorAndWeight.xyz = fColorAndWeight.xyz / fColorAndWeight.w;
        fColorAndWeight.w *= fUpsampleLanczosWeightScale;
        // Deringing
        fColorAndWeight.xyz = clamp(fColorAndWeight.xyz, clippingBox.aabbMin, clippingBox.aabbMax);
    }

    return fColorAndWeight;
}

// ---- ffx_fsr2_reconstruct_dilated_velocity_and_previous_depth.h (inline) --------------------------------------------

// Motion vector of the closest surface in the 3x3 render-resolution neighbourhood (thin edges keep their motion).
vec4 SampleDilatedMotion(vec2 fLrUv) {
    ivec2 renderSize = ivec2(RenderSize());
    ivec2 iPxPos = clamp(ivec2(floor(fLrUv * RenderSize())), ivec2(0), renderSize - 1);
    ivec2 iNearest = iPxPos;
    float fNearestDepth = -1.0;

    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            ivec2 p = clamp(iPxPos + ivec2(x, y), ivec2(0), renderSize - 1);
            float d = texelFetch(fsrDepth, p, 0).r; // reversed Z: larger = closer

            if (d > fNearestDepth) {
                fNearestDepth = d;
                iNearest = p;
            }
        }
    }

    vec4 nearest = texelFetch(fsrMotion, iNearest, 0);
    // Disocclusion of the centre pixel drives the depth clip.
    nearest.b = texelFetch(fsrMotion, iPxPos, 0).b;
    return nearest;
}

// ---- ffx_fsr2_accumulate.h ------------------------------------------------------------------------------------------

Params InitParams(ivec2 iPxHrPos) {
    Params params;
    params.iPxHrPos = iPxHrPos;
    params.fHrUv = (vec2(iPxHrPos) + 0.5) / DisplaySize();
    params.fLrUvJittered = params.fHrUv + Jitter() / RenderSize();

    vec4 motion = SampleDilatedMotion(params.fLrUvJittered);
    params.fMotionVector = motion.xy;
    params.fHrVelocity = length(params.fMotionVector * DisplaySize());

    params.fReprojectedHrUv = params.fHrUv + params.fMotionVector;
    params.bIsExistingSample = IsUvInside(params.fReprojectedHrUv);

    params.fDepthClipFactor = saturate(motion.b);
    params.bIsResetFrame = fsrParams.z < 0.5;
    params.bIsNewSample = !params.bIsExistingSample || params.bIsResetFrame;
    return params;
}

void RectifyHistory(Params params, RectificationBox clippingBox, inout vec3 fHistoryColor, inout vec3 fAccumulation) {
    float fScaleFactorInfluence = min(20.0, pow(1.0 / length(DownscaleFactor().x * DownscaleFactor().y), 3.0));

    float fHrVelocityFactor = saturate(params.fHrVelocity / 20.0);
    float fBoxScaleT = max(params.fDepthClipFactor, fHrVelocityFactor); // no accumulation mask
    float fBoxScale = mix(fScaleFactorInfluence, 1.0, fBoxScaleT);

    vec3 fScaledBoxVec = clippingBox.boxVec * fBoxScale;
    vec3 boxMin = max(clippingBox.aabbMin, clippingBox.boxCenter - fScaledBoxVec);
    vec3 boxMax = min(clippingBox.aabbMax, clippingBox.boxCenter + fScaledBoxVec);

    if (any(greaterThan(boxMin, fHistoryColor)) || any(greaterThan(fHistoryColor, boxMax))) {
        vec3 fClampedHistoryColor = clamp(fHistoryColor, boxMin, boxMax);
        // No lock / luma-instability contribution and no reactive mask: the history is fully rectified.
        float fHistoryContribution = 0.0;
        fHistoryColor = mix(fClampedHistoryColor, fHistoryColor, saturate(fHistoryContribution));
        vec3 fAccumulationMin = min(fAccumulation, vec3(0.1));
        fAccumulation = mix(fAccumulationMin, fAccumulation, saturate(fHistoryContribution));
    }
}

void Accumulate(inout vec3 fHistoryColor, vec3 fAccumulation, vec4 fUpsampledColorAndWeight) {
    fAccumulation = max(vec3(FSR2_EPSILON), fAccumulation + fUpsampledColorAndWeight.www);
    vec3 fAlpha = fUpsampledColorAndWeight.www / fAccumulation;
    fHistoryColor = mix(fHistoryColor, fUpsampledColorAndWeight.xyz, fAlpha);
    fHistoryColor = YCoCgToRGB(fHistoryColor);
}

vec3 ComputeBaseAccumulationWeight(Params params, float fThisFrameReactiveFactor, bool bInMotionLastFrame, float fUpsampledWeight) {
    float fBaseAccumulation = fMaxAccumulationLanczosWeight * float(params.bIsExistingSample) * (1.0 - fThisFrameReactiveFactor)
        * (1.0 - params.fDepthClipFactor);
    fBaseAccumulation = min(fBaseAccumulation, mix(fBaseAccumulation, fUpsampledWeight * 10.0,
        max(float(bInMotionLastFrame), saturate(params.fHrVelocity * 10.0))));
    fBaseAccumulation = min(fBaseAccumulation, mix(fBaseAccumulation, fUpsampledWeight, saturate(params.fHrVelocity / 20.0)));
    return vec3(fBaseAccumulation);
}

float ComputeTemporalReactiveFactor(Params params, float fTemporalReactiveFactor) {
    float fNewFactor = min(0.99, fTemporalReactiveFactor);
    fNewFactor = max(fNewFactor, mix(fNewFactor, 0.4, saturate(params.fHrVelocity)));
    fNewFactor = max(fNewFactor * fNewFactor, params.fDepthClipFactor * 0.1); // no dilated reactive mask
    fNewFactor = params.bIsNewSample ? 1.0 : fNewFactor;

    if (saturate(params.fHrVelocity * 10.0) >= 1.0) {
        fNewFactor = max(FSR2_EPSILON, fNewFactor) * -1.0;
    }

    return fNewFactor;
}

void main() {
    ivec2 iPxHrPos = ivec2(floor(uv * DisplaySize()));
    Params params = InitParams(iPxHrPos);

    vec3 fHistoryColor = vec3(0.0);
    float fTemporalReactiveFactor = 0.0;
    bool bInMotionLastFrame = false;

    if (params.bIsExistingSample && !params.bIsResetFrame) {
        // ReprojectHistoryColor
        vec4 fHistory = HistorySample(params.fReprojectedHrUv);
        fHistoryColor = RGBToYCoCg(clamp(fHistory.rgb, 0.0, FSR2_FP16_MAX));
        fTemporalReactiveFactor = saturate(abs(fHistory.w));
        bInMotionLastFrame = fHistory.w < 0.0;
    }

    float fThisFrameReactiveFactor = fTemporalReactiveFactor;

    RectificationBox clippingBox;
    clippingBox.boxCenter = vec3(0.0);
    clippingBox.boxVec = vec3(0.0);
    clippingBox.aabbMin = vec3(FSR2_FLT_MAX);
    clippingBox.aabbMax = vec3(-FSR2_FLT_MAX);
    clippingBox.fBoxCenterWeight = 0.0;

    vec4 fUpsampledColorAndWeight = ComputeUpsampledColorAndWeight(params, clippingBox, fThisFrameReactiveFactor);
    vec3 fAccumulation = ComputeBaseAccumulationWeight(params, fThisFrameReactiveFactor, bInMotionLastFrame, fUpsampledColorAndWeight.w);

    if (params.bIsNewSample) {
        fHistoryColor = YCoCgToRGB(fUpsampledColorAndWeight.xyz);
    } else {
        RectifyHistory(params, clippingBox, fHistoryColor, fAccumulation);
        Accumulate(fHistoryColor, fAccumulation, fUpsampledColorAndWeight);
    }

    fTemporalReactiveFactor = ComputeTemporalReactiveFactor(params, fThisFrameReactiveFactor);
    outHistory = vec4(fHistoryColor, fTemporalReactiveFactor);
}
