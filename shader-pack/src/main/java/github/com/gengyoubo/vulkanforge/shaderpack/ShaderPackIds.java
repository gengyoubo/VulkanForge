package github.com.gengyoubo.vulkanforge.shaderpack;

import java.io.*;
import java.util.*;

/** block/item/entity.properties: numeric IDs by registry name, after the same option preprocessing as shaders.properties. */
public final class ShaderPackIds {
    private final Map<String,Integer> ids;
    private ShaderPackIds(Map<String,Integer> ids){this.ids=Map.copyOf(ids);}
    /** {@code kind} is block, item or entity; a missing file yields no IDs. */
    public static ShaderPackIds load(ShaderPack pack,ShaderPackConfiguration configuration,String kind)throws IOException{
        String raw;try(var archive=ShaderPackArchive.open(pack.path())){raw=archive.exists(kind+".properties")?archive.text(kind+".properties"):"";}
        Properties decoded=new Properties();decoded.load(new StringReader(ShaderPackPreprocessor.process(raw,configuration.macros()).text()));
        Map<String,Integer> ids=new HashMap<>();
        for(String key:decoded.stringPropertyNames()){
            if(!key.startsWith(kind+"."))continue;int id;try{id=Integer.parseInt(key.substring(kind.length()+1));}catch(NumberFormatException error){throw new ShaderPackException(kind+".properties: invalid id "+key,error);}
            for(String entry:decoded.getProperty(key).strip().split("\\s+"))if(!entry.isEmpty())ids.putIfAbsent(name(entry),id);
        }
        return new ShaderPackIds(ids);
    }
    /** Strips block-state suffixes and defaults the namespace: {@code oak_leaves:persistent=true} → {@code minecraft:oak_leaves}. */
    static String name(String entry){
        String[] parts=entry.split(":");int end=parts.length;while(end>1&&parts[end-1].contains("="))end--;
        return end==1?"minecraft:"+parts[0]:parts[0]+":"+parts[1];
    }
    /** -1 when unmapped, matching an unknown ID in the reference loaders. */
    public int id(String registryName){return ids.getOrDefault(registryName,-1);}
    public int size(){return ids.size();}
}
