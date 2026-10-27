package github.com.gengyoubo.vulkanforge.lab;

import java.nio.*;
import java.util.Arrays;
import org.lwjgl.system.MemoryUtil;
import static github.com.gengyoubo.vulkanforge.lab.UnifiedWorkload.*;

/** Records the arguments actually passed to draw APIs, separately from the expected input tape and GPU culling output. */
final class UnifiedSubmissions {
    private final int[] direct,fallback,starts=new int[4],counts=new int[4];
    private int directCount,fallbackCount,ranges;private long hash,expectedHash;private boolean established;
    UnifiedSubmissions(UnifiedWorkload scene){direct=new int[scene.total];fallback=new int[scene.total];reset();}
    void reset(){directCount=fallbackCount=ranges=0;hash=0xcbf29ce484222325L;}
    private void mix(int value){hash=(hash^(value&0xffffffffL))*0x100000001b3L;}
    void direct(int id,boolean compatibility){mix(compatibility?2:0);mix(id);mix(INDEX_COUNT);if(compatibility)fallback[fallbackCount++]=id;else direct[directCount++]=id;}
    void indirect(int start,int count){if(ranges==starts.length)throw new AssertionError("Unexpected indirect submissions");mix(1);mix(start);mix(count);starts[ranges]=start;counts[ranges++]=count;}
    void completed(){if(established&&expectedHash!=hash)throw new AssertionError("Actual draw API arguments changed between frames");expectedHash=hash;established=true;}
    int calls(){return directCount+ranges;}
    int slots(){int n=0;for(int i=0;i<ranges;i++)n+=counts[i];return n;}
    int[] fallbacks(){return Arrays.copyOf(fallback,fallbackCount);}
    ByteBuffer actualCommands(ByteBuffer gpu,int expected) {
        if(directCount+slots()!=expected)throw new AssertionError("Submitted command slot count differs from captured work");
        ByteBuffer result=MemoryUtil.memAlloc(expected*COMMAND_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for(int i=0;i<directCount;i++)result.putInt(INDEX_COUNT).putInt(1).putInt(0).putInt(0).putInt(direct[i]);
        for(int i=0;i<ranges;i++) {
            int start=starts[i],count=counts[i];if(start<0||count<0||start+count>expected)throw new AssertionError("Invalid submitted indirect range");
            ByteBuffer source=gpu.duplicate();source.position(start*COMMAND_BYTES).limit((start+count)*COMMAND_BYTES);result.put(source);
        }
        return result.flip();
    }
}
