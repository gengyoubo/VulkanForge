package github.com.gengyoubo.vulkanforge.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.vulkanforge.bridge.WorldProtocol;
import github.com.gengyoubo.vulkanforge.config.VulkanForgeConfig.OpenGLVersion;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL46C.*;

/** Real GPU checks for sampling cadence, mesh residency flags, and restoration across foreign raw GL calls. */
public final class GlTerrainOptimizationSmoke {
    private static final List<String> report=new ArrayList<>();
    public static void main(String[] args) throws Exception {
        Path output=Path.of(System.getProperty("bench.output","build/reports/terrain-opt-smoke"));Files.createDirectories(output);
        System.setProperty("vulkanforge.offscreenReportDir",output.toAbsolutePath().toString());
        RenderSystem.initRenderThread();
        if(!glfwInit()) throw new AssertionError("GLFW init failed");
        long window=0;
        try {
            glfwDefaultWindowHints();glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,6);glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
            window=glfwCreateWindow(32,32,"Hidden optimization check",0,0);if(window==0)throw new AssertionError("Context failed");
            glfwMakeContextCurrent(window);GL.createCapabilities();Gl46Context.select(OpenGLVersion.GL_4_6);
            int color=texture(64,GL_RGBA8,-1),depth=texture(64,GL_DEPTH_COMPONENT32F,0);
            int target=glGenFramebuffers();glBindFramebuffer(GL_FRAMEBUFFER,target);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,color,0);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,depth,0);
            check(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE,"FBO complete");glViewport(0,0,64,64);
            int atlas=texture(1,GL_RGBA8,-1),light=texture(16,GL_RGBA8,-1);
            RenderSystem.setShaderFogStart(100);RenderSystem.setShaderFogEnd(200);
            try(Gl46Terrain terrain=new Gl46Terrain()) {
                terrain.send(new WorldProtocol.Reset(1));terrain.send(camera(1));terrain.quads(1,1,0,0,0,layers(0,0xff0000ff));
                for(int i=0;i<180;i++) draw(terrain,atlas,light);
                pixel(255,0,0);long[] stats=terrain.submissionStats();
                int interval=Math.max(0,Integer.getInteger("vulkanforge.gl46.counterInterval",60));
                check(stats[1]==(interval==0?0:(179/interval+1)),"sampling cadence copies="+stats[1]+" interval="+interval);
                check(stats[4]==180&&stats[5]==360,"two globally empty layers skipped without visible-count readback");
                if(interval>0)check(stats[2]>0&&stats[2]<=stats[1],"completed samples collected once");else check(stats[2]==0,"sampling disabled without affecting pixels");
                terrain.quads(1,1,0,0,0,layers(1,0xff00ff00));long calls=terrain.submissionStats()[4];draw(terrain,atlas,light);pixel(0,255,0);
                check(terrain.submissionStats()[4]-calls==1,"mesh replacement updates layer residency");
                terrain.quads(1,1,0,0,0,new byte[][]{quad(0xff0000ff),quad(0xff00ff00),quad(0xffff0000),new byte[0]});calls=terrain.submissionStats()[4];draw(terrain,atlas,light);pixel(0,0,255);
                check(terrain.submissionStats()[4]-calls==3,"all populated layers submitted");
                terrain.send(new WorldProtocol.Remove(1,1));calls=terrain.submissionStats()[4];draw(terrain,atlas,light);pixel(0,0,0);
                check(terrain.submissionStats()[4]==calls,"removing last section skips all layers");
                terrain.quads(1,2,100,0,0,layers(0,0xff0000ff));calls=terrain.submissionStats()[4];draw(terrain,atlas,light);pixel(0,0,0);
                check(terrain.submissionStats()[4]-calls==1,"resident but GPU-culled layer still submits Indirect Count");
                terrain.send(new WorldProtocol.Reset(2));terrain.send(camera(2));terrain.quads(2,3,0,0,0,layers(2,0xffff0000));calls=terrain.submissionStats()[4];draw(terrain,atlas,light);pixel(0,0,255);
                check(terrain.submissionStats()[4]-calls==1,"epoch reset clears residency and pending samples");
                report.add("SUBMISSIONS="+Arrays.toString(terrain.submissionStats()));
            }
            if(Gl46State.differential()) stateRestoration(atlas,light);
            bindlessProbe();
            glDeleteTextures(atlas);glDeleteTextures(light);glDeleteTextures(color);glDeleteTextures(depth);glDeleteFramebuffers(target);
            check(glGetError()==GL_NO_ERROR,"GL_ERRORS=0");report.add("STATUS=OK");
        } catch(Throwable error) {report.add("STATUS=FAILED "+error);throw error;}
        finally {Files.write(output.resolve("report.txt"),report);report.forEach(System.out::println);if(window!=0)glfwDestroyWindow(window);glfwTerminate();}
    }
    private static void stateRestoration(int first,int second) {
        RenderSystem.disableCull();RenderSystem.enableCull();RenderSystem.disableDepthTest();RenderSystem.enableDepthTest();RenderSystem.depthFunc(GL_LESS);RenderSystem.depthFunc(GL_LEQUAL);
        RenderSystem.disableBlend();RenderSystem.blendFuncSeparate(GL_ONE,GL_ZERO,GL_ONE,GL_ZERO);RenderSystem.depthMask(true);
        for(int unit=0;unit<3;unit++){RenderSystem.activeTexture(GL_TEXTURE0+unit);RenderSystem.bindTexture(first);}RenderSystem.activeTexture(GL_TEXTURE0);
        glUseProgram(0);check(GlStateCache.initialise(),"vanilla cache initially coherent");
        int sampler=glGenSamplers();glBindSampler(0,sampler);
        // Deliberately bypass Minecraft's caches before entering our pass.
        glDisable(GL_CULL_FACE);glBindTexture(GL_TEXTURE_2D,second);
        var saved=GlStateCache.actualSnapshot();
        var program=Gl46Programs.load("terrain.vert","terrain.frag");
        try {
            try(Gl46State scope=new Gl46State()) {
                Gl46State.glUseProgram(program.id());Gl46State.glEnable(GL_CULL_FACE);Gl46State.glDisable(GL_DEPTH_TEST);Gl46State.glDepthMask(false);Gl46State.glDepthFunc(GL_ALWAYS);
                Gl46State.glEnable(GL_BLEND);Gl46State.glBlendFuncSeparate(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA,GL_ONE,GL_ONE_MINUS_SRC_ALPHA);
                Gl46State.glBindSampler(0,0);GlCompat.glBindTextureUnit(0,first);
            }
            check(!GlStateCache.trusted(),"foreign raw GL invalidates vanilla cache trust");
            check(saved.equals(GlStateCache.actualSnapshot())&&glGetIntegeri(GL_SAMPLER_BINDING,0)==sampler,"real boundary including foreign sampler restored");
            try {try(Gl46State scope=new Gl46State()){Gl46State.glDisable(GL_DEPTH_TEST);throw new IllegalArgumentException("scope failure");}}
            catch(IllegalArgumentException expected){}
            check(saved.equals(GlStateCache.actualSnapshot()),"exception path restores state");
            try(Gl46State scope=new Gl46State()) {Gl46State.invalidateCurrent();glEnable(GL_CULL_FACE);glDepthFunc(GL_GREATER);glBindSampler(0,0);}
            check(saved.equals(GlStateCache.actualSnapshot())&&glGetIntegeri(GL_SAMPLER_BINDING,0)==sampler,"unknown callback falls back to full restoration");
            try(Gl46State outer=new Gl46State()) {Gl46State.glUseProgram(program.id());try(Gl46State inner=new Gl46State()){Gl46State.glDepthFunc(GL_NEVER);}}
            check(saved.equals(GlStateCache.actualSnapshot()),"nested scopes restore conservatively");
            long before=Gl46State.stats()[3];try(Gl46State unchanged=new Gl46State()){}
            check(Gl46State.stats()[3]>before,"unchanged state avoids native restore calls");
            report.add("STATE_RESTORE="+Arrays.toString(Gl46State.stats()));
        } finally {glBindSampler(0,0);glDeleteSamplers(sampler);glDeleteProgram(program.id());}
    }
    private static void bindlessProbe() {
        var caps=GL.getCapabilities();
        report.add("BINDLESS_CAPS ARB="+caps.GL_ARB_bindless_texture+" NV="+caps.GL_NV_bindless_texture+" GPU_INT64="+caps.GL_ARB_gpu_shader_int64);
        if(!caps.GL_ARB_bindless_texture)return;
        int red=texture(1,GL_RGBA8,0xff0000ff),blue=texture(1,GL_RGBA8,0xffff0000),vao=glGenVertexArrays(),handles=glGenBuffers(),indirect=glGenBuffers();
        long h0=ARBBindlessTexture.glGetTextureHandleARB(red),h1=ARBBindlessTexture.glGetTextureHandleARB(blue);
        int vs=0,fs=0,program=0;
        try {
            ARBBindlessTexture.glMakeTextureHandleResidentARB(h0);ARBBindlessTexture.glMakeTextureHandleResidentARB(h1);
            vs=compile(GL_VERTEX_SHADER,"#version 460 core\nflat out int id;void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.0-1.0,0,1);id=gl_BaseInstance;}");
            fs=compile(GL_FRAGMENT_SHADER,"#version 460 core\n#extension GL_ARB_bindless_texture : require\nlayout(std430,binding=0) readonly buffer Handles {uvec2 handles[];};flat in int id;out vec4 color;void main(){if((id==0&&gl_FragCoord.x>=32)||(id==1&&gl_FragCoord.x<32))discard;color=texture(sampler2D(handles[id]),vec2(.5));}");
            program=glCreateProgram();glAttachShader(program,vs);glAttachShader(program,fs);glLinkProgram(program);check(glGetProgrami(program,GL_LINK_STATUS)!=0,"bindless GLSL program links");
            glBindBuffer(GL_SHADER_STORAGE_BUFFER,handles);glBufferData(GL_SHADER_STORAGE_BUFFER,new long[]{h0,h1},GL_STATIC_DRAW);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,handles);
            glBindBuffer(GL_DRAW_INDIRECT_BUFFER,indirect);glBufferData(GL_DRAW_INDIRECT_BUFFER,new int[]{3,1,0,0,3,1,0,1},GL_STATIC_DRAW);
            glUseProgram(program);glBindVertexArray(vao);glDisable(GL_DEPTH_TEST);glDisable(GL_BLEND);glDisable(GL_CULL_FACE);
            glMultiDrawArraysIndirect(GL_TRIANGLES,0L,2,16);glFinish();
            pixelAt(16,32,255,0,0);pixelAt(48,32,0,0,255);
            check(ARBBindlessTexture.glIsTextureHandleResidentARB(h0)&&ARBBindlessTexture.glIsTextureHandleResidentARB(h1),"bindless: two resident textures in one MDI, both pixel colors correct");
        } finally {
            glFinish();ARBBindlessTexture.glMakeTextureHandleNonResidentARB(h0);ARBBindlessTexture.glMakeTextureHandleNonResidentARB(h1);
            glUseProgram(0);glBindVertexArray(0);if(program!=0)glDeleteProgram(program);if(vs!=0)glDeleteShader(vs);if(fs!=0)glDeleteShader(fs);
            glDeleteBuffers(handles);glDeleteBuffers(indirect);glDeleteVertexArrays(vao);glDeleteTextures(red);glDeleteTextures(blue);
        }
    }
    private static int compile(int type,String source){int shader=glCreateShader(type);glShaderSource(shader,source);glCompileShader(shader);if(glGetShaderi(shader,GL_COMPILE_STATUS)==0)throw new AssertionError(glGetShaderInfoLog(shader));return shader;}
    private static void draw(Gl46Terrain terrain,int atlas,int light){glDepthMask(true);glClearColor(0,0,0,1);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);check(terrain.drawOpaque(atlas,light),"draw");glFinish();}
    private static WorldProtocol.Camera camera(long epoch){return new WorldProtocol.Camera(epoch,0,0,0,new org.joml.Matrix4f().get(new float[16]),0,0,0);}
    private static byte[][] layers(int layer,int color){byte[][] layers={new byte[0],new byte[0],new byte[0],new byte[0]};layers[layer]=quad(color);return layers;}
    private static byte[] quad(int color){ByteBuffer b=ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);for(float[] p:new float[][]{{-.8f,-.8f},{.8f,-.8f},{.8f,.8f},{-.8f,.8f}})b.putFloat(p[0]).putFloat(p[1]).putFloat(0).putInt(color).putFloat(.5f).putFloat(.5f).putShort((short)240).putShort((short)240).putInt(0);return b.array();}
    private static int texture(int size,int format,int color){int id=glGenTextures();glBindTexture(GL_TEXTURE_2D,id);glTexStorage2D(GL_TEXTURE_2D,1,format,size,size);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAX_LEVEL,0);if(format==GL_RGBA8){int[] data=new int[size*size];Arrays.fill(data,color);glTexSubImage2D(GL_TEXTURE_2D,0,0,0,size,size,GL_RGBA,GL_UNSIGNED_BYTE,data);}return id;}
    private static void pixel(int r,int g,int b){pixelAt(32,32,r,g,b);}
    private static void pixelAt(int x,int y,int r,int g,int b){ByteBuffer value=MemoryUtil.memAlloc(4);try{glReadPixels(x,y,1,1,GL_RGBA,GL_UNSIGNED_BYTE,value);check(Math.abs((value.get(0)&255)-r)<3&&Math.abs((value.get(1)&255)-g)<3&&Math.abs((value.get(2)&255)-b)<3,"pixel RGB="+(value.get(0)&255)+","+(value.get(1)&255)+","+(value.get(2)&255));}finally{MemoryUtil.memFree(value);}}
    private static void check(boolean pass,String message){if(!pass)throw new AssertionError(message);if(!message.equals("draw")&&!message.startsWith("pixel"))report.add("PASS "+message);}
}
