package github.com.gengyoubo.vulkanforge.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.vulkanforge.bridge.WorldProtocol;
import github.com.gengyoubo.vulkanforge.config.VulkanForgeConfig.OpenGLVersion;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;

/** Completed-frame throughput over the same terrain/atlas/camera as the Vulkan offscreen benchmark. */
public final class GlOffscreenBenchmark {
    private static final boolean MATCHED = Boolean.getBoolean("bench.matchVulkanTargets");
    private static final int[] opaqueTargets=new int[2], translucentTargets=new int[2], opaqueDepth=new int[2], translucentDepth=new int[2], sceneDepth=new int[2];
    private static final int[] opaqueColors=new int[2], translucentColors=new int[2];
    private static final long[] frameFences=new long[2];
    private static int targetWidth,targetHeight,targetFrame;
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        int width = Integer.getInteger("bench.width", 1920), height = Integer.getInteger("bench.height", 1080);
        int warmup = Integer.getInteger("bench.warmup", 2000), minimumFrames = Integer.getInteger("bench.frames", 3000);
        int runs = Integer.getInteger("bench.runs", 3);
        double minimumSeconds = Double.parseDouble(System.getProperty("bench.seconds", "6"));
        String api = args[0];
        Path directory = Path.of(System.getProperty("bench.output", "build/reports/offscreen-performance/gl-" + api)).toAbsolutePath(); Files.createDirectories(directory);
        System.setProperty("vulkanforge.offscreenReportDir", directory.toString());
        // Vulkan's benchmark has frustum culling, but no Hi-Z or vanilla occlusion graph.
        System.setProperty("vulkanforge.gl46.noHiZ", "true");
        RenderSystem.initRenderThread();
        if (!glfwInit()) throw new AssertionError("GLFW init failed");
        long hidden = 0;
        List<String> report = new ArrayList<>();
        try {
            glfwDefaultWindowHints(); glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, Integer.parseInt(api.substring(2)));
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            hidden = glfwCreateWindow(32, 32, "Hidden benchmark context", 0, 0);
            if (hidden == 0) throw new AssertionError("GL context creation failed");
            glfwMakeContextCurrent(hidden); GL.createCapabilities();
            Gl46Context.select(OpenGLVersion.valueOf("GL_" + api.replace('.', '_')));
            if (Gl46Context.terrainUnsupported() != null) throw new AssertionError(Gl46Context.terrainUnsupported());
            report.add("GPU=" + glGetString(GL_RENDERER) + " ACTUAL_GL=" + glGetString(GL_VERSION));
            report.add("API=OpenGL " + api + " " + GlCompat.paths() + " HIZ=false PRESENT=NONE");
            int color = texture(width, height, GL_RGBA8), depth = texture(width, height, GL_DEPTH_COMPONENT32F);
            int target = glGenFramebuffers(); glBindFramebuffer(GL_FRAMEBUFFER, target);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, color, 0);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depth, 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) throw new AssertionError("Incomplete FBO");
            glViewport(0, 0, width, height);
            if (MATCHED) matchedTargets(width,height);
            int atlas = 0, light = 0, sections = 0; long vertices = 0, translucentVertices = 0;
            RenderSystem.setShaderFogStart(1e8f); RenderSystem.setShaderFogEnd(2e8f);
            try (Gl46Terrain terrain = new Gl46Terrain(); DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(Path.of(args[1]))))) {
                while (true) {
                    WorldProtocol.Command command;
                    try { command = WorldProtocol.read(in); } catch (EOFException done) { break; }
                    if (command instanceof WorldProtocol.Atlas a) { atlas = texture(a.width(), a.height(), GL_RGBA8); upload(atlas, a.width(), a.height(), a.rgba()); }
                    else if (command instanceof WorldProtocol.TexturePatch p) { light = texture(16, 16, GL_RGBA8); upload(light, 16, 16, p.rgba()); }
                    else if (command instanceof WorldProtocol.Mesh m) {
                        sections++; vertices += m.vertices().length / WorldProtocol.STRIDE; translucentVertices += m.translucent().length / WorldProtocol.STRIDE;
                        terrain.quads(m.epoch(), m.key(), m.x(), m.y(), m.z(), new byte[][]{quads(m.vertices()), new byte[0], new byte[0], quads(m.translucent())});
                    } else terrain.send(command);
                }
                report.add("SCENE sections=" + sections + " opaqueVertices=" + vertices + " translucentVertices=" + translucentVertices);
                if(Boolean.getBoolean("bench.trustedState")) {
                    RenderSystem.disableCull();RenderSystem.enableCull();RenderSystem.disableDepthTest();RenderSystem.enableDepthTest();
                    RenderSystem.depthFunc(GL_LESS);RenderSystem.depthFunc(GL_LEQUAL);RenderSystem.depthMask(true);RenderSystem.disableBlend();RenderSystem.blendFuncSeparate(GL_ONE,GL_ZERO,GL_ONE,GL_ZERO);
                    for(int unit=0;unit<3;unit++){RenderSystem.activeTexture(GL_TEXTURE0+unit);RenderSystem.bindTexture(atlas);RenderSystem.bindTexture(0);}RenderSystem.activeTexture(GL_TEXTURE0);
                    if(!GlStateCache.initialise())throw new AssertionError("Benchmark state cache not coherent");
                    report.add("BENCH_STATE_CACHE_TRUSTED=true");
                }
                if(Boolean.getBoolean("bench.pipeline")) {
                    final int atlasTexture=atlas,lightTexture=light;
                    try(var pipeline=new dev.vulkanforge.bench.PipelineBenchmark(width,height,new dev.vulkanforge.bench.PipelineBenchmark.Renderer(){
                        public void terrain(dev.vulkanforge.bench.PipelineBenchmark.Target target,int slot){frame(terrain,atlasTexture,lightTexture);}
                        public void audit(Path out) throws Exception {dev.vulkanforge.bench.Workload.audit(out.resolve("workload.txt"),terrain.auditOpaqueDraws(),terrain.auditTranslucentDraws());}
                    },null,null)) {
                        glDisable(GL_DITHER);glDisable(GL_FRAMEBUFFER_SRGB);pipeline.run(directory,"CUSTOM_GL46",warmup,minimumSeconds);
                    }
                    report.add("PIPELINE_STATUS=OK");return;
                }
                for (int i = 0; i < warmup; i++) frame(terrain, atlas, light);
                glFinish();
                if (Boolean.getBoolean("bench.audit")) dev.vulkanforge.bench.Workload.audit(directory.resolve("workload.txt"),terrain.auditOpaqueDraws(),terrain.auditTranslucentDraws());
                // Verify actual geometry before measurement, with no pixel readback inside the timed loops.
                ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
                try {
                    glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
                    if (Boolean.getBoolean("bench.capture")) { byte[] rgba=new byte[pixels.capacity()];pixels.get(0,rgba);Files.write(directory.resolve("preview.rgba"),rgba); }
                    int geometry = 0; for (int i = 3; i < pixels.capacity(); i += 4) if ((pixels.get(i) & 255) > 0) geometry++;
                    if (geometry < width * height / 100) throw new AssertionError("Blank benchmark scene");
                    report.add("GEOMETRY_PIXELS=" + geometry);
                } finally { MemoryUtil.memFree(pixels); }
                int[] queries = new int[64]; glGenQueries(queries);
                for (int run = 1; run <= runs; run++) {
                    glFinish(); long gpuNanos = 0, samples = 0, frames = 0;
                    long epochStart = System.currentTimeMillis(), start = System.nanoTime();
                    do {
                        int slot = (int)(frames % queries.length);
                        if (frames >= queries.length) { gpuNanos += glGetQueryObjectui64(queries[slot], GL_QUERY_RESULT); samples++; }
                        glBeginQuery(GL_TIME_ELAPSED, queries[slot]); frame(terrain, atlas, light); glEndQuery(GL_TIME_ELAPSED);
                        frames++;
                    } while (frames < minimumFrames || System.nanoTime() - start < minimumSeconds * 1e9);
                    glFinish(); double seconds = (System.nanoTime() - start) / 1e9; long epochEnd = System.currentTimeMillis();
                    for (long i = Math.max(0, frames - queries.length); i < frames; i++) { gpuNanos += glGetQueryObjectui64(queries[(int)(i % queries.length)], GL_QUERY_RESULT); samples++; }
                    if (samples != frames) throw new AssertionError("GPU query accounting mismatch");
                    String line = String.format("BENCH_RESULT API=GL%s run=%d width=%d height=%d frames=%d seconds=%.6f FPS=%.3f GPU_MS=%.6f START_MS=%d END_MS=%d", api, run, width, height, frames, seconds, frames / seconds, gpuNanos / 1e6 / frames, epochStart, epochEnd);
                    report.add(line); System.out.println(line);
                }
                glDeleteQueries(queries);
                report.add("SUBMISSION_STATS="+diagnostics(terrain,Gl46Terrain.class,"submissionStats"));
                report.add("STATE_RESTORE_STATS="+diagnostics(null,Gl46State.class,"stats"));
            }
            glDeleteTextures(atlas); glDeleteTextures(light); glDeleteTextures(color); glDeleteTextures(depth); glDeleteFramebuffers(target);
            if (MATCHED) for(int i=0;i<2;i++) {
                if(frameFences[i]!=0) glDeleteSync(frameFences[i]);
                glDeleteFramebuffers(opaqueTargets[i]); glDeleteFramebuffers(translucentTargets[i]);
                glDeleteTextures(opaqueDepth[i]); glDeleteTextures(translucentDepth[i]); glDeleteTextures(sceneDepth[i]);
                glDeleteTextures(opaqueColors[i]); glDeleteTextures(translucentColors[i]);
            }
            if (glGetError() != GL_NO_ERROR) throw new AssertionError("OpenGL benchmark error");
            report.add("STATUS=OK");
        } finally {
            Files.write(directory.resolve("benchmark.txt"), report);
            if (hidden != 0) glfwDestroyWindow(hidden); glfwTerminate();
        }
    }
    private static String diagnostics(Object instance,Class<?> type,String name) {
        try {var method=type.getDeclaredMethod(name);method.setAccessible(true);return Arrays.toString((long[])method.invoke(instance));}
        catch(NoSuchMethodException legacy) {return "LEGACY";}
        catch(ReflectiveOperationException error) {throw new IllegalStateException(error);}
    }
    private static void frame(Gl46Terrain terrain, int atlas, int light) {
        int slot=targetFrame++%2;
        if(MATCHED) {
            if(frameFences[slot]!=0) {
                int state;
                do { state=glClientWaitSync(frameFences[slot],GL_SYNC_FLUSH_COMMANDS_BIT,1_000_000_000L); if(state==GL_WAIT_FAILED) throw new AssertionError("Fence wait failed"); } while(state==GL_TIMEOUT_EXPIRED);
                glDeleteSync(frameFences[slot]);
            }
            glBindFramebuffer(GL_FRAMEBUFFER,opaqueTargets[slot]);
        }
        glDepthMask(true); glClearColor(.5f, .7f, 1, 0); glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        if (!terrain.drawOpaque(atlas, light)) throw new AssertionError("Terrain not ready");
        if(MATCHED) {
            glCopyImageSubData(opaqueDepth[slot],GL_TEXTURE_2D,0,0,0,0,translucentDepth[slot],GL_TEXTURE_2D,0,0,0,0,targetWidth,targetHeight,1);
            glCopyImageSubData(opaqueDepth[slot],GL_TEXTURE_2D,0,0,0,0,sceneDepth[slot],GL_TEXTURE_2D,0,0,0,0,targetWidth,targetHeight,1);
            glBindFramebuffer(GL_FRAMEBUFFER,translucentTargets[slot]); glClearColor(0,0,0,0); glClear(GL_COLOR_BUFFER_BIT);
        }
        terrain.drawTranslucent(atlas, light);
        if(MATCHED) { frameFences[slot]=glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE,0); glBindFramebuffer(GL_FRAMEBUFFER,opaqueTargets[slot]); }
    }
    private static void matchedTargets(int width,int height) {
        targetWidth=width;targetHeight=height;
        for(int i=0;i<2;i++) {
            opaqueDepth[i]=texture(width,height,GL_DEPTH_COMPONENT32F);translucentDepth[i]=texture(width,height,GL_DEPTH_COMPONENT32F);sceneDepth[i]=texture(width,height,GL_DEPTH_COMPONENT32F);
            opaqueColors[i]=texture(width,height,GL_RGBA8);translucentColors[i]=texture(width,height,GL_RGBA8);
            opaqueTargets[i]=target(opaqueColors[i],opaqueDepth[i]);
            translucentTargets[i]=target(translucentColors[i],translucentDepth[i]);
        }
    }
    private static int target(int color,int depth) {
        int fbo=glGenFramebuffers();glBindFramebuffer(GL_FRAMEBUFFER,fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,color,0);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,depth,0);
        if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE) throw new AssertionError("Incomplete matched FBO");
        return fbo;
    }
    private static int texture(int width, int height, int format) {
        int id = glGenTextures(); glBindTexture(GL_TEXTURE_2D, id); glTexStorage2D(GL_TEXTURE_2D, 1, format, width, height);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_BASE_LEVEL, 0); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 0);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST); return id;
    }
    private static void upload(int id, int width, int height, byte[] rgba) {
        ByteBuffer bytes = MemoryUtil.memAlloc(rgba.length);
        try { bytes.put(rgba).flip(); glBindTexture(GL_TEXTURE_2D, id); glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, bytes); }
        finally { MemoryUtil.memFree(bytes); }
    }
    private static byte[] quads(byte[] triangles) {
        ByteBuffer out = ByteBuffer.allocate(triangles.length / (6 * 28) * 4 * 32).order(ByteOrder.LITTLE_ENDIAN);
        for (int at = 0; at < triangles.length; at += 6 * 28) for (int vertex : new int[]{0, 1, 2, 4}) out.put(triangles, at + vertex * 28, 28).putInt(0);
        return out.array();
    }
}
