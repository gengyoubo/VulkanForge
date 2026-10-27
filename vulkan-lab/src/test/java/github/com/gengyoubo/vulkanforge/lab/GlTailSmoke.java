package github.com.gengyoubo.vulkanforge.lab;

import dev.vulkanforge.bridge.WorldProtocol;
import github.com.gengyoubo.vulkanforge.interop.GlInterop;
import java.nio.*;
import java.nio.file.*;
import java.util.function.Consumer;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;

/** Real shared terrain, then GL opaque/compatibility/translucent draws, with no Vulkan re-entry. */
public final class GlTailSmoke {
    public static void main(String[] ignored) throws Exception {
        org.lwjgl.system.Configuration.STACK_SIZE.set(4096);
        if (!glfwInit()) throw new AssertionError("GLFW");
        glfwDefaultWindowHints(); glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        long window = glfwCreateWindow(32,32,"GL tail functional smoke",0,0);
        if (window == 0) throw new AssertionError("GL context");
        glfwMakeContextCurrent(window); org.lwjgl.opengl.GL.createCapabilities();
        Path out=Path.of(System.getProperty("vulkanforge.smokeReportDir","build/reports/gl-tail-smoke")); Files.createDirectories(out);
        // The explicit startup plan must win even when the old standalone JVM switch is false.
        System.setProperty("vulkanforge.glTailCompatibility","false");
        try (MainWindowBackend backend=new MainWindowBackend(out.resolve("backend.txt").toString(),GlInterop.deviceUUID(),GlInterop::importImage,org.lwjgl.opengl.GL11::glFinish,VulkanContext.requestedApiVersion(),"",true)) {
            var send=backend.worldCommands(GlInterop::importTerrain);
            Consumer<WorldProtocol.Command> command=c->send.accept(WorldProtocol.pack(c));
            command.accept(new WorldProtocol.Reset(1)); command.accept(new WorldProtocol.Atlas(1,1,1,new byte[]{-1,-1,-1,-1}));
            byte[] light=new byte[1024];java.util.Arrays.fill(light,(byte)-1);
            command.accept(new WorldProtocol.TexturePatch(1,WorldProtocol.LIGHT_MAP,0,0,16,16,light));
            command.accept(new WorldProtocol.Camera(1,0,0,0,new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1},0,0,0));
            command.accept(new WorldProtocol.Mesh(1,0,0,0,0,quad(.2f,0xff0000ff),quad(-.2f,0x80ff0000)));
            int vao=glGenVertexArrays(),program=program(); glBindVertexArray(vao);glUseProgram(program);
            glUniform1i(glGetUniformLocation(program,"sourceColor"),0);glUniform1i(glGetUniformLocation(program,"sourceDepth"),1);
            for(int size:new int[]{32,48}) {
                int fbo=glGenFramebuffers(),color=glGenTextures(),depth=glGenTextures();glBindFramebuffer(GL_FRAMEBUFFER,fbo);
                glBindTexture(GL_TEXTURE_2D,color);glTexStorage2D(GL_TEXTURE_2D,1,GL_RGBA8,size,size);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,color,0);
                glBindTexture(GL_TEXTURE_2D,depth);glTexStorage2D(GL_TEXTURE_2D,1,GL_DEPTH_COMPONENT32F,size,size);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,depth,0);
                require(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE,"private GL FBO");
                for(int frame=0;frame<6;frame++) {
                    long[] before=backend.interopStats();
                    int opaque=backend.renderWorld(size,size);long[] stats=backend.worldStats();
                    glBindFramebuffer(GL_FRAMEBUFFER,fbo);glViewport(0,0,size,size);glDisable(GL_SCISSOR_TEST);glDisable(GL_DITHER);glDisable(GL_FRAMEBUFFER_SRGB);glDisable(GL_CULL_FACE);
                    glDepthMask(true);glColorMask(true,true,true,true);glClearColor(0,0,0,0);glClearDepth(1);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
                    glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LEQUAL);glDisable(GL_BLEND);draw(opaque,(int)stats[9]);
                    // GL compatibility content sits behind/in front of the future translucent composite.
                    glEnable(GL_SCISSOR_TEST);glClearColor(0,1,0,1);
                    glScissor(0,0,size/2,size);glClearDepth(.3);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
                    glScissor(size/2,0,size/2,size);glClearDepth(.5);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);glDisable(GL_SCISSOR_TEST);
                    glEnable(GL_BLEND);glBlendFunc(GL_ONE,GL_ONE_MINUS_SRC_ALPHA);draw((int)stats[13],(int)stats[14]);
                    pixel(size*3/8,size/2,0,255,0,.3f);pixel(size*5/8,size/2,0,127,128,.4f);
                    require(!backend.nativeComposite(fbo,size,size,0,new Object[0]),"VK composite entered GL tail");
                    require(!backend.nativePost(null),"VK post entered GL tail");
                    require(backend.renderOverlay(new Object[0])==null,"VK overlay entered GL tail");
                    backend.finishWorldFrame();long[] after=backend.interopStats();
                    require(after[4]-before[4]==1&&after[5]-before[5]==2&&after[8]-before[8]==8,"tail submit/handoff counts");
                    require(after[0]-before[0]==1&&after[1]-before[1]==1,"unnecessary entity depth copy");
                    require(backend.frames()==0&&backend.renderTargetStats()[0]==0&&backend.guiPasses()==0,"unexpected VK presentation/targets/GUI");
                }
                glDeleteFramebuffers(fbo);glDeleteTextures(color);glDeleteTextures(depth);
            }
            boolean rejected=false;try{backend.renderWorldWithSky(32,32,new Object[0],true);}catch(IllegalStateException expected){rejected=true;}
            require(rejected,"native-owned terrain accepted in GL tail");
            backend.closeWorld();require(glGetError()==GL_NO_ERROR,"GL error");
            glDeleteProgram(program);glDeleteVertexArrays(vao);
            Files.writeString(out.resolve("result.txt"),"STATUS=OK\nONE_VK_SUBMIT_PER_FRAME=1\nGL_GROUP_CALLS_PER_FRAME=2\nGL_IMAGE_REFERENCES_PER_FRAME=8\nDEPTH_COPIES_PER_FRAME=1\nVK_FINAL_PRESENTS=0\nGL_ORDER_AND_DEPTH=PASS\n");
            System.out.println("GL_TAIL_SMOKE=OK (GL interposition depth, both slots, resize, one submit, no VK re-entry or present)");
        } finally {glfwDestroyWindow(window);glfwTerminate();}
    }
    private static byte[] quad(float z,int color) {
        ByteBuffer mesh=ByteBuffer.allocate(6*28).order(ByteOrder.LITTLE_ENDIAN);
        float[][] corners={{-.9f,-.9f},{.9f,-.9f},{.9f,.9f},{-.9f,.9f}};
        for(int i:new int[]{0,1,2,2,3,0})mesh.putFloat(corners[i][0]).putFloat(corners[i][1]).putFloat(z).putInt(color).putFloat(.5f).putFloat(.5f).putShort((short)240).putShort((short)240);
        return mesh.array();
    }
    private static void draw(int color,int depth){glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,color);glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,depth);glDrawArrays(GL_TRIANGLES,0,3);}
    private static int program() {
        int p=glCreateProgram();
        String[] sources={"#version 430\nout vec2 uv;void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);uv=vec2(p.x,1-p.y);gl_Position=vec4(p*2-1,0,1);}",
                "#version 430\nin vec2 uv;out vec4 color;uniform sampler2D sourceColor;uniform sampler2D sourceDepth;void main(){color=texture(sourceColor,uv);if(color.a<.003)discard;gl_FragDepth=texture(sourceDepth,uv).r;}"};
        for(int i=0;i<2;i++){int s=glCreateShader(i==0?GL_VERTEX_SHADER:GL_FRAGMENT_SHADER);glShaderSource(s,sources[i]);glCompileShader(s);require(glGetShaderi(s,GL_COMPILE_STATUS)!=0,glGetShaderInfoLog(s));glAttachShader(p,s);glDeleteShader(s);}
        glLinkProgram(p);require(glGetProgrami(p,GL_LINK_STATUS)!=0,glGetProgramInfoLog(p));return p;
    }
    private static void pixel(int x,int y,int r,int g,int b,float depth) {
        try(var stack=org.lwjgl.system.MemoryStack.stackPush()) {
            ByteBuffer rgba=stack.malloc(4);var z=stack.mallocFloat(1);glReadPixels(x,y,1,1,GL_RGBA,GL_UNSIGNED_BYTE,rgba);glReadPixels(x,y,1,1,GL_DEPTH_COMPONENT,GL_FLOAT,z);
            require(Math.abs((rgba.get(0)&255)-r)<=2&&Math.abs((rgba.get(1)&255)-g)<=2&&Math.abs((rgba.get(2)&255)-b)<=2&&Math.abs(z.get(0)-depth)<1e-6,"GL order/depth at "+x+": "+(rgba.get(0)&255)+","+(rgba.get(1)&255)+","+(rgba.get(2)&255)+" z="+z.get(0));
        }
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
