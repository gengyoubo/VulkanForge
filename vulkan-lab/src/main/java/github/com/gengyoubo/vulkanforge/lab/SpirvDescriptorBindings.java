package github.com.gengyoubo.vulkanforge.lab;

import java.nio.*;
import java.util.*;

/** Reads live opaque descriptor bindings from optimized SPIR-V, excluding unused source declarations. */
final class SpirvDescriptorBindings {
    private SpirvDescriptorBindings(){ }
    static Set<Integer> read(ByteBuffer bytes){
        IntBuffer words=bytes.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();Map<Integer,Integer> bindings=new HashMap<>();Set<Integer> variables=new HashSet<>();
        for(int at=5;at<words.limit();){int instruction=words.get(at),size=instruction>>>16,opcode=instruction&65535;if(size<1||at+size>words.limit())throw new IllegalArgumentException("Invalid SPIR-V instruction");
            if(opcode==71&&size>=4&&words.get(at+2)==33)bindings.put(words.get(at+1),words.get(at+3));
            if(opcode==59&&size>=4&&words.get(at+3)==0)variables.add(words.get(at+2));at+=size;
        }
        Set<Integer> result=new HashSet<>();for(int variable:variables){Integer binding=bindings.get(variable);if(binding!=null)result.add(binding);}return result;
    }
}
