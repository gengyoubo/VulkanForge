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
    private static Map<String,String> requestedValues=Map.of();
    private static String runtimeError="";
    private static boolean initialized;
    private ShaderPackRuntime(){ }
    public static Path directory(){initialize();return directory;}
    private static void initialize(){
        if(initialized)return;initialized=true;Minecraft mc=Minecraft.getInstance();directory=mc.gameDirectory.toPath().resolve("shaderpacks");settings=mc.gameDirectory.toPath().resolve("config/vulkanforge-shaders.properties");
        try{Files.createDirectories(directory);if(Files.isRegularFile(settings))try(Reader reader=Files.newBufferedReader(settings,StandardCharsets.UTF_8)){preferences.load(reader);}}catch(IOException error){runtimeError=error.getMessage();}
        controller=new ShaderPackController<>(IO,mc::execute,pack->{
            if(VulkanForgeConfig.startup().mode()==dev.vulkanforge.bridge.RenderMode.GL_VK)throw new ShaderPackException("Shader pack execution currently requires GL or native VK mode");
            var target=mc.getMainRenderTarget();return new ShaderPackGraph(pack,requestedValues,target.viewWidth,target.viewHeight);
        },graph->{try(ShaderPackState state=new ShaderPackState(4)){graph.close();}});
        String selected=preferences.getProperty("selected","");if(!selected.isBlank())request(selected,storedOptions(selected));
    }
    public static List<String> available(){initialize();try(var files=Files.list(directory)){return files.filter(path->Files.isDirectory(path)||path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")).map(path->path.getFileName().toString()).sorted(String.CASE_INSENSITIVE_ORDER).toList();}catch(IOException error){runtimeError=error.getMessage();return List.of();}}
    private static String key(String name){return Base64.getUrlEncoder().withoutPadding().encodeToString(name.getBytes(StandardCharsets.UTF_8));}
    private static Map<String,String> storedOptions(String name){Map<String,String> values=new HashMap<>();String prefix="pack."+key(name)+".";preferences.forEach((k,v)->{if(k.toString().startsWith(prefix))values.put(k.toString().substring(prefix.length()),v.toString());});return Map.copyOf(values);}
    public static void select(String name){initialize();request(name,storedOptions(name));}
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
    public static boolean active(){return initialized&&controller.active()!=null;}
    public static void beginFrame(){
        initialize();if(controller.beginFrame()){
            var graph=controller.active();String name=graph==null?"":graph.pack.name();preferences.setProperty("selected",name);
            if(graph!=null){String prefix="pack."+key(name)+".";preferences.keySet().removeIf(k->k.toString().startsWith(prefix));graph.values.forEach((option,value)->preferences.setProperty(prefix+option,value));}
            try{Files.createDirectories(settings.getParent());Path temp=Files.createTempFile(settings.getParent(),"vulkanforge-shaders-",".tmp");try(Writer writer=Files.newBufferedWriter(temp,StandardCharsets.UTF_8)){preferences.store(writer,"Live shader pack selection");}try{Files.move(temp,settings,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException error){Files.move(temp,settings,StandardCopyOption.REPLACE_EXISTING);}}catch(IOException error){runtimeError="Could not save shader selection: "+error.getMessage();}
            VulkanForge.LOGGER.info("Shader pack committed at frame boundary: {}",name.isEmpty()?"OFF":name);
        }
    }
    public static void matrices(Matrix4f projection,Matrix4f view){if(active())controller.active().matrices(projection,view);}
    public static void renderWorldEnd(float partialTick){
        if(!active())return;Minecraft mc=Minecraft.getInstance();if(mc.level==null)return;
        String dimension=mc.level.dimension()==net.minecraft.world.level.Level.NETHER?"world-1":mc.level.dimension()==net.minecraft.world.level.Level.END?"world1":"world0";
        try{controller.active().render(mc.getMainRenderTarget(),dimension,partialTick);}catch(RuntimeException error){runtimeError=error.getMessage();VulkanForge.LOGGER.error("Shader pack frame failed: {}",runtimeError);controller.request(null);}
    }
    public static long[] statistics(){if(!active())return new long[2];var graph=controller.active();return new long[]{graph.frames,graph.passes};}
    public static void close(){if(!initialized)return;controller.close();IO.shutdownNow();}
}
