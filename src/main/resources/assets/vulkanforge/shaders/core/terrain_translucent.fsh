#version 150
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
in vec2 texCoord;
out vec4 fragColor;
// Premultiplied Vulkan translucency, blended with (ONE, ONE_MINUS_SRC_ALPHA) and depth-tested against GL entities.
void main() {
    vec4 color = texture(Sampler0, texCoord);
    if (color.a < 1.0 / 255.0) discard;
    fragColor = color;
    gl_FragDepth = texture(Sampler1, texCoord).r;
}
