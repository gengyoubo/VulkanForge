#version 460 core
// Shared by the three opaque layers (MultiDrawElementsIndirectCount) and translucent (MultiDrawElementsIndirect): baseInstance is the section slot.
layout(std140, binding = 0) uniform Frame {
    mat4 viewProjection;
    ivec4 cameraBlock;
    vec4 cameraFraction;
    vec4 fogColor;
    vec4 fog;      // start, end, shape (0 sphere, 1 cylinder), alpha cutoff (fragment)
    uvec4 counts;  // x: section slots, y: command capacity per layer
};
struct Section { ivec4 origin; vec4 minimum; vec4 maximum; uvec4 draw; };
layout(std430, binding = 0) readonly buffer Sections { Section sections[]; };
layout(binding = 2) uniform sampler2D lightMap;
layout(location = 0) in vec3 position;
layout(location = 1) in vec4 color;
layout(location = 2) in vec2 uv0;
layout(location = 3) in ivec2 uv2;
layout(location = 0) out vec4 vertexColor;
layout(location = 1) out vec2 texCoord;
layout(location = 2) out float fogDistance;
void main() {
    // Integer subtraction first keeps precision at large world coordinates.
    vec3 relative = vec3(sections[gl_BaseInstance].origin.xyz - cameraBlock.xyz) - cameraFraction.xyz + position;
    gl_Position = viewProjection * vec4(relative, 1.0);
    fogDistance = fog.z < 0.5 ? length(relative) : max(length(relative.xz), abs(relative.y));
    vertexColor = color * texture(lightMap, clamp(vec2(uv2) / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
    texCoord = uv0;
}
