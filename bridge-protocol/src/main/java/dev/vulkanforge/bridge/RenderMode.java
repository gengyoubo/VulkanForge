package dev.vulkanforge.bridge;

/** Running modes share two APIs; only GL_VK permits interop and OpenGL fallback. */
public enum RenderMode {
    GL(false,true,false), GL_VK(true,true,true), VK(true,false,false);
    private final boolean vulkan,openGL,interop;
    RenderMode(boolean vulkan,boolean openGL,boolean interop){this.vulkan=vulkan;this.openGL=openGL;this.interop=interop;}
    public boolean usesVulkan(){return vulkan;}
    public boolean allowsOpenGL(){return openGL;}
    public boolean allowsInterop(){return interop;}
    public boolean allowsGlFallback(){return this==GL_VK;}
    public static RenderMode parse(Object value,RenderMode fallback){
        if(value==null)return fallback;
        return switch(value.toString().trim().toUpperCase(java.util.Locale.ROOT).replace('-','_')){
            case "GL","OPENGL"->GL;
            // Existing VULKAN settings describe the old hybrid renderer, never silently become pure VK.
            case "GL_VK","VULKAN","HYBRID","GLVK_HYBRID"->GL_VK;
            case "VK","VK_NATIVE"->VK;
            default->fallback;
        };
    }
}
