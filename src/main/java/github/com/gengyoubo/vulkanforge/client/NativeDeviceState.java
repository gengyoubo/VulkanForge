package github.com.gengyoubo.vulkanforge.client;

import java.nio.*;
import java.util.*;
import org.lwjgl.system.MemoryUtil;

/** CPU resource/state adapter for Blaze3D. It never loads an OpenGL entry point. Unknown operations fail closed. */
public final class NativeDeviceState {
    private static final boolean DIAGNOSTICS=Boolean.getBoolean("vulkanforge.nativeDiagnostics"),PROFILE=Boolean.getBoolean("vulkanforge.nativeProfile");
    private static long adapterCalls,adapterNs,drawAdapterNs,bufferGeneration,terrainSubmissions,unchangedTerrainSubmissions,unchangedTerrainBytes,indexSliceBytes,embeddiumBatches;
    private static long textureGeneration;
    private static final Set<Long> cpuFences=new HashSet<>();
    private static final Map<Integer,Long> bufferVersions=new HashMap<>();
    private record BufferUse(long version,long frame) { }
    private static final Map<Integer,BufferUse> terrainUses=new HashMap<>();
    private static int sequence=1,activeTexture,arrayBuffer,vao,drawTarget,readTarget,currentProgram,proxyWidth;
    private static final int[] boundTextures=new int[32],viewport={0,0,1,1},scissor={0,0,1,1};
    private static final Map<Integer,Boolean> enabled=new HashMap<>();
    private static final Map<Integer,Integer> integers=new HashMap<>();
    private static final Map<Integer,byte[]> buffers=new HashMap<>();
    private static final Set<Integer> sharedBufferSnapshots=new HashSet<>();
    private record Mapping(ByteBuffer bytes,int offset,int flags) { }
    private static final Map<Integer,Mapping> mapped=new HashMap<>();
    private static final class VertexArray {int indices;int[][] layout;final Map<Integer,int[]> attributes=new TreeMap<>();final Set<Integer> enabled=new HashSet<>();}
    private static final Map<Integer,VertexArray> arrays=new HashMap<>();
    private static final class Page {int width,height,internal;byte[] rgba;long version,uploaded;int flags;int[] swizzle;boolean target,empty;final Map<Integer,Integer> parameters=new HashMap<>();}
    private static final Map<Integer,Page> pages=new HashMap<>();
    private static final class Framebuffer {int color,depth,width,height,read;long generation;final int[] colors=new int[16];int[] selection={0};final long[] versions=new long[17];boolean dirty=true;}
    private static final Map<Integer,Framebuffer> framebuffers=new HashMap<>();
    private static final class Shader {int type;String source;}
    private static final Map<Integer,Shader> shaders=new HashMap<>();
    private static final class Program {final List<Integer> shaders=new ArrayList<>();final Map<String,Integer> attributes=new LinkedHashMap<>(),outputs=new LinkedHashMap<>();final Map<String,float[]> values=new LinkedHashMap<>();final Map<String,Object[]> entries=new LinkedHashMap<>();Object[] snapshot;String[] samplers={};List<NativeUniformInfo.Uniform> uniforms=List.of();boolean linked,terrain;}
    private static final Map<Integer,Program> programs=new HashMap<>();
    private static final class Sampler {int flags=56;boolean whiteBorder;final Map<Integer,Integer> parameters=new HashMap<>();}
    private static final Map<Integer,Sampler> samplerObjects=new HashMap<>();
    private static final int[] boundSamplers=new int[32];
    private static final Map<Integer,float[]> constantAttributes=new HashMap<>();
    private static String[] deviceInfo;
    private record Location(int program,String name) { }
    private static final Map<Integer,Location> locations=new HashMap<>();
    private static final Map<String,Integer> locationIds=new HashMap<>();
    private static int src=1,dst=0,srcAlpha=1,dstAlpha=0,equation=32774,equationAlpha=32774,depthFunc=513,cullFace=1029,frontFace=2305,colorMask=15;
    private static boolean depthMask=true;
    private static final int[][] indexedBlend=new int[8][];
    private static int[] blend(int index){if(index<0||index>=indexedBlend.length)throw unsupported("blend attachment "+index);if(indexedBlend[index]==null)indexedBlend[index]=new int[]{on(3042)?1:0,src,dst,srcAlpha,dstAlpha,equation,equationAlpha,colorMask};return indexedBlend[index];}
    private static float clearDepth=1,offsetFactor,offsetUnits;
    private static final float[] clearColor=new float[4];
    static {integers.put(3317,4);integers.put(3333,4);arrays.put(0,new VertexArray());}
    private NativeDeviceState() { }
    private static int id(){return sequence++;}
    private static int number(Object[] args,int at){return ((Number)args[at]).intValue();}
    private static boolean flag(Object[] args,int at){return (Boolean)args[at];}
    private static VertexArray array(){return arrays.computeIfAbsent(vao,key->new VertexArray());}
    private static int buffer(int target){return target==34963?array().indices:target==34962?arrayBuffer:integers.getOrDefault(target,0);}
    private static Program program(){Program value=programs.get(currentProgram);if(value==null)throw new IllegalStateException("No native shader program is bound");return value;}
    private static Page page(){Page value=pages.get(boundTextures[activeTexture]);if(value==null)throw new IllegalStateException("No native texture is bound");return value;}
    public static Object call(String operation,Object[] a) {
        if(!PROFILE)return execute(operation,a);
        long start=System.nanoTime();
        try{return execute(operation,a);}finally{long spent=System.nanoTime()-start;adapterCalls++;adapterNs+=spent;if(operation.equals("glDrawElements"))drawAdapterNs+=spent;}
    }
    public static Map<String,Long> diagnostics(){
        Map<String,Long> result=new LinkedHashMap<>();result.put("adapterCalls",adapterCalls);result.put("adapterInclusiveNs",adapterNs);result.put("adapterDrawInclusiveNs",drawAdapterNs);
        result.put("terrainBufferSubmissions",terrainSubmissions);result.put("unchangedTerrainBufferDraws",unchangedTerrainSubmissions);result.put("unchangedTerrainReferencedBytes",unchangedTerrainBytes);result.put("indexSliceBytes",indexSliceBytes);result.put("embeddiumMultiDrawBatches",embeddiumBatches);return result;
    }
    private static Object execute(String operation,Object[] a) {
        if(!NativeGameWindow.enabled())throw new IllegalStateException("Native adapter used outside the native bootstrap");
        switch(operation) {
            case "createCapabilities":case "getCapabilities":return NativeFrontendCapabilities.get();
            case "glGetError":return 0;
            case "glGetString":return switch(number(a,0)){case 7938->"3.3 VulkanForge Vulkan frontend";case 35724->"3.30";case 7939->String.join(" ",NativeFrontendCapabilities.EXTENSIONS);case 7936->deviceInfo()[1];case 7937->deviceInfo()[0]+" (VulkanForge Vulkan)";default->"VulkanForge native Vulkan (no OpenGL context)";};
            case "glGetStringi":return NativeFrontendCapabilities.EXTENSIONS[number(a,1)];
            case "glGenSamplers":{int value=id();samplerObjects.put(value,new Sampler());return value;}
            case "glDeleteSamplers":for(int value:ids(a[0])){samplerObjects.remove(value);for(int i=0;i<boundSamplers.length;i++)if(boundSamplers[i]==value)boundSamplers[i]=0;}return null;
            case "glBindSampler":boundSamplers[number(a,0)]=number(a,1);return null;
            case "glBindSamplers":{int first=number(a,0);int[] values=(int[])a[1];System.arraycopy(values,0,boundSamplers,first,values.length);return null;}
            case "glSamplerParameteri":case "glSamplerParameterf":{Sampler sampler=Objects.requireNonNull(samplerObjects.get(number(a,0)),"Native sampler");int parameter=number(a,1),value=number(a,2);sampler.parameters.put(parameter,value);sampler.flags=samplerFlags(sampler.flags,parameter,value);return null;}
            case "glSamplerParameteriv":{Sampler sampler=Objects.requireNonNull(samplerObjects.get(number(a,0)));int[] values=(int[])a[2];if(number(a,1)==4100){sampler.whiteBorder=borderWhite(values);return null;}sampler.parameters.put(number(a,1),values[0]);sampler.flags=samplerFlags(sampler.flags,number(a,1),values[0]);return null;}
            case "glGenTextures":case "glGenBuffers":case "glGenVertexArrays":case "glGenFramebuffers":case "glGenRenderbuffers": {
                if(a.length==0){int value=id();if(operation.equals("glGenTextures"))pages.put(value,new Page());return value;}
                if(a[0] instanceof int[] values){for(int i=0;i<values.length;i++){values[i]=id();if(operation.equals("glGenTextures"))pages.put(values[i],new Page());}return null;}
                throw unsupported(operation);
            }
            case "glDeleteTextures":for(int value:ids(a[0])){pages.remove(value);NativeGameWindow.call("deleteTexture",value);}return null;
            case "glDeleteBuffers":for(int value:ids(a[0])){buffers.remove(value);sharedBufferSnapshots.remove(value);mapped.remove(value);bufferVersions.remove(value);terrainUses.remove(value);NativeGameWindow.call("deleteBuffer",value);}return null;
            case "glDeleteVertexArrays":for(int value:ids(a[0]))arrays.remove(value);return null;
            case "glDeleteFramebuffers":for(int value:ids(a[0])){framebuffers.remove(value);NativeGameWindow.call("deleteTarget",value);}return null;
            case "glBindTexture":if(number(a,0)!=3553)throw unsupported(operation+" target "+a[0]);boundTextures[activeTexture]=number(a,1);if(number(a,1)>0)pages.computeIfAbsent(number(a,1),key->new Page());return null;
            case "glActiveTexture":activeTexture=number(a,0)-33984;return null;
            case "glPixelStorei":integers.put(number(a,0),number(a,1));return null;
            case "glTexImage2D":image(a);return null;
            case "glTexSubImage2D":subImage(a);return null;
            case "glTexParameteriv":{Page page=page();int parameter=number(a,1);int[] values=(int[])a[2];if(parameter==36422){page.swizzle=values.clone();if(page.uploaded==page.version&&page.version>0)NativeGameWindow.call("textureSwizzle",boundTextures[activeTexture],page.swizzle);return null;}if(parameter!=4100)throw unsupported("texture vector parameter "+parameter);page.flags=(page.flags&~128)|(borderWhite(values)?0:128);NativeGameWindow.call("textureSampler",boundTextures[activeTexture],page.flags);return null;}
            case "glGenerateMipmap":if(number(a,0)!=3553)throw unsupported(operation+" target "+a[0]);ensureTexture(boundTextures[activeTexture]);NativeGameWindow.call("generateMipmaps",boundTextures[activeTexture]);return null;
            case "glCopyTexImage2D":{if(number(a,0)!=3553||number(a,1)!=0||number(a,7)!=0)throw unsupported(operation+" target/level/border");Page page=page();page.internal=number(a,2);page.width=number(a,5);page.height=number(a,6);page.empty=true;page.rgba=null;page.version++;textureGeneration++;ensureTexture(boundTextures[activeTexture]);copyTexture(number(a,3),number(a,4),page.width,page.height,0,0);return null;}
            case "glCopyTexSubImage2D":if(number(a,0)!=3553||number(a,1)!=0)throw unsupported(operation+" target/level");ensureTexture(boundTextures[activeTexture]);copyTexture(number(a,4),number(a,5),number(a,6),number(a,7),number(a,2),number(a,3));return null;
            case "glReadPixels":{if(number(a,5)!=5126||!(a[6] instanceof float[] out))throw unsupported(operation+" pixel type/storage");prepareTarget(readTarget);Framebuffer fb=framebuffers.get(readTarget);int format=number(a,4),texture=format==6402?fb.depth:fb.colors[fb.read],components=format==6408?4:format==6407?3:1;float[] pixels=(float[])NativeGameWindow.call("readTextureFloats",texture);Page page=pages.get(texture);int x=number(a,0),y=number(a,1),width=number(a,2),height=number(a,3);Objects.checkFromIndexSize(x,width,page.width);Objects.checkFromIndexSize(y,height,page.height);for(int row=0;row<height;row++)for(int col=0;col<width;col++)System.arraycopy(pixels,((y+row)*page.width+x+col)*4,out,(row*width+col)*components,components);return null;}
            case "glTexParameteri":case "glTexParameterf": {
                Page page=page();int name=number(a,1),value=number(a,2);page.parameters.put(name,value);
                page.flags=samplerFlags(page.flags,name,value);
                NativeGameWindow.call("textureSampler",boundTextures[activeTexture],page.flags);return null;
            }
            case "glGetTexParameteri":return page().parameters.getOrDefault(number(a,1),number(a,1)==10240?9728:10497);
            case "glGetTexLevelParameteri":{int name=number(a,2);if(number(a,0)==32868)return proxyWidth;Page page=page();return name==4096?page.width:name==4097?page.height:name==4099?page.internal:0;}
            case "glGetTexImage":{
                if(number(a,1)!=0||number(a,2)!=6408||number(a,3)!=5121)throw unsupported("native texture readback format");
                int texture=boundTextures[activeTexture];ensureTexture(texture);Page page=page();byte[] pixels=(byte[])NativeGameWindow.call("readTexture",texture);
                long destination=a[4] instanceof Number value?value.longValue():a[4] instanceof IntBuffer data?MemoryUtil.memAddress(data):MemoryUtil.memAddress((ByteBuffer)a[4]);
                int row=integers.getOrDefault(3330,0);if(row==0)row=page.width;int alignment=integers.getOrDefault(3333,4),stride=(row*4+alignment-1)/alignment*alignment,skipRows=integers.getOrDefault(3331,0),skipPixels=integers.getOrDefault(3332,0);
                for(int y=0;y<page.height;y++)MemoryUtil.memByteBuffer(destination+(long)(y+skipRows)*stride+skipPixels*4L,page.width*4).put(pixels,y*page.width*4,page.width*4);return null;
            }
            case "glBindBuffer":if(number(a,0)==34963)array().indices=number(a,1);else if(number(a,0)==34962)arrayBuffer=number(a,1);else integers.put(number(a,0),number(a,1));return null;
            case "glBufferData":{int target=buffer(number(a,0));if(a[1] instanceof ByteBuffer bytes){byte[] data=new byte[bytes.remaining()];bytes.duplicate().get(data);buffers.put(target,data);}else if(a[1] instanceof float[] floats){ByteBuffer bytes=ByteBuffer.allocate(floats.length*4).order(ByteOrder.nativeOrder());bytes.asFloatBuffer().put(floats);buffers.put(target,bytes.array());}else buffers.put(target,new byte[Math.toIntExact(((Number)a[1]).longValue())]);bufferVersions.put(target,++bufferGeneration);return null;}
            case "glCopyBufferSubData":{int source=buffer(number(a,0)),destination=buffer(number(a,1)),read=Math.toIntExact(((Number)a[2]).longValue()),write=Math.toIntExact(((Number)a[3]).longValue()),size=Math.toIntExact(((Number)a[4]).longValue());byte[] input=Objects.requireNonNull(buffers.get(source)),output=Objects.requireNonNull(buffers.get(destination));if(sharedBufferSnapshots.remove(destination))output=output.clone();System.arraycopy(input,read,output,write,size);buffers.put(destination,output);bufferVersions.put(destination,++bufferGeneration);return null;}
            case "glMapBuffer":{int target=buffer(number(a,0)),access=number(a,1);return map(target,0,Objects.requireNonNull(buffers.get(target)).length,access==35000?1:access==35001?2:3);}
            case "glMapBufferRange":return map(buffer(number(a,0)),Math.toIntExact(((Number)a[1]).longValue()),Math.toIntExact(((Number)a[2]).longValue()),number(a,3));
            case "glFlushMappedBufferRange":{int target=buffer(number(a,0));publishMapping(target,Objects.requireNonNull(mapped.get(target)),Math.toIntExact(((Number)a[1]).longValue()),Math.toIntExact(((Number)a[2]).longValue()));return null;}
            case "glUnmapBuffer":{int target=buffer(number(a,0));Mapping mapping=mapped.remove(target);if(mapping!=null&&(mapping.flags()&2)!=0&&(mapping.flags()&16)==0)publishMapping(target,mapping,0,mapping.bytes().capacity());return true;}
            case "glBindVertexArray":vao=number(a,0);array();return null;
            case "glVertexAttribPointer":case "glVertexAttribIPointer": {
                boolean integer=operation.equals("glVertexAttribIPointer");int stride=number(a,integer?3:4),offset=Math.toIntExact(((Number)a[integer?4:5]).longValue());
                array().attributes.put(number(a,0),new int[]{number(a,0),number(a,1),number(a,2),!integer&&flag(a,3)?1:0,integer?1:0,offset,stride,arrayBuffer});array().layout=null;return null;
            }
            case "glEnableVertexAttribArray":if(array().enabled.add(number(a,0)))array().layout=null;return null;
            case "glDisableVertexAttribArray":if(array().enabled.remove(number(a,0)))array().layout=null;return null;
            case "glCreateShader":{int value=id();Shader shader=new Shader();shader.type=number(a,0);shaders.put(value,shader);return value;}
            case "nglShaderSource":{Shader shader=shaders.get(number(a,0));StringBuilder source=new StringBuilder();int count=number(a,1);var pointers=MemoryUtil.memPointerBuffer(((Number)a[2]).longValue(),count);long lengths=((Number)a[3]).longValue();for(int i=0;i<count;i++){int length=lengths==0?-1:MemoryUtil.memGetInt(lengths+i*4L);source.append(length<0?MemoryUtil.memUTF8(pointers.get(i)):MemoryUtil.memUTF8(pointers.get(i),length));}shader.source=source.toString();return null;}
            case "glShaderSource":{Shader shader=shaders.get(number(a,0));shader.source=a[1].toString();return null;}
            case "glCompileShader":if(shaders.get(number(a,0)).source==null)throw new IllegalStateException("Native shader has no source");return null;
            case "glGetShaderi":return number(a,1)==35713?1:0;
            case "glGetShaderInfoLog":case "glGetProgramInfoLog":return "";
            case "glDeleteShader":shaders.remove(number(a,0));return null;
            case "glCreateProgram":{int value=id();programs.put(value,new Program());return value;}
            case "glAttachShader":programs.get(number(a,0)).shaders.add(number(a,1));return null;
            case "glDetachShader":return null; // The Vulkan modules own their code after link.
            case "glBindAttribLocation":programs.get(number(a,0)).attributes.put(a[2].toString(),number(a,1));return null;
            case "glBindFragDataLocation":programs.get(number(a,0)).outputs.put(a[2].toString(),number(a,1));return null;
            case "glGetAttribLocation":return programs.get(number(a,0)).attributes.getOrDefault(a[1].toString(),-1);
            case "glLinkProgram":{int value=number(a,0);Program program=programs.get(value);String vertex=null,fragment=null;for(int shaderId:program.shaders){Shader shader=shaders.get(shaderId);if(shader.type==35633)vertex=shader.source;else if(shader.type==35632)fragment=shader.source;}
                if(vertex==null||fragment==null)throw new IllegalStateException("Native program lacks vertex or fragment source");
                program.terrain=vertex.contains("ChunkOffset")||vertex.contains("u_RegionOffset")&&vertex.contains("a_PosId");
                var inputs=java.util.regex.Pattern.compile("(?m)^\\s*(?:layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s*)?in\\s+\\w+\\s+(\\w+)\\s*;").matcher(vertex);
                while(inputs.find()){String name=inputs.group(2);if(inputs.group(1)!=null)program.attributes.put(name,Integer.parseInt(inputs.group(1)));else if(!program.attributes.containsKey(name)){int slot=0;while(program.attributes.containsValue(slot))slot++;program.attributes.put(name,slot);}}
                Object[] attributes=program.attributes.entrySet().stream().map(entry->new Object[]{entry.getKey(),entry.getValue()}).toArray();
                Object[] outputs=program.outputs.entrySet().stream().map(entry->new Object[]{entry.getKey(),entry.getValue()}).toArray();
                program.samplers=(String[])NativeGameWindow.call("programWithOutputs",value,vertex,fragment,attributes,outputs);Set<String> active=Set.of((String[])NativeGameWindow.call("programUniforms",value));program.uniforms=NativeUniformInfo.read(vertex+"\n"+fragment,Set.of(program.samplers)).stream().filter(uniform->active.contains(uniform.name())).toList();program.linked=true;return null;}
            case "glGetProgrami":return programParameter(number(a,0),number(a,1));
            case "glGetProgramiv":{int value=programParameter(number(a,0),number(a,1));if(a[2] instanceof int[] out)out[0]=value;else ((IntBuffer)a[2]).put(((IntBuffer)a[2]).position(),value);return null;}
            case "glGetActiveUniform":{var uniform=programs.get(number(a,0)).uniforms.get(number(a,1));((IntBuffer)a[3]).put(((IntBuffer)a[3]).position(),uniform.count());((IntBuffer)a[4]).put(((IntBuffer)a[4]).position(),NativeUniformInfo.type(uniform.type()));String name=uniform.activeName();return name.substring(0,Math.min(name.length(),Math.max(0,number(a,2)-1)));}
            case "glGetUniformBlockIndex":return -1;
            case "glUniformBlockBinding":throw unsupported("explicit uniform block binding");
            case "glDeleteProgram":{int owner=number(a,0);programs.remove(owner);locations.entrySet().removeIf(entry->entry.getValue().program()==owner);locationIds.keySet().removeIf(key->key.startsWith(owner+":"));NativeGameWindow.call("deleteProgram",owner);return null;}
            case "glUseProgram":currentProgram=number(a,0);return null;
            case "glGetUniformLocation":{int owner=number(a,0);String name=a[1].toString(),base=name.replaceFirst("\\[\\d+]$",""),key=owner+":"+name;if(programs.get(owner).uniforms.stream().noneMatch(uniform->uniform.name().equals(base)))return -1;return locationIds.computeIfAbsent(key,unused->{int value=id();locations.put(value,new Location(owner,name));return value;});}
            case "glUniform1i":case "glUniform1iv":case "glUniform2iv":case "glUniform3iv":case "glUniform4iv":case "glUniform1fv":case "glUniform2fv":case "glUniform3fv":case "glUniform4fv":case "glUniformMatrix2fv":case "glUniformMatrix3fv":case "glUniformMatrix4fv":uniform(a);return null;
            case "glUniform1f":case "glUniform2f":case "glUniform3f":case "glUniform4f":{float[] data=new float[a.length-1];for(int i=0;i<data.length;i++)data[i]=((Number)a[i+1]).floatValue();uniform(new Object[]{a[0],data});return null;}
            case "glUniform2i":case "glUniform3i":case "glUniform4i":{int[] data=new int[a.length-1];for(int i=0;i<data.length;i++)data[i]=number(a,i+1);uniform(new Object[]{a[0],data});return null;}
            case "glVertexAttrib4f":constantAttributes.put(number(a,0),new float[]{((Number)a[1]).floatValue(),((Number)a[2]).floatValue(),((Number)a[3]).floatValue(),((Number)a[4]).floatValue()});return null;
            case "glBindFramebuffer":{int target=number(a,0),value=number(a,1);if(target==36160||target==36009)drawTarget=value;if(target==36160||target==36008)readTarget=value;return null;}
            case "glFramebufferTexture2D":{Framebuffer framebuffer=framebuffers.computeIfAbsent(number(a,0)==36008?readTarget:drawTarget,key->new Framebuffer());int texture=number(a,3),attachment=number(a,1);if(attachment>=36064&&attachment<36080){framebuffer.colors[attachment-36064]=texture;if(attachment==36064)framebuffer.color=texture;}else if(attachment==36096)framebuffer.depth=texture;else throw unsupported(operation+" attachment "+attachment);framebuffer.dirty=true;if(texture>0)pages.get(texture).target=true;return null;}
            case "glDrawBuffer":setDrawBuffers(new int[]{number(a,0)});return null;
            case "glDrawBuffers":{int[] values;if(a[0] instanceof int[] array)values=array.clone();else if(a[0] instanceof IntBuffer data){values=new int[data.remaining()];data.duplicate().get(values);}else values=new int[]{number(a,0)};setDrawBuffers(values);return null;}
            case "glReadBuffer":framebuffers.computeIfAbsent(readTarget,key->new Framebuffer()).read=attachmentSlot(number(a,0));return null;
            case "glMemoryBarrier":NativeGameWindow.call("memoryBarrier",number(a,0));return null;
            case "glCheckFramebufferStatus":prepareTarget(number(a,0)==36008?readTarget:drawTarget);return 36053;
            case "glBlitFramebuffer":prepareTarget(readTarget);prepareTarget(drawTarget);NativeGameWindow.call("blitAttachments",readTarget,framebuffers.get(readTarget).read,drawTarget,framebuffers.get(drawTarget).selection.length==0?-1:framebuffers.get(drawTarget).selection[0],Arrays.stream(a).limit(8).mapToInt(value->((Number)value).intValue()).toArray(),number(a,8),number(a,9));return null;
            case "glEnable":case "glDisable":{boolean value=operation.equals("glEnable");enabled.put(number(a,0),value);if(number(a,0)==3042)for(int[] state:indexedBlend)if(state!=null)state[0]=value?1:0;return null;}
            case "glEnablei":case "glDisablei":if(number(a,0)!=3042)throw unsupported(operation+" "+a[0]);blend(number(a,1))[0]=operation.equals("glEnablei")?1:0;return null;
            case "glBlendFuncSeparateiARB":case "glBlendFuncSeparatei":{int[] state=blend(number(a,0));for(int i=1;i<=4;i++)state[i]=number(a,i);return null;}
            case "glIsEnabled":return on(number(a,0));
            case "glViewport":for(int i=0;i<4;i++)viewport[i]=number(a,i);return null;
            case "glScissor":for(int i=0;i<4;i++)scissor[i]=number(a,i);return null;
            case "glDepthMask":depthMask=flag(a,0);return null;
            case "glDepthFunc":depthFunc=number(a,0);return null;
            case "glCullFace":cullFace=number(a,0);return null;
            case "glFrontFace":frontFace=number(a,0);return null;
            case "glBlendFunc":case "glBlendFuncSeparate":src=number(a,0);dst=number(a,1);srcAlpha=a.length==2?src:number(a,2);dstAlpha=a.length==2?dst:number(a,3);for(int[] state:indexedBlend)if(state!=null){state[1]=src;state[2]=dst;state[3]=srcAlpha;state[4]=dstAlpha;}return null;
            case "glBlendEquation":case "glBlendEquationSeparate":equation=number(a,0);equationAlpha=a.length==1?equation:number(a,1);for(int[] state:indexedBlend)if(state!=null){state[5]=equation;state[6]=equationAlpha;}return null;
            case "glColorMask":colorMask=0;for(int i=0;i<4;i++)if(flag(a,i))colorMask|=1<<i;for(int[] state:indexedBlend)if(state!=null)state[7]=colorMask;return null;
            case "glPolygonOffset":offsetFactor=((Number)a[0]).floatValue();offsetUnits=((Number)a[1]).floatValue();return null;
            case "glPolygonMode":if(number(a,1)!=6914)throw unsupported(operation+" non-fill");return null;
            case "glClearDepth":clearDepth=((Number)a[0]).floatValue();return null;
            case "glClearColor":for(int i=0;i<4;i++)clearColor[i]=((Number)a[i]).floatValue();return null;
            case "glClear":{prepareTarget(drawTarget);int mask=number(a,0);if((mask&~(16384|256))!=0)throw unsupported(operation+" mask "+mask);if((mask&16384)!=0&&colorMask!=15)throw unsupported("masked color clear");NativeGameWindow.call("clear",drawTarget,mask,clearColor.clone(),clearDepth,clip(drawTarget));return null;}
            case "glDrawElements":draw(number(a,0),number(a,1),number(a,2),((Number)a[3]).longValue());return null;
            case "nglMultiDrawElementsBaseVertex":{int count=number(a,4);long counts=((Number)a[1]).longValue(),offsets=((Number)a[3]).longValue(),bases=((Number)a[5]).longValue();embeddiumBatches++;for(int i=0;i<count;i++)draw(number(a,0),MemoryUtil.memGetInt(counts+i*4L),number(a,2),MemoryUtil.memGetAddress(offsets+i*(long)org.lwjgl.system.Pointer.POINTER_SIZE),MemoryUtil.memGetInt(bases+i*4L));return null;}
            case "glGetInteger":return integer(number(a,0));
            case "glGetIntegerv":{int[] value=number(a,0)==2978?viewport:number(a,0)==3088?scissor:new int[]{integer(number(a,0))};if(a[1] instanceof int[] out)System.arraycopy(value,0,out,0,value.length);else ((IntBuffer)a[1]).duplicate().put(value);return null;}
            case "glGetBoolean":return number(a,0)==2930?depthMask:on(number(a,0));
            case "glGetBooleanv":{ByteBuffer out=(ByteBuffer)a[1];if(number(a,0)==3107){for(int i=0;i<4;i++)out.put(out.position()+i,(byte)((colorMask>>i)&1));}else out.put(out.position(),(byte)(number(a,0)==2930&&depthMask?1:0));return null;}
            case "glGetFloat":return number(a,0)==32824?offsetFactor:number(a,0)==10752?offsetUnits:0f;
            case "glGetFloatv":{if(number(a,0)!=3106)throw unsupported("native float vector query "+a[0]);if(a[1] instanceof float[] out)System.arraycopy(clearColor,0,out,0,4);else ((FloatBuffer)a[1]).duplicate().put(clearColor);return null;}
            // These fences protect CPU staging/arena copies, which complete synchronously above.
            // Real Vulkan staging and in-flight mesh versions retain their own executor fences.
            case "glFenceSync":{long value=id();cpuFences.add(value);return value;}
            case "glClientWaitSync":if(!cpuFences.contains(((Number)a[0]).longValue()))throw unsupported("unknown CPU staging fence");return 37146;
            case "glWaitSync":if(!cpuFences.contains(((Number)a[0]).longValue()))throw unsupported("unknown CPU staging fence");return null;
            case "glGetSynci":if(!cpuFences.contains(((Number)a[0]).longValue())||number(a,1)!=37140)throw unsupported("CPU staging fence query");return 37145;
            case "glDeleteSync":cpuFences.remove(((Number)a[0]).longValue());return null;
            case "glFinish":case "glFlush":return null; // Ordering and completion are established by the Vulkan executor, never a GL queue.
            default:throw unsupported(operation);
        }
    }
    private static int[] ids(Object value){return value instanceof int[] ids?ids:new int[]{((Number)value).intValue()};}
    private static ByteBuffer map(int target,int offset,int size,int flags){
        if((flags&192)!=0)throw unsupported("persistent CPU mappings; native uploads own the GPU staging ring");
        if(mapped.containsKey(target))throw new IllegalStateException("Native buffer already mapped");
        byte[] data=Objects.requireNonNull(buffers.get(target));Objects.checkFromIndexSize(offset,size,data.length);
        ByteBuffer bytes=ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());bytes.put(data,offset,size).clear();mapped.put(target,new Mapping(bytes,offset,flags));return bytes;
    }
    private static void publishMapping(int target,Mapping mapping,int offset,int size){
        if((mapping.flags()&2)==0)throw unsupported("flushing a read-only CPU mapping");Objects.checkFromIndexSize(offset,size,mapping.bytes().capacity());
        byte[] data=Objects.requireNonNull(buffers.get(target));if(sharedBufferSnapshots.remove(target))data=data.clone();
        mapping.bytes().duplicate().position(offset).limit(offset+size).get(data,mapping.offset()+offset,size);buffers.put(target,data);bufferVersions.put(target,++bufferGeneration);
    }
    private static boolean on(int value){return enabled.getOrDefault(value,false);}
    private static String[] deviceInfo(){if(deviceInfo==null)deviceInfo=(String[])NativeGameWindow.call("deviceInfo");return deviceInfo;}
    private static boolean borderWhite(int[] values){if(Arrays.stream(values).allMatch(value->value==1))return true;if(Arrays.stream(values).allMatch(value->value==0))return false;throw unsupported("custom sampler border color "+Arrays.toString(values));}
    private static int samplerFlags(int flags,int name,int value){return switch(name){case 10240->(flags&~32)|(value==9729?32:0);case 10241->(flags&~(1|8|16))|(value==9729||value==9985||value==9987?1:0)|(value>=9984&&value<=9987?8:0)|(value==9986||value==9987?16:0);case 10242,10243->(flags&~(2|64))|(value==33071?2:value==33069?64:0);case 34892->(flags&~4)|(value==34894?4:0);default->flags;};}
    private static int programParameter(int id,int name){Program program=Objects.requireNonNull(programs.get(id),"Program");return switch(name){case 35714,35715->program.linked?1:0;case 35716->1;case 35717->program.shaders.size();case 35718->program.uniforms.size();case 35719->program.uniforms.stream().mapToInt(value->value.activeName().length()+1).max().orElse(1);case 35721->program.attributes.size();case 35722->program.attributes.keySet().stream().mapToInt(value->value.length()+1).max().orElse(1);default->0;};}
    private static int integer(int value){return switch(value){case 33307,33308->3;case 33309->NativeFrontendCapabilities.EXTENSIONS.length;case 3379,34076->8192;case 34852->8;case 36063,34921->16;case 34930,35660,35661->32;case 35658,35657->4096;case 35376->65536;case 35380->256;case 37158->1;case 32873->boundTextures[activeTexture];case 34016->33984+activeTexture;case 34964->arrayBuffer;case 34965->array().indices;case 34229->vao;case 36006->drawTarget;case 36010->readTarget;case 35725->currentProgram;case 32969->src;case 32968->dst;case 32971->srcAlpha;case 32970->dstAlpha;case 32777->equation;case 34877->equationAlpha;case 2932->depthFunc;case 2885->cullFace;case 2886->frontFace;default->integers.getOrDefault(value,0);};}
    private static void image(Object[] a) {
        int width=number(a,3),height=number(a,4);if(number(a,0)==32868){proxyWidth=width<=8192&&height<=8192?width:0;return;}
        if(number(a,1)!=0)return;Page page=page();page.width=width;page.height=height;page.internal=number(a,2);page.rgba=null;page.empty=true;page.version++;textureGeneration++;
        if(a[8]!=null&&(!(a[8] instanceof Number address)||address.longValue()!=0))upload(page,0,0,width,height,number(a,6),number(a,7),a[8]);
    }
    private static void subImage(Object[] a){if(number(a,1)!=0)return;upload(page(),number(a,2),number(a,3),number(a,4),number(a,5),number(a,6),number(a,7),a[8]);}
    private static void upload(Page page,int x,int y,int width,int height,int format,int type,Object source) {
        if(type!=5121)throw unsupported("texture pixel type "+type);if(width==0||height==0)return;
        int channels=switch(format){case 6408,32993->4;case 6407,32992->3;case 6410->2;case 6403,6406,6409->1;default->throw unsupported("texture pixel format "+format);};
        long address=source instanceof Number value?value.longValue():source instanceof IntBuffer data?MemoryUtil.memAddress(data):MemoryUtil.memAddress((ByteBuffer)source);
        if(address==0)throw new IllegalArgumentException("Null native texture upload");
        int row=integers.getOrDefault(3314,0);if(row==0)row=width;int alignment=integers.getOrDefault(3317,4),stride=(row*channels+alignment-1)/alignment*alignment;
        int skipRows=integers.getOrDefault(3315,0),skipPixels=integers.getOrDefault(3316,0);
        if(x<0||y<0||x+width>page.width||y+height>page.height)throw new IllegalArgumentException("Native texture patch out of bounds");
        if(page.rgba==null)page.rgba=new byte[Math.multiplyExact(Math.multiplyExact(page.width,page.height),4)];page.empty=false;
        for(int r=0;r<height;r++){ByteBuffer input=MemoryUtil.memByteBuffer(address+(long)(r+skipRows)*stride+(long)skipPixels*channels,width*channels);for(int c=0;c<width;c++){int at=((y+r)*page.width+x+c)*4;byte red=0,green=0,blue=0,alpha=-1;if(channels>=3){red=input.get();green=input.get();blue=input.get();if(format==32993||format==32992){byte swap=red;red=blue;blue=swap;}if(channels==4)alpha=input.get();}else if(format==6406)alpha=input.get();else{red=input.get();if(format==6409||format==6410)green=blue=red;if(channels==2)alpha=input.get();}page.rgba[at]=red;page.rgba[at+1]=green;page.rgba[at+2]=blue;page.rgba[at+3]=alpha;}}
        page.version++;textureGeneration++;
    }
    private static void uniform(Object[] a) {
        Location location=locations.get(number(a,0));if(location==null)return;Object input=a[a.length-1];float[] values;
        if(input instanceof float[] data)values=data.clone();else if(input instanceof int[] data){values=new float[data.length];for(int i=0;i<data.length;i++)values[i]=data[i];}else if(input instanceof FloatBuffer data){values=new float[data.remaining()];data.duplicate().get(values);}else if(input instanceof IntBuffer data){values=new float[data.remaining()];for(int i=0;i<values.length;i++)values[i]=data.get(data.position()+i);}else values=new float[]{((Number)input).floatValue()};
        Program program=programs.get(location.program());program.values.put(location.name(),values);
        program.entries.put(location.name(),new Object[]{location.name(),values});program.snapshot=null;
    }
    private static void prepareTarget(int id) {
        if(id==0){int[] size=NativeGameWindow.extent();Framebuffer window=framebuffers.computeIfAbsent(0,key->new Framebuffer());if(window.color==0){window.color=id();window.colors[0]=window.color;window.depth=id();Page color=new Page(),depth=new Page();color.target=depth.target=color.empty=depth.empty=true;color.internal=32856;depth.internal=36012;pages.put(window.color,color);pages.put(window.depth,depth);}for(int texture:new int[]{window.color,window.depth}){Page page=pages.get(texture);if(page.width!=size[0]||page.height!=size[1]){page.width=size[0];page.height=size[1];page.version++;window.dirty=true;}}}
        Framebuffer fb=framebuffers.get(id);if(fb==null)throw new IllegalStateException("Native framebuffer has no attachments: "+id);
        if(!fb.dirty&&fb.generation==textureGeneration)return;
        Page reference=fb.depth>0?pages.get(fb.depth):null;boolean changed=fb.dirty;
        for(int slot=0;slot<16;slot++)if(fb.colors[slot]>0){Page page=pages.get(fb.colors[slot]);if(reference==null)reference=page;if(fb.versions[slot]!=page.version)changed=true;ensureTexture(fb.colors[slot]);}
        if(fb.depth>0){Page depth=pages.get(fb.depth);if(fb.versions[16]!=depth.version)changed=true;ensureTexture(fb.depth);}
        if(reference==null||reference.width<1||reference.height<1)throw new IllegalStateException("Native framebuffer has no allocated attachments: "+id);
        int width=reference.width,height=reference.height;for(int texture:fb.colors)if(texture>0){Page page=pages.get(texture);width=Math.min(width,page.width);height=Math.min(height,page.height);}
        if(changed||fb.width!=width||fb.height!=height){NativeGameWindow.call("framebuffer",id,fb.colors,fb.depth,fb.selection,width,height);fb.width=width;fb.height=height;for(int slot=0;slot<16;slot++)fb.versions[slot]=fb.colors[slot]>0?pages.get(fb.colors[slot]).version:0;fb.versions[16]=fb.depth>0?pages.get(fb.depth).version:0;fb.dirty=false;}fb.generation=textureGeneration;
    }
    private static int[] clip(int target) {Framebuffer framebuffer=framebuffers.get(target);int width=framebuffer.width,height=framebuffer.height;int x=on(3089)?Math.max(0,scissor[0]):0,y=on(3089)?Math.max(0,scissor[1]):0;int right=on(3089)?Math.min(width,scissor[0]+scissor[2]):width,top=on(3089)?Math.min(height,scissor[1]+scissor[3]):height;return new int[]{x,y,Math.max(0,right-x),Math.max(0,top-y)};}
    private static int attachmentSlot(int value){if(value==0)return -1;if(value==1029||value==1028)return 0;if(value>=36064&&value<36080)return value-36064;throw unsupported("framebuffer buffer enum "+value);}
    private static void copyTexture(int x,int y,int width,int height,int destinationX,int destinationY){prepareTarget(readTarget);Framebuffer fb=framebuffers.get(readTarget);NativeGameWindow.call("copyToTexture",readTarget,fb.read,boundTextures[activeTexture],x,y,width,height,destinationX,destinationY);}
    private static void setDrawBuffers(int[] values){if(values.length>8)throw unsupported("more than eight framebuffer outputs");int[] selection=Arrays.stream(values).map(NativeDeviceState::attachmentSlot).toArray();Framebuffer fb=framebuffers.computeIfAbsent(drawTarget,key->new Framebuffer());if(!Arrays.equals(fb.selection,selection)){fb.selection=selection;fb.dirty=true;}}
    private static void ensureTexture(int id) {if(id==0)return;Page page=pages.get(id);if(page==null)throw new IllegalStateException("Unknown native texture "+id);if(page.uploaded!=page.version){if(page.empty)NativeGameWindow.call("renderTexture",id,page.width,page.height,page.internal,page.flags);else NativeGameWindow.call("texture",id,page.width,page.height,page.flags,page.rgba);if(page.swizzle!=null)NativeGameWindow.call("textureSwizzle",id,page.swizzle);page.uploaded=page.version;}}
    private static void draw(int mode,int count,int type,long offset) {
        draw(mode,count,type,offset,0);
    }
    private static void draw(int mode,int count,int type,long offset,int baseVertex) {
        if(count==0)return;prepareTarget(drawTarget);if(on(3058)||on(2960))throw unsupported("logic-op/stencil draw");Program program=program();VertexArray array=array();
        int[][] attributes=array.layout;if(attributes==null){attributes=array.enabled.stream().sorted().map(array.attributes::get).filter(Objects::nonNull).toArray(int[][]::new);array.layout=attributes;}if(attributes.length==0)throw new IllegalStateException("Native draw has no vertex attributes");int stride=attributes[0][6],vertexBuffer=attributes[0][7];
        for(int[] attr:attributes)if(attr[6]!=stride||attr[7]!=vertexBuffer)throw unsupported("multiple native vertex streams");
        byte[] vertexBytes=Objects.requireNonNull(buffers.get(vertexBuffer),"Native vertex data"),allIndices=Objects.requireNonNull(buffers.get(array.indices),"Native index data");int indexBytes=type==5123?2:type==5125?4:0;if(indexBytes==0)throw unsupported("native index type "+type);
        sharedBufferSnapshots.add(vertexBuffer);sharedBufferSnapshots.add(array.indices);
        Objects.checkFromIndexSize(Math.toIntExact(offset),Math.multiplyExact(count,indexBytes),allIndices.length);
        if(DIAGNOSTICS){
            if(program.terrain){terrainSubmissions++;long version=bufferVersions.getOrDefault(vertexBuffer,0L),frame=NativeGameWindow.frameSerial();
                BufferUse old=terrainUses.put(vertexBuffer,new BufferUse(version,frame));
                if(old!=null&&old.version()==version&&old.frame()!=frame){unchangedTerrainSubmissions++;unchangedTerrainBytes+=vertexBytes.length;}
            }
        }
        int[] textures=new int[program.samplers.length],samplerOverrides=new int[program.samplers.length];Arrays.fill(samplerOverrides,-1);for(int i=0;i<textures.length;i++){float[] value=program.values.get(program.samplers[i]);int unit=value==null?0:(int)value[0];textures[i]=boundTextures[unit];ensureTexture(textures[i]);if(boundSamplers[unit]!=0){Sampler sampler=Objects.requireNonNull(samplerObjects.get(boundSamplers[unit]));samplerOverrides[i]=sampler.flags|((sampler.flags&64)!=0&&!sampler.whiteBorder?128:0);}}
        int[] state={on(3042)?1:0,src,dst,srcAlpha,dstAlpha,equation,equationAlpha,on(2929)&&framebuffers.get(drawTarget).depth>0?1:0,depthMask?1:0,depthFunc,on(2884)?cullFace:0,frontFace,colorMask,6914,on(32823)?1:0,mode,0};
        if(Arrays.stream(indexedBlend).anyMatch(Objects::nonNull)){state=Arrays.copyOf(state,17+8*8);for(int i=0;i<8;i++)System.arraycopy(indexedBlend[i]==null?new int[]{on(3042)?1:0,src,dst,srcAlpha,dstAlpha,equation,equationAlpha,colorMask}:indexedBlend[i],0,state,17+i*8,8);}
        int[] box=clip(drawTarget),area={viewport[0],viewport[1],viewport[2],viewport[3],box[0],box[1],box[2],box[3]};
        Object[] values=program.snapshot;if(values==null){values=program.entries.values().toArray();program.snapshot=values;}
        long[] resources={vertexBuffer,bufferVersions.getOrDefault(vertexBuffer,0L),array.indices,bufferVersions.getOrDefault(array.indices,0L),offset,baseVertex};
        Object[] constants=constantAttributes.entrySet().stream().map(entry->new Object[]{entry.getKey(),entry.getValue()}).toArray();
        NativeGameWindow.call("draw",drawTarget,new Object[]{currentProgram,vertexBytes,allIndices,type,count,stride,attributes,state,area,new float[]{offsetFactor,offsetUnits},values,textures,resources,samplerOverrides,constants});
    }
    private static UnsupportedOperationException unsupported(String operation){return new UnsupportedOperationException("Unimplemented native operation (GL fallback forbidden): "+operation);}
}
