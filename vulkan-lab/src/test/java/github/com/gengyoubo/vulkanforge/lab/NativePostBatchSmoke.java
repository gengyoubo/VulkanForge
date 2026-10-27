package github.com.gengyoubo.vulkanforge.lab;

import dev.vulkanforge.bench.PipelineBenchmark;
import dev.vulkanforge.bridge.WorldProtocol;
import github.com.gengyoubo.vulkanforge.interop.GlInterop;
import java.nio.*;
import java.nio.file.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;

public final class NativePostBatchSmoke {
    public static void main(String[] ignored) throws Exception {
        org.lwjgl.system.Configuration.STACK_SIZE.set(4096);if(!glfwInit())throw new AssertionError("GLFW");glfwDefaultWindowHints();glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);long hidden=glfwCreateWindow(32,32,"Hidden post batch smoke",0,0);glfwMakeContextCurrent(hidden);org.lwjgl.opengl.GL.createCapabilities();
        Files.createDirectories(Path.of("build/reports/post-batch-smoke"));
        try(MainWindowBackend backend=new MainWindowBackend("build/reports/post-batch-smoke/report.txt",GlInterop.deviceUUID(),GlInterop::importImage,org.lwjgl.opengl.GL11::glFinish)) {
            var send=backend.worldCommands(GlInterop::importTerrain);java.util.function.Consumer<WorldProtocol.Command> command=c->send.accept(WorldProtocol.pack(c));command.accept(new WorldProtocol.Reset(1));command.accept(new WorldProtocol.Atlas(1,1,1,new byte[]{-1,-1,-1,-1}));byte[] light=new byte[1024];java.util.Arrays.fill(light,(byte)-1);command.accept(new WorldProtocol.TexturePatch(1,WorldProtocol.LIGHT_MAP,0,0,16,16,light));command.accept(new WorldProtocol.Camera(1,0,0,0,new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1},0,0,0));
            ByteBuffer mesh=ByteBuffer.allocate(6*28).order(ByteOrder.LITTLE_ENDIAN);for(int index:new int[]{0,1,2,2,3,0}){float[][] corners={{-.5f,-.5f},{.5f,-.5f},{.5f,.5f},{-.5f,.5f}};mesh.putFloat(corners[index][0]).putFloat(corners[index][1]).putFloat(.2f).putInt(-1).putFloat(.5f).putFloat(.5f).putShort((short)240).putShort((short)240);}command.accept(new WorldProtocol.Mesh(1,0,0,0,0,mesh.array()));
            for(int size:new int[]{32,48}) {
                int a=glGenFramebuffers(),b=glGenFramebuffers();int[] ta=attach(backend,a,size),tb=attach(backend,b,size);
                for(int frame=0;frame<4;frame++) {
                    backend.renderWorldWithSky(size,size,new Object[0],true);clear(a,.5f,.25f,.75f);clear(b,0,0,0);
                    long[] before=backend.interopStats();backend.beginNativePostChain();backend.beginNativePostChain();
                    require(backend.nativePost(packet(ta[0],b,size)),"first post");backend.endNativePostChain();
                    require(backend.nativePost(packet(tb[0],a,size)),"second post");require(backend.interopStats()[4]==before[4],"batch submitted before scope ended");
                    if(frame==1){ // A GL-only pass must observe the two prior VK results.
                        Object[] unsupported=packet(ta[0],b,size);((int[])unsupported[3])[0]=-123;require(!backend.nativePost(unsupported),"unknown source accepted");
                        check(a,.5f*.875f*.875f+.03125f*1.875f);clear(a,.125f,.125f,.125f);
                    }
                    backend.endNativePostChain();
                    if(frame!=1){check(a,.5f*.875f*.875f+.03125f*1.875f);long[] after=backend.interopStats();require(after[4]-before[4]==1&&after[3]-before[3]==2,"two posts did not share one submit");}
                    // Compile/record rejection must leave both GL leases usable and the scope balanced.
                    backend.beginNativePostChain();require(backend.nativePost(packet(ta[0],b,size)),"valid post before shader failure");Object[] bad=packet(tb[0],a,size);bad[2]="#version 450\ninvalid shader";require(!backend.nativePost(bad),"invalid shader accepted");backend.endNativePostChain();check(b,(frame==1?.125f:.5f*.875f*.875f+.03125f*1.875f)*.875f+.03125f);clear(b,.5f,.5f,.5f);check(b,.5f);
                    backend.finishWorldFrame();
                }
                intervalChecks(backend,a,b,ta,tb,size);
                backend.destroyRenderTarget(a);backend.destroyRenderTarget(b);glDeleteFramebuffers(a);glDeleteFramebuffers(b);
            }
            require(glGetError()==GL_NO_ERROR,"GL error");System.out.println("NATIVE_POST_BATCH_SMOKE=OK (post and composite intervals, exact RGBA/depth, both slots, nested scopes, GL fallback flush, shader rejection, resize, destruction)");
        }finally{glfwDestroyWindow(hidden);glfwTerminate();}
    }
    private static void intervalChecks(MainWindowBackend backend,int a,int b,int[] ta,int[] tb,int size) {
        byte[] expected=null;
        for(int frame=0;frame<5;frame++) {
            backend.renderWorldWithSky(size,size,new Object[0],true);clear(a,.5f,.25f,.75f);clear(b,0,0,0);
            glBindFramebuffer(GL_FRAMEBUFFER,a);glDepthMask(true);glClearDepth(1);glClear(GL_DEPTH_BUFFER_BIT);
            boolean merged=frame>0;
            long[] before=backend.interopStats();
            if(merged){backend.beginNativeInterval();backend.beginNativeInterval();}
            require(backend.nativeComposite(a,size,size,0,new Object[0]),"opaque interval composite");
            require(backend.nativeComposite(a,size,size,1,new Object[0]),"transparent interval composite");
            backend.beginNativePostChain();
            require(backend.nativePost(packet(ta[0],b,size)),"interval first post");
            require(backend.nativePost(packet(tb[0],a,size)),"interval second post");
            require(backend.nativePost(packet(ta[0],b,size)),"interval third post");backend.endNativePostChain();
            if(merged){
                require(backend.interopStats()[4]==before[4],"native interval submitted early");
                backend.endNativeInterval();require(backend.interopStats()[4]==before[4],"nested interval submitted early");backend.endNativeInterval();
                long[] after=backend.interopStats();
                require(after[4]-before[4]==1&&after[5]-before[5]==4&&after[8]-before[8]==8,"native interval handoff/submission counts");
                require(after[7]-before[7]==2,"missing composite work");
            }
            byte[] actual=read(b,size);if(expected==null)expected=actual;else require(java.util.Arrays.equals(expected,actual),"interval changed RGBA/depth or reused stale slot");
            backend.finishWorldFrame();
        }
        backend.renderWorldWithSky(size,size,new Object[0],true);clear(a,.5f,.25f,.75f);
        backend.beginNativeInterval();require(backend.nativeComposite(a,size,size,0,new Object[0]),"fallback composite");
        Object[] bad=packet(ta[0],b,size);((int[])bad[3])[0]=-123;
        require(!backend.nativePost(bad),"unsupported interval post accepted"); // flushes before returning to GL
        read(a,size);clear(a,.125f,.125f,.125f);
        require(backend.nativePost(packet(ta[0],b,size)),"post after GL intervention");backend.endNativeInterval();check(b,.125f*.875f+.03125f);
        backend.finishWorldFrame();
        boolean rejected=false;try{backend.endNativeInterval();}catch(IllegalStateException expectedError){rejected=true;}require(rejected,"unbalanced interval accepted");
        // A caller's exceptional exit may miss endNativeInterval; the frame boundary must still publish its work.
        backend.renderWorldWithSky(size,size,new Object[0],true);clear(a,.25f,.25f,.25f);
        backend.beginNativeInterval();require(backend.nativePost(packet(ta[0],b,size)),"post before frame exit");backend.finishWorldFrame();check(b,.25f*.875f+.03125f);
        // Destruction is a GL/lifetime boundary even when a native scope is still open.
        backend.renderWorldWithSky(size,size,new Object[0],true);clear(a,.375f,.375f,.375f);
        backend.beginNativeInterval();require(backend.nativePost(packet(ta[0],b,size)),"post before target destruction");
        backend.destroyRenderTarget(a);backend.endNativeInterval();check(b,.375f*.875f+.03125f);
        attach(backend,a,size);backend.finishWorldFrame();
    }
    private static byte[] read(int fbo,int size) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER,fbo);
        try(var stack=org.lwjgl.system.MemoryStack.stackPush()){
            ByteBuffer rgba=stack.malloc(size*size*4),depth=stack.malloc(size*size*4);
            glReadPixels(0,0,size,size,GL_RGBA,GL_UNSIGNED_BYTE,rgba);glReadPixels(0,0,size,size,GL_DEPTH_COMPONENT,GL_FLOAT,depth);
            byte[] result=new byte[size*size*8];rgba.get(result,0,size*size*4);depth.get(result,size*size*4,size*size*4);return result;
        }
    }
    private static Object[] packet(int texture,int output,int size){return new Object[]{"post-smoke",PipelineBenchmark.VERTEX_VK,PipelineBenchmark.FRAGMENT_VK,new int[]{texture,size,size,0,0},new float[256],new int[]{output,size,size},new int[]{1,0,1,0,GL_FUNC_ADD},new float[]{0,0,0,0}};}
    private static int[] attach(MainWindowBackend backend,int fbo,int size){int[] textures=backend.createRenderTarget(fbo,size,size);glBindFramebuffer(GL_FRAMEBUFFER,fbo);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,textures[0],0);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,textures[1],0);require(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE,"FBO");return textures;}
    private static void clear(int fbo,float r,float g,float b){glBindFramebuffer(GL_FRAMEBUFFER,fbo);glDisable(GL_SCISSOR_TEST);glDisable(GL_DITHER);glDisable(GL_FRAMEBUFFER_SRGB);glColorMask(true,true,true,true);glClearColor(r,g,b,1);glClear(GL_COLOR_BUFFER_BIT);}
    private static void check(int fbo,float r){glBindFramebuffer(GL_READ_FRAMEBUFFER,fbo);try(var stack=org.lwjgl.system.MemoryStack.stackPush()){ByteBuffer color=stack.malloc(4);glReadPixels(0,0,1,1,GL_RGBA,GL_UNSIGNED_BYTE,color);require(Math.abs((color.get(0)&255)-Math.round(r*255))<=2,"stale/wrong post output: "+(color.get(0)&255));}}
    private static void require(boolean success,String message){if(!success)throw new AssertionError(message);}
}
