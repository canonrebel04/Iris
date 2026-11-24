#version 330 core

// Iris FSR 3 frame generation: camera-reprojection motion vectors.
//
// Written for Iris (not taken from the FidelityFX SDK). It produces the "game motion vectors" input that
// FSR 2/3 expect, without any help from the shader pack, by reprojecting every pixel's depth with the
// current and previous camera.
//
// Output (RGBA16F):
//   rg = motion vector in UV space, pointing from this frame to the previous one (prevUv - uv),
//        the same convention as FidelityFX FSR 2/3 game motion vectors
//   b  = 1 if this surface was not visible in the previous frame (depth disocclusion), else 0
//   a  = 1 for first-person hand pixels (they move with the camera, so their motion is forced to 0)

uniform sampler2D fgDepth;       // raw depth of this frame (Minecraft reversed-Z), includes the hand
uniform sampler2D fgDepthNoHand; // raw depth copied just before the hand was drawn
uniform sampler2D fgPrevDepth;   // raw depth of the previous frame
uniform mat4 fgReproject;        // this frame's NDC (OpenGL -1..1 Z, Iris gbufferProjection convention) -> previous clip
uniform vec4 fgParams;           // x = prev proj m22, y = prev proj m32, z = has history (0/1), w = hand depth valid (0/1)

in vec2 uv;
out vec4 outMotion;

float linearDistance(float ndcZ) {
    // Distance in front of the camera for a standard OpenGL perspective projection.
    return fgParams.y / (ndcZ + fgParams.x);
}

bool insideUv(vec2 p) {
    return p.x >= 0.0 && p.x <= 1.0 && p.y >= 0.0 && p.y <= 1.0;
}

void main() {
    float raw = texture(fgDepth, uv).r;
    bool hand = false;

    if (fgParams.w > 0.5) {
        float rawNoHand = texture(fgDepthNoHand, uv).r;
        hand = abs(raw - rawNoHand) > 1.0e-7;
    }

    if (hand) {
        outMotion = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    // Iris undoes Minecraft's reversed Z for packs: pack depth = 1 - raw, NDC z = depth * 2 - 1.
    float ndcZ = (1.0 - raw) * 2.0 - 1.0;
    vec4 previousClip = fgReproject * vec4(uv * 2.0 - 1.0, ndcZ, 1.0);

    if (previousClip.w <= 1.0e-6) {
        outMotion = vec4(0.0, 0.0, 1.0, 0.0);
        return;
    }

    vec3 previousNdc = previousClip.xyz / previousClip.w;
    vec2 previousUv = previousNdc.xy * 0.5 + 0.5;
    vec2 motion = previousUv - uv;

    float disoccluded = 0.0;

    if (fgParams.z > 0.5) {
        if (!insideUv(previousUv)) {
            disoccluded = 1.0;
        } else {
            float previousRaw = texture(fgPrevDepth, previousUv).r;
            float expected = linearDistance(previousNdc.z);
            float actual = linearDistance((1.0 - previousRaw) * 2.0 - 1.0);

            // Something closer to the camera covered this surface in the previous frame.
            if (actual < expected - (0.02 * expected + 0.05)) {
                disoccluded = 1.0;
            }
        }
    }

    outMotion = vec4(motion, disoccluded, 0.0);
}
