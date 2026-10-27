package github.com.gengyoubo.vulkanforge.lab;

import dev.vulkanforge.bench.Workload;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Reads completed culling output once, outside timing; no instrumentation is added to production Vulkan draws. */
final class BenchmarkDrawAudit {
    private static Object field(Object object,String name) throws Exception {
        Field field=object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static Object value(Object object,String name) throws Exception {
        Method method=object.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(object);
    }
    static void write(MainWindowBackend backend,Path output) throws Exception {
        write(backend,output,false);
    }
    static void write(MainWindowBackend backend,Path output,boolean nativeMode) throws Exception {
        Object shared=field(backend,"world"), scene=field(shared,"world"), culler=field(shared,"culler");
        @SuppressWarnings("unchecked") Map<Long,?> sections=(Map<Long,?>)field(scene,"sections");
        Map<Integer,Long> keys=new HashMap<>(); Map<Long,Long> translucent=new TreeMap<>();
        for(var entry:sections.entrySet()) {
            keys.put((Integer)value(entry.getValue(),"cullIndex"),entry.getKey());
            Object transparent=value(entry.getValue(),"translucent");
            if(transparent!=null) translucent.put(entry.getKey(),((Integer)field(transparent,"quads")).longValue());
        }
        long before=(Long)field(scene,"translucentDraws");
        long[] size=backend.worldStats();
        for(int i=0;i<2;i++) { if(nativeMode)backend.renderWorldWithSky((int)size[4],(int)size[5],new Object[0],true);else backend.renderWorld((int)size[4],(int)size[5]); backend.finishWorldFrame(); }
        new BenchmarkScene.Probe(backend).drain();
        if((Long)field(scene,"translucentDraws")-before!=2L*translucent.size())
            throw new AssertionError("Actual Vulkan translucent submissions differ from the audited list");
        GpuBuffer[] counts=(GpuBuffer[])field(culler,"drawCounts"), commands=(GpuBuffer[])field(culler,"outputs");
        Map<Long,Long> previous=null;
        for(int slot=0;slot<counts.length;slot++) {
            Map<Long,Long> opaque=new TreeMap<>();
            for(int i=0;i<counts[slot].mapped.getInt(0);i++) {
                int at=i*16; Long key=keys.get(commands[slot].mapped.getInt(at+12));
                if(key==null) throw new AssertionError("GPU emitted unknown section");
                opaque.merge(key,commands[slot].mapped.getInt(at)/6L,Long::sum);
            }
            if(previous!=null&&!previous.equals(opaque)) throw new AssertionError("Frame slots disagree on actual draw workload");
            previous=opaque;
        }
        Workload.audit(output,previous,translucent);
    }
}
