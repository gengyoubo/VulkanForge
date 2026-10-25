#version 450
layout(binding = 0) uniform sampler2D blockAtlas;
layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 uv;
layout(location = 0) out vec4 outColor;
// Premultiplied output: the cleared (0,0,0,0) target composites over GL with (ONE, ONE_MINUS_SRC_ALPHA).
void main() {
    vec4 sampled = texture(blockAtlas, uv) * vertexColor;
    outColor = vec4(sampled.rgb * sampled.a, sampled.a);
}
