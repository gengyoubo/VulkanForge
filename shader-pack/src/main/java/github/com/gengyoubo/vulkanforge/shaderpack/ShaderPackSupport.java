package github.com.gengyoubo.vulkanforge.shaderpack;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/** Preflight for the implemented renderer; discovering a file is not rendering support. */
public final class ShaderPackSupport {
    /** Blocking: a pack needing any of these is rejected before it can replace the active pack. */
    public enum Feature { SHADOW_COMPOSITE,COMPUTE_STAGES,GEOMETRY_STAGES,TESSELLATION_STAGES,CUSTOM_TEXTURES,REQUIRED_FEATURES }
    /** Non-blocking: the pack runs, but these inputs carry fixed stand-in values and are listed in the compatibility report. */
    public enum Approximation { BLOCK_IDS,ENTITY_IDS,HAND_DEPTH,SHADOW_CULLING,SHADOW_ENTITIES,RENDERER_SETTINGS }
    public record Missing(Feature feature,String example){ }
    public record Approximate(Approximation approximation,String example){ }
    /** Settings that only adjust the vanilla renderer around the pack; they are accepted without effect. */
    public static final Set<String> RENDERER_SETTINGS=Set.of("oldLighting","oldHandLight","separateAo","beacon.beam.depth","rain.depth","particles.ordering","underwaterOverlay","vignette","supportsColorCorrection","breaksAnisotropy","dynamicHandLight","frustum.culling","sun","moon","stars","weather","particles.before.deferred");
    private static final Pattern BLOCK_ATTRIBUTES=Pattern.compile("\\b(?:mc_Entity|mc_midTexCoord|at_midBlock|at_tangent)\\b"),ENTITY_UNIFORMS=Pattern.compile("\\buniform\\s+\\w+\\s+(?:entityId|blockEntityId|currentRenderedItemId|entityColor)\\b");
    private ShaderPackSupport(){ }
    /** Only programs enabled by the effective option set count; a disabled program never runs. */
    public static List<Missing> missing(ShaderPack pack,Map<String,String> selected)throws IOException{
        var configuration=new ShaderPackConfiguration(pack,selected);
        Map<Feature,String> missing=new EnumMap<>(Feature.class);
        for(String dimension:new TreeSet<>(pack.dimensions()))for(var program:new TreeMap<>(pack.programs(dimension)).values()){
            if(!configuration.enabled(dimension,program.name()))continue;
            String path=(dimension.isEmpty()?"":dimension+"/")+program.name();
            if(program.phase()==ShaderPack.Phase.SHADOW_COMPOSITE)missing.putIfAbsent(Feature.SHADOW_COMPOSITE,path);
            if(program.stages().containsKey(ShaderPack.Stage.COMPUTE))missing.putIfAbsent(Feature.COMPUTE_STAGES,path+".csh");
            if(program.stages().containsKey(ShaderPack.Stage.GEOMETRY))missing.putIfAbsent(Feature.GEOMETRY_STAGES,path+".gsh");
            if(program.stages().containsKey(ShaderPack.Stage.TESS_CONTROL)||program.stages().containsKey(ShaderPack.Stage.TESS_EVALUATION))missing.putIfAbsent(Feature.TESSELLATION_STAGES,path);
        }
        for(var entry:new TreeMap<>(configuration.properties()).entrySet()){
            String key=entry.getKey();
            if((key.startsWith("texture.")||key.startsWith("customTexture."))&&!ShaderPackTextures.supported(entry.getValue()))missing.putIfAbsent(Feature.CUSTOM_TEXTURES,key+"="+entry.getValue().strip());
            if(key.equals("iris.features.required")&&!entry.getValue().isBlank())missing.putIfAbsent(Feature.REQUIRED_FEATURES,entry.getValue().strip());
        }
        return missing.entrySet().stream().map(entry->new Missing(entry.getKey(),entry.getValue())).toList();
    }
    public static List<Approximate> approximations(ShaderPack pack,Map<String,String> selected)throws IOException{
        var configuration=new ShaderPackConfiguration(pack,selected);var options=new ShaderPackOptions(pack);
        Map<Approximation,String> result=new EnumMap<>(Approximation.class);
        for(String dimension:new TreeSet<>(pack.dimensions()))for(var program:new TreeMap<>(pack.programs(dimension)).values()){
            if(!configuration.enabled(dimension,program.name()))continue;String path=(dimension.isEmpty()?"":dimension+"/")+program.name();
            if(program.phase()==ShaderPack.Phase.SHADOW){
                // The shadow map reuses the chunk list culled for the player camera, and draws terrain only.
                result.putIfAbsent(Approximation.SHADOW_CULLING,path);
                if(!"false".equals(configuration.properties().get("shadowEntities"))||!"false".equals(configuration.properties().getOrDefault("shadowBlockEntities","true")))result.putIfAbsent(Approximation.SHADOW_ENTITIES,path);
            }
            if(program.phase()!=ShaderPack.Phase.GBUFFER)continue;result.putIfAbsent(Approximation.HAND_DEPTH,path);
            for(var source:program.stages().values()){
                if(result.containsKey(Approximation.BLOCK_IDS)&&result.containsKey(Approximation.ENTITY_IDS))break;
                String active=ShaderPackPreprocessor.active(options.apply(source.text(),configuration.values())).replaceAll("(?s)/\\*.*?\\*/|//[^\\r\\n]*"," ");
                if(BLOCK_ATTRIBUTES.matcher(active).find())result.putIfAbsent(Approximation.BLOCK_IDS,path);
                if(ENTITY_UNIFORMS.matcher(active).find())result.putIfAbsent(Approximation.ENTITY_IDS,path);
            }
        }
        for(String key:new TreeSet<>(configuration.properties().keySet()))if(RENDERER_SETTINGS.contains(key))result.putIfAbsent(Approximation.RENDERER_SETTINGS,key+"="+configuration.properties().get(key));
        return result.entrySet().stream().map(entry->new Approximate(entry.getKey(),entry.getValue())).toList();
    }
}
