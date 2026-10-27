package github.com.gengyoubo.vulkanforge.lab;

import java.net.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;

/** A NO_API GLFW window is allowed; loading any GL or interop implementation is a hard test failure. */
public final class VkWindowOnlyLauncher {
    public static void main(String[] args) throws Exception {
        AtomicInteger attempted = new AtomicInteger();
        Path output = Path.of(System.getProperty("vulkanforge.smokeReportDir", "build/reports/native-window"));
        Files.createDirectories(output); Files.writeString(output.resolve("result.txt"), "STATUS=RUNNING\nSCOPE=NATIVE_BACKEND_ONLY\n");
        var urls = java.util.Arrays.stream(System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                .map(Path::of).map(path -> { try { return path.toUri().toURL(); } catch (MalformedURLException e) { throw new IllegalArgumentException(e); } }).toArray(URL[]::new);
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (var isolated = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("org.lwjgl.opengl.") || name.equals("github.com.gengyoubo.vulkanforge.interop.GlInterop")
                        || name.equals("github.com.gengyoubo.vulkanforge.lab.MainWindowBackend")
                        || name.startsWith("github.com.gengyoubo.vulkanforge.lab.Shared")) {
                    attempted.incrementAndGet(); throw new ClassNotFoundException("Native window forbids GL/interop: " + name);
                }
                return super.loadClass(name, resolve);
            }
        }) {
            Thread.currentThread().setContextClassLoader(isolated);
            try {
                Class.forName("github.com.gengyoubo.vulkanforge.lab.VkNativeWindowSmoke", true, isolated).getMethod("main", String[].class).invoke(null, (Object) args);
            } catch (java.lang.reflect.InvocationTargetException error) {
                if (error.getCause() instanceof Error e) throw e;
                if (error.getCause() instanceof Exception e) throw e;
                throw error;
            }
            if (attempted.get() != 0) throw new AssertionError("Native window attempted " + attempted.get() + " forbidden class loads");
            Files.writeString(output.resolve("result.txt"), "STATUS=OK\nSCOPE=NATIVE_BACKEND_ONLY\nMINECRAFT_GAME_INTEGRATION=NOT_CONNECTED\nOPENGL_CONTEXTS=0\nGL_INTEROP_LOAD_ATTEMPTS=" + attempted.get() + "\n");
            System.out.println("VK_WINDOW_ONLY_CLASSLOADER=PASS GL_INTEROP_LOAD_ATTEMPTS=0");
        } catch (Exception | Error error) {
            Files.writeString(output.resolve("result.txt"), "STATUS=FAILED\nSCOPE=NATIVE_BACKEND_ONLY\nGL_INTEROP_LOAD_ATTEMPTS=" + attempted.get() + "\nERROR=" + error + "\n");
            throw error;
        } finally { Thread.currentThread().setContextClassLoader(previous); }
    }
}
