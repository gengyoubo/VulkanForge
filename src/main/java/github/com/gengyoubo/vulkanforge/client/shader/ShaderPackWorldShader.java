package github.com.gengyoubo.vulkanforge.client.shader;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vertex.VertexFormat;
import github.com.gengyoubo.vulkanforge.shaderpack.ShaderPackRenderPlan;
import java.io.IOException;
import java.util.*;
import java.util.function.ToIntFunction;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.server.packs.resources.ResourceProvider;

/**
 * A pack world program compiled as a game core shader: the game keeps supplying matrices, chunk offset, color modulator,
 * albedo and lightmap per draw, and the graph binds the gbuffer framebuffer, pack uniforms and pack samplers on apply.
 */
final class ShaderPackWorldShader extends ShaderInstance {
    record Sampler(int location,ToIntFunction<RenderTarget> texture){ }
    final ShaderPackRenderPlan.Pass pass;
    final Map<String,ShaderPackPass.Uniform> uniforms=new LinkedHashMap<>();
    final List<Sampler> samplers=new ArrayList<>();
    final int[] blend=new int[5];
    long frame=-1;
    boolean bound,blendChanged;
    private final ShaderPackGraph graph;
    ShaderPackWorldShader(ResourceProvider provider,String name,VertexFormat format,ShaderPackGraph graph,ShaderPackRenderPlan.Pass pass)throws IOException{super(provider,name,format);this.graph=graph;this.pass=pass;}
    @Override public void apply(){super.apply();graph.applyWorld(this);}
    @Override public void clear(){graph.clearWorld(this);super.clear();}
}
