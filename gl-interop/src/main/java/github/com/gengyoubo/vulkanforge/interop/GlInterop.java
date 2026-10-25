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
    public static Consumer<int[]> importImage(long[] resources) { return new SharedImage(resources); }
    private static void check(String stage) {
        int error = glGetError();
        if (error != GL_NO_ERROR) throw new IllegalStateException(stage + ": GL error 0x" + Integer.toHexString(error));
    }
    private static final class SharedImage implements Consumer<int[]>, AutoCloseable {
        private int texture, memory, framebuffer, ready, complete;
        private final int width, height;
        SharedImage(long[] resources) {
            width = Math.toIntExact(resources[4]); height = Math.toIntExact(resources[5]);
            try {
                check("before shared image import");
                memory = glCreateMemoryObjectsEXT(); glMemoryObjectParameteriEXT(memory, GL_DEDICATED_MEMORY_OBJECT_EXT, GL_TRUE);
                glImportMemoryWin32HandleEXT(memory, resources[1], org.lwjgl.opengl.EXTMemoryObjectWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, resources[0]);
                check("import shared memory");
                int previous = glGetInteger(GL_TEXTURE_BINDING_2D);
                try {
                    texture = glGenTextures(); glBindTexture(GL_TEXTURE_2D, texture);
                    glTexStorageMem2DEXT(GL_TEXTURE_2D, 1, GL_RGBA8, width, height, memory, 0); check("shared texture storage");
                } finally { glBindTexture(GL_TEXTURE_2D, previous); }
                previous = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
                try {
                    framebuffer = glGenFramebuffers(); glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
                    glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
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
            if (texture != 0) { glDeleteTextures(texture); texture = 0; }
            if (memory != 0) { glDeleteMemoryObjectsEXT(memory); memory = 0; }
            if (ready != 0) { glDeleteSemaphoresEXT(ready); ready = 0; }
            if (complete != 0) { glDeleteSemaphoresEXT(complete); complete = 0; }
        }
    }
}
