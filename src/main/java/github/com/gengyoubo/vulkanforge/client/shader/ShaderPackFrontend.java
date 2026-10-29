package github.com.gengyoubo.vulkanforge.client.shader;

import net.minecraft.client.gui.screens.Screen;

/** Production entry point for VulkanForge's own shader pack loader and settings. */
public final class ShaderPackFrontend {
    private static boolean announced;
    private ShaderPackFrontend() { }
    public static boolean irisAvailable(){return Boolean.getBoolean("vulkanforge.irisReference");}
    public static void beginFrame(){
        if(announced)return;announced=true;
        String mode=github.com.gengyoubo.vulkanforge.config.VulkanForgeConfig.startup().mode().name();
        github.com.gengyoubo.vulkanforge.VulkanForge.LOGGER.info("VulkanForge shader pack loader active, backend={}",mode);
        try{var report=net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().resolve("logs/vulkanforge/shader-pack-compatibility.txt");java.nio.file.Files.createDirectories(report.getParent());java.nio.file.Files.writeString(report,"FRONTEND=VULKANFORGE\nBACKEND="+mode+"\nEXTERNAL_LOADER=NONE\n");}
        catch(java.io.IOException error){github.com.gengyoubo.vulkanforge.VulkanForge.LOGGER.warn("Could not write shader frontend selection report",error);}
    }
    public static Screen screen(Screen parent){
        return new ShaderPackScreen(parent);
    }
}
