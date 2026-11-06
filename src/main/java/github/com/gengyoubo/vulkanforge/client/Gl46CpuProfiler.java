package github.com.gengyoubo.vulkanforge.client;

/** Frame-coherent CPU sampling: disabled stamps never read the clock. Zero denotes an unmeasured scope. */
final class Gl46CpuProfiler {
    private static final boolean ENABLED=Boolean.getBoolean("vulkanforge.gl46.profileCpu");
    private static final int INTERVAL=Math.max(1,Integer.getInteger("vulkanforge.gl46.cpuProfileInterval",60));
    private static int benchmark=-1;
    private Gl46CpuProfiler() { }
    static boolean sample(long frame){return benchmark>=0?benchmark>0:ENABLED&&frame%INTERVAL==0;}
    static long stamp(boolean sample){return sample?System.nanoTime():0;}
    static long elapsed(long start){return start==0?0:System.nanoTime()-start;}
    static void benchmark(int mode){if(!System.getProperty("vulkanforge.gameFps","").equals("GL46"))throw new IllegalStateException("GL46 benchmark only");benchmark=mode;}
}
