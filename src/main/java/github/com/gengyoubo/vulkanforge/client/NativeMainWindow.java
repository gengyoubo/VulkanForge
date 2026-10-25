package github.com.gengyoubo.vulkanforge.client;

import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.platform.GLX;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.loading.ImmediateWindowHandler;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.opengl.GL;
import github.com.gengyoubo.vulkanforge.interop.GlInterop;
import org.slf4j.Logger;
import java.lang.reflect.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.function.*;

/** Window ownership and primitive-only boundary to the isolated LWJGL 3.3.6 backend. */
public final class NativeMainWindow {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static long mainWindow, hiddenContext;
    private static URLClassLoader loader;
    private static Object backend;
    private static boolean revealed;
    private static Method present, beginFrame, setVsync, frames, generations, capture, close, earlyAcquires, lateAcquires;
    private static Method renderWorld, renderWorldWithSky, worldStats, finishWorldFrame, closeWorld, renderOverlay, finishOverlay, entityTexture, entityTexturePatch, gui, guiPasses;
    private static Consumer<Object[]> worldCommands;

    private NativeMainWindow() { }

    public static long createWindow(IntSupplier width, IntSupplier height, Supplier<String> title, LongSupplier monitor) {
        long legacy = ImmediateWindowHandler.setupMinecraftWindow(width, height, title, monitor);
        if (!System.getProperty("vulkanforge.directPresent", "true").equalsIgnoreCase("true")
                || !System.getProperty("os.name").startsWith("Windows")) return legacy;
        try {
            GLFW.glfwMakeContextCurrent(legacy); GL.createCapabilities();
            byte[] uuid = GlInterop.deviceUUID();
            Path logs = FMLPaths.GAMEDIR.get().resolve("logs/vulkanforge"); Files.createDirectories(logs);
            // Platform parent prevents any game LWJGL class from leaking into this backend.
            loader = new URLClassLoader(new java.net.URL[]{LabLauncher.extract().toUri().toURL()}, ClassLoader.getPlatformClassLoader());
            Class<?> type = Class.forName("github.com.gengyoubo.vulkanforge.lab.MainWindowBackend", true, loader);
            Function<long[], Consumer<int[]>> importer = GlInterop::importImage;
            Runnable finishGL = org.lwjgl.opengl.GL11::glFinish;
            backend = type.getConstructor(String.class, byte[].class, Function.class, Runnable.class)
                    .newInstance(logs.resolve("main-window-report.txt").toString(), uuid, importer, finishGL);
            present = type.getMethod("present", int.class, int.class, int.class, int.class, int.class);
            beginFrame = type.getMethod("beginFrame", int.class, int.class);
            earlyAcquires = type.getMethod("earlyAcquires"); lateAcquires = type.getMethod("lateAcquires");
            setVsync = type.getMethod("setVsync", boolean.class);
            frames = type.getMethod("frames"); generations = type.getMethod("generations");
            capture = type.getMethod("captureNext", String.class); close = type.getMethod("close");
            renderWorld = type.getMethod("renderWorld", int.class, int.class); worldStats = type.getMethod("worldStats");
            renderWorldWithSky = type.getMethod("renderWorldWithSky", int.class, int.class, Object[].class);
            finishWorldFrame = type.getMethod("finishWorldFrame"); closeWorld = type.getMethod("closeWorld");
            renderOverlay = type.getMethod("renderOverlay", Object[].class, boolean.class); finishOverlay = type.getMethod("finishOverlay");
            gui = type.getMethod("gui", Object[].class); guiPasses = type.getMethod("guiPasses");
            entityTexture = type.getMethod("entityTexture", Object[].class);
            entityTexturePatch = type.getMethod("entityTexturePatch", Object[].class);
            Function<long[], Consumer<int[]>> terrainImporter = GlInterop::importTerrain;
            @SuppressWarnings("unchecked") Consumer<Object[]> commands = (Consumer<Object[]>) type.getMethod("worldCommands", Function.class).invoke(backend, terrainImporter);
            worldCommands = commands;
        } catch (Throwable error) {
            LOGGER.warn("Vulkan main-window probe failed; using the original OpenGL window. Report: logs/vulkanforge/main-window-report.txt", cause(error));
            releaseBackend();
            return legacy;
        }
        // Only after a successful interop probe do we create the Vulkan-only native window.
        long created = 0;
        try {
            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            created = GLFW.glfwCreateWindow(width.getAsInt(), height.getAsInt(), title.get(), monitor.getAsLong(), 0);
            if (created == 0) throw new IllegalStateException("GLFW_NO_API main window creation failed");
            long hwnd = GLFWNativeWin32.glfwGetWin32Window(created);
            backend.getClass().getMethod("attach", long.class, long.class).invoke(backend, hwnd, GlInterop.windowInstance(hwnd));
            int[] framebufferWidth = {0}, framebufferHeight = {0};
            GLFW.glfwGetFramebufferSize(created, framebufferWidth, framebufferHeight);
            backend.getClass().getMethod("prepare", int.class, int.class).invoke(backend, framebufferWidth[0], framebufferHeight[0]);
            hiddenContext = legacy; mainWindow = created;
            revealed = false;
            GLFW.glfwMakeContextCurrent(hiddenContext);
            LOGGER.info("Minecraft main window now uses Vulkan Surface/Swapchain; legacy OpenGL context is hidden");
            return created;
        } catch (Throwable error) {
            // No presentation has taken place on the new HWND, so the original remains a legal fallback.
            releaseBackend();
            if (created != 0) GLFW.glfwDestroyWindow(created);
            mainWindow = hiddenContext = 0;
            GLFW.glfwMakeContextCurrent(legacy); GLFW.glfwShowWindow(legacy);
            LOGGER.warn("Vulkan Surface setup failed; using the original OpenGL window", cause(error));
            return legacy;
        } finally { GLFW.glfwDefaultWindowHints(); }
    }

    public static boolean active() { return backend != null && mainWindow != 0; }
    public static boolean positionWindow(java.util.Optional<Object> monitor, IntConsumer width, IntConsumer height, IntConsumer x, IntConsumer y) {
        return !active() && ImmediateWindowHandler.positionWindow(monitor, width, height, x, y);
    }
    public static void makeContextCurrent(long window) { GLFW.glfwMakeContextCurrent(active() && window == mainWindow ? hiddenContext : window); }
    /** Window.shouldClose is called at the start of Minecraft.runTick, before timer/ticks/mouse.turnPlayer. */
    public static boolean shouldClose(Window window) {
        if (GLX._shouldClose(window)) return true;
        if (active() && window.getWindow() == mainWindow) {
            // Process close/minimize/resize before a WSI wait; then sample again after the wait.
            GLFW.glfwPollEvents();
            if (GLX._shouldClose(window)) return true;
            Minecraft mc = Minecraft.getInstance();
            call(setVsync, mc.options.enableVsync().get());
            int[] width = {0}, height = {0}; GLFW.glfwGetFramebufferSize(mainWindow, width, height);
            if (GLFW.glfwGetWindowAttrib(mainWindow, GLFW.GLFW_ICONIFIED) == 0) call(beginFrame, width[0], height[0]);
            // Acquire/fence waits are over: deliver fresh mouse/key events before simulation and camera sampling.
            GLFW.glfwPollEvents();
        }
        return GLX._shouldClose(window);
    }
    public static void swapBuffers(long window) {
        if (!active() || window != mainWindow) { GLFW.glfwSwapBuffers(window); return; }
        // P11: a hand/GUI window still open (e.g. F1 hides the GUI, so no GUI event closed HAND) is composited before present.
        VulkanPreview.beforeSwap();
        call(gui, (Object) GuiDraws.take());
        call(finishWorldFrame);
        Minecraft mc = Minecraft.getInstance();
        var target = mc.getMainRenderTarget();
        if (target == null || target.frameBufferId < 0) return;
        int[] width = {0}, height = {0}; GLFW.glfwGetFramebufferSize(window, width, height);
        if (GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_ICONIFIED) != 0) return;
        call(setVsync, mc.options.enableVsync().get());
        call(present, target.frameBufferId, target.viewWidth, target.viewHeight, width[0], height[0]);
        if (!revealed && presentedFrames() > 0) {
            // Keep Forge's loading window visible until the Vulkan-only window has real content.
            GLFW.glfwShowWindow(mainWindow); GLFW.glfwHideWindow(hiddenContext); revealed = true;
            LOGGER.info("Vulkan main window revealed after its first completed frame");
        }
    }

    static long guiPasses() { return active() ? ((Number) call(guiPasses)).longValue() : 0; }
    public static long presentedFrames() { return active() ? ((Number) call(frames)).longValue() : 0; }
    static boolean sendWorld(dev.vulkanforge.bridge.WorldProtocol.Command command) {
        if (!active()) return false;
        worldCommands.accept(dev.vulkanforge.bridge.WorldProtocol.pack(command)); return true;
    }
    static int renderWorld(int width, int height) { return active() ? ((Number)call(renderWorld, width, height)).intValue() : 0; }
    /** P10: captured sky packets are drawn under the terrain in the same submit. */
    static int renderWorld(int width, int height, Object[] sky) {
        if (sky.length == 0) return renderWorld(width, height);
        return active() ? ((Number)call(renderWorldWithSky, width, height, (Object) sky)).intValue() : 0;
    }
    static boolean worldVisible() { return active() && GLFW.glfwGetWindowAttrib(mainWindow, GLFW.GLFW_ICONIFIED) == 0; }
    static long[] worldStats() { return active() ? (long[])call(worldStats) : new long[25]; }
    /** P7-P9: {color, depth} GL textures of one Vulkan overlay pass, or null; call finishOverlay() right after compositing. */
    static int[] renderOverlay(Object[] packets, boolean clearDepth) { return active() ? (int[]) call(renderOverlay, (Object) packets, clearDepth) : null; }
    static void finishOverlay() { if (active()) call(finishOverlay); }
    static boolean entityTexturePatch(Object[] patch) { return active() && (Boolean) call(entityTexturePatch, (Object) patch); }
    static boolean entityTexture(Object[] texture) { return active() && (Boolean) call(entityTexture, (Object) texture); }
    static void closeWorld() { if (active()) call(closeWorld); }
    public static long acquiresBeforeInput() { return active() ? ((Number) call(earlyAcquires)).longValue() : 0; }
    public static long acquiresAfterRender() { return active() ? ((Number) call(lateAcquires)).longValue() : 0; }
    public static int swapchainGenerations() { return active() ? ((Number) call(generations)).intValue() : 0; }
    public static void captureNext(Path path) {
        if (!active()) throw new IllegalStateException("Vulkan main-window backend is inactive");
        call(capture, path.toAbsolutePath().toString());
    }
    public static void destroyWindow(long window) {
        if (window == mainWindow && active()) {
            makeContextCurrent(window); releaseBackend();
            GLFW.glfwDestroyWindow(window); GLFW.glfwDestroyWindow(hiddenContext);
            mainWindow = hiddenContext = 0;
        } else GLFW.glfwDestroyWindow(window);
    }
    private static Object call(Method method, Object... args) {
        try { return method.invoke(backend, args); }
        catch (Throwable error) { throw new IllegalStateException("Vulkan main-window presentation failed", cause(error)); }
    }
    private static Throwable cause(Throwable error) { return error instanceof InvocationTargetException && error.getCause() != null ? error.getCause() : error; }
    private static void releaseBackend() {
        try { if (backend != null) { if (close != null) close.invoke(backend); else ((AutoCloseable) backend).close(); } }
        catch (Throwable error) { LOGGER.error("Could not close Vulkan main-window resources", cause(error)); }
        finally {
            backend = null; present = beginFrame = setVsync = frames = generations = capture = close = earlyAcquires = lateAcquires = null;
            renderWorld = renderWorldWithSky = worldStats = finishWorldFrame = closeWorld = renderOverlay = finishOverlay = entityTexture = entityTexturePatch = gui = guiPasses = null; worldCommands = null;
            try { if (loader != null) loader.close(); } catch (Exception error) { LOGGER.warn("Could not close backend loader", error); }
            loader = null;
        }
    }
}
