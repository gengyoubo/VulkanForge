package github.com.gengyoubo.vulkanforge;

import com.mojang.logging.LogUtils;
import github.com.gengyoubo.vulkanforge.client.VulkanForgeConfigScreen;
import github.com.gengyoubo.vulkanforge.config.VulkanForgeConfig;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

@Mod(VulkanForge.MOD_ID)
public final class VulkanForge {
    public static final String MOD_ID = "vulkanforge";
    public static final Logger LOGGER = LogUtils.getLogger();

    public VulkanForge(FMLJavaModLoadingContext context) {
        context.registerConfig(ModConfig.Type.CLIENT, VulkanForgeConfig.SPEC);
        if (FMLEnvironment.dist == Dist.CLIENT) VulkanForgeConfigScreen.register(context);
        LOGGER.info("VulkanForge loaded: ASM main-window Vulkan presentation, terrain bridge and capability detection");
    }
}
