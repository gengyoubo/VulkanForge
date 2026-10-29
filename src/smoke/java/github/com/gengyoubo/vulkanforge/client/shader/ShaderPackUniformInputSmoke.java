package github.com.gengyoubo.vulkanforge.client.shader;

import github.com.gengyoubo.vulkanforge.shaderpack.*;
import java.nio.file.*;
import java.util.*;

/** The real loader's preview input table must cover the original pack, without supplying a test-only input map. */
public final class ShaderPackUniformInputSmoke {
    public static void main(String[] args)throws Exception{
        for(String arg:args)verify(Path.of(arg));
    }
    private static void verify(Path path)throws Exception{
        ShaderPack pack=ShaderPack.load(path);var configuration=new ShaderPackConfiguration(pack,Map.of());
        Map<String,float[]> input=ShaderPackUniforms.preview();
        require(input.containsKey("endFlashIntensity")&&input.get("endFlashIntensity")[0]==0,"Missing 1.20.1 end-flash semantic");
        require(input.containsKey("BIOME_PALE_GARDEN")&&input.containsKey("BIOME_SULFUR_CAVES"),"Newer pack biome names unavailable in the preview table");
        var expressions=new ShaderPackExpressions(configuration.properties());var output=expressions.evaluate(input,0);
        require(!output.isEmpty(),"Original pack expressions were skipped");
        if(pack.name().contains("Complementary")){require(output.size()>=22,"Original Complementary expressions were skipped");require(output.get("endFlashIntensityM")[0]==0,"A nonexistent end flash affected the output");}
        if(pack.name().contains("MakeUp")){float random=output.get("ditherShift")[0];require(random>=0&&random<1,"MakeUp ditherShift is outside the random range");require(Float.isFinite(output.get("fovYInverse")[0]),"MakeUp matrix expression failed");}
        for(var entry:output.entrySet())for(float value:entry.getValue())require(Float.isFinite(value),"Non-finite preview uniform: "+entry.getKey());
        try{new ShaderPackExpressions(Map.of("uniform.float.bad","misspelledInput")).evaluate(input,0);throw new AssertionError("Unknown inputs were silently replaced with zero");}catch(ShaderPackException expected){}
        System.out.println("OWN_PACK_UNIFORM_INPUTS=OK original="+pack.name()+" evaluated="+output.size()+" missing=0 externalLoader=NONE");
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
