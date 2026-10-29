package github.com.gengyoubo.vulkanforge.client;

/** Capability data for CPU compatibility frontends; native GL entry points remain trapped by the agent. */
final class NativeFrontendCapabilities {
    static final String[] EXTENSIONS={"GL_ARB_draw_buffers_blend","GL_ARB_shader_texture_lod","GL_ARB_texture_gather","GL_ARB_texture_float","GL_ARB_half_float_pixel","GL_ARB_texture_rg","GL_ARB_framebuffer_object","GL_ARB_vertex_array_object","GL_ARB_sampler_objects"};
    private static Object capabilities;
    private NativeFrontendCapabilities(){ }
    static synchronized Object get(){
        if(capabilities!=null)return capabilities;
        try{
            var field=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);sun.misc.Unsafe access=(sun.misc.Unsafe)field.get(null);
            Class<?> type=Class.forName("org.lwjgl.opengl.GLCapabilities");Object data=access.allocateInstance(type);
            for(String name:new String[]{"OpenGL10","OpenGL11","OpenGL12","OpenGL13","OpenGL14","OpenGL15","OpenGL20","OpenGL21","OpenGL30","OpenGL31","OpenGL32","OpenGL33"})try{access.putBoolean(data,access.objectFieldOffset(type.getField(name)),true);}catch(NoSuchFieldException ignored){}
            for(String name:EXTENSIONS)try{access.putBoolean(data,access.objectFieldOffset(type.getField(name)),true);}catch(NoSuchFieldException ignored){}
            capabilities=data;return data;
        }catch(ReflectiveOperationException error){throw new IllegalStateException("Cannot construct native compatibility capability data",error);}
    }
}
