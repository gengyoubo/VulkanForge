package github.com.gengyoubo.vulkanforge.client;

import com.mojang.blaze3d.platform.InputConstants;
import github.com.gengyoubo.vulkanforge.VulkanForge;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
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
        }
    }

    @SubscribeEvent
    public static void screenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof TitleScreen screen) {
            event.addListener(Button.builder(Component.translatable("vulkanforge.preview"), b -> VulkanPreview.toggle())
                    .bounds(6, screen.height - 30, 150, 20).build());
        }
    }

    @SubscribeEvent
    public static void renderScreen(ScreenEvent.Render.Post event) { VulkanPreview.render(event.getGuiGraphics()); }

    @SubscribeEvent
    public static void terrain(RenderLevelStageEvent event) {
        // This stage carries the view pose; AFTER_LEVEL in 1.20.1 carries a different pose stack.
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) VulkanPreview.terrain(event);
    }

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
    public static void shutdown(GameShuttingDownEvent event) { VulkanPreview.close(); }

    private static void launchTriangle() {
        boolean started = LabLauncher.triangle();
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.displayClientMessage(Component.translatable(
                    started ? "vulkanforge.launching" : "vulkanforge.busy"), false);
        }
    }

    @Mod.EventBusSubscriber(modid = VulkanForge.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Lifecycle {
        @SubscribeEvent
        public static void keys(RegisterKeyMappingsEvent event) { event.register(PREVIEW); event.register(TRIANGLE); event.register(INSET); }

        @SubscribeEvent
        public static void reload(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener((ResourceManagerReloadListener) resources -> VulkanPreview.invalidateWorld());
        }

        @SubscribeEvent
        public static void setup(FMLClientSetupEvent event) { event.enqueueWork(LabLauncher::probe); }
    }
}
