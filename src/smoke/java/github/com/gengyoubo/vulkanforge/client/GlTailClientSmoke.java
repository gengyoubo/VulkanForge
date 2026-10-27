package github.com.gengyoubo.vulkanforge.client;

import github.com.gengyoubo.vulkanforge.VulkanForge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.*;
import net.minecraftforge.fml.common.Mod;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

/** Opt-in actual Forge client check; never included in the mod jar. */
@Mod.EventBusSubscriber(modid=VulkanForge.MOD_ID,value=Dist.CLIENT)
public final class GlTailClientSmoke {
    private static int stage,ticks,width,height;
    private static Path out;
    private static String worldCapture;
    private static float[] tailDepth,vanillaDepth;
    private static CompletableFuture<Void> reload;
    private static long framesBefore,submitsBefore,groupsBefore;
    private static final long START=System.nanoTime();
    @SubscribeEvent(priority=EventPriority.LOWEST)
    public static void world(net.minecraftforge.client.event.RenderLevelStageEvent event) {
        if(!Boolean.getBoolean("vulkanforge.glTailSmoke")||worldCapture==null||stage==99
                ||event.getStage()!=net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_LEVEL)return;
        Minecraft mc=Minecraft.getInstance();String name=worldCapture;worldCapture=null;
        try {
            try(var image=net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(out.resolve(name+".png"));}
            var target=mc.getMainRenderTarget();var depth=org.lwjgl.system.MemoryUtil.memAllocFloat(target.width*target.height);
            int previous=glGetInteger(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER_BINDING);
            try {
                org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER,target.frameBufferId);
                glReadPixels(0,0,target.width,target.height,GL_DEPTH_COMPONENT,GL_FLOAT,depth);
                float[] values=new float[depth.remaining()];depth.get(values);
                if(name.equals("tail-world"))tailDepth=values;else vanillaDepth=values;
            }finally{org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER,previous);org.lwjgl.system.MemoryUtil.memFree(depth);}
        }catch(Throwable error){fail(mc,error);}
    }
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if(!Boolean.getBoolean("vulkanforge.glTailSmoke")||event.phase!=TickEvent.Phase.END||stage==99)return;
        Minecraft mc=Minecraft.getInstance();
        try {
            require(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-START)<300,"timeout stage="+stage);
            if(stage==0&&mc.screen instanceof TitleScreen&&mc.getOverlay()==null) {
                out=mc.gameDirectory.toPath().resolve("logs/vulkanforge/gl-tail-smoke");Files.createDirectories(out);
                for(String name:new String[]{"tail-world","vanilla-world","inventory","resized","fabulous"})Files.deleteIfExists(out.resolve(name+".png"));
                Files.writeString(out.resolve("report.txt"),"");checkWindow(mc);
                require(NativeMainWindow.presentedFrames()>0,"no GL swaps");
                mc.options.pauseOnLostFocus=false;mc.options.renderDistance().set(4);mc.options.graphicsMode().set(net.minecraft.client.GraphicsStatus.FANCY);
                mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);mc.options.gamma().set(0.0);
                width=mc.getWindow().getWidth();height=mc.getWindow().getHeight();
                String name="VulkanForge GL Tail Smoke "+System.currentTimeMillis();
                mc.createWorldOpenFlows().createFreshLevel(name,new LevelSettings(name,GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),
                        new WorldOptions(1234L,false,false),r->r.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
                log("PASS GL visible context and final swaps; created isolated world "+name);next(1);
            }else if(stage==1&&mc.player!=null&&mc.level!=null&&mc.screen==null) {
                var server=mc.getSingleplayerServer();var uuid=mc.player.getUUID();
                server.execute(()->{
                    var level=server.overworld();level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,server);level.setDayTime(6000);level.setWeatherParameters(6000,0,false,false);
                    for(int x=-12;x<=12;x++)for(int z=-12;z<=12;z++)level.setBlock(new BlockPos(x,64,z),Blocks.STONE_BRICKS.defaultBlockState(),3);
                    for(int x=-2;x<=2;x++)for(int y=65;y<=69;y++)level.setBlock(new BlockPos(x,y,0),Blocks.RED_CONCRETE.defaultBlockState(),3);
                    for(int x=-2;x<=2;x++)for(int y=65;y<=69;y++)level.setBlock(new BlockPos(x,y,3),Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT,true),3);
                    for(int x=-8;x<=-4;x++)for(int z=4;z<=8;z++)level.setBlock(new BlockPos(x,65,z),(x==-8||x==-4||z==4||z==8?Blocks.STONE_BRICKS:Blocks.WATER).defaultBlockState(),3);
                    for(int x=-1;x<=1;x++)for(int y=65;y<=66;y++)level.setBlock(new BlockPos(x,y,7),Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState(),3);
                    level.setBlock(new BlockPos(3,65,6),Blocks.CHEST.defaultBlockState(),3);
                    var cow=net.minecraft.world.entity.EntityType.COW.spawn(level,new BlockPos(-3,65,6),net.minecraft.world.entity.MobSpawnType.COMMAND);if(cow!=null)cow.setNoAi(true);
                    var player=server.getPlayerList().getPlayer(uuid);player.teleportTo(level,.5,68,11,180,18);player.getAbilities().flying=true;player.onUpdateAbilities();
                    player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD));
                });next(2);
            }else if(stage==2&&++ticks>60) {VulkanPreview.toggle();next(3);
            }else if(stage==3&&++ticks>100&&VulkanPreview.worldEpoch()>0&&VulkanPreview.capturedSections()>=8) {
                checkPlan(mc);require(VulkanPreview.translucentComposites()>0,"no GL translucent composites");
                require(TerrainPasses.stats()[0]>0,"terrain was not replaced");
                framesBefore=VulkanPreview.directWorldStats()[1];long[] interop=NativeMainWindow.interopStats();submitsBefore=interop[4];groupsBefore=interop[5];next(4);
            }else if(stage==4&&++ticks>20) {
                long frames=VulkanPreview.directWorldStats()[1]-framesBefore;long[] interop=NativeMainWindow.interopStats();
                require(frames>0&&interop[4]-submitsBefore==frames&&interop[5]-groupsBefore==frames*2,"steady terrain submits/handoffs "+java.util.Arrays.toString(interop));
                log("PASS steady interval: terrain frames="+frames+", one VK submit and two GL group calls per frame");worldCapture="tail-world";next(5);
            }else if(stage==5&&tailDepth!=null){VulkanPreview.toggle();next(6);
            }else if(stage==6&&++ticks>50){worldCapture="vanilla-world";next(7);
            }else if(stage==7&&vanillaDepth!=null){
                compare();VulkanPreview.toggle();next(8);
            }else if(stage==8&&++ticks>80&&VulkanPreview.worldEpoch()>0){
                checkPlan(mc);mc.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));next(9);
            }else if(stage==9&&++ticks>20){
                require(!GuiDraws.active()&&NativeMainWindow.guiPasses()==0&&NativeMainWindow.handPasses()==0,"inventory/hand captured by VK");
                NativeMainWindow.captureNext(out.resolve("inventory.png"));next(10);
            }else if(stage==10&&++ticks>10){
                require(Files.size(out.resolve("inventory.png"))>1000,"inventory GL capture missing");mc.setScreen(null);glfwSetWindowSize(mc.getWindow().getWindow(),900,600);next(11);
            }else if(stage==11&&++ticks>30&&mc.getMainRenderTarget().viewWidth==900){
                checkPlan(mc);NativeMainWindow.captureNext(out.resolve("resized.png"));glfwSetWindowSize(mc.getWindow().getWindow(),width,height);next(12);
            }else if(stage==12&&++ticks>30){
                mc.getWindow().toggleFullScreen();next(13);
            }else if(stage==13&&++ticks>30){checkWindow(mc);mc.getWindow().toggleFullScreen();reload=mc.reloadResourcePacks();next(14);
            }else if(stage==14&&reload.isDone()&&mc.getOverlay()==null&&++ticks>30){
                reload.join();checkPlan(mc);mc.options.graphicsMode().set(net.minecraft.client.GraphicsStatus.FABULOUS);mc.levelRenderer.allChanged();next(15);
            }else if(stage==15&&++ticks>80&&VulkanPreview.worldEpoch()>0){
                checkPlan(mc);require(PostEffects.stats()[0]==0,"GL-owned post chain entered Vulkan");
                NativeMainWindow.captureNext(out.resolve("fabulous.png"));next(16);
            }else if(stage==16&&++ticks>10){
                require(Files.size(out.resolve("fabulous.png"))>1000,"Fabulous GL output missing");
                require(glGetError()==GL_NO_ERROR,"GL error");VulkanPreview.close();
                log("PASS GL hand/HUD/inventory, resize/fullscreen, F8 restart, resource reload and Fabulous GL fallback");log("STATUS=OK");stage=99;
                Thread watchdog=new Thread(()->{try{Thread.sleep(30_000);log("EXIT_TIMEOUT");}catch(Exception ignored){}Runtime.getRuntime().halt(3);},"GL-tail-smoke-exit-watchdog");watchdog.setDaemon(true);watchdog.start();mc.stop();
            }
        }catch(Throwable error){fail(mc,error);}
    }
    private static void checkWindow(Minecraft mc){
        require(NativeMainWindow.glTailCompatibility(),"GL tail inactive");long window=mc.getWindow().getWindow();
        require(glfwGetCurrentContext()==window&&glfwGetWindowAttrib(window,GLFW_CLIENT_API)==GLFW_OPENGL_API,"visible GL context lost");
        require(NativeMainWindow.swapchainGenerations()==0&&NativeMainWindow.acquiresBeforeInput()==0,"VK swapchain used");
        require(NativeMainWindow.skippedScreenBlits()==0,"normal GL final blit skipped");
    }
    private static void checkPlan(Minecraft mc){
        checkWindow(mc);require(VulkanPreview.directWorld(),"no Vulkan terrain");
        require(!NativeRenderTargets.shared(mc.getMainRenderTarget())&&NativeMainWindow.renderTargetStats()[0]==0,"GL targets exported");
        require(EntityDraws.stats()[0]==0&&GuiDraws.stats()[0]==0&&!EntityDraws.frameActive(),"compatibility draws captured by VK");
        long[] world=VulkanPreview.directWorldStats();require(world[7]>0&&world[18]==0&&world[19]==0&&world[24]==0,"VK overlay/sky entered tail");
    }
    private static void compare() throws Exception {
        var a=javax.imageio.ImageIO.read(out.resolve("tail-world.png").toFile());var b=javax.imageio.ImageIO.read(out.resolve("vanilla-world.png").toFile());
        require(a.getWidth()==b.getWidth()&&a.getHeight()==b.getHeight()&&tailDepth.length==vanillaDepth.length,"capture sizes changed");
        double sum=0;long bad=0,zbad=0;int pixels=a.getWidth()*a.getHeight();
        for(int y=0;y<a.getHeight();y++)for(int x=0;x<a.getWidth();x++){int c=a.getRGB(x,y),d=b.getRGB(x,y),worst=0;for(int s=0;s<=16;s+=8){int error=Math.abs((c>>s&255)-(d>>s&255));sum+=error;worst=Math.max(worst,error);}if(worst>48)bad++;}
        for(int i=0;i<tailDepth.length;i++){require(Float.isFinite(tailDepth[i])&&Float.isFinite(vanillaDepth[i]),"non-finite depth");if(Math.abs(tailDepth[i]-vanillaDepth[i])>2e-4)zbad++;}
        log("COMPARE GL tail / vanilla: RGB mean="+sum/(pixels*3.0)+", fraction >48="+(double)bad/pixels+", depth fraction >2e-4="+(double)zbad/pixels);
        require(sum/(pixels*3.0)<12&&(double)bad/pixels<.08&&(double)zbad/pixels<.03,"tail/vanilla scene mismatch");
    }
    private static void next(int value){stage=value;ticks=0;}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void log(String line) throws java.io.IOException {Files.writeString(out.resolve("report.txt"),line+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
    private static void fail(Minecraft mc,Throwable error){VulkanForge.LOGGER.error("GL tail smoke failed at stage "+stage,error);try{if(out!=null)log("STATUS=FAILED "+error);}catch(Exception ignored){}stage=99;mc.stop();}
}
