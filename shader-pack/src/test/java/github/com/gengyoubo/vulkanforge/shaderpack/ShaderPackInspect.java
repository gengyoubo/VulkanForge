package github.com.gengyoubo.vulkanforge.shaderpack;

import java.nio.file.Path;
import java.util.*;

/** Read-only inspection of a real pack; never modifies the ZIP or launches a graphics context. */
public final class ShaderPackInspect {
    public static void main(String[] files)throws Exception{
        for(String file:files){ShaderPack pack=ShaderPack.load(Path.of(file));System.out.println("PACK="+pack.name());
            var configuration=new ShaderPackConfiguration(pack,Map.of());System.out.println("EFFECTIVE_PROPERTIES="+configuration.properties().size());
            for(String dimension:new TreeSet<>(pack.dimensions())){
                System.out.println("DIMENSION="+dimension+" PROGRAMS="+pack.programs(dimension).size());
                var program=pack.programs(dimension).get("deferred1");if(program!=null)System.out.println("DEFERRED1_OUTPUTS="+Arrays.toString(program.drawBuffers()));
                for(var phase:ShaderPack.Phase.values()){var passes=configuration.passes(dimension,phase);if(!passes.isEmpty())System.out.println(phase+"="+passes.stream().map(ShaderPack.Program::name).toList());}
            }
            for(var missing:ShaderPackSupport.missing(pack,Map.of()))System.out.println("MISSING_"+missing.feature()+"="+missing.example());
            String dump=System.getProperty("shaderPack.dump");if(dump!=null){Path output=Path.of(dump);java.nio.file.Files.createDirectories(output);var options=new ShaderPackOptions(pack);var uniformNames=new TreeSet<String>();
                for(String dimension:new TreeSet<>(pack.dimensions()))for(var program:new TreeMap<>(pack.programs(dimension)).values())if(configuration.enabled(dimension,program.name()))for(var stage:program.stages().entrySet()){
                    String active=ShaderPackPreprocessor.active(options.apply(stage.getValue().text(),configuration.values()));Path folder=output.resolve(dimension);java.nio.file.Files.createDirectories(folder);java.nio.file.Files.writeString(folder.resolve(program.name()+"."+stage.getKey().extension),active);
                    var declarations=java.util.regex.Pattern.compile("(?m)^\\s*(?:uniform|varying|attribute|flat\\s+(?:in|out))\\s+[^;{]+;").matcher(active);while(declarations.find())uniformNames.add(declarations.group().strip());
                }java.nio.file.Files.write(output.resolve("interfaces.txt"),uniformNames);java.nio.file.Files.write(output.resolve("properties.txt"),new TreeMap<>(configuration.properties()).entrySet().stream().map(entry->entry.getKey()+"="+entry.getValue()).toList());
            }
        }
    }
}
