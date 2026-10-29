package github.com.gengyoubo.vulkanforge.lab;

import java.nio.*;
import java.util.*;

/** Active byte offsets in the translated uniform block, from optimized SPIR-V rather than GLSL declarations. */
final class SpirvUniformMembers {
    private SpirvUniformMembers(){ }
    static Set<Integer> read(ByteBuffer bytes,int binding){
        IntBuffer code=bytes.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        Map<Integer,Integer> bindings=new HashMap<>(),pointees=new HashMap<>(),variables=new HashMap<>(),constants=new HashMap<>();Map<Integer,Map<Integer,Integer>> offsets=new HashMap<>();
        for(int at=5;at<code.limit();){int header=code.get(at),size=header>>>16,op=header&65535;if(size<1||at+size>code.limit())throw new IllegalArgumentException("Invalid SPIR-V instruction");
            if(op==71&&size>=4&&code.get(at+2)==33)bindings.put(code.get(at+1),code.get(at+3));
            if(op==72&&size>=5&&code.get(at+3)==35)offsets.computeIfAbsent(code.get(at+1),unused->new HashMap<>()).put(code.get(at+2),code.get(at+4));
            if(op==32&&size>=4)pointees.put(code.get(at+1),code.get(at+3));
            if(op==59&&size>=4&&code.get(at+3)==2)variables.put(code.get(at+2),code.get(at+1));
            if(op==43&&size==4)constants.put(code.get(at+2),code.get(at+3));at+=size;
        }
        Set<Integer> active=new HashSet<>();
        for(var variable:variables.entrySet())if(Objects.equals(bindings.get(variable.getKey()),binding)){
            int root=variable.getKey();Map<Integer,Integer> members=offsets.getOrDefault(pointees.get(variable.getValue()),Map.of());Set<Integer> descendants=new HashSet<>();
            for(int at=5;at<code.limit();){int header=code.get(at),size=header>>>16,op=header&65535;
                if((op==65||op==66)&&size>=5){int base=code.get(at+3);if(base==root){Integer member=constants.get(code.get(at+4));if(member==null||!members.containsKey(member))active.addAll(members.values());else active.add(members.get(member));descendants.add(code.get(at+2));}else if(descendants.contains(base))descendants.add(code.get(at+2));}
                // A whole-block load exposes all fields; keep them active conservatively.
                if(op==61&&size>=4&&code.get(at+3)==root)active.addAll(members.values());
                at+=size;
            }
        }
        return active;
    }
}
