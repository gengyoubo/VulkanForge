package github.com.gengyoubo.vulkanforge.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.*;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/** Opt-in hotbar-only geometry cache. RenderType and shader state still execute on every draw. */
public final class NativeHotbarCache {
    private static final boolean ENABLED=Boolean.getBoolean("vulkanforge.nativeHotbarCache");
    private static final int MAX_BYTES=256*1024,MAX_LAYERS=16;
    private static final Slot[] slots=new Slot[10];
    private static final AtomicLong reloads=new AtomicLong();
    private static long epoch=-1,hits,misses,fallbacks,builds,invalidations,replayedDraws;
    private static Object level,player;
    private static int width,height;
    private static double scale;
    private static boolean benchmarkEnabled=true,resident;
    private static Slot capture;

    private record Layer(RenderType type,VertexBuffer buffer) { }
    private static final class Slot {
        final Item item;
        final CompoundTag tag;
        final int x,y;
        final Matrix4f pose;
        final Matrix3f normal;
        final boolean flat;
        final BakedModel model;
        final List<Layer> layers=new ArrayList<>();
        int bytes;
        long lastFrame;
        boolean rejected;
        Slot(ItemStack stack,int x,int y,GuiGraphics graphics,BakedModel model){
            item=stack.getItem();tag=stack.getTag()==null?null:stack.getTag().copy();this.x=x;this.y=y;
            pose=new Matrix4f(graphics.pose().last().pose());normal=new Matrix3f(graphics.pose().last().normal());this.model=model;flat=!model.usesBlockLight();
        }
        boolean matches(ItemStack stack,int x,int y,GuiGraphics graphics){
            return item==stack.getItem()&&Objects.equals(tag,stack.getTag())&&this.x==x&&this.y==y
                    &&pose.equals(graphics.pose().last().pose())&&normal.equals(graphics.pose().last().normal());
        }
        void close(){for(Layer layer:layers)layer.buffer.close();layers.clear();}
    }
    private NativeHotbarCache() { }

    /** Called only in Gui.renderSlot, leaving decorations and every other item rendering call untouched. */
    public static void renderItem(GuiGraphics graphics,LivingEntity entity,ItemStack stack,int x,int y,int seed){
        if(!ENABLED||!benchmarkEnabled||!NativeGameWindow.enabled()||!RenderSystem.isOnRenderThread()){
            graphics.renderItem(entity,stack,x,y,seed);return;
        }
        Minecraft mc=Minecraft.getInstance();
        if(seed<1||seed>slots.length||entity!=mc.player||mc.screen!=null||capture!=null||!eligible(stack,mc)){
            fallbacks++;if(seed>=1&&seed<=slots.length)drop(seed-1);graphics.renderItem(entity,stack,x,y,seed);return;
        }
        int index=seed-1;
        // Check the unresolved base: a selected override model can itself have an empty override list.
        BakedModel base=mc.getItemRenderer().getItemModelShaper().getItemModel(stack);
        if(base.getClass()!=SimpleBakedModel.class||base.getOverrides().getClass()!=ItemOverrides.class
                ||!base.getOverrides().getOverrides().isEmpty()||base.isCustomRenderer()){
            fallbacks++;drop(index);graphics.renderItem(entity,stack,x,y,seed);return;
        }
        Slot cached=slots[index];
        // Resolve pending GUI geometry before capturing; it must not become part of this item's buffers.
        graphics.flush();
        if(cached!=null&&cached.model==base&&cached.matches(stack,x,y,graphics)){
            hits++;cached.lastFrame=NativeGameWindow.frameSerial();
            if(cached.flat)Lighting.setupForFlatItems();
            RenderSystem.disableDepthTest();
            try{for(Layer layer:cached.layers){draw(layer);replayedDraws++;}}
            finally{VertexBuffer.unbind();RenderSystem.enableDepthTest();if(cached.flat)Lighting.setupFor3DItems();}
            return;
        }
        drop(index);misses++;
        BakedModel model=mc.getItemRenderer().getModel(stack,mc.level,entity,seed);
        // Overrides, custom renderers and model implementations can depend on arbitrary game state.
        if(model!=base){
            fallbacks++;graphics.renderItem(entity,stack,x,y,seed);return;
        }
        Slot next=new Slot(stack,x,y,graphics,model);
        capture=next;
        try{
            graphics.renderItem(entity,stack,x,y,seed);
            if(!next.rejected&&!next.layers.isEmpty()){next.lastFrame=NativeGameWindow.frameSerial();slots[index]=next;builds++;next=null;}
        }finally{capture=null;if(next!=null)next.close();}
    }

    private static boolean eligible(ItemStack stack,Minecraft mc){
        if(stack.isEmpty()||stack.hasFoil()||stack.getPopTime()>0||mc.player.getCooldowns().isOnCooldown(stack.getItem()))return false;
        if(!BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace().equals("minecraft"))return false;
        Item item=stack.getItem();
        // Initial supported set is deliberately explicit: no property-driven, tinted or block-entity models.
        return item==Items.STONE||item==Items.COBBLESTONE||item==Items.DIRT||item==Items.GLASS||item==Items.TORCH
                ||item==Items.DIAMOND||item==Items.IRON_INGOT||item==Items.GOLD_INGOT||item==Items.COAL
                ||item==Items.DIAMOND_SWORD||item==Items.IRON_SWORD||item==Items.WATER_BUCKET||item==Items.BUCKET;
    }

    /** Existing RenderType capture supplies the actual layer after its sorting and state setup. */
    static boolean capture(RenderType type,BufferBuilder.RenderedBuffer rendered){
        Slot next=capture;
        if(next==null||next.rejected)return false;
        if(rendered.isEmpty()){rendered.release();return true;}
        int bytes=Math.addExact(rendered.vertexBuffer().remaining(),rendered.indexBuffer().remaining());
        if(next.layers.size()>=MAX_LAYERS||bytes>MAX_BYTES-next.bytes){next.rejected=true;return false;}
        VertexBuffer buffer=new VertexBuffer(VertexBuffer.Usage.STATIC);boolean added=false;
        try{
            buffer.bind();buffer.upload(rendered);next.layers.add(new Layer(type,buffer));next.bytes+=bytes;added=true;
            resident=true;buffer.drawWithShader(RenderSystem.getModelViewMatrix(),RenderSystem.getProjectionMatrix(),RenderSystem.getShader());
        }finally{resident=false;VertexBuffer.unbind();if(!added)buffer.close();}
        return true;
    }
    private static void draw(Layer layer){
        layer.type.setupRenderState();
        try{layer.buffer.bind();resident=true;layer.buffer.drawWithShader(RenderSystem.getModelViewMatrix(),RenderSystem.getProjectionMatrix(),RenderSystem.getShader());}
        finally{resident=false;layer.type.clearRenderState();}
    }
    static boolean drawingResident(){return resident;}
    public static void resourcesReloaded(){reloads.incrementAndGet();}
    static void beginFrame(){
        if(!ENABLED)return;
        Minecraft mc=Minecraft.getInstance();var window=mc.getWindow();long current=reloads.get();
        if(epoch!=current||level!=mc.level||player!=mc.player||width!=window.getWidth()||height!=window.getHeight()||scale!=window.getGuiScale()){
            clear();epoch=current;level=mc.level;player=mc.player;width=window.getWidth();height=window.getHeight();scale=window.getGuiScale();
        }
        long frame=NativeGameWindow.frameSerial();for(int i=0;i<slots.length;i++)if(slots[i]!=null&&frame-slots[i].lastFrame>120)drop(i);
    }
    private static void drop(int index){if(slots[index]!=null){slots[index].close();slots[index]=null;invalidations++;}}
    static void clear(){for(int i=0;i<slots.length;i++)drop(i);}
    public static void benchmarkEnabled(boolean value){
        if(System.getProperty("vulkanforge.gameFps","").isEmpty())throw new IllegalStateException("Hotbar comparison is test-only");
        if(benchmarkEnabled!=value)clear();benchmarkEnabled=value;
    }
    static Map<String,Long> diagnostics(){
        long entries=0,bytes=0;for(Slot slot:slots)if(slot!=null){entries++;bytes+=slot.bytes;}
        return Map.of("hotbarCacheHits",hits,"hotbarCacheMisses",misses,"hotbarCacheFallbacks",fallbacks,"hotbarCacheBuilds",builds,
                "hotbarCacheInvalidations",invalidations,"hotbarCacheReplayedDraws",replayedDraws,"hotbarCacheEntries",entries,"hotbarCacheBytes",bytes);
    }
}
