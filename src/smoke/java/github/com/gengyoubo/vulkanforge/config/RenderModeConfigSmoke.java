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
            require(config.glVkPlan()==VulkanForgeConfig.GlVkPlan.GL_TAIL&&migrated.contains("glVkPlan"),"GL tail plan was not migrated");
        }
        var base=VulkanForgeConfig.Startup.DEFAULTS;
        require(base.glTailCompatibility(),"GL-VK default is not GL tail");
        require(VulkanForgeConfig.override(base,"VULKAN:V1_3").mode()==RenderMode.GL_VK,"legacy override became native");
        require(VulkanForgeConfig.override(base,"VK:V1_4").mode()==RenderMode.VK,"VK override");
        require(VulkanForgeConfig.override(base,"GL:GL_4_3").gl46Terrain(),"GL compatibility path");
        require(RenderMode.GL_VK.allowsGlFallback()&&RenderMode.GL_VK.allowsInterop(),"hybrid policy");
        require(!RenderMode.VK.allowsOpenGL()&&!RenderMode.VK.allowsInterop()&&!RenderMode.VK.allowsGlFallback(),"native allowed GL");
        Path explicit=directory.resolve("explicit-vk.toml");Files.writeString(explicit,"[renderer]\nmode=\"VK\"\nbackend=\"OPENGL\"\n");require(VulkanForgeConfig.read(explicit).mode()==RenderMode.VK,"explicit mode did not win");
        Path plans=directory.resolve("plans.toml");Files.writeString(plans,"[renderer]\nmode=\"GL_VK\"\nglVkPlan=\"INTERLEAVED\"\n[vulkan]\nversion=\"V1_2\"\n");
        var interleaved=VulkanForgeConfig.read(plans);require(!interleaved.glTailCompatibility(),"explicit interleaved plan lost");
        require(VulkanForgeConfig.override(interleaved,"GL_VK:V1_3").glVkPlan()==VulkanForgeConfig.GlVkPlan.INTERLEAVED,"API override reset plan");
        require(VulkanForgeConfig.overridePlan(interleaved,"true").glTailCompatibility(),"legacy true override ignored");
        require(!VulkanForgeConfig.overridePlan(base,"false").glTailCompatibility(),"legacy false override ignored");
        require(VulkanForgeConfig.read(plans).equals(interleaved),"launch override wrote saved plan");
        require(!VulkanForgeConfig.override(base,"GL:GL_4_6").glTailCompatibility()&&!VulkanForgeConfig.override(base,"VK:V1_4").glTailCompatibility(),"plan enabled outside GL-VK");
        Files.writeString(plans,"[renderer]\nmode=\"GL_VK\"\nglVkPlan=\"invalid\"\n");require(VulkanForgeConfig.read(plans).glTailCompatibility()&&Files.readString(plans).contains("GL_TAIL"),"invalid plan fallback not persisted");
        System.out.println("RENDER_MODE_CONFIG_SMOKE=OK (saved GL tail/interleaved plans, default migration, API/CLI overrides, version/extension preservation and mode policy)");
    }
    private static void require(boolean value,String reason){if(!value)throw new AssertionError(reason);}
}
