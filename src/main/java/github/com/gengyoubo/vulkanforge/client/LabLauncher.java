package github.com.gengyoubo.vulkanforge.client;

import github.com.gengyoubo.vulkanforge.VulkanForge;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process isolation keeps lab LWJGL classes and natives out of the game's class loader. */
final class LabLauncher {
    private static final AtomicBoolean TRIANGLE_RUNNING = new AtomicBoolean();
    private static final Set<Process> CHILDREN = ConcurrentHashMap.newKeySet();
    private static final ExecutorService IO = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "VulkanForge-lab");
        t.setDaemon(true);
        return t;
    });

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            CHILDREN.forEach(Process::destroy);
            IO.shutdownNow();
        }, "VulkanForge-shutdown"));
    }

    static void probe() { IO.execute(() -> run("--probe", "probe-report.txt")); }

    static boolean triangle() {
        if (!TRIANGLE_RUNNING.compareAndSet(false, true)) return false;
        IO.execute(() -> {
            try { run("--triangle", "triangle-report.txt"); }
            finally { TRIANGLE_RUNNING.set(false); }
        });
        return true;
    }

    static synchronized Path extract() throws Exception {
        byte[] bytes;
        try (InputStream in = LabLauncher.class.getResourceAsStream("/vulkanforge/vulkan-lab.jar")) {
            if (in == null) throw new IOException("Bundled vulkan-lab.jar is missing; run processResources first");
            bytes = in.readAllBytes();
        }
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Path dir = FMLPaths.GAMEDIR.get().resolve(".vulkanforge").resolve(hash);
        Path jar = dir.resolve("vulkan-lab.jar");
        Files.createDirectories(dir);
        if (!Files.exists(jar) || !MessageDigest.isEqual(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(jar)), MessageDigest.getInstance("SHA-256").digest(bytes))) {
            Path temp = Files.createTempFile(dir, "lab-", ".tmp");
            try {
                Files.write(temp, bytes);
                Files.move(temp, jar, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temp); }
        }
        return jar;
    }

    static ProcessBuilder process(String... arguments) throws Exception {
        Path jar = extract();
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", executable);
        var command = new java.util.ArrayList<String>();
        java.util.Collections.addAll(command, javaExecutable.toString(), "-Dfile.encoding=UTF-8",
                "-Dvulkanforge.vulkanVersion=" + github.com.gengyoubo.vulkanforge.config.VulkanForgeConfig.startup().vulkanVersion().label,
                "-Dvulkanforge.nvidiaExtensions=" + github.com.gengyoubo.vulkanforge.config.VulkanForgeConfig.startup().nvidiaExtensionNames(),
                "-jar", jar.toString());
        java.util.Collections.addAll(command, arguments);
        ProcessBuilder builder = new ProcessBuilder(command).directory(FMLPaths.GAMEDIR.get().toFile());
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("_JAVA_OPTIONS");
        builder.environment().remove("JDK_JAVA_OPTIONS");
        return builder;
    }

    private static void run(String mode, String reportName) {
        Process child = null;
        try {
            Path logs = FMLPaths.GAMEDIR.get().resolve("logs/vulkanforge");
            Files.createDirectories(logs);
            Path report = logs.resolve(reportName);
            ProcessBuilder builder = process(mode).redirectErrorStream(true).redirectOutput(report.toFile());
            child = builder.start();
            CHILDREN.add(child);
            if (mode.equals("--probe") && !child.waitFor(60, TimeUnit.SECONDS)) {
                child.destroyForcibly();
                throw new IOException("Vulkan capability probe timed out after 60 seconds");
            }
            int exit = child.waitFor();
            VulkanForge.LOGGER.info("Vulkan lab {} exited with code {}; report: {}", mode, exit, report);
            if (mode.equals("--probe") || exit != 0) {
                VulkanForge.LOGGER.info("Vulkan lab report:\n{}", Files.readString(report));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            VulkanForge.LOGGER.error("Could not run Vulkan lab {}", mode, e);
        } finally {
            if (child != null) {
                if (child.isAlive()) child.destroyForcibly();
                CHILDREN.remove(child);
            }
        }
    }
}
