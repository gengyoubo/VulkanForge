#version 460 core
// Cached block-entity meshes (block-local NEW_ENTITY vertices, uploaded once). One instance per block entity: its PoseStack
// matrices, packed light and overlay, and the index of the Draw record (projection, model view, lights, fog) shared by the run.
struct Draw {
    mat4 projection;
    mat4 modelView;
    vec4 inverseViewRotation[3];
    vec4 colorModulator;
    vec4 light0;
    vec4 light1;
    vec4 fogColor;
    vec4 fog;                    // start, end, shape (0 sphere / 1 cylinder), fragment variant
};
struct Instance {
    mat4 pose;                   // PoseStack.last().pose(): block space to camera space
    vec4 normal[3];              // PoseStack.last().normal() columns
    ivec4 info;                  // x packed light, y packed overlay, z Draw record
};
layout(std430, binding = 0) readonly buffer Draws { Draw draws[]; };
layout(std430, binding = 1) readonly buffer Instances { Instance instances[]; };
layout(binding = 1) uniform sampler2D overlay;
layout(binding = 2) uniform sampler2D lightMap;
layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 5) in vec3 Normal;
layout(location = 0) out float vertexDistance;
layout(location = 1) out vec4 vertexColor;
layout(location = 2) out vec4 lightMapColor;
layout(location = 3) out vec4 overlayColor;
layout(location = 4) out vec2 texCoord0;
layout(location = 5) flat out int drawIndex;
void main() {
    Instance instance = instances[gl_BaseInstance + gl_InstanceID];
    Draw draw = draws[instance.info.z];
    // What vanilla's VertexConsumer does on the CPU: pose * position, normal matrix * normal.
    vec3 position = (instance.pose * vec4(Position, 1.0)).xyz;
    vec3 normal = mat3(instance.normal[0].xyz, instance.normal[1].xyz, instance.normal[2].xyz) * Normal;
    gl_Position = draw.projection * draw.modelView * vec4(position, 1.0);
    vec3 pos = mat3(draw.inverseViewRotation[0].xyz, draw.inverseViewRotation[1].xyz, draw.inverseViewRotation[2].xyz) * position;
    vertexDistance = draw.fog.z < 0.5 ? length((draw.modelView * vec4(pos, 1.0)).xyz)
            : max(length((draw.modelView * vec4(pos.x, 0.0, pos.z, 1.0)).xyz), length((draw.modelView * vec4(0.0, pos.y, 0.0, 1.0)).xyz));
    vec3 l0 = normalize(draw.light0.xyz), l1 = normalize(draw.light1.xyz);
    float light = min(1.0, (max(0.0, dot(l0, normal)) + max(0.0, dot(l1, normal))) * 0.6 + 0.4);
    vertexColor = vec4(Color.rgb * light, Color.a);
    ivec2 uv2 = ivec2(instance.info.x & 0xFFFF, (instance.info.x >> 16) & 0xFFFF);
    ivec2 uv1 = ivec2(instance.info.y & 0xFFFF, (instance.info.y >> 16) & 0xFFFF);
    lightMapColor = texelFetch(lightMap, uv2 / 16, 0);
    overlayColor = texelFetch(overlay, uv1, 0);
    texCoord0 = UV0;
    drawIndex = instance.info.z;
}
