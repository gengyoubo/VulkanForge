package github.com.gengyoubo.vulkanforge.lab;

import dev.vulkanforge.bench.*;
import dev.vulkanforge.bridge.WorldProtocol;
import github.com.gengyoubo.vulkanforge.interop.GlInterop;
import java.nio.file.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;

/** Production GL-tail terrain, GL depth composites and GL posts, with private final targets. */
public final class GlTailPipelineBenchmark {
    public static void main(String[] args) throws Exception {
        org.lwjgl.system.Configuration.STACK_SIZE.set(4096);
        java.util.Locale.setDefault(java.util.Locale.ROOT);
        if(!glfwInit())throw new AssertionError("GLFW init");
        glfwDefaultWindowHints();glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,6);
        glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        long window=glfwCreateWindow(32,32,"Hidden GL tail pipeline",0,0);
        if(window==0)throw new AssertionError("GL context");
        try {
            glfwMakeContextCurrent(window);org.lwjgl.opengl.GL.createCapabilities();
            Path out=Path.of(System.getProperty("bench.output"));Files.createDirectories(out);
            int width=Integer.getInteger("bench.width",1920),height=Integer.getInteger("bench.height",1080);
            try(MainWindowBackend backend=new MainWindowBackend(out.resolve("report.txt").toString(),GlInterop.deviceUUID(),
                    GlInterop::importImage,org.lwjgl.opengl.GL11::glFinish,VulkanContext.requestedApiVersion(),"",true)) {
                var commands=backend.worldCommands(GlInterop::importTerrain);
                Workload.load(Path.of(System.getProperty("bench.scene")),c->commands.accept(WorldProtocol.pack(c)));
                int program=program(),vao=glGenVertexArrays();
                try(var pipeline=new PipelineBenchmark(width,height,new PipelineBenchmark.Renderer(){
                    public void terrain(PipelineBenchmark.Target target,int slot) {
                        glBindFramebuffer(GL_FRAMEBUFFER,target.framebuffer());glViewport(0,0,width,height);
                        glDisable(GL_SCISSOR_TEST);glDepthMask(true);glColorMask(true,true,true,true);
                        glClearColor(.5f,.7f,1,0);glClearDepth(1);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
                        int opaque=backend.renderWorld(width,height);
                        if(opaque==0)throw new AssertionError("GL-tail terrain unavailable");
                        long[] stats=backend.worldStats();
                        glBindFramebuffer(GL_FRAMEBUFFER,target.framebuffer());glViewport(0,0,width,height);
                        glUseProgram(program);glBindVertexArray(vao);glDisable(GL_CULL_FACE);
                        glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LEQUAL);glDepthMask(true);glEnable(GL_BLEND);glBlendEquation(GL_FUNC_ADD);
                        // Same alpha thresholds, depth and blend semantics as TerrainPresenter's two shaders.
                        glBlendFuncSeparate(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA,GL_ONE,GL_ZERO);
                        draw(program,opaque,(int)stats[9],.1f);
                        glBlendFuncSeparate(GL_ONE,GL_ONE_MINUS_SRC_ALPHA,GL_ONE,GL_ONE_MINUS_SRC_ALPHA);
                        draw(program,(int)stats[13],(int)stats[14],1f/255f);
                    }
                    public void endFrame(){backend.finishWorldFrame();}
                    public void audit(Path path)throws Exception{BenchmarkDrawAudit.write(backend,path.resolve("workload.txt"));}
                    public long[] counters(){return backend.interopStats();}
                    public double[] profile(){return backend.phaseProfile();}
                },null,null)) {
                    glDisable(GL_DITHER);glDisable(GL_FRAMEBUFFER_SRGB);
                    pipeline.run(out,System.getProperty("bench.label","GLVK_GL_TAIL"),Integer.getInteger("bench.warmup",1000),
                            Double.parseDouble(System.getProperty("bench.seconds","6")));
                } finally {glDeleteProgram(program);glDeleteVertexArrays(vao);}
                if(glGetError()!=GL_NO_ERROR)throw new AssertionError("GL tail pipeline GL error");
            }
        } finally {glfwDestroyWindow(window);glfwTerminate();}
    }
    private static void draw(int program,int color,int depth,float cutoff) {
        glUniform1f(glGetUniformLocation(program,"cutoff"),cutoff);
        glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,color);glBindSampler(0,0);
        glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,depth);glBindSampler(1,0);
        glDrawArrays(GL_TRIANGLES,0,3);
    }
    private static int program() {
        String[] sources={"#version 430\nout vec2 uv;void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);uv=vec2(p.x,1-p.y);gl_Position=vec4(p*2-1,0,1);}",
                "#version 430\nin vec2 uv;out vec4 color;uniform sampler2D sourceColor;uniform sampler2D sourceDepth;uniform float cutoff;void main(){color=texture(sourceColor,uv);if(color.a<cutoff)discard;gl_FragDepth=texture(sourceDepth,uv).r;}"};
        int program=glCreateProgram();
        for(int i=0;i<2;i++) {
            int shader=glCreateShader(i==0?GL_VERTEX_SHADER:GL_FRAGMENT_SHADER);glShaderSource(shader,sources[i]);glCompileShader(shader);
            if(glGetShaderi(shader,GL_COMPILE_STATUS)==0)throw new AssertionError(glGetShaderInfoLog(shader));
            glAttachShader(program,shader);glDeleteShader(shader);
        }
        glLinkProgram(program);if(glGetProgrami(program,GL_LINK_STATUS)==0)throw new AssertionError(glGetProgramInfoLog(program));
        glUseProgram(program);glUniform1i(glGetUniformLocation(program,"sourceColor"),0);glUniform1i(glGetUniformLocation(program,"sourceDepth"),1);
        return program;
    }
}
