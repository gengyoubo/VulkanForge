package github.com.gengyoubo.vulkanforge.client;

import java.nio.ByteBuffer;

/** CPU staging contract: actual adapter operations, no game window, OpenGL or GPU required. */
public final class NativeBuffersSmoke {
    public static void main(String[] ignored){
        int source=(Integer)call("glGenBuffers");call("glBindBuffer",34962,source);
        call("glBufferData",34962,ByteBuffer.wrap(new byte[]{0,1,2,3,4,5,6,7}),35044);
        ByteBuffer range=(ByteBuffer)call("glMapBufferRange",34962,2L,4L,2);
        require(range.capacity()==4,"Mapped range size");range.put(new byte[]{21,22,23,24});call("glUnmapBuffer",34962);
        require(java.util.Arrays.equals(read(source),new byte[]{0,1,21,22,23,24,6,7}),"Partial map clobbered surrounding bytes");
        int destination=(Integer)call("glGenBuffers");call("glBindBuffer",34962,destination);call("glBufferData",34962,10L,35044);
        call("glBindBuffer",36662,source);call("glBindBuffer",36663,destination);call("glCopyBufferSubData",36662,36663,2L,3L,4L);
        require(java.util.Arrays.equals(read(destination),new byte[]{0,0,0,21,22,23,24,0,0,0}),"Arena copy offsets or target bindings");
        call("glBindBuffer",34962,destination);range=(ByteBuffer)call("glMapBufferRange",34962,3L,4L,18);range.put(new byte[]{31,32,33,34});call("glFlushMappedBufferRange",34962,1L,2L);call("glUnmapBuffer",34962);
        byte[] flushed=read(destination);require(flushed[4]==32&&flushed[5]==33&&flushed[0]==0&&flushed[7]==0,"Explicit mapped flush offsets");
        try{call("glMapBufferRange",34962,100L,4L,2);throw new AssertionError("Out of range mapping accepted");}catch(IndexOutOfBoundsException expected){}
        long fence=(Long)call("glFenceSync",37143,0);require((Integer)call("glClientWaitSync",fence,0,0L)==37146,"CPU copy completion fence");call("glDeleteSync",fence);
        try{call("glClientWaitSync",fence,0,0L);throw new AssertionError("Deleted CPU fence accepted");}catch(UnsupportedOperationException expected){}
        snapshotsAndDirtyRanges();
        stableTopK();
        guiFallbackSignature();
        System.out.println("NATIVE_BUFFERS_SMOKE=OK (partial maps, arena copy bindings/offsets, explicit flush, CPU copy fences, no OpenGL)");
    }
    @SuppressWarnings("unchecked") private static void guiFallbackSignature(){try{
        Class<?> type=Class.forName(NativeDeviceState.class.getName()+"$Program");var constructor=type.getDeclaredConstructor();constructor.setAccessible(true);Object program=constructor.newInstance();
        var programs=NativeDeviceState.class.getDeclaredField("programs");programs.setAccessible(true);((java.util.Map<Integer,Object>)programs.get(null)).put(990,program);
        var linked=type.getDeclaredField("linked");linked.setAccessible(true);linked.setBoolean(program,true);var attributes=type.getDeclaredField("attributes");attributes.setAccessible(true);var layout=(java.util.Map<String,Integer>)attributes.get(program);layout.put("Position",0);layout.put("UV0",1);
        var samplers=type.getDeclaredField("samplers");samplers.setAccessible(true);samplers.set(program,new String[]{"Sampler0"});var uniforms=type.getDeclaredField("uniforms");uniforms.setAccessible(true);String source="uniform mat4 ModelViewMat; uniform mat4 ProjMat; uniform vec4 ColorModulator; uniform sampler2D Sampler0;";uniforms.set(program,NativeUniformInfo.read(source,java.util.Set.of("Sampler0")));
        require(NativeDeviceState.guiBlitSupported(990),"Standard GUI shader was rejected");var cached=type.getDeclaredField("guiBlit");cached.setAccessible(true);cached.set(program,null);uniforms.set(program,NativeUniformInfo.read(source+" uniform float CustomTint;",java.util.Set.of("Sampler0")));require(!NativeDeviceState.guiBlitSupported(990),"Custom GUI uniform bypassed compatibility fallback");
        cached.set(program,null);uniforms.set(program,NativeUniformInfo.read(source,java.util.Set.of("Sampler0")));layout.put("UV0",7);require(!NativeDeviceState.guiBlitSupported(990),"Custom GUI input locations bypassed compatibility fallback");
    }catch(ReflectiveOperationException error){throw new AssertionError(error);}}
    private static void stableTopK(){
        record Item(int value,int original) { }
        var random=new java.util.Random(12012);var order=java.util.Comparator.comparingInt(Item::value);
        for(int count:new int[]{0,1,10,100,1000})for(int maximum:new int[]{0,1,5,20,99,2000}){
            var original=new java.util.ArrayList<Item>();for(int i=0;i<count;i++)original.add(new Item(random.nextInt(8),i));
            var expected=new java.util.ArrayList<>(original);expected.sort(order);expected.subList(Math.min(maximum,count),expected.size()).clear();
            NativeModOptimizations.selectStable(original,order,maximum);require(original.equals(expected),"Top-K changed stable selection for n="+count+", k="+maximum);
        }
    }
    @SuppressWarnings("unchecked") private static void snapshotsAndDirtyRanges(){
        try{
            var take=NativeDeviceState.class.getDeclaredMethod("take",int.class,long.class,long[].class,int.class);take.setAccessible(true);
            var written=NativeDeviceState.class.getDeclaredMethod("written",int.class,int.class,int.class);written.setAccessible(true);
            long[] versions=new long[8];take.invoke(null,900,1L,versions,6);
            written.invoke(null,900,8,12);written.invoke(null,900,16,20);written.invoke(null,900,4,16);
            int[] ranges=(int[])take.invoke(null,900,2L,versions,6);
            require(java.util.Arrays.equals(ranges,new int[]{4,20})&&versions[6]==1,"Dirty ranges bridging adjacent writes / base version");
            Object empty=take.invoke(null,900,2L,versions,6);require(empty==take.invoke(null,900,2L,versions,6),"Unchanged buffer allocates dirty ranges");
            for(int i=0;i<65;i++)written.invoke(null,900,i*1024,i*1024+1);
            ranges=(int[])take.invoke(null,900,3L,versions,6);require(ranges!=null&&ranges.length==128,"Many small patches degraded to a whole arena upload");
            for(int i=0;i<65;i++){boolean covered=false;for(int j=0;j<ranges.length;j+=2)if(ranges[j]<=i*1024&&ranges[j+1]>=i*1024+1)covered=true;require(covered,"Coalescing lost a dirty byte");}
            var fbo=Class.forName(NativeDeviceState.class.getName()+"$Framebuffer").getDeclaredConstructor();fbo.setAccessible(true);
            var targets=NativeDeviceState.class.getDeclaredField("framebuffers");targets.setAccessible(true);((java.util.Map<Integer,Object>)targets.get(null)).put(0,fbo.newInstance());
            var state=NativeDeviceState.class.getDeclaredMethod("drawState",int.class);state.setAccessible(true);
            int[] a=(int[])state.invoke(null,4);require(a==state.invoke(null,4),"Unchanged draw state allocated");
            call("glEnable",3042);int[] b=(int[])state.invoke(null,4);require(a!=b&&a[0]==0&&b[0]==1,"Queued state snapshot mutated");
            call("glBlendFuncSeparatei",1,770,771,1,0);int[] c=(int[])state.invoke(null,4);call("glDisablei",3042,1);int[] d=(int[])state.invoke(null,4);
            require(c.length==81&&c[25]==1&&d[25]==0&&c[26]==770,"Indexed blend snapshot mutated");
        }catch(ReflectiveOperationException error){throw new AssertionError(error);}
    }
    private static byte[] read(int id){call("glBindBuffer",34962,id);ByteBuffer data=(ByteBuffer)call("glMapBuffer",34962,35000);byte[] bytes=new byte[data.remaining()];data.get(bytes);call("glUnmapBuffer",34962);return bytes;}
    private static Object call(String name,Object...args){return NativeDeviceState.call(name,args);}
    private static void require(boolean condition,String reason){if(!condition)throw new AssertionError(reason);}
}
