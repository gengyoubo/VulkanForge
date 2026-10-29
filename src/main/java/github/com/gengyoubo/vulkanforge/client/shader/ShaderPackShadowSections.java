package github.com.gengyoubo.vulkanforge.client.shader;

import java.lang.reflect.*;
import java.util.*;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.joml.Matrix4f;

/** Selects compiled resident sections with the light frustum, independently of the player's visibility graph. */
final class ShaderPackShadowSections {
    private static final Field VIEW=ObfuscationReflectionHelper.findField(LevelRenderer.class,"f_109469_"),VISIBLE=ObfuscationReflectionHelper.findField(LevelRenderer.class,"f_194297_");
    private static Constructor<?> infoConstructor;
    private ViewArea area;
    private final Map<ChunkRenderDispatcher.RenderChunk,Object> records=new IdentityHashMap<>();
    private final List<Object> saved=new ArrayList<>();
    int sections,normalSections;
    @SuppressWarnings("unchecked") AutoCloseable select(LevelRenderer renderer,Matrix4f model,Matrix4f projection,Vec3 camera)throws ReflectiveOperationException{
        var current=(ViewArea)VIEW.get(renderer);var visible=(List<Object>)VISIBLE.get(renderer);
        if(current!=area){records.clear();area=current;}
        if(infoConstructor==null){Class<?> info=Arrays.stream(LevelRenderer.class.getDeclaredClasses()).filter(type->Arrays.stream(type.getDeclaredFields()).anyMatch(field->field.getType()==ChunkRenderDispatcher.RenderChunk.class)).findFirst().orElseThrow();infoConstructor=info.getDeclaredConstructor(ChunkRenderDispatcher.RenderChunk.class,Direction.class,int.class);infoConstructor.setAccessible(true);}
        Frustum light=new Frustum(model,projection);light.prepare(camera.x,camera.y,camera.z);
        saved.clear();saved.addAll(visible);normalSections=visible.size();visible.clear();
        try{
            if(current!=null)for(var chunk:current.chunks){
                if(chunk.getCompiledChunk().hasNoRenderableLayers()||!light.isVisible(chunk.getBoundingBox()))continue;
                Object record=records.get(chunk);if(record==null){record=infoConstructor.newInstance(chunk,null,0);records.put(chunk,record);}visible.add(record);
            }
            sections=visible.size();
        }catch(ReflectiveOperationException|RuntimeException error){restore(visible);throw error;}
        return ()->restore(visible);
    }
    private void restore(List<Object> visible){visible.clear();visible.addAll(saved);saved.clear();}
}
