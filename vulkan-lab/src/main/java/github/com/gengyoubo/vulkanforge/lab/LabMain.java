package github.com.gengyoubo.vulkanforge.lab;

import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Configuration;
import org.lwjgl.vulkan.VK;

import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWVulkan.glfwVulkanSupported;
import static org.lwjgl.system.MemoryUtil.NULL;

public final class LabMain {
    private LabMain() { }

    public static void main(String[] args) {
        // Recent drivers expose enough extensions to exceed LWJGL's default 64 KiB stack.
        Configuration.STACK_SIZE.set(1024);
        int exit = java.util.Arrays.asList(args).contains("--bridge") ? BridgeMain.execute(args) : execute(args);
        if (exit != 0) System.exit(exit);
    }

    static int execute(String[] args) {
        boolean triangle = false, requireValidation = false, resizeSmoke = false;
        int maxFrames = 0;
        long window = NULL;
        GLFWErrorCallback errors = null;
        VulkanContext context = null;
        try {
            for (String arg : args) {
                if (arg.equals("--triangle")) triangle = true;
                else if (arg.equals("--probe")) triangle = false;
                else if (arg.equals("--require-validation")) requireValidation = true;
                else if (arg.equals("--resize-smoke")) resizeSmoke = true;
                else if (arg.startsWith("--frames=")) {
                    maxFrames = Integer.parseInt(arg.substring("--frames=".length()));
                    if (maxFrames < 1) throw new IllegalArgumentException("--frames must be positive");
                } else throw new IllegalArgumentException("Unknown argument: " + arg);
            }
            String os = System.getProperty("os.name");
            String arch = System.getProperty("os.arch");
            System.out.println("VulkanForge lab 0.1.0 | Java " + System.getProperty("java.version") + " | " + os + " " + arch);
            if (!(os.startsWith("Windows") || os.startsWith("Linux")) || !(arch.equals("amd64") || arch.equals("x86_64"))) {
                throw new IllegalStateException("Stage 1 currently packages native libraries for Windows/Linux x86_64 only");
            }
            int loader = VK.getInstanceVersionSupported();
            System.out.println("LOADER_API=" + VulkanContext.version(loader));
            if (loader < VulkanContext.requestedApiVersion()) {
                throw new IllegalStateException("Vulkan " + VulkanContext.version(VulkanContext.requestedApiVersion()) + " loader required");
            }
            errors = GLFWErrorCallback.createPrint(System.err).set();
            if (!glfwInit()) throw new IllegalStateException("GLFW initialization failed");
            if (!glfwVulkanSupported()) throw new IllegalStateException("GLFW found no Vulkan loader/ICD");
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
            window = glfwCreateWindow(960, 600, "VulkanForge | Vulkan " + VulkanContext.version(VulkanContext.requestedApiVersion()) + " triangle", NULL, NULL);
            if (window == NULL) throw new IllegalStateException("Could not create Vulkan test window");
            context = new VulkanContext();
            context.initialize(window, loader, requireValidation);
            if (triangle) {
                final long handle = window;
                glfwSetKeyCallback(window, (w, key, scan, action, mods) -> {
                    if (key == GLFW_KEY_ESCAPE && action == GLFW_PRESS) glfwSetWindowShouldClose(w, true);
                });
                try (TriangleRenderer renderer = new TriangleRenderer(context, window)) {
                    renderer.initialize();
                    glfwSetFramebufferSizeCallback(window, (w, width, height) -> renderer.requestResize());
                    glfwShowWindow(window);
                    int frames = 0;
                    while (!glfwWindowShouldClose(window) && (maxFrames == 0 || frames < maxFrames)) {
                        glfwPollEvents();
                        try (MemoryStack stack = MemoryStack.stackPush()) {
                            IntBuffer width = stack.ints(0), height = stack.ints(0);
                            glfwGetFramebufferSize(window, width, height);
                            if (width.get(0) == 0 || height.get(0) == 0) {
                                glfwWaitEventsTimeout(0.1);
                                continue;
                            }
                        }
                        if (renderer.draw()) frames++;
                        if (resizeSmoke && frames == 8) glfwSetWindowSize(handle, 1120, 720);
                        if (resizeSmoke && frames == 20) glfwSetWindowSize(handle, 720, 480);
                    }
                    System.out.println("FRAMES_RENDERED=" + frames);
                    System.out.println("SWAPCHAINS_CREATED=" + renderer.swapchainGenerations());
                    if (resizeSmoke && renderer.swapchainGenerations() < 3) {
                        throw new IllegalStateException("Resize smoke did not rebuild both swapchains; use --frames=60 or more");
                    }
                } finally {
                    var callback = glfwSetFramebufferSizeCallback(window, null);
                    if (callback != null) callback.free();
                }
            }
            context.close();
            if (context.validationErrors() != 0) throw new IllegalStateException("Vulkan validation reported " + context.validationErrors() + " errors");
            System.out.println("VALIDATION_ERRORS=" + context.validationErrors());
            System.out.println("STATUS=OK");
            return 0;
        } catch (Throwable e) {
            System.err.println("STATUS=FAILED");
            e.printStackTrace(System.err);
            return 1;
        } finally {
            if (context != null) context.close();
            if (window != NULL) {
                org.lwjgl.glfw.Callbacks.glfwFreeCallbacks(window);
                glfwDestroyWindow(window);
            }
            glfwTerminate();
            if (errors != null) {
                glfwSetErrorCallback(null);
                errors.free();
            }
        }
    }
}
