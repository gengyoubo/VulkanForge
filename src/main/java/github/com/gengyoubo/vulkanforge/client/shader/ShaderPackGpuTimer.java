package github.com.gengyoubo.vulkanforge.client.shader;

import github.com.gengyoubo.vulkanforge.client.NativeGameWindow;
import java.util.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL33.*;

/**
 * GPU time of labelled frame sections: a section runs from its mark to the next mark of the same frame, and repeated
 * labels of one frame are summed. GL reads timestamp queries a few frames late without stalling; under the native
 * bootstrap the marks become timestamps in the backend's own command buffer.
 */
final class ShaderPackGpuTimer implements AutoCloseable {
    private static final int WARMUP=120,MAX_IN_FLIGHT=8;
    private final boolean nativeBackend=NativeGameWindow.enabled();
    private record Frame(int[] queries,String[] labels,int count){ }
    private final ArrayDeque<Integer> free=new ArrayDeque<>();
    private final ArrayDeque<Frame> inFlight=new ArrayDeque<>();
    private final List<Integer> queries=new ArrayList<>();
    private final List<String> labels=new ArrayList<>();
    private final Map<String,double[]> stats=new TreeMap<>();
    private long frames;
    void mark(String label){
        if(nativeBackend){NativeGameWindow.call("gpuMarker",label);return;}
        int query=free.isEmpty()?glGenQueries():free.poll();glQueryCounter(query,GL_TIMESTAMP);queries.add(query);labels.add(label);
    }
    void endFrame(){
        if(nativeBackend||queries.isEmpty())return;
        inFlight.add(new Frame(queries.stream().mapToInt(Integer::intValue).toArray(),labels.toArray(String[]::new),queries.size()));queries.clear();labels.clear();
        while(!inFlight.isEmpty()){
            Frame frame=inFlight.peek();
            if(inFlight.size()<=MAX_IN_FLIGHT&&glGetQueryObjecti(frame.queries()[frame.count()-1],GL_QUERY_RESULT_AVAILABLE)==GL_FALSE)break;
            inFlight.poll();
            // An overflowing backlog is dropped rather than read with a blocking wait.
            if(inFlight.size()<MAX_IN_FLIGHT&&++frames>WARMUP){
                Map<String,Double> sums=new LinkedHashMap<>();long previous=glGetQueryObjecti64(frame.queries()[0],GL_QUERY_RESULT);
                for(int i=1;i<frame.count();i++){long time=glGetQueryObjecti64(frame.queries()[i],GL_QUERY_RESULT);sums.merge(frame.labels()[i-1],(time-previous)/1e6,Double::sum);previous=time;}
                sums.forEach((label,ms)->{double[] stat=stats.computeIfAbsent(label,unused->new double[3]);stat[0]++;stat[1]+=ms;stat[2]=Math.max(stat[2],ms);});
            }
            for(int query:frame.queries())free.add(query);
        }
    }
    String report(){
        // The pack can outlive the native device at shutdown.
        if(nativeBackend)try{return (String)NativeGameWindow.call("gpuMarkerReport");}catch(RuntimeException error){return "GPU_MARKERS=UNAVAILABLE\n";}
        StringBuilder text=new StringBuilder("GPU_MARKERS=GL_TIMESTAMPS\n");
        stats.forEach((label,stat)->text.append(String.format(Locale.ROOT,"GPU_%s_COUNT=%d\nGPU_%s_MEAN_MS=%.4f\nGPU_%s_MAX_MS=%.4f\n",label,(long)stat[0],label,stat[1]/stat[0],label,stat[2])));
        return text.toString();
    }
    @Override public void close(){
        if(nativeBackend)return;
        for(Frame frame:inFlight)for(int query:frame.queries())free.add(query);inFlight.clear();free.addAll(queries);queries.clear();
        for(int query:free)glDeleteQueries(query);free.clear();
    }
}
