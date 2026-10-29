package github.com.gengyoubo.vulkanforge.client.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import github.com.gengyoubo.vulkanforge.VulkanForge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CubeMap;
import net.minecraft.client.renderer.PanoramaRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;

/** The game's blur program is also compiled by the native Vulkan shader backend. */
final class ShaderMenuBackground implements AutoCloseable {
    private final PanoramaRenderer panorama=new PanoramaRenderer(new CubeMap(ResourceLocation.parse("textures/gui/title/background/panorama")));
    private PostChain blur;
    private int width,height;
    private boolean failed;
    void render(float partialTick){
        Minecraft mc=Minecraft.getInstance();if(mc.level==null)panorama.render(partialTick,1);
        if(failed)return;
        try{
            var target=mc.getMainRenderTarget();
            if(blur==null)blur=new PostChain(mc.getTextureManager(),mc.getResourceManager(),target,ResourceLocation.fromNamespaceAndPath("vulkanforge","shaders/post/menu_blur.json"));
            if(width!=target.viewWidth||height!=target.viewHeight){width=target.viewWidth;height=target.viewHeight;blur.resize(width,height);}
            RenderSystem.disableBlend();RenderSystem.disableDepthTest();blur.process(partialTick);
        }catch(Exception error){failed=true;close();VulkanForge.LOGGER.warn("Could not render shader menu blur",error);}
        finally{mc.getMainRenderTarget().bindWrite(true);RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();RenderSystem.setShaderColor(1,1,1,1);}
    }
    @Override public void close(){if(blur!=null){blur.close();blur=null;}width=height=0;}
}
