#ifdef UNIFIED_VULKAN
#define SET0 set=0,
#else
#define SET0
#endif
struct Record { vec4 rect; vec4 color; vec4 details; };
layout(SET0 std430,binding=0) readonly buffer Records { Record records[]; };
