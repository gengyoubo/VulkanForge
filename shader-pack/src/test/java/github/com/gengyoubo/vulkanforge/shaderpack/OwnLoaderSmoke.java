package github.com.gengyoubo.vulkanforge.shaderpack;

import java.nio.file.*;
import java.util.*;

/** Exercises VulkanForge-owned planning and expression semantics with no Minecraft, Iris, or Oculus classes. */
public final class OwnLoaderSmoke {
    public static void main(String[] args)throws Exception{
        expressions();
        if(args.length>0){
            ShaderPack pack=ShaderPack.load(Path.of(args[0]));ShaderPackOptions options=new ShaderPackOptions(pack);
            require(options.options().containsKey("shadowDistance"),"Constant-valued option was lost");Map<String,String> high=options.profile("HIGH");require(high.get("shadowDistance").equals("192.0")&&high.get("SHADOW_QUALITY").equals("2"),"Existing pack's equals-style profile failed");
            ShaderPackRenderPlan plan=new ShaderPackRenderPlan(pack,Map.of());var world=plan.dimension("world0");require(world.world().containsKey("gbuffers_terrain")&&!world.shadow().isEmpty(),"Actual world/shadow programs missing");
            require(world.deferred().stream().filter(pass->pass.source().name().equals("deferred1")).anyMatch(pass->Arrays.equals(pass.outputs(),new int[]{0,5,4})),"Wrong active MRT outputs");
            require(world.shadow().stream().noneMatch(pass->pass.source().stages().containsKey(ShaderPack.Stage.COMPUTE)),"Disabled default compute stage entered graph");
            Map<String,float[]> globals=new HashMap<>();for(String biome:List.of("BASALT_DELTAS","CRIMSON_FOREST","NETHER_WASTES","PALE_GARDEN","SULFUR_CAVES","SOUL_SAND_VALLEY","WARPED_FOREST"))globals.put("BIOME_"+biome,new float[]{-100-biome.hashCode()%10000});
            globals.putAll(Map.ofEntries(Map.entry("biome",new float[]{1}),Map.entry("biome_precipitation",new float[]{1}),Map.entry("eyeBrightness",new float[]{240,240}),Map.entry("frameCounter",new float[]{7}),Map.entry("frameTime",new float[]{.016f}),Map.entry("isEyeInWater",new float[]{0}),Map.entry("eyeAltitude",new float[]{68}),Map.entry("rainStrength",new float[]{0}),Map.entry("blindness",new float[]{0}),Map.entry("darknessFactor",new float[]{0}),Map.entry("endFlashIntensity",new float[]{0}),Map.entry("cameraPosition",new float[]{1,68,2}),Map.entry("previousCameraPosition",new float[]{1,68,2})));
            var values=plan.expressions().evaluate(globals,.016f);require(values.size()>=20&&values.get("framemod4")[0]==3,"Actual pack expressions not evaluated");
            var chain=world.chain();require(!chain.isEmpty()&&chain.get(chain.size()-1).source().name().equals("final"),"Fullscreen chain missing final");
            Set<Integer> state=new HashSet<>();for(var pass:chain){require(pass.alternate().equals(state),"Flip schedule diverges at "+pass.source().name());require(world.colors().keySet().containsAll(pass.alternate()),"Flipped buffer without image");if(pass!=world.last())for(int target:pass.flips())if(!state.remove(target))state.add(target);}
            require(world.flipped().equals(state),"End-of-frame flip state");
            var missing=ShaderPackSupport.missing(pack,Map.of()).stream().map(ShaderPackSupport.Missing::feature).toList();
            require(missing.isEmpty(),"Preflight blocks the real pack's default configuration: "+missing);
            require(ShaderPackSupport.approximations(pack,Map.of()).stream().anyMatch(item->item.approximation()==ShaderPackSupport.Approximation.SHADOW_CULLING),"Shadow culling stand-in not reported");
            require(world.shadowPrograms().containsKey("shadow")&&world.shadowColors().keySet().equals(Set.of(0,1))&&world.shadowResolution()==2048&&world.shadowFiltering()[0]&&world.shadowFiltering()[1],"Shadow map configuration");
            // The pack's own POTATO profile disables its shadow program, leaving only reported approximations.
            var potato=options.profile("POTATO");require(ShaderPackSupport.missing(pack,potato).isEmpty(),"POTATO profile still blocked: "+ShaderPackSupport.missing(pack,potato));
            var approximations=ShaderPackSupport.approximations(pack,potato);require(approximations.stream().anyMatch(item->item.approximation()==ShaderPackSupport.Approximation.BLOCK_IDS),"Block ID stand-ins not reported");
            System.out.println("OWN_PACK_PREFLIGHT="+ShaderPackSupport.missing(pack,Map.of())+" POTATO_APPROXIMATIONS="+approximations);
            System.out.println("OWN_PACK_PLAN=OK dimensions="+plan.dimensions().keySet()+" world="+world.world().size()+" deferred="+world.deferred().size()+" composite="+world.composite().size()+" colors="+world.colors()+" uniforms="+values.size());
        }
        System.out.println("OWN_LOADER_CPU_SMOKE=OK (no external loader)");
    }
    private static void expressions(){
        var randoms=new ShaderPackExpressions(Map.of("variable.float.shared","random()","uniform.float.a","shared","uniform.float.b","shared","uniform.float.range","random(-5,3)","uniform.float.wrap","fmod(-3,2)","uniform.float.matrix","projection.2.1"));
        float previous=-1;boolean changed=false;
        for(int frame=0;frame<128;frame++){var result=randoms.evaluate(Map.of("projection",new float[]{0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15}),.016f);float a=result.get("a")[0],b=result.get("b")[0],range=result.get("range")[0];require(a>=0&&a<1&&a==b,"Random is outside bounds or changes through aliases within one frame");require(range>=-5&&range<3,"Random range bounds failed");require(result.get("wrap")[0]==1&&result.get("matrix")[0]==9,"Floor modulus or column-major matrix indexing failed");if(frame>0)changed|=a!=previous;previous=a;}require(changed,"Random does not advance between frames");
        var expressions=new ShaderPackExpressions(Map.of("variable.float.distance","length(camera - previous)","uniform.float.motion","if(distance > 0 && distance < 10, smooth(1, distance, 1, 1), 0)","uniform.vec3.tint","mix(vec3(0), vec3(1,2,3), 0.5)","uniform.float.lazy","if(true, 4, missingInput)","uniform.bool.member","in(2, 1, 2, 3)"));
        var input=new HashMap<>(Map.of("camera",new float[]{3,0,0},"previous",new float[3]));var first=expressions.evaluate(input,1);require(first.get("motion")[0]==3&&first.get("lazy")[0]==4&&first.get("member")[0]==1&&first.get("tint")[2]==1.5f,"Expression evaluation or lazy branch failed");
        input.put("camera",new float[]{5,0,0});require(Math.abs(expressions.evaluate(input,1).get("motion")[0]-4)<.0001,"Temporal smoothing state failed");
        try{new ShaderPackExpressions(Map.of("variable.float.a","b","uniform.float.b","a")).evaluate(Map.of(),.1f);throw new AssertionError("Expression cycle accepted");}catch(ShaderPackException expected){}
        try{new ShaderPackExpressions(Map.of("uniform.float.x","java.lang.Runtime()")).evaluate(Map.of(),.1f);throw new AssertionError("Executable expression accepted");}catch(ShaderPackException expected){}
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
