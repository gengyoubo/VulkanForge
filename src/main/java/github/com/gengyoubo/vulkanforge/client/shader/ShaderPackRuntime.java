package github.com.gengyoubo.vulkanforge.client.shader;

import github.com.gengyoubo.vulkanforge.VulkanForge;
import github.com.gengyoubo.vulkanforge.config.VulkanForgeConfig;
import github.com.gengyoubo.vulkanforge.shaderpack.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;

/** Live pack selection. IO is asynchronous; compilation and frame-boundary commit stay on the render thread. */
public final class ShaderPackRuntime {
    private static ShaderPackController<ShaderPackGraph> controller;
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{Thread thread=new Thread(r,"VulkanForge shader pack IO");thread.setDaemon(true);return thread;});
    private static Path directory,settings;
    private static final Properties preferences=new Properties();
    private static volatile Map<String,String> requestedValues=Map.of();
    private static String runtimeError="";
    private static String loggedError="";
    private static boolean initialized;
    private static java.util.function.Consumer<String> diagnosticObserver;
    public static void diagnosticObserver(java.util.function.Consumer<String> observer){diagnosticObserver=observer;}
    static void diagnosticStage(String stage){var observer=diagnosticObserver;if(observer!=null)observer.accept(stage);}
    private ShaderPackRuntime(){ }
    public static Path directory(){initialize();return directory;}
    private static void initialize(){
        if(initialized)return;initialized=true;Minecraft mc=Minecraft.getInstance();directory=mc.gameDirectory.toPath().resolve("shaderpacks");settings=mc.gameDirectory.toPath().resolve("config/vulkanforge-shaders.properties");
        try{Files.createDirectories(directory);if(Files.isRegularFile(settings))try(Reader reader=Files.newBufferedReader(settings,StandardCharsets.UTF_8)){preferences.load(reader);}}catch(IOException error){runtimeError=error.getMessage();}
        controller=new ShaderPackController<>(IO,mc::execute,pack->new ShaderPackPrepared(pack,requestedValues),prepared->{
            ShaderPack pack=prepared.pack;
            if(VulkanForgeConfig.startup().mode()==dev.vulkanforge.bridge.RenderMode.GL_VK)throw new ShaderPackException("Shader pack execution currently requires GL or native VK mode");
            var missing=prepared.missing;StringBuilder report=new StringBuilder("PACK=").append(pack.name()).append("\nBACKEND=").append(VulkanForgeConfig.startup().mode()).append("\nPREFLIGHT=").append(missing.isEmpty()?"PASSED":"UNSUPPORTED_FEATURES").append('\n');
            for(var feature:missing)report.append(feature.feature()).append('=').append(feature.example()).append('\n');
            if(missing.isEmpty())for(var approximation:prepared.approximations)report.append("APPROXIMATED_").append(approximation.approximation()).append('=').append(approximation.example()).append('\n');
            prepared.milliseconds.forEach((name,value)->report.append("CPU_").append(name).append("_MS=").append(value).append('\n'));
            writeDiagnostic(report.toString());
            if(!missing.isEmpty()){
                String features=String.join("、",missing.stream().map(feature->net.minecraft.network.chat.Component.translatable("vulkanforge.shaders.feature."+feature.feature().name().toLowerCase(Locale.ROOT)).getString()).toList());
                throw new ShaderPackException(net.minecraft.network.chat.Component.translatable("vulkanforge.shaders.unsupported",pack.name(),features).getString());
            }
            ShaderPackGraph graph=new ShaderPackGraph(prepared);
            // Chunk terrain drawn by a third-party chunk renderer bypasses the game's core shaders, so terrain programs would silently not run.
            if(graph.terrain()&&net.minecraftforge.fml.ModList.get().isLoaded("embeddium")){graph.close();throw new ShaderPackException(net.minecraft.network.chat.Component.translatable("vulkanforge.shaders.embeddium_terrain",pack.name()).getString());}
            return graph;
        },graph->{try(ShaderPackState state=new ShaderPackState(16).retiring()){graph.close();}});
        String selected=preferences.getProperty("selected","");if(!selected.isBlank())request(selected,storedOptions(selected));
    }
    private static void writeDiagnostic(String text){
        try{Path report=Minecraft.getInstance().gameDirectory.toPath().resolve("logs/vulkanforge/shader-pack-compatibility.txt");Files.createDirectories(report.getParent());Files.writeString(report,text,StandardCharsets.UTF_8);}catch(IOException error){VulkanForge.LOGGER.warn("Could not write shader pack compatibility report",error);}
    }
    public static List<String> available(){initialize();try(var files=Files.list(directory)){return files.filter(path->!path.getFileName().toString().startsWith(".")).filter(path->Files.isDirectory(path)||path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")).map(path->path.getFileName().toString()).sorted(String.CASE_INSENSITIVE_ORDER).toList();}catch(IOException error){runtimeError=error.getMessage();return List.of();}}
    private static String key(String name){return Base64.getUrlEncoder().withoutPadding().encodeToString(name.getBytes(StandardCharsets.UTF_8));}
    private static Map<String,String> storedOptions(String name){Map<String,String> values=new HashMap<>();String prefix="pack."+key(name)+".";preferences.forEach((k,v)->{if(k.toString().startsWith(prefix))values.put(k.toString().substring(prefix.length()),v.toString());});return Map.copyOf(values);}
    public static void select(String name){initialize();request(name,storedOptions(name));}
    public static String selectedPack(){initialize();String active=controller.status().active();return active.isEmpty()?preferences.getProperty("lastSelected",preferences.getProperty("selected","")):active;}
    public static CompletableFuture<List<String>> importPacks(List<Path> files){
        initialize();List<Path> paths=List.copyOf(files);return CompletableFuture.supplyAsync(()->{List<String> names=new ArrayList<>();try{for(Path path:paths)names.add(ShaderPackImporter.copy(path,directory));return List.copyOf(names);}catch(IOException error){throw new CompletionException(error);}},IO);
    }
    public static void request(String name,Map<String,String> values){
        initialize();runtimeError="";requestedValues=Map.copyOf(values);
        if(name.isEmpty()){controller.request(null);return;}
        Path selected=directory.resolve(name).normalize();if(!Objects.equals(selected.getParent(),directory.normalize())){runtimeError="Invalid shader pack name";return;}
        controller.request(selected);
    }
    public static void reload(){initialize();var graph=controller.active();if(graph!=null)request(graph.pack.name(),graph.values);}
    public static ShaderPackController.Status status(){initialize();var state=controller.status();return runtimeError.isEmpty()?state:new ShaderPackController.Status(state.active(),state.requested(),state.loading(),runtimeError);}
    public static ShaderPackOptions options(){initialize();var graph=controller.active();return graph==null?null:graph.options;}
    public static Map<String,String> values(){initialize();var graph=controller.active();return graph==null?Map.of():graph.values;}
    public static boolean active(){return !ShaderPackFrontend.irisAvailable()&&initialized&&controller.active()!=null;}
    public static void beginFrame(){
        if(ShaderPackFrontend.irisAvailable())return;
        ShaderPackFrontend.beginFrame();
        initialize();String preparationError=controller.status().error();if(!preparationError.equals(loggedError)){loggedError=preparationError;if(!preparationError.isEmpty())VulkanForge.LOGGER.error("Shader pack preparation failed; keeping the active pack: {}",preparationError);}if(controller.beginFrame()){
            var graph=controller.active();String name=graph==null?"":graph.pack.name();preferences.setProperty("selected",name);if(!name.isEmpty())preferences.setProperty("lastSelected",name);
            if(graph!=null){String prefix="pack."+key(name)+".";preferences.keySet().removeIf(k->k.toString().startsWith(prefix));graph.values.forEach((option,value)->preferences.setProperty(prefix+option,value));}
            try{Files.createDirectories(settings.getParent());Path temp=Files.createTempFile(settings.getParent(),"vulkanforge-shaders-",".tmp");try(Writer writer=Files.newBufferedWriter(temp,StandardCharsets.UTF_8)){preferences.store(writer,"Live shader pack selection");}try{Files.move(temp,settings,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException error){Files.move(temp,settings,StandardCopyOption.REPLACE_EXISTING);}}catch(IOException error){runtimeError="Could not save shader selection: "+error.getMessage();}
            VulkanForge.LOGGER.info("Shader pack committed at frame boundary: {}",name.isEmpty()?"OFF":name);
        }
    }
    private static boolean worldFrame;
    private static String dimension(Minecraft mc){return mc.level.dimension()==net.minecraft.world.level.Level.NETHER?"world-1":mc.level.dimension()==net.minecraft.world.level.Level.END?"world1":"world0";}
    private static void failed(String error){runtimeError=error;worldFrame=false;VulkanForge.LOGGER.error("Shader pack frame failed: {}",error);controller.request(null);}
    /** Start of LevelRenderer.renderLevel: the view pose and projection of this frame, before the sky is drawn. */
    public static void levelBegin(com.mojang.blaze3d.vertex.PoseStack pose,float partialTick,Matrix4f projection){
        worldFrame=false;if(!active())return;Minecraft mc=Minecraft.getInstance();if(mc.level==null)return;
        try{worldFrame=controller.active().beginWorld(mc.getMainRenderTarget(),dimension(mc),partialTick,new Matrix4f(projection),new Matrix4f(pose.last().pose()));}catch(RuntimeException error){failed(error.getMessage());}
    }
    /** Every GameRenderer core-shader getter passes through here; outside a world-program frame it is the identity. */
    public static net.minecraft.client.renderer.ShaderInstance shader(net.minecraft.client.renderer.ShaderInstance vanilla){return worldFrame?controller.active().shader(vanilla):vanilla;}
    public static boolean worldFrame(){return worldFrame;}
    public static boolean shadowFrame(){return active()&&controller.active().shadowing();}
    public static void stage(net.minecraftforge.client.event.RenderLevelStageEvent.Stage stage){
        if(!worldFrame||shadowFrame())return;var graph=controller.active();
        if(stage==net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_SKY){graph.shadow(Minecraft.getInstance().levelRenderer);graph.phase(ShaderPackWorldPrograms.Phase.TERRAIN);}
        else if(stage==net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS)graph.phase(ShaderPackWorldPrograms.Phase.ENTITIES);
        else if(stage==net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_ENTITIES)graph.phase(ShaderPackWorldPrograms.Phase.BLOCK_ENTITIES);
        else if(stage==net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES){try{graph.deferred();}catch(RuntimeException error){failed(error.getMessage());return;}graph.phase(ShaderPackWorldPrograms.Phase.TRANSLUCENT);}
        else if(stage==net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS)graph.phase(ShaderPackWorldPrograms.Phase.PARTICLES);
        else if(stage==net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_PARTICLES)graph.phase(ShaderPackWorldPrograms.Phase.WEATHER);
        else if(stage==net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_LEVEL)graph.phase(ShaderPackWorldPrograms.Phase.HAND);
        if(graph.failure()!=null)failed(graph.failure());
    }
    public static void renderWorldEnd(float partialTick){
        if(!active())return;Minecraft mc=Minecraft.getInstance();if(mc.level==null){worldFrame=false;return;}
        var graph=controller.active();
        try{graph.render(mc.getMainRenderTarget(),dimension(mc),partialTick);}catch(RuntimeException error){failed(error.getMessage());return;}finally{worldFrame=false;}
        if(graph.failure()!=null)failed(graph.failure());
    }
    public static long[] statistics(){if(!active())return new long[3];var graph=controller.active();return new long[]{graph.frames,graph.passes,graph.worldDraws};}
    public static void close(){if(!initialized)return;controller.close();IO.shutdownNow();}
}
