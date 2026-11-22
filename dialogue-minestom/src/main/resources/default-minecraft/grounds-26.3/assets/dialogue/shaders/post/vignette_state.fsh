#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:globals.glsl>
layout(std140) uniform FadeConfig {
    float DurationTicks;
    float From;
    float To;
    float Inverse;
};
uniform sampler2D StateSampler;
layout(location = 0) out vec4 fragColor;
#include <dialogue:vignette_clock.glsl>
void main() {
    vec4 state = texelFetch(StateSampler, ivec2(0), 0);
    if (state.a == 0.0) {
        uint encoded = uint(fract(GameTime) * VIGNETTE_TIMESTAMP_SCALE);
        uvec3 bytes = uvec3(encoded >> 16u, (encoded >> 8u) & 255u, encoded & 255u);
        fragColor = vec4(vec3(bytes) / 255.0, VIGNETTE_ACTIVE_SENTINEL);
    } else if (vignetteProgress(state) >= 1.0) {
        fragColor = vec4(1.0);
    } else {
        fragColor = state;
    }
}
