package github.com.gengyoubo.vulkanforge.client;

import com.mojang.blaze3d.font.SheetGlyphInfo;
import static org.lwjgl.opengl.GL11.*;

/** FontTexture.add calls this after binding its page. Notify after the glyph's actual upload, including non-NativeImage providers. */
public final class FontUploads {
    private FontUploads() { }
    public static void upload(SheetGlyphInfo glyph, int x, int y) {
        int texture=glGetInteger(GL_TEXTURE_BINDING_2D);long before=CpuTextures.version(texture);
        glyph.upload(x, y);
        if(before!=0&&before==CpuTextures.version(texture))CpuTextures.unknown(texture);
        if (NativeMainWindow.active()) EntityTextures.dirty(glGetInteger(GL_TEXTURE_BINDING_2D));
    }
}
