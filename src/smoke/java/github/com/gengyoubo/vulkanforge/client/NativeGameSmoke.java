package github.com.gengyoubo.vulkanforge.client;

import java.nio.file.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
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
    private static Path report;
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if(!Boolean.getBoolean("vulkanforge.nativeGameSmoke")||event.phase!=TickEvent.Phase.END||stage==99)return;
        Minecraft mc=Minecraft.getInstance();
        try {
            if((System.nanoTime()-start)/1e9>180)throw new AssertionError("Native game smoke timed out at stage "+stage);
            if(stage==0&&mc.screen instanceof TitleScreen&&mc.getOverlay()==null&&++ticks>25) {
                report=mc.gameDirectory.toPath().resolve("logs/vulkanforge/native-game-smoke");Files.createDirectories(report);
                require(NativeGameWindow.enabled(),"Native bootstrap absent");require(GLFW.glfwGetCurrentContext()==0,"OpenGL context exists");
                require(GLFW.glfwGetWindowAttrib(mc.getWindow().getWindow(),GLFW.GLFW_CLIENT_API)==GLFW.GLFW_NO_API,"Game window has an API context");
                NativeGameWindow.captureNext(report.resolve("main-menu.png"));stage=1;ticks=0;
            }else if(stage==1&&++ticks>10) {
                verify(report.resolve("main-menu.png"));
                if(Boolean.getBoolean("vulkanforge.nativeMenuOnly")){finish(mc);return;}
                mc.options.pauseOnLostFocus=false;mc.options.renderDistance().set(3);mc.options.graphicsMode().set(net.minecraft.client.GraphicsStatus.FANCY);
                String name="Native Vulkan Smoke "+System.currentTimeMillis();stage=2;ticks=0;
                mc.createWorldOpenFlows().createFreshLevel(name,new LevelSettings(name,GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(1234L,false,false),registries->registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
            }else if(stage==2&&mc.level!=null&&mc.player!=null&&mc.screen==null){stage=3;ticks=0;}
            else if(stage==3&&++ticks>100){NativeGameWindow.captureNext(report.resolve("world.png"));stage=4;ticks=0;}
            else if(stage==4&&++ticks>10){verify(report.resolve("world.png"));finish(mc);}
        }catch(Throwable error){try{Path path=mc.gameDirectory.toPath().resolve("logs/vulkanforge/native-game-smoke/result.txt");Files.createDirectories(path.getParent());Files.writeString(path,"STATUS=FAILED\nSTAGE="+stage+"\nERROR="+error+"\n");}catch(Exception ignored){}error.printStackTrace();stage=99;mc.stop();}
    }
    private static void finish(Minecraft mc)throws Exception {
        require(System.getProperty("vulkanforge.forbiddenGlCalls","missing").equals("0"),"Forbidden GL entry point was attempted");
        long[] stats=NativeGameWindow.stats();require(stats[0]>0&&stats[1]>0&&stats[5]==0,"No native game draws or validation error");
        Files.writeString(report.resolve("result.txt"),"STATUS=OK\nSCOPE="+(Boolean.getBoolean("vulkanforge.nativeMenuOnly")?"MINECRAFT_MAIN_MENU":"MINECRAFT_WORLD")+"\nOPENGL_CONTEXTS=0\nFORBIDDEN_GL_CALLS=0\nNATIVE_STATS="+java.util.Arrays.toString(stats)+"\n");stage=99;mc.stop();
    }
    private static void verify(Path path)throws Exception {var image=javax.imageio.ImageIO.read(path.toFile());require(image!=null,"Missing native game screenshot");java.util.Set<Integer> colors=new java.util.HashSet<>();for(int y=0;y<image.getHeight();y+=3)for(int x=0;x<image.getWidth();x+=3)colors.add(image.getRGB(x,y));require(colors.size()>64,"Native game screenshot is blank");}
    private static void require(boolean value,String reason){if(!value)throw new AssertionError(reason);}
}
