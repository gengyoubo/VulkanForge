package github.com.gengyoubo.vulkanforge.shaderpack;

import java.util.*;
import java.util.regex.*;
import java.util.function.Function;

/** Legacy GLSL interface normalization shared by the GL compiler and Vulkan translator. */
public final class ShaderPackGlsl {
    public record Graphics(String vertex,String fragment,Map<String,Integer> attributes){public Graphics{attributes=Map.copyOf(attributes);}}
    /**
     * A world program bound to an engine shader interface. {@code engineUniforms}/{@code engineSamplers} are supplied by the engine per draw;
     * {@code uniforms} (name → GLSL type) and {@code samplers} must be bound by the pack runtime, except {@code optional} ones that declare a default.
     */
    public record World(String vertex,String fragment,Set<String> engineUniforms,Set<String> engineSamplers,Map<String,String> uniforms,Set<String> optional,List<String> samplers){
        public World{engineUniforms=Set.copyOf(engineUniforms);engineSamplers=Set.copyOf(engineSamplers);uniforms=Map.copyOf(uniforms);optional=Set.copyOf(optional);samplers=List.copyOf(samplers);}
    }
    /** Where an engine supplies vertex data, matrices and textures; every legacy or core-profile name is rewritten onto it. */
    private record Engine(String position,String model,String projection,String texture0,String normal,String color,String uv0,String light,
                          Map<String,String> core,Map<String,String> coreExpressions,Map<String,String> samplers,Map<String,String> metadata,
                          Map<String,String> attributes,Map<String,String> uniforms,String vertexPrefix,String vertexInit){ }
    /** Embeddium chunk attributes added by the extended chunk vertex format, with their shader locations. */
    public static final Map<String,Integer> CHUNK_ATTRIBUTES=Map.of("vfNormal",10,"vfEntity",11,"vfMidTexCoord",12,"vfTangent",13);
    private static final String PROJECTION="mat4(2,0,0,0, 0,2,0,0, 0,0,1,0, -1,-1,0,1)",LIGHTMAP_MATRIX="mat4(0.00390625,0,0,0, 0,0.00390625,0,0, 0,0,1,0, 0.03125,0.03125,0,1)";
    private static final Map<String,String> ENGINE_UNIFORMS=Map.of("ModelViewMat","mat4","ProjMat","mat4","TextureMat","mat4","ChunkOffset","vec3","ColorModulator","vec4");
    private static final Map<String,String> ATTRIBUTES=Map.of("Position","vec3","Color","vec4","UV0","vec2","UV1","ivec2","UV2","ivec2","Normal","vec3");
    /** Game core shaders carry no block/entity metadata; those attributes take the "unmapped" values. */
    private static final Map<String,String> METADATA=Map.of("mc_Entity","vec4(-1.0, -1.0, 0.0, 0.0)","mc_midTexCoord","vec4(UV0, 0.0, 1.0)","at_tangent","vec4(1.0, 0.0, 0.0, 1.0)","at_midBlock","vec4(0.0)");
    private static final Map<String,String> CORE=Map.ofEntries(Map.entry("vaPosition","Position"),Map.entry("vaColor","Color"),Map.entry("vaUV0","UV0"),Map.entry("vaUV1","UV1"),Map.entry("vaUV2","UV2"),Map.entry("vaNormal","Normal"),Map.entry("modelViewMatrix","ModelViewMat"),Map.entry("projectionMatrix","ProjMat"),Map.entry("textureMatrix","TextureMat"));
    private static final Map<String,String> ALPHA=Map.of("NEVER","false","ALWAYS","true","LESS","<","EQUAL","==","LEQUAL","<=","GREATER",">","NOTEQUAL","!=","GEQUAL",">=");
    private static final Pattern UNIFORM=Pattern.compile("\\buniform\\s+(?:(?:lowp|mediump|highp)\\s+)?(\\w+)\\s+(\\w+(?:\\s*,\\s*\\w+)*)\\s*(=[^;]*)?;");
    private ShaderPackGlsl(){ }
    public static Graphics fullscreen(ShaderPack.Program program,ShaderPackOptions options,Map<String,String> values){
        return fullscreen(program,source->ShaderPackPreprocessor.active(options.apply(source.text(),values)));
    }
    public static Graphics fullscreen(ShaderPack.Program program,ShaderPackConfiguration configuration){return fullscreen(program,configuration::active);}
    private static Graphics fullscreen(ShaderPack.Program program,Function<ShaderPackIncludes.Source,String> active){
        for(ShaderPack.Stage stage:program.stages().keySet())if(stage!=ShaderPack.Stage.VERTEX&&stage!=ShaderPack.Stage.FRAGMENT)throw new ShaderPackException(program.name()+": "+stage+" stage is not implemented by the fullscreen renderer");
        var fragment=program.stages().get(ShaderPack.Stage.FRAGMENT);if(fragment==null)throw new ShaderPackException(program.name()+": missing fragment shader");
        String vertex=program.stages().containsKey(ShaderPack.Stage.VERTEX)?active.apply(program.stages().get(ShaderPack.Stage.VERTEX)):"#version 120\nvarying vec2 texcoord;\nvoid main(){texcoord=gl_MultiTexCoord0.xy;gl_Position=ftransform();}";
        String pixel=active.apply(fragment);Set<Integer> legacyCoords=coords(vertex+"\n"+pixel);
        boolean legacyColor=vertex.contains("gl_FrontColor")||pixel.contains("gl_Color");
        return new Graphics(normalize(vertex,true,legacyCoords,legacyColor,null),normalize(pixel,false,legacyCoords,legacyColor,null),Map.of("Position",0,"UV0",1,"vaPosition",0,"vaUV0",1));
    }
    /**
     * Binds a world program to a game core shader. {@code format} lists the vanilla shader's vertex format elements
     * (Position, Color, UV0, UV1, UV2, Normal); {@code chunkOffset} is true only for shaders that receive a chunk offset (terrain).
     */
    public static World world(ShaderPack.Program program,ShaderPackOptions options,Map<String,String> values,Set<String> format,boolean chunkOffset,ShaderPackRenderPlan.AlphaTest alphaTest){
        return world(program,source->ShaderPackPreprocessor.active(options.apply(source.text(),values)),format,chunkOffset,alphaTest);
    }
    public static World world(ShaderPack.Program program,ShaderPackConfiguration configuration,Set<String> format,boolean chunkOffset,ShaderPackRenderPlan.AlphaTest alphaTest){return world(program,configuration::active,format,chunkOffset,alphaTest);}
    private static World world(ShaderPack.Program program,Function<ShaderPackIncludes.Source,String> active,Set<String> format,boolean chunkOffset,ShaderPackRenderPlan.AlphaTest alphaTest){
        Map<String,String> core=new HashMap<>(CORE),expressions=new HashMap<>(expressions("ModelViewMat","ProjMat"));
        if(chunkOffset)core.put("chunkOffset","ChunkOffset");else expressions.put("chunkOffset","vec3(0.0)");
        Map<String,String> attributes=new HashMap<>(),uniforms=new HashMap<>(ENGINE_UNIFORMS),metadata=new HashMap<>(METADATA);
        ATTRIBUTES.forEach((name,type)->{if(format.contains(name))attributes.put(name,type);});if(!chunkOffset)uniforms.remove("ChunkOffset");if(!format.contains("UV0"))metadata.put("mc_midTexCoord","vec4(0.0)");
        Engine engine=new Engine(chunkOffset?"vec4(Position + ChunkOffset, 1.0)":"vec4(Position, 1.0)","ModelViewMat","ProjMat","TextureMat",format.contains("Normal")?"Normal":"vec3(0.0, 0.0, 1.0)",format.contains("Color")?"(Color * ColorModulator)":"ColorModulator",
            format.contains("UV0")?"vec4(UV0, 0.0, 1.0)":"vec4(0.0, 0.0, 0.0, 1.0)",format.contains("UV2")?"vec4(vec2(UV2), 0.0, 1.0)":"vec4(240.0, 240.0, 0.0, 1.0)",
            core,expressions,Map.of("texture","Sampler0","gtexture","Sampler0","tex","Sampler0","lightmap","Sampler2"),metadata,attributes,uniforms,"",null);
        return translate(program,active,engine,alphaTest);
    }
    /**
     * Binds a world program to Embeddium's chunk shader interface. {@code vertexInclude} is Embeddium's own chunk vertex decoder
     * (uncompressed branch); normals, block IDs, mid texture coordinates and tangents come from the extended chunk vertex format.
     */
    public static World chunks(ShaderPack.Program program,ShaderPackOptions options,Map<String,String> values,String vertexInclude,ShaderPackRenderPlan.AlphaTest alphaTest){
        String normal="(vfNormal.xyz * 2.0 - 1.0)";
        Map<String,String> expressions=new HashMap<>(expressions("u_ModelViewMatrix","u_ProjectionMatrix"));
        expressions.putAll(Map.of("vaPosition","_vert_position","vaColor","_vert_color","vaUV0","_vert_tex_diffuse_coord","vaUV1","ivec2(0, 10)","vaUV2","_vert_tex_light_coord","vaNormal",normal,"modelViewMatrix","u_ModelViewMatrix","projectionMatrix","u_ProjectionMatrix","textureMatrix","mat4(1.0)","chunkOffset","vfChunkTranslation"));
        String prefix=vertexInclude+"\nin vec4 vfNormal;\nin vec2 vfEntity;\nin vec2 vfMidTexCoord;\nin vec4 vfTangent;\n"
            +"uvec3 vfRelativeChunk(uint pos){return uvec3(pos) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u);}\nvec3 vfDrawTranslation(uint pos){return vec3(vfRelativeChunk(pos)) * vec3(16.0);}\n"
            +"#define vfChunkTranslation (u_RegionOffset + vfDrawTranslation(_draw_id))\n";
        Engine engine=new Engine("vec4(_vert_position + vfChunkTranslation, 1.0)","u_ModelViewMatrix","u_ProjectionMatrix","mat4(1.0)",normal,"_vert_color","vec4(_vert_tex_diffuse_coord, 0.0, 1.0)","vec4(vec2(_vert_tex_light_coord), 0.0, 1.0)",
            Map.of(),expressions,Map.of("texture","u_BlockTex","gtexture","u_BlockTex","tex","u_BlockTex","lightmap","u_LightTex"),
            Map.of("mc_Entity","vec4(vfEntity.x > 65534.5 ? -1.0 : vfEntity.x, -1.0, 0.0, 0.0)","mc_midTexCoord","vec4(vfMidTexCoord, 0.0, 1.0)","at_tangent","(vfTangent * 2.0 - 1.0)","at_midBlock","vec4(0.0)"),
            Map.of(),Map.of("u_ModelViewMatrix","mat4","u_ProjectionMatrix","mat4","u_RegionOffset","vec3"),prefix,"_vert_init();");
        return translate(program,source->ShaderPackPreprocessor.active(options.apply(source.text(),values)),engine,alphaTest);
    }
    private static Map<String,String> expressions(String model,String projection){return Map.of("normalMatrix","mat3(transpose(inverse("+model+")))","modelViewMatrixInverse","inverse("+model+")","projectionMatrixInverse","inverse("+projection+")");}
    private static World translate(ShaderPack.Program program,Function<ShaderPackIncludes.Source,String> active,Engine engine,ShaderPackRenderPlan.AlphaTest alphaTest){
        for(ShaderPack.Stage stage:program.stages().keySet())if(stage!=ShaderPack.Stage.VERTEX&&stage!=ShaderPack.Stage.FRAGMENT)throw new ShaderPackException(program.name()+": "+stage+" stage is not implemented for world programs");
        var vertex=program.stages().get(ShaderPack.Stage.VERTEX);var fragment=program.stages().get(ShaderPack.Stage.FRAGMENT);if(vertex==null||fragment==null)throw new ShaderPackException(program.name()+": world program needs both graphics stages");
        String vs=interfaces(active.apply(vertex),engine),fs=interfaces(active.apply(fragment),engine);
        Set<Integer> coords=coords(vs+"\n"+fs);boolean color=vs.contains("gl_FrontColor")||fs.contains("gl_Color");
        String vsOut=normalize(vs,true,coords,color,engine),fsOut=normalize(fs,false,coords,color,engine);
        if(alphaTest!=null)fsOut=alpha(fsOut,alphaTest,program.name());
        String all=strip(vsOut+"\n"+fsOut);
        if(Pattern.compile("\\buniform\\s+\\w+\\s+\\w+\\s*\\[").matcher(all).find()||Pattern.compile("\\buniform\\s+\\w+\\s*\\{").matcher(all).find())throw new ShaderPackException(program.name()+": uniform arrays/blocks require an extended pack uniform layout");
        Set<String> engineUniforms=new TreeSet<>(),engineSamplers=new TreeSet<>(),optional=new TreeSet<>(),samplerTargets=new HashSet<>(engine.samplers().values());Map<String,String> uniforms=new TreeMap<>();List<String> samplers=new ArrayList<>();
        Matcher declarations=UNIFORM.matcher(all);
        while(declarations.find())for(String part:declarations.group(2).split(",")){
            String type=declarations.group(1),name=part.strip();
            if(engine.uniforms().containsKey(name))engineUniforms.add(name);
            else if(samplerTargets.contains(name))engineSamplers.add(name);
            else if(type.contains("sampler")){if(!samplers.contains(name))samplers.add(name);}
            else{uniforms.putIfAbsent(name,type);if(declarations.group(3)!=null)optional.add(name);}
        }
        return new World(vsOut,fsOut,engineUniforms,engineSamplers,uniforms,optional,samplers);
    }
    private static Set<Integer> coords(String source){Set<Integer> result=new TreeSet<>();Matcher matcher=Pattern.compile("\\bgl_TexCoord\\s*\\[\\s*([0-7])\\s*]").matcher(source);while(matcher.find())result.add(Integer.parseInt(matcher.group(1)));return result;}
    private static String strip(String source){return source.replaceAll("(?s)/\\*.*?\\*/|//[^\\r\\n]*"," ");}
    private static boolean declared(String source,String name){return Pattern.compile("\\b(?:uniform|in|attribute)\\s+(?:(?:lowp|mediump|highp|flat)\\s+)?\\w+\\s+"+Pattern.quote(name)+"\\s*(?:=[^;]*)?;").matcher(strip(source)).find();}
    private static String removeDeclaration(String source,String name){return source.replaceAll("(?m)^[ \\t]*(?:uniform|in|attribute)\\s+(?:(?:lowp|mediump|highp|flat)\\s+)?\\w+\\s+"+Pattern.quote(name)+"\\s*(?:=[^;]*)?;","");}
    /** Core-profile names are implicitly declared, as in the reference loader, unless the pack defines a variable of that name itself. */
    private static boolean core(String source,String name){
        if(!source.contains(name)||!Pattern.compile("\\b"+Pattern.quote(name)+"\\b").matcher(source).find())return false;
        if(Pattern.compile("\\b(?:uniform|in|attribute)\\s+(?:(?:lowp|mediump|highp|flat)\\s+)?\\w+\\s+"+Pattern.quote(name)+"\\b").matcher(source).find())return true;
        return !Pattern.compile("\\b(?:float|int|uint|bool|[iub]?vec[234]|mat[234](?:x[234])?)\\s+"+Pattern.quote(name)+"\\b").matcher(source).find();
    }
    /** Iris core-profile names and the albedo/lightmap sampler aliases become the engine's names. */
    private static String interfaces(String source,Engine engine){
        String code=strip(source);Map<String,String> names=new HashMap<>();
        for(var entry:engine.coreExpressions().entrySet())if(core(code,entry.getKey())){source=removeDeclaration(source,entry.getKey());names.put(entry.getKey(),"("+entry.getValue()+")");}
        for(var entry:engine.core().entrySet())if(core(code,entry.getKey()))names.put(entry.getKey(),entry.getValue());source=replace(source,names);
        for(var alias:engine.samplers().entrySet()){
            // Only declared samplers are renamed; texture(...) calls keep the built-in function.
            if(!source.contains(alias.getKey())||!Pattern.compile("\\buniform\\s+sampler2D\\s+"+alias.getKey()+"\\s*;").matcher(source).find())continue;
            source=source.replaceAll("(?<![.\\w])"+alias.getKey()+"(?!\\w)(?!\\s*\\()",alias.getValue());
        }
        for(String sampler:new TreeSet<>(engine.samplers().values())){Matcher duplicate=Pattern.compile("(?m)^[ \\t]*uniform\\s+sampler2D\\s+"+sampler+"\\s*;").matcher(source);boolean first=true;StringBuilder result=new StringBuilder();while(duplicate.find()){duplicate.appendReplacement(result,first?"$0":"");first=false;}duplicate.appendTail(result);source=result.toString();}
        return source;
    }
    private static String alpha(String source,ShaderPackRenderPlan.AlphaTest test,String program){
        String output=source.contains("vfFragColor0")?"vfFragColor0":null;
        if(output==null){Matcher layout=Pattern.compile("layout\\s*\\(\\s*location\\s*=\\s*0\\s*\\)\\s*out\\s+vec4\\s+(\\w+)\\s*;").matcher(source);if(layout.find())output=layout.group(1);}
        // A program that writes no color (e.g. one that always discards) has nothing to test.
        if(output==null)return source;
        String operator=ALPHA.get(test.function()),condition=operator.equals("true")||operator.equals("false")?operator:output+".a "+operator+" "+test.reference();
        Matcher main=Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)").matcher(source);if(!main.find())throw new ShaderPackException(program+": no fragment main");
        return main.replaceFirst("void vfAlphaMain()")+"\nvoid main(){vfAlphaMain();if(!("+condition+"))discard;}\n";
    }
    private static String normalize(String source,boolean vertex,Set<Integer> legacyCoords,boolean legacyColor,Engine engine){
        source=source.replaceFirst("#version\\s+\\d+(?:[ \\t]+(?:core|compatibility))?","#version 330 core");if(!source.stripLeading().startsWith("#version"))source="#version 330 core\n"+source;
        for(int index:legacyCoords)source=source.replaceAll("\\bgl_TexCoord\\s*\\[\\s*"+index+"\\s*]","vfTexCoord"+index);
        Matcher outputs=Pattern.compile("\\bgl_FragData\\s*\\[\\s*([^]]+)\\s*]").matcher(source);StringBuffer rewritten=new StringBuffer();Set<Integer> outputSlots=new TreeSet<>();
        while(outputs.find()){String raw=outputs.group(1).strip();if(!raw.matches("[0-7]"))throw new ShaderPackException("Fragment output must be a fixed location from 0 to 7: "+raw);int slot=Integer.parseInt(raw);outputs.appendReplacement(rewritten,"vfFragColor"+slot);outputSlots.add(slot);}outputs.appendTail(rewritten);source=rewritten.toString();
        if(source.contains("gl_FragColor")){source=source.replaceAll("\\bgl_FragColor\\b","vfFragColor0");outputSlots.add(0);}
        StringBuilder prefix=new StringBuilder("\n#define MC_VERSION 12001\n").append(ShaderPackFormats.defines(source));
        if(vertex&&engine!=null)prefix.append(engine.vertexPrefix());
        if(Pattern.compile("\\bgl_FogFragCoord\\b").matcher(strip(source)).find()){
            source=replace(source,Map.of("gl_FogFragCoord","vfFogFragCoord"));
            prefix.append(vertex?"out":"in").append(" float vfFogFragCoord;\n");
        }
        if(Pattern.compile("\\bgl_Fog\\b").matcher(strip(source)).find()){
            // Fixed-function fog parameters become plain uniforms supplied from the game's current fog state.
            for(var field:Map.of("start","vfFogStart","end","vfFogEnd","density","vfFogDensity","color","vfFogColor","scale","(1.0/(vfFogEnd-vfFogStart))").entrySet())source=source.replaceAll("\\bgl_Fog\\s*\\.\\s*"+field.getKey()+"\\b",Matcher.quoteReplacement(field.getValue()));
            if(Pattern.compile("\\bgl_Fog\\b").matcher(strip(source)).find())throw new ShaderPackException("Unsupported gl_Fog usage");
            prefix.append("uniform float vfFogStart;\nuniform float vfFogEnd;\nuniform float vfFogDensity;\nuniform vec4 vfFogColor;\n");
        }
        if(vertex){
            if(engine==null&&(source.contains("gl_Vertex")||source.contains("ftransform"))&&!Pattern.compile("\\b(?:in|attribute)\\s+\\w+\\s+Position\\s*;").matcher(source).find())prefix.append("in vec3 Position;\n");
            if(engine==null&&(source.contains("gl_MultiTexCoord")||!legacyCoords.isEmpty())&&!Pattern.compile("\\b(?:in|attribute)\\s+\\w+\\s+UV0\\s*;").matcher(source).find())prefix.append("in vec2 UV0;\n");
            String position=engine==null?"vec4(Position,1.0)":engine.position(),projection=engine==null?PROJECTION:engine.projection(),model=engine==null?"mat4(1.0)":engine.model(),mvp=engine==null?PROJECTION:"("+projection+" * "+model+")";
            source=source.replaceAll("\\bftransform\\s*\\(\\s*\\)","("+mvp+" * "+position+")");
            for(int i=0;i<8;i++){String matrix=engine==null?"mat4(1.0)":i==0?engine.texture0():i==1?LIGHTMAP_MATRIX:"mat4(1.0)";source=source.replaceAll("\\bgl_TextureMatrix\\s*\\[\\s*"+i+"\\s*]",matrix);}
            source=replace(source,Map.ofEntries(Map.entry("attribute","in"),Map.entry("varying","out"),Map.entry("gl_Vertex",position),Map.entry("gl_ModelViewProjectionMatrix",mvp),Map.entry("gl_ProjectionMatrix",projection),Map.entry("gl_ModelViewMatrix",model),Map.entry("gl_ModelViewMatrixInverse","inverse("+model+")"),Map.entry("gl_ProjectionMatrixInverse","inverse("+projection+")"),Map.entry("gl_NormalMatrix",engine==null?"mat3(1.0)":"mat3(transpose(inverse("+model+")))"),Map.entry("gl_Normal",engine==null?"vec3(0,0,1)":engine.normal()),Map.entry("gl_Color",engine==null?"vec4(1.0)":engine.color()),Map.entry("gl_FrontColor","vfVertexColor")));
            for(int i=0;i<8;i++){
                String coordinate=engine==null?"vec4(UV0,0,1)":i==0?engine.uv0():i==1||i==2?engine.light():"vec4(0.0,0.0,0.0,1.0)";
                source=source.replaceAll("\\bgl_MultiTexCoord"+i+"\\b",coordinate);
            }
        }else source=replace(source,Map.of("varying","in","gl_Color","vfVertexColor"));
        source=replace(source,Map.of("texture2D","texture","texture2DLod","textureLod","texture2DGradARB","textureGrad","texture2DProj","textureProj","texture3D","texture","textureCube","texture"));
        if(source.contains("shadow2D")){source=replace(source,Map.of("shadow2D","vfShadowSample"));prefix.append("vec4 vfShadowSample(sampler2DShadow s,vec3 coord){return vec4(texture(s,coord));}\n");}
        for(int index:legacyCoords)prefix.append(vertex?"out":"in").append(" vec4 vfTexCoord").append(index).append(";\n");
        if(legacyColor)prefix.append(vertex?"out":"in").append(" vec4 vfVertexColor;\n");
        if(!vertex)for(int slot:outputSlots)prefix.append("layout(location=").append(slot).append(") out vec4 vfFragColor").append(slot).append(";\n");
        if(vertex&&(!legacyCoords.isEmpty()||legacyColor)){
            String uv=engine==null?"vec4(UV0,0,1)":engine.uv0();
            source=source.replaceFirst("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)","void vfPackMain()");StringBuilder main=new StringBuilder("\nvoid main(){");for(int index:legacyCoords)main.append("vfTexCoord").append(index).append('=').append(uv).append(';');if(legacyColor)main.append("vfVertexColor=vec4(1);");source+=main+"vfPackMain();}\n";
        }
        if(engine!=null){
            if(vertex)for(var metadata:new TreeMap<>(engine.metadata()).entrySet())if(Pattern.compile("\\b"+metadata.getKey()+"\\b").matcher(source).find()){
                source=removeDeclaration(source,metadata.getKey());prefix.append("#define ").append(metadata.getKey()).append(' ').append(metadata.getValue()).append('\n');
            }
            String code=strip(source);
            String combined=code+prefix;
            if(vertex)for(var attribute:new TreeMap<>(engine.attributes()).entrySet())if(Pattern.compile("\\b"+attribute.getKey()+"\\b").matcher(combined).find()&&!declared(source,attribute.getKey()))prefix.append("in ").append(attribute.getValue()).append(' ').append(attribute.getKey()).append(";\n");
            for(var uniform:new TreeMap<>(engine.uniforms()).entrySet())if(Pattern.compile("\\b"+uniform.getKey()+"\\b").matcher(combined).find()&&!declared(source,uniform.getKey()))prefix.append("uniform ").append(uniform.getValue()).append(' ').append(uniform.getKey()).append(";\n");
            // The engine decodes its vertex inputs before the pack's main runs.
            if(vertex&&engine.vertexInit()!=null){source=source.replaceFirst("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)","void vfEngineMain()");source+="\nvoid main(){"+engine.vertexInit()+"vfEngineMain();}\n";}
        }
        int end=source.indexOf('\n');return source.substring(0,end+1)+prefix+source.substring(end+1);
    }
    private static String replace(String source,Map<String,String> identifiers){
        if(identifiers.isEmpty())return source;
        // Token boundaries prevent replacing names which merely contain a legacy identifier.
        Matcher tokens=Pattern.compile("\\b[A-Za-z_]\\w*\\b").matcher(source);StringBuffer result=new StringBuffer();while(tokens.find())tokens.appendReplacement(result,Matcher.quoteReplacement(identifiers.getOrDefault(tokens.group(),tokens.group())));tokens.appendTail(result);return result.toString();
    }
}
