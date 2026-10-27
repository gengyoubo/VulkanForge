#include "unified_common.glsl"
layout(location=0) in vec3 position;
layout(location=1) in vec3 normal;
layout(location=2) in uint recordID;
layout(location=0) flat out vec4 color;
void main() {
    Record r=records[recordID];
    vec3 p=vec3(position.xy*r.rect.zw+r.rect.xy,position.z*r.details.y+r.details.x);
    gl_Position=vec4(p,1.0);
#ifdef UNIFIED_VULKAN
    gl_Position.z=(gl_Position.z+gl_Position.w)*0.5;
#endif
    float shade=0.4+0.6*max(0.0,dot(normal,vec3(0.25,0.75,0.5)));
    color=vec4(r.color.rgb*shade,r.color.a);
}
