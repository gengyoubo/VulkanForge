package github.com.gengyoubo.vulkanforge.client.shader;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import github.com.gengyoubo.vulkanforge.shaderpack.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToIntFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Executes a pack's plan with colortex ping-pong images, MRT, custom formats, mipmaps, blending, custom/noise textures and
 * expression uniforms. World programs replace the game's core shaders during a level frame and draw into gbuffer images
 * that share the scene depth; deferred passes run between opaque and translucent geometry. Shadow programs are rejected by preflight.
 */
final class ShaderPackGraph implements AutoCloseable {
    private record Pass(ShaderPackRenderPlan.Pass plan,ShaderPackPass shader,List<ToIntFunction<RenderTarget>> bindings,boolean last){ }
    private record Chain(ShaderPackRenderPlan.Dimension plan,List<Pass> prepare,List<Pass> deferred,List<Pass> post,Set<Integer> mipmapped,Map<String,ShaderPackRenderPlan.Pass> shadow){
        List<Pass> all(){List<Pass> result=new ArrayList<>(prepare);result.addAll(deferred);result.addAll(post);return result;}
    }
    private record WorldTarget(ShaderPackRenderPlan.Pass pass,boolean translucent){ }
    /** Units 0-2 belong to the game's Sampler0..2; pack samplers of world programs start after them. */
    private static final int FIRST_WORLD_UNIT=3,GAME_TRACKED_UNITS=12;
    private static final AtomicInteger SERIAL=new AtomicInteger();
    private static final Set<String> ROOT_SETTINGS=Set.of("sliders","iris.features.required","iris.features.optional","clouds","voxelizeLightBlocks");
    private static final Set<String> VALUE_TYPES=Set.of("float","int","bool","vec2","vec3","vec4","ivec2","ivec3","ivec4","mat2","mat3","mat4");
    final ShaderPack pack;
    final ShaderPackOptions options;
    final Map<String,String> values;
    private final ShaderPackRenderPlan plan;
    private final Map<String,Chain> dimensions=new HashMap<>();
    private final Map<String,ShaderPackTexture> images=new HashMap<>();
    private final ShaderPackUniforms globals;
    private final Set<String> available;
    private final Map<Integer,ShaderPackTarget[]> targets=new HashMap<>();
    private final Map<Pass,Integer> framebuffers=new HashMap<>();
    private final Map<WorldTarget,Integer> worldFramebuffers=new HashMap<>();
    private final Map<String,ShaderInstance> worldShaders=new HashMap<>();
    private final List<ShaderPackPass> compiled=new ArrayList<>();
    private final Map<Integer,ShaderPackTarget> shadowColors=new HashMap<>();
    private final Map<ShaderPackRenderPlan.Pass,Integer> shadowFramebuffers=new HashMap<>();
    private int shadowSize,shadowDepth0,shadowDepth1,shadowDepthFramebuffer;
    private static java.lang.reflect.Method renderChunkLayer;
    private ShaderPackQuad quad;
    private String allocated;
    private int allocatedWidth,allocatedHeight,allocatedDepth,depthCopy;
    private boolean closed;
    // Level frame state: valid between beginWorld and render.
    private Chain frameChain;
    private String frameKey,failure;
    private RenderTarget frameScene;
    private Map<String,float[]> frameUniforms=Map.of();
    private long frameSerial;
    private boolean worldFrame,deferredDone;
    private ShaderPackWorldPrograms.Phase phase=ShaderPackWorldPrograms.Phase.SKY;
    long frames,passes,worldDraws;
    ShaderPackGraph(ShaderPack pack,Map<String,String> selected)throws IOException{
        this.pack=pack;options=new ShaderPackOptions(pack);plan=new ShaderPackRenderPlan(pack,selected);values=plan.configuration().values();
        globals=new ShaderPackUniforms(ShaderPackIds.load(pack,plan.configuration(),"item"));
        try(ShaderPackState state=new ShaderPackState(16)){
            validateProperties();
            // Dry run against valid preview inputs: unknown inputs fail before the first world frame.
            Map<String,float[]> preview=ShaderPackUniforms.preview();preview.putAll(new ShaderPackExpressions(plan.configuration().properties()).evaluate(preview,0));
            available=Set.copyOf(preview.keySet());
            try(ShaderPackArchive archive=ShaderPackArchive.open(pack.path())){
                // Fullscreen passes resolve noisetex while they are built, before world resources are bound.
                if(plan.noise().isPresent())image(archive,plan.noise().get());
                for(var entry:plan.dimensions().entrySet()){
                    var dimension=entry.getValue();List<Pass> prepare=new ArrayList<>(),deferred=new ArrayList<>(),post=new ArrayList<>();Set<Integer> mipmapped=new TreeSet<>();
                    for(var step:dimension.chain()){
                        ShaderPackPass shader=new ShaderPackPass(step.source(),options,values,available);compiled.add(shader);List<ToIntFunction<RenderTarget>> bindings=new ArrayList<>();
                        for(String sampler:shader.samplers)bindings.add(binding(archive,dimension,step,sampler));
                        Pass pass=new Pass(step,shader,List.copyOf(bindings),step==dimension.last());mipmapped.addAll(step.mipmaps());
                        (step.source().phase()==ShaderPack.Phase.PREPARE?prepare:step.source().phase()==ShaderPack.Phase.DEFERRED?deferred:post).add(pass);
                    }
                    if(!dimension.chain().isEmpty()||!dimension.world().isEmpty())dimensions.put(entry.getKey(),new Chain(dimension,List.copyOf(prepare),List.copyOf(deferred),List.copyOf(post),Set.copyOf(mipmapped),Map.copyOf(dimension.shadowPrograms())));
                }
                // World programs compile lazily during a frame, without the archive: load their pack textures now.
                for(String stage:List.of("gbuffers","*"))for(String definition:plan.textures().getOrDefault(stage,Map.of()).values())image(archive,definition);
            }
            if(dimensions.isEmpty())throw new ShaderPackException("No enabled programs in "+pack.name());
            quad=new ShaderPackQuad();
        }catch(Throwable error){close();throw error;}
    }
    /** Resolution order matches the reference loaders: stage texture, customTexture, noisetex, colortex, scene depth. */
    private ToIntFunction<RenderTarget> binding(ShaderPackArchive archive,ShaderPackRenderPlan.Dimension dimension,ShaderPackRenderPlan.Pass step,String sampler)throws IOException{
        var custom=plan.texture(step.stage(),sampler);
        if(custom.isPresent()){ShaderPackTexture image=image(archive,custom.get());return scene->image.texture;}
        if(sampler.equals("noisetex")){ShaderPackTexture image=noise(dimension);return scene->image.texture;}
        int index=ShaderPackRenderPlan.colorIndex(sampler);
        if(index>=0){if(!dimension.colors().containsKey(index))throw new ShaderPackException(step.source().name()+": no image for "+sampler);boolean alternate=step.alternate().contains(index);return scene->targets.get(index)[alternate?1:0].texture;}
        // depthtex0 is the final scene depth. With world programs depthtex1/2 are the depth before translucent geometry;
        // without them every view is the vanilla depth after translucents and hand.
        if(sampler.equals("depthtex0")||sampler.matches("depthtex[12]")&&dimension.world().isEmpty())return scene->{int depth=scene.getDepthTextureId();if(depth<=0)throw new ShaderPackException("Scene has no depth texture");return depth;};
        if(sampler.matches("depthtex[12]"))return scene->depthCopy;
        var shadow=shadowBinding(dimension,step.source().name(),sampler);if(shadow!=null)return shadow;
        throw new ShaderPackException(step.source().name()+": unavailable input texture "+sampler);
    }
    /** shadowtex0/1 (and the legacy shadow/watershadow names) and shadowcolor0/1; null for other samplers. */
    private ToIntFunction<RenderTarget> shadowBinding(ShaderPackRenderPlan.Dimension dimension,String program,String sampler){
        boolean depth=sampler.matches("shadowtex[01]")||sampler.equals("shadow")||sampler.equals("watershadow");int color=ShaderPackRenderPlan.shadowColorIndex(sampler);
        if(!depth&&color<0)return null;
        if(dimension.shadowPrograms().isEmpty()||dimension.world().isEmpty())throw new ShaderPackException(program+": "+sampler+" requires an enabled shadow program");
        if(depth){boolean translucent=!sampler.equals("shadowtex1");return scene->translucent?shadowDepth0:shadowDepth1;}
        if(!dimension.shadowColors().containsKey(color))throw new ShaderPackException(program+": no image for "+sampler);
        return scene->shadowColors.get(color).texture;
    }
    private ShaderPackTexture noise(ShaderPackRenderPlan.Dimension dimension){
        if(plan.noise().isPresent())return Objects.requireNonNull(images.get(plan.noise().get()),"noise texture");
        return images.computeIfAbsent("noise:"+dimension.noiseResolution(),unused->new ShaderPackTexture(ShaderPackTextures.noise(dimension.noiseResolution())));
    }
    private ShaderPackTexture image(ShaderPackArchive archive,String definition)throws IOException{
        ShaderPackTexture loaded=images.get(definition);if(loaded!=null)return loaded;
        ShaderPackTextures.Image image;
        if(ShaderPackTextures.resource(definition)){
            var resources=Minecraft.getInstance().getResourceManager();var location=ResourceLocation.tryParse(definition.strip());if(location==null)throw new ShaderPackException("Invalid game texture: "+definition);
            var resource=resources.getResource(location).orElseThrow(()->new ShaderPackException("Missing game texture: "+definition));var meta=resources.getResource(location.withSuffix(".mcmeta"));
            byte[] png;try(var input=resource.open()){png=input.readAllBytes();}byte[] mcmeta=null;if(meta.isPresent())try(var input=meta.get().open()){mcmeta=input.readAllBytes();}
            image=ShaderPackTextures.decode(png,mcmeta,definition);
        }else image=ShaderPackTextures.load(archive,definition);
        ShaderPackTexture texture=new ShaderPackTexture(image);images.put(definition,texture);return texture;
    }
    private void validateProperties(){
        Set<String> post=new HashSet<>(),world=new HashSet<>();for(var dimension:plan.dimensions().values()){for(var step:dimension.chain())post.add(step.source().name());world.addAll(dimension.world().keySet());}
        for(String key:plan.configuration().properties().keySet()){
            if(ROOT_SETTINGS.contains(key)||ShaderPackSupport.RENDERER_SETTINGS.contains(key)||key.startsWith("screen")||key.startsWith("program.")||key.startsWith("profile.")||key.startsWith("option.")||key.startsWith("version."))continue;
            if(key.startsWith("uniform.")||key.startsWith("variable.")||key.startsWith("flip.")||key.startsWith("size.buffer.")||key.startsWith("texture.")||key.startsWith("customTexture."))continue;
            // Shadow settings are inert: preflight rejects every pack that enables a shadow program.
            if(key.startsWith("shadow"))continue;
            String[] parts=key.split("\\.");
            if(parts.length>=2&&Set.of("blend","scale","alphaTest").contains(parts[0])){
                String program=parts[1];
                if(world.contains(program)&&!parts[0].equals("scale"))continue;
                if(post.contains(program)&&!parts[0].equals("alphaTest"))continue;
                // Settings of programs the pack does not enable (or that this game has no stage for) never apply.
                if(!world.contains(program)&&!post.contains(program)&&(program.startsWith("gbuffers_")||program.startsWith("shadow")||program.startsWith("dh_")))continue;
            }
            throw new ShaderPackException("Unsupported shaders.properties setting: "+key);
        }
    }
    String failure(){return failure;}
    boolean world(){for(var chain:dimensions.values())if(!chain.plan().world().isEmpty())return true;return false;}
    /** Whether any dimension replaces chunk terrain shaders, which a third-party chunk renderer would bypass. */
    boolean terrain(){for(var chain:dimensions.values())if(ShaderPackWorldPrograms.resolve("rendertype_solid",ShaderPackWorldPrograms.Phase.TERRAIN,chain.plan().world().keySet()).isPresent())return true;return false;}
    private ShaderPackTarget side(int index,boolean alternate){return targets.get(index)[alternate?1:0];}
    private void allocate(String key,Chain chain,RenderTarget scene){
        int width=scene.viewWidth,height=scene.viewHeight,depth=scene.getDepthTextureId();
        if(key.equals(allocated)&&width==allocatedWidth&&height==allocatedHeight&&depth==allocatedDepth)return;
        release();allocated=key;allocatedWidth=width;allocatedHeight=height;allocatedDepth=depth;
        for(var color:chain.plan().colors().values()){
            ShaderPackTarget[] pair=new ShaderPackTarget[2];targets.put(color.index(),pair);boolean mipmapped=chain.mipmapped().contains(color.index())&&!color.integer();
            for(int side=0;side<2;side++){
                pair[side]=new ShaderPackTarget(color.width(width),color.height(height),color.format(),mipmapped);pair[side].clear(color.clearColor());
                // Allocates the level chain so a mipmapped image is complete before its first regeneration.
                if(mipmapped){glBindTexture(GL_TEXTURE_2D,pair[side].texture);glGenerateMipmap(GL_TEXTURE_2D);}
            }
        }
        for(Pass pass:chain.all()){
            if(pass.last())continue;int[] outputs=pass.plan().outputs();int framebuffer=glGenFramebuffers();framebuffers.put(pass,framebuffer);glBindFramebuffer(GL_FRAMEBUFFER,framebuffer);
            for(int i=0;i<outputs.length;i++)glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0+i,GL_TEXTURE_2D,side(outputs[i],!pass.plan().alternate().contains(outputs[i])).texture,0);
            drawBuffers(outputs.length);if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new ShaderPackException(pass.shader().name+": incomplete output framebuffer "+Arrays.toString(outputs));
        }
        if(!chain.plan().world().isEmpty()){
            depthCopy=depthTexture(width,height,false);
            if(!chain.shadow().isEmpty()){
                var dimension=chain.plan();boolean[] filtering=dimension.shadowFiltering();shadowSize=dimension.shadowResolution();
                shadowDepth0=depthTexture(shadowSize,shadowSize,filtering[0]);shadowDepth1=depthTexture(shadowSize,shadowSize,filtering[1]);
                for(var color:dimension.shadowColors().values())shadowColors.put(color.index(),new ShaderPackTarget(shadowSize,shadowSize,color.format(),false));
                shadowDepthFramebuffer=glGenFramebuffers();glBindFramebuffer(GL_FRAMEBUFFER,shadowDepthFramebuffer);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,shadowDepth0,0);glDrawBuffer(GL_NONE);glReadBuffer(GL_NONE);
                if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new ShaderPackException("Incomplete shadow depth framebuffer");
            }
        }
    }
    /** Same format as the game's depth attachment, so glCopyTexSubImage2D can snapshot it; {@code compare} enables sampler2DShadow lookups. */
    private static int depthTexture(int width,int height,boolean compare){
        int texture=glGenTextures();glBindTexture(GL_TEXTURE_2D,texture);glTexImage2D(GL_TEXTURE_2D,0,GL_DEPTH_COMPONENT,width,height,0,GL_DEPTH_COMPONENT,GL_FLOAT,(ByteBuffer)null);
        int filter=compare?GL_LINEAR:GL_NEAREST;glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,filter);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,filter);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_COMPARE_MODE,compare?GL_COMPARE_REF_TO_TEXTURE:GL_NONE);if(compare)glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_COMPARE_FUNC,GL_LEQUAL);
        return texture;
    }
    private int shadowFramebuffer(ShaderPackRenderPlan.Pass pass){
        int[] outputs=pass.outputs();int framebuffer=glGenFramebuffers();glBindFramebuffer(GL_FRAMEBUFFER,framebuffer);
        for(int i=0;i<outputs.length;i++)glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0+i,GL_TEXTURE_2D,shadowColors.get(outputs[i]).texture,0);
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,shadowDepth0,0);drawBuffers(outputs.length);
        if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE){glDeleteFramebuffers(framebuffer);throw new ShaderPackException(pass.source().name()+": incomplete shadow framebuffer "+Arrays.toString(outputs));}
        return framebuffer;
    }
    /**
     * Shadow-map pass between sky and terrain: the chunk layers are drawn again from the light into shadowtex0/1 and shadowcolor0/1.
     * Casters are limited to the chunks culled for the player camera, and entities are not drawn (both reported as approximations).
     */
    void shadow(net.minecraft.client.renderer.LevelRenderer renderer){
        if(!worldFrame||frameChain.shadow().isEmpty())return;
        int[] viewport=new int[4];glGetIntegerv(GL_VIEWPORT,viewport);int draw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING),read=glGetInteger(GL_READ_FRAMEBUFFER_BINDING),texture=glGetInteger(GL_TEXTURE_BINDING_2D);boolean depthMask=glGetBoolean(GL_DEPTH_WRITEMASK);
        var previous=phase;
        try{
            for(var color:frameChain.plan().shadowColors().values())if(color.clear())shadowColors.get(color.index()).clear(color.clearColor());
            glBindFramebuffer(GL_FRAMEBUFFER,shadowDepthFramebuffer);glDepthMask(true);glClearDepth(1);glClear(GL_DEPTH_BUFFER_BIT);glDepthMask(depthMask);
            glViewport(0,0,shadowSize,shadowSize);phase=ShaderPackWorldPrograms.Phase.SHADOW;
            var camera=Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();var pose=new com.mojang.blaze3d.vertex.PoseStack();pose.last().pose().set(globals.shadowView);pose.last().normal().set(new org.joml.Matrix3f(globals.shadowView));Matrix4f projection=new Matrix4f(globals.shadowProjection);
            layer(renderer,net.minecraft.client.renderer.RenderType.solid(),"rendertype_solid",pose,camera,projection);
            layer(renderer,net.minecraft.client.renderer.RenderType.cutoutMipped(),"rendertype_cutout_mipped",pose,camera,projection);
            layer(renderer,net.minecraft.client.renderer.RenderType.cutout(),"rendertype_cutout",pose,camera,projection);
            // shadowtex1 keeps only opaque casters; translucent casters then go into shadowtex0.
            glBindFramebuffer(GL_READ_FRAMEBUFFER,shadowDepthFramebuffer);glBindTexture(GL_TEXTURE_2D,shadowDepth1);glCopyTexSubImage2D(GL_TEXTURE_2D,0,0,0,0,0,shadowSize,shadowSize);
            layer(renderer,net.minecraft.client.renderer.RenderType.translucent(),"rendertype_translucent",pose,camera,projection);
        }catch(Throwable error){fail(error);}
        finally{phase=previous;glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,draw);glBindFramebuffer(GL_READ_FRAMEBUFFER,read);glBindTexture(GL_TEXTURE_2D,texture);}
    }
    private void layer(net.minecraft.client.renderer.LevelRenderer renderer,net.minecraft.client.renderer.RenderType layer,String shader,com.mojang.blaze3d.vertex.PoseStack pose,net.minecraft.world.phys.Vec3 camera,Matrix4f projection)throws ReflectiveOperationException{
        // A layer no shadow program covers is not drawn into the shadow map.
        if(ShaderPackWorldPrograms.resolve(shader,ShaderPackWorldPrograms.Phase.SHADOW,frameChain.shadow().keySet()).isEmpty())return;
        if(renderChunkLayer==null)renderChunkLayer=net.minecraftforge.fml.util.ObfuscationReflectionHelper.findMethod(net.minecraft.client.renderer.LevelRenderer.class,"m_172993_",net.minecraft.client.renderer.RenderType.class,com.mojang.blaze3d.vertex.PoseStack.class,double.class,double.class,double.class,Matrix4f.class);
        renderChunkLayer.invoke(renderer,layer,pose,camera.x,camera.y,camera.z,projection);
    }
    private static void drawBuffers(int count){int[] buffers=new int[count];for(int i=0;i<count;i++)buffers[i]=GL_COLOR_ATTACHMENT0+i;glDrawBuffers(buffers);}
    private void release(){
        for(int framebuffer:framebuffers.values())glDeleteFramebuffers(framebuffer);framebuffers.clear();
        for(int framebuffer:worldFramebuffers.values())glDeleteFramebuffers(framebuffer);worldFramebuffers.clear();
        if(depthCopy!=0){glDeleteTextures(depthCopy);depthCopy=0;}
        for(int framebuffer:shadowFramebuffers.values())glDeleteFramebuffers(framebuffer);shadowFramebuffers.clear();
        if(shadowDepthFramebuffer!=0){glDeleteFramebuffers(shadowDepthFramebuffer);shadowDepthFramebuffer=0;}
        for(int texture:new int[]{shadowDepth0,shadowDepth1})if(texture!=0)glDeleteTextures(texture);shadowDepth0=shadowDepth1=0;
        for(var target:shadowColors.values())target.close();shadowColors.clear();
        for(var pair:targets.values())for(var target:pair)if(target!=null)target.close();targets.clear();allocated=null;
    }
    private Map<String,float[]> uniforms(float partialTick,int width,int height){Map<String,float[]> uniforms=globals.next(partialTick,width,height);uniforms.putAll(plan.expressions().evaluate(uniforms,uniforms.get("frameTime")[0]));return uniforms;}
    /** Start of LevelRenderer.renderLevel: matrices for every pack; for world programs also clears, prepare passes and routing. */
    boolean beginWorld(RenderTarget scene,String dimension,float partialTick,Matrix4f projection,Matrix4f view){
        globals.matrices(projection,view);worldFrame=false;
        String key=dimensions.containsKey(dimension)?dimension:"";Chain chain=dimensions.get(key);
        if(chain==null||chain.plan().world().isEmpty()||failure!=null||scene.viewWidth<1||scene.viewHeight<1)return false;
        if(Minecraft.useShaderTransparency())throw new ShaderPackException("Fabulous graphics cannot be combined with shader pack world programs; select Fancy or Fast graphics");
        try(ShaderPackState state=new ShaderPackState(16)){
            allocate(key,chain,scene);ShaderPackState.fullscreen();
            for(var color:chain.plan().colors().values())if(color.clear())for(var target:targets.get(color.index()))target.clear(color.clearColor());
            globals.celestial(chain.plan().sunRotation(),chain.plan().shadowInterval(),chain.plan().shadowDistance());frameUniforms=uniforms(partialTick,scene.viewWidth,scene.viewHeight);run(chain,chain.prepare(),scene,frameUniforms);
        }
        frameChain=chain;frameKey=key;frameScene=scene;frameSerial++;deferredDone=false;phase=ShaderPackWorldPrograms.Phase.SKY;worldFrame=true;return true;
    }
    void phase(ShaderPackWorldPrograms.Phase next){if(worldFrame)phase=next;}
    /** After opaque geometry: snapshot depth for depthtex1/2, run deferred passes, and route translucent geometry to the post-deferred images. */
    void deferred(){
        if(!worldFrame||deferredDone)return;deferredDone=true;
        try(ShaderPackState state=new ShaderPackState(16)){
            glBindFramebuffer(GL_READ_FRAMEBUFFER,frameScene.frameBufferId);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,depthCopy);glCopyTexSubImage2D(GL_TEXTURE_2D,0,0,0,0,0,frameScene.viewWidth,frameScene.viewHeight);
            ShaderPackState.fullscreen();run(frameChain,frameChain.deferred(),frameScene,frameUniforms);
        }
    }
    /** Game core shader → pack world program for the current phase; anything unmapped keeps the vanilla shader. */
    ShaderInstance shader(ShaderInstance vanilla){
        if(!worldFrame||vanilla==null||failure!=null)return vanilla;
        try{
            String name=vanilla.getName();
            if(name.equals("position_tex_color_normal")&&"off".equals(plan.configuration().properties().get("clouds"))){ShaderInstance hidden=worldShaders.get("hidden|"+name);if(hidden==null){hidden=hidden(vanilla);worldShaders.put("hidden|"+name,hidden);}return hidden;}
            var programs=phase==ShaderPackWorldPrograms.Phase.SHADOW?frameChain.shadow():frameChain.plan().world();
            var program=ShaderPackWorldPrograms.resolve(name,phase,programs.keySet());if(program.isEmpty())return vanilla;
            String key=frameKey+"|"+program.get()+"|"+name;ShaderInstance cached=worldShaders.get(key);if(cached!=null)return cached;
            // Lazy world compilation can allocate normal/specular textures while Minecraft's atlas is bound.
            // Restore the raw bindings so the engine's texture cache still describes the actual device state.
            try(ShaderPackState state=new ShaderPackState(16)){ShaderInstance created=create(programs.get(program.get()),vanilla);worldShaders.put(key,created);return created;}
        }catch(Throwable error){fail(error);return vanilla;}
    }
    private void fail(Throwable error){Throwable cause=error;while(cause.getCause()!=null&&cause.getCause()!=cause)cause=cause.getCause();failure=Objects.requireNonNullElse(error.getMessage(),cause.toString());worldFrame=false;}
    private static ResourceProvider provider(Map<String,String> files){
        return location->{String text=files.get(location.getPath());if(text==null)return Optional.empty();byte[] data=text.getBytes(StandardCharsets.UTF_8);return Optional.of(new Resource(Minecraft.getInstance().getVanillaPackResources(),()->new ByteArrayInputStream(data)));};
    }
    private ShaderInstance create(ShaderPackRenderPlan.Pass pass,ShaderInstance vanilla)throws IOException{
        var format=vanilla.getVertexFormat();// Only terrain shaders receive a chunk offset; elsewhere the position is already camera-relative.
        var world=ShaderPackGlsl.world(pass.source(),options,values,Set.copyOf(format.getElementAttributeNames()),vanilla.CHUNK_OFFSET!=null,pass.alphaTest());
        String name="vulkanforge_pack_"+SERIAL.incrementAndGet();
        StringBuilder json=new StringBuilder("{\"vertex\":\"").append(name).append("\",\"fragment\":\"").append(name).append("\",\"samplers\":[");
        json.append(String.join(",",world.engineSamplers().stream().map(sampler->"{\"name\":\""+sampler+"\"}").toList())).append("],\"uniforms\":[");
        List<String> uniforms=new ArrayList<>();
        for(String uniform:world.engineUniforms())uniforms.add(switch(uniform){
            case "ChunkOffset"->"{\"name\":\"ChunkOffset\",\"type\":\"float\",\"count\":3,\"values\":[0,0,0]}";
            case "ColorModulator"->"{\"name\":\"ColorModulator\",\"type\":\"float\",\"count\":4,\"values\":[1,1,1,1]}";
            default->"{\"name\":\""+uniform+"\",\"type\":\"matrix4x4\",\"count\":16,\"values\":[1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1]}";
        });
        json.append(String.join(",",uniforms)).append("]}");
        ShaderPackWorldShader shader;
        try{shader=new ShaderPackWorldShader(provider(Map.of("shaders/core/"+name+".json",json.toString(),"shaders/core/"+name+".vsh",world.vertex(),"shaders/core/"+name+".fsh",world.fragment())),name,format,this,pass);}
        catch(IOException|RuntimeException error){dump(pass.source().name(),vanilla.getName(),world);throw new ShaderPackException(pass.source().name()+" ("+vanilla.getName()+"): "+error.getMessage(),error);}
        try{
            int id=shader.getId();
            for(var entry:world.uniforms().entrySet()){
                int location=glGetUniformLocation(id,entry.getKey());if(location<0)continue;
                if(!available.contains(entry.getKey())){if(world.optional().contains(entry.getKey()))continue;throw new ShaderPackException(pass.source().name()+": unsupported frame uniform "+entry.getKey());}
                if(!VALUE_TYPES.contains(entry.getValue()))throw new ShaderPackException(pass.source().name()+": unsupported uniform type "+entry.getValue()+" "+entry.getKey());
                shader.uniforms.put(entry.getKey(),new ShaderPackPass.Uniform(entry.getValue(),location));
            }
            for(String sampler:world.samplers()){int location=glGetUniformLocation(id,sampler);if(location>=0)shader.samplers.add(new ShaderPackWorldShader.Sampler(location,worldBinding(pass,sampler)));}
            if(FIRST_WORLD_UNIT+shader.samplers.size()>32)throw new ShaderPackException(pass.source().name()+": too many samplers");
            return shader;
        }catch(Throwable error){shader.close();throw error;}
    }
    /** Writes generated sources of a failed world program next to the compatibility report. */
    private static void dump(String program,String vanilla,ShaderPackGlsl.World world){
        try{var folder=Minecraft.getInstance().gameDirectory.toPath().resolve("logs/vulkanforge/shader-pack-failed");Files.createDirectories(folder);Files.writeString(folder.resolve(program+"-"+vanilla+".vsh"),world.vertex());Files.writeString(folder.resolve(program+"-"+vanilla+".fsh"),world.fragment());}catch(IOException ignored){}
    }
    private ToIntFunction<RenderTarget> worldBinding(ShaderPackRenderPlan.Pass pass,String sampler){
        var custom=plan.texture("gbuffers",sampler);
        if(custom.isPresent()){ShaderPackTexture image=Objects.requireNonNull(images.get(custom.get()),custom.get());return scene->image.texture;}
        if(sampler.equals("noisetex")){ShaderPackTexture image=noise(frameChain.plan());return scene->image.texture;}
        int index=ShaderPackRenderPlan.colorIndex(sampler);
        if(index>=0){if(!frameChain.plan().colors().containsKey(index))throw new ShaderPackException(pass.source().name()+": no image for "+sampler);return scene->side(index,(deferredDone?frameChain.plan().translucent():frameChain.plan().opaque()).contains(index)).texture;}
        // The live depth attachment cannot be sampled while it is written: world programs read the pre-translucent snapshot.
        if(sampler.matches("depthtex[0-2]"))return scene->depthCopy;
        if(sampler.equals("normals")||sampler.equals("specular")){
            // Without a PBR resource pack: flat tangent-space normal, and no specular data.
            byte[] texel=sampler.equals("normals")?new byte[]{(byte)128,(byte)128,(byte)255,(byte)255}:new byte[4];
            ShaderPackTexture image=images.computeIfAbsent("default:"+sampler,unused->new ShaderPackTexture(new ShaderPackTextures.Image(1,1,texel,false,false)));return scene->image.texture;
        }
        var shadow=shadowBinding(frameChain.plan(),pass.source().name(),sampler);if(shadow!=null)return shadow;
        throw new ShaderPackException(pass.source().name()+": unavailable input texture "+sampler);
    }
    /** Clouds disabled by the pack: a shader that places every vertex outside the clip volume. */
    private ShaderInstance hidden(ShaderInstance vanilla)throws IOException{
        String name="vulkanforge_pack_"+SERIAL.incrementAndGet();
        return new ShaderInstance(provider(Map.of("shaders/core/"+name+".json","{\"vertex\":\""+name+"\",\"fragment\":\""+name+"\",\"samplers\":[],\"uniforms\":[]}","shaders/core/"+name+".vsh","#version 150\nvoid main(){gl_Position=vec4(2.0,2.0,2.0,1.0);}\n","shaders/core/"+name+".fsh","#version 150\nout vec4 color;\nvoid main(){discard;}\n")),name,vanilla.getVertexFormat());
    }
    /** Runs inside the game's draw call: errors are recorded and the pack is retired at the frame boundary, never thrown into the game. */
    void applyWorld(ShaderPackWorldShader shader){
        if(!worldFrame)return;
        try{
            var pass=shader.pass;int framebuffer=pass.source().phase()==ShaderPack.Phase.SHADOW?shadowFramebuffers.computeIfAbsent(pass,this::shadowFramebuffer):worldFramebuffers.computeIfAbsent(new WorldTarget(pass,deferredDone),this::worldFramebuffer);
            glBindFramebuffer(GL_FRAMEBUFFER,framebuffer);drawBuffers(pass.outputs().length);
            int previous=GlStateManager._getActiveTexture();
            for(int i=0;i<shader.samplers.size();i++){
                int unit=FIRST_WORLD_UNIT+i,texture=shader.samplers.get(i).texture().applyAsInt(frameScene);
                // The game tracks units below 12 in its own cache, so those go through it.
                if(unit<GAME_TRACKED_UNITS){GlStateManager._activeTexture(GL_TEXTURE0+unit);GlStateManager._bindTexture(texture);}else{glActiveTexture(GL_TEXTURE0+unit);glBindTexture(GL_TEXTURE_2D,texture);}
            }
            GlStateManager._activeTexture(previous);glActiveTexture(previous);
            if(!shader.bound){shader.bound=true;for(int i=0;i<shader.samplers.size();i++)glUniform1i(shader.samplers.get(i).location(),FIRST_WORLD_UNIT+i);}
            if(shader.frame!=frameSerial){shader.frame=frameSerial;try(MemoryStack stack=MemoryStack.stackPush()){for(var entry:shader.uniforms.entrySet()){float[] data=frameUniforms.get(entry.getKey());if(data==null)throw new ShaderPackException(pass.source().name()+": no value for uniform "+entry.getKey());ShaderPackPass.upload(entry.getValue(),data,stack);}}}
            if(pass.blend()!=null||!pass.blendOff().isEmpty()){
                // The game caches blend state; the exact raw state is restored in clear().
                shader.blendChanged=true;shader.blend[0]=glIsEnabled(GL_BLEND)?1:0;shader.blend[1]=glGetInteger(GL_BLEND_SRC_RGB);shader.blend[2]=glGetInteger(GL_BLEND_DST_RGB);shader.blend[3]=glGetInteger(GL_BLEND_SRC_ALPHA);shader.blend[4]=glGetInteger(GL_BLEND_DST_ALPHA);
                if(pass.blend()!=null){glEnable(GL_BLEND);glBlendFuncSeparate(factor(pass.blend().source()),factor(pass.blend().destination()),factor(pass.blend().sourceAlpha()),factor(pass.blend().destinationAlpha()));}
                int[] outputs=pass.outputs();for(int i=0;i<outputs.length;i++)if(pass.blendOff().contains(outputs[i]))glDisablei(GL_BLEND,i);
            }
            worldDraws++;
        }catch(Throwable error){fail(error);}
    }
    void clearWorld(ShaderPackWorldShader shader){
        if(!shader.blendChanged)return;shader.blendChanged=false;
        if(shader.blend[0]==1)glEnable(GL_BLEND);else glDisable(GL_BLEND);glBlendFuncSeparate(shader.blend[1],shader.blend[2],shader.blend[3],shader.blend[4]);
    }
    private int worldFramebuffer(WorldTarget target){
        Set<Integer> state=target.translucent()?frameChain.plan().translucent():frameChain.plan().opaque();int[] outputs=target.pass().outputs();
        int framebuffer=glGenFramebuffers();glBindFramebuffer(GL_FRAMEBUFFER,framebuffer);
        for(int i=0;i<outputs.length;i++)glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0+i,GL_TEXTURE_2D,side(outputs[i],state.contains(outputs[i])).texture,0);
        // Shares the scene's depth image: vanilla depth testing, clears and later depthtex reads all see world program depth.
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,frameScene.getDepthTextureId(),0);drawBuffers(outputs.length);
        if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE){glDeleteFramebuffers(framebuffer);throw new ShaderPackException(target.pass().source().name()+": incomplete gbuffer framebuffer "+Arrays.toString(outputs));}
        return framebuffer;
    }
    private void run(Chain chain,List<Pass> list,RenderTarget scene,Map<String,float[]> uniforms){
        int width=scene.viewWidth,height=scene.viewHeight;
        for(Pass pass:list){
            var step=pass.plan();
            for(int index:step.mipmaps())if(!chain.plan().colors().get(index).integer()){glBindTexture(GL_TEXTURE_2D,side(index,step.alternate().contains(index)).texture);glGenerateMipmap(GL_TEXTURE_2D);}
            int targetWidth=width,targetHeight=height;
            if(pass.last()){glBindFramebuffer(GL_FRAMEBUFFER,scene.frameBufferId);glDrawBuffers(GL_COLOR_ATTACHMENT0);}
            else{glBindFramebuffer(GL_FRAMEBUFFER,framebuffers.get(pass));drawBuffers(step.outputs().length);ShaderPackTarget first=side(step.outputs()[0],false);targetWidth=first.width;targetHeight=first.height;}
            float[] viewport=step.viewport();glViewport(Math.round(viewport[1]*targetWidth),Math.round(viewport[2]*targetHeight),Math.max(1,Math.round(viewport[0]*targetWidth)),Math.max(1,Math.round(viewport[0]*targetHeight)));
            if(step.blend()!=null){
                glEnable(GL_BLEND);glBlendFuncSeparate(factor(step.blend().source()),factor(step.blend().destination()),factor(step.blend().sourceAlpha()),factor(step.blend().destinationAlpha()));
                int[] outputs=step.outputs();for(int i=0;i<outputs.length;i++)if(step.blendOff().contains(outputs[i]))glDisablei(GL_BLEND,i);
            }
            glUseProgram(pass.shader().program);
            for(int unit=0;unit<pass.bindings().size();unit++){glActiveTexture(GL_TEXTURE0+unit);glBindTexture(GL_TEXTURE_2D,pass.bindings().get(unit).applyAsInt(scene));glUniform1i(pass.shader().uniforms.get(pass.shader().samplers.get(unit)).location(),unit);}
            glActiveTexture(GL_TEXTURE0);pass.shader().values(uniforms);quad.draw();passes++;
            if(step.blend()!=null)glDisable(GL_BLEND);
        }
    }
    private void finish(Chain chain,RenderTarget scene){
        Set<Integer> flipped=chain.plan().flipped();
        if(chain.plan().last()==null&&targets.containsKey(0))blit(side(0,flipped.contains(0)).framebuffer,side(0,false).width,side(0,false).height,scene.frameBufferId,scene.viewWidth,scene.viewHeight);
        // Persistent buffers left on their alternate image are copied back so the next frame reads them from main.
        for(int index:flipped){var color=chain.plan().colors().get(index);if(color!=null&&!color.clear()){ShaderPackTarget main=side(index,false),alternate=side(index,true);blit(alternate.framebuffer,alternate.width,alternate.height,main.framebuffer,main.width,main.height);}}
        frames++;
    }
    /** End of the level frame (after hand): composite and final; fullscreen-only packs run their whole chain here. */
    void render(RenderTarget scene,String dimension,float partialTick){
        if(worldFrame){
            try{deferred();try(ShaderPackState state=new ShaderPackState(16)){ShaderPackState.fullscreen();run(frameChain,frameChain.post(),scene,frameUniforms);finish(frameChain,scene);}}
            finally{worldFrame=false;}
            return;
        }
        int width=scene.viewWidth,height=scene.viewHeight;if(width<1||height<1)return;
        String key=dimensions.containsKey(dimension)?dimension:"";Chain chain=dimensions.get(key);
        // World-program packs only render inside a level frame; their scene color is never the input.
        if(chain==null||!chain.plan().world().isEmpty())return;
        try(ShaderPackState state=new ShaderPackState(16)){
            allocate(key,chain,scene);ShaderPackState.fullscreen();
            for(var color:chain.plan().colors().values())if(color.clear())for(var target:targets.get(color.index()))target.clear(color.clearColor());
            if(targets.containsKey(0))blit(scene.frameBufferId,width,height,side(0,false));
            globals.celestial(chain.plan().sunRotation(),chain.plan().shadowInterval(),chain.plan().shadowDistance());run(chain,chain.all(),scene,uniforms(partialTick,width,height));finish(chain,scene);
        }
    }
    private static void blit(int source,int width,int height,ShaderPackTarget target){blit(source,width,height,target.framebuffer,target.width,target.height);}
    private static void blit(int source,int sourceWidth,int sourceHeight,int target,int targetWidth,int targetHeight){
        glBindFramebuffer(GL_READ_FRAMEBUFFER,source);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,target);
        glBlitFramebuffer(0,0,sourceWidth,sourceHeight,0,0,targetWidth,targetHeight,GL_COLOR_BUFFER_BIT,sourceWidth==targetWidth&&sourceHeight==targetHeight?GL_NEAREST:GL_LINEAR);
    }
    private static int factor(String name){return switch(name){
        case "ZERO"->GL_ZERO;case "ONE"->GL_ONE;case "SRC_COLOR"->GL_SRC_COLOR;case "ONE_MINUS_SRC_COLOR"->GL_ONE_MINUS_SRC_COLOR;case "DST_COLOR"->GL_DST_COLOR;case "ONE_MINUS_DST_COLOR"->GL_ONE_MINUS_DST_COLOR;
        case "SRC_ALPHA"->GL_SRC_ALPHA;case "ONE_MINUS_SRC_ALPHA"->GL_ONE_MINUS_SRC_ALPHA;case "DST_ALPHA"->GL_DST_ALPHA;case "ONE_MINUS_DST_ALPHA"->GL_ONE_MINUS_DST_ALPHA;case "SRC_ALPHA_SATURATE"->GL_SRC_ALPHA_SATURATE;
        default->throw new ShaderPackException("Unknown blend factor "+name);
    };}
    @Override public void close(){
        if(closed)return;closed=true;worldFrame=false;
        for(var shader:worldShaders.values())shader.close();worldShaders.clear();
        for(var shader:compiled)shader.close();compiled.clear();dimensions.clear();
        for(var image:images.values())image.close();images.clear();
        if(quad!=null)quad.close();release();
    }
}
