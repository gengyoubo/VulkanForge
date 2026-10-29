package github.com.gengyoubo.vulkanforge.shaderpack;

import java.io.*;
import java.util.*;

/** A pack's effective properties and stage selection for one option set. */
public final class ShaderPackConfiguration {
    private final ShaderPack pack;
    private final ShaderPackOptions options;
    private final Map<String,String> values,properties,macros;
    public ShaderPackConfiguration(ShaderPack pack,Map<String,String> selected)throws IOException{
        this.pack=pack;options=new ShaderPackOptions(pack);Map<String,String> merged=options.defaults();merged.putAll(selected);values=Map.copyOf(merged);
        Map<String,String> macros=ShaderPackPreprocessor.environment();
        for(var option:options.options().values()){
            String value=values.get(option.name());if(option.toggle()){if(Boolean.parseBoolean(value))macros.put(option.name(),"1");}else macros.put(option.name(),value);
        }
        this.macros=Map.copyOf(macros);
        String raw;try(var archive=ShaderPackArchive.open(pack.path())){raw=archive.exists("shaders.properties")?archive.text("shaders.properties"):"";}
        var processed=ShaderPackPreprocessor.process(raw,macros);Properties decoded=new Properties();decoded.load(new StringReader(processed.text()));
        Map<String,String> result=new TreeMap<>();for(String key:decoded.stringPropertyNames())result.put(key,key.startsWith("screen")||key.startsWith("profile.")||key.equals("sliders")?decoded.getProperty(key):ShaderPackPreprocessor.expandValue(decoded.getProperty(key),processed.macros()));properties=Map.copyOf(result);
    }
    public Map<String,String> values(){return values;}
    public Map<String,String> properties(){return properties;}
    /** Preprocessor definitions for pack data files: platform macros plus the effective option values. */
    public Map<String,String> macros(){return macros;}
    public boolean enabled(String dimension,String program){
        String prefix=dimension.isEmpty()?program:dimension+"/"+program;
        String value=values.getOrDefault("program."+program,properties.getOrDefault("program."+prefix+".enabled",properties.getOrDefault("program."+program+".enabled","true")));
        if(value.equals("true"))return true;if(value.equals("false"))return false;
        Map<String,String> definitions=new HashMap<>(ShaderPackPreprocessor.environment());definitions.putAll(values);definitions.put("true","1");definitions.put("false","0");return ShaderPackPreprocessor.condition(value,definitions);
    }
    public List<ShaderPack.Program> passes(String dimension,ShaderPack.Phase phase){return pack.passes(dimension,phase).stream().filter(program->enabled(dimension,program.name())).toList();}
}
