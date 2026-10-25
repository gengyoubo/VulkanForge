#version 460
struct Bounds { vec4 minimum; vec4 maximum; uvec4 draw; vec4 origin; };
layout(std430, set=1, binding=0) readonly buffer Sections { Bounds sections[]; };
layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec4 inColor;
layout(location = 2) in vec2 inUV;
layout(location = 3) in uvec2 inLight;
layout(binding = 1) uniform sampler2D lightMap;
layout(push_constant) uniform Draw { mat4 viewProjection; vec4 sectionOffset; } draw;
layout(location = 0) out vec4 vertexColor;
layout(location = 1) out vec2 uv;
void main() {
    gl_Position = draw.viewProjection * vec4(inPosition + sections[gl_BaseInstance].origin.xyz, 1.0);
    gl_Position.y = -gl_Position.y;
    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;
    vec2 lightUV = clamp(vec2(inLight) / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0));
    vertexColor = inColor * texture(lightMap, lightUV);
    uv = inUV;
}
