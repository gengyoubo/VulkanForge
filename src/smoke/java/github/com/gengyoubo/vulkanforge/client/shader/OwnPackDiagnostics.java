package github.com.gengyoubo.vulkanforge.client.shader;

import github.com.gengyoubo.vulkanforge.shaderpack.ShaderPackController;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import static org.lwjgl.opengl.GL30.*;

/** Test-only attachment readback: a colorful sky must not hide missing world geometry. */
public final class OwnPackDiagnostics {
    @SuppressWarnings("unchecked") public static void capture(Path folder)throws Exception{
        capture(folder,null);
    }
    @SuppressWarnings("unchecked") public static void capture(Path folder,Set<Integer> selected)throws Exception{
        Files.createDirectories(folder);
        var controller=(ShaderPackController<?>)field(ShaderPackRuntime.class,null,"controller");
        var graph=(ShaderPackGraph)controller.active();if(graph==null)return;
        var targets=(Map<Integer,ShaderPackTarget[]>)field(ShaderPackGraph.class,graph,"targets");
        StringBuilder report=new StringBuilder();
        try(var state=new ShaderPackState(16)){
            for(var entry:new TreeMap<>(targets).entrySet())for(int side=0;side<2;side++){
                if(selected!=null&&!selected.contains(entry.getKey()))continue;
                var source=entry.getValue()[side];
                try(var target=new ShaderPackTarget(source.width,source.height)){
                    glBindFramebuffer(GL_READ_FRAMEBUFFER,source.framebuffer);glReadBuffer(GL_COLOR_ATTACHMENT0);
                    glBindFramebuffer(GL_DRAW_FRAMEBUFFER,target.framebuffer);glBlitFramebuffer(0,0,source.width,source.height,0,0,target.width,target.height,GL_COLOR_BUFFER_BIT,GL_NEAREST);
                    glBindFramebuffer(GL_READ_FRAMEBUFFER,target.framebuffer);
                    float[] pixels=new float[target.width*target.height*4];glReadPixels(0,0,target.width,target.height,GL_RGBA,GL_FLOAT,pixels);
                    save(folder,"colortex"+entry.getKey()+"-"+side,target.width,target.height,pixels,4,report);
                }
            }
            var scene=Minecraft.getInstance().getMainRenderTarget();glBindFramebuffer(GL_READ_FRAMEBUFFER,scene.frameBufferId);
            float[] depth=new float[scene.width*scene.height];glReadPixels(0,0,scene.width,scene.height,GL_DEPTH_COMPONENT,GL_FLOAT,depth);
            save(folder,"depth",scene.width,scene.height,depth,1,report);
        }
        var shaders=(Map<String,ShaderInstance>)field(ShaderPackGraph.class,graph,"worldShaders");
        for(var entry:new TreeMap<>(shaders).entrySet()){
            var value=entry.getValue();var projection=value.getUniform("ProjMat");
            if(value instanceof ShaderPackWorldShader worldShader&&worldShader.pass.source().phase()!=github.com.gengyoubo.vulkanforge.shaderpack.ShaderPack.Phase.SHADOW&&projection!=null&&Math.abs(projection.getFloatBuffer().get(15))>.0001f)throw new AssertionError("Shadow projection leaked into world program: "+entry.getKey());
            report.append(entry.getKey()).append(" program=").append(entry.getValue().getId()).append('\n');
            for(String name:List.of("ModelViewMat","ProjMat","ChunkOffset")){
                var uniform=entry.getValue().getUniform(name);if(uniform==null)continue;var data=uniform.getFloatBuffer();float[] values=new float[data.limit()];data.duplicate().position(0).get(values);report.append(name).append('=').append(Arrays.toString(values)).append('\n');
            }
        }
        var globals=(Map<String,float[]>)field(ShaderPackGraph.class,graph,"frameUniforms");
        for(String name:List.of("gbufferProjection","gbufferModelView"))report.append(name).append('=').append(Arrays.toString(globals.get(name))).append('\n');
        Files.writeString(folder.resolve("attachments.txt"),report);
    }
    private static Object field(Class<?> owner,Object instance,String name)throws Exception{var field=owner.getDeclaredField(name);field.setAccessible(true);return field.get(instance);}
    private static void save(Path folder,String name,int width,int height,float[] data,int components,StringBuilder report)throws Exception{
        float min=Float.POSITIVE_INFINITY,max=Float.NEGATIVE_INFINITY;int nonFinite=0;var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){
            int offset=(y*width+x)*components,rgb=0;for(int c=0;c<components;c++){float value=data[offset+c];if(!Float.isFinite(value))nonFinite++;else{min=Math.min(min,value);max=Math.max(max,value);}}
            for(int c=0;c<3;c++){float value=data[offset+(components==1?0:c)];rgb=rgb<<8|Math.max(0,Math.min(255,Math.round(value*255)));}image.setRGB(x,height-1-y,rgb);
        }
        javax.imageio.ImageIO.write(image,"png",folder.resolve(name+".png").toFile());report.append(name).append(" min=").append(min).append(" max=").append(max).append(" nonFinite=").append(nonFinite).append('\n');
    }
}
