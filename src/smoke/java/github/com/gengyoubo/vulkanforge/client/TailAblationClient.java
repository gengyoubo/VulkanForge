package github.com.gengyoubo.vulkanforge.client;

import com.google.gson.*;
import github.com.gengyoubo.vulkanforge.VulkanForge;
import github.com.gengyoubo.vulkanforge.interop.GlInterop;
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
import java.nio.file.*;
import java.util.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

/** Actual Forge render loop; fixed world/camera and randomized A/B/C/D blocks. */
@Mod.EventBusSubscriber(modid=VulkanForge.MOD_ID,value=Dist.CLIENT)
public final class TailAblationClient {
    private record Job(int round,boolean effect,String variant){}
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private static final List<Job> jobs=new ArrayList<>();
    private static final List<Map<String,Object>> runs=new ArrayList<>();
    private static final long START=System.nanoTime();
    private static Path out,folder;
    private static int stage,ticks,index;
    private static long warmStart,measureStart,measureEpoch,jobStart;
    private static long[] world0,interop0,post0,render0,gl0,terrain0,uploads0;
    private static double[] profile0;
    private static boolean activeEffect;
    private static double seconds,warmup;
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event){
        if(!Boolean.getBoolean("vulkanforge.tailAblation")||event.phase!=TickEvent.Phase.END||stage==99)return;
        Minecraft mc=Minecraft.getInstance();
        try {
            require((System.nanoTime()-START)/1e9<900,"ablation timeout stage="+stage+" job="+index);
            if(stage==0&&mc.screen instanceof TitleScreen&&mc.getOverlay()==null){
                out=Path.of(System.getProperty("tailBench.output","build/reports/tail-game-ablation")).toAbsolutePath();
                // JavaExec runs from run/. Default reports are always placed under the repository build directory.
                if(System.getProperty("tailBench.output")==null)out=mc.gameDirectory.toPath().resolve("../build/reports/tail-game-ablation").toAbsolutePath().normalize();
                if(Files.exists(out)&&Files.exists(out.resolve("runs.json")))throw new IllegalStateException("Choose a fresh tailBench.output");
                Files.createDirectories(out);Files.writeString(out.resolve("status.txt"),"RUNNING\n");
                Files.writeString(out.resolve("pid.txt"),Long.toString(ProcessHandle.current().pid()));
                Files.writeString(out.resolve("gl-context.txt"),glGetString(GL_VENDOR)+"\n"+glGetString(GL_RENDERER)+"\n"+glGetString(GL_VERSION)+"\n");
                require(NativeMainWindow.glTailCompatibility(),"GL tail failed to initialize");
                seconds=Double.parseDouble(System.getProperty("tailBench.seconds","5"));warmup=Double.parseDouble(System.getProperty("tailBench.warmup","3"));
                int rounds=Integer.getInteger("tailBench.rounds",3);require(rounds>0&&seconds>0&&warmup>0,"invalid durations");
                Random random=new Random(20261027);
                List<String> selectedCases=List.of(System.getProperty("tailBench.cases","A,B,C,D").split(","));require(selectedCases.contains("A")&&new HashSet<>(selectedCases).size()==selectedCases.size()&&List.of("A","B","C","D").containsAll(selectedCases),"invalid cases");
                for(boolean effect:new boolean[]{false,true})for(int round=1;round<=rounds;round++){
                    List<String> cases=new ArrayList<>(selectedCases);Collections.shuffle(cases,random);for(String variant:cases)jobs.add(new Job(round,effect,variant));
                }
                Files.writeString(out.resolve("schedule.json"),JSON.toJson(jobs));
                mc.options.pauseOnLostFocus=false;mc.options.renderDistance().set(8);mc.options.graphicsMode().set(net.minecraft.client.GraphicsStatus.FANCY);
                mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);mc.options.framerateLimit().set(260);mc.options.enableVsync().set(false);mc.getWindow().setFramerateLimit(260);mc.getWindow().updateVsync(false);
                glfwSetWindowAttrib(mc.getWindow().getWindow(),GLFW_DECORATED,GLFW_FALSE);glfwRestoreWindow(mc.getWindow().getWindow());
                glfwSetWindowMonitor(mc.getWindow().getWindow(),0,0,0,1920,1080,GLFW_DONT_CARE);
                String name="VulkanForge Tail Ablation "+System.currentTimeMillis();
                mc.createWorldOpenFlows().createFreshLevel(name,new LevelSettings(name,GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),
                        new WorldOptions(1234L,false,false),r->r.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
                Files.writeString(out.resolve("scene.txt"),"WORLD="+name+"\nSEED=1234\nCAMERA=.5,68,11,180,18\nRESOLUTION=1920x1080\nWINDOW=BORDERLESS\nRENDER_DISTANCE=8\nVSYNC=false\nFPS_LIMIT=260_UNLIMITED\nGRAPHICS=FANCY\nCLOUDS=OFF\nOPTIONAL_EFFECT=minecraft:shaders/post/desaturate.json\n");
                stage=1;
            }else if(stage==1&&mc.player!=null&&mc.level!=null&&mc.screen==null){buildScene(mc);stage=2;ticks=0;
            }else if(stage==2&&++ticks>100){beginJob(mc);
            }else if(stage==3){
                freezeCamera(mc);
                require((System.nanoTime()-jobStart)/1e9<60,"scene/resolution not ready: "+mc.getMainRenderTarget().viewWidth+"x"+mc.getMainRenderTarget().viewHeight);
                boolean ready=jobs.get(index).variant().equals("D")||VulkanPreview.worldEpoch()>0&&VulkanPreview.capturedSections()>=8;
                if(!ready||mc.getMainRenderTarget().viewWidth!=1920||mc.getMainRenderTarget().viewHeight!=1080){warmStart=System.nanoTime();return;}
                if((System.nanoTime()-warmStart)/1e9>=warmup){
                    require(mc.gameRenderer.currentEffect()!=null==jobs.get(index).effect(),"optional effect state changed");
                    world0=NativeMainWindow.worldStats();interop0=NativeMainWindow.interopStats();post0=TailDiagnostics.postStats();render0=NativeMainWindow.renderCpuStats();gl0=GlInterop.groupedCpuStats();terrain0=TerrainPasses.stats();uploads0=NativeMainWindow.uploadStats();profile0=NativeMainWindow.phaseProfileSnapshot();
                    measureEpoch=System.currentTimeMillis();TailFrameProfiler.start();measureStart=System.nanoTime();stage=4;
                }
            }else if(stage==4){
                freezeCamera(mc);
                if((System.nanoTime()-measureStart)/1e9>=seconds){finishJob(mc);if(++index==jobs.size()){finish(mc);}else beginJob(mc);}
            }
        }catch(Throwable error){
            VulkanForge.LOGGER.error("Tail ablation failed at job "+index,error);
            try{if(out!=null)Files.writeString(out.resolve("status.txt"),"FAILED "+error+"\n");}catch(Exception ignored){}
            stage=99;mc.stop();
        }
    }
    private static void beginJob(Minecraft mc) throws Exception {
        Job job=jobs.get(index);TailDiagnostics.select(job.variant());
        if(job.effect()!=activeEffect){if(job.effect())mc.gameRenderer.loadEffect(net.minecraft.resources.ResourceLocation.withDefaultNamespace("shaders/post/desaturate.json"));else mc.gameRenderer.shutdownEffect();activeEffect=job.effect();}
        boolean useVk=!job.variant().equals("D");if(useVk!=VulkanPreview.directWorld())VulkanPreview.toggle();
        folder=out.resolve((job.effect()?"OPTIONAL_POST":"BASE_GAME")+"/round-"+String.format(Locale.ROOT,"%02d",job.round())+"/"+job.variant());Files.createDirectories(folder);
        NativeMainWindow.captureNext(folder.resolve("screen.png"));
        warmStart=jobStart=System.nanoTime();stage=3;
        VulkanForge.LOGGER.info("TAIL_ABLATION_WARMUP {}",folder);
    }
    private static void finishJob(Minecraft mc) throws Exception {
        Job job=jobs.get(index);Map<String,Double> timing=TailFrameProfiler.stop(folder);
        long[] w=NativeMainWindow.worldStats(),i=NativeMainWindow.interopStats(),p=TailDiagnostics.postStats(),r=NativeMainWindow.renderCpuStats(),g=GlInterop.groupedCpuStats(),t=TerrainPasses.stats(),u=NativeMainWindow.uploadStats();
        double[] profile=NativeMainWindow.phaseProfileSnapshot();double frames=timing.get("frames");require(frames>20&&timing.get("cpu_overflow")==0,"bad frame capture");
        if(TailFrameProfiler.GPU_ENABLED)require(timing.get("gl_span_render_to_swap_samples")>frames*.5,"too few completed GPU query rows");
        Map<String,Object> row=new LinkedHashMap<>();row.putAll(timing);row.put("round",job.round());row.put("case",job.variant());row.put("phase",job.effect()?"OPTIONAL_POST":"BASE_GAME");
        row.put("start_ms",measureEpoch);row.put("end_ms",measureEpoch+Math.round(timing.get("seconds")*1000));
        row.put("camera",new double[]{mc.player.getX(),mc.player.getY(),mc.player.getZ(),mc.player.getYRot(),mc.player.getXRot()});row.put("window_focused",glfwGetWindowAttrib(mc.getWindow().getWindow(),GLFW_FOCUSED)==GLFW_TRUE);
        row.put("width",mc.getMainRenderTarget().viewWidth);row.put("height",mc.getMainRenderTarget().viewHeight);row.put("vk_terrain_frames",w[1]-world0[1]);row.put("vk_section_draws",w[7]-world0[7]);row.put("vk_translucent_section_draws",w[16]-world0[16]);
        row.put("vk_terrain_submits_per_frame",(i[4]-interop0[4])/frames);row.put("gl_group_calls_per_frame",(i[5]-interop0[5])/frames);row.put("gl_image_refs_per_frame",(i[8]-interop0[8])/frames);
        row.put("cpu_vk_fence_ms_per_frame",(i[10]-interop0[10])/1e6/frames);row.put("cpu_vk_queue_submit_ms_per_frame",(i[9]-interop0[9])/1e6/frames);
        row.put("shared_render_calls",r[0]-render0[0]);row.put("cpu_shared_render_mean_ms",r[0]==render0[0]?0:(r[1]-render0[1])/1e6/(r[0]-render0[0]));row.put("omitted_terrain_frames",r[2]-render0[2]);
        row.put("optional_post_executed",p[0]-post0[0]);row.put("optional_post_skipped",p[1]-post0[1]);row.put("vanilla_gl_terrain_draws",t[2]-terrain0[2]);row.put("vanilla_terrain_stats_delta",delta(t,terrain0));row.put("upload_stats_delta",delta(u,uploads0));
        for(int n=0;n<3;n++){String name=new String[]{"wait","signal","flush"}[n];row.put("gl_"+name+"_calls_per_frame",(g[n]-gl0[n])/frames);row.put("cpu_gl_"+name+"_ms_per_frame",(g[n+3]-gl0[n+3])/1e6/frames);}
        String[] metrics={"opaque","translucent","cull","depth_copy","composite","post"};
        for(int n=0;n<6;n++){double samples=profile[n*2]-profile0[n*2];row.put("vk_"+metrics[n]+"_gpu_samples",samples);if(samples>0)row.put("vk_"+metrics[n]+"_gpu_mean_ms",(profile[n*2+1]-profile0[n*2+1])/samples);}
        row.put("cpu_vk_record_ms_per_frame",(profile[12]-profile0[12])/frames);
        row.put("workload_changed",job.variant().equals("B")||job.variant().equals("C")&&job.effect());row.put("vk_backend_still_initialized",true);
        require(glGetError()==GL_NO_ERROR,"GL error");
        if(job.variant().equals("D")){require(w[1]-world0[1]==0&&i[4]-interop0[4]==0&&t[2]-terrain0[2]>0,"D did not use vanilla GL terrain");}
        else {require(w[1]-world0[1]>0&&Math.abs((i[4]-interop0[4])/frames-1)<.01,"terrain submit count");require(Math.abs((i[5]-interop0[5])/frames-2)<.02,"group handoff count");}
        if(job.variant().equals("B"))require(r[2]-render0[2]==w[1]-world0[1]&&w[7]-world0[7]==0,"B still drew Vulkan terrain");
        else if(!job.variant().equals("D"))require(w[7]-world0[7]>0,"terrain draws missing");
        if(job.effect())require(job.variant().equals("C")?p[1]-post0[1]>0&&p[0]-post0[0]==0:p[0]-post0[0]>0&&p[1]-post0[1]==0,"optional post routing");
        else require(p[0]==post0[0]&&p[1]==post0[1],"unexpected optional post in base phase");
        Files.writeString(folder.resolve("result.json"),JSON.toJson(row));runs.add(row);Files.writeString(out.resolve("runs.json"),JSON.toJson(runs));
        VulkanForge.LOGGER.info("TAIL_ABLATION_PASS {} FPS={} renderP99={}ms",folder,timing.get("fps"),timing.get("cpu_game_render_p99_ms"));
    }
    private static long[] delta(long[] a,long[] b){long[] result=new long[a.length];for(int n=0;n<a.length;n++)result[n]=a[n]-b[n];return result;}
    private static void freezeCamera(Minecraft mc){if(mc.player!=null){mc.player.setYRot(180);mc.player.setXRot(18);mc.player.yRotO=180;mc.player.xRotO=18;}}
    private static void buildScene(Minecraft mc){
        var server=mc.getSingleplayerServer();var uuid=mc.player.getUUID();server.execute(()->{
            var level=server.overworld();level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,server);level.setDayTime(6000);level.setWeatherParameters(6000,0,false,false);
            for(int x=-16;x<=16;x++)for(int z=-16;z<=16;z++)level.setBlock(new BlockPos(x,64,z),Blocks.STONE_BRICKS.defaultBlockState(),3);
            for(int x=-2;x<=2;x++)for(int y=65;y<=69;y++)level.setBlock(new BlockPos(x,y,0),Blocks.RED_CONCRETE.defaultBlockState(),3);
            for(int x=-2;x<=2;x++)for(int y=65;y<=69;y++)level.setBlock(new BlockPos(x,y,3),Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT,true),3);
            for(int x=-8;x<=-4;x++)for(int z=4;z<=8;z++)level.setBlock(new BlockPos(x,65,z),(x==-8||x==-4||z==4||z==8?Blocks.STONE_BRICKS:Blocks.WATER).defaultBlockState(),3);
            for(int x=-1;x<=1;x++)for(int y=65;y<=66;y++)level.setBlock(new BlockPos(x,y,7),Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState(),3);
            level.setBlock(new BlockPos(3,65,6),Blocks.CHEST.defaultBlockState(),3);var cow=net.minecraft.world.entity.EntityType.COW.spawn(level,new BlockPos(-3,65,6),net.minecraft.world.entity.MobSpawnType.COMMAND);if(cow!=null)cow.setNoAi(true);
            var player=server.getPlayerList().getPlayer(uuid);player.teleportTo(level,.5,68,11,180,18);player.getAbilities().flying=true;player.onUpdateAbilities();player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD));
        });
    }
    private static void finish(Minecraft mc) throws Exception {
        mc.gameRenderer.shutdownEffect();VulkanPreview.close();Files.writeString(out.resolve("status.txt"),"OK\n");stage=99;
        Thread watchdog=new Thread(()->{try{Thread.sleep(30_000);Files.writeString(out.resolve("exit-timeout.txt"),"FAILED");}catch(Exception ignored){}Runtime.getRuntime().halt(3);},"tail-ablation-exit-watchdog");watchdog.setDaemon(true);watchdog.start();mc.stop();
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
