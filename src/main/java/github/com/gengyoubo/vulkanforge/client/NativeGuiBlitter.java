package github.com.gengyoubo.vulkanforge.client;

import com.mojang.blaze3d.shaders.BlendMode;
import com.mojang.blaze3d.systems.RenderSystem;
import java.lang.reflect.Field;
import java.nio.*;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import org.joml.*;

/** Native POSITION_TEX sprites; unsupported shader interfaces retain GuiGraphics' original method. */
public final class NativeGuiBlitter {
    private static final boolean ENABLED=Boolean.getBoolean("vulkanforge.nativeGuiFastPath");
    private static boolean benchmarkEnabled=true;
    private static final Matrix4f model=new Matrix4f(),projection=new Matrix4f();
    private static final Vector4f vertex=new Vector4f();
    private static float[] color;
    private static Object[] uniforms;
    private static long quads,fallbacks;
    private static final ClassValue<Optional<Field>> blends=new ClassValue<>(){
        @Override protected Optional<Field> computeValue(Class<?> type){for(Class<?> current=type;current!=null;current=current.getSuperclass())for(Field field:current.getDeclaredFields())if(field.getType()==BlendMode.class&&field.trySetAccessible())return Optional.of(field);return Optional.empty();}
    };
    private NativeGuiBlitter() { }
    public static boolean tryBlit(GuiGraphics graphics,ResourceLocation texture,int x1,int x2,int y1,int y2,int z,float u1,float u2,float v1,float v2){
        if(!ENABLED||!benchmarkEnabled||!NativeGameWindow.enabled()||!RenderSystem.isOnRenderThread())return false;
        ShaderInstance shader=GameRenderer.getPositionTexShader();if(shader==null||!NativeDeviceState.guiBlitSupported(shader.getId())){fallbacks++;return false;}
        Optional<Field> field=blends.get(shader.getClass());if(field.isEmpty()){fallbacks++;return false;}
        try{((BlendMode)field.get().get(shader)).apply();}catch(IllegalAccessException error){fallbacks++;return false;}
        RenderSystem.setShaderTexture(0,texture);RenderSystem.setShader(GameRenderer::getPositionTexShader);
        Matrix4f currentModel=RenderSystem.getModelViewMatrix(),currentProjection=RenderSystem.getProjectionMatrix();float[] currentColor=RenderSystem.getShaderColor();
        if(uniforms==null||!model.equals(currentModel)||!projection.equals(currentProjection)||!Arrays.equals(color,currentColor)){
            model.set(currentModel);projection.set(currentProjection);color=currentColor.clone();uniforms=new Object[]{new Object[]{"ModelViewMat",model.get(new float[16])},new Object[]{"ProjMat",projection.get(new float[16])},new Object[]{"ColorModulator",color}};
        }
        if(shader.MODEL_VIEW_MATRIX!=null)shader.MODEL_VIEW_MATRIX.set(currentModel);if(shader.PROJECTION_MATRIX!=null)shader.PROJECTION_MATRIX.set(currentProjection);if(shader.COLOR_MODULATOR!=null)shader.COLOR_MODULATOR.set(currentColor);shader.setSampler("Sampler0",RenderSystem.getShaderTexture(0));
        ByteBuffer bytes=ByteBuffer.allocate(80).order(ByteOrder.nativeOrder());Matrix4f pose=graphics.pose().last().pose();
        vertex(bytes,pose,x1,y1,z,u1,v1);vertex(bytes,pose,x1,y2,z,u1,v2);vertex(bytes,pose,x2,y2,z,u2,v2);vertex(bytes,pose,x2,y1,z,u2,v1);
        NativeDeviceState.guiBlit(shader.getId(),RenderSystem.getShaderTexture(0),bytes.array(),uniforms);quads++;
        // Preserve ShaderInstance's public uniform/sampler values and its program/texture cache cleanup contract.
        shader.clear();
        return true;
    }
    public static void benchmarkEnabled(boolean enabled){if(System.getProperty("vulkanforge.gameFps","").isEmpty())throw new IllegalStateException("GUI comparison is test-only");benchmarkEnabled=enabled;}
    private static void vertex(ByteBuffer output,Matrix4f pose,float x,float y,float z,float u,float v){pose.transform(vertex.set(x,y,z,1));output.putFloat(vertex.x).putFloat(vertex.y).putFloat(vertex.z).putFloat(u).putFloat(v);}
    static Map<String,Long> diagnostics(){return Map.of("guiFastQuads",quads,"guiFastFallbacks",fallbacks);}
}
