package github.com.gengyoubo.vulkanforge.client;

import github.com.gengyoubo.vulkanforge.config.VulkanForgeConfig;
import java.lang.reflect.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.system.JNI;
import org.lwjgl.system.windows.User32;

/** Game-side owner of a NO_API window. The native agent must be active before Forge initializes. */
public final class NativeGameWindow {
    private static long window;
    private static Object backend;
    private static URLClassLoader loader;
    private static long frameSerial;
    private static final Map<String,Method> methods=new HashMap<>();
    private NativeGameWindow() { }
    public static boolean enabled(){return Boolean.getBoolean("vulkanforge.nativeBootstrap");}
    public static long handle(){return window;}
    public static int[] extent(){int[] width={0},height={0};GLFW.glfwGetFramebufferSize(window,width,height);return new int[]{Math.max(1,width[0]),Math.max(1,height[0])};}
    static long create(IntSupplier width,IntSupplier height,Supplier<String> title,LongSupplier monitor) {
        if(!System.getProperty("os.name").startsWith("Windows"))throw new UnsupportedOperationException("Native game window currently requires Windows");
        if(GLFW.glfwGetCurrentContext()!=0)throw new IllegalStateException("Native bootstrap detected an existing OpenGL context");
        var startup=VulkanForgeConfig.startup();
        if(startup.mode()!=dev.vulkanforge.bridge.RenderMode.VK)throw new IllegalStateException("Native bootstrap requires VK mode");
        try {
            GLFW.glfwDefaultWindowHints();GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API,GLFW.GLFW_NO_API);
            window=GLFW.glfwCreateWindow(width.getAsInt(),height.getAsInt(),title.get(),monitor.getAsLong(),0);
            if(window==0)throw new IllegalStateException("Native game NO_API window creation failed");
            long hwnd=GLFWNativeWin32.glfwGetWin32Window(window),instance=JNI.callPP(hwnd,-6,User32.getLibrary().getFunctionAddress("GetWindowLongPtrW"));
            loader=new URLClassLoader(new java.net.URL[]{LabLauncher.extract().toUri().toURL()},ClassLoader.getPlatformClassLoader());
            Class<?> configuration=Class.forName("org.lwjgl.system.Configuration",true,loader);
            Object stackSize=configuration.getField("STACK_SIZE").get(null);stackSize.getClass().getMethod("set",Object.class).invoke(stackSize,4096);
            Class<?> type=Class.forName("github.com.gengyoubo.vulkanforge.lab.NativeGameBackend",true,loader);
            Path report=FMLPaths.GAMEDIR.get().resolve("logs/vulkanforge/native-game-report.txt");
            backend=type.getConstructor(String.class,long.class,long.class,int.class,String.class,boolean.class).newInstance(report.toAbsolutePath().toString(),hwnd,instance,startup.vulkanVersion().apiVersion,startup.nvidiaExtensionNames(),Boolean.getBoolean("vulkanforge.requireValidation"));
            for(Method method:type.getMethods())if(method.getDeclaringClass()==type)methods.put(method.getName(),method);
            NativeModeAvailability.markActive(report.resolveSibling("vk-native-compatibility.json"));
            return window;
        }catch(Throwable error){
            long failedWindow=window;
            try{close();}catch(Throwable cleanup){error.addSuppressed(cleanup);}
            finally{if(failedWindow!=0)GLFW.glfwDestroyWindow(failedWindow);}
            throw new IllegalStateException("Native Vulkan game bootstrap failed",cause(error));
        }
    }
    public static Object call(String name,Object...arguments) {
        if(backend==null)throw new IllegalStateException("Native device is not initialized: "+name);
        try{return methods.get(name).invoke(backend,arguments);}catch(Throwable error){throw new IllegalStateException("Native Vulkan operation failed: "+name,cause(error));}
    }
    private static Throwable cause(Throwable error){return error instanceof InvocationTargetException&&error.getCause()!=null?error.getCause():error;}
    public static void hint(int name,int value) {
        if(name==GLFW.GLFW_CLIENT_API)GLFW.glfwWindowHint(name,GLFW.GLFW_NO_API);
        else if(name!=GLFW.GLFW_CONTEXT_VERSION_MAJOR&&name!=GLFW.GLFW_CONTEXT_VERSION_MINOR&&name!=GLFW.GLFW_OPENGL_PROFILE&&name!=GLFW.GLFW_OPENGL_FORWARD_COMPAT&&name!=GLFW.GLFW_OPENGL_DEBUG_CONTEXT&&name!=GLFW.GLFW_CONTEXT_CREATION_API)GLFW.glfwWindowHint(name,value);
    }
    public static void swapInterval(int interval){if(backend!=null)call("setVsync",interval!=0);}
    static void beginFrame(){if(backend!=null){frameSerial++;call("beginFrame");}}
    static long frameSerial(){return frameSerial;}
    static void present() {
        var target=Minecraft.getInstance().getMainRenderTarget();if(target==null)return;
        int[] width={0},height={0};GLFW.glfwGetFramebufferSize(window,width,height);
        call("present",target.frameBufferId,width[0],height[0]);
        if(!System.getProperty("vulkanforge.forbiddenGlCalls","0").equals("0"))throw new IllegalStateException("A forbidden OpenGL call occurred");
    }
    public static long[] stats(){return backend==null?new long[8]:(long[])call("stats");}
    @SuppressWarnings("unchecked") public static Map<String,Long> diagnostics(){
        Map<String,Long> result=new LinkedHashMap<>((Map<String,Long>)call("diagnostics"));
        result.putAll(NativeDeviceState.diagnostics());return result;
    }
    public static void captureNext(Path path){call("captureNext",path.toAbsolutePath().toString());}
    static void close() {
        try{if(backend!=null)call("close");}
        finally{backend=null;methods.clear();try{if(loader!=null)loader.close();}catch(java.io.IOException error){throw new IllegalStateException(error);}finally{loader=null;window=0;}}
    }
}
