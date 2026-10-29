package github.com.gengyoubo.vulkanforge.lab;

import java.nio.*;
import java.nio.file.*;
import org.lwjgl.vulkan.VK;

/** Real blended pixels, mixed index widths/offsets, negative base vertex, and uniform boundaries. */
public final class NativeDynamicBatchSmoke {
    public static void main(String[] ignored)throws Exception{
        org.lwjgl.system.Configuration.STACK_SIZE.set(4096);
        Path out=Path.of("build/reports/native-dynamic");Files.createDirectories(out);
        int supported=VK.getInstanceVersionSupported(),api=org.lwjgl.vulkan.VK10.VK_MAKE_VERSION(org.lwjgl.vulkan.VK10.VK_VERSION_MAJOR(supported),org.lwjgl.vulkan.VK10.VK_VERSION_MINOR(supported),0);
        try(var backend=NativeGameBackend.offscreen(out.resolve("backend.txt").toString(),api,Boolean.getBoolean("vulkanforge.requireValidation"))){
            backend.renderTexture(10,32,32,32856,0);backend.framebuffer(20,new int[]{10},0,new int[]{0},32,32);
            backend.program(1,"#version 330\nin vec3 Position;\nin vec4 Color;\nin vec2 UV1;\nin vec3 Normal;\nuniform vec4 ColorModulator;\nout vec4 tint;\nvoid main(){gl_Position=vec4(Position,1);tint=Color*ColorModulator;}","#version 330\nin vec4 tint;\nout vec4 color;\nvoid main(){color=tint;}",new Object[]{new Object[]{"Position",0},new Object[]{"Color",1},new Object[]{"UV1",2},new Object[]{"Normal",3}});
            backend.clear(20,16384,new float[4],1,new int[]{0,0,32,32});backend.draw(20,wideIndices(packet(1,0,false)));backend.draw(20,packet(0,1,true));
            pixel(backend.readTextureFloats(10),.25f,0,.5f,.75f,"Ordered blended dynamic batch");
            boolean legacy=Boolean.getBoolean("vulkanforge.nativeLegacyModSubmission");long merged=backend.diagnostics().get("dynamicMergedDraws");
            if(merged!=(legacy?0:1))throw new AssertionError("Dynamic merge entry not exercised: "+merged);
            backend.clear(20,16384,new float[4],1,new int[]{0,0,32,32});Object[] first=wideIndices(packet(1,0,false)),second=packet(0,1,true);second[10]=new Object[]{new Object[]{"ColorModulator",new float[]{.5f,.5f,.5f,1}}};backend.draw(20,first);backend.draw(20,second);
            pixel(backend.readTextureFloats(10),.25f,0,.25f,.75f,"Uniform boundary preserved");
            backend.clear(20,16384,new float[4],1,new int[]{0,0,32,32});backend.draw(20,packet(1,0,false));backend.draw(20,packet(0,1,true));pixel(backend.readTextureFloats(10),.25f,0,.5f,.75f,"Mixed index widths stay separate");
            if(backend.diagnostics().get("dynamicMergedDraws")!=merged||backend.stats()[1]!=6||backend.stats()[5]!=0)throw new AssertionError("Lost logical draws, crossed a uniform/index-width boundary, or validation errors");
            backend.clear(20,16384,new float[4],1,new int[]{0,0,32,32});Object[] zero=packet(0,1,false);zero[5]=0;int[][] zeroLayout=(int[][])zero[6];for(int[] attribute:zeroLayout)attribute[6]=0;backend.draw(20,zero);backend.draw(20,packet(1,0,false));pixel(backend.readTextureFloats(10),.5f,0,0,.5f,"Zero stride stays on the ordinary draw path");
            if(backend.diagnostics().get("dynamicMergedDraws")!=merged||backend.stats()[1]!=8||backend.stats()[5]!=0)throw new AssertionError("Zero-stride fallback failed");
            backend.finishOffscreenFrame();
            Files.writeString(out.resolve(legacy?"legacy.txt":"batch.txt"),"STATUS=OK\nMERGED="+merged+"\nLOGICAL_DRAWS="+backend.stats()[1]+"\nGPU_DRAWS="+backend.diagnostics().get("dynamicGpuDraws")+"\nVALIDATION_ERRORS="+backend.stats()[5]+"\nPIXELS=PASS\n");
        }
        System.out.println("NATIVE_DYNAMIC_BATCH_SMOKE=OK (blended pixels, mixed indices, negative base vertex, uniform boundary, logical draws preserved)");
    }
    private static Object[] wideIndices(Object[] packet){ByteBuffer source=ByteBuffer.wrap((byte[])packet[2]).order(ByteOrder.nativeOrder()),wide=ByteBuffer.allocate(24).order(ByteOrder.nativeOrder());while(source.hasRemaining())wide.putInt(Short.toUnsignedInt(source.getShort()));packet[2]=wide.array();packet[3]=5125;return packet;}
    private static Object[] packet(float red,float blue,boolean wide){
        ByteBuffer vertices=ByteBuffer.allocate((wide?5:4)*28).order(ByteOrder.nativeOrder());if(wide)vertices.position(28);
        for(float[] xy:new float[][]{{-1,-1},{1,-1},{1,1},{-1,1}})vertices.putFloat(xy[0]).putFloat(xy[1]).putFloat(0).putFloat(red).putFloat(0).putFloat(blue).putFloat(.5f);
        ByteBuffer indices=ByteBuffer.allocate(wide?28:12).order(ByteOrder.nativeOrder());if(wide)indices.putInt(999);
        for(int index:new int[]{0,1,2,2,3,0})if(wide)indices.putInt(index+2);else indices.putShort((short)index);
        return new Object[]{1,vertices.array(),indices.array(),wide?5125:5123,6,28,new int[][]{{0,3,5126,0,0,0,28,1},{1,4,5126,0,0,12,28,1}},new int[]{1,770,771,1,771,32774,32774,0,0,515,0,2305,15,6914,0,4,0},new int[]{0,0,32,32,0,0,32,32},new float[2],new Object[]{new Object[]{"ColorModulator",new float[]{1,1,1,1}}},new int[0],new long[]{1,1,2,1,wide?4:0,wide?-1:0,-1,-1},new int[0],new Object[0]};
    }
    private static void pixel(float[] values,float r,float g,float b,float a,String label){float[] expected={r,g,b,a};for(int i=0;i<4;i++)if(Math.abs(values[(16*32+16)*4+i]-expected[i])>.006f)throw new AssertionError(label+" channel "+i+": "+values[(16*32+16)*4+i]);}
}
