package github.com.gengyoubo.vulkanforge.client;

import java.nio.file.*;
import java.util.*;
import static org.lwjgl.opengl.GL33.*;

/** Opt-in render-thread profiler. GPU results are read only after availability, never waited for. */
public final class TailFrameProfiler {
    public static final boolean ENABLED=Boolean.getBoolean("vulkanforge.tailProfile");
    public static final boolean GPU_ENABLED=ENABLED&&!Boolean.getBoolean("vulkanforge.tailProfileCpuOnly");
    public static final int GAME=0,NATIVE=1,OPAQUE=2,TRANSLUCENT=3,POST=4,SWAP=5,FRAME=6,PERIOD=7;
    private static final String[] NAMES={"game_render","native_render_call","gl_opaque_composite","gl_translucent_composite","gl_post_chain","swap_buffers","render_to_swap","frame_period"};
    private static final int STAGES=8,CAPACITY=131072,EVENTS=32;
    private static final long[] started=new long[STAGES],cpu=new long[STAGES];
    private static final int[] depth=new int[STAGES],event=new int[STAGES];
    private static final long[] cpuRows=ENABLED?new long[CAPACITY*STAGES]:null;
    private static final double[] gpuRows=ENABLED?new double[CAPACITY*STAGES]:null;
    private static final Slot[] slots=new Slot[8];
    private static Slot current;
    private static boolean open,recording;
    private static int count,generation,nextSlot;
    private static long begin,elapsed,frames,frameBegin,previousBegin,cpuOverflow,gpuDropped;
    private static final class Slot {
        final int[] queries=new int[2+EVENTS*2],stage=new int[EVENTS];
        int events,row=-1,generation;boolean pending;
        Slot(){glGenQueries(queries);}
    }
    private TailFrameProfiler(){}
    public static void beginGameRender(){
        if(!ENABLED)return;
        TailDiagnostics.initialize();
        if(open)endFrame();
        poll();
        if(!Boolean.getBoolean("vulkanforge.tailAblation")&&!recording&&net.minecraft.client.Minecraft.getInstance().level!=null)start();
        open=true;frameBegin=System.nanoTime();Arrays.fill(cpu,0);Arrays.fill(depth,0);Arrays.fill(event,-1);
        cpu[PERIOD]=previousBegin==0?0:frameBegin-previousBegin;previousBegin=frameBegin;
        current=null;
        for(int n=0;GPU_ENABLED&&n<slots.length;n++){
            int index=(nextSlot+n)%slots.length;if(slots[index]==null)slots[index]=new Slot();
            if(!slots[index].pending){current=slots[index];nextSlot=(index+1)%slots.length;break;}
        }
        if(current!=null){current.events=0;current.row=-1;glQueryCounter(current.queries[0],GL_TIMESTAMP);}
        else if(recording&&GPU_ENABLED)gpuDropped++;
        begin(GAME);
    }
    public static void endGameRender(){end(GAME);}
    public static void beginPost(){begin(POST);}
    public static void endPost(){end(POST);}
    public static void begin(int stage){
        if(!ENABLED||!open)return;
        if(depth[stage]++>0)return;
        started[stage]=System.nanoTime();
        if(current!=null&&current.events<EVENTS){int e=current.events++;event[stage]=e;current.stage[e]=stage;glQueryCounter(current.queries[2+e*2],GL_TIMESTAMP);}
        else {event[stage]=-1;if(recording&&GPU_ENABLED)gpuDropped++;}
    }
    public static void end(int stage){
        if(!ENABLED||!open||depth[stage]==0)return;
        if(--depth[stage]>0)return;
        cpu[stage]+=System.nanoTime()-started[stage];
        if(current!=null&&event[stage]>=0)glQueryCounter(current.queries[3+event[stage]*2],GL_TIMESTAMP);
        event[stage]=-1;
    }
    public static void endFrame(){
        if(!ENABLED||!open)return;
        for(int s=0;s<FRAME;s++)while(depth[s]>0)end(s);
        cpu[FRAME]=System.nanoTime()-frameBegin;
        int row=-1;
        if(recording){frames++;if(count<CAPACITY){row=count++;System.arraycopy(cpu,0,cpuRows,row*STAGES,STAGES);Arrays.fill(gpuRows,row*STAGES,(row+1)*STAGES,Double.NaN);}else cpuOverflow++;}
        if(current!=null){current.row=row;current.generation=generation;glQueryCounter(current.queries[1],GL_TIMESTAMP);current.pending=true;}
        current=null;open=false;
    }
    private static void poll(){
        if(!GPU_ENABLED)return;
        for(Slot slot:slots){
            if(slot==null||!slot.pending||glGetQueryObjecti(slot.queries[1],GL_QUERY_RESULT_AVAILABLE)==0)continue;
            if(slot.generation==generation&&slot.row>=0){
                int row=slot.row*STAGES;Arrays.fill(gpuRows,row,row+STAGES,0);
                gpuRows[row+PERIOD]=Double.NaN;
                gpuRows[row+FRAME]=(glGetQueryObjectui64(slot.queries[1],GL_QUERY_RESULT)-glGetQueryObjectui64(slot.queries[0],GL_QUERY_RESULT))/1e6;
                for(int e=0;e<slot.events;e++)gpuRows[row+slot.stage[e]]+=(glGetQueryObjectui64(slot.queries[3+e*2],GL_QUERY_RESULT)-glGetQueryObjectui64(slot.queries[2+e*2],GL_QUERY_RESULT))/1e6;
            }
            slot.pending=false;
        }
    }
    public static void start(){
        if(!ENABLED)throw new IllegalStateException("tailProfile is disabled");
        if(open||recording)throw new IllegalStateException("Start profiling at a frame boundary");
        poll();generation++;count=0;frames=cpuOverflow=gpuDropped=0;previousBegin=0;begin=System.nanoTime();recording=true;
    }
    /** Stops without draining the GPU. Pending query rows stay explicitly unavailable in the CSV. */
    public static Map<String,Double> stop(Path out) throws java.io.IOException {
        if(!recording||open)throw new IllegalStateException("Stop profiling at a frame boundary");
        elapsed=System.nanoTime()-begin;recording=false;poll();Files.createDirectories(out);
        Map<String,Double> result=new LinkedHashMap<>();result.put("frames",(double)frames);result.put("seconds",elapsed/1e9);result.put("fps",frames/(elapsed/1e9));result.put("cpu_overflow",(double)cpuOverflow);result.put("gpu_dropped",(double)gpuDropped);result.put("gpu_capture_enabled",GPU_ENABLED?1d:0d);
        StringBuilder csv=new StringBuilder("frame");for(String name:NAMES)csv.append(",cpu_").append(name).append("_ms");for(String name:NAMES)csv.append(",gl_span_").append(name).append("_ms");csv.append('\n');
        for(int r=0;r<count;r++){csv.append(r);for(int s=0;s<STAGES;s++)csv.append(',').append(cpuRows[r*STAGES+s]/1e6);for(int s=0;s<STAGES;s++)csv.append(',').append(gpuRows[r*STAGES+s]);csv.append('\n');}
        Files.writeString(out.resolve("frames.csv"),csv);
        for(int s=0;s<STAGES;s++){
            double[] values=new double[count];double total=0;for(int r=0;r<count;r++){values[r]=cpuRows[r*STAGES+s]/1e6;total+=values[r];}Arrays.sort(values);
            result.put("cpu_"+NAMES[s]+"_mean_ms",count==0?0:total/count);
            for(int p:new int[]{50,95,99})result.put("cpu_"+NAMES[s]+"_p"+p+"_ms",count==0?0:values[(count-1)*p/100]);
            result.put("cpu_"+NAMES[s]+"_max_ms",count==0?0:values[count-1]);
            double gpu=0;int samples=0;for(int r=0;r<count;r++)if(Double.isFinite(gpuRows[r*STAGES+s])){gpu+=gpuRows[r*STAGES+s];samples++;}
            result.put("gl_span_"+NAMES[s]+"_samples",(double)samples);if(samples>0)result.put("gl_span_"+NAMES[s]+"_mean_ms",gpu/samples);
        }
        StringBuilder props=new StringBuilder();result.forEach((k,v)->props.append(k).append('=').append(v).append('\n'));Files.writeString(out.resolve("profile.txt"),props);
        return result;
    }
    public static void close(){
        if(!ENABLED)return;
        if(open)endFrame();
        try {if(recording)stop(net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get().resolve("logs/vulkanforge/tail-profile"));}
        catch(java.io.IOException error){throw new IllegalStateException(error);}
        finally {for(int i=0;i<slots.length;i++)if(slots[i]!=null){glDeleteQueries(slots[i].queries);slots[i]=null;}}
    }
}
