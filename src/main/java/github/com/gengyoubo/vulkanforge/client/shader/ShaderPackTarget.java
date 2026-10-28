package github.com.gengyoubo.vulkanforge.client.shader;

import java.nio.ByteBuffer;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/** Private ping-pong image; the native adapter gives the same logical handles private Vulkan storage. */
final class ShaderPackTarget implements AutoCloseable {
    final int framebuffer,texture,width,height;
    private boolean closed;
    ShaderPackTarget(int width,int height){
        this.width=width;this.height=height;int image=0,target=0;
        try{
            image=glGenTextures();glBindTexture(GL_TEXTURE_2D,image);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,width,height,0,GL_RGBA,GL_UNSIGNED_BYTE,(ByteBuffer)null);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
            target=glGenFramebuffers();glBindFramebuffer(GL_FRAMEBUFFER,target);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,image,0);
            if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Incomplete shader pack framebuffer");framebuffer=target;texture=image;
        }catch(Throwable error){if(image!=0)glDeleteTextures(image);if(target!=0)glDeleteFramebuffers(target);throw error;}
    }
    @Override public void close(){if(closed)return;closed=true;glDeleteTextures(texture);glDeleteFramebuffers(framebuffer);}
}
