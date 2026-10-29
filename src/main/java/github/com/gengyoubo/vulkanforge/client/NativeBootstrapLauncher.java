package github.com.gengyoubo.vulkanforge.client;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Installs the bundled premain agent into a fresh JVM; never attaches to a running Forge JVM. */
final class NativeBootstrapLauncher {
    static final String RELAUNCHED = "vulkanforge.nativeBootstrapRelaunched";
    private static final String RESOURCE = "/vulkanforge/vulkanforge-native-bootstrap.jar";

    static boolean available() { return NativeBootstrapLauncher.class.getResource(RESOURCE) != null; }

    static void prepareRestart(Path gameDirectory, String startup) throws Exception {
        List<String> command = GameRestart.restartCommand(bootstrapOptions(gameDirectory, startup));
        GameRestart.prepare(gameDirectory.resolve("logs/vulkanforge/restart.log"), command);
    }

    static List<String> bootstrapOptions(Path gameDirectory, String startup) throws IOException {
        Path agent = extract(gameDirectory);
        return List.of("-javaagent:" + agent + "=" + gameDirectory.toAbsolutePath().normalize(),
                "-D" + RELAUNCHED + "=true", "-Dvulkanforge.startup=" + startup);
    }

    private static Path extract(Path gameDirectory) throws IOException {
        byte[] bytes;
        try (var input = NativeBootstrapLauncher.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IOException("Bundled native bootstrap is missing");
            bytes = input.readAllBytes();
        }
        String digest;
        try { digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
        Path directory = gameDirectory.toAbsolutePath().normalize().resolve(".vulkanforge/bootstrap").resolve(digest);
        Files.createDirectories(directory);
        Path agent = directory.resolve("vulkanforge-native-bootstrap.jar");
        if (Files.isRegularFile(agent) && Arrays.equals(bytes, Files.readAllBytes(agent))) return agent;
        Path temporary = Files.createTempFile(directory, "bootstrap-", ".tmp");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, agent, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
        return agent;
    }
}
