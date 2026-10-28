package github.com.gengyoubo.vulkanforge.shaderpack;

import java.io.*;
import java.util.*;
import java.util.regex.*;

/** Resolves pack-local includes with stable compiler source IDs and bounded expansion. */
public final class ShaderPackIncludes {
    public record Source(String path,String text,Map<Integer,String> sourceNames){public Source{sourceNames=Map.copyOf(sourceNames);}}
    private static final Pattern INCLUDE=Pattern.compile("^\\s*#\\s*include\\s+\"([^\"]+)\"\\s*(?://.*)?$");
    private static final int MAX_EXPANDED=16<<20;
    private final ShaderPackArchive archive;
    public ShaderPackIncludes(ShaderPackArchive archive){this.archive=archive;}
    public Source expand(String requested)throws IOException {
        String path=ShaderPackArchive.normalize(requested);Map<String,Integer> ids=new LinkedHashMap<>();StringBuilder output=new StringBuilder();expand(path,new ArrayDeque<>(),new HashSet<>(),ids,output);
        Map<Integer,String> names=new LinkedHashMap<>();ids.forEach((name,id)->names.put(id,name));return new Source(path,output.toString(),names);
    }
    private void expand(String path,Deque<String> stack,Set<String> once,Map<String,Integer> ids,StringBuilder output)throws IOException {
        if(once.contains(path))return;
        if(stack.contains(path))throw new ShaderPackException("Shader include cycle: "+String.join(" → ",stack)+" → "+path);
        if(stack.size()>=64)throw new ShaderPackException("Shader include depth exceeds 64 at "+path);
        int id=ids.computeIfAbsent(path,unused->ids.size());String source=archive.text(path);stack.addLast(path);
        try {
            String[] lines=source.split("\\R",-1);
            for(int line=0;line<lines.length;line++){
                String text=lines[line];Matcher include=INCLUDE.matcher(text);
                if(text.strip().equals("#pragma once")){once.add(path);output.append('\n');}
                else if(include.matches()){
                    String child=ShaderPackArchive.resolve(path,include.group(1));int childId=ids.computeIfAbsent(child,unused->ids.size());
                    output.append("#line 1 ").append(childId).append('\n');
                    try{expand(child,stack,once,ids,output);}catch(IOException|ShaderPackException error){throw new ShaderPackException(path+":"+(line+1)+": "+error.getMessage(),error);}
                    output.append("#line ").append(line+2).append(' ').append(id).append('\n');
                } else {if(stack.size()>1&&text.stripLeading().startsWith("#version"))throw new ShaderPackException(path+":"+(line+1)+": included file declares #version");output.append(text).append('\n');}
                if(output.length()>MAX_EXPANDED)throw new ShaderPackException("Expanded shader exceeds 16 MiB: "+stack.getFirst());
            }
        }finally{stack.removeLast();}
    }
}
