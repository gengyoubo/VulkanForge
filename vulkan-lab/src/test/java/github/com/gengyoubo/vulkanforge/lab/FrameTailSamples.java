package github.com.gengyoubo.vulkanforge.lab;

import java.util.Arrays;
import java.util.Locale;

/** Test-only CPU tail diagnostics. Exact percentiles over the most recent 65536 frames; no GPU wait is added. */
final class FrameTailSamples {
    private static final int CAPACITY=65536;
    private final long[][] samples={new long[CAPACITY],new long[CAPACITY],new long[CAPACITY]};
    private final long[] maxima=new long[3];
    private long frames,pendingPeak;
    void add(long frame,long fence,long handoff,long pendingBytes) {
        int at=(int)(frames++%CAPACITY);
        samples[0][at]=frame;samples[1][at]=fence;samples[2][at]=handoff;
        maxima[0]=Math.max(maxima[0],frame);maxima[1]=Math.max(maxima[1],fence);maxima[2]=Math.max(maxima[2],handoff);
        pendingPeak=Math.max(pendingPeak,pendingBytes);
    }
    void report(String phase) {
        int count=(int)Math.min(frames,CAPACITY);String[] names={"FRAME_CPU","FENCE_WAIT_CPU","GL_HANDOFF_CPU"};
        for(int i=0;i<3;i++) {
            long[] sorted=Arrays.copyOf(samples[i],count);Arrays.sort(sorted);
            if(count>0)System.out.printf(Locale.ROOT,"TAIL_%s_%s_MS samples=%d p50=%.6f p95=%.6f p99=%.6f max=%.6f%n",phase,names[i],count,
                    sorted[(count-1)/2]/1e6,sorted[(count-1)*95/100]/1e6,sorted[(count-1)*99/100]/1e6,maxima[i]/1e6);
        }
        // This is admission accounting as last polled by production, not a live GPU backlog query.
        System.out.println("TAIL_"+phase+"_ACCOUNTED_UPLOAD_PENDING_PEAK_BYTES="+pendingPeak);
    }
}
