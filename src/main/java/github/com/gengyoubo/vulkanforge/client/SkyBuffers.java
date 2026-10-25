package github.com.gengyoubo.vulkanforge.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * P10: LevelRenderer's sky disc, star field, dark disc and cloud buffers are static VertexBuffers that GL draws with their own
 * matrices. coremods/entities.js redirects only LevelRenderer's VertexBuffer.upload/drawWithShader calls here, so CPU copies are
 * kept for those few buffers - chunk sections and immediate draws upload elsewhere and are never copied.
 */
public final class SkyBuffers {
    record Mesh(VertexFormat format, VertexFormat.Mode mode, int vertexCount, byte[] vertices, byte[] indices, int indexCount, int indexSize) { }
    private static final Map<VertexBuffer, Mesh> MESHES = new WeakHashMap<>();
    private SkyBuffers() { }

    public static void upload(VertexBuffer buffer, BufferBuilder.RenderedBuffer rendered) {
        BufferBuilder.DrawState state = rendered.drawState();
        if (state.indexOnly()) MESHES.remove(buffer);
        else MESHES.put(buffer, new Mesh(state.format(), state.mode(), state.vertexCount(), copy(rendered.vertexBuffer()),
                state.sequentialIndex() ? null : copy(rendered.indexBuffer()), state.indexCount(), state.indexType().bytes));
        buffer.upload(rendered);
    }

    public static void draw(VertexBuffer buffer, Matrix4f modelView, Matrix4f projection, ShaderInstance shader) {
        Mesh mesh = MESHES.get(buffer);
        if (mesh == null || !EntityDraws.drawMesh(mesh, modelView, projection, shader)) buffer.drawWithShader(modelView, projection, shader);
    }

    private static byte[] copy(java.nio.ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.duplicate().get(bytes);
        return bytes;
    }
}
