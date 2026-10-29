package github.com.gengyoubo.vulkanforge.client;

import java.util.*;
import java.util.regex.*;

/** Source uniform metadata for the compatibility frontend; optimized opaque bindings come from SPIR-V. */
final class NativeUniformInfo {
    record Uniform(String name,String type,int count,boolean array){String activeName(){return array?name+"[0]":name;}}
    private static final Pattern DECLARATION=Pattern.compile("\\buniform\\s+(?:(?:lowp|mediump|highp)\\s+)?(\\w+)\\s+(\\w+)(?:\\s*\\[\\s*(\\d+)\\s*])?(?:\\s*=\\s*[^;]+)?\\s*;");
    static List<Uniform> read(String source,Set<String> samplers){
        Map<String,Uniform> found=new LinkedHashMap<>();Matcher declarations=DECLARATION.matcher(source.replaceAll("(?s)/\\*.*?\\*/|//[^\\r\\n]*"," "));
        while(declarations.find()){String type=declarations.group(1),name=declarations.group(2);if(type.startsWith("sampler")&&!samplers.contains(name))continue;found.putIfAbsent(name,new Uniform(name,type,declarations.group(3)==null?1:Integer.parseInt(declarations.group(3)),declarations.group(3)!=null));}return List.copyOf(found.values());
    }
    static int type(String type){return switch(type){case "float"->5126;case "int"->5124;case "uint"->5125;case "bool"->35670;case "vec2"->35664;case "vec3"->35665;case "vec4"->35666;case "ivec2"->35667;case "ivec3"->35668;case "ivec4"->35669;case "uvec2"->36294;case "uvec3"->36295;case "uvec4"->36296;case "bvec2"->35671;case "bvec3"->35672;case "bvec4"->35673;case "mat2"->35674;case "mat3"->35675;case "mat4"->35676;case "sampler1D"->35677;case "sampler2D"->35678;case "sampler3D"->35679;case "samplerCube"->35680;case "sampler1DShadow"->35681;case "sampler2DShadow"->35682;default->throw new UnsupportedOperationException("Native uniform metadata type "+type);};}
}
