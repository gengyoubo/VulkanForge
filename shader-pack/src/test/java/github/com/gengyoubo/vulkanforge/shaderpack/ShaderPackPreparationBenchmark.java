package github.com.gengyoubo.vulkanforge.shaderpack;

import java.nio.file.Path;
import java.util.*;

/** Wall-clock CPU preparation phases for the original pack; no graphics context or reference loader. */
public final class ShaderPackPreparationBenchmark {
    private static long previous=System.nanoTime();
    private static void phase(String name){long now=System.nanoTime();System.out.printf(Locale.ROOT,"%s_MS=%.3f%n",name,(now-previous)/1e6);previous=now;}
    public static void main(String[] args)throws Exception{
        var pack=ShaderPack.load(Path.of(args[0]));phase("LOAD");
        if(args.length>1){var prepared=new ShaderPackPrepared(pack,Map.of());System.out.println("PREPARED_MS="+prepared.milliseconds);System.out.println("PREWARMED_WORLD_VARIANTS="+prepared.worldVariants());phase("PREPARE");return;}
        var options=new ShaderPackOptions(pack);phase("OPTIONS");
        ShaderPackSupport.missing(pack,Map.of());phase("PREFLIGHT");
        ShaderPackSupport.approximations(pack,Map.of());phase("APPROXIMATIONS");
        var plan=new ShaderPackRenderPlan(pack,Map.of());phase("PLAN");
        int count=0;
        for(var pass:plan.dimension("world0").world().values()){
            ShaderPackGlsl.world(pass.source(),options,plan.configuration().values(),Set.of("Position","Color","UV0","UV1","UV2","Normal"),true,pass.alphaTest());count++;
        }
        phase("WORLD_NORMALIZATION");System.out.println("PROGRAMS="+count);
    }
}
