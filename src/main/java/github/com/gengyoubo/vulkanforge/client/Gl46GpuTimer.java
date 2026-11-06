package github.com.gengyoubo.vulkanforge.client;

import static org.lwjgl.opengl.GL33C.*;

/** Optional sampled timestamp pairs. Busy query slots are dropped; results never introduce a GPU wait. */
final class Gl46GpuTimer implements AutoCloseable {
    private static final boolean ENABLED = Boolean.getBoolean("vulkanforge.gl46.profileGpu");
    private static final int INTERVAL = Math.max(1, Integer.getInteger("vulkanforge.gl46.profileInterval", 60));
    private final int[][] queries = new int[8][2];
    private final boolean[] pending = new boolean[8];
    private final Gl46Timer elapsed = new Gl46Timer();
    private int current = -1, next;
    private long attempts, dropped, total;
    private final long[] tags=new long[8];
    private long lastTag=-1,lastNanos;

    void begin() {
        begin(-1);
    }
    void begin(long frame) {
        if (!ENABLED) return;
        poll();
        if (attempts++ % INTERVAL != 0) return;
        for (int n = 0; n < pending.length; n++) {
            int slot = (next + n) % pending.length;
            if (pending[slot]) continue;
            if (queries[slot][0] == 0) glGenQueries(queries[slot]);
            current = slot; next = (slot + 1) % pending.length;
            tags[slot]=frame;
            glQueryCounter(queries[slot][0], GL_TIMESTAMP);
            return;
        }
        dropped++;
    }

    void end() {
        if (current < 0) return;
        glQueryCounter(queries[current][1], GL_TIMESTAMP);
        pending[current] = true; current = -1;
    }

    private void poll() {
        for (int slot = 0; slot < pending.length; slot++) {
            if (!pending[slot] || glGetQueryObjecti(queries[slot][1], GL_QUERY_RESULT_AVAILABLE) == 0) continue;
            long nanos = glGetQueryObjectui64(queries[slot][1], GL_QUERY_RESULT) - glGetQueryObjectui64(queries[slot][0], GL_QUERY_RESULT);
            elapsed.sample(nanos); total += nanos; pending[slot] = false;
            if(tags[slot]>=lastTag){lastTag=tags[slot];lastNanos=nanos;}
        }
    }

    java.util.Map<String,Long> diagnostics(String prefix){
        if(ENABLED)poll();
        return java.util.Map.of(prefix+"Samples",elapsed.frames(),prefix+"TotalNs",total,prefix+"LastFrame",lastTag,prefix+"LastNs",lastNanos,prefix+"Dropped",dropped);
    }

    @Override public String toString() {
        if (!ENABLED) return "DISABLED";
        poll();
        int outstanding = 0; for (boolean value : pending) if (value) outstanding++;
        return "samples=" + elapsed.frames() + " dropped=" + dropped + " pending=" + outstanding
                + " meanUs=" + total / 1e3 / Math.max(1, elapsed.frames()) + " " + elapsed;
    }

    @Override public void close() {
        for (int[] pair : queries) if (pair[0] != 0) { glDeleteQueries(pair); pair[0] = pair[1] = 0; }
    }
}
