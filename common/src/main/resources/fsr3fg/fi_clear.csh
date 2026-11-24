#version 430 core
layout(local_size_x = 8, local_size_y = 8) in;

// Iris FSR 3 frame generation: clears the vector-field and depth images before fi_scatter.csh (written for Iris).

layout(r32ui) uniform uimage2D fgFieldX;
layout(r32ui) uniform uimage2D fgFieldY;
layout(r32ui) uniform uimage2D fgInterpDepth;
uniform vec4 fgSizes; // xy = render size

void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);

    if (any(greaterThanEqual(p, ivec2(fgSizes.xy)))) {
        return;
    }

    imageStore(fgFieldX, p, uvec4(0u));
    imageStore(fgFieldY, p, uvec4(0u));
    imageStore(fgInterpDepth, p, uvec4(0u));
}
