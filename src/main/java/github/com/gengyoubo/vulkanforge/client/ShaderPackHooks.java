package github.com.gengyoubo.vulkanforge.client;

import github.com.gengyoubo.vulkanforge.client.shader.ShaderPackRuntime;

/** Boundary after world + hand rendering and before GUI; ordered captures must be flushed first. */
public final class ShaderPackHooks {
    private ShaderPackHooks(){ }
    public static void afterLevel(float partialTick){if(!ShaderPackRuntime.active())return;EntityDraws.flushCurrent();ShaderPackRuntime.renderWorldEnd(partialTick);}
}
