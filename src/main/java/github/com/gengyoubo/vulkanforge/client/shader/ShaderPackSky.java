package github.com.gengyoubo.vulkanforge.client.shader;

import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;

/** Full sky coverage behind the vanilla sky plane; it writes color only and leaves sky depth at one. */
final class ShaderPackSky implements AutoCloseable {
    private final VertexBuffer mesh=new VertexBuffer(VertexBuffer.Usage.STATIC);
    ShaderPackSky(){
        var builder=new BufferBuilder(1024);builder.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION);
        float s=16;float[][] corners={{-s,-s,-s},{s,-s,-s},{s,s,-s},{-s,s,-s},{-s,-s,s},{s,-s,s},{s,s,s},{-s,s,s}};
        for(int[] face:new int[][]{{0,1,2,3},{5,4,7,6},{4,0,3,7},{1,5,6,2},{3,2,6,7},{4,5,1,0}})for(int index:face){float[] p=corners[index];builder.vertex(p[0],p[1],p[2]).endVertex();}
        mesh.bind();mesh.upload(builder.end());VertexBuffer.unbind();
    }
    void draw(ShaderInstance shader,Matrix4f model,Matrix4f projection,float[] color){
        if(shader.MODEL_VIEW_MATRIX!=null)shader.MODEL_VIEW_MATRIX.set(model);if(shader.PROJECTION_MATRIX!=null)shader.PROJECTION_MATRIX.set(projection);if(shader.COLOR_MODULATOR!=null)shader.COLOR_MODULATOR.set(color[0],color[1],color[2],1);
        mesh.bind();try{shader.apply();mesh.draw();}finally{shader.clear();VertexBuffer.unbind();}
    }
    @Override public void close(){mesh.close();}
}
