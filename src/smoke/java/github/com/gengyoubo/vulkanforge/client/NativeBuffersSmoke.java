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
        System.out.println("NATIVE_BUFFERS_SMOKE=OK (partial maps, arena copy bindings/offsets, explicit flush, CPU copy fences, no OpenGL)");
    }
    private static byte[] read(int id){call("glBindBuffer",34962,id);ByteBuffer data=(ByteBuffer)call("glMapBuffer",34962,35000);byte[] bytes=new byte[data.remaining()];data.get(bytes);call("glUnmapBuffer",34962);return bytes;}
    private static Object call(String name,Object...args){return NativeDeviceState.call(name,args);}
    private static void require(boolean condition,String reason){if(!condition)throw new AssertionError(reason);}
}
