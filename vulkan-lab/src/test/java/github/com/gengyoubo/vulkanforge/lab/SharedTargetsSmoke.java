package github.com.gengyoubo.vulkanforge.lab;

import dev.vulkanforge.bridge.WorldProtocol;
import github.com.gengyoubo.vulkanforge.interop.GlInterop;
import java.nio.*;
import java.util.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

/** Actual shared attachments: GL clear -> Vulkan depth composite -> GL fallback -> native post -> GL read. */
final class SharedTargetsSmoke {
    static void run(MainWindowBackend backend) {
        var send = backend.worldCommands(GlInterop::importTerrain);
        send.accept(WorldProtocol.pack(new WorldProtocol.Reset(71)));
        send.accept(WorldProtocol.pack(new WorldProtocol.Atlas(71, 1, 1, new byte[]{-1,0,0,-1})));
        byte[] light = new byte[16*16*4]; Arrays.fill(light, (byte)-1);
        send.accept(WorldProtocol.pack(new WorldProtocol.TexturePatch(71, WorldProtocol.LIGHT_MAP, 0,0,16,16,light)));
        send.accept(WorldProtocol.pack(new WorldProtocol.Camera(71,0,0,0,new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1},0,0,0)));
        var vertices = ByteBuffer.allocate(6*WorldProtocol.STRIDE).order(ByteOrder.LITTLE_ENDIAN);
        for (float[] p : new float[][]{{-.5f,-.5f},{.5f,-.5f},{.5f,.5f},{.5f,.5f},{-.5f,.5f},{-.5f,-.5f}})
            vertices.putFloat(p[0]).putFloat(p[1]).putFloat(0).putInt(-1).putFloat(.5f).putFloat(.5f).putShort((short)240).putShort((short)240);
        send.accept(WorldProtocol.pack(new WorldProtocol.Mesh(71,1,0,0,0,vertices.array())));
        long[] initial = backend.renderTargetStats();
        for (int size : new int[]{48,64}) {
            int source = glGenFramebuffers(), output = glGenFramebuffers();
            try {
                int[] a = backend.createRenderTarget(source,size,size), b = backend.createRenderTarget(output,size,size);
                attach(source,a); attach(output,b);
                for (int frame=0; frame<4; frame++) {
                    backend.renderWorldWithSky(size,size,new Object[0],true);
                    glBindFramebuffer(GL_FRAMEBUFFER,source); glViewport(0,0,size,size);
                    glDepthMask(true); glClearColor(0,0,1,1); glClearDepth(.25); glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
                    require(backend.nativeComposite(source,size,size,0,new Object[0]),"native target rejected");
                    require(color(size/2,size/2)==0x0000ff,"composite ignored GL depth");
                    glClearDepth(.75); glClear(GL_DEPTH_BUFFER_BIT);
                    backend.nativeComposite(source,size,size,0,new Object[0]);
                    require(color(size/2,size/2)==0xff0000,"native composite did not update shared color");
                    float[] depth={0}; glReadPixels(size/2,size/2,1,1,GL_DEPTH_COMPONENT,GL_FLOAT,depth);
                    require(Math.abs(depth[0]-.5f)<.00001f,"native depth is not visible to GL");
                    // An unsupported GL draw/clear in between native passes must be visible without copying attachments.
                    glEnable(GL_SCISSOR_TEST); glScissor(0,0,size/4,size/4); glClearColor(0,1,0,1); glClear(GL_COLOR_BUFFER_BIT); glDisable(GL_SCISSOR_TEST);
                    backend.nativeComposite(source,size,size,2,new Object[0]);
                    String vertex="#version 450\nlayout(location=0) out vec2 uv; void main(){vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);uv=p;gl_Position=vec4(p*2-1,0,1);}";
                    String fragment="#version 450\nlayout(set=0,binding=0) uniform sampler2D a; layout(set=0,binding=1) uniform sampler2D d; layout(set=0,binding=2) uniform sampler2D b; layout(location=0) in vec2 uv; layout(location=0) out vec4 c; void main(){c=mix(texture(a,uv),texture(b,uv),0.5);c.a=texture(d,uv).r;}";
                    Object[] packet={"shared-smoke",vertex,fragment,new int[]{a[0],size,size,0,0,a[1],size,size,1,0,a[0],size,size,0,0},new float[256],new int[]{output,size,size}};
                    require(backend.nativePost(packet),"native post rejected shared samplers");
                    glBindFramebuffer(GL_FRAMEBUFFER,output);
                    require(color(size/2,size/2)==0xff0000,"post lost center color");
                    require(color(1,1)==0x00ff00,"post flipped GL image or lost GL fallback");
                    require(color(size-2,size-2)==0x0000ff,"post lost background");
                    require(Math.abs((pixel(size/2,size/2)>>>24)-128)<=1,"post did not sample shared depth");
                    String blit="#version 450\nlayout(set=0,binding=0) uniform sampler2D a; layout(location=0) in vec2 uv; layout(location=0) out vec4 c; void main(){c=texture(a,uv);}";
                    Object[] blended={"blended-blit",vertex,blit,new int[]{b[0],size,size,0,0},new float[256],new int[]{source,size,size},new int[]{770,771,770,771,32774},new float[]{0,0,1,1}};
                    require(backend.nativePost(blended),"alpha blit rejected");
                    glBindFramebuffer(GL_FRAMEBUFFER,source);int mixed=pixel(size/2,size/2);
                    require(Math.abs((mixed>>>16&255)-128)<=1&&Math.abs((mixed&255)-127)<=1&&Math.abs((mixed>>>24)-191)<=1,"post alpha blend/clear differs from GL");
                    blended[6]=new int[]{1,0,1,0,32774};
                    require(backend.nativePost(blended),"replacement blit rejected");
                    require(color(size/2,size/2)==0xff0000,"pipeline cache reused a different blend state");
                    blended[5]=new int[]{output,size,size};
                    require(!backend.nativePost(blended),"post accepted feedback into its own sampled target");
                    ((int[])blended[3])[0]=0;
                    require(!backend.nativePost(blended),"post accepted an unknown sampler");
                    glBindFramebuffer(GL_FRAMEBUFFER,output);
                    packet[2]="#version 450\ninvalid shader";
                    require(!backend.nativePost(packet),"unsupported shader did not fall back");
                    require(color(size/2,size/2)==0xff0000,"rejected post changed output");
                    backend.finishWorldFrame();
                }
            } finally {
                backend.destroyRenderTarget(source);backend.destroyRenderTarget(output);
                glBindFramebuffer(GL_FRAMEBUFFER,0);glDeleteFramebuffers(source);glDeleteFramebuffers(output);
            }
        }
        long[] stats=backend.renderTargetStats();
        require(stats[0]-initial[0]==4&&stats[1]-initial[1]==4&&stats[2]==initial[2],"shared target lifetime leak");
        require(stats[3]-initial[3]==24&&stats[4]==initial[4]&&stats[5]-initial[5]==24,"shared target unexpectedly used copies");
        require(glGetError()==GL_NO_ERROR,"GL error in shared targets");
        backend.closeWorld();
        System.out.println("SHARED_TARGETS_SMOKE=OK (GL/Vulkan depth, fallback order, duplicate samplers, alpha blit and clear pixels, pipeline blend keys, both slots, resize, shader/feedback fallback, no composite copies)");
    }
    private static void attach(int framebuffer,int[] textures) {
        glBindFramebuffer(GL_FRAMEBUFFER,framebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,textures[0],0);
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,textures[1],0);
        require(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE,"shared framebuffer incomplete");
    }
    private static int color(int x,int y) {return pixel(x,y)&0xffffff;}
    private static int pixel(int x,int y) {try(var stack=org.lwjgl.system.MemoryStack.stackPush()){var rgba=stack.malloc(4);glReadPixels(x,y,1,1,GL_RGBA,GL_UNSIGNED_BYTE,rgba);return Byte.toUnsignedInt(rgba.get(3))<<24|Byte.toUnsignedInt(rgba.get(0))<<16|Byte.toUnsignedInt(rgba.get(1))<<8|Byte.toUnsignedInt(rgba.get(2));}}
    private static void require(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
}
