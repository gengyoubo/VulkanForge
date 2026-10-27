package github.com.gengyoubo.vulkanforge.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.*;
import java.nio.file.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;

/** Real GL 8192 readback and CPU-mirror paths, including pack state preservation. */
public final class LargeTextureCaptureSmoke {
    public static void main(String[] args) throws Exception {
        Path out=Path.of("build/reports/large-atlas");Files.createDirectories(out);Files.writeString(out.resolve("gl-capture.txt"),"STATUS=RUNNING\n");
        RenderSystem.initRenderThread();
        if(!glfwInit())throw new AssertionError("GLFW");
        glfwDefaultWindowHints();glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);
        long window=glfwCreateWindow(32,32,"Large texture readback",0,0);
        if(window==0)throw new AssertionError("GL context");
        try {
            glfwMakeContextCurrent(window);org.lwjgl.opengl.GL.createCapabilities();
            int texture=glGenTextures();GlStateManager._bindTexture(texture);
            glTexStorage2D(GL_TEXTURE_2D,1,GL_RGBA8,8192,8192);
            glTexSubImage2D(GL_TEXTURE_2D,0,8191,8191,1,1,GL_RGBA,GL_UNSIGNED_BYTE,new int[]{0xffc95d11});
            glPixelStorei(GL_PACK_ALIGNMENT,8);glPixelStorei(GL_PACK_ROW_LENGTH,13);
            glPixelStorei(GL_PACK_SKIP_ROWS,3);glPixelStorei(GL_PACK_SKIP_PIXELS,2);
            try(TextureCapture.Readback readback=new TextureCapture.Readback()) {
                readback.request(texture);
                require(glGetInteger(GL_PACK_ALIGNMENT)==8&&glGetInteger(GL_PACK_ROW_LENGTH)==13&&glGetInteger(GL_PACK_SKIP_ROWS)==3&&glGetInteger(GL_PACK_SKIP_PIXELS)==2,"pack state changed");
                glFlush(); // Test submission only; production polling never forces completion.
                long deadline=System.nanoTime()+15_000_000_000L;
                TextureCapture.Snapshot snapshot;
                while((snapshot=readback.poll())==null&&System.nanoTime()<deadline)Thread.yield();
                require(snapshot!=null,"PBO readback timeout");
                require(snapshot.width()==8192&&snapshot.height()==8192&&snapshot.rgba().length==268435456,"full PBO extent");
                pixel(snapshot,8191,8191);
            }
            GlStateManager._bindTexture(0);glDeleteTextures(texture);
            glPixelStorei(GL_PACK_ALIGNMENT,4);glPixelStorei(GL_PACK_ROW_LENGTH,0);glPixelStorei(GL_PACK_SKIP_ROWS,0);glPixelStorei(GL_PACK_SKIP_PIXELS,0);
            // Exercise tracked CPU uploads at the same width without depending on a 256 MiB mirror budget being empty.
            texture=glGenTextures();GlStateManager._bindTexture(texture);
            CpuTextures.image(GL_TEXTURE_2D,0,GL_RGBA8,8192,512,0,GL_RGBA,GL_UNSIGNED_BYTE,null);
            IntBuffer source=org.lwjgl.system.MemoryUtil.memAllocInt(1);
            try {
                source.put(0,0xffc95d11);
                CpuTextures.subImageBuffer(GL_TEXTURE_2D,0,8191,511,1,1,GL_RGBA,GL_UNSIGNED_BYTE,source);
                TextureCapture.Snapshot mirror=CpuTextures.snapshot(texture);
                require(mirror!=null&&mirror.width()==8192&&mirror.height()==512,"large CPU mirror");pixel(mirror,8191,511);
                pixel(TextureCapture.read(texture),8191,511);
                try(TextureCapture.Readback readback=new TextureCapture.Readback()) {
                    readback.request(texture);require(readback.poll()==mirror,"CPU snapshot bypass did not retain large atlas");
                }
            } finally { org.lwjgl.system.MemoryUtil.memFree(source);CpuTextures.delete(texture); }
            require(glGetError()==GL_NO_ERROR,"GL error");
            String report="STATUS=OK\nPBO=8192x8192\nCPU_MIRROR=8192x512\nSYNC_READ=8192x512\nPACK_STATE=preserved\nEDGE_PIXELS=OK\n";
            Files.writeString(out.resolve("gl-capture.txt"),report);System.out.print(report);
        } finally { glfwDestroyWindow(window);glfwTerminate(); }
    }
    private static void pixel(TextureCapture.Snapshot snapshot,int x,int y) {
        int at=(y*snapshot.width()+x)*4;
        require((snapshot.rgba()[at]&255)==17&&(snapshot.rgba()[at+1]&255)==93&&(snapshot.rgba()[at+2]&255)==201&&(snapshot.rgba()[at+3]&255)==255,"edge pixel mismatch");
    }
    private static void require(boolean value,String message) { if(!value)throw new AssertionError(message); }
}
