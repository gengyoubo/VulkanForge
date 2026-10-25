#version 450
#extension GL_GOOGLE_include_directive : require
#include "entity_common.glsl"
// Port of vanilla rendertype_entity_* (NEW_ENTITY): diffuse light, overlay and lightmap texel fetches, fog distance.
layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV1;
layout(location = 4) in ivec2 UV2;
layout(location = 5) in vec4 Normal;
layout(set = 0, binding = 1) uniform sampler2D overlay;
layout(set = 0, binding = 2) uniform sampler2D lightMap;
layout(location = 0) out float vertexDistance;
layout(location = 1) out vec4 vertexColor;
layout(location = 2) out vec4 lightMapColor;
layout(location = 3) out vec4 overlayColor;
layout(location = 4) out vec2 texCoord0;
float fogDistance(vec3 position) {
    mat3 rotation = mat3(draw.inverseViewRotation[0].xyz, draw.inverseViewRotation[1].xyz, draw.inverseViewRotation[2].xyz);
    vec3 pos = rotation * position;
    if (draw.fog.z < 0.5) return length((draw.modelView * vec4(pos, 1.0)).xyz);
    return max(length((draw.modelView * vec4(pos.x, 0.0, pos.z, 1.0)).xyz), length((draw.modelView * vec4(0.0, pos.y, 0.0, 1.0)).xyz));
}
void main() {
    gl_Position = draw.projection * draw.modelView * vec4(Position, 1.0);
    gl_Position.y = -gl_Position.y;
    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;
    vertexDistance = fogDistance(Position);
    vec3 l0 = normalize(draw.light0.xyz), l1 = normalize(draw.light1.xyz);
    float light = min(1.0, (max(0.0, dot(l0, Normal.xyz)) + max(0.0, dot(l1, Normal.xyz))) * 0.6 + 0.4);
    vertexColor = vec4(Color.rgb * light, Color.a);
    lightMapColor = texelFetch(lightMap, UV2 / 16, 0);
    overlayColor = texelFetch(overlay, UV1, 0);
    texCoord0 = UV0;
}
