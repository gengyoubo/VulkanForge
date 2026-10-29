package github.com.gengyoubo.vulkanforge.client;

import java.nio.file.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/** Actual Forge game test. The bootstrap agent fails any GL entry point anywhere in this JVM. */
@Mod.EventBusSubscriber(modid="vulkanforge",value=Dist.CLIENT)
public final class NativeGameSmoke {
    private static int stage,ticks;
    private static long start=System.nanoTime();
    private static java.util.concurrent.CompletableFuture<Void> reload;
    private static Path report;
    private static int[] beforeResize,resizedExtent;
    private static java.util.Map<String,Long> cacheBefore;
    private static long cacheBeforeReload;
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if(!Boolean.getBoolean("vulkanforge.nativeGameSmoke")||event.phase!=TickEvent.Phase.END||stage==99)return;
        Minecraft mc=Minecraft.getInstance();
        try {
            if((System.nanoTime()-start)/1e9>(stage==0?600:240))throw new AssertionError("Native game smoke timed out at stage "+stage);
            if(stage==0&&mc.screen instanceof TitleScreen&&mc.getOverlay()==null&&++ticks>25) {
                report=mc.gameDirectory.toPath().resolve("logs/vulkanforge/native-game-smoke");Files.createDirectories(report);
                require(NativeGameWindow.enabled(),"Native bootstrap absent");require(GLFW.glfwGetCurrentContext()==0,"OpenGL context exists");
                require(GLFW.glfwGetWindowAttrib(mc.getWindow().getWindow(),GLFW.GLFW_CLIENT_API)==GLFW.GLFW_NO_API,"Game window has an API context");
                require(Files.readString(report.getParent().resolve("vk-native-compatibility.json")).contains("NATIVE_ACTIVE"),"Stale or missing native compatibility report");
                NativeGameWindow.captureNext(report.resolve("main-menu.png"));next(1);
            }else if(stage==1&&++ticks>10) {
                verify(report.resolve("main-menu.png"));
                if(Boolean.getBoolean("vulkanforge.nativeMenuOnly")){finish(mc);return;}
                mc.options.pauseOnLostFocus=false;mc.options.renderDistance().set(3);mc.options.graphicsMode().set(net.minecraft.client.GraphicsStatus.FANCY);
                String name="Native Vulkan Smoke "+System.currentTimeMillis();next(2);
                mc.createWorldOpenFlows().createFreshLevel(name,new LevelSettings(name,GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(1234L,false,false),registries->registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
            }else if(stage==2&&mc.level!=null&&mc.player!=null&&mc.screen==null){buildScene(mc);next(3);}
            else if(stage==3&&++ticks>100){NativeGameWindow.captureNext(report.resolve("world.png"));next(4);}
            else if(stage==4&&++ticks>10){verify(report.resolve("world.png"));long[] stats=NativeGameWindow.stats();require(stats[6]>0,"Terrain was not drawn by Vulkan");require(stats[7]>0,"No native entity/item draws");verifyEmbeddium();
                if(Boolean.getBoolean("vulkanforge.nativeHotbarCache")){
                    cacheBefore=NativeGameWindow.diagnostics();require(cacheBefore.get("hotbarCacheHits")>0&&cacheBefore.get("hotbarCacheBuilds")>=3,"Hotbar cache was not exercised");
                    mc.player.getInventory().getItem(1).setCount(32);next(20);
                }else{mc.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));next(5);}}
            else if(stage==20&&++ticks>10){
                var stats=NativeGameWindow.diagnostics();require(stats.get("hotbarCacheBuilds").equals(cacheBefore.get("hotbarCacheBuilds")),"Count-only change rebuilt model geometry");
                NativeGameWindow.captureNext(report.resolve("cache-count.png"));next(21);
            }else if(stage==21&&++ticks>3){
                verify(report.resolve("cache-count.png"));cacheBefore=NativeGameWindow.diagnostics();mc.player.getInventory().getItem(0).setDamageValue(71);next(22);
            }else if(stage==22&&++ticks>10){
                var stats=NativeGameWindow.diagnostics();require(stats.get("hotbarCacheBuilds")>cacheBefore.get("hotbarCacheBuilds"),"In-place damage/NBT change did not rebuild");
                NativeGameWindow.captureNext(report.resolve("cache-damage.png"));next(23);
            }else if(stage==23&&++ticks>3){
                verify(report.resolve("cache-damage.png"));cacheBefore=NativeGameWindow.diagnostics();mc.player.getInventory().setItem(0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND));next(24);
            }else if(stage==24&&++ticks>10){
                var stats=NativeGameWindow.diagnostics();require(stats.get("hotbarCacheBuilds")>cacheBefore.get("hotbarCacheBuilds"),"Item swap did not rebuild");
                NativeGameWindow.captureNext(report.resolve("cache-swap.png"));next(25);
            }else if(stage==25&&++ticks>3){
                verify(report.resolve("cache-swap.png"));cacheBefore=NativeGameWindow.diagnostics();
                mc.player.getInventory().getItem(1).setPopTime(10);mc.player.getCooldowns().addCooldown(net.minecraft.world.item.Items.STONE,40);
                mc.player.getInventory().setItem(0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CLOCK));next(26);
            }else if(stage==26&&++ticks>3){
                require(NativeGameWindow.diagnostics().get("hotbarCacheFallbacks")>cacheBefore.get("hotbarCacheFallbacks"),"Animated/cooldown items did not fall back");
                NativeGameWindow.captureNext(report.resolve("cache-dynamic.png"));next(27);
            }else if(stage==27&&++ticks>3){
                verify(report.resolve("cache-dynamic.png"));Files.writeString(report.resolve("hotbar-cache.txt"),"COUNT_NO_REBUILD=PASS\nDAMAGE_NBT_REBUILD=PASS\nITEM_SWAP_REBUILD=PASS\nDYNAMIC_FALLBACK=PASS\nDIAGNOSTICS="+NativeGameWindow.diagnostics()+"\n");
                mc.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));next(5);
            }
            else if(stage==5&&++ticks>20){NativeGameWindow.captureNext(report.resolve("inventory.png"));next(6);}
            else if(stage==6&&++ticks>10){verify(report.resolve("inventory.png"));mc.setScreen(null);beforeResize=NativeGameWindow.extent();if(mc.getWindow().isFullscreen())mc.getWindow().toggleFullScreen();GLFW.glfwRestoreWindow(mc.getWindow().getWindow());GLFW.glfwSetWindowSize(mc.getWindow().getWindow(),1024,640);next(7);}
            else if(stage==7&&++ticks>20){resizedExtent=NativeGameWindow.extent();require(!java.util.Arrays.equals(beforeResize,resizedExtent),"Window did not resize");NativeGameWindow.captureNext(report.resolve("resized.png"));next(8);}
            else if(stage==8&&++ticks>10){verify(report.resolve("resized.png"));var image=javax.imageio.ImageIO.read(report.resolve("resized.png").toFile());require(image.getWidth()==resizedExtent[0]&&image.getHeight()==resizedExtent[1],"Swapchain extent differs from the actual native framebuffer");cacheBeforeReload=NativeGameWindow.diagnostics().getOrDefault("hotbarCacheBuilds",0L);reload=mc.reloadResourcePacks();next(9);}
            else if(stage==9&&reload.isDone()&&mc.getOverlay()==null&&++ticks>30){reload.join();NativeGameWindow.captureNext(report.resolve("reloaded.png"));next(10);}
            else if(stage==10&&++ticks>10){verify(report.resolve("reloaded.png"));if(Boolean.getBoolean("vulkanforge.nativeHotbarCache"))require(NativeGameWindow.diagnostics().get("hotbarCacheBuilds")>cacheBeforeReload,"Resource reload did not rebuild cached geometry");mc.setScreen(null);mc.options.graphicsMode().set(net.minecraft.client.GraphicsStatus.FABULOUS);mc.levelRenderer.allChanged();next(11);}
            else if(stage==11&&mc.screen==null&&mc.getOverlay()==null&&++ticks>40){NativeGameWindow.captureNext(report.resolve("fabulous.png"));next(12);}
            else if(stage==12&&++ticks>10){verify(report.resolve("fabulous.png"));mc.level.disconnect();mc.clearLevel(new net.minecraft.client.gui.screens.GenericDirtMessageScreen(net.minecraft.network.chat.Component.translatable("menu.savingLevel")));mc.setScreen(new TitleScreen());next(13);}
            else if(stage==13&&mc.screen instanceof TitleScreen&&mc.getOverlay()==null&&++ticks>20){NativeGameWindow.captureNext(report.resolve("returned-menu.png"));next(14);}
            else if(stage==14&&++ticks>10){verify(report.resolve("returned-menu.png"));if(Boolean.getBoolean("vulkanforge.nativeHotbarCache"))require(NativeHotbarCache.diagnostics().get("hotbarCacheEntries")==0,"World exit retained cached items");finish(mc);}
        }catch(Throwable error){try{Path path=mc.gameDirectory.toPath().resolve("logs/vulkanforge/native-game-smoke/result.txt");Files.createDirectories(path.getParent());Files.writeString(path,"STATUS=FAILED\nSTAGE="+stage+"\nERROR="+error+"\n");}catch(Exception ignored){}error.printStackTrace();stage=99;mc.stop();}
    }
    private static void next(int value){stage=value;ticks=0;start=System.nanoTime();com.mojang.logging.LogUtils.getLogger().info("Native game smoke stage {}",value);}
    private static void buildScene(Minecraft mc){
        var server=mc.getSingleplayerServer();var uuid=mc.player.getUUID();server.execute(()->{
            var level=server.overworld();level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,server);level.setDayTime(6000);
            for(int x=-12;x<=12;x++)for(int z=-4;z<=16;z++)level.setBlock(new BlockPos(x,64,z),Blocks.STONE_BRICKS.defaultBlockState(),3);
            for(int x=-2;x<=2;x++)for(int y=65;y<=69;y++)level.setBlock(new BlockPos(x,y,0),Blocks.RED_CONCRETE.defaultBlockState(),3);
            for(int x=-2;x<=2;x++)for(int y=65;y<=69;y++)level.setBlock(new BlockPos(x,y,3),Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT,true),3);
            for(int x=-8;x<=-4;x++)for(int z=4;z<=8;z++)level.setBlock(new BlockPos(x,65,z),x==-8||x==-4||z==4||z==8?Blocks.STONE_BRICKS.defaultBlockState():Blocks.WATER.defaultBlockState(),3);
            for(int x=-1;x<=1;x++)for(int y=65;y<=66;y++)level.setBlock(new BlockPos(x,y,7),Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState(),3);
            level.setBlock(new BlockPos(3,65,6),Blocks.CHEST.defaultBlockState(),3);level.setBlock(new BlockPos(1,65,5),Blocks.CAMPFIRE.defaultBlockState(),3);
            var cow=net.minecraft.world.entity.EntityType.COW.spawn(level,new BlockPos(-3,65,6),net.minecraft.world.entity.MobSpawnType.COMMAND);if(cow!=null)cow.setNoAi(true);
            var player=server.getPlayerList().getPlayer(uuid);player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD));player.teleportTo(level,0.5,68,10,180,18);player.getAbilities().flying=true;player.onUpdateAbilities();
            if(Boolean.getBoolean("vulkanforge.nativeHotbarCache")){player.getInventory().setItem(1,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE,64));player.getInventory().setItem(2,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GLASS));}
        });
    }
    private static void finish(Minecraft mc)throws Exception {
        require(System.getProperty("vulkanforge.forbiddenGlCalls","missing").equals("0"),"Forbidden GL entry point was attempted");
        long[] stats=NativeGameWindow.stats();require(stats[0]>0&&stats[1]>0&&stats[5]==0,"No native game draws or validation error");
        Files.writeString(report.resolve("result.txt"),"STATUS=OK\nSCOPE="+(Boolean.getBoolean("vulkanforge.nativeMenuOnly")?"MINECRAFT_MAIN_MENU":"MINECRAFT_WORLD")+"\nOPENGL_CONTEXTS=0\nFORBIDDEN_GL_CALLS=0\nNATIVE_STATS="+java.util.Arrays.toString(stats)+"\n");stage=99;mc.stop();
    }
    private static void verifyEmbeddium()throws Exception {
        if(!net.minecraftforge.fml.ModList.get().isLoaded("embeddium"))return;
        Class<?> type=Class.forName("me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer");Object renderer=type.getMethod("instance").invoke(null);
        int visible=(Integer)type.getMethod("getVisibleChunkCount").invoke(renderer);
        var stats=NativeGameWindow.diagnostics();
        require(visible>0&&stats.getOrDefault("embeddiumMultiDrawBatches",0L)>0&&stats.getOrDefault("embeddiumTerrainDraws",0L)>0,"Embeddium's actual terrain renderer did not submit visible chunks to Vulkan");
        if((Boolean.getBoolean("vulkanforge.nativeModSubmission")||Boolean.getBoolean("vulkanforge.nativeRegionBridge"))&&!Boolean.getBoolean("vulkanforge.nativeLegacyModSubmission"))require(stats.getOrDefault("regionBridgeBatches",0L)>0,"Native region batch bridge was not exercised");
        if(Boolean.getBoolean("vulkanforge.nativeProfile")){require(stats.getOrDefault("visibilityGraphCalls",0L)>0,"Chunk graph profiling hook was not exercised");if(net.minecraftforge.fml.ModList.get().isLoaded("chloride"))require(stats.getOrDefault("chlorideCullingCalls",0L)>0,"Chloride callback profiling hook was not exercised");}
        Files.writeString(report.resolve("embeddium.txt"),"RENDERER="+type.getName()+"\nVISIBLE_SECTIONS="+visible+"\nNATIVE_DIAGNOSTICS="+stats+"\n");
    }
    private static void verify(Path path)throws Exception {var image=javax.imageio.ImageIO.read(path.toFile());require(image!=null,"Missing native game screenshot");java.util.Set<Integer> colors=new java.util.HashSet<>();for(int y=0;y<image.getHeight();y+=3)for(int x=0;x<image.getWidth();x+=3)colors.add(image.getRGB(x,y));require(colors.size()>64,"Native game screenshot is blank");}
    private static void require(boolean value,String reason){if(!value)throw new AssertionError(reason);}
}
