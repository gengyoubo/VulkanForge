#version 450
#extension GL_GOOGLE_include_directive : require
#include "entity_common.glsl"
// Port of vanilla particle.vsh (PARTICLE format; also used by rain and snow): lightmap tint, fog distance without view rotation.
layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV2;
layout(set = 0, binding = 2) uniform sampler2D lightMap;
layout(location = 0) out float vertexDistance;
layout(location = 1) out vec4 vertexColor;
layout(location = 2) out vec4 lightMapColor;
layout(location = 3) out vec4 overlayColor;
layout(location = 4) out vec2 texCoord0;
void main() {
    gl_Position = draw.projection * draw.modelView * vec4(Position, 1.0);
    gl_Position.y = -gl_Position.y;
    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;
    vertexDistance = draw.fog.z < 0.5 ? length((draw.modelView * vec4(Position, 1.0)).xyz)
            : max(length((draw.modelView * vec4(Position.x, 0.0, Position.z, 1.0)).xyz), length((draw.modelView * vec4(0.0, Position.y, 0.0, 1.0)).xyz));
    vertexColor = Color;
    lightMapColor = texelFetch(lightMap, UV2 / 16, 0);
    overlayColor = vec4(1.0);
    texCoord0 = UV0;
}
