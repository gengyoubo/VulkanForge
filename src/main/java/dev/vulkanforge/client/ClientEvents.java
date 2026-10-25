package dev.vulkanforge.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.vulkanforge.VulkanForge;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = VulkanForge.MOD_ID, value = Dist.CLIENT)
public final class ClientEvents {
    private static final KeyMapping TRIANGLE = new KeyMapping("key.vulkanforge.triangle",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, "key.categories.vulkanforge");

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            while (TRIANGLE.consumeClick()) launchTriangle();
        }
    }

    @SubscribeEvent
    public static void screenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof TitleScreen screen) {
            event.addListener(Button.builder(Component.translatable("vulkanforge.triangle"), b -> launchTriangle())
                    .bounds(6, screen.height - 30, 150, 20).build());
        }
    }

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
        public static void keys(RegisterKeyMappingsEvent event) { event.register(TRIANGLE); }

        @SubscribeEvent
        public static void setup(FMLClientSetupEvent event) { event.enqueueWork(LabLauncher::probe); }
    }
}
