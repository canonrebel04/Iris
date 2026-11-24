#version 330

// Iris FSR 3 frame generation: fullscreen triangle-pair vertex shader.
// Iris's composite quad has corners in [0, 1]; the varying uses the same convention as shader pack composites.

layout(location = 0) in vec3 Position;

out vec2 uv;

void main() {
    uv = Position.xy;
    gl_Position = vec4(Position.xy * 2.0 - 1.0, 0, 1);
}
