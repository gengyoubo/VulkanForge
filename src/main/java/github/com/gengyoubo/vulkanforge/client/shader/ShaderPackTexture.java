package github.com.gengyoubo.vulkanforge.client.shader;

import github.com.gengyoubo.vulkanforge.shaderpack.ShaderPackTextures;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;

/** Immutable sampled image (custom pack texture or noisetex), uploaded once per compiled graph. */
final class ShaderPackTexture implements AutoCloseable {
    final int texture;
    private boolean closed;
    ShaderPackTexture(ShaderPackTextures.Image image){
        var pixels=MemoryUtil.memAlloc(image.width()*image.height()*4);int handle=0;
        try{
            // Top row first, like the reference loaders: texcoord y=0 samples the image's first row.
            pixels.put(image.rgba()).flip();
            handle=glGenTextures();glBindTexture(GL_TEXTURE_2D,handle);glPixelStorei(GL_UNPACK_ALIGNMENT,4);glPixelStorei(GL_UNPACK_ROW_LENGTH,0);glPixelStorei(GL_UNPACK_SKIP_ROWS,0);glPixelStorei(GL_UNPACK_SKIP_PIXELS,0);glTexImage2D(GL_TEXTURE_2D,0,org.lwjgl.opengl.GL11.GL_RGBA8,image.width(),image.height(),0,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
            int filter=image.blur()?GL_LINEAR:GL_NEAREST,wrap=image.clamp()?GL_CLAMP_TO_EDGE:GL_REPEAT;
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,filter);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,filter);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,wrap);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,wrap);
            texture=handle;handle=0;
        }finally{MemoryUtil.memFree(pixels);if(handle!=0)glDeleteTextures(handle);}
    }
    @Override public void close(){if(closed)return;closed=true;glDeleteTextures(texture);}
}
