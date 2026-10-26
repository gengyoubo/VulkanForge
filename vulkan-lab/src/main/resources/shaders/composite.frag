#version 450
layout(set=0,binding=0) uniform sampler2D sourceColor;
layout(set=0,binding=1) uniform sampler2D sourceDepth;
layout(location=0) in vec2 uv;
layout(location=0) out vec4 color;
void main() {
    color = texture(sourceColor, uv);
    if (color.a <= 0.0) discard;
    gl_FragDepth = texture(sourceDepth, uv).r;
}
