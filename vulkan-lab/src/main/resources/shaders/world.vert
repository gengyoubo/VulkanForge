#version 450
layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec4 inColor;
layout(location = 2) in vec2 inUV;
layout(push_constant) uniform Draw { mat4 viewProjection; vec4 sectionOffset; } draw;
layout(location = 0) out vec4 vertexColor;
layout(location = 1) out vec2 uv;
void main() {
    gl_Position = draw.viewProjection * vec4(inPosition + draw.sectionOffset.xyz, 1.0);
    gl_Position.y = -gl_Position.y;
    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;
    vertexColor = inColor;
    uv = inUV;
}
