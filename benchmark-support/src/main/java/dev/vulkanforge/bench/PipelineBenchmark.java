package dev.vulkanforge.bench;

import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL43C.*;

/** Same two-slot final-target contract around the real vanilla/custom terrain implementations. */
public final class PipelineBenchmark implements AutoCloseable {
    public record Target(int framebuffer,int color,int depth,int width,int height) { }
    public interface Renderer {
        void terrain(Target target,int slot);
        default void beginPost(){ }
        default boolean post(Object[] packet){return false;}
        default void endPost(){ }
        default void endFrame(){ }
        default void audit(Path out) throws Exception { }
        default long[] counters(){return new long[6];}
    }
    public static final String VERTEX_GL="#version 430\nout vec2 uv;void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);uv=p;gl_Position=vec4(p*2.0-1.0,0,1);}";
    public static final String FRAGMENT_GL="#version 430\nin vec2 uv;out vec4 color;uniform sampler2D sourceColor;void main(){vec4 c=texture(sourceColor,uv);color=vec4(c.rgb*.875+vec3(.03125),1);}";
    public static final String VERTEX_VK="#version 450\nlayout(location=0)out vec2 uv;void main(){vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);uv=p;gl_Position=vec4(p*2.0-1.0,0,1);}";
    public static final String FRAGMENT_VK="#version 450\nlayout(location=0)in vec2 uv;layout(location=0)out vec4 color;layout(set=0,binding=0)uniform sampler2D sourceColor;void main(){vec4 c=texture(sourceColor,uv);color=vec4(c.rgb*.875+vec3(.03125),1);}";
    private final Target[][] targets=new Target[2][2];private final long[] fences=new long[2];
    private final int[][] queries=new int[2][2];private final Renderer renderer;
    private final IntConsumer destroy;private final int width,height,program,vao,passes;
    private long sequence;private final long[] actualPosts=new long[2];
    public PipelineBenchmark(int width,int height,Renderer renderer,Function<int[],int[]> allocate,IntConsumer destroy) {
        this.width=width;this.height=height;this.renderer=renderer;this.destroy=destroy;
        passes=Integer.getInteger("bench.postPasses",3);if(passes!=0&&passes!=3)throw new IllegalArgumentException("Post count must be 0 or 3");
        for(int s=0;s<2;s++)for(int t=0;t<2;t++) {
            int fbo=glGenFramebuffers();int[] textures=allocate==null?new int[]{texture(false),texture(true)}:allocate.apply(new int[]{fbo,width,height});
            glBindFramebuffer(GL_FRAMEBUFFER,fbo);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,textures[0],0);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,textures[1],0);
            if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new AssertionError("Pipeline target incomplete");targets[s][t]=new Target(fbo,textures[0],textures[1],width,height);
        }
        for(int[] q:queries)glGenQueries(q);vao=glGenVertexArrays();program=program(VERTEX_GL,FRAGMENT_GL);
    }
    private void frame(int slot,Path capture) throws Exception {
        Target scene=targets[slot][0];glBindFramebuffer(GL_FRAMEBUFFER,scene.framebuffer());glViewport(0,0,width,height);
        renderer.terrain(scene,slot);
        if(capture!=null)capture(capture,"scene",scene);
        actualPosts[slot]=0;
        if(passes!=0){renderer.beginPost();try{
            for(int p=0;p<passes;p++) {
                Target from=targets[slot][p%2],to=targets[slot][(p+1)%2];
                Object[] packet={"benchmark-color-transform",VERTEX_VK,FRAGMENT_VK,new int[]{from.color(),width,height,0,0},new float[256],new int[]{to.framebuffer(),width,height},new int[]{1,0,1,0,GL_FUNC_ADD},new float[]{0,0,0,0}};
                if(!renderer.post(packet))glPost(from,to);actualPosts[slot]++;
            }
        }finally{renderer.endPost();}}
        renderer.endFrame();Target result=targets[slot][passes%2];glBindFramebuffer(GL_FRAMEBUFFER,result.framebuffer());
        if(capture!=null)capture(capture,"final",result);
    }
    public void run(Path out,String api,int warmup,double seconds) throws Exception {
        Files.createDirectories(out);for(int i=0;i<warmup;i++){int s=(int)(sequence++%2);waitSlot(s);frame(s,null);fence(s);}drain();
        for(int s=0;s<2;s++){frame(s,s==0?out:null);fence(s);}drain();renderer.audit(out);
        long[] counters0=renderer.counters();long[] cpu=new long[262144];long frames=0,completed=0;double gpu=0;double[] times=new double[2];boolean[] pending=new boolean[2];
        long beginEpoch=System.currentTimeMillis(),begin=System.nanoTime();
        do {
            long t=System.nanoTime();int s=(int)(sequence++%2);waitSlot(s);
            if(pending[s]){gpu+=gpu(s);completed++;pending[s]=false;}
            glQueryCounter(queries[s][0],GL_TIMESTAMP);frame(s,null);glQueryCounter(queries[s][1],GL_TIMESTAMP);fence(s);pending[s]=true;
            if(frames>=cpu.length)cpu=Arrays.copyOf(cpu,cpu.length*2);cpu[(int)frames++]=System.nanoTime()-t;
        }while(frames<128||System.nanoTime()-begin<seconds*1e9);
        long drainStart=System.nanoTime();drain();for(int s=0;s<2;s++)if(pending[s]){gpu+=gpu(s);completed++;}
        long end=System.nanoTime(),endEpoch=System.currentTimeMillis();if(completed!=frames)throw new AssertionError("Completed frame mismatch");
        long[] sorted=Arrays.copyOf(cpu,(int)frames);Arrays.sort(sorted);StringBuilder raw=new StringBuilder("frame,cpu_ms\n");for(int i=0;i<frames;i++)raw.append(i).append(',').append(cpu[i]/1e6).append('\n');Files.writeString(out.resolve("cpu-frames.csv"),raw);
        long[] counters=renderer.counters();StringBuilder audit=new StringBuilder("POST_PASSES_PER_FRAME="+passes+"\nSLOTS=2\nCOLOR=RGBA8\nDEPTH=D32F\nPRESENT_CALLS=0\n");
        for(int i=0;i<counters.length;i++)audit.append("COUNTER_").append(i).append("_PER_FRAME=").append((double)(counters[i]-counters0[i])/frames).append('\n');
        if(actualPosts[0]!=passes||actualPosts[1]!=passes)throw new AssertionError("Post pass workload changed");Files.writeString(out.resolve("pipeline-audit.txt"),audit);
        String result=String.format(Locale.ROOT,"PIPELINE_RESULT API=%s frames=%d seconds=%.6f FPS=%.3f GPU_MS=%.6f CPU_P50_MS=%.6f CPU_P99_MS=%.6f CPU_MAX_MS=%.6f DRAIN_MS=%.6f POST_PASSES=%d START_MS=%d END_MS=%d",api,frames,(end-begin)/1e9,frames/((end-begin)/1e9),gpu/frames,sorted[(sorted.length-1)/2]/1e6,sorted[(sorted.length-1)*99/100]/1e6,sorted[sorted.length-1]/1e6,(end-drainStart)/1e6,passes,beginEpoch,endEpoch);
        Files.writeString(out.resolve("pipeline-result.txt"),result+"\n");System.out.println(result);
        frame(0,null);fence(0);drain();renderer.audit(out);capture(out,"after-final",targets[0][passes%2]);
    }
    private double gpu(int s){return (glGetQueryObjectui64(queries[s][1],GL_QUERY_RESULT)-glGetQueryObjectui64(queries[s][0],GL_QUERY_RESULT))/1e6;}
    private void fence(int s){fences[s]=glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE,0);glFlush();}
    private void waitSlot(int s){if(fences[s]==0)return;int result;do{result=glClientWaitSync(fences[s],GL_SYNC_FLUSH_COMMANDS_BIT,1_000_000_000L);if(result==GL_WAIT_FAILED)throw new AssertionError("Pipeline fence failed");}while(result==GL_TIMEOUT_EXPIRED);glDeleteSync(fences[s]);fences[s]=0;}
    private void drain(){waitSlot(0);waitSlot(1);}
    private void capture(Path out,String name,Target target) throws Exception {
        glBindFramebuffer(GL_READ_FRAMEBUFFER,target.framebuffer());ByteBuffer rgba=MemoryUtil.memAlloc(width*height*4);FloatBuffer z=MemoryUtil.memAllocFloat(width*height);
        try{glReadPixels(0,0,width,height,GL_RGBA,GL_UNSIGNED_BYTE,rgba);glReadPixels(0,0,width,height,GL_DEPTH_COMPONENT,GL_FLOAT,z);byte[] bytes=new byte[rgba.remaining()];rgba.get(bytes);Files.write(out.resolve(name+".rgba"),bytes);ByteBuffer depth=ByteBuffer.allocate(width*height*4).order(ByteOrder.LITTLE_ENDIAN);while(z.hasRemaining()){float v=z.get();if(!Float.isFinite(v))throw new AssertionError("Nonfinite depth");depth.putFloat(v);}Files.write(out.resolve(name+"-depth.f32"),depth.array());}finally{MemoryUtil.memFree(rgba);MemoryUtil.memFree(z);}
    }
    private void glPost(Target from,Target to){
        int previousProgram=glGetInteger(GL_CURRENT_PROGRAM),previousVao=glGetInteger(GL_VERTEX_ARRAY_BINDING),active=glGetInteger(GL_ACTIVE_TEXTURE);
        boolean depthTest=glIsEnabled(GL_DEPTH_TEST),depthMask=glGetBoolean(GL_DEPTH_WRITEMASK),blend=glIsEnabled(GL_BLEND),cull=glIsEnabled(GL_CULL_FACE),scissor=glIsEnabled(GL_SCISSOR_TEST);
        glActiveTexture(GL_TEXTURE0);int texture=glGetInteger(GL_TEXTURE_BINDING_2D),sampler=glGetIntegeri(GL_SAMPLER_BINDING,0);
        try{glBindFramebuffer(GL_FRAMEBUFFER,to.framebuffer());glDisable(GL_SCISSOR_TEST);glColorMask(true,true,true,true);glDepthMask(true);glClearBufferfv(GL_COLOR,0,new float[]{0,0,0,0});glClearBufferfv(GL_DEPTH,0,new float[]{1});glViewport(0,0,width,height);glDisable(GL_DEPTH_TEST);glDepthMask(false);glDisable(GL_BLEND);glDisable(GL_CULL_FACE);glUseProgram(program);glBindTexture(GL_TEXTURE_2D,from.color());glBindSampler(0,0);glBindVertexArray(vao);glDrawArrays(GL_TRIANGLES,0,3);}
        finally{glUseProgram(previousProgram);glBindVertexArray(previousVao);glBindTexture(GL_TEXTURE_2D,texture);glBindSampler(0,sampler);glActiveTexture(active);glDepthMask(depthMask);enable(GL_DEPTH_TEST,depthTest);enable(GL_BLEND,blend);enable(GL_CULL_FACE,cull);enable(GL_SCISSOR_TEST,scissor);}
    }
    private static void enable(int state,boolean enabled){if(enabled)glEnable(state);else glDisable(state);}
    private int texture(boolean depth){int id=glGenTextures();glBindTexture(GL_TEXTURE_2D,id);glTexStorage2D(GL_TEXTURE_2D,1,depth?GL_DEPTH_COMPONENT32F:GL_RGBA8,width,height);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);return id;}
    private static int program(String vert,String frag){int p=glCreateProgram();for(int type:new int[]{GL_VERTEX_SHADER,GL_FRAGMENT_SHADER}){int s=glCreateShader(type);glShaderSource(s,type==GL_VERTEX_SHADER?vert:frag);glCompileShader(s);if(glGetShaderi(s,GL_COMPILE_STATUS)==0)throw new IllegalStateException(glGetShaderInfoLog(s));glAttachShader(p,s);glDeleteShader(s);}glLinkProgram(p);if(glGetProgrami(p,GL_LINK_STATUS)==0)throw new IllegalStateException(glGetProgramInfoLog(p));return p;}
    public void close(){drain();for(var row:targets)for(var t:row){if(destroy!=null)destroy.accept(t.framebuffer());else{glDeleteTextures(t.color());glDeleteTextures(t.depth());}glDeleteFramebuffers(t.framebuffer());}for(int[] q:queries)glDeleteQueries(q);glDeleteProgram(program);glDeleteVertexArrays(vao);}
}
