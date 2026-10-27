package github.com.gengyoubo.vulkanforge.interop;

import java.util.function.Consumer;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.EXTMemoryObject.*;
import static org.lwjgl.opengl.EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT;
import static org.lwjgl.opengl.EXTSemaphore.*;
import static org.lwjgl.opengl.EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT;

/** Compiled against each host's LWJGL. Only JDK interfaces and primitive arrays cross the loader boundary. */
public final class GlInterop {
    private GlInterop() { }
    private static final boolean PROFILE = Boolean.getBoolean("vulkanforge.interopProfile");
    private static long waitCalls, signalCalls, flushCalls, waitNanos, signalNanos, flushNanos;
    /** CPU call duration only; this does not measure the GPU's semaphore wait span. Host GL loader owns these counters. */
    public static long[] groupedCpuStats() { return new long[]{waitCalls,signalCalls,flushCalls,waitNanos,signalNanos,flushNanos}; }
    public static byte[] deviceUUID() {
        var caps = GL.getCapabilities();
        if (!(caps.GL_EXT_memory_object_win32 && caps.GL_EXT_semaphore_win32)) throw new IllegalStateException("GL Win32 external memory/semaphore extensions are required");
        byte[] uuid = new byte[16];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var bytes = stack.malloc(16); org.lwjgl.opengl.EXTMemoryObject.glGetUnsignedBytei_vEXT(org.lwjgl.opengl.EXTMemoryObject.GL_DEVICE_UUID_EXT, 0, bytes); bytes.get(uuid);
        }
        check("GL device UUID"); return uuid;
    }
    public static long windowInstance(long hwnd) {
        // User32's generated Java signature changed between LWJGL 3.3.1 and 3.3.6.
        long function = org.lwjgl.system.windows.User32.getLibrary().getFunctionAddress("GetWindowLongPtrW");
        return org.lwjgl.system.JNI.callPP(hwnd, -6, function); // GWLP_HINSTANCE, Win64 ABI
    }
    /** memory HANDLE, allocation size, ready HANDLE, complete HANDLE, width, height. */
    public static Consumer<int[]> importImage(long[] resources) { return new SharedImage(resources, false); }
    /** Commands: {0, textureId output} waits for Vulkan; {1} releases after all GL sampling. */
    public static Consumer<int[]> importTerrain(long[] resources) { return new SharedImage(resources, true); }
    private static void check(String stage) {
        int error = glGetError();
        if (error != GL_NO_ERROR) throw new IllegalStateException(stage + ": GL error 0x" + Integer.toHexString(error));
    }
    private static final class SharedImage implements Consumer<int[]>, AutoCloseable {
        private int texture, memory, framebuffer, sourceFramebuffer, ready, complete;
        private final int width, height;
        private final boolean terrain;
        SharedImage(long[] resources, boolean terrain) {
            this.terrain = terrain;
            width = Math.toIntExact(resources[4]); height = Math.toIntExact(resources[5]);
            boolean depth = resources.length > 6 && resources[6] == 126; // VK_FORMAT_D32_SFLOAT
            try {
                check("before shared image import");
                memory = glCreateMemoryObjectsEXT(); glMemoryObjectParameteriEXT(memory, GL_DEDICATED_MEMORY_OBJECT_EXT, GL_TRUE);
                glImportMemoryWin32HandleEXT(memory, resources[1], org.lwjgl.opengl.EXTMemoryObjectWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, resources[0]);
                check("import shared memory");
                int previous = glGetInteger(GL_TEXTURE_BINDING_2D);
                try {
                    texture = glGenTextures(); glBindTexture(GL_TEXTURE_2D, texture);
                    glTexStorageMem2DEXT(GL_TEXTURE_2D, 1, depth ? GL_DEPTH_COMPONENT32F : GL_RGBA8, width, height, memory, 0); check("shared texture storage");
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
                } finally { glBindTexture(GL_TEXTURE_2D, previous); }
                previous = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
                try {
                    framebuffer = glGenFramebuffers(); glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
                    glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, depth ? GL_DEPTH_ATTACHMENT : GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
                    if (depth) glDrawBuffer(GL_NONE);
                    int status = glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER);
                    if (status != GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("Shared GL framebuffer incomplete: " + status);
                } finally { glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previous); }
                ready = glGenSemaphoresEXT(); complete = glGenSemaphoresEXT();
                glImportSemaphoreWin32HandleEXT(ready, org.lwjgl.opengl.EXTSemaphoreWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, resources[2]);
                glImportSemaphoreWin32HandleEXT(complete, org.lwjgl.opengl.EXTSemaphoreWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, resources[3]);
                check("import shared semaphores");
            } catch (Throwable error) { close(); throw error; }
        }
        @Override public void accept(int[] source) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var empty = stack.mallocInt(0); var textures = stack.ints(texture); var layouts = stack.ints(GL_LAYOUT_GENERAL_EXT);
                if (terrain) {
                    if (source[0] == 2) { source[1] = texture; return; }
                    if(source[0]==7){
                        int read=glGetInteger(GL_READ_FRAMEBUFFER_BINDING),draw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
                        boolean scissor=glIsEnabled(GL_SCISSOR_TEST),srgb=glIsEnabled(GL_FRAMEBUFFER_SRGB);
                        try {
                            glDisable(GL_SCISSOR_TEST);glDisable(GL_FRAMEBUFFER_SRGB);
                            if(sourceFramebuffer==0)sourceFramebuffer=glGenFramebuffers();
                            glBindFramebuffer(GL_READ_FRAMEBUFFER,sourceFramebuffer);
                            glFramebufferTexture2D(GL_READ_FRAMEBUFFER,source[4]!=0?GL_DEPTH_ATTACHMENT:GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,source[1],0);
                            glReadBuffer(source[4]!=0?GL_NONE:GL_COLOR_ATTACHMENT0);
                            glBindFramebuffer(GL_DRAW_FRAMEBUFFER,framebuffer);
                            glBlitFramebuffer(0,0,source[2],source[3],0,0,width,height,source[4]!=0?GL_DEPTH_BUFFER_BIT:GL_COLOR_BUFFER_BIT,GL_NEAREST);
                        }finally{glBindFramebuffer(GL_READ_FRAMEBUFFER,read);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,draw);if(scissor)glEnable(GL_SCISSOR_TEST);if(srgb)glEnable(GL_FRAMEBUFFER_SRGB);}
                        check("post source image copy");return;
                    }
                    if (source[0] == 5 || source[0] == 6) {
                        int read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING), draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
                        boolean scissor = glIsEnabled(GL_SCISSOR_TEST), srgb = glIsEnabled(GL_FRAMEBUFFER_SRGB);
                        try {
                            glDisable(GL_SCISSOR_TEST); glDisable(GL_FRAMEBUFFER_SRGB);
                            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
                            glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, source[4], 0);
                            glBindFramebuffer(GL_READ_FRAMEBUFFER, source[1]);
                            boolean hasDepth = glGetFramebufferAttachmentParameteri(GL_READ_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) != GL_NONE;
                            if (source[0] == 5) {
                                source[5] = hasDepth ? 1 : 0;
                                glBlitFramebuffer(0, 0, source[2], source[3], 0, 0, width, height,
                                        GL_COLOR_BUFFER_BIT | (hasDepth ? GL_DEPTH_BUFFER_BIT : 0), GL_NEAREST);
                            } else {
                                glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer); glBindFramebuffer(GL_DRAW_FRAMEBUFFER, source[1]);
                                glBlitFramebuffer(0, 0, width, height, 0, 0, source[2], source[3],
                                        GL_COLOR_BUFFER_BIT | (hasDepth ? GL_DEPTH_BUFFER_BIT : 0), GL_NEAREST);
                            }
                        } finally {
                            glBindFramebuffer(GL_READ_FRAMEBUFFER, read); glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw);
                            if (scissor) glEnable(GL_SCISSOR_TEST); if (srgb) glEnable(GL_FRAMEBUFFER_SRGB);
                        }
                        check("native composite target copy"); return;
                    }
                    if (source[0] == 3 || source[0] == 4) {
                        var groupTextures = stack.mallocInt(source[1]); var groupLayouts = stack.mallocInt(source[1]);
                        for (int i = 0; i < source[1]; i++) { groupTextures.put(source[i + 2]); groupLayouts.put(GL_LAYOUT_GENERAL_EXT); }
                        groupTextures.flip(); groupLayouts.flip();
                        if (source[0] == 3) {
                            long start=PROFILE?System.nanoTime():0;
                            glWaitSemaphoreEXT(ready, empty, groupTextures, groupLayouts);
                            if(PROFILE){waitCalls++;waitNanos+=System.nanoTime()-start;}
                        } else {
                            long start=PROFILE?System.nanoTime():0;
                            glSignalSemaphoreEXT(complete, empty, groupTextures, groupLayouts);
                            if(PROFILE){signalCalls++;signalNanos+=System.nanoTime()-start;start=System.nanoTime();}
                            glFlush();
                            if(PROFILE){flushCalls++;flushNanos+=System.nanoTime()-start;}
                        }
                    }
                    else if (source[0] == 0) { glWaitSemaphoreEXT(ready, empty, textures, layouts); source[1] = texture; }
                    else if (source[0] == 1) { glSignalSemaphoreEXT(complete, empty, textures, layouts); glFlush(); }
                    else throw new IllegalArgumentException("Unknown shared terrain command");
                    check("shared terrain handoff"); return;
                }
                glWaitSemaphoreEXT(ready, empty, textures, layouts);
                int read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING), draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
                boolean scissor = glIsEnabled(GL_SCISSOR_TEST), srgb = glIsEnabled(GL_FRAMEBUFFER_SRGB);
                try {
                    glDisable(GL_SCISSOR_TEST); glDisable(GL_FRAMEBUFFER_SRGB);
                    glBindFramebuffer(GL_READ_FRAMEBUFFER, source[0]); glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
                    glBlitFramebuffer(0, 0, source[1], source[2], 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);
                } finally {
                    glBindFramebuffer(GL_READ_FRAMEBUFFER, read); glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw);
                    if (scissor) glEnable(GL_SCISSOR_TEST); if (srgb) glEnable(GL_FRAMEBUFFER_SRGB);
                }
                glSignalSemaphoreEXT(complete, empty, textures, layouts); glFlush();
            }
        }
        @Override public void close() {
            if (memory != 0) glFinish();
            if (framebuffer != 0) { glDeleteFramebuffers(framebuffer); framebuffer = 0; }
            if (sourceFramebuffer != 0) { glDeleteFramebuffers(sourceFramebuffer); sourceFramebuffer = 0; }
            if (texture != 0) { glDeleteTextures(texture); texture = 0; }
            if (memory != 0) { glDeleteMemoryObjectsEXT(memory); memory = 0; }
            if (ready != 0) { glDeleteSemaphoresEXT(ready); ready = 0; }
            if (complete != 0) { glDeleteSemaphoresEXT(complete); complete = 0; }
        }
    }
}
