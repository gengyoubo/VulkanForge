package github.com.gengyoubo.vulkanforge.client;

import github.com.gengyoubo.vulkanforge.VulkanForge;
import dev.vulkanforge.bridge.RenderMode;
import github.com.gengyoubo.vulkanforge.config.VulkanForgeConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import java.nio.file.*;

/** Opt-in (-PconfigSmoke): Mods list config factory, widgets, Done -> TOML round trip. Never packaged. */
@Mod.EventBusSubscriber(modid = VulkanForge.MOD_ID, value = Dist.CLIENT)
public final class ConfigScreenSmoke {
    private static int stage, ticks;
    private static RenderMode original;

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (!Boolean.getBoolean("vulkanforge.configSmoke") || event.phase != TickEvent.Phase.END || stage == 99) return;
        Minecraft mc = Minecraft.getInstance();
        Path report = mc.gameDirectory.toPath().resolve("logs/vulkanforge/config-smoke.txt");
        Path restartMarker = report.resolveSibling("config-restart-smoke.pending");
        try {
            if (stage == 0 && mc.getOverlay() == null && mc.screen instanceof TitleScreen title) {
                if (Boolean.getBoolean("vulkanforge.restartClientSmoke") && Files.exists(restartMarker)) {
                    String[] state = Files.readString(restartMarker).split("\\n");
                    var expected = RenderMode.valueOf(state[1]);
                    if (VulkanForgeConfig.startup().mode() != expected) throw new AssertionError("Restart did not apply " + expected);
                    VulkanForgeConfig.MODE.set(RenderMode.valueOf(state[0]));
                    VulkanForgeConfig.SPEC.save();
                    Files.delete(restartMarker);
                    Files.writeString(report, "PASS config factory, widgets, Done saved TOML, Save and restart relaunched Minecraft with " + expected + "\nSTATUS=OK\n");
                    stage = 99; mc.stop(); return;
                }
                original = VulkanForgeConfig.MODE.get();
                var info = ModList.get().getModContainerById(VulkanForge.MOD_ID).orElseThrow().getModInfo();
                // Same lookup the Mods list uses to enable its "Config" button.
                var screen = ConfigScreenHandler.getScreenFactoryFor(info).orElseThrow(() -> new AssertionError("No config screen registered")).apply(mc, title);
                if (!(screen instanceof VulkanForgeConfigScreen)) throw new AssertionError("Unexpected config screen " + screen);
                mc.setScreen(screen); stage = 1; ticks = 0;
            } else if (stage == 1 && ++ticks > 20) {
                var widgets = mc.screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).toList();
                int expected = expected();
                if (widgets.size() != expected) throw new AssertionError("Expected " + expected + " widgets, found " + widgets.size());
                net.minecraft.client.Screenshot.grab(mc.gameDirectory, "vulkanforge-config.png", mc.getMainRenderTarget(), message -> { });
                // Cycle the backend through the screen itself, then press Done.
                CycleButton<?> backend = (CycleButton<?>) widgets.get(0);
                backend.onPress();
                if(Boolean.getBoolean("vulkanforge.restartClientSmoke") && backend.getValue()==RenderMode.VK)backend.onPress();
                widgets.get(widgets.size() - 2).onClick(0, 0);
                stage = 2; ticks = 0;
            } else if (stage == 2 && ++ticks > 5) {
                if (VulkanForgeConfig.MODE.get() == original) throw new AssertionError("Done did not store the new backend");
                String toml = Files.readString(FMLPaths.CONFIGDIR.get().resolve("vulkanforge-client.toml"));
                if (!toml.contains("mode = \"" + VulkanForgeConfig.MODE.get().name() + "\"")) throw new AssertionError("TOML not saved:\n" + toml);
                if (!(mc.screen instanceof TitleScreen)) throw new AssertionError("Done did not return to the parent screen");
                if (Boolean.getBoolean("vulkanforge.restartClientSmoke")) {
                    Files.createDirectories(report.getParent());
                    Files.writeString(restartMarker, original.name() + "\n" + VulkanForgeConfig.MODE.get().name());
                    mc.setScreen(new VulkanForgeConfigScreen(mc.screen));
                    var restart = mc.screen.children().stream().filter(AbstractWidget.class::isInstance)
                            .map(AbstractWidget.class::cast).toList().get(expected() - 3);
                    if (!restart.active) throw new AssertionError("Restart disabled on main menu");
                    restart.onClick(0, 0);
                    stage = 3; ticks = 0; return;
                }
                VulkanForgeConfig.MODE.set(original); VulkanForgeConfig.SPEC.save();
                Files.createDirectories(report.getParent());
                Files.writeString(report, "PASS config factory, " + expected() + " widgets, Done saved backend to TOML and returned to parent\nSTATUS=OK\n");
                stage = 99; mc.stop();
            } else if (stage == 3 && ++ticks > 600) {
                throw new AssertionError("Save and restart did not stop the game");
            }
        } catch (Throwable error) {
            VulkanForge.LOGGER.error("Config screen smoke failed at stage " + stage, error);
            try { Files.createDirectories(report.getParent()); Files.writeString(report, "STATUS=FAILED " + error + "\n"); } catch (Exception ignored) { }
            stage = 99; mc.stop();
        }
    }
    /** backend + Vulkan/OpenGL versions + NVIDIA extensions + Restart + Done + Cancel. */
    private static int expected() { return 3 + VulkanForgeConfig.NvidiaExtension.values().length + 3; }
}
