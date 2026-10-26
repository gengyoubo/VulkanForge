#version 460 core
// Port of vanilla rendertype_entity_* (NEW_ENTITY). One MultiDrawElementsIndirect per state/texture run; the Draws SSBO is
// bound at the run's first record, so gl_DrawID selects this draw's matrices, ColorModulator, lights and fog.
struct Draw {
    mat4 projection;
    mat4 modelView;
    vec4 inverseViewRotation[3]; // RenderSystem.getInverseViewRotationMatrix() columns
    vec4 colorModulator;
    vec4 light0;
    vec4 light1;
    vec4 fogColor;
    vec4 fog;                    // start, end, shape (0 sphere / 1 cylinder), fragment variant
};
layout(std430, binding = 0) readonly buffer Draws { Draw draws[]; };
layout(binding = 1) uniform sampler2D overlay;
layout(binding = 2) uniform sampler2D lightMap;
layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV1;
layout(location = 4) in ivec2 UV2;
layout(location = 5) in vec3 Normal;
layout(location = 0) out float vertexDistance;
layout(location = 1) out vec4 vertexColor;
layout(location = 2) out vec4 lightMapColor;
layout(location = 3) out vec4 overlayColor;
layout(location = 4) out vec2 texCoord0;
layout(location = 5) flat out int drawIndex;
void main() {
    Draw draw = draws[gl_DrawID];
    gl_Position = draw.projection * draw.modelView * vec4(Position, 1.0);
    // vanilla fog_distance(ModelViewMat, IViewRotMat * Position, FogShape)
    vec3 pos = mat3(draw.inverseViewRotation[0].xyz, draw.inverseViewRotation[1].xyz, draw.inverseViewRotation[2].xyz) * Position;
    vertexDistance = draw.fog.z < 0.5 ? length((draw.modelView * vec4(pos, 1.0)).xyz)
            : max(length((draw.modelView * vec4(pos.x, 0.0, pos.z, 1.0)).xyz), length((draw.modelView * vec4(0.0, pos.y, 0.0, 1.0)).xyz));
    vec3 l0 = normalize(draw.light0.xyz), l1 = normalize(draw.light1.xyz);
    float light = min(1.0, (max(0.0, dot(l0, Normal)) + max(0.0, dot(l1, Normal))) * 0.6 + 0.4);
    vertexColor = vec4(Color.rgb * light, Color.a);
    lightMapColor = texelFetch(lightMap, UV2 / 16, 0);
    overlayColor = texelFetch(overlay, UV1, 0);
    texCoord0 = UV0;
    drawIndex = gl_DrawID;
}
