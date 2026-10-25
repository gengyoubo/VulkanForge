#version 450
layout(push_constant) uniform Frame { float angle; float aspect; } frame;
layout(location = 0) out vec3 vertexColor;
const vec2 positions[3] = vec2[](vec2(0.0, -0.68), vec2(0.59, 0.34), vec2(-0.59, 0.34));
const vec3 colors[3] = vec3[](vec3(1.0, 0.25, 0.22), vec3(0.18, 0.85, 0.48), vec3(0.24, 0.45, 1.0));
void main() {
    float c = cos(frame.angle), s = sin(frame.angle);
    vec2 p = mat2(c, s, -s, c) * positions[gl_VertexIndex];
    p.x /= frame.aspect;
    gl_Position = vec4(p, 0.0, 1.0);
    vertexColor = colors[gl_VertexIndex];
}
