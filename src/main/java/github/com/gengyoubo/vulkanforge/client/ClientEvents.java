package github.com.gengyoubo.vulkanforge.client;

import com.mojang.blaze3d.platform.InputConstants;
import github.com.gengyoubo.vulkanforge.VulkanForge;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = VulkanForge.MOD_ID, value = Dist.CLIENT)
public final class ClientEvents {
    @SubscribeEvent public static void shaderPackScreen(ScreenEvent.Init.Post event){String name=event.getScreen().getClass().getName();if(event.getScreen() instanceof net.minecraft.client.gui.screens.VideoSettingsScreen||name.endsWith(".SodiumOptionsGUI"))event.addListener(net.minecraft.client.gui.components.Button.builder(Component.translatable("vulkanforge.shaders.title"),button->Minecraft.getInstance().setScreen(new github.com.gengyoubo.vulkanforge.client.shader.ShaderPackScreen(event.getScreen()))).bounds(event.getScreen().width-100,3,96,20).build());}
    @SubscribeEvent public static void shaderPackFrame(TickEvent.RenderTickEvent event){if(event.phase==TickEvent.Phase.START)github.com.gengyoubo.vulkanforge.client.shader.ShaderPackRuntime.beginFrame();}
    private static final KeyMapping PREVIEW = new KeyMapping("key.vulkanforge.preview",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, "key.categories.vulkanforge");
    private static final KeyMapping TRIANGLE = new KeyMapping("key.vulkanforge.triangle",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F9, "key.categories.vulkanforge");
    private static final KeyMapping INSET = new KeyMapping("key.vulkanforge.inset",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F10, "key.categories.vulkanforge");

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            while (PREVIEW.consumeClick()) VulkanPreview.toggle();
            while (TRIANGLE.consumeClick()) launchTriangle();
            while (INSET.consumeClick()) VulkanPreview.toggleInset();
            if(TailDiagnostics.ENABLED&&TailDiagnostics.selected().equals("D")&&VulkanPreview.directWorld())VulkanPreview.toggle();
        }
    }

    @SubscribeEvent
    public static void renderScreen(ScreenEvent.Render.Post event) { VulkanPreview.render(event.getGuiGraphics()); }

    @SubscribeEvent
    public static void terrain(RenderLevelStageEvent event) {
        // This stage carries the view pose; AFTER_LEVEL in 1.20.1 carries a different pose stack.
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {VulkanPreview.skyEnd(event);if(github.com.gengyoubo.vulkanforge.client.shader.ShaderPackRuntime.active())github.com.gengyoubo.vulkanforge.client.shader.ShaderPackRuntime.matrices(new org.joml.Matrix4f(com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix()),new org.joml.Matrix4f(event.getPoseStack().last().pose()));}
        else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) VulkanPreview.handBegin();
        else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS) VulkanPreview.terrain(event);
        else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) VulkanPreview.entities();
        else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) VulkanPreview.translucent();
        else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) VulkanPreview.particlesBegin();
        else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES) VulkanPreview.particlesEnd();
        else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_WEATHER) VulkanPreview.weather();
    }

    /** Fired by FogRenderer.setupColor right before LevelRenderer clears and renders the sky (P10). */
    @SubscribeEvent
    public static void skyFog(net.minecraftforge.client.event.ViewportEvent.ComputeFogColor event) { VulkanPreview.skyBegin(); }

    @SubscribeEvent
    public static void screenKey(ScreenEvent.KeyPressed.Pre event) {
        if (PREVIEW.matches(event.getKeyCode(), event.getScanCode())) {
            VulkanPreview.toggle();
            event.setCanceled(true);
        } else if (TRIANGLE.matches(event.getKeyCode(), event.getScanCode())) {
            launchTriangle();
            event.setCanceled(true);
        } else if (INSET.matches(event.getKeyCode(), event.getScanCode())) {
            VulkanPreview.toggleInset(); event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void renderHud(RenderGuiEvent.Post event) {
        if (Minecraft.getInstance().screen == null) VulkanPreview.render(event.getGuiGraphics());
    }

    @SubscribeEvent
    public static void shutdown(GameShuttingDownEvent event) { github.com.gengyoubo.vulkanforge.client.shader.ShaderPackRuntime.close();VulkanPreview.close(); }

    private static void launchTriangle() {
        boolean started = LabLauncher.triangle();
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.displayClientMessage(Component.translatable(
                    started ? "vulkanforge.launching" : "vulkanforge.busy"), false);
        }
    }

    @Mod.EventBusSubscriber(modid = VulkanForge.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Lifecycle {
        @SubscribeEvent public static void shaders(net.minecraftforge.client.event.RegisterShadersEvent event) throws java.io.IOException {
            event.registerShader(new net.minecraft.client.renderer.ShaderInstance(event.getResourceProvider(),
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(VulkanForge.MOD_ID, "terrain_composite"),
                    com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX), TerrainPresenter::shader);
            event.registerShader(new net.minecraft.client.renderer.ShaderInstance(event.getResourceProvider(),
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(VulkanForge.MOD_ID, "terrain_translucent"),
                    com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX), TerrainPresenter::translucentShader);
        }
        @SubscribeEvent
        public static void keys(RegisterKeyMappingsEvent event) { event.register(PREVIEW); event.register(TRIANGLE); event.register(INSET); }

        @SubscribeEvent
        public static void reload(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener((ResourceManagerReloadListener) resources -> { VulkanPreview.invalidateWorld(); PostEffects.reset(); });
        }

        @SubscribeEvent
        public static void setup(FMLClientSetupEvent event) {
            // The OpenGL backend never creates a Vulkan device; F9's standalone window still probes on demand.
            if (!NativeGameWindow.enabled() && github.com.gengyoubo.vulkanforge.config.VulkanForgeConfig.startup().mode().usesVulkan()) event.enqueueWork(LabLauncher::probe);
        }
    }
}
