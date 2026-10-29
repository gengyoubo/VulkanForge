package github.com.gengyoubo.vulkanforge.client.shader;

import java.nio.ByteBuffer;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.*;

/** Private colortex image with its own framebuffer; the native adapter gives the same logical handles private Vulkan storage. */
final class ShaderPackTarget implements AutoCloseable {
    final int framebuffer,texture,width,height;
    final String format;
    final boolean mipmapped;
    private boolean closed;
    ShaderPackTarget(int width,int height){this(width,height,"RGBA8",false);}
    ShaderPackTarget(int width,int height,String format,boolean mipmapped){
        this.width=width;this.height=height;this.format=format;this.mipmapped=mipmapped;int image=0,target=0;boolean integer=format.matches(".*\\dU?I");
        try{
            image=glGenTextures();glBindTexture(GL_TEXTURE_2D,image);glTexImage2D(GL_TEXTURE_2D,0,internal(format),width,height,0,pixels(format,integer),integer?(format.endsWith("UI")?GL_UNSIGNED_BYTE:GL_BYTE):GL_UNSIGNED_BYTE,(ByteBuffer)null);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,integer?GL_NEAREST:mipmapped?GL_LINEAR_MIPMAP_LINEAR:GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,integer?GL_NEAREST:GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
            target=glGenFramebuffers();glBindFramebuffer(GL_FRAMEBUFFER,target);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,image,0);glDrawBuffers(GL_COLOR_ATTACHMENT0);
            if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Incomplete shader pack framebuffer for "+format);framebuffer=target;texture=image;
        }catch(Throwable error){if(image!=0)glDeleteTextures(image);if(target!=0)glDeleteFramebuffers(target);throw error;}
    }
    /** Whether this image can stand in for a newly requested one: identical size, format and mip chain. */
    boolean matches(int width,int height,String format,boolean mipmapped){return !closed&&this.width==width&&this.height==height&&this.format.equals(format)&&this.mipmapped==mipmapped;}
    void clear(float[] color){glBindFramebuffer(GL_FRAMEBUFFER,framebuffer);glDrawBuffers(GL_COLOR_ATTACHMENT0);glViewport(0,0,width,height);glClearColor(color[0],color[1],color[2],color[3]);glClear(GL_COLOR_BUFFER_BIT);}
    /** Pack format names, as declared by {@code const int colortexNFormat}. */
    static int internal(String format){return switch(format){
        case "RGBA","RGBA8"->GL_RGBA8;case "RGB","RGB8"->GL_RGB8;case "RGBA16"->GL_RGBA16;case "RGBA16F"->GL_RGBA16F;case "RGB16F"->GL_RGB16F;case "RGBA32F"->GL_RGBA32F;case "RGB32F"->GL_RGB32F;
        case "R8"->GL_R8;case "R16"->GL_R16;case "RG8"->GL_RG8;case "RG16"->GL_RG16;case "R16F"->GL_R16F;case "R32F"->GL_R32F;case "RG16F"->GL_RG16F;case "RG32F"->GL_RG32F;
        case "R11F_G11F_B10F"->GL_R11F_G11F_B10F;case "RGB10_A2"->GL_RGB10_A2;case "RGB8_SNORM"->GL_RGB8_SNORM;case "RGB16_SNORM"->GL_RGB16_SNORM;case "RGBA8_SNORM"->GL_RGBA8_SNORM;case "RGBA16_SNORM"->GL_RGBA16_SNORM;
        case "R8I"->GL_R8I;case "R8UI"->GL_R8UI;case "R16I"->GL_R16I;case "R16UI"->GL_R16UI;case "R32I"->GL_R32I;case "R32UI"->GL_R32UI;
        case "RG8I"->GL_RG8I;case "RG8UI"->GL_RG8UI;case "RG16I"->GL_RG16I;case "RG16UI"->GL_RG16UI;case "RG32I"->GL_RG32I;case "RG32UI"->GL_RG32UI;
        case "RGBA8I"->GL_RGBA8I;case "RGBA8UI"->GL_RGBA8UI;case "RGBA16I"->GL_RGBA16I;case "RGBA16UI"->GL_RGBA16UI;case "RGBA32I"->GL_RGBA32I;case "RGBA32UI"->GL_RGBA32UI;
        default->throw new IllegalArgumentException("Unknown color format "+format);
    };}
    private static int pixels(String format,boolean integer){
        int channels=format.startsWith("RGBA")||format.equals("RGB10_A2")?4:format.startsWith("RGB")||format.equals("R11F_G11F_B10F")?3:format.startsWith("RG")?2:1;
        return switch(channels){case 4->integer?GL_RGBA_INTEGER:GL_RGBA;case 3->integer?GL_RGB_INTEGER:GL_RGB;case 2->integer?GL_RG_INTEGER:GL_RG;default->integer?GL_RED_INTEGER:GL_RED;};
    }
    @Override public void close(){if(closed)return;closed=true;glDeleteTextures(texture);glDeleteFramebuffers(framebuffer);}
}
