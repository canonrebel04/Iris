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
//   sdk/include/FidelityFX/gpu/frameinterpolation/ffx_frameinterpolation_disocclusion_mask.h
//       (computeDisocclusionMask, ComputeDepthClip)
//   sdk/include/FidelityFX/gpu/frameinterpolation/ffx_frameinterpolation_common.h (UnpackVectorFieldEntries,
//       LoadInpaintedGameFieldMv)
// Modifications by the Iris contributors:
//   - Writes the unpacked game vector and the disocclusion mask into one RGBA16F image for the interpolation pass.
//   - Holes in the game vector field are filled by a backward fixed-point search on Iris's motion vectors instead of
//     the motion-vector inpainting pyramid.
//   - "Previous frame depth" is the real previous depth buffer (FSR 3 reconstructs it from the upscaler); current
//     depth is not dilated.
// See third_party/FidelityFX/LICENSE.txt.

layout(r32ui) uniform readonly uimage2D fgFieldX;
layout(r32ui) uniform readonly uimage2D fgFieldY;
layout(r32ui) uniform readonly uimage2D fgInterpDepth;
layout(rgba16f) uniform writeonly image2D fgVectors; // xy = half game vector (interpolated -> previous),
                                                      // z = visible in previous (0/1), w = visible in current (0/1)
uniform sampler2D fgMotion;    // render resolution
uniform sampler2D fgDepth;     // render resolution: this frame's raw depth
uniform sampler2D fgPrevDepth; // render resolution: previous frame's raw depth
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

struct VectorFieldEntry {
    vec2 fMotionVector;
    float uHighPriorityFactor;
    float uLowPriorityFactor;
    bool bValid;
    bool bPrimary;
    bool bSecondary;
    bool bInPainted;
    float fVelocity;
    bool bNegOutside;
    bool bPosOutside;
};

void UnpackVectorFieldEntries(uvec2 packed, out VectorFieldEntry vfElement) {
    vfElement.uHighPriorityFactor = float((packed.x >> PRIORITY_HIGH_OFFSET) & PRIORITY_HIGH_MAX) / float(PRIORITY_HIGH_MAX);
    vfElement.uLowPriorityFactor = float((packed.x >> PRIORITY_LOW_OFFSET) & PRIORITY_LOW_MAX) / float(PRIORITY_LOW_MAX);
    vfElement.bPrimary = (packed.x & MOTION_VECTOR_FIELD_PRIMARY_VECTOR_INDICATION_BIT) != 0u;
    vfElement.bValid = (vfElement.uHighPriorityFactor > 0.0);
    vfElement.bSecondary = vfElement.bValid && !vfElement.bPrimary;

    // Reverse priority factor for secondary vectors
    if (vfElement.bSecondary) {
        vfElement.uHighPriorityFactor = 1.0 - vfElement.uHighPriorityFactor;
    }

    vfElement.fMotionVector.x = unpackHalf2x16(packed.x).x;
    vfElement.fMotionVector.y = unpackHalf2x16(packed.y).x;
    vfElement.bInPainted = false;
    vfElement.fVelocity = 0.0;
    vfElement.bNegOutside = false;
    vfElement.bPosOutside = false;
}

// Iris replacement for the motion-vector inpainting pyramid: find the current-frame pixel c whose motion carries it to
// the interpolated position (c + 0.5 * mv(c) == uv).
vec2 SearchGameVector(vec2 fUv) {
    vec2 c = fUv;

    for (int i = 0; i < 4; i++) {
        c = fUv - 0.5 * textureLod(fgMotion, c, 0.0).xy;
    }

    return 0.5 * textureLod(fgMotion, c, 0.0).xy;
}

void LoadInpaintedGameFieldMv(vec2 fUv, out VectorFieldEntry vfElement) {
    ivec2 iPxSample = clamp(ivec2(fUv * vec2(RenderSize())), ivec2(0), RenderSize() - 1);
    uvec2 packedGameFieldMv = uvec2(imageLoad(fgFieldX, iPxSample).x, imageLoad(fgFieldY, iPxSample).x);
    UnpackVectorFieldEntries(packedGameFieldMv, vfElement);

    if (!vfElement.bValid) {
        vfElement.fMotionVector = SearchGameVector(fUv);
        vfElement.bInPainted = true;
    }

    vfElement.bNegOutside = !IsUvInside(fUv - vfElement.fMotionVector);
    vfElement.bPosOutside = !IsUvInside(fUv + vfElement.fMotionVector);
    vfElement.fVelocity = length(vfElement.fMotionVector);
}

float LoadEstimatedDepth(uint estimatedIndex, ivec2 iSamplePos) {
    if (estimatedIndex == 0u) {
        return texelFetch(fgPrevDepth, iSamplePos, 0).r;
    } else if (estimatedIndex == 1u) {
        return texelFetch(fgDepth, iSamplePos, 0).r;
    }

    return 0.0;
}

float ComputeDepthClip(uint estimatedIndex, vec2 fUvSample, float fCurrentDepthSample) {
    float fCurrentDepthViewSpace = ConvertFromDeviceDepthToViewSpace(fCurrentDepthSample);
    BilinearSamplingData bilinearInfo = GetBilinearSamplingData(fUvSample, RenderSize());

    float fDepth = 0.0;
    float fWeightSum = 0.0;

    for (int iSampleIndex = 0; iSampleIndex < 4; iSampleIndex++) {
        ivec2 iSamplePos = bilinearInfo.iBasePos + bilinearInfo.iOffsets[iSampleIndex];

        if (IsOnScreen(iSamplePos, RenderSize())) {
            float fWeight = bilinearInfo.fWeights[iSampleIndex];

            if (fWeight > fReconstructedDepthBilinearWeightThreshold) {
                float fPrevDepthSample = LoadEstimatedDepth(estimatedIndex, iSamplePos);
                float fPrevNearestDepthViewSpace = ConvertFromDeviceDepthToViewSpace(fPrevDepthSample);
                float fDepthDiff = fCurrentDepthViewSpace - fPrevNearestDepthViewSpace;

                if (fDepthDiff > 0.0) {
                    // Depth separation logic ref: "Minimum Triangle Separation for Correct Z-Buffer Occlusion".
                    // Kfov = length(view-space corner) / length(view-space centre) at the same depth.
                    float fHalfViewportWidth = length(vec2(RenderSize()));
                    float fDepthThreshold = min(fCurrentDepthViewSpace, fPrevNearestDepthViewSpace);
                    const float Ksep = 1.37e-05;
                    float Kfov = fgDepthParams.z;
                    float fRequiredDepthSeparation = Ksep * Kfov * fHalfViewportWidth * fDepthThreshold;

                    fDepth += float((fRequiredDepthSeparation / fDepthDiff) >= 1.0) * fWeight;
                    fWeightSum += fWeight;
                }
            }
        }
    }

    return (fWeightSum > 0.0) ? clamp(1.0 - fDepth / fWeightSum, 0.0, 1.0) : 0.0;
}

void main() {
    ivec2 iPxPos = ivec2(gl_GlobalInvocationID.xy);

    if (!IsOnScreen(iPxPos, RenderSize())) {
        return;
    }

    float fDilatedDepth = uintBitsToFloat(imageLoad(fgInterpDepth, iPxPos).x);
    vec2 fDepthUv = (vec2(iPxPos) + 0.5) / vec2(RenderSize());

    VectorFieldEntry gameMv;
    LoadInpaintedGameFieldMv(fDepthUv, gameMv);

    float fDepthClipInterpolatedToPrevious = fgDepthParams.w > 0.5 ? 1.0 - ComputeDepthClip(0u, fDepthUv + gameMv.fMotionVector, fDilatedDepth) : 1.0;
    float fDepthClipInterpolatedToCurrent = 1.0 - ComputeDepthClip(1u, fDepthUv - gameMv.fMotionVector, fDilatedDepth);
    vec2 fDisocclusionMask = vec2(fDepthClipInterpolatedToPrevious, fDepthClipInterpolatedToCurrent);
    fDisocclusionMask = vec2(greaterThanEqual(fDisocclusionMask, vec2(FFX_FRAMEINTERPOLATION_EPSILON)));

    // Avoid false disocclusion if primary game vector pointer outside screen area
    vec2 fSrcMotionVector = gameMv.fMotionVector * 2.0;
    ivec2 iSamplePosPrevious = ivec2((fDepthUv + fSrcMotionVector) * vec2(RenderSize()));
    fDisocclusionMask.x = clamp(fDisocclusionMask.x + float(!IsOnScreen(iSamplePosPrevious, RenderSize())), 0.0, 1.0);
    ivec2 iSamplePosCurrent = ivec2((fDepthUv - fSrcMotionVector) * vec2(RenderSize()));
    fDisocclusionMask.y = clamp(fDisocclusionMask.y + float(!IsOnScreen(iSamplePosCurrent, RenderSize())), 0.0, 1.0);

    imageStore(fgVectors, iPxPos, vec4(gameMv.fMotionVector, fDisocclusionMask));
}
