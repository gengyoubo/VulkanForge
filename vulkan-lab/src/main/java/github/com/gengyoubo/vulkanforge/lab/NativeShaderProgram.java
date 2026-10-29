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
    private static final Pattern UNIFORM = Pattern.compile("(?m)\\buniform\\s+(\\w+)\\s+(\\w+)(?:\\s*=\\s*([^;]+))?\\s*;");
    private static final Pattern IO = Pattern.compile("(?m)^(\\s*(?:flat\\s+|smooth\\s+|noperspective\\s+)?)\\b(in|out)\\s+(\\w+)\\s+(\\w+)(\\s*\\[\\s*\\d+\\s*])?\\s*;");
    private static final Pattern OUTPUT=Pattern.compile("layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s*(?:(?:flat|smooth|noperspective|centroid)\\s+)*out\\s+(\\w+)\\s+(\\w+)(\\s*\\[\\s*\\d+\\s*])?\\s*;");
    final List<String> samplers = new ArrayList<>();
    final int[] samplerBindings;
    final int uniformBinding;
    final Set<String> activeUniforms=new LinkedHashSet<>();
    private final Map<String,Uniform> uniforms = new LinkedHashMap<>();
    private final List<Object[]> uniformDefaults=new ArrayList<>();
    private final VulkanContext context;
    final boolean terrain, entity, embeddium, batchableDynamic, batchableGui;
    long textureLayout,layout,vertex,fragment;
    private final Map<PipelineKey,Long> pipelines = new HashMap<>();
    private record Stages(long vertex,long fragment){ }
    private final Map<Integer,Stages> stageVariants=new HashMap<>();
    private String translatedVertex,translatedFragment;
    private int fragmentOutputMask;
    private Set<Integer> fragmentInputs=Set.of();
    private final float[] uniformScratch;
    final int uniformBytes,uniformStride,uniformSlots;
    private Map<Integer,Integer> vertexInputs=Map.of();
    private static final int[] DEFAULT_FORMATS={VK_FORMAT_R8G8B8A8_UNORM};
    /** Numeric lookup key: no per-draw strings, streams or cloned vertex layouts. Stored keys own their arrays. */
    private final class PipelineKey {
        final int[] state,formats;final int[][] attributes;final int stride,hash,depthFormat;
        PipelineKey(int[] state,int[][] attributes,int stride){
            this(state,attributes,stride,DEFAULT_FORMATS,VK_FORMAT_D32_SFLOAT);
        }
        PipelineKey(int[] state,int[][] attributes,int stride,int[] formats,int depthFormat){
            this.state=state;this.attributes=attributes;this.stride=stride;this.formats=formats;this.depthFormat=depthFormat;int h=31*(31*Arrays.hashCode(state)+stride)+Arrays.hashCode(formats)+depthFormat,count=0;
            for(int[] attribute:attributes)if(vertexInputs.containsKey(attribute[0])){for(int i=0;i<6;i++)h=31*h+attribute[i];count++;}
            if(count!=vertexInputs.size())throw new UnsupportedOperationException("Native draw lacks shader vertex inputs: "+vertexInputs.keySet());hash=h;
        }
        @Override public int hashCode(){return hash;}
        @Override public boolean equals(Object other){
            if(!(other instanceof NativeShaderProgram.PipelineKey key)||stride!=key.stride||depthFormat!=key.depthFormat||!Arrays.equals(formats,key.formats)||!Arrays.equals(state,key.state))return false;
            int a=0,b=0;
            while(true){while(a<attributes.length&&!vertexInputs.containsKey(attributes[a][0]))a++;while(b<key.attributes.length&&!vertexInputs.containsKey(key.attributes[b][0]))b++;
                if(a==attributes.length||b==key.attributes.length)return a==attributes.length&&b==key.attributes.length;
                for(int i=0;i<6;i++)if(attributes[a][i]!=key.attributes[b][i])return false;a++;b++;
            }
        }
    }
    NativeShaderProgram(VulkanContext context,String vertexSource,String fragmentSource,Map<String,Integer> attributes) {
        this(context,vertexSource,fragmentSource,attributes,Map.of());
    }
    NativeShaderProgram(VulkanContext context,String vertexSource,String fragmentSource,Map<String,Integer> attributes,Map<String,Integer> fragmentOutputs) {
        this.context=context;
        vertexSource=splitIo(vertexSource);fragmentSource=splitIo(fragmentSource);
        embeddium=vertexSource.contains("u_RegionOffset")&&vertexSource.contains("a_PosId");
        terrain=vertexSource.contains("ChunkOffset")||embeddium;
        entity=vertexSource.contains("UV1")&&vertexSource.contains("Normal")&&!terrain;
        boolean drawIds=java.util.regex.Pattern.compile("gl_(?:VertexID|VertexIndex|InstanceID|InstanceIndex|DrawID|BaseVertex|BaseInstance|PrimitiveID|PrimitiveIDIn)(?:ARB|EXT)?\\b").matcher(vertexSource+fragmentSource).find();batchableDynamic=entity&&!drawIds;batchableGui=!terrain&&!drawIds;
        int next=0; Matcher matcher=UNIFORM.matcher(vertexSource+"\n"+fragmentSource);
        while(matcher.find()) {
            String type=matcher.group(1),name=matcher.group(2);
            if(type.equals("sampler2D")||type.equals("sampler2DShadow")){if(!samplers.contains(name))samplers.add(name);continue;}
            if(uniforms.containsKey(name))continue;
            int columns=switch(type){case "mat2"->2;case "mat3"->3;case "mat4"->4;default->1;};
            if(!Set.of("float","int","bool","vec2","vec3","vec4","ivec2","ivec3","ivec4","mat2","mat3","mat4").contains(type)||next+columns>1024)
                throw new UnsupportedOperationException("Native shader uniform: "+type+" "+name);
            uniforms.put(name,new Uniform(type,next,columns));next+=columns;
            if(matcher.group(3)!=null){String literal=matcher.group(3).strip();try{float value=literal.equals("true")?1:literal.equals("false")?0:Float.parseFloat(literal);uniformDefaults.add(new Object[]{name,new float[]{value}});}catch(NumberFormatException error){throw new UnsupportedOperationException("Native uniform initializer "+name+" = "+literal,error);}}
        }
        uniformSlots=Math.max(1,next);uniformBytes=uniformSlots*16;uniformScratch=new float[uniformSlots*4];
        uniformBinding=samplers.size();
        try(var stack=MemoryStack.stackPush()){var properties=VkPhysicalDeviceProperties.malloc(stack);vkGetPhysicalDeviceProperties(context.physical,properties);int alignment=(int)Math.max(16,properties.limits().minUniformBufferOffsetAlignment());if(uniformBytes>properties.limits().maxUniformBufferRange())throw new UnsupportedOperationException("Native shader exceeds device uniform range");uniformStride=(uniformBytes+alignment-1)/alignment*alignment;}
        Map<String,Integer> varying=new LinkedHashMap<>(); matcher=IO.matcher(vertexSource);
        int varyingSlot=0;while(matcher.find())if(matcher.group(2).equals("out")){varying.put(matcher.group(4),varyingSlot);varyingSlot+=matcher.group(5)==null?1:Integer.parseInt(matcher.group(5).replaceAll("\\D",""));}
        matcher=IO.matcher(fragmentSource);while(matcher.find())if(matcher.group(2).equals("in")&&!varying.containsKey(matcher.group(4))){varying.put(matcher.group(4),varyingSlot);varyingSlot+=matcher.group(5)==null?1:Integer.parseInt(matcher.group(5).replaceAll("\\D",""));}
        try(var stack=MemoryStack.stackPush()) {
            Set<Integer> live=new HashSet<>(),members=new HashSet<>();
            translatedVertex=translate(vertexSource,true,attributes,varying,fragmentOutputs);translatedFragment=translate(fragmentSource,false,attributes,varying,fragmentOutputs);
            Matcher outputs=OUTPUT.matcher(translatedFragment);while(outputs.find()){int location=Integer.parseInt(outputs.group(1)),count=outputs.group(4)==null?1:Integer.parseInt(outputs.group(4).replaceAll("\\D",""));for(int i=0;i<count;i++)fragmentOutputMask|=1<<(location+i);}
            fragment=ShaderCompiler.moduleSource(context,translatedFragment,"minecraft-native.frag",shaderc_glsl_fragment_shader,true,code->{fragmentInputs=SpirvLocations.inputs(code);live.addAll(SpirvDescriptorBindings.read(code));members.addAll(SpirvUniformMembers.read(code,uniformBinding));});
            vertex=ShaderCompiler.moduleSource(context,privateOutputs(translatedVertex,fragmentInputs),"minecraft-native.vert",shaderc_glsl_vertex_shader,true,code->{vertexInputs=SpirvVertexInputs.read(code);live.addAll(SpirvDescriptorBindings.read(code));members.addAll(SpirvUniformMembers.read(code,uniformBinding));});
            List<String> declared=List.copyOf(samplers);samplers.clear();List<Integer> used=new ArrayList<>();for(int i=0;i<declared.size();i++)if(live.contains(i)){samplers.add(declared.get(i));used.add(i);}samplerBindings=used.stream().mapToInt(Integer::intValue).toArray();
            activeUniforms.addAll(samplers);uniforms.forEach((name,uniform)->{if(members.contains(uniform.slot()*16))activeUniforms.add(name);});
            var bindings=VkDescriptorSetLayoutBinding.calloc(samplers.size()+1,stack);
            for(int i=0;i<samplers.size();i++)bindings.get(i).binding(samplerBindings[i]).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).stageFlags(VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT);
            bindings.get(samplers.size()).binding(uniformBinding).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).stageFlags(VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT);
            var handle=stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(context.device,VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings),null,handle),"native game shader descriptors");textureLayout=handle.get(0);
            check(vkCreatePipelineLayout(context.device,VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(textureLayout)),null,handle),"native game shader layout");layout=handle.get(0);
        }catch(RuntimeException|Error error){close();throw error;}
    }
    /** Unconsumed outputs stay ordinary GLSL variables, preserving references and side effects without an interface export. */
    private static String privateOutputs(String source,Set<Integer> consumed){
        Matcher matcher=OUTPUT.matcher(source);StringBuffer result=new StringBuffer();
        while(matcher.find()){int location=Integer.parseInt(matcher.group(1));String declaration=consumed.contains(location)?matcher.group():matcher.group(2)+" "+matcher.group(3)+Objects.requireNonNullElse(matcher.group(4),"")+";";matcher.appendReplacement(result,Matcher.quoteReplacement(declaration));}matcher.appendTail(result);return result.toString();
    }
    private Stages stages(int[] formats){
        int mask=0;Set<Integer> outputs=new HashSet<>();for(int i=0;i<formats.length;i++)if(formats[i]!=VK_FORMAT_UNDEFINED){mask|=1<<i;outputs.add(i);}
        if((fragmentOutputMask&~mask)==0)return new Stages(vertex,fragment);
        Stages cached=stageVariants.get(mask);if(cached!=null)return cached;
        long pixel=0,geometry=vertex;try{
            var inputs=new HashSet<Integer>();pixel=ShaderCompiler.moduleSource(context,privateOutputs(translatedFragment,outputs),"minecraft-native-masked.frag",shaderc_glsl_fragment_shader,true,code->inputs.addAll(SpirvLocations.inputs(code)));
            if(!inputs.equals(fragmentInputs))geometry=ShaderCompiler.moduleSource(context,privateOutputs(translatedVertex,inputs),"minecraft-native-masked.vert",shaderc_glsl_vertex_shader,true,null);
            Stages created=new Stages(geometry,pixel);stageVariants.put(mask,created);return created;
        }catch(RuntimeException|Error error){if(pixel!=0)vkDestroyShaderModule(context.device,pixel,null);if(geometry!=vertex)vkDestroyShaderModule(context.device,geometry,null);throw error;}
    }
    private static String splitIo(String source){
        Matcher declarations=Pattern.compile("(?m)^(\\s*(?:(?:flat|smooth|noperspective)\\s+)?(?:in|out)\\s+\\w+\\s+)([A-Za-z_]\\w*(?:\\s*,\\s*[A-Za-z_]\\w*)+)\\s*;").matcher(source);StringBuffer result=new StringBuffer();while(declarations.find()){StringJoiner lines=new StringJoiner("\n");for(String name:declarations.group(2).split(","))lines.add(declarations.group(1)+name.strip()+";");declarations.appendReplacement(result,Matcher.quoteReplacement(lines.toString()));}declarations.appendTail(result);return result.toString();
    }
    private String translate(String source,boolean isVertex,Map<String,Integer> attributes,Map<String,Integer> varying,Map<String,Integer> fragmentOutputs) {
        source=source.replaceFirst("#version\\s+\\d+[^\\r\\n]*","#version 450").replace("gl_VertexID","gl_VertexIndex");
        StringBuilder prefix=new StringBuilder("\nlayout(std140,set=0,binding="+samplers.size()+") uniform NativeValues {\n");
        if(uniforms.isEmpty())prefix.append("layout(offset=0) vec4 nativeUnused;\n");
        uniforms.forEach((name,uniform)->prefix.append("layout(offset=").append(uniform.slot()*16).append(") ").append(uniform.type()).append(' ').append(name).append(";\n"));prefix.append("};\n");
        Matcher matcher=UNIFORM.matcher(source);StringBuffer replaced=new StringBuffer();
        while(matcher.find()) {
            String type=matcher.group(1),name=matcher.group(2),declaration="";
            if(type.equals("sampler2D")||type.equals("sampler2DShadow"))declaration="layout(set=0,binding="+samplers.indexOf(name)+") uniform "+type+" "+name+";";
            else {
                // Anonymous block members preserve GLSL scope and shadowing; no global text-substitution macros.
            }
            matcher.appendReplacement(replaced,Matcher.quoteReplacement(declaration));
        }
        matcher.appendTail(replaced);source=replaced.toString();matcher=IO.matcher(source);replaced=new StringBuffer();
        int outputSlot=0;while(matcher.find()) {
            String direction=matcher.group(2),name=matcher.group(4);int location;
            if(isVertex&&direction.equals("in")) {
                Integer attribute=attributes.get(name);if(attribute==null)throw new UnsupportedOperationException("Unbound native vertex attribute "+name);location=attribute;
            } else if(!isVertex&&direction.equals("out")){location=fragmentOutputs.getOrDefault(name,outputSlot);outputSlot=location+(matcher.group(5)==null?1:Integer.parseInt(matcher.group(5).replaceAll("\\D","")));}
            else location=varying.computeIfAbsent(name,unused->varying.size()); // GL permits unused fragment inputs absent from the vertex stage.
            if(embeddium&&isVertex&&direction.equals("in")&&name.equals("a_LightCoord")&&matcher.group(3).equals("ivec2")) {
                // Embeddium feeds UNSIGNED_SHORT to an ivec2 in GL. Vulkan requires matching input numeric classes.
                // Fetch unsigned, then widen/cast to int32, preserving 0..65535 rather than reinterpreting as signed16.
                // Keep the define inside the original #ifdef so the uncompressed uint branch remains unchanged.
                matcher.appendReplacement(replaced,Matcher.quoteReplacement("layout(location="+location+") in uvec2 nativeUnsignedLightCoord;\n#define a_LightCoord ivec2(nativeUnsignedLightCoord)\n"));continue;
            }
            matcher.appendReplacement(replaced,Matcher.quoteReplacement("layout(location="+location+") "+matcher.group(1).trim()+" "+direction+" "+matcher.group(3)+" "+name+Objects.requireNonNullElse(matcher.group(5),"")+";"));
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
        float[] result=new float[uniformSlots*4];
        fillValues(uniformDefaults.toArray(),result);fillValues(values,result);return result;
    }
    void writeValues(Object[] entries,java.nio.FloatBuffer output,int offset){Arrays.fill(uniformScratch,0);fillValues(uniformDefaults.toArray(),uniformScratch);fillValues(entries,uniformScratch);output.put(offset,uniformScratch);}
    private void fillValues(Object[] values,float[] result){
        for(Object value:values){Object[] entry=(Object[])value;Uniform uniform=uniforms.get((String)entry[0]);if(uniform==null)continue;float[] data=(float[])entry[1];int rows=uniform.columns();
            if(rows>1){for(int c=0;c<rows;c++)for(int r=0;r<rows;r++)result[(uniform.slot()+c)*4+r]=data[c*rows+r];}
            else if(uniform.type().equals("int")||uniform.type().equals("bool")||uniform.type().startsWith("ivec")){for(int i=0;i<Math.min(data.length,4);i++)result[uniform.slot()*4+i]=Float.intBitsToFloat((int)data[i]);}
            else System.arraycopy(data,0,result,uniform.slot()*4,Math.min(data.length,4));}
    }
    int[][] withConstantInputs(int[][] attributes){
        Set<Integer> supplied=new HashSet<>();for(int[] attribute:attributes)supplied.add(attribute[0]);
        List<int[]> result=null;for(var input:vertexInputs.entrySet())if(!supplied.contains(input.getKey())){
            int location=input.getKey();if(location<0||location>=32)throw new UnsupportedOperationException("Constant vertex attribute location "+location);
            if(result==null)result=new ArrayList<>(Arrays.asList(attributes));result.add(new int[]{location,4,input.getValue()==SpirvVertexInputs.FLOAT?5126:input.getValue()==SpirvVertexInputs.SIGNED?5124:5125,0,2,location*16});
        }
        return result==null?attributes:result.toArray(int[][]::new);
    }
    void writeConstants(int[][] attributes,Object[] entries,java.nio.FloatBuffer output,int offset){
        for(int[] attribute:attributes)if(attribute[4]==2){int slot=attribute[0];float[] value={0,0,0,1};for(Object object:entries){Object[] entry=(Object[])object;if((Integer)entry[0]==slot){value=(float[])entry[1];break;}}for(int i=0;i<4;i++)output.put(offset+slot*4+i,vertexInputs.get(slot)==SpirvVertexInputs.FLOAT?value[i]:Float.intBitsToFloat((int)value[i]));}
    }
    void bindVertexInputs(VkCommandBuffer command,int[][] attributes,int stride){
        if(!context.legacyVertexAttributes)return;
        try(var stack=MemoryStack.stackPush()){
            int[][] consumed=Arrays.stream(attributes).filter(attribute->vertexInputs.containsKey(attribute[0])).toArray(int[][]::new);boolean constants=Arrays.stream(consumed).anyMatch(a->a[4]==2);
            var streams=VkVertexInputBindingDescription2EXT.calloc(constants?2:1,stack);streams.get(0).sType$Default().binding(0).stride(stride).inputRate(VK_VERTEX_INPUT_RATE_VERTEX).divisor(1);if(constants)streams.get(1).sType$Default().binding(1).stride(0).inputRate(VK_VERTEX_INPUT_RATE_INSTANCE).divisor(1);
            var inputs=VkVertexInputAttributeDescription2EXT.calloc(consumed.length,stack);for(int i=0;i<consumed.length;i++){int[] a=consumed[i];inputs.get(i).sType$Default().location(a[0]).binding(a[4]==2?1:0).format(format(a[1],a[2],a[3]!=0,vertexInputs.get(a[0])!=SpirvVertexInputs.FLOAT)).offset(a[5]);}
            EXTVertexInputDynamicState.vkCmdSetVertexInputEXT(command,streams,inputs);
        }
    }
    long pipeline(int[] state,int[][] attributes,int stride) {
        return pipeline(state,attributes,stride,DEFAULT_FORMATS,VK_FORMAT_D32_SFLOAT);
    }
    long pipeline(int[] state,int[][] attributes,int stride,int[] formats,int depthFormat) {
        PipelineKey key=new PipelineKey(state,attributes,stride,formats,depthFormat);Long existing=pipelines.get(key);if(existing!=null)return existing;
        int[][] consumed=Arrays.stream(attributes).filter(attribute->vertexInputs.containsKey(attribute[0])).toArray(int[][]::new);
        if(consumed.length!=vertexInputs.size())throw new UnsupportedOperationException("Native draw lacks shader vertex inputs: "+vertexInputs.keySet());
        // Buffer names are resource identities, not pipeline state. Chunk recycling must reuse the same pipeline.
        int[][] layoutAttributes=Arrays.stream(consumed).map(attribute->Arrays.copyOf(attribute,6)).toArray(int[][]::new);
        try(var stack=MemoryStack.stackPush()) {
            Stages modules=stages(formats);
            var stages=VkPipelineShaderStageCreateInfo.calloc(2,stack);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(modules.vertex()).pName(stack.UTF8("main"));stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(modules.fragment()).pName(stack.UTF8("main"));
            var attrs=VkVertexInputAttributeDescription.calloc(consumed.length,stack);
            boolean constants=false;for(int i=0;i<consumed.length;i++){int[] a=consumed[i];boolean fixed=a[4]==2;constants|=fixed;attrs.get(i).location(a[0]).binding(fixed?1:0).format(format(a[1],a[2],a[3]!=0,vertexInputs.get(a[0])!=SpirvVertexInputs.FLOAT)).offset(a[5]);}
            var streams=VkVertexInputBindingDescription.calloc(constants?2:1,stack);streams.get(0).binding(0).stride(stride).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);if(constants)streams.get(1).binding(1).stride(0).inputRate(VK_VERTEX_INPUT_RATE_INSTANCE);
            var input=VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default().pVertexBindingDescriptions(streams).pVertexAttributeDescriptions(attrs);
            var blend=VkPipelineColorBlendAttachmentState.calloc(formats.length,stack);
            for(int i=0;i<formats.length;i++){
                int at=17+i*8;boolean custom=state.length>=at+8;
                blend.get(i).colorWriteMask(formats[i]==VK_FORMAT_UNDEFINED?0:custom?state[at+7]:state[12]).blendEnable(formats[i]!=VK_FORMAT_UNDEFINED&&(custom?state[at]:state[0])!=0)
                    .srcColorBlendFactor(factor(custom?state[at+1]:state[1])).dstColorBlendFactor(factor(custom?state[at+2]:state[2])).srcAlphaBlendFactor(factor(custom?state[at+3]:state[3])).dstAlphaBlendFactor(factor(custom?state[at+4]:state[4])).colorBlendOp(operation(custom?state[at+5]:state[5])).alphaBlendOp(operation(custom?state[at+6]:state[6]));
            }
            var raster=VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default().polygonMode(VK_POLYGON_MODE_FILL).lineWidth(1)
                    .cullMode(state[10]==0?VK_CULL_MODE_NONE:state[10]==1029?VK_CULL_MODE_BACK_BIT:state[10]==1028?VK_CULL_MODE_FRONT_BIT:VK_CULL_MODE_FRONT_AND_BACK)
                    .frontFace(state[11]==2305?VK_FRONT_FACE_CLOCKWISE:VK_FRONT_FACE_COUNTER_CLOCKWISE).depthBiasEnable(state[14]!=0);
            var info=VkGraphicsPipelineCreateInfo.calloc(1,stack).sType$Default().pStages(stages).layout(layout)
                    .pNext(VkPipelineRenderingCreateInfo.calloc(stack).sType$Default().pColorAttachmentFormats(stack.ints(formats)).depthAttachmentFormat(depthFormat))
                    .pVertexInputState(input).pInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default().topology(topology(state[15])))
                    .pViewportState(VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().viewportCount(1).scissorCount(1)).pRasterizationState(raster)
                    .pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT))
                    .pDepthStencilState(VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default().depthTestEnable(state[7]!=0).depthWriteEnable(state[8]!=0).depthCompareOp(compare(state[9])))
                    .pColorBlendState(VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().pAttachments(blend))
                    .pDynamicState(VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default().pDynamicStates(context.legacyVertexAttributes?stack.ints(VK_DYNAMIC_STATE_VIEWPORT,VK_DYNAMIC_STATE_SCISSOR,VK_DYNAMIC_STATE_DEPTH_BIAS,EXTVertexInputDynamicState.VK_DYNAMIC_STATE_VERTEX_INPUT_EXT):stack.ints(VK_DYNAMIC_STATE_VIEWPORT,VK_DYNAMIC_STATE_SCISSOR,VK_DYNAMIC_STATE_DEPTH_BIAS)));
            var out=stack.mallocLong(1);check(vkCreateGraphicsPipelines(context.device,context.pipelineCache,info,null,out),"native game graphics pipeline");context.pipelinesCreated++;pipelines.put(new PipelineKey(state.clone(),layoutAttributes,stride,formats.clone(),depthFormat),out.get(0));return out.get(0);
        }
    }
    private static int format(int size,int type,boolean normalized,boolean integer) {
        int[] formats=switch(type){
            case 5126->new int[]{VK_FORMAT_R32_SFLOAT,VK_FORMAT_R32G32_SFLOAT,VK_FORMAT_R32G32B32_SFLOAT,VK_FORMAT_R32G32B32A32_SFLOAT};
            case 5125->{if(!integer)throw new UnsupportedOperationException("Native uint attribute requires integer input");yield new int[]{VK_FORMAT_R32_UINT,VK_FORMAT_R32G32_UINT,VK_FORMAT_R32G32B32_UINT,VK_FORMAT_R32G32B32A32_UINT};}
            case 5124->new int[]{VK_FORMAT_R32_SINT,VK_FORMAT_R32G32_SINT,VK_FORMAT_R32G32B32_SINT,VK_FORMAT_R32G32B32A32_SINT};
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
    @Override public void close(){for(long pipeline:pipelines.values())vkDestroyPipeline(context.device,pipeline,null);pipelines.clear();for(var modules:stageVariants.values()){if(modules.vertex()!=vertex)vkDestroyShaderModule(context.device,modules.vertex(),null);vkDestroyShaderModule(context.device,modules.fragment(),null);}stageVariants.clear();if(layout!=0)vkDestroyPipelineLayout(context.device,layout,null);if(textureLayout!=0)vkDestroyDescriptorSetLayout(context.device,textureLayout,null);if(vertex!=0)vkDestroyShaderModule(context.device,vertex,null);if(fragment!=0)vkDestroyShaderModule(context.device,fragment,null);}
}
