#version 450
layout(binding = 0) uniform sampler2D blockAtlas;
layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 uv;
layout(location = 0) out vec4 outColor;
void main() { outColor = vec4((texture(blockAtlas, uv) * vertexColor).rgb, 1.0); }
