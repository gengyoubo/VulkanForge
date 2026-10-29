package github.com.gengyoubo.vulkanforge.client.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;

/** Frame globals use actual camera/projection state, shared by either graphics backend. */
final class ShaderPackUniforms {
    private static final Map<String,Integer> SIZES=sizes();
    private static final class BiomeKeys {static final Map<String,ResourceKey<Biome>> VALUES=biomes();}
    private final Matrix4f projection=new Matrix4f(),view=new Matrix4f(),previousProjection=new Matrix4f(),previousView=new Matrix4f();
    private final Vector3d previousCamera=new Vector3d();
    private final float[] eyeBrightnessSmooth=new float[2];
    private long started=System.nanoTime(),last=started;
    private int frame;
    private boolean matrices;
    private float sunPathRotation,shadowInterval=2,shadowDistance=128;
    final Matrix4f shadowView=new Matrix4f(),shadowProjection=new Matrix4f();
    private Object constantsLevel;
    private final Map<String,float[]> constants=new HashMap<>();
    private final github.com.gengyoubo.vulkanforge.shaderpack.ShaderPackIds items;
    ShaderPackUniforms(github.com.gengyoubo.vulkanforge.shaderpack.ShaderPackIds items){this.items=items;}
    void matrices(Matrix4f projection,Matrix4f view){this.projection.set(projection);this.view.set(view);if(!matrices){previousProjection.set(projection);previousView.set(view);matrices=true;}}
    /** Per-dimension pack constants: sunPathRotation, shadowIntervalSize and shadowDistance. */
    void celestial(float rotation,float interval,float distance){sunPathRotation=rotation;shadowInterval=interval;shadowDistance=distance;}
    static Set<String> supported(){return SIZES.keySet();}
    /** Valid non-level inputs with real component counts; dimensions and matrices must not induce preview-only division by zero. */
    static Map<String,float[]> preview(){
        Map<String,float[]> values=new HashMap<>();SIZES.forEach((name,size)->{float[] data=new float[size];if(size==16)for(int i=0;i<4;i++)data[i*5]=1;if(name.startsWith("BIOME_"))data[0]=-1;values.put(name,data);});
        for(String name:List.of("viewWidth","viewHeight","aspectRatio"))put(values,name,1);
        for(String name:List.of("viewSize","pixelSize","screenSize"))put(values,name,1,1);
        put(values,"frameTime",1f/60);put(values,"near",.05f);put(values,"far",128);return values;
    }
    private static Map<String,Integer> sizes(){
        Map<String,Integer> sizes=new HashMap<>();
        for(String name:List.of("viewWidth","viewHeight","aspectRatio","frameCounter","frameTime","frameTimeCounter","worldTime","worldDay","moonPhase","rainStrength","wetness","thunderStrength","sunAngle","shadowAngle","near","far","eyeAltitude","isEyeInWater","fogStart","fogEnd","blindness","darknessFactor","nightVision","screenBrightness","playerMood","hideGUI","biome","biome_precipitation","temperature","rainfall","heldBlockLightValue","heldBlockLightValue2","PPT_NONE","PPT_RAIN","PPT_SNOW","vfFogStart","vfFogEnd","vfFogDensity","darknessLightFactor","cloudHeight","isElytraFlying","heldItemId","heldItemId2","entityId","blockEntityId","currentRenderedItemId"))sizes.put(name,1);
        sizes.put("endFlashIntensity",1);
        for(String name:List.of("vfFogColor","lightningBoltPosition","entityColor"))sizes.put(name,4);sizes.put("atlasSize",2);
        for(String name:List.of("viewSize","pixelSize","screenSize","eyeBrightness","eyeBrightnessSmooth"))sizes.put(name,2);
        for(String name:List.of("cameraPositionInt","cameraPositionFract","previousCameraPositionInt","previousCameraPositionFract","eyePosition","relativeEyePosition","playerLookVector","cameraPosition","previousCameraPosition","sunPosition","moonPosition","shadowLightPosition","upPosition","fogColor","skyColor"))sizes.put(name,3);
        for(String name:List.of("gbufferProjection","gbufferProjectionInverse","gbufferPreviousProjection","gbufferModelView","gbufferModelViewInverse","gbufferPreviousModelView","shadowModelView","shadowModelViewInverse","shadowProjection","shadowProjectionInverse"))sizes.put(name,16);
        // Reading field names does not initialize Minecraft's registries; the preview is also usable in CPU validation.
        for(var field:net.minecraft.world.level.biome.Biomes.class.getFields())if(java.lang.reflect.Modifier.isStatic(field.getModifiers())&&field.getType()==ResourceKey.class)sizes.put("BIOME_"+field.getName(),1);
        sizes.put("BIOME_PALE_GARDEN",1);sizes.put("BIOME_SULFUR_CAVES",1);return Map.copyOf(sizes);
    }
    /** BIOME_* names come from the vanilla biome keys; their values are the running level's registry ids. */
    @SuppressWarnings("unchecked") private static Map<String,ResourceKey<Biome>> biomes(){
        Map<String,ResourceKey<Biome>> result=new HashMap<>();
        for(var field:net.minecraft.world.level.biome.Biomes.class.getFields())if(java.lang.reflect.Modifier.isStatic(field.getModifiers())&&field.getType()==ResourceKey.class)try{var key=(ResourceKey<Biome>)field.get(null);result.put("BIOME_"+key.location().getPath().toUpperCase(Locale.ROOT),key);}catch(IllegalAccessException error){throw new IllegalStateException(error);}
        // Newer packs can name biomes absent in 1.20.1. A missing registry key resolves to -1, never a real biome.
        for(String name:List.of("pale_garden","sulfur_caves"))result.putIfAbsent("BIOME_"+name.toUpperCase(Locale.ROOT),ResourceKey.create(Registries.BIOME,net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minecraft",name)));
        return Map.copyOf(result);
    }
    Map<String,float[]> next(float partialTick,int width,int height){
        Minecraft mc=Minecraft.getInstance();if(mc.level==null)throw new IllegalStateException("Shader pack world globals require a level");
        long now=System.nanoTime();float seconds=(now-last)/1e9f;last=now;
        if(!matrices)matrices(RenderSystem.getProjectionMatrix(),RenderSystem.getModelViewMatrix());
        var camera=mc.gameRenderer.getMainCamera().getPosition();if(frame==0)previousCamera.set(camera.x,camera.y,camera.z);
        if(constantsLevel!=mc.level){constantsLevel=mc.level;constants.clear();var registry=mc.level.registryAccess().registryOrThrow(Registries.BIOME);BiomeKeys.VALUES.forEach((name,key)->constants.put(name,new float[]{registry.getOptional(key).map(registry::getId).orElse(-1)}));constants.put("PPT_NONE",new float[]{0});constants.put("PPT_RAIN",new float[]{1});constants.put("PPT_SNOW",new float[]{2});}
        Map<String,float[]> values=new HashMap<>(constants);
        put(values,"viewWidth",width);put(values,"viewHeight",height);put(values,"viewSize",width,height);put(values,"screenSize",width,height);put(values,"aspectRatio",(float)width/height);put(values,"pixelSize",1f/width,1f/height);
        put(values,"frameCounter",frame++);put(values,"frameTime",seconds);put(values,"frameTimeCounter",(float)((now-started)/1e9%3600));
        long worldTime=mc.level.getDayTime();put(values,"worldTime",worldTime%24000);put(values,"worldDay",worldTime/24000);put(values,"moonPhase",mc.level.getMoonPhase());
        put(values,"rainStrength",mc.level.getRainLevel(partialTick));put(values,"wetness",mc.level.getRainLevel(partialTick));put(values,"thunderStrength",mc.level.getThunderLevel(partialTick));
        // End flashes were added after this game's version; the 1.20.1 renderer has no such event.
        put(values,"endFlashIntensity",0);
        // sunAngle is 0 at sunrise and 0.5 at sunset (the sky angle shifted by a quarter); shadowAngle follows the sun by day and the moon by night.
        float skyAngle=mc.level.getTimeOfDay(partialTick),sunAngle=skyAngle<.75f?skyAngle+.25f:skyAngle-.75f;boolean day=sunAngle<=.5f;float shadowAngle=day?sunAngle:sunAngle-.5f;
        put(values,"sunAngle",sunAngle);put(values,"shadowAngle",shadowAngle);
        // near is the fixed near plane; far is the render distance in blocks, not the projection's far plane.
        put(values,"near",.05f);put(values,"far",mc.gameRenderer.getRenderDistance());put(values,"eyeAltitude",(float)camera.y);
        String fluid=mc.gameRenderer.getMainCamera().getFluidInCamera().name();put(values,"isEyeInWater",fluid.equals("WATER")?1:fluid.equals("LAVA")?2:0);
        put(values,"cameraPosition",(float)camera.x,(float)camera.y,(float)camera.z);put(values,"previousCameraPosition",(float)previousCamera.x,(float)previousCamera.y,(float)previousCamera.z);
        values.put("gbufferProjection",projection.get(new float[16]));values.put("gbufferProjectionInverse",new Matrix4f(projection).invert().get(new float[16]));values.put("gbufferPreviousProjection",previousProjection.get(new float[16]));
        values.put("gbufferModelView",view.get(new float[16]));values.put("gbufferModelViewInverse",new Matrix4f(view).invert().get(new float[16]));values.put("gbufferPreviousModelView",previousView.get(new float[16]));
        // Celestial bodies on the vanilla sun path, tilted by sunPathRotation, in view space.
        Matrix4f celestial=new Matrix4f(view).rotateY((float)Math.toRadians(-90)).rotateZ((float)Math.toRadians(sunPathRotation)).rotateX((float)Math.toRadians(skyAngle*360));
        Vector3f sun=celestial.transformDirection(new Vector3f(0,100,0)),moon=celestial.transformDirection(new Vector3f(0,-100,0)),light=day?sun:moon;
        put(values,"sunPosition",sun.x,sun.y,sun.z);put(values,"moonPosition",moon.x,moon.y,moon.z);put(values,"shadowLightPosition",light.x,light.y,light.z);
        // Shadow camera: orthographic, looking along the light, snapped to shadowIntervalSize so the map does not shimmer while moving.
        float tilt=shadowAngle<.25f?shadowAngle+.75f:shadowAngle-.25f;
        Matrix4f shadowView=new Matrix4f().translate(0,0,-100).rotateX((float)Math.toRadians(90)).rotateZ((float)Math.toRadians(tilt*-360)).rotateX((float)Math.toRadians(sunPathRotation));
        if(shadowInterval!=0){float half=shadowInterval/2;shadowView.translate((float)(camera.x%shadowInterval)-half,(float)(camera.y%shadowInterval)-half,(float)(camera.z%shadowInterval)-half);}
        float near=.05f,farPlane=256;Matrix4f shadowProjection=new Matrix4f(1/shadowDistance,0,0,0, 0,1/shadowDistance,0,0, 0,0,2/(near-farPlane),0, 0,0,-(farPlane+near)/(farPlane-near),1);
        this.shadowView.set(shadowView);this.shadowProjection.set(shadowProjection);
        values.put("shadowModelView",shadowView.get(new float[16]));values.put("shadowModelViewInverse",new Matrix4f(shadowView).invert().get(new float[16]));values.put("shadowProjection",shadowProjection.get(new float[16]));values.put("shadowProjectionInverse",new Matrix4f(shadowProjection).invert().get(new float[16]));
        Vector3f up=view.transformDirection(new Vector3f(0,100,0));put(values,"upPosition",up.x,up.y,up.z);
        float[] fog=RenderSystem.getShaderFogColor();put(values,"fogColor",fog[0],fog[1],fog[2]);put(values,"fogStart",RenderSystem.getShaderFogStart());put(values,"fogEnd",RenderSystem.getShaderFogEnd());
        put(values,"vfFogStart",RenderSystem.getShaderFogStart());put(values,"vfFogEnd",RenderSystem.getShaderFogEnd());put(values,"vfFogDensity",1);put(values,"vfFogColor",fog[0],fog[1],fog[2],fog.length>3?fog[3]:1);
        var sky=mc.level.getSkyColor(camera,partialTick);put(values,"skyColor",(float)sky.x,(float)sky.y,(float)sky.z);
        // Light at the eye block, scaled to 0..240 like OptiFine; the smoothed value approaches it with a 10 s half-life.
        var entity=mc.getCameraEntity();BlockPos eye=entity==null?BlockPos.containing(camera):BlockPos.containing(entity.getX(),entity.getEyeY(),entity.getZ());
        float block=mc.level.getBrightness(LightLayer.BLOCK,eye)*16,skyLight=mc.level.getBrightness(LightLayer.SKY,eye)*16;put(values,"eyeBrightness",block,skyLight);
        float fade=frame==1?1:(float)-Math.expm1(-Math.log(2)*Math.min(seconds,1)/10);eyeBrightnessSmooth[0]+=(block-eyeBrightnessSmooth[0])*fade;eyeBrightnessSmooth[1]+=(skyLight-eyeBrightnessSmooth[1])*fade;put(values,"eyeBrightnessSmooth",Math.round(eyeBrightnessSmooth[0]),Math.round(eyeBrightnessSmooth[1]));
        float blindness=0,darkness=0,nightVision=0,heldLight=0,heldLight2=0;
        if(entity instanceof LivingEntity living){
            var effect=living.getEffect(MobEffects.BLINDNESS);if(effect!=null)blindness=effect.isInfiniteDuration()?1:Math.min(1,effect.getDuration()/20f);
            var dark=living.getEffect(MobEffects.DARKNESS);if(dark!=null)darkness=dark.getFactorData().map(data->data.getFactor(living,partialTick)).orElse(0f)*mc.options.darknessEffectScale().get().floatValue();
            if(living.hasEffect(MobEffects.NIGHT_VISION))nightVision=net.minecraft.client.renderer.GameRenderer.getNightVisionScale(living,partialTick);
            heldLight=light(living.getMainHandItem());heldLight2=light(living.getOffhandItem());
        }
        put(values,"blindness",blindness);put(values,"darknessFactor",darkness);put(values,"nightVision",nightVision);put(values,"heldBlockLightValue",heldLight);put(values,"heldBlockLightValue2",heldLight2);
        put(values,"screenBrightness",mc.options.gamma().get().floatValue());put(values,"playerMood",mc.player==null?0:mc.player.getCurrentMood());put(values,"hideGUI",mc.options.hideGui?1:0);
        var biome=mc.level.getBiome(BlockPos.containing(camera));var registry=mc.level.registryAccess().registryOrThrow(Registries.BIOME);
        put(values,"biome",registry.getId(biome.value()));put(values,"biome_precipitation",biome.value().getPrecipitationAt(BlockPos.containing(camera)).ordinal());put(values,"temperature",biome.value().getBaseTemperature());put(values,"rainfall",biome.value().getModifiedClimateSettings().downfall());
        whole(values,"cameraPosition",camera.x,camera.y,camera.z);whole(values,"previousCameraPosition",previousCamera.x,previousCamera.y,previousCamera.z);
        var look=entity==null?camera:entity.getEyePosition(partialTick);put(values,"eyePosition",(float)look.x,(float)look.y,(float)look.z);put(values,"relativeEyePosition",(float)(camera.x-look.x),(float)(camera.y-look.y),(float)(camera.z-look.z));
        var direction=entity==null?new net.minecraft.world.phys.Vec3(0,0,1):entity.getViewVector(partialTick);put(values,"playerLookVector",(float)direction.x,(float)direction.y,(float)direction.z);
        // Same scale the vanilla lightmap applies for the Darkness effect.
        float darknessLight=entity==null?0:Math.max(0,(float)Math.cos((entity.tickCount-partialTick)*Math.PI*.025)*.45f*darkness)*mc.options.darknessEffectScale().get().floatValue();put(values,"darknessLightFactor",darknessLight);
        put(values,"cloudHeight",mc.level.effects().getCloudHeight());put(values,"isElytraFlying",mc.player!=null&&mc.player.isFallFlying()?1:0);
        float[] bolt={0,0,0,0};for(var candidate:mc.level.entitiesForRendering())if(candidate instanceof net.minecraft.world.entity.LightningBolt){bolt=new float[]{(float)(candidate.getX()-camera.x),(float)(candidate.getY()-camera.y),(float)(candidate.getZ()-camera.z),1};break;}values.put("lightningBoltPosition",bolt);
        put(values,"heldItemId",mc.player==null?-1:items.id(itemName(mc.player.getMainHandItem())));put(values,"heldItemId2",mc.player==null?-1:items.id(itemName(mc.player.getOffhandItem())));
        // Per-draw identities need entity-aware batching; until then every draw reports "unmapped".
        put(values,"entityId",-1);put(values,"blockEntityId",-1);put(values,"currentRenderedItemId",-1);put(values,"entityColor",0,0,0,0);
        var atlas=mc.getModelManager().getAtlas(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);var sprite=atlas.getSprite(net.minecraft.client.renderer.texture.MissingTextureAtlasSprite.getLocation());
        put(values,"atlasSize",Math.round(sprite.contents().width()/(sprite.getU1()-sprite.getU0())),Math.round(sprite.contents().height()/(sprite.getV1()-sprite.getV0())));
        previousCamera.set(camera.x,camera.y,camera.z);previousProjection.set(projection);previousView.set(view);return values;
    }
    private static String itemName(net.minecraft.world.item.ItemStack stack){return net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();}
    /** Integer and fractional parts of a world position, for precision far from the origin. */
    private static void whole(Map<String,float[]> values,String name,double x,double y,double z){double fx=Math.floor(x),fy=Math.floor(y),fz=Math.floor(z);values.put(name+"Int",new float[]{(float)fx,(float)fy,(float)fz});values.put(name+"Fract",new float[]{(float)(x-fx),(float)(y-fy),(float)(z-fz)});}
    private static float light(net.minecraft.world.item.ItemStack stack){return stack.getItem() instanceof net.minecraft.world.item.BlockItem item?item.getBlock().defaultBlockState().getLightEmission():0;}
    private static void put(Map<String,float[]> values,String name,float...data){values.put(name,data);}
}
