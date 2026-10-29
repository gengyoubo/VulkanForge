package github.com.gengyoubo.vulkanforge.client.shader;

import github.com.gengyoubo.vulkanforge.client.NativeGameWindow;
import github.com.gengyoubo.vulkanforge.shaderpack.*;
import java.util.*;
import java.util.regex.*;
import org.lwjgl.system.MemoryStack;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/** One compiled standard pack pass. Calls become CPU/Vulkan operations under the native bootstrap. */
final class ShaderPackPass implements AutoCloseable {
    record Uniform(String type,int location){ }
    private static final Pattern UNIFORM=Pattern.compile("\\buniform\\s+(\\w+)\\s+(\\w+)\\s*;");
    final String name;
    final int program;
    final Map<String,Uniform> uniforms=new LinkedHashMap<>();
    final List<String> samplers=new ArrayList<>();
    private boolean closed;
    ShaderPackPass(ShaderPack.Program source,ShaderPackOptions options,Map<String,String> values){
        name=source.name();ShaderPackGlsl.Graphics graphics=ShaderPackGlsl.fullscreen(source,options,values);int vertex=0,fragment=0,linked=0;
        try {
            String all=graphics.vertex()+"\n"+graphics.fragment();
            if(Pattern.compile("\\buniform\\s+\\w+\\s+\\w+\\s*\\[").matcher(all).find()||Pattern.compile("\\buniform\\s+\\w+\\s*\\{").matcher(all).find())throw new ShaderPackException(name+": uniform arrays/blocks require an extended pack uniform layout");
            vertex=compile(GL_VERTEX_SHADER,graphics.vertex());fragment=compile(GL_FRAGMENT_SHADER,graphics.fragment());linked=glCreateProgram();
            glAttachShader(linked,vertex);glAttachShader(linked,fragment);for(var attribute:graphics.attributes().entrySet())glBindAttribLocation(linked,attribute.getValue(),attribute.getKey());
            glLinkProgram(linked);if(glGetProgrami(linked,GL_LINK_STATUS)==GL_FALSE)throw new ShaderPackException(name+": "+glGetProgramInfoLog(linked));
            Matcher declarations=UNIFORM.matcher(graphics.vertex()+"\n"+graphics.fragment());
            while(declarations.find()){
                String type=declarations.group(1),field=declarations.group(2);if(uniforms.containsKey(field))continue;
                if(!Set.of("sampler2D","float","int","bool","vec2","vec3","vec4","mat2","mat3","mat4").contains(type))throw new ShaderPackException(name+": unsupported uniform type "+type+" "+field);
                int location=glGetUniformLocation(linked,field);if(location<0)continue;
                if(type.equals("sampler2D")){if(!Set.of("colortex0","gcolor","depthtex0").contains(field))throw new ShaderPackException(name+": unavailable input texture "+field);samplers.add(field);}
                else if(!ShaderPackUniforms.supported().contains(field))throw new ShaderPackException(name+": unsupported frame uniform "+field);
                uniforms.put(field,new Uniform(type,location));
            }
            if(NativeGameWindow.enabled())NativeGameWindow.call("prepareShaderPackProgram",linked);
            program=linked;linked=0;
        }catch(RuntimeException error){Throwable cause=error;while(cause.getCause()!=null&&cause.getCause()!=cause)cause=cause.getCause();throw new ShaderPackException(name+": "+Objects.requireNonNullElse(cause.getMessage(),cause.toString()),error);}
        finally{if(vertex!=0)glDeleteShader(vertex);if(fragment!=0)glDeleteShader(fragment);if(linked!=0)glDeleteProgram(linked);}
    }
    private int compile(int stage,String source){int shader=glCreateShader(stage);try{glShaderSource(shader,source);glCompileShader(shader);if(glGetShaderi(shader,GL_COMPILE_STATUS)==GL_FALSE)throw new ShaderPackException(name+": "+glGetShaderInfoLog(shader));return shader;}catch(Throwable error){glDeleteShader(shader);throw error;}}
    void values(Map<String,float[]> values){
        try(MemoryStack stack=MemoryStack.stackPush()){
            for(var entry:uniforms.entrySet()){
                Uniform uniform=entry.getValue();if(uniform.type().equals("sampler2D"))continue;float[] data=values.get(entry.getKey());if(data==null)throw new ShaderPackException(name+": no value for uniform "+entry.getKey());
                switch(uniform.type()){
                    case "float"->glUniform1f(uniform.location(),data[0]);case "int","bool"->glUniform1i(uniform.location(),(int)data[0]);
                    case "vec2"->glUniform2f(uniform.location(),data[0],data[1]);case "vec3"->glUniform3f(uniform.location(),data[0],data[1],data[2]);case "vec4"->glUniform4f(uniform.location(),data[0],data[1],data[2],data[3]);
                    case "mat2"->glUniformMatrix2fv(uniform.location(),false,stack.floats(data));case "mat3"->glUniformMatrix3fv(uniform.location(),false,stack.floats(data));case "mat4"->glUniformMatrix4fv(uniform.location(),false,stack.floats(data));
                    default->throw new ShaderPackException(name+": unsupported uniform "+uniform.type());
                }
            }
        }
    }
    @Override public void close(){if(closed)return;closed=true;glDeleteProgram(program);}
}
