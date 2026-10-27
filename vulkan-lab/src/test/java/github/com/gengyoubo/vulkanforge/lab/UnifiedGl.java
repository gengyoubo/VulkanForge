package github.com.gengyoubo.vulkanforge.lab;

import java.nio.*;
import java.util.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL43C.*;
import static org.lwjgl.opengl.ARBPipelineStatisticsQuery.*;
import static github.com.gengyoubo.vulkanforge.lab.UnifiedWorkload.*;

/** Same GL 4.3-compatible kernel on all GL contexts; also supplies the identical producer/post/fallback stages for Vulkan. */
final class UnifiedGl implements AutoCloseable {
    record Metrics(double timeline,double entity,double compute,double post,double fallback,long vertices,long invocations,long fallbackVertices) { }
    static final class Queries implements AutoCloseable {
        final int[] time=new int[12],stats=new int[4];final boolean[] written=new boolean[12];
        Queries(){glGenQueries(time);glGenQueries(stats);}
        void reset(){Arrays.fill(written,false);}
        void stamp(int at){glQueryCounter(time[at],GL_TIMESTAMP);written[at]=true;}
        double interval(int pair){int at=pair*2;return written[at]?(glGetQueryObjectui64(time[at+1],GL_QUERY_RESULT)-glGetQueryObjectui64(time[at],GL_QUERY_RESULT))/1e6:0;}
        void beginStats(boolean fallback){glBeginQuery(GL_VERTICES_SUBMITTED_ARB,stats[fallback?2:0]);glBeginQuery(GL_VERTEX_SHADER_INVOCATIONS_ARB,stats[fallback?3:1]);}
        void endStats(){glEndQuery(GL_VERTEX_SHADER_INVOCATIONS_ARB);glEndQuery(GL_VERTICES_SUBMITTED_ARB);}
        long vertices(boolean fallback){return glGetQueryObjectui64(stats[fallback?2:0],GL_QUERY_RESULT);}
        long invocations(){return glGetQueryObjectui64(stats[1],GL_QUERY_RESULT);}
        public void close(){glDeleteQueries(time);glDeleteQueries(stats);}
    }
    static final class Target implements AutoCloseable {
        final int color,depth,framebuffer,transparentDepth,transparentFramebuffer,finalColor,finalDepth,finalFramebuffer;
        final boolean ownsMain;
        Target(int width,int height,int level,int importedColor,int importedDepth,boolean glCore) {
            ownsMain=importedColor==0;color=ownsMain?texture(width,height,false):importedColor;depth=ownsMain?texture(width,height,true):importedDepth;
            framebuffer=fbo(color,depth);
            transparentDepth=level==FULL&&glCore?texture(width,height,true):0;
            transparentFramebuffer=transparentDepth!=0?fbo(color,transparentDepth):0;
            finalColor=level==FULL?texture(width,height,false):0;finalDepth=level==FULL?texture(width,height,true):0;
            finalFramebuffer=level==FULL?fbo(finalColor,finalDepth):0;
        }
        int output(int level){return level==FULL?finalFramebuffer:framebuffer;}
        public void close(){glDeleteFramebuffers(framebuffer);if(transparentFramebuffer!=0)glDeleteFramebuffers(transparentFramebuffer);if(finalFramebuffer!=0)glDeleteFramebuffers(finalFramebuffer);if(ownsMain){glDeleteTextures(color);glDeleteTextures(depth);}for(int t:new int[]{transparentDepth,finalColor,finalDepth})if(t!=0)glDeleteTextures(t);}
    }
    final UnifiedWorkload scene;final int level,width,height;final boolean core;
    final Target[] targets=new Target[SLOTS];final Queries[] queries=new Queries[SLOTS];
    final UnifiedSubmissions[] submissions=new UnifiedSubmissions[SLOTS];
    final int[] depthCopies=new int[SLOTS],postPasses=new int[SLOTS];
    private int activeSlot;private boolean compatibility;
    final long[] fences=new long[SLOTS];final boolean[] pending=new boolean[SLOTS];
    private final int drawProgram,computeProgram,postProgram,vao,vertices,indices,instanceIDs,records,parameters;
    private final int[] commands=new int[SLOTS];
    private final boolean drawCompatibility;
    UnifiedGl(UnifiedWorkload scene,int level,int width,int height,boolean core,int[] colors,int[] depths) throws Exception {
        this(scene,level,width,height,core,colors,depths,true);
    }
    UnifiedGl(UnifiedWorkload scene,int level,int width,int height,boolean core,int[] colors,int[] depths,boolean drawCompatibility) throws Exception {
        this.scene=scene;this.level=level;this.width=width;this.height=height;this.core=core;
        this.drawCompatibility=drawCompatibility;
        if(!GL.getCapabilities().GL_ARB_pipeline_statistics_query)throw new IllegalStateException("Pipeline statistics required for verified workload");
        drawProgram=program("unified_draw.vert","unified_draw.frag");computeProgram=core&&level!=BASELINE?program("unified_cull.comp"):0;postProgram=level==FULL?program("unified_post.vert","unified_post.frag"):0;
        vertices=buffer(GL_ARRAY_BUFFER,scene.mesh);indices=buffer(GL_ARRAY_BUFFER,scene.indices);instanceIDs=buffer(GL_ARRAY_BUFFER,scene.ids);records=buffer(GL_SHADER_STORAGE_BUFFER,scene.records);parameters=buffer(GL_SHADER_STORAGE_BUFFER,bytes(16).putInt(scene.core(level)).array());
        vao=glGenVertexArrays();glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER,vertices);glVertexAttribPointer(0,3,GL_FLOAT,false,24,0);glVertexAttribPointer(1,3,GL_FLOAT,false,24,12);glEnableVertexAttribArray(0);glEnableVertexAttribArray(1);
        glBindBuffer(GL_ARRAY_BUFFER,instanceIDs);glVertexAttribIPointer(2,1,GL_UNSIGNED_INT,4,0);glEnableVertexAttribArray(2);glVertexAttribDivisor(2,1);glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,indices);
        for(int s=0;s<SLOTS;s++){submissions[s]=new UnifiedSubmissions(scene);queries[s]=new Queries();targets[s]=new Target(width,height,level,colors==null?0:colors[s],depths==null?0:depths[s],core);if(core)commands[s]=buffer(GL_DRAW_INDIRECT_BUFFER,scene.commands(level));}
        glBindVertexArray(0);check("GL init");
    }
    void clear(int slot) {
        activeSlot=slot;compatibility=false;submissions[slot].reset();
        depthCopies[slot]=postPasses[slot]=0;
        glBindFramebuffer(GL_FRAMEBUFFER,targets[slot].framebuffer);glViewport(0,0,width,height);glDisable(GL_SCISSOR_TEST);glDisable(GL_FRAMEBUFFER_SRGB);glDisable(GL_DITHER);glColorMask(true,true,true,true);glDepthMask(true);
        glClearBufferfv(GL_COLOR,0,new float[]{.03125f,.0625f,.09375f,1});glClearBufferfv(GL_DEPTH,0,new float[]{1});
    }
    void frame(int slot) {
        Queries q=queries[slot];q.reset();q.stamp(0);clear(slot);
        if(level!=BASELINE){q.stamp(6);glUseProgram(computeProgram);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,records);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,1,commands[slot]);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,2,parameters);glDispatchCompute((scene.core(level)+63)/64,1,1);glMemoryBarrier(GL_COMMAND_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT);q.stamp(7);}
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER,commands[slot]);q.beginStats(false);q.stamp(2);state(false);bindDraw();drawRange(0,scene.opaque,level!=BASELINE);q.stamp(3);
        if(level==FULL) {
            copyDepth(targets[slot].framebuffer,targets[slot].transparentFramebuffer);
            glBindFramebuffer(GL_FRAMEBUFFER,targets[slot].transparentFramebuffer);q.stamp(4);state(true);bindDraw();drawRange(scene.opaque,scene.transparent,true);q.stamp(5);
        }
        q.endStats();
        if(level==FULL)post(slot);
        q.stamp(1);submit(slot);
    }
    void post(int slot) {
        Target t=targets[slot];Queries q=queries[slot];
        copyDepth(t.framebuffer,t.finalFramebuffer);glBindFramebuffer(GL_FRAMEBUFFER,t.finalFramebuffer);glViewport(0,0,width,height);
        q.stamp(8);glDisable(GL_DEPTH_TEST);glDepthMask(false);glDisable(GL_BLEND);glDisable(GL_CULL_FACE);glUseProgram(postProgram);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,t.color);glBindSampler(0,0);glBindVertexArray(vao);glDrawArrays(GL_TRIANGLES,0,3);postPasses[slot]++;q.stamp(9);
        compatibility=true;q.beginStats(true);q.stamp(10);
        state(true);bindDraw();if(drawCompatibility)drawRange(scene.core(level),scene.fallback,false);
        q.stamp(11);q.endStats();
    }
    void submit(int slot){fences[slot]=glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE,0);glFlush();pending[slot]=true;}
    Metrics waitSlot(int slot) {
        if(!pending[slot])return null;int state;
        do{state=glClientWaitSync(fences[slot],GL_SYNC_FLUSH_COMMANDS_BIT,1_000_000_000L);if(state==GL_WAIT_FAILED)throw new IllegalStateException("GL frame fence failed");}while(state==GL_TIMEOUT_EXPIRED);
        glDeleteSync(fences[slot]);fences[slot]=0;pending[slot]=false;Queries q=queries[slot];
        submissions[slot].completed();
        if(depthCopies[slot]!=(level==FULL?(core?2:1):0)||postPasses[slot]!=(level==FULL?1:0))throw new AssertionError("GL frame operation graph changed");
        return new Metrics(q.interval(0),core?q.interval(1)+q.interval(2):0,core?q.interval(3):0,q.interval(4),q.interval(5),core?q.vertices(false):0,core?q.invocations():0,level==FULL?q.vertices(true):0);
    }
    ByteBuffer commands(int slot) {
        ByteBuffer data=MemoryUtil.memAlloc(scene.core(level)*COMMAND_BYTES);try{glBindBuffer(GL_DRAW_INDIRECT_BUFFER,commands[slot]);glGetBufferSubData(GL_DRAW_INDIRECT_BUFFER,0,data);return submissions[slot].actualCommands(data,scene.core(level));}finally{MemoryUtil.memFree(data);}
    }
    byte[] color(int slot) {glBindFramebuffer(GL_READ_FRAMEBUFFER,targets[slot].output(level));ByteBuffer data=MemoryUtil.memAlloc(width*height*4);try{glReadPixels(0,0,width,height,GL_RGBA,GL_UNSIGNED_BYTE,data);byte[] result=new byte[data.remaining()];data.get(result);return result;}finally{MemoryUtil.memFree(data);}}
    float[] depth(int slot) {glBindFramebuffer(GL_READ_FRAMEBUFFER,targets[slot].output(level));FloatBuffer data=MemoryUtil.memAllocFloat(width*height);try{glReadPixels(0,0,width,height,GL_DEPTH_COMPONENT,GL_FLOAT,data);float[] result=new float[data.remaining()];data.get(result);return result;}finally{MemoryUtil.memFree(data);}}
    private void drawRange(int start,int count,boolean indirect) {
        if(indirect){submissions[activeSlot].indirect(start,count);glMultiDrawElementsIndirect(GL_TRIANGLES,GL_UNSIGNED_INT,(long)start*COMMAND_BYTES,count,COMMAND_BYTES);}
        else for(int id=start;id<start+count;id++){submissions[activeSlot].direct(id,compatibility);org.lwjgl.opengl.GL42C.glDrawElementsInstancedBaseVertexBaseInstance(GL_TRIANGLES,INDEX_COUNT,GL_UNSIGNED_INT,0,1,0,id);}
    }
    private void bindDraw(){glUseProgram(drawProgram);glBindVertexArray(vao);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,records);}
    private static void state(boolean blend){glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LEQUAL);glDepthMask(!blend);glDisable(GL_CULL_FACE);if(blend){glEnable(GL_BLEND);glBlendFuncSeparate(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA,GL_ONE,GL_ONE_MINUS_SRC_ALPHA);}else glDisable(GL_BLEND);}
    private void copyDepth(int from,int to){glBindFramebuffer(GL_READ_FRAMEBUFFER,from);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,to);glBlitFramebuffer(0,0,width,height,0,0,width,height,GL_DEPTH_BUFFER_BIT,GL_NEAREST);depthCopies[activeSlot]++;}
    private static int texture(int width,int height,boolean depth){int id=glGenTextures();glBindTexture(GL_TEXTURE_2D,id);glTexStorage2D(GL_TEXTURE_2D,1,depth?GL_DEPTH_COMPONENT32F:GL_RGBA8,width,height);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);return id;}
    private static int fbo(int color,int depth){int id=glGenFramebuffers();glBindFramebuffer(GL_FRAMEBUFFER,id);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,color,0);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,depth,0);if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Unified FBO incomplete");return id;}
    private static int buffer(int target,byte[] data){int id=glGenBuffers();glBindBuffer(target,id);ByteBuffer bytes=MemoryUtil.memAlloc(data.length);try{bytes.put(data).flip();glBufferData(target,bytes,GL_STATIC_DRAW);}finally{MemoryUtil.memFree(bytes);}return id;}
    private static int program(String... files) throws Exception {
        int p=glCreateProgram();List<Integer> shaders=new ArrayList<>();
        try{for(String file:files){int type=file.endsWith(".vert")?GL_VERTEX_SHADER:file.endsWith(".frag")?GL_FRAGMENT_SHADER:GL_COMPUTE_SHADER;int s=glCreateShader(type);shaders.add(s);glShaderSource(s,shader(file,false));glCompileShader(s);if(glGetShaderi(s,GL_COMPILE_STATUS)==0)throw new IllegalStateException(file+": "+glGetShaderInfoLog(s));glAttachShader(p,s);}glLinkProgram(p);if(glGetProgrami(p,GL_LINK_STATUS)==0)throw new IllegalStateException(glGetProgramInfoLog(p));return p;}catch(Throwable e){glDeleteProgram(p);throw e;}finally{for(int s:shaders)glDeleteShader(s);}
    }
    static void check(String where){int e=glGetError();if(e!=GL_NO_ERROR)throw new IllegalStateException(where+" GL error 0x"+Integer.toHexString(e));}
    public void close(){for(int s=0;s<SLOTS;s++){waitSlot(s);targets[s].close();queries[s].close();if(commands[s]!=0)glDeleteBuffers(commands[s]);}for(int b:new int[]{vertices,indices,instanceIDs,records,parameters})glDeleteBuffers(b);glDeleteVertexArrays(vao);glDeleteProgram(drawProgram);if(computeProgram!=0)glDeleteProgram(computeProgram);if(postProgram!=0)glDeleteProgram(postProgram);}
}
