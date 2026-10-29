package github.com.gengyoubo.vulkanforge.client;

import github.com.gengyoubo.vulkanforge.client.shader.ShaderPackRuntime;
import java.nio.file.*;
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
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/** Original Complementary pack through VulkanForge's production loader; no reference loader is allowed. */
@Mod.EventBusSubscriber(modid="vulkanforge",value=Dist.CLIENT)
public final class OwnPackGameSmoke {
    private static final String MODE=System.getProperty("vulkanforge.ownPackSmoke","");
    private static final String PACK=System.getProperty("ownPack.pack","ComplementaryReimagined_r5.9.3.zip");
    private static int stage,ticks;private static long started=System.nanoTime();private static Path output;
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event){
        if(MODE.isEmpty()||stage==99||event.phase!=TickEvent.Phase.END)return;var mc=Minecraft.getInstance();
        try{
            if(output==null){output=Path.of(System.getProperty("ownPack.output"));Files.createDirectories(output);}
            if((stage>=11||stage==3||stage==4)&&mc.player!=null){
                // Shader compilation stalls can queue real mouse motion. Keep the isolated fixture's camera fixed.
                mc.mouseHandler.releaseMouse();mc.player.setYRot(180);mc.player.yRotO=180;mc.player.setXRot(18);mc.player.xRotO=18;
            }
            require((System.nanoTime()-started)/1e9<600,"Own pack test timed out at stage "+stage);
            require(!net.minecraftforge.fml.ModList.get().isLoaded("oculus")&&!net.minecraftforge.fml.ModList.get().isLoaded("iris"),"External shader loader present");
            if(stage==0&&mc.screen instanceof TitleScreen&&mc.getOverlay()==null&&++ticks>15){mc.options.pauseOnLostFocus=false;mc.options.tutorialStep=net.minecraft.client.tutorial.TutorialSteps.NONE;ShaderPackRuntime.select("");next(10);}
            else if(stage==10&&ShaderPackRuntime.status().active().isEmpty()){
                String name="Own Shader Scene "+System.currentTimeMillis();next(2);mc.createWorldOpenFlows().createFreshLevel(name,new LevelSettings(name,GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(1234,false,false),r->r.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
            }else if(stage==2&&mc.level!=null&&mc.player!=null&&mc.screen==null){scene(mc);next(11);}
            else if(stage==11&&++ticks>180&&mc.level.getBlockState(new BlockPos(0,65,0)).is(Blocks.STONE)&&Math.abs(mc.player.getY()-68)<1){capture(mc,"baseline.png");next(12);}
            else if(stage==12&&++ticks>10){require(terrainEdges(output.resolve("baseline.png"))>.01,"Test fixture terrain did not render before enabling the pack");ShaderPackRuntime.select(PACK);next(13);}
            else if(stage==13){require(ShaderPackRuntime.status().error().isEmpty(),ShaderPackRuntime.status().error());if(ShaderPackRuntime.status().active().equals(PACK))next(3);}
            else if(stage==3){require(ShaderPackRuntime.status().error().isEmpty(),ShaderPackRuntime.status().error());require(ShaderPackRuntime.status().active().equals(PACK),"Original pack was disabled");if(++ticks>180){long[] stats=ShaderPackRuntime.statistics();require(stats[0]>30&&stats[1]>100&&stats[2]>100,"Pack did not execute world/fullscreen passes: "+java.util.Arrays.toString(stats));capture(mc,"world.png");next(4);}}
            else if(stage==4&&++ticks>10){github.com.gengyoubo.vulkanforge.client.shader.OwnPackDiagnostics.capture(output.resolve("attachments"));var image=javax.imageio.ImageIO.read(output.resolve("world.png").toFile());var colors=new java.util.HashSet<Integer>();for(int y=0;y<image.getHeight();y+=4)for(int x=0;x<image.getWidth();x+=4)colors.add(image.getRGB(x,y));require(colors.size()>1000,"World output lacks detail: "+colors.size());double edges=terrainEdges(output.resolve("world.png"));require(edges>.01,"Pack lost the test terrain's texture/geometry edges: "+edges);if(MODE.equals("VK")){require(GLFW.glfwGetCurrentContext()==0&&System.getProperty("vulkanforge.forbiddenGlCalls").equals("0"),"OpenGL was used");require(NativeGameWindow.stats()[5]==0,"Vulkan validation errors");}Files.writeString(output.resolve("result.txt"),"STATUS=OK\nFRONTEND=VULKANFORGE\nEXTERNAL_LOADER=NONE\nMODE="+MODE+"\nPACK="+PACK+"\nWORLD_COLORS="+colors.size()+"\nTERRAIN_EDGES="+edges+"\nPASSES="+java.util.Arrays.toString(ShaderPackRuntime.statistics())+"\n");stage=99;mc.stop();}
        }catch(Throwable error){error.printStackTrace();try{Files.writeString(output.resolve("result.txt"),"STATUS=FAILED\nSTAGE="+stage+"\nERROR="+error+"\n");}catch(Exception ignored){}stage=99;mc.stop();}
    }
    private static void next(int value){stage=value;ticks=0;started=System.nanoTime();com.mojang.logging.LogUtils.getLogger().info("OWN_PACK_STAGE={}",stage);}
    private static void capture(Minecraft mc,String file)throws Exception{if(MODE.equals("VK"))NativeGameWindow.captureNext(output.resolve(file));else try(var image=net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(output.resolve(file));}}
    private static void scene(Minecraft mc){var server=mc.getSingleplayerServer();var id=mc.player.getUUID();server.execute(()->{var world=server.overworld();world.setDayTime(4500);world.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,server);for(int x=-12;x<=12;x++)for(int z=-12;z<=12;z++)world.setBlock(new BlockPos(x,64,z),Blocks.STONE_BRICKS.defaultBlockState(),3);for(int y=65;y<72;y++)for(int x=0;x<2;x++)for(int z=0;z<2;z++)world.setBlock(new BlockPos(x,y,z),Blocks.STONE.defaultBlockState(),3);for(int x=-7;x<=-3;x++)for(int z=-4;z<=0;z++){world.setBlock(new BlockPos(x,64,z),Blocks.WATER.defaultBlockState(),3);world.setBlock(new BlockPos(x,63,z),Blocks.SAND.defaultBlockState(),3);}var player=server.getPlayerList().getPlayer(id);player.teleportTo(world,.5,68,12,180,18);player.getAbilities().flying=true;player.onUpdateAbilities();player.setItemInHand(InteractionHand.MAIN_HAND,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD));});}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static double terrainEdges(Path path)throws Exception{var image=javax.imageio.ImageIO.read(path.toFile());int edges=0,samples=0;for(int y=(int)(image.getHeight()*.55);y<image.getHeight()*.85;y++)for(int x=(int)(image.getWidth()*.1);x<image.getWidth()*.7;x++){int a=image.getRGB(x,y),b=image.getRGB(x+1,y);int difference=Math.abs((a>>16&255)-(b>>16&255))+Math.abs((a>>8&255)-(b>>8&255))+Math.abs((a&255)-(b&255));if(difference>24)edges++;samples++;}return (double)edges/samples;}
}
