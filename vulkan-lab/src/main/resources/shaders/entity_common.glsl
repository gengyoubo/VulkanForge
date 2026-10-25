// Shared by entity.vert / text.vert / entity.frag. One 256-byte block per draw (dynamic offset).
layout(set = 1, binding = 0) uniform Draw {
    mat4 projection;
    mat4 modelView;
    vec4 inverseViewRotation[3]; // RenderSystem.getInverseViewRotationMatrix() columns
    vec4 colorModulator;
    vec4 light0;                 // xyz direction, w unused
    vec4 light1;                 // xyz direction, w: 1 = opaque output (blend disabled in GL)
    vec4 fogColor;
    vec4 fog;                    // start, end, shape (0 sphere / 1 cylinder), fragment variant
} draw;
