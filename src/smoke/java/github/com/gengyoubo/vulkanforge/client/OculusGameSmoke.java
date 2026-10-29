package github.com.gengyoubo.vulkanforge.client;

import java.nio.file.*;
import java.lang.reflect.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.*;
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

/** A real full-pack world test; fallback/preflight-only success is explicitly rejected. */
@Mod.EventBusSubscriber(modid="vulkanforge",value=Dist.CLIENT)
public final class OculusGameSmoke {
    private static final String MODE=System.getProperty("vulkanforge.oculusSmoke","");
    private static int stage,ticks;private static long started=System.nanoTime();private static Path output;
    private static String worldResult;
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event){
        if(MODE.isEmpty()||stage==99||event.phase!=TickEvent.Phase.END)return;Minecraft mc=Minecraft.getInstance();
        try{
            if(output==null){output=Path.of(System.getProperty("oculusSmoke.output"));Files.createDirectories(output);}
            require((System.nanoTime()-started)/1e9<900,"Full shader world smoke timed out at stage "+stage);
            if(stage==0&&mc.screen instanceof TitleScreen&&mc.getOverlay()==null&&++ticks>15){
                checkPack();mc.options.pauseOnLostFocus=false;mc.options.tutorialStep=net.minecraft.client.tutorial.TutorialSteps.NONE;
                String name="Complementary Full Pack "+System.currentTimeMillis();next(1);
                mc.createWorldOpenFlows().createFreshLevel(name,new LevelSettings(name,GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(1234L,false,false),r->r.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
            }else if(stage==1&&mc.level!=null&&mc.player!=null&&mc.screen==null){scene(mc);next(2);}
            else if(stage==2&&++ticks>180){checkPack();capture(mc,"world.png");next(3);}
            else if(stage==3&&++ticks>10){
                var image=javax.imageio.ImageIO.read(output.resolve("world.png").toFile());require(image!=null,"No rendered pack image");var colors=new java.util.HashSet<Integer>();for(int y=0;y<image.getHeight();y+=5)for(int x=0;x<image.getWidth();x+=5)colors.add(image.getRGB(x,y));require(colors.size()>1000,"Full pack output lost world detail: "+colors.size());
                Object manager=iris("getPipelineManager"),pipeline=manager.getClass().getMethod("getPipelineNullable").invoke(manager);String type=pipeline==null?"null":pipeline.getClass().getName();require(!type.contains("Vanilla")&&!type.equals("null"),"Only the fallback renderer ran: "+type);
                if(MODE.equals("VK")){require(NativeGameWindow.enabled()&&GLFW.glfwGetCurrentContext()==0,"A GL context exists");require(System.getProperty("vulkanforge.forbiddenGlCalls","missing").equals("0"),"A forbidden GL entry point was called");require(NativeGameWindow.stats()[5]==0,"Vulkan validation errors");Files.writeString(output.resolve("native-stats.txt"),java.util.Arrays.toString(NativeGameWindow.stats()));}
                worldResult="STATUS=OK\nMODE="+MODE+"\nPACK="+iris("getCurrentPackName")+"\nPIPELINE="+type+"\nWORLD_COLORS="+colors.size()+"\n";
                mc.setScreen(github.com.gengyoubo.vulkanforge.client.shader.ShaderPackFrontend.screen(null));require(mc.screen.getClass().getName().equals("net.irisshaders.iris.gui.screen.ShaderPackScreen"),"Wrong production shader GUI");next(4);
            }else if(stage==4&&++ticks>10){capture(mc,"shader-gui.png");next(5);}
            else if(stage==5&&++ticks>10){mc.setScreen(null);enable(false);next(6);}
            else if(stage==6&&++ticks>80){capture(mc,"disabled.png");next(7);}
            else if(stage==7&&++ticks>10){enable(true);GLFW.glfwSetWindowSize(mc.getWindow().getWindow(),1024,576);next(8);}
            else if(stage==8&&++ticks>140){checkPack();capture(mc,"reloaded.png");next(9);}
            else if(stage==9&&++ticks>10){var image=javax.imageio.ImageIO.read(output.resolve("reloaded.png").toFile());require(image.getWidth()==1024&&image.getHeight()==576,"Shader target did not resize");if(MODE.equals("VK")){require(GLFW.glfwGetCurrentContext()==0&&System.getProperty("vulkanforge.forbiddenGlCalls").equals("0"),"Hot reload used OpenGL");require(NativeGameWindow.stats()[5]==0,"Hot reload/resize validation errors");Files.writeString(output.resolve("native-stats.txt"),java.util.Arrays.toString(NativeGameWindow.stats()));}Files.writeString(output.resolve("result.txt"),worldResult+"PRODUCTION_GUI=true\nHOT_RELOAD=true\nRESIZE=true\n");stage=99;mc.stop();}
        }catch(Throwable failure){Throwable error=failure;while(error instanceof InvocationTargetException&&error.getCause()!=null)error=error.getCause();error.printStackTrace();try{Files.writeString(output.resolve("result.txt"),"STATUS=FAILED\nSTAGE="+stage+"\nERROR="+error+"\n");}catch(Exception ignored){}stage=99;mc.stop();}
    }
    private static Object iris(String name)throws Exception{return Class.forName("net.irisshaders.iris.Iris").getMethod(name).invoke(null);}
    private static void enable(boolean value)throws Exception{Object config=iris("getIrisConfig");config.getClass().getMethod("setShadersEnabled",boolean.class).invoke(config,value);config.getClass().getMethod("save").invoke(config);iris("reload");}
    private static void checkPack()throws Exception{
        String name=String.valueOf(iris("getCurrentPackName"));require(name.contains("ComplementaryReimagined_r5.9.3"),"Wrong/no shader pack: "+name);require(!(Boolean)iris("isFallback"),"Iris fell back from the actual pack");var error=(java.util.Optional<?>)iris("getStoredError");if(error.isPresent())throw new AssertionError("Pack pipeline failed",(Throwable)error.get());
    }
    private static void next(int value){stage=value;ticks=0;started=System.nanoTime();com.mojang.logging.LogUtils.getLogger().info("OCULUS_FULL_PACK_STAGE={}",stage);}
    private static void capture(Minecraft mc,String file)throws Exception{if(MODE.equals("VK"))NativeGameWindow.captureNext(output.resolve(file));else try(var image=net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(output.resolve(file));}}
    private static void scene(Minecraft mc){var server=mc.getSingleplayerServer();var id=mc.player.getUUID();server.execute(()->{var level=server.overworld();level.setDayTime(4500);level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,server);for(int x=-16;x<=16;x++)for(int z=-16;z<=16;z++)level.setBlock(new BlockPos(x,64,z),Blocks.STONE_BRICKS.defaultBlockState(),3);for(int y=65;y<72;y++)for(int x=0;x<2;x++)for(int z=0;z<2;z++)level.setBlock(new BlockPos(x,y,z),Blocks.STONE.defaultBlockState(),3);for(int x=-7;x<=-3;x++)for(int z=-4;z<=0;z++){level.setBlock(new BlockPos(x,64,z),Blocks.WATER.defaultBlockState(),3);level.setBlock(new BlockPos(x,63,z),Blocks.SAND.defaultBlockState(),3);}for(int x=4;x<8;x++)level.setBlock(new BlockPos(x,65,0),Blocks.OAK_LEAVES.defaultBlockState(),3);level.setBlock(new BlockPos(3,65,2),Blocks.TORCH.defaultBlockState(),3);var cow=net.minecraft.world.entity.EntityType.COW.create(level);if(cow!=null){cow.moveTo(4,65,4,180,0);cow.setNoAi(true);level.addFreshEntity(cow);}var player=server.getPlayerList().getPlayer(id);player.teleportTo(level,.5,68,12,180,18);player.getAbilities().flying=true;player.onUpdateAbilities();player.setItemInHand(InteractionHand.MAIN_HAND,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD));});}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
