package github.com.gengyoubo.vulkanforge.shaderpack;

import java.util.*;
import java.util.regex.*;

/** Legacy fullscreen GLSL interface normalization shared by the GL compiler and Vulkan translator. */
public final class ShaderPackGlsl {
    public record Graphics(String vertex,String fragment,Map<String,Integer> attributes){public Graphics{attributes=Map.copyOf(attributes);}}
    private static final String PROJECTION="mat4(2,0,0,0, 0,2,0,0, 0,0,1,0, -1,-1,0,1)";
    private ShaderPackGlsl(){ }
    public static Graphics fullscreen(ShaderPack.Program program,ShaderPackOptions options,Map<String,String> values){
        for(ShaderPack.Stage stage:program.stages().keySet())if(stage!=ShaderPack.Stage.VERTEX&&stage!=ShaderPack.Stage.FRAGMENT)throw new ShaderPackException(program.name()+": "+stage+" stage is not implemented by the fullscreen renderer");
        var fragment=program.stages().get(ShaderPack.Stage.FRAGMENT);if(fragment==null)throw new ShaderPackException(program.name()+": missing fragment shader");
        String vertex=program.stages().containsKey(ShaderPack.Stage.VERTEX)?options.apply(program.stages().get(ShaderPack.Stage.VERTEX).text(),values):"#version 120\nvarying vec2 texcoord;\nvoid main(){texcoord=gl_MultiTexCoord0.xy;gl_Position=ftransform();}";
        String pixel=options.apply(fragment.text(),values);Set<Integer> legacyCoords=new TreeSet<>();Matcher coords=Pattern.compile("\\bgl_TexCoord\\s*\\[\\s*([0-7])\\s*]").matcher(vertex+"\n"+pixel);while(coords.find())legacyCoords.add(Integer.parseInt(coords.group(1)));
        boolean legacyColor=vertex.contains("gl_FrontColor")||pixel.contains("gl_Color");
        return new Graphics(normalize(vertex,true,legacyCoords,legacyColor),normalize(pixel,false,legacyCoords,legacyColor),Map.of("Position",0,"UV0",1,"vaPosition",0,"vaUV0",1));
    }
    private static String normalize(String source,boolean vertex,Set<Integer> legacyCoords,boolean legacyColor){
        source=source.replaceFirst("#version\\s+\\d+(?:[ \\t]+(?:core|compatibility))?","#version 330 core");if(!source.stripLeading().startsWith("#version"))source="#version 330 core\n"+source;
        for(int index:legacyCoords)source=source.replaceAll("\\bgl_TexCoord\\s*\\[\\s*"+index+"\\s*]","vfTexCoord"+index);
        Matcher outputs=Pattern.compile("\\bgl_FragData\\s*\\[\\s*([^]]+)\\s*]").matcher(source);StringBuffer rewritten=new StringBuffer();boolean legacyOutput=false;
        while(outputs.find()){if(!outputs.group(1).strip().equals("0"))throw new ShaderPackException("Fullscreen fragment requires multiple output locations");outputs.appendReplacement(rewritten,"vfFragColor");legacyOutput=true;}outputs.appendTail(rewritten);source=rewritten.toString();
        if(source.contains("gl_FragColor")){source=source.replaceAll("\\bgl_FragColor\\b","vfFragColor");legacyOutput=true;}
        StringBuilder prefix=new StringBuilder("\n#define MC_VERSION 12001\n");
        if(vertex){
            if((source.contains("gl_Vertex")||source.contains("ftransform"))&&!Pattern.compile("\\b(?:in|attribute)\\s+\\w+\\s+Position\\s*;").matcher(source).find())prefix.append("in vec3 Position;\n");
            if((source.contains("gl_MultiTexCoord")||!legacyCoords.isEmpty())&&!Pattern.compile("\\b(?:in|attribute)\\s+\\w+\\s+UV0\\s*;").matcher(source).find())prefix.append("in vec2 UV0;\n");
            source=source.replaceAll("\\bftransform\\s*\\(\\s*\\)","("+PROJECTION+" * vec4(Position, 1.0))");
            source=source.replaceAll("\\bgl_TextureMatrix\\s*\\[\\s*[0-7]\\s*]","mat4(1.0)");
            source=replace(source,Map.ofEntries(Map.entry("attribute","in"),Map.entry("varying","out"),Map.entry("gl_Vertex","vec4(Position,1.0)"),Map.entry("gl_ModelViewProjectionMatrix",PROJECTION),Map.entry("gl_ProjectionMatrix",PROJECTION),Map.entry("gl_ModelViewMatrix","mat4(1.0)"),Map.entry("gl_NormalMatrix","mat3(1.0)"),Map.entry("gl_Normal","vec3(0,0,1)"),Map.entry("gl_Color","vec4(1.0)"),Map.entry("gl_FrontColor","vfVertexColor")));
            for(int i=0;i<8;i++)source=source.replaceAll("\\bgl_MultiTexCoord"+i+"\\b","vec4(UV0,0,1)");
        }else source=replace(source,Map.of("varying","in","gl_Color","vfVertexColor"));
        source=replace(source,Map.of("texture2D","texture","texture2DLod","textureLod","texture2DGradARB","textureGrad","texture2DProj","textureProj","texture3D","texture","textureCube","texture"));
        for(int index:legacyCoords)prefix.append(vertex?"out":"in").append(" vec4 vfTexCoord").append(index).append(";\n");
        if(legacyColor)prefix.append(vertex?"out":"in").append(" vec4 vfVertexColor;\n");
        if(!vertex&&legacyOutput)prefix.append("out vec4 vfFragColor;\n");
        if(vertex&&(!legacyCoords.isEmpty()||legacyColor)){
            source=source.replaceFirst("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)","void vfPackMain()");StringBuilder main=new StringBuilder("\nvoid main(){");for(int index:legacyCoords)main.append("vfTexCoord").append(index).append("=vec4(UV0,0,1);");if(legacyColor)main.append("vfVertexColor=vec4(1);");source+=main+"vfPackMain();}\n";
        }
        int end=source.indexOf('\n');return source.substring(0,end+1)+prefix+source.substring(end+1);
    }
    private static String replace(String source,Map<String,String> identifiers){
        // Token boundaries prevent replacing names which merely contain a legacy identifier.
        Matcher tokens=Pattern.compile("\\b[A-Za-z_]\\w*\\b").matcher(source);StringBuffer result=new StringBuffer();while(tokens.find())tokens.appendReplacement(result,Matcher.quoteReplacement(identifiers.getOrDefault(tokens.group(),tokens.group())));tokens.appendTail(result);return result.toString();
    }
}
