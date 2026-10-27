package github.com.gengyoubo.vulkanforge.lab;

import github.com.gengyoubo.vulkanforge.interop.GlInterop;
import java.awt.image.BufferedImage;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.*;
import org.lwjgl.vulkan.VK;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static github.com.gengyoubo.vulkanforge.lab.UnifiedWorkload.*;

/** Three verified, offscreen contracts. Controlled rendering kernels; synthetic capture tape, not game/Mekanism FPS. */
public final class UnifiedRenderingBenchmark {
    private interface Backend extends AutoCloseable {
        void frame(int slot);UnifiedGl.Metrics waitSlot(int slot);ByteBuffer commands(int slot);UnifiedVk.Pixels pixels(int slot);
        int[] fallbackIDs(int slot);int coreCalls(int slot);int commandSlots(int slot);
        int depthCopies(int slot);int postPasses(int slot);int handOffs(int slot);
        default void close(){ }
    }
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);Configuration.STACK_SIZE.set(4096);
        String api=System.getProperty("bench.api","VK1.4");boolean vulkan=api.startsWith("VK");String version=api.substring(2);
        int width=Integer.getInteger("bench.width",1920),height=Integer.getInteger("bench.height",1080),warmup=Integer.getInteger("bench.warmup",500);
        double seconds=Double.parseDouble(System.getProperty("bench.seconds","3"));int minimumFrames=Integer.getInteger("bench.frames",128);
        Path output=Path.of(System.getProperty("bench.output","build/reports/unified-rendering/"+api));Files.createDirectories(output);
        UnifiedWorkload scene=new UnifiedWorkload(Integer.getInteger("bench.draws",1024));
        scene.verifyAuditRejectsBadWork();
        if(!glfwInit())throw new IllegalStateException("GLFW init");glfwDefaultWindowHints();glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,vulkan?3:Integer.parseInt(version.substring(2)));glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        long hidden=glfwCreateWindow(32,32,"Hidden unified benchmark",0,0);if(hidden==0)throw new IllegalStateException("Hidden context unavailable");
        VulkanContext context=null;Throwable failure=null;
        try {
            glfwMakeContextCurrent(hidden);GL.createCapabilities();
            String renderer=glGetString(GL_RENDERER);byte[] uuid=GlInterop.deviceUUID();
            Files.writeString(output.resolve("environment.txt"),"API="+api+"\nGL_RENDERER="+renderer+"\nGL_CONTEXT="+glGetString(GL_VERSION)+"\nDEVICE_UUID="+HexFormat.of().formatHex(uuid)+"\nCAPTURE_SOURCE=SYNTHETIC_CANONICAL_TAPE\nSCENE_SHA256="+scene.hash+"\nVISIBLE=false SURFACE=false SWAPCHAIN=false PRESENT_CALLS=0\n");
            if(vulkan){System.setProperty("vulkanforge.vulkanVersion",version);context=new VulkanContext(VulkanContext.requestedApiVersion(),"",true);context.initializeInterop(uuid,false);}
            List<Integer> levels=new ArrayList<>(List.of(BASELINE,DRIVEN,FULL));Collections.shuffle(levels,new Random(Long.getLong("bench.seed",20261027L)));
            for(int level:levels) {
                String requested=System.getProperty("bench.level","ALL");if(!requested.equals("ALL")&&!requested.equals(LEVELS[level]))continue;
                Path folder=output.resolve(LEVELS[level]);Files.createDirectories(folder);
                Backend backend;
                if(vulkan){UnifiedVk vk=new UnifiedVk(context,scene,level,width,height);backend=new Backend(){public void frame(int s){vk.frame(s);}public UnifiedGl.Metrics waitSlot(int s){return vk.waitSlot(s);}public ByteBuffer commands(int s){return vk.commands(s);}public UnifiedVk.Pixels pixels(int s){return vk.pixels(s);}public int[] fallbackIDs(int s){return vk.fallbackIDs(s);}public int coreCalls(int s){return vk.coreCalls(s);}public int commandSlots(int s){return vk.commandSlots(s);}public int depthCopies(int s){return vk.depthCopies(s);}public int postPasses(int s){return vk.postPasses(s);}public int handOffs(int s){return vk.handOffs(s);}public void close(){vk.close();}};}
                else{UnifiedGl gl=new UnifiedGl(scene,level,width,height,true,null,null);backend=new Backend(){public void frame(int s){gl.frame(s);}public UnifiedGl.Metrics waitSlot(int s){return gl.waitSlot(s);}public ByteBuffer commands(int s){return gl.commands(s);}public UnifiedVk.Pixels pixels(int s){return new UnifiedVk.Pixels(gl.color(s),gl.depth(s));}public int[] fallbackIDs(int s){return gl.submissions[s].fallbacks();}public int coreCalls(int s){return gl.submissions[s].calls();}public int commandSlots(int s){return gl.submissions[s].slots();}public int depthCopies(int s){return gl.depthCopies[s];}public int postPasses(int s){return gl.postPasses[s];}public int handOffs(int s){return 0;}public void close(){gl.close();}};}
                try(backend) {
                    UnifiedGl.Metrics[] last=new UnifiedGl.Metrics[SLOTS];
                    for(int i=0;i<Math.max(warmup,SLOTS);i++){int slot=i%SLOTS;UnifiedGl.Metrics old=backend.waitSlot(slot);if(old!=null)last[slot]=old;backend.frame(slot);}
                    for(int slot=0;slot<SLOTS;slot++)last[slot]=backend.waitSlot(slot);
                    UnifiedWorkload.Audit audit=null;
                    for(int slot=0;slot<SLOTS;slot++) {
                        ByteBuffer commands=backend.commands(slot);
                        try{var candidate=scene.audit(level,commands,last[slot].vertices(),last[slot].invocations(),last[slot].fallbackVertices(),backend.fallbackIDs(slot));if(audit!=null&&!audit.hash().equals(candidate.hash()))throw new AssertionError("Frame slots disagree on draw IDs");audit=candidate;}finally{MemoryUtil.memFree(commands);}
                    }
                    writeAudit(folder,api,scene,level,audit,backend.coreCalls(0),backend.commandSlots(0),backend.depthCopies(0),backend.postPasses(0),backend.handOffs(0));
                    long[] cpu=new long[65536];long frames=0,collected=0;double timeline=0,entity=0,compute=0,post=0,fallback=0;long vs=0;
                    long epochStart=System.currentTimeMillis(),start=System.nanoTime();
                    do {
                        long cpuStart=System.nanoTime();int slot=(int)(frames%SLOTS);UnifiedGl.Metrics old=backend.waitSlot(slot);
                        if(old!=null){verifyCounters(audit,old);timeline+=old.timeline();entity+=old.entity();compute+=old.compute();post+=old.post();fallback+=old.fallback();vs+=old.invocations();collected++;}
                        backend.frame(slot);cpu[(int)(frames%cpu.length)]=System.nanoTime()-cpuStart;frames++;
                    }while(frames<minimumFrames||System.nanoTime()-start<seconds*1e9);
                    for(int slot=0;slot<SLOTS;slot++){UnifiedGl.Metrics old=backend.waitSlot(slot);if(old!=null){verifyCounters(audit,old);timeline+=old.timeline();entity+=old.entity();compute+=old.compute();post+=old.post();fallback+=old.fallback();vs+=old.invocations();collected++;}}
                    double elapsed=(System.nanoTime()-start)/1e9;long epochEnd=System.currentTimeMillis();if(collected!=frames)throw new AssertionError("Completed-frame count mismatch");
                    int n=(int)Math.min(frames,cpu.length);long[] sorted=Arrays.copyOf(cpu,n);Arrays.sort(sorted);
                    String result=String.format(Locale.ROOT,"UNIFIED_RESULT API=%s LEVEL=%s width=%d height=%d frames=%d seconds=%.6f FPS=%.3f GPU_TIMELINE_MS=%.6f GPU_ENTITY_MS=%.6f GPU_CULL_MS=%.6f GPU_POST_MS=%.6f GPU_FALLBACK_MS=%.6f CPU_P50_MS=%.6f CPU_P99_MS=%.6f VS_INVOCATIONS=%.3f START_MS=%d END_MS=%d",api,LEVELS[level],width,height,frames,elapsed,frames/elapsed,timeline/frames,entity/frames,compute/frames,post/frames,fallback/frames,sorted[(n-1)/2]/1e6,sorted[(n-1)*99/100]/1e6,(double)vs/frames,epochStart,epochEnd);
                    System.out.println(result);Files.writeString(folder.resolve("result.txt"),result+"\n");
                    Files.writeString(folder.resolve("audit.txt"),(vulkan?"VK":"GL")+"_GPU_ENTITY_MS="+(entity/frames)+"\n",StandardOpenOption.APPEND);
                    ByteBuffer finalCommands=backend.commands(0);try{var after=scene.audit(level,finalCommands,audit.vertices(),audit.vsInvocations(),audit.fallback()*(long)INDEX_COUNT,backend.fallbackIDs(0));if(!after.hash().equals(audit.hash()))throw new AssertionError("Workload changed during timed run");}finally{MemoryUtil.memFree(finalCommands);}
                    writePixels(folder,backend.pixels(0),width,height);
                    UnifiedGl.check("Unified "+api+" "+LEVELS[level]);
                }
            }
            System.out.println("UNIFIED_BENCHMARK=OK");
        }catch(Exception|Error error){failure=error;throw error;}
        finally{try{if(context!=null)context.close();}catch(RuntimeException|Error error){if(failure!=null)failure.addSuppressed(error);else throw error;}finally{glfwDestroyWindow(hidden);glfwTerminate();}}
    }
    private static void verifyCounters(UnifiedWorkload.Audit expected,UnifiedGl.Metrics actual){if(actual.vertices()!=expected.vertices()||actual.fallbackVertices()!=expected.fallback()*(long)INDEX_COUNT)throw new AssertionError("Timed frame changed actual GPU vertex workload");}
    private static void writeAudit(Path folder,String api,UnifiedWorkload scene,int level,UnifiedWorkload.Audit audit,int coreCalls,int commandSlots,int copies,int posts,int handOffs) throws Exception {
        String prefix=api.startsWith("VK")?"VK":"GL";
        String text="CAPTURE_SOURCE=SYNTHETIC_CANONICAL_TAPE\nSCENE_SHA256="+scene.hash+"\nDRAW_IDS_SHA256="+audit.hash()+"\nCAPTURED_DRAWS="+audit.captured()+"\n"+prefix+"_SUBMITTED_DRAWS="+audit.submitted()+"\nGL_FALLBACK_DRAWS="+audit.fallback()+"\nDUPLICATED_DRAWS="+audit.duplicated()+"\n"+prefix+"_CULLED_DRAWS="+audit.culled()+"\n"+prefix+"_DRAWN_VERTICES="+audit.vertices()+"\n"+prefix+"_VERTEX_SHADER_INVOCATIONS="+audit.vsInvocations()+"\nINDIRECT_COMMAND_SLOTS="+commandSlots+"\nCORE_API_DRAW_CALLS="+coreCalls+"\nDEPTH_COPIES="+copies+"\nPOST_PASSES="+posts+"\nRESOURCE_GRAPH_BOUNDARIES="+(level==FULL?2:0)+"\nCROSS_API_HANDOFFS="+handOffs+"\nSLOTS=2 COLOR=RGBA8 DEPTH=D32F MSAA=1\n";
        Files.writeString(folder.resolve("audit.txt"),text);Files.writeString(folder.resolve("draw-ids.txt"),audit.ids());
    }
    private static void writePixels(Path folder,UnifiedVk.Pixels pixels,int width,int height) throws Exception {
        Files.write(folder.resolve("color.rgba"),pixels.color());ByteBuffer depth=bytes(pixels.depth().length*4);for(float z:pixels.depth()){if(!Float.isFinite(z))throw new AssertionError("Nonfinite depth");depth.putFloat(z);}Files.write(folder.resolve("depth.f32"),depth.array());
        BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);int geometry=0;for(int y=0;y<height;y++)for(int x=0;x<width;x++){int at=(y*width+x)*4,r=pixels.color()[at]&255,g=pixels.color()[at+1]&255,b=pixels.color()[at+2]&255,a=pixels.color()[at+3]&255;image.setRGB(x,height-y-1,a<<24|r<<16|g<<8|b);if(pixels.depth()[y*width+x]<.99f)geometry++;}
        if(geometry==0)throw new AssertionError("Blank unified workload");ImageIO.write(image,"PNG",folder.resolve("color.png").toFile());
    }
}
