#version 430 core
layout(local_size_x = 8, local_size_y = 8) in;

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
// Iris port (GLSL compute shader) of FidelityFX SDK v1.1.4:
//   sdk/include/FidelityFX/gpu/frameinterpolation/ffx_frameinterpolation_game_motion_vector_field.h
//       (computeGameFieldMvs, getPriorityFactorFromViewSpaceDepth)
//   sdk/include/FidelityFX/gpu/frameinterpolation/ffx_frameinterpolation_reconstruct_previous_depth.h
//       (ReconstructPrevDepth for the interpolated frame)
//   sdk/include/FidelityFX/gpu/frameinterpolation/ffx_frameinterpolation_common.h (PackVectorFieldEntries,
//       GetBilinearSamplingData, priority constants)
//   sdk/include/FidelityFX/gpu/frameinterpolation/ffx_frameinterpolation_callbacks_glsl.h (atomic updates)
// Modifications by the Iris contributors:
//   - Both passes merged into one dispatch; dilated depth/motion (nearest depth in 3x3) computed inline from Iris's
//     camera-reprojection motion vectors; no distortion field, no letterbox rect (interpolation rect = full screen).
//   - Depth is Minecraft's reversed Z (FSR's inverted-depth option): atomicMax keeps the closest surface.
//   - The secondary-vector walk is capped at 256 steps.
// See third_party/FidelityFX/LICENSE.txt.

layout(r32ui) uniform uimage2D fgFieldX;
layout(r32ui) uniform uimage2D fgFieldY;
layout(r32ui) uniform uimage2D fgInterpDepth;

uniform sampler2D fgMotion;    // render resolution: rg = prevUv - uv, b = disoccluded, a = hand
uniform sampler2D fgDepth;     // render resolution: this frame's raw depth
uniform sampler2D fgPrevColor; // display resolution: previous HUD-less frame
uniform sampler2D fgCurrColor; // display resolution: current HUD-less frame
uniform vec4 fgSizes;          // xy = render size, zw = display size
uniform vec4 fgDepthParams;    // x = proj m22, y = proj m32 (OpenGL-style), z = Kfov, w = has previous depth

const float FFX_FRAMEINTERPOLATION_EPSILON = 1e-03;
const float fReconstructedDepthBilinearWeightThreshold = FFX_FRAMEINTERPOLATION_EPSILON;

const uint MOTION_VECTOR_FIELD_ENTRY_BIT_COUNT = 32u;
const uint MOTION_VECTOR_FIELD_VECTOR_COEFFICIENT_BIT_COUNT = 16u;
const uint MOTION_VECTOR_FIELD_PRIORITY_LOW_BIT_COUNT = 5u;
const uint MOTION_VECTOR_FIELD_PRIORITY_HIGH_BIT_COUNT = 10u;
const uint MOTION_VECTOR_FIELD_PRIMARY_VECTOR_INDICATION_BIT = (1u << (MOTION_VECTOR_FIELD_ENTRY_BIT_COUNT - 1u));
const uint PRIORITY_LOW_MAX = (1u << MOTION_VECTOR_FIELD_PRIORITY_LOW_BIT_COUNT) - 1u;
const uint PRIORITY_HIGH_MAX = (1u << MOTION_VECTOR_FIELD_PRIORITY_HIGH_BIT_COUNT) - 1u;
const uint PRIORITY_LOW_OFFSET = MOTION_VECTOR_FIELD_VECTOR_COEFFICIENT_BIT_COUNT;
const uint PRIORITY_HIGH_OFFSET = PRIORITY_LOW_OFFSET + MOTION_VECTOR_FIELD_PRIORITY_LOW_BIT_COUNT;

ivec2 RenderSize() { return ivec2(fgSizes.xy); }

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

bool IsUvInside(vec2 fUv) {
    return (fUv.x > 0.0 && fUv.x < 1.0) && (fUv.y > 0.0 && fUv.y < 1.0);
}

float MinDividedByMax(float v0, float v1) {
    float m = max(v0, v1);
    return m != 0.0 ? min(v0, v1) / m : 0.0;
}

// Iris: Minecraft depth is reversed Z (1 = near). Iris's pack convention is depth = 1 - raw, ndc = depth * 2 - 1, and
// fgDepthParams.xy are m22/m32 of the matching OpenGL-style projection, so this returns the distance in blocks (~metres).
float ConvertFromDeviceDepthToViewSpace(float fDeviceDepth) {
    float ndcZ = (1.0 - fDeviceDepth) * 2.0 - 1.0;
    return fgDepthParams.y / (ndcZ + fgDepthParams.x);
}

vec3 ffxLinearFromSrgb(vec3 c) {
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c));
}

float RawRGBToLuminance(vec3 fRawRgb) {
    return dot(ffxLinearFromSrgb(max(fRawRgb, 0.0)), vec3(0.2126, 0.7152, 0.0722));
}

uvec2 PackVectorFieldEntries(bool bIsPrimary, uint uHighPriorityFactor, uint uLowPriorityFactor, vec2 fMotionVector) {
    uint uPriority = (uint(bIsPrimary) * MOTION_VECTOR_FIELD_PRIMARY_VECTOR_INDICATION_BIT)
        | ((uHighPriorityFactor & PRIORITY_HIGH_MAX) << PRIORITY_HIGH_OFFSET)
        | ((uLowPriorityFactor & PRIORITY_LOW_MAX) << PRIORITY_LOW_OFFSET);
    uint packedX = uPriority | (packHalf2x16(vec2(fMotionVector.x, 0.0)) & 0xFFFFu);
    uint packedY = uPriority | (packHalf2x16(vec2(fMotionVector.y, 0.0)) & 0xFFFFu);
    return uvec2(packedX, packedY);
}

bool PackedVectorFieldEntryIsPrimary(uint packedEntry) {
    return (packedEntry & MOTION_VECTOR_FIELD_PRIMARY_VECTOR_INDICATION_BIT) != 0u;
}

void UpdateGameMotionVectorField(ivec2 iPxPos, uvec2 packedVector) {
    imageAtomicMax(fgFieldX, iPxPos, packedVector.x);
    imageAtomicMax(fgFieldY, iPxPos, packedVector.y);
}

uint UpdateGameMotionVectorFieldEx(ivec2 iPxPos, uvec2 packedVector) {
    uint uPreviousValueX = imageAtomicMax(fgFieldX, iPxPos, packedVector.x);
    uint uPreviousValueY = imageAtomicMax(fgFieldY, iPxPos, packedVector.y);
    return max(uPreviousValueX, uPreviousValueY);
}

void UpdateReconstructedDepthInterpolatedFrame(ivec2 iPxSample, float fDepth) {
    // Inverted (reversed) depth: max keeps the closest surface.
    imageAtomicMax(fgInterpDepth, iPxSample, floatBitsToUint(fDepth));
}

uint getPriorityFactorFromViewSpaceDepth(float fViewSpaceDepthInMeters) {
    fViewSpaceDepthInMeters = pow(max(fViewSpaceDepthInMeters, 0.0), 0.33);
    uint uPriorityFactor = uint(float(1.0 - (fViewSpaceDepthInMeters * (1.0 / (1.0 + fViewSpaceDepthInMeters)))) * float(PRIORITY_HIGH_MAX));
    return max(1u, uPriorityFactor);
}

// Iris: FSR 3 reads dilated depth/motion produced by the upscaler; here: nearest depth in 3x3.
void LoadDilated(ivec2 iPxPos, out float fDepth, out vec2 fMotionVector) {
    ivec2 renderSize = RenderSize();
    ivec2 iNearest = iPxPos;
    fDepth = -1.0;

    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            ivec2 p = clamp(iPxPos + ivec2(x, y), ivec2(0), renderSize - 1);
            float d = texelFetch(fgDepth, p, 0).r;

            if (d > fDepth) {
                fDepth = d;
                iNearest = p;
            }
        }
    }

    fMotionVector = texelFetch(fgMotion, iNearest, 0).xy;
}

void ReconstructPrevDepth(ivec2 iPxPos, float fDepth, vec2 fMotionVector, ivec2 iPxDepthSize) {
    vec2 fUv = (vec2(iPxPos) + 0.5) / vec2(iPxDepthSize);
    BilinearSamplingData bilinearInfo = GetBilinearSamplingData(fUv + fMotionVector, RenderSize());

    for (int iSampleIndex = 0; iSampleIndex < 4; iSampleIndex++) {
        ivec2 iSamplePos = bilinearInfo.iBasePos + bilinearInfo.iOffsets[iSampleIndex];
        float fSampleWeight = bilinearInfo.fWeights[iSampleIndex];

        if (fSampleWeight > fReconstructedDepthBilinearWeightThreshold && IsOnScreen(iSamplePos, RenderSize())) {
            UpdateReconstructedDepthInterpolatedFrame(iSamplePos, fDepth);
        }
    }
}

void computeGameFieldMvs(ivec2 iPxPos, float fDepthSample, vec2 fGameMotionVector) {
    vec2 fUvInScreenSpace = (vec2(iPxPos) + 0.5) / vec2(RenderSize());
    vec2 fMotionVectorHalf = fGameMotionVector * 0.5;
    vec2 fInterpolatedLocationUv = fUvInScreenSpace + fMotionVectorHalf;

    float fViewSpaceDepth = ConvertFromDeviceDepthToViewSpace(fDepthSample);
    uint uHighPriorityFactorPrimary = getPriorityFactorFromViewSpaceDepth(fViewSpaceDepth);

    // pixel position in current frame + game motion vector -> pixel position in previous frame
    vec3 prevBackbufferCol = textureLod(fgPrevColor, fUvInScreenSpace + fGameMotionVector, 0.0).rgb;
    vec3 curBackbufferCol = textureLod(fgCurrColor, fUvInScreenSpace, 0.0).rgb;
    float prevLuma = 0.001 + RawRGBToLuminance(prevBackbufferCol);
    float currLuma = 0.001 + RawRGBToLuminance(curBackbufferCol);

    uint uLowPriorityFactor = uint(round(MinDividedByMax(prevLuma, currLuma) * float(PRIORITY_LOW_MAX)))
        * uint(IsUvInside(fUvInScreenSpace + fGameMotionVector));

    // Update primary motion vectors
    {
        uvec2 packedVectorPrimary = PackVectorFieldEntries(true, uHighPriorityFactorPrimary, uLowPriorityFactor, fMotionVectorHalf);
        BilinearSamplingData bilinearInfo = GetBilinearSamplingData(fInterpolatedLocationUv, RenderSize());

        for (int iSampleIndex = 0; iSampleIndex < 4; iSampleIndex++) {
            ivec2 iSamplePos = bilinearInfo.iBasePos + bilinearInfo.iOffsets[iSampleIndex];

            if (IsOnScreen(iSamplePos, RenderSize())) {
                UpdateGameMotionVectorField(iSamplePos, packedVectorPrimary);
            }
        }
    }

    // Update secondary vectors (improve the quality of inpainted vectors)
    bool bWriteSecondaryVectors = length(fMotionVectorHalf * vec2(RenderSize())) > FFX_FRAMEINTERPOLATION_EPSILON;

    if (bWriteSecondaryVectors) {
        bool bWriteSecondary = true;
        uint uNumPrimaryHits = 0u;
        float fSecondaryStepScale = length(1.0 / vec2(RenderSize()));
        vec2 fStepMv = normalize(fGameMotionVector);
        float fBreakDist = min(length(fMotionVectorHalf), length(vec2(0.5, 0.5)));
        int iSteps = 0;

        for (float fMvScale = fSecondaryStepScale; fMvScale <= fBreakDist && bWriteSecondary && iSteps < 256; fMvScale += fSecondaryStepScale) {
            iSteps++;
            vec2 fSecondaryLocationUv = fInterpolatedLocationUv - fStepMv * fMvScale;
            BilinearSamplingData bilinearInfo = GetBilinearSamplingData(fSecondaryLocationUv, RenderSize());

            // Reverse depth prio for secondary vectors
            uint uHighPriorityFactorSecondary = max(1u, PRIORITY_HIGH_MAX - uHighPriorityFactorPrimary);

            vec2 fToCenter = normalize(vec2(0.5, 0.5) - fSecondaryLocationUv);
            uint uLowPriorityFactorSecondary = uint(max(0.0, dot(fToCenter, fStepMv)) * float(PRIORITY_LOW_MAX));
            uvec2 packedVectorSecondary = PackVectorFieldEntries(false, uHighPriorityFactorSecondary, uLowPriorityFactorSecondary, fMotionVectorHalf);

            // Only write secondary mvs to single bilinear location
            ivec2 iSamplePos = bilinearInfo.iBasePos + bilinearInfo.iOffsets[0];
            bWriteSecondary = bWriteSecondary && IsOnScreen(iSamplePos, RenderSize());

            if (bWriteSecondary) {
                uint uExistingVectorFieldEntry = UpdateGameMotionVectorFieldEx(iSamplePos, packedVectorSecondary);
                uNumPrimaryHits += uint(PackedVectorFieldEntryIsPrimary(uExistingVectorFieldEntry));
                bWriteSecondary = bWriteSecondary && (uNumPrimaryHits <= 3u);
            }
        }
    }
}

void main() {
    ivec2 iPxPos = ivec2(gl_GlobalInvocationID.xy);

    if (!IsOnScreen(iPxPos, RenderSize())) {
        return;
    }

    float fDilatedDepth;
    vec2 fMotionVector;
    LoadDilated(iPxPos, fDilatedDepth, fMotionVector);

    computeGameFieldMvs(iPxPos, fDilatedDepth, fMotionVector);
    // reconstructPreviousDepth: estimated depth of the interpolated frame
    ReconstructPrevDepth(iPxPos, fDilatedDepth, fMotionVector * 0.5, RenderSize());
}
