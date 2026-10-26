package github.com.gengyoubo.vulkanforge.lab;

import github.com.gengyoubo.vulkanforge.interop.GlInterop;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import static github.com.gengyoubo.vulkanforge.lab.EntityPass.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

/** Existing shared main target -> sampled swapchain, including GL reuse after failed recording. */
final class SharedSourcePresentSmoke {
    static void run(MainWindowBackend backend, long window, Path output, AtomicInteger legacyFault) throws Exception {
        backend.worldCommands(GlInterop::importTerrain); // Registers the host importer without creating a world renderer.
        long[] before = backend.presentationStats();
        int framebuffer = glGenFramebuffers();
        int sourceWidth = 320, sourceHeight = 240;
        try {
            attach(backend, framebuffer, sourceWidth, sourceHeight);
            // The old importer deliberately fails if used. Direct presentation must never call it or allocate an intermediate image.
            legacyFault.set(1);
            for (int frame = 0; frame < 16; frame++) {
                if (frame == 8) {
                    backend.destroyRenderTarget(framebuffer);
                    sourceWidth = 400; sourceHeight = 250;
                    attach(backend, framebuffer, sourceWidth, sourceHeight);
                    glfwSetWindowSize(window, 800, 500); backend.setVsync(false);
                }
                glfwPollEvents(); int[] width={0},height={0}; glfwGetFramebufferSize(window,width,height);
                require(backend.beginFrame(width[0],height[0]),"direct frame acquire failed");
                glBindFramebuffer(GL_FRAMEBUFFER,framebuffer);glViewport(0,0,sourceWidth,sourceHeight);
                glDepthMask(true);glClearDepth(.25+frame*.01);glClear(GL_DEPTH_BUFFER_BIT);
                glEnable(GL_SCISSOR_TEST);
                clear(0,sourceHeight/2,sourceWidth/2,sourceHeight/2,1,0,0);
                clear(sourceWidth/2,sourceHeight/2,sourceWidth/2,sourceHeight/2,0,1,0);
                clear(0,0,sourceWidth/2,sourceHeight/2,0,0,1);
                clear(sourceWidth/2,0,sourceWidth/2,sourceHeight/2,1,1,1);
                int red=20+frame*11,green=255-red,blue=frame*13;
                clear(sourceWidth/2-8,sourceHeight/2-8,16,16,red/255f,green/255f,blue/255f);
                glDisable(GL_SCISSOR_TEST);
                Path capture=output.resolve("direct-source-"+frame+".png");backend.captureNext(capture.toString());
                backend.present(framebuffer,sourceWidth,sourceHeight,width[0],height[0]);
                var pixels=ImageIO.read(capture.toFile());
                require(pixels.getWidth()==width[0]&&pixels.getHeight()==height[0],"direct capture extent");
                int[] corners={0xff0000,0x00ff00,0x0000ff,0xffffff};
                for(int i=0;i<4;i++)require((pixels.getRGB((i%2*2+1)*width[0]/4,(i/2*2+1)*height[0]/4)&0xffffff)==corners[i],"direct source orientation/channels");
                require((pixels.getRGB(width[0]/2,height[0]/2)&0xffffff)==(red<<16|green<<8|blue),"stale direct source frame");
                float[] depth={0};glReadPixels(sourceWidth/2,sourceHeight/2,1,1,GL_DEPTH_COMPONENT,GL_FLOAT,depth);
                require(Math.abs(depth[0]-(.25+frame*.01))<.00001,"presentation changed shared depth");
            }
            require(legacyFault.getAndSet(0)==1,"direct path called compatibility importer");
            long[] stats=backend.presentationStats();
            require(stats[0]-before[0]==16&&stats[1]==before[1]&&stats[2]==before[2],"direct source used a copy/intermediate image");

            // Force a command-recording failure after the source was released, before any presentation command was submitted.
            long frames=backend.frames();backend.beginFrame(800,500);
            backend.gui(new Object[]{new Object[]{new int[0]}});
            try {backend.present(framebuffer,sourceWidth,sourceHeight,800,500);throw new AssertionError("invalid GUI packet was accepted");}
            catch(IndexOutOfBoundsException expected) { }
            require(backend.frames()==frames,"failed direct frame was counted");
            // GL must be able to use the source immediately; an unsignaled ready semaphore would hang this read.
            glClearColor(1,0,1,1);glClear(GL_COLOR_BUFFER_BIT);glFinish();
            backend.beginFrame(800,500);backend.captureNext(output.resolve("direct-recovered.png").toString());
            backend.present(framebuffer,sourceWidth,sourceHeight,800,500);
            var recovered=ImageIO.read(output.resolve("direct-recovered.png").toFile());
            require((recovered.getRGB(400,250)&0xffffff)==0xff00ff,"direct source lease did not recover");

            // Hand and GUI still draw after the sampled scene and retain their independent depth ordering.
            backend.beginFrame(800,500);
            var hand=GuiPassSmoke.quad(FORMAT_POSITION_COLOR,4,DEPTH_TEST|DEPTH_WRITE,0,0xff0000ff,-.5f);
            var gui=GuiPassSmoke.quad(FORMAT_POSITION_COLOR,4,DEPTH_TEST|DEPTH_WRITE,0,0xffff0000,.5f);
            hand[4]=new int[]{0,0,800,500};gui[4]=new int[]{400,0,400,500};
            backend.hand(new Object[]{hand});backend.gui(new Object[]{gui});
            backend.captureNext(output.resolve("direct-hand-gui.png").toString());
            backend.present(framebuffer,sourceWidth,sourceHeight,800,500);
            var overlay=ImageIO.read(output.resolve("direct-hand-gui.png").toFile());
            require((overlay.getRGB(200,250)&0xffffff)==0xff0000&&(overlay.getRGB(600,250)&0xffffff)==0x0000ff,"direct scene/hand/GUI order");
            frames=backend.frames();backend.beginFrame(800,500);backend.present(framebuffer,sourceWidth,sourceHeight,0,0);
            require(backend.frames()==frames,"zero extent consumed source frame");
            backend.present(framebuffer,sourceWidth,sourceHeight,800,500);
            require(backend.presentationStats()[1]==before[1]&&backend.presentationStats()[2]==before[2],"direct recovery allocated compatibility storage");
        } finally {
            legacyFault.set(0);backend.destroyRenderTarget(framebuffer);
            glBindFramebuffer(GL_FRAMEBUFFER,0);glDeleteFramebuffers(framebuffer);glfwSetWindowSize(window,640,480);glfwPollEvents();
        }
        require(glGetError()==GL_NO_ERROR,"GL error in direct source presentation");
        System.out.println("SHARED_SOURCE_PRESENT_SMOKE=OK (no GL copy/Vulkan blit/intermediate images, orientation, fresh pixels, scaling, depth preserved, both slots, resize, hand/GUI, failure recovery, zero extent)");
    }
    private static void attach(MainWindowBackend backend,int framebuffer,int width,int height) {
        int[] textures=backend.createRenderTarget(framebuffer,width,height);glBindFramebuffer(GL_FRAMEBUFFER,framebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,textures[0],0);
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,textures[1],0);
        require(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE,"shared source framebuffer incomplete");
    }
    private static void clear(int x,int y,int width,int height,float r,float g,float b) {glScissor(x,y,width,height);glClearColor(r,g,b,1);glClear(GL_COLOR_BUFFER_BIT);}
    private static void require(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
}
