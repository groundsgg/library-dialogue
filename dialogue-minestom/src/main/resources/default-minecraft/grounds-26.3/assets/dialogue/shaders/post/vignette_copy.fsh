#version 330
uniform sampler2D StateSampler;
layout(location = 0) out vec4 fragColor;
void main() {
    fragColor = texelFetch(StateSampler, ivec2(0), 0);
}
