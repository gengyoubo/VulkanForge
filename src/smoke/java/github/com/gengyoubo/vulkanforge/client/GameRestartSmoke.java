package github.com.gengyoubo.vulkanforge.client;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Plain-Java process test: no Minecraft window or launcher credentials required. */
public final class GameRestartSmoke {
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            Path directory = Path.of(args[0]);
            require(args.length == 3 && args[1].equals("中文 path with spaces\\") && args[2].isEmpty(), "Arguments changed");
            require(Path.of("").toAbsolutePath().equals(directory), "Working directory changed");
            if (Files.exists(directory.resolve("prepared"))) {
                require(System.getProperty("vulkanforge.startup") == null, "Startup override survived restart");
                Files.writeString(directory.resolve("restarted"), "OK");
                return;
            }
            GameRestart.prepare(directory.resolve("restart.log"));
            Files.writeString(directory.resolve("prepared"), "OK");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!Files.exists(directory.resolve("exit"))) {
                require(System.nanoTime() < deadline, "Parent was never allowed to exit");
                Thread.sleep(25);
            }
            return;
        }
        require(GameRestart.windowsArguments("\"C:\\Program Files\\java.exe\" -cp \"a b\" Main \"\" \"中文 path\\\\\" x\\\"y")
                .equals(List.of("C:\\Program Files\\java.exe", "-cp", "a b", "Main", "", "中文 path\\", "x\"y")), "Windows quoting failed");
        Path directory = Files.createTempDirectory("VulkanForge 重启 test ").toAbsolutePath();
        Process parent = null;
        try {
            String executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            List<String> command = List.of(executable, "-Dvulkanforge.startup=OPENGL:GL_4_6", "-cp",
                    System.getProperty("java.class.path"), GameRestartSmoke.class.getName(), directory.toString(), "中文 path with spaces\\", "");
            parent = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
                    .redirectOutput(directory.resolve("parent.log").toFile()).start();
            await(directory.resolve("prepared"));
            Thread.sleep(300);
            require(parent.isAlive() && !Files.exists(directory.resolve("restarted")), "Restart did not wait for old JVM");
            Files.writeString(directory.resolve("exit"), "OK");
            require(parent.waitFor(15, TimeUnit.SECONDS) && parent.exitValue() == 0, "Old JVM did not exit normally");
            await(directory.resolve("restarted"));
            System.out.println("PASS restart waits for exit, preserves Unicode/space/empty arguments and cwd, clears startup override");
        } catch (Exception | AssertionError error) {
            for (String name : List.of("parent.log", "restart.log")) {
                Path log = directory.resolve(name);
                if (Files.exists(log)) System.err.println(Files.readString(log));
            }
            throw error;
        } finally {
            if (parent != null && parent.isAlive()) parent.destroyForcibly().waitFor();
            // Test files stay under a unique temporary directory for diagnostics.
        }
    }

    private static void await(Path file) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!Files.exists(file)) {
            require(System.nanoTime() < deadline, "Timed out waiting for " + file.getFileName());
            Thread.sleep(25);
        }
    }

    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
