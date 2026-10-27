package github.com.gengyoubo.vulkanforge.config;

import dev.vulkanforge.bridge.RenderMode;
import java.nio.file.*;

/** Headless config migration, aliases, mode policy and version/extension preservation. */
public final class RenderModeConfigSmoke {
    public static void main(String[] args) throws Exception {
        Path directory=Path.of("build/reports/render-mode-config");Files.createDirectories(directory);
        for(String legacy:new String[]{"VULKAN","OPENGL"}) {
            Path file=directory.resolve(legacy.toLowerCase()+".toml");Files.writeString(file,"[renderer]\nbackend=\""+legacy+"\"\n[vulkan]\nversion=\"V1_2\"\n[vulkan.nvidiaExtensions]\nmesh_shader=true\n[opengl]\nversion=\"GL_4_4\"\n");
            var config=VulkanForgeConfig.read(file);require(config.mode()==(legacy.equals("VULKAN")?RenderMode.GL_VK:RenderMode.GL),"wrong legacy migration");require(config.vulkanVersion()==VulkanForgeConfig.VulkanVersion.V1_2&&config.openGLVersion()==VulkanForgeConfig.OpenGLVersion.GL_4_4,"lost version");require(config.nvidiaExtensions().contains(VulkanForgeConfig.NvidiaExtension.MESH_SHADER),"lost extension");
            String migrated=Files.readString(file);require(migrated.contains("mode")&&!migrated.contains("backend"),"migration not persisted");require(VulkanForgeConfig.read(file).equals(config),"migration not idempotent");
        }
        var base=VulkanForgeConfig.Startup.DEFAULTS;
        require(VulkanForgeConfig.override(base,"VULKAN:V1_3").mode()==RenderMode.GL_VK,"legacy override became native");
        require(VulkanForgeConfig.override(base,"VK:V1_4").mode()==RenderMode.VK,"VK override");
        require(VulkanForgeConfig.override(base,"GL:GL_4_3").gl46Terrain(),"GL compatibility path");
        require(RenderMode.GL_VK.allowsGlFallback()&&RenderMode.GL_VK.allowsInterop(),"hybrid policy");
        require(!RenderMode.VK.allowsOpenGL()&&!RenderMode.VK.allowsInterop()&&!RenderMode.VK.allowsGlFallback(),"native allowed GL");
        Path explicit=directory.resolve("explicit-vk.toml");Files.writeString(explicit,"[renderer]\nmode=\"VK\"\nbackend=\"OPENGL\"\n");require(VulkanForgeConfig.read(explicit).mode()==RenderMode.VK,"explicit mode did not win");
        System.out.println("RENDER_MODE_CONFIG_SMOKE=OK (three modes, persisted legacy migration, versions/extensions, aliases and no-GL native policy)");
    }
    private static void require(boolean value,String reason){if(!value)throw new AssertionError(reason);}
}
