package github.com.gengyoubo.vulkanforge.client.shader;

import java.util.*;

/**
 * Opt-in render-thread CPU timings and GPU section timings ({@code -Dvulkanforge.shaderPackProfile=true});
 * the first 120 world frames are excluded from steady-state samples.
 */
final class ShaderPackTimings implements AutoCloseable {
    private final boolean enabled=Boolean.getBoolean("vulkanforge.shaderPackProfile");
    private final Map<String,Samples> stages=new TreeMap<>();
    private ShaderPackGpuTimer gpu;
    private int frames;
    boolean enabled(){return enabled;}
    void beginFrame(){frames++;}
    long start(){return enabled&&frames>120?System.nanoTime():0;}
    void end(String stage,long started){if(started!=0)stages.computeIfAbsent(stage,unused->new Samples()).add(System.nanoTime()-started);}
    /** Starts the GPU section {@code label}; the previous section of this frame ends here. */
    void mark(String label){if(enabled){if(gpu==null)gpu=new ShaderPackGpuTimer();gpu.mark(label);}}
    /** Closes the frame's last section; call after the frame's final mark. */
    void endFrame(){if(gpu!=null)gpu.endFrame();}
    String report(){StringBuilder text=new StringBuilder("FRAME_CPU_PROFILE=").append(enabled).append("\nWARMUP_FRAMES=120\n");stages.forEach((name,samples)->text.append(samples.report(name)));if(gpu!=null)text.append(gpu.report());return text.toString();}
    @Override public void close(){if(gpu!=null){gpu.close();gpu=null;}}
    private static final class Samples {
        final long[] recent=new long[1024];long count,total,max;
        void add(long value){recent[(int)(count++%recent.length)]=value;total+=value;max=Math.max(max,value);}
        String report(String name){long[] sorted=Arrays.copyOf(recent,(int)Math.min(count,recent.length));Arrays.sort(sorted);return String.format(Locale.ROOT,"%s_COUNT=%d\n%s_MEAN_MS=%.4f\n%s_RECENT_P95_MS=%.4f\n%s_MAX_MS=%.4f\n",name,count,name,total/(double)count/1e6,name,sorted[Math.min(sorted.length-1,(int)(sorted.length*.95))]/1e6,name,max/1e6);}
    }
}
