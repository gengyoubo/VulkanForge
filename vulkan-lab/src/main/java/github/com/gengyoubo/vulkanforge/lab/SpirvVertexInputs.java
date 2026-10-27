package github.com.gengyoubo.vulkanforge.lab;

import java.nio.*;
import java.util.*;

/** Reads the location and numeric class of optimized SPIR-V Input variables. Built-ins have no Location. */
final class SpirvVertexInputs {
    static final int FLOAT=0,SIGNED=1,UNSIGNED=2;
    private SpirvVertexInputs() { }
    static Map<Integer,Integer> read(ByteBuffer code) {
        IntBuffer words=code.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        if(words.remaining()<5||words.get(0)!=0x07230203)throw new IllegalArgumentException("Invalid SPIR-V module");
        Map<Integer,Integer> locations=new HashMap<>(),pointers=new HashMap<>(),vectors=new HashMap<>(),types=new HashMap<>(),variables=new HashMap<>();
        for(int at=5;at<words.limit();) {
            int header=words.get(at),count=header>>>16,op=header&65535;
            if(count==0||at+count>words.limit())throw new IllegalArgumentException("Invalid SPIR-V instruction");
            switch(op) {
                case 21 -> types.put(words.get(at+1),words.get(at+3)!=0?SIGNED:UNSIGNED); // OpTypeInt
                case 22 -> types.put(words.get(at+1),FLOAT); // OpTypeFloat
                case 23 -> vectors.put(words.get(at+1),words.get(at+2)); // OpTypeVector
                case 32 -> pointers.put(words.get(at+1),words.get(at+3)); // OpTypePointer
                case 59 -> {if(words.get(at+3)==1)variables.put(words.get(at+2),words.get(at+1));} // OpVariable, Input
                case 71 -> {if(count>=4&&words.get(at+2)==30)locations.put(words.get(at+1),words.get(at+3));} // Location
                default -> { }
            }
            at+=count;
        }
        Map<Integer,Integer> result=new TreeMap<>();
        for(var entry:variables.entrySet()) {
            Integer location=locations.get(entry.getKey());if(location==null)continue;
            Integer type=pointers.get(entry.getValue());if(type==null)throw new IllegalArgumentException("SPIR-V input has no pointer type");
            type=vectors.getOrDefault(type,type);Integer numeric=types.get(type);
            if(numeric==null)throw new UnsupportedOperationException("Native vertex inputs require scalar/vector numeric types");
            result.put(location,numeric);
        }
        return Map.copyOf(result);
    }
}
