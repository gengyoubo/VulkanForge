package github.com.gengyoubo.vulkanforge.client.shader;

import java.util.*;

/** Opt-in render-thread CPU timings; the first 120 world frames are excluded from steady-state samples. */
final class ShaderPackTimings {
    private final boolean enabled=Boolean.getBoolean("vulkanforge.shaderPackProfile");
    private final Map<String,Samples> stages=new TreeMap<>();
    private int frames;
    void beginFrame(){frames++;}
    long start(){return enabled&&frames>120?System.nanoTime():0;}
    void end(String stage,long started){if(started!=0)stages.computeIfAbsent(stage,unused->new Samples()).add(System.nanoTime()-started);}
    String report(){StringBuilder text=new StringBuilder("FRAME_CPU_PROFILE=").append(enabled).append("\nWARMUP_FRAMES=120\n");stages.forEach((name,samples)->text.append(samples.report(name)));return text.toString();}
    private static final class Samples {
        final long[] recent=new long[1024];long count,total,max;
        void add(long value){recent[(int)(count++%recent.length)]=value;total+=value;max=Math.max(max,value);}
        String report(String name){long[] sorted=Arrays.copyOf(recent,(int)Math.min(count,recent.length));Arrays.sort(sorted);return String.format(Locale.ROOT,"%s_COUNT=%d\n%s_MEAN_MS=%.4f\n%s_RECENT_P95_MS=%.4f\n%s_MAX_MS=%.4f\n",name,count,name,total/(double)count/1e6,name,sorted[Math.min(sorted.length-1,(int)(sorted.length*.95))]/1e6,name,max/1e6);}
    }
}
