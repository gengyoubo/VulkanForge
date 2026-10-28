package github.com.gengyoubo.vulkanforge.client;

import github.com.gengyoubo.vulkanforge.client.shader.*;
import java.nio.file.*;
import java.util.*;
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

/** Actual in-world hot-switch, failed compilation rollback, resize, selection UI and disable, on either API. */
@Mod.EventBusSubscriber(modid="vulkanforge",value=Dist.CLIENT)
public final class ShaderPackGameSmoke {
    private static final String MODE=System.getProperty("vulkanforge.shaderPackSmoke","");
    private static final String RED="VF Smoke Red",GREEN="VF Smoke Green",BAD="VF Smoke Invalid";
    private static int stage,ticks;private static long started=System.nanoTime(),beforeFailure;private static Path output;
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event){
        if(MODE.isEmpty()||stage==99||event.phase!=TickEvent.Phase.END)return;Minecraft mc=Minecraft.getInstance();
        try{
            require((System.nanoTime()-started)/1e9<(stage<2?600:180),"Shader pack game smoke timed out at "+stage);
            if(stage==0&&mc.screen instanceof TitleScreen&&mc.getOverlay()==null&&++ticks>15){
                output=Path.of(System.getProperty("shaderPack.output"));Files.createDirectories(output);ShaderPackRuntime.select("");
                mc.options.pauseOnLostFocus=false;mc.options.renderDistance().set(3);mc.options.enableVsync().set(false);mc.getWindow().updateVsync(false);
                fixture(RED,"vec4(0.75,0.15,0.25,1.0)");fixture(GREEN,"vec4(0.1,0.65,0.2,1.0)");fixture(BAD,"invalid shader expression");
                String name="Shader Pack Smoke "+System.currentTimeMillis();next(1);
                mc.createWorldOpenFlows().createFreshLevel(name,new LevelSettings(name,GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(1234L,false,false),r->r.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
            }else if(stage==1&&mc.level!=null&&mc.player!=null&&mc.screen==null){scene(mc);next(2);}
            else if(stage==2&&++ticks>80){ShaderPackRuntime.select(RED);next(3);}
            else if(stage==3&&active(RED)){require(ShaderPackRuntime.status().error().isEmpty(),ShaderPackRuntime.status().error());capture(mc,"red.png");next(4);}
            else if(stage==4&&++ticks>6){color("red.png",191,38,64);beforeFailure=ShaderPackRuntime.statistics()[0];ShaderPackRuntime.select(BAD);next(5);}
            else if(stage==5&&!ShaderPackRuntime.status().error().isEmpty()){
                require(ShaderPackRuntime.status().active().equals(RED),"Compile failure discarded the working pack");if(ShaderPackRuntime.statistics()[0]<=beforeFailure+3)return;
                Files.writeString(output.resolve("compile-error.txt"),ShaderPackRuntime.status().error());capture(mc,"after-error.png");next(6);
            }else if(stage==6&&++ticks>6){color("after-error.png",191,38,64);ShaderPackRuntime.select(GREEN);next(7);}
            else if(stage==7&&active(GREEN)){capture(mc,"green.png");next(8);}
            else if(stage==8&&++ticks>6){color("green.png",26,166,51);GLFW.glfwSetWindowSize(mc.getWindow().getWindow(),1024,640);next(9);}
            else if(stage==9&&mc.getMainRenderTarget().viewWidth==1024&&mc.getMainRenderTarget().viewHeight==640&&++ticks>20){capture(mc,"resized.png");next(10);}
            else if(stage==10&&++ticks>6){color("resized.png",26,166,51);mc.setScreen(new ShaderPackScreen(null));next(11);}
            else if(stage==11&&++ticks>20){capture(mc,"selection.png");next(12);}
            else if(stage==12&&++ticks>6){var image=javax.imageio.ImageIO.read(output.resolve("selection.png").toFile());require(image!=null,"Selection screen was not rendered");mc.setScreen(null);ShaderPackRuntime.select("");next(13);}
            else if(stage==13&&ShaderPackRuntime.status().active().isEmpty()&&++ticks>12){capture(mc,"disabled.png");next(14);}
            else if(stage==14&&++ticks>6){
                var image=javax.imageio.ImageIO.read(output.resolve("disabled.png").toFile());Set<Integer> colors=new HashSet<>();for(int y=0;y<image.getHeight();y+=5)for(int x=0;x<image.getWidth();x+=5)colors.add(image.getRGB(x,y));require(colors.size()>100,"Disabling the pack did not restore the world");
                if(MODE.equals("VK")){require(NativeGameWindow.enabled()&&GLFW.glfwGetCurrentContext()==0,"Native context contract");require(NativeGameWindow.stats()[5]==0,"Vulkan validation errors");require(System.getProperty("vulkanforge.forbiddenGlCalls","missing").equals("0"),"Forbidden OpenGL call");}
                Files.writeString(output.resolve("result.txt"),"STATUS=OK\nMODE="+MODE+"\nHOT_SWITCH=true\nCOMPILE_FAILURE_PRESERVED_ACTIVE=true\nRESIZE=true\nSELECTION_UI=true\nDISABLE=true\n");stage=99;mc.stop();
            }
        }catch(Throwable error){error.printStackTrace();try{if(output!=null)Files.writeString(output.resolve("result.txt"),"STATUS=FAILED\nSTAGE="+stage+"\nERROR="+error+"\n");}catch(Exception ignored){}stage=99;mc.stop();}
    }
    private static boolean active(String name){return ShaderPackRuntime.status().active().equals(name)&&ShaderPackRuntime.statistics()[0]>5;}
    private static void next(int value){stage=value;ticks=0;started=System.nanoTime();com.mojang.logging.LogUtils.getLogger().info("SHADER_PACK_SMOKE_STAGE={}",stage);}
    private static void fixture(String name,String color)throws Exception{Path root=ShaderPackRuntime.directory().resolve(name).resolve("shaders");Files.createDirectories(root);Files.writeString(root.resolve("final.vsh"),"#version 120\nvarying vec2 texcoord;\nvoid main(){texcoord=gl_MultiTexCoord0.xy;gl_Position=ftransform();}\n");Files.writeString(root.resolve("final.fsh"),"#version 120\nvarying vec2 texcoord;\nuniform sampler2D gcolor;\nvoid main(){gl_FragColor="+color+";}\n");}
    private static void scene(Minecraft mc){var server=mc.getSingleplayerServer();var id=mc.player.getUUID();server.execute(()->{var level=server.overworld();level.setDayTime(6000);level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,server);for(int x=-10;x<=10;x++)for(int z=-8;z<=8;z++)level.setBlock(new BlockPos(x,64,z),Blocks.STONE_BRICKS.defaultBlockState(),3);var player=server.getPlayerList().getPlayer(id);player.teleportTo(level,.5,68,8,180,18);player.getAbilities().flying=true;player.onUpdateAbilities();player.setItemInHand(InteractionHand.MAIN_HAND,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD));});}
    private static void capture(Minecraft mc,String name)throws Exception{if(MODE.equals("VK"))NativeGameWindow.captureNext(output.resolve(name));else try(var image=net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(output.resolve(name));}}
    private static void color(String name,int red,int green,int blue)throws Exception{var image=javax.imageio.ImageIO.read(output.resolve(name).toFile());require(image!=null,"Missing screenshot "+name);int value=image.getRGB(20,image.getHeight()/2);require(Math.abs((value>>16&255)-red)<=3&&Math.abs((value>>8&255)-green)<=3&&Math.abs((value&255)-blue)<=3,"Unexpected world pixel in "+name+": 0x"+Integer.toHexString(value));}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
