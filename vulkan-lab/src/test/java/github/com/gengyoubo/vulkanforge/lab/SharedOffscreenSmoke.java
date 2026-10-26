package github.com.gengyoubo.vulkanforge.lab;

import dev.vulkanforge.bridge.WorldProtocol;
import github.com.gengyoubo.vulkanforge.interop.GlInterop;
import java.nio.*;
import java.nio.file.*;
import java.util.function.Consumer;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

/** Exercises the game's shared GPU world backend without attaching any surface or presenting a frame. */
public final class SharedOffscreenSmoke {
    public static void main(String[] args) throws Exception {
        org.lwjgl.system.Configuration.STACK_SIZE.set(1024);
        Path directory = Path.of(System.getProperty("vulkanforge.smokeReportDir", "build/reports"));
        Files.createDirectories(directory);
        Path report = directory.resolve("shared-backend.txt");
        if (!glfwInit()) throw new AssertionError("GLFW init failed");
        long hidden = 0;
        try {
            glfwDefaultWindowHints(); glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            hidden = glfwCreateWindow(32, 32, "Hidden interop context", 0, 0);
            if (hidden == 0) throw new AssertionError("Hidden context creation failed");
            glfwMakeContextCurrent(hidden); GL.createCapabilities();
            try (MainWindowBackend backend = new MainWindowBackend(report.toString(), GlInterop.deviceUUID(), GlInterop::importImage, org.lwjgl.opengl.GL11::glFinish)) {
                Consumer<Object[]> sink = backend.worldCommands(GlInterop::importTerrain);
                Consumer<WorldProtocol.Command> send = command -> sink.accept(WorldProtocol.pack(command));
                send.accept(new WorldProtocol.Reset(1));
                send.accept(new WorldProtocol.Atlas(1, 1, 1, new byte[]{(byte)255, 0, 0, (byte)255}));
                byte[] light = new byte[16 * 16 * 4]; java.util.Arrays.fill(light, (byte)255);
                send.accept(new WorldProtocol.TexturePatch(1, WorldProtocol.LIGHT_MAP, 0, 0, 16, 16, light));
                send.accept(new WorldProtocol.Camera(1, 0, 0, 0, new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1}, 0, 0, 0));
                ByteBuffer mesh = ByteBuffer.allocate(6 * WorldProtocol.STRIDE).order(ByteOrder.LITTLE_ENDIAN);
                for (float[] p : new float[][]{{-.5f,-.5f},{.5f,-.5f},{.5f,.5f},{.5f,.5f},{-.5f,.5f},{-.5f,-.5f}})
                    mesh.putFloat(p[0]).putFloat(p[1]).putFloat(0).putInt(-1).putFloat(.5f).putFloat(.5f).putShort((short)240).putShort((short)240);
                send.accept(new WorldProtocol.Mesh(1, 1, 0, 0, 0, mesh.array()));
                for (int size : new int[]{64, 96, 48}) {
                    boolean red = false;
                    for (int i = 0; i < 32; i++) {
                        int texture = backend.renderWorld(size, size);
                        if (texture == 0) throw new AssertionError("Shared frame missing");
                        ByteBuffer pixels = MemoryUtil.memAlloc(size * size * 4);
                        try {
                            glBindTexture(GL_TEXTURE_2D, texture); glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
                            int at = (size / 2 * size + size / 2) * 4;
                            red |= (pixels.get(at) & 255) > 240 && (pixels.get(at + 1) & 255) < 10 && (pixels.get(at + 2) & 255) < 10;
                        } finally { MemoryUtil.memFree(pixels); }
                        if(i<3) {
                            Object[] left=GuiPassSmoke.quad(EntityPass.FORMAT_POSITION_COLOR,4,EntityPass.DEPTH_TEST|EntityPass.DEPTH_WRITE,0,0xff00ff00,-.2f);
                            Object[] right=GuiPassSmoke.quad(EntityPass.FORMAT_POSITION_COLOR,4,EntityPass.DEPTH_TEST|EntityPass.DEPTH_WRITE,0,0xff00ff00,-.2f);
                            for(Object[] packet:new Object[][]{left,right}) {
                                ByteBuffer data=ByteBuffer.wrap((byte[])packet[2]).order(ByteOrder.LITTLE_ENDIAN);
                                for(int v=0;v<4;v++)data.putFloat(v*16,data.getFloat(v*16)*.5f+(packet==left?-.45f:.45f));
                                if(i==1) {
                                    int[] header=java.util.Arrays.copyOf((int[])packet[0],11);packet[0]=header;
                                    header[EntityPass.H_FLAGS]|=4096;header[10]=Float.floatToRawIntBits(packet==left?-1:1);
                                } else if(i==2) {
                                    int[] header=(int[])packet[0];header[EntityPass.H_INDICES]=6;header[EntityPass.H_INDEX_SIZE]=2;
                                    ByteBuffer indices=ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);for(int v:new int[]{0,1,2,2,3,0})indices.putShort((short)v);packet[3]=indices.array();
                                }
                            }
                            long beforeDraws=backend.worldStats()[19];
                            int[] overlay=backend.renderOverlay(new Object[]{left,right});
                            if(overlay==null)throw new AssertionError("Entity overlay missing");
                            if(backend.worldStats()[19]-beforeDraws!=(i==0?1:2))throw new AssertionError("Entity batch boundary or indexed path changed");
                            ByteBuffer entityPixels=MemoryUtil.memAlloc(size*size*4);
                            try {
                                glBindTexture(GL_TEXTURE_2D,overlay[0]);glGetTexImage(GL_TEXTURE_2D,0,GL_RGBA,GL_UNSIGNED_BYTE,entityPixels);
                                for(int x:new int[]{size/4,size*3/4}) {
                                    int at=(size/2*size+x)*4;
                                    if((entityPixels.get(at+1)&255)!=255||(entityPixels.get(at)&255)!=0)throw new AssertionError("Merged entity overlay pixels");
                                }
                            } finally {MemoryUtil.memFree(entityPixels);backend.finishOverlay();}
                        }
                        backend.finishWorldFrame();
                    }
                    if (!red) throw new AssertionError("Shared red pixels missing at " + size);
                    System.out.println("PASS shared image pixels, semaphore round trips and resize " + size + "x" + size);
                }
                if (backend.frames() != 0 || backend.worldStats()[1] != 96) throw new AssertionError("Offscreen frame accounting mismatch");
                backend.closeWorld();
            }
            String log = Files.readString(report);
            if (!log.contains("FINAL_PRESENTED_FRAMES=0") || !log.contains("VALIDATION_ERRORS=0") || log.contains("SURFACE_CREATED=true"))
                throw new AssertionError("Offscreen shutdown or no-surface contract failed");
            System.out.println("PASS SURFACE=NONE SWAPCHAIN=NONE PRESENTED_FRAMES=0 SHARED_FRAMES=96 clean shutdown");
            if(Boolean.getBoolean("vulkanforge.entityProfile")&&!log.contains("GPU_ENTITY_OVERLAY_MS samples=9"))throw new AssertionError("Entity GPU timer samples missing");
            System.out.println("PASS merged/indexed entity overlay pixels, polygon-offset boundaries, frame-slot reuse and profiling timestamps");
        } finally { if (hidden != 0) glfwDestroyWindow(hidden); glfwTerminate(); }
    }
}
