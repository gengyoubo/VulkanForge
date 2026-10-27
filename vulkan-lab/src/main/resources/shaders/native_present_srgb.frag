#version 450
layout(set=0,binding=0) uniform sampler2D scene;
layout(location=0) in vec2 uv;
layout(location=0) out vec4 color;
void main() {
    vec4 sampleColor = texture(scene, uv);
    // Minecraft terrain stores display-encoded colors in its UNORM target.
    // An sRGB swapchain encodes on store, so decode here to preserve those colors.
    vec3 linearColor = mix(pow((sampleColor.rgb + 0.055) / 1.055, vec3(2.4)),
                           sampleColor.rgb / 12.92, lessThanEqual(sampleColor.rgb, vec3(0.04045)));
    color = vec4(linearColor, sampleColor.a);
}
