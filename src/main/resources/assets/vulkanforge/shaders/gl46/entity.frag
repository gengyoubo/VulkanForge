#version 460 core
// Variants: 0 overlay + discard before tint (entity cutout/translucent), 1 overlay, no discard (entity solid),
// 2 lightmap tint + discard after tint (translucent_cull, armor), 3 lightmap tint, no discard (no_outline).
struct Draw {
    mat4 projection;
    mat4 modelView;
    vec4 inverseViewRotation[3];
    vec4 colorModulator;
    vec4 light0;
    vec4 light1;
    vec4 fogColor;
    vec4 fog;
};
layout(std430, binding = 0) readonly buffer Draws { Draw draws[]; };
layout(binding = 0) uniform sampler2D Sampler0;
layout(location = 0) in float vertexDistance;
layout(location = 1) in vec4 vertexColor;
layout(location = 2) in vec4 lightMapColor;
layout(location = 3) in vec4 overlayColor;
layout(location = 4) in vec2 texCoord0;
layout(location = 5) flat in int drawIndex;
layout(location = 0) out vec4 fragColor;
void main() {
    vec4 colorModulator = draws[drawIndex].colorModulator, fogColor = draws[drawIndex].fogColor, fog = draws[drawIndex].fog;
    int variant = int(fog.w + 0.5);
    vec4 color = texture(Sampler0, texCoord0);
    if (variant <= 1) {
        if (variant == 0 && color.a < 0.1) discard;
        color *= vertexColor * colorModulator;
        color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
        color *= lightMapColor;
    } else {
        color *= vertexColor * colorModulator * lightMapColor;
        if (variant == 2 && color.a < 0.1) discard;
    }
    if (vertexDistance > fog.x) {
        float amount = vertexDistance < fog.y ? smoothstep(fog.x, fog.y, vertexDistance) : 1.0;
        color.rgb = mix(color.rgb, fogColor.rgb, amount * fogColor.a);
    }
    fragColor = color;
}
