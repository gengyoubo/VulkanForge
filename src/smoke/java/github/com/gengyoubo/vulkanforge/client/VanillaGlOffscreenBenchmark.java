package github.com.gengyoubo.vulkanforge.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import dev.vulkanforge.bench.Workload;
import dev.vulkanforge.bridge.WorldProtocol;
import java.io.*;
import java.net.URL;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.resources.*;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;

/** Minecraft's ShaderInstance/VertexBuffer chunk draws in a hidden FBO; no custom terrain renderer is instantiated. */
public final class VanillaGlOffscreenBenchmark {
    private record Section(long key,int x,int y,int z,VertexBuffer opaque,VertexBuffer translucent,long opaqueQuads,long translucentQuads) { }
    private static final List<Section> sections=new ArrayList<>(),translucent=new ArrayList<>();
    private static WorldProtocol.Camera camera;
    private static ShaderInstance solidShader,translucentShader;
    private static long opaqueDraws,translucentDraws;
    private static final int WIDTH=1920,HEIGHT=1080;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path output=Path.of(System.getProperty("bench.output","build/reports/vanilla-gl"));Files.createDirectories(output);
        List<String> report=new ArrayList<>();
        RenderSystem.initRenderThread();
        if(!glfwInit()) throw new AssertionError("GLFW initialization failed");
        long window=0;
        try {
            glfwDefaultWindowHints();glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,6);glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
            window=glfwCreateWindow(32,32,"Hidden vanilla GL benchmark",0,0);
            if(window==0) throw new AssertionError("Hidden context failed");
            glfwMakeContextCurrent(window);GL.createCapabilities();
            report.add("GPU="+glGetString(GL_RENDERER)+" GL="+glGetString(GL_VERSION));
            report.add("RENDERER=MINECRAFT_1_20_1_SHADERINSTANCE_VERTEXBUFFER PER_SECTION_DRAWS=true CUSTOM_TERRAIN=false VULKAN=false SWAP_CALLS=0");
            PathPackResources pack=new PathPackResources("Minecraft classpath resources",Path.of("."),true);
            ResourceProvider resources=location->{
                URL url=VanillaGlOffscreenBenchmark.class.getResource("/assets/"+location.getNamespace()+"/"+location.getPath());
                if(url==null) return Optional.empty();
                return Optional.of(new Resource(pack,()->location.getPath().endsWith(".vsh")||location.getPath().endsWith(".fsh")
                        ? new ByteArrayInputStream(expand(url,new HashSet<>(),false).getBytes(java.nio.charset.StandardCharsets.UTF_8)) : url.openStream()));
            };
            solidShader=new ShaderInstance(resources,"rendertype_solid",DefaultVertexFormat.BLOCK);
            translucentShader=new ShaderInstance(resources,"rendertype_translucent",DefaultVertexFormat.BLOCK);
            int color=texture(WIDTH,HEIGHT,GL_RGBA8),depth=texture(WIDTH,HEIGHT,GL_DEPTH_COMPONENT32F);
            int target=glGenFramebuffers();glBindFramebuffer(GL_FRAMEBUFFER,target);
            glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,color,0);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,depth,0);
            if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE) throw new AssertionError("Incomplete FBO");
            int atlas=0,light=0;
            var commands=Workload.read(Path.of(args[0]));
            camera=(WorldProtocol.Camera)commands.stream().filter(WorldProtocol.Camera.class::isInstance).findFirst().orElseThrow();
            for(var command:commands) {
                if(command instanceof WorldProtocol.Atlas a) {atlas=texture(a.width(),a.height(),GL_RGBA8);upload(atlas,a.width(),a.height(),a.rgba());}
                else if(command instanceof WorldProtocol.TexturePatch p) {light=texture(16,16,GL_RGBA8);upload(light,16,16,p.rgba());}
                else if(command instanceof WorldProtocol.Mesh m) {
                    if(!Workload.visible(m,camera)) continue;
                    var section=new Section(m.key(),m.x(),m.y(),m.z(),buffer(m.vertices(),m,false),buffer(m.translucent(),m,true),m.vertices().length/(6L*28),m.translucent().length/(6L*28));
                    sections.add(section);if(section.translucent()!=null) translucent.add(section);
                }
            }
            sections.sort(Comparator.comparingDouble(VanillaGlOffscreenBenchmark::distance));
            translucent.sort(Comparator.comparingDouble(VanillaGlOffscreenBenchmark::distance).reversed());
            for(ShaderInstance shader:new ShaderInstance[]{solidShader,translucentShader}) {
                shader.setSampler("Sampler0",atlas);shader.setSampler("Sampler2",light);
                shader.MODEL_VIEW_MATRIX.set(new Matrix4f());shader.PROJECTION_MATRIX.set(new Matrix4f().set(camera.matrix()));
                shader.COLOR_MODULATOR.set(1f,1f,1f,1f);shader.FOG_START.set(1e8f);shader.FOG_END.set(2e8f);shader.FOG_COLOR.set(0f,0f,0f,0f);shader.FOG_SHAPE.set(0);
                if(shader.TEXTURE_MATRIX!=null) shader.TEXTURE_MATRIX.set(new Matrix4f());
            }
            glViewport(0,0,WIDTH,HEIGHT);
            RenderSystem.disableCull();RenderSystem.enableCull();RenderSystem.disableDepthTest();RenderSystem.enableDepthTest();
            int warmup=Integer.getInteger("bench.warmup",2000);
            for(int i=0;i<warmup;i++) frame();glFinish();
            long beforeOpaque=opaqueDraws,beforeTranslucent=translucentDraws;frame();glFinish();
            Map<Long,Long> opaqueManifest=new TreeMap<>(),translucentManifest=new TreeMap<>();
            for(var section:sections) if(section.opaque()!=null) opaqueManifest.put(section.key(),section.opaqueQuads());
            for(var section:translucent) translucentManifest.put(section.key(),section.translucentQuads());
            if(opaqueDraws-beforeOpaque!=opaqueManifest.size()||translucentDraws-beforeTranslucent!=translucentManifest.size()) throw new AssertionError("Draw accounting mismatch");
            Workload.audit(output.resolve("workload.txt"),opaqueManifest,translucentManifest);
            ByteBuffer pixels=MemoryUtil.memAlloc(WIDTH*HEIGHT*4);
            try {
                glReadPixels(0,0,WIDTH,HEIGHT,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
                if(Boolean.getBoolean("bench.capture")) {byte[] rgba=new byte[pixels.capacity()];pixels.get(0,rgba);Files.write(output.resolve("preview.rgba"),rgba);}
                int geometry=0;for(int i=3;i<pixels.capacity();i+=4) if((pixels.get(i)&255)>0) geometry++;
                if(geometry<WIDTH*HEIGHT/100) throw new AssertionError("Blank native scene");report.add("GEOMETRY_PIXELS="+geometry);
            } finally {MemoryUtil.memFree(pixels);}
            int[] queries=new int[64];glGenQueries(queries);
            double minimumSeconds=Double.parseDouble(System.getProperty("bench.seconds","6"));
            long gpu=0,frames=0,samples=0,epochStart=System.currentTimeMillis(),start=System.nanoTime();
            do {
                int slot=(int)(frames%queries.length);
                if(frames>=queries.length) {gpu+=glGetQueryObjectui64(queries[slot],GL_QUERY_RESULT);samples++;}
                glBeginQuery(GL_TIME_ELAPSED,queries[slot]);frame();glEndQuery(GL_TIME_ELAPSED);frames++;
            } while(System.nanoTime()-start<minimumSeconds*1e9);
            glFinish();double seconds=(System.nanoTime()-start)/1e9;long epochEnd=System.currentTimeMillis();
            for(long i=Math.max(0,frames-queries.length);i<frames;i++) {gpu+=glGetQueryObjectui64(queries[(int)(i%queries.length)],GL_QUERY_RESULT);samples++;}
            if(samples!=frames) throw new AssertionError("Query accounting mismatch");
            String result=String.format("BENCH_RESULT API=VANILLA_GL run=1 width=%d height=%d frames=%d seconds=%.6f FPS=%.3f GPU_MS=%.6f START_MS=%d END_MS=%d",WIDTH,HEIGHT,frames,seconds,frames/seconds,gpu/1e6/frames,epochStart,epochEnd);
            report.add(result);System.out.println(result);glDeleteQueries(queries);
            for(var section:sections) {if(section.opaque()!=null)section.opaque().close();if(section.translucent()!=null)section.translucent().close();}
            solidShader.close();translucentShader.close();
            glDeleteTextures(atlas);glDeleteTextures(light);glDeleteTextures(color);glDeleteTextures(depth);glDeleteFramebuffers(target);
            if(glGetError()!=GL_NO_ERROR) throw new AssertionError("Native GL error");report.add("STATUS=OK");
        } finally {
            Files.write(output.resolve("benchmark.txt"),report);
            if(window!=0)glfwDestroyWindow(window);glfwTerminate();
        }
    }
    // Standalone runtimes have no Forge registry lifecycle. Expand vanilla moj_import files before ShaderInstance's
    // preprocessor reaches Forge's import-location hook; the shader bodies and native compile/link/draw code are unchanged.
    static String expand(URL url,Set<String> included,boolean nested) throws IOException {
        if(!included.add(url.toString())) return "";
        String source;
        try(InputStream in=url.openStream()) {source=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
        StringBuilder result=new StringBuilder();
        Pattern pattern=Pattern.compile("\\s*#moj_import\\s+[<\"]([^>\"]+)[>\"].*");
        for(String line:source.split("\\R")) {
            Matcher importLine=pattern.matcher(line);
            if(importLine.matches()) {
                URL dependency=VanillaGlOffscreenBenchmark.class.getResource("/assets/minecraft/shaders/include/"+importLine.group(1));
                if(dependency==null) throw new FileNotFoundException(importLine.group(1));result.append(expand(dependency,included,true));
            } else if(!nested||!line.stripLeading().startsWith("#version")) result.append(line).append('\n');
        }
        return result.toString();
    }
    private static void frame() {
        RenderSystem.depthMask(true);glClearColor(.5f,.7f,1,0);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
        RenderSystem.enableDepthTest();RenderSystem.depthFunc(GL_LEQUAL);RenderSystem.enableCull();RenderSystem.disableBlend();
        layer(solidShader,sections,false);
        RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();layer(translucentShader,translucent,true);
    }
    /** Same shader-per-layer, ChunkOffset upload, VBO bind/draw sequence as LevelRenderer.renderChunkLayer. */
    private static void layer(ShaderInstance shader,List<Section> list,boolean transparent) {
        shader.apply();
        for(var section:list) {
            VertexBuffer buffer=transparent?section.translucent():section.opaque();if(buffer==null) continue;
            shader.CHUNK_OFFSET.set((float)(section.x()-camera.x()),(float)(section.y()-camera.y()),(float)(section.z()-camera.z()));shader.CHUNK_OFFSET.upload();
            buffer.bind();buffer.draw();if(transparent)translucentDraws++;else opaqueDraws++;
        }
        shader.CHUNK_OFFSET.set(0f,0f,0f);shader.clear();VertexBuffer.unbind();
    }
    private static VertexBuffer buffer(byte[] triangles,WorldProtocol.Mesh mesh,boolean transparent) {
        if(triangles.length==0) return null;
        ByteBuffer quads=ByteBuffer.allocate(triangles.length/(6*28)*4*32).order(ByteOrder.LITTLE_ENDIAN);
        for(int at=0;at<triangles.length;at+=6*28) for(int v:new int[]{0,1,2,4}) quads.put(triangles,at+v*28,28).putInt(0);
        BufferBuilder builder=new BufferBuilder(quads.capacity()+256);builder.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.BLOCK);builder.putBulkData(quads.flip());
        if(transparent) builder.setQuadSorting(VertexSorting.byDistance((float)(camera.x()-mesh.x()),(float)(camera.y()-mesh.y()),(float)(camera.z()-mesh.z())));
        VertexBuffer buffer=new VertexBuffer(VertexBuffer.Usage.STATIC);buffer.bind();buffer.upload(builder.end());VertexBuffer.unbind();return buffer;
    }
    private static double distance(Section s) {double x=s.x()+8-camera.x(),y=s.y()+8-camera.y(),z=s.z()+8-camera.z();return x*x+y*y+z*z;}
    private static int texture(int width,int height,int format) {int id=glGenTextures();glBindTexture(GL_TEXTURE_2D,id);glTexStorage2D(GL_TEXTURE_2D,1,format,width,height);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAX_LEVEL,0);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);return id;}
    private static void upload(int id,int width,int height,byte[] rgba) {ByteBuffer bytes=MemoryUtil.memAlloc(rgba.length);try {bytes.put(rgba).flip();glBindTexture(GL_TEXTURE_2D,id);glTexSubImage2D(GL_TEXTURE_2D,0,0,0,width,height,GL_RGBA,GL_UNSIGNED_BYTE,bytes);}finally {MemoryUtil.memFree(bytes);}}
}
