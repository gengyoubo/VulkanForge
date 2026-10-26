package github.com.gengyoubo.vulkanforge.client;

import com.google.gson.*;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import java.util.*;
import java.util.function.IntSupplier;
import java.util.regex.*;
import static org.lwjgl.opengl.GL11.*;

/** Ports the active resource-pack post program, including its uniforms and auxiliary targets, to a native Vulkan pass. */
public final class PostEffects {
    private record Uniform(String type,int slot,int slots){}
    private record Program(String vertex,String fragment,List<String> samplers,Map<String,Uniform> uniforms,JsonObject json){}
    private static final Map<String,Program> programs=new HashMap<>();
    private static final Pattern UNIFORM=Pattern.compile("uniform\\s+(\\w+)\\s+(\\w+)\\s*;");
    private static final Pattern IO=Pattern.compile("(?m)^\\s*(in|out)\\s+(\\w+)\\s+(\\w+)\\s*;");
    private static long passes,fallbacks;
    private PostEffects(){}
    public static boolean process(PostPass pass,float time){
        if(!VulkanPreview.nativeTerrain())return false;
        EntityDraws.flushCurrent();
        try {
            Program program=programs.computeIfAbsent(pass.getName(),name->{try{return load(name);}catch(Exception error){throw new IllegalStateException(error);}});
            Map<String,Integer> textures=new HashMap<>();textures.put("DiffuseSampler",pass.inTarget.getColorTextureId());
            List<IntSupplier> aux=aux(pass,IntSupplier.class);List<String> names=aux(pass,String.class);
            for(int i=0;i<aux.size();i++)textures.put(names.get(i),aux.get(i).getAsInt());
            int[] inputs=new int[program.samplers.size()*5];int bound=glGetInteger(GL_TEXTURE_BINDING_2D);
            try {for(int i=0;i<program.samplers.size();i++){
                Integer texture=textures.get(program.samplers.get(i));if(texture==null){fallbacks++;return false;}
                com.mojang.blaze3d.platform.GlStateManager._bindTexture(texture);int at=i*5;
                inputs[at]=texture;inputs[at+1]=glGetTexLevelParameteri(GL_TEXTURE_2D,0,GL_TEXTURE_WIDTH);inputs[at+2]=glGetTexLevelParameteri(GL_TEXTURE_2D,0,GL_TEXTURE_HEIGHT);
                inputs[at+3]=program.samplers.get(i).toLowerCase(Locale.ROOT).contains("depth")?1:0;inputs[at+4]=glGetTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER)==GL_LINEAR?1:0;
            }}finally{com.mojang.blaze3d.platform.GlStateManager._bindTexture(bound);}
            float[] values=new float[256];
            for(var entry:program.uniforms.entrySet()){
                String name=entry.getKey();Uniform uniform=entry.getValue();int offset=uniform.slot*4;
                var original=pass.getEffect().getUniform(name);
                if(original!=null&&original.getFloatBuffer()!=null){var buffer=original.getFloatBuffer().duplicate();for(int i=0;i<Math.min(buffer.remaining(),uniform.slots*4);i++)values[offset+i]=buffer.get();}
                if(name.equals("ProjMat"))ortho(pass).get(values,offset);
                if(name.equals("InSize")){values[offset]=pass.inTarget.width;values[offset+1]=pass.inTarget.height;}
                if(name.equals("OutSize")){values[offset]=pass.outTarget.width;values[offset+1]=pass.outTarget.height;}
                if(name.equals("ScreenSize")){values[offset]=Minecraft.getInstance().getWindow().getWidth();values[offset+1]=Minecraft.getInstance().getWindow().getHeight();}
                if(name.equals("Time"))values[offset]=time;
            }
            if(!NativeMainWindow.nativePost(new Object[]{pass.getName(),program.vertex,program.fragment,inputs,values,
                    new int[]{pass.outTarget.frameBufferId,pass.outTarget.width,pass.outTarget.height}})){fallbacks++;return false;}
            passes++;RenderSystem.viewport(0,0,pass.outTarget.width,pass.outTarget.height);RenderSystem.depthFunc(GL_LEQUAL);
            pass.outTarget.unbindWrite();pass.inTarget.unbindRead();return true;
        }catch(RuntimeException error){throw error;}catch(Exception error){throw new IllegalStateException("Native post pass "+pass.getName(),error);}
    }
    @SuppressWarnings("unchecked") private static <T> List<T> aux(PostPass pass,Class<T> type)throws Exception{
        for(var field:PostPass.class.getDeclaredFields())if(field.getGenericType().getTypeName().equals("java.util.List<"+type.getName()+">")){field.setAccessible(true);return(List<T>)field.get(pass);}
        throw new IllegalStateException("Missing post auxiliary metadata "+type.getName());
    }
    private static Matrix4f ortho(PostPass pass)throws Exception{for(var field:PostPass.class.getDeclaredFields())if(field.getType()==Matrix4f.class){field.setAccessible(true);return(Matrix4f)field.get(pass);}throw new IllegalStateException("Missing post projection");}
    private static String resource(ResourceLocation name,String suffix)throws Exception{
        ResourceLocation path=ResourceLocation.fromNamespaceAndPath(name.getNamespace(),"shaders/program/"+name.getPath()+suffix);
        try(var reader=Minecraft.getInstance().getResourceManager().openAsReader(path)){return reader.lines().collect(java.util.stream.Collectors.joining("\n"));}
    }
    private static Program load(String name)throws Exception{
        ResourceLocation location=new ResourceLocation(name);JsonObject json=JsonParser.parseString(resource(location,".json")).getAsJsonObject();
        String vertex=resource(new ResourceLocation(json.get("vertex").getAsString()),".vsh"),fragment=resource(new ResourceLocation(json.get("fragment").getAsString()),".fsh");
        List<String> samplers=new ArrayList<>();Map<String,Uniform> uniforms=new LinkedHashMap<>();int slot=0;
        Matcher matcher=UNIFORM.matcher(vertex+"\n"+fragment);
        while(matcher.find()){
            String type=matcher.group(1),field=matcher.group(2);if(type.equals("sampler2D")){if(!samplers.contains(field))samplers.add(field);continue;}
            if(uniforms.containsKey(field))continue;int count=type.equals("mat4")?4:type.equals("mat3")?3:1;
            if(!Set.of("float","int","vec2","vec3","vec4","mat3","mat4").contains(type)||slot+count>64)throw new IllegalStateException("Unsupported post uniform "+type);
            uniforms.put(field,new Uniform(type,slot,count));slot+=count;
        }
        Map<String,Integer> varying=new LinkedHashMap<>();Matcher io=IO.matcher(fragment);while(io.find())if(io.group(1).equals("in"))varying.put(io.group(3),varying.size());
        return new Program(translate(vertex,true,samplers,uniforms,varying),translate(fragment,false,samplers,uniforms,varying),samplers,uniforms,json);
    }
    private static String translate(String source,boolean vertex,List<String> samplers,Map<String,Uniform> uniforms,Map<String,Integer> varying){
        source=source.replaceFirst("#version\\s+\\d+","#version 450");StringBuilder prefix=new StringBuilder("\nlayout(std140,set=0,binding="+samplers.size()+") uniform NativePost { vec4 data[64]; } nativePost;\n");
        Matcher matcher=UNIFORM.matcher(source);StringBuffer replaced=new StringBuffer();
        while(matcher.find()){
            String type=matcher.group(1),name=matcher.group(2),declaration="";
            if(type.equals("sampler2D"))declaration="layout(set=0,binding="+samplers.indexOf(name)+") uniform sampler2D "+name+";";
            else {Uniform uniform=uniforms.get(name);String value="nativePost.data["+uniform.slot+"]";
                if(uniform.slots>1){StringJoiner columns=new StringJoiner(",",type+"(",")");for(int i=0;i<uniform.slots;i++)columns.add("nativePost.data["+(uniform.slot+i)+"]"+(type.equals("mat3")?".xyz":""));value=columns.toString();}
                else value=type.equals("float")?value+".x":type.equals("int")?"int("+value+".x)":value+(type.equals("vec2")?".xy":type.equals("vec3")?".xyz":"");
                prefix.append("#define ").append(name).append(' ').append(value).append('\n');
            }
            matcher.appendReplacement(replaced,Matcher.quoteReplacement(declaration));
        }matcher.appendTail(replaced);source=replaced.toString();matcher=IO.matcher(source);replaced=new StringBuffer();
        while(matcher.find()){
            String direction=matcher.group(1),type=matcher.group(2),name=matcher.group(3),declaration;
            if(vertex&&direction.equals("in")){if(!name.equals("Position"))throw new IllegalStateException("Unsupported post attribute "+name);declaration=type+" Position;";}
            else declaration="layout(location="+(!vertex&&direction.equals("out")?0:varying.getOrDefault(name,0))+") "+direction+" "+type+" "+name+";";
            matcher.appendReplacement(replaced,Matcher.quoteReplacement(declaration));
        }matcher.appendTail(replaced);source=replaced.toString();
        if(vertex)source=source.replaceFirst("void\\s+main\\s*\\(\\s*\\)\\s*\\{","void main(){ vec2 nativeXY=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2); Position=vec4(nativeXY*OutSize,500.0,1.0);");
        int end=source.indexOf('\n');return source.substring(0,end+1)+prefix+source.substring(end+1);
    }
    static long[] stats(){return new long[]{passes,fallbacks};}
    static void reset(){programs.clear();}
}
