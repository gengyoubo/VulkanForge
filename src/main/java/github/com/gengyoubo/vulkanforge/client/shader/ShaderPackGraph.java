package github.com.gengyoubo.vulkanforge.client.shader;

import com.mojang.blaze3d.pipeline.RenderTarget;
import github.com.gengyoubo.vulkanforge.shaderpack.*;
import java.util.*;
import java.util.regex.Pattern;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/** Compiled color/depth fullscreen graph; unsupported world/shadow requirements fail before hot-switch commit. */
final class ShaderPackGraph implements AutoCloseable {
    private record Pass(ShaderPackPass shader,boolean flip,boolean last){ }
    final ShaderPack pack;
    final ShaderPackOptions options;
    final Map<String,String> values;
    private final Map<String,List<Pass>> dimensions=new HashMap<>();
    private final ShaderPackUniforms globals=new ShaderPackUniforms();
    private ShaderPackQuad quad;
    private ShaderPackTarget[] targets;
    private boolean closed;
    long frames,passes;
    ShaderPackGraph(ShaderPack pack,Map<String,String> selected,int width,int height){
        this.pack=pack;options=new ShaderPackOptions(pack);Map<String,String> merged=options.defaults();merged.putAll(selected);values=Map.copyOf(merged);
        try(ShaderPackState state=new ShaderPackState(4)){
            validateProperties();
            for(String dimension:pack.dimensions()){
                List<Pass> chain=new ArrayList<>();dimensions.put(dimension,chain);
                for(var program:pack.programs(dimension).values())if(enabled(program.name())&&program.phase()!=ShaderPack.Phase.DEFERRED&&program.phase()!=ShaderPack.Phase.COMPOSITE&&program.phase()!=ShaderPack.Phase.FINAL)throw new ShaderPackException(program.name()+": world/shadow/compute program execution is not implemented yet");
                for(var phase:List.of(ShaderPack.Phase.DEFERRED,ShaderPack.Phase.COMPOSITE,ShaderPack.Phase.FINAL))for(var program:pack.passes(dimension,phase)){
                    if(!enabled(program.name()))continue;
                    if(!Arrays.equals(program.drawBuffers(),new int[]{0}))throw new ShaderPackException(program.name()+": multiple/nonzero color targets require MRT support");
                    for(var source:program.stages().values())if(Pattern.compile("\\b(?:colortex0|gcolor)MipmapEnabled\\s*=\\s*true").matcher(options.apply(source.text(),values)).find())throw new ShaderPackException(program.name()+": color mipmap generation is not implemented");
                    ShaderPackPass shader=new ShaderPackPass(program,options,values);boolean flip=Boolean.parseBoolean(pack.properties().getOrDefault("flip."+program.name()+".colortex0","true"));chain.add(new Pass(shader,flip,phase==ShaderPack.Phase.FINAL));
                }
                if(chain.isEmpty())throw new ShaderPackException("No enabled fullscreen programs for dimension "+dimension);
            }
            quad=new ShaderPackQuad();resize(width,height);
        }catch(Throwable error){close();throw error;}
    }
    private boolean enabled(String name){String value=values.getOrDefault("program."+name,pack.properties().getOrDefault("program."+name+".enabled","true"));if(values.containsKey(value))value=values.get(value);if(value.startsWith("!")&&values.containsKey(value.substring(1)))value=Boolean.toString(!Boolean.parseBoolean(values.get(value.substring(1))));if(!value.equals("true")&&!value.equals("false"))throw new ShaderPackException(name+": unsupported program condition "+value);return Boolean.parseBoolean(value);}
    private void validateProperties(){
        for(var entry:pack.properties().entrySet()){
            String key=entry.getKey(),value=entry.getValue();
            if(key.startsWith("program.")||key.startsWith("profile.")||key.startsWith("screen")||key.equals("sliders")||key.startsWith("option."))continue;
            if(key.startsWith("flip.")&&key.endsWith(".colortex0")&&(value.equals("true")||value.equals("false")))continue;
            if(key.startsWith("blend.")&&value.equals("off"))continue;
            throw new ShaderPackException("Unsupported shaders.properties setting: "+key);
        }
    }
    void matrices(org.joml.Matrix4f projection,org.joml.Matrix4f view){globals.matrices(projection,view);}
    private void resize(int width,int height){
        if(width<1||height<1)return;if(targets!=null&&targets[0].width==width&&targets[0].height==height)return;
        ShaderPackTarget[] next=new ShaderPackTarget[2];try{next[0]=new ShaderPackTarget(width,height);next[1]=new ShaderPackTarget(width,height);}catch(Throwable error){for(var target:next)if(target!=null)target.close();throw error;}
        ShaderPackTarget[] old=targets;targets=next;if(old!=null)for(var target:old)target.close();
    }
    void render(RenderTarget scene,String dimension,float partialTick){
        int width=scene.viewWidth,height=scene.viewHeight;if(width<1||height<1)return;
        List<Pass> chain=dimensions.getOrDefault(dimension,dimensions.get(""));if(chain==null||chain.isEmpty())return;
        try(ShaderPackState state=new ShaderPackState(4)){
            resize(width,height);ShaderPackState.fullscreen();glViewport(0,0,width,height);glClearColor(0,0,0,0);
            for(var target:targets){glBindFramebuffer(GL_FRAMEBUFFER,target.framebuffer);glClear(GL_COLOR_BUFFER_BIT);}
            glBindFramebuffer(GL_READ_FRAMEBUFFER,scene.frameBufferId);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,targets[0].framebuffer);glBlitFramebuffer(0,0,width,height,0,0,width,height,GL_COLOR_BUFFER_BIT,GL_NEAREST);
            Map<String,float[]> uniforms=globals.next(partialTick,width,height);int read=0,output=0;
            for(Pass pass:chain){
                output=1-read;glBindFramebuffer(GL_FRAMEBUFFER,targets[output].framebuffer);glUseProgram(pass.shader().program);
                int unit=0;for(String sampler:pass.shader().samplers){int texture=sampler.equals("depthtex0")?scene.getDepthTextureId():targets[read].texture;if(texture<=0)throw new ShaderPackException(pass.shader().name+": scene has no "+sampler);glActiveTexture(GL_TEXTURE0+unit);glBindTexture(GL_TEXTURE_2D,texture);glUniform1i(pass.shader().uniforms.get(sampler).location(),unit++);}
                pass.shader().values(uniforms);quad.draw();passes++;if(pass.flip()||pass.last())read=output;
            }
            glBindFramebuffer(GL_READ_FRAMEBUFFER,targets[read].framebuffer);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,scene.frameBufferId);glBlitFramebuffer(0,0,width,height,0,0,width,height,GL_COLOR_BUFFER_BIT,GL_NEAREST);frames++;
        }
    }
    @Override public void close(){
        if(closed)return;closed=true;
        for(var chain:dimensions.values())for(var pass:chain)pass.shader().close();dimensions.clear();
        if(quad!=null)quad.close();if(targets!=null)for(var target:targets)target.close();targets=null;
    }
}
