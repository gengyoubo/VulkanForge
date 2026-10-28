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
            var options=new ShaderPackOptions(folder);require(options.profile("HIGH").get("QUALITY").equals("2")&&options.profile("LOW").get("BLOOM").equals("false"),"Option profile inheritance");require(options.apply("#define BLOOM\n#define QUALITY 1 // [0 1 2]\n",options.profile("LOW")).contains("//#define BLOOM"),"Boolean option expansion");
            require(folder.programs("").size()==6,"Program discovery");
            require(folder.resolve("","gbuffers_water").orElseThrow().name().equals("gbuffers_terrain"),"Gbuffer fallback hierarchy");
            require(folder.passes("",ShaderPack.Phase.COMPOSITE).stream().map(ShaderPack.Program::name).toList().equals(List.of("composite","composite2","composite10")),"Numeric composite order");
            require(Arrays.equals(folder.programs("").get("composite").drawBuffers(),new int[]{0,10}),"Last output directive / multi-digit target");
            var overridden=folder.programs("world-1").get("gbuffers_terrain");require(overridden.stages().get(ShaderPack.Stage.FRAGMENT).path().startsWith("world-1/"),"Dimension fragment override");require(!overridden.stages().get(ShaderPack.Stage.VERTEX).path().startsWith("world-1/"),"Dimension stage inheritance");
            var source=overridden.stages().get(ShaderPack.Stage.VERTEX);require(source.text().startsWith("#version")&&source.text().contains("#line")&&source.sourceNames().containsValue("lib/common.glsl"),"Include source diagnostics");
            Path zip=work.resolve("fixture.zip");try(var output=new ZipOutputStream(Files.newOutputStream(zip));var files=Files.walk(root)){for(Path file:files.filter(Files::isRegularFile).toList()){output.putNextEntry(new ZipEntry("wrapper/shaders/"+root.relativize(file).toString().replace('\\','/')));Files.copy(file,output);output.closeEntry();}}
            require(ShaderPack.load(zip).programs("").keySet().equals(folder.programs("").keySet()),"ZIP/folder program parity");
            hotSwitch(pack);
            try{ShaderPackArchive.resolve("a.vsh","../outside");throw new AssertionError("Include escaped pack");}catch(ShaderPackException expected){}
            write(root,"cycle.vsh","#version 120\n#include \"cycle.vsh\"\n");try(var archive=ShaderPackArchive.open(pack)){try{new ShaderPackIncludes(archive).expand("cycle.vsh");throw new AssertionError("Include cycle accepted");}catch(ShaderPackException expected){require(expected.getMessage().contains("cycle"),"Include cycle diagnostic");}}
            System.out.println("SHADER_PACK_SMOKE=OK (standard ZIP/folder discovery, includes/source maps, pass order, output targets, dimension overrides, fallbacks, path/cycle rejection)");
        }finally{try(var paths=Files.walk(work)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
    private static void write(Path root,String name,String content)throws Exception{Files.writeString(root.resolve(name),content);}
    private static final class Resource implements AutoCloseable {boolean closed;@Override public void close(){require(!closed,"GPU resource retired twice");closed=true;}}
    private static void hotSwitch(Path pack){
        Deque<Runnable> io=new ArrayDeque<>(),render=new ArrayDeque<>();List<Resource> retired=new ArrayList<>();boolean[] fail={false};
        var controller=new ShaderPackController<Resource>(io::addLast,render::addLast,source->{if(fail[0])throw new ShaderPackException("compile rejected");return new Resource();},retired::add);
        controller.request(pack);io.removeFirst().run();render.removeFirst().run();require(controller.active()==null,"Pack changed during the current frame");require(controller.beginFrame(),"Prepared pack did not commit");Resource first=controller.active();
        fail[0]=true;controller.request(pack);io.removeFirst().run();render.removeFirst().run();require(!controller.beginFrame()&&controller.active()==first&&retired.isEmpty(),"Failed compile discarded the active pack");require(controller.status().error().contains("compile rejected"),"Compile error not reported");
        fail[0]=false;controller.request(pack);controller.request(null);io.removeFirst().run();while(!render.isEmpty())render.removeFirst().run();controller.beginFrame();require(controller.active()==null&&retired.equals(List.of(first))&&!first.closed,"Disable/cancellation or deferred GPU retirement failed");
        controller.request(pack);io.removeFirst().run();render.removeFirst().run();controller.request(pack);io.removeFirst().run();render.removeFirst().run();require(retired.size()==2,"Superseded prepared shaders leaked");controller.beginFrame();controller.close();require(retired.size()==3,"Close leaked active shaders");for(Resource resource:retired)resource.close();
    }
    private static void require(boolean value,String reason){if(!value)throw new AssertionError(reason);}
}
