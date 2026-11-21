#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D InSampler;
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
void main() {
    vec4 scene = texture(InSampler, texCoord);
    float edge = smoothstep(0.35, 1.35, length((texCoord - vec2(0.5)) * 2.0));
    fragColor = vec4(scene.rgb * (1.0 - 0.06 * edge), scene.a);
}
