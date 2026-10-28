package github.com.gengyoubo.vulkanforge.lab;

import java.util.*;
import java.util.regex.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static github.com.gengyoubo.vulkanforge.lab.VulkanContext.check;

/** Compiles the actual game shader sources to SPIR-V; no shader-family substitution or GL compiler. */
final class NativeShaderProgram implements AutoCloseable {
    private record Uniform(String type,int slot,int columns) { }
    private static final Pattern UNIFORM = Pattern.compile("(?m)\\buniform\\s+(\\w+)\\s+(\\w+)\\s*;");
    private static final Pattern IO = Pattern.compile("(?m)^(\\s*(?:flat\\s+|smooth\\s+|noperspective\\s+)?)\\b(in|out)\\s+(\\w+)\\s+(\\w+)\\s*;");
    final List<String> samplers = new ArrayList<>();
    private final Map<String,Uniform> uniforms = new LinkedHashMap<>();
    private final VulkanContext context;
    final boolean terrain, entity;
    long textureLayout,layout,vertex,fragment;
    private final Map<PipelineKey,Long> pipelines = new HashMap<>();
    private final float[] uniformScratch=new float[256];
    private Map<Integer,Integer> vertexInputs=Map.of();
    /** Numeric lookup key: no per-draw strings, streams or cloned vertex layouts. Stored keys own their arrays. */
    private final class PipelineKey {
        final int[] state;final int[][] attributes;final int stride,hash;
        PipelineKey(int[] state,int[][] attributes,int stride){
            this.state=state;this.attributes=attributes;this.stride=stride;int h=31*Arrays.hashCode(state)+stride,count=0;
            for(int[] attribute:attributes)if(vertexInputs.containsKey(attribute[0])){for(int i=0;i<6;i++)h=31*h+attribute[i];count++;}
            if(count!=vertexInputs.size())throw new UnsupportedOperationException("Native draw lacks shader vertex inputs: "+vertexInputs.keySet());hash=h;
        }
        @Override public int hashCode(){return hash;}
        @Override public boolean equals(Object other){
            if(!(other instanceof NativeShaderProgram.PipelineKey key)||stride!=key.stride||!Arrays.equals(state,key.state))return false;
            int a=0,b=0;
            while(true){while(a<attributes.length&&!vertexInputs.containsKey(attributes[a][0]))a++;while(b<key.attributes.length&&!vertexInputs.containsKey(key.attributes[b][0]))b++;
                if(a==attributes.length||b==key.attributes.length)return a==attributes.length&&b==key.attributes.length;
                for(int i=0;i<6;i++)if(attributes[a][i]!=key.attributes[b][i])return false;a++;b++;
            }
        }
    }
    NativeShaderProgram(VulkanContext context,String vertexSource,String fragmentSource,Map<String,Integer> attributes) {
        this.context=context;
        terrain=vertexSource.contains("ChunkOffset");
        entity=vertexSource.contains("UV1")&&vertexSource.contains("Normal")&&!terrain;
        int next=0; Matcher matcher=UNIFORM.matcher(vertexSource+"\n"+fragmentSource);
        while(matcher.find()) {
            String type=matcher.group(1),name=matcher.group(2);
            if(type.equals("sampler2D")){if(!samplers.contains(name))samplers.add(name);continue;}
            if(uniforms.containsKey(name))continue;
            int columns=switch(type){case "mat2"->2;case "mat3"->3;case "mat4"->4;default->1;};
            if(!Set.of("float","int","bool","vec2","vec3","vec4","ivec2","ivec3","ivec4","mat2","mat3","mat4").contains(type)||next+columns>64)
                throw new UnsupportedOperationException("Native shader uniform: "+type+" "+name);
            uniforms.put(name,new Uniform(type,next,columns));next+=columns;
        }
        Map<String,Integer> varying=new LinkedHashMap<>(); matcher=IO.matcher(vertexSource);
        while(matcher.find())if(matcher.group(2).equals("out"))varying.put(matcher.group(4),varying.size());
        try(var stack=MemoryStack.stackPush()) {
            vertex=ShaderCompiler.moduleSource(context,translate(vertexSource,true,attributes,varying),"minecraft-native.vert",shaderc_glsl_vertex_shader,true,code->vertexInputs=SpirvVertexInputs.read(code));
            fragment=ShaderCompiler.moduleSource(context,translate(fragmentSource,false,attributes,varying),"minecraft-native.frag",shaderc_glsl_fragment_shader,true,null);
            var bindings=VkDescriptorSetLayoutBinding.calloc(samplers.size()+1,stack);
            for(int i=0;i<samplers.size();i++)bindings.get(i).binding(i).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).stageFlags(VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT);
            bindings.get(samplers.size()).binding(samplers.size()).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).stageFlags(VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT);
            var handle=stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(context.device,VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings),null,handle),"native game shader descriptors");textureLayout=handle.get(0);
            check(vkCreatePipelineLayout(context.device,VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(textureLayout)),null,handle),"native game shader layout");layout=handle.get(0);
        }catch(RuntimeException|Error error){close();throw error;}
    }
    private String translate(String source,boolean isVertex,Map<String,Integer> attributes,Map<String,Integer> varying) {
        source=source.replaceFirst("#version\\s+\\d+[^\\r\\n]*","#version 450").replace("gl_VertexID","gl_VertexIndex");
        StringBuilder prefix=new StringBuilder("\nlayout(std140,set=0,binding="+samplers.size()+") uniform NativeValues { vec4 data[64]; } nativeValues;\n");
        Matcher matcher=UNIFORM.matcher(source);StringBuffer replaced=new StringBuffer();
        while(matcher.find()) {
            String type=matcher.group(1),name=matcher.group(2),declaration="";
            if(type.equals("sampler2D"))declaration="layout(set=0,binding="+samplers.indexOf(name)+") uniform sampler2D "+name+";";
            else {
                Uniform uniform=uniforms.get(name);String value="nativeValues.data["+uniform.slot()+"]";
                if(uniform.columns()>1){StringJoiner columns=new StringJoiner(",",type+"(",")");for(int c=0;c<uniform.columns();c++)columns.add("nativeValues.data["+(uniform.slot()+c)+"]"+(uniform.columns()==3?".xyz":uniform.columns()==2?".xy":""));value=columns.toString();}
                else value=switch(type){case "float"->value+".x";case "int"->"int("+value+".x)";case "bool"->"("+value+".x!=0.0)";case "vec2","vec3"->value+(type.equals("vec2")?".xy":".xyz");case "ivec2","ivec3","ivec4"->type+"("+value+(type.equals("ivec2")?".xy":type.equals("ivec3")?".xyz":"")+")";default->value;};
                prefix.append("#define ").append(name).append(' ').append(value).append('\n');
            }
            matcher.appendReplacement(replaced,Matcher.quoteReplacement(declaration));
        }
        matcher.appendTail(replaced);source=replaced.toString();matcher=IO.matcher(source);replaced=new StringBuffer();
        while(matcher.find()) {
            String direction=matcher.group(2),name=matcher.group(4);int location;
            if(isVertex&&direction.equals("in")) {
                Integer attribute=attributes.get(name);if(attribute==null)throw new UnsupportedOperationException("Unbound native vertex attribute "+name);location=attribute;
            } else if(!isVertex&&direction.equals("out"))location=0;
            else location=varying.computeIfAbsent(name,unused->varying.size()); // GL permits unused fragment inputs absent from the vertex stage.
            matcher.appendReplacement(replaced,Matcher.quoteReplacement("layout(location="+location+") "+matcher.group(1).trim()+" "+direction+" "+matcher.group(3)+" "+name+";"));
        }
        matcher.appendTail(replaced);source=replaced.toString();int line=source.indexOf('\n');
        source=source.substring(0,line+1)+prefix+source.substring(line+1);
        if(isVertex) {
            source=source.replaceFirst("void\\s+main\\s*\\(\\s*\\)","void nativeOriginalMain()");
            // Private targets retain GL row orientation so all original framebuffer samplers remain correct.
            source+="\nvoid main(){nativeOriginalMain();gl_Position.z=(gl_Position.z+gl_Position.w)*0.5;}\n";
        }
        return source;
    }
    float[] values(Object[] values) {
        float[] result=new float[256];
        fillValues(values,result);return result;
    }
    void writeValues(Object[] entries,java.nio.FloatBuffer output,int offset){Arrays.fill(uniformScratch,0);fillValues(entries,uniformScratch);output.put(offset,uniformScratch);}
    private void fillValues(Object[] values,float[] result){
        for(Object value:values){Object[] entry=(Object[])value;Uniform uniform=uniforms.get((String)entry[0]);if(uniform==null)continue;float[] data=(float[])entry[1];int rows=uniform.columns();
            if(rows>1){for(int c=0;c<rows;c++)for(int r=0;r<rows;r++)result[(uniform.slot()+c)*4+r]=data[c*rows+r];}
            else System.arraycopy(data,0,result,uniform.slot()*4,Math.min(data.length,4));}
    }
    long pipeline(int[] state,int[][] attributes,int stride) {
        PipelineKey key=new PipelineKey(state,attributes,stride);Long existing=pipelines.get(key);if(existing!=null)return existing;
        int[][] consumed=Arrays.stream(attributes).filter(attribute->vertexInputs.containsKey(attribute[0])).toArray(int[][]::new);
        if(consumed.length!=vertexInputs.size())throw new UnsupportedOperationException("Native draw lacks shader vertex inputs: "+vertexInputs.keySet());
        // Buffer names are resource identities, not pipeline state. Chunk recycling must reuse the same pipeline.
        int[][] layoutAttributes=Arrays.stream(consumed).map(attribute->Arrays.copyOf(attribute,6)).toArray(int[][]::new);
        try(var stack=MemoryStack.stackPush()) {
            var stages=VkPipelineShaderStageCreateInfo.calloc(2,stack);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertex).pName(stack.UTF8("main"));stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragment).pName(stack.UTF8("main"));
            var attrs=VkVertexInputAttributeDescription.calloc(consumed.length,stack);
            for(int i=0;i<consumed.length;i++){int[] a=consumed[i];attrs.get(i).location(a[0]).binding(0).format(format(a[1],a[2],a[3]!=0,vertexInputs.get(a[0])!=SpirvVertexInputs.FLOAT)).offset(a[5]);}
            var input=VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default().pVertexBindingDescriptions(VkVertexInputBindingDescription.calloc(1,stack).binding(0).stride(stride).inputRate(VK_VERTEX_INPUT_RATE_VERTEX)).pVertexAttributeDescriptions(attrs);
            var blend=VkPipelineColorBlendAttachmentState.calloc(1,stack).colorWriteMask(state[12]).blendEnable(state[0]!=0)
                    .srcColorBlendFactor(factor(state[1])).dstColorBlendFactor(factor(state[2])).srcAlphaBlendFactor(factor(state[3])).dstAlphaBlendFactor(factor(state[4])).colorBlendOp(operation(state[5])).alphaBlendOp(operation(state[6]));
            var raster=VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default().polygonMode(VK_POLYGON_MODE_FILL).lineWidth(1)
                    .cullMode(state[10]==0?VK_CULL_MODE_NONE:state[10]==1029?VK_CULL_MODE_BACK_BIT:state[10]==1028?VK_CULL_MODE_FRONT_BIT:VK_CULL_MODE_FRONT_AND_BACK)
                    .frontFace(state[11]==2305?VK_FRONT_FACE_CLOCKWISE:VK_FRONT_FACE_COUNTER_CLOCKWISE).depthBiasEnable(state[14]!=0);
            var info=VkGraphicsPipelineCreateInfo.calloc(1,stack).sType$Default().pStages(stages).layout(layout)
                    .pNext(VkPipelineRenderingCreateInfo.calloc(stack).sType$Default().pColorAttachmentFormats(stack.ints(VK_FORMAT_R8G8B8A8_UNORM)).depthAttachmentFormat(VK_FORMAT_D32_SFLOAT))
                    .pVertexInputState(input).pInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default().topology(topology(state[15])))
                    .pViewportState(VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().viewportCount(1).scissorCount(1)).pRasterizationState(raster)
                    .pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT))
                    .pDepthStencilState(VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default().depthTestEnable(state[7]!=0).depthWriteEnable(state[8]!=0).depthCompareOp(compare(state[9])))
                    .pColorBlendState(VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().pAttachments(blend))
                    .pDynamicState(VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default().pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT,VK_DYNAMIC_STATE_SCISSOR,VK_DYNAMIC_STATE_DEPTH_BIAS)));
            var out=stack.mallocLong(1);check(vkCreateGraphicsPipelines(context.device,0,info,null,out),"native game graphics pipeline");pipelines.put(new PipelineKey(state.clone(),layoutAttributes,stride),out.get(0));return out.get(0);
        }
    }
    private static int format(int size,int type,boolean normalized,boolean integer) {
        int[] formats=switch(type){
            case 5126->new int[]{VK_FORMAT_R32_SFLOAT,VK_FORMAT_R32G32_SFLOAT,VK_FORMAT_R32G32B32_SFLOAT,VK_FORMAT_R32G32B32A32_SFLOAT};
            case 5121->integer?new int[]{VK_FORMAT_R8_UINT,VK_FORMAT_R8G8_UINT,VK_FORMAT_R8G8B8A8_UINT,VK_FORMAT_R8G8B8A8_UINT}:normalized?new int[]{VK_FORMAT_R8_UNORM,VK_FORMAT_R8G8_UNORM,VK_FORMAT_R8G8B8A8_UNORM,VK_FORMAT_R8G8B8A8_UNORM}:new int[]{VK_FORMAT_R8_USCALED,VK_FORMAT_R8G8_USCALED,VK_FORMAT_R8G8B8A8_USCALED,VK_FORMAT_R8G8B8A8_USCALED};
            case 5120->integer?new int[]{VK_FORMAT_R8_SINT,VK_FORMAT_R8G8_SINT,VK_FORMAT_R8G8B8A8_SINT,VK_FORMAT_R8G8B8A8_SINT}:normalized?new int[]{VK_FORMAT_R8_SNORM,VK_FORMAT_R8G8_SNORM,VK_FORMAT_R8G8B8A8_SNORM,VK_FORMAT_R8G8B8A8_SNORM}:new int[]{VK_FORMAT_R8_SSCALED,VK_FORMAT_R8G8_SSCALED,VK_FORMAT_R8G8B8A8_SSCALED,VK_FORMAT_R8G8B8A8_SSCALED};
            case 5122->integer?new int[]{VK_FORMAT_R16_SINT,VK_FORMAT_R16G16_SINT,VK_FORMAT_R16G16B16_SINT,VK_FORMAT_R16G16B16A16_SINT}:normalized?new int[]{VK_FORMAT_R16_SNORM,VK_FORMAT_R16G16_SNORM,VK_FORMAT_R16G16B16_SNORM,VK_FORMAT_R16G16B16A16_SNORM}:new int[]{VK_FORMAT_R16_SSCALED,VK_FORMAT_R16G16_SSCALED,VK_FORMAT_R16G16B16_SSCALED,VK_FORMAT_R16G16B16A16_SSCALED};
            case 5123->integer?new int[]{VK_FORMAT_R16_UINT,VK_FORMAT_R16G16_UINT,VK_FORMAT_R16G16B16_UINT,VK_FORMAT_R16G16B16A16_UINT}:normalized?new int[]{VK_FORMAT_R16_UNORM,VK_FORMAT_R16G16_UNORM,VK_FORMAT_R16G16B16_UNORM,VK_FORMAT_R16G16B16A16_UNORM}:new int[]{VK_FORMAT_R16_USCALED,VK_FORMAT_R16G16_USCALED,VK_FORMAT_R16G16B16_USCALED,VK_FORMAT_R16G16B16A16_USCALED};
            default->throw new UnsupportedOperationException("Native vertex type "+type);};
        return formats[size-1];
    }
    private static int topology(int mode){return switch(mode){case 0->VK_PRIMITIVE_TOPOLOGY_POINT_LIST;case 1->VK_PRIMITIVE_TOPOLOGY_LINE_LIST;case 3->VK_PRIMITIVE_TOPOLOGY_LINE_STRIP;case 4->VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;case 5->VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;case 6->VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN;default->throw new UnsupportedOperationException("Native primitive mode "+mode);};}
    private static int compare(int value){if(value<512||value>519)throw new IllegalArgumentException("Native depth comparison "+value);return value-512;}
    private static int factor(int value){return switch(value){case 0->VK_BLEND_FACTOR_ZERO;case 1->VK_BLEND_FACTOR_ONE;case 768->VK_BLEND_FACTOR_SRC_COLOR;case 769->VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;case 770->VK_BLEND_FACTOR_SRC_ALPHA;case 771->VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;case 772->VK_BLEND_FACTOR_DST_ALPHA;case 773->VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;case 774->VK_BLEND_FACTOR_DST_COLOR;case 775->VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;default->throw new UnsupportedOperationException("Native blend factor "+value);};}
    private static int operation(int value){return switch(value){case 32774->VK_BLEND_OP_ADD;case 32775->VK_BLEND_OP_MIN;case 32776->VK_BLEND_OP_MAX;case 32778->VK_BLEND_OP_SUBTRACT;case 32779->VK_BLEND_OP_REVERSE_SUBTRACT;default->throw new UnsupportedOperationException("Native blend operation "+value);};}
    @Override public void close(){for(long pipeline:pipelines.values())vkDestroyPipeline(context.device,pipeline,null);pipelines.clear();if(layout!=0)vkDestroyPipelineLayout(context.device,layout,null);if(textureLayout!=0)vkDestroyDescriptorSetLayout(context.device,textureLayout,null);if(vertex!=0)vkDestroyShaderModule(context.device,vertex,null);if(fragment!=0)vkDestroyShaderModule(context.device,fragment,null);}
}
