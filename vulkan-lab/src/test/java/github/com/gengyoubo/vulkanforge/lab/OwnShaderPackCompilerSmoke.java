package github.com.gengyoubo.vulkanforge.lab;

import github.com.gengyoubo.vulkanforge.shaderpack.*;
import java.nio.file.*;
import java.util.*;

/** Compile original pack programs through VulkanForge's own frontend, with no Iris/Oculus runtime. */
public final class OwnShaderPackCompilerSmoke {
    public static void main(String[] ignored)throws Exception{
        org.lwjgl.system.Configuration.STACK_SIZE.set(4096);
        ShaderPack pack=ShaderPack.load(Path.of(System.getProperty("shaderPack.input")));ShaderPackOptions options=new ShaderPackOptions(pack);ShaderPackConfiguration configuration=new ShaderPackConfiguration(pack,Map.of());
        Path output=Path.of(System.getProperty("shaderPack.output","build/reports/own-loader-compiler"));Files.createDirectories(output);int compiled=0;
        int api=org.lwjgl.vulkan.VK.getInstanceVersionSupported();api=org.lwjgl.vulkan.VK10.VK_MAKE_VERSION(org.lwjgl.vulkan.VK10.VK_VERSION_MAJOR(api),org.lwjgl.vulkan.VK10.VK_VERSION_MINOR(api),0);
        try(var backend=NativeGameBackend.offscreen(output.resolve("backend.txt").toString(),api,true)){
            // Minecraft's NEW_ENTITY layout: the widest vanilla format a world program is bound to.
            var attributes=Map.of("Position",0,"Color",1,"UV0",2,"UV1",3,"UV2",4,"Normal",5);var plan=new ShaderPackRenderPlan(pack,Map.of());
            for(String dimension:List.of("world0"))for(var program:new TreeMap<>(pack.programs(dimension)).values()){
                if(!configuration.enabled(dimension,program.name())||!program.graphics())continue;
                // shadowcomp is not implemented; world and shadow programs compile only when requested (-PshaderPack.world).
                if(program.phase()==ShaderPack.Phase.SHADOW_COMPOSITE||(program.phase()==ShaderPack.Phase.GBUFFER||program.phase()==ShaderPack.Phase.SHADOW)&&!Boolean.getBoolean("shaderPack.world"))continue;
                System.out.println("OWN_COMPILE_BEGIN="+dimension+"/"+program.name());
                String vertex,fragment;Map<String,Integer> bindings;
                if(program.phase()==ShaderPack.Phase.GBUFFER||program.phase()==ShaderPack.Phase.SHADOW){var pass=program.phase()==ShaderPack.Phase.SHADOW?plan.dimension(dimension).shadowPrograms().get(program.name()):plan.dimension(dimension).world().get(program.name());var world=ShaderPackGlsl.world(program,options,configuration.values(),attributes.keySet(),true,pass.alphaTest());vertex=world.vertex();fragment=world.fragment();bindings=attributes;}
                else{var graphics=ShaderPackGlsl.fullscreen(program,options,configuration.values());vertex=graphics.vertex();fragment=graphics.fragment();bindings=graphics.attributes();}
                Files.writeString(output.resolve("current.vert"),vertex);Files.writeString(output.resolve("current.frag"),fragment);
                backend.program(++compiled,vertex,fragment,bindings.entrySet().stream().map(entry->new Object[]{entry.getKey(),entry.getValue()}).toArray());
                System.out.println("OWN_COMPILE_OK="+dimension+"/"+program.name());
            }
            // Embeddium chunk programs: the same pack sources on Embeddium's own chunk vertex decoder plus the extended attributes.
            String include=System.getProperty("shaderPack.embeddiumInclude");
            if(include!=null&&Files.isRegularFile(Path.of(include))&&Boolean.getBoolean("shaderPack.world")){
                String decoder=Files.readString(Path.of(include));var dimension=plan.dimension("world0");
                Map<String,Integer> chunk=new HashMap<>(Map.of("a_PosId",0,"a_Color",1,"a_TexCoord",2,"a_LightCoord",3));chunk.putAll(ShaderPackGlsl.CHUNK_ATTRIBUTES);
                for(var phase:List.of(ShaderPackWorldPrograms.Phase.TERRAIN,ShaderPackWorldPrograms.Phase.SHADOW))for(String layer:List.of("rendertype_solid","rendertype_cutout","rendertype_translucent")){
                    var programs=phase==ShaderPackWorldPrograms.Phase.SHADOW?dimension.shadowPrograms():dimension.world();var name=ShaderPackWorldPrograms.resolve(layer,phase,programs.keySet());if(name.isEmpty())continue;
                    var pass=programs.get(name.get());var world=ShaderPackGlsl.chunks(pass.source(),options,configuration.values(),decoder,pass.alphaTest());
                    Files.writeString(output.resolve("current.vert"),world.vertex());Files.writeString(output.resolve("current.frag"),world.fragment());
                    backend.program(++compiled,world.vertex(),world.fragment(),chunk.entrySet().stream().map(entry->new Object[]{entry.getKey(),entry.getValue()}).toArray());
                    System.out.println("OWN_COMPILE_OK=embeddium/"+phase+"/"+layer+"="+name.get());
                }
            }
            if(backend.stats()[5]!=0)throw new AssertionError("Own shader compiler produced validation errors");
        }
        Files.writeString(output.resolve("result.txt"),"STATUS=OK\nFRONTEND=VULKANFORGE\nIRIS_OCULUS_CLASSES=ABSENT\nORIGINAL_PROGRAMS_COMPILED="+compiled+"\n");
    }
}
