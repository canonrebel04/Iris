#version 330 core

// Iris FSR 3 frame generation: one level of the colour inpainting pyramid (written for Iris; FSR 3 builds the same
// pyramid with FidelityFX SPD). Each texel stores the average of the valid (not-to-be-inpainted) source texels in rgb
// and the fraction of valid texels in a, which is what ComputeInpaintingLevel in fi_inpaint.fsh expects.

uniform sampler2D fgPyramidSource;
uniform vec4 fgPyramidParams; // x = 1 when the source is the interpolated frame (a = inpainting weight, < 0 for debug)

in vec2 uv;
out vec4 outColor;

void main() {
    ivec2 srcSize = textureSize(fgPyramidSource, 0);
    ivec2 dstSize = max(srcSize / 2, ivec2(1));
    ivec2 dst = clamp(ivec2(floor(uv * vec2(dstSize))), ivec2(0), dstSize - 1);

    vec3 color = vec3(0.0);
    float weight = 0.0;

    for (int y = 0; y < 2; y++) {
        for (int x = 0; x < 2; x++) {
            vec4 c = texelFetch(fgPyramidSource, clamp(dst * 2 + ivec2(x, y), ivec2(0), srcSize - 1), 0);
            float w = fgPyramidParams.x > 0.5 ? (c.a < 0.0 ? 0.0 : float(c.a <= 1e-3)) : c.a;
            color += c.rgb * w;
            weight += w;
        }
    }

    outColor = vec4(weight > 0.0 ? color / weight : vec3(0.0), weight * 0.25);
}
