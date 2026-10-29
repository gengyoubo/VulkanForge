package github.com.gengyoubo.vulkanforge.lab;

import java.nio.*;
import java.nio.file.*;
import org.lwjgl.vulkan.VK;

/** Non-terrain resident buffers must retain draw order and survive reuse/deletion beside dynamic batching. */
public final class NativeHotbarResidentSmoke {
    public static void main(String[] ignored)throws Exception{
        org.lwjgl.system.Configuration.STACK_SIZE.set(4096);
        Path out=Path.of("build/reports/native-hotbar-resident");Files.createDirectories(out);
        int supported=VK.getInstanceVersionSupported(),api=org.lwjgl.vulkan.VK10.VK_MAKE_VERSION(org.lwjgl.vulkan.VK10.VK_VERSION_MAJOR(supported),org.lwjgl.vulkan.VK10.VK_VERSION_MINOR(supported),0);
        try(var backend=NativeGameBackend.offscreen(out.resolve("backend.txt").toString(),api,Boolean.getBoolean("vulkanforge.requireValidation"))){
            backend.renderTexture(10,32,32,32856,0);backend.framebuffer(20,new int[]{10},0,new int[]{0},32,32);
            backend.program(1,"#version 330\nin vec3 Position;\nin vec4 Color;\nin vec2 UV1;\nin vec3 Normal;\nout vec4 tint;\nvoid main(){gl_Position=vec4(Position,1);tint=Color;}","#version 330\nin vec4 tint;\nout vec4 color;\nvoid main(){color=tint;}",new Object[]{new Object[]{"Position",0},new Object[]{"Color",1},new Object[]{"UV1",2},new Object[]{"Normal",3}});
            Object[] red=packet(101,1,1,0,0),blue=packet(103,1,0,0,1);
            for(int frame=0;frame<2;frame++){
                backend.clear(20,16384,new float[4],1,new int[]{0,0,32,32});backend.draw(20,red);backend.draw(20,blue);
                pixel(backend.readTextureFloats(10),.25f,0,.5f,.75f);backend.finishOffscreenFrame();
            }
            var stats=backend.diagnostics();
            require(stats.get("residentUploads")==3&&stats.get("residentReuses")==5,"Resident geometry was re-uploaded instead of reused");
            require(stats.get("dynamicMergedDraws")==0&&stats.get("hotbarResidentDraws")==4,"Resident packets entered transient dynamic groups");
            backend.deleteBuffer(101);backend.clear(20,16384,new float[4],1,new int[]{0,0,32,32});backend.draw(20,packet(101,2,0,1,0));
            pixel(backend.readTextureFloats(10),0,.5f,0,.5f);backend.finishOffscreenFrame();
            require(backend.diagnostics().get("residentUploads")==4&&backend.stats()[5]==0,"Resident deletion/rebuild or validation failed");
            Files.writeString(out.resolve("result.txt"),"STATUS=OK\nPIXELS=PASS\nREUSE=PASS\nDELETE_REBUILD=PASS\nDYNAMIC_BOUNDARY=PASS\nVALIDATION_ERRORS="+backend.stats()[5]+"\n");
        }
        System.out.println("NATIVE_HOTBAR_RESIDENT_SMOKE=OK");
    }
    private static Object[] packet(int id,long version,float r,float g,float b){
        ByteBuffer vertices=ByteBuffer.allocate(112).order(ByteOrder.nativeOrder());
        for(float[] xy:new float[][]{{-1,-1},{1,-1},{1,1},{-1,1}})vertices.putFloat(xy[0]).putFloat(xy[1]).putFloat(0).putFloat(r).putFloat(g).putFloat(b).putFloat(.5f);
        ByteBuffer indices=ByteBuffer.allocate(12).order(ByteOrder.nativeOrder());for(int i:new int[]{0,1,2,2,3,0})indices.putShort((short)i);
        return new Object[]{1,vertices.array(),indices.array(),5123,6,28,new int[][]{{0,3,5126,0,0,0,28,id},{1,4,5126,0,0,12,28,id}},new int[]{1,770,771,1,771,32774,32774,0,0,515,0,2305,15,6914,0,4,0},new int[]{0,0,32,32,0,0,32,32},new float[2],new Object[0],new int[0],new long[]{id,version,102,1,0,0,-1,-1},new int[0],new Object[0],null,null,null,null,false,true};
    }
    private static void pixel(float[] values,float r,float g,float b,float a){float[] expected={r,g,b,a};for(int i=0;i<4;i++)require(Math.abs(values[(16*32+16)*4+i]-expected[i])<.006f,"Resident pixel channel "+i+": "+values[(16*32+16)*4+i]);}
    private static void require(boolean value,String reason){if(!value)throw new AssertionError(reason);}
}
