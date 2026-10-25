#version 450
layout(location = 0) out vec3 vertexColor;
const vec2 positions[3] = vec2[](vec2(0.0, -0.65), vec2(0.65, 0.55), vec2(-0.65, 0.55));
const vec3 colors[3] = vec3[](vec3(1.0, 0.25, 0.22), vec3(0.18, 0.85, 0.48), vec3(0.24, 0.45, 1.0));
void main() {
    gl_Position = vec4(positions[gl_VertexIndex], 0.0, 1.0);
    vertexColor = colors[gl_VertexIndex];
}
