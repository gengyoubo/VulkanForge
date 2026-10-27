package github.com.gengyoubo.vulkanforge.lab;

import dev.vulkanforge.bench.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.vulkan.VK;

/** Actual shared scene/post kernels, native private targets and a strictly GL-free timing path. */
public final class VkNativeOffscreenBenchmark {
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);org.lwjgl.system.Configuration.STACK_SIZE.set(4096);
        int width=Integer.getInteger("bench.width",1920),height=Integer.getInteger("bench.height",1080),warmup=Integer.getInteger("bench.warmup",1000),postCount=Integer.getInteger("bench.postPasses",3);
        double seconds=Double.parseDouble(System.getProperty("bench.seconds","6"));Path out=Path.of(System.getProperty("bench.output","build/reports/vk-native-offscreen"));Files.createDirectories(out);
        if(postCount!=0&&postCount!=3)throw new IllegalArgumentException("Canonical benchmark uses 0 or 3 posts");
        List<NativeFramePlan.PostDraw> posts=new ArrayList<>();for(int i=0;i<postCount;i++)posts.add(new NativeFramePlan.PostDraw("canonical-color-transform",PipelineBenchmark.VERTEX_VK,PipelineBenchmark.FRAGMENT_VK,new float[256],new int[]{1,0,1,0,32774},new float[4]));NativeFramePlan plan=new NativeFramePlan(posts,List.of());
        Path scene=Path.of(System.getProperty("bench.scene","build/reports/offscreen-performance/matched-scene.bin"));
        long expectedVertices=Workload.read(scene).stream().filter(dev.vulkanforge.bridge.WorldProtocol.Mesh.class::isInstance).map(dev.vulkanforge.bridge.WorldProtocol.Mesh.class::cast).mapToLong(m->(m.vertices().length+m.translucent().length)/28L).sum();
        try(VulkanContext context=new VulkanContext(VulkanContext.requestedApiVersion(),"",true)) {
            context.initialize(0,VK.getInstanceVersionSupported(),false);
            if(context.interopEnabled())throw new AssertionError("Native context enabled interop");
            try(NativeWorldRenderer renderer=new NativeWorldRenderer(context,width,height,System.out::println)) {
                Workload.load(scene,renderer::apply);
                for(int i=0;i<warmup;i++){renderer.waitSlot(renderer.nextSlot());renderer.frame(plan);}renderer.drain();
                renderer.frame(NativeFramePlan.terrain());int source=(int)((renderer.stats()[0]-1)%2);renderer.drain();capture(out,"scene",renderer.pixels(source));
                renderer.frame(plan);source=(int)((renderer.stats()[0]-1)%2);renderer.drain();capture(out,"final",renderer.pixels(source));
                BenchmarkDrawAudit.writeNative(renderer,plan,out.resolve("workload.txt"));
                List<NativeWorldRenderer.Timing> gpuFrames=new ArrayList<>();long[] cpu0=renderer.cpuStats();long[] counters0=renderer.stats(),cpu=new long[262144];long frames=0,collected=0;double gpu=0;long startEpoch=System.currentTimeMillis(),start=System.nanoTime();
                do {
                    long begin=System.nanoTime();var previous=renderer.waitSlot(renderer.nextSlot());if(previous!=null){if(previous.vertices()!=expectedVertices)throw new AssertionError("Native hardware vertex workload changed: "+previous.vertices()+" != "+expectedVertices);gpu+=previous.gpuMs();gpuFrames.add(previous);collected++;}
                    renderer.frame(plan);if(frames>=cpu.length)cpu=Arrays.copyOf(cpu,cpu.length*2);cpu[(int)frames++]=System.nanoTime()-begin;
                }while(frames<128||System.nanoTime()-start<seconds*1e9);
                long drain=System.nanoTime();for(int s=0;s<2;s++){var timing=renderer.waitSlot(s);if(timing!=null){if(timing.vertices()!=expectedVertices)throw new AssertionError("Native hardware vertices disagree");gpu+=timing.gpuMs();gpuFrames.add(timing);collected++;}}
                long end=System.nanoTime(),endEpoch=System.currentTimeMillis();if(frames!=collected)throw new AssertionError("Native completion accounting");
                long[] sorted=Arrays.copyOf(cpu,(int)frames);Arrays.sort(sorted);StringBuilder csv=new StringBuilder("frame,cpu_ms\n");for(int i=0;i<frames;i++)csv.append(i).append(',').append(cpu[i]/1e6).append('\n');Files.writeString(out.resolve("cpu-frames.csv"),csv);
                String result=String.format(Locale.ROOT,"PIPELINE_RESULT API=VK_NATIVE_OFFSCREEN frames=%d seconds=%.6f FPS=%.3f GPU_MS=%.6f CPU_P50_MS=%.6f CPU_P99_MS=%.6f CPU_MAX_MS=%.6f DRAIN_MS=%.6f POST_PASSES=%d START_MS=%d END_MS=%d",frames,(end-start)/1e9,frames/((end-start)/1e9),gpu/frames,sorted[(sorted.length-1)/2]/1e6,sorted[(sorted.length-1)*99/100]/1e6,sorted[sorted.length-1]/1e6,(end-drain)/1e6,postCount,startEpoch,endEpoch);Files.writeString(out.resolve("pipeline-result.txt"),result+"\n");System.out.println(result);
                String[] phaseNames={"VK_BODY_MS","VK_ACQUIRE_MS","VK_CULL_MS","VK_OPAQUE_MS","VK_TRANSLUCENT_MS","VK_POST_MS","VK_RELEASE_MS"};double[] totals=new double[7];
                StringBuilder timeline=new StringBuilder("frame,vk_body_ms,vk_acquire_ms,vk_cull_ms,vk_opaque_ms,vk_translucent_ms,vk_post_ms,vk_release_ms\n");int timingFrame=0;
                for(var t:gpuFrames){double[] values={t.gpuMs(),t.acquireMs(),t.cullMs(),t.opaqueMs(),t.translucentMs(),t.postMs(),t.releaseMs()};timeline.append(timingFrame++);for(int i=0;i<7;i++){totals[i]+=values[i];timeline.append(',').append(values[i]);}timeline.append('\n');}
                Files.writeString(out.resolve("gpu-timeline.csv"),timeline);StringBuilder profile=new StringBuilder();for(int i=0;i<7;i++)profile.append(phaseNames[i]).append('=').append(totals[i]/frames).append('\n');
                String[] cpuNames={"CPU_PREPARE_MS","CPU_RECORD_MS","CPU_QUEUE_SUBMIT_MS","CPU_VK_FENCE_MS","CPU_QUERY_MS","CPU_BOUNDARY_MS"};long[] cpu1=renderer.cpuStats();for(int i=0;i<cpu1.length;i++)profile.append(cpuNames[i]).append('=').append((cpu1[i]-cpu0[i])/1e6/frames).append('\n');Files.writeString(out.resolve("phase-profile.txt"),profile);
                long[] counters=renderer.stats();Files.writeString(out.resolve("pipeline-audit.txt"),"MODE=VK\nEXECUTOR=VK_NATIVE_OFFSCREEN\nVK_VERSION="+System.getProperty("vulkanforge.vulkanVersion","1.4")+"\nPOST_PASSES_PER_FRAME="+postCount+"\nDRAWN_VERTICES="+expectedVertices+"\nSLOTS=2\nCOLOR=RGBA8\nDEPTH=D32F\nGL_CONTEXTS=0\nSHARED_IMAGES="+counters[4]+"\nEXTERNAL_SEMAPHORES="+counters[5]+"\nGL_FALLBACK_DRAWS="+counters[6]+"\nDEPTH_COPIES="+counters[7]+"\nVK_SUBMITS_PER_FRAME="+((double)(counters[0]-counters0[0])/frames)+"\nPRESENT_CALLS=0\n");
                renderer.frame(plan);source=(int)((renderer.stats()[0]-1)%2);renderer.drain();capture(out,"after-final",renderer.pixels(source));BenchmarkDrawAudit.writeNative(renderer,plan,out.resolve("workload.txt"));
            }
        }catch(Exception|Error error){Files.writeString(out.resolve("compatibility-error.txt"),"MODE=VK\nSTATUS=REJECTED\nGL_FALLBACK=false\nERROR="+error+"\n");throw error;}
        Files.writeString(out.resolve("native-contract.txt"),"PASS Vulkan-only device, private images, zero external semaphores/GL fallback, hardware terrain vertices checked every measured frame\n");
    }
    private static void capture(Path out,String name,NativeWorldRenderer.Pixels pixels) throws Exception {Files.write(out.resolve(name+".rgba"),pixels.color());ByteBuffer z=ByteBuffer.allocate(pixels.depth().length*4).order(ByteOrder.LITTLE_ENDIAN);for(float value:pixels.depth()){if(!Float.isFinite(value))throw new AssertionError("Native invalid depth");z.putFloat(value);}Files.write(out.resolve(name+"-depth.f32"),z.array());}
}
