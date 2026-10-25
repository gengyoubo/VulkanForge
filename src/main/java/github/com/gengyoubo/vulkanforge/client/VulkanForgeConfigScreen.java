package github.com.gengyoubo.vulkanforge.client;

import github.com.gengyoubo.vulkanforge.config.VulkanForgeConfig;
import github.com.gengyoubo.vulkanforge.config.VulkanForgeConfig.*;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/** Mods list "Config" screen. Edits a copy of the values; Done writes them to vulkanforge-client.toml. */
public final class VulkanForgeConfigScreen extends Screen {
    private final Screen parent;
    private Backend backend = VulkanForgeConfig.BACKEND.get();
    private VulkanVersion vulkanVersion = VulkanForgeConfig.VULKAN_VERSION.get();
    private OpenGLVersion openGLVersion = VulkanForgeConfig.OPENGL_VERSION.get();
    private final Map<NvidiaExtension, Boolean> extensions = new EnumMap<>(NvidiaExtension.class);
    private CycleButton<VulkanVersion> vulkanButton;
    private CycleButton<OpenGLVersion> openGLButton;
    private final List<CycleButton<Boolean>> extensionButtons = new ArrayList<>();
    private int extensionLabelY;

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
        addRenderableWidget(CycleButton.<Backend>builder(value -> Component.translatable("vulkanforge.config.backend." + value.name().toLowerCase(Locale.ROOT)))
                .withValues(Backend.values()).withInitialValue(backend)
                .create(left, y, column * 2 + gap, 20, Component.translatable("vulkanforge.config.backend"), (button, value) -> { backend = value; refreshActive(); }));
        y += 24;
        vulkanButton = addRenderableWidget(CycleButton.<VulkanVersion>builder(value -> Component.literal(value.label))
                .withValues(VulkanVersion.values()).withInitialValue(vulkanVersion)
                .create(left, y, column, 20, Component.translatable("vulkanforge.config.vulkan_version"), (button, value) -> vulkanVersion = value));
        openGLButton = addRenderableWidget(CycleButton.<OpenGLVersion>builder(value -> value == OpenGLVersion.DEFAULT
                        ? Component.translatable("vulkanforge.config.opengl_version.default") : Component.literal(value.label))
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
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> { save(); onClose(); })
                .bounds(left, height - 26, column, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                .bounds(right, height - 26, column, 20).build());
        refreshActive();
    }

    private void refreshActive() {
        boolean vulkan = backend == Backend.VULKAN;
        vulkanButton.active = vulkan; openGLButton.active = !vulkan;
        for (var button : extensionButtons) button.active = vulkan;
    }

    private void save() {
        VulkanForgeConfig.BACKEND.set(backend);
        VulkanForgeConfig.VULKAN_VERSION.set(vulkanVersion);
        VulkanForgeConfig.OPENGL_VERSION.set(openGLVersion);
        extensions.forEach((extension, value) -> VulkanForgeConfig.NVIDIA_EXTENSIONS.get(extension).set(value));
        VulkanForgeConfig.SPEC.save();
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xffffff);
        graphics.drawCenteredString(font, Component.translatable("vulkanforge.config.pending"), width / 2, 24, 0xffaa55);
        graphics.drawCenteredString(font, Component.translatable("vulkanforge.config.nvidia"), width / 2, extensionLabelY, backend == Backend.VULKAN ? 0xa0c8ff : 0x707070);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
}
