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
uniform sampler2D InSampler;
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
#include <dialogue:vignette_clock.glsl>
void main() {
    vec4 state = texelFetch(StateSampler, ivec2(0), 0);
    float progress = vignetteProgress(state);
    float strength = mix(From, To, progress);
    vec4 scene = texture(InSampler, texCoord);
    float edge = smoothstep(0.35, 1.35, length((texCoord - vec2(0.5)) * 2.0));
    float multiplier = Inverse == 1.0
        ? 1.0 / (1.0 - edge * strength)
        : 1.0 - strength * edge;
    fragColor = vec4(scene.rgb * multiplier, scene.a);
}
