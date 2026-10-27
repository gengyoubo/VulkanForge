package github.com.gengyoubo.vulkanforge.lab;

import dev.vulkanforge.bench.*;
import github.com.gengyoubo.vulkanforge.interop.GlInterop;
import java.nio.file.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;

/** Production native terrain composites and PostChain batch, without a window surface/presentation. */
public final class NativePipelineBenchmark {
    public static void main(String[] args) throws Exception {
        org.lwjgl.system.Configuration.STACK_SIZE.set(4096);java.util.Locale.setDefault(java.util.Locale.ROOT);
        if(!glfwInit())throw new AssertionError("GLFW init");glfwDefaultWindowHints();glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,6);glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        long hidden=glfwCreateWindow(32,32,"Hidden native pipeline",0,0);if(hidden==0)throw new AssertionError("Hidden context");glfwMakeContextCurrent(hidden);org.lwjgl.opengl.GL.createCapabilities();
        Path out=Path.of(System.getProperty("bench.output","build/native-pipeline"));Files.createDirectories(out);int width=Integer.getInteger("bench.width",1920),height=Integer.getInteger("bench.height",1080);
        try(MainWindowBackend backend=new MainWindowBackend(out.resolve("report.txt").toString(),GlInterop.deviceUUID(),GlInterop::importImage,org.lwjgl.opengl.GL11::glFinish)) {
            var commands=backend.worldCommands(GlInterop::importTerrain);Workload.load(Path.of(System.getProperty("bench.scene")),command->commands.accept(dev.vulkanforge.bridge.WorldProtocol.pack(command)));
            try(var pipeline=new PipelineBenchmark(width,height,new PipelineBenchmark.Renderer(){
                public void terrain(PipelineBenchmark.Target target,int slot){
                    glDepthMask(true);glColorMask(true,true,true,true);glDisable(GL_SCISSOR_TEST);glBindFramebuffer(GL_FRAMEBUFFER,target.framebuffer());glClearColor(.5f,.7f,1,0);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
                    if(backend.renderWorldWithSky(width,height,new Object[0],true)==0)throw new AssertionError("Native world unavailable");
                    if(Boolean.getBoolean("bench.nativeInterval"))backend.beginNativeInterval();
                    if(!backend.nativeComposite(target.framebuffer(),width,height,0,new Object[0])||!backend.nativeComposite(target.framebuffer(),width,height,1,new Object[0]))throw new AssertionError("Native composite unavailable");
                }
                public void beginPost(){backend.beginNativePostChain();}
                public boolean post(Object[] packet){if(!backend.nativePost(packet))throw new AssertionError("Native post unexpectedly fell back");return true;}
                public void endPost(){backend.endNativePostChain();}
                public void endFrame(){if(Boolean.getBoolean("bench.nativeInterval"))backend.endNativeInterval();backend.finishWorldFrame();}
                public void beforeRead(){backend.flushNativePosts();}
                public void audit(Path path) throws Exception {BenchmarkDrawAudit.write(backend,path.resolve("workload.txt"),true);}
                public long[] counters(){long[] a=backend.interopStats(),b=GlInterop.groupedCpuStats(),all=java.util.Arrays.copyOf(a,a.length+b.length);System.arraycopy(b,0,all,a.length,b.length);return all;}
                public double[] profile(){return backend.phaseProfile();}
            },dimensions->backend.createRenderTarget(dimensions[0],dimensions[1],dimensions[2]),backend::destroyRenderTarget)){
                glDisable(GL_DITHER);glDisable(GL_FRAMEBUFFER_SRGB);
                pipeline.run(out,System.getProperty("bench.label",Boolean.getBoolean("vulkanforge.noInteropOptimizations")?"VK14_BEFORE":"VK14_AFTER"),Integer.getInteger("bench.warmup",2000),Double.parseDouble(System.getProperty("bench.seconds","6")));
            }
            if(glGetError()!=GL_NO_ERROR)throw new AssertionError("Native pipeline GL error");
        }finally{glfwDestroyWindow(hidden);glfwTerminate();}
    }
}
