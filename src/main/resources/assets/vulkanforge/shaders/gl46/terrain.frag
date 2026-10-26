#version 460 core
layout(std140, binding = 0) uniform Frame {
    mat4 viewProjection;
    ivec4 cameraBlock;
    vec4 cameraFraction;
    vec4 fogColor;
    vec4 fog;
    uvec4 counts;
};
layout(binding = 0) uniform sampler2D blockAtlas;
layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 texCoord;
layout(location = 2) in float fogDistance;
layout(location = 0) out vec4 fragColor;
void main() {
    vec4 color = texture(blockAtlas, texCoord) * vertexColor;
    // Solid, cutoutMipped and cutout arrive as one mesh; same 0.1 threshold as the Vulkan world pass.
    if (color.a < 0.1) discard;
    float amount = fogDistance <= fog.x ? 0.0 : fogDistance < fog.y ? smoothstep(fog.x, fog.y, fogDistance) : 1.0;
    fragColor = vec4(mix(color.rgb, fogColor.rgb, amount * fogColor.a), color.a);
}
