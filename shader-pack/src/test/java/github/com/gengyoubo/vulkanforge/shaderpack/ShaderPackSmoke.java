package github.com.gengyoubo.vulkanforge.shaderpack;

import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Standard folder/ZIP fixtures; no game or graphics libraries on the runtime classpath. */
public final class ShaderPackSmoke {
    public static void main(String[] ignored)throws Exception {
        Path work=Files.createTempDirectory("vulkanforge-pack-");
        try {
            Path pack=work.resolve("fixture"),root=pack.resolve("shaders");Files.createDirectories(root.resolve("lib"));Files.createDirectories(root.resolve("world-1"));
            write(root,"shaders.properties","screen=QUALITY BLOOM\nprofile.LOW=QUALITY:0 !BLOOM\nprofile.HIGH=profile.LOW QUALITY:2 BLOOM\n");write(root,"lib/common.glsl","#pragma once\n#define QUALITY 1 // [0 1 2]\n#define BLOOM\nvec4 color(){return vec4(1);}\n");
            String vertex="#version 120\n#include \"/lib/common.glsl\"\nvoid main(){gl_Position=ftransform();}\n";
            String fragment="#version 120\n/* DRAWBUFFERS:01 */\n/* RENDERTARGETS:0,10 */\nvoid main(){gl_FragData[0]=vec4(1);}\n";
            for(String program:new String[]{"gbuffers_basic","gbuffers_terrain","composite","composite2","composite10","final"}){write(root,program+".vsh",vertex);write(root,program+".fsh",fragment);}
            write(root,"world-1/gbuffers_terrain.fsh",fragment.replace("vec4(1)","vec4(0)"));
            ShaderPack folder=ShaderPack.load(pack);
            require(ShaderPackSupport.missing(folder,Map.of()).isEmpty()&&ShaderPackSupport.approximations(folder,Map.of()).stream().anyMatch(item->item.approximation()==ShaderPackSupport.Approximation.HAND_DEPTH),"World program preflight");
            var options=new ShaderPackOptions(folder);require(options.profile("HIGH").get("QUALITY").equals("2")&&options.profile("LOW").get("BLOOM").equals("false"),"Option profile inheritance");require(options.apply("#define BLOOM\n#define QUALITY 1 // [0 1 2]\n",options.profile("LOW")).contains("//#define BLOOM"),"Boolean option expansion");
            write(root,"shaders.properties","screen=QUALITY BLOOM\nprofile.HIGH=QUALITY:2 BLOOM\n#if QUALITY >= 2\nprogram.world-1/composite.enabled=false\n#else\nprogram.world-1/composite.enabled=true\n#endif\nsize.buffer.colortex0=QUALITY QUALITY\n");
            var effective=new ShaderPackConfiguration(folder,Map.of("QUALITY","2"));require(!effective.enabled("world-1","composite")&&effective.enabled("world0","composite"),"Dimension-specific conditional program selection");require(effective.properties().get("screen").equals("QUALITY BLOOM")&&effective.properties().get("profile.HIGH").equals("QUALITY:2 BLOOM"),"Property preprocessing expanded GUI option names");require(effective.properties().get("size.buffer.colortex0").equals("2 2"),"Property macro values were not expanded");
            write(root,"shaders.properties","screen=QUALITY BLOOM\nprofile.LOW=QUALITY:0 !BLOOM\nprofile.HIGH=profile.LOW QUALITY:2 BLOOM\n");
            require(folder.programs("").size()==6,"Program discovery");
            require(folder.resolve("","gbuffers_water").orElseThrow().name().equals("gbuffers_terrain"),"Gbuffer fallback hierarchy");
            require(folder.passes("",ShaderPack.Phase.COMPOSITE).stream().map(ShaderPack.Program::name).toList().equals(List.of("composite","composite2","composite10")),"Numeric composite order");
            require(Arrays.equals(folder.programs("").get("composite").drawBuffers(),new int[]{0,10}),"Last output directive / multi-digit target");
            var overridden=folder.programs("world-1").get("gbuffers_terrain");require(overridden.stages().get(ShaderPack.Stage.FRAGMENT).path().startsWith("world-1/"),"Dimension fragment override");require(!overridden.stages().get(ShaderPack.Stage.VERTEX).path().startsWith("world-1/"),"Dimension stage inheritance");
            var source=overridden.stages().get(ShaderPack.Stage.VERTEX);require(source.text().startsWith("#version")&&source.text().contains("#line")&&source.sourceNames().containsValue("lib/common.glsl"),"Include source diagnostics");
            Path zip=work.resolve("fixture.zip");try(var output=new ZipOutputStream(Files.newOutputStream(zip));var files=Files.walk(root)){for(Path file:files.filter(Files::isRegularFile).toList()){output.putNextEntry(new ZipEntry("wrapper/shaders/"+root.relativize(file).toString().replace('\\','/')));Files.copy(file,output);output.closeEntry();}}
            require(ShaderPack.load(zip).programs("").keySet().equals(folder.programs("").keySet()),"ZIP/folder program parity");
            Path installed=work.resolve("installed");String imported=ShaderPackImporter.copy(zip,installed);require(Files.mismatch(zip,installed.resolve(imported))==-1,"ZIP import changed the pack");
            try{ShaderPackImporter.copy(zip,installed);throw new AssertionError("Import overwrote an existing pack");}catch(FileAlreadyExistsException expected){}
            ShaderPackImporter.copy(pack,installed);require(ShaderPack.load(installed.resolve("fixture")).programs("").keySet().equals(folder.programs("").keySet()),"Folder import lost shader assets");
            Path invalid=work.resolve("invalid.zip");Files.writeString(invalid,"not a ZIP");try{ShaderPackImporter.copy(invalid,installed);throw new AssertionError("Invalid import accepted");}catch(java.io.IOException expected){}
            try(var entries=Files.list(installed)){require(entries.noneMatch(path->path.getFileName().toString().startsWith(".import-")),"Import left partial staging files");}
            hotSwitch(pack);
            preprocess(work);
            postPlan(work);
            worldPrograms(work);
            try{ShaderPackArchive.resolve("a.vsh","../outside");throw new AssertionError("Include escaped pack");}catch(ShaderPackException expected){}
            write(root,"cycle.vsh","#version 120\n#include \"cycle.vsh\"\n");try(var archive=ShaderPackArchive.open(pack)){try{new ShaderPackIncludes(archive).expand("cycle.vsh");throw new AssertionError("Include cycle accepted");}catch(ShaderPackException expected){require(expected.getMessage().contains("cycle"),"Include cycle diagnostic");}}
            System.out.println("SHADER_PACK_SMOKE=OK (ZIP/folder, includes, conditional macros, active output targets, options/profiles, dimension overrides, transactions, import safety, preflight)");
        }finally{try(var paths=Files.walk(work)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
    private static void write(Path root,String name,String content)throws Exception{Files.writeString(root.resolve(name),content);}
    private static void preprocess(Path work)throws Exception{
        String conditionals="#version 130\n#define FRAGMENT_SHADER\n#define REFLECT 3\n#define MODE 1\n/*DRAWBUFFERS:05*/\n#if REFLECT >= 2 && MODE >= 1\n/*DRAWBUFFERS:054*/\n#ifdef VOXY\n/* RENDERTARGETS: 0,5,4,19 */\n#endif\n#elif defined VOXY\n/* RENDERTARGETS: 0,5,19,18 */\n#endif\n";
        Path root=work.resolve("conditional/shaders/world-1");Files.createDirectories(root);write(root,"deferred1.fsh",conditionals+"void main(){gl_FragData[0]=vec4(1);}\n");
        ShaderPack pack=ShaderPack.load(root.getParent().getParent());require(Arrays.equals(pack.programs("world-1").get("deferred1").drawBuffers(),new int[]{0,5,4}),"Inactive VOXY outputs changed attachment validation");
        require(ShaderPackPreprocessor.condition("X*3==7 && defined(FLAG) && !defined(ABSENT)",Map.of("X","1+2","FLAG","")),"Macro expansion precedence / defined");
        String active=ShaderPackPreprocessor.active("#define FN(a,b) ((a) << (b))\n#if FN(3,2)==12 && (0 && 1/0 || 1)\nYES\n#else\nNO\n#endif\n");require(active.contains("YES")&&!active.contains("NO"),"Function macros, bit shifts or logical short circuit");
        require(ShaderPackPreprocessor.condition("0 ? 1/0 : 0x10U == 020",Map.of()),"Conditional / hexadecimal / octal expression");
        require(ShaderPackPreprocessor.condition("definedFlag==1",Map.of("definedFlag","1")),"defined was matched inside an identifier");
        require(ShaderPackPreprocessor.active("#version 130\n#if __VERSION__ == 130\nVERSION_OK\n#endif\n").contains("VERSION_OK"),"GLSL version macro missing");
        require(ShaderPackPreprocessor.active("/*\n#if BROKEN(\n*/\n#if 0\n#if ALSO_BROKEN(\n#endif\n#else\nACTIVE\n#endif\n").contains("ACTIVE"),"Comments or inactive nested expressions were evaluated");
        try{ShaderPackPreprocessor.active("#if 1\n");throw new AssertionError("Unterminated conditional accepted");}catch(ShaderPackException expected){}
        write(root,"deferred1.fsh","#version 130\n#define EXTENDED 0 // [0 1]\n#if EXTENDED\n/*DRAWBUFFERS:05*/\n#else\n/*DRAWBUFFERS:0*/\n#endif\nvoid main(){gl_FragData[0]=vec4(1);}\n");pack=ShaderPack.load(root.getParent().getParent());ShaderPackOptions options=new ShaderPackOptions(pack);var values=options.defaults();values.put("EXTENDED","1");require(Arrays.equals(ShaderPack.drawBuffers(pack.programs("world-1").get("deferred1"),options,values),new int[]{0,5}),"Changed options did not recompute output directives");
        write(root,"deferred1.fsh","#version 130\n/*RENDERTARGETS:99*/\n/*DRAWBUFFERS:0*/\nvoid main(){gl_FragData[0]=vec4(1);}\n");pack=ShaderPack.load(root.getParent().getParent());require(Arrays.equals(pack.programs("world-1").get("deferred1").drawBuffers(),new int[]{0}),"An obsolete output directive overrode the last active directive");
    }
    /** MRT ping-pong schedule, formats, persistence, mipmaps, blend/scale, textures and expressions of a fullscreen-only pack. */
    private static void postPlan(Path work)throws Exception{
        Path root=work.resolve("post/shaders");Files.createDirectories(root.resolve("tex"));
        write(root,"prepare.fsh","#version 120\n/* DRAWBUFFERS:3 */\nvoid main(){gl_FragData[0]=vec4(1);}\n");
        write(root,"composite.fsh","#version 120\nuniform sampler2D colortex0;\nconst int colortex1Format = RGBA16F;\nconst bool colortex1Clear = false;\nconst int noiseTextureResolution = 64;\n/* RENDERTARGETS: 0,1 */\nvoid main(){gl_FragData[0]=vec4(1);gl_FragData[1]=vec4(2);}\n");
        write(root,"composite1.fsh","#version 120\nuniform sampler2D colortex1;\nconst bool colortex1MipmapEnabled = true;\n/* DRAWBUFFERS:0 */\nvoid main(){gl_FragData[0]=vec4(1);}\n");
        write(root,"composite2.fsh","#version 120\n/* DRAWBUFFERS:2 */\nvoid main(){gl_FragData[0]=vec4(1);}\n");
        write(root,"final.fsh","#version 120\nuniform sampler2D colortex0,colortex2,noisetex;\nuniform sampler2D colortex5;\nvoid main(){gl_FragColor=vec4(1);}\n");
        write(root,"shaders.properties","flip.composite2.colortex2=false\nblend.composite1=SRC_ALPHA ONE_MINUS_SRC_ALPHA ONE ONE\nblend.composite1.gcolor=off\nscale.composite2=0.5\nsize.buffer.colortex2=0.5 0.5\ntexture.composite.colortex5=tex/pattern.png\nuniform.float.pulse=sin(frameTimeCounter) * 0.5\n");
        var pixels=new java.awt.image.BufferedImage(2,1,java.awt.image.BufferedImage.TYPE_INT_ARGB);pixels.setRGB(0,0,0xff102030);pixels.setRGB(1,0,0x80405060);javax.imageio.ImageIO.write(pixels,"png",root.resolve("tex/pattern.png").toFile());
        write(root,"tex/pattern.png.mcmeta","{\"texture\":{\"blur\":true}}");
        ShaderPack pack=ShaderPack.load(work.resolve("post"));require(ShaderPackSupport.missing(pack,Map.of()).isEmpty(),"Fullscreen MRT/HDR/expression pack rejected: "+ShaderPackSupport.missing(pack,Map.of()));
        var plan=new ShaderPackRenderPlan(pack,Map.of());var dimension=plan.dimension("world0");var chain=dimension.chain();
        require(chain.stream().map(pass->pass.source().name()).toList().equals(List.of("prepare","composite","composite1","composite2","final")),"Fullscreen execution order: "+chain);
        require(chain.get(0).alternate().isEmpty()&&chain.get(1).alternate().equals(Set.of(3))&&chain.get(2).alternate().equals(Set.of(0,1,3))&&chain.get(3).alternate().equals(Set.of(1,3))&&chain.get(4).alternate().equals(Set.of(1,3)),"Buffer flip schedule");
        require(dimension.flipped().equals(Set.of(1,3)),"End-of-frame flip state");
        var colors=dimension.colors();require(colors.get(1).format().equals("RGBA16F")&&!colors.get(1).clear()&&Arrays.equals(colors.get(1).clearColor(),new float[]{1,1,1,1}),"colortex1 format/persistence/default clear");
        require(colors.get(2).width(1000)==500&&colors.containsKey(5)&&!colors.containsKey(4),"Relative buffer size / sampled buffers");
        require(chain.get(2).mipmaps().equals(Set.of(1))&&chain.get(2).blend().equals(new ShaderPackRenderPlan.Blend("SRC_ALPHA","ONE_MINUS_SRC_ALPHA","ONE","ONE"))&&chain.get(2).blendOff().equals(Set.of(0)),"Mipmap/blend directives");
        require(chain.get(3).viewport()[0]==.5f&&chain.get(4).stage().equals("composite")&&chain.get(0).stage().equals("prepare"),"Viewport scale / texture stage");
        require(dimension.noiseResolution()==64&&plan.texture("composite","colortex5").orElseThrow().equals("tex/pattern.png")&&plan.texture("deferred","colortex5").isEmpty(),"Custom texture binding");
        require(plan.expressions().uniforms().equals(Set.of("pulse")),"Custom uniform expressions");
        try(var archive=ShaderPackArchive.open(work.resolve("post"))){var image=ShaderPackTextures.load(archive,"/tex/pattern.png");require(image.width()==2&&image.blur()&&!image.clamp()&&Arrays.equals(image.rgba(),new byte[]{16,32,48,-1,64,80,96,-128}),"PNG decode / mcmeta");}
        require(Arrays.equals(ShaderPackTextures.noise(4).rgba(),ShaderPackTextures.noise(4).rgba()),"Noise texture is not deterministic");
        write(root,"shaders.properties","texture.composite.colortex5=data.bin TEXTURE_2D RGBA8 4 4 RGBA UNSIGNED_BYTE\n");
        require(ShaderPackSupport.missing(pack,Map.of()).stream().anyMatch(missing->missing.feature()==ShaderPackSupport.Feature.CUSTOM_TEXTURES),"Raw texture definition passed preflight");
        write(root,"shaders.properties","blend.composite1.colortex0=ONE ONE ONE ONE\n");
        try{new ShaderPackRenderPlan(pack,Map.of());throw new AssertionError("Per-buffer blend factors accepted");}catch(ShaderPackException expected){}
    }
    /** Vanilla shader routing, legacy and core-profile world GLSL translation, alpha test, gbuffer flip states and ID files. */
    private static void worldPrograms(Path work)throws Exception{
        require(ShaderPackWorldPrograms.resolve("rendertype_translucent",ShaderPackWorldPrograms.Phase.TRANSLUCENT,Set.of("gbuffers_terrain","gbuffers_textured")).orElseThrow().equals("gbuffers_terrain"),"Water falls back to terrain");
        require(ShaderPackWorldPrograms.resolve("rendertype_entity_cutout",ShaderPackWorldPrograms.Phase.HAND,Set.of("gbuffers_textured_lit","gbuffers_entities")).orElseThrow().equals("gbuffers_textured_lit"),"Hand items use the hand hierarchy");
        require(ShaderPackWorldPrograms.resolve("rendertype_entity_solid",ShaderPackWorldPrograms.Phase.BLOCK_ENTITIES,Set.of("gbuffers_block","gbuffers_entities")).orElseThrow().equals("gbuffers_block"),"Block entities use gbuffers_block");
        require(ShaderPackWorldPrograms.resolve("position",ShaderPackWorldPrograms.Phase.SKY,Set.of("gbuffers_basic")).orElseThrow().equals("gbuffers_basic")&&ShaderPackWorldPrograms.resolve("particle",ShaderPackWorldPrograms.Phase.WEATHER,Set.of("gbuffers_weather")).isPresent(),"Sky/weather routing");
        require(ShaderPackWorldPrograms.requested("rendertype_gui",ShaderPackWorldPrograms.Phase.ENTITIES).isEmpty()&&ShaderPackWorldPrograms.resolve("rendertype_lines",ShaderPackWorldPrograms.Phase.ENTITIES,Set.of()).isEmpty(),"Unmapped/unavailable shaders keep vanilla");
        Path root=work.resolve("world/shaders");Files.createDirectories(root);
        write(root,"gbuffers_terrain.vsh","#version 120\nattribute vec4 mc_Entity;\nattribute vec2 mc_midTexCoord;\nvarying vec2 uv;\nvarying float id;\nvoid main(){gl_Position=ftransform();uv=(gl_TextureMatrix[0]*gl_MultiTexCoord0).xy;vec2 light=(gl_TextureMatrix[1]*gl_MultiTexCoord1).xy;gl_FrontColor=gl_Color;id=mc_Entity.x+mc_midTexCoord.x+light.x;vec3 n=gl_NormalMatrix*gl_Normal;}\n");
        write(root,"gbuffers_terrain.fsh","#version 120\nuniform sampler2D texture;\nuniform sampler2D lightmap;\nuniform sampler2D colortex4;\nuniform float rainStrength;\nuniform bool heavyFog = false;\nvarying vec2 uv;\n/* DRAWBUFFERS:04 */\nvoid main(){vec4 c=texture2D(texture,uv)*gl_Color*texture2D(lightmap,uv);gl_FragData[0]=c*rainStrength*float(heavyFog)+texture2D(colortex4,uv);gl_FragData[1]=vec4(1);}\n");
        write(root,"gbuffers_line.vsh","#version 330 core\nin vec3 vaPosition;\nin vec3 vaNormal;\nuniform mat4 modelViewMatrix;\nuniform mat4 projectionMatrix;\nuniform mat3 normalMatrix;\nuniform vec3 chunkOffset;\nvoid main(){gl_Position=projectionMatrix*modelViewMatrix*vec4(vaPosition+chunkOffset,1.0);vec3 n=normalMatrix*vaNormal;}\n");
        write(root,"gbuffers_line.fsh","#version 330 core\nlayout(location = 0) out vec4 color;\nvoid main(){color=vec4(1);}\n");
        write(root,"deferred.fsh","#version 120\nuniform sampler2D colortex4;\n/* DRAWBUFFERS:4 */\nvoid main(){gl_FragData[0]=vec4(1);}\n");
        write(root,"shaders.properties","alphaTest.gbuffers_terrain=GREATER 0.1\nalphaTest.gbuffers_line=off\n");
        write(root,"item.properties","#if 1\nitem.300=diamond_sword minecraft:torch\n#endif\nitem.301=oak_leaves:persistent=true\n");
        ShaderPack pack=ShaderPack.load(work.resolve("world"));var plan=new ShaderPackRenderPlan(pack,Map.of());var dimension=plan.dimension("world0");var options=new ShaderPackOptions(pack);
        require(dimension.opaque().isEmpty()&&dimension.translucent().equals(Set.of(4)),"Opaque/translucent gbuffer image states");
        var terrain=dimension.world().get("gbuffers_terrain");require(terrain.alphaTest().equals(new ShaderPackRenderPlan.AlphaTest("GREATER",.1f))&&terrain.stage().equals("gbuffers")&&dimension.world().get("gbuffers_line").alphaTest()==null,"alphaTest directives");
        Set<String> block=Set.of("Position","Color","UV0","UV2","Normal");
        var world=ShaderPackGlsl.world(terrain.source(),options,plan.configuration().values(),block,true,terrain.alphaTest());
        var fogProgram=new ShaderPack.Program("gbuffers_fog",ShaderPack.Phase.GBUFFER,0,Map.of(
            ShaderPack.Stage.VERTEX,new ShaderPackIncludes.Source("fog.vsh","#version 120\nvoid main(){gl_Position=ftransform();gl_FogFragCoord=length(gl_Vertex.xyz);}",Map.of()),
            ShaderPack.Stage.FRAGMENT,new ShaderPackIncludes.Source("fog.fsh","#version 120\n// gl_Fog is a legacy uniform struct; gl_FogFragCoord is a varying.\nvoid main(){gl_FragColor=vec4(gl_FogFragCoord);}",Map.of())),new int[]{0});
        var fog=ShaderPackGlsl.world(fogProgram,options,Map.of(),block,true,null);
        require(fog.vertex().contains("out float vfFogFragCoord;")&&fog.fragment().contains("in float vfFogFragCoord;")&&fog.fragment().contains("vec4(vfFogFragCoord)"),"Legacy fog distance must link as a vertex-to-fragment varying");
        require(world.vertex().contains("in vec3 Position;")&&world.vertex().contains("in ivec2 UV2;")&&world.vertex().contains("Position + ChunkOffset")&&world.vertex().contains("#define mc_Entity vec4(-1.0")&&!world.vertex().contains("in vec4 mc_Entity"),"Legacy world vertex interface");
        require(world.engineUniforms().containsAll(Set.of("ModelViewMat","ProjMat","TextureMat","ChunkOffset","ColorModulator"))&&world.engineSamplers().equals(Set.of("Sampler0","Sampler2")),"Engine uniforms/samplers: "+world.engineUniforms()+world.engineSamplers());
        require(world.uniforms().equals(Map.of("rainStrength","float","heavyFog","bool"))&&world.optional().equals(Set.of("heavyFog"))&&world.samplers().equals(List.of("colortex4")),"Pack uniforms: "+world.uniforms()+world.samplers());
        require(world.fragment().contains("texture(Sampler0,uv)")&&world.fragment().contains("if(!(vfFragColor0.a > 0.1))discard;"),"Sampler alias / alpha test");
        var line=dimension.world().get("gbuffers_line");var core=ShaderPackGlsl.world(line.source(),options,plan.configuration().values(),Set.of("Position","Color","Normal"),true,null);
        require(core.vertex().contains("ProjMat*ModelViewMat*vec4(Position+ChunkOffset")&&core.vertex().contains("(mat3(transpose(inverse(ModelViewMat))))*Normal")&&!core.vertex().contains("normalMatrix")&&!core.vertex().contains("uniform mat3")&&core.uniforms().isEmpty(),"Core-profile world names:\n"+core.vertex());
        require(ShaderPackWorldPrograms.resolve("rendertype_cutout",ShaderPackWorldPrograms.Phase.SHADOW,Set.of("shadow")).orElseThrow().equals("shadow")&&ShaderPackWorldPrograms.resolve("position",ShaderPackWorldPrograms.Phase.SHADOW,Set.of("shadow")).isEmpty()&&!ShaderPackWorldPrograms.Phase.SHADOW.translucent(),"Shadow pass routing");
        write(root,"shadow.vsh","#version 120\nvoid main(){gl_Position=ftransform();}\n");
        write(root,"shadow.fsh","#version 120\nconst int shadowcolor1Format = RGBA16F;\nconst bool shadowHardwareFiltering0 = true;\nconst float shadowIntervalSize = 4.0;\nconst int shadowMapResolution = 512;\n/* DRAWBUFFERS:01 */\nvoid main(){gl_FragData[0]=vec4(1);gl_FragData[1]=vec4(0);}\n");
        var shadowPlan=new ShaderPackRenderPlan(ShaderPack.load(work.resolve("world")),Map.of()).dimension("world0");
        require(shadowPlan.shadowPrograms().containsKey("shadow")&&shadowPlan.shadowColors().get(1).format().equals("RGBA16F")&&Arrays.equals(shadowPlan.shadowColors().get(0).clearColor(),new float[]{1,1,1,1})&&!shadowPlan.colors().containsKey(1),"Shadow color images are separate from colortex");
        require(shadowPlan.shadowFiltering()[0]&&!shadowPlan.shadowFiltering()[1]&&shadowPlan.shadowInterval()==4f&&shadowPlan.shadowResolution()==512,"Shadow filtering/interval/resolution");
        Files.delete(root.resolve("shadow.vsh"));Files.delete(root.resolve("shadow.fsh"));
        var items=ShaderPackIds.load(pack,plan.configuration(),"item");require(items.id("minecraft:diamond_sword")==300&&items.id("minecraft:torch")==300&&items.id("minecraft:oak_leaves")==301&&items.id("minecraft:stone")==-1,"item.properties IDs");
        require(ShaderPackSupport.missing(pack,Map.of()).isEmpty()&&ShaderPackSupport.approximations(pack,Map.of()).stream().anyMatch(item->item.approximation()==ShaderPackSupport.Approximation.BLOCK_IDS),"Block IDs must be a reported approximation, not a rejection");
    }
    private static final class Resource implements AutoCloseable,ShaderPackController.Incremental {boolean closed;int remaining;boolean fail;@Override public boolean prepareFrame(){if(fail)throw new ShaderPackException("incremental compile failed");return remaining--<=0;}@Override public void close(){require(!closed,"GPU resource retired twice");closed=true;}}
    private static void hotSwitch(Path pack){
        Deque<Runnable> cpuQueue=new ArrayDeque<>(),gpuQueue=new ArrayDeque<>();boolean[] prepared={false},compiled={false};
        var split=new ShaderPackController<Resource>(cpuQueue::addLast,gpuQueue::addLast,source->{prepared[0]=true;return source.name();},name->{require(prepared[0],"GPU compiler ran before CPU preparation");compiled[0]=true;return new Resource();},Resource::close);
        split.request(pack);require(!prepared[0]&&!compiled[0],"Pack preparation ran inline");cpuQueue.removeFirst().run();require(prepared[0]&&!compiled[0],"GPU work escaped onto the CPU executor");gpuQueue.removeFirst().run();require(compiled[0]&&split.active()==null,"Prepared pack committed inside a GPU task");split.beginFrame();split.close();
        Resource delayed=new Resource();delayed.remaining=2;
        var budgeted=new ShaderPackController<Resource>(cpuQueue::addLast,gpuQueue::addLast,source->delayed,Resource::close);budgeted.request(pack);cpuQueue.removeFirst().run();gpuQueue.removeFirst().run();require(!budgeted.beginFrame()&&!budgeted.beginFrame()&&budgeted.status().loading()&&budgeted.active()==null,"GPU budget committed an incomplete candidate");require(budgeted.beginFrame()&&budgeted.active()==delayed,"Completed GPU work did not commit");budgeted.close();
        Deque<Runnable> io=new ArrayDeque<>(),render=new ArrayDeque<>();List<Resource> retired=new ArrayList<>();boolean[] fail={false};
        var controller=new ShaderPackController<Resource>(io::addLast,render::addLast,source->{if(fail[0])throw new ShaderPackException("compile rejected");return new Resource();},retired::add);
        controller.request(pack);io.removeFirst().run();render.removeFirst().run();require(controller.active()==null,"Pack changed during the current frame");require(controller.beginFrame(),"Prepared pack did not commit");Resource first=controller.active();
        fail[0]=true;controller.request(pack);io.removeFirst().run();render.removeFirst().run();require(!controller.beginFrame()&&controller.active()==first&&retired.isEmpty(),"Failed compile discarded the active pack");require(controller.status().error().contains("compile rejected"),"Compile error not reported");
        fail[0]=false;controller.request(pack);controller.request(null);io.removeFirst().run();while(!render.isEmpty())render.removeFirst().run();controller.beginFrame();require(controller.active()==null&&retired.equals(List.of(first))&&!first.closed,"Disable/cancellation or deferred GPU retirement failed");
        controller.request(pack);io.removeFirst().run();render.removeFirst().run();controller.request(pack);io.removeFirst().run();render.removeFirst().run();require(retired.size()==2,"Superseded prepared shaders leaked");controller.beginFrame();controller.close();require(retired.size()==3,"Close leaked active shaders");for(Resource resource:retired)resource.close();
    }
    private static void require(boolean value,String reason){if(!value)throw new AssertionError(reason);}
}
