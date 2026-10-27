package github.com.gengyoubo.vulkanforge.lab;

import dev.vulkanforge.bench.*;
import github.com.gengyoubo.vulkanforge.interop.GlInterop;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;

/** Same private Vulkan world/post draws; only final color is shared. GL consumer is optional and explicit. */
public final class FinalColorBenchmark implements AutoCloseable {
    private final NativeWorldRenderer renderer;private final FinalColorBoundary boundary;private final NativeFramePlan plan;
    private final int width,height,program,vao;private final boolean consume;private final boolean minimal=Boolean.getBoolean("bench.minimalHandoff");
    private final int[] fbos=new int[2],textures=new int[2];private final long[] fences=new long[2];private final boolean[] pending=new boolean[2];private final int[][] queries=new int[2][3];
    private long serial;private final long[] serials=new long[2];
    private record Sample(long serial,double glMs,double consumerMs,NativeWorldRenderer.Timing vk,double vkGateMs,double glGateMs) { }
    public static void main(String[] ignored) throws Exception {
        Locale.setDefault(Locale.ROOT);org.lwjgl.system.Configuration.STACK_SIZE.set(4096);
        int width=Integer.getInteger("bench.width",1920),height=Integer.getInteger("bench.height",1080);Path out=Path.of(System.getProperty("bench.output","build/reports/final-color"));Files.createDirectories(out);
        if(!glfwInit())throw new AssertionError("GLFW");glfwDefaultWindowHints();glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,6);glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        long hidden=glfwCreateWindow(32,32,"Hidden final-color experiment",0,0);if(hidden==0)throw new AssertionError("Hidden context");glfwMakeContextCurrent(hidden);org.lwjgl.opengl.GL.createCapabilities();
        var probe=FinalColorBoundary.Probe.valueOf(System.getProperty("bench.probe","NONE"));boolean consume=!Boolean.getBoolean("bench.noConsumer");
        List<NativeFramePlan.PostDraw> posts=new ArrayList<>();for(int i=0;i<3;i++)posts.add(new NativeFramePlan.PostDraw("canonical-color-transform",PipelineBenchmark.VERTEX_VK,PipelineBenchmark.FRAGMENT_VK,new float[256],new int[]{1,0,1,0,GL_FUNC_ADD},new float[4]));
        try(VulkanContext context=new VulkanContext(VulkanContext.requestedApiVersion(),"",true)) {
            context.initializeInterop(GlInterop.deviceUUID(),false);
            FinalColorBoundary boundary=new FinalColorBoundary(context,width,height,probe);
            try(NativeWorldRenderer renderer=new NativeWorldRenderer(context,width,height,System.out::println,boundary);
                FinalColorBenchmark bench=new FinalColorBenchmark(renderer,boundary,new NativeFramePlan(posts,List.of()),width,height,consume)) {
                if(!bench.minimal)Workload.load(Path.of(System.getProperty("bench.scene","build/reports/offscreen-performance/matched-scene.bin")),renderer::apply);
                bench.run(out,Integer.getInteger("bench.warmup",3000),Double.parseDouble(System.getProperty("bench.seconds","8")),probe);
            }
        } finally {glfwDestroyWindow(hidden);glfwTerminate();}
    }
    private FinalColorBenchmark(NativeWorldRenderer renderer,FinalColorBoundary boundary,NativeFramePlan plan,int width,int height,boolean consume) {
        this.renderer=renderer;this.boundary=boundary;this.plan=plan;this.width=width;this.height=height;this.consume=consume;if(minimal&&consume)throw new IllegalArgumentException("Minimal handoff must not consume or draw");
        for(int s=0;s<2;s++) {
            fbos[s]=glGenFramebuffers();glBindFramebuffer(GL_FRAMEBUFFER,fbos[s]);
            if(consume){textures[s]=glGenTextures();glBindTexture(GL_TEXTURE_2D,textures[s]);glTexStorage2D(GL_TEXTURE_2D,1,GL_RGBA8,width,height);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,textures[s],0);}
            else glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,boundary.texture(s),0);
            if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new AssertionError("Final-color FBO");glGenQueries(queries[s]);
        }
        vao=glGenVertexArrays();program=program();
    }
    private void frame() {
        int slot=renderer.nextSlot();if(pending[slot])throw new IllegalStateException("Consumer slot was not collected");
        glQueryCounter(queries[slot][0],GL_TIMESTAMP);if(minimal)renderer.minimalFrame();else renderer.frame(plan);glQueryCounter(queries[slot][1],GL_TIMESTAMP);
        if(consume) {
            glBindFramebuffer(GL_FRAMEBUFFER,fbos[slot]);glViewport(0,0,width,height);glDisable(GL_SCISSOR_TEST);glDisable(GL_DEPTH_TEST);glDisable(GL_BLEND);glDisable(GL_CULL_FACE);glDisable(GL_DITHER);glDisable(GL_FRAMEBUFFER_SRGB);glColorMask(true,true,true,true);
            glUseProgram(program);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,boundary.texture(slot));glBindSampler(0,0);glBindVertexArray(vao);glDrawArrays(GL_TRIANGLES,0,3);
        }
        glQueryCounter(queries[slot][2],GL_TIMESTAMP);fences[slot]=glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE,0);glFlush();pending[slot]=true;serials[slot]=++serial;
    }
    private Sample waitSlot(int slot) {
        if(!pending[slot])return null;
        int status;do{status=glClientWaitSync(fences[slot],GL_SYNC_FLUSH_COMMANDS_BIT,1_000_000_000L);if(status==GL_WAIT_FAILED)throw new AssertionError("Final GL fence");}while(status==GL_TIMEOUT_EXPIRED);
        glDeleteSync(fences[slot]);fences[slot]=0;pending[slot]=false;var vk=renderer.waitSlot(slot);if(vk==null||vk.vertices()!=(minimal?0:699024))throw new AssertionError("Final-color hardware vertex workload");
        double[] gates=boundary.collect(slot,renderer,vk.entryTicks());
        return new Sample(serials[slot],(glGetQueryObjectui64(queries[slot][2],GL_QUERY_RESULT)-glGetQueryObjectui64(queries[slot][0],GL_QUERY_RESULT))/1e6,(glGetQueryObjectui64(queries[slot][2],GL_QUERY_RESULT)-glGetQueryObjectui64(queries[slot][1],GL_QUERY_RESULT))/1e6,vk,gates[0],gates[1]);
    }
    private void drain(){for(int s=0;s<2;s++)waitSlot(s);renderer.drain();}
    private void run(Path out,int warmup,double seconds,FinalColorBoundary.Probe probe) throws Exception {
        if(minimal)capture(out,"initial",0);
        for(int i=0;i<warmup;i++){waitSlot(renderer.nextSlot());frame();}drain();
        if(!minimal){renderer.frame(NativeFramePlan.terrain());int sceneSlot=(int)((renderer.stats()[0]-1)%2);renderer.drain();var scene=renderer.pixels(sceneSlot);Files.write(out.resolve("scene.rgba"),scene.color());ByteBuffer z=ByteBuffer.allocate(scene.depth().length*4).order(ByteOrder.LITTLE_ENDIAN);for(float d:scene.depth())z.putFloat(d);Files.write(out.resolve("scene-depth.f32"),z.array());}
        frame();int finalSlot=(int)((renderer.stats()[0]-1)%2);drain();capture(out,"final",finalSlot);if(!minimal)BenchmarkDrawAudit.writeNative(renderer,plan,out.resolve("workload.txt"));else Files.writeString(out.resolve("workload.txt"),"MINIMAL_HANDOFF WORLD_DRAWS=0 POST_DRAWS=0 VERTICES=0\n");
        long[] cpu0=renderer.cpuStats(),gl0=GlInterop.groupedCpuStats(),control0=boundary.cpuStats(),count0=renderer.stats();long groups0=SharedPresentImage.groupCalls,refs0=SharedPresentImage.groupImageReferences;
        List<Sample> samples=new ArrayList<>();long[] cpu=new long[262144];long frames=0,startEpoch=System.currentTimeMillis(),start=System.nanoTime();
        do {
            long begin=System.nanoTime();Sample old=waitSlot(renderer.nextSlot());if(old!=null)samples.add(old);frame();if(frames>=cpu.length)cpu=Arrays.copyOf(cpu,cpu.length*2);cpu[(int)frames++]=System.nanoTime()-begin;
        }while(frames<128||System.nanoTime()-start<seconds*1e9);
        long drain=System.nanoTime();for(int s=0;s<2;s++){Sample old=waitSlot(s);if(old!=null)samples.add(old);}long end=System.nanoTime(),endEpoch=System.currentTimeMillis();if(samples.size()!=frames)throw new AssertionError("Final-color frame accounting");
        long[] sorted=Arrays.copyOf(cpu,(int)frames);Arrays.sort(sorted);StringBuilder raw=new StringBuilder("frame,cpu_ms\n");for(int i=0;i<frames;i++)raw.append(i).append(',').append(cpu[i]/1e6).append('\n');Files.writeString(out.resolve("cpu-frames.csv"),raw);
        double[] total=new double[11];StringBuilder timeline=new StringBuilder("frame,vk_body_ms,vk_acquire_ms,vk_cull_ms,vk_opaque_ms,vk_translucent_ms,vk_post_ms,vk_release_ms,gl_frame_span_ms,gl_consumer_ms,vk_gate_span_ms,gl_gate_span_ms\n");
        samples.sort(Comparator.comparingLong(Sample::serial));for(var s:samples){double[] values={s.vk().gpuMs(),s.vk().acquireMs(),s.vk().cullMs(),s.vk().opaqueMs(),s.vk().translucentMs(),s.vk().postMs(),s.vk().releaseMs(),s.glMs(),s.consumerMs(),s.vkGateMs(),s.glGateMs()};timeline.append(s.serial());for(int i=0;i<values.length;i++){total[i]+=values[i];timeline.append(',').append(values[i]);}timeline.append('\n');}Files.writeString(out.resolve("gpu-timeline.csv"),timeline);
        String[] names={"VK_BODY_MS","VK_ACQUIRE_MS","VK_CULL_MS","VK_OPAQUE_MS","VK_TRANSLUCENT_MS","VK_POST_MS","VK_RELEASE_MS","GL_FRAME_SPAN_MS","GL_CONSUMER_MS","VK_GATE_SPAN_MS","GL_GATE_SPAN_MS"};StringBuilder profile=new StringBuilder();for(int i=0;i<names.length;i++)profile.append(names[i]).append('=').append(total[i]/frames).append('\n');
        long[] cp=renderer.cpuStats(),gl=GlInterop.groupedCpuStats();String[] cpuNames={"CPU_PREPARE_MS","CPU_RECORD_MS","CPU_QUEUE_SUBMIT_MS","CPU_VK_FENCE_MS","CPU_QUERY_MS","CPU_BOUNDARY_MS"};for(int i=0;i<cp.length;i++)profile.append(cpuNames[i]).append('=').append((cp[i]-cpu0[i])/1e6/frames).append('\n');
        for(int i=0;i<gl.length;i++)profile.append("GL_CPU_COUNTER_").append(i).append('=').append((double)(gl[i]-gl0[i])/frames).append('\n');profile.append("DIAGNOSTIC_CONTROL_CPU_MS=").append((boundary.cpuStats()[0]-control0[0])/1e6/frames).append('\n');Files.writeString(out.resolve("phase-profile.txt"),profile);
        String label=System.getProperty("bench.label",consume?"FINAL_COLOR_GL":"FINAL_COLOR_ONLY");String result=String.format(Locale.ROOT,"PIPELINE_RESULT API=%s frames=%d seconds=%.6f FPS=%.3f GPU_MS=%.6f CPU_P50_MS=%.6f CPU_P99_MS=%.6f CPU_MAX_MS=%.6f DRAIN_MS=%.6f POST_PASSES=%d START_MS=%d END_MS=%d",label,frames,(end-start)/1e9,frames/((end-start)/1e9),total[7]/frames,sorted[(sorted.length-1)/2]/1e6,sorted[(sorted.length-1)*99/100]/1e6,sorted[sorted.length-1]/1e6,(end-drain)/1e6,minimal?0:3,startEpoch,endEpoch);Files.writeString(out.resolve("pipeline-result.txt"),result+"\n");System.out.println(result);
        long[] counts=renderer.stats();Files.writeString(out.resolve("pipeline-audit.txt"),"MODE=GL_VK_EXPERIMENT\nPOST_PASSES_PER_FRAME="+(minimal?0:3)+"\nDRAWN_VERTICES="+(minimal?0:699024)+"\nMINIMAL_HANDOFF="+minimal+"\nEARLY_RELEASE="+Boolean.getBoolean("bench.releaseEarly")+"\nPROBE_BEFORE_EARLY_RELEASE="+Boolean.getBoolean("bench.probeBeforeEarlyRelease")+"\nSHARED_IMAGES=2\nEXTERNAL_SEMAPHORES=4\nSHARED_DEPTH_IMAGES=0\nDEPTH_COPIES=0\nINTERMEDIATE_GL_DRAWS=0\nGL_FALLBACK_DRAWS=0\nGL_CONSUMER_DRAWS_PER_FRAME="+(consume?1:0)+"\nFINAL_DEPTH_EXPORTED=false\nFINAL_COLOR_EXPORTS_PER_FRAME=1\nGL_GROUP_CALLS_PER_FRAME="+((double)(SharedPresentImage.groupCalls-groups0)/frames)+"\nGL_IMAGE_REFERENCES_PER_FRAME="+((double)(SharedPresentImage.groupImageReferences-refs0)/frames)+"\nVK_FRAME_SUBMITS_PER_FRAME="+((double)(counts[0]-count0[0])/frames)+"\nVK_PROBE_SUBMITS_PER_FRAME="+((double)(boundary.cpuStats()[1]-control0[1])/frames)+"\nPROBE="+probe+"\nPRESENT_CALLS=0\n");
        frame();finalSlot=(int)((renderer.stats()[0]-1)%2);drain();capture(out,"after-final",finalSlot);if(glGetError()!=GL_NO_ERROR)throw new AssertionError("Final-color GL error");
    }
    private void capture(Path out,String name,int slot) throws Exception {
        glBindFramebuffer(GL_READ_FRAMEBUFFER,fbos[slot]);ByteBuffer pixels=org.lwjgl.system.MemoryUtil.memAlloc(width*height*4);
        try{glReadPixels(0,0,width,height,GL_RGBA,GL_UNSIGNED_BYTE,pixels);byte[] bytes=new byte[pixels.remaining()];if(consume)pixels.get(bytes);else for(int y=0;y<height;y++)pixels.get(y*width*4,bytes,(height-y-1)*width*4,width*4);Files.write(out.resolve(name+".rgba"),bytes);}finally{org.lwjgl.system.MemoryUtil.memFree(pixels);}
    }
    private static int program(){int p=glCreateProgram();String vert="#version 430\nout vec2 uv;void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);uv=vec2(p.x,1-p.y);gl_Position=vec4(p*2-1,0,1);}";String frag="#version 430\nin vec2 uv;out vec4 color;uniform sampler2D sourceColor;void main(){color=texture(sourceColor,uv);}";for(int type:new int[]{GL_VERTEX_SHADER,GL_FRAGMENT_SHADER}){int s=glCreateShader(type);glShaderSource(s,type==GL_VERTEX_SHADER?vert:frag);glCompileShader(s);if(glGetShaderi(s,GL_COMPILE_STATUS)==0)throw new AssertionError(glGetShaderInfoLog(s));glAttachShader(p,s);glDeleteShader(s);}glLinkProgram(p);if(glGetProgrami(p,GL_LINK_STATUS)==0)throw new AssertionError(glGetProgramInfoLog(p));return p;}
    public void close(){drain();for(int s=0;s<2;s++){glDeleteFramebuffers(fbos[s]);if(consume)glDeleteTextures(textures[s]);glDeleteQueries(queries[s]);}glDeleteProgram(program);glDeleteVertexArrays(vao);}
}
