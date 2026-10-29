package github.com.gengyoubo.vulkanforge.lab;

import java.nio.*;
import java.util.*;

/** Optimized stage interface locations, including scalar, vector and array variables; built-ins have no Location. */
final class SpirvLocations {
    static Set<Integer> inputs(ByteBuffer code){
        IntBuffer words=code.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();Set<Integer> inputs=new HashSet<>();Map<Integer,Integer> locations=new HashMap<>();
        for(int at=5;at<words.limit();){int header=words.get(at),count=header>>>16,op=header&65535;if(count<1||at+count>words.limit())throw new IllegalArgumentException("Invalid SPIR-V instruction");
            if(op==59&&count>=4&&words.get(at+3)==1)inputs.add(words.get(at+2));
            if(op==71&&count>=4&&words.get(at+2)==30)locations.put(words.get(at+1),words.get(at+3));at+=count;
        }
        Set<Integer> result=new HashSet<>();for(int variable:inputs)if(locations.containsKey(variable))result.add(locations.get(variable));return Set.copyOf(result);
    }
}
