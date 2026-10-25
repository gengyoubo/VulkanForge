#version 450
#extension GL_GOOGLE_include_directive : require
#include "entity_common.glsl"
// Variants: 0 overlay + discard before tint (entity cutout/translucent), 1 overlay, no discard (entity solid),
// 2 lightmap tint + discard after tint (translucent_cull, armor, text, particle, clouds), 3 lightmap tint, no discard (no_outline, position),
// 4 discard alpha == 0 before ColorModulator (position_color, position_tex), 6 discard alpha < 0.1 before ColorModulator (position_tex_color).
// Shaders without fog in vanilla get a fog start beyond any distance from the capture side.
layout(set = 0, binding = 0) uniform sampler2D Sampler0;
layout(location = 0) in float vertexDistance;
layout(location = 1) in vec4 vertexColor;
layout(location = 2) in vec4 lightMapColor;
layout(location = 3) in vec4 overlayColor;
layout(location = 4) in vec2 texCoord0;
layout(location = 0) out vec4 outColor;
void main() {
    int variant = int(draw.fog.w + 0.5);
    vec4 color = texture(Sampler0, texCoord0);
    if (variant <= 1) {
        if (variant == 0 && color.a < 0.1) discard;
        color *= vertexColor * draw.colorModulator;
        color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
        color *= lightMapColor;
    } else if (variant >= 4) {
        color *= vertexColor;
        if (variant == 4 ? color.a == 0.0 : color.a < 0.1) discard;
        color *= draw.colorModulator;
    } else {
        color *= vertexColor * draw.colorModulator * lightMapColor;
        if (variant == 2 && color.a < 0.1) discard;
    }
    if (vertexDistance > draw.fog.x) {
        float fogValue = vertexDistance < draw.fog.y ? smoothstep(draw.fog.x, draw.fog.y, vertexDistance) : 1.0;
        color.rgb = mix(color.rgb, draw.fogColor.rgb, fogValue * draw.fogColor.a);
    }
    // light1.w: 0 premultiplied "over" (blend ONE, ONE_MINUS_SRC_ALPHA), 1 opaque (covers GL completely),
    // 2 additive (GL SRC_ALPHA, ONE: adds rgb * a and leaves coverage untouched).
    if (draw.light1.w > 1.5) outColor = vec4(color.rgb * color.a, 0.0);
    else outColor = draw.light1.w > 0.5 ? vec4(color.rgb, 1.0) : vec4(color.rgb * color.a, color.a);
}
