package github.com.gengyoubo.vulkanforge.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import github.com.gengyoubo.vulkanforge.config.VulkanForgeConfig.OpenGLVersion;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.resources.*;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL46C.*;

/** Real shader uniform records: non-adjacent reuse, changed values, and submitted-batch boundaries. */
public final class Gl46EntityRecordSmoke {
    public static void main(String[] args)throws Exception{
        Path out=Path.of("build/reports/gl46-records");Files.createDirectories(out);System.setProperty("vulkanforge.offscreenReportDir",out.toAbsolutePath().toString());
        require(!Gl46CpuProfiler.sample(0)&&Gl46CpuProfiler.stamp(false)==0&&Gl46CpuProfiler.elapsed(0)==0,"Disabled CPU profiler sampled a scope");
        RenderSystem.initRenderThread();require(glfwInit(),"GLFW init");glfwDefaultWindowHints();glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,6);glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        long window=glfwCreateWindow(32,32,"Hidden GL46 records",0,0);require(window!=0,"GL46 context");
        try{
            glfwMakeContextCurrent(window);GL.createCapabilities();Gl46Context.select(OpenGLVersion.GL_4_6);
            PathPackResources pack=new PathPackResources("Classpath",Path.of("."),true);
            ResourceProvider resources=location->{var url=Gl46EntityRecordSmoke.class.getResource("/assets/"+location.getNamespace()+"/"+location.getPath());if(url==null)return Optional.empty();return Optional.of(new Resource(pack,()->location.getPath().endsWith(".vsh")||location.getPath().endsWith(".fsh")?new ByteArrayInputStream(VanillaGlOffscreenBenchmark.expand(url,new HashSet<>(),false).getBytes(java.nio.charset.StandardCharsets.UTF_8)):url.openStream()));};
            try(ShaderInstance shader=new ShaderInstance(resources,"rendertype_entity_solid",DefaultVertexFormat.NEW_ENTITY);Gl46Entities pass=new Gl46Entities()){
                RenderSystem.setShaderLights(new Vector3f(0,0,1),new Vector3f(0,0,1));RenderSystem.setShaderColor(1,1,1,1);
                int opaque=pass.record(shader,0),shadow=pass.record(shader,4);
                require(pass.record(shader,0)==opaque&&pass.record(shader,4)==shadow,"Non-adjacent records were uploaded twice");
                RenderSystem.setShaderColor(.5f,1,1,1);int changed=pass.record(shader,0);require(changed!=opaque,"Changed uniform reused a stale record");
                RenderSystem.setShaderColor(1,1,1,1);require(pass.record(shader,4)==shadow,"Another variant's unchanged uniform was lost");
                pass.benchmark(true,true);require(pass.record(shader,4)!=shadow,"Record reused across a batch reset");
                pass.benchmark(false,true);int a=pass.record(shader,0);pass.record(shader,4);require(pass.record(shader,0)!=a,"Legacy adjacent-only mode did not stay independent");
                require(pass.diagnostics().get("entityRecordReuses")==3,"Record reuse accounting");
                require(glGetError()==GL_NO_ERROR,"GL error");
            }
            Files.writeString(out.resolve("result.txt"),"STATUS=OK\nNON_ADJACENT_REUSE=PASS\nUNIFORM_CHANGE=PASS\nBATCH_RESET=PASS\nLEGACY_CONTROL=PASS\nCPU_PROFILER_DISABLED=PASS\nGL_ERRORS=0\n");
            System.out.println("GL46_ENTITY_RECORD_SMOKE=OK");
        }finally{glfwDestroyWindow(window);glfwTerminate();}
    }
    private static void require(boolean value,String reason){if(!value)throw new AssertionError(reason);}
}
