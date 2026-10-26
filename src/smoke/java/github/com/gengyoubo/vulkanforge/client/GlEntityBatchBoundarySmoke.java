package github.com.gengyoubo.vulkanforge.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import github.com.gengyoubo.vulkanforge.config.VulkanForgeConfig.OpenGLVersion;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.opengl.GL;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL46C.*;

/** Real cached-instance GPU submission followed by an unsupported vanilla draw, with no ordinary captured entities. */
public final class GlEntityBatchBoundarySmoke {
    public static void main(String[] args) throws Exception {
        Path output=Path.of("build/reports/entity-batch-boundary").toAbsolutePath();Files.createDirectories(output);
        System.setProperty("vulkanforge.offscreenReportDir",output.toString());
        RenderSystem.initRenderThread();
        if(!glfwInit())throw new AssertionError("GLFW init");
        glfwDefaultWindowHints();glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,6);glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        long window=glfwCreateWindow(32,32,"Hidden entity boundary check",0,0);
        if(window==0)throw new AssertionError("Hidden context");
        try {
            glfwMakeContextCurrent(window);GL.createCapabilities();Gl46Context.select(OpenGLVersion.GL_4_6);
            int color=texture(0),red=texture(0xff0000ff),white=texture(-1),target=glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER,target);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,color,0);
            if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new AssertionError("FBO");
            glViewport(0,0,1,1);glClearColor(0,0,0,1);glClear(GL_COLOR_BUFFER_BIT);
            try(Gl46Entities pass=new Gl46Entities()) {
                seedCachedInstance(pass,red,white);
                var open=Gl46Entities.class.getDeclaredField("open");open.setAccessible(true);open.setBoolean(pass,true);
                BufferBuilder builder=new BufferBuilder(256);builder.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_COLOR);
                for(float[] p:new float[][]{{-1,-1},{1,-1},{1,1},{-1,1}})builder.vertex(p[0],p[1],0).color(0,0,255,255).endVertex();
                BufferBuilder.RenderedBuffer unsupported=builder.end();
                try {if(Gl46Entities.take(unsupported))throw new AssertionError("Unsupported draw must stay vanilla");}
                finally {unsupported.release();}
                // Before the fix split() ignored the cached queue: flushes=0 and the pixel was still black.
                if(pass.stats()[1]!=1||pass.stats()[3]!=1)throw new AssertionError("Cached-only fallback must flush once: "+Arrays.toString(pass.stats()));
                pixel(255,0,0);
                glClearBufferfv(GL_COLOR,0,new float[]{0,0,1,1}); // Foreign vanilla work after the fallback boundary.
                pass.end();pixel(0,0,255);
                if(pass.stats()[1]!=1)throw new AssertionError("End must not replay a consumed instance");
            }
            if(glGetError()!=GL_NO_ERROR)throw new AssertionError("GL error");
            glDeleteFramebuffers(target);glDeleteTextures(color);glDeleteTextures(red);glDeleteTextures(white);
            String result="PASS cached-only queue flushed before unsupported vanilla draw\nPASS red cached instance rendered before blue fallback; no stale instance at end\nPASS GL_ERRORS=0 VISIBLE=false SWAP_CALLS=0\nSTATUS=OK\n";
            Files.writeString(output.resolve("report.txt"),result);System.out.print(result);
        } finally {glfwDestroyWindow(window);glfwTerminate();}
    }

    /** Seed the private cache with one real resident mesh, avoiding a full Minecraft world/renderer bootstrap. */
    @SuppressWarnings("unchecked")
    private static void seedCachedInstance(Gl46Entities owner,int red,int white) throws Exception {
        Gl46BlockEntities cache=(Gl46BlockEntities)field(owner,"blockEntities").get(owner);
        Gl46Buffers.Arena arena=(Gl46Buffers.Arena)field(cache,"arena").get(cache);
        Gl46Buffers.Uploader uploads=(Gl46Buffers.Uploader)field(cache,"uploads").get(cache);
        ByteBuffer mesh=ByteBuffer.allocate(4*36).order(ByteOrder.nativeOrder());
        for(float[] p:new float[][]{{-.8f,-.8f},{.8f,-.8f},{.8f,.8f},{-.8f,.8f}})
            mesh.putFloat(p[0]).putFloat(p[1]).putFloat(0).putInt(-1).putFloat(.5f).putFloat(.5f).putInt(0).putInt(0).putInt(127<<16);
        int first=arena.allocate(4);arena.upload(first,uploads,mesh.array());uploads.flush();
        Gl46Buffers.Ring records=(Gl46Buffers.Ring)field(owner,"records").get(owner);
        int record=records.allocate(256,256);ByteBuffer r=records.mapped;
        for(int i=0;i<256;i+=4)r.putFloat(record+i,0);
        for(int matrix:new int[]{0,64})for(int i=0;i<4;i++)r.putFloat(record+matrix+i*20,1);
        for(int i=0;i<3;i++)r.putFloat(record+128+i*20,1);
        for(int i=0;i<4;i++)r.putFloat(record+176+i*4,1);
        r.putFloat(record+200,1).putFloat(record+216,1).putFloat(record+240,100).putFloat(record+244,200);
        var key=new Gl46Entities.Key(false,GL_ONE,GL_ZERO,GL_ONE,GL_ZERO,false,GL_LEQUAL,true,false,red,white,white);
        Class<?> partType=Class.forName(Gl46BlockEntities.class.getName()+"$Part");
        var constructor=partType.getDeclaredConstructors()[0];constructor.setAccessible(true);
        Object part=constructor.newInstance(key,null,0,first,1);
        field(part,"record").setInt(part,record);field(part,"count").setInt(part,1);
        float[] data=(float[])field(part,"data").get(part);
        for(int i=0;i<4;i++)data[i*5]=1;
        for(int i=0;i<3;i++)data[16+i*5]=1;
        data[30]=Float.intBitsToFloat(record/256);
        ((List<Object>)field(cache,"active").get(cache)).add(part);
    }
    private static java.lang.reflect.Field field(Object target,String name) throws Exception {
        var f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f;
    }
    private static int texture(int rgba) {
        int id=glGenTextures();glBindTexture(GL_TEXTURE_2D,id);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,1,1,0,GL_RGBA,GL_UNSIGNED_BYTE,new int[]{rgba});
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);return id;
    }
    private static void pixel(int red,int green,int blue) {
        int[] pixel=new int[1];glReadPixels(0,0,1,1,GL_RGBA,GL_UNSIGNED_BYTE,pixel);int p=pixel[0];
        if((p&255)!=red||((p>>>8)&255)!=green||((p>>>16)&255)!=blue)throw new AssertionError("Unexpected pixel "+Integer.toHexString(p));
    }
}
