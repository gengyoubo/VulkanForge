package github.com.gengyoubo.vulkanforge.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import github.com.gengyoubo.vulkanforge.config.VulkanForgeConfig;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/** Explicit test source only: complete presented Minecraft frames; no GPU queries or renderer changes. */
@Mod.EventBusSubscriber(modid="vulkanforge",value=Dist.CLIENT)
public final class GameFpsBenchmark {
    private static final String MODE=System.getProperty("vulkanforge.gameFps","");
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private static final List<String> HUD_PLAN=hudPlan();
    private static final int WIDTH=Integer.getInteger("fps.width",1280),HEIGHT=Integer.getInteger("fps.height",720),DISTANCE=Integer.getInteger("fps.distance",8),ROUNDS=HUD_PLAN.isEmpty()?Integer.getInteger("fps.rounds",3):HUD_PLAN.size();
    private static final double SECONDS=Double.parseDouble(System.getProperty("fps.seconds","10")),WARMUP=Double.parseDouble(System.getProperty("fps.warmup","10"));
    private static final int COWS=Integer.getInteger("fps.cows",60),ITEMS=Integer.getInteger("fps.items",0),ENTITY_LIMIT=Integer.getInteger("fps.entityLimit",-1);
    private static final boolean UPDATES=Boolean.getBoolean("fps.updates");
    private static int updateTick;
    private static int pendingHudFrames;
    private static String selectedHud="BASELINE";
    private static final boolean SURVIVAL_HUD=!HUD_PLAN.isEmpty()||Boolean.getBoolean("fps.survivalHud");
    private static final Set<String> hudScreenshots=new HashSet<>();
    private static final long[] periods=new long[1<<20];
    private static final List<Map<String,Object>> results=new ArrayList<>();
    private static final List<Map<String,Long>> timeline=new ArrayList<>();
    private static final boolean TIMELINE=Boolean.getBoolean("vulkanforge.nativeProfile");
    private static final long launched=System.nanoTime();
    private static int stage,ticks,count,round;
    private static long warmStart,last,measureStart,presentedStart,startEpoch;
    private static Map<String,Long> diagnosticsStart;
    private static final java.lang.management.ThreadMXBean CPU=java.lang.management.ManagementFactory.getThreadMXBean();
    private static long allocatedStart,cpuStart;
    private static long allocated(){return CPU instanceof com.sun.management.ThreadMXBean bean&&bean.isThreadAllocatedMemorySupported()&&bean.isThreadAllocatedMemoryEnabled()?bean.getThreadAllocatedBytes(Thread.currentThread().getId()):-1;}
    private static long threadCpu(){return CPU.isCurrentThreadCpuTimeSupported()&&CPU.isThreadCpuTimeEnabled()?CPU.getCurrentThreadCpuTime():-1;}
    private static volatile boolean sceneReady;
    private static Path output;

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if(MODE.isEmpty()||event.phase!=TickEvent.Phase.END||stage==99)return;
        Minecraft mc=Minecraft.getInstance();
        try {
            require((System.nanoTime()-launched)/1e9<600,"Benchmark timeout, stage="+stage);
            if(stage==0&&mc.screen instanceof TitleScreen&&mc.getOverlay()==null) {
                output=Path.of(System.getProperty("fps.output"));Files.createDirectories(output);
                Files.writeString(output.resolve("pid.txt"),Long.toString(ProcessHandle.current().pid()));
                require(SECONDS>0&&WARMUP>0&&ROUNDS>0,"Invalid measurement durations");
                if(ENTITY_LIMIT>=0){Class<?> config=Class.forName("me.srrapero720.chloride.ChlorideConfig");Object culling=config.getField("culling").get(null);culling.getClass().getField("entityLimit").setInt(culling,ENTITY_LIMIT);}
                mc.options.pauseOnLostFocus=false;mc.options.renderDistance().set(DISTANCE);mc.options.simulationDistance().set(5);
                mc.options.graphicsMode().set(net.minecraft.client.GraphicsStatus.FANCY);mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
                mc.options.mipmapLevels().set(0);mc.options.fov().set(70);mc.options.hideGui=false;mc.options.renderDebug=false;
                mc.options.framerateLimit().set(260);mc.options.enableVsync().set(false);mc.getWindow().setFramerateLimit(260);mc.getWindow().updateVsync(false);
                if(mc.getWindow().isFullscreen())mc.getWindow().toggleFullScreen();
                long handle=mc.getWindow().getWindow();GLFW.glfwRestoreWindow(handle);GLFW.glfwSetWindowAttrib(handle,GLFW.GLFW_DECORATED,GLFW.GLFW_FALSE);
                GLFW.glfwSetWindowMonitor(handle,0,0,0,WIDTH,HEIGHT,GLFW.GLFW_DONT_CARE);GLFW.glfwFocusWindow(handle);
                if(MODE.equals("VK"))require(NativeGameWindow.enabled()&&GLFW.glfwGetCurrentContext()==0,"Native VK absent / GL context exists");
                else require(!NativeGameWindow.enabled()&&!NativeMainWindow.active(),"GL launch entered Vulkan/hybrid path");
                String name="FPS "+MODE+" "+System.currentTimeMillis();
                mc.createWorldOpenFlows().createFreshLevel(name,new LevelSettings(name,GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),
                        new WorldOptions(1234L,false,false),r->r.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
                stage=1;
            } else if(stage==1&&mc.player!=null&&mc.level!=null&&mc.screen==null) {buildScene(mc);stage=2;ticks=0;}
            else if(stage==2&&sceneReady&&++ticks>100) {
                if(MODE.equals("GL46")){VulkanPreview.toggle();require(VulkanPreview.gl46World(),"GL46 terrain unavailable");}
                if(!HUD_PLAN.isEmpty())selectHudCase(mc,HUD_PLAN.get(0));warmStart=System.nanoTime();stage=3;
                Map<String,Object> scene=new LinkedHashMap<>();scene.put("mode",MODE);scene.put("startup",VulkanForgeConfig.startup().toString());
                scene.put("resolution",List.of(WIDTH,HEIGHT));scene.put("distance",DISTANCE);scene.put("simulationDistance",5);scene.put("seed",1234);
                scene.put("window","BORDERLESS");
                scene.put("camera",List.of(.5,68,11,180,18));scene.put("graphics","FANCY");scene.put("mipmaps",0);scene.put("clouds","OFF");
                scene.put("vsync",false);scene.put("fpsLimit","UNLIMITED (260)");scene.put("hud",true);scene.put("cows",COWS);scene.put("items",ITEMS);scene.put("entityLimitOverride",ENTITY_LIMIT);scene.put("blockUpdates",UPDATES);scene.put("legacyModSubmission",Boolean.getBoolean("vulkanforge.nativeLegacyModSubmission"));scene.put("warmupSeconds",WARMUP);
                scene.put("secondsPerRound",SECONDS);scene.put("rounds",ROUNDS);scene.put("scope","ACTUAL_MINECRAFT_PRESENTED_FRAMES");
                scene.put("validation",false);scene.put("gpuProfiling",Boolean.getBoolean("vulkanforge.nativeProfile"));scene.put("nativeDiagnostics",Boolean.getBoolean("vulkanforge.nativeDiagnostics"));
                scene.put("nativeGuiFastPath",Boolean.getBoolean("vulkanforge.nativeGuiFastPath"));scene.put("hudScopeProfiling",Boolean.getBoolean("vulkanforge.hudDiagnostics"));
                boolean nativeFeatures=Boolean.getBoolean("vulkanforge.nativeModSubmission"),legacyFeatures=Boolean.getBoolean("vulkanforge.nativeLegacyModSubmission");
                scene.put("nativeModFeatures",Map.of("regionBridge",(nativeFeatures||Boolean.getBoolean("vulkanforge.nativeRegionBridge"))&&!legacyFeatures,"dynamicBatching",(nativeFeatures||Boolean.getBoolean("vulkanforge.nativeDynamicBatching"))&&!legacyFeatures,"chlorideTopK",(nativeFeatures||Boolean.getBoolean("vulkanforge.nativeChlorideTopK"))&&!legacyFeatures));
                scene.put("mods",net.minecraftforge.fml.ModList.get().getMods().stream().collect(java.util.stream.Collectors.toMap(mod->mod.getModId(),mod->mod.getVersion().toString())));
                if(SURVIVAL_HUD){scene.put("hudCaseOrder",HUD_PLAN);scene.put("playerMode","SURVIVAL_WITH_FLIGHT");scene.put("hotbarSlots",9);scene.put("health",16);scene.put("food",17);}
                if(net.minecraftforge.fml.ModList.get().isLoaded("chloride")){Object culling=Class.forName("me.srrapero720.chloride.ChlorideConfig").getField("culling").get(null);Map<String,Object> config=new LinkedHashMap<>();for(String key:new String[]{"entityLimit","entities","monsters","entityDistanceX","entityDistanceY","monsterDistanceX","monsterDistanceY"})config.put(key,culling.getClass().getField(key).get(culling));scene.put("chlorideCulling",config);}
                if(net.minecraftforge.fml.ModList.get().isLoaded("magnesium_extras")||net.minecraftforge.fml.ModList.get().isLoaded("sodiumextras")){Class<?> config=Class.forName("toni.sodiumextras.EmbyConfig");scene.put("extrasDistanceCulling",config.getField("entityDistanceCullingCache").get(null));}
                if(!MODE.equals("VK")){
                    scene.put("gpu",org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER));
                    scene.put("glVersion",org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION));
                }
                Files.writeString(output.resolve("scene.json"),JSON.toJson(scene));
            } else if(stage>=3&&stage<=5) {
                if(UPDATES&&stage<5&&++updateTick%10==0){boolean alternate=(updateTick/10&1)==0;mc.getSingleplayerServer().execute(()->{var level=mc.getSingleplayerServer().overworld();for(int i=0;i<16;i++)level.setBlock(new BlockPos((i%4-2)*16+3,65,(i/4-2)*16+3),(alternate?Blocks.STONE:Blocks.GLASS).defaultBlockState(),3);});}
                if(stage<5&&mc.screen instanceof net.minecraft.client.gui.screens.PauseScreen){
                    com.mojang.logging.LogUtils.getLogger().info("FPS_DISCARD pause screen interrupted {} round {}",MODE,round+1);
                    mc.setScreen(null);stage=3;warmStart=System.nanoTime();return;
                }
                require(mc.player!=null&&mc.screen==null&&mc.getOverlay()==null,"World interrupted during measurement: screen="+(mc.screen==null?"null":mc.screen.getClass().getName())+", overlay="+(mc.getOverlay()==null?"null":mc.getOverlay().getClass().getName()));
                mc.player.setYRot(180);mc.player.setXRot(18);mc.player.yRotO=180;mc.player.xRotO=18;
                require(mc.player.distanceToSqr(.5,68,11)<.04,"Camera moved");
                require(mc.getMainRenderTarget().viewWidth==WIDTH&&mc.getMainRenderTarget().viewHeight==HEIGHT,"Framebuffer extent changed");
                if(stage==5&&++ticks>20){Files.writeString(output.resolve("status.txt"),"OK\n");stage=99;mc.stop();}
            }
        }catch(Throwable error){fail(mc,error);}
    }

    @SubscribeEvent public static void render(TickEvent.RenderTickEvent event) {
        if(MODE.isEmpty()||event.phase!=TickEvent.Phase.START||stage<3||stage>4)return;
        Minecraft mc=Minecraft.getInstance();
        try {
            long now=System.nanoTime();
            if(stage==3) {
                if(pendingHudFrames>0){if(--pendingHudFrames==0){selectHudCase(mc,HUD_PLAN.get(round));warmStart=System.nanoTime();}return;}
                if(MODE.equals("GL46")&&VulkanPreview.capturedSections()<8){warmStart=now;return;}
                if((now-warmStart)/1e9<(round==0?WARMUP:3))return;
                com.mojang.logging.LogUtils.getLogger().info("FPS_MEASURE {} round {}",MODE,round+1);
                count=0;presentedStart=MODE.equals("VK")?NativeGameWindow.stats()[0]:0;
                diagnosticsStart=MODE.equals("VK")?NativeGameWindow.diagnostics():Map.of();timeline.clear();if(TIMELINE&&MODE.equals("VK"))sampleTimeline(0);allocatedStart=allocated();cpuStart=threadCpu();
                startEpoch=System.currentTimeMillis();measureStart=last=System.nanoTime();stage=4;return;
            }
            require(count<periods.length,"Frame sample capacity exceeded");periods[count++]=now-last;last=now;
            if(TIMELINE&&MODE.equals("VK")&&count%120==0)sampleTimeline(count);
            if((now-measureStart)/1e9>=SECONDS)finishRound(mc,now);
        }catch(Throwable error){fail(mc,error);}
    }

    private static void finishRound(Minecraft mc,long now)throws Exception {
        long allocatedEnd=allocated(),cpuEnd=threadCpu();
        long[] sorted=Arrays.copyOf(periods,count);Arrays.sort(sorted);long total=0;for(long period:sorted)total+=period;
        int slow=Math.max(1,(count+99)/100);long tail=0;for(int i=count-slow;i<count;i++)tail+=sorted[i];
        Map<String,Object> row=new LinkedHashMap<>();row.put("mode",MODE);row.put("round",++round);row.put("frames",count);row.put("seconds",total/1e9);
        if(!HUD_PLAN.isEmpty()){row.put("hudCase",selectedHud);row.put("hudBlock",1+(round-1)/new HashSet<>(HUD_PLAN).size());}
        row.put("startEpochMs",startEpoch);row.put("endEpochMs",System.currentTimeMillis());
        row.put("fps",count*1e9/total);row.put("onePercentLowFps",slow*1e9/tail);row.put("frameP50Ms",sorted[(count-1)/2]/1e6);
        row.put("frameP95Ms",sorted[(count-1)*95/100]/1e6);row.put("frameP99Ms",sorted[(count-1)*99/100]/1e6);row.put("maxMs",sorted[count-1]/1e6);
        if(allocatedStart>=0&&allocatedEnd>=0)row.put("renderThreadAllocatedBytes",allocatedEnd-allocatedStart);
        if(cpuStart>=0&&cpuEnd>=0)row.put("renderThreadCpuNs",cpuEnd-cpuStart);
        if(MODE.equals("VK")){
            long[] stats=NativeGameWindow.stats();require(Math.abs(stats[0]-presentedStart-count)<=2,"Presented frame count mismatch");
            require(System.getProperty("vulkanforge.forbiddenGlCalls","missing").equals("0"),"Forbidden GL attempt");
            row.put("nativeStats",stats);row.put("openGlContexts",0);row.put("forbiddenGlCalls",0);
            Map<String,Long> delta=new LinkedHashMap<>();NativeGameWindow.diagnostics().forEach((key,value)->delta.put(key,value-diagnosticsStart.getOrDefault(key,0L)));row.put("nativeDiagnostics",delta);
        }else if(MODE.equals("GL46")){require(VulkanPreview.gl46World(),"GL46 was disabled");row.put("terrainStats",VulkanPreview.gl46Stats());}
        else require(!VulkanPreview.gl46World(),"Vanilla GL unexpectedly used custom terrain");
        results.add(row);Files.writeString(output.resolve("results.json"),JSON.toJson(results));
        StringBuilder csv=new StringBuilder("frame,period_ns\n");for(int i=0;i<count;i++)csv.append(i).append(',').append(periods[i]).append('\n');
        Files.writeString(output.resolve("round-"+round+".csv"),csv);
        if(TIMELINE&&MODE.equals("VK")){
            sampleTimeline(count);StringBuilder trace=new StringBuilder();List<String> keys=new ArrayList<>(timeline.get(0).keySet());trace.append(String.join(",",keys)).append('\n');
            for(var sample:timeline){for(int i=0;i<keys.size();i++){if(i>0)trace.append(',');trace.append(sample.getOrDefault(keys.get(i),0L));}trace.append('\n');}
            Files.writeString(output.resolve("timeline-"+round+".csv"),trace);
        }
        com.mojang.logging.LogUtils.getLogger().info("FPS_RESULT {}",JSON.toJson(row));
        if(!HUD_PLAN.isEmpty()&&hudScreenshots.add(selectedHud))NativeGameWindow.captureNext(output.resolve("case-"+selectedHud+".png"));
        if(round<ROUNDS){warmStart=System.nanoTime();stage=3;if(!HUD_PLAN.isEmpty())pendingHudFrames=2;}
        else {
            if(MODE.equals("VK"))NativeGameWindow.captureNext(output.resolve("screen.png"));
            else try(var image=net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(output.resolve("screen.png"));}
            ticks=0;stage=5;
        }
    }

    private static List<String> hudPlan(){String raw=Boolean.getBoolean("fps.guiCompare")?"LEGACY_GUI,FAST_GUI":System.getProperty("fps.hudCases","");if(raw.isBlank())return List.of();List<String> cases=List.of(raw.split(",")),plan=new ArrayList<>();var random=new Random(13013);for(int block=0;block<Integer.getInteger("fps.rounds",3);block++){var order=new ArrayList<>(cases);if(block>0)Collections.shuffle(order,random);plan.addAll(order);}return List.copyOf(plan);}
    private static void selectHudCase(Minecraft mc,String name){selectedHud=name;if(name.equals("LEGACY_GUI")||name.equals("FAST_GUI"))NativeGuiBlitter.benchmarkEnabled(name.equals("FAST_GUI"));else NativeHudDiagnostics.select(name);mc.options.hideGui=name.equals("F1");com.mojang.logging.LogUtils.getLogger().info("HUD_ABLATION_CASE {}",name);}

    /** Cumulative counters every 120 frames; GPU samples are collected later at the existing slot fence. No extra waits. */
    private static void sampleTimeline(int frame){Map<String,Long> sample=new LinkedHashMap<>();sample.put("sampleFrame",(long)frame);sample.put("epochMs",System.currentTimeMillis());sample.putAll(NativeGameWindow.diagnostics());timeline.add(sample);}

    private static void buildScene(Minecraft mc) {
        var server=mc.getSingleplayerServer();var uuid=mc.player.getUUID();server.execute(()->{
            var level=server.overworld();level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,server);
            level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,server);level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(0,server);
            level.setDayTime(6000);level.setWeatherParameters(6000,0,false,false);
            for(int x=-16;x<=16;x++)for(int z=-16;z<=16;z++)level.setBlock(new BlockPos(x,64,z),Blocks.STONE_BRICKS.defaultBlockState(),3);
            for(int x=-2;x<=2;x++)for(int y=65;y<=69;y++){level.setBlock(new BlockPos(x,y,0),Blocks.RED_CONCRETE.defaultBlockState(),3);level.setBlock(new BlockPos(x,y,3),Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT,true),3);}
            for(int x=-8;x<=-4;x++)for(int z=4;z<=8;z++)level.setBlock(new BlockPos(x,65,z),(x==-8||x==-4||z==4||z==8?Blocks.STONE_BRICKS:Blocks.WATER).defaultBlockState(),3);
            for(int x=-1;x<=1;x++)for(int y=65;y<=66;y++)level.setBlock(new BlockPos(x,y,7),Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState(),3);
            level.setBlock(new BlockPos(3,65,6),Blocks.CHEST.defaultBlockState(),3);
            for(int i=0;i<COWS;i++){int x=-9+(i/6%10)*2,z=-3-i%6*2;var cow=net.minecraft.world.entity.EntityType.COW.spawn(level,new BlockPos(x,65+i/60,z),net.minecraft.world.entity.MobSpawnType.COMMAND);if(cow!=null){cow.setNoAi(true);cow.setYRot(x*9);}}
            for(int i=0;i<ITEMS;i++){var stack=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND);stack.getOrCreateTag().putInt("VulkanForgeBenchId",i);var item=new net.minecraft.world.entity.item.ItemEntity(level,-8+(i%16),66+(i/16%4)*.25,-2-(i/64),stack);item.setNoGravity(true);item.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);item.setUnlimitedLifetime();level.addFreshEntity(item);}
            var player=server.getPlayerList().getPlayer(uuid);player.teleportTo(level,.5,68,11,180,18);player.getAbilities().flying=true;player.onUpdateAbilities();
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD));
            if(SURVIVAL_HUD){
                player.setGameMode(GameType.SURVIVAL);player.getAbilities().mayfly=true;player.getAbilities().flying=true;player.getAbilities().invulnerable=true;player.onUpdateAbilities();player.setHealth(16);player.getFoodData().setFoodLevel(17);level.getGameRules().getRule(GameRules.RULE_NATURAL_REGENERATION).set(false,server);
                var hotbar=new net.minecraft.world.item.Item[]{net.minecraft.world.item.Items.DIAMOND_SWORD,net.minecraft.world.item.Items.STONE,net.minecraft.world.item.Items.OAK_LEAVES,net.minecraft.world.item.Items.CHEST,net.minecraft.world.item.Items.WATER_BUCKET,net.minecraft.world.item.Items.LIGHT_BLUE_STAINED_GLASS,net.minecraft.world.item.Items.TORCH,net.minecraft.world.item.Items.DIAMOND,net.minecraft.world.item.Items.CLOCK};
                for(int i=0;i<hotbar.length;i++)player.getInventory().setItem(i,new net.minecraft.world.item.ItemStack(hotbar[i]));player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_CHESTPLATE));
            }
            sceneReady=true;
        });
    }
    private static void fail(Minecraft mc,Throwable error){error.printStackTrace();try{if(output!=null)Files.writeString(output.resolve("status.txt"),"FAILED "+error+"\n");}catch(Exception ignored){}stage=99;mc.stop();}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
