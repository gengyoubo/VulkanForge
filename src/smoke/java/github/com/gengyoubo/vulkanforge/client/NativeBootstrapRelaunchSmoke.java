package github.com.gengyoubo.vulkanforge.client;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Exercises the shipped resource, real premain, and the same restart path as the Minecraft window. */
public final class NativeBootstrapRelaunchSmoke {
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            Path game = Path.of(args[1]);
            if (args[0].equals("--gl")) {
                require(Boolean.getBoolean("vulkanforge.nativeBootstrapAvailable"), "Agent not installed");
                require(!Boolean.getBoolean("vulkanforge.nativeBootstrap"), "GL entered native mode");
                return;
            }
            require(args.length == 4 && args[2].equals("中文 path with spaces\\") && args[3].isEmpty(), "Application arguments changed");
            if (!Boolean.getBoolean("vulkanforge.nativeBootstrap")) {
                NativeModeAvailability.bootstrapOrReject(game, "VK:V1_2");
                throw new AssertionError("Initial JVM continued without native bootstrap");
            }
            require(Boolean.getBoolean(NativeBootstrapLauncher.RELAUNCHED), "Relaunch marker missing");
            require(System.getProperty("vulkanforge.startup").equals("VK:V1_2"), "Startup API lost");
            require(!Files.exists(Path.of("config/vulkanforge-client.toml")), "Test requires a different working directory");
            Class<?> probe = nativeGlProbe();
            try { probe.getMethod("glGetError").invoke(null); throw new AssertionError("GL call escaped"); }
            catch (InvocationTargetException expected) { require(expected.getCause() instanceof IllegalStateException, "Premain did not rewrite GL"); }
            require(System.getProperty("vulkanforge.forbiddenGlCalls").contains("glGetError"), "GL guard did not record the call");
            List<String> command = GameRestart.restartCommand(NativeBootstrapLauncher.bootstrapOptions(game, "VK:V1_3"));
            require(command.stream().filter(value -> value.startsWith("-javaagent:")).count() == 1, "Duplicate agent on repeated restart");
            Files.writeString(game.resolve("native-restarted"), "OK");
            return;
        }

        require(NativeBootstrapLauncher.available(), "Bootstrap is not embedded in mod resources");
        Path root = Files.createTempDirectory("VulkanForge native 重启 ").toAbsolutePath();
        Path game = Files.createDirectories(root.resolve("isolated game"));
        Files.createDirectories(game.resolve("config"));
        Files.writeString(game.resolve("config/vulkanforge-client.toml"), "[renderer]\nmode=\"VK\"\n[vulkan]\nversion=\"V1_3\"\n");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        Process initial = new ProcessBuilder(java, "-cp", classpath, NativeBootstrapRelaunchSmoke.class.getName(),
                "--native", game.toString(), "中文 path with spaces\\", "")
                .directory(root.toFile()).redirectErrorStream(true).redirectOutput(root.resolve("initial.log").toFile()).start();
        require(initial.waitFor(30, TimeUnit.SECONDS) && initial.exitValue() == 0, "Initial launch failed; see " + root);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!Files.exists(game.resolve("native-restarted"))) {
            require(System.nanoTime() < deadline, "Native relaunch did not complete; see " + game.resolve("logs/vulkanforge/restart.log"));
            Thread.sleep(25);
        }
        List<String> gl = new ArrayList<>(List.of(java));
        gl.addAll(NativeBootstrapLauncher.bootstrapOptions(game, "GL:DEFAULT"));
        gl.addAll(List.of("-cp", classpath, NativeBootstrapRelaunchSmoke.class.getName(), "--gl", game.toString()));
        Process inactive = new ProcessBuilder(gl).directory(root.toFile()).inheritIO().start();
        require(inactive.waitFor(15, TimeUnit.SECONDS) && inactive.exitValue() == 0, "GL mode was not inert");
        System.setProperty(NativeBootstrapLauncher.RELAUNCHED, "true");
        try {
            try { NativeModeAvailability.bootstrapOrReject(game, "VK:V1_2"); throw new AssertionError("Failed relaunch was retried"); }
            catch (UnsupportedOperationException expected) { require(expected.getMessage().contains("Compatibility report"), "No failure report"); }
        } finally { System.clearProperty(NativeBootstrapLauncher.RELAUNCHED); }
        System.out.println("NATIVE_BOOTSTRAP_RELAUNCH_SMOKE=OK (embedded agent, fresh JVM, isolated game dir, Unicode arguments, GL trap, GL inert, no restart loop)");
    }

    private static Class<?> nativeGlProbe() throws Exception {
        // Define only in the isolated child: an org.lwjgl.opengl class in smoke output would
        // create a split package when Forge puts the smoke source set into its mod module.
        var bytes = new java.io.ByteArrayOutputStream();
        try (var out = new java.io.DataOutputStream(bytes)) {
            out.writeInt(0xCAFEBABE); out.writeShort(0); out.writeShort(61); out.writeShort(7);
            out.writeByte(1); out.writeUTF("org/lwjgl/opengl/BootstrapGuardProbe");
            out.writeByte(7); out.writeShort(1);
            out.writeByte(1); out.writeUTF("java/lang/Object");
            out.writeByte(7); out.writeShort(3);
            out.writeByte(1); out.writeUTF("glGetError");
            out.writeByte(1); out.writeUTF("()I");
            out.writeShort(0x21); out.writeShort(2); out.writeShort(4);
            out.writeShort(0); out.writeShort(0); out.writeShort(1);
            out.writeShort(0x109); out.writeShort(5); out.writeShort(6); out.writeShort(0);
            out.writeShort(0);
        }
        byte[] code = bytes.toByteArray();
        return new ClassLoader() { Class<?> probe() { return defineClass("org.lwjgl.opengl.BootstrapGuardProbe", code, 0, code.length); } }.probe();
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
