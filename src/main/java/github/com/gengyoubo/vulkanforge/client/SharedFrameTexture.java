package github.com.gengyoubo.vulkanforge.client;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;

/** Texture-manager name only. The native backend owns and destroys the imported GL texture. */
final class SharedFrameTexture extends AbstractTexture {
    private int external;
    void use(int texture) { external = texture; }
    @Override public int getId() { return external; }
    @Override public void load(ResourceManager resources) { }
    @Override public void close() { external = 0; }
}
