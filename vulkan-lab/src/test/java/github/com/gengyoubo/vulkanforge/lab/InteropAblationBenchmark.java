package github.com.gengyoubo.vulkanforge.lab;

import github.com.gengyoubo.vulkanforge.interop.GlInterop;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static github.com.gengyoubo.vulkanforge.lab.UnifiedWorkload.*;

/** A/B/C/D experiments; every reported frame is completed and workload-checked. */
public final class InteropAblationBenchmark {
    private static final String[] FIELDS={"GPU_TIMELINE_MS","VK_BODY_MS","VK_DEPTH_MS","GL_DEPTH_MS","GPU_ENTITY_MS","GPU_POST_MS","GPU_FALLBACK_MS","GL_SIGNAL_CPU_MS","VK_RECORD_CPU_MS","VK_SUBMIT_CPU_MS","GL_WAIT_CPU_MS","FENCE_CPU_MS","QUERY_CPU_MS"};
    private interface Backend extends AutoCloseable {
        void frame(int slot);double[] waitSlot(int slot);String audit(Path folder) throws Exception;
        int handoffs();int copies();default void close(){ }
        default boolean hostSegments(){return false;}
    }
    public static void main(String[] ignored) throws Exception {
        Locale.setDefault(Locale.ROOT);Configuration.STACK_SIZE.set(4096);
        String api=System.getProperty("bench.api","VK1.4");boolean vk=api.startsWith("VK");
        int width=Integer.getInteger("bench.width",1920),height=Integer.getInteger("bench.height",1080),warmup=Integer.getInteger("bench.warmup",1000);
        int minimum=Integer.getInteger("bench.frames",128);double seconds=Double.parseDouble(System.getProperty("bench.seconds","3"));
        Path out=Path.of(System.getProperty("bench.output","build/reports/interop-ablation/"+api));Files.createDirectories(out);
        if(!glfwInit())throw new IllegalStateException("GLFW init");glfwDefaultWindowHints();glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,vk?3:Integer.parseInt(api.substring(4)));glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        long window=glfwCreateWindow(32,32,"Invisible interop experiments",0,0);if(window==0)throw new IllegalStateException("Offscreen GL context unavailable");
        VulkanContext context=null;Throwable failure=null;
        try {
            glfwMakeContextCurrent(window);GL.createCapabilities();byte[] uuid=GlInterop.deviceUUID();
            Files.writeString(out.resolve("environment.txt"),"API="+api+"\nGL_VERSION="+glGetString(GL_VERSION)+"\nGPU="+glGetString(GL_RENDERER)+"\nDEVICE_UUID="+HexFormat.of().formatHex(uuid)+"\nWIDTH="+width+"\nHEIGHT="+height+"\nSURFACE=false\nPRESENT_CALLS=0\nSLOTS=2\nCOLOR=RGBA8\nDEPTH=D32F\nCAPTURE_SOURCE=SYNTHETIC_CANONICAL_TAPE\n");
            if(vk){System.setProperty("vulkanforge.vulkanVersion",api.substring(2));context=new VulkanContext(VulkanContext.requestedApiVersion(),"",true);context.initializeInterop(uuid,false);}
            UnifiedWorkload scene=new UnifiedWorkload(Integer.getInteger("bench.draws",1024));scene.verifyAuditRejectsBadWork();
            // Keep B's two cases adjacent, randomize their order as well as all other blocks.
            Random random=new Random(Long.getLong("bench.seed",20261028L));List<List<String>> blocks=new ArrayList<>();
            for(String name:vk?InteropMicroBenchmark.VK_CASES:InteropMicroBenchmark.GL_CASES)blocks.add(new ArrayList<>(List.of(name)));
            List<String> b=new ArrayList<>(List.of("B_FULL_ON","B_FULL_OFF"));Collections.shuffle(b,random);blocks.add(b);
            if(vk)blocks.add(new ArrayList<>(List.of("D_ONE_WAY_GL_VK","D_ONE_WAY_VK_GL")));
            Collections.shuffle(blocks,random);String selected=System.getProperty("bench.cases","ALL");Set<String> filter=new HashSet<>(Arrays.asList(selected.split(",")));
            for(List<String> block:blocks)for(String name:block) {
                if(!selected.equals("ALL")&&!filter.contains(name))continue;
                Path folder=out.resolve(name);Files.createDirectories(folder);System.out.println("ABLATION_BEGIN "+name);System.out.flush();
                if(name.startsWith("D_ONE_WAY")) {oneWay(context,name,width,height,warmup,minimum,seconds,folder,api);continue;}
                Backend backend=name.startsWith("B_")?full(context,scene,width,height,name.equals("B_FULL_ON")):micro(context,name,width,height);
                try(backend) {
                    for(int i=0;i<Math.max(SLOTS,warmup);i++){int slot=i%SLOTS;backend.waitSlot(slot);backend.frame(slot);}
                    for(int slot=0;slot<SLOTS;slot++)backend.waitSlot(slot);
                    String audit=backend.audit(folder);Files.writeString(folder.resolve("audit.txt"),audit);
                    long[] cpu=new long[262144];long frames=0;double[] sum=new double[FIELDS.length];long collected=0,maximum=0;
                    long startEpoch=System.currentTimeMillis(),start=System.nanoTime();
                    do {
                        long t=System.nanoTime();int slot=(int)(frames%SLOTS);double[] old=backend.waitSlot(slot);if(old!=null){add(sum,old);collected++;}
                        backend.frame(slot);long duration=System.nanoTime()-t;if(frames>=cpu.length)cpu=Arrays.copyOf(cpu,cpu.length*2);cpu[(int)frames]=duration;maximum=Math.max(maximum,duration);frames++;
                    }while(frames<minimum||System.nanoTime()-start<seconds*1e9);
                    long activeEnd=System.nanoTime(),drainStart=activeEnd;
                    for(int slot=0;slot<SLOTS;slot++){double[] old=backend.waitSlot(slot);if(old!=null){add(sum,old);collected++;}}
                    long end=System.nanoTime(),endEpoch=System.currentTimeMillis();if(collected!=frames)throw new AssertionError("Completed frames differ");
                    writeCpu(folder,cpu,(int)frames);long[] sorted=Arrays.copyOf(cpu,(int)frames);Arrays.sort(sorted);
                    String result=String.format(Locale.ROOT,"ABLATION_RESULT API=%s CASE=%s MODE=PIPELINED frames=%d seconds=%.6f FPS=%.3f CPU_P50_MS=%.6f CPU_P99_MS=%.6f CPU_MAX_MS=%.6f DRAIN_MS=%.6f ACTIVE_SECONDS=%.6f HANDOFFS=%d DEPTH_COPIES=%d START_MS=%d END_MS=%d",api,name,frames,(end-start)/1e9,frames/((end-start)/1e9),sorted[(sorted.length-1)/2]/1e6,sorted[(sorted.length-1)*99/100]/1e6,maximum/1e6,(end-drainStart)/1e6,(activeEnd-start)/1e9,backend.handoffs(),backend.copies(),startEpoch,endEpoch);
                    result+=" HOST_SEGMENTS_AVAILABLE="+(backend.hostSegments()?1:0);
                    for(int f=0;f<FIELDS.length;f++)result+=" "+FIELDS[f]+"="+String.format(Locale.ROOT,"%.6f",sum[f]/frames);
                    Files.writeString(folder.resolve("result.txt"),result+"\n");System.out.println(result);System.out.flush();
                    String after=backend.audit(folder);if(!audit.equals(after))throw new AssertionError("Workload changed during timing");
                    Files.writeString(folder.resolve("conformance.txt"),"PASS before/after timing; both frame slots; full shared color/depth and copy destinations or full Draw ID/IA audit\n");
                }
            }
            System.out.println("INTEROP_ABLATION=OK");
        }catch(Exception|Error error){failure=error;throw error;}
        finally{try{if(context!=null)context.close();}catch(RuntimeException|Error error){if(failure!=null)failure.addSuppressed(error);else throw error;}finally{glfwDestroyWindow(window);glfwTerminate();}}
    }
    private static Backend micro(VulkanContext context,String name,int width,int height) {
        InteropMicroBenchmark micro=new InteropMicroBenchmark(context,name,width,height);
        return new Backend(){
            public void frame(int slot){micro.frame(slot);}
            public double[] waitSlot(int slot){var m=micro.waitSlot(slot);return m==null?null:new double[]{m.timeline(),m.vkBody(),m.vkDepth(),m.glDepth(),0,0,0,m.glSignalCpu(),m.vkRecordCpu(),m.vkSubmitCpu(),m.glWaitCpu(),m.fenceCpu(),m.queryCpu()};}
            public String audit(Path folder){micro.verify();return "ZERO_DRAW_VERTICES=0\nCOLOR_PATTERN=PASS\nDEPTH_PATTERN=PASS\nVK_DEPTH_COPY_VERIFIED="+micro.vkCopy+"\nGL_DEPTH_COPY_VERIFIED="+micro.glCopy+"\nHANDOFFS="+handoffs()+"\nDEPTH_COPIES="+copies()+"\nLAYOUTS="+(micro.attachmentLayouts?"ATTACHMENT_GENERAL":"GENERAL_ONLY")+"\n";}
            public int handoffs(){return micro.rounds*2;}public int copies(){return (micro.vkCopy?1:0)+(micro.glCopy?1:0);}
            public boolean hostSegments(){return true;}
            public void close(){micro.close();}
        };
    }
    private static Backend full(VulkanContext context,UnifiedWorkload scene,int width,int height,boolean compatibility) throws Exception {
        boolean isVk=context!=null;UnifiedVk vk=isVk?new UnifiedVk(context,scene,FULL,width,height,compatibility):null;
        UnifiedGl gl=isVk?vk.gl:new UnifiedGl(scene,FULL,width,height,true,null,null,compatibility);
        return new Backend(){
            final UnifiedGl.Metrics[] last=new UnifiedGl.Metrics[SLOTS];
            public void frame(int s){if(isVk)vk.frame(s);else gl.frame(s);}
            public double[] waitSlot(int s){var m=isVk?vk.waitSlot(s):gl.waitSlot(s);if(m==null)return null;if(last[s]!=null&&(last[s].vertices()!=m.vertices()||last[s].fallbackVertices()!=m.fallbackVertices()||last[s].invocations()!=m.invocations()))throw new AssertionError("B changed hardware workload within one case");last[s]=m;return new double[]{m.timeline(),0,0,0,m.entity(),m.post(),m.fallback(),0,0,0,0,0,0};}
            public String audit(Path folder) throws Exception {
                String result=null;for(int s=0;s<SLOTS;s++) {
                    ByteBuffer commands=isVk?vk.commands(s):gl.commands(s);UnifiedWorkload.Audit a;
                    int[] omitted=compatibility?new int[0]:java.util.stream.IntStream.range(scene.core(FULL),scene.total).toArray();
                    try{a=scene.audit(FULL,commands,last[s].vertices(),last[s].invocations(),last[s].fallbackVertices(),isVk?vk.fallbackIDs(s):gl.submissions[s].fallbacks(),omitted);}finally{MemoryUtil.memFree(commands);}
                    if(a.fallback()!=(compatibility?scene.fallback:0))throw new AssertionError("B did not switch actual compatibility IDs");
                    String current="SCENE_SHA256="+scene.hash+"\nDRAW_IDS_SHA256="+a.hash()+"\nCAPTURED_DRAWS="+a.captured()+"\nSUBMITTED_DRAWS="+a.submitted()+"\nCULLED_DRAWS="+a.culled()+"\nGL_FALLBACK_DRAWS="+a.fallback()+"\nINTENTIONALLY_EXCLUDED_DRAWS="+omitted.length+"\nDUPLICATED_DRAWS="+a.duplicated()+"\nDRAWN_VERTICES="+a.vertices()+"\nFALLBACK_VERTICES="+last[s].fallbackVertices()+"\nDEPTH_COPIES="+(isVk?vk.depthCopies(s):gl.depthCopies[s])+"\nPOST_PASSES="+(isVk?vk.postPasses(s):gl.postPasses[s])+"\nHANDOFFS="+handoffs()+"\n";
                    if(result!=null&&!result.equals(current))throw new AssertionError("Full frame slots differ");result=current;
                    if(s==0){Files.writeString(folder.resolve("draw-ids.txt"),a.ids());var pixels=isVk?vk.pixels(s):new UnifiedVk.Pixels(gl.color(s),gl.depth(s));Files.write(folder.resolve("color.rgba"),pixels.color());ByteBuffer z=bytes(pixels.depth().length*4);for(float value:pixels.depth()){if(!Float.isFinite(value))throw new AssertionError("Nonfinite depth");z.putFloat(value);}Files.write(folder.resolve("depth.f32"),z.array());result+="COLOR_SHA256="+sha(pixels.color())+"\nDEPTH_SHA256="+sha(z.array())+"\n";}
                    // Compare counts between slots above, image hashes belong to slot zero only.
                    if(s==0)Files.writeString(folder.resolve("slot-zero-image-hashes.txt"),result.substring(current.length()));
                    result=current;
                }
                return result+Files.readString(folder.resolve("slot-zero-image-hashes.txt"));
            }
            public int handoffs(){return isVk?2:0;}public int copies(){return 2;}
            public void close(){if(isVk)vk.close();else gl.close();}
        };
    }
    private static void oneWay(VulkanContext context,String name,int width,int height,int warmup,int minimum,double seconds,Path folder,String api) throws Exception {
        try(InteropMicroBenchmark micro=new InteropMicroBenchmark(context,"A_HANDOFF_ATTACHMENTS",width,height)) {
            boolean toVk=name.equals("D_ONE_WAY_GL_VK");for(int i=0;i<Math.min(warmup,100);i++)micro.oneWay(toVk);
            double[] samples=new double[262144];int n=0;long startEpoch=System.currentTimeMillis(),start=System.nanoTime();double measured=0;
            do{double sample=micro.oneWay(toVk);samples[n++]=sample;measured+=sample;}while(n<minimum||System.nanoTime()-start<Math.min(seconds,2)*1e9);
            long end=System.nanoTime(),endEpoch=System.currentTimeMillis();samples=Arrays.copyOf(samples,n);StringBuilder csv=new StringBuilder("sample,completion_ms\n");for(int i=0;i<n;i++)csv.append(i).append(',').append(samples[i]).append('\n');Files.writeString(folder.resolve("latencies.csv"),csv);Arrays.sort(samples);
            String result=String.format(Locale.ROOT,"ABLATION_RESULT API=%s CASE=%s MODE=SYNCHRONIZED_DIRECTION samples=%d seconds=%.6f COMPLETION_MEAN_MS=%.6f COMPLETION_P50_MS=%.6f COMPLETION_P99_MS=%.6f COMPLETION_MAX_MS=%.6f HANDOFFS=1 RESET_HANDOFFS_OUTSIDE_SAMPLE=1 START_MS=%d END_MS=%d",api,name,n,(end-start)/1e9,measured/n,samples[(n-1)/2],samples[(n-1)*99/100],samples[n-1],startEpoch,endEpoch);
            micro.verify();Files.writeString(folder.resolve("result.txt"),result+"\n");Files.writeString(folder.resolve("conformance.txt"),"PASS shared color/depth unchanged; no drawing; direction completed before ending each CPU sample; reverse/reset outside sample\n");System.out.println(result);System.out.flush();
        }
    }
    private static void add(double[] sum,double[] values){for(int i=0;i<sum.length;i++)sum[i]+=values[i];}
    private static String sha(byte[] bytes) throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static void writeCpu(Path folder,long[] ns,int count) throws Exception {StringBuilder csv=new StringBuilder("frame,cpu_ms\n");for(int i=0;i<count;i++)csv.append(i).append(',').append(ns[i]/1e6).append('\n');Files.writeString(folder.resolve("cpu-frames.csv"),csv);}
}
