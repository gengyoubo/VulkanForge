package github.com.gengyoubo.vulkanforge.lab;

import dev.vulkanforge.bench.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.vulkan.VK;

public final class VkNativeContractSmoke {
    public static void main(String[] ignored) throws Exception {
        org.lwjgl.system.Configuration.STACK_SIZE.set(4096);
        try(VulkanContext context=new VulkanContext(VulkanContext.requestedApiVersion(),"",true)) {
            context.initialize(0,VK.getInstanceVersionSupported(),false);
            require(!context.device.getCapabilities().VK_KHR_external_memory_win32&&!context.device.getCapabilities().VK_KHR_external_semaphore_win32,"native device enabled external Win32 extensions");
            try{context.initializeInterop(new byte[16],false);throw new AssertionError("Native context switched to interop");}catch(IllegalStateException expected){require(!context.interopEnabled(),"native policy changed before rejection");}
            try{new SharedPresentImage(context,4,4,null);throw new AssertionError("Native context allocated a shared image");}catch(IllegalStateException expected){require(expected.getMessage().contains("forbidden"),"wrong image-policy rejection");}
            try(NativeWorldRenderer renderer=new NativeWorldRenderer(context,64,64,System.out::println)) {
                Workload.load(Path.of("build/reports/offscreen-performance/matched-scene.bin"),renderer::apply);
                for(int i=0;i<4;i++){renderer.waitSlot(renderer.nextSlot());renderer.frame(NativeFramePlan.terrain());}renderer.drain();long before=renderer.stats()[0];
                try{renderer.frame(new NativeFramePlan(List.of(),List.of(new NativeFramePlan.UnsupportedDraw(42,"ENTITY","unimplemented-test-draw"))));throw new AssertionError("Unsupported native draw disappeared");}catch(UnsupportedOperationException expected){require(expected.getMessage().contains("42")&&expected.getMessage().contains("ENTITY"),"missing compatibility detail");}
                require(renderer.stats()[0]==before,"rejected draw submitted a frame");
                var invalid=new NativeFramePlan.PostDraw("bad","#version 450\ninvalid shader","#version 450\ninvalid shader",new float[256],new int[]{1,0,1,0,32774},new float[4]);
                try{renderer.frame(new NativeFramePlan(List.of(invalid),List.of()));throw new AssertionError("Unsupported shader accepted");}catch(IllegalArgumentException|IllegalStateException expected){ }
                require(renderer.stats()[0]==before,"rejected shader changed submission count");
                var valid=new NativeFramePlan.PostDraw("valid",PipelineBenchmark.VERTEX_VK,PipelineBenchmark.FRAGMENT_VK,new float[256],new int[]{1,0,1,0,32774},new float[4]);
                NativeFramePlan plan=new NativeFramePlan(List.of(valid,valid,valid),List.of());for(int i=0;i<4;i++){renderer.waitSlot(renderer.nextSlot());renderer.frame(plan);}renderer.drain();
                BenchmarkDrawAudit.writeNative(renderer,plan,Path.of("build/native-contract-workload.txt"));
                var pixels=renderer.pixels(0);require(pixels.color().length==64*64*4,"native readback size");for(float depth:pixels.depth())require(depth==1f,"post depth clear changed");
                long[] stats=renderer.stats();require(stats[4]==0&&stats[5]==0&&stats[6]==0&&stats[7]==0,"native interop/fallback/copy counters");
                System.out.println("VK_NATIVE_CONTRACT_SMOKE=OK (private targets, external extensions disabled, shared-image rejection, unsupported draw/shader rejection, recovery, both frame slots, actual Draw IDs)");
            }
        }
    }
    private static void require(boolean value,String reason){if(!value)throw new AssertionError(reason);}
}
