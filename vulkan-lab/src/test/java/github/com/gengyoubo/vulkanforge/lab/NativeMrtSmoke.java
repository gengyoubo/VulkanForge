package github.com.gengyoubo.vulkanforge.lab;

import java.nio.*;
import java.nio.file.*;
import java.util.Arrays;
import org.lwjgl.vulkan.VK;

/** Actual MRT/HDR/depth execution and resource lifetime; the launcher forbids GL and GLFW. */
public final class NativeMrtSmoke {
    private static final String VERTEX="#version 330\nin vec3 Position;\nout float unusedFogDistance;\nvoid main(){gl_Position=vec4(Position,1);unusedFogDistance=length(Position);}";
    public static void main(String[] ignored)throws Exception{
        org.lwjgl.system.Configuration.STACK_SIZE.set(4096);
        Path report=Path.of(System.getProperty("vulkanforge.smokeReportDir","build/reports/native-mrt"));Files.createDirectories(report);
        int supported=VK.getInstanceVersionSupported(),api=org.lwjgl.vulkan.VK10.VK_MAKE_VERSION(org.lwjgl.vulkan.VK10.VK_VERSION_MAJOR(supported),org.lwjgl.vulkan.VK10.VK_VERSION_MINOR(supported),0);
        try(var backend=NativeGameBackend.offscreen(report.resolve("backend.txt").toString(),api,Boolean.getBoolean("vulkanforge.requireValidation"))){
            backend.renderTexture(10,32,32,32856,0);backend.renderTexture(11,32,32,34842,0);backend.renderTexture(12,32,32,36012,0);
            backend.framebuffer(50,new int[]{10,11},12,new int[]{1,0},32,32);
            backend.program(1,VERTEX,"#version 330\nlayout(location=0) out vec4 hdr;\nlayout(location=1) out vec4 color;\nvoid main(){hdr=vec4(2.25,.25,4.5,1);color=vec4(.1,.2,.3,.4);}",new Object[]{new Object[]{"Position",0}});
            backend.clear(50,16384|256,new float[]{0,0,0,0},1,new int[]{0,0,32,32});backend.draw(50,packet(1,0));
            pixel(backend.readTextureFloats(11),2.25f,.25f,4.5f,1,.001f,"HDR output location 0");pixel(backend.readTextureFloats(10),.1f,.2f,.3f,.4f,.004f,"Reordered MRT location 1");pixel(backend.readTextureFloats(12),.5f,0,0,1,.0001f,"Shared depth output");
            backend.framebuffer(51,new int[]{10,11},12,new int[]{0,-1,1},32,32);
            backend.program(2,VERTEX,"#version 330\nlayout(location=0) out vec4 color;\nlayout(location=2) out vec4 hdr;\nvoid main(){color=vec4(.5,.4,.3,1);hdr=vec4(3,2,1,.5);}",new Object[]{new Object[]{"Position",0}});
            backend.draw(51,packet(2,0));pixel(backend.readTextureFloats(10),.5f,.4f,.3f,1,.004f,"MRT hole output 0");pixel(backend.readTextureFloats(11),3,2,1,.5f,.001f,"MRT hole output 2");
            backend.renderTexture(14,32,32,32856,0);backend.framebuffer(53,new int[]{14},0,new int[]{0},32,32);
            backend.draw(53,packet(2,0));pixel(backend.readTextureFloats(14),.5f,.4f,.3f,1,.004f,"Disabled fragment output is private");
            backend.draw(51,packet(2,0));pixel(backend.readTextureFloats(11),3,2,1,.5f,.001f,"Re-enabled fragment output survives variant cache");
            // Deleting a texture name must keep the image alive while an FBO/queued draw still references it.
            backend.deleteTexture(10);backend.draw(50,packet(1,0));backend.renderTexture(13,32,32,32856,0);backend.framebuffer(52,new int[]{13},0,new int[]{0},32,32);
            backend.blitAttachments(50,0,52,0,new int[]{0,0,32,32,0,0,32,32},16384,9728);pixel(backend.readTextureFloats(13),.1f,.2f,.3f,.4f,.004f,"Deleted texture retained by FBO");
            backend.framebuffer(60,new int[0],12,new int[0],32,32);backend.program(3,VERTEX,"#version 330\nvoid main(){}",new Object[]{new Object[]{"Position",0}});
            backend.clear(60,256,new float[4],1,new int[]{0,0,32,32});backend.draw(60,packet(3,-.5f));pixel(backend.readTextureFloats(12),.25f,0,0,1,.0001f,"Depth-only target");
            backend.deleteTarget(50);backend.deleteTarget(51);backend.deleteTarget(60);backend.deleteTexture(11);backend.deleteTexture(12);backend.finishOffscreenFrame();backend.beginFrame();
            backend.renderTexture(11,32,32,32856,0);backend.framebuffer(51,new int[]{13,11},0,new int[]{0,1},32,32);backend.draw(51,packet(1,0));pixel(backend.readTextureFloats(13),1,.25f,1,1,.004f,"Pipeline format cache key");
            backend.renderTexture(20,32,32,34842,0);backend.framebuffer(70,new int[]{20},0,new int[]{0},32,32);backend.clear(70,16384,new float[]{2,.25f,.5f,1},1,new int[]{0,0,32,32});backend.generateMipmaps(20);
            backend.program(4,VERTEX,"#version 330\nuniform sampler2D source;\nuniform int factor;\nuniform bool enabled;\nuniform float defaultValue=0.25;\nout vec4 color;\nvoid main(){color=textureLod(source,vec2(.5),4.0);if(enabled)color.r*=float(factor);color.g=defaultValue;}",new Object[]{new Object[]{"Position",0}});
            Object[] mip=packet(4,0);mip[10]=new Object[]{new Object[]{"factor",new float[]{2}},new Object[]{"enabled",new float[]{1}}};mip[11]=new int[]{20};mip=Arrays.copyOf(mip,14);mip[12]=new long[6];mip[13]=new int[]{8};backend.draw(52,mip);pixel(backend.readTextureFloats(13),1,.25f,.5f,1,.004f,"Mip sampling and typed/default uniforms");
            backend.renderTexture(21,32,32,32856,0);backend.copyToTexture(52,0,21,0,0,32,32,0,0);pixel(backend.readTextureFloats(21),1,.25f,.5f,1,.004f,"Framebuffer to texture copy");
            backend.textureSwizzle(20,new int[]{6405,6404,6403,1});backend.draw(52,mip);pixel(backend.readTextureFloats(13),1,.25f,1,1,.004f,"Sampler component swizzle");
            backend.program(5,"#version 330\nin vec3 Position;\nin vec4 constantColor;\nout vec4 tint;\nvoid main(){gl_Position=vec4(Position,1);tint=constantColor;}","#version 330\nin vec4 tint;\nout vec4 color;\nvoid main(){color=tint;}",new Object[]{new Object[]{"Position",0},new Object[]{"constantColor",7}});
            Object[] fixed=Arrays.copyOf(packet(5,0),15);fixed[12]=new long[6];fixed[13]=new int[0];fixed[14]=new Object[]{new Object[]{7,new float[]{.3f,.4f,.5f,.6f}}};backend.draw(52,fixed);pixel(backend.readTextureFloats(13),.3f,.4f,.5f,.6f,.004f,"Constant vertex attribute binding");
            // Multi-draw packets: each sub-draw is one triangle of the quad, through copied indices and resident (terrain) geometry.
            backend.renderTexture(22,32,32,32856,0);backend.framebuffer(72,new int[]{22},0,new int[]{0},32,32);
            backend.program(6,VERTEX,"#version 330\nout vec4 color;\nvoid main(){color=vec4(.6,.2,.8,1);}",new Object[]{new Object[]{"Position",0}});
            backend.clear(72,16384,new float[4],1,new int[]{0,0,32,32});backend.draw(72,multi(6,new long[8]));
            corners(backend.readTextureFloats(22),.6f,.2f,.8f,"Multi-draw with copied indices");
            backend.program(7,"#version 330\nin vec3 Position;\nuniform vec3 ChunkOffset;\nvoid main(){gl_Position=vec4(Position+ChunkOffset,1);}","#version 330\nout vec4 color;\nvoid main(){color=vec4(.2,.8,.4,1);}",new Object[]{new Object[]{"Position",0}});
            backend.clear(72,16384,new float[4],1,new int[]{0,0,32,32});backend.draw(72,multi(7,new long[]{900,1,901,1,0,0,-1,-1}));
            corners(backend.readTextureFloats(22),.2f,.8f,.4f,"Multi-draw with resident geometry");
            // More draws than one submission holds: the frame is split and waited, not failed.
            backend.clear(72,16384,new float[4],1,new int[]{0,0,32,32});for(int i=0;i<16500;i++)backend.draw(72,packet(6,0));
            backend.finishOffscreenFrame();backend.beginFrame();corners(backend.readTextureFloats(22),.6f,.2f,.8f,"Split oversized frame");
            if(backend.diagnostics().get("frameSplits")<1)throw new AssertionError("Oversized frame was not split");
            if(backend.stats()[5]!=0)throw new AssertionError("Vulkan validation errors during MRT execution");
        }
        Files.writeString(report.resolve("result.txt"),"STATUS=OK\nMRT_REORDER=true\nMRT_HOLE=true\nRGBA16F_HDR=true\nSHARED_DEPTH=true\nDEPTH_ONLY=true\nATTACHED_TEXTURE_LIFETIME=true\nPIPELINE_FORMAT_KEY=true\n");
        System.out.println("NATIVE_MRT_SMOKE=OK (actual reordered/holed MRT pixels, HDR values above 1, shared/depth-only targets, image lifetime, no OpenGL, zero validation errors)");
    }
    private static Object[] packet(int program,float z){
        ByteBuffer vertices=ByteBuffer.allocate(48).order(ByteOrder.nativeOrder());for(float[] xy:new float[][]{{-1,-1},{1,-1},{1,1},{-1,1}})vertices.putFloat(xy[0]).putFloat(xy[1]).putFloat(z);
        ByteBuffer indices=ByteBuffer.allocate(12).order(ByteOrder.nativeOrder());for(int index:new int[]{0,1,2,2,3,0})indices.putShort((short)index);
        return new Object[]{program,vertices.array(),indices.array(),5123,6,12,new int[][]{{0,3,5126,0,0,0,12,1}},new int[]{0,1,0,1,0,32774,32774,1,1,515,0,2305,15,6914,0,4,0},new int[]{0,0,32,32,0,0,32,32},new float[2],new Object[0],new int[0]};
    }
    private static Object[] multi(int program,long[] resources){
        Object[] packet=Arrays.copyOf(packet(program,0),19);packet[12]=resources;packet[13]=new int[0];packet[14]=new Object[0];
        packet[15]=new int[]{3,3};packet[16]=new int[]{0,6};packet[17]=new int[]{0,0};packet[18]=new int[][]{null,null};return packet;
    }
    /** Both triangles of the quad: one pixel on each side of its diagonal. */
    private static void corners(float[] pixels,float red,float green,float blue,String label){
        for(int[] xy:new int[][]{{24,6},{6,24}}){int at=(xy[1]*32+xy[0])*4;float[] expected={red,green,blue,1};for(int i=0;i<4;i++)if(Math.abs(pixels[at+i]-expected[i])>.004f)throw new AssertionError(label+" at "+xy[0]+","+xy[1]+" component "+i+": "+pixels[at+i]+" != "+expected[i]);}
    }
    private static void pixel(float[] pixels,float red,float green,float blue,float alpha,float tolerance,String label){int center=(16*32+16)*4;float[] expected={red,green,blue,alpha};for(int i=0;i<4;i++)if(Math.abs(pixels[center+i]-expected[i])>tolerance)throw new AssertionError(label+" component "+i+": "+pixels[center+i]+" != "+expected[i]);}
}
