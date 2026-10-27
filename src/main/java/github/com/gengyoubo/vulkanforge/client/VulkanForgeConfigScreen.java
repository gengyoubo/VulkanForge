package github.com.gengyoubo.vulkanforge.client;

import github.com.gengyoubo.vulkanforge.config.VulkanForgeConfig;
import github.com.gengyoubo.vulkanforge.config.VulkanForgeConfig.*;
import dev.vulkanforge.bridge.RenderMode;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/** Mods list "Config" screen. Edits a copy of the values; Done writes them to vulkanforge-client.toml. */
public final class VulkanForgeConfigScreen extends Screen {
    private final Screen parent;
    private RenderMode mode = VulkanForgeConfig.MODE.get();
    private VulkanVersion vulkanVersion = VulkanForgeConfig.VULKAN_VERSION.get();
    private OpenGLVersion openGLVersion = VulkanForgeConfig.OPENGL_VERSION.get();
    private final Map<NvidiaExtension, Boolean> extensions = new EnumMap<>(NvidiaExtension.class);
    private CycleButton<VulkanVersion> vulkanButton;
    private CycleButton<OpenGLVersion> openGLButton;
    private final List<CycleButton<Boolean>> extensionButtons = new ArrayList<>();
    private int extensionLabelY;
    private boolean restarting;
    private Button restartButton;

    public VulkanForgeConfigScreen(Screen parent) {
        super(Component.translatable("vulkanforge.config.title"));
        this.parent = parent;
        VulkanForgeConfig.NVIDIA_EXTENSIONS.forEach((extension, value) -> extensions.put(extension, value.get()));
    }

    /** Called from the mod constructor on the client only. */
    public static void register(FMLJavaModLoadingContext context) {
        context.registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((minecraft, parent) -> new VulkanForgeConfigScreen(parent)));
    }

    @Override protected void init() {
        int column = 150, gap = 10, left = width / 2 - column - gap / 2, right = width / 2 + gap / 2, y = 38;
        addRenderableWidget(CycleButton.<RenderMode>builder(value -> Component.translatable("vulkanforge.config.mode." + value.name().toLowerCase(Locale.ROOT)))
                .withValues(RenderMode.values()).withInitialValue(mode)
                .withTooltip(value->Tooltip.create(Component.translatable("vulkanforge.config.mode."+value.name().toLowerCase(Locale.ROOT)+".tooltip")))
                .create(left, y, column * 2 + gap, 20, Component.translatable("vulkanforge.config.mode"), (button, value) -> { mode = value; refreshActive(); }));
        y += 24;
        vulkanButton = addRenderableWidget(CycleButton.<VulkanVersion>builder(value -> versionLabel(Component.literal(value.label), value.implemented))
                .withValues(VulkanVersion.values()).withInitialValue(vulkanVersion)
                .create(left, y, column, 20, Component.translatable("vulkanforge.config.vulkan_version"), (button, value) -> vulkanVersion = value));
        openGLButton = addRenderableWidget(CycleButton.<OpenGLVersion>builder(value -> versionLabel(value == OpenGLVersion.DEFAULT
                        ? Component.translatable("vulkanforge.config.opengl_version.default") : Component.literal(value.label), value.implemented))
                .withValues(OpenGLVersion.values()).withInitialValue(openGLVersion)
                .create(right, y, column, 20, Component.translatable("vulkanforge.config.opengl_version"), (button, value) -> openGLVersion = value));
        extensionLabelY = y + 28;
        y += 40;
        extensionButtons.clear();
        NvidiaExtension[] all = NvidiaExtension.values();
        for (int i = 0; i < all.length; i++) {
            NvidiaExtension extension = all[i];
            String key = "vulkanforge.config.nvidia." + extension.key();
            extensionButtons.add(addRenderableWidget(CycleButton.onOffBuilder(extensions.get(extension))
                    .withTooltip(value -> Tooltip.create(Component.literal(extension.extensionName + "\n").append(Component.translatable(key + ".tooltip"))))
                    .create(i % 2 == 0 ? left : right, y + i / 2 * 24, column, 20, Component.translatable(key), (button, value) -> extensions.put(extension, value))));
        }
        var restart = restartButton = addRenderableWidget(Button.builder(Component.translatable("vulkanforge.config.restart"), button -> restart())
                .bounds(left, height - 26, 100, 20).build());
        restart.active = minecraft.level == null;
        restart.setTooltip(Tooltip.create(Component.translatable(restart.active
                ? "vulkanforge.config.restart.tooltip" : "vulkanforge.config.restart.in_world")));
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> { save(); onClose(); })
                .bounds(left + 105, height - 26, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                .bounds(left + 210, height - 26, 100, 20).build());
        refreshActive();
    }

    private static Component versionLabel(Component label, boolean implemented) {
        return implemented ? label : label.copy().append(Component.translatable("vulkanforge.config.unimplemented"));
    }

    private void refreshActive() {
        boolean vulkan = mode.usesVulkan();
        vulkanButton.active = vulkan; openGLButton.active = mode==RenderMode.GL;
        for (var button : extensionButtons) button.active = vulkan;
        if(restartButton!=null){restartButton.active=minecraft.level==null&&mode!=RenderMode.VK;restartButton.setTooltip(Tooltip.create(Component.translatable(mode==RenderMode.VK?"vulkanforge.config.mode.vk.tooltip":minecraft.level!=null?"vulkanforge.config.restart.in_world":"vulkanforge.config.restart.tooltip")));}
    }

    private void save() {
        VulkanForgeConfig.MODE.set(mode);
        VulkanForgeConfig.VULKAN_VERSION.set(vulkanVersion);
        VulkanForgeConfig.OPENGL_VERSION.set(openGLVersion);
        extensions.forEach((extension, value) -> VulkanForgeConfig.NVIDIA_EXTENSIONS.get(extension).set(value));
        VulkanForgeConfig.SPEC.save();
    }

    private void restart() {
        if (restarting || minecraft.level != null || mode==RenderMode.VK) return;
        try { save(); }
        catch (RuntimeException error) { restartFailed(error, false); return; }
        restarting = true;
        minecraft.setScreen(new GenericDirtMessageScreen(Component.translatable("vulkanforge.config.restarting")));
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                GameRestart.prepare(minecraft.gameDirectory.toPath().resolve("logs/vulkanforge/restart.log"));
                minecraft.execute(minecraft::stop);
            } catch (Exception | LinkageError error) {
                minecraft.execute(() -> restartFailed(error, true));
            }
        });
    }

    private void restartFailed(Throwable error, boolean saved) {
        restarting = false;
        // Launcher arguments can contain credentials, so do not log the exception or launch command.
        github.com.gengyoubo.vulkanforge.VulkanForge.LOGGER.warn("Game restart could not be prepared ({})", error.getClass().getSimpleName());
        minecraft.setScreen(new AlertScreen(() -> minecraft.setScreen(this),
                Component.translatable("vulkanforge.config.restart.failed"),
                Component.translatable(saved ? "vulkanforge.config.restart.manual" : "vulkanforge.config.save.failed")));
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xffffff);
        graphics.drawCenteredString(font, Component.translatable("vulkanforge.config.pending", VulkanForgeConfig.startup().toString()), width / 2, 24, 0xffaa55);
        graphics.drawCenteredString(font, Component.translatable("vulkanforge.config.nvidia"), width / 2, extensionLabelY, mode.usesVulkan() ? 0xa0c8ff : 0x707070);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
}
