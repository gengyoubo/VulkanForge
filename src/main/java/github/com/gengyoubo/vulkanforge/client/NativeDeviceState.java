package github.com.gengyoubo.vulkanforge.client;

import java.nio.*;
import java.util.*;
import org.lwjgl.system.MemoryUtil;

/** CPU resource/state adapter for Blaze3D. It never loads an OpenGL entry point. Unknown operations fail closed. */
public final class NativeDeviceState {
    private static final boolean DIAGNOSTICS=Boolean.getBoolean("vulkanforge.nativeDiagnostics"),PROFILE=Boolean.getBoolean("vulkanforge.nativeProfile");
    private static long adapterCalls,adapterNs,drawAdapterNs,bufferGeneration,terrainSubmissions,unchangedTerrainSubmissions,unchangedTerrainBytes,indexSliceBytes,embeddiumBatches;
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
    private static final class Page {int width,height,internal;byte[] rgba;long version,uploaded;int flags;boolean target;final Map<Integer,Integer> parameters=new HashMap<>();}
    private static final Map<Integer,Page> pages=new HashMap<>();
    private static final class Framebuffer {int color,depth;int registeredColor,registeredDepth,width,height;}
    private static final Map<Integer,Framebuffer> framebuffers=new HashMap<>();
    private static final class Shader {int type;String source;}
    private static final Map<Integer,Shader> shaders=new HashMap<>();
    private static final class Program {final List<Integer> shaders=new ArrayList<>();final Map<String,Integer> attributes=new LinkedHashMap<>();final Map<String,float[]> values=new LinkedHashMap<>();final Map<String,Object[]> entries=new LinkedHashMap<>();Object[] snapshot;String[] samplers={};boolean linked,terrain;}
    private static final Map<Integer,Program> programs=new HashMap<>();
    private record Location(int program,String name) { }
    private static final Map<Integer,Location> locations=new HashMap<>();
    private static final Map<String,Integer> locationIds=new HashMap<>();
    private static int src=1,dst=0,srcAlpha=1,dstAlpha=0,equation=32774,equationAlpha=32774,depthFunc=513,cullFace=1029,frontFace=2305,colorMask=15;
    private static boolean depthMask=true;
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
            case "createCapabilities":return null; // Window discards the GL result; the Vulkan device is already initialized.
            case "glGetError":return 0;
            case "glGetString":return "VulkanForge native Vulkan (no OpenGL context)";
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
            case "glTexParameteri":case "glTexParameterf": {
                Page page=page();int name=number(a,1),value=number(a,2);page.parameters.put(name,value);
                if(name==10240)page.flags=(page.flags&~1)|(value==9729?1:0);if(name==10242||name==10243)page.flags=(page.flags&~2)|(value==33071?2:0);
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
            case "glBufferData":{int target=buffer(number(a,0));if(a[1] instanceof ByteBuffer bytes){byte[] data=new byte[bytes.remaining()];bytes.duplicate().get(data);buffers.put(target,data);}else buffers.put(target,new byte[Math.toIntExact(((Number)a[1]).longValue())]);bufferVersions.put(target,++bufferGeneration);return null;}
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
            case "glBindFragDataLocation":if(number(a,1)!=0)throw unsupported("multiple fragment outputs");return null;
            case "glGetAttribLocation":return programs.get(number(a,0)).attributes.getOrDefault(a[1].toString(),-1);
            case "glLinkProgram":{int value=number(a,0);Program program=programs.get(value);String vertex=null,fragment=null;for(int shaderId:program.shaders){Shader shader=shaders.get(shaderId);if(shader.type==35633)vertex=shader.source;else if(shader.type==35632)fragment=shader.source;}
                if(vertex==null||fragment==null)throw new IllegalStateException("Native program lacks vertex or fragment source");
                program.terrain=vertex.contains("ChunkOffset")||vertex.contains("u_RegionOffset")&&vertex.contains("a_PosId");
                if(program.attributes.isEmpty()){var inputs=java.util.regex.Pattern.compile("(?m)^\\s*in\\s+\\w+\\s+(\\w+)\\s*;").matcher(vertex);while(inputs.find())program.attributes.put(inputs.group(1),program.attributes.size());}
                Object[] attributes=program.attributes.entrySet().stream().map(entry->new Object[]{entry.getKey(),entry.getValue()}).toArray();
                program.samplers=(String[])NativeGameWindow.call("program",value,vertex,fragment,attributes);program.linked=true;return null;}
            case "glGetProgrami":return number(a,1)==35714&&programs.get(number(a,0)).linked?1:0;
            case "glDeleteProgram":programs.remove(number(a,0));NativeGameWindow.call("deleteProgram",number(a,0));return null;
            case "glUseProgram":currentProgram=number(a,0);return null;
            case "glGetUniformLocation":{int owner=number(a,0);String name=a[1].toString(),key=owner+":"+name;return locationIds.computeIfAbsent(key,unused->{int value=id();locations.put(value,new Location(owner,name));return value;});}
            case "glUniform1i":case "glUniform1iv":case "glUniform2iv":case "glUniform3iv":case "glUniform4iv":case "glUniform1fv":case "glUniform2fv":case "glUniform3fv":case "glUniform4fv":case "glUniformMatrix2fv":case "glUniformMatrix3fv":case "glUniformMatrix4fv":uniform(a);return null;
            case "glUniform1f":case "glUniform2f":case "glUniform3f":case "glUniform4f":{float[] data=new float[a.length-1];for(int i=0;i<data.length;i++)data[i]=((Number)a[i+1]).floatValue();uniform(new Object[]{a[0],data});return null;}
            case "glBindFramebuffer":{int target=number(a,0),value=number(a,1);if(target==36160||target==36009)drawTarget=value;if(target==36160||target==36008)readTarget=value;return null;}
            case "glFramebufferTexture2D":{Framebuffer framebuffer=framebuffers.computeIfAbsent(number(a,0)==36008?readTarget:drawTarget,key->new Framebuffer());int texture=number(a,3);if(number(a,1)==36064)framebuffer.color=texture;else if(number(a,1)==36096)framebuffer.depth=texture;else throw unsupported(operation+" attachment "+a[1]);if(texture>0)pages.get(texture).target=true;return null;}
            case "glCheckFramebufferStatus":prepareTarget(number(a,0)==36008?readTarget:drawTarget);return 36053;
            case "glBlitFramebuffer":prepareTarget(readTarget);prepareTarget(drawTarget);NativeGameWindow.call("blit",readTarget,drawTarget,Arrays.stream(a).limit(8).mapToInt(value->((Number)value).intValue()).toArray(),number(a,8),number(a,9));return null;
            case "glEnable":enabled.put(number(a,0),true);return null;
            case "glDisable":enabled.put(number(a,0),false);return null;
            case "glIsEnabled":return on(number(a,0));
            case "glViewport":for(int i=0;i<4;i++)viewport[i]=number(a,i);return null;
            case "glScissor":for(int i=0;i<4;i++)scissor[i]=number(a,i);return null;
            case "glDepthMask":depthMask=flag(a,0);return null;
            case "glDepthFunc":depthFunc=number(a,0);return null;
            case "glCullFace":cullFace=number(a,0);return null;
            case "glFrontFace":frontFace=number(a,0);return null;
            case "glBlendFunc":src=srcAlpha=number(a,0);dst=dstAlpha=number(a,1);return null;
            case "glBlendFuncSeparate":src=number(a,0);dst=number(a,1);srcAlpha=number(a,2);dstAlpha=number(a,3);return null;
            case "glBlendEquation":equation=equationAlpha=number(a,0);return null;
            case "glBlendEquationSeparate":equation=number(a,0);equationAlpha=number(a,1);return null;
            case "glColorMask":colorMask=0;for(int i=0;i<4;i++)if(flag(a,i))colorMask|=1<<i;return null;
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
    private static int integer(int value){return switch(value){case 3379->8192;case 32873->boundTextures[activeTexture];case 34016->33984+activeTexture;case 34964->arrayBuffer;case 34965->array().indices;case 36006->drawTarget;case 36010->readTarget;case 35725->currentProgram;case 32969->src;case 32968->dst;case 32971->srcAlpha;case 32970->dstAlpha;case 32777->equation;case 34877->equationAlpha;case 2932->depthFunc;case 2885->cullFace;case 2886->frontFace;default->integers.getOrDefault(value,0);};}
    private static void image(Object[] a) {
        int width=number(a,3),height=number(a,4);if(number(a,0)==32868){proxyWidth=width<=8192&&height<=8192?width:0;return;}
        if(number(a,1)!=0)return;Page page=page();page.width=width;page.height=height;page.internal=number(a,2);page.rgba=new byte[Math.multiplyExact(Math.multiplyExact(width,height),4)];page.version++;
        if(a[8]!=null)upload(page,0,0,width,height,number(a,6),number(a,7),a[8]);
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
        for(int r=0;r<height;r++){ByteBuffer input=MemoryUtil.memByteBuffer(address+(long)(r+skipRows)*stride+(long)skipPixels*channels,width*channels);for(int c=0;c<width;c++){int at=((y+r)*page.width+x+c)*4;byte red=0,green=0,blue=0,alpha=-1;if(channels>=3){red=input.get();green=input.get();blue=input.get();if(format==32993||format==32992){byte swap=red;red=blue;blue=swap;}if(channels==4)alpha=input.get();}else if(format==6406)alpha=input.get();else{red=input.get();if(format==6409||format==6410)green=blue=red;if(channels==2)alpha=input.get();}page.rgba[at]=red;page.rgba[at+1]=green;page.rgba[at+2]=blue;page.rgba[at+3]=alpha;}}
        page.version++;
    }
    private static void uniform(Object[] a) {
        Location location=locations.get(number(a,0));if(location==null)return;Object input=a[a.length-1];float[] values;
        if(input instanceof float[] data)values=data.clone();else if(input instanceof int[] data){values=new float[data.length];for(int i=0;i<data.length;i++)values[i]=data[i];}else if(input instanceof FloatBuffer data){values=new float[data.remaining()];data.duplicate().get(values);}else if(input instanceof IntBuffer data){values=new float[data.remaining()];for(int i=0;i<values.length;i++)values[i]=data.get(data.position()+i);}else values=new float[]{((Number)input).floatValue()};
        Program program=programs.get(location.program());program.values.put(location.name(),values);
        program.entries.put(location.name(),new Object[]{location.name(),values});program.snapshot=null;
    }
    private static void prepareTarget(int id) {
        if(id==0){int[] size=NativeGameWindow.extent();Framebuffer window=framebuffers.computeIfAbsent(0,key->new Framebuffer());if(window.color==0){window.color=id();window.depth=id();Page color=new Page(),depth=new Page();color.target=depth.target=true;pages.put(window.color,color);pages.put(window.depth,depth);}pages.get(window.color).width=size[0];pages.get(window.color).height=size[1];}
        Framebuffer fb=framebuffers.get(id);if(fb==null||fb.color==0)throw new IllegalStateException("Native framebuffer has no color attachment: "+id);Page color=pages.get(fb.color);
        if(fb.registeredColor!=fb.color||fb.registeredDepth!=fb.depth||fb.width!=color.width||fb.height!=color.height){NativeGameWindow.call("target",id,fb.color,fb.depth,color.width,color.height);fb.registeredColor=fb.color;fb.registeredDepth=fb.depth;fb.width=color.width;fb.height=color.height;}
    }
    private static int[] clip(int target) {Framebuffer framebuffer=framebuffers.get(target);int width=framebuffer.width,height=framebuffer.height;int x=on(3089)?Math.max(0,scissor[0]):0,y=on(3089)?Math.max(0,scissor[1]):0;int right=on(3089)?Math.min(width,scissor[0]+scissor[2]):width,top=on(3089)?Math.min(height,scissor[1]+scissor[3]):height;return new int[]{x,y,Math.max(0,right-x),Math.max(0,top-y)};}
    private static void ensureTexture(int id) {if(id==0)return;Page page=pages.get(id);if(page==null)throw new IllegalStateException("Unknown native texture "+id);if(page.target)return;if(page.uploaded!=page.version){NativeGameWindow.call("texture",id,page.width,page.height,page.flags,page.rgba);page.uploaded=page.version;}}
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
        int[] textures=new int[program.samplers.length];for(int i=0;i<textures.length;i++){float[] value=program.values.get(program.samplers[i]);int unit=value==null?0:(int)value[0];textures[i]=boundTextures[unit];ensureTexture(textures[i]);}
        int[] state={on(3042)?1:0,src,dst,srcAlpha,dstAlpha,equation,equationAlpha,on(2929)&&framebuffers.get(drawTarget).depth>0?1:0,depthMask?1:0,depthFunc,on(2884)?cullFace:0,frontFace,colorMask,6914,on(32823)?1:0,mode,0};
        int[] box=clip(drawTarget),area={viewport[0],viewport[1],viewport[2],viewport[3],box[0],box[1],box[2],box[3]};
        Object[] values=program.snapshot;if(values==null){values=program.entries.values().toArray();program.snapshot=values;}
        long[] resources={vertexBuffer,bufferVersions.getOrDefault(vertexBuffer,0L),array.indices,bufferVersions.getOrDefault(array.indices,0L),offset,baseVertex};
        NativeGameWindow.call("draw",drawTarget,new Object[]{currentProgram,vertexBytes,allIndices,type,count,stride,attributes,state,area,new float[]{offsetFactor,offsetUnits},values,textures,resources});
    }
    private static UnsupportedOperationException unsupported(String operation){return new UnsupportedOperationException("Unimplemented native operation (GL fallback forbidden): "+operation);}
}
