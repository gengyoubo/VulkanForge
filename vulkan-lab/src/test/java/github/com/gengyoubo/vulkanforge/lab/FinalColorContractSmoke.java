package github.com.gengyoubo.vulkanforge.lab;

import dev.vulkanforge.bench.*;
import github.com.gengyoubo.vulkanforge.interop.GlInterop;
import java.nio.*;
import java.nio.file.Path;
import java.util.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;

/** Final-only output must retain ownership on rejection, survive both slots and preserve no-draw contents. */
public final class FinalColorContractSmoke {
    public static void main(String[] ignored) throws Exception {
        org.lwjgl.system.Configuration.STACK_SIZE.set(4096);if(!glfwInit())throw new AssertionError("GLFW");glfwDefaultWindowHints();glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);long hidden=glfwCreateWindow(32,32,"Hidden final-color contracts",0,0);glfwMakeContextCurrent(hidden);org.lwjgl.opengl.GL.createCapabilities();
        try(VulkanContext context=new VulkanContext(VulkanContext.requestedApiVersion(),"",true)) {
            context.initializeInterop(GlInterop.deviceUUID(),false);
            for(boolean early:new boolean[]{false,true}) {
                System.setProperty("bench.releaseEarly",Boolean.toString(early));FinalColorBoundary boundary=new FinalColorBoundary(context,32,32,FinalColorBoundary.Probe.NONE);
                try(NativeWorldRenderer renderer=new NativeWorldRenderer(context,32,32,s->{},boundary)) {
                    Workload.load(Path.of("build/reports/offscreen-performance/matched-scene.bin"),renderer::apply);
                    var draw=new NativeFramePlan.PostDraw("contract",PipelineBenchmark.VERTEX_VK,PipelineBenchmark.FRAGMENT_VK,new float[256],new int[]{1,0,1,0,GL_FUNC_ADD},new float[4]);var plan=new NativeFramePlan(List.of(draw,draw,draw),List.of());
                    for(int frame=0;frame<4;frame++) {
                        int slot=renderer.nextSlot();renderer.waitSlot(slot);renderer.frame(plan);var timing=renderer.waitSlot(slot);require(timing.vertices()==699024,"World hardware vertices");glFinish();
                        boolean rejected=false;try{renderer.pixels(slot);}catch(IllegalStateException expected){rejected=true;}require(rejected,"VK readback accepted a GL-owned image");
                    }
                    long submitted=renderer.stats()[0];int slot=renderer.nextSlot();
                    expectUnsupported(()->renderer.frame(new NativeFramePlan(List.of(draw,draw),List.of())));require(renderer.stats()[0]==submitted,"Unsupported graph submitted work");boundary.texture(slot);
                    expectUnsupported(()->renderer.frame(new NativeFramePlan(List.of(),List.of(new NativeFramePlan.UnsupportedDraw(42,"ENTITY","unsupported")))));boundary.texture(slot);
                    var bad=new NativeFramePlan.PostDraw("bad",PipelineBenchmark.VERTEX_VK,"#version 450\ninvalid shader",new float[256],new int[]{1,0,1,0,GL_FUNC_ADD},new float[4]);boolean rejected=false;
                    try{renderer.frame(new NativeFramePlan(List.of(bad,draw,draw),List.of()));}catch(IllegalArgumentException|IllegalStateException expected){rejected=true;}require(rejected,"Bad shader accepted");require(renderer.stats()[0]==submitted,"Shader rejection changed submission count");boundary.texture(slot);
                    byte[] before=read(boundary.texture(slot));renderer.minimalFrame();var empty=renderer.waitSlot(slot);require(empty.vertices()==0,"Minimal path drew geometry");byte[] after=read(boundary.texture(slot));require(Arrays.equals(before,after),"Minimal path modified output");
                    slot=renderer.nextSlot();renderer.frame(plan);require(renderer.waitSlot(slot).vertices()==699024,"World did not recover after rejection/minimal frame");glFinish();require(glGetError()==GL_NO_ERROR,"GL error");
                }
            }
            require(context.validationErrors()==0,"Vulkan validation error");System.out.println("FINAL_COLOR_CONTRACT_SMOKE=OK (late/early, both slots, unsupported graph/shader rejection before release, no VK readback of GL-owned output, minimal preserves contents, recovery)");
        } finally {glfwDestroyWindow(hidden);glfwTerminate();}
    }
    private static void expectUnsupported(Runnable work){boolean rejected=false;try{work.run();}catch(UnsupportedOperationException expected){rejected=true;}require(rejected,"Unsupported work accepted");}
    private static byte[] read(int texture){int fbo=glGenFramebuffers();glBindFramebuffer(GL_READ_FRAMEBUFFER,fbo);glFramebufferTexture2D(GL_READ_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,texture,0);try(var stack=org.lwjgl.system.MemoryStack.stackPush()){ByteBuffer pixels=stack.malloc(32*32*4);glReadPixels(0,0,32,32,GL_RGBA,GL_UNSIGNED_BYTE,pixels);byte[] bytes=new byte[pixels.remaining()];pixels.get(bytes);return bytes;}finally{glBindFramebuffer(GL_READ_FRAMEBUFFER,0);glDeleteFramebuffers(fbo);}}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
