package github.com.gengyoubo.vulkanforge.shaderpack;

import java.util.*;
import java.util.regex.*;

/** Standard #define options and shaders.properties profiles; profiles are data, never executed code. */
public final class ShaderPackOptions {
    public record Option(String name,String defaultValue,List<String> values,boolean toggle,String description){public Option{values=List.copyOf(values);}}
    private static final Pattern DEFINE=Pattern.compile("^(\\s*)(//\\s*)?#\\s*define\\s+([A-Za-z_]\\w*)(?:[ \\t]+([^/\\r\\n]+?))?[ \\t]*(?://[ \\t]*(.*))?$");
    private static final Pattern CHOICES=Pattern.compile("\\[([^]]+)]");
    private static final Pattern CONSTANT=Pattern.compile("^(\\s*)const\\s+(bool|int|float)\\s+(\\w+)\\s*=\\s*([^;]+)\\s*;\\s*//\\s*(.*)$");
    private final Map<String,Option> options;
    private final Map<String,String> profiles;
    public ShaderPackOptions(ShaderPack pack){
        Map<String,Option> found=new TreeMap<>();profiles=new TreeMap<>();pack.properties().forEach((key,value)->{if(key.startsWith("profile."))profiles.put(key.substring(8),value);});
        String declared=String.join(" ",pack.properties().values());
        Set<String> declaredNames=new HashSet<>();Matcher names=Pattern.compile("[A-Za-z_]\\w*").matcher(declared);while(names.find())declaredNames.add(names.group());
        Set<String> seen=new HashSet<>(),seenDefinitions=new HashSet<>();
        for(String dimension:new TreeSet<>(pack.dimensions()))for(var program:new TreeMap<>(pack.programs(dimension)).values())for(var source:new TreeMap<>(program.stages()).values())if(seen.add(source.path()))for(String line:source.text().split("\\R")){
            if((!line.contains("const")&&!line.contains("define"))||!seenDefinitions.add(line))continue;
            Matcher constant=CONSTANT.matcher(line);if(constant.matches()){Matcher choices=CHOICES.matcher(constant.group(5));if(choices.find()){var values=new ArrayList<>(Arrays.asList(choices.group(1).strip().split("\\s+")));String initial=constant.group(4).strip();if(!values.contains(initial))values.add(initial);found.putIfAbsent(constant.group(3),new Option(constant.group(3),initial,values,false,constant.group(5).replaceAll("\\[[^]]*]","").strip()));}continue;}
            Matcher define=DEFINE.matcher(line);if(!define.matches())continue;String name=define.group(3),raw=define.group(4),description=Objects.requireNonNullElse(define.group(5),"");boolean toggle=raw==null||raw.isBlank();Matcher choices=CHOICES.matcher(description);
            List<String> values=choices.find()?Arrays.asList(choices.group(1).strip().split("\\s+")):toggle&&declaredNames.contains(name)?List.of("false","true"):List.of();
            if(values.isEmpty())continue;String initial=toggle?Boolean.toString(define.group(2)==null):raw.strip();if(!values.contains(initial)){values=new ArrayList<>(values);values.add(initial);}
            found.putIfAbsent(name,new Option(name,initial,values,toggle,description.replaceAll("\\[[^]]*]","").strip()));
        }
        options=Map.copyOf(found);
    }
    public Map<String,Option> options(){return options;}
    public Set<String> profiles(){return Collections.unmodifiableSet(profiles.keySet());}
    public Map<String,String> defaults(){Map<String,String> result=new TreeMap<>();options.forEach((name,option)->result.put(name,option.defaultValue()));return result;}
    public Map<String,String> profile(String name){Map<String,String> values=defaults();applyProfile(name,new LinkedHashSet<>(),values);return Map.copyOf(values);}
    private void applyProfile(String name,Set<String> visiting,Map<String,String> values){
        if(!visiting.add(name))throw new ShaderPackException("Profile inheritance cycle: "+visiting+" → "+name);String definition=profiles.get(name);if(definition==null)throw new ShaderPackException("Unknown shader profile: "+name);
        for(String item:definition.strip().split("\\s+")){
            if(item.isEmpty())continue;if(item.startsWith("profile.")){applyProfile(item.substring(8),visiting,values);continue;}
            String[] pair=item.split("[:=]",2);String option=pair[0].startsWith("!")?pair[0].substring(1):pair[0],value=pair.length==2?pair[1]:pair[0].startsWith("!")?"false":"true";
            if(option.startsWith("program.")){values.put(option,value);continue;}validate(option,value);values.put(option,value);
        }visiting.remove(name);
    }
    public void validate(String name,String value){Option option=options.get(name);if(option==null)throw new ShaderPackException("Unknown shader option: "+name);if(!option.values().contains(value))throw new ShaderPackException("Invalid value for "+name+": "+value);}
    public String apply(String source,Map<String,String> values){
        for(var entry:values.entrySet())if(!entry.getKey().startsWith("program."))validate(entry.getKey(),entry.getValue());
        StringBuilder output=new StringBuilder();for(String line:source.split("\\R",-1)){
            if(!line.contains("const")&&!line.contains("define")){output.append(line).append('\n');continue;}
            Matcher constant=CONSTANT.matcher(line);if(constant.matches()&&options.containsKey(constant.group(3))&&values.containsKey(constant.group(3)))line=constant.group(1)+"const "+constant.group(2)+" "+constant.group(3)+" = "+values.get(constant.group(3))+"; // "+constant.group(5);
            Matcher define=DEFINE.matcher(line);if(define.matches()&&values.containsKey(define.group(3))&&options.containsKey(define.group(3))){Option option=options.get(define.group(3));String value=values.get(option.name());line=define.group(1)+(option.toggle()&&value.equals("false")?"//":"")+"#define "+option.name()+(option.toggle()?"":" "+value)+(define.group(5)==null?"":" // "+define.group(5));}
            output.append(line).append('\n');
        }return output.toString();
    }
}
