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
// Iris port (GLSL fragment shader) of FSR 1 RCAS (robust contrast-adaptive sharpening) from FidelityFX SDK v1.1.4:
//   sdk/include/FidelityFX/gpu/fsr1/ffx_fsr1.h (FsrRcasF, FSR_RCAS_LIMIT), as used by the FSR 2 RCAS pass
//   sdk/include/FidelityFX/gpu/fsr2/ffx_fsr2_rcas.h
// Modifications by the Iris contributors:
//   - GLSL 330 fullscreen fragment shader; sharpness constant passed as a float uniform (FsrRcasCon equivalent:
//     con = exp2(-stops)), 0 = pass-through. No denoise, no alpha pass-through (alpha written as 1).
//   - Small epsilon guards on the reciprocals (HLSL relies on IEEE inf behaviour there).

uniform sampler2D fsrInput; // upscaled color (display resolution)
uniform vec4 fsrRcas;       // x = sharpness constant (exp2(-stops)), 0 disables sharpening

in vec2 uv;
out vec4 outColor;

const float FSR_RCAS_LIMIT = 0.25 - (1.0 / 16.0);

vec3 RcasLoad(ivec2 p) {
    ivec2 size = textureSize(fsrInput, 0);
    return max(texelFetch(fsrInput, clamp(p, ivec2(0), size - 1), 0).rgb, 0.0);
}

float max3(float a, float b, float c) { return max(a, max(b, c)); }
float min3(float a, float b, float c) { return min(a, min(b, c)); }

void main() {
    ivec2 sp = ivec2(floor(uv * vec2(textureSize(fsrInput, 0))));
    // Algorithm uses minimal 3x3 pixel neighborhood.
    //    b
    //  d e f
    //    h
    vec3 b = RcasLoad(sp + ivec2(0, -1));
    vec3 d = RcasLoad(sp + ivec2(-1, 0));
    vec3 e = RcasLoad(sp);
    vec3 f = RcasLoad(sp + ivec2(1, 0));
    vec3 h = RcasLoad(sp + ivec2(0, 1));

    if (fsrRcas.x <= 0.0) {
        outColor = vec4(e, 1.0);
        return;
    }

    // Min and max of ring.
    vec3 mn4 = min(min(min(b, d), f), h);
    vec3 mx4 = max(max(max(b, d), f), h);
    // Immediate constants for peak range.
    vec2 peakC = vec2(1.0, -1.0 * 4.0);
    // Limiters.
    vec3 hitMin = mn4 / max(4.0 * mx4, 1e-6);
    vec3 hitMax = (peakC.x - mx4) / min(4.0 * mn4 + peakC.y, -1e-6);
    vec3 lobeRGB = max(-hitMin, hitMax);
    float lobe = max(-FSR_RCAS_LIMIT, min(max3(lobeRGB.r, lobeRGB.g, lobeRGB.b), 0.0)) * fsrRcas.x;
    // Resolve.
    float rcpL = 1.0 / (4.0 * lobe + 1.0);
    vec3 pix = (lobe * b + lobe * d + lobe * h + lobe * f + e) * rcpL;
    outColor = vec4(pix, 1.0);
}
