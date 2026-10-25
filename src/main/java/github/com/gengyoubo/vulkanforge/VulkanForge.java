package github.com.gengyoubo.vulkanforge;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

@Mod(VulkanForge.MOD_ID)
public final class VulkanForge {
    public static final String MOD_ID = "vulkanforge";
    public static final Logger LOGGER = LogUtils.getLogger();

    public VulkanForge() {
        LOGGER.info("VulkanForge loaded: in-window offscreen preview, Vulkan lab and capability detection");
    }
}
