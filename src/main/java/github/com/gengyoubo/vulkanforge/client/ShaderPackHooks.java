package github.com.gengyoubo.vulkanforge.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import github.com.gengyoubo.vulkanforge.client.shader.ShaderPackRuntime;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;

/** Bytecode hook targets (coremods/shader_packs.js). Each is the identity when no world-program pack is running. */
public final class ShaderPackHooks {
    private ShaderPackHooks(){ }
    /** Boundary after world + hand rendering and before GUI; ordered captures must be flushed first. */
    public static void afterLevel(float partialTick){if(!ShaderPackRuntime.active())return;EntityDraws.flushCurrent();ShaderPackRuntime.renderWorldEnd(partialTick);}
    /** Head of LevelRenderer.renderLevel. */
    public static void levelBegin(PoseStack pose,float partialTick,Matrix4f projection){ShaderPackRuntime.levelBegin(pose,partialTick,projection);}
    /** Return value of every GameRenderer core-shader getter. */
    public static ShaderInstance shader(ShaderInstance vanilla){return ShaderPackRuntime.shader(vanilla);}
    /**
     * Replaces the depth clear before the hand. World programs share one depth image whose contents depthtex0 exposes to
     * composite passes, so it keeps the world depth; the hand is then depth-tested against the world.
     */
    public static void clearHandDepth(int mask,boolean checkError){if(!ShaderPackRuntime.worldFrame())RenderSystem.clear(mask,checkError);}
}
