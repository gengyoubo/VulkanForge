package github.com.gengyoubo.vulkanforge.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import java.io.ByteArrayInputStream;
import java.lang.management.ManagementFactory;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.resources.*;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;

/** Production capture with vanilla ShaderInstance in a hidden GL context; synthetic geometry, no game simulation. */
public final class EntityCaptureBenchmark {
    public static void main(String[] args) throws Exception {
        Path output=Path.of(System.getProperty("bench.output","build/reports/entity-capture"));Files.createDirectories(output);
        RenderSystem.initRenderThread();if(!glfwInit())throw new AssertionError("GLFW");
        glfwDefaultWindowHints();glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);long window=glfwCreateWindow(64,64,"Hidden capture benchmark",0,0);
        if(window==0)throw new AssertionError("Hidden GL context");
        try {
            glfwMakeContextCurrent(window);GL.createCapabilities();
            PathPackResources pack=new PathPackResources("Minecraft classpath resources",Path.of("."),true);
            ResourceProvider resources=location->{
                var url=EntityCaptureBenchmark.class.getResource("/assets/"+location.getNamespace()+"/"+location.getPath());
                if(url==null)return Optional.empty();
                return Optional.of(new Resource(pack,()->location.getPath().endsWith(".vsh")||location.getPath().endsWith(".fsh")
                    ?new ByteArrayInputStream(VanillaGlOffscreenBenchmark.expand(url,new HashSet<>(),false).getBytes(java.nio.charset.StandardCharsets.UTF_8)):url.openStream()));
            };
            try(ShaderInstance shader=new ShaderInstance(resources,"rendertype_entity_solid",DefaultVertexFormat.NEW_ENTITY)) {
                RenderSystem.setShader(()->shader);for(int unit=0;unit<3;unit++)RenderSystem.setShaderTexture(unit,0);
                RenderSystem.setShaderLights(new Vector3f(0,0,1),new Vector3f(0,0,1));RenderSystem.setShaderFogStart(100);RenderSystem.setShaderFogEnd(200);
                glDisable(GL_BLEND);glDisable(GL_POLYGON_OFFSET_FILL);glDisable(GL_SCISSOR_TEST);glDisable(GL_COLOR_LOGIC_OP);glDisable(GL_CULL_FACE);glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LEQUAL);glDepthMask(true);
                var capture=EntityDraws.class.getDeclaredMethod("capture",BufferBuilder.RenderedBuffer.class);capture.setAccessible(true);
                checkConversions();
                var allocation=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();if(!allocation.isThreadAllocatedMemoryEnabled())allocation.setThreadAllocatedMemoryEnabled(true);
                long thread=Thread.currentThread().getId();int quads=Integer.getInteger("bench.quads",96),warmup=Integer.getInteger("bench.warmup",10),samples=Integer.getInteger("bench.samples",40);
                ByteBuffer geometry=ByteBuffer.allocateDirect(quads*4*36).order(ByteOrder.nativeOrder());
                for(int q=0;q<quads;q++)for(float[] p:new float[][]{{0,0},{.1f,0},{.1f,.1f},{0,.1f}})geometry.putFloat(p[0]).putFloat(p[1]).putFloat(0).putInt(-1).putFloat(.5f).putFloat(.5f).putInt(0).putInt(0).putInt(127<<16);
                geometry.flip();BufferBuilder builder=new BufferBuilder(geometry.remaining()+256);
                // Compile the actual hot capture path before any count is sampled; otherwise 50/100 draws ran interpreted.
                for(int frame=0;frame<Integer.getInteger("bench.prewarm",300);frame++) {
                    EntityDraws.beginFrame(0,null);
                    for(int i=0;i<500;i++) {
                        builder.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.NEW_ENTITY);builder.putBulkData(geometry.duplicate());
                        if(!(boolean)capture.invoke(null,builder.end()))throw new AssertionError("Prewarm capture rejected");
                    }
                    EntityDraws.cancel();
                }
                List<String> report=new ArrayList<>();report.add("count,capture_p50_ms,capture_p95_ms,capture_p99_ms,copy_mean_ms,allocated_bytes_per_frame,copied_bytes_per_frame,state_mean_ms,packet_mean_ms");
                List<Integer> counts=new ArrayList<>(List.of(0,50,100,200,500));Collections.shuffle(counts,new Random(Long.getLong("bench.seed",20261027L)));
                for(int count:counts) {
                    long[] times=new long[samples];long allocated=0,bytes=0,copyTime=0,stateTime=0,packetTime=0;
                    for(int frame=0;frame<warmup+samples;frame++) {
                        EntityDraws.beginFrame(0,null);long elapsed=0,allocatedFrame=0;long[] before=EntityDraws.performanceStats();
                        for(int i=0;i<count;i++) {
                            builder.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.NEW_ENTITY);builder.putBulkData(geometry.duplicate());var rendered=builder.end();
                            long allocated0=allocation.getThreadAllocatedBytes(thread),start=System.nanoTime();
                            if(!(boolean)capture.invoke(null,rendered))throw new AssertionError("Capture rejected a supported entity");
                            elapsed+=System.nanoTime()-start;allocatedFrame+=allocation.getThreadAllocatedBytes(thread)-allocated0;
                        }
                        long[] after=EntityDraws.performanceStats();EntityDraws.cancel();
                        if(frame>=warmup){times[frame-warmup]=elapsed;allocated+=allocatedFrame;bytes+=after[3]-before[3];copyTime+=after[4]-before[4];stateTime+=after[6]-before[6];packetTime+=after[7]-before[7];}
                    }
                    Arrays.sort(times);String row=String.format(Locale.ROOT,"%d,%.6f,%.6f,%.6f,%.6f,%d,%d,%.6f,%.6f",count,times[(samples-1)/2]/1e6,times[(samples-1)*95/100]/1e6,times[(samples-1)*99/100]/1e6,copyTime/1e6/samples,allocated/samples,bytes/samples,stateTime/1e6/samples,packetTime/1e6/samples);
                    report.add(row);System.out.println("ENTITY_CAPTURE_SCALE "+row);Files.write(output.resolve("results.csv"),report);
                }
                if(glGetError()!=GL_NO_ERROR)throw new AssertionError("GL error");System.out.println("ENTITY_CAPTURE=OK VISIBLE=false NO_PRESENT=true TEXTURE_MIRROR_COST_INCLUDED=false");
            }
        } finally {EntityDraws.cancel();glfwDestroyWindow(window);glfwTerminate();}
    }
    private static void checkConversions() throws Exception {
        var convert=EntityDraws.class.getDeclaredMethod("vertices",BufferBuilder.RenderedBuffer.class,Object[].class);convert.setAccessible(true);
        for(VertexFormat format:new VertexFormat[]{DefaultVertexFormat.NEW_ENTITY,DefaultVertexFormat.BLOCK})for(int variant:new int[]{9,11}) {
            byte[] input=new byte[4*format.getVertexSize()];for(int i=0;i<input.length;i++)input[i]=(byte)(i*7);
            BufferBuilder builder=new BufferBuilder(256);builder.begin(VertexFormat.Mode.QUADS,format);builder.putBulkData(ByteBuffer.wrap(input));var rendered=builder.end();
            int[] header=new int[11];header[1]=variant;Object[] packet={header,null,null,null};
            try {
                int position=rendered.vertexBuffer().position();byte[] result=(byte[])convert.invoke(null,rendered,packet);
                byte[] expected=new byte[4*24];for(int i=0;i<4;i++)System.arraycopy(input,i*format.getVertexSize(),expected,i*24,24);
                if(!Arrays.equals(result,expected)||position!=rendered.vertexBuffer().position())throw new AssertionError("Packed geometry mismatch variant="+variant);
            } finally {rendered.release();}
        }
        System.out.println("ENTITY_GEOMETRY_CONVERSIONS=OK (shadow/crumbling, NEW_ENTITY/BLOCK, unchanged source positions)");
    }
}
