package github.com.gengyoubo.vulkanforge.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/** Optional Embeddium 0.3.31 batch entry. Reflection is confined to first use of an immutable tessellation layout. */
public final class NativeEmbeddiumBridge {
    private static final boolean PROFILE=Boolean.getBoolean("vulkanforge.nativeProfile");
    private static final boolean DIAGNOSTICS=PROFILE||Boolean.getBoolean("vulkanforge.nativeDiagnostics");
    private record Layout(int vertex,int index,int[][] attributes) { }
    private record BatchFields(Field counts,Field offsets,Field bases,Field size) { }
    private static final Map<Object,Layout> layouts=new WeakHashMap<>();
    private static long batches,subDraws,indices,layoutBuilds,inclusiveNs;
    private static Method rendererInstance,visibleCount;
    private static final ClassValue<BatchFields> batchFields=new ClassValue<>(){
        @Override protected BatchFields computeValue(Class<?> type){try{return new BatchFields(type.getField("pElementCount"),type.getField("pElementPointer"),type.getField("pBaseVertex"),type.getField("size"));}catch(ReflectiveOperationException error){throw incompatible(error);}}
    };
    private NativeEmbeddiumBridge() { }
    public static void draw(Object commandList,Object tessellation,Object batch){
        long start=PROFILE?System.nanoTime():0;
        try{
            Layout layout=layouts.get(tessellation);if(layout==null){layout=layout(tessellation);layouts.put(tessellation,layout);layoutBuilds++;}
            BatchFields fields=batchFields.get(batch.getClass());int size=fields.size().getInt(batch);if(size==0)return;
            long counts=fields.counts().getLong(batch);batches++;subDraws+=size;
            if(DIAGNOSTICS)for(int i=0;i<size;i++)indices+=org.lwjgl.system.MemoryUtil.memGetInt(counts+i*4L);
            NativeDeviceState.regionDraw(layout.vertex(),layout.index(),layout.attributes(),counts,fields.offsets().getLong(batch),fields.bases().getLong(batch),size);
        }catch(ReflectiveOperationException error){throw incompatible(error);}
        finally{if(PROFILE)inclusiveNs+=System.nanoTime()-start;}
    }
    private static Layout layout(Object tessellation)throws ReflectiveOperationException{
        Field bindings=null;for(Class<?> type=tessellation.getClass();type!=null;type=type.getSuperclass())try{bindings=type.getDeclaredField("bindings");break;}catch(NoSuchFieldException ignored){}
        if(bindings==null)throw new NoSuchFieldException("Embeddium tessellation bindings");bindings.setAccessible(true);
        Object primitive=invoke(tessellation,"getPrimitiveType");if((Integer)invoke(primitive,"getId")!=4)throw new IllegalStateException("Native region batch requires triangles");
        int vertex=0,index=0;List<int[]> attributes=new ArrayList<>();
        for(Object binding:(Object[])bindings.get(tessellation)){
            int handle=(Integer)invoke(invoke(binding,"buffer"),"handle");Object[] values=(Object[])invoke(binding,"attributeBindings");
            if(values.length==0){if(index!=0)throw new IllegalStateException("Multiple region index buffers");index=handle;continue;}
            if(vertex!=0)throw new IllegalStateException("Multiple region vertex buffers");vertex=handle;
            for(Object attribute:values)attributes.add(new int[]{(Integer)invoke(attribute,"getIndex"),(Integer)invoke(attribute,"getCount"),(Integer)invoke(attribute,"getFormat"),(Boolean)invoke(attribute,"isNormalized")?1:0,(Boolean)invoke(attribute,"isIntType")?1:0,(Integer)invoke(attribute,"getPointer"),(Integer)invoke(attribute,"getStride"),handle});
        }
        if(vertex==0||index==0||attributes.isEmpty())throw new IllegalStateException("Incomplete native region layout");
        attributes.sort(Comparator.comparingInt(value->value[0]));return new Layout(vertex,index,attributes.toArray(int[][]::new));
    }
    private static Object invoke(Object owner,String name)throws ReflectiveOperationException{Method method=owner.getClass().getMethod(name);return method.invoke(owner);}
    private static IllegalStateException incompatible(Exception error){return new IllegalStateException("Unsupported Embeddium region layout; use nativeLegacyModSubmission for the compatibility entry",error);}
    static Map<String,Long> diagnostics(){
        long visible=0;
        if(net.minecraftforge.fml.ModList.get().isLoaded("embeddium"))try{if(rendererInstance==null){Class<?> type=Class.forName("me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer");rendererInstance=type.getMethod("instance");visibleCount=type.getMethod("getVisibleChunkCount");}Object renderer=rendererInstance.invoke(null);if(renderer!=null)visible=((Number)visibleCount.invoke(renderer)).longValue();}catch(ReflectiveOperationException error){throw incompatible(error);}
        return Map.of("regionBridgeBatches",batches,"regionBridgeSubDraws",subDraws,"regionBridgeIndices",indices,"regionBridgeLayoutBuilds",layoutBuilds,"regionBridgeInclusiveNs",inclusiveNs,"visibleSections",visible);
    }
}
