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
        Object shared=field(backend,"world");WorldScene scene=(WorldScene)field(shared,"world");GpuCuller culler=(GpuCuller)field(shared,"culler");
        long[] size=backend.worldStats();
        scene.auditDrawIds(true);
        try {
            for(int i=0;i<2;i++){if(nativeMode)backend.renderWorldWithSky((int)size[4],(int)size[5],new Object[0],true);else backend.renderWorld((int)size[4],(int)size[5]);backend.finishWorldFrame();}
            new BenchmarkScene.Probe(backend).drain();
            Map<Long,Long> opaque=scene.actualOpaque(culler,0),glass=scene.actualTranslucent(0);
            if(!opaque.equals(scene.actualOpaque(culler,1))||!glass.equals(scene.actualTranslucent(1)))throw new AssertionError("Frame slots disagree on actual draw IDs");
            Workload.audit(output,opaque,glass);
        } finally {scene.auditDrawIds(false);}
    }
    static void writeNative(NativeWorldRenderer renderer,NativeFramePlan plan,Path output) throws Exception {
        renderer.drain();renderer.world.auditDrawIds(true);
        try {
            for(int i=0;i<2;i++){renderer.waitSlot(renderer.nextSlot());renderer.frame(plan);}renderer.drain();
            Map<Long,Long> opaque=renderer.world.actualOpaque(renderer.culler,0),glass=renderer.world.actualTranslucent(0);
            if(!opaque.equals(renderer.world.actualOpaque(renderer.culler,1))||!glass.equals(renderer.world.actualTranslucent(1)))throw new AssertionError("Native frame slots disagree on actual draw IDs");
            Workload.audit(output,opaque,glass);
        }finally{renderer.world.auditDrawIds(false);}
    }
}
