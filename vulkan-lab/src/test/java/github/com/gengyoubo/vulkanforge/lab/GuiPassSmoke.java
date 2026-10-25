package github.com.gengyoubo.vulkanforge.lab;

import java.nio.*;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import static github.com.gengyoubo.vulkanforge.lab.EntityPass.*;

/** Pixels read from the final swapchain, with no world renderer: painter order, alpha, scissor, depth clear and font patches. */
final class GuiPassSmoke {
    static void run(MainWindowBackend backend, int source, Path output) throws Exception {
        backend.beginFrame(640, 480);
        backend.entityTexture(new Object[]{9001, 2, 2, 0, new byte[]{-1,0,0,-1, -1,0,0,-1, -1,0,0,-1, -1,0,0,-1}});
        Object[] red = quad(FORMAT_POSITION_COLOR, 4, DEPTH_TEST | DEPTH_WRITE, 0, 0xff0000ff, -.5f);
        Object[] behind = quad(FORMAT_POSITION_COLOR, 4, DEPTH_TEST | DEPTH_WRITE, 0, 0xffff0000, .5f);
        Object[] alpha = quad(FORMAT_POSITION_COLOR, 4, BLEND, 0, 0x8000ff00, 0);
        alpha[4] = new int[]{0, 0, 320, 480};
        backend.gui(new Object[]{red, behind, alpha});
        Path first = output.resolve("gui-alpha-depth.png"); backend.captureNext(first.toString()); backend.present(source, 320, 240, 640, 480);
        var image = ImageIO.read(first.toFile());
        near(image.getRGB(160, 240), 127, 128, 0, "GUI alpha/scissor");
        near(image.getRGB(480, 240), 255, 0, 0, "GUI depth occlusion / scissor");
        // Patch one texel while prior frame resources may still be referenced. The untouched texel must remain red.
        backend.beginFrame(640, 480);
        backend.entityTexturePatch(new Object[]{9001, 0, 0, 1, 1, new byte[]{0,-1,0,-1}});
        Object[] patch = quad(FORMAT_POSITION_TEX, 4, 0, 9001, -1, 0);
        byte[] vertices = (byte[]) patch[2]; var data = ByteBuffer.wrap(vertices).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 4; i++) { data.putFloat(i * 20 + 12, .25f); data.putFloat(i * 20 + 16, .25f); }
        patch[4] = new int[]{0, 0, 320, 480};
        Object[] untouched = quad(FORMAT_POSITION_TEX, 4, 0, 9001, -1, 0);
        backend.gui(new Object[]{red, new Object[]{null, new int[]{0,0,640,480}}, behind, untouched, patch});
        Path second = output.resolve("gui-patch-clear.png"); backend.captureNext(second.toString()); backend.present(source, 320, 240, 640, 480);
        image = ImageIO.read(second.toFile());
        near(image.getRGB(160, 240), 0, 255, 0, "Incremental glyph texel patch");
        near(image.getRGB(480, 240), 255, 0, 0, "Patch must preserve other texels");
        backend.beginFrame(640, 480);
        backend.gui(new Object[]{red, new Object[]{null, new int[]{0,0,640,480}}, behind});
        Path third = output.resolve("gui-depth-clear.png"); backend.captureNext(third.toString()); backend.present(source, 320, 240, 640, 480);
        image = ImageIO.read(third.toFile());
        near(image.getRGB(320, 240), 0, 0, 255, "HUD -> screen depth clear");
        if (backend.guiPasses() < 3) throw new AssertionError("GUI did not draw after blit");
        System.out.println("GUI_SWAPCHAIN_SMOKE=OK (alpha, scissor, depth, depth clear, incremental texture patch, no world renderer)");
    }
    private static Object[] quad(int format, int variant, int flags, int texture, int color, float z) {
        int stride = format == FORMAT_POSITION_TEX ? 20 : 16;
        var bytes = ByteBuffer.allocate(stride * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float[] xy : new float[][]{{-.9f,-.9f},{.9f,-.9f},{.9f,.9f},{-.9f,.9f}}) {
            bytes.putFloat(xy[0]).putFloat(xy[1]).putFloat(z);
            if (format == FORMAT_POSITION_TEX) bytes.putFloat(.75f).putFloat(.75f); else bytes.putInt(color);
        }
        int[] header = {format, variant, flags, texture, texture, 4, MODE_QUADS, 0, 0};
        float[] uniforms = new float[64];
        for (int i = 0; i < 4; i++) { uniforms[i * 5] = uniforms[16 + i * 5] = 1; uniforms[44 + i] = 1; }
        uniforms[60] = 1e9f; uniforms[61] = 2e9f; uniforms[63] = variant;
        return new Object[]{header, uniforms, bytes.array(), null, new int[]{0,0,640,480}};
    }
    private static void near(int pixel, int r, int g, int b, String label) {
        if (Math.abs((pixel >> 16 & 255) - r) > 2 || Math.abs((pixel >> 8 & 255) - g) > 2 || Math.abs((pixel & 255) - b) > 2)
            throw new AssertionError(label + ": " + Integer.toHexString(pixel));
    }
}
