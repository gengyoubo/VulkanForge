layout(binding=0) uniform sampler2D sourceColor;
layout(location=0) out vec4 outputColor;
void main(){vec4 c=texelFetch(sourceColor,ivec2(gl_FragCoord.xy),0);outputColor=vec4(c.rgb*0.75+vec3(0.0625,0.03125,0.015625),1.0);}
