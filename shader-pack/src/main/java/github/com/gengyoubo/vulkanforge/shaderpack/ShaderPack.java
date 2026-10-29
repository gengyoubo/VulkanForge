package github.com.gengyoubo.vulkanforge.shaderpack;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.*;

/** Backend-neutral snapshot of a standard Iris/OptiFine pack, including all discovered stages. */
public final class ShaderPack {
    public enum Stage {VERTEX("vsh"),FRAGMENT("fsh"),GEOMETRY("gsh"),TESS_CONTROL("tcs"),TESS_EVALUATION("tes"),COMPUTE("csh");public final String extension;Stage(String extension){this.extension=extension;}static Stage from(String suffix){for(Stage stage:values())if(stage.extension.equals(suffix))return stage;throw new IllegalArgumentException(suffix);}}
    public enum Phase {SETUP,BEGIN,SHADOW,SHADOW_COMPOSITE,PREPARE,GBUFFER,DEFERRED,COMPOSITE,FINAL}
    public record Program(String name,Phase phase,int order,Map<Stage,ShaderPackIncludes.Source> stages,int[] drawBuffers){
        public Program{stages=Map.copyOf(stages);drawBuffers=drawBuffers.clone();}
        @Override public int[] drawBuffers(){return drawBuffers.clone();}
        public boolean graphics(){return stages.containsKey(Stage.VERTEX)||stages.containsKey(Stage.FRAGMENT);}
    }
    private static final Pattern ENTRY=Pattern.compile("(?:(world-?\\d+)/)?((?:gbuffers|shadow)_[a-zA-Z0-9_]+|shadow|final|(?:setup|begin|shadowcomp|prepare|deferred|composite)(?:[0-9]{1,2})?(?:_[a-z])?)\\.(vsh|fsh|gsh|tcs|tes|csh)");
    private static final Pattern OUTPUTS=Pattern.compile("/\\*\\s*(DRAWBUFFERS|RENDERTARGETS)\\s*:\\s*([0-9N, \\t]*)\\s*\\*/");
    private static final Map<String,String> FALLBACKS=fallbacks();
    private final Path path;
    private final Map<String,String> properties;
    private final Map<String,Map<String,Program>> dimensions;
    private ShaderPack(Path path,Map<String,String> properties,Map<String,Map<String,Program>> dimensions){this.path=path;this.properties=Map.copyOf(properties);this.dimensions=Map.copyOf(dimensions);}
    public static ShaderPack load(Path path)throws IOException {
        try(ShaderPackArchive archive=ShaderPackArchive.open(path)){
            Properties raw=new Properties();if(archive.exists("shaders.properties"))raw.load(new StringReader(archive.text("shaders.properties")));
            Map<String,String> properties=new LinkedHashMap<>();for(String key:raw.stringPropertyNames())properties.put(key,raw.getProperty(key));
            Map<String,Map<String,EnumMap<Stage,String>>> found=new LinkedHashMap<>();found.put("",new LinkedHashMap<>());
            for(String file:archive.files()){Matcher match=ENTRY.matcher(file);if(!match.matches())continue;String dimension=Objects.requireNonNullElse(match.group(1),""),name=match.group(2);found.computeIfAbsent(dimension,unused->new LinkedHashMap<>()).computeIfAbsent(name,unused->new EnumMap<>(Stage.class)).put(Stage.from(match.group(3)),file);}
            ShaderPackIncludes includes=new ShaderPackIncludes(archive);Map<String,ShaderPackIncludes.Source> expanded=new HashMap<>();Map<String,Map<String,Program>> dimensions=new LinkedHashMap<>();
            for(var dimension:found.entrySet()){
                Map<String,EnumMap<Stage,String>> entries=new LinkedHashMap<>();found.get("").forEach((name,stages)->entries.put(name,new EnumMap<>(stages)));
                dimension.getValue().forEach((name,stages)->entries.computeIfAbsent(name,unused->new EnumMap<>(Stage.class)).putAll(stages));
                Map<String,Program> programs=new LinkedHashMap<>();
                for(var entry:entries.entrySet()){
                    EnumMap<Stage,ShaderPackIncludes.Source> sources=new EnumMap<>(Stage.class);for(var stage:entry.getValue().entrySet()){var source=expanded.get(stage.getValue());if(source==null){source=includes.expand(stage.getValue());expanded.put(stage.getValue(),source);}sources.put(stage.getKey(),source);}
                    String name=entry.getKey();programs.put(name,new Program(name,phase(name),order(name),sources,drawBuffers(sources.get(Stage.FRAGMENT))));
                }
                dimensions.put(dimension.getKey(),Map.copyOf(programs));
            }
            if(dimensions.values().stream().allMatch(Map::isEmpty))throw new ShaderPackException("No Iris/OptiFine shader programs found in "+path.getFileName());
            return new ShaderPack(archive.source(),properties,dimensions);
        }
    }
    public Path path(){return path;}
    public String name(){return path.getFileName().toString();}
    public Map<String,String> properties(){return properties;}
    public Set<String> dimensions(){return dimensions.keySet();}
    public Map<String,Program> programs(String dimension){return dimensions.getOrDefault(dimension,dimensions.get(""));}
    public List<Program> passes(String dimension,Phase phase){return programs(dimension).values().stream().filter(program->program.phase()==phase).sorted(Comparator.comparingInt(Program::order).thenComparing(Program::name)).toList();}
    public Optional<Program> resolve(String dimension,String requested){Map<String,Program> programs=programs(dimension);for(String name=requested;name!=null;name=FALLBACKS.get(name)){Program program=programs.get(name);if(program!=null&&program.graphics())return Optional.of(program);}return Optional.empty();}
    /** Next program in the standard fallback hierarchy, e.g. gbuffers_water → gbuffers_terrain. */
    public static Optional<String> fallback(String name){return Optional.ofNullable(FALLBACKS.get(name));}
    private static Phase phase(String name){if(name.startsWith("gbuffers_"))return Phase.GBUFFER;if(name.equals("shadow")||name.startsWith("shadow_"))return Phase.SHADOW;if(name.startsWith("shadowcomp"))return Phase.SHADOW_COMPOSITE;if(name.startsWith("prepare"))return Phase.PREPARE;if(name.startsWith("deferred"))return Phase.DEFERRED;if(name.startsWith("composite"))return Phase.COMPOSITE;if(name.startsWith("setup"))return Phase.SETUP;if(name.startsWith("begin"))return Phase.BEGIN;if(name.equals("final"))return Phase.FINAL;throw new ShaderPackException("Unknown shader program "+name);}
    private static int order(String name){Matcher suffix=Pattern.compile("(?:setup|begin|shadowcomp|prepare|deferred|composite)([0-9]*)(?:_([a-z]))?").matcher(name);if(!suffix.matches())return 0;int index=suffix.group(1).isEmpty()?0:Integer.parseInt(suffix.group(1));return index*32+(suffix.group(2)==null?31:suffix.group(2).charAt(0)-'a');}
    private static int[] drawBuffers(ShaderPackIncludes.Source fragment){
        if(fragment==null)return new int[]{0};
        return drawBuffers(fragment.path(),ShaderPackPreprocessor.active(fragment.text()));
    }
    private static int[] drawBuffers(String path,String active){
        Matcher directives=OUTPUTS.matcher(active);
        String kind=null,declaration=null;
        while(directives.find()){kind=directives.group(1);declaration=directives.group(2).replaceAll("\\s+","");}
        if(kind==null)return new int[]{0};
        int[] output;
        if(kind.equals("DRAWBUFFERS")){
            output=new int[declaration.length()];for(int i=0;i<output.length;i++)output[i]=declaration.charAt(i)=='N'?-1:declaration.charAt(i)-'0';
        }else output=declaration.isEmpty()?new int[0]:Arrays.stream(declaration.split(",")).mapToInt(Integer::parseInt).toArray();
        Set<Integer> used=new HashSet<>();
        for(int value:output){
            if(value< -1||value>15)throw new ShaderPackException(path+": active "+kind+" selects unsupported color target "+value+" (0..15)");
            if(value>=0&&!used.add(value))throw new ShaderPackException(path+": duplicate color target "+value);
        }
        return output;
    }
    public static int[] drawBuffers(Program program,ShaderPackOptions options,Map<String,String> values){
        var fragment=program.stages().get(Stage.FRAGMENT);if(fragment==null)return new int[]{0};
        return drawBuffers(new ShaderPackIncludes.Source(fragment.path(),options.apply(fragment.text(),values),fragment.sourceNames()));
    }
    public static int[] drawBuffers(Program program,ShaderPackConfiguration configuration){var fragment=program.stages().get(Stage.FRAGMENT);return fragment==null?new int[]{0}:drawBuffers(fragment.path(),configuration.active(fragment));}
    private static Map<String,String> fallbacks(){
        Map<String,String> result=new HashMap<>();String[][] families={{"basic","line","textured","skybasic"},{"textured","textured_lit","skytextured","clouds","beaconbeam","armor_glint","spidereyes"},{"textured_lit","terrain","item","entities","particles","hand","weather"},{"terrain","terrain_solid","terrain_cutout","damagedblock","block","water"},{"entities","entities_translucent","entities_glowing","lightning"},{"particles","particles_translucent"},{"block","block_translucent"},{"hand","hand_water"}};
        for(String[] family:families)for(int i=1;i<family.length;i++)result.put("gbuffers_"+family[i],"gbuffers_"+family[0]);for(String shadow:new String[]{"solid","cutout","water","entities","block"})result.put("shadow_"+shadow,"shadow");result.put("shadow_lightning","shadow_entities");return Map.copyOf(result);
    }
}
