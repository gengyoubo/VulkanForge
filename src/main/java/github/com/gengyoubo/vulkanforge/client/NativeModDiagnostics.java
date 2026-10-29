package github.com.gengyoubo.vulkanforge.client;

import java.util.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Opt-in observation of the addons' actual callbacks. No culling predicate or configuration is replaced. */
public final class NativeModDiagnostics {
    private static final boolean PROFILE=Boolean.getBoolean("vulkanforge.nativeProfile");
    private static final IdentityHashMap<Object,Integer> checked=new IdentityHashMap<>();
    private static final long[] calls=new long[2],culled=new long[2],nanos=new long[2];
    private static long overlapping;
    private static final long[] chunkCalls=new long[3],chunkUnits=new long[3],chunkNs=new long[3];
    private NativeModDiagnostics() { }
    static void beginFrame(){if(PROFILE)checked.clear();}
    public static void cullingResult(int addon,long start,Object entity,Object callback){
        nanos[addon]+=System.nanoTime()-start;calls[addon]++;
        var result=(CallbackInfoReturnable<?>)callback;if(result.isCancelled()&&Boolean.FALSE.equals(result.getReturnValue()))culled[addon]++;
        int bit=1<<addon,previous=checked.getOrDefault(entity,0);if((previous&bit)==0&&previous!=0)overlapping++;checked.put(entity,previous|bit);
    }
    public static void chunkScope(int kind,long start,int units){chunkCalls[kind]++;chunkUnits[kind]+=units;chunkNs[kind]+=System.nanoTime()-start;}
    static Map<String,Long> diagnostics(){Map<String,Long> stats=new LinkedHashMap<>(Map.of("chlorideCullingCalls",calls[0],"chlorideCulled",culled[0],"chlorideCullingNs",nanos[0],"extrasCullingCalls",calls[1],"extrasCulled",culled[1],"extrasCullingNs",nanos[1],"overlappingEntityCullingChecks",overlapping));String[] names={"visibilityGraph","chunkBuildResults","regionUploadResults"};for(int i=0;i<3;i++){stats.put(names[i]+"Calls",chunkCalls[i]);stats.put(names[i]+"Units",chunkUnits[i]);stats.put(names[i]+"Ns",chunkNs[i]);}return stats;}
}
