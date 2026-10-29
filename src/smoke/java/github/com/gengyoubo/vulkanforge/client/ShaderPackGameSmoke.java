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
    private static boolean titleChecked;
    private static String referenceName;
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event){
        if(MODE.isEmpty()||stage==99||event.phase!=TickEvent.Phase.END)return;Minecraft mc=Minecraft.getInstance();mc.gui.vignetteBrightness=0;
        try{
            require((System.nanoTime()-started)/1e9<(stage<2?600:180),"Shader pack game smoke timed out at "+stage);
            if(stage==0&&mc.screen instanceof TitleScreen&&mc.getOverlay()==null&&++ticks>15){
                output=Path.of(System.getProperty("shaderPack.output"));Files.createDirectories(output);ShaderPackRuntime.select("");
                mc.options.pauseOnLostFocus=false;mc.options.renderDistance().set(3);mc.options.graphicsMode().set(net.minecraft.client.GraphicsStatus.FAST);mc.options.enableVsync().set(false);mc.getWindow().updateVsync(false);
                mc.options.tutorialStep=net.minecraft.client.tutorial.TutorialSteps.NONE;
                if(!titleChecked){titleChecked=true;GLFW.glfwSetWindowSize(mc.getWindow().getWindow(),1918,987);mc.setScreen(new ShaderPackScreen(mc.screen));next(20);return;}
                fixture(RED,"vec4(0.75,0.15,0.25,1.0)");fixture(GREEN,"vec4(0.1,0.65,0.2,1.0)");fixture(BAD,"invalid shader expression");
                String reference=System.getProperty("shaderPack.reference");if(reference!=null){Path pack=Path.of(reference);referenceName=pack.getFileName().toString();if(!Files.exists(ShaderPackRuntime.directory().resolve(referenceName)))github.com.gengyoubo.vulkanforge.shaderpack.ShaderPackImporter.copy(pack,ShaderPackRuntime.directory());}
                String name="Shader Pack Smoke "+System.currentTimeMillis();next(1);
                mc.createWorldOpenFlows().createFreshLevel(name,new LevelSettings(name,GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(1234L,false,false),r->r.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
            }else if(stage==1&&mc.level!=null&&mc.player!=null&&mc.screen==null){scene(mc);next(2);}
            else if(stage==20&&++ticks>25){capture(mc,"title-selection.png");next(21);}
            else if(stage==21&&++ticks>6){press(mc,"gui.cancel");require(mc.screen instanceof TitleScreen,"Cancel did not return to the parent screen");next(0);}
            else if(stage==2&&++ticks>80){ShaderPackRuntime.select(RED);next(3);}
            else if((stage==3||stage==7)&&!ShaderPackRuntime.status().error().isEmpty()){throw new AssertionError(ShaderPackRuntime.status().error());}
            else if(stage==3&&active(RED)){capture(mc,"red.png");next(4);}
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
            else if(stage==12&&++ticks>6){
                var image=javax.imageio.ImageIO.read(output.resolve("selection.png").toFile());require(image!=null,"Selection screen was not rendered");
                choose(mc,RED);require(ShaderPackRuntime.status().active().equals(GREEN),"Clicking a pack applied it before Apply");press(mc,"gui.cancel");
                require(mc.screen==null&&ShaderPackRuntime.status().active().equals(GREEN),"Cancel changed the running pack");
                mc.setScreen(new ShaderPackScreen(null));choose(mc,RED);press(mc,"vulkanforge.shaders.apply");next(22);
            }
            else if(stage==22&&active(RED)){require(mc.screen instanceof ShaderPackScreen,"Apply closed the selection screen");choose(mc,GREEN);press(mc,"gui.done");next(23);}
            else if(stage==23&&active(GREEN)&&mc.screen==null){mc.setScreen(new ShaderPackScreen(null));choose(mc,net.minecraft.network.chat.Component.translatable("vulkanforge.shaders.enabled").getString());press(mc,"gui.done");next(13);}
            else if(stage==13&&ShaderPackRuntime.status().active().isEmpty()&&++ticks>12){capture(mc,"disabled.png");next(14);}
            else if(stage==14&&++ticks>6){
                var image=javax.imageio.ImageIO.read(output.resolve("disabled.png").toFile());Set<Integer> colors=new HashSet<>();for(int y=0;y<image.getHeight();y+=5)for(int x=0;x<image.getWidth();x+=5)colors.add(image.getRGB(x,y));require(colors.size()>100,"Disabling the pack did not restore the world");
                if(referenceName!=null){ShaderPackRuntime.select(referenceName);next(30);}else{mc.setScreen(new ShaderPackScreen(null));next(32);}
            }
            else if(stage==30){require(ShaderPackRuntime.status().error().isEmpty(),ShaderPackRuntime.status().error());if(active(referenceName)){capture(mc,"reference.png");next(31);}}
            else if(stage==31&&++ticks>6){grayscale();ShaderPackRuntime.select("");next(33);}
            else if(stage==33&&ShaderPackRuntime.status().active().isEmpty()){mc.setScreen(new ShaderPackScreen(null));next(32);}
            else if(stage==32&&++ticks>20){capture(mc,"selection-world.png");next(34);}
            else if(stage==34&&++ticks>6){press(mc,"gui.cancel");complete(mc);}
        }catch(Throwable error){error.printStackTrace();try{if(output!=null)Files.writeString(output.resolve("result.txt"),"STATUS=FAILED\nSTAGE="+stage+"\nERROR="+error+"\n");}catch(Exception ignored){}stage=99;mc.stop();}
    }
    private static boolean active(String name){return ShaderPackRuntime.status().active().equals(name)&&ShaderPackRuntime.statistics()[0]>5;}
    private static void next(int value){stage=value;ticks=0;started=System.nanoTime();com.mojang.logging.LogUtils.getLogger().info("SHADER_PACK_SMOKE_STAGE={}",stage);}
    private static void fixture(String name,String color)throws Exception{Path root=ShaderPackRuntime.directory().resolve(name).resolve("shaders");Files.createDirectories(root);Files.writeString(root.resolve("final.vsh"),"#version 120\nvarying vec2 texcoord;\nvoid main(){texcoord=gl_MultiTexCoord0.xy;gl_Position=ftransform();}\n");Files.writeString(root.resolve("final.fsh"),"#version 120\nvarying vec2 texcoord;\nuniform sampler2D gcolor;\nvoid main(){gl_FragColor="+color+";}\n");}
    private static void scene(Minecraft mc){var server=mc.getSingleplayerServer();var id=mc.player.getUUID();server.execute(()->{var level=server.overworld();level.setDayTime(6000);level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,server);for(int x=-10;x<=10;x++)for(int z=-8;z<=8;z++)level.setBlock(new BlockPos(x,64,z),Blocks.STONE_BRICKS.defaultBlockState(),3);var player=server.getPlayerList().getPlayer(id);player.teleportTo(level,.5,68,8,180,18);player.getAbilities().flying=true;player.onUpdateAbilities();player.setItemInHand(InteractionHand.MAIN_HAND,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD));});}
    private static void capture(Minecraft mc,String name)throws Exception{if(MODE.equals("VK"))NativeGameWindow.captureNext(output.resolve(name));else try(var image=net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(output.resolve(name));}}
    private static void color(String name,int red,int green,int blue)throws Exception{var image=javax.imageio.ImageIO.read(output.resolve(name).toFile());require(image!=null,"Missing screenshot "+name);int value=image.getRGB(20,image.getHeight()/2);require(Math.abs((value>>16&255)-red)<=3&&Math.abs((value>>8&255)-green)<=3&&Math.abs((value&255)-blue)<=3,"Unexpected world pixel in "+name+": 0x"+Integer.toHexString(value));}
    private static void complete(Minecraft mc)throws Exception{
        if(MODE.equals("VK")){require(NativeGameWindow.enabled()&&GLFW.glfwGetCurrentContext()==0,"Native context contract");require(NativeGameWindow.stats()[5]==0,"Vulkan validation errors");require(System.getProperty("vulkanforge.forbiddenGlCalls","missing").equals("0"),"Forbidden OpenGL call");}
        Files.writeString(output.resolve("result.txt"),"STATUS=OK\nMODE="+MODE+"\nHOT_SWITCH=true\nCOMPILE_FAILURE_PRESERVED_ACTIVE=true\nRESIZE=true\nSELECTION_UI=true\nDRAFT_CANCEL=true\nAPPLY_STAYS_OPEN=true\nDONE_APPLIES_AND_CLOSES=true\nDISABLE=true\nEXTERNAL_PACK="+Objects.toString(referenceName,"NOT_REQUESTED")+"\n");stage=99;mc.stop();
    }
    private static void grayscale()throws Exception{
        var image=javax.imageio.ImageIO.read(output.resolve("reference.png").toFile());var original=javax.imageio.ImageIO.read(output.resolve("disabled.png").toFile());
        Set<Integer> shades=new HashSet<>();for(int y=45;y<image.getHeight()-90;y+=9)for(int x=10;x<image.getWidth()-10;x+=9){int pixel=image.getRGB(x,y),r=pixel>>16&255,g=pixel>>8&255,b=pixel&255;require(Math.abs(r-g)<=3&&Math.abs(g-b)<=3,"External grayscale pack did not process the world");shades.add(r);}
        require(shades.size()>8,"External pack returned a constant image");int samples=0,matches=0;
        // Fixed platform region: compare the external shader's actual dot(RGB, vec3(.333)) operation.
        for(int y=(int)(image.getHeight()*.65);y<image.getHeight()*.8;y+=4)for(int x=(int)(image.getWidth()*.2);x<image.getWidth()*.6;x+=4){int pixel=original.getRGB(x,y);float expected=((pixel>>16&255)+(pixel>>8&255)+(pixel&255))*.333f;if(Math.abs((image.getRGB(x,y)>>16&255)-expected)<=3)matches++;samples++;}
        require(matches>samples*.98,"External shader texture sampling changed scene coordinates or grayscale values: "+matches+"/"+samples);
        Files.writeString(output.resolve("reference-pixels.txt"),"MATCHED="+matches+"\nSAMPLED="+samples+"\nGRAY_LEVELS="+shades.size()+"\n");
    }
    private static void press(Minecraft mc,String key){String label=net.minecraft.network.chat.Component.translatable(key).getString();for(var child:mc.screen.children())if(child instanceof net.minecraft.client.gui.components.Button button&&button.getMessage().getString().equals(label)){require(button.active,"Inactive button: "+label);button.onPress();return;}throw new AssertionError("Missing button: "+label);}
    private static void choose(Minecraft mc,String label){for(var child:mc.screen.children())if(child instanceof net.minecraft.client.gui.components.ObjectSelectionList<?> list){int index=0;for(var row:list.children()){if(row.getNarration().getString().equals(label)){require(mc.screen.mouseClicked(mc.screen.width/2.,34+4+20*index-list.getScrollAmount()+8,0),"Row did not accept click: "+label);return;}index++;}}throw new AssertionError("Missing row: "+label);}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
