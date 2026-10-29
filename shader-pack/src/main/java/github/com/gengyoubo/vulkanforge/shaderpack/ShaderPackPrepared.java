package github.com.gengyoubo.vulkanforge.shaderpack;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Immutable pack inputs plus option-local source caches. Construction never accesses a game or graphics device. */
public final class ShaderPackPrepared {
    private record WorldKey(ShaderPack.Program program,Set<String> format,boolean offset){WorldKey{format=Set.copyOf(format);}}
    private record Core(String name,Set<String> format,boolean offset){ }
    private static final Set<String> BLOCK=Set.of("Position","Color","UV0","UV2","Normal"),ENTITY=Set.of("Position","Color","UV0","UV1","UV2","Normal");
    private static final List<Core> CORES=List.of(
        core("position",false,"Position"),core("position_color",false,"Position","Color"),core("position_tex",false,"Position","UV0"),
        core("position_tex_color",false,"Position","UV0","Color"),core("position_color_tex_lightmap",false,"Position","Color","UV0","UV2"),
        core("position_tex_color_normal",false,"Position","UV0","Color","Normal"),core("particle",false,"Position","UV0","Color","UV2"),
        new Core("rendertype_solid",BLOCK,true),new Core("rendertype_cutout",BLOCK,true),new Core("rendertype_translucent",BLOCK,true),
        new Core("rendertype_entity_solid",ENTITY,false),new Core("rendertype_entity_translucent",ENTITY,false),
        core("rendertype_lines",false,"Position","Color","Normal"),core("rendertype_glint",false,"Position","UV0"),
        new Core("rendertype_beacon_beam",BLOCK,false),new Core("rendertype_crumbling",BLOCK,false));
    public final ShaderPack pack;
    public final ShaderPackConfiguration configuration;
    public final ShaderPackRenderPlan plan;
    public final List<ShaderPackSupport.Missing> missing;
    public final List<ShaderPackSupport.Approximate> approximations;
    public final Map<String,Double> milliseconds;
    private final Map<ShaderPack.Program,ShaderPackGlsl.Graphics> graphics=new HashMap<>();
    private final Map<WorldKey,ShaderPackGlsl.World> world=new ConcurrentHashMap<>();
    private final Map<String,ShaderPackTextures.Image> textures=new HashMap<>();
    public ShaderPackPrepared(ShaderPack pack,Map<String,String> selected)throws IOException{
        this.pack=pack;Map<String,Double> times=new LinkedHashMap<>();long started=System.nanoTime(),at=started;
        configuration=new ShaderPackConfiguration(pack,selected);at=mark(times,"options_properties",at);
        missing=ShaderPackSupport.missing(pack,configuration);approximations=missing.isEmpty()?ShaderPackSupport.approximations(pack,configuration):List.of();at=mark(times,"preflight",at);
        if(missing.isEmpty()){
            plan=new ShaderPackRenderPlan(pack,configuration);at=mark(times,"plan",at);
            for(var dimension:plan.dimensions().values()){
                for(var pass:dimension.chain())graphics.computeIfAbsent(pass.source(),program->ShaderPackGlsl.fullscreen(program,configuration));
                for(var phase:ShaderPackWorldPrograms.Phase.values()){
                    var programs=phase==ShaderPackWorldPrograms.Phase.SHADOW?dimension.shadowPrograms():dimension.world();
                    for(var core:CORES)ShaderPackWorldPrograms.resolve(core.name(),phase,programs.keySet()).ifPresent(name->world(programs.get(name),core.format(),core.offset()));
                }
            }
            at=mark(times,"normalize",at);
            Set<String> definitions=new HashSet<>();plan.textures().values().forEach(stage->definitions.addAll(stage.values()));plan.noise().ifPresent(definitions::add);
            try(var archive=ShaderPackArchive.open(pack.path())){for(String definition:definitions)if(!ShaderPackTextures.resource(definition))textures.put(definition,ShaderPackTextures.load(archive,definition));}
            mark(times,"textures",at);
        }else plan=null;
        times.put("total",(System.nanoTime()-started)/1e6);milliseconds=Collections.unmodifiableMap(times);
    }
    private static Core core(String name,boolean offset,String...attributes){return new Core(name,Set.of(attributes),offset);}
    private static long mark(Map<String,Double> values,String name,long at){long now=System.nanoTime();values.put(name,(now-at)/1e6);return now;}
    public ShaderPackGlsl.Graphics graphics(ShaderPack.Program program){return Objects.requireNonNull(graphics.get(program),program.name());}
    public ShaderPackGlsl.World world(ShaderPackRenderPlan.Pass pass,Set<String> format,boolean offset){return world.computeIfAbsent(new WorldKey(pass.source(),format,offset),key->ShaderPackGlsl.world(pass.source(),configuration,key.format(),offset,pass.alphaTest()));}
    public ShaderPackTextures.Image texture(String definition){return textures.get(definition);}
    public int worldVariants(){return world.size();}
}
