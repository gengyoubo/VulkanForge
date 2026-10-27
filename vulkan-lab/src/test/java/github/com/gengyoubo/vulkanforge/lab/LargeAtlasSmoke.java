package github.com.gengyoubo.vulkanforge.lab;

import dev.vulkanforge.bridge.WorldProtocol;
import github.com.gengyoubo.vulkanforge.interop.GlInterop;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;

/** Full-sized production atlas upload, sampling beyond 4096, animated edge patches and reload. */
public final class LargeAtlasSmoke {
    public static void main(String[] args) throws Exception {
        org.lwjgl.system.Configuration.STACK_SIZE.set(4096);
        Path out = Path.of(System.getProperty("vulkanforge.smokeReportDir")); Files.createDirectories(out);
        Files.writeString(out.resolve("result.txt"),"STATUS=RUNNING\n");
        protocolChecks();
        require(glfwInit(), "GLFW");
        glfwDefaultWindowHints(); glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        long window = glfwCreateWindow(32, 32, "Large Vulkan atlas smoke", 0, 0);
        require(window != 0, "hidden GL window");
        try {
            glfwMakeContextCurrent(window); org.lwjgl.opengl.GL.createCapabilities();
            try (MainWindowBackend backend = new MainWindowBackend(out.resolve("backend.txt").toString(),
                    GlInterop.deviceUUID(), GlInterop::importImage, org.lwjgl.opengl.GL11::glFinish,
                    VulkanContext.requestedApiVersion(), "", true)) {
                Consumer<Object[]> local = backend.worldCommands(GlInterop::importTerrain);
                Consumer<WorldProtocol.Command> send = c -> local.accept(WorldProtocol.pack(c));
                send.accept(new WorldProtocol.Reset(1));
                byte[] light = new byte[1024]; Arrays.fill(light, (byte)-1);
                send.accept(new WorldProtocol.TexturePatch(1, WorldProtocol.LIGHT_MAP, 0, 0, 16, 16, light));
                send.accept(new WorldProtocol.Camera(1, 0, 0, 0, new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1}, 0,0,0));
                byte[] pixels = new byte[WorldProtocol.atlasBytes(8192,8192)];
                for (int y = 0; y < 8192; y++) for (int x = 0; x < 8192; x++) {
                    int at = (y * 8192 + x) * 4;
                    pixels[at] = (byte)x; pixels[at+1] = (byte)y; pixels[at+2] = (byte)(x/256 + y/256); pixels[at+3] = -1;
                }
                long[] before = backend.uploadStats();
                WorldProtocol.Atlas decoded = readAtlas(pixels);
                require(Arrays.equals(pixels,decoded.rgba()),"256 MiB binary atlas round trip");
                pixels = decoded.rgba();
                send.accept(decoded);
                for (int[] point : new int[][]{{0,0},{8191,0},{0,8191},{8191,8191},{4097,4097},{237,511},{4111,512}})
                    sample(backend, send, 8192,8192,point[0],point[1],point[0]&255,point[1]&255,point[0]/256+point[1]/256);
                long[] after = backend.uploadStats();
                require(after[2]-before[2] == pixels.length + 7L*6*WorldProtocol.STRIDE, "full atlas byte count");
                require(after[5]-before[5] >= 16, "atlas did not use bounded row uploads");
                // Binary bridge and in-process bridge must agree on patches above the former 4096 boundary.
                WorldProtocol.TexturePatch patch = new WorldProtocol.TexturePatch(1,WorldProtocol.BLOCK_ATLAS,8190,8190,2,2,
                        new byte[]{17,93,(byte)201,-1,17,93,(byte)201,-1,17,93,(byte)201,-1,17,93,(byte)201,-1});
                ByteArrayOutputStream wire = new ByteArrayOutputStream(); WorldProtocol.write(new DataOutputStream(wire),patch);
                send.accept(WorldProtocol.read(new DataInputStream(new ByteArrayInputStream(wire.toByteArray()))));
                for(int i=0;i<3;i++) sample(backend,send,8192,8192,8191,8191,17,93,201);
                rejects(() -> send.accept(new WorldProtocol.TexturePatch(1,0,8191,8191,2,2,new byte[16])));
                // Non-square large image and replacement of an atlas while frame slots have been used.
                send.accept(new WorldProtocol.Atlas(1,8192,512,Arrays.copyOf(pixels,WorldProtocol.atlasBytes(8192,512))));
                sample(backend,send,8192,512,8191,511,255,255,32);
                rejects(() -> send.accept(new WorldProtocol.TexturePatch(1,0,0,512,1,1,new byte[4])));
                send.accept(new WorldProtocol.Atlas(1,1,1,new byte[]{31,63,127,-1}));
                sample(backend,send,1,1,0,0,31,63,127);
                require(backend.validationErrors()==0,"Vulkan validation errors");
                require(glGetError()==GL_NO_ERROR,"GL error");
                String report="STATUS=OK\nFULL_ATLAS=8192x8192 RGBA_BYTES=268435456\nBINARY_FULL_ATLAS=OK\nRECTANGULAR_ATLAS=8192x512\nEDGE_PATCH=8191,8191\nRELOAD=1x1\nUPLOADS="+Arrays.toString(after)+"\n";
                Files.writeString(out.resolve("result.txt"),report); System.out.print(report);
            }
        } finally { glfwDestroyWindow(window); glfwTerminate(); }
    }

    private static WorldProtocol.Atlas readAtlas(byte[] pixels) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(header);
        out.writeByte(2);out.writeLong(1);out.writeInt(8192);out.writeInt(8192);out.writeInt(pixels.length);
        try (var in = new DataInputStream(new SequenceInputStream(new ByteArrayInputStream(header.toByteArray()),new ByteArrayInputStream(pixels)))) {
            return (WorldProtocol.Atlas)WorldProtocol.read(in);
        }
    }

    private static void sample(MainWindowBackend backend, Consumer<WorldProtocol.Command> send, int width,int height,int x,int y,int r,int g,int b) {
        ByteBuffer vertices=ByteBuffer.allocate(6*WorldProtocol.STRIDE).order(ByteOrder.LITTLE_ENDIAN);
        for(float[] p:new float[][]{{-.8f,-.8f},{.8f,-.8f},{.8f,.8f},{-.8f,-.8f},{.8f,.8f},{-.8f,.8f}})
            vertices.putFloat(p[0]).putFloat(p[1]).putFloat(.2f).putInt(-1).putFloat((x+.5f)/width).putFloat((y+.5f)/height).putShort((short)240).putShort((short)240);
        send.accept(new WorldProtocol.Mesh(1,0,0,0,0,vertices.array()));
        int texture=backend.renderWorld(32,32),fbo=glGenFramebuffers(); require(texture!=0,"terrain output");
        try {
            glBindFramebuffer(GL_FRAMEBUFFER,fbo); glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,texture,0);
            ByteBuffer pixel=ByteBuffer.allocateDirect(4); glReadPixels(16,16,1,1,GL_RGBA,GL_UNSIGNED_BYTE,pixel);
            require(Math.abs((pixel.get(0)&255)-r)<=1&&Math.abs((pixel.get(1)&255)-g)<=1&&Math.abs((pixel.get(2)&255)-b)<=1&&(pixel.get(3)&255)==255,
                    "atlas pixel "+x+","+y+" expected="+r+","+g+","+b+" actual="+(pixel.get(0)&255)+","+(pixel.get(1)&255)+","+(pixel.get(2)&255));
        } finally { glBindFramebuffer(GL_FRAMEBUFFER,0); glDeleteFramebuffers(fbo); backend.finishWorldFrame(); }
    }
    private static void protocolChecks() throws Exception {
        require(WorldProtocol.atlasBytes(8192,8192)==268435456,"8192 byte count");
        rejects(()->WorldProtocol.atlasBytes(Integer.MAX_VALUE,Integer.MAX_VALUE));
        rejects(()->WorldProtocol.atlasBytes(8193,1)); rejects(()->WorldProtocol.atlasBytes(0,1));
        rejects(()->new WorldProtocol.Atlas(1,8192,8192,new byte[4]));
        rejects(()->new WorldProtocol.TexturePatch(1,0,Integer.MAX_VALUE,0,1,1,new byte[4]));
        rejects(()->new WorldProtocol.TexturePatch(1,1,0,0,1,1,new byte[4]));
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); DataOutputStream out=new DataOutputStream(bytes);
        out.writeByte(2);out.writeLong(1);out.writeInt(8193);out.writeInt(1);out.writeInt(8193*4);
        try { WorldProtocol.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))); throw new AssertionError("invalid wire atlas accepted"); }
        catch(IOException expected) { require(!(expected instanceof EOFException),"invalid header read payload before validation"); }
    }
    private static void rejects(Runnable action) { try { action.run(); } catch(IllegalArgumentException expected) { return; } throw new AssertionError("invalid image accepted"); }
    private static void require(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
}
